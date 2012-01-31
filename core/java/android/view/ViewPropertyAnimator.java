/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.animation.TimeInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

/**
 * This class enables automatic and optimized animation of select properties on View objects.
 * If only one or two properties on a View object are being animated, then using an
 * {@link android.animation.ObjectAnimator} is fine; the property setters called by ObjectAnimator
 * are well equipped to do the right thing to set the property and invalidate the view
 * appropriately. But if several properties are animated simultaneously, or if you just want a
 * more convenient syntax to animate a specific property, then ViewPropertyAnimator might be
 * more well-suited to the task.
 *
 * <p>This class may provide better performance for several simultaneous animations, because
 * it will optimize invalidate calls to take place only once for several properties instead of each
 * animated property independently causing its own invalidation. Also, the syntax of using this
 * class could be easier to use because the caller need only tell the View object which
 * property to animate, and the value to animate either to or by, and this class handles the
 * details of configuring the underlying Animator class and starting it.</p>
 *
 * <p>This class is not constructed by the caller, but rather by the View whose properties
 * it will animate. Calls to {@link android.view.View#animate()} will return a reference
 * to the appropriate ViewPropertyAnimator object for that View.</p>
 * 
 */
public class ViewPropertyAnimator {

    /**
     * The View whose properties are being animated by this class. This is set at
     * construction time.
     */
    private final View mView;

    /**
     * The duration of the underlying Animator object. By default, we don't set the duration
     * on the Animator and just use its default duration. If the duration is ever set on this
     * Animator, then we use the duration that it was set to.
     */
    private long mDuration;

    /**
     * A flag indicating whether the duration has been set on this object. If not, we don't set
     * the duration on the underlying Animator, but instead just use its default duration.
     */
    private boolean mDurationSet = false;

    /**
     * The startDelay of the underlying Animator object. By default, we don't set the startDelay
     * on the Animator and just use its default startDelay. If the startDelay is ever set on this
     * Animator, then we use the startDelay that it was set to.
     */
    private long mStartDelay = 0;

    /**
     * A flag indicating whether the startDelay has been set on this object. If not, we don't set
     * the startDelay on the underlying Animator, but instead just use its default startDelay.
     */
    private boolean mStartDelaySet = false;

    /**
     * The interpolator of the underlying Animator object. By default, we don't set the interpolator
     * on the Animator and just use its default interpolator. If the interpolator is ever set on
     * this Animator, then we use the interpolator that it was set to.
     */
    private TimeInterpolator mInterpolator;

    /**
     * A flag indicating whether the interpolator has been set on this object. If not, we don't set
     * the interpolator on the underlying Animator, but instead just use its default interpolator.
     */
    private boolean mInterpolatorSet = false;

    /**
     * Listener for the lifecycle events of the underlying 
     */
    private Animator.AnimatorListener mListener = null;

    /**
     * This listener is the mechanism by which the underlying Animator causes changes to the
     * properties currently being animated, as well as the cleanup after an animation is
     * complete.
     */
    private AnimatorEventListener mAnimatorEventListener = new AnimatorEventListener();

    /**
     * This list holds the properties that have been asked to animate. We allow the caller to
     * request several animations prior to actually starting the underlying animator. This
     * enables us to run one single animator to handle several properties in parallel. Each
     * property is tossed onto the pending list until the animation actually starts (which is
     * done by posting it onto mView), at which time the pending list is cleared and the properties
     * on that list are added to the list of properties associated with that animator.
     */
    ArrayList<NameValuesHolder> mPendingAnimations = new ArrayList<NameValuesHolder>();
    private Runnable mPendingSetupAction;
    private Runnable mPendingCleanupAction;
    private Runnable mPendingOnStartAction;
    private Runnable mPendingOnEndAction;

