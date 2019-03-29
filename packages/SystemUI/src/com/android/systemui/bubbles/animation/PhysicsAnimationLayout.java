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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

        /** Configures a given {@link PhysicsPropertyAnimator} for a view at the given index. */
        interface ChildAnimationConfigurator {

            /**
             * Called to configure the animator for the view at the given index.
             *
             * This method should make use of methods such as
             * {@link PhysicsPropertyAnimator#translationX} and
             * {@link PhysicsPropertyAnimator#withStartDelay} to configure the animation.
             *
             * Implementations should not call {@link PhysicsPropertyAnimator#start}, this will
             * happen elsewhere after configuration is complete.
             */
            void configureAnimationForChildAtIndex(int index, PhysicsPropertyAnimator animation);
        }

        /**
         * Returned by {@link #animationsForChildrenFromIndex} to allow starting multiple animations
         * on multiple child views at the same time.
         */
        interface MultiAnimationStarter {

            /**
             * Start all animations and call the given end actions once all animations have
             * completed.
             */
            void startAll(Runnable... endActions);
        }

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
         * Called with a child view that has been removed from the layout, from the given index. The
         * passed view has been removed from the layout and added back as a transient view, which
         * renders normally, but is not part of the normal view hierarchy and will not be considered
         * by getChildAt() and getChildCount().
         *
         * The controller can perform animations on the child (either manually, or by using
         * {@link #animationForChild(View)}), and then call finishRemoval when complete.
         *
         * finishRemoval must be called by implementations of this method, or transient views will
         * never be removed.
         */
        abstract void onChildRemoved(View child, int index, Runnable finishRemoval);

        protected PhysicsAnimationLayout mLayout;

        PhysicsAnimationController() { }

        protected void setLayout(PhysicsAnimationLayout layout) {
            this.mLayout = layout;
        }

        protected PhysicsAnimationLayout getLayout() {
            return mLayout;
        }

        /**
         * Sets the child's visibility when it moves beyond or within the limits set by a call to
         * {@link PhysicsAnimationLayout#setMaxRenderedChildren}. This can be overridden to animate
         * this transition.
         */
        protected void setChildVisibility(View child, int index, int visibility) {
            child.setVisibility(visibility);
        }

        /**
         * Returns a {@link PhysicsPropertyAnimator} instance for the given child view.
         */
        protected PhysicsPropertyAnimator animationForChild(View child) {
            PhysicsPropertyAnimator animator =
                    (PhysicsPropertyAnimator) child.getTag(R.id.physics_animator_tag);

            if (animator == null) {
                animator = mLayout.new PhysicsPropertyAnimator(child);
                child.setTag(R.id.physics_animator_tag, animator);
            }

            return animator;
        }

        /** Returns a {@link PhysicsPropertyAnimator} instance for child at the given index. */
        protected PhysicsPropertyAnimator animationForChildAtIndex(int index) {
            return animationForChild(mLayout.getChildAt(index));
        }

        /**
         * Returns a {@link MultiAnimationStarter} whose startAll method will start the physics
         * animations for all children from startIndex onward. The provided configurator will be
         * called with each child's {@link PhysicsPropertyAnimator}, where it can set up each
         * animation appropriately.
         */
        protected MultiAnimationStarter animationsForChildrenFromIndex(
                int startIndex, ChildAnimationConfigurator configurator) {
            final Set<DynamicAnimation.ViewProperty> allAnimatedProperties = new HashSet<>();
            final List<PhysicsPropertyAnimator> allChildAnims = new ArrayList<>();

            // Retrieve the animator for each child, ask the configurator to configure it, then save
            // it and the properties it chose to animate.
            for (int i = startIndex; i < mLayout.getChildCount(); i++) {
                final PhysicsPropertyAnimator anim = animationForChildAtIndex(i);
                configurator.configureAnimationForChildAtIndex(i, anim);
                allAnimatedProperties.addAll(anim.getAnimatedProperties());
                allChildAnims.add(anim);
            }

            // Return a MultiAnimationStarter that will start all of the child animations, and also
            // add a multiple property end listener to the layout that will call the end action
            // provided to startAll() once all animations on the animated properties complete.
            return (endActions) -> {
                if (endActions != null) {
                    mLayout.setEndActionForMultipleProperties(
                            () -> {
                                for (Runnable action : endActions) {
                                    action.run();
                                }
                            },
                            allAnimatedProperties.toArray(
                                    new DynamicAnimation.ViewProperty[0]));
                }

                for (PhysicsPropertyAnimator childAnim : allChildAnims) {
                    childAnim.start();
                }
            };
        }
    }

    /**
     * End actions that are called when every child's animation of the given property has finished.
     */
    protected final HashMap<DynamicAnimation.ViewProperty, Runnable> mEndActionForProperty =
            new HashMap<>();

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
        mEndActionForProperty.clear();

        this.mController = controller;
        mController.setLayout(this);

        // Set up animations for this controller's animated properties.
        for (DynamicAnimation.ViewProperty property : mController.getAnimatedProperties()) {
            setUpAnimationsForProperty(property);
        }
    }

    /**
     * Sets an end action that will be run when all child animations for a given property have
     * stopped running.
     */
    public void setEndActionForProperty(Runnable action, DynamicAnimation.ViewProperty property) {
        mEndActionForProperty.put(property, action);
    }

    /**
     * Sets an end action that will be run when all child animations for all of the given properties
     * have stopped running.
     */
    public void setEndActionForMultipleProperties(
            Runnable action, DynamicAnimation.ViewProperty... properties) {
        final Runnable checkIfAllFinished = () -> {
            if (!arePropertiesAnimating(properties)) {
                action.run();
            }
        };

        for (DynamicAnimation.ViewProperty property : properties) {
            setEndActionForProperty(checkIfAllFinished, property);
        }
    }

    /**
     * Removes the end listener that would have been called when all child animations for a given
     * property stopped running.
     */
    public void removeEndActionForProperty(DynamicAnimation.ViewProperty property) {
        mEndActionForProperty.remove(property);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);

        // Set up animations for the new view, if the controller is set. If it isn't set, we'll be
        // setting up animations for all children when setController is called.
        if (mController != null) {
            for (DynamicAnimation.ViewProperty property : mController.getAnimatedProperties()) {
                setUpAnimationForChild(property, child, index);
            }

            mController.onChildAdded(child, index);
        }

        setChildrenVisibility();
    }

    @Override
    public void removeView(View view) {
        removeViewAndThen(view, /* callback */ null);
    }

    @Override
    public void removeViewAt(int index) {
        removeView(getChildAt(index));
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
            mController.onChildRemoved(view, index, () -> {
                // The controller says it's done with the transient view, cancel animations in case
                // any are still running and then remove it.
                cancelAnimationsOnView(view);
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
                final DynamicAnimation anim = getAnimationAtIndex(property, i);
                if (anim != null) {
                    anim.cancel();
                }
            }
        }
    }

    /** Cancels all of the physics animations running on the given view. */
    public void cancelAnimationsOnView(View view) {
        for (DynamicAnimation.ViewProperty property : mController.getAnimatedProperties()) {
            getAnimationFromView(property, view).cancel();
        }
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
        return getAnimationFromView(property, getChildAt(index));
    }

    /** Retrieves the animation of the given property from the view via the view tag system. */
    private SpringAnimation getAnimationFromView(
            DynamicAnimation.ViewProperty property, View view) {
        return (SpringAnimation) view.getTag(getTagIdForProperty(property));
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
            final int indexOfChild = indexOfChild(child);
            final int nextAnimInChain =
                    mController.getNextAnimationInChain(property, indexOfChild);

            if (nextAnimInChain == PhysicsAnimationController.NONE || indexOfChild < 0) {
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
            final int targetVisibility = i < mMaxRenderedChildren ? View.VISIBLE : View.GONE;
            final View targetView = getChildAt(i);

            if (targetView.getVisibility() != targetVisibility) {
                if (mController != null) {
                    mController.setChildVisibility(targetView, i, targetVisibility);
                } else {
                    targetView.setVisibility(targetVisibility);
                }
            }
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
                if (mEndActionForProperty.containsKey(mProperty)) {
                    mEndActionForProperty.get(mProperty).run();
                }
            }
        }
    }

    /**
     * Animator class returned by {@link PhysicsAnimationController#animationForChild}, to allow
     * controllers to animate child views using physics animations.
     *
     * See docs/physics-animation-layout.md for documentation and examples.
     */
    protected class PhysicsPropertyAnimator {
        /** The view whose properties this animator animates. */
        private View mView;

        /** Start velocity to use for all property animations. */
        private float mDefaultStartVelocity = 0f;

        /** Start delay to use when start is called. */
        private long mStartDelay = 0;

        /** End actions to call when animations for the given property complete. */
        private Map<DynamicAnimation.ViewProperty, Runnable[]> mEndActionsForProperty =
                new HashMap<>();

        /**
         * Start velocities to use for TRANSLATION_X and TRANSLATION_Y, since these are often
         * provided by VelocityTrackers and differ from each other.
         */
        private Map<DynamicAnimation.ViewProperty, Float> mPositionStartVelocities =
                new HashMap<>();

        /**
         * End actions to call when both TRANSLATION_X and TRANSLATION_Y animations have completed,
         * if {@link #position} was used to animate TRANSLATION_X and TRANSLATION_Y simultaneously.
         */
        private Runnable[] mPositionEndActions;

        /**
         * All of the properties that have been set and will animate when {@link #start} is called.
         */
        private Map<DynamicAnimation.ViewProperty, Float> mAnimatedProperties = new HashMap<>();

        protected PhysicsPropertyAnimator(View view) {
            this.mView = view;
        }

        /** Animate a property to the given value, then call the optional end actions. */
        public PhysicsPropertyAnimator property(
                DynamicAnimation.ViewProperty property, float value, Runnable... endActions) {
            mAnimatedProperties.put(property, value);
            mEndActionsForProperty.put(property, endActions);
            return this;
        }

        /** Animate the view's alpha value to the provided value. */
        public PhysicsPropertyAnimator alpha(float alpha, Runnable... endActions) {
            return property(DynamicAnimation.ALPHA, alpha, endActions);
        }

        /** Set the view's alpha value to 'from', then animate it to the given value. */
        public PhysicsPropertyAnimator alpha(float from, float to, Runnable... endActions) {
            mView.setAlpha(from);
            return alpha(to, endActions);
        }

        /** Animate the view's translationX value to the provided value. */
        public PhysicsPropertyAnimator translationX(float translationX, Runnable... endActions) {
            return property(DynamicAnimation.TRANSLATION_X, translationX, endActions);
        }

        /** Set the view's translationX value to 'from', then animate it to the given value. */
        public PhysicsPropertyAnimator translationX(
                float from, float to, Runnable... endActions) {
            mView.setTranslationX(from);
            return translationX(to, endActions);
        }

        /** Animate the view's translationY value to the provided value. */
        public PhysicsPropertyAnimator translationY(float translationY, Runnable... endActions) {
            return property(DynamicAnimation.TRANSLATION_Y, translationY, endActions);
        }

        /** Set the view's translationY value to 'from', then animate it to the given value. */
        public PhysicsPropertyAnimator translationY(
                float from, float to, Runnable... endActions) {
            mView.setTranslationY(from);
            return translationY(to, endActions);
        }

        /**
         * Animate the view's translationX and translationY values, and call the end actions only
         * once both TRANSLATION_X and TRANSLATION_Y animations have completed.
         */
        public PhysicsPropertyAnimator position(
                float translationX, float translationY, Runnable... endActions) {
            mPositionEndActions = endActions;
            translationX(translationX);
            return translationY(translationY);
        }

        /** Animate the view's scaleX value to the provided value. */
        public PhysicsPropertyAnimator scaleX(float scaleX, Runnable... endActions) {
            return property(DynamicAnimation.SCALE_X, scaleX, endActions);
        }

        /** Set the view's scaleX value to 'from', then animate it to the given value. */
        public PhysicsPropertyAnimator scaleX(float from, float to, Runnable... endActions) {
            mView.setScaleX(from);
            return scaleX(to, endActions);
        }

        /** Animate the view's scaleY value to the provided value. */
        public PhysicsPropertyAnimator scaleY(float scaleY, Runnable... endActions) {
            return property(DynamicAnimation.SCALE_Y, scaleY, endActions);
        }

        /** Set the view's scaleY value to 'from', then animate it to the given value. */
        public PhysicsPropertyAnimator scaleY(float from, float to, Runnable... endActions) {
            mView.setScaleY(from);
            return scaleY(to, endActions);
        }

        /** Set the start velocity to use for all property animations. */
        public PhysicsPropertyAnimator withStartVelocity(float startVel) {
            mDefaultStartVelocity = startVel;
            return this;
        }

        /**
         * Set the start velocities to use for TRANSLATION_X and TRANSLATION_Y animations. This
         * overrides any value set via {@link #withStartVelocity(float)} for those properties.
         */
        public PhysicsPropertyAnimator withPositionStartVelocities(float velX, float velY) {
            mPositionStartVelocities.put(DynamicAnimation.TRANSLATION_X, velX);
            mPositionStartVelocities.put(DynamicAnimation.TRANSLATION_Y, velY);
            return this;
        }

        /** Set a delay, in milliseconds, before kicking off the animations. */
        public PhysicsPropertyAnimator withStartDelay(long startDelay) {
            mStartDelay = startDelay;
            return this;
        }

        /**
         * Start the animations, and call the optional end actions once all animations for every
         * animated property on every child (including chained animations) have ended.
         */
        public void start(Runnable... after) {
            final Set<DynamicAnimation.ViewProperty> properties = getAnimatedProperties();

            // If there are end actions, set an end listener on the layout for all the properties
            // we're about to animate.
            if (after != null) {
                final DynamicAnimation.ViewProperty[] propertiesArray =
                        properties.toArray(new DynamicAnimation.ViewProperty[0]);
                for (Runnable callback : after) {
                    setEndActionForMultipleProperties(callback, propertiesArray);
                }
            }

            // If we used position-specific end actions, we'll need to listen for both TRANSLATION_X
            // and TRANSLATION_Y animations ending, and call them once both have finished.
            if (mPositionEndActions != null) {
                final SpringAnimation translationXAnim =
                        getAnimationFromView(DynamicAnimation.TRANSLATION_X, mView);
                final SpringAnimation translationYAnim =
                        getAnimationFromView(DynamicAnimation.TRANSLATION_Y, mView);
                final Runnable waitForBothXAndY = () -> {
                    if (!translationXAnim.isRunning() && !translationYAnim.isRunning()) {
                        if (mPositionEndActions != null) {
                            for (Runnable callback : mPositionEndActions) {
                                callback.run();
                            }
                        }

                        mPositionEndActions = null;
                    }
                };

                mEndActionsForProperty.put(DynamicAnimation.TRANSLATION_X,
                        new Runnable[]{waitForBothXAndY});
                mEndActionsForProperty.put(DynamicAnimation.TRANSLATION_Y,
                        new Runnable[]{waitForBothXAndY});
            }

            // Actually start the animations.
            for (DynamicAnimation.ViewProperty property : properties) {
                animateValueForChild(
                        property,
                        mView,
                        mAnimatedProperties.get(property),
                        mPositionStartVelocities.getOrDefault(property, mDefaultStartVelocity),
                        mStartDelay,
                        mEndActionsForProperty.get(property));
            }

            // Clear out the animator.
            mAnimatedProperties.clear();
            mPositionStartVelocities.clear();
            mDefaultStartVelocity = 0;
            mStartDelay = 0;
            mEndActionsForProperty.clear();
        }

        /** Returns the set of properties that will animate once {@link #start} is called. */
        protected Set<DynamicAnimation.ViewProperty> getAnimatedProperties() {
            return mAnimatedProperties.keySet();
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
                long startDelay,
                Runnable[] afterCallbacks) {
            if (view != null) {
                final SpringAnimation animation =
                        (SpringAnimation) view.getTag(getTagIdForProperty(property));
                if (afterCallbacks != null) {
                    animation.addEndListener(new OneTimeEndListener() {
                        @Override
                        public void onAnimationEnd(DynamicAnimation animation, boolean canceled,
                                float value, float velocity) {
                            super.onAnimationEnd(animation, canceled, value, velocity);
                            for (Runnable runnable : afterCallbacks) {
                                runnable.run();
                            }
                        }
                    });
                }

                if (startVel > 0) {
                    animation.setStartVelocity(startVel);
                }

                if (startDelay > 0) {
                    postDelayed(() -> animation.animateToFinalPosition(value), startDelay);
                } else {
                    animation.animateToFinalPosition(value);
                }
            }
        }
    }
}
