# Keyguard Quick Affordances
Quick Affordances are interactive UI elements that appear on the lock screen when the device is
locked. They allow the user to perform quick actions without necessarily unlocking their device. For example:
opening an screen that lets them control the smart devices in their home, access their touch-to-pay
credit card, turn on the flashlight, etc.

## Creating a new Quick Affordance
All Quick Affordances are defined in System UI code.

To implement a new Quick Affordance, a developer may add a new implementation of `KeyguardQuickAffordanceConfig` in the `packages/SystemUI/src/com/android/systemui/keyguard/data/quickaffordance` package/directory and add it to the set defined in `KeyguardDataQuickAffordanceModule`.

Tests belong in the `packages/SystemUI/tests/src/com/android/systemui/keyguard/data/quickaffordance` package. This should be enough for the system to pick up the new config and make it available for selection by the user.

## Slots
"Slots" is what we call the position of Quick Affordances on the lock screen. Each slot has a unique ID and a capacity denoting how many Quick Affordances can be "placed" in that slot.

By default, AOSP ships with a "bottom right" and a "bottom left" slot, each with a slot capacity of `1`, allowing only one Quick Affordance on each side of the lock screen.

### Customizing Slots
OEMs may choose to override the IDs and number of slots and/or override the default capacities. This can be achieved by overridding the `config_keyguardQuickAffordanceSlots` resource in `packages/SystemUI/res/values/config.xml`.

### Default Quick Affordances
OEMs may also choose to predefine default Quick Affordances for each slot. To achieve this, a developer may override the `config_keyguardQuickAffordanceDefaults` resource in `packages/SystemUI/res/values/config.xml`. Note that defaults only work until the user of the device selects a different quick affordance for that slot, even if they select the "None" option.

## Selections
"Selections" are many-to-many relationships between slots and quick affordances. We add a selection when the user selects a quick affordance for a specific slot. We remove a selection when the user un-selects a quick affordance in a slot or when the user selects an additional quick affordance for a slot that is already at capacity. The definition of each slot tells us the maximum number of quick affordances that may be selected for each slot.

## Building a Quick affordance Picker Experience
This section describes how to implement a potential picker, selector, or configuration experience for quick affordances.

### Accessing Quick Affordance Data
Quick Affordances structured data are exposed to other applications through the `KeyguardQuickAffordanceProvider` content provider which is owned by the System UI process.

To access this content provider, applications must have the `android.permission.CUSTOMIZE_SYSTEM_UI` permission which is a signature and privileged permission, limiting access to system apps or apps signed by the same signature as System UI. The `KeyguardQuickAffordanceProviderContract` file defines the content provider schema for consumers.

Generally speaking, there are three important tables served by the content provider: `slots`, `affordances`, and `selections`. There is also a `flags` table, but that's not important and may be ignored.

The `slots`, `affordances`, and `selections` tables may be queried using their `Uri` resulting with a `Cursor` where each row represents a slot, affordance, or selection, respectively. Note that the affordance list does not include the "None" option.

### Modifying Quick Affordance Data
The `selections` table accepts `insert` or `delete` operations to either add or remove a quick affordance on a slot.
* To add a selection of a quick affordance on a slot, execute the `insert` operation on the `selections` table `Uri` and include the `slot_id` of the slot and `affordance_id` of the affordance, both in the `ContentValues`
* To remove a selection of a specific quick affordance from a slot, execute the `delete` operation on the `selections` table `Uri` and include the `slot_id` of the slot and the `affordance_id` of the affordance to remove as the first and second selection arguments, respectively
* To remove all selections of any currently-selected quick affordance from a specific slot, repeat the above, but omit the `affordance_id`

### The Picker Experience
A picker experience may:
* Show the list of available slots based on the result of the `slots` table query
* Show the list of available quick affordances on the device (regardless of selection) based on the result of the `affordances` table query
* Show the quick affordances already selected for each slot based on the result of the `selections` table query
* Select one quick affordance per slot at a time
* Unselect an already-selected quick affordance from a slot
* Unselect all already-selected quick affordances from a slot

## Debugging
To see the current state of the system, you can run `dumpsys`:

```
$ adb shell dumpsys activity service com.android.systemui/.SystemUIService KeyguardQuickAffordances
```

The output will spell out the current slot configuration, selections, and collection of available affordances, for example:
```
    KeyguardQuickAffordances:
    ----------------------------------------------------------------------------
    Slots & selections:
        bottom_start: home (capacity = 1)
        bottom_end is empty (capacity = 1)
    Available affordances on device:
        home ("Home")
        wallet ("Wallet")
        qr_code_scanner ("QR code scanner")
```
