# Bouncer

[KeyguardBouncer][1] is the component responsible for displaying the security method set by the user (password, PIN, pattern) as well as SIM-related security methods, allowing the user to unlock the device or SIM.

![ss-bouncer](./imgs/bouncer_pin.png)

## Supported States

1. Phone, portrait mode - The default and typically only way to view the bouncer. Screen cannot rotate.
1. Phone, landscape - Can only get into this state via lockscreen activities. Launch camera, rotate to landscape, tap lock icon is one example.
1. Foldables - Both landscape and portrait are supported. In landscape, the bouncer can appear on either of the hinge and can be dragged to the other side. Also refered to as "OneHandedMode in [KeyguardSecurityContainerController][3]
1. Tablets - The bouncer is supplemented with user icons and a multi-user switcher, when available.

## Components

The bouncer contains a hierarchy of controllers/views to render the user's security method and to manage the authentication attempts.

1. [KeyguardBouncer][1] - Entrypoint for managing the bouncer visibility.
    1. [KeyguardHostViewController][2] - Intercepts media keys. Can most likely be merged with the next item.
        1. [KeyguardSecurityContainerController][3] - Manages unlock attempt responses, determines the correct security view layout, which may include a user switcher or enable one-handed use.
            1. [KeyguardSecurityViewFlipperController][4] - Based upon the [KeyguardSecurityModel#SecurityMode][5], will instantiate the required view and controller. PIN, Pattern, etc.

Fun fact: Naming comes from the concept of a bouncer at a bar or nightclub, who prevent troublemakers from entering or eject them from the premises.

[1]: /frameworks/base/packages/SystemUI/com/android/systemui/statusbar/phone/KeyguardBouncer
[2]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardHostViewController
[3]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardSecurityContainerController
[4]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardSecurityViewFlipperController
[5]: /frameworks/base/packages/SystemUI/com/android/keyguard/KeyguardSecurityModel
