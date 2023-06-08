# Dagger 2 in SystemUI
*Dagger 2 is a dependency injection framework that compiles annotations to code
to create dependencies without reflection*

## Recommended reading

Go read about Dagger 2.

 - [User's guide](https://google.github.io/dagger/users-guide)

## State of the world

Dagger 2 has been turned on for SystemUI and much of
[Dependency.java](../src/com/android/systemui/Dependency.java)
has been converted to use Dagger. Since a lot of SystemUI depends on Dependency,
stubs have been added to Dependency to proxy any gets through to the instances
provided by dagger, this will allow migration of SystemUI through a number of CLs.

### How it works in SystemUI

There are three high level "scopes" of concern in SystemUI. They all represent
singleton scopes, but serve different purposes.

* `@Singleton` - Instances that are shared everywhere. There isn't a  lot of
   code in this scope. Things like the main thread, and Android Framework
   provided instances mostly.
* `@WMShell` - WindowManager related code in the SystemUI process. We don't
   want this code relying on the rest of SystemUI, and we don't want the rest
   of SystemUI peeking into its internals, so it runs in its own Subcomponent.
* `@SysUISingleton` - Most of what would be considered "SystemUI". Most feature
   work by SystemUI developers goes into this scope. Useful interfaces from
   WindowManager are made available inside this Subcomponent.

The root dagger graph is created by an instance of `SystemUIInitializer`.
See [README.md](../README.md) for more details.
For the classes that we're using in Dependency and are switching to dagger, the
equivalent dagger version is using `@Singleton` and therefore only has one instance.
To have the single instance span all of SystemUI and be easily accessible for
other components, there is a single root `@Component` that exists that generates
these. The component lives in
[ReferenceGlobalRootComponent.java](../src/com/android/systemui/dagger/ReferenceGlobalRootComponent.java).

### Adding a new injectable object

First annotate the constructor with `@Inject`. Also annotate it with
`@SysUISingleton` if only one instance should be created.

```kotlin
@SysUISingleton
class FeatureStartable
@Inject
constructor(
/* ... */
) {
    // ...
}
```

If you have an interface class and an implementation class, Dagger needs to
know how to map it. The simplest way to do this is to add an `@Binds` method
in a module. The type of the return value tells dagger which dependency it's
providing:

```kotlin
@Module
abstract class FeatureModule {
    @Binds
    abstract fun bindsFeature(impl: FeatureImpl): Feature
}
```

If you have a class that you want to make injectable that has can not
be easily constructed by Dagger, write a `@Provides` method for it:

```kotlin
@Module
abstract class FeatureModule {
    @Module
    companion object {
        @Provides
        fun providesFeature(ctx: Context): Feature {
            return FeatureImpl.constructFromContext(ctx)
        }
    }
}
```

### Module Organization

Please define your modules on _at least_ per-package level. If the scope of a
package grows to encompass a great number of features, create per-feature
modules.

**Do not create catch-all modules.** Those quickly grow unwieldy and
unmaintainable. Any that exist today should be refactored into obsolescence.

You can then include your module in one of three places:

1) Within another module that depends on it. Ideally, this creates a clean
   dependency graph between features and utilities.
2) For features that should exist in all versions of SystemUI (AOSP and
   any variants), include the module in
   [SystemUIModule.java](../src/com/android/systemui/dagger/SystemUIModule.java).
3) For features that should exist only in AOSP, include the module in
   [ReferenceSystemUIModule.java](../src/com/android/systemui/dagger/ReferenceSystemUIModule.java).
   Similarly, if you are working on a custom version of SystemUI and have code
   specific to your version, include it in a module specific to your version.

### Using injection with Fragments

Fragments are created as part of the FragmentManager, so they need to be
setup so the manager knows how to create them. To do that, add a method
to com.android.systemui.fragments.FragmentService$FragmentCreator that
returns your fragment class. That is all that is required, once the method
exists, FragmentService will automatically pick it up and use injection
whenever your fragment needs to be created.

```java
public interface FragmentCreator {
    NavigationBarFragment createNavigationBar();
}
```

If you need to create your fragment (i.e. for the add or replace transaction),
then the FragmentHostManager can do this for you.

```java
FragmentHostManager.get(view).create(NavigationBarFragment.class);
```

## Updating Dagger2

We depend on the Dagger source found in external/dagger2. We should automatically pick up on updates
when that repository is updated.
 
## TODO List

 - Eliminate usages of Dependency#get: http://b/hotlists/3940788
