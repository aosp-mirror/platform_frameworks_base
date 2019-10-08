# Dagger 2 in SystemUI
*Dagger 2 is a dependency injection framework that compiles annotations to code
to create dependencies without reflection*

## Recommended reading

Go read about Dagger 2.

 - [User's guide](https://google.github.io/dagger/users-guide)

TODO: Add some links.

## State of the world

Dagger 2 has been turned on for SystemUI and a early first pass has been taken
for converting everything in [Dependency.java](packages/systemui/src/com/android/systemui/Dependency.java)
to use Dagger. Since a lot of SystemUI depends on Dependency, stubs have been added to Dependency 
to proxy any gets through to the instances provided by dagger, this will allow migration of SystemUI 
through a number of CLs.

### How it works in SystemUI

For the classes that we're using in Dependency and are switching to dagger, the
equivalent dagger version is using `@Singleton` and therefore only has one instance.
To have the single instance span all of SystemUI and be easily accessible for
other components, there is a single root `@Component` that exists that generates
these. The component lives in [SystemUIFactory](packages/systemui/src/com/android/systemui/SystemUIFactory.java)
and is called `SystemUIRootComponent`.

```java

@Singleton
@Component(modules = {SystemUIFactory.class, DependencyProvider.class, DependencyBinder.class,
        ContextHolder.class})
public interface SystemUIRootComponent {
    @Singleton
    Dependency.DependencyInjector createDependency();
}
```

The root component is composed of root modules, which in turn provide the global singleton 
dependencies across all of SystemUI.

- `ContextHolder` is just a wrapper that provides a context.

- `SystemUIFactory` `@Provides` dependencies that need to be overridden by SystemUI
variants (like other form factors e.g. Car). 

- `DependencyBinder` creates the mapping from interfaces to implementation classes. 

- `DependencyProvider` provides or binds any remaining depedencies required.

### Adding injection to a new SystemUI object

Anything that depends on any `@Singleton` provider from SystemUIRootComponent
should be declared as an `@Subcomponent` of the root component, this requires
declaring your own interface for generating your own modules or just the
object you need injected. The subcomponent also needs to be added to
SystemUIRootComponent in SystemUIFactory so it can be acquired.

```java
public interface SystemUIRootComponent {
+    @Singleton
+    Dependency.DependencyInjector createDependency();
}

public class Dependency extends SystemUI {
  //...
+  @Subcomponent
+  public interface DependencyInjector {
+      Dependency createSystemUI();
+  }
}
```

For objects which extend SystemUI and require injection, you can define an
injector that creates the injected object for you. This other class should
be referenced in [@string/config_systemUIServiceComponents](packages/SystemUI/res/values/config.xml).

```java
public static class DependencyCreator implements Injector {
    @Override
    public SystemUI apply(Context context) {
        return SystemUIFactory.getInstance().getRootComponent()
                .createDependency()
                .createSystemUI();
    }
}
```

### Adding a new injectable object

First tag the constructor with `@Inject`. Also tag it with `@Singleton` if only one
instance should be created.

```java
@Singleton
public class SomethingController {
  @Inject
  public SomethingController(Context context,
    @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
      // context and mainHandler will be automatically populated.
  }
}
```

If you have an interface class and an implementation class, dagger needs to know
how to map it. The simplest way to do this is to add an `@Provides` method to
DependencyProvider. The type of the return value tells dagger which dependency it's providing.

```java
public class DependencyProvider {
  //...
  @Singleton
  @Provides
  public SomethingController provideSomethingController(Context context,
      @Named(MAIN_HANDLER_NAME) Handler mainHandler) {
    return new SomethingControllerImpl(context, mainHandler);
  }
}
```

If you need to access this from Dependency#get, then add an adapter to Dependency
that maps to the instance provided by Dagger. The changes should be similar
to the following diff.

```java
public class Dependency {
  //...
  @Inject Lazy<SomethingController> mSomethingController;
  //...
  public void start() {
    //...
    mProviders.put(SomethingController.class, mSomethingController::get);
  }
}
```

### Using injection with Fragments

Fragments are created as part of the FragmentManager, so they need to be
setup so the manager knows how to create them. To do that, add a method
to com.android.systemui.fragments.FragmentService$FragmentCreator that
returns your fragment class. Thats all thats required, once the method
exists, FragmentService will automatically pick it up and use injection
whenever your fragment needs to be created.

```java
public interface FragmentCreator {
+   NavigationBarFragment createNavigationBar();
}
```

If you need to create your fragment (i.e. for the add or replace transaction),
then the FragmentHostManager can do this for you.

```java
FragmentHostManager.get(view).create(NavigationBarFragment.class);
```

### Using injection with Views

Generally, you shouldn't need to inject for a view, as the view should
be relatively self contained and logic that requires injection should be
moved to a higher level construct such as a Fragment or a top-level SystemUI
component, see above for how to do injection for both of which.

Still here? Yeah, ok, sysui has a lot of pre-existing views that contain a
lot of code that could benefit from injection and will need to be migrated
off from Dependency#get uses. Similar to how fragments are injected, the view
needs to be added to the interface
com.android.systemui.util.InjectionInflationController$ViewInstanceCreator.

```java
public interface ViewInstanceCreator {
+   QuickStatusBarHeader createQsHeader();
}
```

Presumably you need to inflate that view from XML (otherwise why do you
need anything special? see earlier sections about generic injection). To obtain
an inflater that supports injected objects, call InjectionInflationController#injectable,
which will wrap the inflater it is passed in one that can create injected
objects when needed.

```java
@Override
public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
        Bundle savedInstanceState) {
    return mInjectionInflater.injectable(inflater).inflate(R.layout.my_layout, container, false);
}
```

There is one other important thing to note about injecting with views. SysUI
already has a Context in its global dagger component, so if you simply inject
a Context, you will not get the one that the view should have with proper
theming. Because of this, always ensure to tag views that have @Inject with
the @Named view context.

```java
public CustomView(@Named(VIEW_CONTEXT) Context themedViewContext, AttributeSet attrs,
        OtherCustomDependency something) {
    //...
}
```

## TODO List

 - Eliminate usages of Dependency#get
 - Add links in above TODO
