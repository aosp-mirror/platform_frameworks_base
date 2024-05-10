# Ravenwood

Ravenwood is an officially-supported lightweight unit testing environment for Android platform code that runs on the host.

Ravenwood’s focus on Android platform use-cases, improved maintainability, and device consistency distinguishes it from Robolectric, which remains a popular choice for app testing.

## Background

Executing tests on a typical Android device has substantial overhead, such as flashing the build, waiting for the boot to complete, and retrying tests that fail due to general flakiness.

In contrast, defining a lightweight unit testing environment mitigates these issues by running directly from build artifacts (no flashing required), runs immediately (no booting required), and runs in an isolated environment (less flakiness).

## Guiding principles
Here’s a summary of the guiding principles for Ravenwood, aimed at addressing Robolectric design concerns and better supporting Android platform developers:

* **API support for Ravenwood is opt-in.**  Teams that own APIs decide exactly what, and how, they support their API functionality being available to tests.  When an API hasn’t opted-in, the API signatures remain available for tests to compile against and/or mock, but they throw when called under a Ravenwood environment.
    * _Contrasted with Robolectric which attempts to run API implementations as-is, causing maintenance pains as teams maintain or redesign their API internals._
* **API support and customizations for Ravenwood appear directly inline with relevant code.** This improves maintenance of APIs by providing awareness of what code runs under Ravenwood, including the ability to replace code at a per-method level when Ravenwood-specific customization is needed.
    * _Contrasted with Robolectric which maintains customized behavior in separate “Shadow” classes that are difficult for maintainers to be aware of._
* **APIs supported under Ravenwood are tested to remain consistent with physical devices.**  As teams progressively opt-in supporting APIs under Ravenwood, we’re requiring they bring along “bivalent” tests (such as the relevant CTS) to validate that Ravenwood behaves just like a physical device.
    * _Contrasted with Robolectric, which has limited (and forked) testing of their environment, increasing their risk of accidental divergence over time and misleading “passing” signals._
* **Ravenwood aims to support more “real” code.**  As API owners progressively opt-in their code, they have the freedom to provide either a limited “fake” that is a faithful emulation of how a device behaves, or they can bring more “real” code that runs on physical devices.
    * _Contrasted with Robolectric, where support for “real” code ends at the app process boundary, such as a call into `system_server`._

## More details

* [Ravenwood for Test Authors](test-authors.md)
* [Ravenwood for API Maintainers](api-maintainers.md)
