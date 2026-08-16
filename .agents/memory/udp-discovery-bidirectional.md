---
name: UDP discovery must be bidirectional
description: On real Wi-Fi/hotspot, one-way receiver broadcast is dropped; bridge must also query and receiver must answer
---

# UDP auto-discovery must be bidirectional

**Rule:** The bridge must actively broadcast a QUERY and the receiver must listen for it and
reply directly to the bridge's discovery listen port (54322). The receiver still periodically
broadcasts its presence too.
**Why:** One-way discovery (receiver broadcasts, bridge passively listens) dropped packets on
real Wi-Fi/hotspot stacks — the bridge stayed "Searching" and only connected when the user
typed the receiver IP + PIN manually (BUG-133).
**How to apply:** When touching `AutoDiscovery`, never rely on a single broadcast direction.
Reply to the discovery port (DISCOVERY_PORT = 54322), NOT the query's ephemeral source port,
or the bridge's listener never receives the answer.
