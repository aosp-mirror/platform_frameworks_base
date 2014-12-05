/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.graphics.drawable;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.annotation.NonNull;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * This class uses {@link android.animation.ObjectAnimator} and
 * {@link android.animation.AnimatorSet} to animate the properties of a
 * {@link android.graphics.drawable.VectorDrawable} to create an animated drawable.
 * <p>
 * AnimatedVectorDrawable are normally defined as 3 separate XML files.
 * </p>
 * <p>
 * First is the XML file for {@link android.graphics.drawable.VectorDrawable}.
 * Note that we allow the animation happen on the group's attributes and path's
 * attributes, which requires they are uniquely named in this xml file. Groups
 * and paths without animations do not need names.
 * </p>
 * <li>Here is a simple VectorDrawable in this vectordrawable.xml file.
 * <pre>
 * &lt;vector xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;
 *     android:height=&quot;64dp&quot;
 *     android:width=&quot;64dp&quot;
 *     android:viewportHeight=&quot;600&quot;
 *     android:viewportWidth=&quot;600&quot; &gt;
 *     &lt;group
 *         android:name=&quot;rotationGroup&quot;
 *         android:pivotX=&quot;300.0&quot;
 *         android:pivotY=&quot;300.0&quot;
 *         android:rotation=&quot;45.0&quot; &gt;
 *         &lt;path
 *             android:name=&quot;v&quot;
 *             android:fillColor=&quot;#000000&quot;
 *             android:pathData=&quot;M300,70 l 0,-70 70,70 0,0 -70,70z&quot; /&gt;
 *     &lt;/group&gt;
 * &lt;/vector&gt;
 * </pre></li>
 * <p>
 * Second is the AnimatedVectorDrawable's xml file, which defines the target
 * VectorDrawable, the target paths and groups to animate, the properties of the
 * path and group to animate and the animations defined as the ObjectAnimators
 * or AnimatorSets.
 * </p>
 * <li>Here is a simple AnimatedVectorDrawable defined in this avd.xml file.
 * Note how we use the names to refer to the groups and paths in the vectordrawable.xml.
 * <pre>
 * &lt;animated-vector xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;
 *   android:drawable=&quot;@drawable/vectordrawable&quot; &gt;
 *     &lt;target
 *         android:name=&quot;rotationGroup&quot;
 *         android:animation=&quot;@anim/rotation&quot; /&gt;
 *     &lt;target
 *         android:name=&quot;v&quot;
 *         android:animation=&quot;@anim/path_morph&quot; /&gt;
 * &lt;/animated-vector&gt;
 * </pre></li>
 * <p>
 * Last is the Animator xml file, which is the same as a normal ObjectAnimator
 * or AnimatorSet.
 * To complete this example, here are the 2 animator files used in avd.xml:
 * rotation.xml and path_morph.xml.
 * </p>
 * <li>Here is the rotation.xml, which will rotate the target group for 360 degrees.
 * <pre>
 * &lt;objectAnimator
 *     android:duration=&quot;6000&quot;
 *     android:propertyName=&quot;rotation&quot;
 *     android:valueFrom=&quot;0&quot;
 *     android:valueTo=&quot;360&quot; /&gt;
 * </pre></li>
 * <li>Here is the path_morph.xml, which will morph the path from one shape to
 * the other. Note that the paths must be compatible for morphing.
 * In more details, the paths should have exact same length of commands , and
 * exact same length of parameters for each commands.
 * Note that the path string are better stored in strings.xml for reusing.
 * <pre>
 * &lt;set xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;&gt;
 *     &lt;objectAnimator
 *         android:duration=&quot;3000&quot;
 *         android:propertyName=&quot;pathData&quot;
 *         android:valueFrom=&quot;M300,70 l 0,-70 70,70 0,0   -70,70z&quot;
 *         android:valueTo=&quot;M300,70 l 0,-70 70,0  0,140 -70,0 z&quot;
 *         android:valueType=&quot;pathType&quot;/&gt;
 * &lt;/set&gt;
 * </pre></li>
 *
 * @attr ref android.R.styleable#AnimatedVectorDrawable_drawable
 * @attr ref android.R.styleable#AnimatedVectorDrawableTarget_name
 * @attr ref android.R.styleable#AnimatedVectorDrawableTarget_animation
 */
public class AnimatedVectorDrawable extends Drawable implements Animatable {
    private static final String LOGTAG = AnimatedVectorDrawable.class.getSimpleName();

    private static final String ANIMATED_VECTOR = "animated-vector";
    private static final String TARGET = "target";

    private static final boolean DBG_ANIMATION_VECTOR_DRAWABLE = false;

    private AnimatedVectorDrawableState mAnimatedVectorState;

    private boolean mMutated;

    public AnimatedVectorDrawable() {
        this(null, null);
    }

    private AnimatedVectorDrawable(AnimatedVectorDrawableState state, Resources res) {
        mAnimatedVectorState = new AnimatedVectorDrawableState(state, mCallback, res);
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mAnimatedVectorState = new AnimatedVectorDrawableState(
                    mAnimatedVectorState, mCallback, null);
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mAnimatedVectorState.mVectorDrawable.clearMutated();
        mMutated = false;
    }

