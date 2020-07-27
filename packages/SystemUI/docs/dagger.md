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

- `SystemUIFactory` `@Provides` dependencies that need to be overridden by SystemUI
variants (like other form factors e.g. Car). 

- `DependencyBinder` creates the mapping from interfaces to implementation classes. 

- `DependencyProvider` provides or binds any remaining depedencies required.

### Adding injection to a new SystemUI object

SystemUI object are made injectable by adding an entry in `SystemUIBinder`. SystemUIApplication uses
information in that file to locate and construct an instance of the requested SystemUI class.

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
    NavigationBarFragment createNavigationBar();
}
```

If you need to create your fragment (i.e. for the add or replace transaction),
then the FragmentHostManager can do this for you.

```java
FragmentHostManager.get(view).create(NavigationBarFragment.class);
```

### Using injection with Views

DO NOT ADD NEW VIEW INJECTION. VIEW INJECTION IS BEING ACTIVELY DEPRECATED.

Needing to inject objects into your View's constructor generally implies you
are doing more work in your presentation layer than is advisable.
Instead, create an injected controller for you view, inject into the
controller, and then attach the view to the controller after inflation.

View injection generally causes headaches while testing, as inflating a view
(which may in turn inflate other views) implicitly causes a Dagger graph to 
be stood up, which may or may not contain the appropriately 
faked/mocked/stubbed objects. It is a hard to control process.

## Updating Dagger2

We depend on the Dagger source found in external/dagger2. We should automatically pick up on updates
when that repository is updated.

*Deprecated:*

Binaries can be downloaded from https://repo1.maven.org/maven2/com/google/dagger/ and then loaded
into
[/prebuilts/tools/common/m2/repository/com/google/dagger/](http://cs/android/prebuilts/tools/common/m2/repository/com/google/dagger/)

The following commands should work, substituting in the version that you are looking for:

````
cd prebuilts/tools/common/m2/repository/com/google/dagger/

wget -r -np -nH --cut-dirs=4 -erobots=off -R "index.html*" -U "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36" https://repo1.maven.org/maven2/com/google/dagger/dagger/2.28.1/

wget -r -np -nH --cut-dirs=4 -erobots=off -R "index.html*" -U "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36" https://repo1.maven.org/maven2/com/google/dagger/dagger-compiler/2.28.1/

wget -r -np -nH --cut-dirs=4 -erobots=off -R "index.html*" -U "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36" https://repo1.maven.org/maven2/com/google/dagger/dagger-spi/2.28.1/

wget -r -np -nH --cut-dirs=4 -erobots=off -R "index.html*" -U "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.116 Safari/537.36" https://repo1.maven.org/maven2/com/google/dagger/dagger-producers/2.28.1/
````

Then update `prebuilts/tools/common/m2/Android.bp` to point at your new jars.
 
## TODO List

 - Eliminate usages of Dependency#get
 - Add links in above TODO
