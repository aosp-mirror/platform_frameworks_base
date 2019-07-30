# Making Job Scheduler into a Mainline Module

## TODOs

See also:
- http://go/moving-js-code-for-mainline
- http://go/jobscheduler-code-dependencies-2019-07

- [ ] Move client code
  - [ ] Move code
  - [ ] Make build file
  - [ ] "m jobscheduler-framework" pass
  - [ ] "m framework" pass
  - [ ] "m service" pass
- [ ] Move proto
  - No, couldn't do it, because it's referred to by incidentd_proto
- [ ] Move service
  - [X] Move code (done, but it won't compile yet)
  - [X] Make build file
  - [X] "m service" pass
  - [X] "m jobscheduler-service" pass
    - To make it pass, jobscheduler-service has to link services.jar too. Many dependencies.
- [ ] Move this into `frameworks/apex/jobscheduler/...`. Currently it's in `frameworks/base/apex/...`
because `frameworks/apex/` is not a part of any git projects. (and also working on multiple
projects is a pain.)


## Problems
- Couldn't move dumpsys proto files. They are used by incidentd_proto, which is in the platform
  (not updatable).
  - One idea is *not* to move the proto files into apex but keep them in the platform.
    Then we make sure to extend the proto files in a backward-compat way (which we do anyway)
    and always use the latest file from the JS apex.

- There are a lot of build tasks that use "framework.jar". (Examples: hiddenapi-greylist.txt check,
  update-api / public API check and SDK stub (android.jar) creation)
  To make the downstream build modules buildable, we need to include js-framework.jar in
  framework.jar. However it turned out to be tricky because soong has special logic for "framework"
  and "framework.jar".
  i.e. Conceptually, we can do it by renaming `framework` to `framework-minus-jobscheduler`, build
  `jobscheduler-framework` with `framework-minus-jobscheduler`, and create `framework` by merging
  `framework-minus-jobscheduler` and `jobscheduler-framework`.
  However it didn't quite work because of the special casing.

- JS-service uses a lot of other code in `services`, so it needs to link services.core.jar e.g.
 - Common system service code, e.g. `com.android.server.SystemService`
 - Common utility code, e.g. `FgThread` and `IoThread`
 - Other system services such as `DeviceIdleController` and `ActivityManagerService`
 - Server side singleton. `AppStateTracker`
 - `DeviceIdleController.LocalService`, which is a local service but there's no interface class.
 - `XxxInternal` interfaces that are not in the framework side. -> We should be able to move them.
