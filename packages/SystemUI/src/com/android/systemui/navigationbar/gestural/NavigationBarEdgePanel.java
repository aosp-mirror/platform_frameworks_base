/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.navigationbar.gestural;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler.DEBUG_MISSING_GESTURE;
import static com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler.DEBUG_MISSING_GESTURE_TAG;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.util.Log;
import android.util.MathUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.core.graphics.ColorUtils;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.android.internal.util.LatencyTracker;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.plugins.NavigationEdgeBackPlugin;
import com.android.systemui.shared.navigationbar.RegionSamplingHelper;
import com.android.systemui.statusbar.VibratorHelper;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

import javax.inject.Inject;

public class NavigationBarEdgePanel extends View implements NavigationEdgeBackPlugin {

    private static final String TAG = "NavigationBarEdgePanel";

    private static final boolean ENABLE_FAILSAFE = true;

    private static final long COLOR_ANIMATION_DURATION_MS = 120;
    private static final long DISAPPEAR_FADE_ANIMATION_DURATION_MS = 80;
    private static final long DISAPPEAR_ARROW_ANIMATION_DURATION_MS = 100;
    private static final long FAILSAFE_DELAY_MS = 200;

    /**
     * The time required since the first vibration effect to automatically trigger a click
     */
    private static final int GESTURE_DURATION_FOR_CLICK_MS = 400;

    /**
     * The size of the protection of the arrow in px. Only used if this is not background protected
     */
    private static final int PROTECTION_WIDTH_PX = 2;

    /**
     * The basic translation in dp where the arrow resides
     */
    private static final int BASE_TRANSLATION_DP = 32;

    /**
     * The length of the arrow leg measured from the center to the end
     */
    private static final int ARROW_LENGTH_DP = 18;

    /**
     * The angle measured from the xAxis, where the leg is when the arrow rests
     */
    private static final int ARROW_ANGLE_WHEN_EXTENDED_DEGREES = 56;

    /**
     * The angle that is added per 1000 px speed to the angle of the leg
     */
    private static final int ARROW_ANGLE_ADDED_PER_1000_SPEED = 4;

    /**
     * The maximum angle offset allowed due to speed
     */
    private static final int ARROW_MAX_ANGLE_SPEED_OFFSET_DEGREES = 4;

    /**
     * The thickness of the arrow. Adjusted to match the home handle (approximately)
     */
    private static final float ARROW_THICKNESS_DP = 2.5f;

    /**
     * The amount of rubber banding we do for the vertical translation
     */
    private static final int RUBBER_BAND_AMOUNT = 15;

    /**
     * The interpolator used to rubberband
     */
    private static final Interpolator RUBBER_BAND_INTERPOLATOR
            = new PathInterpolator(1.0f / 5.0f, 1.0f, 1.0f, 1.0f);

    /**
     * The amount of rubber banding we do for the translation before base translation
     */
    private static final int RUBBER_BAND_AMOUNT_APPEAR = 4;

    /**
     * The interpolator used to rubberband the appearing of the arrow.
     */
    private static final Interpolator RUBBER_BAND_INTERPOLATOR_APPEAR
            = new PathInterpolator(1.0f / RUBBER_BAND_AMOUNT_APPEAR, 1.0f, 1.0f, 1.0f);

    private final WindowManager mWindowManager;
    private final VibratorHelper mVibratorHelper;

    /**
     * The paint the arrow is drawn with
     */
    private final Paint mPaint = new Paint();
    /**
     * The paint the arrow protection is drawn with
     */
    private final Paint mProtectionPaint;

    private final float mDensity;
    private final float mBaseTranslation;
    private final float mArrowLength;
    private final float mArrowThickness;

    /**
     * The minimum delta needed in movement for the arrow to change direction / stop triggering back
     */
    private final float mMinDeltaForSwitch;
    // The closest to y = 0 that the arrow will be displayed.
    private int mMinArrowPosition;
    // The amount the arrow is shifted to avoid the finger.
    private int mFingerOffset;