    @Override
    public ConstantState getConstantState() {
        mAnimatedVectorState.mChangingConfigurations = getChangingConfigurations();
        return mAnimatedVectorState;
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mAnimatedVectorState.mChangingConfigurations;
    }

    @Override
    public void draw(Canvas canvas) {
        mAnimatedVectorState.mVectorDrawable.draw(canvas);
        if (isStarted()) {
            invalidateSelf();
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mAnimatedVectorState.mVectorDrawable.setBounds(bounds);
    }

    @Override
    protected boolean onStateChange(int[] state) {
        return mAnimatedVectorState.mVectorDrawable.setState(state);
    }

    @Override
    protected boolean onLevelChange(int level) {
        return mAnimatedVectorState.mVectorDrawable.setLevel(level);
    }

    @Override
    public int getAlpha() {
        return mAnimatedVectorState.mVectorDrawable.getAlpha();
    }

    @Override
    public void setAlpha(int alpha) {
        mAnimatedVectorState.mVectorDrawable.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mAnimatedVectorState.mVectorDrawable.setColorFilter(colorFilter);
    }

    @Override
    public void setTintList(ColorStateList tint) {
        mAnimatedVectorState.mVectorDrawable.setTintList(tint);
    }

    @Override
    public void setHotspot(float x, float y) {
        mAnimatedVectorState.mVectorDrawable.setHotspot(x, y);
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        mAnimatedVectorState.mVectorDrawable.setHotspotBounds(left, top, right, bottom);
    }

    @Override
    public void setTintMode(PorterDuff.Mode tintMode) {
        mAnimatedVectorState.mVectorDrawable.setTintMode(tintMode);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mAnimatedVectorState.mVectorDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    /** {@hide} */
    @Override
    public void setLayoutDirection(int layoutDirection) {
        mAnimatedVectorState.mVectorDrawable.setLayoutDirection(layoutDirection);
    }

    @Override
    public boolean isStateful() {
        return mAnimatedVectorState.mVectorDrawable.isStateful();
    }

    @Override
    public int getOpacity() {
        return mAnimatedVectorState.mVectorDrawable.getOpacity();
    }

    @Override
    public int getIntrinsicWidth() {
        return mAnimatedVectorState.mVectorDrawable.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mAnimatedVectorState.mVectorDrawable.getIntrinsicHeight();
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        mAnimatedVectorState.mVectorDrawable.getOutline(outline);
    }

    @Override
    public void inflate(Resources res, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {

        int eventType = parser.getEventType();
        float pathErrorScale = 1;
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                final String tagName = parser.getName();
                if (ANIMATED_VECTOR.equals(tagName)) {
                    final TypedArray a = obtainAttributes(res, theme, attrs,
                            R.styleable.AnimatedVectorDrawable);
                    int drawableRes = a.getResourceId(
                            R.styleable.AnimatedVectorDrawable_drawable, 0);
                    if (drawableRes != 0) {
                        VectorDrawable vectorDrawable = (VectorDrawable) res.getDrawable(
                                drawableRes, theme).mutate();
                        vectorDrawable.setAllowCaching(false);
                        vectorDrawable.setCallback(mCallback);
                        pathErrorScale = vectorDrawable.getPixelSize();
                        if (mAnimatedVectorState.mVectorDrawable != null) {
                            mAnimatedVectorState.mVectorDrawable.setCallback(null);
                        }
                        mAnimatedVectorState.mVectorDrawable = vectorDrawable;
                    }
                    a.recycle();
                } else if (TARGET.equals(tagName)) {
                    final TypedArray a = obtainAttributes(res, theme, attrs,
                            R.styleable.AnimatedVectorDrawableTarget);
                    final String target = a.getString(
                            R.styleable.AnimatedVectorDrawableTarget_name);

                    int id = a.getResourceId(
                            R.styleable.AnimatedVectorDrawableTarget_animation, 0);
                    if (id != 0) {
                        Animator objectAnimator = AnimatorInflater.loadAnimator(res, theme, id,
                                pathErrorScale);
                        setupAnimatorsForTarget(target, objectAnimator);
                    }
                    a.recycle();
                }
            }

            eventType = parser.next();
        }
    }

    @Override
    public boolean canApplyTheme() {
        return (mAnimatedVectorState != null && mAnimatedVectorState.canApplyTheme())
                || super.canApplyTheme();
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final VectorDrawable vectorDrawable = mAnimatedVectorState.mVectorDrawable;
        if (vectorDrawable != null && vectorDrawable.canApplyTheme()) {
            vectorDrawable.applyTheme(t);
        }
    }

    private static class AnimatedVectorDrawableState extends ConstantState {
        int mChangingConfigurations;
        VectorDrawable mVectorDrawable;
        ArrayList<Animator> mAnimators;
        ArrayMap<Animator, String> mTargetNameMap;

        public AnimatedVectorDrawableState(AnimatedVectorDrawableState copy,
                Callback owner, Resources res) {
            if (copy != null) {
                mChangingConfigurations = copy.mChangingConfigurations;
                if (copy.mVectorDrawable != null) {
                    final ConstantState cs = copy.mVectorDrawable.getConstantState();
                    if (res != null) {
                        mVectorDrawable = (VectorDrawable) cs.newDrawable(res);
                    } else {
                        mVectorDrawable = (VectorDrawable) cs.newDrawable();
                    }
                    mVectorDrawable = (VectorDrawable) mVectorDrawable.mutate();
                    mVectorDrawable.setCallback(owner);
                    mVectorDrawable.setLayoutDirection(copy.mVectorDrawable.getLayoutDirection());
                    mVectorDrawable.setBounds(copy.mVectorDrawable.getBounds());
                    mVectorDrawable.setAllowCaching(false);
                }
                if (copy.mAnimators != null) {
                    final int numAnimators = copy.mAnimators.size();
                    mAnimators = new ArrayList<Animator>(numAnimators);
                    mTargetNameMap = new ArrayMap<Animator, String>(numAnimators);
                    for (int i = 0; i < numAnimators; ++i) {
                        Animator anim = copy.mAnimators.get(i);
                        Animator animClone = anim.clone();
                        String targetName = copy.mTargetNameMap.get(anim);
                        Object targetObject = mVectorDrawable.getTargetByName(targetName);
                        animClone.setTarget(targetObject);
                        mAnimators.add(animClone);
                        mTargetNameMap.put(animClone, targetName);
                    }
                }
            } else {
                mVectorDrawable = new VectorDrawable();
            }
        }

        @Override
        public boolean canApplyTheme() {
            return (mVectorDrawable != null && mVectorDrawable.canApplyTheme())
                    || super.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new AnimatedVectorDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new AnimatedVectorDrawable(this, res);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    private void setupAnimatorsForTarget(String name, Animator animator) {
        Object target = mAnimatedVectorState.mVectorDrawable.getTargetByName(name);
        animator.setTarget(target);
        if (mAnimatedVectorState.mAnimators == null) {
            mAnimatedVectorState.mAnimators = new ArrayList<Animator>();
            mAnimatedVectorState.mTargetNameMap = new ArrayMap<Animator, String>();
        }
        mAnimatedVectorState.mAnimators.add(animator);
        mAnimatedVectorState.mTargetNameMap.put(animator, name);
        if (DBG_ANIMATION_VECTOR_DRAWABLE) {
            Log.v(LOGTAG, "add animator  for target " + name + " " + animator);
        }
    }

    @Override
    public boolean isRunning() {
        final ArrayList<Animator> animators = mAnimatedVectorState.mAnimators;
        final int size = animators.size();
        for (int i = 0; i < size; i++) {
            final Animator animator = animators.get(i);
            if (animator.isRunning()) {
                return true;
            }
        }
        return false;
    }

    private boolean isStarted() {
        final ArrayList<Animator> animators = mAnimatedVectorState.mAnimators;
        final int size = animators.size();
        for (int i = 0; i < size; i++) {
            final Animator animator = animators.get(i);
            if (animator.isStarted()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void start() {
        // If any one of the animator has not ended, do nothing.
        if (isStarted()) {
            return;
        }
        // Otherwise, kick off every animator.
        final ArrayList<Animator> animators = mAnimatedVectorState.mAnimators;
        final int size = animators.size();
        for (int i = 0; i < size; i++) {
            final Animator animator = animators.get(i);
            animator.start();
        }
        invalidateSelf();
    }

    @Override
    public void stop() {
        final ArrayList<Animator> animators = mAnimatedVectorState.mAnimators;
        final int size = animators.size();
        for (int i = 0; i < size; i++) {
            final Animator animator = animators.get(i);
            animator.end();
        }
    }

    /**
     * Reverses ongoing animations or starts pending animations in reverse.
     * <p>
     * NOTE: Only works if all animations support reverse. Otherwise, this will
     * do nothing.
     * @hide
     */
    public void reverse() {
        // Only reverse when all the animators can be reverse. Otherwise, partially
        // reverse is confusing.
        if (!canReverse()) {
            Log.w(LOGTAG, "AnimatedVectorDrawable can't reverse()");
            return;
        }
        final ArrayList<Animator> animators = mAnimatedVectorState.mAnimators;
        final int size = animators.size();
        for (int i = 0; i < size; i++) {
            final Animator animator = animators.get(i);
            animator.reverse();
        }
    }

    /**
     * @hide
     */
    public boolean canReverse() {
        final ArrayList<Animator> animators = mAnimatedVectorState.mAnimators;
        final int size = animators.size();
        for (int i = 0; i < size; i++) {
            final Animator animator = animators.get(i);
            if (!animator.canReverse()) {
                return false;
            }
        }
        return true;
    }

    private final Callback mCallback = new Callback() {
        @Override
        public void invalidateDrawable(Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            unscheduleSelf(what);
        }
    };
}
