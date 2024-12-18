# The Scene Framework

Known internally as "Flexiglass", this framework defines a graph where each node
is a "scene" and each edge between the scenes is a transition. The scenes are
the main components of System UI, on phones these are: the lockscreen, bouncer,
shade, and quick settings panels/views/screens). Each scene is a standalone
experience.

The **main goal** of the framework is to increase code health by applying
[Separation of concerns](https://en.wikipedia.org/wiki/Separation_of_concerns)
over several dimensions:

1.  Each scene is a standalone piece of UI; their code doesn't need to concern
    itself with either transition animations or anything in other scenes. This
    frees the developer to be able to focus only on the content of the UI for
    that scene.
2.  Transition definitions (which scene leads to which other scene following
    which user action) are pulled out and separated from the content of the UI.
3.  Transition animations (the effects that happen alongside the gradual change
    from one scene to another) are also pulled out and separated from the
    content of the UI.

In addition to the above, some of the **secondary goals** are:

4. Make **customization easier**: by separating scenes to standalone pieces, it
becomes possible for variant owners and OEMs to exclude or replace certain scenes
or to add brand-new scenes.
5. **Enable modularization**: by separating scenes to standalone pieces, it
becomes possible to break down System UI into smaller codebases, each one of
which could be built on its own. Note: this isn't part of the scene framework
itself but is something that can be done more easily once the scene framework
is in place.

## Terminology

*   **Scene** a collection of UI elements in a layout that, together, make up a
    "screen" or "page" that is as large as the container. Scenes can be
    navigated between / transition to/from. To learn more, please see
    [this section](#Defining-a-scene).
*   **Element** (or "UI element") a single unit of UI within a scene. One scene
    can arrange multiple elements within a layout structure.
*   **Transition** the gradual switching from one scene to another scene. There
    are two kinds: [user-driven](Scene-navigation) and
    [automatic](Automatic-scene-transitions) scene transitions.
*   **Transition animation** the set of UI effects that occurs while/during a
    transition. These can apply to the entire scene or to specific elements in
    the scene. To learn more, please see
    [this section](#Scene-transition-animations).
*   **Scene container** (or just "container") the root piece of UI (typically a
    `@Composable` function) that sets up all the scenes, their transitions, etc.
    To learn more, please see [this section](#Scene-container).
*   **Container configuration** (or just "configuration") the collection of
    scenes and some added information about the desired behaviour of a
    container. To learn more, please see
    [this section](#Scene-container-configuration).

## Enabling the framework

As of the end of 2023, the scene framework is under development; as such, it is
disabled by default. For those who are interested in a preview, please follow
the instructions below to turn it on.

NOTE: in case these instructions become stale and don't actually enable the
framework, please make sure `SceneContainerFlag.isEnabled` in the
[`SceneContainerFlag.kt`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/shared/flag/SceneContainerFlag.kt)
file evalutes to `true`.

1.  Set a collection of **aconfig flags** to `true` by running the following
    commands:
    ```console
    $ adb shell device_config override systemui com.android.systemui.keyguard_bottom_area_refactor true
    $ adb shell device_config override systemui com.android.systemui.keyguard_wm_state_refactor true
    $ adb shell device_config override systemui com.android.systemui.migrate_clocks_to_blueprint true
    $ adb shell device_config override systemui com.android.systemui.notification_avalanche_throttle_hun true
    $ adb shell device_config override systemui com.android.systemui.predictive_back_sysui true
    $ adb shell device_config override systemui com.android.systemui.device_entry_udfps_refactor true
    $ adb shell device_config override systemui com.android.systemui.scene_container true
    ```
2.  **Restart** System UI by issuing the following command:
    ```console
    $ adb shell am crash com.android.systemui
    ```
3.  **Verify** that the scene framework was turned on. There are two ways to do
    this:

    *(a)* look for the sash/ribbon UI at the bottom-right corner of the display:
    ![ribbon](imgs/ribbon.png)

    NOTE: this will be removed proper to the actual release of the framework.

    *(b)* Turn on logging and look for the logging statements in `logcat`:
    ```console

    # Turn on logging from the framework:

    $ adb shell cmd statusbar echo -b SceneFramework:verbose

### Checking if the framework is enabled

Look for the log statements from the framework:

```console
$ adb logcat -v time SceneFramework:* *:S
```

### Disabling the framework

To **disable** the framework, simply turn off the main aconfig flag:

```console
$ adb shell device_config put systemui com.android.systemui.scene_container false
```

## Defining a scene

By default, the framework ships with fully functional scenes as enumarated
[here](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/shared/model/SceneKey.kt).
Should a variant owner or OEM want to replace or add a new scene, they could
do so by defining their own scene. This section describes how to do that.

Each scene is defined as an implementation of the
[`Scene`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/features/src/com/android/systemui/scene/ui/composable/Scene.kt)
interface, which has three parts: 1. The `key` property returns the
[`SceneKey`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/shared/model/SceneKey.kt)
that uniquely identifies that scene 2. The `userActions` `Flow` returns
the (potentially ever-changing) set of navigation edges to other content, based
on user-actions, which is how the navigation graph is defined (see
[the Scene navigation](#Scene-navigation) section for more) 3. The `Content`
function which uses
[Jetpack Compose](https://developer.android.com/jetpack/compose) to declare of
the UI itself. This is the UI "at rest", e.g. once there is no transition
between any two scenes. The Scene Framework has other ways to define how the
content of your UI changes with and throughout a transition to learn more please
see the [Scene transition animations](#Scene-transition-animations) section

For example:

```kotlin
@SysUISingleton class YourScene @Inject constructor( /* your dependencies here */ ) : Scene {
    override val key = SceneKey.YourScene

    override val userActions: StateFlow<Map<UserAction, SceneModel>> =
        MutableStateFlow<Map<UserAction, SceneModel>>(
            mapOf(
                // This is where scene navigation is defined, more on that below.
            )
        ).asStateFlow()

    @Composable
    override fun SceneScope.Content(
        modifier: Modifier,
    ) {
        // This is where the UI is defined using Jetpack Compose.
    }
}
```

### Injecting scenes

Scenes are injected into the Dagger dependency graph from the
[`SceneModule`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/facade/enabled/src/com/android/systemui/scene/ui/composable/SceneModule.kt;l=35-50;drc=564f233d5b597aedf06961c76e582464eebe8ba6).

## Scene navigation

As seen above, each scene is responsible for providing an observable `Flow` of a
`Map` that connects `UserAction` (for example: swipe down, swipe up, back
button/gesture, etc.) keys to `SceneModel` destinations. This is how the scene
navigation graph is defined.

NOTE: this controls *only* user-input based navigation. To learn about the other
type of scene navigation, please see the
[Automatic scene transitions](#Automatic-scene-transitions) section.

Because this is a `Flow`, scene implemetations should feel free to emit new
values over time. For example, the `Lockscreen` scene ties the "swipe up" user
action to go to the `Bouncer` scene if the device is still locked or to go to
the `Gone` scene if the device is unlocked, allowing the user to dismiss the
lockscreen UI when not locked.

## Scene transition animations

The Scene Framework separates transition animations from content UI declaration
by placing the definition of the former in a different location. This way,
there's no longer a need to contaminate the content UI declaration with
animation logic, a practice that becomes unscalable over time.

Under the hood, the Scene Framework uses
[`SceneTransitionLayout`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/core/src/com/android/compose/animation/scene/SceneTransitionLayout.kt),
a `@Composable` function designed with scene graph and transitions in mind. In
fact, the Scene Framework is merely a shallow wrapper around
`SceneTransitionLayout`.

The `SceneTransitionLayout` API requires the transitions to be passed-in
separately from the scenes themselves. In System UI, the transitions can be
found in
[`SceneContainerTransitions`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/features/src/com/android/systemui/scene/ui/composable/SceneContainerTransitions.kt).
As you can see, each possible scene-to-scene transition has its own builder,
here's one example:

```kotlin
fun TransitionBuilder.lockscreenToShadeTransition() {
    spec = tween(durationMillis = 500)

    punchHole(Shade.Elements.QuickSettings, bounds = Shade.Elements.Scrim, Shade.Shapes.Scrim)
    translate(Shade.Elements.Scrim, Edge.Top, startsOutsideLayoutBounds = false)
    fractionRange(end = 0.5f) {
        fade(Shade.Elements.ScrimBackground)
        translate(
            QuickSettings.Elements.CollapsedGrid,
            Edge.Top,
            startsOutsideLayoutBounds = false,
        )
    }
    fractionRange(start = 0.5f) { fade(Notifications.Elements.Notifications) }
}
```

Going through the example code:

* The `spec` is the animation that should be invoked, in the example above, we use a `tween`
animation with a duration of 500 milliseconds
* Then there's a series of function calls: `punchHole` applies a clip mask to the `Scrim`
element in the destination scene (in this case it's the `Shade` scene) which has the
position and size determined by the `bounds` parameter and the shape passed into the `shape`
parameter. This lets the `Lockscreen` scene render "through" the `Shade` scene
* The `translate` call shifts the `Scrim` element to/from the `Top` edge of the scene container
* The first `fractionRange` wrapper tells the system to apply its contained functions
only during the first half of the transition. Inside of it, we see a `fade` of
the `ScrimBackground` element and a `translate` o the `CollpasedGrid` element
to/from the `Top` edge
* The second `fractionRange` only starts at the second half of the transition (e.g. when
the previous one ends) and applies a `fade` on the `Notifications` element

You can find the actual documentation for this API
[here](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/core/src/com/android/compose/animation/scene/TransitionDsl.kt).

### Tagging elements

As demonstrated above, elements within a scene can be addressed from transition
defintions. In order to "tag" an element with a specific `ElementKey`, the
[`element` modifier](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/core/src/com/android/compose/animation/scene/SceneTransitionLayout.kt)
must be used on the composable that declared that element's UI:

```kotlin
Text(
    text = "Some text",
    modifier = Modifier.element(MyElements.SomeText),
)
```

In addition to the ability to refer to a tagged element in transition
definitions, if the same `ElementKey` is used for one element in the current
scene and another element in the destination scene, the element is considered to
be a **shared element**. As such, the framework automatically translates and
scales the bounds of the shared element from its current bounds in the source
scene to its final bounds in the destination scene.

## Scene container

To set up a scene framework instance, a scene container must be declared. This
is the root of an entire scene graph that puts together the scenes, their
transitions, and the configuration. The container is then added to a parent
`@Composable` or `View` so it can be displayed.

The default scene container in System UI is defined in the
[`SceneContainer.kt` file](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/features/src/com/android/systemui/scene/ui/composable/SceneContainer.kt).

### Scene container configuration

The `SceneContainer` function is passed a few parameters including a view-model
and a set of scenes. The exact details of what gets passed in depends on the
[`SceneContainerConfig` object](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/shared/model/SceneContainerConfig.kt)
which is injected into the Dagger dependency graph
[here](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/shared/model/SceneContainerConfigModule.kt).

## Automatic scene transitions

The scene framework supports the ability for scenes to change automatically
based on device state or events other than direct user input. For example: when
the device is locked, there's an automatic scene transition to the `Lockscreen`
scene.

This logic is contained within the
[`SceneContainerStartable`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/domain/startable/SceneContainerStartable.kt)
class.

## Side-effects

Similarly to [the above](#Automatic-scene-transitions), the
`SceneContainerStartable` also handles side-effects by updating other parts of
the System UI codebase whenever internal scene framework state changes. As an
example: the visibility of the `View` that contains our
[scene container](#Scene-container) is updated every time there's a transition
to or from the `Gone` scene.

## Observing scene transition state

There are a couple of ways to observe the transition state:

1.  [Easiest] using the `SceneScope` of the scene container, simply use the
    `animateSharedXAsState` API, the full list is
    [here](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/core/src/com/android/compose/animation/scene/AnimateSharedAsState.kt).
2.  [Harder] if outside the `SceneScope` of the scene container, observe
    [`SceneInteractor.transitionState`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/domain/interactor/SceneInteractor.kt;l=88;drc=af57d5e49431c6728e7cf192bada88e0541ebf0c).

## Dependency Injection

The entire framework is provided into the Dagger dependency graph from the
top-level Dagger module at
[`SceneContainerFrameworkModule`](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/src/com/android/systemui/scene/SceneContainerFrameworkModule.kt)
this puts together the scenes from `SceneModule`, the configuration from
`SceneContainerConfigModule`, and the startable from
`SceneContainerStartableModule`.

## Integration Notes

### Relationship to Jetpack Compose

The scene framework depends on Jetpack Compose; therefore, compiling System UI with
Jetpack Compose is required. However, because Jetpack Compose and Android Views
[interoperate](https://developer.android.com/jetpack/compose/migrate/interoperability-apis/views-in-compose),
the UI in each scene doesn't necessarily need to be a pure hierarchy of `@Composable`
functions; instead, it's acceptable to use an `AndroidView` somewhere in the
hierarchy of composable functions to include a `View` or `ViewGroup` subtree.

#### Interoperability with Views
The scene framework comes with built-in functionality to animate the entire scene and/or
elements within the scene in-tandem with the actual scene transition progress.

For example, as the user drags their finger down rom the top of the lockscreen,
the shade scene becomes visible and gradually expands, the amount of expansion tracks
the movement of the finger.

That feature of the framework uses a custom `element(ElementKey)` Jetpack Compose
`Modifier` to refer to elements within a scene.
The transition builders then use the same `ElementKey` objects to refer to those elements
and describe how they animate in-tandem with scene transitions. Because this is a
Jetpack Compose `Modifier`, it means that, in order for an element in a scene to be
animated automatically by the framework, that element must be nested within a pure
`@Composable` hierarchy. The element itself is allowed to be a classic Android `View`
(nested within a Jetpack Compose `AndroidView`) but all ancestors must be `@Composable`
functions.

### Notifications

As of January 2024, the integration of notifications and heads-up notifications (HUNs)
into the scene framework follows an unusual pattern. We chose this pattern due to migration
risk and performance concerns but will eventually replace it with the more common element
placement pattern that all other elements are following.

The special pattern for notifications is that, instead of the notification list
(`NotificationStackScrollLayout` or "NSSL", which also displays HUNs) being placed in the element
hierarchy within the scenes that display notifications, the NSSL (which continues to be an Android View)
"floats" above the scene container, rendering on top of everything. This is very similar to
how NSSL is integrated with the legacy shade, prior to the scene framework.

In order to render the NSSL as if it's part of the organic hierarchy of elements within its
scenes, we control the NSSL's self-imposed effective bounds (e.g. position offsets, clip path,
size) from `@Composable` elements within the normal scene hierarchy. These special
"placeholder" elements can be found
[here](https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/packages/SystemUI/compose/features/src/com/android/systemui/notifications/ui/composable/Notifications.kt).

