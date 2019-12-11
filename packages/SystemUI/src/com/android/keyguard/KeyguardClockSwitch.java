package com.android.keyguard;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.os.Build;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.transition.TransitionValues;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
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
import com.android.systemui.R;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;

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
    private static final boolean CUSTOM_CLOCKS_ENABLED = false;

    /**
     * Animation fraction when text is transitioned to/from bold.
     */
    private static final float TO_BOLD_TRANSITION_FRACTION = 0.7f;

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

    private final ClockVisibilityTransition mClockTransition;
    private final ClockVisibilityTransition mBoldClockTransition;

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
     * Boolean value indicating if notifications are visible on lock screen.
     */
    private boolean mHasVisibleNotifications;

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

        mClockTransition = new ClockVisibilityTransition().setCutoff(
                1 - TO_BOLD_TRANSITION_FRACTION);
        mClockTransition.addTarget(R.id.default_clock_view);
        mBoldClockTransition = new ClockVisibilityTransition().setCutoff(
                TO_BOLD_TRANSITION_FRACTION);
        mBoldClockTransition.addTarget(R.id.default_clock_view_bold);
        mTransition = new TransitionSet()
                .setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(mClockTransition)
                .addTransition(mBoldClockTransition)
                .setDuration(KeyguardSliceView.DEFAULT_ANIM_DURATION / 2)
                .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
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
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.addOnClockChangedListener(mClockChangedListener);
        }
        mStatusBarStateController.addCallback(mStateListener);
        mSysuiColorExtractor.addOnColorsChangedListener(mColorsListener);
        updateColors();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.removeOnClockChangedListener(mClockChangedListener);
        }
        mStatusBarStateController.removeCallback(mStateListener);
        mSysuiColorExtractor.removeOnColorsChangedListener(mColorsListener);
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
            if (mShowingHeader) {
                mClockView.setVisibility(View.GONE);
                mClockViewBold.setVisibility(View.VISIBLE);
            } else {
                mClockView.setVisibility(View.VISIBLE);
                mClockViewBold.setVisibility(View.INVISIBLE);
            }
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
        updateBigClockAlpha();
    }

    /**
     * Set whether or not the lock screen is showing notifications.
     */
    void setHasVisibleNotifications(boolean hasVisibleNotifications) {
        if (hasVisibleNotifications == mHasVisibleNotifications) {
            return;
        }
        mHasVisibleNotifications = hasVisibleNotifications;
        if (mDarkAmount == 0f && mBigClockContainer != null) {
            // Starting a fade transition since the visibility of the big clock will change.
            TransitionManager.beginDelayedTransition(mBigClockContainer,
                    new Fade().setDuration(KeyguardSliceView.DEFAULT_ANIM_DURATION / 2).addTarget(
                            mBigClockContainer));
        }
        updateBigClockAlpha();
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
                WallpaperManager.FLAG_LOCK);
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

    private void updateBigClockAlpha() {
        if (mBigClockContainer != null) {
            final float alpha = mHasVisibleNotifications ? mDarkAmount : 1f;
            mBigClockContainer.setAlpha(alpha);
            if (alpha == 0f) {
                mBigClockContainer.setVisibility(INVISIBLE);
            } else if (mBigClockContainer.getVisibility() == INVISIBLE) {
                mBigClockContainer.setVisibility(VISIBLE);
            }
        }
    }

    /**
     * Sets if the keyguard slice is showing a center-aligned header. We need a smaller clock in
     * these cases.
     */
    void setKeyguardShowingHeader(boolean hasHeader) {
        if (mShowingHeader == hasHeader) {
            return;
        }
        mShowingHeader = hasHeader;
        if (hasCustomClock()) {
            return;
        }

        float smallFontSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.widget_small_font_size);
        float bigFontSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.widget_big_font_size);
        mClockTransition.setScale(smallFontSize / bigFontSize);
        mBoldClockTransition.setScale(bigFontSize / smallFontSize);

        // End any current transitions before starting a new transition so that the new transition
        // starts from a good state instead of a potentially bad intermediate state arrived at
        // during a transition animation.
        TransitionManager.endTransitions((ViewGroup) mClockView.getParent());

        if (hasHeader) {
            // After the transition, make the default clock GONE so that it doesn't make the
            // KeyguardStatusView appear taller in KeyguardClockPositionAlgorithm and elsewhere.
            mTransition.addListener(new TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    super.onTransitionEnd(transition);
                    // Check that header is actually showing. I saw issues where this event was
                    // fired after the big clock transitioned back to visible, which causes the time
                    // to completely disappear.
                    if (mShowingHeader) {
                        mClockView.setVisibility(View.GONE);
                    }
                    transition.removeListener(this);
                }
            });
        }

        TransitionManager.beginDelayedTransition((ViewGroup) mClockView.getParent(), mTransition);
        mClockView.setVisibility(hasHeader ? View.INVISIBLE : View.VISIBLE);
        mClockViewBold.setVisibility(hasHeader ? View.VISIBLE : View.INVISIBLE);
        int paddingBottom = mContext.getResources().getDimensionPixelSize(hasHeader
                ? R.dimen.widget_vertical_padding_clock : R.dimen.title_clock_padding);
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
     * {@link Visibility} transformation that scales the view while it is disappearing/appearing and
     * transitions suddenly at a cutoff fraction during the animation.
     */
    private class ClockVisibilityTransition extends android.transition.Visibility {

        private static final String PROPNAME_VISIBILITY = "systemui:keyguard:visibility";

        private float mCutoff;
        private float mScale;

        /**
         * Constructs a transition that switches between visible/invisible at a cutoff and scales in
         * size while appearing/disappearing.
         */
        ClockVisibilityTransition() {
            setCutoff(1f);
            setScale(1f);
        }

        /**
         * Sets the transition point between visible/invisible.
         *
         * @param cutoff The fraction in [0, 1] when the view switches between visible/invisible.
         * @return This transition object
         */
        public ClockVisibilityTransition setCutoff(float cutoff) {
            mCutoff = cutoff;
            return this;
        }

        /**
         * Sets the scale factor applied while appearing/disappearing.
         *
         * @param scale Scale factor applied while appearing/disappearing. When factor is less than
         *              one, the view will shrink while disappearing. When it is greater than one,
         *              the view will expand while disappearing.
         * @return This transition object
         */
        public ClockVisibilityTransition setScale(float scale) {
            mScale = scale;
            return this;
        }

        @Override
        public void captureStartValues(TransitionValues transitionValues) {
            super.captureStartValues(transitionValues);
            captureVisibility(transitionValues);
        }

        @Override
        public void captureEndValues(TransitionValues transitionValues) {
            super.captureStartValues(transitionValues);
            captureVisibility(transitionValues);
        }

        private void captureVisibility(TransitionValues transitionValues) {
            transitionValues.values.put(PROPNAME_VISIBILITY,
                    transitionValues.view.getVisibility());
        }

        @Override
        public Animator onAppear(ViewGroup sceneRoot, View view, TransitionValues startValues,
                TransitionValues endValues) {
            if (!sceneRoot.isShown()) {
                return null;
            }
            final float cutoff = mCutoff;
            final int startVisibility = View.INVISIBLE;
            final int endVisibility = (int) endValues.values.get(PROPNAME_VISIBILITY);
            final float startScale = mScale;
            final float endScale = 1f;
            return createAnimator(view, cutoff, startVisibility, endVisibility, startScale,
                    endScale);
        }

        @Override
        public Animator onDisappear(ViewGroup sceneRoot, View view, TransitionValues startValues,
                TransitionValues endValues) {
            if (!sceneRoot.isShown()) {
                return null;
            }
            final float cutoff = 1f - mCutoff;
            final int startVisibility = View.VISIBLE;
            final int endVisibility = (int) endValues.values.get(PROPNAME_VISIBILITY);
            final float startScale = 1f;
            final float endScale = mScale;
            return createAnimator(view, cutoff, startVisibility, endVisibility, startScale,
                    endScale);
        }

        private Animator createAnimator(View view, float cutoff, int startVisibility,
                int endVisibility, float startScale, float endScale) {
            view.setPivotY(view.getHeight() - view.getPaddingBottom());
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.addUpdateListener(animation -> {
                final float fraction = animation.getAnimatedFraction();
                if (fraction > cutoff) {
                    view.setVisibility(endVisibility);
                }
                final float scale = MathUtils.lerp(startScale, endScale, fraction);
                view.setScaleX(scale);
                view.setScaleY(scale);
            });
            animator.addListener(new KeepAwakeAnimationListener(getContext()) {
                @Override
                public void onAnimationStart(Animator animation) {
                    super.onAnimationStart(animation);
                    view.setVisibility(startVisibility);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    super.onAnimationEnd(animation);
                    animation.removeListener(this);
                }
            });
            addListener(new TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    view.setVisibility(endVisibility);
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    transition.removeListener(this);
                }
            });
            return animator;
        }
    }
}
