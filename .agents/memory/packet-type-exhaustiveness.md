---
name: PacketType exhaustiveness in hot loops
description: ReceiverService and BridgeService packet-dispatch when() blocks must enumerate all PacketType values explicitly; lastInputSeqNo must only advance for input-event arms
---

# PacketType Exhaustiveness in Hot Loops

**Rule:** Every `when (packet.type)` block in `ReceiverService` and `BridgeService` must be
exhaustive over all `PacketType` enum values — no `else →`. Same applies to `when (mode)` over
`TransportMode` in `BridgeService.startPipeline()` and `WelcomeScreen`.

**Why:** The `else →` in `ReceiverService`'s hot receive loop (BUG-060) caused control packets
(`PONG`, `PAIR_RESPONSE`, `MODE_SWITCH`, `RECONNECT`, `ACK`, `ERROR`) to be routed through the
input-event path. The sequence-gap detector (`lastInputSeqNo`) advanced on those control packets,
producing false "dropped packet" log entries and corrupting the Diagnostics counter. The fix
required enumerating all 20 `PacketType` values across two groups: ignored-control and input-events.

**How to apply:**
1. When adding a new `PacketType`, grep for every `when (packet.type)` in the codebase. The
   compiler will fail both hot loops, forcing you to explicitly handle it in both services.
2. `lastInputSeqNo` tracking MUST only run inside input-event arms:
   `KEY_DOWN, KEY_UP, MOUSE_MOVE, MOUSE_DOWN, MOUSE_UP, SCROLL, TEXT_INPUT, MODIFIER_STATE, SPECIAL_ACTION`.
   Never inside control-packet arms.
3. When adding a new `TransportMode`, grep for `when (prefs.transportMode)` and `when (mode)` —
   both `startPipeline()` and `WelcomeScreen` must get explicit arms.

Current PacketType groups (as of Session 016):
- **Handled by ReceiverService**: PAIR_REQUEST, PAIR_CONFIRM, PING, KEEP_ALIVE, DISCONNECT
- **Ignored by ReceiverService (control, unexpected)**: PONG, PAIR_RESPONSE, MODE_SWITCH, RECONNECT, ACK, ERROR
- **Input events (both services)**: KEY_DOWN, KEY_UP, MOUSE_MOVE, MOUSE_DOWN, MOUSE_UP, SCROLL, TEXT_INPUT, MODIFIER_STATE, SPECIAL_ACTION
- **Handled by BridgeService incoming loop**: PAIR_RESPONSE, PONG
- **Unexpected at bridge (logged)**: PING, KEEP_ALIVE, PAIR_REQUEST, PAIR_CONFIRM, MODE_SWITCH, DISCONNECT, RECONNECT, ACK, ERROR
