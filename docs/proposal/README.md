# Historical group idea proposal

`ThogaMura_Group_Proposal.pdf` and the editable LaTeX source record the **original idea** discussed before choosing the implementation stack. That document proposed a Java Swing UI and embedded database.

**The current implementation plan supersedes those technology choices.** The team now prefers **Next.js + Spring Boot HTTP gateway + separate Spring Boot Java socket server + PostgreSQL**; authoritative sources are `../ARCHITECTURE.md`, `../FRONTEND_PLAN.md`, `../BACKEND_PLAN.md`, `../contracts/` and `../decisions/DECISION_LOG.md`.

The original business problem remains useful, but its original UDP discovery allocation has been replaced by the M2 supplier HTTP/URL connector in `../NETWORK_FEATURES_V2.md`. The five-member workload split in the PDF is historical, not current. The original five lecture-concept workloads remain historical, but do not ask an AI to implement Swing/H2 from the old PDF unless the whole team changes the decision.
