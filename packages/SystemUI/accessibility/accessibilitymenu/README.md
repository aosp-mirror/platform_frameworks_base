The Accessibility Menu is an accessibility service
that presents a large on-screen menu to control your Android device.
This service can be enabled from the Accessibility page in the Settings app.
You can control gestures, hardware buttons, navigation, and more. From the menu, you can:

- Take screenshots
- Lock your screen
- Open the device's voice assistant
- Open Quick Settings and Notifications
- Turn volume up or down
- Turn brightness up or down

The UI consists of a `ViewPager` populated by multiple pages of shortcut buttons.
In the settings for the menu, there is an option to display the buttons in a 3x3 grid per page,
or a 2x2 grid with larger buttons.

Upon activation, most buttons will close the menu while performing their function.
The exception to this are buttons that adjust a value, like volume or brightness,
where the user is likely to want to press the button multiple times.
In addition, touching other parts of the screen or locking the phone through other means
should dismiss the menu.

A majority of the shortcuts correspond directly to an existing accessibility service global action
(see `AccessibilityService#performGlobalAction()` constants) that is performed when pressed.
Shortcuts that navigate to a different menu, such as Quick Settings, use an intent to do so.
Shortcuts that adjust brightness or volume interface directly with
`DisplayManager` & `AudioManager` respectively.

To add a new shortcut:

1. Add a value for the new shortcut to the `ShortcutId` enum in `A11yMenuShortcut`.
2. Put an entry for the enum value into the `sShortcutResource` `HashMap` in `A11yMenuShortcut`.
This will require resources for a drawable icon, a color for the icon,
the displayed name of the shortcut and the desired text-to-speech output.
3. Add the enum value to the `SHORTCUT_LIST_DEFAULT` & `LARGE_SHORTCUT_LIST_DEFAULT` arrays
in `A11yMenuOverlayLayout`.
4. For functionality, add a code block to the if-else chain in
`AccessibilityMenuService.handleClick()`, detailing the effect of the shortcut.
If you don't want the shortcut to close the menu,
include a return statement at the end of the code block.
