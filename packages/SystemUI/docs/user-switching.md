# User Switching

Multiple users and the ability to switch between them is controlled by Settings -> System -> Multiple Users.

## Entry Points

### Quick Settings

In the QS footer, an icon becomes available for users to tap on. The view and its onClick actions are handled by [MultiUserSwitchController][2]. Multiple visual implementations are currently in use; one for phones/foldables ([UserSwitchDialogController][6]) and one for tablets ([UserSwitcherActivity][5]).

### Bouncer

May allow changing or adding new users directly from they bouncer. See [KeyguardBouncer][1]

### Keyguard affordance

[KeyguardQsUserSwitchController][4]

## Components

All visual implementations should derive their logic and use the adapter specified in:

### [UserSwitcherController][3]

* Contains the current list of all system users
* Listens for relevant events and broadcasts to make sure this list stays up to date
* Manages user switching and dialogs for exiting from guest users
* Is settings aware regarding adding users from the lockscreen

## Visual Components

### [UserSwitcherActivity][5]

A fullscreen user switching activity, supporting add guest/user actions if configured.

### [UserSwitchDialogController][6]

Renders user switching as a dialog over the current surface, and supports add guest user/actions if configured.

[1]: /frameworks/base/packages/SystemUI/docs/device-entry/bouncer.md
[2]: /frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/phone/MultiUserController.java
[3]: /frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/policy/UserSwitcherController.java
[4]: /frameworks/base/packages/SystemUI/src/com/android/systemui/statusbar/policy/KeyguardQsUserSwitchController.java
[5]: /frameworks/base/packages/SystemUI/src/com/android/systemui/user/UserSwitcherActivity.kt
[6]: /frameworks/base/packages/SystemUI/src/com/android/systemui/qs/user/UserSwitchDialogController.kt
