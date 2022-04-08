/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view.animation;

import android.annotation.AnimRes;
import android.annotation.InterpolatorRes;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.Random;

/**
 * A layout animation controller is used to animated a layout's, or a view
 * group's, children. Each child uses the same animation but for every one of
 * them, the animation starts at a different time. A layout animation controller
 * is used by {@link android.view.ViewGroup} to compute the delay by which each
 * child's animation start must be offset. The delay is computed by using
 * characteristics of each child, like its index in the view group.
 *
 * This standard implementation computes the delay by multiplying a fixed
 * amount of miliseconds by the index of the child in its parent view group.
 * Subclasses are supposed to override
 * {@link #getDelayForView(android.view.View)} to implement a different way
 * of computing the delay. For instance, a
 * {@link android.view.animation.GridLayoutAnimationController} will compute the
 * delay based on the column and row indices of the child in its parent view
 * group.
 *
 * Information used to compute the animation delay of each child are stored
 * in an instance of
 * {@link android.view.animation.LayoutAnimationController.AnimationParameters},
 * itself stored in the {@link android.view.ViewGroup.LayoutParams} of the view.
 *
 * @attr ref android.R.styleable#LayoutAnimation_delay
 * @attr ref android.R.styleable#LayoutAnimation_animationOrder
 * @attr ref android.R.styleable#LayoutAnimation_interpolator
 * @attr ref android.R.styleable#LayoutAnimation_animation
 */
public class LayoutAnimationController {
    /**
     * Distributes the animation delays in the order in which view were added
     * to their view group.
     */
    public static final int ORDER_NORMAL  = 0;

    /**
     * Distributes the animation delays in the reverse order in which view were
     * added to their view group.
     */
    public static final int ORDER_REVERSE = 1;

    /**
     * Randomly distributes the animation delays.
     */
    public static final int ORDER_RANDOM  = 2;

    /**
     * The animation applied on each child of the view group on which this
     * layout animation controller is set.
     */
    protected Animation mAnimation;

    /**
     * The randomizer used when the order is set to random. Subclasses should
     * use this object to avoid creating their own.
     */
    protected Random mRandomizer;

    /**
     * The interpolator used to interpolate the delays.
     */
    protected Interpolator mInterpolator;

    private float mDelay;
    private int mOrder;

    private long mDuration;
    private long mMaxDelay;    

    /**
     * Creates a new layout animation controller from external resources.
     *
     * @param context the Context the view  group is running in, through which
     *        it can access the resources
     * @param attrs the attributes of the XML tag that is inflating the
     *        layout animation controller
     */
    public LayoutAnimationController(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.LayoutAnimation);

        Animation.Description d = Animation.Description.parseValue(
                a.peekValue(com.android.internal.R.styleable.LayoutAnimation_delay));
        mDelay = d.value;

        mOrder = a.getInt(com.android.internal.R.styleable.LayoutAnimation_animationOrder, ORDER_NORMAL);

        int resource = a.getResourceId(com.android.internal.R.styleable.LayoutAnimation_animation, 0);
        if (resource > 0) {
            setAnimation(context, resource);
        }

        resource = a.getResourceId(com.android.internal.R.styleable.LayoutAnimation_interpolator, 0);
        if (resource > 0) {
            setInterpolator(context, resource);
        }

