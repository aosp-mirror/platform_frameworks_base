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
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.animation.NativeInterpolatorFactory;
import android.os.Build;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.LongArray;
import android.util.PathParser;
import android.util.Property;
import android.util.TimeUtils;
import android.view.Choreographer;
import android.view.NativeVectorDrawableAnimator;
import android.view.View;

import com.android.internal.R;
import com.android.internal.util.VirtualRefBasePtr;

import dalvik.annotation.optimization.FastNative;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;


/**
 * This class animates properties of a {@link android.graphics.drawable.VectorDrawable} with
 * animations defined using {@link android.animation.ObjectAnimator} or
 * {@link android.animation.AnimatorSet}.
 * <p>
 * Starting from API 25, AnimatedVectorDrawable runs on RenderThread (as opposed to on UI thread for
 * earlier APIs). This means animations in AnimatedVectorDrawable can remain smooth even when there
 * is heavy workload on the UI thread. Note: If the UI thread is unresponsive, RenderThread may
 * continue animating until the UI thread is capable of pushing another frame. Therefore, it is not
 * possible to precisely coordinate a RenderThread-enabled AnimatedVectorDrawable with UI thread
 * animations. Additionally,
 * {@link android.graphics.drawable.Animatable2.AnimationCallback#onAnimationEnd(Drawable)} will be
 * called the frame after the AnimatedVectorDrawable finishes on the RenderThread.
 * </p>
 * <p>
 * AnimatedVectorDrawable can be defined in either <a href="#ThreeXML">three separate XML files</a>,
 * or <a href="#OneXML">one XML</a>.
 * </p>
 * <a name="ThreeXML"></a>
 * <h3>Define an AnimatedVectorDrawable in three separate XML files</h3>
 * <ul>
 * <a name="VDExample"></a>
 * <li><h4>XML for the VectorDrawable containing properties to be animated</h4>
 * <p>
 * Animations can be performed on the animatable attributes in
 * {@link android.graphics.drawable.VectorDrawable}. These attributes will be animated by
 * {@link android.animation.ObjectAnimator}. The ObjectAnimator's target can be the root element,
 * a group element or a path element. The targeted elements need to be named uniquely within
 * the same VectorDrawable. Elements without animation do not need to be named.
 * </p>
 * <p>
 * Here are all the animatable attributes in {@link android.graphics.drawable.VectorDrawable}:
 * <table border="2" align="center" cellpadding="5">
 *     <thead>
 *         <tr>
 *             <th>Element Name</th>
 *             <th>Animatable attribute name</th>
 *         </tr>
 *     </thead>
 *     <tr>
 *         <td>&lt;vector&gt;</td>
 *         <td>alpha</td>
 *     </tr>
 *     <tr>
 *         <td rowspan="7">&lt;group&gt;</td>
 *         <td>rotation</td>
 *     </tr>
 *     <tr>
 *         <td>pivotX</td>
 *     </tr>
 *     <tr>
 *         <td>pivotY</td>
 *     </tr>
 *     <tr>
 *         <td>scaleX</td>
 *     </tr>
 *     <tr>
 *         <td>scaleY</td>
 *     </tr>
 *     <tr>
 *         <td>translateX</td>
 *     </tr>
 *     <tr>
 *         <td>translateY</td>
 *     </tr>
 *     <tr>
 *         <td rowspan="9">&lt;path&gt;</td>
 *         <td>pathData</td>
 *     </tr>
 *     <tr>
 *         <td>fillColor</td>
 *     </tr>
 *     <tr>
 *         <td>strokeColor</td>
 *     </tr>
 *     <tr>
 *         <td>strokeWidth</td>
 *     </tr>
 *     <tr>
 *         <td>strokeAlpha</td>
 *     </tr>
 *     <tr>
 *         <td>fillAlpha</td>
 *     </tr>
 *     <tr>
 *         <td>trimPathStart</td>
 *     </tr>
 *     <tr>
 *         <td>trimPathEnd</td>
 *     </tr>
 *     <tr>
 *         <td>trimPathOffset</td>
 *     </tr>
 *     <tr>
 *         <td>&lt;clip-path&gt;</td>
 *         <td>pathData</td>
 *     </tr>
 * </table>
 * </p>
 * Below is an example of a VectorDrawable defined in vectordrawable.xml. This VectorDrawable is
 * referred to by its file name (not including file suffix) in the
 * <a href="#AVDExample">AnimatedVectorDrawable XML example</a>.
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
 *
 * <a name="AVDExample"></a>
 * <li><h4>XML for AnimatedVectorDrawable</h4>
 * <p>
 * An AnimatedVectorDrawable element has a VectorDrawable attribute, and one or more target
 * element(s). The target element can specify its target by android:name attribute, and link the
 * target with the proper ObjectAnimator or AnimatorSet by android:animation attribute.
 * </p>
 * The following code sample defines an AnimatedVectorDrawable. Note that the names refer to the
 * groups and paths in the <a href="#VDExample">VectorDrawable XML above</a>.
 * <pre>
 * &lt;animated-vector xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;
 *     android:drawable=&quot;@drawable/vectordrawable&quot; &gt;
 *     &lt;target
 *         android:name=&quot;rotationGroup&quot;
 *         android:animation=&quot;@animator/rotation&quot; /&gt;
 *     &lt;target
 *         android:name=&quot;v&quot;
 *         android:animation=&quot;@animator/path_morph&quot; /&gt;
 * &lt;/animated-vector&gt;
 * </pre>
 * </li>
 *
 * <li><h4>XML for Animations defined using ObjectAnimator or AnimatorSet</h4>
 * <p>
 * From the previous <a href="#AVDExample">example of AnimatedVectorDrawable</a>, two animations
 * were used: rotation.xml and path_morph.xml.
 * </p>
 * rotation.xml rotates the target group from 0 degree to 360 degrees over 6000ms:
 * <pre>
 * &lt;objectAnimator
 *     android:duration=&quot;6000&quot;
 *     android:propertyName=&quot;rotation&quot;
 *     android:valueFrom=&quot;0&quot;
 *     android:valueTo=&quot;360&quot; /&gt;
 * </pre>
 *
 * path_morph.xml morphs the path from one shape into the other. Note that the paths must be
 * compatible for morphing. Specifically, the paths must have the same commands, in the same order,
 * and must have the same number of parameters for each command. It is recommended to store path
 * strings as string resources for reuse.
 * <pre>
 * &lt;set xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;&gt;
 *     &lt;objectAnimator
 *         android:duration=&quot;3000&quot;
 *         android:propertyName=&quot;pathData&quot;
 *         android:valueFrom=&quot;M300,70 l 0,-70 70,70 0,0 -70,70z&quot;
 *         android:valueTo=&quot;M300,70 l 0,-70 70,0  0,140 -70,0 z&quot;
 *         android:valueType=&quot;pathType&quot;/&gt;
 * &lt;/set&gt;
 * </pre>
 * </ul>
 * <a name="OneXML"></a>
 * <h3>Define an AnimatedVectorDrawable all in one XML file</h3>
 * <p>
 * Since the AAPT tool supports a new format that bundles several related XML files together, we can
 * merge the XML files from the previous examples into one XML file:
 * </p>
 * <pre>
 * &lt;animated-vector xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;
 *                  xmlns:aapt=&quothttp://schemas.android.com/aapt&quot; &gt;
 *     &lt;aapt:attr name="android:drawable"&gt;
 *         &lt;vector
 *             android:height=&quot;64dp&quot;
 *             android:width=&quot;64dp&quot;
 *             android:viewportHeight=&quot;600&quot;
 *             android:viewportWidth=&quot;600&quot; &gt;
 *             &lt;group
 *                 android:name=&quot;rotationGroup&quot;
 *                 android:pivotX=&quot;300.0&quot;
 *                 android:pivotY=&quot;300.0&quot;
 *                 android:rotation=&quot;45.0&quot; &gt;
 *                 &lt;path
 *                     android:name=&quot;v&quot;
 *                     android:fillColor=&quot;#000000&quot;
 *                     android:pathData=&quot;M300,70 l 0,-70 70,70 0,0 -70,70z&quot; /&gt;
 *             &lt;/group&gt;
 *         &lt;/vector&gt;
 *     &lt;/aapt:attr&gt;
 *
 *     &lt;target android:name=&quot;rotationGroup&quot;&gt; *
 *         &lt;aapt:attr name="android:animation"&gt;
 *             &lt;objectAnimator
 *             android:duration=&quot;6000&quot;
 *             android:propertyName=&quot;rotation&quot;
 *             android:valueFrom=&quot;0&quot;
 *             android:valueTo=&quot;360&quot; /&gt;
 *         &lt;/aapt:attr&gt;
 *     &lt;/target&gt;
 *
 *     &lt;target android:name=&quot;v&quot; &gt;
 *         &lt;aapt:attr name="android:animation"&gt;
 *             &lt;set&gt;
 *                 &lt;objectAnimator
 *                     android:duration=&quot;3000&quot;
 *                     android:propertyName=&quot;pathData&quot;
 *                     android:valueFrom=&quot;M300,70 l 0,-70 70,70 0,0 -70,70z&quot;
 *                     android:valueTo=&quot;M300,70 l 0,-70 70,0  0,140 -70,0 z&quot;
 *                     android:valueType=&quot;pathType&quot;/&gt;
 *             &lt;/set&gt;
 *         &lt;/aapt:attr&gt;
 *      &lt;/target&gt;
 * &lt;/animated-vector&gt;
 * </pre>
 *
 * @attr ref android.R.styleable#AnimatedVectorDrawable_drawable
 * @attr ref android.R.styleable#AnimatedVectorDrawableTarget_name
 * @attr ref android.R.styleable#AnimatedVectorDrawableTarget_animation
 */
