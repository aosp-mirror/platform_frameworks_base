package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.dagger.KeyguardStatusViewScope;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.ClockPlugin;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.TimeZone;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
@KeyguardStatusViewScope
public class KeyguardClockSwitch extends RelativeLayout {

    private static final String TAG = "KeyguardClockSwitch";

    private static final long CLOCK_OUT_MILLIS = 150;
    private static final long CLOCK_IN_MILLIS = 200;
    private static final long SMARTSPACE_MOVE_MILLIS = 350;

    /**
     * Optional/alternative clock injected via plugin.
     */
    private ClockPlugin mClockPlugin;

    /**
     * Frame for small/large clocks
     */
    private FrameLayout mClockFrame;
    private FrameLayout mLargeClockFrame;
    private AnimatableClockView mClockView;
    private AnimatableClockView mLargeClockView;

    /**
     * Status area (date and other stuff) shown below the clock. Plugin can decide whether or not to
     * show it below the alternate clock.
     */
    private View mKeyguardStatusArea;
    /** Mutually exclusive with mKeyguardStatusArea */
    private View mSmartspaceView;

    /**
     * Maintain state so that a newly connected plugin can be initialized.
     */
    private float mDarkAmount;

    /**
     * Boolean value indicating if notifications are visible on lock screen. Use null to signify
     * it is uninitialized.
     */
    private Boolean mHasVisibleNotifications = null;

    private AnimatorSet mClockInAnim = null;
    private AnimatorSet mClockOutAnim = null;
    private ObjectAnimator mSmartspaceAnim = null;

    /**
     * If the Keyguard Slice has a header (big center-aligned text.)
     */
    private boolean mSupportsDarkText;
    private int[] mColorPalette;

    private int mClockSwitchYAmount;

