package com.android.keyguard;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.os.Build;
import android.transition.ChangeBounds;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.transition.TransitionValues;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;

import androidx.annotation.VisibleForTesting;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.ColorExtractor.OnColorsChangedListener;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.Interpolators;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.TimeZone;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
public class KeyguardClockSwitch extends RelativeLayout {

    private static final String TAG = "KeyguardClockSwitch";

    /**
     * Controller used to track StatusBar state to know when to show the big_clock_container.
     */
    private final StatusBarStateController mStatusBarStateController;

    /**
     * Color extractor used to apply colors from wallpaper to custom clock faces.
     */
    private final SysuiColorExtractor mSysuiColorExtractor;

    /**
     * Manager used to know when to show a custom clock face.
     */
    private final ClockManager mClockManager;

    /**
     * Layout transition that scales the default clock face.
     */
    private final Transition mTransition;

    /**
     * Listener for layout transitions.
     */
    private final Transition.TransitionListener mTransitionListener;

    /**
     * Optional/alternative clock injected via plugin.
     */
    private ClockPlugin mClockPlugin;

    /**
     * Default clock.
     */
    private TextClock mClockView;

    /**
     * Default clock, bold version.
     * Used to transition to bold when shrinking the default clock.
     */
    private TextClock mClockViewBold;

    /**
     * Frame for default and custom clock.
     */
    private FrameLayout mSmallClockFrame;

    /**
     * Container for big custom clock.
     */
    private ViewGroup mBigClockContainer;

    /**
     * Status area (date and other stuff) shown below the clock. Plugin can decide whether or not to
     * show it below the alternate clock.
     */
    private View mKeyguardStatusArea;

    /**
     * Maintain state so that a newly connected plugin can be initialized.
     */
    private float mDarkAmount;

    /**
     * If the Keyguard Slice has a header (big center-aligned text.)
     */
    private boolean mShowingHeader;
    private boolean mSupportsDarkText;
    private int[] mColorPalette;

