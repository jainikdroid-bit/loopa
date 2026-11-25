# Independent creator source review

Requested scope: new independent, creator-originated videos rather than more copies of the existing aggregators. This is a source-review backlog, **not enabled integrations**.

Existing adapters already include creator/user uploads and federated video. Adding a second index of the same sites does not establish unique content. The labels "amateur" and "homemade" are not proof of authorship, adulthood, consent or redistribution rights.

## Integration candidates / decisions

| Route | Status | Required before enabling |
| --- | --- | --- |
| Existing PeerTube integration, with individually approved creator-operated instances | Existing API; additional instances not approved | Verify instance moderation, creator provenance, consent, licensing, withdrawal and permitted playback; do not infer eligibility from NSFW flags or tags. |
| Creator-authorized Fanvue OAuth application | Official API access policy exists; not a public catalogue integration | Obtain approved scopes, written authorization for intended use, and verify which media actions the platform actually permits. Do not scrape, bypass subscriptions, or retain protected content. |
| Direct partner feeds from independent rights holders | No partner credentials or contract supplied | Agree feed schema, content permissions, takedown/revocation SLA and allowed cache/download behavior with each partner. |

References (developer/rights documentation):
- https://docs.joinpeertube.org/api-rest-reference.html
- https://docs.joinpeertube.org/use/create-upload-video
- https://legal.fanvue.com/api-policy

## Acceptance requirements for Claude

- Use only documented, authorized access with explicit opt-in and all existing age gates. Do not enable unreviewed sources by default or use tags to assert verified consent.
- Confirm creators and depicted persons are adults and have consented to publication; rely on reviewed provider processes, not speculative image classification. Exclude unverified, leaked or non-consensual uploads.
- Respect source-specific rights, playback restrictions and takedowns. No bypass of DRM, login, paywall, rate limits or moderation.
- Preserve canonical source ID and attribution; measure duplicate rate locally using authorized metadata, without collecting intimate data on the telemetry server. Do not send private content fingerprints or URLs to external services.
- Validate provider URLs/redirects and do not forward credentials across hosts. Use synthetic parser fixtures, pagination, timeout, malformed metadata, revoked content and duplicate tests.
- Add a source only when authorization and API contract are established. Record the reviewed permissions and tests, not private keys or identity documents, in the release handoff.