    private final float mSwipeTriggerThreshold;
    private final float mSwipeProgressThreshold;
    private final Path mArrowPath = new Path();
    private final Point mDisplaySize = new Point();

    private final SpringAnimation mAngleAnimation;
    private final SpringAnimation mTranslationAnimation;
    private final SpringAnimation mVerticalTranslationAnimation;
    private final SpringForce mAngleAppearForce;
    private final SpringForce mAngleDisappearForce;
    private final ValueAnimator mArrowColorAnimator;
    private final ValueAnimator mArrowDisappearAnimation;
    private final SpringForce mRegularTranslationSpring;
    private final SpringForce mTriggerBackSpring;
    private final LatencyTracker mLatencyTracker;

    private VelocityTracker mVelocityTracker;
    private boolean mIsDark = false;
    private boolean mShowProtection = false;
    private int mProtectionColorLight;
    private int mArrowPaddingEnd;
    private int mArrowColorLight;
    private int mProtectionColorDark;
    private int mArrowColorDark;
    private int mProtectionColor;
    private int mArrowColor;
    private RegionSamplingHelper mRegionSamplingHelper;
    private final Rect mSamplingRect = new Rect();
    private WindowManager.LayoutParams mLayoutParams;
    private int mLeftInset;
    private int mRightInset;

    /**
     * True if the panel is currently on the left of the screen
     */
    private boolean mIsLeftPanel;

    private float mStartX;
    private float mStartY;
    private float mCurrentAngle;
    /**
     * The current translation of the arrow
     */
    private float mCurrentTranslation;
    /**
     * Where the arrow will be in the resting position.
     */
    private float mDesiredTranslation;

    private boolean mDragSlopPassed;
    private boolean mArrowsPointLeft;
    private float mMaxTranslation;
    private boolean mTriggerBack;
    private float mPreviousTouchTranslation;
    private float mTotalTouchDelta;
    private float mVerticalTranslation;
    private float mDesiredVerticalTranslation;
    private float mDesiredAngle;
    private float mAngleOffset;
    private int mArrowStartColor;
    private int mCurrentArrowColor;
    private float mDisappearAmount;
    private long mVibrationTime;
    private int mScreenSize;
    private boolean mTrackingBackArrowLatency = false;

    private final Handler mHandler = new Handler();
    private final Runnable mFailsafeRunnable = this::onFailsafe;

    private DynamicAnimation.OnAnimationEndListener mSetGoneEndListener
            = new DynamicAnimation.OnAnimationEndListener() {
        @Override
        public void onAnimationEnd(DynamicAnimation animation, boolean canceled, float value,
                float velocity) {
            animation.removeEndListener(this);
            if (!canceled) {
                setVisibility(GONE);
            }
        }
    };
    private static final FloatPropertyCompat<NavigationBarEdgePanel> CURRENT_ANGLE =
            new FloatPropertyCompat<NavigationBarEdgePanel>("currentAngle") {
        @Override
        public void setValue(NavigationBarEdgePanel object, float value) {
            object.setCurrentAngle(value);
        }

        @Override
        public float getValue(NavigationBarEdgePanel object) {
            return object.getCurrentAngle();
        }
    };

    private static final FloatPropertyCompat<NavigationBarEdgePanel> CURRENT_TRANSLATION =
            new FloatPropertyCompat<NavigationBarEdgePanel>("currentTranslation") {

                @Override
                public void setValue(NavigationBarEdgePanel object, float value) {
                    object.setCurrentTranslation(value);
                }

                @Override
                public float getValue(NavigationBarEdgePanel object) {
                    return object.getCurrentTranslation();
                }
            };
    private static final FloatPropertyCompat<NavigationBarEdgePanel> CURRENT_VERTICAL_TRANSLATION =
            new FloatPropertyCompat<NavigationBarEdgePanel>("verticalTranslation") {

                @Override
                public void setValue(NavigationBarEdgePanel object, float value) {
                    object.setVerticalTranslation(value);
                }

                @Override
                public float getValue(NavigationBarEdgePanel object) {
                    return object.getVerticalTranslation();
                }
            };
    private BackCallback mBackCallback;