    /**
     * Constants used to associate a property being requested and the mechanism used to set
     * the property (this class calls directly into View to set the properties in question).
     */
    private static final int NONE           = 0x0000;
    private static final int TRANSLATION_X  = 0x0001;
    private static final int TRANSLATION_Y  = 0x0002;
    private static final int SCALE_X        = 0x0004;
    private static final int SCALE_Y        = 0x0008;
    private static final int ROTATION       = 0x0010;
    private static final int ROTATION_X     = 0x0020;
    private static final int ROTATION_Y     = 0x0040;
    private static final int X              = 0x0080;
    private static final int Y              = 0x0100;
    private static final int ALPHA          = 0x0200;

    private static final int TRANSFORM_MASK = TRANSLATION_X | TRANSLATION_Y | SCALE_X | SCALE_Y |
            ROTATION | ROTATION_X | ROTATION_Y | X | Y;

    /**
     * The mechanism by which the user can request several properties that are then animated
     * together works by posting this Runnable to start the underlying Animator. Every time
     * a property animation is requested, we cancel any previous postings of the Runnable
     * and re-post it. This means that we will only ever run the Runnable (and thus start the
     * underlying animator) after the caller is done setting the properties that should be
     * animated together.
     */
    private Runnable mAnimationStarter = new Runnable() {
        @Override
        public void run() {
            startAnimation();
        }
    };

    /**
     * This class holds information about the overall animation being run on the set of
     * properties. The mask describes which properties are being animated and the
     * values holder is the list of all property/value objects.
     */
    private static class PropertyBundle {
        int mPropertyMask;
        ArrayList<NameValuesHolder> mNameValuesHolder;

        PropertyBundle(int propertyMask, ArrayList<NameValuesHolder> nameValuesHolder) {
            mPropertyMask = propertyMask;
            mNameValuesHolder = nameValuesHolder;
        }