public class AnimatedVectorDrawable extends Drawable implements Animatable2 {
    private static final String LOGTAG = "AnimatedVectorDrawable";

    private static final String ANIMATED_VECTOR = "animated-vector";
    private static final String TARGET = "target";

    private static final boolean DBG_ANIMATION_VECTOR_DRAWABLE = false;

    /** Local, mutable animator set. */
    @UnsupportedAppUsage
    private VectorDrawableAnimator mAnimatorSet;

    /**
     * The resources against which this drawable was created. Used to attempt
     * to inflate animators if applyTheme() doesn't get called.
     */
    private Resources mRes;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private AnimatedVectorDrawableState mAnimatedVectorState;

    /** The animator set that is parsed from the xml. */
    private AnimatorSet mAnimatorSetFromXml = null;

    private boolean mMutated;

    /** Use a internal AnimatorListener to support callbacks during animation events. */
    private ArrayList<Animatable2.AnimationCallback> mAnimationCallbacks = null;
    private AnimatorListener mAnimatorListener = null;

    public AnimatedVectorDrawable() {
        this(null, null);
    }

    private AnimatedVectorDrawable(AnimatedVectorDrawableState state, Resources res) {
        mAnimatedVectorState = new AnimatedVectorDrawableState(state, mCallback, res);
        mAnimatorSet = new VectorDrawableAnimatorRT(this);
        mRes = res;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mAnimatedVectorState = new AnimatedVectorDrawableState(
                    mAnimatedVectorState, mCallback, mRes);
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        if (mAnimatedVectorState.mVectorDrawable != null) {
            mAnimatedVectorState.mVectorDrawable.clearMutated();
        }
        mMutated = false;
    }

    /**
     * In order to avoid breaking old apps, we only throw exception on invalid VectorDrawable
     * animations for apps targeting N and later. For older apps, we ignore (i.e. quietly skip)
     * these animations.
     *
     * @return whether invalid animations for vector drawable should be ignored.
     */
    private static boolean shouldIgnoreInvalidAnimation() {
        return android.graphics.Compatibility.getTargetSdkVersion() < Build.VERSION_CODES.N;
    }

    @Override
    public ConstantState getConstantState() {
        mAnimatedVectorState.mChangingConfigurations = getChangingConfigurations();
        return mAnimatedVectorState;
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mAnimatedVectorState.getChangingConfigurations();
    }

    /**
     * Draws the AnimatedVectorDrawable into the given canvas.
     * <p>
     * <strong>Note:</strong> Calling this method with a software canvas when the
     * AnimatedVectorDrawable is being animated on RenderThread (for API 25 and later) may yield
     * outdated result, as the UI thread is not guaranteed to be in sync with RenderThread on
     * VectorDrawable's property changes during RenderThread animations.
     * </p>
     *
     * @param canvas The canvas to draw into
     */
    @Override
    public void draw(Canvas canvas) {
        if (!canvas.isHardwareAccelerated() && mAnimatorSet instanceof VectorDrawableAnimatorRT) {
            // If we have SW canvas and the RT animation is waiting to start, We need to fallback
            // to UI thread animation for AVD.
            if (!mAnimatorSet.isRunning() &&
                    ((VectorDrawableAnimatorRT) mAnimatorSet).mPendingAnimationActions.size() > 0) {
                fallbackOntoUI();
            }
        }
        mAnimatorSet.onDraw(canvas);
        mAnimatedVectorState.mVectorDrawable.draw(canvas);
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
    public boolean onLayoutDirectionChanged(@View.ResolvedLayoutDir int layoutDirection) {
        return mAnimatedVectorState.mVectorDrawable.setLayoutDirection(layoutDirection);
    }

    /**
     * For API 25 and later, AnimatedVectorDrawable runs on RenderThread. Therefore, when the
     * root alpha is being animated, this getter does not guarantee to return an up-to-date alpha
     * value.
     *
     * @return the containing vector drawable's root alpha value.
     */
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
    public ColorFilter getColorFilter() {
        return mAnimatedVectorState.mVectorDrawable.getColorFilter();
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
    public void setTintBlendMode(@NonNull BlendMode blendMode) {
        mAnimatedVectorState.mVectorDrawable.setTintBlendMode(blendMode);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        if (mAnimatorSet.isInfinite() && mAnimatorSet.isStarted()) {
            if (visible) {
                // Resume the infinite animation when the drawable becomes visible again.
                mAnimatorSet.resume();
            } else {
                // Pause the infinite animation once the drawable is no longer visible.
                mAnimatorSet.pause();
            }
        }
        mAnimatedVectorState.mVectorDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    public boolean isStateful() {
        return mAnimatedVectorState.mVectorDrawable.isStateful();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
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
    public Insets getOpticalInsets() {
        return mAnimatedVectorState.mVectorDrawable.getOpticalInsets();
    }

    @Override
    public void inflate(Resources res, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final AnimatedVectorDrawableState state = mAnimatedVectorState;

        int eventType = parser.getEventType();
        float pathErrorScale = 1;
        final int innerDepth = parser.getDepth() + 1;

        // Parse everything until the end of the animated-vector element.
        while (eventType != XmlPullParser.END_DOCUMENT
                && (parser.getDepth() >= innerDepth || eventType != XmlPullParser.END_TAG)) {
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
                        if (state.mVectorDrawable != null) {
                            state.mVectorDrawable.setCallback(null);
                        }
                        state.mVectorDrawable = vectorDrawable;
                    }
                    a.recycle();
                } else if (TARGET.equals(tagName)) {
                    final TypedArray a = obtainAttributes(res, theme, attrs,
                            R.styleable.AnimatedVectorDrawableTarget);
                    final String target = a.getString(
                            R.styleable.AnimatedVectorDrawableTarget_name);
                    final int animResId = a.getResourceId(
                            R.styleable.AnimatedVectorDrawableTarget_animation, 0);
                    if (animResId != 0) {
                        if (theme != null) {
                            // The animator here could be ObjectAnimator or AnimatorSet.
                            final Animator animator = AnimatorInflater.loadAnimator(
                                    res, theme, animResId, pathErrorScale);
                            updateAnimatorProperty(animator, target, state.mVectorDrawable,
                                    state.mShouldIgnoreInvalidAnim);
                            state.addTargetAnimator(target, animator);
                        } else {
                            // The animation may be theme-dependent. As a
                            // workaround until Animator has full support for
                            // applyTheme(), postpone loading the animator
                            // until we have a theme in applyTheme().
                            state.addPendingAnimator(animResId, pathErrorScale, target);

                        }
                    }
                    a.recycle();
                }
            }

            eventType = parser.next();
        }

        // If we don't have any pending animations, we don't need to hold a
        // reference to the resources.
        mRes = state.mPendingAnims == null ? null : res;
    }

    private static void updateAnimatorProperty(Animator animator, String targetName,
            VectorDrawable vectorDrawable, boolean ignoreInvalidAnim) {
        if (animator instanceof ObjectAnimator) {
            // Change the property of the Animator from using reflection based on the property
            // name to a Property object that wraps the setter and getter for modifying that
            // specific property for a given object. By replacing the reflection with a direct call,
            // we can largely reduce the time it takes for a animator to modify a VD property.
            PropertyValuesHolder[] holders = ((ObjectAnimator) animator).getValues();
            for (int i = 0; i < holders.length; i++) {
                PropertyValuesHolder pvh = holders[i];
                String propertyName = pvh.getPropertyName();
                Object targetNameObj = vectorDrawable.getTargetByName(targetName);
                Property property = null;
                if (targetNameObj instanceof VectorDrawable.VObject) {
                    property = ((VectorDrawable.VObject) targetNameObj).getProperty(propertyName);
                } else if (targetNameObj instanceof VectorDrawable.VectorDrawableState) {
                    property = ((VectorDrawable.VectorDrawableState) targetNameObj)
                            .getProperty(propertyName);
                }
                if (property != null) {
                    if (containsSameValueType(pvh, property)) {
                        pvh.setProperty(property);
                    } else if (!ignoreInvalidAnim) {
                        throw new RuntimeException("Wrong valueType for Property: " + propertyName
                                + ".  Expected type: " + property.getType().toString() + ". Actual "
                                + "type defined in resources: " + pvh.getValueType().toString());

                    }
                }
            }
        } else if (animator instanceof AnimatorSet) {
            for (Animator anim : ((AnimatorSet) animator).getChildAnimations()) {
                updateAnimatorProperty(anim, targetName, vectorDrawable, ignoreInvalidAnim);
            }
        }
    }

    private static boolean containsSameValueType(PropertyValuesHolder holder, Property property) {
        Class type1 = holder.getValueType();
        Class type2 = property.getType();
        if (type1 == float.class || type1 == Float.class) {
            return type2 == float.class || type2 == Float.class;
        } else if (type1 == int.class || type1 == Integer.class) {
            return type2 == int.class || type2 == Integer.class;
        } else {
            return type1 == type2;
        }
    }

    /**
     * Force to animate on UI thread.
     * @hide
     */
    @UnsupportedAppUsage
    public void forceAnimationOnUI() {
        if (mAnimatorSet instanceof VectorDrawableAnimatorRT) {
            VectorDrawableAnimatorRT animator = (VectorDrawableAnimatorRT) mAnimatorSet;
            if (animator.isRunning()) {
                throw new UnsupportedOperationException("Cannot force Animated Vector Drawable to" +
                        " run on UI thread when the animation has started on RenderThread.");
            }
            fallbackOntoUI();
        }
    }

    private void fallbackOntoUI() {
        if (mAnimatorSet instanceof VectorDrawableAnimatorRT) {
            VectorDrawableAnimatorRT oldAnim = (VectorDrawableAnimatorRT) mAnimatorSet;
            mAnimatorSet = new VectorDrawableAnimatorUI(this);
            if (mAnimatorSetFromXml != null) {
                mAnimatorSet.init(mAnimatorSetFromXml);
            }
            // Transfer the listener from RT animator to UI animator
            if (oldAnim.mListener != null) {
                mAnimatorSet.setListener(oldAnim.mListener);
            }
            oldAnim.transferPendingActions(mAnimatorSet);
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

        if (t != null) {
            mAnimatedVectorState.inflatePendingAnimators(t.getResources(), t);
        }

        // If we don't have any pending animations, we don't need to hold a
        // reference to the resources.
        if (mAnimatedVectorState.mPendingAnims == null) {
            mRes = null;
        }
    }

    /**
     * Gets the total duration of the animation
     * @hide
     */
    public long getTotalDuration() {
        return mAnimatorSet.getTotalDuration();
    }

    private static class AnimatedVectorDrawableState extends ConstantState {
        @Config int mChangingConfigurations;
        VectorDrawable mVectorDrawable;

        private final boolean mShouldIgnoreInvalidAnim;

        /** Animators that require a theme before inflation. */
        ArrayList<PendingAnimator> mPendingAnims;

        /** Fully inflated animators awaiting cloning into an AnimatorSet. */
        ArrayList<Animator> mAnimators;

        /** Map of animators to their target object names */
        ArrayMap<Animator, String> mTargetNameMap;

        public AnimatedVectorDrawableState(AnimatedVectorDrawableState copy,
                Callback owner, Resources res) {
            mShouldIgnoreInvalidAnim = shouldIgnoreInvalidAnimation();
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
                    mAnimators = new ArrayList<>(copy.mAnimators);
                }

                if (copy.mTargetNameMap != null) {
                    mTargetNameMap = new ArrayMap<>(copy.mTargetNameMap);
                }

                if (copy.mPendingAnims != null) {
                    mPendingAnims = new ArrayList<>(copy.mPendingAnims);
                }
            } else {
                mVectorDrawable = new VectorDrawable();
            }
        }

        @Override
        public boolean canApplyTheme() {
            return (mVectorDrawable != null && mVectorDrawable.canApplyTheme())
                    || mPendingAnims != null || super.canApplyTheme();
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
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        public void addPendingAnimator(int resId, float pathErrorScale, String target) {
            if (mPendingAnims == null) {
                mPendingAnims = new ArrayList<>(1);
            }
            mPendingAnims.add(new PendingAnimator(resId, pathErrorScale, target));
        }

        public void addTargetAnimator(String targetName, Animator animator) {
            if (mAnimators == null) {
                mAnimators = new ArrayList<>(1);
                mTargetNameMap = new ArrayMap<>(1);
            }
            mAnimators.add(animator);
            mTargetNameMap.put(animator, targetName);

            if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                Log.v(LOGTAG, "add animator  for target " + targetName + " " + animator);
            }
        }

        /**
         * Prepares a local set of mutable animators based on the constant
         * state.
         * <p>
         * If there are any pending uninflated animators, attempts to inflate
         * them immediately against the provided resources object.
         *
         * @param animatorSet the animator set to which the animators should
         *                    be added
         * @param res the resources against which to inflate any pending
         *            animators, or {@code null} if not available
         */
        public void prepareLocalAnimators(@NonNull AnimatorSet animatorSet,
                @Nullable Resources res) {
            // Check for uninflated animators. We can remove this after we add
            // support for Animator.applyTheme(). See comments in inflate().
            if (mPendingAnims != null) {
                // Attempt to load animators without applying a theme.
                if (res != null) {
                    inflatePendingAnimators(res, null);
                } else {
                    Log.e(LOGTAG, "Failed to load animators. Either the AnimatedVectorDrawable"
                            + " must be created using a Resources object or applyTheme() must be"
                            + " called with a non-null Theme object.");
                }

                mPendingAnims = null;
            }

            // Perform a deep copy of the constant state's animators.
            final int count = mAnimators == null ? 0 : mAnimators.size();
            if (count > 0) {
                final Animator firstAnim = prepareLocalAnimator(0);
                final AnimatorSet.Builder builder = animatorSet.play(firstAnim);
                for (int i = 1; i < count; ++i) {
                    final Animator nextAnim = prepareLocalAnimator(i);
                    builder.with(nextAnim);
                }
            }
        }

        /**
         * Prepares a local animator for the given index within the constant
         * state's list of animators.
         *
         * @param index the index of the animator within the constant state
         */
        private Animator prepareLocalAnimator(int index) {
            final Animator animator = mAnimators.get(index);
            final Animator localAnimator = animator.clone();
            final String targetName = mTargetNameMap.get(animator);
            final Object target = mVectorDrawable.getTargetByName(targetName);
            if (!mShouldIgnoreInvalidAnim) {
                if (target == null) {
                    throw new IllegalStateException("Target with the name \"" + targetName
                            + "\" cannot be found in the VectorDrawable to be animated.");
                } else if (!(target instanceof VectorDrawable.VectorDrawableState)
                        && !(target instanceof VectorDrawable.VObject)) {
                    throw new UnsupportedOperationException("Target should be either VGroup, VPath,"
                            + " or ConstantState, " + target.getClass() + " is not supported");
                }
            }
            localAnimator.setTarget(target);
            return localAnimator;
        }

        /**
         * Inflates pending animators, if any, against a theme. Clears the list of
         * pending animators.
         *
         * @param t the theme against which to inflate the animators
         */
        public void inflatePendingAnimators(@NonNull Resources res, @Nullable Theme t) {
            final ArrayList<PendingAnimator> pendingAnims = mPendingAnims;
            if (pendingAnims != null) {
                mPendingAnims = null;

                for (int i = 0, count = pendingAnims.size(); i < count; i++) {
                    final PendingAnimator pendingAnimator = pendingAnims.get(i);
                    final Animator animator = pendingAnimator.newInstance(res, t);
                    updateAnimatorProperty(animator, pendingAnimator.target, mVectorDrawable,
                            mShouldIgnoreInvalidAnim);
                    addTargetAnimator(pendingAnimator.target, animator);
                }
            }
        }

        /**
         * Basically a constant state for Animators until we actually implement
         * constant states for Animators.
         */
        private static class PendingAnimator {
            public final int animResId;
            public final float pathErrorScale;
            public final String target;

            public PendingAnimator(int animResId, float pathErrorScale, String target) {
                this.animResId = animResId;
                this.pathErrorScale = pathErrorScale;
                this.target = target;
            }

            public Animator newInstance(Resources res, Theme theme) {
                return AnimatorInflater.loadAnimator(res, theme, animResId, pathErrorScale);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return mAnimatorSet.isRunning();
    }

    /**
     * Resets the AnimatedVectorDrawable to the start state as specified in the animators.
     */
    public void reset() {
        ensureAnimatorSet();
        if (DBG_ANIMATION_VECTOR_DRAWABLE) {
            Log.w(LOGTAG, "calling reset on AVD: " +
                    ((VectorDrawable.VectorDrawableState) ((AnimatedVectorDrawableState)
                    getConstantState()).mVectorDrawable.getConstantState()).mRootName
                    + ", at: " + this);
        }
        mAnimatorSet.reset();
    }

    @Override
    public void start() {
        ensureAnimatorSet();
        if (DBG_ANIMATION_VECTOR_DRAWABLE) {
            Log.w(LOGTAG, "calling start on AVD: " +
                    ((VectorDrawable.VectorDrawableState) ((AnimatedVectorDrawableState)
                    getConstantState()).mVectorDrawable.getConstantState()).mRootName
                    + ", at: " + this);
        }
        mAnimatorSet.start();
    }

    @NonNull
    private void ensureAnimatorSet() {
        if (mAnimatorSetFromXml == null) {
            // TODO: Skip the AnimatorSet creation and init the VectorDrawableAnimator directly
            // with a list of LocalAnimators.
            mAnimatorSetFromXml = new AnimatorSet();
            mAnimatedVectorState.prepareLocalAnimators(mAnimatorSetFromXml, mRes);
            mAnimatorSet.init(mAnimatorSetFromXml);
            mRes = null;
        }
    }

    @Override
    public void stop() {
        if (DBG_ANIMATION_VECTOR_DRAWABLE) {
            Log.w(LOGTAG, "calling stop on AVD: " +
                    ((VectorDrawable.VectorDrawableState) ((AnimatedVectorDrawableState)
                            getConstantState()).mVectorDrawable.getConstantState())
                            .mRootName + ", at: " + this);
        }
        mAnimatorSet.end();
    }

    /**
     * Reverses ongoing animations or starts pending animations in reverse.
     * <p>
     * NOTE: Only works if all animations support reverse. Otherwise, this will
     * do nothing.
     * @hide
     */
    public void reverse() {
        ensureAnimatorSet();

        // Only reverse when all the animators can be reversed.
        if (!canReverse()) {
            Log.w(LOGTAG, "AnimatedVectorDrawable can't reverse()");
            return;
        }

        mAnimatorSet.reverse();
    }

    /**
     * @hide
     */
    public boolean canReverse() {
        return mAnimatorSet.canReverse();
    }

    private final Callback mCallback = new Callback() {
        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    };

    @Override
    public void registerAnimationCallback(@NonNull AnimationCallback callback) {
        if (callback == null) {
            return;
        }

        // Add listener accordingly.
        if (mAnimationCallbacks == null) {
            mAnimationCallbacks = new ArrayList<>();
        }

        mAnimationCallbacks.add(callback);

        if (mAnimatorListener == null) {
            // Create a animator listener and trigger the callback events when listener is
            // triggered.
            mAnimatorListener = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    ArrayList<AnimationCallback> tmpCallbacks = new ArrayList<>(mAnimationCallbacks);
                    int size = tmpCallbacks.size();
                    for (int i = 0; i < size; i ++) {
                        tmpCallbacks.get(i).onAnimationStart(AnimatedVectorDrawable.this);
                    }
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    ArrayList<AnimationCallback> tmpCallbacks = new ArrayList<>(mAnimationCallbacks);
                    int size = tmpCallbacks.size();
                    for (int i = 0; i < size; i ++) {
                        tmpCallbacks.get(i).onAnimationEnd(AnimatedVectorDrawable.this);
                    }
                }
            };
        }
        mAnimatorSet.setListener(mAnimatorListener);
    }

    // A helper function to clean up the animator listener in the mAnimatorSet.
    private void removeAnimatorSetListener() {
        if (mAnimatorListener != null) {
            mAnimatorSet.removeListener(mAnimatorListener);
            mAnimatorListener = null;
        }
    }

    @Override
    public boolean unregisterAnimationCallback(@NonNull AnimationCallback callback) {
        if (mAnimationCallbacks == null || callback == null) {
            // Nothing to be removed.
            return false;
        }
        boolean removed = mAnimationCallbacks.remove(callback);

        //  When the last call back unregistered, remove the listener accordingly.
        if (mAnimationCallbacks.size() == 0) {
            removeAnimatorSetListener();
        }
        return removed;
    }

    @Override
    public void clearAnimationCallbacks() {
        removeAnimatorSetListener();
        if (mAnimationCallbacks == null) {
            return;
        }

        mAnimationCallbacks.clear();
    }

    private interface VectorDrawableAnimator {
        void init(@NonNull AnimatorSet set);
        void start();
        void end();
        void reset();
        void reverse();
        boolean canReverse();
        void setListener(AnimatorListener listener);
        void removeListener(AnimatorListener listener);
        void onDraw(Canvas canvas);
        boolean isStarted();
        boolean isRunning();
        boolean isInfinite();
        void pause();
        void resume();
        long getTotalDuration();
    }

    private static class VectorDrawableAnimatorUI implements VectorDrawableAnimator {
        // mSet is only initialized in init(). So we need to check whether it is null before any
        // operation.
        private AnimatorSet mSet = null;
        private final Drawable mDrawable;
        // Caching the listener in the case when listener operation is called before the mSet is
        // setup by init().
        private ArrayList<AnimatorListener> mListenerArray = null;
        private boolean mIsInfinite = false;
        private long mTotalDuration;

        VectorDrawableAnimatorUI(@NonNull AnimatedVectorDrawable drawable) {
            mDrawable = drawable;
        }

        @Override
        public void init(@NonNull AnimatorSet set) {
            if (mSet != null) {
                // Already initialized
                throw new UnsupportedOperationException("VectorDrawableAnimator cannot be " +
                        "re-initialized");
            }
            // Keep a deep copy of the set, such that set can be still be constantly representing
            // the static content from XML file.
            mSet = set.clone();
            mTotalDuration = mSet.getTotalDuration();
            mIsInfinite = mTotalDuration == Animator.DURATION_INFINITE;

            // If there are listeners added before calling init(), now they should be setup.
            if (mListenerArray != null && !mListenerArray.isEmpty()) {
                for (int i = 0; i < mListenerArray.size(); i++) {
                    mSet.addListener(mListenerArray.get(i));
                }
                mListenerArray.clear();
                mListenerArray = null;
            }
        }

        // Although start(), reset() and reverse() should call init() already, it is better to
        // protect these functions from NPE in any situation.
        @Override
        public void start() {
            if (mSet == null || mSet.isStarted()) {
                return;
            }
            mSet.start();
            invalidateOwningView();
        }

        @Override
        public void end() {
            if (mSet == null) {
                return;
            }
            mSet.end();
        }

        @Override
        public void reset() {
            if (mSet == null) {
                return;
            }
            start();
            mSet.cancel();
        }

        @Override
        public void reverse() {
            if (mSet == null) {
                return;
            }
            mSet.reverse();
            invalidateOwningView();
        }

        @Override
        public boolean canReverse() {
            return mSet != null && mSet.canReverse();
        }

        @Override
        public void setListener(AnimatorListener listener) {
            if (mSet == null) {
                if (mListenerArray == null) {
                    mListenerArray = new ArrayList<AnimatorListener>();
                }
                mListenerArray.add(listener);
            } else {
                mSet.addListener(listener);
            }
        }

        @Override
        public void removeListener(AnimatorListener listener) {
            if (mSet == null) {
                if (mListenerArray == null) {
                    return;
                }
                mListenerArray.remove(listener);
            } else {
                mSet.removeListener(listener);
            }
        }

        @Override
        public void onDraw(Canvas canvas) {
            if (mSet != null && mSet.isStarted()) {
                invalidateOwningView();
            }
        }

        @Override
        public boolean isStarted() {
            return mSet != null && mSet.isStarted();
        }

        @Override
        public boolean isRunning() {
            return mSet != null && mSet.isRunning();
        }

        @Override
        public boolean isInfinite() {
            return mIsInfinite;
        }

        @Override
        public void pause() {
            if (mSet == null) {
                return;
            }
            mSet.pause();
        }

        @Override
        public void resume() {
            if (mSet == null) {
                return;
            }
            mSet.resume();
        }

        private void invalidateOwningView() {
            mDrawable.invalidateSelf();
        }

        @Override
        public long getTotalDuration() {
            return mTotalDuration;
        }
    }

