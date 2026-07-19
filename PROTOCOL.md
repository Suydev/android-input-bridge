# PROTOCOL.md — InputBridge Wire Protocol

Version: 1

---

## Overview

All communication between bridge and receiver uses a compact binary protocol over UDP (or TCP for setup). Every packet is self-contained and can be processed independently — no session state is required to decode a packet.

---

## Packet Layout

```
┌──────────┬──────────┬───────────────┬────────────────┬──────────────────────┐
│ version  │ type_id  │  sequence_no  │  timestamp_ms  │  payload             │
│ 1 byte   │ 1 byte   │  4 bytes      │  8 bytes       │  0–N bytes           │
└──────────┴──────────┴───────────────┴────────────────┴──────────────────────┘
```

- **Total header size**: 14 bytes
- **Byte order**: Big-endian (network byte order)
- **Max packet size**: 1400 bytes (safely under Ethernet MTU)
- **version**: Always `0x01` in this version of the protocol
- **type_id**: See PacketType table below
- **sequence_no**: 32-bit unsigned, monotonically increasing per sender. Wraps at 2³²
- **timestamp_ms**: Sender wall-clock milliseconds since Unix epoch. Used for latency measurement
- **payload**: Type-specific bytes (see per-type layout below)

---

## Packet Types

| ID   | Name           | Direction      | Description                      |
|------|----------------|----------------|----------------------------------|
| 0x00 | PING           | Bridge → Recv  | Latency probe                    |
| 0x01 | PONG           | Recv → Bridge  | Reply to PING                    |
| 0x02 | KEEP_ALIVE     | Both           | Heartbeat, no payload            |
| 0x03 | PAIR_REQUEST   | Bridge → Recv  | Initiate pairing                 |
| 0x04 | PAIR_RESPONSE  | Recv → Bridge  | Accept/reject pairing            |
| 0x05 | PAIR_CONFIRM   | Bridge → Recv  | Confirm pairing token            |
| 0x06 | MODE_SWITCH    | Bridge → Recv  | Switch active input mode         |
| 0x07 | DISCONNECT     | Both           | Clean disconnect                 |
| 0x08 | RECONNECT      | Both           | Request reconnect                |
| 0x09 | ACK            | Both           | Generic acknowledgement          |
| 0x0A | ERROR          | Both           | Error notification                |
| 0x20 | KEY_DOWN       | Bridge → Recv  | Key pressed                      |
| 0x21 | KEY_UP         | Bridge → Recv  | Key released                     |
| 0x22 | MOUSE_MOVE     | Bridge → Recv  | Relative mouse movement          |
| 0x23 | MOUSE_DOWN     | Bridge → Recv  | Mouse button pressed             |
| 0x24 | MOUSE_UP       | Bridge → Recv  | Mouse button released            |
| 0x25 | SCROLL         | Bridge → Recv  | Scroll wheel event               |
| 0x26 | TEXT_INPUT     | Bridge → Recv  | Composed text string             |
| 0x27 | MODIFIER_STATE | Bridge → Recv  | Current modifier key bitmask     |
| 0x28 | SPECIAL_ACTION | Bridge → Recv  | Android navigation action        |

**ID ranges**:
- `0x00–0x1F`: Control / meta packets
- `0x20–0x5F`: Input event packets
- `0x60–0xFF`: Reserved for future extension

**DO NOT CHANGE existing IDs.** This breaks compatibility with paired devices running older versions.

---

## Payload Layouts

### PING (0x00)
No payload.

### PONG (0x01)
```
│ ping_seq_no │
│ 4 bytes     │
```

### KEY_DOWN / KEY_UP (0x20, 0x21)
```
│ key_code  │ scan_code │ modifiers │
│ 4 bytes   │ 4 bytes   │ 1 byte    │
```
- `key_code`: Android `KeyEvent.KEYCODE_*` value
- `scan_code`: USB HID Usage ID (for debugging/remapping)
- `modifiers`: Bitmask — bit 0=Shift, 1=Ctrl, 2=Alt, 3=Meta, 4=CapsLock, 5=NumLock

### MOUSE_MOVE (0x22)
```
│ dx      │ dy      │
│ 4 bytes │ 4 bytes │  (IEEE 754 float32)
```
Relative movement. dx > 0 = right, dy > 0 = down.

### MOUSE_DOWN / MOUSE_UP (0x23, 0x24)
```
│ button │
│ 1 byte │
```
Button IDs: 0=Left, 1=Right, 2=Middle, 3=Back, 4=Forward

### SCROLL (0x25)
```
│ dx      │ dy      │
│ 4 bytes │ 4 bytes │  (IEEE 754 float32)
```
dy > 0 = scroll down, dy < 0 = scroll up (natural scroll).

### TEXT_INPUT (0x26)
```
│ text (UTF-8 bytes, variable length) │
```
Max ~1380 bytes (stay under MTU). Longer text must be split.

### MODIFIER_STATE (0x27)
```
│ modifiers │
│ 1 byte    │
```
Same bitmask as KEY_DOWN.

### SPECIAL_ACTION (0x28)
```
│ action │
│ 1 byte │
```
Action IDs: 0=Back, 1=Home, 2=Recents, 3=Notifications, 4=Power, 5=VolumeUp, 6=VolumeDown, 7=Screenshot

---

## Pairing Flow

```
Bridge                              Receiver
  │                                    │
  │── PAIR_REQUEST ──────────────────► │  (contains device name)
  │                                    │  (user confirms on receiver)
  │◄─ PAIR_RESPONSE ──────────────── │  (contains token)
  │                                    │
  │── PAIR_CONFIRM ─────────────────► │  (echoes token)
  │                                    │
  │  (both store token in DataStore)   │
  │                                    │
  │  subsequent packets validated      │
  │  by shared token                   │
```

---

## Error Handling

- **Malformed packet**: discard silently, log if PACKET_LOGGING_ENABLED
- **Unknown type ID**: discard silently
- **Wrong version**: discard, log warning
- **Invalid token**: discard, log warning, do not disconnect (may be a stray broadcast)
- **Packet loss**: acceptable for input events (UDP). Control packets (PAIR_*, PING) may be retried.

---

## Example Packets (hex)

Key down 'A' with no modifiers:
```
01 20 00000001 0000000000000000 00000041 00000004 00
^  ^  ^^^^^^^^ ^^^^^^^^^^^^^^^^ ^^^^^^^^ ^^^^^^^^ ^^
v  t  seqNo    timestampMs      keyCode  scanCode modifiers
```

Mouse move (+5, -3):
```
01 22 00000002 0000000000000000 40A00000 C0400000
                                ^^^^^^^^ ^^^^^^^^
                                dx=5.0f  dy=-3.0f
```
