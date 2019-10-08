# Physics Animation Layout

## Overview
**PhysicsAnimationLayout** works with an implementation of **PhysicsAnimationController** to construct and maintain physics animations for each of its child views. During the initial construction of the animations, the layout queries the controller for configuration settings such as which properties to animate, which animations to chain together, and what stiffness or bounciness to use. Once the animations are built to the controller’s specifications, the controller can then ask the layout to start, stop and manipulate them arbitrarily to achieve any desired animation effect. The controller is notified whenever children are added or removed from the layout, so that it can animate their entrance or exit, respectively.

An example usage is Bubbles, which uses a PhysicsAnimationLayout for its stack of bubbles. Bubbles has controller subclasses including StackAnimationController and ExpansionAnimationController. StackAnimationController tells the layout to configure the translation animations to be chained (for the ‘following’ drag effect), and has methods such as ```moveStack(x, y)``` to animate the stack to a given point. ExpansionAnimationController asks for no animations to be chained, and exposes methods like ```expandStack()``` and ```collapseStack()```, which animate the bubbles to positions along the bottom of the screen.

## PhysicsAnimationController
PhysicsAnimationController is a public abstract class in PhysicsAnimationLayout. Controller instances must override configuration methods, which are used by the layout while constructing the animations, and animation control methods, which are called to initiate animations in response to events.

### Configuration Methods
![Diagram of how animations are configured using the controller's configuration methods.](physics-animation-layout-config-methods.png)
The controller must override the following methods:

```Set<ViewProperty> getAnimatedProperties()```
Returns the properties, such as TRANSLATION_X and TRANSLATION_Y, for which the layout should construct physics animations.

```int getNextAnimationInChain(ViewProperty property, int index)```
If the animation at the given index should update another animation whenever its value changes, return the index of the other animation. Otherwise, return NONE. This is used to chain animations together, so that when one animation moves, the other ‘follows’ closely behind.

```float getOffsetForChainedPropertyAnimation(ViewProperty property)```
Value to add every time chained animations update the subsequent animation in the chain. For example, returning TRANSLATION_X offset = 20px means that if the first animation in the chain is animated to 10px, the second will update to 30px, the third to 50px, etc.

```SpringForce getSpringForce(ViewProperty property)```
Returns a SpringForce instance to use for animations of the given property. This allows the controller to configure stiffness and bounciness values. Since the physics animations internally use SpringForce instances to hold inflight animation values, this method needs to return a new SpringForce instance each time - no constants allowed.

### Animation Control Methods
Once the layout has used the controller’s configuration properties to build the animations, the controller can use them to actually run animations. This is done for two reasons - reacting to a view being added or removed, or responding to another class (such as a touch handler or broadcast receiver) requesting an animation. ```onChildAdded```, ```onChildRemoved```, and ```setChildVisibility``` are called automatically by the layout, giving the controller the opportunity to animate the child in/out/visible/gone. Custom methods are called by anyone with access to the controller instance to do things like expand, collapse, or move the child views.

In either case, the controller can use `super.animationForChild` to retrieve a `PhysicsPropertyAnimator` instance. This object behaves similarly to the `ViewPropertyAnimator` object you would receive from `View.animate()`. 

#### PhysicsPropertyAnimator

Like `ViewPropertyAnimator`, `PhysicsPropertyAnimator` provides the following methods for animating properties:
- `alpha(float)`
- `translationX/Y/Z(float)`
- `scaleX/Y(float)`

It also provides the following configuration methods:
- `withStartDelay(int)`, for starting the animation after a given delay.
- `withStartVelocity(float)`, for starting the animation with the given start velocity.
- `withPositionStartVelocities(float, float)`, for setting specific start velocities for TRANSLATION_X and TRANSLATION_Y, since these typically differ.
- `start(Runnable)`, to start the animation, with an optional end action to call when the animations for every property (including chained animations) have completed.

For example, moving the first child view:

```
animationForChild(getChildAt(0))
    .translationX(100)
    .translationY(200)
    .setStartDelay(500)
    .start();
```

This would use the physics animations constructed by the layout to spring the view to *(100, 200)* after 500ms.

If the controller’s ```getNextAnimationInChain``` method set up the first child’s TRANSLATION_X/Y animations to be chained to the second child’s, this would result in the second child also springing towards (100, 200), plus any offset returned by ```getOffsetForChainedPropertyAnimation```.

##### Advanced Usage
The animator has additional functionality to reduce the amount of boilerplate required for typical physics animation use cases.

- Often, animations will set starting values for properties before the animation begins. Property methods like `translationX` have an overloaded variant: `translationX(from, to)`. When `start()` is called, the animation will set the view's translationX property to `from` before beginning the animation to `to`.
- We may want to use different end actions for each property. For example, if we're animating a view to the bottom of the screen, and also fading it out, we might want to perform an action as soon as the fade out is complete. We can use `alpha(to, endAction)`, which will call endAction as soon as the alpha animation is finished. A special case is `position(x, y, endAction)`, where the endAction is called when both translationX and translationY animations have completed.

`PhysicsAnimationController` also provides `animationsForChildrenFromIndex(int, ChildAnimationConfigurator)`. This is a convenience method for starting animations on multiple child views, starting at the given index. The `ChildAnimationConfigurator` is called with a `PhysicsPropertyAnimator` for each child, where calls to methods like `translationX` and `withStartVelocity` can be made. `animationsForChildrenFromIndex` returns a `MultiAnimationStarter` with a single method, `startAll(endAction)`, which starts all of the animations and calls the end action when they have all completed.

##### Examples
Spring the stack of bubbles (whose animations are chained) to the bottom of the screen, shrinking them to 50% size. Once the first bubble is done shrinking, begin fading them out, and then remove them all from the parent once all bubbles have faded out:

```
animationForChild(leadBubble)
    .position(screenCenter, screenBottom)
    .scaleX(0.5f)
    .scaleY(0.5f, () -> animationForChild(leadBubble).alpha(0).start(removeAllFromParent))
    .start();
```

'Drop in' a child view that was just added to the layout:

```
animationForChild(newView)
    .scaleX(1.15f /* from */, 1f /* to */)
    .scaleY(1.15f /* from */, 1f /* to */)
    .alpha(0f /* from */, 1f /* to */)
    .position(posX, posY)
    .start();
```

Move every view except for the first to x = (index - 1) * 50, then remove the first view.

```
animationsForChildrenFromIndex(1, (index, anim) -> anim.translationX((index - 1) * 50))
    .startAll(removeFirstView);
```

## PhysicsAnimationLayout
The layout itself is a FrameLayout descendant with a few extra methods:

```setController(PhysicsAnimationController controller)```
Attaches the layout to the controller, so that the controller can access the layout’s protected methods. It also constructs or reconfigures the physics animations according to the new controller’s configuration methods.

```setEndListenerForProperty(ViewProperty property, AnimationEndListener endListener)```
Sets an end listener that is called when all animations on the given property have ended.

```setMaxRenderedChildren(int max)```
Child views beyond this limit will be set to GONE, and won't be animated, for performance reasons. Defaults to **5**.

It has one protected method, ```animateValueForChildAtIndex(ViewProperty property, int index, float value)```, which is visible to PhysicsAnimationController descendants. This method dispatches the given value to the appropriate animation.