    /**
     * Track the state of the status bar to know when to hide the big_clock_container.
     */
    private int mStatusBarState;

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mStatusBarState = newState;
                    updateBigClockVisibility();
                }
            };

    private ClockManager.ClockChangedListener mClockChangedListener = this::setClockPlugin;

    /**
     * Listener for changes to the color palette.
     *
     * The color palette changes when the wallpaper is changed.
     */
    private final OnColorsChangedListener mColorsListener = (extractor, which) -> {
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            updateColors();
        }
    };

    @Inject
    public KeyguardClockSwitch(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            StatusBarStateController statusBarStateController, SysuiColorExtractor colorExtractor,
            ClockManager clockManager) {
        super(context, attrs);
        mStatusBarStateController = statusBarStateController;
        mStatusBarState = mStatusBarStateController.getState();
        mSysuiColorExtractor = colorExtractor;
        mClockManager = clockManager;
        mTransition = new ClockBoundsTransition();
        mTransitionListener = new ClockBoundsTransitionListener();
    }

    /**
     * Returns if this view is presenting a custom clock, or the default implementation.
     */
    public boolean hasCustomClock() {
        return mClockPlugin != null;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClockView = findViewById(R.id.default_clock_view);
        mClockViewBold = findViewById(R.id.default_clock_view_bold);
        mSmallClockFrame = findViewById(R.id.clock_view);
        mKeyguardStatusArea = findViewById(R.id.keyguard_status_area);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mClockManager.addOnClockChangedListener(mClockChangedListener);
        mStatusBarStateController.addCallback(mStateListener);
        mSysuiColorExtractor.addOnColorsChangedListener(mColorsListener);
        mTransition.addListener(mTransitionListener);
        updateColors();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mClockManager.removeOnClockChangedListener(mClockChangedListener);
        mStatusBarStateController.removeCallback(mStateListener);
        mSysuiColorExtractor.removeOnColorsChangedListener(mColorsListener);
        mTransition.removeListener(mTransitionListener);
        setClockPlugin(null);
    }

    private void setClockPlugin(ClockPlugin plugin) {
        // Disconnect from existing plugin.
        if (mClockPlugin != null) {
            View smallClockView = mClockPlugin.getView();
            if (smallClockView != null && smallClockView.getParent() == mSmallClockFrame) {
                mSmallClockFrame.removeView(smallClockView);
            }
            if (mBigClockContainer != null) {
                mBigClockContainer.removeAllViews();
                updateBigClockVisibility();
            }
            mClockPlugin.onDestroyView();
            mClockPlugin = null;
        }
        if (plugin == null) {
            mClockView.setVisibility(View.VISIBLE);
            mClockViewBold.setVisibility(View.INVISIBLE);
            mKeyguardStatusArea.setVisibility(View.VISIBLE);
            return;
        }
        // Attach small and big clock views to hierarchy.
        View smallClockView = plugin.getView();
        if (smallClockView != null) {
            mSmallClockFrame.addView(smallClockView, -1,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            mClockView.setVisibility(View.GONE);
            mClockViewBold.setVisibility(View.GONE);
        }
        View bigClockView = plugin.getBigClockView();
        if (bigClockView != null && mBigClockContainer != null) {
            mBigClockContainer.addView(bigClockView);
            updateBigClockVisibility();
        }
        // Hide default clock.
        if (!plugin.shouldShowStatusArea()) {
            mKeyguardStatusArea.setVisibility(View.GONE);
        }
        // Initialize plugin parameters.
        mClockPlugin = plugin;
        mClockPlugin.setStyle(getPaint().getStyle());
        mClockPlugin.setTextColor(getCurrentTextColor());
        mClockPlugin.setDarkAmount(mDarkAmount);
        if (mColorPalette != null) {
            mClockPlugin.setColorPalette(mSupportsDarkText, mColorPalette);
        }
    }

    /**
     * Set container for big clock face appearing behind NSSL and KeyguardStatusView.
     */
    public void setBigClockContainer(ViewGroup container) {
        if (mClockPlugin != null && container != null) {
            View bigClockView = mClockPlugin.getBigClockView();
            if (bigClockView != null) {
                container.addView(bigClockView);
            }
        }
        mBigClockContainer = container;
        updateBigClockVisibility();
    }

    /**
     * It will also update plugin setStyle if plugin is connected.
     */
    public void setStyle(Style style) {
        mClockView.getPaint().setStyle(style);
        mClockViewBold.getPaint().setStyle(style);
        if (mClockPlugin != null) {
            mClockPlugin.setStyle(style);
        }
    }

    /**
     * It will also update plugin setTextColor if plugin is connected.
     */
    public void setTextColor(int color) {
        mClockView.setTextColor(color);
        mClockViewBold.setTextColor(color);
        if (mClockPlugin != null) {
            mClockPlugin.setTextColor(color);
        }
    }

    public void setShowCurrentUserTime(boolean showCurrentUserTime) {
        mClockView.setShowCurrentUserTime(showCurrentUserTime);
        mClockViewBold.setShowCurrentUserTime(showCurrentUserTime);
    }

    public void setTextSize(int unit, float size) {
        mClockView.setTextSize(unit, size);
        mClockViewBold.setTextSize(unit, size);
    }

    public void setFormat12Hour(CharSequence format) {
        mClockView.setFormat12Hour(format);
        mClockViewBold.setFormat12Hour(format);
    }

    public void setFormat24Hour(CharSequence format) {
        mClockView.setFormat24Hour(format);
        mClockViewBold.setFormat24Hour(format);
    }

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
     *
     * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
     */
    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        if (mClockPlugin != null) {
            mClockPlugin.setDarkAmount(darkAmount);
        }
    }

    public Paint getPaint() {
        return mClockView.getPaint();
    }

    public int getCurrentTextColor() {
        return mClockView.getCurrentTextColor();
    }

    public float getTextSize() {
        return mClockView.getTextSize();
    }

    /**
     * Returns the preferred Y position of the clock.
     *
     * @param totalHeight Height of the parent container.
     * @return preferred Y position.
     */
    int getPreferredY(int totalHeight) {
        if (mClockPlugin != null) {
            return mClockPlugin.getPreferredY(totalHeight);
        } else {
            return totalHeight / 2;
        }
    }

    /**
     * Refresh the time of the clock, due to either time tick broadcast or doze time tick alarm.
     */
    public void refresh() {
        mClockView.refresh();
        mClockViewBold.refresh();
        if (mClockPlugin != null) {
            mClockPlugin.onTimeTick();
        }
        if (Build.IS_DEBUGGABLE) {
            // Log for debugging b/130888082 (sysui waking up, but clock not updating)
            Log.d(TAG, "Updating clock: " + mClockView.getText());
        }
    }

    /**
     * Notifies that the time zone has changed.
     */
    public void onTimeZoneChanged(TimeZone timeZone) {
        if (mClockPlugin != null) {
            mClockPlugin.onTimeZoneChanged(timeZone);
        }
    }

    private void updateColors() {
        ColorExtractor.GradientColors colors = mSysuiColorExtractor.getColors(
                WallpaperManager.FLAG_LOCK, true);
        mSupportsDarkText = colors.supportsDarkText();
        mColorPalette = colors.getColorPalette();
        if (mClockPlugin != null) {
            mClockPlugin.setColorPalette(mSupportsDarkText, mColorPalette);
        }
    }

    private void updateBigClockVisibility() {
        if (mBigClockContainer == null) {
            return;
        }
        final boolean inDisplayState = mStatusBarState == StatusBarState.KEYGUARD
                || mStatusBarState == StatusBarState.SHADE_LOCKED;
        final int visibility =
                inDisplayState && mBigClockContainer.getChildCount() != 0 ? View.VISIBLE
                        : View.GONE;
        if (mBigClockContainer.getVisibility() != visibility) {
            mBigClockContainer.setVisibility(visibility);
        }
    }

    /**
     * Sets if the keyguard slice is showing a center-aligned header. We need a smaller clock in
     * these cases.
     */
    public void setKeyguardShowingHeader(boolean hasHeader) {
        if (mShowingHeader == hasHeader || hasCustomClock()) {
            return;
        }
        mShowingHeader = hasHeader;

        TransitionManager.beginDelayedTransition((ViewGroup) mClockView.getParent(), mTransition);
        int fontSize = mContext.getResources().getDimensionPixelSize(mShowingHeader
                ? R.dimen.widget_small_font_size : R.dimen.widget_big_font_size);
        int paddingBottom = mContext.getResources().getDimensionPixelSize(mShowingHeader
                ? R.dimen.widget_vertical_padding_clock : R.dimen.header_subtitle_padding);
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        mClockViewBold.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        mClockView.setPadding(mClockView.getPaddingLeft(), mClockView.getPaddingTop(),
                mClockView.getPaddingRight(), paddingBottom);
        mClockViewBold.setPadding(mClockViewBold.getPaddingLeft(), mClockViewBold.getPaddingTop(),
                mClockViewBold.getPaddingRight(), paddingBottom);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    ClockManager.ClockChangedListener getClockChangedListener() {
        return mClockChangedListener;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    StatusBarStateController.StateListener getStateListener() {
        return mStateListener;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardClockSwitch:");
        pw.println("  mClockPlugin: " + mClockPlugin);
        pw.println("  mClockView: " + mClockView);
        pw.println("  mClockViewBold: " + mClockViewBold);
        pw.println("  mSmallClockFrame: " + mSmallClockFrame);
        pw.println("  mBigClockContainer: " + mBigClockContainer);
        pw.println("  mKeyguardStatusArea: " + mKeyguardStatusArea);
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mShowingHeader: " + mShowingHeader);
        pw.println("  mSupportsDarkText: " + mSupportsDarkText);
        pw.println("  mColorPalette: " + Arrays.toString(mColorPalette));
    }

    /**
     * Special layout transition that scales the clock view as its bounds change, to make it look
     * like the text is shrinking.
     */
    private class ClockBoundsTransition extends ChangeBounds {

        /**
         * Animation fraction when text is transitioned to/from bold.
         */
        private static final float TO_BOLD_TRANSITION_FRACTION = 0.7f;

        ClockBoundsTransition() {
            setDuration(KeyguardSliceView.DEFAULT_ANIM_DURATION / 2);
            setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        }

        @Override
        public Animator createAnimator(ViewGroup sceneRoot, TransitionValues startValues,
                TransitionValues endValues) {
            Animator animator = super.createAnimator(sceneRoot, startValues, endValues);
            if (animator == null || startValues.view != mClockView) {
                return animator;
            }

            ValueAnimator boundsAnimator = null;
            if (animator instanceof AnimatorSet) {
                Animator first = ((AnimatorSet) animator).getChildAnimations().get(0);
                if (first instanceof ValueAnimator) {
                    boundsAnimator = (ValueAnimator) first;
                }
            } else if (animator instanceof ValueAnimator) {
                boundsAnimator = (ValueAnimator) animator;
            }

            if (boundsAnimator != null) {
                float bigFontSize = mContext.getResources()
                        .getDimensionPixelSize(R.dimen.widget_big_font_size);
                float smallFontSize = mContext.getResources()
                        .getDimensionPixelSize(R.dimen.widget_small_font_size);
                float startScale = mShowingHeader
                        ? bigFontSize / smallFontSize : smallFontSize / bigFontSize;
                final int normalViewVisibility = mShowingHeader ? View.INVISIBLE : View.VISIBLE;
                final int boldViewVisibility = mShowingHeader ? View.VISIBLE : View.INVISIBLE;
                final float boldTransitionFraction = mShowingHeader ? TO_BOLD_TRANSITION_FRACTION :
                        1f - TO_BOLD_TRANSITION_FRACTION;
                boundsAnimator.addUpdateListener(animation -> {
                    final float fraction = animation.getAnimatedFraction();
                    if (fraction > boldTransitionFraction) {
                        mClockView.setVisibility(normalViewVisibility);
                        mClockViewBold.setVisibility(boldViewVisibility);
                    }
                    float scale = MathUtils.lerp(startScale, 1f /* stop */,
                            animation.getAnimatedFraction());
                    mClockView.setPivotX(mClockView.getWidth() / 2f);
                    mClockViewBold.setPivotX(mClockViewBold.getWidth() / 2f);
                    mClockView.setPivotY(0);
                    mClockViewBold.setPivotY(0);
                    mClockView.setScaleX(scale);
                    mClockViewBold.setScaleX(scale);
                    mClockView.setScaleY(scale);
                    mClockViewBold.setScaleY(scale);
                });
            }

            return animator;
        }
    }

    /**
     * Transition listener for layout transition that scales the clock view.
     */
    private class ClockBoundsTransitionListener extends TransitionListenerAdapter {

        @Override
        public void onTransitionEnd(Transition transition) {
            mClockView.setVisibility(mShowingHeader ? View.INVISIBLE : View.VISIBLE);
            mClockViewBold.setVisibility(mShowingHeader ? View.VISIBLE : View.INVISIBLE);
            mClockView.setScaleX(1f);
            mClockViewBold.setScaleX(1f);
            mClockView.setScaleY(1f);
            mClockViewBold.setScaleY(1f);
        }

        @Override
        public void onTransitionCancel(Transition transition) {
            onTransitionEnd(transition);
        }
    }
}
