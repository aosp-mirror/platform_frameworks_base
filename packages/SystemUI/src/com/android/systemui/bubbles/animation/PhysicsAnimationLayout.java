/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.bubbles.animation;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.systemui.R;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Layout that constructs physics-based animations for each of its children, which behave according
 * to settings provided by a {@link PhysicsAnimationController} instance.
 *
 * See physics-animation-layout.md.
 */
public class PhysicsAnimationLayout extends FrameLayout {
    private static final String TAG = "Bubbs.PAL";

    /**
     * Controls the construction, configuration, and use of the physics animations supplied by this
     * layout.
     */
    abstract static class PhysicsAnimationController {

        /**
         * Constant to return from {@link #getNextAnimationInChain} if the animation should not be
         * chained at all.
         */
        protected static final int NONE = -1;

        /** Set of properties for which the layout should construct physics animations. */
        abstract Set<DynamicAnimation.ViewProperty> getAnimatedProperties();

        /**
         * Returns the index of the next animation after the given index in the animation chain, or
         * {@link #NONE} if it should not be chained, or if the chain should end at the given index.
         *
         * If a next index is returned, an update listener will be added to the animation at the
         * given index that dispatches value updates to the animation at the next index. This
         * creates a 'following' effect.
         *
         * Typical implementations of this method will return either index + 1, or index - 1, to
         * create forward or backward chains between adjacent child views, but this is not required.
         */
        abstract int getNextAnimationInChain(DynamicAnimation.ViewProperty property, int index);

        /**
         * Offsets to be added to the value that chained animations of the given property dispatch
         * to subsequent child animations.
         *
         * This is used for things like maintaining the 'stack' effect in Bubbles, where bubbles
         * stack off to the left or right side slightly.
         */
        abstract float getOffsetForChainedPropertyAnimation(DynamicAnimation.ViewProperty property);

        /**
         * Returns the SpringForce to be used for the given child view's property animation. Despite
         * these usually being similar or identical across properties and views, {@link SpringForce}
         * also contains the SpringAnimation's final position, so we have to construct a new one for
         * each animation rather than using a constant.
         */
        abstract SpringForce getSpringForce(DynamicAnimation.ViewProperty property, View view);

        /**
         * Called when a new child is added at the specified index. Controllers can use this
         * opportunity to animate in the new view.
         */
        abstract void onChildAdded(View child, int index);

        /**
         * Called when a child is to be removed from the layout. Controllers can use this
         * opportunity to animate out the new view before calling the provided callback to actually
         * remove it.
         *
         * Controllers should be careful to ensure that actuallyRemove is called on all code paths
         * or child views will never be removed.
         */
        abstract void onChildToBeRemoved(View child, int index, Runnable actuallyRemove);

        protected PhysicsAnimationLayout mLayout;

        PhysicsAnimationController() { }

        protected void setLayout(PhysicsAnimationLayout layout) {
            this.mLayout = layout;
        }

        protected PhysicsAnimationLayout getLayout() {
            return mLayout;
        }
    }

    /**
     * End listeners that are called when every child's animation of the given property has
     * finished.
     */
    protected final HashMap<DynamicAnimation.ViewProperty, DynamicAnimation.OnAnimationEndListener>
            mEndListenerForProperty = new HashMap<>();

    /** Set of currently rendered transient views. */
    private final Set<View> mTransientViews = new HashSet<>();

    /** The currently active animation controller. */
    private PhysicsAnimationController mController;

    /**
     * The maximum number of children to render and animate at a time. See
     * {@link #setMaxRenderedChildren}.
     */
    private int mMaxRenderedChildren = 5;

    public PhysicsAnimationLayout(Context context) {
        super(context);
    }

    /**
     * The maximum number of children to render and animate at a time. Any child views added beyond
     * this limit will be set to {@link View#GONE}. If any animations attempt to run on the view,
     * the corresponding property will be set with no animation.
     */
    public void setMaxRenderedChildren(int max) {
        this.mMaxRenderedChildren = max;
    }

