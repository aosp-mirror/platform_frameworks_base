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

import android.app.PendingIntent;
import android.app.WallpaperManager;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceManager;
import android.app.smartspace.SmartspaceSession;
import android.app.smartspace.SmartspaceTarget;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.colorextraction.ColorExtractor;
import com.android.keyguard.clock.ClockManager;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.BcSmartspaceDataPlugin;
import com.android.systemui.plugins.BcSmartspaceDataPlugin.IntentStarter;
import com.android.systemui.plugins.ClockPlugin;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.NotificationIconContainer;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.settings.SecureSettings;

import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardClockSwitch}.
 */
public class KeyguardClockSwitchController extends ViewController<KeyguardClockSwitch> {
    private static final boolean CUSTOM_CLOCKS_ENABLED = true;

    private final StatusBarStateController mStatusBarStateController;
    private final SysuiColorExtractor mColorExtractor;
    private final ClockManager mClockManager;
    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final NotificationIconAreaController mNotificationIconAreaController;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Executor mUiExecutor;
    private final BatteryController mBatteryController;
    private final FeatureFlags mFeatureFlags;

    /**
     * Clock for both small and large sizes
     */
    private AnimatableClockController mClockViewController;
    private FrameLayout mClockFrame;
    private AnimatableClockController mLargeClockViewController;
    private FrameLayout mLargeClockFrame;

