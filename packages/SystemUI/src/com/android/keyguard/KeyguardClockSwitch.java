package com.android.keyguard;

import static com.android.keyguard.KeyguardStatusAreaView.TRANSLATE_X_CLOCK_DESIGN;
import static com.android.keyguard.KeyguardStatusAreaView.TRANSLATE_Y_CLOCK_DESIGN;
import static com.android.keyguard.KeyguardStatusAreaView.TRANSLATE_Y_CLOCK_SIZE;
import static com.android.systemui.Flags.migrateClocksToBlueprint;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.IntDef;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.res.ResourcesCompat;

import com.android.app.animation.Interpolators;
import com.android.keyguard.dagger.KeyguardStatusViewScope;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.LogLevel;
import com.android.systemui.plugins.clocks.ClockController;
import com.android.systemui.res.R;
import com.android.systemui.shared.clocks.DefaultClockController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
@KeyguardStatusViewScope
public class KeyguardClockSwitch extends RelativeLayout {

    private static final String TAG = "KeyguardClockSwitch";
    public static final String MISSING_CLOCK_ID = "CLOCK_MISSING";

    private static final long CLOCK_OUT_MILLIS = 133;
    private static final long CLOCK_IN_MILLIS = 167;
    public static final long CLOCK_IN_START_DELAY_MILLIS = 133;
    private static final long STATUS_AREA_START_DELAY_MILLIS = 0;
    private static final long STATUS_AREA_MOVE_UP_MILLIS = 967;
    private static final long STATUS_AREA_MOVE_DOWN_MILLIS = 467;
    private static final float SMARTSPACE_TRANSLATION_CENTER_MULTIPLIER = 1.4f;
    private static final float SMARTSPACE_TOP_PADDING_MULTIPLIER = 2.625f;

    @IntDef({LARGE, SMALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ClockSize { }

    public static final int LARGE = 0;
    public static final int SMALL = 1;
    // compensate for translation of parents subject to device screen
    // In this case, the translation comes from KeyguardStatusView
    public int screenOffsetYPadding = 0;

    /** Returns a region for the large clock to position itself, based on the given parent. */
    public static Rect getLargeClockRegion(ViewGroup parent) {
        int largeClockTopMargin = parent.getResources()
                .getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.keyguard_large_clock_top_margin);
        int targetHeight = parent.getResources()
                .getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.large_clock_text_size)
                * 2;
        int top = parent.getHeight() / 2 - targetHeight / 2
                + largeClockTopMargin / 2;
        return new Rect(
                parent.getLeft(),
                top,
                parent.getRight(),
                top + targetHeight);
    }

    /** Returns a region for the small clock to position itself, based on the given parent. */
    public static Rect getSmallClockRegion(ViewGroup parent) {
        int targetHeight = parent.getResources()
                .getDimensionPixelSize(
                        com.android.systemui.customization.R.dimen.small_clock_text_size);
        return new Rect(
                parent.getLeft(),
                parent.getTop(),
                parent.getRight(),
                parent.getTop() + targetHeight);
    }

    /**
     * Frame for small/large clocks
     */
    private KeyguardClockFrame mSmallClockFrame;
    private KeyguardClockFrame mLargeClockFrame;
    private ClockController mClock;

    // It's bc_smartspace_view, assigned by KeyguardClockSwitchController
    // to get the top padding for translating smartspace for weather clock
    private View mSmartspace;

    // Smartspace in weather clock is translated by this value
    // to compensate for the position invisible dateWeatherView
    private int mSmartspaceTop = -1;

    private KeyguardStatusAreaView mStatusArea;
    private int mSmartspaceTopOffset;
    private float mWeatherClockSmartspaceScaling = 1f;
    private int mWeatherClockSmartspaceTranslateX = 0;
    private int mWeatherClockSmartspaceTranslateY = 0;
    private int mDrawAlpha = 255;

    private int mStatusBarHeight = 0;

    /**
     * Maintain state so that a newly connected plugin can be initialized.
     */
    private float mDarkAmount;
    private boolean mSplitShadeCentered = false;

    /**
     * Indicates which clock is currently displayed - should be one of {@link ClockSize}.
     * Use null to signify it is uninitialized.
     */
    @ClockSize private Integer mDisplayedClockSize = null;

