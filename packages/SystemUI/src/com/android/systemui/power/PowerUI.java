/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.power;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IThermalEventListener;
import android.os.IThermalService;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Temperature;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Slog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.fuelgauge.Estimate;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.ConfigurationController;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Future;

import javax.inject.Inject;

@SysUISingleton
public class PowerUI implements
        CoreStartable,
        ConfigurationController.ConfigurationListener,
        CommandQueue.Callbacks {

    static final String TAG = "PowerUI";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long TEMPERATURE_INTERVAL = 30 * DateUtils.SECOND_IN_MILLIS;
    private static final long TEMPERATURE_LOGGING_INTERVAL = DateUtils.HOUR_IN_MILLIS;
    private static final int MAX_RECENT_TEMPS = 125; // TEMPERATURE_LOGGING_INTERVAL plus a buffer
    static final long THREE_HOURS_IN_MILLIS = DateUtils.HOUR_IN_MILLIS * 3;
    private static final int CHARGE_CYCLE_PERCENT_RESET = 30;
    public static final int NO_ESTIMATE_AVAILABLE = -1;
    private static final String BOOT_COUNT_KEY = "boot_count";
    private static final String PREFS = "powerui_prefs";

    private final Handler mHandler = new Handler();
    @VisibleForTesting
    final Receiver mReceiver = new Receiver();

    private final PowerManager mPowerManager;
    private final WarningsUI mWarnings;
    private final WakefulnessLifecycle mWakefulnessLifecycle;
    private final UserTracker mUserTracker;
    private InattentiveSleepWarningView mOverlayView;
    private final Configuration mLastConfiguration = new Configuration();
    private int mPlugType = 0;
    private int mInvalidCharger = 0;
    private final EnhancedEstimates mEnhancedEstimates;
    private Future mLastShowWarningTask;
    private boolean mEnableSkinTemperatureWarning;
    private boolean mEnableUsbTemperatureAlarm;
    private final HandlerThread mHandlerThread;

    private int mLowBatteryAlertCloseLevel;
    private final int[] mLowBatteryReminderLevels = new int[2];

    private long mScreenOffTime = -1;

    @VisibleForTesting boolean mLowWarningShownThisChargeCycle;
    @VisibleForTesting boolean mSevereWarningShownThisChargeCycle;
    @VisibleForTesting BatteryStateSnapshot mCurrentBatteryStateSnapshot;
    @VisibleForTesting BatteryStateSnapshot mLastBatteryStateSnapshot;
    @VisibleForTesting IThermalService mThermalService;

    @VisibleForTesting int mBatteryLevel = 100;
    @VisibleForTesting int mBatteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN;

    private boolean mInVrMode;

    private IThermalEventListener mSkinThermalEventListener;
    private IThermalEventListener mUsbThermalEventListener;
    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CommandQueue mCommandQueue;
    @Nullable
    private final IVrManager mVrManager;
    private final WakefulnessLifecycle.Observer mWakefulnessObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedWakingUp() {
                    mScreenOffTime = -1;
                }

                @Override
                public void onFinishedGoingToSleep() {
                    mScreenOffTime = SystemClock.elapsedRealtime();
                }
            };

    private final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    mWarnings.userSwitched();
                }
            };

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mInVrMode = enabled;
        }
    };

    @Inject
    public PowerUI(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            CommandQueue commandQueue,
            @Nullable IVrManager vrManager,
            WarningsUI warningsUI,
            EnhancedEstimates enhancedEstimates,
            WakefulnessLifecycle wakefulnessLifecycle,
            PowerManager powerManager,
            UserTracker userTracker) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mCommandQueue = commandQueue;
        mVrManager = vrManager;
        mWarnings = warningsUI;
        mEnhancedEstimates = enhancedEstimates;
        mPowerManager = powerManager;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mUserTracker = userTracker;
        mHandlerThread = new HandlerThread("PowerUI");
        mHandlerThread.start();
    }

    public void start() {
        mScreenOffTime = mPowerManager.isScreenOn() ? -1 : SystemClock.elapsedRealtime();
        mLastConfiguration.setTo(mContext.getResources().getConfiguration());

        ContentObserver obs = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                updateBatteryWarningLevels();
            }
        };
        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.Global.getUriFor(
                        Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL),
                false, obs, UserHandle.USER_ALL);
        updateBatteryWarningLevels();
        mReceiver.init();
        mUserTracker.addCallback(mUserChangedCallback,
                    new HandlerExecutor(mHandlerThread.getThreadHandler()));
        mWakefulnessLifecycle.addObserver(mWakefulnessObserver);

        // Check to see if we need to let the user know that the phone previously shut down due
        // to the temperature being too high.
        showWarnOnThermalShutdown();

        // Register an observer to configure mEnableSkinTemperatureWarning and perform the
        // registration of skin thermal event listener upon Settings change.
        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.SHOW_TEMPERATURE_WARNING),
                false /*notifyForDescendants*/,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        doSkinThermalEventListenerRegistration();
                    }
                });
        // Register an observer to configure mEnableUsbTemperatureAlarm and perform the
        // registration of usb thermal event listener upon Settings change.
        resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.SHOW_USB_TEMPERATURE_ALARM),
                false /*notifyForDescendants*/,
                new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        doUsbThermalEventListenerRegistration();
                    }
                });
        initThermalEventListeners();
        mCommandQueue.addCallback(this);

        if (mVrManager != null) {
            try {
                mVrManager.registerListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to register VR mode state listener: " + e);
            }
        }
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        final int mask = ActivityInfo.CONFIG_MCC | ActivityInfo.CONFIG_MNC;

        // Safe to modify mLastConfiguration here as it's only updated by the main thread (here).
        if ((mLastConfiguration.updateFrom(newConfig) & mask) != 0) {
            mHandler.post(this::initThermalEventListeners);
        }
    }

    void updateBatteryWarningLevels() {
        int critLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        int warnLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);

        if (warnLevel < critLevel) {
            warnLevel = critLevel;
        }

        mLowBatteryReminderLevels[0] = warnLevel;
        mLowBatteryReminderLevels[1] = critLevel;
        mLowBatteryAlertCloseLevel = mLowBatteryReminderLevels[0]
                + mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_lowBatteryCloseWarningBump);
    }

    /**
     * Buckets the battery level.
     *
     * The code in this function is a little weird because I couldn't comprehend
     * the bucket going up when the battery level was going down. --joeo
     *
     * 1 means that the battery is "ok"
     * 0 means that the battery is between "ok" and what we should warn about.
     * less than 0 means that the battery is low, -1 means the battery is reaching warning level,
     * -2 means the battery is reaching severe level.
     */
    private int findBatteryLevelBucket(int level) {
        if (level >= mLowBatteryAlertCloseLevel) {
            return 1;
        }
        if (level > mLowBatteryReminderLevels[0]) {
            return 0;
        }
        final int N = mLowBatteryReminderLevels.length;
        for (int i=N-1; i>=0; i--) {
            if (level <= mLowBatteryReminderLevels[i]) {
                return -1-i;
            }
        }
        throw new RuntimeException("not possible!");
    }

    @VisibleForTesting
    final class Receiver extends BroadcastReceiver {

        private boolean mHasReceivedBattery = false;

        public void init() {
            // Register for Intent broadcasts for...
            IntentFilter filter = new IntentFilter();
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            mBroadcastDispatcher.registerReceiverWithHandler(this, filter, mHandler);
            // Force get initial values. Relying on Sticky behavior until API for getting info.
            if (!mHasReceivedBattery) {
                // Get initial state
                Intent intent = mContext.registerReceiver(
                        null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                );
                if (intent != null) {
                    onReceive(mContext, intent);
                }
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) {
                ThreadUtils.postOnBackgroundThread(() -> {
                    if (mPowerManager.isPowerSaveMode()) {
                        mWarnings.dismissLowBatteryWarning();
                    }
                });
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                mHasReceivedBattery = true;
                final int oldBatteryLevel = mBatteryLevel;
                mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
                final int oldBatteryStatus = mBatteryStatus;
                mBatteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
                final int oldPlugType = mPlugType;
                mPlugType = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 1);
                final int oldInvalidCharger = mInvalidCharger;
                mInvalidCharger = intent.getIntExtra(BatteryManager.EXTRA_INVALID_CHARGER, 0);
                mLastBatteryStateSnapshot = mCurrentBatteryStateSnapshot;

                final boolean plugged = mPlugType != 0;
                final boolean oldPlugged = oldPlugType != 0;

                int oldBucket = findBatteryLevelBucket(oldBatteryLevel);
                int bucket = findBatteryLevelBucket(mBatteryLevel);

                if (DEBUG) {
                    Slog.d(TAG, "buckets   ....." + mLowBatteryAlertCloseLevel
                            + " .. " + mLowBatteryReminderLevels[0]
                            + " .. " + mLowBatteryReminderLevels[1]);
                    Slog.d(TAG, "level          " + oldBatteryLevel + " --> " + mBatteryLevel);
                    Slog.d(TAG, "status         " + oldBatteryStatus + " --> " + mBatteryStatus);
                    Slog.d(TAG, "plugType       " + oldPlugType + " --> " + mPlugType);
                    Slog.d(TAG, "invalidCharger " + oldInvalidCharger + " --> " + mInvalidCharger);
                    Slog.d(TAG, "bucket         " + oldBucket + " --> " + bucket);
                    Slog.d(TAG, "plugged        " + oldPlugged + " --> " + plugged);
                }

                mWarnings.update(mBatteryLevel, bucket, mScreenOffTime);
                if (oldInvalidCharger == 0 && mInvalidCharger != 0) {
                    Slog.d(TAG, "showing invalid charger warning");
                    mWarnings.showInvalidChargerWarning();
                    return;
                } else if (oldInvalidCharger != 0 && mInvalidCharger == 0) {
                    mWarnings.dismissInvalidChargerWarning();
                } else if (mWarnings.isInvalidChargerWarningShowing()) {
                    // if invalid charger is showing, don't show low battery
                    if (DEBUG) {
                        Slog.d(TAG, "Bad Charger");
                    }
                    return;
                }

                // Show the correct version of low battery warning if needed
                if (mLastShowWarningTask != null) {
                    mLastShowWarningTask.cancel(true);
                    if (DEBUG) {
                        Slog.d(TAG, "cancelled task");
                    }
                }
                mLastShowWarningTask = ThreadUtils.postOnBackgroundThread(() -> {
                    maybeShowBatteryWarningV2(
                            plugged, bucket);
                });

            } else {
                Slog.w(TAG, "unknown intent: " + intent);
            }
        }
    }

    protected void maybeShowBatteryWarningV2(boolean plugged, int bucket) {
        final boolean hybridEnabled = mEnhancedEstimates.isHybridNotificationEnabled();
        final boolean isPowerSaverMode = mPowerManager.isPowerSaveMode();

        // Stick current battery state into an immutable container to determine if we should show
        // a warning.
        if (DEBUG) {
            Slog.d(TAG, "evaluating which notification to show");
        }
        if (hybridEnabled) {
            if (DEBUG) {
                Slog.d(TAG, "using hybrid");
            }
            Estimate estimate = refreshEstimateIfNeeded();
            mCurrentBatteryStateSnapshot = new BatteryStateSnapshot(mBatteryLevel, isPowerSaverMode,
                    plugged, bucket, mBatteryStatus, mLowBatteryReminderLevels[1],
                    mLowBatteryReminderLevels[0], estimate.getEstimateMillis(),
                    estimate.getAverageDischargeTime(),
                    mEnhancedEstimates.getSevereWarningThreshold(),
                    mEnhancedEstimates.getLowWarningThreshold(), estimate.isBasedOnUsage(),
                    mEnhancedEstimates.getLowWarningEnabled());
        } else {
            if (DEBUG) {
                Slog.d(TAG, "using standard");
            }
            mCurrentBatteryStateSnapshot = new BatteryStateSnapshot(mBatteryLevel, isPowerSaverMode,
                    plugged, bucket, mBatteryStatus, mLowBatteryReminderLevels[1],
                    mLowBatteryReminderLevels[0]);
        }

        mWarnings.updateSnapshot(mCurrentBatteryStateSnapshot);
        maybeShowHybridWarning(mCurrentBatteryStateSnapshot, mLastBatteryStateSnapshot);
    }

    // updates the time estimate if we don't have one or battery level has changed.
    @VisibleForTesting
    Estimate refreshEstimateIfNeeded() {
        if (mLastBatteryStateSnapshot == null
                || mLastBatteryStateSnapshot.getTimeRemainingMillis() == NO_ESTIMATE_AVAILABLE
                || mBatteryLevel != mLastBatteryStateSnapshot.getBatteryLevel()) {
            final Estimate estimate = mEnhancedEstimates.getEstimate();
            if (DEBUG) {
                Slog.d(TAG, "updated estimate: " + estimate.getEstimateMillis());
            }
            return estimate;
        }
        return new Estimate(mLastBatteryStateSnapshot.getTimeRemainingMillis(),
                mLastBatteryStateSnapshot.isBasedOnUsage(),
                mLastBatteryStateSnapshot.getAverageTimeToDischargeMillis());
    }

    @VisibleForTesting
    void maybeShowHybridWarning(BatteryStateSnapshot currentSnapshot,
            BatteryStateSnapshot lastSnapshot) {
        // if we are now over 30% battery, we can trigger hybrid notification again
        if (currentSnapshot.getBatteryLevel() >= CHARGE_CYCLE_PERCENT_RESET) {
            mLowWarningShownThisChargeCycle = false;
            mSevereWarningShownThisChargeCycle = false;
            if (DEBUG) {
                Slog.d(TAG, "Charge cycle reset! Can show warnings again");
            }
        }

        final boolean playSound = currentSnapshot.getBucket() != lastSnapshot.getBucket()
                || lastSnapshot.getPlugged();

        if (shouldShowHybridWarning(currentSnapshot)) {
            mWarnings.showLowBatteryWarning(playSound);
            // mark if we've already shown a warning this cycle. This will prevent the notification
            // trigger from spamming users by only showing low/critical warnings once per cycle
            if (currentSnapshot.getBatteryLevel() <= currentSnapshot.getSevereLevelThreshold()) {
                mSevereWarningShownThisChargeCycle = true;
                mLowWarningShownThisChargeCycle = true;
                if (DEBUG) {
                    Slog.d(TAG, "Severe warning marked as shown this cycle");
                }
            } else {
                Slog.d(TAG, "Low warning marked as shown this cycle");
                mLowWarningShownThisChargeCycle = true;
            }
        } else if (shouldDismissHybridWarning(currentSnapshot)) {
            if (DEBUG) {
                Slog.d(TAG, "Dismissing warning");
            }
            mWarnings.dismissLowBatteryWarning();
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Updating warning");
            }
            mWarnings.updateLowBatteryWarning();
        }
    }

    @VisibleForTesting
    boolean shouldShowHybridWarning(BatteryStateSnapshot snapshot) {
        if (snapshot.getPlugged()
                || snapshot.getBatteryStatus() == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            Slog.d(TAG, "can't show warning due to - plugged: " + snapshot.getPlugged()
                    + " status unknown: "
                    + (snapshot.getBatteryStatus() == BatteryManager.BATTERY_STATUS_UNKNOWN));
            return false;
        }

        // Only show the low warning if enabled once per charge cycle & no battery saver
        final boolean canShowWarning = !mLowWarningShownThisChargeCycle && !snapshot.isPowerSaver()
                && snapshot.getBatteryLevel() <= snapshot.getLowLevelThreshold();

        // Only show the severe warning once per charge cycle
        final boolean canShowSevereWarning = !mSevereWarningShownThisChargeCycle
                && snapshot.getBatteryLevel() <= snapshot.getSevereLevelThreshold();

        final boolean canShow = canShowWarning || canShowSevereWarning;

        if (DEBUG) {
            Slog.d(TAG, "Enhanced trigger is: " + canShow + "\nwith battery snapshot:"
                    + " mLowWarningShownThisChargeCycle: " + mLowWarningShownThisChargeCycle
                    + " mSevereWarningShownThisChargeCycle: " + mSevereWarningShownThisChargeCycle
                    + "\n" + snapshot.toString());
        }
        return canShow;
    }

    @VisibleForTesting
    boolean shouldDismissHybridWarning(BatteryStateSnapshot snapshot) {
        return snapshot.getPlugged()
                || snapshot.getBatteryLevel()
                > snapshot.getLowLevelThreshold();
    }

    protected void maybeShowBatteryWarning(
            BatteryStateSnapshot currentSnapshot,
            BatteryStateSnapshot lastSnapshot) {
        final boolean playSound = currentSnapshot.getBucket() != lastSnapshot.getBucket()
                || lastSnapshot.getPlugged();

        if (shouldShowLowBatteryWarning(currentSnapshot, lastSnapshot)) {
            mWarnings.showLowBatteryWarning(playSound);
        } else if (shouldDismissLowBatteryWarning(currentSnapshot, lastSnapshot)) {
            mWarnings.dismissLowBatteryWarning();
        } else {
            mWarnings.updateLowBatteryWarning();
        }
    }

    @VisibleForTesting
    boolean shouldShowLowBatteryWarning(
            BatteryStateSnapshot currentSnapshot,
            BatteryStateSnapshot lastSnapshot) {
        return !currentSnapshot.getPlugged()
                && !currentSnapshot.isPowerSaver()
                && (((currentSnapshot.getBucket() < lastSnapshot.getBucket()
                        || lastSnapshot.getPlugged())
                && currentSnapshot.getBucket() < 0))
                && currentSnapshot.getBatteryStatus() != BatteryManager.BATTERY_STATUS_UNKNOWN;
    }

    @VisibleForTesting
    boolean shouldDismissLowBatteryWarning(
            BatteryStateSnapshot currentSnapshot,
            BatteryStateSnapshot lastSnapshot) {
        return currentSnapshot.isPowerSaver()
                || currentSnapshot.getPlugged()
                || (currentSnapshot.getBucket() > lastSnapshot.getBucket()
                        && currentSnapshot.getBucket() > 0);
    }

    private void initThermalEventListeners() {
        doSkinThermalEventListenerRegistration();
        doUsbThermalEventListenerRegistration();
    }

    @VisibleForTesting
    synchronized void doSkinThermalEventListenerRegistration() {
        final boolean oldEnableSkinTemperatureWarning = mEnableSkinTemperatureWarning;
        boolean ret = false;

        mEnableSkinTemperatureWarning = Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.SHOW_TEMPERATURE_WARNING,
            mContext.getResources().getInteger(R.integer.config_showTemperatureWarning)) != 0;

        if (mEnableSkinTemperatureWarning != oldEnableSkinTemperatureWarning) {
            try {
                if (mSkinThermalEventListener == null) {
                    mSkinThermalEventListener = new SkinThermalEventListener();
                }
                if (mThermalService == null) {
                    mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
                }
                if (mEnableSkinTemperatureWarning) {
                    ret = mThermalService.registerThermalEventListenerWithType(
                            mSkinThermalEventListener, Temperature.TYPE_SKIN);
                } else {
                    ret = mThermalService.unregisterThermalEventListener(mSkinThermalEventListener);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while (un)registering skin thermal event listener.", e);
            }

            if (!ret) {
                mEnableSkinTemperatureWarning = !mEnableSkinTemperatureWarning;
                Slog.e(TAG, "Failed to register or unregister skin thermal event listener.");
            }
        }
    }

    @VisibleForTesting
    synchronized void doUsbThermalEventListenerRegistration() {
        final boolean oldEnableUsbTemperatureAlarm = mEnableUsbTemperatureAlarm;
        boolean ret = false;

        mEnableUsbTemperatureAlarm = Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.SHOW_USB_TEMPERATURE_ALARM,
            mContext.getResources().getInteger(R.integer.config_showUsbPortAlarm)) != 0;

        if (mEnableUsbTemperatureAlarm != oldEnableUsbTemperatureAlarm) {
            try {
                if (mUsbThermalEventListener == null) {
                    mUsbThermalEventListener = new UsbThermalEventListener();
                }
                if (mThermalService == null) {
                    mThermalService = IThermalService.Stub.asInterface(
                        ServiceManager.getService(Context.THERMAL_SERVICE));
                }
                if (mEnableUsbTemperatureAlarm) {
                    ret = mThermalService.registerThermalEventListenerWithType(
                            mUsbThermalEventListener, Temperature.TYPE_USB_PORT);
                } else {
                    ret = mThermalService.unregisterThermalEventListener(mUsbThermalEventListener);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while (un)registering usb thermal event listener.", e);
            }

            if (!ret) {
                mEnableUsbTemperatureAlarm = !mEnableUsbTemperatureAlarm;
                Slog.e(TAG, "Failed to register or unregister usb thermal event listener.");
            }
        }
    }

    private void showWarnOnThermalShutdown() {
        int bootCount = -1;
        int lastReboot = mContext.getSharedPreferences(PREFS, 0).getInt(BOOT_COUNT_KEY, -1);
        try {
            bootCount = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException e) {
            Slog.e(TAG, "Failed to read system boot count from Settings.Global.BOOT_COUNT");
        }
        // Only show the thermal shutdown warning when there is a thermal reboot.
        if (bootCount > lastReboot) {
            mContext.getSharedPreferences(PREFS, 0).edit().putInt(BOOT_COUNT_KEY,
                    bootCount).apply();
            if (mPowerManager.getLastShutdownReason()
                    == PowerManager.SHUTDOWN_REASON_THERMAL_SHUTDOWN) {
                mWarnings.showThermalShutdownWarning();
            }
        }
    }

    @Override
    public void showInattentiveSleepWarning() {
        if (mOverlayView == null) {
            mOverlayView = new InattentiveSleepWarningView(mContext);
        }

        mOverlayView.show();
    }

    @Override
    public void dismissInattentiveSleepWarning(boolean animated) {
        if (mOverlayView != null) {
            mOverlayView.dismiss(animated);
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.print("mLowBatteryAlertCloseLevel=");
        pw.println(mLowBatteryAlertCloseLevel);
        pw.print("mLowBatteryReminderLevels=");
        pw.println(Arrays.toString(mLowBatteryReminderLevels));
        pw.print("mBatteryLevel=");
        pw.println(Integer.toString(mBatteryLevel));
        pw.print("mBatteryStatus=");
        pw.println(Integer.toString(mBatteryStatus));
        pw.print("mPlugType=");
        pw.println(Integer.toString(mPlugType));
        pw.print("mInvalidCharger=");
        pw.println(Integer.toString(mInvalidCharger));
        pw.print("mScreenOffTime=");
        pw.print(mScreenOffTime);
        if (mScreenOffTime >= 0) {
            pw.print(" (");
            pw.print(SystemClock.elapsedRealtime() - mScreenOffTime);
            pw.print(" ago)");
        }
        pw.println();
        pw.print("soundTimeout=");
        pw.println(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.LOW_BATTERY_SOUND_TIMEOUT, 0));
        pw.print("bucket: ");
        pw.println(Integer.toString(findBatteryLevelBucket(mBatteryLevel)));
        pw.print("mEnableSkinTemperatureWarning=");
        pw.println(mEnableSkinTemperatureWarning);
        pw.print("mEnableUsbTemperatureAlarm=");
        pw.println(mEnableUsbTemperatureAlarm);
        mWarnings.dump(pw);
    }

    /**
     * The interface to allow PowerUI to communicate with whatever implementation of WarningsUI
     * is being used by the system.
     */
    public interface WarningsUI {

        /**
         * Updates battery and screen info for determining whether to trigger battery warnings or
         * not.
         * @param batteryLevel The current battery level
         * @param bucket The current battery bucket
         * @param screenOffTime How long the screen has been off in millis
         */
        void update(int batteryLevel, int bucket, long screenOffTime);

        void dismissLowBatteryWarning();

        void showLowBatteryWarning(boolean playSound);

        void dismissInvalidChargerWarning();

        void showInvalidChargerWarning();

        void updateLowBatteryWarning();

        boolean isInvalidChargerWarningShowing();

        void dismissHighTemperatureWarning();

        void showHighTemperatureWarning();

        /**
         * Display USB port overheat alarm
         */
        void showUsbHighTemperatureAlarm();

        void showThermalShutdownWarning();

        void dump(PrintWriter pw);

        void userSwitched();

        /**
         * Updates the snapshot of battery state used for evaluating battery warnings
         * @param snapshot object containing relevant values for making battery warning decisions.
         */
        void updateSnapshot(BatteryStateSnapshot snapshot);
    }

    // Skin thermal event received from thermal service manager subsystem
    @VisibleForTesting
    final class SkinThermalEventListener extends IThermalEventListener.Stub {
        @Override public void notifyThrottling(Temperature temp) {
            int status = temp.getStatus();

            if (status >= Temperature.THROTTLING_EMERGENCY) {
                if (!mInVrMode) {
                    mWarnings.showHighTemperatureWarning();
                    Slog.d(TAG, "SkinThermalEventListener: notifyThrottling was called "
                            + ", current skin status = " + status
                            + ", temperature = " + temp.getValue());
                }
            } else {
                mWarnings.dismissHighTemperatureWarning();
            }
        }
    }

    // Usb thermal event received from thermal service manager subsystem
    @VisibleForTesting
    final class UsbThermalEventListener extends IThermalEventListener.Stub {
        @Override public void notifyThrottling(Temperature temp) {
            int status = temp.getStatus();

            if (status >= Temperature.THROTTLING_EMERGENCY) {
                mWarnings.showUsbHighTemperatureAlarm();
                Slog.d(TAG, "UsbThermalEventListener: notifyThrottling was called "
                        + ", current usb port status = " + status
                        + ", temperature = " + temp.getValue());
            }
        }
    }
}
