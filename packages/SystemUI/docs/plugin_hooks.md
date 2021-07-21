# Plugin hooks
### Action: com.android.systemui.action.PLUGIN_OVERLAY
Expected interface: [OverlayPlugin](/frameworks/base/packages/SystemUI/plugin/src/com/android
/systemui/plugins/OverlayPlugin.java)

Use: Allows plugin access to the status bar and nav bar window for whatever nefarious purposes you can imagine.

### Action: com.android.systemui.action.PLUGIN_QS
Expected interface: [QS](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/qs/QS.java)

Use: Allows the entire QS panel to be replaced with something else that is optionally expandable.

Notes: To not mess up the notification panel interaction, much of the QSContainer interface needs to actually be implemented.

### Action: com.android.systemui.action.PLUGIN_QS_FACTORY
Expected interface: [QSFactory](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/qs/QSFactory.java)

Use: Controls the creation of QS Tiles and their views, can used to add or change system QS tiles, can also be used to change the layout/interaction of the tile views.

### Action: com.android.systemui.action.PLUGIN_NAV_BUTTON
Expected interface: [NavBarButtonProvider](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/statusbar/phone/NavBarButtonProvider.java)

Use: Allows a plugin to create a new nav bar button, or override an existing one with a view of its own.

### Action: com.android.systemui.action.PLUGIN_NAV_GESTURE
Expected interface: [NavGesture](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/statusbar/phone/NavGesture.java)

Use: Allows touch events from the nav bar to be intercepted and used for other gestures.

### Action: com.android.systemui.action.PLUGIN_LOCKSCREEN_RIGHT_BUTTON
Expected interface: [IntentButtonProvider](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/IntentButtonProvider.java)

Use: Allows a plugin to specify the icon for the bottom right lock screen button, and the intent that gets launched when it is activated.

### Action: com.android.systemui.action.PLUGIN_LOCKSCREEN_LEFT_BUTTON
Expected interface: [IntentButtonProvider](/packages/SystemUI/plugin/src/com/android/systemui/plugins/IntentButtonProvider.java)

Use: Allows a plugin to specify the icon for the bottom left lock screen button, and the intent that gets launched when it is activated.

### Action: com.android.systemui.action.PLUGIN_GLOBAL_ACTIONS
Expected interface: [GlobalActions](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/GlobalActions.java)

Use: Allows the long-press power menu to be completely replaced.

### Action: com.android.systemui.action.PLUGIN_VOLUME
Expected interface: [VolumeDialog](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/VolumeDialog.java)

Use: Allows replacement of the volume dialog.

### Action: com.android.systemui.action.PLUGIN_NOTIFICATION_SWIPE_ACTION
Expected interface: [NotificationSwipeActionHelper](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/statusbar/NotificationSwipeActionHelper.java)

Use: Control over swipes/input for notification views, can be used to control what happens when you swipe/long-press

### Action: com.android.systemui.action.PLUGIN_CLOCK
Expected interface: [ClockPlugin](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/ClockPlugin.java)

Use: Allows replacement of the keyguard main clock.

### Action: com.android.systemui.action.PLUGIN_TOAST
Expected interface: [ToastPlugin](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/ToastPlugin.java)

Use: Allows replacement of uncustomized toasts created via Toast.makeText().

# Global plugin dependencies
These classes can be accessed by any plugin using PluginDependency as long as they @Requires them.

[VolumeDialogController](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/VolumeDialogController.java) - Mostly just API for the volume plugin

[ActivityStarter](/frameworks/base/packages/SystemUI/plugin/src/com/android/systemui/plugins/ActivityStarter.java) - Allows starting of intents while co-operating with keyguard unlocks.
