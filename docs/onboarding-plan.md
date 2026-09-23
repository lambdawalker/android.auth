# Implementation plan

1. Add typed onboarding API and wire-contract tests for capability headers, revision/consent bodies, credential JSON, email rotation and API failures.
2. Add single-flight, memory-only ViewModel and tests for passkey/fallback progression, final email ordering, uncertain retry, restart and 202 reconciliation.
3. Add Credential Manager bridge and Compose onboarding forms; keep user secrets out of saved state and logs.
4. Integrate configurable sample application and document API, Digital Asset Links, password manager use, lifecycle and Windows build commands.
5. Run available verification, record environment limitations, and publish feature/onboarding without merging into master.