    public KeyguardClockSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mLargeClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mContext.getResources()
                .getDimensionPixelSize(R.dimen.large_clock_text_size));
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mContext.getResources()
                .getDimensionPixelSize(R.dimen.clock_text_size));

        mClockSwitchYAmount = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_clock_switch_y_shift);
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

        mClockFrame = findViewById(R.id.lockscreen_clock_view);
        mClockView = findViewById(R.id.animatable_clock_view);
        mLargeClockFrame = findViewById(R.id.lockscreen_clock_view_large);
        mLargeClockView = findViewById(R.id.animatable_clock_view_large);
        mKeyguardStatusArea = findViewById(R.id.keyguard_status_area);

        onDensityOrFontScaleChanged();
    }

    void setClockPlugin(ClockPlugin plugin, int statusBarState) {
        // Disconnect from existing plugin.
        if (mClockPlugin != null) {
            View smallClockView = mClockPlugin.getView();
            if (smallClockView != null && smallClockView.getParent() == mClockFrame) {
                mClockFrame.removeView(smallClockView);
            }
            View bigClockView = mClockPlugin.getBigClockView();
            if (bigClockView != null && bigClockView.getParent() == mLargeClockFrame) {
                mLargeClockFrame.removeView(bigClockView);
            }
            mClockPlugin.onDestroyView();
            mClockPlugin = null;
        }
        if (plugin == null) {
            mClockView.setVisibility(View.VISIBLE);
            mLargeClockView.setVisibility(View.VISIBLE);
            return;
        }
        // Attach small and big clock views to hierarchy.
        View smallClockView = plugin.getView();
        if (smallClockView != null) {
            mClockFrame.addView(smallClockView, -1,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            mClockView.setVisibility(View.GONE);
        }
        View bigClockView = plugin.getBigClockView();
        if (bigClockView != null) {
            mLargeClockFrame.addView(bigClockView);
            mLargeClockView.setVisibility(View.GONE);
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
     * It will also update plugin setStyle if plugin is connected.
     */
    public void setStyle(Style style) {
        if (mClockPlugin != null) {
            mClockPlugin.setStyle(style);
        }
    }

    /**
     * It will also update plugin setTextColor if plugin is connected.
     */
    public void setTextColor(int color) {
        if (mClockPlugin != null) {
            mClockPlugin.setTextColor(color);
        }
    }

    private void animateClockChange(boolean useLargeClock) {
        if (mClockInAnim != null) mClockInAnim.cancel();
        if (mClockOutAnim != null) mClockOutAnim.cancel();
        if (mSmartspaceAnim != null) mSmartspaceAnim.cancel();

        View in, out;
        int direction = 1;
        float smartspaceYTranslation;
        if (useLargeClock) {
            out = mClockFrame;
            in = mLargeClockFrame;
            if (indexOfChild(in) == -1) addView(in);
            direction = -1;
            smartspaceYTranslation = mSmartspaceView == null ? 0
                    : mClockFrame.getTop() - mSmartspaceView.getTop();
        } else {
            in = mClockFrame;
            out = mLargeClockFrame;
            smartspaceYTranslation = 0f;

            // Must remove in order for notifications to appear in the proper place
            removeView(out);
        }

        mClockOutAnim = new AnimatorSet();
        mClockOutAnim.setDuration(CLOCK_OUT_MILLIS);
        mClockOutAnim.setInterpolator(Interpolators.FAST_OUT_LINEAR_IN);
        mClockOutAnim.playTogether(
                ObjectAnimator.ofFloat(out, View.ALPHA, 0f),
                ObjectAnimator.ofFloat(out, View.TRANSLATION_Y, 0,
                        direction * -mClockSwitchYAmount));
        mClockOutAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mClockOutAnim = null;
            }
        });

        in.setAlpha(0);
        in.setVisibility(View.VISIBLE);
        mClockInAnim = new AnimatorSet();
        mClockInAnim.setDuration(CLOCK_IN_MILLIS);
        mClockInAnim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        mClockInAnim.playTogether(ObjectAnimator.ofFloat(in, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(in, View.TRANSLATION_Y, direction * mClockSwitchYAmount, 0));
        mClockInAnim.setStartDelay(CLOCK_OUT_MILLIS / 2);
        mClockInAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mClockInAnim = null;
            }
        });

        mClockInAnim.start();
        mClockOutAnim.start();

        if (mSmartspaceView != null) {
            mSmartspaceAnim = ObjectAnimator.ofFloat(mSmartspaceView, View.TRANSLATION_Y,
                    smartspaceYTranslation);
            mSmartspaceAnim.setDuration(SMARTSPACE_MOVE_MILLIS);
            mSmartspaceAnim.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
            mSmartspaceAnim.addListener(new AnimatorListenerAdapter() {
                public void onAnimationEnd(Animator animation) {
                    mSmartspaceAnim = null;
                }
            });
            mSmartspaceAnim.start();
        }
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

    /**
     * Based upon whether notifications are showing or not, display/hide the large clock and
     * the smaller version.
     */
    boolean willSwitchToLargeClock(boolean hasVisibleNotifications) {
        if (mHasVisibleNotifications != null
                && hasVisibleNotifications == mHasVisibleNotifications) {
            return false;
        }
        boolean useLargeClock = !hasVisibleNotifications;
        animateClockChange(useLargeClock);

        mHasVisibleNotifications = hasVisibleNotifications;
        return useLargeClock;
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
        if (mClockPlugin != null) {
            mClockPlugin.onTimeTick();
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

    /**
     * Notifies that the time format has changed.
     *
     * @param timeFormat "12" for 12-hour format, "24" for 24-hour format
     */
    public void onTimeFormatChanged(String timeFormat) {
        if (mClockPlugin != null) {
            mClockPlugin.onTimeFormatChanged(timeFormat);
        }
    }

    void setSmartspaceView(View smartspaceView) {
        mSmartspaceView = smartspaceView;
    }

    void updateColors(ColorExtractor.GradientColors colors) {
        mSupportsDarkText = colors.supportsDarkText();
        mColorPalette = colors.getColorPalette();
        if (mClockPlugin != null) {
            mClockPlugin.setColorPalette(mSupportsDarkText, mColorPalette);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardClockSwitch:");
        pw.println("  mClockPlugin: " + mClockPlugin);
        pw.println("  mClockFrame: " + mClockFrame);
        pw.println("  mLargeClockFrame: " + mLargeClockFrame);
        pw.println("  mKeyguardStatusArea: " + mKeyguardStatusArea);
        pw.println("  mSmartspaceView: " + mSmartspaceView);
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mSupportsDarkText: " + mSupportsDarkText);
        pw.println("  mColorPalette: " + Arrays.toString(mColorPalette));
    }
}
