# TrustTests framework tests

These tests test the "trust" part of the platform primarily implemented via TrustManagerService in
the system server and TrustAgentService in system apps.

Tests are separated into separate files based on major groupings. When creating new tests, find a
_closely_ matching existing test file or create a new test file. Prefer many test files over large
test files.

Each test file has its own trust agent. To create a new trust agent:

1. Create a new class extending from `BaseTrustAgentService` class in your test file
2. Add a new `<service>` stanza to `AndroidManifest.xml` in this directory for the new agent
   following the pattern fo the existing agents.

To run:

```atest TrustTests```

## Testing approach:

1. Test the agent service as a black box; avoid inspecting internal state of the service or
   modifying the system code outside of this directory.
2. The primary interface to the system is through these three points:
    1. `TrustAgentService`, your agent created by the `TrustAgentRule` and accessible via
       the `agent` property of the rule.
        1. Call command methods (e.g. `grantTrust`) directly on the agent
        2. Listen to events (e.g. `onUserRequestedUnlock`) by implementing the method in
           your test's agent class and tracking invocations. See `UserUnlockRequestTest` for an
           example.
    2. `TrustManager` which is the interface the rest of the system (e.g. SystemUI) has to the
       service.
        1. Through this API, simulate system events that the service cares about
           (e.g. `reportUnlockAttempt`).
    3. `TrustListener` which is the interface the rest of the system (e.g. SystemUI) uses to receive
       events from the service.
        1. Through this, verify behavior that affects the rest of the system. For example,
           see `LockStateTrackingRule`.
3. To re-use code between tests, prefer creating new rules alongside the existing rules or adding
   functionality to a _closely_ matching existing rule.
