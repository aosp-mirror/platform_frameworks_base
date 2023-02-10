package com.android.keyguard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;

import com.android.keyguard.dagger.KeyguardStatusViewScope;
import com.android.systemui.R;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.plugins.ClockController;
import com.android.systemui.plugins.log.LogBuffer;
import com.android.systemui.plugins.log.LogLevel;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import kotlin.Unit;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
@KeyguardStatusViewScope
public class KeyguardClockSwitch extends RelativeLayout {

    private static final String TAG = "KeyguardClockSwitch";

    private static final long CLOCK_OUT_MILLIS = 150;
    private static final long CLOCK_IN_MILLIS = 200;
    private static final long STATUS_AREA_MOVE_MILLIS = 350;

    @IntDef({LARGE, SMALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ClockSize { }

    public static final int LARGE = 0;
    public static final int SMALL = 1;

    /** Returns a region for the large clock to position itself, based on the given parent. */
    public static Rect getLargeClockRegion(ViewGroup parent) {
        int largeClockTopMargin = parent.getResources()
                .getDimensionPixelSize(R.dimen.keyguard_large_clock_top_margin);
        int targetHeight = parent.getResources()
                .getDimensionPixelSize(R.dimen.large_clock_text_size) * 2;
        int top = parent.getHeight() / 2 - targetHeight / 2
                + largeClockTopMargin / 2;
        return new Rect(
                parent.getLeft(),
                top,
                parent.getRight(),
                top + targetHeight);
    }

    /**
     * Frame for small/large clocks
     */
    private FrameLayout mSmallClockFrame;
    private FrameLayout mLargeClockFrame;
    private ClockController mClock;

    private View mStatusArea;
    private int mSmartspaceTopOffset;

    /**
     * Maintain state so that a newly connected plugin can be initialized.
     */
    private float mDarkAmount;

    /**
     * Indicates which clock is currently displayed - should be one of {@link ClockSize}.
     * Use null to signify it is uninitialized.
     */
    @ClockSize private Integer mDisplayedClockSize = null;

    @VisibleForTesting AnimatorSet mClockInAnim = null;
    @VisibleForTesting AnimatorSet mClockOutAnim = null;
    private ObjectAnimator mStatusAreaAnim = null;

    private int mClockSwitchYAmount;
    @VisibleForTesting boolean mChildrenAreLaidOut = false;
    @VisibleForTesting boolean mAnimateOnLayout = true;
    private LogBuffer mLogBuffer = null;

    public KeyguardClockSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mClockSwitchYAmount = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_clock_switch_y_shift);
        mSmartspaceTopOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_smartspace_top_offset);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSmallClockFrame = findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = findViewById(R.id.lockscreen_clock_view_large);
        mStatusArea = findViewById(R.id.keyguard_status_area);

