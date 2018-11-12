package com.android.keyguard;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.plugins.PluginManager;

import java.util.Objects;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
public class KeyguardClockSwitch extends FrameLayout {
    /**
     * Optional/alternative clock injected via plugin.
     */
    private ClockPlugin mClockPlugin;
    /**
     * Default clock.
     */
    private TextClock mClockView;

    private final PluginListener<ClockPlugin> mClockPluginListener =
            new PluginListener<ClockPlugin>() {
                @Override
                public void onPluginConnected(ClockPlugin plugin, Context pluginContext) {
                    View view = plugin.getView();
                    if (view != null) {
                        disconnectPlugin();
                        // For now, assume that the most recently connected plugin is the
                        // selected clock face. In the future, the user should be able to
                        // pick a clock face from the available plugins.
                        mClockPlugin = plugin;
                        addView(view, -1,
                                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT));
                        initPluginParams();
                        mClockView.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onPluginDisconnected(ClockPlugin plugin) {
                    if (Objects.equals(plugin, mClockPlugin)) {
                        disconnectPlugin();
                        mClockView.setVisibility(View.VISIBLE);
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
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Dependency.get(PluginManager.class).addPluginListener(mClockPluginListener,
                ClockPlugin.class);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Dependency.get(PluginManager.class).removePluginListener(mClockPluginListener);
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
     * When plugin changes, set all kept parameters into newer plugin.
     */
    private void initPluginParams() {
        if (mClockPlugin != null) {
            mClockPlugin.setStyle(getPaint().getStyle());
            mClockPlugin.setTextColor(getCurrentTextColor());
        }
    }

    private void disconnectPlugin() {
        if (mClockPlugin != null) {
            View view = mClockPlugin.getView();
            if (view != null) {
                removeView(view);
            }
            mClockPlugin = null;
        }
    }

    @VisibleForTesting (otherwise = VisibleForTesting.NONE)
    PluginListener getClockPluginListener() {
        return mClockPluginListener;
    }
}
