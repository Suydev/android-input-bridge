---
name: Latency-critical send path
description: UdpTransport.send is declared suspend but only trySends; use sendNow()/sendDirect() in hot paths to skip coroutine dispatch
---

# Latency Hot-Path Sends

**Rule:** `UdpTransport.send()` is declared `suspend` but never actually suspends (it only serializes and `trySend` into a channel). On latency-critical paths (trackpad mouse moves, cursor-goto), call `transport.sendDirect(packet)` — it serializes and calls `socket.send()` synchronously on the calling thread, skipping the channel + send-loop coroutine dispatch hop entirely.

**Why:** The mouse pipeline goal is lowest possible end-to-end latency on WiFi (~<10ms RTT budget). A coroutine hop per event (channel read in a separate send loop) eats the frame budget even if the caller itself doesn't launch a coroutine.

**How to apply:** `sendDirect()` is sender-mode only (needs cached `fixedTargetAddress`); receiver mode still uses the channel path (PONG etc.). `sendNow()` is the channel-based non-suspend variant for when ordering matters or in receiver mode. Never `withContext(Dispatchers.Main)` when the caller is already on the main thread. Socket buffers: 64KB for interactive traffic, not 256KB+ (bufferbloat). Send/receive loops run at `THREAD_PRIORITY_URGENT_AUDIO`. `PacketSerializer.deserialize(data, length)` avoids the per-packet `copyOf()`.