        onDensityOrFontScaleChanged();
    }

    public void setLogBuffer(LogBuffer logBuffer) {
        mLogBuffer = logBuffer;
    }

    public LogBuffer getLogBuffer() {
        return mLogBuffer;
    }

    void setClock(ClockController clock, int statusBarState) {
        mClock = clock;

        // Disconnect from existing plugin.
        mSmallClockFrame.removeAllViews();
        mLargeClockFrame.removeAllViews();

        if (clock == null) {
            if (mLogBuffer != null) {
                mLogBuffer.log(TAG, LogLevel.ERROR, "No clock being shown");
            }
            return;
        }

        // Attach small and big clock views to hierarchy.
        if (mLogBuffer != null) {
            mLogBuffer.log(TAG, LogLevel.INFO, "Attached new clock views to switch");
        }
        mSmallClockFrame.addView(clock.getSmallClock().getView());
        mLargeClockFrame.addView(clock.getLargeClock().getView());
        updateClockTargetRegions();
    }

    void updateClockTargetRegions() {
        if (mClock != null) {
            if (mSmallClockFrame.isLaidOut()) {
                int targetHeight =  getResources()
                        .getDimensionPixelSize(R.dimen.small_clock_text_size);
                mClock.getSmallClock().getEvents().onTargetRegionChanged(new Rect(
                        mSmallClockFrame.getLeft(),
                        mSmallClockFrame.getTop(),
                        mSmallClockFrame.getRight(),
                        mSmallClockFrame.getTop() + targetHeight));
            }

            if (mLargeClockFrame.isLaidOut()) {
                mClock.getLargeClock().getEvents().onTargetRegionChanged(
                        getLargeClockRegion(mLargeClockFrame));
            }
        }
    }

    private void updateClockViews(boolean useLargeClock, boolean animate) {
        if (mLogBuffer != null) {
            mLogBuffer.log(TAG, LogLevel.DEBUG, (msg) -> {
                msg.setBool1(useLargeClock);
                msg.setBool2(animate);
                msg.setBool3(mChildrenAreLaidOut);
                return Unit.INSTANCE;
            }, (msg) -> "updateClockViews"
                    + "; useLargeClock=" + msg.getBool1()
                    + "; animate=" + msg.getBool2()
                    + "; mChildrenAreLaidOut=" + msg.getBool3());
        }

        if (mClockInAnim != null) mClockInAnim.cancel();
        if (mClockOutAnim != null) mClockOutAnim.cancel();
        if (mStatusAreaAnim != null) mStatusAreaAnim.cancel();

        mClockInAnim = null;
        mClockOutAnim = null;
        mStatusAreaAnim = null;

        View in, out;
        int direction = 1;
        float statusAreaYTranslation;
        if (useLargeClock) {
            out = mSmallClockFrame;
            in = mLargeClockFrame;
            if (indexOfChild(in) == -1) addView(in, 0);
            direction = -1;
            statusAreaYTranslation = mSmallClockFrame.getTop() - mStatusArea.getTop()
                    + mSmartspaceTopOffset;
        } else {
            in = mSmallClockFrame;
            out = mLargeClockFrame;
            statusAreaYTranslation = 0f;

            // Must remove in order for notifications to appear in the proper place
            removeView(out);
        }

        if (!animate) {
            out.setAlpha(0f);
            in.setAlpha(1f);
            in.setVisibility(VISIBLE);
            mStatusArea.setTranslationY(statusAreaYTranslation);
            return;
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

        mStatusAreaAnim = ObjectAnimator.ofFloat(mStatusArea, View.TRANSLATION_Y,
                statusAreaYTranslation);
        mStatusAreaAnim.setDuration(STATUS_AREA_MOVE_MILLIS);
        mStatusAreaAnim.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mStatusAreaAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                mStatusAreaAnim = null;
            }
        });
        mStatusAreaAnim.start();
    }

    /**
     * Display the desired clock and hide the other one
     *
     * @return true if desired clock appeared and false if it was already visible
     */
    boolean switchToClock(@ClockSize int clockSize, boolean animate) {
        if (mDisplayedClockSize != null && clockSize == mDisplayedClockSize) {
            return false;
        }

        // let's make sure clock is changed only after all views were laid out so we can
        // translate them properly
        if (mChildrenAreLaidOut) {
            updateClockViews(clockSize == LARGE, animate);
        }

        mDisplayedClockSize = clockSize;
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (changed) {
            post(() -> updateClockTargetRegions());
        }

        if (mDisplayedClockSize != null && !mChildrenAreLaidOut) {
            post(() -> updateClockViews(mDisplayedClockSize == LARGE, mAnimateOnLayout));
        }

        mChildrenAreLaidOut = true;
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardClockSwitch:");
        pw.println("  mSmallClockFrame: " + mSmallClockFrame);
        pw.println("  mSmallClockFrame.alpha: " + mSmallClockFrame.getAlpha());
        pw.println("  mLargeClockFrame: " + mLargeClockFrame);
        pw.println("  mLargeClockFrame.alpha: " + mLargeClockFrame.getAlpha());
        pw.println("  mStatusArea: " + mStatusArea);
        pw.println("  mDisplayedClockSize: " + mDisplayedClockSize);
    }
}
