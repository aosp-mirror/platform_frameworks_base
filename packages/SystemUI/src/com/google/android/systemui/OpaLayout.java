package com.google.android.systemui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.RenderNodeAnimator;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.phone.ButtonInterface;
import com.android.systemui.statusbar.policy.KeyButtonDrawable;
import com.android.systemui.statusbar.policy.KeyButtonView;

import java.util.ArrayList;

public class OpaLayout extends FrameLayout implements ButtonInterface {
    static final Interpolator INTERPOLATOR_40_40 = new PathInterpolator(0.4f, 0.0f, 0.6f, 1.0f);
    static final Interpolator INTERPOLATOR_40_OUT = new PathInterpolator(0.4f, 0.0f, 1.0f, 1.0f);
    private final Interpolator HOME_DISAPPEAR_INTERPOLATOR;
    private final ArrayList<View> mAnimatedViews;
    private final Interpolator mCollapseInterpolator;
    private final ArraySet<Animator> mCurrentAnimators;
    private final Runnable mDiamondAnimation;
    private final Interpolator mDiamondInterpolator;
    private final Interpolator mDotsFullSizeInterpolator;
    private final Interpolator mFastOutSlowInInterpolator;
    private final Interpolator mHomeDisappearInterpolator;
    private final OverviewProxyService.OverviewProxyListener mOverviewProxyListener;
    private final Runnable mRetract;
    private final Interpolator mRetractInterpolator;
    private int mAnimationState;
    private View mBlue;
    private View mBottom;
    private boolean mDelayTouchFeedback;
    private boolean mDiamondAnimationDelayed;
    private View mGreen;
    private ImageView mHalo;
    private KeyButtonView mHome;
    private int mHomeDiameter;
    private boolean mIsPressed;
    private boolean mIsVertical;
    private View mLeft;
    private OverviewProxyService mOverviewProxyService;
    private View mRed;
    private Resources mResources;
    private View mRight;
    private long mStartTime;
    private View mTop;
    private int mTouchDownX;
    private int mTouchDownY;
    private ImageView mWhite;
    private ImageView mWhiteCutout;
    private boolean mWindowVisible;
    private View mYellow;

    public OpaLayout(Context context) {
        this(context, null);
    }