        a.recycle();
    }

    /**
     * Creates a new layout animation controller with a delay of 50%
     * and the specified animation.
     *
     * @param animation the animation to use on each child of the view group
     */
    public LayoutAnimationController(Animation animation) {
        this(animation, 0.5f);
    }

    /**
     * Creates a new layout animation controller with the specified delay
     * and the specified animation.
     *
     * @param animation the animation to use on each child of the view group
     * @param delay the delay by which each child's animation must be offset
     */
    public LayoutAnimationController(Animation animation, float delay) {
        mDelay = delay;
        setAnimation(animation);
    }

    /**
     * Returns the order used to compute the delay of each child's animation.
     *
     * @return one of {@link #ORDER_NORMAL}, {@link #ORDER_REVERSE} or
     *         {@link #ORDER_RANDOM}
     *
     * @attr ref android.R.styleable#LayoutAnimation_animationOrder
     */
    public int getOrder() {
        return mOrder;
    }

    /**
     * Sets the order used to compute the delay of each child's animation.
     *
     * @param order one of {@link #ORDER_NORMAL}, {@link #ORDER_REVERSE} or
     *        {@link #ORDER_RANDOM}
     *
     * @attr ref android.R.styleable#LayoutAnimation_animationOrder
     */
    public void setOrder(int order) {
        mOrder = order;
    }

    /**
     * Sets the animation to be run on each child of the view group on which
     * this layout animation controller is .
     *
     * @param context the context from which the animation must be inflated
     * @param resourceID the resource identifier of the animation
     *
     * @see #setAnimation(Animation)
     * @see #getAnimation() 
     *
     * @attr ref android.R.styleable#LayoutAnimation_animation
     */
    public void setAnimation(Context context, @AnimRes int resourceID) {
        setAnimation(AnimationUtils.loadAnimation(context, resourceID));
    }

    /**
     * Sets the animation to be run on each child of the view group on which
     * this layout animation controller is .
     *
     * @param animation the animation to run on each child of the view group

     * @see #setAnimation(android.content.Context, int)
     * @see #getAnimation()
     *
     * @attr ref android.R.styleable#LayoutAnimation_animation
     */
    public void setAnimation(Animation animation) {
        mAnimation = animation;
        mAnimation.setFillBefore(true);
    }

    /**
     * Returns the animation applied to each child of the view group on which
     * this controller is set.
     *
     * @return an {@link android.view.animation.Animation} instance
     *
     * @see #setAnimation(android.content.Context, int)
     * @see #setAnimation(Animation)
     */
    public Animation getAnimation() {
        return mAnimation;
    }

    /**
     * Sets the interpolator used to interpolate the delays between the
     * children.
     *
     * @param context the context from which the interpolator must be inflated
     * @param resourceID the resource identifier of the interpolator
     *
     * @see #getInterpolator()
     * @see #setInterpolator(Interpolator)
     *
     * @attr ref android.R.styleable#LayoutAnimation_interpolator
     */
    public void setInterpolator(Context context, @InterpolatorRes int resourceID) {
        setInterpolator(AnimationUtils.loadInterpolator(context, resourceID));
    }

    /**
     * Sets the interpolator used to interpolate the delays between the
     * children.
     *
     * @param interpolator the interpolator
     *
     * @see #getInterpolator()
     * @see #setInterpolator(Interpolator)
     *
     * @attr ref android.R.styleable#LayoutAnimation_interpolator
     */
    public void setInterpolator(Interpolator interpolator) {
        mInterpolator = interpolator;
    }

    /**
     * Returns the interpolator used to interpolate the delays between the
     * children.
     *
     * @return an {@link android.view.animation.Interpolator}
     */
    public Interpolator getInterpolator() {
        return mInterpolator;
    }

    /**
     * Returns the delay by which the children's animation are offset. The
     * delay is expressed as a fraction of the animation duration.
     *
     * @return a fraction of the animation duration
     *
     * @see #setDelay(float)
     */
    public float getDelay() {
        return mDelay;
    }

    /**
     * Sets the delay, as a fraction of the animation duration, by which the
     * children's animations are offset. The general formula is:
     *
     * <pre>
     * child animation delay = child index * delay * animation duration
     * </pre>
     *
     * @param delay a fraction of the animation duration
     *
     * @see #getDelay()
     */
    public void setDelay(float delay) {
        mDelay = delay;
    }

    /**
     * Indicates whether two children's animations will overlap. Animations
     * overlap when the delay is lower than 100% (or 1.0).
     *
     * @return true if animations will overlap, false otherwise
     */
    public boolean willOverlap() {
        return mDelay < 1.0f;
    }

    /**
     * Starts the animation.
     */
    public void start() {
        mDuration = mAnimation.getDuration();
        mMaxDelay = Long.MIN_VALUE;
        mAnimation.setStartTime(-1);
    }

    /**
     * Returns the animation to be applied to the specified view. The returned
     * animation is delayed by an offset computed according to the information
     * provided by
     * {@link android.view.animation.LayoutAnimationController.AnimationParameters}.
     * This method is called by view groups to obtain the animation to set on
     * a specific child.
     *
     * @param view the view to animate
     * @return an animation delayed by the number of milliseconds returned by
     *         {@link #getDelayForView(android.view.View)}
     *
     * @see #getDelay()
     * @see #setDelay(float)
     * @see #getDelayForView(android.view.View)
     */
    public final Animation getAnimationForView(View view) {
        final long delay = getDelayForView(view) + mAnimation.getStartOffset();
        mMaxDelay = Math.max(mMaxDelay, delay);

        try {
            final Animation animation = mAnimation.clone();
            animation.setStartOffset(delay);
            return animation;
        } catch (CloneNotSupportedException e) {
            return null;
        }
    }

    /**
     * Indicates whether the layout animation is over or not. A layout animation
     * is considered done when the animation with the longest delay is done.
     *
     * @return true if all of the children's animations are over, false otherwise
     */
    public boolean isDone() {
        return AnimationUtils.currentAnimationTimeMillis() >
                mAnimation.getStartTime() + mMaxDelay + mDuration;
    }

    /**
     * Returns the amount of milliseconds by which the specified view's
     * animation must be delayed or offset. Subclasses should override this
     * method to return a suitable value.
     *
     * This implementation returns <code>child animation delay</code>
     * milliseconds where:
     *
     * <pre>
     * child animation delay = child index * delay
     * </pre>
     *
     * The index is retrieved from the
     * {@link android.view.animation.LayoutAnimationController.AnimationParameters}
     * found in the view's {@link android.view.ViewGroup.LayoutParams}.
     *
     * @param view the view for which to obtain the animation's delay
     * @return a delay in milliseconds
     *
     * @see #getAnimationForView(android.view.View)
     * @see #getDelay()
     * @see #getTransformedIndex(android.view.animation.LayoutAnimationController.AnimationParameters)
     * @see android.view.ViewGroup.LayoutParams
     */
    protected long getDelayForView(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        AnimationParameters params = lp.layoutAnimationParameters;

        if (params == null) {
            return 0;
        }

        final float delay = mDelay * mAnimation.getDuration();
        final long viewDelay = (long) (getTransformedIndex(params) * delay);
        final float totalDelay = delay * params.count;

        if (mInterpolator == null) {
            mInterpolator = new LinearInterpolator();
        }

        float normalizedDelay = viewDelay / totalDelay;
        normalizedDelay = mInterpolator.getInterpolation(normalizedDelay);

        return (long) (normalizedDelay * totalDelay);
    }

    /**
     * Transforms the index stored in
     * {@link android.view.animation.LayoutAnimationController.AnimationParameters}
     * by the order returned by {@link #getOrder()}. Subclasses should override
     * this method to provide additional support for other types of ordering.
     * This method should be invoked by
     * {@link #getDelayForView(android.view.View)} prior to any computation. 
     *
     * @param params the animation parameters containing the index
     * @return a transformed index
     */
    protected int getTransformedIndex(AnimationParameters params) {
        switch (getOrder()) {
            case ORDER_REVERSE:
                return params.count - 1 - params.index;
            case ORDER_RANDOM:
                if (mRandomizer == null) {
                    mRandomizer = new Random();
                }
                return (int) (params.count * mRandomizer.nextFloat());
            case ORDER_NORMAL:
            default:
                return params.index;
        }
    }

    /**
     * The set of parameters that has to be attached to each view contained in
     * the view group animated by the layout animation controller. These
     * parameters are used to compute the start time of each individual view's
     * animation.
     */
    public static class AnimationParameters {
        /**
         * The number of children in the view group containing the view to which
         * these parameters are attached.
         */
        public int count;

        /**
         * The index of the view to which these parameters are attached in its
         * containing view group.
         */
        public int index;
    }
}