    /**
     * Sets the animation controller and constructs or reconfigures the layout's physics animations
     * to meet the controller's specifications.
     */
    public void setController(PhysicsAnimationController controller) {
        cancelAllAnimations();
        mEndListenerForProperty.clear();

        this.mController = controller;
        mController.setLayout(this);

        // Set up animations for this controller's animated properties.
        for (DynamicAnimation.ViewProperty property : mController.getAnimatedProperties()) {
            setUpAnimationsForProperty(property);
        }
    }

    /**
     * Sets an end listener that will be called when all child animations for a given property have
     * stopped running.
     */
    public void setEndListenerForProperty(
            DynamicAnimation.OnAnimationEndListener listener,
            DynamicAnimation.ViewProperty property) {
        mEndListenerForProperty.put(property, listener);
    }

    /**
     * Removes the end listener that would have been called when all child animations for a given
     * property stopped running.
     */
    public void removeEndListenerForProperty(DynamicAnimation.ViewProperty property) {
        mEndListenerForProperty.remove(property);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        setChildrenVisibility();

        // Set up animations for the new view, if the controller is set. If it isn't set, we'll be
        // setting up animations for all children when setController is called.
        if (mController != null) {
            for (DynamicAnimation.ViewProperty property : mController.getAnimatedProperties()) {
                setUpAnimationForChild(property, child, index);
            }

            mController.onChildAdded(child, index);
        }
    }

    @Override
    public void removeView(View view) {
        removeViewAndThen(view, /* callback */ null);
    }

    @Override
    public void addTransientView(View view, int index) {
        super.addTransientView(view, index);
        mTransientViews.add(view);
    }

    @Override
    public void removeTransientView(View view) {
        super.removeTransientView(view);
        mTransientViews.remove(view);
    }

    /** Immediately moves the view from wherever it currently is, to the given index. */
    public void moveViewTo(View view, int index) {
        super.removeView(view);
        addView(view, index);
    }

    /**
     * Let the controller know that this view should be removed, and then call the callback once the
     * controller has finished any removal animations and the view has actually been removed.
     */
    public void removeViewAndThen(View view, Runnable callback) {
        if (mController != null) {
            final int index = indexOfChild(view);

            // Remove the view and add it back as a transient view so we can animate it out.
            super.removeView(view);
            addTransientView(view, index);

            setChildrenVisibility();

            // Tell the controller to animate this view out, and call the callback when it's
            // finished.
            mController.onChildToBeRemoved(view, index, () -> {
                // Done animating, remove the transient view.
                removeTransientView(view);

                if (callback != null) {
                    callback.run();
                }
            });
        } else {
            // Without a controller, nobody will animate this view out, so it gets an unceremonious
            // departure.
            super.removeView(view);

            if (callback != null) {
                callback.run();
            }
        }
    }

