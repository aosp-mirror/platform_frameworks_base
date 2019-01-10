package com.android.keyguard;

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

import com.android.systemui.Dependency;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;

import java.util.TimeZone;
import java.util.function.Consumer;

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
     * Used to select between plugin or default implementations of ClockPlugin interface.
     */
    private Extension<ClockPlugin> mClockExtension;
    /**
     * Consumer that accepts the a new ClockPlugin implementation when the Extension reloads.
     */
    private final Consumer<ClockPlugin> mClockPluginConsumer = plugin -> setClockPlugin(plugin);

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

    public KeyguardClockSwitch(Context context) {
        this(context, null);
    }

    public KeyguardClockSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
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
        mClockExtension = Dependency.get(ExtensionController.class).newExtension(ClockPlugin.class)
                .withPlugin(ClockPlugin.class)
                .withCallback(mClockPluginConsumer)
                .build();
        Dependency.get(StatusBarStateController.class).addCallback(mStateListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mClockExtension.destroy();
        Dependency.get(StatusBarStateController.class).removeCallback(mStateListener);
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
    }

    /**
     * Set container for big clock face appearing behind NSSL and KeyguardStatusView.
     */
    public void setBigClockContainer(ViewGroup container) {
        if (mClockPlugin != null && container != null) {
            View bigClockView = mClockPlugin.getBigClockView();
            if (bigClockView != null) {
                container.addView(bigClockView);
                container.setVisibility(View.VISIBLE);
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

    public void setElegantTextHeight(boolean elegant) {
        mClockView.setElegantTextHeight(elegant);
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

    @VisibleForTesting (otherwise = VisibleForTesting.NONE)
    Consumer<ClockPlugin> getClockPluginConsumer() {
        return mClockPluginConsumer;
    }

    @VisibleForTesting (otherwise = VisibleForTesting.NONE)
    StatusBarStateController.StateListener getStateListener() {
        return mStateListener;
    }
}
