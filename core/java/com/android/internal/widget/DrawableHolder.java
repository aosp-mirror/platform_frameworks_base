/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.widget;

import java.util.ArrayList;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.Animator.AnimatorListener;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;
import android.view.animation.DecelerateInterpolator;

/**
 * This class is a container for a Drawable with multiple animated properties.
 *
 */
public class DrawableHolder implements AnimatorListener {
    public static final DecelerateInterpolator EASE_OUT_INTERPOLATOR = new DecelerateInterpolator();
    private static final String TAG = "DrawableHolder";
    private static final boolean DBG = false;
    private float mX = 0.0f;
    private float mY = 0.0f;
    private float mScaleX = 1.0f;
    private float mScaleY = 1.0f;
    private BitmapDrawable mDrawable;
    private float mAlpha = 1f;
    private ArrayList<ObjectAnimator> mAnimators = new ArrayList<ObjectAnimator>();
    private ArrayList<ObjectAnimator> mNeedToStart = new ArrayList<ObjectAnimator>();

    public DrawableHolder(BitmapDrawable drawable) {
        this(drawable, 0.0f, 0.0f);
    }

    public DrawableHolder(BitmapDrawable drawable, float x, float y) {
        mDrawable = drawable;
        mX = x;
        mY = y;
        mDrawable.getPaint().setAntiAlias(true); // Force AA
        mDrawable.setBounds(0, 0, mDrawable.getIntrinsicWidth(), mDrawable.getIntrinsicHeight());
    }

    /**
     *
     * Adds an animation that interpolates given property from its current value
     * to the given value.
     *
     * @param duration the duration, in ms.
     * @param delay the delay to start the animation, in ms.
     * @param property the property to animate
     * @param toValue the target value
     * @param replace if true, replace the current animation with this one.
     */
    public ObjectAnimator addAnimTo(long duration, long delay,
            String property, float toValue, boolean replace) {

        if (replace) removeAnimationFor(property);

        ObjectAnimator anim = ObjectAnimator.ofFloat(this, property, toValue);
        anim.setDuration(duration);
        anim.setStartDelay(delay);
        anim.setInterpolator(EASE_OUT_INTERPOLATOR);
        this.addAnimation(anim, replace);
        if (DBG) Log.v(TAG, "animationCount = " + mAnimators.size());
        return anim;
    }

    /**
     * Stops all animations for the given property and removes it from the list.
     *
     * @param property
     */
    public void removeAnimationFor(String property) {
        ArrayList<ObjectAnimator> removalList = (ArrayList<ObjectAnimator>)mAnimators.clone();
        for (ObjectAnimator currentAnim : removalList) {
            if (property.equals(currentAnim.getPropertyName())) {
                currentAnim.cancel();
            }
        }
    }

    /**
     * Stops all animations and removes them from the list.
     */
    public void clearAnimations() {
        for (ObjectAnimator currentAnim : mAnimators) {
            currentAnim.cancel();
        }
        mAnimators.clear();
    }

    /**
     * Adds the given animation to the list of animations for this object.
     *
     * @param anim
     * @param overwrite
     * @return
     */
    private DrawableHolder addAnimation(ObjectAnimator anim, boolean overwrite) {
        if (anim != null)
            mAnimators.add(anim);
        mNeedToStart.add(anim);
        return this;
    }

    /**
     * Draw this object to the canvas using the properties defined in this class.
     *
     * @param canvas canvas to draw into
     */
    public void draw(Canvas canvas) {
        final float threshold = 1.0f / 256.0f; // contribution less than 1 LSB of RGB byte
        if (mAlpha <= threshold) // don't bother if it won't show up
            return;
        canvas.save(Canvas.MATRIX_SAVE_FLAG);
        canvas.translate(mX, mY);
        canvas.scale(mScaleX, mScaleY);
        canvas.translate(-0.5f*getWidth(), -0.5f*getHeight());
        mDrawable.setAlpha((int) Math.round(mAlpha * 255f));
        mDrawable.draw(canvas);
        canvas.restore();
    }

    /**
     * Starts all animations added since the last call to this function.  Used to synchronize
     * animations.
     *
     * @param listener an optional listener to add to the animations. Typically used to know when
     * to invalidate the surface these are being drawn to.
     */
    public void startAnimations(ValueAnimator.AnimatorUpdateListener listener) {
        for (int i = 0; i < mNeedToStart.size(); i++) {
            ObjectAnimator anim = mNeedToStart.get(i);
            anim.addUpdateListener(listener);
            anim.addListener(this);
            anim.start();
        }
        mNeedToStart.clear();
    }


    public void setX(float value) {
        mX = value;
    }

    public void setY(float value) {
        mY = value;
    }

    public void setScaleX(float value) {
        mScaleX = value;
    }

    public void setScaleY(float value) {
        mScaleY = value;
    }

    public void setAlpha(float alpha) {
        mAlpha = alpha;
    }

    public float getX() {
        return mX;
    }

    public float getY() {
        return mY;
    }

    public float getScaleX() {
        return mScaleX;
    }

    public float getScaleY() {
        return mScaleY;
    }

    public float getAlpha() {
        return mAlpha;
    }

    public BitmapDrawable getDrawable() {
        return mDrawable;
    }

    public int getWidth() {
        return mDrawable.getIntrinsicWidth();
    }

    public int getHeight() {
        return mDrawable.getIntrinsicHeight();
    }

    public void onAnimationCancel(Animator animation) {

    }

    public void onAnimationEnd(Animator animation) {
        mAnimators.remove(animation);
    }

    public void onAnimationRepeat(Animator animation) {

    }

    public void onAnimationStart(Animator animation) {

    }
}
