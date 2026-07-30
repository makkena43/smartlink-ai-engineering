# Scenario 02 — Brownfield · Task Decomposition

> **Status: not started.** Requires Gate C, which requires this scenario's engineering spec,
> which requires Scenario 01 to be complete.

Each task carries intent, constraints, acceptance criteria and technical context — the task
envelope used to direct the AI assistant.

Sequencing differs from greenfield in one important way: the **migration lands before the
code that depends on it**, so a rollback of the application does not strand the schema.