    private SmartspaceSession mSmartspaceSession;
    private SmartspaceSession.OnTargetsAvailableListener mSmartspaceCallback;
    private ConfigurationController mConfigurationController;
    private ActivityStarter mActivityStarter;
    private FalsingManager mFalsingManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardBypassController mBypassController;
    private Handler mHandler;
    private UserTracker mUserTracker;
    private SecureSettings mSecureSettings;
    private ContentObserver mSettingsObserver;
    private boolean mShowSensitiveContentForCurrentUser;
    private boolean mShowSensitiveContentForManagedUser;
    private UserHandle mManagedUserHandle;

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

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onThemeChanged() {
            updateWallpaperColor();
        }
    };

    private ClockManager.ClockChangedListener mClockChangedListener = this::setClockPlugin;

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozeAmountChanged(float linear, float eased) {
                    if (mSmartspaceView != null) {
                        mSmartspaceView.setDozeAmount(eased);
                    }
                }
            };

    // If set, will replace keyguard_status_area
    private BcSmartspaceDataPlugin.SmartspaceView mSmartspaceView;
    private Optional<BcSmartspaceDataPlugin> mSmartspacePlugin;

    @Inject
    public KeyguardClockSwitchController(
            KeyguardClockSwitch keyguardClockSwitch,
            StatusBarStateController statusBarStateController,
            SysuiColorExtractor colorExtractor, ClockManager clockManager,
            KeyguardSliceViewController keyguardSliceViewController,
            NotificationIconAreaController notificationIconAreaController,
            BroadcastDispatcher broadcastDispatcher,
            FeatureFlags featureFlags,
            @Main Executor uiExecutor,
            BatteryController batteryController,
            ConfigurationController configurationController,
            ActivityStarter activityStarter,
            FalsingManager falsingManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardBypassController bypassController,
            @Main Handler handler,
            UserTracker userTracker,
            SecureSettings secureSettings,
            Optional<BcSmartspaceDataPlugin> smartspacePlugin) {
        super(keyguardClockSwitch);
        mStatusBarStateController = statusBarStateController;
        mColorExtractor = colorExtractor;
        mClockManager = clockManager;
        mKeyguardSliceViewController = keyguardSliceViewController;
        mNotificationIconAreaController = notificationIconAreaController;
        mBroadcastDispatcher = broadcastDispatcher;
        mFeatureFlags = featureFlags;
        mUiExecutor = uiExecutor;
        mBatteryController = batteryController;
        mConfigurationController = configurationController;
        mActivityStarter = activityStarter;
        mFalsingManager = falsingManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mBypassController = bypassController;
        mHandler = handler;
        mUserTracker = userTracker;
        mSecureSettings = secureSettings;
        mSmartspacePlugin = smartspacePlugin;
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
        mColorExtractor.addOnColorsChangedListener(mColorsListener);
        mView.updateColors(getGradientColors());
        updateAodIcons();

        mClockFrame = mView.findViewById(R.id.lockscreen_clock_view);
        mLargeClockFrame = mView.findViewById(R.id.lockscreen_clock_view_large);

        mClockViewController =
                new AnimatableClockController(
                        mView.findViewById(R.id.animatable_clock_view),
                        mStatusBarStateController,
                        mBroadcastDispatcher,
                        mBatteryController,
                        mKeyguardUpdateMonitor,
                        mBypassController);
        mClockViewController.init();

        mLargeClockViewController =
                new AnimatableClockController(
                        mView.findViewById(R.id.animatable_clock_view_large),
                        mStatusBarStateController,
                        mBroadcastDispatcher,
                        mBatteryController,
                        mKeyguardUpdateMonitor,
                        mBypassController);
        mLargeClockViewController.init();

        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mConfigurationController.addCallback(mConfigurationListener);

        if (mFeatureFlags.isSmartspaceEnabled() && mSmartspacePlugin.isPresent()) {
            BcSmartspaceDataPlugin smartspaceDataPlugin = mSmartspacePlugin.get();
            View ksa = mView.findViewById(R.id.keyguard_status_area);
            int ksaIndex = mView.indexOfChild(ksa);
            ksa.setVisibility(View.GONE);

            mSmartspaceView = smartspaceDataPlugin.getView(mView);
            mSmartspaceView.registerDataProvider(smartspaceDataPlugin);
            mSmartspaceView.setIntentStarter(new IntentStarter() {
                public void startIntent(View v, Intent i) {
                    mActivityStarter.startActivity(i, true /* dismissShade */);
                }

                public void startPendingIntent(PendingIntent pi) {
                    mActivityStarter.startPendingIntentDismissingKeyguard(pi);
                }
            });
            mSmartspaceView.setFalsingManager(mFalsingManager);
            updateWallpaperColor();
            View asView = (View) mSmartspaceView;

            // Place smartspace view below normal clock...
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT);
            lp.addRule(RelativeLayout.BELOW, R.id.lockscreen_clock_view);

            mView.addView(asView, ksaIndex, lp);
            int padding = getContext().getResources()
                    .getDimensionPixelSize(R.dimen.below_clock_padding_start);
            asView.setPadding(padding, 0, padding, 0);

            // ... but above the large clock
            lp = new RelativeLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            lp.addRule(RelativeLayout.BELOW, asView.getId());
            mLargeClockFrame.setLayoutParams(lp);

            View nic = mView.findViewById(
                    R.id.left_aligned_notification_icon_container);
            lp = (RelativeLayout.LayoutParams) nic.getLayoutParams();
            lp.addRule(RelativeLayout.BELOW, asView.getId());
            nic.setLayoutParams(lp);

            mSmartspaceSession = getContext().getSystemService(SmartspaceManager.class)
                    .createSmartspaceSession(
                            new SmartspaceConfig.Builder(getContext(), "lockscreen").build());
            mSmartspaceCallback = targets -> {
                targets.removeIf(this::filterSmartspaceTarget);
                smartspaceDataPlugin.onTargetsAvailable(targets);
            };
            mSmartspaceSession.addOnTargetsAvailableListener(mUiExecutor, mSmartspaceCallback);
            mSettingsObserver = new ContentObserver(mHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    reloadSmartspace();
                }
            };

            getContext().getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(
                            Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS),
                    true, mSettingsObserver, UserHandle.USER_ALL);
            reloadSmartspace();
        }

        float dozeAmount = mStatusBarStateController.getDozeAmount();
        mStatusBarStateListener.onDozeAmountChanged(dozeAmount, dozeAmount);
    }

    @VisibleForTesting
    boolean filterSmartspaceTarget(SmartspaceTarget t) {
        if (!t.isSensitive()) return false;

        if (t.getUserHandle().equals(mUserTracker.getUserHandle())) {
            return !mShowSensitiveContentForCurrentUser;
        }
        if (t.getUserHandle().equals(mManagedUserHandle)) {
            return !mShowSensitiveContentForManagedUser;
        }

        return false;
    }

    private void reloadSmartspace() {
        mManagedUserHandle = getWorkProfileUser();
        final String setting = Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS;

        mShowSensitiveContentForCurrentUser =
                mSecureSettings.getIntForUser(setting, 0, mUserTracker.getUserId()) == 1;
        if (mManagedUserHandle != null) {
            int id = mManagedUserHandle.getIdentifier();
            mShowSensitiveContentForManagedUser =
                    mSecureSettings.getIntForUser(setting, 0, id) == 1;
        }

        mSmartspaceSession.requestSmartspaceUpdate();
    }

    private UserHandle getWorkProfileUser() {
        for (UserInfo userInfo : mUserTracker.getUserProfiles()) {
            if (userInfo.isManagedProfile()) {
                return userInfo.getUserHandle();
            }
        }
        return null;
    }

    private void updateWallpaperColor() {
        if (mSmartspaceView != null) {
            int color = Utils.getColorAttrDefaultColor(getContext(), R.attr.wallpaperTextColor);
            mSmartspaceView.setPrimaryTextColor(color);
        }
    }

    @Override
    protected void onViewDetached() {
        if (CUSTOM_CLOCKS_ENABLED) {
            mClockManager.removeOnClockChangedListener(mClockChangedListener);
        }
        mColorExtractor.removeOnColorsChangedListener(mColorsListener);
        mView.setClockPlugin(null, mStatusBarStateController.getState());

        if (mSmartspaceSession != null) {
            mSmartspaceSession.removeOnTargetsAvailableListener(mSmartspaceCallback);
            mSmartspaceSession.close();
            mSmartspaceSession = null;
        }
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mConfigurationController.removeCallback(mConfigurationListener);

        if (mSettingsObserver != null) {
            getContext().getContentResolver().unregisterContentObserver(mSettingsObserver);
        }
    }

    /**
     * Apply dp changes on font/scale change
     */
    public void onDensityOrFontScaleChanged() {
        mView.onDensityOrFontScaleChanged();
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
        if (mClockViewController != null) {
            mClockViewController.refreshTime();
            mLargeClockViewController.refreshTime();
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

        PropertyAnimator.setProperty(mClockFrame, AnimatableProperty.TRANSLATION_X,
                x, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_X,
                scale, props, animate);
        PropertyAnimator.setProperty(mLargeClockFrame, AnimatableProperty.SCALE_Y,
                scale, props, animate);

        if (mSmartspaceView != null) {
            PropertyAnimator.setProperty((View) mSmartspaceView, AnimatableProperty.TRANSLATION_X,
                    x, props, animate);
        }

        mKeyguardSliceViewController.updatePosition(x, props, animate);
        mNotificationIconAreaController.updatePosition(x, props, animate);
    }

    void updateTimeZone(TimeZone timeZone) {
        mView.onTimeZoneChanged(timeZone);
        if (mClockViewController != null) {
            mClockViewController.onTimeZoneChanged(timeZone);
            mLargeClockViewController.onTimeZoneChanged(timeZone);
        }
    }

    void refreshFormat(String timeFormat) {
        if (mClockViewController != null) {
            mClockViewController.refreshFormat();
            mLargeClockViewController.refreshFormat();
        }
    }

    private void updateAodIcons() {
        NotificationIconContainer nic = (NotificationIconContainer)
                mView.findViewById(
                        com.android.systemui.R.id.left_aligned_notification_icon_container);

        // alt icon area is set in KeyguardClockSwitchController
        mNotificationIconAreaController.setupAodIcons(nic);
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

    @VisibleForTesting
    ConfigurationController.ConfigurationListener getConfigurationListener() {
        return mConfigurationListener;
    }

    @VisibleForTesting
    ContentObserver getSettingsObserver() {
        return mSettingsObserver;
    }
}
