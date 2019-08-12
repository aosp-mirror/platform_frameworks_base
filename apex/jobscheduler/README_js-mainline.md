# Making Job Scheduler into a Mainline Module

## TODOs

See also:
- http://go/moving-js-code-for-mainline
- http://go/jobscheduler-code-dependencies-2019-07

- [ ] Move this into `frameworks/apex/jobscheduler/...`. Currently it's in `frameworks/base/apex/...`
because `frameworks/apex/` is not a part of any git projects. (and also working on multiple
projects is a pain.)

## Current structure

- JS service side classes are put in `jobscheduler-service.jar`.
It's *not* included in services.jar, and instead it's put in the system server classpath,
which currently looks like the following:
`SYSTEMSERVERCLASSPATH=/system/framework/services.jar:/system/framework/jobscheduler-service.jar:/system/framework/ethernet-service.jar:/system/framework/wifi-service.jar:/system/framework/com.android.location.provider.jar`

  (Note `jobscheduler-service.jar` will be put at the end in http://ag/9128109)

  `SYSTEMSERVERCLASSPATH` is generated from `PRODUCT_SYSTEM_SERVER_JARS`.

- JS framework side classes are put in `jobscheduler-framework.jar`,
and the rest of the framework code is put in `framework-minus-apex.jar`,
as of http://ag/9145619.

  However these jar files are *not* put on the device. We still generate
  `framework.jar` merging the two jar files, and this jar file is what's
  put on the device and loaded by Zygote.


This is *not* the final design. From a gerrit comment on http://ag/9145619:

> This CL is just the first step, and the current state isn't not really the final form. For now we just want to have two separate jars, which makes it easier for us to analyze dependencies between them, and I wanted to minimize the change to the rest of the system. So, for example, zygote will still only have "framework.jar" in its classpath, instead of the two jars for now.
> But yes, eventually, we won't even be able to have the monolithic "framework.jar" file because of mainline, so we need to figure out how to build the system without creating it. At that point zygote will have the two separate jar files in its classpath.
> When we reach that point, we should revisit the naming of it, and yes, maybe the simple "framework.jar" is a good option.
> But again, for now, I want to make this change as transparent as possible to the rest of the world.
