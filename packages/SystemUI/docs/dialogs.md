# Dialogs in SystemUI

## Creating a dialog

### Styling

In order to have uniform styling in dialogs, use [SystemUIDialog][1] with its default theme.
If not possible, use [AlertDialog][2] with the SystemUI theme `R.style.Theme_SystemUI_Dialog`.
If needed, consider extending this theme instead of creating a new one.

### Setting the internal elements

The internal elements of the dialog are laid out using the following resources:

* [@layout/alert_dialog_systemui][3]
* [@layout/alert_dialog_title_systemui][4]
* [@layout/alert_dialog_button_bar_systemui][5]

Use the default components of the layout by calling the appropriate setters (in the dialog or
[AlertDialog.Builder][2]). The supported styled setters are:

* `setIcon`: tinted using `attr/colorAccentPrimaryVariant`.
* `setTitle`: this will use `R.style.TextAppearance_Dialog_Title`.
* `setMessage`: this will use `R.style.TextAppearance_Dialog_Body_Message`.
* `SystemUIDialog.set<Positive|Negative|Neutral>Button` or `AlertDialog.setButton`: this will use
   the following styles for buttons.
  * `R.style.Widget_Dialog_Button` for the positive button.
  * `R.style.Widget_Dialog_Button_BorderButton` for the negative and neutral buttons.

  If needed to use the same style for all three buttons, the style attributes
  `?android:attr/buttonBar<Positive|NegativeNeutral>Button` can be overriden in a theme that extends
  from `R.style.Theme_SystemUI_Dialog`.
* `setView`: to set a custom view in the dialog instead of using `setMessage`.

Using `setContentView` is discouraged as this replaces the content completely.

All these calls should be made before `Dialog#create` or `Dialog#show` (which internally calls
`create`) are called, as that's when the content is installed.

## Showing the dialog

When showing a dialog triggered by clicking on a `View`, you should use [DialogLaunchAnimator][6] to
nicely animate the dialog from/to that `View`, instead of calling `Dialog.show`.

This animator provides the following methods:

* `showFromView`: animates the dialog show from a view , and the dialog dismissal/cancel/hide to the
  same view.
* `showFromDialog`: animates the dialog show from a currently showing dialog, and the dialog
  dismissal/cancel/hide back to that dialog. The originating dialog must have been shown using
  `DialogLaunchAnimator`.
* `dismissStack`: dismisses a stack of dialogs that were launched using `showFromDialog` animating
  the top-most dialog back into the view that was used in the initial `showFromView`.

## Example

Here's a short code snippet with an example on how to use the guidelines.

```kotlin
val dialog = SystemUIDialog(context).apply {
    setIcon(R.drawable.my_icon)
    setTitle(context.getString(R.string.title))
    setMessage(context.getString(R.string.message))
    // Alternatively to setMessage:
    // val content = LayoutManager.from(context).inflate(R.layout.content, null)
    // setView(content)
    setPositiveButton(R.string.positive_button_text, ::onPositiveButton)
    setNegativeButton(R.string.negative_button_text, ::onNegativeButton)
    setNeutralButton(R.string.neutral_button_text, ::onNeutralButton)
}
dialogLaunchAnimator.showFromView(dialog, view)
```

[1]: /packages/SystemUI/src/com/android/systemui/statusbar/phone/SystemUIDialog.java
[2]: /core/java/android/app/AlertDialog.java
[3]: /packages/SystemUI/res/layout/alert_dialog_systemui.xml
[4]: /packages/SystemUI/res/layout/alert_dialog_title_systemui.xml
[5]: /packages/SystemUI/res/layout/alert_dialog_button_bar_systemui.xml
[6]: /packages/SystemUI/animation/src/com/android/systemui/animation/DialogLaunchAnimator.kt