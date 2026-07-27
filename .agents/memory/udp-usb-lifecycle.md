---
name: UDP and USB startup lifecycle
description: Preserve reply endpoints and pre-launch lifecycle flags in the input pipeline.
---

# UDP and USB startup lifecycle

**Rule:** A receiver-mode UDP reply goes to the full observed sender endpoint; every loop
guarded by a lifecycle flag starts only after that flag is true and also respects its own
coroutine cancellation. Reconnects create fresh queues and discard the prior endpoint.
**Why:** The bridge's UDP source port is ephemeral, so sending a reply to the configured receiver
port makes pairing/PONG disappear. Launching UDP or USB reader coroutines while their guard flag
is false lets them exit immediately without an error. A fast reconnect can make an old cancelled
reader see a shared flag turn true again and spin on its closed socket.
**How to apply:** Retain `InetSocketAddress` for receiver replies; set `isConnected`/`isActive`
before launch; include `coroutineContext.isActive` in long-running loop guards; recreate closed
UDP queues and clear `lastSenderAddress` per connection; attach the bridge collector before USB
readers emit because the input flow has no replay.