    public OpaLayout(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public OpaLayout(Context context, AttributeSet attributeSet, int i, int i2) {
        super(context, attributeSet, i, i2);
        mFastOutSlowInInterpolator = Interpolators.FAST_OUT_SLOW_IN;
        mHomeDisappearInterpolator = new PathInterpolator(0.65f, 0.0f, 1.0f, 1.0f);
        mCollapseInterpolator = Interpolators.FAST_OUT_LINEAR_IN;
        mDotsFullSizeInterpolator = new PathInterpolator(0.4f, 0.0f, 0.0f, 1.0f);
        mRetractInterpolator = new PathInterpolator(0.4f, 0.0f, 0.0f, 1.0f);
        mDiamondInterpolator = new PathInterpolator(0.2f, 0.0f, 0.2f, 1.0f);
        HOME_DISAPPEAR_INTERPOLATOR = new PathInterpolator(0.65f, 0.0f, 1.0f, 1.0f);
        mCurrentAnimators = new ArraySet<>();
        mAnimatedViews = new ArrayList<>();
        mAnimationState = 0;
        mRetract = new Runnable() {
            @Override
            public void run() {
                cancelCurrentAnimation();
                startRetractAnimation();
            }
        };
        mOverviewProxyListener = new OverviewProxyService.OverviewProxyListener() {
            @Override
            public void onConnectionChanged(boolean z) {
                updateOpaLayout();
            }
        };
        mDiamondAnimation = new Runnable() {
            @Override
            public final void run() {
                if (mCurrentAnimators.isEmpty()) {
                    startDiamondAnimation();
                }
            }
        };
    }

    public OpaLayout(Context context, AttributeSet attributeSet, int i) {
        this(context, attributeSet, i, 0);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mResources = getResources();
        mBlue = findViewById(R.id.blue);
        mRed = findViewById(R.id.red);
        mYellow = findViewById(R.id.yellow);
        mGreen = findViewById(R.id.green);
        mWhite = findViewById(R.id.white);
        mWhiteCutout = findViewById(R.id.white_cutout);
        mHalo = findViewById(R.id.halo);
        mHome = findViewById(R.id.home_button);
        mHalo.setImageDrawable(KeyButtonDrawable.create(new ContextThemeWrapper(getContext(), R.style.DualToneLightTheme), new ContextThemeWrapper(getContext(), R.style.DualToneDarkTheme), R.drawable.halo, true, null));
        mHomeDiameter = mResources.getDimensionPixelSize(R.dimen.opa_disabled_home_diameter);
        Paint paint = new Paint();
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        mWhiteCutout.setLayerType(2, paint);
        mAnimatedViews.add(mBlue);
        mAnimatedViews.add(mRed);
        mAnimatedViews.add(mYellow);
        mAnimatedViews.add(mGreen);
        mAnimatedViews.add(mWhite);
        mAnimatedViews.add(mWhiteCutout);
        mAnimatedViews.add(mHalo);
        mOverviewProxyService = (OverviewProxyService) Dependency.get(OverviewProxyService.class);
        updateOpaLayout();
    }

    @Override
    public void onWindowVisibilityChanged(int i) {
        super.onWindowVisibilityChanged(i);
        mWindowVisible = i == 0;
        if (i == 0) {
            updateOpaLayout();
            return;
        }
        cancelCurrentAnimation();
        skipToStartingValue();
    }

    @Override
    public void setOnLongClickListener(View.OnLongClickListener onLongClickListener) {
        mHome.setOnLongClickListener(onLongClickListener);
    }

    @Override
    public void setOnTouchListener(View.OnTouchListener onTouchListener) {
        mHome.setOnTouchListener(onTouchListener);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent motionEvent) {
        if (ValueAnimator.areAnimatorsEnabled()) {
            int action = motionEvent.getAction();
            if (action != 0) {
                if (action != 1) {
                    if (action == 2) {
                        float quickStepTouchSlopPx = QuickStepContract.getQuickStepTouchSlopPx(getContext());
                        if (Math.abs(motionEvent.getRawX() - ((float) mTouchDownX)) > quickStepTouchSlopPx || Math.abs(motionEvent.getRawY() - ((float) mTouchDownY)) > quickStepTouchSlopPx) {
                            abortCurrentGesture();
                        }
                    }
                }
                if (mDiamondAnimationDelayed) {
                    if (mIsPressed) {
                        postDelayed(mRetract, 200);
                    }
                } else if (mAnimationState == 1) {
                    removeCallbacks(mRetract);
                    postDelayed(mRetract, 100 - (SystemClock.elapsedRealtime() - mStartTime));
                    removeCallbacks(mDiamondAnimation);
                    cancelLongPress();
                    return false;
                } else if (mIsPressed) {
                    mRetract.run();
                }
                mIsPressed = false;
            } else {
                mTouchDownX = (int) motionEvent.getRawX();
                mTouchDownY = (int) motionEvent.getRawY();
                boolean shouldStartDiamondAnimation;
                if (mCurrentAnimators.isEmpty()) {
                    shouldStartDiamondAnimation = false;
                } else if (mAnimationState != 2) {
                    return false;
                } else {
                    endCurrentAnimation();
                    shouldStartDiamondAnimation = true;
                }
                mStartTime = SystemClock.elapsedRealtime();
                mIsPressed = true;
                removeCallbacks(mDiamondAnimation);
                removeCallbacks(mRetract);
                if (!mDelayTouchFeedback || shouldStartDiamondAnimation) {
                    mDiamondAnimationDelayed = false;
                    startDiamondAnimation();
                } else {
                    mDiamondAnimationDelayed = true;
                    postDelayed(mDiamondAnimation, ViewConfiguration.getTapTimeout());
                }
            }
        }
        return false;
    }

    @Override
    public void setAccessibilityDelegate(View.AccessibilityDelegate accessibilityDelegate) {
        super.setAccessibilityDelegate(accessibilityDelegate);
        mHome.setAccessibilityDelegate(accessibilityDelegate);
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        mWhite.setImageDrawable(drawable);
        mWhiteCutout.setImageDrawable(drawable);
    }

    @Override
    public void abortCurrentGesture() {
        mHome.abortCurrentGesture();
        mIsPressed = false;
        mDiamondAnimationDelayed = false;
        removeCallbacks(mDiamondAnimation);
        cancelLongPress();
        int i = mAnimationState;
        if (i == 3 || i == 1) {
            mRetract.run();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        updateOpaLayout();
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        mOverviewProxyService.addCallback(mOverviewProxyListener);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
    }

    private void startDiamondAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            setDotsVisible();
            mCurrentAnimators.addAll(getDiamondAnimatorSet());
            mAnimationState = 1;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startRetractAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll(getRetractAnimatorSet());
            mAnimationState = 2;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startLineAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll(getLineAnimatorSet());
            mAnimationState = 3;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private void startCollapseAnimation() {
        if (allowAnimations()) {
            mCurrentAnimators.clear();
            mCurrentAnimators.addAll(getCollapseAnimatorSet());
            mAnimationState = 3;
            startAll(mCurrentAnimators);
            return;
        }
        skipToStartingValue();
    }

    private Animator getScaleAnimatorX(View view, float f, int i, Interpolator interpolator) {
        RenderNodeAnimator renderNodeAnimator = new RenderNodeAnimator(3, f);
        renderNodeAnimator.setTarget(view);
        renderNodeAnimator.setInterpolator(interpolator);
        renderNodeAnimator.setDuration((long) i);
        return renderNodeAnimator;
    }

    private Animator getScaleAnimatorY(View view, float f, int i, Interpolator interpolator) {
        RenderNodeAnimator renderNodeAnimator = new RenderNodeAnimator(4, f);
        renderNodeAnimator.setTarget(view);
        renderNodeAnimator.setInterpolator(interpolator);
        renderNodeAnimator.setDuration((long) i);
        return renderNodeAnimator;
    }

    private Animator getDeltaAnimatorX(View view, Interpolator interpolator, float f, int i) {
        RenderNodeAnimator renderNodeAnimator = new RenderNodeAnimator(8, view.getX() + f);
        renderNodeAnimator.setTarget(view);
        renderNodeAnimator.setInterpolator(interpolator);
        renderNodeAnimator.setDuration((long) i);
        return renderNodeAnimator;
    }

    private Animator getDeltaAnimatorY(View view, Interpolator interpolator, float f, int i) {
        RenderNodeAnimator renderNodeAnimator = new RenderNodeAnimator(9, view.getY() + f);
        renderNodeAnimator.setTarget(view);
        renderNodeAnimator.setInterpolator(interpolator);
        renderNodeAnimator.setDuration((long) i);
        return renderNodeAnimator;
    }

    private Animator getTranslationAnimatorX(View view, Interpolator interpolator, int i) {
        RenderNodeAnimator renderNodeAnimator = new RenderNodeAnimator(0, 0.0f);
        renderNodeAnimator.setTarget(view);
        renderNodeAnimator.setInterpolator(interpolator);
        renderNodeAnimator.setDuration((long) i);
        return renderNodeAnimator;
    }

    private Animator getTranslationAnimatorY(View view, Interpolator interpolator, int i) {
        RenderNodeAnimator renderNodeAnimator = new RenderNodeAnimator(1, 0.0f);
        renderNodeAnimator.setTarget(view);
        renderNodeAnimator.setInterpolator(interpolator);
        renderNodeAnimator.setDuration((long) i);
        return renderNodeAnimator;
    }

    private Animator getAlphaAnimator(View view, float f, int i, Interpolator interpolator) {
        return getAlphaAnimator(view, f, i, 0, interpolator);
    }

    private Animator getAlphaAnimator(View view, float f, int i, int i2, Interpolator interpolator) {
        RenderNodeAnimator renderNodeAnimator = new RenderNodeAnimator(11, f);
        renderNodeAnimator.setTarget(view);
        renderNodeAnimator.setInterpolator(interpolator);
        renderNodeAnimator.setDuration((long) i);
        renderNodeAnimator.setStartDelay((long) i2);
        return renderNodeAnimator;
    }

    private void startAll(ArraySet<Animator> arraySet) {
        for (int size = arraySet.size() - 1; size >= 0; size--) {
            arraySet.valueAt(size).start();
        }
    }

    private boolean allowAnimations() {
        return isAttachedToWindow() && mWindowVisible;
    }

    private float getPxVal(int i) {
        return (float) getResources().getDimensionPixelOffset(i);
    }

    private ArraySet<Animator> getDiamondAnimatorSet() {
        ArraySet<Animator> arraySet = new ArraySet<>();
        arraySet.add(getDeltaAnimatorY(mTop, mDiamondInterpolator, -getPxVal(R.dimen.opa_diamond_translation), 200));
        arraySet.add(getScaleAnimatorX(mTop, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mTop, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getAlphaAnimator(mTop, 1.0f, 50, Interpolators.LINEAR));
        arraySet.add(getDeltaAnimatorY(mBottom, mDiamondInterpolator, getPxVal(R.dimen.opa_diamond_translation), 200));
        arraySet.add(getScaleAnimatorX(mBottom, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mBottom, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getAlphaAnimator(mBottom, 1.0f, 50, Interpolators.LINEAR));
        arraySet.add(getDeltaAnimatorX(mLeft, mDiamondInterpolator, -getPxVal(R.dimen.opa_diamond_translation), 200));
        arraySet.add(getScaleAnimatorX(mLeft, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mLeft, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getAlphaAnimator(mLeft, 1.0f, 50, Interpolators.LINEAR));
        arraySet.add(getDeltaAnimatorX(mRight, mDiamondInterpolator, getPxVal(R.dimen.opa_diamond_translation), 200));
        arraySet.add(getScaleAnimatorX(mRight, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mRight, 0.8f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getAlphaAnimator(mRight, 1.0f, 50, Interpolators.LINEAR));
        arraySet.add(getScaleAnimatorX(mWhite, 0.625f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mWhite, 0.625f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorX(mWhiteCutout, 0.625f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mWhiteCutout, 0.625f, 200, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorX(mHalo, 0.47619048f, 100, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mHalo, 0.47619048f, 100, mFastOutSlowInInterpolator));
        arraySet.add(getAlphaAnimator(mHalo, 0.0f, 100, mFastOutSlowInInterpolator));
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animator) {
                mCurrentAnimators.clear();
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                startLineAnimation();
            }
        });
        return arraySet;
    }

    private ArraySet<Animator> getRetractAnimatorSet() {
        ArraySet<Animator> arraySet = new ArraySet<>();
        arraySet.add(getTranslationAnimatorX(mRed, mRetractInterpolator, 300));
        arraySet.add(getTranslationAnimatorY(mRed, mRetractInterpolator, 300));
        arraySet.add(getScaleAnimatorX(mRed, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getScaleAnimatorY(mRed, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getAlphaAnimator(mRed, 0.0f, 50, 50, Interpolators.LINEAR));
        arraySet.add(getTranslationAnimatorX(mBlue, mRetractInterpolator, 300));
        arraySet.add(getTranslationAnimatorY(mBlue, mRetractInterpolator, 300));
        arraySet.add(getScaleAnimatorX(mBlue, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getScaleAnimatorY(mBlue, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getAlphaAnimator(mBlue, 0.0f, 50, 50, Interpolators.LINEAR));
        arraySet.add(getTranslationAnimatorX(mGreen, mRetractInterpolator, 300));
        arraySet.add(getTranslationAnimatorY(mGreen, mRetractInterpolator, 300));
        arraySet.add(getScaleAnimatorX(mGreen, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getScaleAnimatorY(mGreen, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getAlphaAnimator(mGreen, 0.0f, 50, 50, Interpolators.LINEAR));
        arraySet.add(getTranslationAnimatorX(mYellow, mRetractInterpolator, 300));
        arraySet.add(getTranslationAnimatorY(mYellow, mRetractInterpolator, 300));
        arraySet.add(getScaleAnimatorX(mYellow, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getScaleAnimatorY(mYellow, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getAlphaAnimator(mYellow, 0.0f, 50, 50, Interpolators.LINEAR));
        arraySet.add(getScaleAnimatorX(mWhite, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getScaleAnimatorY(mWhite, 1.0f, 300, mRetractInterpolator));
        arraySet.add(getScaleAnimatorX(mWhiteCutout, 1.0f, 300, INTERPOLATOR_40_OUT));
        arraySet.add(getScaleAnimatorY(mWhiteCutout, 1.0f, 300, INTERPOLATOR_40_OUT));
        arraySet.add(getScaleAnimatorX(mHalo, 1.0f, 300, mFastOutSlowInInterpolator));
        arraySet.add(getScaleAnimatorY(mHalo, 1.0f, 300, mFastOutSlowInInterpolator));
        arraySet.add(getAlphaAnimator(mHalo, 1.0f, 300, mFastOutSlowInInterpolator));
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                mCurrentAnimators.clear();
                skipToStartingValue();
            }
        });
        return arraySet;
    }

    private ArraySet<Animator> getCollapseAnimatorSet() {
        Animator animator;
        Animator animator2;
        Animator animator3;
        Animator animator4;
        ArraySet<Animator> arraySet = new ArraySet<>();
        if (mIsVertical) {
            animator = getTranslationAnimatorY(mRed, INTERPOLATOR_40_OUT, 133);
        } else {
            animator = getTranslationAnimatorX(mRed, INTERPOLATOR_40_OUT, 133);
        }
        arraySet.add(animator);
        arraySet.add(getScaleAnimatorX(mRed, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getScaleAnimatorY(mRed, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getAlphaAnimator(mRed, 0.0f, 50, 33, Interpolators.LINEAR));
        if (mIsVertical) {
            animator2 = getTranslationAnimatorY(mBlue, INTERPOLATOR_40_OUT, 150);
        } else {
            animator2 = getTranslationAnimatorX(mBlue, INTERPOLATOR_40_OUT, 150);
        }
        arraySet.add(animator2);
        arraySet.add(getScaleAnimatorX(mBlue, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getScaleAnimatorY(mBlue, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getAlphaAnimator(mBlue, 0.0f, 50, 33, Interpolators.LINEAR));
        if (mIsVertical) {
            animator3 = getTranslationAnimatorY(mYellow, INTERPOLATOR_40_OUT, 133);
        } else {
            animator3 = getTranslationAnimatorX(mYellow, INTERPOLATOR_40_OUT, 133);
        }
        arraySet.add(animator3);
        arraySet.add(getScaleAnimatorX(mYellow, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getScaleAnimatorY(mYellow, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getAlphaAnimator(mYellow, 0.0f, 50, 33, Interpolators.LINEAR));
        if (mIsVertical) {
            animator4 = getTranslationAnimatorY(mGreen, INTERPOLATOR_40_OUT, 150);
        } else {
            animator4 = getTranslationAnimatorX(mGreen, INTERPOLATOR_40_OUT, 150);
        }
        arraySet.add(animator4);
        arraySet.add(getScaleAnimatorX(mGreen, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getScaleAnimatorY(mGreen, 1.0f, 200, INTERPOLATOR_40_OUT));
        arraySet.add(getAlphaAnimator(mGreen, 0.0f, 50, 33, Interpolators.LINEAR));
        Animator scaleAnimatorX = getScaleAnimatorX(mWhite, 1.0f, 150, mFastOutSlowInInterpolator);
        Animator scaleAnimatorY = getScaleAnimatorY(mWhite, 1.0f, 150, mFastOutSlowInInterpolator);
        Animator scaleAnimatorX2 = getScaleAnimatorX(mWhiteCutout, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator scaleAnimatorY2 = getScaleAnimatorY(mWhiteCutout, 1.0f, 150, Interpolators.FAST_OUT_SLOW_IN);
        Animator scaleAnimatorX3 = getScaleAnimatorX(mHalo, 1.0f, 150, mFastOutSlowInInterpolator);
        Animator scaleAnimatorY3 = getScaleAnimatorY(mHalo, 1.0f, 150, mFastOutSlowInInterpolator);
        Animator alphaAnimator = getAlphaAnimator(mHalo, 1.0f, 150, mFastOutSlowInInterpolator);
        scaleAnimatorX.setStartDelay(33);
        scaleAnimatorY.setStartDelay(33);
        scaleAnimatorX2.setStartDelay(33);
        scaleAnimatorY2.setStartDelay(33);
        scaleAnimatorX3.setStartDelay(33);
        scaleAnimatorY3.setStartDelay(33);
        alphaAnimator.setStartDelay(33);
        arraySet.add(scaleAnimatorX);
        arraySet.add(scaleAnimatorY);
        arraySet.add(scaleAnimatorX2);
        arraySet.add(scaleAnimatorY2);
        arraySet.add(scaleAnimatorX3);
        arraySet.add(scaleAnimatorY3);
        arraySet.add(alphaAnimator);
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                mCurrentAnimators.clear();
                skipToStartingValue();
            }
        });
        return arraySet;
    }

    private ArraySet<Animator> getLineAnimatorSet() {
        ArraySet<Animator> arraySet = new ArraySet<>();
        if (mIsVertical) {
            arraySet.add(getDeltaAnimatorY(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_ry), 225));
            arraySet.add(getDeltaAnimatorX(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_y_translation), 133));
            arraySet.add(getDeltaAnimatorY(mBlue, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_bg), 225));
            arraySet.add(getDeltaAnimatorY(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_ry), 225));
            arraySet.add(getDeltaAnimatorX(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_y_translation), 133));
            arraySet.add(getDeltaAnimatorY(mGreen, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_bg), 225));
        } else {
            arraySet.add(getDeltaAnimatorX(mRed, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_ry), 225));
            arraySet.add(getDeltaAnimatorY(mRed, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_y_translation), 133));
            arraySet.add(getDeltaAnimatorX(mBlue, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_x_trans_bg), 225));
            arraySet.add(getDeltaAnimatorX(mYellow, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_ry), 225));
            arraySet.add(getDeltaAnimatorY(mYellow, mFastOutSlowInInterpolator, -getPxVal(R.dimen.opa_line_y_translation), 133));
            arraySet.add(getDeltaAnimatorX(mGreen, mFastOutSlowInInterpolator, getPxVal(R.dimen.opa_line_x_trans_bg), 225));
        }
        arraySet.add(getScaleAnimatorX(mWhite, 0.0f, 83, mHomeDisappearInterpolator));
        arraySet.add(getScaleAnimatorY(mWhite, 0.0f, 83, mHomeDisappearInterpolator));
        arraySet.add(getScaleAnimatorX(mWhiteCutout, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        arraySet.add(getScaleAnimatorY(mWhiteCutout, 0.0f, 83, HOME_DISAPPEAR_INTERPOLATOR));
        arraySet.add(getScaleAnimatorX(mHalo, 0.0f, 83, mHomeDisappearInterpolator));
        arraySet.add(getScaleAnimatorY(mHalo, 0.0f, 83, mHomeDisappearInterpolator));
        getLongestAnim(arraySet).addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                startCollapseAnimation();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                mCurrentAnimators.clear();
            }
        });
        return arraySet;
    }

    private void updateOpaLayout() {
        boolean shouldShowSwipeUpUI = mOverviewProxyService.shouldShowSwipeUpUI();
        mHalo.setVisibility(!shouldShowSwipeUpUI ? View.VISIBLE : View.INVISIBLE);
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) mHalo.getLayoutParams();
        layoutParams.width = mHomeDiameter;
        layoutParams.height = mHomeDiameter;
        mWhite.setLayoutParams(layoutParams);
        mWhiteCutout.setLayoutParams(layoutParams);
        ImageView.ScaleType scaleType = ImageView.ScaleType.CENTER;
        mWhite.setScaleType(scaleType);
        mWhiteCutout.setScaleType(scaleType);
    }

    private void cancelCurrentAnimation() {
        if (!mCurrentAnimators.isEmpty()) {
            for (int size = mCurrentAnimators.size() - 1; size >= 0; size--) {
                Animator valueAt = mCurrentAnimators.valueAt(size);
                valueAt.removeAllListeners();
                valueAt.cancel();
            }
            mCurrentAnimators.clear();
            mAnimationState = 0;
        }
    }

    private void endCurrentAnimation() {
        if (!mCurrentAnimators.isEmpty()) {
            for (int size = mCurrentAnimators.size() - 1; size >= 0; size--) {
                Animator valueAt = mCurrentAnimators.valueAt(size);
                valueAt.removeAllListeners();
                valueAt.end();
            }
            mCurrentAnimators.clear();
        }
        mAnimationState = 0;
    }

    private Animator getLongestAnim(ArraySet<Animator> arraySet) {
        long j = Long.MIN_VALUE;
        Animator animator = null;
        for (int size = arraySet.size() - 1; size >= 0; size--) {
            Animator valueAt = arraySet.valueAt(size);
            if (valueAt.getTotalDuration() > j) {
                j = valueAt.getTotalDuration();
                animator = valueAt;
            }
        }
        return animator;
    }

    private void setDotsVisible() {
        int size = mAnimatedViews.size();
        for (int i = 0; i < size; i++) {
            mAnimatedViews.get(i).setAlpha(1.0f);
        }
    }

    private void skipToStartingValue() {
        int size = mAnimatedViews.size();
        for (int i = 0; i < size; i++) {
            View view = mAnimatedViews.get(i);
            view.setScaleY(1.0f);
            view.setScaleX(1.0f);
            view.setTranslationY(0.0f);
            view.setTranslationX(0.0f);
            view.setAlpha(0.0f);
        }
        mHalo.setAlpha(1.0f);
        mWhite.setAlpha(1.0f);
        mWhiteCutout.setAlpha(1.0f);
        mAnimationState = 0;
    }

    @Override
    public void setVertical(boolean vertical) {
        mIsVertical = vertical;
        mHome.setVertical(vertical);
        if (mIsVertical) {
            mTop = mGreen;
            mBottom = mBlue;
            mRight = mYellow;
            mLeft = mRed;
            return;
        }
        mTop = mRed;
        mBottom = mYellow;
        mLeft = mBlue;
        mRight = mGreen;
    }

    @Override
    public void setDarkIntensity(float intensity) {
        if (mWhite.getDrawable() instanceof KeyButtonDrawable) {
            ((KeyButtonDrawable) mWhite.getDrawable()).setDarkIntensity(intensity);
        }
        ((KeyButtonDrawable) mHalo.getDrawable()).setDarkIntensity(intensity);
        mWhite.invalidate();
        mHalo.invalidate();
        mHome.setDarkIntensity(intensity);
    }

    @Override
    public void setDelayTouchFeedback(boolean shouldDelay) {
        mHome.setDelayTouchFeedback(shouldDelay);
        mDelayTouchFeedback = shouldDelay;
    }
}
