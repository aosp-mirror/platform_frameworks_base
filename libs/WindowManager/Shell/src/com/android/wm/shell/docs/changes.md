# Making changes in the Shell

---

## Code reviews

In addition to the individual reviewers who are most familiar with the changes you are making,
please also add [wm-code-reviewers@google.com](http://g/wm-code-reviewers) to keep other WM folks
in the loop.

## Adding new code

### Internal Shell utility classes
If the new component is used only within the WMShell library, then there are no special
considerations, go ahead and add it (in the `com.android.wm.shell.common` package for example)
and make sure the appropriate [unit tests](testing.md) are added.

### Internal Shell components
If the new component is to be used by other components/features within the Shell library, then
you can create an appropriate package for this component to add your new code. The current
pattern is to have a single `<Component name>Controller` that handles the initialization of the
component.

As mentioned in the [Dagger usage](dagger.md) docs, you need to determine whether it should go into:
- `WMShellBaseModule` for components that other base & product components will depend on
- or `WMShellModule`, `TvWmShellModule`, etc. for product specific components that no base
  components depend on

### SysUI accessible components
In addition to doing the above, you will also need to provide an interface for calling to SysUI
from the Shell and vice versa.  The current pattern is to have a parallel `Optional<Component name>`
interface that the `<Component name>Controller` implements and handles on the main Shell thread.

In addition, because components accessible to SysUI injection are explicitly listed, you'll have to
add an appropriate method in `WMComponent` to get the interface and update the `Builder` in
`SysUIComponent` to take the interface so it can be injected in SysUI code.  The binding between
the two is done in `SystemUIFactory#init()` which will need to be updated as well.

### Launcher accessible components
Because Launcher is not a part of SystemUI and is a separate process, exposing controllers to
Launcher requires a new AIDL interface to be created and implemented by the controller.  The
implementation of the stub interface in the controller otherwise behaves similar to the interface
to SysUI where it posts the work to the main Shell thread.

### Component initialization
To initialize the component:
- On the Shell side, update `ShellInitImpl` to get a signal to initialize when the SysUI is started
- On the SysUI side, update `WMShell` to setup any bindings for the component that depend on
  SysUI code

### General Do's & Dont's
Do:
- Do add unit tests for all new components
- Do keep controllers simple and break them down as needed

Don't:
- **Don't** do initialization in the constructor, only do initialization in the init callbacks.
  Otherwise it complicates the building of the dependency graph.
- **Don't** create dependencies from base-module components on specific features (the base module
  is intended for use with all products)
  - Try adding a mechanism to register and listen for changes from the base module component instead
- **Don't** add blocking synchronous calls in the SysUI interface between Shell & SysUI
  - Try adding a push-mechanism to share data, or an async callback to request data

### Exposing shared code for use in Launcher
Launcher doesn't currently build against the Shell library, but needs to have access to some shared
AIDL interfaces and constants.  Currently, all AIDL files, and classes under the
`com.android.wm.shell.util` package are automatically built into the `SystemUISharedLib` that
Launcher uses.

If the new code doesn't fall into those categories, they can be added explicitly in the Shell's
[Android.bp](frameworks/base/libs/WindowManager/Shell/Android.bp) file under the
`wm_shell_util-sources` filegroup.