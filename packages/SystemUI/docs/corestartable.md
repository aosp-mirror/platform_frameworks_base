# Starting SystemUI CoreStartables

## Overview

A [CoreStartable](/packages/SystemUI/src/com/android/systemui/CoreStartable.java) class represents
a chunk of SystemUI functionality that is initialized at startup time, independent of the rest of
the system. Which CoreStartables are included and run can be customized per-build via Dagger.

The base class contains nominal functionality, making it lightweight and inexpensive to construct.
Unlike Activities, Services, and similar Android constructs, CoreStartables do not have  unique
context, and have no lifecycle methods except for a singular `#start` method that is called once.

## How to Define a CoreStartable

1) Subclass `CoreStartable`. Put any initialization logic in its `#start` method. Preferably, put it
   in its own source package (with related code) to keep it organizationally distinct from other
   code in SystemUI.

2) Mark its class with `@SysUISingleton` and its constructor with `@Inject`.

3) Define a corresponding Dagger module in the same package. The name of the module should follow
   the pattern: “Start<Feature>Module” where <Feature> is replaced with the name of the
   CoreStartable.

4) Put the following definition inside your new module:

```java
     @Binds
     @IntoMap
     @ClassKey(Feature.class)
     abstract CoreStartable bindFeature(Feature impl);
```

5) Include the new module in any clients that may need it. For AOSP, this is the
   SystemUICoreStartableModule.

## Tips and Tricks

**CoreStartables should be single-feature focused.** If you need something run at startup time
that doesn't have a clear initialization path in existing code, strongly consider defining a _new_
CoreStartable instead of inserting into a random place in an existing one.

**CoreStartables should be order independent.** They currently are started in an arbitrary but
deterministic order. We do not promise that this order won't change in the future, however. We do
not provide a mechanism for changing the order. If you need some other part of the system to
come online first, consider adding a listener to that part of the system.