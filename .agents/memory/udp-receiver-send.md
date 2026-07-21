---
name: UDP bidirectional in receiver mode
description: How UdpTransport handles sending in receiver mode (PONG etc.) — the lastSenderAddress pattern
---

# UDP bidirectional in receiver mode

**Why:** UdpTransport in receiver mode (isSender=false) has empty config.targetIp. Before the fix, startSendLoop called InetAddress.getByName("") which threw, silently dropping all PONG replies.

**How to apply:** Any time the receiver needs to send a packet back to the bridge (PONG, future ACK, pairing response), it works automatically via lastSenderAddress — no API changes needed.

## Implementation
- `startReceiveLoop()`: on every received datagram, `lastSenderAddress = dp.socketAddress as InetSocketAddress`
- `startSendLoop()`: if `!isSender`, iterate sendChannel and send each packet to `lastSenderAddress` (skip if null — bridge not seen yet)
- If isSender: pre-resolve `InetAddress.getByName(config.targetIp)` and use for all sends (original behavior)

## Gotcha
- Receiver cannot reply until at least one packet has arrived (lastSenderAddress is null before that).
- This is fine: PONG replies to PING, and PING arrives before we need to send PONG.
