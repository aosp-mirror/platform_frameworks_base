# Keyguard Quick Affordances
These are interactive UI elements that appear at the bottom of the lockscreen when the device is
locked. They allow the user to perform quick actions without unlocking their device. For example:
opening an screen that lets them control the smart devices in their home, access their touch-to-pay
credit card, etc.

## Adding a new Quick Affordance
### Step 1: create a new quick affordance config
* Create a new class under the [systemui/keyguard/domain/quickaffordance](../../src/com/android/systemui/keyguard/domain/quickaffordance) directory
* Please make sure that the class is injected through the Dagger dependency injection system by using the `@Inject` annotation on its main constructor and the `@SysUISingleton` annotation at class level, to make sure only one instance of the class is ever instantiated
* Have the class implement the [KeyguardQuickAffordanceConfig](../../src/com/android/systemui/keyguard/domain/quickaffordance/KeyguardQuickAffordanceConfig.kt) interface, notes:
  * The `state` Flow property must emit `State.Hidden` when the feature is not enabled!
  * It is safe to assume that `onQuickAffordanceClicked` will not be invoked if-and-only-if the previous rule is followed
  * When implementing `onQuickAffordanceClicked`, the implementation can do something or it can ask the framework to start an activity using an `Intent` provided by the implementation
* Please include a unit test for your new implementation under [the correct directory](../../tests/src/com/android/systemui/keyguard/domain/quickaffordance)

### Step 2: choose a position and priority
* Add the new class as a dependency in the constructor of [KeyguardQuickAffordanceRegistry](../../src/com/android/systemui/keyguard/domain/quickaffordance/KeyguardQuickAffordanceRegistry.kt)
* Place the new class in one of the available positions in the `configsByPosition` property, note:
  * In each position, there is a list. The order matters. The order of that list is the priority order in which the framework considers each config. The first config whose state property returns `State.Visible` determines the button that is shown for that position
  * Please only add to one position. The framework treats each position individually and there is no good way to prevent the same config from making its button appear in more than one position at the same time

### Step 3: manually verify the new quick affordance
* Build and launch SysUI on a device
* Verify that the quick affordance button for the new implementation is correctly visible and clicking it does the right thing
