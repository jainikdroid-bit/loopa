# Handoff — stability branch on 1.38

Start from prepare/stability-1.38, based on main a811c2c. PR #2 history is now joined to PR #3 by an explicit two-parent merge; read the conflict-resolution record in docs/DEVELOPMENT_STATUS.md. Do not blindly reintroduce superseded or intentionally excluded code.

Read docs/DEVELOPMENT_STATUS.md. Inspect the new PR diff, confirm latest main, run CI and device tests using synthetic fixtures. Preserve original grid layout defaults, concurrency, timeouts, search and model behavior. No real personal data in fixtures or logs. Do not deploy the stats Worker, publish APKs or change signing without owner approval. Test upgrade consent and v1/v2 compatibility before release. Document actual pass/fail results, not assumed outcomes.
