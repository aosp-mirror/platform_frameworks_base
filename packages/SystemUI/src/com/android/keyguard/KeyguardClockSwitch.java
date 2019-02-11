package com.android.keyguard;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;

import androidx.annotation.VisibleForTesting;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.Dependency;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;

import java.util.TimeZone;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
public class KeyguardClockSwitch extends RelativeLayout {

    /**
     * Optional/alternative clock injected via plugin.
     */
    private ClockPlugin mClockPlugin;
    /**
     * Default clock.
     */
    private TextClock mClockView;
    /**
     * Frame for default and custom clock.
     */
    private FrameLayout mSmallClockFrame;
    /**
     * Container for big custom clock.
     */
    private ViewGroup mBigClockContainer;
    /**
     * Status area (date and other stuff) shown below the clock. Plugin can decide whether
     * or not to show it below the alternate clock.
     */
    private View mKeyguardStatusArea;
    /**
     * Maintain state so that a newly connected plugin can be initialized.
     */
    private float mDarkAmount;
    private boolean mSupportsDarkText;
    private int[] mColorPalette;

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    if (mBigClockContainer == null) {
                        return;
                    }
                    if (newState == StatusBarState.SHADE) {
                        if (mBigClockContainer.getVisibility() == View.VISIBLE) {
                            mBigClockContainer.setVisibility(View.INVISIBLE);
                        }
                    } else {
                        if (mBigClockContainer.getVisibility() == View.INVISIBLE) {
                            mBigClockContainer.setVisibility(View.VISIBLE);
                        }
                    }
                }
    };

    private ClockManager.ClockChangedListener mClockChangedListener = this::setClockPlugin;

    /**
     * Listener for changes to the color palette.
     *
     * The color palette changes when the wallpaper is changed.
     */
    private SysuiColorExtractor.OnColorsChangedListener mColorsListener = (extractor, which) -> {
        if ((which & WallpaperManager.FLAG_LOCK) != 0) {
            if (extractor instanceof SysuiColorExtractor) {
                updateColors((SysuiColorExtractor) extractor);
            } else {
                updateColors(Dependency.get(SysuiColorExtractor.class));
            }
        }
    };

    public KeyguardClockSwitch(Context context) {
        this(context, null);
    }

    public KeyguardClockSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
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
        mSmallClockFrame = findViewById(R.id.clock_view);
        mKeyguardStatusArea = findViewById(R.id.keyguard_status_area);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(ClockManager.class).addOnClockChangedListener(mClockChangedListener);
        Dependency.get(StatusBarStateController.class).addCallback(mStateListener);
        SysuiColorExtractor colorExtractor = Dependency.get(SysuiColorExtractor.class);
        colorExtractor.addOnColorsChangedListener(mColorsListener);
        updateColors(colorExtractor);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(ClockManager.class).removeOnClockChangedListener(mClockChangedListener);
        Dependency.get(StatusBarStateController.class).removeCallback(mStateListener);
        Dependency.get(SysuiColorExtractor.class)
            .removeOnColorsChangedListener(mColorsListener);
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
                mBigClockContainer.setVisibility(View.GONE);
            }
            mClockPlugin = null;
        }
        if (plugin == null) {
            mClockView.setVisibility(View.VISIBLE);
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
        }
        View bigClockView = plugin.getBigClockView();
        if (bigClockView != null && mBigClockContainer != null) {
            mBigClockContainer.addView(bigClockView);
            mBigClockContainer.setVisibility(View.VISIBLE);
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
                if (container.getVisibility() == View.GONE) {
                    container.setVisibility(View.VISIBLE);
                }
            }
        }
        mBigClockContainer = container;
    }

    /**
     * It will also update plugin setStyle if plugin is connected.
     */
    public void setStyle(Style style) {
        mClockView.getPaint().setStyle(style);
        if (mClockPlugin != null) {
            mClockPlugin.setStyle(style);
        }
    }

    /**
     * It will also update plugin setTextColor if plugin is connected.
     */
    public void setTextColor(int color) {
        mClockView.setTextColor(color);
        if (mClockPlugin != null) {
            mClockPlugin.setTextColor(color);
        }
    }

    public void setShowCurrentUserTime(boolean showCurrentUserTime) {
        mClockView.setShowCurrentUserTime(showCurrentUserTime);
    }

    public void setTextSize(int unit, float size) {
        mClockView.setTextSize(unit, size);
    }

    public void setFormat12Hour(CharSequence format) {
        mClockView.setFormat12Hour(format);
    }

    public void setFormat24Hour(CharSequence format) {
        mClockView.setFormat24Hour(format);
    }

    /**
     * Set the amount (ratio) that the device has transitioned to doze.
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

    public void refresh() {
        mClockView.refresh();
    }

    /**
     * Notifies that time tick alarm from doze service fired.
     */
    public void dozeTimeTick() {
        if (mClockPlugin != null) {
            mClockPlugin.dozeTimeTick();
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

    private void updateColors(SysuiColorExtractor colorExtractor) {
        ColorExtractor.GradientColors colors = colorExtractor.getColors(WallpaperManager.FLAG_LOCK,
                true);
        mSupportsDarkText = colors.supportsDarkText();
        mColorPalette = colors.getColorPalette();
        if (mClockPlugin != null) {
            mClockPlugin.setColorPalette(mSupportsDarkText, mColorPalette);
        }
    }

    @VisibleForTesting (otherwise = VisibleForTesting.NONE)
    ClockManager.ClockChangedListener getClockChangedListener() {
        return mClockChangedListener;
    }

    @VisibleForTesting (otherwise = VisibleForTesting.NONE)
    StatusBarStateController.StateListener getStateListener() {
        return mStateListener;
    }
}