    @Inject
    public NavigationBarEdgePanel(
            Context context,
            LatencyTracker latencyTracker,
            VibratorHelper vibratorHelper,
            @Background Executor backgroundExecutor) {
        super(context);

        mWindowManager = context.getSystemService(WindowManager.class);
        mVibratorHelper = vibratorHelper;

        mDensity = context.getResources().getDisplayMetrics().density;

        mBaseTranslation = dp(BASE_TRANSLATION_DP);
        mArrowLength = dp(ARROW_LENGTH_DP);
        mArrowThickness = dp(ARROW_THICKNESS_DP);
        mMinDeltaForSwitch = dp(32);

        mPaint.setStrokeWidth(mArrowThickness);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeJoin(Paint.Join.ROUND);

        mArrowColorAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mArrowColorAnimator.setDuration(COLOR_ANIMATION_DURATION_MS);
        mArrowColorAnimator.addUpdateListener(animation -> {
            int newColor = ColorUtils.blendARGB(
                    mArrowStartColor, mArrowColor, animation.getAnimatedFraction());
            setCurrentArrowColor(newColor);
        });

        mArrowDisappearAnimation = ValueAnimator.ofFloat(0.0f, 1.0f);
        mArrowDisappearAnimation.setDuration(DISAPPEAR_ARROW_ANIMATION_DURATION_MS);
        mArrowDisappearAnimation.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mArrowDisappearAnimation.addUpdateListener(animation -> {
            mDisappearAmount = (float) animation.getAnimatedValue();
            invalidate();
        });

        mAngleAnimation =
                new SpringAnimation(this, CURRENT_ANGLE);
        mAngleAppearForce = new SpringForce()
                .setStiffness(500)
                .setDampingRatio(0.5f);
        mAngleDisappearForce = new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                .setFinalPosition(90);
        mAngleAnimation.setSpring(mAngleAppearForce).setMaxValue(90);

        mTranslationAnimation =
                new SpringAnimation(this, CURRENT_TRANSLATION);
        mRegularTranslationSpring = new SpringForce()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY);
        mTriggerBackSpring = new SpringForce()
                .setStiffness(450)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY);
        mTranslationAnimation.setSpring(mRegularTranslationSpring);
        mVerticalTranslationAnimation =
                new SpringAnimation(this, CURRENT_VERTICAL_TRANSLATION);
        mVerticalTranslationAnimation.setSpring(
                new SpringForce()
                        .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                        .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));

        mProtectionPaint = new Paint(mPaint);
        mProtectionPaint.setStrokeWidth(mArrowThickness + PROTECTION_WIDTH_PX);
        loadDimens();

        loadColors(context);
        updateArrowDirection();

        mSwipeTriggerThreshold = context.getResources()
                .getDimension(R.dimen.navigation_edge_action_drag_threshold);
        mSwipeProgressThreshold = context.getResources()
                .getDimension(R.dimen.navigation_edge_action_progress_threshold);

        setVisibility(GONE);

        boolean isPrimaryDisplay = mContext.getDisplayId() == DEFAULT_DISPLAY;
        mRegionSamplingHelper = new RegionSamplingHelper(this,
                new RegionSamplingHelper.SamplingCallback() {
                    @Override
                    public void onRegionDarknessChanged(boolean isRegionDark) {
                        setIsDark(!isRegionDark, true /* animate */);
                    }

                    @Override
                    public Rect getSampledRegion(View sampledView) {
                        return mSamplingRect;
                    }

                    @Override
                    public boolean isSamplingEnabled() {
                        return isPrimaryDisplay;
                    }
                }, backgroundExecutor);
        mRegionSamplingHelper.setWindowVisible(true);
        mShowProtection = !isPrimaryDisplay;
        mLatencyTracker = latencyTracker;
    }

    @Override
    public void onDestroy() {
        cancelFailsafe();
        mWindowManager.removeView(this);
        mRegionSamplingHelper.stop();
        mRegionSamplingHelper = null;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private void setIsDark(boolean isDark, boolean animate) {
        mIsDark = isDark;
        updateIsDark(animate);
    }

    @Override
    public void setIsLeftPanel(boolean isLeftPanel) {
        mIsLeftPanel = isLeftPanel;
        mLayoutParams.gravity = mIsLeftPanel
                ? (Gravity.LEFT | Gravity.TOP)
                : (Gravity.RIGHT | Gravity.TOP);
    }

    @Override
    public void setInsets(int leftInset, int rightInset) {
        mLeftInset = leftInset;
        mRightInset = rightInset;
    }

    @Override
    public void setDisplaySize(Point displaySize) {
        mDisplaySize.set(displaySize.x, displaySize.y);
        mScreenSize = Math.min(mDisplaySize.x, mDisplaySize.y);
    }

    @Override
    public void setBackCallback(BackCallback callback) {
        mBackCallback = callback;
    }

    @Override
    public void setLayoutParams(WindowManager.LayoutParams layoutParams) {
        mLayoutParams = layoutParams;
        mWindowManager.addView(this, mLayoutParams);
    }

    /**
     * Adjusts the sampling rect to conform to the actual visible bounding box of the arrow.
     */
    private void adjustSamplingRectToBoundingBox() {
        float translation = mDesiredTranslation;
        if (!mTriggerBack) {
            // Let's take the resting position and bounds as the sampling rect, since we are not
            // visible right now
            translation = mBaseTranslation;
            if (mIsLeftPanel && mArrowsPointLeft
                    || (!mIsLeftPanel && !mArrowsPointLeft)) {
                // If we're on the left we should move less, because the arrow is facing the other
                // direction
                translation -= getStaticArrowWidth();
            }
        }
        float left = translation - mArrowThickness / 2.0f;
        left = mIsLeftPanel ? left : mSamplingRect.width() - left;

        // Let's calculate the position of the end based on the angle
        float width = getStaticArrowWidth();
        float height = polarToCartY(ARROW_ANGLE_WHEN_EXTENDED_DEGREES) * mArrowLength * 2.0f;
        if (!mArrowsPointLeft) {
            left -= width;
        }

        float top = (getHeight() * 0.5f) + mDesiredVerticalTranslation - height / 2.0f;
        mSamplingRect.offset((int) left, (int) top);
        mSamplingRect.set(mSamplingRect.left, mSamplingRect.top,
                (int) (mSamplingRect.left + width),
                (int) (mSamplingRect.top + height));
        mRegionSamplingHelper.updateSamplingRect();
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDragSlopPassed = false;
                resetOnDown();
                mStartX = event.getX();
                mStartY = event.getY();
                setVisibility(VISIBLE);
                updatePosition(event.getY());
                mRegionSamplingHelper.start(mSamplingRect);
                mWindowManager.updateViewLayout(this, mLayoutParams);
                mLatencyTracker.onActionStart(LatencyTracker.ACTION_SHOW_BACK_ARROW);
                mTrackingBackArrowLatency = true;
                break;
            case MotionEvent.ACTION_MOVE:
                handleMoveEvent(event);
                break;
            case MotionEvent.ACTION_UP:
                if (DEBUG_MISSING_GESTURE) {
                    Log.d(DEBUG_MISSING_GESTURE_TAG,
                            "NavigationBarEdgePanel ACTION_UP, mTriggerBack=" + mTriggerBack);
                }
                if (mTriggerBack) {
                    triggerBack();
                } else {
                    cancelBack();
                }
                mRegionSamplingHelper.stop();
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                break;
            case MotionEvent.ACTION_CANCEL:
                if (DEBUG_MISSING_GESTURE) {
                    Log.d(DEBUG_MISSING_GESTURE_TAG, "NavigationBarEdgePanel ACTION_CANCEL");
                }
                cancelBack();
                mRegionSamplingHelper.stop();
                mVelocityTracker.recycle();
                mVelocityTracker = null;
                break;
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateArrowDirection();
        loadDimens();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float pointerPosition = mCurrentTranslation - mArrowThickness / 2.0f;
        canvas.save();
        canvas.translate(
                mIsLeftPanel ? pointerPosition : getWidth() - pointerPosition,
                (getHeight() * 0.5f) + mVerticalTranslation);

        // Let's calculate the position of the end based on the angle
        float x = (polarToCartX(mCurrentAngle) * mArrowLength);
        float y = (polarToCartY(mCurrentAngle) * mArrowLength);
        Path arrowPath = calculatePath(x,y);
        if (mShowProtection) {
            canvas.drawPath(arrowPath, mProtectionPaint);
        }

        canvas.drawPath(arrowPath, mPaint);
        canvas.restore();
        if (mTrackingBackArrowLatency) {
            mLatencyTracker.onActionEnd(LatencyTracker.ACTION_SHOW_BACK_ARROW);
            mTrackingBackArrowLatency = false;
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        mMaxTranslation = getWidth() - mArrowPaddingEnd;
    }

    private void loadDimens() {
        Resources res = getResources();
        mArrowPaddingEnd = res.getDimensionPixelSize(R.dimen.navigation_edge_panel_padding);
        mMinArrowPosition = res.getDimensionPixelSize(R.dimen.navigation_edge_arrow_min_y);
        mFingerOffset = res.getDimensionPixelSize(R.dimen.navigation_edge_finger_offset);
    }

    private void updateArrowDirection() {
        // Both panels arrow point the same way
        mArrowsPointLeft = getLayoutDirection() == LAYOUT_DIRECTION_LTR;
        invalidate();
    }

    private void loadColors(Context context) {
        final int dualToneDarkTheme = Utils.getThemeAttr(context, R.attr.darkIconTheme);
        final int dualToneLightTheme = Utils.getThemeAttr(context, R.attr.lightIconTheme);
        Context lightContext = new ContextThemeWrapper(context, dualToneLightTheme);
        Context darkContext = new ContextThemeWrapper(context, dualToneDarkTheme);
        mArrowColorLight = Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor);
        mArrowColorDark = Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor);
        mProtectionColorDark = mArrowColorLight;
        mProtectionColorLight = mArrowColorDark;
        updateIsDark(false /* animate */);
    }

    private void updateIsDark(boolean animate) {
        // TODO: Maybe animate protection as well
        mProtectionColor = mIsDark ? mProtectionColorDark : mProtectionColorLight;
        mProtectionPaint.setColor(mProtectionColor);
        mArrowColor = mIsDark ? mArrowColorDark : mArrowColorLight;
        mArrowColorAnimator.cancel();
        if (!animate) {
            setCurrentArrowColor(mArrowColor);
        } else {
            mArrowStartColor = mCurrentArrowColor;
            mArrowColorAnimator.start();
        }
    }

    private void setCurrentArrowColor(int color) {
        mCurrentArrowColor = color;
        mPaint.setColor(color);
        invalidate();
    }

    private float getStaticArrowWidth() {
        return polarToCartX(ARROW_ANGLE_WHEN_EXTENDED_DEGREES) * mArrowLength;
    }

    private float polarToCartX(float angleInDegrees) {
        return (float) Math.cos(Math.toRadians(angleInDegrees));
    }

    private float polarToCartY(float angleInDegrees) {
        return (float) Math.sin(Math.toRadians(angleInDegrees));
    }

    private Path calculatePath(float x, float y) {
        if (!mArrowsPointLeft) {
            x = -x;
        }
        float extent = MathUtils.lerp(1.0f, 0.75f, mDisappearAmount);
        x = x * extent;
        y = y * extent;
        mArrowPath.reset();
        mArrowPath.moveTo(x, y);
        mArrowPath.lineTo(0, 0);
        mArrowPath.lineTo(x, -y);
        return mArrowPath;
    }

    private float getCurrentAngle() {
        return mCurrentAngle;
    }

    private float getCurrentTranslation() {
        return mCurrentTranslation;
    }

    private void triggerBack() {
        mBackCallback.triggerBack();

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.computeCurrentVelocity(1000);
        // Only do the extra translation if we're not already flinging
        boolean isSlow = Math.abs(mVelocityTracker.getXVelocity()) < 500;
        if (isSlow
                || SystemClock.uptimeMillis() - mVibrationTime >= GESTURE_DURATION_FOR_CLICK_MS) {
            mVibratorHelper.vibrate(VibrationEffect.EFFECT_CLICK);
        }

        // Let's also snap the angle a bit
        if (mAngleOffset > -4) {
            mAngleOffset = Math.max(-8, mAngleOffset - 8);
            updateAngle(true /* animated */);
        }

        // Finally, after the translation, animate back and disappear the arrow
        Runnable translationEnd = () -> {
            // let's snap it back
            mAngleOffset = Math.max(0, mAngleOffset + 8);
            updateAngle(true /* animated */);

            mTranslationAnimation.setSpring(mTriggerBackSpring);
            // Translate the arrow back a bit to make for a nice transition
            setDesiredTranslation(mDesiredTranslation - dp(32), true /* animated */);
            animate().alpha(0f).setDuration(DISAPPEAR_FADE_ANIMATION_DURATION_MS)
                    .withEndAction(() -> setVisibility(GONE));
            mArrowDisappearAnimation.start();
            // Schedule failsafe in case alpha end callback is not called
            scheduleFailsafe();
        };
        if (mTranslationAnimation.isRunning()) {
            mTranslationAnimation.addEndListener(new DynamicAnimation.OnAnimationEndListener() {
                @Override
                public void onAnimationEnd(DynamicAnimation animation, boolean canceled,
                        float value,
                        float velocity) {
                    animation.removeEndListener(this);
                    if (!canceled) {
                        translationEnd.run();
                    }
                }
            });
            // Schedule failsafe in case mTranslationAnimation end callback is not called
            scheduleFailsafe();
        } else {
            translationEnd.run();
        }
    }

    private void cancelBack() {
        mBackCallback.cancelBack();

        if (mTranslationAnimation.isRunning()) {
            mTranslationAnimation.addEndListener(mSetGoneEndListener);
            // Schedule failsafe in case mTranslationAnimation end callback is not called
            scheduleFailsafe();
        } else {
            setVisibility(GONE);
        }
    }

    private void resetOnDown() {
        animate().cancel();
        mAngleAnimation.cancel();
        mTranslationAnimation.cancel();
        mVerticalTranslationAnimation.cancel();
        mArrowDisappearAnimation.cancel();
        mAngleOffset = 0;
        mTranslationAnimation.setSpring(mRegularTranslationSpring);
        // Reset the arrow to the side
        if (DEBUG_MISSING_GESTURE) {
            Log.d(DEBUG_MISSING_GESTURE_TAG, "reset mTriggerBack=false");
        }
        setTriggerBack(false /* triggerBack */, false /* animated */);
        setDesiredTranslation(0, false /* animated */);
        setCurrentTranslation(0);
        updateAngle(false /* animate */);
        mPreviousTouchTranslation = 0;
        mTotalTouchDelta = 0;
        mVibrationTime = 0;
        setDesiredVerticalTransition(0, false /* animated */);
        cancelFailsafe();
    }

    private void handleMoveEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        float touchTranslation = MathUtils.abs(x - mStartX);
        float yOffset = y - mStartY;
        float delta = touchTranslation - mPreviousTouchTranslation;
        if (Math.abs(delta) > 0) {
            if (Math.signum(delta) == Math.signum(mTotalTouchDelta)) {
                mTotalTouchDelta += delta;
            } else {
                mTotalTouchDelta = delta;
            }
        }
        mPreviousTouchTranslation = touchTranslation;

        // Apply a haptic on drag slop passed
        if (!mDragSlopPassed && touchTranslation > mSwipeTriggerThreshold) {
            mDragSlopPassed = true;
            mVibratorHelper.vibrate(VibrationEffect.EFFECT_TICK);
            mVibrationTime = SystemClock.uptimeMillis();

            // Let's show the arrow and animate it in!
            mDisappearAmount = 0.0f;
            setAlpha(1f);
            // And animate it go to back by default!
            if (DEBUG_MISSING_GESTURE) {
                Log.d(DEBUG_MISSING_GESTURE_TAG, "set mTriggerBack=true");
            }
            setTriggerBack(true /* triggerBack */, true /* animated */);
        }

        // Let's make sure we only go to the baseextend and apply rubberbanding afterwards
        if (touchTranslation > mBaseTranslation) {
            float diff = touchTranslation - mBaseTranslation;
            float progress = MathUtils.saturate(diff / (mScreenSize - mBaseTranslation));
            progress = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress)
                    * (mMaxTranslation - mBaseTranslation);
            touchTranslation = mBaseTranslation + progress;
        } else {
            float diff = mBaseTranslation - touchTranslation;
            float progress = MathUtils.saturate(diff / mBaseTranslation);
            progress = RUBBER_BAND_INTERPOLATOR_APPEAR.getInterpolation(progress)
                    * (mBaseTranslation / RUBBER_BAND_AMOUNT_APPEAR);
            touchTranslation = mBaseTranslation - progress;
        }
        // By default we just assume the current direction is kept
        boolean triggerBack = mTriggerBack;

        //  First lets see if we had continuous motion in one direction for a while
        if (Math.abs(mTotalTouchDelta) > mMinDeltaForSwitch) {
            triggerBack = mTotalTouchDelta > 0;
        }

        // Then, let's see if our velocity tells us to change direction
        mVelocityTracker.computeCurrentVelocity(1000);
        float xVelocity = mVelocityTracker.getXVelocity();
        float yVelocity = mVelocityTracker.getYVelocity();
        float velocity = MathUtils.mag(xVelocity, yVelocity);
        mAngleOffset = Math.min(velocity / 1000 * ARROW_ANGLE_ADDED_PER_1000_SPEED,
                ARROW_MAX_ANGLE_SPEED_OFFSET_DEGREES) * Math.signum(xVelocity);
        if (mIsLeftPanel && mArrowsPointLeft || !mIsLeftPanel && !mArrowsPointLeft) {
            mAngleOffset *= -1;
        }

        // Last if the direction in Y is bigger than X * 2 we also abort
        if (Math.abs(yOffset) > Math.abs(x - mStartX) * 2) {
            triggerBack = false;
        }
        if (DEBUG_MISSING_GESTURE && mTriggerBack != triggerBack) {
            Log.d(DEBUG_MISSING_GESTURE_TAG, "set mTriggerBack=" + triggerBack
                    + ", mTotalTouchDelta=" + mTotalTouchDelta
                    + ", mMinDeltaForSwitch=" + mMinDeltaForSwitch
                    + ", yOffset=" + yOffset
                    + ", x=" + x
                    + ", mStartX=" + mStartX);
        }
        setTriggerBack(triggerBack, true /* animated */);

        if (!mTriggerBack) {
            touchTranslation = 0;
        } else if (mIsLeftPanel && mArrowsPointLeft
                || (!mIsLeftPanel && !mArrowsPointLeft)) {
            // If we're on the left we should move less, because the arrow is facing the other
            // direction
            touchTranslation -= getStaticArrowWidth();
        }
        setDesiredTranslation(touchTranslation, true /* animated */);
        updateAngle(true /* animated */);

        float maxYOffset = getHeight() / 2.0f - mArrowLength;
        float progress = MathUtils.constrain(
                Math.abs(yOffset) / (maxYOffset * RUBBER_BAND_AMOUNT),
                0, 1);
        float verticalTranslation = RUBBER_BAND_INTERPOLATOR.getInterpolation(progress)
                * maxYOffset * Math.signum(yOffset);
        setDesiredVerticalTransition(verticalTranslation, true /* animated */);
        updateSamplingRect();
    }

    private void updatePosition(float touchY) {
        float position = touchY - mFingerOffset;
        position = Math.max(position, mMinArrowPosition);
        position -= mLayoutParams.height / 2.0f;
        mLayoutParams.y = MathUtils.constrain((int) position, 0, mDisplaySize.y);
        updateSamplingRect();
    }

    private void updateSamplingRect() {
        int top = mLayoutParams.y;
        int left = mIsLeftPanel ? mLeftInset : mDisplaySize.x - mRightInset - mLayoutParams.width;
        int right = left + mLayoutParams.width;
        int bottom = top + mLayoutParams.height;
        mSamplingRect.set(left, top, right, bottom);
        adjustSamplingRectToBoundingBox();
    }

    private void setDesiredVerticalTransition(float verticalTranslation, boolean animated) {
        if (mDesiredVerticalTranslation != verticalTranslation) {
            mDesiredVerticalTranslation = verticalTranslation;
            if (!animated) {
                setVerticalTranslation(verticalTranslation);
            } else {
                mVerticalTranslationAnimation.animateToFinalPosition(verticalTranslation);
            }
            invalidate();
        }
    }

    private void setVerticalTranslation(float verticalTranslation) {
        mVerticalTranslation = verticalTranslation;
        invalidate();
    }

    private float getVerticalTranslation() {
        return mVerticalTranslation;
    }

    private void setDesiredTranslation(float desiredTranslation, boolean animated) {
        if (mDesiredTranslation != desiredTranslation) {
            mDesiredTranslation = desiredTranslation;
            if (!animated) {
                setCurrentTranslation(desiredTranslation);
            } else {
                mTranslationAnimation.animateToFinalPosition(desiredTranslation);
            }
        }
    }

    private void setCurrentTranslation(float currentTranslation) {
        mCurrentTranslation = currentTranslation;
        invalidate();
    }

    private void setTriggerBack(boolean triggerBack, boolean animated) {
        if (mTriggerBack != triggerBack) {
            mTriggerBack = triggerBack;
            mAngleAnimation.cancel();
            updateAngle(animated);
            // Whenever the trigger back state changes the existing translation animation should be
            // cancelled
            mTranslationAnimation.cancel();
            mBackCallback.setTriggerBack(mTriggerBack);
        }
    }

    private void updateAngle(boolean animated) {
        float newAngle = mTriggerBack ? ARROW_ANGLE_WHEN_EXTENDED_DEGREES + mAngleOffset : 90;
        if (newAngle != mDesiredAngle) {
            if (!animated) {
                setCurrentAngle(newAngle);
            } else {
                mAngleAnimation.setSpring(mTriggerBack ? mAngleAppearForce : mAngleDisappearForce);
                mAngleAnimation.animateToFinalPosition(newAngle);
            }
            mDesiredAngle = newAngle;
        }
    }

    private void setCurrentAngle(float currentAngle) {
        mCurrentAngle = currentAngle;
        invalidate();
    }

    private void scheduleFailsafe() {
        if (!ENABLE_FAILSAFE) {
            return;
        }
        cancelFailsafe();
        mHandler.postDelayed(mFailsafeRunnable, FAILSAFE_DELAY_MS);
    }

    private void cancelFailsafe() {
        mHandler.removeCallbacks(mFailsafeRunnable);
    }

    private void onFailsafe() {
        setVisibility(GONE);
    }

    private float dp(float dp) {
        return mDensity * dp;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("NavigationBarEdgePanel:");
        pw.println("  mIsLeftPanel=" + mIsLeftPanel);
        pw.println("  mTriggerBack=" + mTriggerBack);
        pw.println("  mDragSlopPassed=" + mDragSlopPassed);
        pw.println("  mCurrentAngle=" + mCurrentAngle);
        pw.println("  mDesiredAngle=" + mDesiredAngle);
        pw.println("  mCurrentTranslation=" + mCurrentTranslation);
        pw.println("  mDesiredTranslation=" + mDesiredTranslation);
        pw.println("  mTranslationAnimation running=" + mTranslationAnimation.isRunning());
        mRegionSamplingHelper.dump(pw);
    }
}
