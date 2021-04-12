/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.WallpaperManager;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceManager;
import android.app.smartspace.SmartspaceSession;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.util.ViewController;

import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardClockSwitch}.
 */
public class KeyguardClockSwitchController extends ViewController<KeyguardClockSwitch> {
    private static final boolean CUSTOM_CLOCKS_ENABLED = true;

    private final Resources mResources;
    private final StatusBarStateController mStatusBarStateController;
    private final SysuiColorExtractor mColorExtractor;
    private final ClockManager mClockManager;
    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final BroadcastDispatcher mBroadcastDispatcher;

    /**
     * Gradient clock for usage when mode != KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL.
     */
    private AnimatableClockController mNewLockScreenClockViewController;
    private FrameLayout mNewLockScreenClockFrame;
    private AnimatableClockController mNewLockScreenLargeClockViewController;
    private FrameLayout mNewLockScreenLargeClockFrame;

    private PluginManager mPluginManager;
    private boolean mIsSmartspaceEnabled;
    PluginListener mPluginListener;
    private Executor mUiExecutor;
    private SmartspaceSession mSmartspaceSession;
    private SmartspaceSession.Callback mSmartspaceCallback;

    private int mLockScreenMode = KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL;

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mView.updateBigClockVisibility(newState);
                }
            };

    /**
     * Listener for changes to the color palette.
     *
     * The color palette changes when the wallpaper is changed.
     */
    private final ColorExtractor.OnColorsChangedListener mColorsListener =
            new ColorExtractor.OnColorsChangedListener() {
        @Override
        public void onColorsChanged(ColorExtractor extractor, int which) {
            if ((which & WallpaperManager.FLAG_LOCK) != 0) {
                mView.updateColors(getGradientColors());
            }
        }
    };

    private ClockManager.ClockChangedListener mClockChangedListener = this::setClockPlugin;
    private String mTimeFormat;

    // If set, will replace keyguard_status_area
    private BcSmartspaceDataPlugin.SmartspaceView mSmartspaceView;

    @Inject
    public KeyguardClockSwitchController(
            KeyguardClockSwitch keyguardClockSwitch,
            @Main Resources resources,
            StatusBarStateController statusBarStateController,
            SysuiColorExtractor colorExtractor, ClockManager clockManager,
            KeyguardSliceViewController keyguardSliceViewController,
            NotificationIconAreaController notificationIconAreaController,
            ContentResolver contentResolver,
            BroadcastDispatcher broadcastDispatcher,
            PluginManager pluginManager,
            FeatureFlags featureFlags,
            @Main Executor uiExecutor) {
        super(keyguardClockSwitch);
        mResources = resources;
        mStatusBarStateController = statusBarStateController;
        mColorExtractor = colorExtractor;
        mClockManager = clockManager;
        mKeyguardSliceViewController = keyguardSliceViewController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBroadcastDispatcher = broadcastDispatcher;
        mTimeFormat = Settings.System.getString(contentResolver, Settings.System.TIME_12_24);
        mPluginManager = pluginManager;
        mIsSmartspaceEnabled = featureFlags.isSmartspaceEnabled();
        mUiExecutor = uiExecutor;
    }

    /**
     * Attach the controller to the view it relates to.
     */
    @Override
    public void onInit() {
        mKeyguardSliceViewController.init();
    }

    @Override
    protected void onViewAttached() {
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.addOnClockChangedListener(mClockChangedListener);
        }
        refreshFormat();
        mStatusBarStateController.addCallback(mStateListener);
        mColorExtractor.addOnColorsChangedListener(mColorsListener);
        mView.updateColors(getGradientColors());
        updateAodIcons();
        mNewLockScreenClockFrame = mView.findViewById(R.id.new_lockscreen_clock_view);
        mNewLockScreenLargeClockFrame = mView.findViewById(R.id.new_lockscreen_clock_view_large);

        // If a smartspace plugin is detected, replace the existing smartspace
        // (keyguard_status_area), and initialize a new session
        mPluginListener = new PluginListener<BcSmartspaceDataPlugin>() {

            @Override
            public void onPluginConnected(BcSmartspaceDataPlugin plugin, Context pluginContext) {
                if (!mIsSmartspaceEnabled) return;

                View ksa = mView.findViewById(R.id.keyguard_status_area);
                int ksaIndex = mView.indexOfChild(ksa);
                ksa.setVisibility(View.GONE);

                mSmartspaceView = plugin.getView(mView);
                mSmartspaceView.registerDataProvider(plugin);

                RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                        MATCH_PARENT, WRAP_CONTENT);
                lp.addRule(RelativeLayout.BELOW, R.id.new_lockscreen_clock_view);
                mView.addView((View) mSmartspaceView, ksaIndex, lp);

                View nic = mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);
                lp = (RelativeLayout.LayoutParams) nic.getLayoutParams();
                lp.addRule(RelativeLayout.BELOW, ((View) mSmartspaceView).getId());
                nic.setLayoutParams(lp);

                createSmartspaceSession(plugin);
            }

            @Override
            public void onPluginDisconnected(BcSmartspaceDataPlugin plugin) {
                if (!mIsSmartspaceEnabled) return;

                mView.removeView((View) mSmartspaceView);
                mView.findViewById(R.id.keyguard_status_area).setVisibility(View.VISIBLE);

                View nic = mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);
                RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams)
                        nic.getLayoutParams();
                lp.addRule(RelativeLayout.BELOW, R.id.keyguard_status_area);
                nic.setLayoutParams(lp);

                mSmartspaceView = null;
            }

            private void createSmartspaceSession(BcSmartspaceDataPlugin plugin) {
                mSmartspaceSession = getContext().getSystemService(SmartspaceManager.class)
                        .createSmartspaceSession(
                                new SmartspaceConfig.Builder(getContext(), "lockscreen").build());
                mSmartspaceCallback = targets -> plugin.onTargetsAvailable(targets);
                mSmartspaceSession.registerSmartspaceUpdates(mUiExecutor, mSmartspaceCallback);
                mSmartspaceSession.requestSmartspaceUpdate();
            }
        };
        mPluginManager.addPluginListener(mPluginListener, BcSmartspaceDataPlugin.class, false);
    }

    @Override
    protected void onViewDetached() {
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.removeOnClockChangedListener(mClockChangedListener);
        }
        mStatusBarStateController.removeCallback(mStateListener);
        mColorExtractor.removeOnColorsChangedListener(mColorsListener);
        mView.setClockPlugin(null, mStatusBarStateController.getState());

        if (mSmartspaceSession != null) {
            mSmartspaceSession.unregisterSmartspaceUpdates(mSmartspaceCallback);
            mSmartspaceSession.destroy();
            mSmartspaceSession = null;
        }
        mPluginManager.removePluginListener(mPluginListener);
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mView.onDensityOrFontScaleChanged();
    }

    /**
     * Set container for big clock face appearing behind NSSL and KeyguardStatusView.
     */
    public void setBigClockContainer(ViewGroup bigClockContainer) {
        mView.setBigClockContainer(bigClockContainer, mStatusBarStateController.getState());
    }

    /**
     * Set whether or not the lock screen is showing notifications.
     */
    public void setHasVisibleNotifications(boolean hasVisibleNotifications) {
        mView.setHasVisibleNotifications(hasVisibleNotifications);
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mView.hasCustomClock();
    }

    /**
     * Get the clock text size.
     */
    public float getClockTextSize() {
        return mView.getTextSize();
    }

    /**
     * Returns the preferred Y position of the clock.
     *
     * @param totalHeight The height available to position the clock.
     * @return Y position of clock.
     */
    public int getClockPreferredY(int totalHeight) {
        return mView.getPreferredY(totalHeight);
    }

    /**
     * Refresh clock. Called in response to TIME_TICK broadcasts.
     */
    void refresh() {
        if (mNewLockScreenClockViewController != null) {
            mNewLockScreenClockViewController.refreshTime();
            mNewLockScreenLargeClockViewController.refreshTime();
        }

        mView.refresh();
    }

    /**
     * Update position of the view, with optional animation. Move the slice view and the clock
     * slightly towards the center in order to prevent burn-in. Y positioning occurs at the
     * view parent level. The large clock view will scale instead of using x position offsets, to
     * keep the clock centered.
     */
    void updatePosition(int x, float scale, AnimationProperties props, boolean animate) {
        x = getCurrentLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -x : x;
        if (mNewLockScreenClockFrame != null) {
            PropertyAnimator.setProperty(mNewLockScreenClockFrame, AnimatableProperty.TRANSLATION_X,
                    x, props, animate);
            PropertyAnimator.setProperty(mNewLockScreenLargeClockFrame, AnimatableProperty.SCALE_X,
                    scale, props, animate);
            PropertyAnimator.setProperty(mNewLockScreenLargeClockFrame, AnimatableProperty.SCALE_Y,
                    scale, props, animate);
        }

        if (mSmartspaceView != null) {
            PropertyAnimator.setProperty((View) mSmartspaceView, AnimatableProperty.TRANSLATION_X,
                    x, props, animate);
        }
        mKeyguardSliceViewController.updatePosition(x, props, animate);
        mNotificationIconAreaController.updatePosition(x, props, animate);
    }

    /**
     * Update lockscreen mode that may change clock display.
     */
    void updateLockScreenMode(int mode) {
        mLockScreenMode = mode;
        if (mode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
            if (mNewLockScreenClockViewController == null) {
                mNewLockScreenClockViewController =
                        new AnimatableClockController(
                                mView.findViewById(R.id.animatable_clock_view),
                                mStatusBarStateController,
                                mBroadcastDispatcher);
                mNewLockScreenClockViewController.init();
                mNewLockScreenLargeClockViewController =
                        new AnimatableClockController(
                                mView.findViewById(R.id.animatable_clock_view_large),
                                mStatusBarStateController,
                                mBroadcastDispatcher);
                mNewLockScreenLargeClockViewController.init();
            }
        } else {
            mNewLockScreenClockViewController = null;
            mNewLockScreenLargeClockViewController = null;
        }
        mView.updateLockScreenMode(mLockScreenMode);
        updateAodIcons();
    }

    void updateTimeZone(TimeZone timeZone) {
        mView.onTimeZoneChanged(timeZone);
        if (mNewLockScreenClockViewController != null) {
            mNewLockScreenClockViewController.onTimeZoneChanged(timeZone);
            mNewLockScreenLargeClockViewController.onTimeZoneChanged(timeZone);
        }
    }

    void refreshFormat(String timeFormat) {
        mTimeFormat = timeFormat;
        Patterns.update(mResources);
        mView.setFormat12Hour(Patterns.sClockView12);
        mView.setFormat24Hour(Patterns.sClockView24);
        mView.onTimeFormatChanged(mTimeFormat);
        if (mNewLockScreenClockViewController != null) {
            mNewLockScreenClockViewController.refreshFormat();
            mNewLockScreenLargeClockViewController.refreshFormat();
        }
    }

    void refreshFormat() {
        refreshFormat(mTimeFormat);
    }

    private void updateAodIcons() {
        NotificationIconContainer nic = (NotificationIconContainer)
                mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);

        if (mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
            // alt icon area is set in KeyguardClockSwitchController
            mNotificationIconAreaController.setupAodIcons(nic, mLockScreenMode);
        } else {
            nic.setVisibility(View.GONE);
        }
    }

    private void setClockPlugin(ClockPlugin plugin) {
        mView.setClockPlugin(plugin, mStatusBarStateController.getState());
    }

    private ColorExtractor.GradientColors getGradientColors() {
        return mColorExtractor.getColors(WallpaperManager.FLAG_LOCK);
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String sClockView12;
        static String sClockView24;
        static String sCacheKey;

        static void update(Resources res) {
            final Locale locale = Locale.getDefault();
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + clockView12Skel + clockView24Skel;
            if (key.equals(sCacheKey)) return;

            sClockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                sClockView12 = sClockView12.replaceAll("a", "").trim();
            }

            sClockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            // Use fancy colon.
            sClockView24 = sClockView24.replace(':', '\uee01');
            sClockView12 = sClockView12.replace(':', '\uee01');

            sCacheKey = key;
        }
    }

    private int getCurrentLayoutDirection() {
        return TextUtils.getLayoutDirectionFromLocale(Locale.getDefault());
    }
}
