---
trigger: model_decision
description: "Apply when changing Java socket transports, NIO, TLS, inventory concurrency or request persistence."
---

# Network core

Follow `AGENTS.md` and `docs/contracts/NETWORK_PROTOCOL_V1.md`. This is assessed Java networking work: TCP commands, UDP discovery, Java NIO notifications, threaded processing, and TLS each have distinct owners. No direct Spring controller-to-repository shortcut. Do not treat NIO notifications as durable events. Use read/write buffers correctly, protect command data with TLS and test duplicate requestId semantics.
