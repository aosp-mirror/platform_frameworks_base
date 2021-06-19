# Background

As general background, `OWNERS` files expedite code reviews by helping code
authors quickly find relevant reviewers, and they also ensure that stakeholders
are involved in code changes in their areas.

The structure of `frameworks/base/` is unique among Android repositories, and
it's evolved into a complex interleaved structure over the years.  Because of
this structure, the best place to authoritatively define `OWNERS` can vary
wildly, but here are some common patterns:

* `core/java/` contains source that is included in the base classpath, and as
such it's where most APIs are defined:
  * `core/java/android/app/`
  * `core/java/android/content/`
* `services/core/` contains most system services, and these directories
typically have more granularity than `core/java/`, since they can be refactored
without API changes:
  * `services/core/java/com/android/server/net/`
  * `services/core/java/com/android/server/wm/`
* `services/` contains several system services that have been isolated from the
main `services/core/` project:
  * `services/appwidget/`
  * `services/midi/`
* `apex/` contains Mainline modules:
  * `apex/jobscheduler/`
  * `apex/permission/`
* Finally, some teams may have dedicated top-level directories:
  * `media/`
  * `wifi/`

# Design

Area maintainers are strongly encouraged to list people in a single
authoritative `OWNERS` file in **exactly one** location.  Then, other paths
should reference that single authoritative `OWNERS` file using an include
directive.  This approach ensures that updates are applied consistently across
the tree, reducing maintenance burden.

# Examples

The exact syntax of `OWNERS` files can be difficult to get correct, so here are
some common examples:

```
# Complete include of top-level owners from this repo
include /ZYGOTE_OWNERS
# Partial include of top-level owners from this repo
per-file ZygoteFile.java = file:/ZYGOTE_OWNERS
```
```
# Complete include of subdirectory owners from this repo
include /services/core/java/com/android/server/net/OWNERS
# Partial include of subdirectory owners from this repo
per-file NetworkFile.java = file:/services/core/java/com/android/server/net/OWNERS
```
```
# Complete include of top-level owners from another repo
include platform/libcore:/OWNERS
# Partial include of top-level owners from another repo
per-file LibcoreFile.java = file:platform/libcore:/OWNERS
```
```
# Complete include of subdirectory owners from another repo
include platform/frameworks/av:/camera/OWNERS
# Partial include of subdirectory owners from another repo
per-file CameraFile.java = file:platform/frameworks/av:/camera/OWNERS
```
