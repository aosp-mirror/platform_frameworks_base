# Bouncer

[KeyguardBouncer][1] is the component responsible for displaying the security method set by the user (password, PIN, pattern) as well as SIM-related security methods, allowing the user to unlock the device or SIM.

## Components

The bouncer contains a hierarchy of controllers/views to render the user's security method and to manage the authentication attempts.

1. [KeyguardBouncer][1] - Entrypoint for managing the bouncer visibility.
    1. [KeyguardHostViewController][2] - Intercepts media keys. Can most likely be merged with the next item.
        1. [KeyguardSecurityContainerController][3] - Manages unlock attempt responses, one-handed use
            1. [KeyguardSecurityViewFlipperController][4] - Based upon the [KeyguardSecurityModel#SecurityMode][5], will instantiate the required view and controller. PIN, Pattern, etc.

[1]: /frameworks/base/packages/SystemUI/com/android/systemui/statusbar/phone/KeyguardBouncer
[2]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardHostViewController
[3]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardSecurityContainerController
[4]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardSecurityViewFlipperController
[5]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardSecurityModel
