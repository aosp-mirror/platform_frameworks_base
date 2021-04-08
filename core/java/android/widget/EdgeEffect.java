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

package android.widget;

import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.Build;
import android.util.AttributeSet;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class performs the graphical effect used at the edges of scrollable widgets
 * when the user scrolls beyond the content bounds in 2D space.
 *
 * <p>EdgeEffect is stateful. Custom widgets using EdgeEffect should create an
 * instance for each edge that should show the effect, feed it input data using
 * the methods {@link #onAbsorb(int)}, {@link #onPull(float)}, and {@link #onRelease()},
 * and draw the effect using {@link #draw(Canvas)} in the widget's overridden
 * {@link android.view.View#draw(Canvas)} method. If {@link #isFinished()} returns
 * false after drawing, the edge effect's animation is not yet complete and the widget
 * should schedule another drawing pass to continue the animation.</p>
 *
 * <p>When drawing, widgets should draw their main content and child views first,
 * usually by invoking <code>super.draw(canvas)</code> from an overridden <code>draw</code>
 * method. (This will invoke onDraw and dispatch drawing to child views as needed.)
 * The edge effect may then be drawn on top of the view's content using the
 * {@link #draw(Canvas)} method.</p>
 */
public class EdgeEffect {
    /**
     * This sets the default value for {@link #setType(int)} to {@link #TYPE_STRETCH} instead
     * of {@link #TYPE_GLOW}. The type can still be overridden by the theme, view attribute,
     * or by calling {@link #setType(int)}.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.BASE)
    public static final long USE_STRETCH_EDGE_EFFECT_BY_DEFAULT = 171228096L;

    /**
     * This sets the default value for {@link #setType(int)} to {@link #TYPE_STRETCH} instead
     * of {@link #TYPE_GLOW} for views that instantiate with
     * {@link #EdgeEffect(Context, AttributeSet)}, indicating use of S+ EdgeEffect support. The
     * type can still be overridden by the theme, view attribute, or by calling
     * {@link #setType(int)}.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    public static final long USE_STRETCH_EDGE_EFFECT_FOR_SUPPORTED = 178807038L;

    /**
     * The default blend mode used by {@link EdgeEffect}.
     */
    public static final BlendMode DEFAULT_BLEND_MODE = BlendMode.SRC_ATOP;

    /**
     * Use a color edge glow for the edge effect. From XML, use
     * <code>android:edgeEffectType="glow"</code>.
     */
    public static final int TYPE_GLOW = 0;

    /**
     * Use a stretch for the edge effect. From XML, use
     * <code>android:edgeEffectType="stretch"</code>.
     */
    public static final int TYPE_STRETCH = 1;

    /**
     * The velocity threshold before the spring animation is considered settled.
     * The idea here is that velocity should be less than 1 pixel per frame (~16ms).
     */
    private static final double VELOCITY_THRESHOLD = 1.0 / 0.016;

    /**
     * The value threshold before the spring animation is considered close enough to
     * the destination to be settled. This should be around 1 pixel.
     */
    private static final double VALUE_THRESHOLD = 1;

    /**
     * The natural frequency of the stretch spring.
     */
    private static final double NATURAL_FREQUENCY = 17.55;

    /**
     * The damping ratio of the stretch spring.
     */
    private static final double DAMPING_RATIO = 0.92;

    /** @hide */
    @IntDef({TYPE_GLOW, TYPE_STRETCH})
    @Retention(RetentionPolicy.SOURCE)
    public @interface EdgeEffectType {
    }

    private static final float LINEAR_STRETCH_INTENSITY = 0.06f;

    private static final float EXP_STRETCH_INTENSITY = 0.06f;

    private static final float SCROLL_DIST_AFFECTED_BY_EXP_STRETCH = 0.33f;

    @SuppressWarnings("UnusedDeclaration")
    private static final String TAG = "EdgeEffect";

    // Time it will take the effect to fully recede in ms
    private static final int RECEDE_TIME = 600;

    // Time it will take before a pulled glow begins receding in ms
    private static final int PULL_TIME = 167;

    // Time it will take in ms for a pulled glow to decay to partial strength before release
    private static final int PULL_DECAY_TIME = 2000;

    private static final float MAX_ALPHA = 0.15f;
    private static final float GLOW_ALPHA_START = .09f;

    private static final float MAX_GLOW_SCALE = 2.f;

    private static final float PULL_GLOW_BEGIN = 0.f;

    // Minimum velocity that will be absorbed
    private static final int MIN_VELOCITY = 100;
    // Maximum velocity, clamps at this value
    private static final int MAX_VELOCITY = 10000;

    private static final float EPSILON = 0.001f;

    private static final double ANGLE = Math.PI / 6;
    private static final float SIN = (float) Math.sin(ANGLE);
    private static final float COS = (float) Math.cos(ANGLE);
    private static final float RADIUS_FACTOR = 0.6f;

    private float mGlowAlpha;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mGlowScaleY;
    private float mDistance;
    private float mVelocity; // only for stretch animations

    private float mGlowAlphaStart;
    private float mGlowAlphaFinish;
    private float mGlowScaleYStart;
    private float mGlowScaleYFinish;

    private long mStartTime;
    private float mDuration;

    private final Interpolator mInterpolator = new DecelerateInterpolator();

    private static final int STATE_IDLE = 0;
    private static final int STATE_PULL = 1;
    private static final int STATE_ABSORB = 2;
    private static final int STATE_RECEDE = 3;
    private static final int STATE_PULL_DECAY = 4;

    private static final float PULL_DISTANCE_ALPHA_GLOW_FACTOR = 0.8f;

    private static final int VELOCITY_GLOW_FACTOR = 6;

    private int mState = STATE_IDLE;

    private float mPullDistance;

    private final Rect mBounds = new Rect();
    private float mWidth;
    private float mHeight;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123769450)
    private final Paint mPaint = new Paint();
    private float mRadius;
    private float mBaseGlowScale;
    private float mDisplacement = 0.5f;
    private float mTargetDisplacement = 0.5f;
    private @EdgeEffectType int mEdgeEffectType = TYPE_GLOW;
    private Matrix mTmpMatrix = null;
    private float[] mTmpPoints = null;

    /**
     * Construct a new EdgeEffect with a theme appropriate for the provided context.
     * @param context Context used to provide theming and resource information for the EdgeEffect
     */
    public EdgeEffect(Context context) {
        this(context, null, Compatibility.isChangeEnabled(USE_STRETCH_EDGE_EFFECT_BY_DEFAULT));
    }

    /**
     * Construct a new EdgeEffect with a theme appropriate for the provided context.
     * @param context Context used to provide theming and resource information for the EdgeEffect
     * @param attrs The attributes of the XML tag that is inflating the view
     */
    public EdgeEffect(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs,
                Compatibility.isChangeEnabled(USE_STRETCH_EDGE_EFFECT_BY_DEFAULT)
                        || Compatibility.isChangeEnabled(USE_STRETCH_EDGE_EFFECT_FOR_SUPPORTED));
    }

    private EdgeEffect(@NonNull Context context, @Nullable AttributeSet attrs,
            boolean defaultStretch) {
        final TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.EdgeEffect);
        final int themeColor = a.getColor(
                com.android.internal.R.styleable.EdgeEffect_colorEdgeEffect, 0xff666666);
        mEdgeEffectType = a.getInt(
                com.android.internal.R.styleable.EdgeEffect_edgeEffectType,
                defaultStretch ? TYPE_STRETCH : TYPE_GLOW);
        a.recycle();

        mPaint.setAntiAlias(true);
        mPaint.setColor((themeColor & 0xffffff) | 0x33000000);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setBlendMode(DEFAULT_BLEND_MODE);
    }

    /**
     * Set the size of this edge effect in pixels.
     *
     * @param width Effect width in pixels
     * @param height Effect height in pixels
     */
    public void setSize(int width, int height) {
        final float r = width * RADIUS_FACTOR / SIN;
        final float y = COS * r;
        final float h = r - y;
        final float or = height * RADIUS_FACTOR / SIN;
        final float oy = COS * or;
        final float oh = or - oy;

        mRadius = r;
        mBaseGlowScale = h > 0 ? Math.min(oh / h, 1.f) : 1.f;

        mBounds.set(mBounds.left, mBounds.top, width, (int) Math.min(height, h));

        mWidth = width;
        mHeight = height;
    }

    /**
     * Reports if this EdgeEffect's animation is finished. If this method returns false
     * after a call to {@link #draw(Canvas)} the host widget should schedule another
     * drawing pass to continue the animation.
     *
     * @return true if animation is finished, false if drawing should continue on the next frame.
     */
    public boolean isFinished() {
        return mState == STATE_IDLE;
    }

    /**
     * Immediately finish the current animation.
     * After this call {@link #isFinished()} will return true.
     */
    public void finish() {
        mState = STATE_IDLE;
        mDistance = 0;
        mVelocity = 0;
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always {@link android.view.View#invalidate()} after this
     * and draw the results accordingly.
     *
     * <p>Views using EdgeEffect should favor {@link #onPull(float, float)} when the displacement
     * of the pull point is known.</p>
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     *                      1.f (full length of the view) or negative values to express change
     *                      back toward the edge reached to initiate the effect.
     */
    public void onPull(float deltaDistance) {
        onPull(deltaDistance, 0.5f);
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always {@link android.view.View#invalidate()} after this
     * and draw the results accordingly.
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     *                      1.f (full length of the view) or negative values to express change
     *                      back toward the edge reached to initiate the effect.
     * @param displacement The displacement from the starting side of the effect of the point
     *                     initiating the pull. In the case of touch this is the finger position.
     *                     Values may be from 0-1.
     */
    public void onPull(float deltaDistance, float displacement) {
        final long now = AnimationUtils.currentAnimationTimeMillis();
        mTargetDisplacement = displacement;
        if (mState == STATE_PULL_DECAY && now - mStartTime < mDuration
                && mEdgeEffectType == TYPE_GLOW) {
            return;
        }
        if (mState != STATE_PULL) {
            if (mEdgeEffectType == TYPE_STRETCH) {
                // Restore the mPullDistance to the fraction it is currently showing -- we want
                // to "catch" the current stretch value.
                mPullDistance = mDistance;
            } else {
                mGlowScaleY = Math.max(PULL_GLOW_BEGIN, mGlowScaleY);
            }
        }
        mState = STATE_PULL;

        mStartTime = now;
        mDuration = PULL_TIME;

        mPullDistance += deltaDistance;
        mDistance = Math.max(0f, mPullDistance);
        mVelocity = 0;

        if (mPullDistance == 0) {
            mGlowScaleY = mGlowScaleYStart = 0;
            mGlowAlpha = mGlowAlphaStart = 0;
        } else {
            final float absdd = Math.abs(deltaDistance);
            mGlowAlpha = mGlowAlphaStart = Math.min(MAX_ALPHA,
                    mGlowAlpha + (absdd * PULL_DISTANCE_ALPHA_GLOW_FACTOR));

            final float scale = (float) (Math.max(0, 1 - 1 /
                    Math.sqrt(Math.abs(mPullDistance) * mBounds.height()) - 0.3d) / 0.7d);

            mGlowScaleY = mGlowScaleYStart = scale;
        }

        mGlowAlphaFinish = mGlowAlpha;
        mGlowScaleYFinish = mGlowScaleY;
    }

    /**
     * A view should call this when content is pulled away from an edge by the user.
     * This will update the state of the current visual effect and its associated animation.
     * The host view should always {@link android.view.View#invalidate()} after this
     * and draw the results accordingly. This works similarly to {@link #onPull(float, float)},
     * but returns the amount of <code>deltaDistance</code> that has been consumed. If the
     * {@link #getDistance()} is currently 0 and <code>deltaDistance</code> is negative, this
     * function will return 0 and the drawn value will remain unchanged.
     *
     * This method can be used to reverse the effect from a pull or absorb and partially consume
     * some of a motion:
     *
     * <pre class="prettyprint">
     *     if (deltaY < 0) {
     *         float consumed = edgeEffect.onPullDistance(deltaY / getHeight(), x / getWidth());
     *         deltaY -= consumed * getHeight();
     *         if (edgeEffect.getDistance() == 0f) edgeEffect.onRelease();
     *     }
     * </pre>
     *
     * @param deltaDistance Change in distance since the last call. Values may be 0 (no change) to
     *                      1.f (full length of the view) or negative values to express change
     *                      back toward the edge reached to initiate the effect.
     * @param displacement The displacement from the starting side of the effect of the point
     *                     initiating the pull. In the case of touch this is the finger position.
     *                     Values may be from 0-1.
     * @return The amount of <code>deltaDistance</code> that was consumed, a number between
     * 0 and <code>deltaDistance</code>.
     */
    public float onPullDistance(float deltaDistance, float displacement) {
        float finalDistance = Math.max(0f, deltaDistance + mDistance);
        float delta = finalDistance - mDistance;
        if (delta == 0f && mDistance == 0f) {
            return 0f; // No pull, don't do anything.
        }

        if (mState != STATE_PULL && mState != STATE_PULL_DECAY && mEdgeEffectType == TYPE_GLOW) {
            // Catch the edge glow in the middle of an animation.
            mPullDistance = mDistance;
            mState = STATE_PULL;
        }
        onPull(delta, displacement);
        return delta;
    }

    /**
     * Returns the pull distance needed to be released to remove the showing effect.
     * It is determined by the {@link #onPull(float, float)} <code>deltaDistance</code> and
     * any animating values, including from {@link #onAbsorb(int)} and {@link #onRelease()}.
     *
     * This can be used in conjunction with {@link #onPullDistance(float, float)} to
     * release the currently showing effect.
     *
     * @return The pull distance that must be released to remove the showing effect.
     */
    public float getDistance() {
        return mDistance;
    }

    /**
     * Call when the object is released after being pulled.
     * This will begin the "decay" phase of the effect. After calling this method
     * the host view should {@link android.view.View#invalidate()} and thereby
     * draw the results accordingly.
     */
    public void onRelease() {
        mPullDistance = 0;

        if (mState != STATE_PULL && mState != STATE_PULL_DECAY) {
            return;
        }

        mState = STATE_RECEDE;
        mGlowAlphaStart = mGlowAlpha;
        mGlowScaleYStart = mGlowScaleY;

        mGlowAlphaFinish = 0.f;
        mGlowScaleYFinish = 0.f;
        mVelocity = 0.f;

        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mDuration = RECEDE_TIME;
    }

    /**
     * Call when the effect absorbs an impact at the given velocity.
     * Used when a fling reaches the scroll boundary.
     *
     * <p>When using a {@link android.widget.Scroller} or {@link android.widget.OverScroller},
     * the method <code>getCurrVelocity</code> will provide a reasonable approximation
     * to use here.</p>
     *
     * @param velocity Velocity at impact in pixels per second.
     */
    public void onAbsorb(int velocity) {
        if (mEdgeEffectType == TYPE_STRETCH) {
            mState = STATE_RECEDE;
            mVelocity = velocity;
            mDistance = 0;
            mStartTime = AnimationUtils.currentAnimationTimeMillis();
        } else {
            mState = STATE_ABSORB;
            mVelocity = 0;
            velocity = Math.min(Math.max(MIN_VELOCITY, Math.abs(velocity)), MAX_VELOCITY);

            mStartTime = AnimationUtils.currentAnimationTimeMillis();
            mDuration = 0.15f + (velocity * 0.02f);

            // The glow depends more on the velocity, and therefore starts out
            // nearly invisible.
            mGlowAlphaStart = GLOW_ALPHA_START;
            mGlowScaleYStart = Math.max(mGlowScaleY, 0.f);

            // Growth for the size of the glow should be quadratic to properly
            // respond
            // to a user's scrolling speed. The faster the scrolling speed, the more
            // intense the effect should be for both the size and the saturation.
            mGlowScaleYFinish = Math.min(0.025f + (velocity * (velocity / 100) * 0.00015f) / 2,
                    1.f);
            // Alpha should change for the glow as well as size.
            mGlowAlphaFinish = Math.max(
                    mGlowAlphaStart,
                    Math.min(velocity * VELOCITY_GLOW_FACTOR * .00001f, MAX_ALPHA));
            mTargetDisplacement = 0.5f;
        }
    }

    /**
     * Set the color of this edge effect in argb.
     *
     * @param color Color in argb
     */
    public void setColor(@ColorInt int color) {
        mPaint.setColor(color);
    }

    /**
     * Sets the edge effect type to use. The default without a theme attribute set is
     * {@link EdgeEffect#TYPE_GLOW}.
     *
     * @param type The edge effect type to use.
     * @attr ref android.R.styleable#EdgeEffect_edgeEffectType
     */
    public void setType(@EdgeEffectType int type) {
        mEdgeEffectType = type;
    }

    /**
     * Set or clear the blend mode. A blend mode defines how source pixels
     * (generated by a drawing command) are composited with the destination pixels
     * (content of the render target).
     * <p />
     * Pass null to clear any previous blend mode.
     * <p />
     *
     * @see BlendMode
     *
     * @param blendmode May be null. The blend mode to be installed in the paint
     */
    public void setBlendMode(@Nullable BlendMode blendmode) {
        mPaint.setBlendMode(blendmode);
    }

    /**
     * Return the color of this edge effect in argb.
     * @return The color of this edge effect in argb
     */
    @ColorInt
    public int getColor() {
        return mPaint.getColor();
    }

    /**
     * Return the edge effect type to use.
     *
     * @return The edge effect type to use.
     * @attr ref android.R.styleable#EdgeEffect_edgeEffectType
     */
    public @EdgeEffectType int getType() {
        return mEdgeEffectType;
    }

    /**
     * Returns the blend mode. A blend mode defines how source pixels
     * (generated by a drawing command) are composited with the destination pixels
     * (content of the render target).
     * <p />
     *
     * @return BlendMode
     */
    @Nullable
    public BlendMode getBlendMode() {
        return mPaint.getBlendMode();
    }

    /**
     * Draw into the provided canvas. Assumes that the canvas has been rotated
     * accordingly and the size has been set. The effect will be drawn the full
     * width of X=0 to X=width, beginning from Y=0 and extending to some factor <
     * 1.f of height. The {@link #TYPE_STRETCH} effect will only be visible on a
     * hardware canvas, e.g. {@link RenderNode#beginRecording()}.
     *
     * @param canvas Canvas to draw into
     * @return true if drawing should continue beyond this frame to continue the
     *         animation
     */
    public boolean draw(Canvas canvas) {
        if (mEdgeEffectType == TYPE_GLOW) {
            update();
            final int count = canvas.save();

            final float centerX = mBounds.centerX();
            final float centerY = mBounds.height() - mRadius;

            canvas.scale(1.f, Math.min(mGlowScaleY, 1.f) * mBaseGlowScale, centerX, 0);

            final float displacement = Math.max(0, Math.min(mDisplacement, 1.f)) - 0.5f;
            float translateX = mBounds.width() * displacement / 2;

            canvas.clipRect(mBounds);
            canvas.translate(translateX, 0);
            mPaint.setAlpha((int) (0xff * mGlowAlpha));
            canvas.drawCircle(centerX, centerY, mRadius, mPaint);
            canvas.restoreToCount(count);
        } else if (canvas instanceof RecordingCanvas) {
            if (mState == STATE_RECEDE) {
                updateSpring();
            }
            RecordingCanvas recordingCanvas = (RecordingCanvas) canvas;
            if (mTmpMatrix == null) {
                mTmpMatrix = new Matrix();
                mTmpPoints = new float[12];
            }
            //noinspection deprecation
            recordingCanvas.getMatrix(mTmpMatrix);

            mTmpPoints[0] = 0;
            mTmpPoints[1] = 0; // top-left
            mTmpPoints[2] = mWidth;
            mTmpPoints[3] = 0; // top-right
            mTmpPoints[4] = mWidth;
            mTmpPoints[5] = mHeight; // bottom-right
            mTmpPoints[6] = 0;
            mTmpPoints[7] = mHeight; // bottom-left
            mTmpPoints[8] = mWidth * mDisplacement;
            mTmpPoints[9] = 0; // drag start point
            mTmpPoints[10] = mWidth * mDisplacement;
            mTmpPoints[11] = mHeight * mDistance; // drag point
            mTmpMatrix.mapPoints(mTmpPoints);

            RenderNode renderNode = recordingCanvas.mNode;

            float left = renderNode.getLeft()
                    + min(mTmpPoints[0], mTmpPoints[2], mTmpPoints[4], mTmpPoints[6]);
            float top = renderNode.getTop()
                    + min(mTmpPoints[1], mTmpPoints[3], mTmpPoints[5], mTmpPoints[7]);
            float right = renderNode.getLeft()
                    + max(mTmpPoints[0], mTmpPoints[2], mTmpPoints[4], mTmpPoints[6]);
            float bottom = renderNode.getTop()
                    + max(mTmpPoints[1], mTmpPoints[3], mTmpPoints[5], mTmpPoints[7]);
            // assume rotations of increments of 90 degrees
            float x = mTmpPoints[10] - mTmpPoints[8];
            float width = right - left;
            float vecX = 0f;
            if (width > 0) {
                vecX = dampStretchVector(Math.max(-1f, Math.min(1f, x / width)));
            }

            float y = mTmpPoints[11] - mTmpPoints[9];
            float height = bottom - top;
            float vecY = 0f;
            if (height > 0) {
                vecY = dampStretchVector(Math.max(-1f, Math.min(1f, y / height)));
            }

            boolean hasStretchVectors = Float.compare(vecX, 0) != 0 || Float.compare(vecY, 0) != 0;
            if (right > left && bottom > top && mWidth > 0 && mHeight > 0 && hasStretchVectors) {
                renderNode.stretch(
                        left,
                        top,
                        right,
                        bottom,
                        vecX,
                        vecY,
                        mWidth,
                        mHeight
                );
            }
        } else {
            // This is TYPE_STRETCH and drawing into a Canvas that isn't a Recording Canvas,
            // so no effect can be shown. Just end the effect.
            mState = STATE_IDLE;
            mDistance = 0;
            mVelocity = 0;
        }

        boolean oneLastFrame = false;
        if (mState == STATE_RECEDE && mDistance == 0 && mVelocity == 0) {
            mState = STATE_IDLE;
            oneLastFrame = true;
        }

        return mState != STATE_IDLE || oneLastFrame;
    }

    private float min(float f1, float f2, float f3, float f4) {
        float min = Math.min(f1, f2);
        min = Math.min(min, f3);
        return Math.min(min, f4);
    }

    private float max(float f1, float f2, float f3, float f4) {
        float max = Math.max(f1, f2);
        max = Math.max(max, f3);
        return Math.max(max, f4);
    }

    /**
     * Return the maximum height that the edge effect will be drawn at given the original
     * {@link #setSize(int, int) input size}.
     * @return The maximum height of the edge effect
     */
    public int getMaxHeight() {
        return (int) (mBounds.height() * MAX_GLOW_SCALE + 0.5f);
    }

    private void update() {
        final long time = AnimationUtils.currentAnimationTimeMillis();
        final float t = Math.min((time - mStartTime) / mDuration, 1.f);

        final float interp = mInterpolator.getInterpolation(t);

        mGlowAlpha = mGlowAlphaStart + (mGlowAlphaFinish - mGlowAlphaStart) * interp;
        mGlowScaleY = mGlowScaleYStart + (mGlowScaleYFinish - mGlowScaleYStart) * interp;
        if (mState != STATE_PULL) {
            mDistance = calculateDistanceFromGlowValues(mGlowScaleY, mGlowAlpha);
        }
        mDisplacement = (mDisplacement + mTargetDisplacement) / 2;

        if (t >= 1.f - EPSILON) {
            switch (mState) {
                case STATE_ABSORB:
                    mState = STATE_RECEDE;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = RECEDE_TIME;

                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;

                    // After absorb, the glow should fade to nothing.
                    mGlowAlphaFinish = 0.f;
                    mGlowScaleYFinish = 0.f;
                    break;
                case STATE_PULL:
                    mState = STATE_PULL_DECAY;
                    mStartTime = AnimationUtils.currentAnimationTimeMillis();
                    mDuration = PULL_DECAY_TIME;

                    mGlowAlphaStart = mGlowAlpha;
                    mGlowScaleYStart = mGlowScaleY;

                    // After pull, the glow should fade to nothing.
                    mGlowAlphaFinish = 0.f;
                    mGlowScaleYFinish = 0.f;
                    break;
                case STATE_PULL_DECAY:
                    mState = STATE_RECEDE;
                    break;
                case STATE_RECEDE:
                    mState = STATE_IDLE;
                    break;
            }
        }
    }

    private void updateSpring() {
        final long time = AnimationUtils.currentAnimationTimeMillis();
        final float deltaT = (time - mStartTime) / 1000f; // Convert from millis to seconds
        if (deltaT < 0.001f) {
            return; // Must have at least 1 ms difference
        }
        final double mDampedFreq = NATURAL_FREQUENCY * Math.sqrt(1 - DAMPING_RATIO * DAMPING_RATIO);

        // We're always underdamped, so we can use only those equations:
        double cosCoeff = mDistance * mHeight;
        double sinCoeff = (1 / mDampedFreq) * (DAMPING_RATIO * NATURAL_FREQUENCY
                * mDistance * mHeight + mVelocity);
        double distance = Math.pow(Math.E, -DAMPING_RATIO * NATURAL_FREQUENCY * deltaT)
                * (cosCoeff * Math.cos(mDampedFreq * deltaT)
                + sinCoeff * Math.sin(mDampedFreq * deltaT));
        double velocity = distance * (-NATURAL_FREQUENCY) * DAMPING_RATIO
                + Math.pow(Math.E, -DAMPING_RATIO * NATURAL_FREQUENCY * deltaT)
                * (-mDampedFreq * cosCoeff * Math.sin(mDampedFreq * deltaT)
                + mDampedFreq * sinCoeff * Math.cos(mDampedFreq * deltaT));
        mDistance = (float) distance / mHeight;
        mVelocity = (float) velocity;
        mStartTime = time;
        if (isAtEquilibrium()) {
            mState = STATE_IDLE;
            mDistance = 0;
            mVelocity = 0;
        }
    }

    /**
     * @return The estimated pull distance as calculated from mGlowScaleY.
     */
    private float calculateDistanceFromGlowValues(float scale, float alpha) {
        if (scale >= 1f) {
            // It should asymptotically approach 1, but not reach there.
            // Here, we're just choosing a value that is large.
            return 1f;
        }
        if (scale > 0f) {
            float v = 1f / 0.7f / (mGlowScaleY - 1f);
            return v * v / mBounds.height();
        }
        return alpha / PULL_DISTANCE_ALPHA_GLOW_FACTOR;
    }

    /**
     * @return true if the spring used for calculating the stretch animation is
     * considered at rest or false if it is still animating.
     */
    private boolean isAtEquilibrium() {
        double displacement = mDistance * mHeight * LINEAR_STRETCH_INTENSITY; // in pixels
        double velocity = mVelocity * LINEAR_STRETCH_INTENSITY;
        return Math.abs(velocity) < VELOCITY_THRESHOLD
                && Math.abs(displacement) < VALUE_THRESHOLD;
    }

    private float dampStretchVector(float normalizedVec) {
        float sign = normalizedVec > 0 ? 1f : -1f;
        float overscroll = Math.abs(normalizedVec);
        float linearIntensity = LINEAR_STRETCH_INTENSITY * overscroll;
        double scalar = Math.E / SCROLL_DIST_AFFECTED_BY_EXP_STRETCH;
        double expIntensity = EXP_STRETCH_INTENSITY * (1 - Math.exp(-overscroll * scalar));
        return sign * (float) (linearIntensity + expIntensity);
    }
}
