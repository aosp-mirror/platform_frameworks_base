package com.android.keyguard;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextClock;

import androidx.annotation.VisibleForTesting;

import com.android.keyguard.clock.BubbleClockController;
import com.android.keyguard.clock.StretchAnalogClockController;
import com.android.keyguard.clock.TypeClockController;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;

import java.util.Objects;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Switch to show plugin clock when plugin is connected, otherwise it will show default clock.
 */
public class KeyguardClockSwitch extends RelativeLayout {

    private LayoutInflater mLayoutInflater;

    private final ContentResolver mContentResolver;
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
    /**
     * Maintain state so that a newly connected plugin can be initialized.
     */
    private float mDarkAmount;

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

    private final ContentObserver mContentObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    if (mClockExtension != null) {
                        mClockExtension.reload();
                    }
                }
    };

    public KeyguardClockSwitch(Context context) {
        this(context, null);
    }

    public KeyguardClockSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLayoutInflater = LayoutInflater.from(context);
        mContentResolver = context.getContentResolver();
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
        mClockExtension = Dependency.get(ExtensionController.class).newExtension(ClockPlugin.class)
                .withPlugin(ClockPlugin.class)
                .withCallback(mClockPluginConsumer)
                // Using withDefault even though this isn't the default as a workaround.
                // ExtensionBulider doesn't provide the ability to supply a ClockPlugin
                // instance based off of the value of a setting. Since multiple "default"
                // can be provided, using a supplier that changes the settings value.
                // A null return will cause Extension#reload to look at the next "default"
                // supplier.
                .withDefault(
                        new SettingsGattedSupplier(
                                mContentResolver,
                                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                                BubbleClockController.class.getName(),
                                () -> BubbleClockController.build(mLayoutInflater)))
                .withDefault(
                        new SettingsGattedSupplier(
                                mContentResolver,
                                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                                StretchAnalogClockController.class.getName(),
                                () -> StretchAnalogClockController.build(mLayoutInflater)))
                .withDefault(
                        new SettingsGattedSupplier(
                                mContentResolver,
                                Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE,
                                TypeClockController.class.getName(),
                                () -> TypeClockController.build(mLayoutInflater)))
                .build();
        mContentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCK_SCREEN_CUSTOM_CLOCK_FACE),
                false, mContentObserver);
        Dependency.get(StatusBarStateController.class).addCallback(mStateListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mClockExtension.destroy();
        mContentResolver.unregisterContentObserver(mContentObserver);
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
        mClockPlugin.setDarkAmount(mDarkAmount);
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

    @VisibleForTesting (otherwise = VisibleForTesting.NONE)
    Consumer<ClockPlugin> getClockPluginConsumer() {
        return mClockPluginConsumer;
    }

    @VisibleForTesting (otherwise = VisibleForTesting.NONE)
    StatusBarStateController.StateListener getStateListener() {
        return mStateListener;
    }

    /**
     * Supplier that only gets an instance when a settings value matches expected value.
     */
    private static class SettingsGattedSupplier implements Supplier<ClockPlugin> {

        private final ContentResolver mContentResolver;
        private final String mKey;
        private final String mValue;
        private final Supplier<ClockPlugin> mSupplier;

        /**
         * Constructs a supplier that changes secure setting key against value.
         *
         * @param contentResolver Used to look up settings value.
         * @param key Settings key.
         * @param value If the setting matches this values that get supplies a ClockPlugin
         *        instance.
         * @param supplier Supplier of ClockPlugin instance, only used if the setting
         *        matches value.
         */
        SettingsGattedSupplier(ContentResolver contentResolver, String key, String value,
                Supplier<ClockPlugin> supplier) {
            mContentResolver = contentResolver;
            mKey = key;
            mValue = value;
            mSupplier = supplier;
        }

        /**
         * Returns null if the settings value doesn't match the expected value.
         *
         * A null return causes Extension#reload to skip this supplier and move to the next.
         */
        @Override
        public ClockPlugin get() {
            final String currentValue = Settings.Secure.getString(mContentResolver, mKey);
            return Objects.equals(currentValue, mValue) ? mSupplier.get() : null;
        }
    }
}