    /** Checks whether any animations of the given properties are still running. */
    public boolean arePropertiesAnimating(DynamicAnimation.ViewProperty... properties) {
        for (int i = 0; i < getChildCount(); i++) {
            for (DynamicAnimation.ViewProperty property : properties) {
                if (getAnimationAtIndex(property, i).isRunning()) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Cancels all animations that are running on all child views, for all properties. */
    public void cancelAllAnimations() {
        if (mController == null) {
            return;
        }

        for (int i = 0; i < getChildCount(); i++) {
            for (DynamicAnimation.ViewProperty property : mController.getAnimatedProperties()) {
                getAnimationAtIndex(property, i).cancel();
            }
        }
    }

    /**
     * Animates the property of the given child view, then runs the callback provided when the
     * animation ends.
     */
    protected void animateValueForChild(
            DynamicAnimation.ViewProperty property,
            View view,
            float value,
            float startVel,
            Runnable after) {
        if (view != null) {
            final SpringAnimation animation =
                    (SpringAnimation) view.getTag(getTagIdForProperty(property));
            if (after != null) {
                animation.addEndListener(new OneTimeEndListener() {
                    @Override
                    public void onAnimationEnd(DynamicAnimation animation, boolean canceled,
                            float value, float velocity) {
                        super.onAnimationEnd(animation, canceled, value, velocity);
                        after.run();
                    }
                });
            }

            animation.animateToFinalPosition(value);
        }
    }

    protected void animateValueForChild(
            DynamicAnimation.ViewProperty property,
            View view,
            float value,
            Runnable after) {
        animateValueForChild(property, view, value, Float.MAX_VALUE, after);
    }

    protected void animateValueForChild(
            DynamicAnimation.ViewProperty property,
            View view,
            float value) {
        animateValueForChild(property, view, value, Float.MAX_VALUE, /* after */ null);
    }

    /**
     * Animates the property of the child at the given index to the given value, then runs the
     * callback provided when the animation ends.
     */
    protected void animateValueForChildAtIndex(
            DynamicAnimation.ViewProperty property,
            int index,
            float value,
            float startVel,
            Runnable after) {
        animateValueForChild(property, getChildAt(index), value, startVel, after);
    }

    /** Shortcut to animate a value with a callback, but no start velocity. */
    protected void animateValueForChildAtIndex(
            DynamicAnimation.ViewProperty property,
            int index,
            float value,
            Runnable after) {
        animateValueForChildAtIndex(property, index, value, Float.MAX_VALUE, after);
    }

    /** Shortcut to animate a value with a start velocity, but no callback. */
    protected void animateValueForChildAtIndex(
            DynamicAnimation.ViewProperty property,
            int index,
            float value,
            float startVel) {
        animateValueForChildAtIndex(property, index, value, startVel, /* callback */ null);
    }

    /** Shortcut to animate a value without changing the velocity or providing a callback. */
    protected void animateValueForChildAtIndex(
            DynamicAnimation.ViewProperty property,
            int index,
            float value) {
        animateValueForChildAtIndex(property, index, value, Float.MAX_VALUE, /* callback */ null);
    }

    /** Shortcut to animate a child view's TRANSLATION_X and TRANSLATION_Y values. */
    protected void animatePositionForChildAtIndex(int index, float x, float y) {
        animateValueForChildAtIndex(DynamicAnimation.TRANSLATION_X, index, x);
        animateValueForChildAtIndex(DynamicAnimation.TRANSLATION_Y, index, y);
    }

    /** Whether the first child would be left of center if translated to the given x value. */
    protected boolean isFirstChildXLeftOfCenter(float x) {
        if (getChildCount() > 0) {
            return x + (getChildAt(0).getWidth() / 2) < getWidth() / 2;
        } else {
            return false; // If there's no first child, really anything is correct, right?
        }
    }

    /** ViewProperty's toString is useless, this returns a readable name for debug logging. */
    protected static String getReadablePropertyName(DynamicAnimation.ViewProperty property) {
        if (property.equals(DynamicAnimation.TRANSLATION_X)) {
            return "TRANSLATION_X";
        } else if (property.equals(DynamicAnimation.TRANSLATION_Y)) {
            return "TRANSLATION_Y";
        } else if (property.equals(DynamicAnimation.SCALE_X)) {
            return "SCALE_X";
        } else if (property.equals(DynamicAnimation.SCALE_Y)) {
            return "SCALE_Y";
        } else if (property.equals(DynamicAnimation.ALPHA)) {
            return "ALPHA";
        } else {
            return "Unknown animation property.";
        }
    }

    /**
     * Retrieves the animation of the given property from the view at the given index via the view
     * tag system.
     */
    private SpringAnimation getAnimationAtIndex(
            DynamicAnimation.ViewProperty property, int index) {
        return (SpringAnimation) getChildAt(index).getTag(getTagIdForProperty(property));
    }

    /** Sets up SpringAnimations of the given property for each child view in the layout. */
    private void setUpAnimationsForProperty(DynamicAnimation.ViewProperty property) {
        for (int i = 0; i < getChildCount(); i++) {
            setUpAnimationForChild(property, getChildAt(i), i);
        }
    }

    /** Constructs a SpringAnimation of the given property for a child view. */
    private void setUpAnimationForChild(
            DynamicAnimation.ViewProperty property, View child, int index) {
        SpringAnimation newAnim = new SpringAnimation(child, property);
        newAnim.addUpdateListener((animation, value, velocity) -> {
            final int nextAnimInChain =
                    mController.getNextAnimationInChain(property, indexOfChild(child));

            // If the controller doesn't want us to chain, or if we're a transient view in the
            // process of being removed, don't chain.
            if (nextAnimInChain == PhysicsAnimationController.NONE
                    || mTransientViews.contains(child)) {
                return;
            }

            final int animIndex = indexOfChild(child);
            final float offset =
                    mController.getOffsetForChainedPropertyAnimation(property);

            // If this property's animations should be chained, then check to see if there is a
            // subsequent animation within the rendering limit, and if so, tell it to animate to
            // this animation's new value (plus the offset).
            if (nextAnimInChain < Math.min(getChildCount(), mMaxRenderedChildren)) {
                getAnimationAtIndex(property, animIndex + 1)
                        .animateToFinalPosition(value + offset);
            } else if (nextAnimInChain < getChildCount()) {
                // If the next child view is not rendered, update the property directly without
                // animating it, so that the view is still in the correct state if it later
                // becomes visible.
                for (int i = nextAnimInChain; i < getChildCount(); i++) {
                    // 'value' here is the value of the last child within the rendering limit,
                    // not the first child's value - so we want to subtract the last child's
                    // index when calculating the offset.
                    property.setValue(getChildAt(i), value + offset * (i - animIndex));
                }
            }
        });

        newAnim.setSpring(mController.getSpringForce(property, child));
        newAnim.addEndListener(new AllAnimationsForPropertyFinishedEndListener(property));
        child.setTag(getTagIdForProperty(property), newAnim);
    }

    /** Hides children beyond the max rendering count. */
    private void setChildrenVisibility() {
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).setVisibility(
                    // Ignore views that are animating out when calculating whether to hide the
                    // view. That is, if we're supposed to render 5 views, but 4 are animating out
                    // and will soon be removed, render up to 9 views temporarily.
                    i < mMaxRenderedChildren ? View.VISIBLE : View.GONE);
        }
    }

    /** Return a stable ID to use as a tag key for the given property's animations. */
    private int getTagIdForProperty(DynamicAnimation.ViewProperty property) {
        if (property.equals(DynamicAnimation.TRANSLATION_X)) {
            return R.id.translation_x_dynamicanimation_tag;
        } else if (property.equals(DynamicAnimation.TRANSLATION_Y)) {
            return R.id.translation_y_dynamicanimation_tag;
        } else if (property.equals(DynamicAnimation.SCALE_X)) {
            return R.id.scale_x_dynamicanimation_tag;
        } else if (property.equals(DynamicAnimation.SCALE_Y)) {
            return R.id.scale_y_dynamicanimation_tag;
        } else if (property.equals(DynamicAnimation.ALPHA)) {
            return R.id.alpha_dynamicanimation_tag;
        }

        return -1;
    }

    /**
     * End listener that is added to each individual DynamicAnimation, which dispatches to a single
     * listener when every other animation of the given property is no longer running.
     *
     * This is required since chained DynamicAnimations can stop and start again due to changes in
     * upstream animations. This means that adding an end listener to just the last animation is not
     * sufficient. By firing only when every other animation on the property has stopped running, we
     * ensure that no animation will be restarted after the single end listener is called.
     */
    protected class AllAnimationsForPropertyFinishedEndListener
            implements DynamicAnimation.OnAnimationEndListener {
        private DynamicAnimation.ViewProperty mProperty;

        AllAnimationsForPropertyFinishedEndListener(DynamicAnimation.ViewProperty property) {
            this.mProperty = property;
        }

        @Override
        public void onAnimationEnd(
                DynamicAnimation anim, boolean canceled, float value, float velocity) {
            if (!arePropertiesAnimating(mProperty)) {
                if (mEndListenerForProperty.containsKey(mProperty)) {
                    mEndListenerForProperty.get(mProperty).onAnimationEnd(anim, canceled, value,
                            velocity);
                }
            }
        }
    }
}