        /**
         * Removes the given property from being animated as a part of this
         * PropertyBundle. If the property was a part of this bundle, it returns
         * true to indicate that it was, in fact, canceled. This is an indication
         * to the caller that a cancellation actually occurred.
         *
         * @param propertyConstant The property whose cancellation is requested.
         * @return true if the given property is a part of this bundle and if it
         * has therefore been canceled.
         */
        boolean cancel(int propertyConstant) {
            if ((mPropertyMask & propertyConstant) != 0 && mNameValuesHolder != null) {
                int count = mNameValuesHolder.size();
                for (int i = 0; i < count; ++i) {
                    NameValuesHolder nameValuesHolder = mNameValuesHolder.get(i);
                    if (nameValuesHolder.mNameConstant == propertyConstant) {
                        mNameValuesHolder.remove(i);
                        mPropertyMask &= ~propertyConstant;
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     * This list tracks the list of properties being animated by any particular animator.
     * In most situations, there would only ever be one animator running at a time. But it is
     * possible to request some properties to animate together, then while those properties
     * are animating, to request some other properties to animate together. The way that
     * works is by having this map associate the group of properties being animated with the
     * animator handling the animation. On every update event for an Animator, we ask the
     * map for the associated properties and set them accordingly.
     */
    private HashMap<Animator, PropertyBundle> mAnimatorMap =
            new HashMap<Animator, PropertyBundle>();
    private HashMap<Animator, Runnable> mAnimatorSetupMap;
    private HashMap<Animator, Runnable> mAnimatorCleanupMap;
    private HashMap<Animator, Runnable> mAnimatorOnStartMap;
    private HashMap<Animator, Runnable> mAnimatorOnEndMap;

    /**
     * This is the information we need to set each property during the animation.
     * mNameConstant is used to set the appropriate field in View, and the from/delta
     * values are used to calculate the animated value for a given animation fraction
     * during the animation.
     */
    private static class NameValuesHolder {
        int mNameConstant;
        float mFromValue;
        float mDeltaValue;
        NameValuesHolder(int nameConstant, float fromValue, float deltaValue) {
            mNameConstant = nameConstant;
            mFromValue = fromValue;
            mDeltaValue = deltaValue;
        }
    }

    /**
     * Constructor, called by View. This is private by design, as the user should only
     * get a ViewPropertyAnimator by calling View.animate().
     *
     * @param view The View associated with this ViewPropertyAnimator
     */
    ViewPropertyAnimator(View view) {
        mView = view;
        view.ensureTransformationInfo();
    }

    /**
     * Sets the duration for the underlying animator that animates the requested properties.
     * By default, the animator uses the default value for ValueAnimator. Calling this method
     * will cause the declared value to be used instead.
     * @param duration The length of ensuing property animations, in milliseconds. The value
     * cannot be negative.
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator setDuration(long duration) {
        if (duration < 0) {
            throw new IllegalArgumentException("Animators cannot have negative duration: " +
                    duration);
        }
        mDurationSet = true;
        mDuration = duration;
        return this;
    }

    /**
     * Returns the current duration of property animations. If the duration was set on this
     * object, that value is returned. Otherwise, the default value of the underlying Animator
     * is returned.
     *
     * @see #setDuration(long)
     * @return The duration of animations, in milliseconds.
     */
    public long getDuration() {
        if (mDurationSet) {
            return mDuration;
        } else {
            // Just return the default from ValueAnimator, since that's what we'd get if
            // the value has not been set otherwise
            return new ValueAnimator().getDuration();
        }
    }

    /**
     * Returns the current startDelay of property animations. If the startDelay was set on this
     * object, that value is returned. Otherwise, the default value of the underlying Animator
     * is returned.
     *
     * @see #setStartDelay(long)
     * @return The startDelay of animations, in milliseconds.
     */
    public long getStartDelay() {
        if (mStartDelaySet) {
            return mStartDelay;
        } else {
            // Just return the default from ValueAnimator (0), since that's what we'd get if
            // the value has not been set otherwise
            return 0;
        }
    }

    /**
     * Sets the startDelay for the underlying animator that animates the requested properties.
     * By default, the animator uses the default value for ValueAnimator. Calling this method
     * will cause the declared value to be used instead.
     * @param startDelay The delay of ensuing property animations, in milliseconds. The value
     * cannot be negative.
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator setStartDelay(long startDelay) {
        if (startDelay < 0) {
            throw new IllegalArgumentException("Animators cannot have negative duration: " +
                    startDelay);
        }
        mStartDelaySet = true;
        mStartDelay = startDelay;
        return this;
    }

    /**
     * Sets the interpolator for the underlying animator that animates the requested properties.
     * By default, the animator uses the default interpolator for ValueAnimator. Calling this method
     * will cause the declared object to be used instead.
     * 
     * @param interpolator The TimeInterpolator to be used for ensuing property animations.
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator setInterpolator(TimeInterpolator interpolator) {
        mInterpolatorSet = true;
        mInterpolator = interpolator;
        return this;
    }

    /**
     * Sets a listener for events in the underlying Animators that run the property
     * animations.
     *
     * @param listener The listener to be called with AnimatorListener events.
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator setListener(Animator.AnimatorListener listener) {
        mListener = listener;
        return this;
    }

    /**
     * Starts the currently pending property animations immediately. Calling <code>start()</code>
     * is optional because all animations start automatically at the next opportunity. However,
     * if the animations are needed to start immediately and synchronously (not at the time when
     * the next event is processed by the hierarchy, which is when the animations would begin
     * otherwise), then this method can be used.
     */
    public void start() {
        startAnimation();
    }

    /**
     * Cancels all property animations that are currently running or pending.
     */
    public void cancel() {
        if (mAnimatorMap.size() > 0) {
            HashMap<Animator, PropertyBundle> mAnimatorMapCopy =
                    (HashMap<Animator, PropertyBundle>)mAnimatorMap.clone();
            Set<Animator> animatorSet = mAnimatorMapCopy.keySet();
            for (Animator runningAnim : animatorSet) {
                runningAnim.cancel();
            }
        }
        mPendingAnimations.clear();
        mView.removeCallbacks(mAnimationStarter);
    }

    /**
     * This method will cause the View's <code>x</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator x(float value) {
        animateProperty(X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>x</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator xBy(float value) {
        animatePropertyBy(X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>y</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator y(float value) {
        animateProperty(Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>y</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator yBy(float value) {
        animatePropertyBy(Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>rotation</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setRotation(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator rotation(float value) {
        animateProperty(ROTATION, value);
        return this;
    }

    /**
     * This method will cause the View's <code>rotation</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setRotation(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator rotationBy(float value) {
        animatePropertyBy(ROTATION, value);
        return this;
    }

    /**
     * This method will cause the View's <code>rotationX</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setRotationX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator rotationX(float value) {
        animateProperty(ROTATION_X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>rotationX</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setRotationX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator rotationXBy(float value) {
        animatePropertyBy(ROTATION_X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>rotationY</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setRotationY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator rotationY(float value) {
        animateProperty(ROTATION_Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>rotationY</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setRotationY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator rotationYBy(float value) {
        animatePropertyBy(ROTATION_Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>translationX</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setTranslationX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator translationX(float value) {
        animateProperty(TRANSLATION_X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>translationX</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setTranslationX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator translationXBy(float value) {
        animatePropertyBy(TRANSLATION_X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>translationY</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setTranslationY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator translationY(float value) {
        animateProperty(TRANSLATION_Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>translationY</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setTranslationY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator translationYBy(float value) {
        animatePropertyBy(TRANSLATION_Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>scaleX</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setScaleX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator scaleX(float value) {
        animateProperty(SCALE_X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>scaleX</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setScaleX(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator scaleXBy(float value) {
        animatePropertyBy(SCALE_X, value);
        return this;
    }

    /**
     * This method will cause the View's <code>scaleY</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setScaleY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator scaleY(float value) {
        animateProperty(SCALE_Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>scaleY</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setScaleY(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator scaleYBy(float value) {
        animatePropertyBy(SCALE_Y, value);
        return this;
    }

    /**
     * This method will cause the View's <code>alpha</code> property to be animated to the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The value to be animated to.
     * @see View#setAlpha(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator alpha(float value) {
        animateProperty(ALPHA, value);
        return this;
    }

    /**
     * This method will cause the View's <code>alpha</code> property to be animated by the
     * specified value. Animations already running on the property will be canceled.
     *
     * @param value The amount to be animated by, as an offset from the current value.
     * @see View#setAlpha(float)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator alphaBy(float value) {
        animatePropertyBy(ALPHA, value);
        return this;
    }

    /**
     * The View associated with this ViewPropertyAnimator will have its
     * {@link View#setLayerType(int, android.graphics.Paint) layer type} set to
     * {@link View#LAYER_TYPE_HARDWARE} for the duration of the next animation. This state
     * is not persistent, either on the View or on this ViewPropertyAnimator: the layer type
     * of the View will be restored when the animation ends to what it was when this method was
     * called, and this setting on ViewPropertyAnimator is only valid for the next animation.
     * Note that calling this method and then independently setting the layer type of the View
     * (by a direct call to {@link View#setLayerType(int, android.graphics.Paint)}) will result
     * in some inconsistency, including having the layer type restored to its pre-withLayer()
     * value when the animation ends.
     *
     * @see View#setLayerType(int, android.graphics.Paint)
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator withLayer() {
         mPendingSetupAction= new Runnable() {
            @Override
            public void run() {
                mView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            }
        };
        final int currentLayerType = mView.getLayerType();
        mPendingCleanupAction = new Runnable() {
            @Override
            public void run() {
                mView.setLayerType(currentLayerType, null);
            }
        };
        if (mAnimatorSetupMap == null) {
            mAnimatorSetupMap = new HashMap<Animator, Runnable>();
        }
        if (mAnimatorCleanupMap == null) {
            mAnimatorCleanupMap = new HashMap<Animator, Runnable>();
        }

        return this;
    }

    /**
     * Specifies an action to take place when the next animation runs. If there is a
     * {@link #setStartDelay(long) startDelay} set on this ViewPropertyAnimator, then the
     * action will run after that startDelay expires, when the actual animation begins.
     * This method, along with {@link #withEndAction(Runnable)}, is intended to help facilitate
     * choreographing ViewPropertyAnimator animations with other animations or actions
     * in the application.
     *
     * @param runnable The action to run when the next animation starts.
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator withStartAction(Runnable runnable) {
        mPendingOnStartAction = runnable;
        if (runnable != null && mAnimatorOnStartMap == null) {
            mAnimatorOnStartMap = new HashMap<Animator, Runnable>();
        }
        return this;
    }

    /**
     * Specifies an action to take place when the next animation ends. The action is only
     * run if the animation ends normally; if the ViewPropertyAnimator is canceled during
     * that animation, the runnable will not run.
     * This method, along with {@link #withStartAction(Runnable)}, is intended to help facilitate
     * choreographing ViewPropertyAnimator animations with other animations or actions
     * in the application.
     *
     * <p>For example, the following code animates a view to x=200 and then back to 0:</p>
     * <pre>
     *     Runnable endAction = new Runnable() {
     *         public void run() {
     *             view.animate().x(0);
     *         }
     *     };
     *     view.animate().x(200).onEnd(endAction);
     * </pre>
     *
     * @param runnable The action to run when the next animation ends.
     * @return This object, allowing calls to methods in this class to be chained.
     */
    public ViewPropertyAnimator withEndAction(Runnable runnable) {
        mPendingOnEndAction = runnable;
        if (runnable != null && mAnimatorOnEndMap == null) {
            mAnimatorOnEndMap = new HashMap<Animator, Runnable>();
        }
        return this;
    }

    /**
     * Starts the underlying Animator for a set of properties. We use a single animator that
     * simply runs from 0 to 1, and then use that fractional value to set each property
     * value accordingly.
     */
    private void startAnimation() {
        ValueAnimator animator = ValueAnimator.ofFloat(1.0f);
        ArrayList<NameValuesHolder> nameValueList =
                (ArrayList<NameValuesHolder>) mPendingAnimations.clone();
        mPendingAnimations.clear();
        int propertyMask = 0;
        int propertyCount = nameValueList.size();
        for (int i = 0; i < propertyCount; ++i) {
            NameValuesHolder nameValuesHolder = nameValueList.get(i);
            propertyMask |= nameValuesHolder.mNameConstant;
        }
        mAnimatorMap.put(animator, new PropertyBundle(propertyMask, nameValueList));
        if (mPendingSetupAction != null) {
            mAnimatorSetupMap.put(animator, mPendingSetupAction);
            mPendingSetupAction = null;
        }
        if (mPendingCleanupAction != null) {
            mAnimatorCleanupMap.put(animator, mPendingCleanupAction);
            mPendingCleanupAction = null;
        }
        if (mPendingOnStartAction != null) {
            mAnimatorOnStartMap.put(animator, mPendingOnStartAction);
            mPendingOnStartAction = null;
        }
        if (mPendingOnEndAction != null) {
            mAnimatorOnEndMap.put(animator, mPendingOnEndAction);
            mPendingOnEndAction = null;
        }
        animator.addUpdateListener(mAnimatorEventListener);
        animator.addListener(mAnimatorEventListener);
        if (mStartDelaySet) {
            animator.setStartDelay(mStartDelay);
        }
        if (mDurationSet) {
            animator.setDuration(mDuration);
        }
        if (mInterpolatorSet) {
            animator.setInterpolator(mInterpolator);
        }
        animator.start();
    }

    /**
     * Utility function, called by the various x(), y(), etc. methods. This stores the
     * constant name for the property along with the from/delta values that will be used to
     * calculate and set the property during the animation. This structure is added to the
     * pending animations, awaiting the eventual start() of the underlying animator. A
     * Runnable is posted to start the animation, and any pending such Runnable is canceled
     * (which enables us to end up starting just one animator for all of the properties
     * specified at one time).
     *
     * @param constantName The specifier for the property being animated
     * @param toValue The value to which the property will animate
     */
    private void animateProperty(int constantName, float toValue) {
        float fromValue = getValue(constantName);
        float deltaValue = toValue - fromValue;
        animatePropertyBy(constantName, fromValue, deltaValue);
    }

    /**
     * Utility function, called by the various xBy(), yBy(), etc. methods. This method is
     * just like animateProperty(), except the value is an offset from the property's
     * current value, instead of an absolute "to" value.
     *
     * @param constantName The specifier for the property being animated
     * @param byValue The amount by which the property will change
     */
    private void animatePropertyBy(int constantName, float byValue) {
        float fromValue = getValue(constantName);
        animatePropertyBy(constantName, fromValue, byValue);
    }

    /**
     * Utility function, called by animateProperty() and animatePropertyBy(), which handles the
     * details of adding a pending animation and posting the request to start the animation.
     *
     * @param constantName The specifier for the property being animated
     * @param startValue The starting value of the property
     * @param byValue The amount by which the property will change
     */
    private void animatePropertyBy(int constantName, float startValue, float byValue) {
        // First, cancel any existing animations on this property
        if (mAnimatorMap.size() > 0) {
            Animator animatorToCancel = null;
            Set<Animator> animatorSet = mAnimatorMap.keySet();
            for (Animator runningAnim : animatorSet) {
                PropertyBundle bundle = mAnimatorMap.get(runningAnim);
                if (bundle.cancel(constantName)) {
                    // property was canceled - cancel the animation if it's now empty
                    // Note that it's safe to break out here because every new animation
                    // on a property will cancel a previous animation on that property, so
                    // there can only ever be one such animation running.
                    if (bundle.mPropertyMask == NONE) {
                        // the animation is no longer changing anything - cancel it
                        animatorToCancel = runningAnim;
                        break;
                    }
                }
            }
            if (animatorToCancel != null) {
                animatorToCancel.cancel();
            }
        }

        NameValuesHolder nameValuePair = new NameValuesHolder(constantName, startValue, byValue);
        mPendingAnimations.add(nameValuePair);
        mView.removeCallbacks(mAnimationStarter);
        mView.post(mAnimationStarter);
    }

    /**
     * This method handles setting the property values directly in the View object's fields.
     * propertyConstant tells it which property should be set, value is the value to set
     * the property to.
     *
     * @param propertyConstant The property to be set
     * @param value The value to set the property to
     */
    private void setValue(int propertyConstant, float value) {
        final View.TransformationInfo info = mView.mTransformationInfo;
        switch (propertyConstant) {
            case TRANSLATION_X:
                info.mTranslationX = value;
                break;
            case TRANSLATION_Y:
                info.mTranslationY = value;
                break;
            case ROTATION:
                info.mRotation = value;
                break;
            case ROTATION_X:
                info.mRotationX = value;
                break;
            case ROTATION_Y:
                info.mRotationY = value;
                break;
            case SCALE_X:
                info.mScaleX = value;
                break;
            case SCALE_Y:
                info.mScaleY = value;
                break;
            case X:
                info.mTranslationX = value - mView.mLeft;
                break;
            case Y:
                info.mTranslationY = value - mView.mTop;
                break;
            case ALPHA:
                info.mAlpha = value;
                break;
        }
    }

    /**
     * This method gets the value of the named property from the View object.
     *
     * @param propertyConstant The property whose value should be returned
     * @return float The value of the named property
     */
    private float getValue(int propertyConstant) {
        final View.TransformationInfo info = mView.mTransformationInfo;
        switch (propertyConstant) {
            case TRANSLATION_X:
                return info.mTranslationX;
            case TRANSLATION_Y:
                return info.mTranslationY;
            case ROTATION:
                return info.mRotation;
            case ROTATION_X:
                return info.mRotationX;
            case ROTATION_Y:
                return info.mRotationY;
            case SCALE_X:
                return info.mScaleX;
            case SCALE_Y:
                return info.mScaleY;
            case X:
                return mView.mLeft + info.mTranslationX;
            case Y:
                return mView.mTop + info.mTranslationY;
            case ALPHA:
                return info.mAlpha;
        }
        return 0;
    }

    /**
     * Utility class that handles the various Animator events. The only ones we care
     * about are the end event (which we use to clean up the animator map when an animator
     * finishes) and the update event (which we use to calculate the current value of each
     * property and then set it on the view object).
     */
    private class AnimatorEventListener
            implements Animator.AnimatorListener, ValueAnimator.AnimatorUpdateListener {
        @Override
        public void onAnimationStart(Animator animation) {
            if (mAnimatorSetupMap != null) {
                Runnable r = mAnimatorSetupMap.get(animation);
                if (r != null) {
                    r.run();
                }
                mAnimatorSetupMap.remove(animation);
            }
            if (mAnimatorOnStartMap != null) {
                Runnable r = mAnimatorOnStartMap.get(animation);
                if (r != null) {
                    r.run();
                }
                mAnimatorOnStartMap.remove(animation);
            }
            if (mListener != null) {
                mListener.onAnimationStart(animation);
            }
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            if (mListener != null) {
                mListener.onAnimationCancel(animation);
            }
            if (mAnimatorOnEndMap != null) {
                mAnimatorOnEndMap.remove(animation);
            }
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
            if (mListener != null) {
                mListener.onAnimationRepeat(animation);
            }
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            if (mListener != null) {
                mListener.onAnimationEnd(animation);
            }
            if (mAnimatorOnEndMap != null) {
                Runnable r = mAnimatorOnEndMap.get(animation);
                if (r != null) {
                    r.run();
                }
                mAnimatorOnEndMap.remove(animation);
            }
            if (mAnimatorCleanupMap != null) {
                Runnable r = mAnimatorCleanupMap.get(animation);
                if (r != null) {
                    r.run();
                }
                mAnimatorCleanupMap.remove(animation);
            }
            mAnimatorMap.remove(animation);
        }

        /**
         * Calculate the current value for each property and set it on the view. Invalidate
         * the view object appropriately, depending on which properties are being animated.
         *
         * @param animation The animator associated with the properties that need to be
         * set. This animator holds the animation fraction which we will use to calculate
         * the current value of each property.
         */
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            PropertyBundle propertyBundle = mAnimatorMap.get(animation);
            if (propertyBundle == null) {
                // Shouldn't happen, but just to play it safe
                return;
            }
            // alpha requires slightly different treatment than the other (transform) properties.
            // The logic in setAlpha() is not simply setting mAlpha, plus the invalidation
            // logic is dependent on how the view handles an internal call to onSetAlpha().
            // We track what kinds of properties are set, and how alpha is handled when it is
            // set, and perform the invalidation steps appropriately.
            boolean alphaHandled = false;
            mView.invalidateParentCaches();
            float fraction = animation.getAnimatedFraction();
            int propertyMask = propertyBundle.mPropertyMask;
            if ((propertyMask & TRANSFORM_MASK) != 0) {
                mView.invalidate(false);
            }
            ArrayList<NameValuesHolder> valueList = propertyBundle.mNameValuesHolder;
            if (valueList != null) {
                int count = valueList.size();
                for (int i = 0; i < count; ++i) {
                    NameValuesHolder values = valueList.get(i);
                    float value = values.mFromValue + fraction * values.mDeltaValue;
                    if (values.mNameConstant == ALPHA) {
                        alphaHandled = mView.setAlphaNoInvalidation(value);
                    } else {
                        setValue(values.mNameConstant, value);
                    }
                }
            }
            if ((propertyMask & TRANSFORM_MASK) != 0) {
                mView.mTransformationInfo.mMatrixDirty = true;
                mView.mPrivateFlags |= View.DRAWN; // force another invalidation
            }
            // invalidate(false) in all cases except if alphaHandled gets set to true
            // via the call to setAlphaNoInvalidation(), above
            mView.invalidate(alphaHandled);
        }
    }
}