    @VisibleForTesting AnimatorSet mClockInAnim = null;
    @VisibleForTesting AnimatorSet mClockOutAnim = null;
    @VisibleForTesting AnimatorSet mStatusAreaAnim = null;

    private int mClockSwitchYAmount;
    @VisibleForTesting boolean mChildrenAreLaidOut = false;
    @VisibleForTesting boolean mAnimateOnLayout = true;
    private LogBuffer mLogBuffer = null;

    public KeyguardClockSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Apply dp changes on configuration change
     */
    public void onConfigChanged() {
        mClockSwitchYAmount = mContext.getResources().getDimensionPixelSize(
                R.dimen.keyguard_clock_switch_y_shift);
        mSmartspaceTopOffset = (int) (mContext.getResources().getDimensionPixelSize(
                        R.dimen.keyguard_smartspace_top_offset)
                * mContext.getResources().getConfiguration().fontScale
                / mContext.getResources().getDisplayMetrics().density
                * SMARTSPACE_TOP_PADDING_MULTIPLIER);
        mWeatherClockSmartspaceScaling = ResourcesCompat.getFloat(
                mContext.getResources(), R.dimen.weather_clock_smartspace_scale);
        mWeatherClockSmartspaceTranslateX = mContext.getResources().getDimensionPixelSize(
                R.dimen.weather_clock_smartspace_translateX);
        mWeatherClockSmartspaceTranslateY = mContext.getResources().getDimensionPixelSize(
                R.dimen.weather_clock_smartspace_translateY);
        mStatusBarHeight = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_height);
        updateStatusArea(/* animate= */false);
    }

    /** Get bc_smartspace_view from KeyguardClockSwitchController
     * Use its top to decide the translation value */
    public void setSmartspace(View smartspace) {
        mSmartspace = smartspace;
    }

    /** Sets whether the large clock is being shown on a connected display. */
    public void setLargeClockOnSecondaryDisplay(boolean onSecondaryDisplay) {
        if (mClock != null) {
            mClock.getLargeClock().getEvents().onSecondaryDisplayChanged(onSecondaryDisplay);
        }
    }

    /**
     * Enable or disable split shade specific positioning
     */
    public void setSplitShadeCentered(boolean splitShadeCentered) {
        if (mSplitShadeCentered != splitShadeCentered) {
            mSplitShadeCentered = splitShadeCentered;
            updateStatusArea(/* animate= */true);
        }
    }

    public boolean getSplitShadeCentered() {
        return mSplitShadeCentered;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (!migrateClocksToBlueprint()) {
            mSmallClockFrame = findViewById(R.id.lockscreen_clock_view);
            mLargeClockFrame = findViewById(R.id.lockscreen_clock_view_large);
            mStatusArea = findViewById(R.id.keyguard_status_area);
        }
        onConfigChanged();
    }

    @Override
    protected boolean onSetAlpha(int alpha) {
        mDrawAlpha = alpha;
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        KeyguardClockFrame.saveCanvasAlpha(
                this, canvas, mDrawAlpha,
                c -> {
                    super.dispatchDraw(c);
                    return kotlin.Unit.INSTANCE;
                });
    }

    public void setLogBuffer(LogBuffer logBuffer) {
        mLogBuffer = logBuffer;
    }

    public LogBuffer getLogBuffer() {
        return mLogBuffer;
    }

    /** Returns the id of the currently rendering clock */
    public String getClockId() {
        if (mClock == null) {
            return MISSING_CLOCK_ID;
        }
        return mClock.getConfig().getId();
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
        updateStatusArea(/* animate= */false);
    }

    private void updateStatusArea(boolean animate) {
        if (mDisplayedClockSize != null && mChildrenAreLaidOut) {
            updateClockViews(mDisplayedClockSize == LARGE, animate);
        }
    }

    void updateClockTargetRegions() {
        if (mClock != null) {
            if (mSmallClockFrame.isLaidOut()) {
                Rect targetRegion = getSmallClockRegion(mSmallClockFrame);
                mClock.getSmallClock().getEvents().onTargetRegionChanged(targetRegion);
            }

            if (mLargeClockFrame.isLaidOut()) {
                Rect targetRegion = getLargeClockRegion(mLargeClockFrame);
                if (mClock instanceof DefaultClockController) {
                    mClock.getLargeClock().getEvents().onTargetRegionChanged(
                            targetRegion);
                } else {
                    mClock.getLargeClock().getEvents().onTargetRegionChanged(
                            new Rect(
                                    targetRegion.left,
                                    targetRegion.top - screenOffsetYPadding,
                                    targetRegion.right,
                                    targetRegion.bottom - screenOffsetYPadding));
                }
            }
        }
    }

    private void updateClockViews(boolean useLargeClock, boolean animate) {
        if (mLogBuffer != null) {
            mLogBuffer.log(TAG, LogLevel.DEBUG, (msg) -> {
                msg.setBool1(useLargeClock);
                msg.setBool2(animate);
                msg.setBool3(mChildrenAreLaidOut);
                return kotlin.Unit.INSTANCE;
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
        // statusAreaYTranslation uses for the translation for both mStatusArea and mSmallClockFrame
        // statusAreaClockTranslateY only uses for mStatusArea
        float statusAreaYTranslation, statusAreaClockScale = 1f;
        float statusAreaClockTranslateX = 0f, statusAreaClockTranslateY = 0f;
        float clockInYTranslation, clockOutYTranslation;
        if (useLargeClock) {
            out = mSmallClockFrame;
            in = mLargeClockFrame;
            if (indexOfChild(in) == -1) addView(in, 0);
            statusAreaYTranslation = mSmallClockFrame.getTop() - mStatusArea.getTop()
                    + mSmartspaceTopOffset;
            // TODO: Load from clock config when less risky
            if (mClock != null
                    && mClock.getLargeClock().getConfig().getHasCustomWeatherDataDisplay()) {
                statusAreaClockScale = mWeatherClockSmartspaceScaling;
                statusAreaClockTranslateX = mWeatherClockSmartspaceTranslateX;
                if (mSplitShadeCentered) {
                    statusAreaClockTranslateX *= SMARTSPACE_TRANSLATION_CENTER_MULTIPLIER;
                }

                // On large weather clock,
                // top padding for time is status bar height from top of the screen.
                // On small one,
                // it's screenOffsetYPadding (translationY for KeyguardStatusView),
                // Cause smartspace is positioned according to the smallClockFrame
                // we need to translate the difference between bottom of large clock and small clock
                // Also, we need to counter offset the empty date weather view, mSmartspaceTop
                // mWeatherClockSmartspaceTranslateY is only for Felix
                statusAreaClockTranslateY = mStatusBarHeight - 0.6F *  mSmallClockFrame.getHeight()
                        - mSmartspaceTop - screenOffsetYPadding
                        - statusAreaYTranslation + mWeatherClockSmartspaceTranslateY;
            }
            clockInYTranslation = 0;
            clockOutYTranslation = 0; // Small clock translation is handled with statusArea
        } else {
            in = mSmallClockFrame;
            out = mLargeClockFrame;
            statusAreaYTranslation = 0f;
            clockInYTranslation = 0f;
            clockOutYTranslation = mClockSwitchYAmount * -1f;

            // Must remove in order for notifications to appear in the proper place, ideally this
            // would happen after the out animation runs, but we can't guarantee that the
            // nofications won't enter only after the out animation runs.
            removeView(out);
        }

        if (!animate) {
            out.setAlpha(0f);
            out.setTranslationY(clockOutYTranslation);
            out.setVisibility(INVISIBLE);
            in.setAlpha(1f);
            in.setTranslationY(clockInYTranslation);
            in.setVisibility(VISIBLE);
            mStatusArea.setScaleX(statusAreaClockScale);
            mStatusArea.setScaleY(statusAreaClockScale);
            mStatusArea.setTranslateXFromClockDesign(statusAreaClockTranslateX);
            mStatusArea.setTranslateYFromClockDesign(statusAreaClockTranslateY);
            mStatusArea.setTranslateYFromClockSize(statusAreaYTranslation);
            mSmallClockFrame.setTranslationY(statusAreaYTranslation);
            return;
        }

        mClockOutAnim = new AnimatorSet();
        mClockOutAnim.setDuration(CLOCK_OUT_MILLIS);
        mClockOutAnim.setInterpolator(Interpolators.LINEAR);
        mClockOutAnim.playTogether(
                ObjectAnimator.ofFloat(out, ALPHA, 0f),
                ObjectAnimator.ofFloat(out, TRANSLATION_Y, clockOutYTranslation));
        mClockOutAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                if (mClockOutAnim == animation) {
                    out.setVisibility(INVISIBLE);
                    mClockOutAnim = null;
                }
            }
        });

        in.setVisibility(View.VISIBLE);
        mClockInAnim = new AnimatorSet();
        mClockInAnim.setDuration(CLOCK_IN_MILLIS);
        mClockInAnim.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
        mClockInAnim.playTogether(
                ObjectAnimator.ofFloat(in, ALPHA, 1f),
                ObjectAnimator.ofFloat(in, TRANSLATION_Y, clockInYTranslation));
        mClockInAnim.setStartDelay(CLOCK_IN_START_DELAY_MILLIS);
        mClockInAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                if (mClockInAnim == animation) {
                    mClockInAnim = null;
                }
            }
        });

        mStatusAreaAnim = new AnimatorSet();
        mStatusAreaAnim.setStartDelay(STATUS_AREA_START_DELAY_MILLIS);
        mStatusAreaAnim.setDuration(
                useLargeClock ? STATUS_AREA_MOVE_UP_MILLIS : STATUS_AREA_MOVE_DOWN_MILLIS);
        mStatusAreaAnim.setInterpolator(Interpolators.EMPHASIZED);
        mStatusAreaAnim.playTogether(
                ObjectAnimator.ofFloat(mStatusArea, TRANSLATE_Y_CLOCK_SIZE.getProperty(),
                        statusAreaYTranslation),
                ObjectAnimator.ofFloat(mSmallClockFrame, TRANSLATION_Y, statusAreaYTranslation),
                ObjectAnimator.ofFloat(mStatusArea, SCALE_X, statusAreaClockScale),
                ObjectAnimator.ofFloat(mStatusArea, SCALE_Y, statusAreaClockScale),
                ObjectAnimator.ofFloat(mStatusArea, TRANSLATE_X_CLOCK_DESIGN.getProperty(),
                        statusAreaClockTranslateX),
                ObjectAnimator.ofFloat(mStatusArea, TRANSLATE_Y_CLOCK_DESIGN.getProperty(),
                        statusAreaClockTranslateY));
        mStatusAreaAnim.addListener(new AnimatorListenerAdapter() {
            public void onAnimationEnd(Animator animation) {
                if (mStatusAreaAnim == animation) {
                    mStatusAreaAnim = null;
                }
            }
        });

        mClockInAnim.start();
        mClockOutAnim.start();
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
        // TODO: b/305022530
        if (mClock != null && mClock.getConfig().getId().equals("DIGITAL_CLOCK_METRO")) {
            mClock.getEvents().onColorPaletteChanged(mContext.getResources());
        }

        if (changed) {
            post(() -> updateClockTargetRegions());
        }

        if (mSmartspace != null && mSmartspaceTop != mSmartspace.getTop()
                && mDisplayedClockSize != null) {
            mSmartspaceTop = mSmartspace.getTop();
            post(() -> updateClockViews(mDisplayedClockSize == LARGE, mAnimateOnLayout));
        }

        if (mDisplayedClockSize != null && !mChildrenAreLaidOut) {
            post(() -> updateClockViews(mDisplayedClockSize == LARGE, mAnimateOnLayout));
        }
        mChildrenAreLaidOut = true;
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardClockSwitch:");
        pw.println("  mSmallClockFrame = " + mSmallClockFrame);
        if (mSmallClockFrame != null) {
            pw.println("  mSmallClockFrame.alpha = " + mSmallClockFrame.getAlpha());
        }
        pw.println("  mLargeClockFrame = " + mLargeClockFrame);
        if (mLargeClockFrame != null) {
            pw.println("  mLargeClockFrame.alpha = " + mLargeClockFrame.getAlpha());
        }
        pw.println("  mStatusArea = " + mStatusArea);
        pw.println("  mDisplayedClockSize = " + mDisplayedClockSize);
    }
}
