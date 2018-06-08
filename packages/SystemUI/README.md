# SystemUI

“Everything you see in Android that's not an app”

SystemUI is a persistent process that provides UI for the system but outside
of the system_server process.

The starting point for most of sysui code is a list of services that extend
SystemUI that are started up by SystemUIApplication. These services then depend
on some custom dependency injection provided by Dependency.

Inputs directed at sysui (as opposed to general listeners) generally come in
through IStatusBar. Outputs from sysui are through a variety of private APIs to
the android platform all over.

## SystemUIApplication

When SystemUIApplication starts up, it will start up the services listed in
config_systemUIServiceComponents or config_systemUIServiceComponentsPerUser.

Each of these services extend SystemUI. SystemUI provides them with a Context
and gives them callbacks for onConfigurationChanged (this historically was
the main path for onConfigurationChanged, now also happens through
ConfigurationController). They also receive a callback for onBootCompleted
since these objects may be started before the device has finished booting.

SystemUI and SystemUIApplication also have methods for putComponent and
getComponent which were existing systems to get a hold of other parts of
sysui before Dependency existed. Generally new things should not be added
to putComponent, instead Dependency and other refactoring is preferred to
make sysui structure cleaner.

Each SystemUI service is expected to be a major part of system ui and the
goal is to minimize communication between them. So in general they should be
relatively silo'd.

## Dependencies

The first SystemUI service that is started should always be Dependency.
Dependency provides a static method for getting a hold of dependencies that
have a lifecycle that spans sysui. Dependency has code for how to create all
dependencies manually added. SystemUIFactory is also capable of
adding/replacing these dependencies.

Dependencies are lazily initialized, so if a Dependency is never referenced at
runtime, it will never be created.

If an instantiated dependency implements Dumpable it will be included in dumps
of sysui (and bug reports), allowing it to include current state information.
This is how \*Controllers dump state to bug reports.

If an instantiated dependency implements ConfigurationChangeReceiver it will
receive onConfigurationChange callbacks when the configuration changes.

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

## Default SystemUI services list

### [com.android.systemui.Dependency](/packages/SystemUI/src/com/android/systemui/Dependency.java)

Provides custom dependency injection.

### [com.android.systemui.util.NotificationChannels](/packages/SystemUI/src/com/android/systemui/util/NotificationChannels.java)

Creates/initializes the channels sysui uses when posting notifications.

### [com.android.systemui.statusbar.CommandQueue$CommandQueueStart](/packages/SystemUI/src/com/android/systemui/sstatusbar/CommandQueue.java)

Creates CommandQueue and calls putComponent because its always been there
and sysui expects it to be there :/

### [com.android.systemui.keyguard.KeyguardViewMediator](/packages/SystemUI/src/com/android/systemui/keyguard/KeyguardViewMediator.java)

Manages keyguard view state.

### [com.android.systemui.recents.Recents](/packages/SystemUI/src/com/android/systemui/recents/Recents.java)

Recents tracks all the data needed for recents and starts/stops the recents
activity. It provides this cached data to RecentsActivity when it is started.

### [com.android.systemui.volume.VolumeUI](/packages/SystemUI/src/com/android/systemui/volume/VolumeUI.java)

Registers all the callbacks/listeners required to show the Volume dialog when
it should be shown.

### [com.android.systemui.stackdivider.Divider](/packages/SystemUI/src/com/android/systemui/stackdivider/Divider.java)

Shows the drag handle for the divider between two apps when in split screen
mode.

### [com.android.systemui.SystemBars](/packages/SystemUI/src/com/android/systemui/SystemBars.java)

This is a proxy to the actual SystemUI for the status bar. This loads from
config_statusBarComponent which defaults to StatusBar. (maybe this should be
removed and copy how config_systemUiVendorServiceComponent works)

### [com.android.systemui.status.phone.StatusBar](/packages/SystemUI/src/com/android/systemui/status/phone/StatusBar.java)

This shows the UI for the status bar and the notification shade it contains.
It also contains a significant amount of other UI that interacts with these
surfaces (keyguard, AOD, etc.). StatusBar also contains a notification listener
to receive notification callbacks.

### [com.android.systemui.usb.StorageNotification](/packages/SystemUI/src/com/android/systemui/usb/StorageNotification.java)

Tracks USB status and sends notifications for it.

### [com.android.systemui.power.PowerUI](/packages/SystemUI/src/com/android/systemui/power/PowerUI.java)

Tracks power status and sends notifications for low battery/power saver.

### [com.android.systemui.media.RingtonePlayer](/packages/SystemUI/src/com/android/systemui/media/RingtonePlayer.java)

Plays ringtones.

### [com.android.systemui.keyboard.KeyboardUI](/packages/SystemUI/src/com/android/systemui/keyboard/KeyboardUI.java)

Shows UI for keyboard shortcuts (triggered by keyboard shortcut).

### [com.android.systemui.pip.PipUI](/packages/SystemUI/src/com/android/systemui/pip/PipUI.java)

Shows the overlay controls when Pip is showing.

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

### [com.android.systemui.fingerprint.FingerprintDialogImpl](/packages/SystemUI/src/com/android/systemui/fingerprint/FingerprintDialogImpl.java)

Fingerprint UI.

---

 * [Plugins](/packages/SystemUI/docs/plugins.md)
 * [Demo Mode](/packages/SystemUI/docs/demo_mode.md)