    /**
     * @hide
     */
    public static class VectorDrawableAnimatorRT implements VectorDrawableAnimator,
            NativeVectorDrawableAnimator {
        private static final int START_ANIMATION = 1;
        private static final int REVERSE_ANIMATION = 2;
        private static final int RESET_ANIMATION = 3;
        private static final int END_ANIMATION = 4;

        // If the duration of an animation is more than 300 frames, we cap the sample size to 300.
        private static final int MAX_SAMPLE_POINTS = 300;
        private Handler mHandler;
        private AnimatorListener mListener = null;
        private final LongArray mStartDelays = new LongArray();
        private PropertyValuesHolder.PropertyValues mTmpValues =
                new PropertyValuesHolder.PropertyValues();
        private long mSetPtr = 0;
        private boolean mContainsSequentialAnimators = false;
        private boolean mStarted = false;
        private boolean mInitialized = false;
        private boolean mIsReversible = false;
        private boolean mIsInfinite = false;
        private final VirtualRefBasePtr mSetRefBasePtr;
        private WeakReference<RenderNode> mLastSeenTarget = null;
        private int mLastListenerId = 0;
        private final IntArray mPendingAnimationActions = new IntArray();
        private final AnimatedVectorDrawable mDrawable;
        private long mTotalDuration;
        private AnimatorListener mThreadedRendererAnimatorListener;

        VectorDrawableAnimatorRT(AnimatedVectorDrawable drawable) {
            mDrawable = drawable;
            mSetPtr = nCreateAnimatorSet();
            // Increment ref count on native AnimatorSet, so it doesn't get released before Java
            // side is done using it.
            mSetRefBasePtr = new VirtualRefBasePtr(mSetPtr);
        }

        @Override
        public void init(@NonNull AnimatorSet set) {
            if (mInitialized) {
                // Already initialized
                throw new UnsupportedOperationException("VectorDrawableAnimator cannot be " +
                        "re-initialized");
            }
            parseAnimatorSet(set, 0);
            long vectorDrawableTreePtr = mDrawable.mAnimatedVectorState.mVectorDrawable
                    .getNativeTree();
            nSetVectorDrawableTarget(mSetPtr, vectorDrawableTreePtr);
            mInitialized = true;
            mTotalDuration = set.getTotalDuration();
            mIsInfinite = mTotalDuration == Animator.DURATION_INFINITE;

            // Check reversible.
            mIsReversible = true;
            if (mContainsSequentialAnimators) {
                mIsReversible = false;
            } else {
                // Check if there's any start delay set on child
                for (int i = 0; i < mStartDelays.size(); i++) {
                    if (mStartDelays.get(i) > 0) {
                        mIsReversible = false;
                        return;
                    }
                }
            }
        }

        private void parseAnimatorSet(AnimatorSet set, long startTime) {
            ArrayList<Animator> animators = set.getChildAnimations();

            boolean playTogether = set.shouldPlayTogether();
            // Convert AnimatorSet to VectorDrawableAnimatorRT
            for (int i = 0; i < animators.size(); i++) {
                Animator animator = animators.get(i);
                // Here we only support ObjectAnimator
                if (animator instanceof AnimatorSet) {
                    parseAnimatorSet((AnimatorSet) animator, startTime);
                } else if (animator instanceof ObjectAnimator) {
                    createRTAnimator((ObjectAnimator) animator, startTime);
                } // ignore ValueAnimators and others because they don't directly modify VD
                  // therefore will be useless to AVD.

                if (!playTogether) {
                    // Assume not play together means play sequentially
                    startTime += animator.getTotalDuration();
                    mContainsSequentialAnimators = true;
                }
            }
        }

        // TODO: This method reads animation data from already parsed Animators. We need to move
        // this step further up the chain in the parser to avoid the detour.
        private void createRTAnimator(ObjectAnimator animator, long startTime) {
            PropertyValuesHolder[] values = animator.getValues();
            Object target = animator.getTarget();
            if (target instanceof VectorDrawable.VGroup) {
                createRTAnimatorForGroup(values, animator, (VectorDrawable.VGroup) target,
                        startTime);
            } else if (target instanceof VectorDrawable.VPath) {
                for (int i = 0; i < values.length; i++) {
                    values[i].getPropertyValues(mTmpValues);
                    if (mTmpValues.endValue instanceof PathParser.PathData &&
                            mTmpValues.propertyName.equals("pathData")) {
                        createRTAnimatorForPath(animator, (VectorDrawable.VPath) target,
                                startTime);
                    }  else if (target instanceof VectorDrawable.VFullPath) {
                        createRTAnimatorForFullPath(animator, (VectorDrawable.VFullPath) target,
                                startTime);
                    } else if (!mDrawable.mAnimatedVectorState.mShouldIgnoreInvalidAnim) {
                        throw new IllegalArgumentException("ClipPath only supports PathData " +
                                "property");
                    }
                }
            } else if (target instanceof VectorDrawable.VectorDrawableState) {
                createRTAnimatorForRootGroup(values, animator,
                        (VectorDrawable.VectorDrawableState) target, startTime);
            }
        }

        private void createRTAnimatorForGroup(PropertyValuesHolder[] values,
                ObjectAnimator animator, VectorDrawable.VGroup target,
                long startTime) {

            long nativePtr = target.getNativePtr();
            int propertyId;
            for (int i = 0; i < values.length; i++) {
                // TODO: We need to support the rare case in AVD where no start value is provided
                values[i].getPropertyValues(mTmpValues);
                propertyId = VectorDrawable.VGroup.getPropertyIndex(mTmpValues.propertyName);
                if (mTmpValues.type != Float.class && mTmpValues.type != float.class) {
                    if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                        Log.e(LOGTAG, "Unsupported type: " +
                                mTmpValues.type + ". Only float value is supported for Groups.");
                    }
                    continue;
                }
                if (propertyId < 0) {
                    if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                        Log.e(LOGTAG, "Unsupported property: " +
                                mTmpValues.propertyName + " for Vector Drawable Group");
                    }
                    continue;
                }
                long propertyPtr = nCreateGroupPropertyHolder(nativePtr, propertyId,
                        (Float) mTmpValues.startValue, (Float) mTmpValues.endValue);
                if (mTmpValues.dataSource != null) {
                    float[] dataPoints = createFloatDataPoints(mTmpValues.dataSource,
                            animator.getDuration());
                    nSetPropertyHolderData(propertyPtr, dataPoints, dataPoints.length);
                }
                createNativeChildAnimator(propertyPtr, startTime, animator);
            }
        }
        private void createRTAnimatorForPath( ObjectAnimator animator, VectorDrawable.VPath target,
                long startTime) {

            long nativePtr = target.getNativePtr();
            long startPathDataPtr = ((PathParser.PathData) mTmpValues.startValue)
                    .getNativePtr();
            long endPathDataPtr = ((PathParser.PathData) mTmpValues.endValue)
                    .getNativePtr();
            long propertyPtr = nCreatePathDataPropertyHolder(nativePtr, startPathDataPtr,
                    endPathDataPtr);
            createNativeChildAnimator(propertyPtr, startTime, animator);
        }

        private void createRTAnimatorForFullPath(ObjectAnimator animator,
                VectorDrawable.VFullPath target, long startTime) {

            int propertyId = target.getPropertyIndex(mTmpValues.propertyName);
            long propertyPtr;
            long nativePtr = target.getNativePtr();
            if (mTmpValues.type == Float.class || mTmpValues.type == float.class) {
                if (propertyId < 0) {
                    if (mDrawable.mAnimatedVectorState.mShouldIgnoreInvalidAnim) {
                        return;
                    } else {
                        throw new IllegalArgumentException("Property: " + mTmpValues.propertyName
                                + " is not supported for FullPath");
                    }
                }
                propertyPtr = nCreatePathPropertyHolder(nativePtr, propertyId,
                        (Float) mTmpValues.startValue, (Float) mTmpValues.endValue);
                if (mTmpValues.dataSource != null) {
                    // Pass keyframe data to native, if any.
                    float[] dataPoints = createFloatDataPoints(mTmpValues.dataSource,
                            animator.getDuration());
                    nSetPropertyHolderData(propertyPtr, dataPoints, dataPoints.length);
                }

            } else if (mTmpValues.type == Integer.class || mTmpValues.type == int.class) {
                propertyPtr = nCreatePathColorPropertyHolder(nativePtr, propertyId,
                        (Integer) mTmpValues.startValue, (Integer) mTmpValues.endValue);
                if (mTmpValues.dataSource != null) {
                    // Pass keyframe data to native, if any.
                    int[] dataPoints = createIntDataPoints(mTmpValues.dataSource,
                            animator.getDuration());
                    nSetPropertyHolderData(propertyPtr, dataPoints, dataPoints.length);
                }
            } else {
                if (mDrawable.mAnimatedVectorState.mShouldIgnoreInvalidAnim) {
                    return;
                } else {
                    throw new UnsupportedOperationException("Unsupported type: " +
                            mTmpValues.type + ". Only float, int or PathData value is " +
                            "supported for Paths.");
                }
            }
            createNativeChildAnimator(propertyPtr, startTime, animator);
        }

        private void createRTAnimatorForRootGroup(PropertyValuesHolder[] values,
                ObjectAnimator animator, VectorDrawable.VectorDrawableState target,
                long startTime) {
            long nativePtr = target.getNativeRenderer();
            if (!animator.getPropertyName().equals("alpha")) {
                if (mDrawable.mAnimatedVectorState.mShouldIgnoreInvalidAnim) {
                    return;
                } else {
                    throw new UnsupportedOperationException("Only alpha is supported for root "
                            + "group");
                }
            }
            Float startValue = null;
            Float endValue = null;
            for (int i = 0; i < values.length; i++) {
                values[i].getPropertyValues(mTmpValues);
                if (mTmpValues.propertyName.equals("alpha")) {
                    startValue = (Float) mTmpValues.startValue;
                    endValue = (Float) mTmpValues.endValue;
                    break;
                }
            }
            if (startValue == null && endValue == null) {
                if (mDrawable.mAnimatedVectorState.mShouldIgnoreInvalidAnim) {
                    return;
                } else {
                    throw new UnsupportedOperationException("No alpha values are specified");
                }
            }
            long propertyPtr = nCreateRootAlphaPropertyHolder(nativePtr, startValue, endValue);
            if (mTmpValues.dataSource != null) {
                // Pass keyframe data to native, if any.
                float[] dataPoints = createFloatDataPoints(mTmpValues.dataSource,
                        animator.getDuration());
                nSetPropertyHolderData(propertyPtr, dataPoints, dataPoints.length);
            }
            createNativeChildAnimator(propertyPtr, startTime, animator);
        }

        /**
         * Calculate the amount of frames an animation will run based on duration.
         */
        private static int getFrameCount(long duration) {
            long frameIntervalNanos = Choreographer.getInstance().getFrameIntervalNanos();
            int animIntervalMs = (int) (frameIntervalNanos / TimeUtils.NANOS_PER_MS);
            int numAnimFrames = (int) Math.ceil(((double) duration) / animIntervalMs);
            // We need 2 frames of data minimum.
            numAnimFrames = Math.max(2, numAnimFrames);
            if (numAnimFrames > MAX_SAMPLE_POINTS) {
                Log.w("AnimatedVectorDrawable", "Duration for the animation is too long :" +
                        duration + ", the animation will subsample the keyframe or path data.");
                numAnimFrames = MAX_SAMPLE_POINTS;
            }
            return numAnimFrames;
        }

        // These are the data points that define the value of the animating properties.
        // e.g. translateX and translateY can animate along a Path, at any fraction in [0, 1]
        // a point on the path corresponds to the values of translateX and translateY.
        // TODO: (Optimization) We should pass the path down in native and chop it into segments
        // in native.
        private static float[] createFloatDataPoints(
                PropertyValuesHolder.PropertyValues.DataSource dataSource, long duration) {
            int numAnimFrames = getFrameCount(duration);
            float values[] = new float[numAnimFrames];
            float lastFrame = numAnimFrames - 1;
            for (int i = 0; i < numAnimFrames; i++) {
                float fraction = i / lastFrame;
                values[i] = (Float) dataSource.getValueAtFraction(fraction);
            }
            return values;
        }

        private static int[] createIntDataPoints(
                PropertyValuesHolder.PropertyValues.DataSource dataSource, long duration) {
            int numAnimFrames = getFrameCount(duration);
            int values[] = new int[numAnimFrames];
            float lastFrame = numAnimFrames - 1;
            for (int i = 0; i < numAnimFrames; i++) {
                float fraction = i / lastFrame;
                values[i] = (Integer) dataSource.getValueAtFraction(fraction);
            }
            return values;
        }

        private void createNativeChildAnimator(long propertyPtr, long extraDelay,
                                               ObjectAnimator animator) {
            long duration = animator.getDuration();
            int repeatCount = animator.getRepeatCount();
            long startDelay = extraDelay + animator.getStartDelay();
            TimeInterpolator interpolator = animator.getInterpolator();
            long nativeInterpolator =
                    NativeInterpolatorFactory.createNativeInterpolator(interpolator, duration);

            startDelay *= ValueAnimator.getDurationScale();
            duration *= ValueAnimator.getDurationScale();

            mStartDelays.add(startDelay);
            nAddAnimator(mSetPtr, propertyPtr, nativeInterpolator, startDelay, duration,
                    repeatCount, animator.getRepeatMode());
        }

        /**
         * Holds a weak reference to the target that was last seen (through the RecordingCanvas
         * in the last draw call), so that when animator set needs to start, we can add the animator
         * to the last seen RenderNode target and start right away.
         */
        protected void recordLastSeenTarget(RecordingCanvas canvas) {
            final RenderNode node = canvas.mNode;
            mLastSeenTarget = new WeakReference<RenderNode>(node);
            // Add the animator to the list of animators on every draw
            if (mInitialized || mPendingAnimationActions.size() > 0) {
                if (useTarget(node)) {
                    if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                        Log.d(LOGTAG, "Target is set in the next frame");
                    }
                    for (int i = 0; i < mPendingAnimationActions.size(); i++) {
                        handlePendingAction(mPendingAnimationActions.get(i));
                    }
                    mPendingAnimationActions.clear();
                }
            }
        }

        private void handlePendingAction(int pendingAnimationAction) {
            if (pendingAnimationAction == START_ANIMATION) {
                startAnimation();
            } else if (pendingAnimationAction == REVERSE_ANIMATION) {
                reverseAnimation();
            } else if (pendingAnimationAction == RESET_ANIMATION) {
                resetAnimation();
            } else if (pendingAnimationAction == END_ANIMATION) {
                endAnimation();
            } else {
                throw new UnsupportedOperationException("Animation action " +
                        pendingAnimationAction + "is not supported");
            }
        }

        private boolean useLastSeenTarget() {
            if (mLastSeenTarget != null) {
                final RenderNode target = mLastSeenTarget.get();
                return useTarget(target);
            }
            return false;
        }

        private boolean useTarget(RenderNode target) {
            if (target != null && target.isAttached()) {
                target.registerVectorDrawableAnimator(this);
                return true;
            }
            return false;
        }

        private void invalidateOwningView() {
            mDrawable.invalidateSelf();
        }

        private void addPendingAction(int pendingAnimationAction) {
            invalidateOwningView();
            mPendingAnimationActions.add(pendingAnimationAction);
        }

        @Override
        public void start() {
            if (!mInitialized) {
                return;
            }

            if (useLastSeenTarget()) {
                if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                    Log.d(LOGTAG, "Target is set. Starting VDAnimatorSet from java");
                }
                startAnimation();
            } else {
                addPendingAction(START_ANIMATION);
            }
        }

        @Override
        public void end() {
            if (!mInitialized) {
                return;
            }

            if (useLastSeenTarget()) {
                endAnimation();
            } else {
                addPendingAction(END_ANIMATION);
            }
        }

        @Override
        public void reset() {
            if (!mInitialized) {
                return;
            }

            if (useLastSeenTarget()) {
                resetAnimation();
            } else {
                addPendingAction(RESET_ANIMATION);
            }
        }

        // Current (imperfect) Java AnimatorSet cannot be reversed when the set contains sequential
        // animators or when the animator set has a start delay
        @Override
        public void reverse() {
            if (!mIsReversible || !mInitialized) {
                return;
            }
            if (useLastSeenTarget()) {
                if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                    Log.d(LOGTAG, "Target is set. Reversing VDAnimatorSet from java");
                }
                reverseAnimation();
            } else {
                addPendingAction(REVERSE_ANIMATION);
            }
        }

        // This should only be called after animator has been added to the RenderNode target.
        private void startAnimation() {
            if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                Log.w(LOGTAG, "starting animation on VD: " +
                        ((VectorDrawable.VectorDrawableState) ((AnimatedVectorDrawableState)
                                mDrawable.getConstantState()).mVectorDrawable.getConstantState())
                                .mRootName);
            }
            mStarted = true;
            if (mHandler == null) {
                mHandler = new Handler();
            }
            nStart(mSetPtr, this, ++mLastListenerId);
            invalidateOwningView();
            if (mListener != null) {
                mListener.onAnimationStart(null);
            }
            if (mThreadedRendererAnimatorListener != null) {
                mThreadedRendererAnimatorListener.onAnimationStart(null);
            }
        }

        // This should only be called after animator has been added to the RenderNode target.
        private void endAnimation() {
            if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                Log.w(LOGTAG, "ending animation on VD: " +
                        ((VectorDrawable.VectorDrawableState) ((AnimatedVectorDrawableState)
                                mDrawable.getConstantState()).mVectorDrawable.getConstantState())
                                .mRootName);
            }
            nEnd(mSetPtr);
            invalidateOwningView();
        }

        // This should only be called after animator has been added to the RenderNode target.
        private void resetAnimation() {
            nReset(mSetPtr);
            invalidateOwningView();
        }

        // This should only be called after animator has been added to the RenderNode target.
        private void reverseAnimation() {
            mStarted = true;
            nReverse(mSetPtr, this, ++mLastListenerId);
            invalidateOwningView();
            if (mListener != null) {
                mListener.onAnimationStart(null);
            }
            if (mThreadedRendererAnimatorListener != null) {
                mThreadedRendererAnimatorListener.onAnimationStart(null);
            }
        }

        @Override
        public long getAnimatorNativePtr() {
            return mSetPtr;
        }

        @Override
        public void setThreadedRendererAnimatorListener(AnimatorListener animatorListener) {
            mThreadedRendererAnimatorListener = animatorListener;
        }

        @Override
        public boolean canReverse() {
            return mIsReversible;
        }

        @Override
        public boolean isStarted() {
            return mStarted;
        }

        @Override
        public boolean isRunning() {
            if (!mInitialized) {
                return false;
            }
            return mStarted;
        }

        @Override
        public void setListener(AnimatorListener listener) {
            mListener = listener;
        }

        @Override
        public void removeListener(AnimatorListener listener) {
            mListener = null;
        }

        @Override
        public void onDraw(Canvas canvas) {
            if (canvas.isHardwareAccelerated()) {
                recordLastSeenTarget((RecordingCanvas) canvas);
            }
        }

        @Override
        public boolean isInfinite() {
            return mIsInfinite;
        }

        @Override
        public void pause() {
            // TODO: Implement pause for Animator On RT.
        }

        @Override
        public void resume() {
            // TODO: Implement resume for Animator On RT.
        }

        private void onAnimationEnd(int listenerId) {
            if (listenerId != mLastListenerId) {
                return;
            }
            if (DBG_ANIMATION_VECTOR_DRAWABLE) {
                Log.d(LOGTAG, "on finished called from native");
            }
            mStarted = false;
            // Invalidate in the end of the animation to make sure the data in
            // RT thread is synced back to UI thread.
            invalidateOwningView();
            if (mListener != null) {
                mListener.onAnimationEnd(null);
            }
            if (mThreadedRendererAnimatorListener != null) {
                mThreadedRendererAnimatorListener.onAnimationEnd(null);
            }
        }

        // onFinished: should be called from native
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private static void callOnFinished(VectorDrawableAnimatorRT set, int id) {
            set.mHandler.post(() -> set.onAnimationEnd(id));
        }

        private void transferPendingActions(VectorDrawableAnimator animatorSet) {
            for (int i = 0; i < mPendingAnimationActions.size(); i++) {
                int pendingAction = mPendingAnimationActions.get(i);
                if (pendingAction == START_ANIMATION) {
                    animatorSet.start();
                } else if (pendingAction == END_ANIMATION) {
                    animatorSet.end();
                } else if (pendingAction == REVERSE_ANIMATION) {
                    animatorSet.reverse();
                } else if (pendingAction == RESET_ANIMATION) {
                    animatorSet.reset();
                } else {
                    throw new UnsupportedOperationException("Animation action " +
                            pendingAction + "is not supported");
                }
            }
            mPendingAnimationActions.clear();
        }

        @Override
        public long getTotalDuration() {
            return mTotalDuration;
        }
    }

    private static native long nCreateAnimatorSet();
    private static native void nSetVectorDrawableTarget(long animatorPtr, long vectorDrawablePtr);
    private static native void nAddAnimator(long setPtr, long propertyValuesHolder,
            long nativeInterpolator, long startDelay, long duration, int repeatCount,
            int repeatMode);
    private static native void nSetPropertyHolderData(long nativePtr, float[] data, int length);
    private static native void nSetPropertyHolderData(long nativePtr, int[] data, int length);
    private static native void nStart(long animatorSetPtr, VectorDrawableAnimatorRT set, int id);
    private static native void nReverse(long animatorSetPtr, VectorDrawableAnimatorRT set, int id);

    // ------------- @FastNative -------------------

    @FastNative
    private static native long nCreateGroupPropertyHolder(long nativePtr, int propertyId,
            float startValue, float endValue);
    @FastNative
    private static native long nCreatePathDataPropertyHolder(long nativePtr, long startValuePtr,
            long endValuePtr);
    @FastNative
    private static native long nCreatePathColorPropertyHolder(long nativePtr, int propertyId,
            int startValue, int endValue);
    @FastNative
    private static native long nCreatePathPropertyHolder(long nativePtr, int propertyId,
            float startValue, float endValue);
    @FastNative
    private static native long nCreateRootAlphaPropertyHolder(long nativePtr, float startValue,
            float endValue);
    @FastNative
    private static native void nEnd(long animatorSetPtr);
    @FastNative
    private static native void nReset(long animatorSetPtr);
}
