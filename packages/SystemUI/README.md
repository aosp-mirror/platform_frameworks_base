# SystemUI

“Everything you see in Android that's not an app”

SystemUI is a persistent process that provides UI for the system but outside
of the system_server process.

Inputs directed at sysui (as opposed to general listeners) generally come in
through IStatusBar. Outputs from sysui are through a variety of private APIs to
the android platform all over.

## SystemUIApplication

When SystemUIApplication starts up, it instantiates a Dagger graph from which
various pieces of the application are built.

To support customization, SystemUIApplication relies on the AndroidManifest.xml
having an `android.app.AppComponentFactory` specified. Specifically, it relies
on an `AppComponentFactory` that subclases `SystemUIAppComponentFactoryBase`.
Implementations of this abstract base class must override
`#createSystemUIInitializer(Context)` which returns a `SystemUIInitializer`.
`SystemUIInitializer` primary job in turn is to intialize and return the Dagger
root component back to the `SystemUIApplication`.

Writing a custom `SystemUIAppComponentFactoryBase` and `SystemUIInitializer`,
should be enough for most implementations to stand up a customized Dagger
graph, and launch a custom version of SystemUI.

## Dagger / Dependency Injection

See [dagger.md](docs/dagger.md) and https://dagger.dev/.

## CoreStartable

The starting point for most of SystemUI code is a list of classes that
implement `CoreStartable` that are started up by SystemUIApplication.
CoreStartables are like miniature services. They have their `#start` method
called after being instantiated, and a reference to them is stored inside
SystemUIApplication. They are in charge of their own behavior beyond this,
registering and unregistering with the rest of the system as needed.

`CoreStartable` also receives a callback for `#onBootCompleted`
since these objects may be started before the device has finished booting.

`CoreStartable` is an ideal place to add new features and functionality
that does not belong directly under the umbrella of an existing feature.
It is better to define a new `CoreStartable` than to stick unrelated
initialization code together in catch-all methods.

CoreStartables are tied to application startup via Dagger:

```kotlin
class FeatureStartable
@Inject
constructor(
    /* ... */
) : CoreStartable {
    override fun start() {
        // ...
    }
}

@Module
abstract class FeatureModule {
    @Binds
    @IntoMap
    @ClassKey(FeatureStartable::class)
    abstract fun bind(impl: FeatureStartable): CoreStartable
}
```

Including `FeatureModule` in the Dagger graph such as this will ensure that
`FeatureStartable` gets constructed and that its `#start` method is called.

## IStatusBar

CommandQueue is the object that receives all of the incoming events from the
system_server. It extends IStatusBar and dispatches those callbacks back any
number of listeners. The system_server gets a hold of the IStatusBar when
StatusBar calls IStatusBarService#registerStatusBar, so if StatusBar is not
included in the XML service list, it will not be registered with the OS.

CommandQueue posts all incoming callbacks to a handler and then dispatches
those messages to each callback that is currently registered. CommandQueue
also tracks the current value of disable flags and will call #disable
immediately for any callbacks added.

There are a few places where CommandQueue is used as a bus to communicate
across sysui. Such as when StatusBar calls CommandQueue#recomputeDisableFlags.
This is generally used a shortcut to directly trigger CommandQueue rather than
calling StatusManager and waiting for the call to come back to IStatusBar.

### [com.android.systemui.util.NotificationChannels](/packages/SystemUI/src/com/android/systemui/util/NotificationChannels.java)

Creates/initializes the channels sysui uses when posting notifications.

### [com.android.systemui.keyguard.KeyguardViewMediator](/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java)

Manages keyguard view state.

### [com.android.systemui.recents.Recents](/packages/SystemUI/src/com/android/systemui/recents/Recents.java)

Recents tracks all the data needed for recents and starts/stops the recents
activity. It provides this cached data to RecentsActivity when it is started.

### [com.android.systemui.volume.VolumeUI](/packages/SystemUI/src/com/android/systemui/volume/VolumeUI.java)

Registers all the callbacks/listeners required to show the Volume dialog when
it should be shown.

### [com.android.systemui.status.phone.CentralSurfaces](/packages/SystemUI/src/com/android/systemui/status/phone/CentralSurfaces.java)

This shows the UI for the status bar and the notification shade it contains.
It also contains a significant amount of other UI that interacts with these
surfaces (keyguard, AOD, etc.). CentralSurfaces also contains a notification listener
to receive notification callbacks.

### [com.android.systemui.usb.StorageNotification](/packages/SystemUI/src/com/android/systemui/usb/StorageNotification.java)

Tracks USB status and sends notifications for it.

### [com.android.systemui.power.PowerUI](/packages/SystemUI/src/com/android/systemui/power/PowerUI.java)

Tracks power status and sends notifications for low battery/power saver.

### [com.android.systemui.media.RingtonePlayer](/packages/SystemUI/src/com/android/systemui/media/RingtonePlayer.java)

Plays ringtones.

### [com.android.systemui.keyboard.KeyboardUI](/packages/SystemUI/src/com/android/systemui/keyboard/KeyboardUI.java)

Shows UI for keyboard shortcuts (triggered by keyboard shortcut).

### [com.android.systemui.shortcut.ShortcutKeyDispatcher](/packages/SystemUI/src/com/android/systemui/shortcut/ShortcutKeyDispatcher.java)

Dispatches shortcut to System UI components.

### @string/config_systemUIVendorServiceComponent

Component allowing the vendor/OEM to inject a custom component.

### [com.android.systemui.util.leak.GarbageMonitor$Service](/packages/SystemUI/src/com/android/systemui/util/leak/GarbageMonitor.java)

Tracks large objects in sysui to see if there are leaks.

### [com.android.systemui.LatencyTester](/packages/SystemUI/src/com/android/systemui/LatencyTester.java)

Class that only runs on debuggable builds that listens to broadcasts that
simulate actions in the system that are used for testing the latency.

### [com.android.systemui.globalactions.GlobalActionsComponent](/packages/SystemUI/src/com/android/systemui/globalactions/GlobalActionsComponent.java)

Shows the global actions dialog (long-press power).

### [com.android.systemui.ScreenDecorations](/packages/SystemUI/src/com/android/systemui/ScreenDecorations.java)

Draws decorations about the screen in software (e.g. rounded corners, cutouts).

### [com.android.systemui.biometrics.BiometricDialogImpl](/packages/SystemUI/src/com/android/systemui/biometrics/BiometricDialogImpl.java)

Biometric UI.

### [com.android.systemui.wmshell.WMShell](/packages/SystemUI/src/com/android/systemui/wmshell/WMShell.java)

Delegates SysUI events to WM Shell controllers.

---

 * [Plugins](/packages/SystemUI/docs/plugins.md)
 * [Demo Mode](/packages/SystemUI/docs/demo_mode.md)
