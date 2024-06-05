/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import static android.os.Flags.batteryServiceSupportCurrentAdbCommand;
import static android.os.Flags.stateOfHealthPublic;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import static com.android.server.health.Utils.copyV1Battery;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.hardware.health.HealthInfo;
import android.hardware.health.V2_1.BatteryCapacityLevel;
import android.metrics.LogMaker;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.BatteryProperty;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBatteryPropertiesRegistrar;
import android.os.IBinder;
import android.os.OsProtoEnums;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.battery.BatteryServiceDumpProto;
import android.sysprop.PowerProperties;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.DumpUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.health.HealthServiceWrapper;
import com.android.server.lights.LightsManager;
import com.android.server.lights.LogicalLight;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * <p>BatteryService monitors the charging status, and charge level of the device
 * battery.  When these values change this service broadcasts the new values
 * to all {@link android.content.BroadcastReceiver IntentReceivers} that are
 * watching the {@link android.content.Intent#ACTION_BATTERY_CHANGED
 * BATTERY_CHANGED} action.</p>
 * <p>The new values are stored in the Intent data and can be retrieved by
 * calling {@link android.content.Intent#getExtra Intent.getExtra} with the
 * following keys:</p>
 * <p>&quot;scale&quot; - int, the maximum value for the charge level</p>
 * <p>&quot;level&quot; - int, charge level, from 0 through &quot;scale&quot; inclusive</p>
 * <p>&quot;status&quot; - String, the current charging status.<br />
 * <p>&quot;health&quot; - String, the current battery health.<br />
 * <p>&quot;present&quot; - boolean, true if the battery is present<br />
 * <p>&quot;icon-small&quot; - int, suggested small icon to use for this state</p>
 * <p>&quot;plugged&quot; - int, 0 if the device is not plugged in; 1 if plugged
 * into an AC power adapter; 2 if plugged in via USB.</p>
 * <p>&quot;voltage&quot; - int, current battery voltage in millivolts</p>
 * <p>&quot;temperature&quot; - int, current battery temperature in tenths of
 * a degree Centigrade</p>
 * <p>&quot;technology&quot; - String, the type of battery installed, e.g. "Li-ion"</p>
 *
 * <p>
 * The battery service may be called by the power manager while holding its locks so
 * we take care to post all outcalls into the activity manager to a handler.
 *
 * FIXME: Ideally the power manager would perform all of its calls into the battery
 * service asynchronously itself.
 * </p>
 */
public final class BatteryService extends SystemService {
    private static final String TAG = BatteryService.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int BATTERY_SCALE = 100;    // battery capacity is a percentage

    private static final long HEALTH_HAL_WAIT_MS = 1000;
    private static final long BATTERY_LEVEL_CHANGE_THROTTLE_MS = 60_000;
    private static final int MAX_BATTERY_LEVELS_QUEUE_SIZE = 100;

    // Used locally for determining when to make a last ditch effort to log
    // discharge stats before the device dies.
    private int mCriticalBatteryLevel;

    // TODO: Current args don't work since "--unplugged" flag was purposefully removed.
    private static final String[] DUMPSYS_ARGS = new String[] { "--checkin", "--unplugged" };

    private static final String DUMPSYS_DATA_PATH = "/data/system/";

    // This should probably be exposed in the API, though it's not critical
    private static final int BATTERY_PLUGGED_NONE = OsProtoEnums.BATTERY_PLUGGED_NONE; // = 0

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    BinderService mBinderService;
    private final Handler mHandler;

    private final Object mLock = new Object();

    private HealthInfo mHealthInfo;
    private final HealthInfo mLastHealthInfo = new HealthInfo();
    private boolean mBatteryLevelCritical;
    private int mLastBatteryStatus;
    private int mLastBatteryHealth;
    private boolean mLastBatteryPresent;
    private int mLastBatteryLevel;
    private int mLastBatteryVoltage;
    private int mLastBatteryTemperature;
    private boolean mLastBatteryLevelCritical;
    private int mLastMaxChargingCurrent;
    private int mLastMaxChargingVoltage;
    private int mLastChargeCounter;
    private int mLastBatteryCycleCount;
    private int mLastCharingState;
    /**
     * The last seen charging policy. This requires the
     * {@link android.Manifest.permission#BATTERY_STATS} permission and should therefore not be
     * included in the ACTION_BATTERY_CHANGED intent extras.
     */
    private int mLastChargingPolicy;

    private int mSequence = 1;

    private int mInvalidCharger;
    private int mLastInvalidCharger;

    private int mLowBatteryWarningLevel;
    private int mLastLowBatteryWarningLevel;
    private int mLowBatteryCloseWarningLevel;
    private int mBatteryNearlyFullLevel;
    private int mShutdownBatteryTemperature;
    private boolean mShutdownIfNoPower;

    private static String sSystemUiPackage;

    private int mPlugType;
    private int mLastPlugType = -1; // Extra state so we can detect first run

    private boolean mBatteryLevelLow;

    private long mDischargeStartTime;
    private int mDischargeStartLevel;

    private long mChargeStartTime;
    private int mChargeStartLevel;

    private boolean mUpdatesStopped;
    private boolean mBatteryInputSuspended;

    private Led mLed;

    private boolean mSentLowBatteryBroadcast = false;

    private ActivityManagerInternal mActivityManagerInternal;

    private HealthServiceWrapper mHealthServiceWrapper;
    private BatteryPropertiesRegistrar mBatteryPropertiesRegistrar;
    private ArrayDeque<Bundle> mBatteryLevelsEventQueue;
    private long mLastBatteryLevelChangedSentMs;

    private final CopyOnWriteArraySet<BatteryManagerInternal.ChargingPolicyChangeListener>
            mChargingPolicyChangeListeners = new CopyOnWriteArraySet<>();

    private Bundle mBatteryChangedOptions = BroadcastOptions.makeBasic()
            .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
            .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
            .toBundle();
    /** Used for both connected/disconnected, so match using key */
    private Bundle mPowerOptions = BroadcastOptions.makeBasic()
            .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
            .setDeliveryGroupMatchingKey("android", Intent.ACTION_POWER_CONNECTED)
            .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
            .toBundle();
    /** Used for both low/okay, so match using key */
    private Bundle mBatteryOptions = BroadcastOptions.makeBasic()
            .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
            .setDeliveryGroupMatchingKey("android", Intent.ACTION_BATTERY_OKAY)
            .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
            .toBundle();

    private MetricsLogger mMetricsLogger;

    public BatteryService(Context context) {
        super(context);

        mContext = context;
        mHandler = new Handler(true /*async*/);
        mLed = new Led(context, getLocalService(LightsManager.class));
        mBatteryStats = BatteryStatsService.getService();
        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);

        mCriticalBatteryLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        mLowBatteryWarningLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryCloseWarningLevel = mLowBatteryWarningLevel + mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryCloseWarningBump);
        mShutdownBatteryTemperature = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_shutdownBatteryTemperature);
        mShutdownIfNoPower = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_shutdownIfNoPower);
        sSystemUiPackage = mContext.getResources().getString(
                com.android.internal.R.string.config_systemUi);

        mBatteryLevelsEventQueue = new ArrayDeque<>();
        mMetricsLogger = new MetricsLogger();

        // watch for invalid charger messages if the invalid_charger switch exists
        if (new File("/sys/devices/virtual/switch/invalid_charger/state").exists()) {
            UEventObserver invalidChargerObserver = new UEventObserver() {
                @Override
                public void onUEvent(UEvent event) {
                    final int invalidCharger = "1".equals(event.get("SWITCH_STATE")) ? 1 : 0;
                    synchronized (mLock) {
                        if (mInvalidCharger != invalidCharger) {
                            mInvalidCharger = invalidCharger;
                        }
                    }
                }
            };
            invalidChargerObserver.startObserving(
                    "DEVPATH=/devices/virtual/switch/invalid_charger");
        }

        mBatteryInputSuspended = PowerProperties.battery_input_suspended().orElse(false);
    }

    @Override
    public void onStart() {
        registerHealthCallback();

        mBinderService = new BinderService();
        publishBinderService("battery", mBinderService);
        mBatteryPropertiesRegistrar = new BatteryPropertiesRegistrar();
        publishBinderService("batteryproperties", mBatteryPropertiesRegistrar);
        publishLocalService(BatteryManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            // check our power situation now that it is safe to display the shutdown dialog.
            synchronized (mLock) {
                ContentObserver obs = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        synchronized (mLock) {
                            updateBatteryWarningLevelLocked();
                        }
                    }
                };
                final ContentResolver resolver = mContext.getContentResolver();
                resolver.registerContentObserver(Settings.Global.getUriFor(
                        Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL),
                        false, obs, UserHandle.USER_ALL);
                updateBatteryWarningLevelLocked();
            }
        }
    }

    private void registerHealthCallback() {
        traceBegin("HealthInitWrapper");
        // IHealth is lazily retrieved.
        try {
            mHealthServiceWrapper = HealthServiceWrapper.create(this::update);
        } catch (RemoteException ex) {
            Slog.e(TAG, "health: cannot register callback. (RemoteException)");
            throw ex.rethrowFromSystemServer();
        } catch (NoSuchElementException ex) {
            Slog.e(TAG, "health: cannot register callback. (no supported health HAL service)");
            throw ex;
        } finally {
            traceEnd();
        }

        traceBegin("HealthInitWaitUpdate");
        // init register for new service notifications, and IServiceManager should return the
        // existing service in a near future. Wait for this.update() to instantiate
        // the initial mHealthInfo.
        long beforeWait = SystemClock.uptimeMillis();
        synchronized (mLock) {
            while (mHealthInfo == null) {
                Slog.i(TAG, "health: Waited " + (SystemClock.uptimeMillis() - beforeWait) +
                        "ms for callbacks. Waiting another " + HEALTH_HAL_WAIT_MS + " ms...");
                try {
                    mLock.wait(HEALTH_HAL_WAIT_MS);
                } catch (InterruptedException ex) {
                    Slog.i(TAG, "health: InterruptedException when waiting for update. "
                        + " Continuing...");
                }
            }
        }

        Slog.i(TAG, "health: Waited " + (SystemClock.uptimeMillis() - beforeWait)
                + "ms and received the update.");
        traceEnd();
    }

    private void updateBatteryWarningLevelLocked() {
        final ContentResolver resolver = mContext.getContentResolver();
        int defWarnLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLastLowBatteryWarningLevel = mLowBatteryWarningLevel;
        mLowBatteryWarningLevel = Settings.Global.getInt(resolver,
                Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, defWarnLevel);
        if (mLowBatteryWarningLevel == 0) {
            mLowBatteryWarningLevel = defWarnLevel;
        }
        if (mLowBatteryWarningLevel < mCriticalBatteryLevel) {
            mLowBatteryWarningLevel = mCriticalBatteryLevel;
        }
        mLowBatteryCloseWarningLevel = mLowBatteryWarningLevel + mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryCloseWarningBump);
        processValuesLocked(true);
    }

    private boolean isPoweredLocked(int plugTypeSet) {
        // assume we are powered if battery state is unknown so
        // the "stay on while plugged in" option will work.
        if (mHealthInfo.batteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_AC) != 0
                && mHealthInfo.chargerAcOnline) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_USB) != 0
                && mHealthInfo.chargerUsbOnline) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0
                && mHealthInfo.chargerWirelessOnline) {
            return true;
        }
        if ((plugTypeSet & BatteryManager.BATTERY_PLUGGED_DOCK) != 0
                && mHealthInfo.chargerDockOnline) {
            return true;
        }
        return false;
    }

    private boolean shouldSendBatteryLowLocked() {
        final boolean plugged = mPlugType != BATTERY_PLUGGED_NONE;
        final boolean oldPlugged = mLastPlugType != BATTERY_PLUGGED_NONE;

        /* The ACTION_BATTERY_LOW broadcast is sent in these situations:
         * - is just un-plugged (previously was plugged) and battery level is
         *   less than or equal to WARNING, or
         * - is not plugged and battery level falls to WARNING boundary
         *   (becomes <= mLowBatteryWarningLevel).
         */
        return !plugged
                && mHealthInfo.batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN
                && mHealthInfo.batteryLevel <= mLowBatteryWarningLevel
                && (oldPlugged || mLastBatteryLevel > mLowBatteryWarningLevel
                    || mHealthInfo.batteryLevel > mLastLowBatteryWarningLevel);
    }

    private boolean shouldShutdownLocked() {
        if (mHealthInfo.batteryCapacityLevel != BatteryCapacityLevel.UNSUPPORTED) {
            return (mHealthInfo.batteryCapacityLevel == BatteryCapacityLevel.CRITICAL);
        }
        if (!mShutdownIfNoPower) {
            return false;
        }
        if (mHealthInfo.batteryLevel > 0) {
            return false;
        }

        // Battery-less devices should not shutdown.
        if (!mHealthInfo.batteryPresent) {
            return false;
        }

        // If battery state is not CHARGING, shutdown.
        // - If battery present and state == unknown, this is an unexpected error state.
        // - If level <= 0 and state == full, this is also an unexpected state
        // - All other states (NOT_CHARGING, DISCHARGING) means it is not charging.
        return mHealthInfo.batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING;
    }

    private void shutdownIfNoPowerLocked() {
        // shut down gracefully if our battery is critically low and we are not powered.
        // wait until the system has booted before attempting to display the shutdown dialog.
        if (shouldShutdownLocked()) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mActivityManagerInternal.isSystemReady()) {
                        Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
                        intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
                        intent.putExtra(Intent.EXTRA_REASON,
                                PowerManager.SHUTDOWN_LOW_BATTERY);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    }
                }
            });
        }
    }

    private void shutdownIfOverTempLocked() {
        // shut down gracefully if temperature is too high (> 68.0C by default)
        // wait until the system has booted before attempting to display the
        // shutdown dialog.
        if (mHealthInfo.batteryTemperatureTenthsCelsius > mShutdownBatteryTemperature) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mActivityManagerInternal.isSystemReady()) {
                        Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
                        intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
                        intent.putExtra(Intent.EXTRA_REASON,
                                PowerManager.SHUTDOWN_BATTERY_THERMAL_STATE);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    }
                }
            });
        }
    }

    private void update(android.hardware.health.HealthInfo info) {
        traceBegin("HealthInfoUpdate");

        Trace.traceCounter(
                Trace.TRACE_TAG_POWER, "BatteryChargeCounter", info.batteryChargeCounterUah);
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "BatteryCurrent", info.batteryCurrentMicroamps);
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "PlugType", plugType(info));
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "BatteryStatus", info.batteryStatus);

        synchronized (mLock) {
            if (!mUpdatesStopped) {
                mHealthInfo = info;
                // Process the new values.
                processValuesLocked(false);
                mLock.notifyAll(); // for any waiters on new info
            } else {
                copyV1Battery(mLastHealthInfo, info);
            }
        }
        traceEnd();
    }

    private static int plugType(HealthInfo healthInfo) {
        if (healthInfo.chargerAcOnline) {
            return BatteryManager.BATTERY_PLUGGED_AC;
        } else if (healthInfo.chargerUsbOnline) {
            return BatteryManager.BATTERY_PLUGGED_USB;
        } else if (healthInfo.chargerWirelessOnline) {
            return BatteryManager.BATTERY_PLUGGED_WIRELESS;
        } else if (healthInfo.chargerDockOnline) {
            return BatteryManager.BATTERY_PLUGGED_DOCK;
        } else {
            return BATTERY_PLUGGED_NONE;
        }
    }

    private void processValuesLocked(boolean force) {
        boolean logOutlier = false;
        long dischargeDuration = 0;

        mBatteryLevelCritical =
            mHealthInfo.batteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN
            && mHealthInfo.batteryLevel <= mCriticalBatteryLevel;
        mPlugType = plugType(mHealthInfo);

        if (DEBUG) {
            Slog.d(TAG, "Processing new values: "
                    + "info=" + mHealthInfo
                    + ", mBatteryLevelCritical=" + mBatteryLevelCritical
                    + ", mPlugType=" + mPlugType);
        }

        // Let the battery stats keep track of the current level.
        try {
            mBatteryStats.setBatteryState(
                    mHealthInfo.batteryStatus,
                    mHealthInfo.batteryHealth,
                    mPlugType,
                    mHealthInfo.batteryLevel,
                    mHealthInfo.batteryTemperatureTenthsCelsius,
                    mHealthInfo.batteryVoltageMillivolts,
                    mHealthInfo.batteryChargeCounterUah,
                    mHealthInfo.batteryFullChargeUah,
                    mHealthInfo.batteryChargeTimeToFullNowSeconds);
        } catch (RemoteException e) {
            // Should never happen.
        }

        shutdownIfNoPowerLocked();
        shutdownIfOverTempLocked();

        if (force || mHealthInfo.chargingPolicy != mLastChargingPolicy) {
            mLastChargingPolicy = mHealthInfo.chargingPolicy;
            mHandler.post(this::notifyChargingPolicyChanged);
        }

        if (force
                || (mHealthInfo.batteryStatus != mLastBatteryStatus
                        || mHealthInfo.batteryHealth != mLastBatteryHealth
                        || mHealthInfo.batteryPresent != mLastBatteryPresent
                        || mHealthInfo.batteryLevel != mLastBatteryLevel
                        || mPlugType != mLastPlugType
                        || mHealthInfo.batteryVoltageMillivolts != mLastBatteryVoltage
                        || mHealthInfo.batteryTemperatureTenthsCelsius != mLastBatteryTemperature
                        || mHealthInfo.maxChargingCurrentMicroamps != mLastMaxChargingCurrent
                        || mHealthInfo.maxChargingVoltageMicrovolts != mLastMaxChargingVoltage
                        || mHealthInfo.batteryChargeCounterUah != mLastChargeCounter
                        || mInvalidCharger != mLastInvalidCharger
                        || mHealthInfo.batteryCycleCount != mLastBatteryCycleCount
                        || mHealthInfo.chargingState != mLastCharingState)) {

            if (mPlugType != mLastPlugType) {
                if (mLastPlugType == BATTERY_PLUGGED_NONE) {
                    // discharging -> charging
                    mChargeStartLevel = mHealthInfo.batteryLevel;
                    mChargeStartTime = SystemClock.elapsedRealtime();

                    final LogMaker builder = new LogMaker(MetricsEvent.ACTION_CHARGE);
                    builder.setType(MetricsEvent.TYPE_ACTION);
                    builder.addTaggedData(MetricsEvent.FIELD_PLUG_TYPE, mPlugType);
                    builder.addTaggedData(MetricsEvent.FIELD_BATTERY_LEVEL_START,
                            mHealthInfo.batteryLevel);
                    mMetricsLogger.write(builder);

                    // There's no value in this data unless we've discharged at least once and the
                    // battery level has changed; so don't log until it does.
                    if (mDischargeStartTime != 0 && mDischargeStartLevel != mHealthInfo.batteryLevel) {
                        dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                        logOutlier = true;
                        EventLog.writeEvent(EventLogTags.BATTERY_DISCHARGE, dischargeDuration,
                                mDischargeStartLevel, mHealthInfo.batteryLevel);
                        // make sure we see a discharge event before logging again
                        mDischargeStartTime = 0;
                    }
                } else if (mPlugType == BATTERY_PLUGGED_NONE) {
                    // charging -> discharging or we just powered up
                    mDischargeStartTime = SystemClock.elapsedRealtime();
                    mDischargeStartLevel = mHealthInfo.batteryLevel;

                    long chargeDuration = SystemClock.elapsedRealtime() - mChargeStartTime;
                    if (mChargeStartTime != 0 && chargeDuration != 0) {
                        final LogMaker builder = new LogMaker(MetricsEvent.ACTION_CHARGE);
                        builder.setType(MetricsEvent.TYPE_DISMISS);
                        builder.addTaggedData(MetricsEvent.FIELD_PLUG_TYPE, mLastPlugType);
                        builder.addTaggedData(MetricsEvent.FIELD_CHARGING_DURATION_MILLIS,
                                chargeDuration);
                        builder.addTaggedData(MetricsEvent.FIELD_BATTERY_LEVEL_START,
                                mChargeStartLevel);
                        builder.addTaggedData(MetricsEvent.FIELD_BATTERY_LEVEL_END,
                                mHealthInfo.batteryLevel);
                        mMetricsLogger.write(builder);
                    }
                    mChargeStartTime = 0;
                }
            }
            if (mHealthInfo.batteryStatus != mLastBatteryStatus ||
                    mHealthInfo.batteryHealth != mLastBatteryHealth ||
                    mHealthInfo.batteryPresent != mLastBatteryPresent ||
                    mPlugType != mLastPlugType) {
                EventLog.writeEvent(EventLogTags.BATTERY_STATUS,
                        mHealthInfo.batteryStatus, mHealthInfo.batteryHealth, mHealthInfo.batteryPresent ? 1 : 0,
                        mPlugType, mHealthInfo.batteryTechnology);
                SystemProperties.set(
                        "debug.tracing.battery_status",
                        Integer.toString(mHealthInfo.batteryStatus));
                SystemProperties.set("debug.tracing.plug_type", Integer.toString(mPlugType));
            }
            if (mHealthInfo.batteryLevel != mLastBatteryLevel) {
                // Don't do this just from voltage or temperature changes, that is
                // too noisy.
                EventLog.writeEvent(
                        EventLogTags.BATTERY_LEVEL,
                        mHealthInfo.batteryLevel,
                        mHealthInfo.batteryVoltageMillivolts,
                        mHealthInfo.batteryTemperatureTenthsCelsius);
            }
            if (mBatteryLevelCritical && !mLastBatteryLevelCritical &&
                    mPlugType == BATTERY_PLUGGED_NONE) {
                // We want to make sure we log discharge cycle outliers
                // if the battery is about to die.
                dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                logOutlier = true;
            }

            if (!mBatteryLevelLow) {
                // Should we now switch in to low battery mode?
                if (mPlugType == BATTERY_PLUGGED_NONE
                        && mHealthInfo.batteryStatus !=
                           BatteryManager.BATTERY_STATUS_UNKNOWN
                        && mHealthInfo.batteryLevel <= mLowBatteryWarningLevel) {
                    mBatteryLevelLow = true;
                }
            } else {
                // Should we now switch out of low battery mode?
                if (mPlugType != BATTERY_PLUGGED_NONE) {
                    mBatteryLevelLow = false;
                } else if (mHealthInfo.batteryLevel >= mLowBatteryCloseWarningLevel)  {
                    mBatteryLevelLow = false;
                } else if (force && mHealthInfo.batteryLevel >= mLowBatteryWarningLevel) {
                    // If being forced, the previous state doesn't matter, we will just
                    // absolutely check to see if we are now above the warning level.
                    mBatteryLevelLow = false;
                }
            }

            mSequence++;

            // Separate broadcast is sent for power connected / not connected
            // since the standard intent will not wake any applications and some
            // applications may want to have smart behavior based on this.
            if (mPlugType != 0 && mLastPlugType == 0) {
                final Intent statusIntent = new Intent(Intent.ACTION_POWER_CONNECTED);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL, null,
                                mPowerOptions);
                    }
                });
            }
            else if (mPlugType == 0 && mLastPlugType != 0) {
                final Intent statusIntent = new Intent(Intent.ACTION_POWER_DISCONNECTED);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL, null,
                                mPowerOptions);
                    }
                });
            }

            if (shouldSendBatteryLowLocked()) {
                mSentLowBatteryBroadcast = true;
                final Intent statusIntent = new Intent(Intent.ACTION_BATTERY_LOW);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL, null,
                                mBatteryOptions);
                    }
                });
            } else if (mSentLowBatteryBroadcast &&
                    mHealthInfo.batteryLevel >= mLowBatteryCloseWarningLevel) {
                mSentLowBatteryBroadcast = false;
                final Intent statusIntent = new Intent(Intent.ACTION_BATTERY_OKAY);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                statusIntent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mContext.sendBroadcastAsUser(statusIntent, UserHandle.ALL, null,
                                mBatteryOptions);
                    }
                });
            }

            // We are doing this after sending the above broadcasts, so anything processing
            // them will get the new sequence number at that point.  (See for example how testing
            // of JobScheduler's BatteryController works.)
            sendBatteryChangedIntentLocked();
            if (mLastBatteryLevel != mHealthInfo.batteryLevel || mLastPlugType != mPlugType) {
                sendBatteryLevelChangedIntentLocked();
            }


            // Update the battery LED
            mLed.updateLightsLocked();

            // This needs to be done after sendIntent() so that we get the lastest battery stats.
            if (logOutlier && dischargeDuration != 0) {
                logOutlierLocked(dischargeDuration);
            }

            mLastBatteryStatus = mHealthInfo.batteryStatus;
            mLastBatteryHealth = mHealthInfo.batteryHealth;
            mLastBatteryPresent = mHealthInfo.batteryPresent;
            mLastBatteryLevel = mHealthInfo.batteryLevel;
            mLastPlugType = mPlugType;
            mLastBatteryVoltage = mHealthInfo.batteryVoltageMillivolts;
            mLastBatteryTemperature = mHealthInfo.batteryTemperatureTenthsCelsius;
            mLastMaxChargingCurrent = mHealthInfo.maxChargingCurrentMicroamps;
            mLastMaxChargingVoltage = mHealthInfo.maxChargingVoltageMicrovolts;
            mLastChargeCounter = mHealthInfo.batteryChargeCounterUah;
            mLastBatteryLevelCritical = mBatteryLevelCritical;
            mLastInvalidCharger = mInvalidCharger;
            mLastBatteryCycleCount = mHealthInfo.batteryCycleCount;
            mLastCharingState = mHealthInfo.chargingState;
        }
    }

    private void sendBatteryChangedIntentLocked() {
        //  Pack up the values and broadcast them to everyone
        final Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);

        int icon = getIconLocked(mHealthInfo.batteryLevel);

        intent.putExtra(BatteryManager.EXTRA_SEQUENCE, mSequence);
        intent.putExtra(BatteryManager.EXTRA_STATUS, mHealthInfo.batteryStatus);
        intent.putExtra(BatteryManager.EXTRA_HEALTH, mHealthInfo.batteryHealth);
        intent.putExtra(BatteryManager.EXTRA_PRESENT, mHealthInfo.batteryPresent);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, mHealthInfo.batteryLevel);
        intent.putExtra(BatteryManager.EXTRA_BATTERY_LOW, mSentLowBatteryBroadcast);
        intent.putExtra(BatteryManager.EXTRA_SCALE, BATTERY_SCALE);
        intent.putExtra(BatteryManager.EXTRA_ICON_SMALL, icon);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, mPlugType);
        intent.putExtra(BatteryManager.EXTRA_VOLTAGE, mHealthInfo.batteryVoltageMillivolts);
        intent.putExtra(
                BatteryManager.EXTRA_TEMPERATURE, mHealthInfo.batteryTemperatureTenthsCelsius);
        intent.putExtra(BatteryManager.EXTRA_TECHNOLOGY, mHealthInfo.batteryTechnology);
        intent.putExtra(BatteryManager.EXTRA_INVALID_CHARGER, mInvalidCharger);
        intent.putExtra(
                BatteryManager.EXTRA_MAX_CHARGING_CURRENT, mHealthInfo.maxChargingCurrentMicroamps);
        intent.putExtra(
                BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE,
                mHealthInfo.maxChargingVoltageMicrovolts);
        intent.putExtra(BatteryManager.EXTRA_CHARGE_COUNTER, mHealthInfo.batteryChargeCounterUah);
        intent.putExtra(BatteryManager.EXTRA_CYCLE_COUNT, mHealthInfo.batteryCycleCount);
        intent.putExtra(BatteryManager.EXTRA_CHARGING_STATUS, mHealthInfo.chargingState);
        if (DEBUG) {
            Slog.d(TAG, "Sending ACTION_BATTERY_CHANGED. scale:" + BATTERY_SCALE
                    + ", info:" + mHealthInfo.toString());
        }

        mHandler.post(() -> broadcastBatteryChangedIntent(mContext,
                intent, mBatteryChangedOptions));
    }

    private static void broadcastBatteryChangedIntent(Context context, Intent intent,
            Bundle options) {
        // TODO (293959093): It is important that SystemUI receives this broadcast as soon as
        // possible. Ideally, it should be using binder callbacks but until then, dispatch this
        // as a foreground broadcast to SystemUI.
        final Intent fgIntent = new Intent(intent);
        fgIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        fgIntent.setPackage(sSystemUiPackage);
        if (com.android.server.flags.Flags.pkgTargetedBatteryChangedNotSticky()) {
            context.sendBroadcastAsUser(fgIntent, UserHandle.ALL, null, options);
        } else {
            ActivityManager.broadcastStickyIntent(fgIntent, AppOpsManager.OP_NONE,
                    options, UserHandle.USER_ALL);
        }

        ActivityManager.broadcastStickyIntent(intent, new String[] {sSystemUiPackage},
                AppOpsManager.OP_NONE, options, UserHandle.USER_ALL);
    }

    private void sendBatteryLevelChangedIntentLocked() {
        Bundle event = new Bundle();
        long now = SystemClock.elapsedRealtime();
        event.putInt(BatteryManager.EXTRA_SEQUENCE, mSequence);
        event.putInt(BatteryManager.EXTRA_STATUS, mHealthInfo.batteryStatus);
        event.putInt(BatteryManager.EXTRA_HEALTH, mHealthInfo.batteryHealth);
        event.putBoolean(BatteryManager.EXTRA_PRESENT, mHealthInfo.batteryPresent);
        event.putInt(BatteryManager.EXTRA_LEVEL, mHealthInfo.batteryLevel);
        event.putBoolean(BatteryManager.EXTRA_BATTERY_LOW, mSentLowBatteryBroadcast);
        event.putInt(BatteryManager.EXTRA_SCALE, BATTERY_SCALE);
        event.putInt(BatteryManager.EXTRA_PLUGGED, mPlugType);
        event.putInt(BatteryManager.EXTRA_VOLTAGE, mHealthInfo.batteryVoltageMillivolts);
        event.putInt(BatteryManager.EXTRA_TEMPERATURE, mHealthInfo.batteryTemperatureTenthsCelsius);
        event.putInt(BatteryManager.EXTRA_CHARGE_COUNTER, mHealthInfo.batteryChargeCounterUah);
        event.putLong(BatteryManager.EXTRA_EVENT_TIMESTAMP, now);
        event.putInt(BatteryManager.EXTRA_CYCLE_COUNT, mHealthInfo.batteryCycleCount);
        event.putInt(BatteryManager.EXTRA_CHARGING_STATUS, mHealthInfo.chargingState);

        boolean queueWasEmpty = mBatteryLevelsEventQueue.isEmpty();
        mBatteryLevelsEventQueue.add(event);
        // Make sure queue is bounded and doesn't exceed intent payload limits
        if (mBatteryLevelsEventQueue.size() > MAX_BATTERY_LEVELS_QUEUE_SIZE) {
            mBatteryLevelsEventQueue.removeFirst();
        }

        if (queueWasEmpty) {
            // send now if last event was before throttle interval, otherwise delay
            long delay = now - mLastBatteryLevelChangedSentMs > BATTERY_LEVEL_CHANGE_THROTTLE_MS
                    ? 0 : mLastBatteryLevelChangedSentMs + BATTERY_LEVEL_CHANGE_THROTTLE_MS - now;
            mHandler.postDelayed(this::sendEnqueuedBatteryLevelChangedEvents, delay);
        }
    }

    private void sendEnqueuedBatteryLevelChangedEvents() {
        ArrayList<Bundle> events;
        synchronized (mLock) {
            events = new ArrayList<>(mBatteryLevelsEventQueue);
            mBatteryLevelsEventQueue.clear();
        }
        final Intent intent = new Intent(Intent.ACTION_BATTERY_LEVEL_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        intent.putParcelableArrayListExtra(BatteryManager.EXTRA_EVENTS, events);

        mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.BATTERY_STATS);
        mLastBatteryLevelChangedSentMs = SystemClock.elapsedRealtime();
    }

    private void notifyChargingPolicyChanged() {
        final int newPolicy;
        synchronized (mLock) {
            newPolicy = mLastChargingPolicy;
        }
        for (BatteryManagerInternal.ChargingPolicyChangeListener listener
                : mChargingPolicyChangeListeners) {
            listener.onChargingPolicyChanged(newPolicy);
        }
    }

    // TODO: Current code doesn't work since "--unplugged" flag in BSS was purposefully removed.
    private void logBatteryStatsLocked() {
        IBinder batteryInfoService = ServiceManager.getService(BatteryStats.SERVICE_NAME);
        if (batteryInfoService == null) return;

        DropBoxManager db = (DropBoxManager) mContext.getSystemService(Context.DROPBOX_SERVICE);
        if (db == null || !db.isTagEnabled("BATTERY_DISCHARGE_INFO")) return;

        File dumpFile = null;
        FileOutputStream dumpStream = null;
        try {
            // dump the service to a file
            dumpFile = new File(DUMPSYS_DATA_PATH + BatteryStats.SERVICE_NAME + ".dump");
            dumpStream = new FileOutputStream(dumpFile);
            batteryInfoService.dump(dumpStream.getFD(), DUMPSYS_ARGS);
            FileUtils.sync(dumpStream);

            // add dump file to drop box
            db.addFile("BATTERY_DISCHARGE_INFO", dumpFile, DropBoxManager.IS_TEXT);
        } catch (RemoteException e) {
            Slog.e(TAG, "failed to dump battery service", e);
        } catch (IOException e) {
            Slog.e(TAG, "failed to write dumpsys file", e);
        } finally {
            // make sure we clean up
            if (dumpStream != null) {
                try {
                    dumpStream.close();
                } catch (IOException e) {
                    Slog.e(TAG, "failed to close dumpsys output stream");
                }
            }
            if (dumpFile != null && !dumpFile.delete()) {
                Slog.e(TAG, "failed to delete temporary dumpsys file: "
                        + dumpFile.getAbsolutePath());
            }
        }
    }

    private void logOutlierLocked(long duration) {
        ContentResolver cr = mContext.getContentResolver();
        String dischargeThresholdString = Settings.Global.getString(cr,
                Settings.Global.BATTERY_DISCHARGE_THRESHOLD);
        String durationThresholdString = Settings.Global.getString(cr,
                Settings.Global.BATTERY_DISCHARGE_DURATION_THRESHOLD);

        if (dischargeThresholdString != null && durationThresholdString != null) {
            try {
                long durationThreshold = Long.parseLong(durationThresholdString);
                int dischargeThreshold = Integer.parseInt(dischargeThresholdString);
                if (duration <= durationThreshold &&
                        mDischargeStartLevel - mHealthInfo.batteryLevel >= dischargeThreshold) {
                    // If the discharge cycle is bad enough we want to know about it.
                    logBatteryStatsLocked();
                }
                if (DEBUG) Slog.v(TAG, "duration threshold: " + durationThreshold +
                        " discharge threshold: " + dischargeThreshold);
                if (DEBUG) Slog.v(TAG, "duration: " + duration + " discharge: " +
                        (mDischargeStartLevel - mHealthInfo.batteryLevel));
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Invalid DischargeThresholds GService string: " +
                        durationThresholdString + " or " + dischargeThresholdString);
            }
        }
    }

    private int getIconLocked(int level) {
        if (mHealthInfo.batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery_charge;
        } else if (mHealthInfo.batteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery;
        } else if (mHealthInfo.batteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING
                || mHealthInfo.batteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
            if (isPoweredLocked(BatteryManager.BATTERY_PLUGGED_ANY)
                    && mHealthInfo.batteryLevel >= 100) {
                return com.android.internal.R.drawable.stat_sys_battery_charge;
            } else {
                return com.android.internal.R.drawable.stat_sys_battery;
            }
        } else {
            return com.android.internal.R.drawable.stat_sys_battery_unknown;
        }
    }

    class Shell extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpHelp(pw);
        }
    }

    static void dumpHelp(PrintWriter pw) {
        pw.println("Battery service (battery) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        String getSetOptions = "ac|usb|wireless|dock|status|level|temp|present|counter|invalid";
        if (batteryServiceSupportCurrentAdbCommand()) {
            getSetOptions += "|current_now|current_average";
        }
        pw.println("  get [-f] [" + getSetOptions + "]");
        pw.println("  set [-f] [" + getSetOptions + "] <value>");
        pw.println("    Force a battery property value, freezing battery state.");
        pw.println("    -f: force a battery change broadcast be sent, prints new sequence.");
        pw.println("  unplug [-f]");
        pw.println("    Force battery unplugged, freezing battery state.");
        pw.println("    -f: force a battery change broadcast be sent, prints new sequence.");
        pw.println("  reset [-f]");
        pw.println("    Unfreeze battery state, returning to current hardware values.");
        pw.println("    -f: force a battery change broadcast be sent, prints new sequence.");
        if (Build.IS_DEBUGGABLE) {
            pw.println("  suspend_input");
            pw.println("    Suspend charging even if plugged in. ");
        }
    }

    static final int OPTION_FORCE_UPDATE = 1<<0;

    int parseOptions(Shell shell) {
        String opt;
        int opts = 0;
        while ((opt = shell.getNextOption()) != null) {
            if ("-f".equals(opt)) {
                opts |= OPTION_FORCE_UPDATE;
            }
        }
        return opts;
    }

    int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        switch (cmd) {
            case "unplug": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                unplugBattery(/* forceUpdate= */ (opts & OPTION_FORCE_UPDATE) != 0, pw);
            } break;
            case "get": {
                final int opts = parseOptions(shell);
                final String key = shell.getNextArg();
                if (key == null) {
                    pw.println("No property specified");
                    return -1;

                }
                switch (key) {
                    case "present":
                        pw.println(mHealthInfo.batteryPresent);
                        break;
                    case "ac":
                        pw.println(mHealthInfo.chargerAcOnline);
                        break;
                    case "usb":
                        pw.println(mHealthInfo.chargerUsbOnline);
                        break;
                    case "wireless":
                        pw.println(mHealthInfo.chargerWirelessOnline);
                        break;
                    case "dock":
                        pw.println(mHealthInfo.chargerDockOnline);
                        break;
                    case "status":
                        pw.println(mHealthInfo.batteryStatus);
                        break;
                    case "level":
                        pw.println(mHealthInfo.batteryLevel);
                        break;
                    case "counter":
                        pw.println(mHealthInfo.batteryChargeCounterUah);
                        break;
                    case "current_now":
                        if (batteryServiceSupportCurrentAdbCommand()) {
                            if ((opts & OPTION_FORCE_UPDATE) != 0) {
                                updateHealthInfo();
                            }
                            pw.println(mHealthInfo.batteryCurrentMicroamps);
                        }
                        break;
                    case "current_average":
                        if (batteryServiceSupportCurrentAdbCommand()) {
                            if ((opts & OPTION_FORCE_UPDATE) != 0) {
                                updateHealthInfo();
                            }
                            pw.println(mHealthInfo.batteryCurrentAverageMicroamps);
                        }
                        break;
                    case "temp":
                        pw.println(mHealthInfo.batteryTemperatureTenthsCelsius);
                        break;
                    case "invalid":
                        pw.println(mInvalidCharger);
                        break;
                    default:
                        pw.println("Unknown get option: " + key);
                        break;
                }
            } break;
            case "set": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                final String key = shell.getNextArg();
                if (key == null) {
                    pw.println("No property specified");
                    return -1;

                }
                final String value = shell.getNextArg();
                if (value == null) {
                    pw.println("No value specified");
                    return -1;

                }
                try {
                    if (!mUpdatesStopped) {
                        copyV1Battery(mLastHealthInfo, mHealthInfo);
                    }
                    boolean update = true;
                    switch (key) {
                        case "present":
                            mHealthInfo.batteryPresent = Integer.parseInt(value) != 0;
                            break;
                        case "ac":
                            mHealthInfo.chargerAcOnline = Integer.parseInt(value) != 0;
                            break;
                        case "usb":
                            mHealthInfo.chargerUsbOnline = Integer.parseInt(value) != 0;
                            break;
                        case "wireless":
                            mHealthInfo.chargerWirelessOnline = Integer.parseInt(value) != 0;
                            break;
                        case "dock":
                            mHealthInfo.chargerDockOnline = Integer.parseInt(value) != 0;
                            break;
                        case "status":
                            mHealthInfo.batteryStatus = Integer.parseInt(value);
                            break;
                        case "level":
                            mHealthInfo.batteryLevel = Integer.parseInt(value);
                            break;
                        case "counter":
                            mHealthInfo.batteryChargeCounterUah = Integer.parseInt(value);
                            break;
                        case "current_now":
                            if (batteryServiceSupportCurrentAdbCommand()) {
                                mHealthInfo.batteryCurrentMicroamps = Integer.parseInt(value);
                            }
                            break;
                        case "current_average":
                            if (batteryServiceSupportCurrentAdbCommand()) {
                                mHealthInfo.batteryCurrentAverageMicroamps =
                                        Integer.parseInt(value);
                            }
                        case "temp":
                            mHealthInfo.batteryTemperatureTenthsCelsius = Integer.parseInt(value);
                            break;
                        case "invalid":
                            mInvalidCharger = Integer.parseInt(value);
                            break;
                        default:
                            pw.println("Unknown set option: " + key);
                            update = false;
                            break;
                    }
                    if (update) {
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            mUpdatesStopped = true;
                            processValuesLocked(
                                    /* forceUpdate= */ (opts & OPTION_FORCE_UPDATE) != 0, pw);
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                    }
                } catch (NumberFormatException ex) {
                    pw.println("Bad value: " + value);
                    return -1;
                }
            } break;
            case "reset": {
                int opts = parseOptions(shell);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                resetBattery(/* forceUpdate= */ (opts & OPTION_FORCE_UPDATE) != 0, pw);
            } break;
            case "suspend_input": {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DEVICE_POWER, null);
                suspendBatteryInput();
            } break;
            default:
                return shell.handleDefaultCommands(cmd);
        }
        return 0;
    }

    private void updateHealthInfo() {
        try {
            mHealthServiceWrapper.scheduleUpdate();
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to update health service data.", e);
        }
    }

    private void setChargerAcOnline(boolean online, boolean forceUpdate) {
        if (!mUpdatesStopped) {
            copyV1Battery(mLastHealthInfo, mHealthInfo);
        }
        mHealthInfo.chargerAcOnline = online;
        mUpdatesStopped = true;
        Binder.withCleanCallingIdentity(() -> processValuesLocked(forceUpdate));
    }

    private void setBatteryLevel(int level, boolean forceUpdate) {
        if (!mUpdatesStopped) {
            copyV1Battery(mLastHealthInfo, mHealthInfo);
        }
        mHealthInfo.batteryLevel = level;
        mUpdatesStopped = true;
        Binder.withCleanCallingIdentity(() -> processValuesLocked(forceUpdate));
    }

    private void unplugBattery(boolean forceUpdate, PrintWriter pw) {
        if (!mUpdatesStopped) {
            copyV1Battery(mLastHealthInfo, mHealthInfo);
        }
        mHealthInfo.chargerAcOnline = false;
        mHealthInfo.chargerUsbOnline = false;
        mHealthInfo.chargerWirelessOnline = false;
        mHealthInfo.chargerDockOnline = false;
        mUpdatesStopped = true;
        Binder.withCleanCallingIdentity(() -> processValuesLocked(forceUpdate, pw));
    }

    private void resetBattery(boolean forceUpdate, @Nullable PrintWriter pw) {
        if (mUpdatesStopped) {
            mUpdatesStopped = false;
            copyV1Battery(mHealthInfo, mLastHealthInfo);
            Binder.withCleanCallingIdentity(() -> processValuesLocked(forceUpdate, pw));
        }
        if (mBatteryInputSuspended) {
            PowerProperties.battery_input_suspended(false);
            mBatteryInputSuspended = false;
        }
    }

    private void suspendBatteryInput() {
        if (!Build.IS_DEBUGGABLE) {
            throw new SecurityException(
                    "battery suspend_input is only supported on debuggable builds");
        }
        PowerProperties.battery_input_suspended(true);
        mBatteryInputSuspended = true;
    }

    private void processValuesLocked(boolean forceUpdate, @Nullable PrintWriter pw) {
        processValuesLocked(forceUpdate);
        if (pw != null && forceUpdate) {
            pw.println(mSequence);
        }
    }

    private void dumpInternal(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (mLock) {
            if (args == null || args.length == 0 || "-a".equals(args[0])) {
                pw.println("Current Battery Service state:");
                if (mUpdatesStopped) {
                    pw.println("  (UPDATES STOPPED -- use 'reset' to restart)");
                }
                pw.println("  AC powered: " + mHealthInfo.chargerAcOnline);
                pw.println("  USB powered: " + mHealthInfo.chargerUsbOnline);
                pw.println("  Wireless powered: " + mHealthInfo.chargerWirelessOnline);
                pw.println("  Dock powered: " + mHealthInfo.chargerDockOnline);
                pw.println("  Max charging current: " + mHealthInfo.maxChargingCurrentMicroamps);
                pw.println("  Max charging voltage: " + mHealthInfo.maxChargingVoltageMicrovolts);
                pw.println("  Charge counter: " + mHealthInfo.batteryChargeCounterUah);
                pw.println("  status: " + mHealthInfo.batteryStatus);
                pw.println("  health: " + mHealthInfo.batteryHealth);
                pw.println("  present: " + mHealthInfo.batteryPresent);
                pw.println("  level: " + mHealthInfo.batteryLevel);
                pw.println("  scale: " + BATTERY_SCALE);
                pw.println("  voltage: " + mHealthInfo.batteryVoltageMillivolts);
                pw.println("  temperature: " + mHealthInfo.batteryTemperatureTenthsCelsius);
                pw.println("  technology: " + mHealthInfo.batteryTechnology);
                pw.println("  Charging state: " + mHealthInfo.chargingState);
                pw.println("  Charging policy: " + mHealthInfo.chargingPolicy);
            } else {
                Shell shell = new Shell();
                shell.exec(mBinderService, null, fd, null, args, null, new ResultReceiver(null));
            }
        }
    }

    private void dumpProto(FileDescriptor fd) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        synchronized (mLock) {
            proto.write(BatteryServiceDumpProto.ARE_UPDATES_STOPPED, mUpdatesStopped);
            int batteryPluggedValue = OsProtoEnums.BATTERY_PLUGGED_NONE;
            if (mHealthInfo.chargerAcOnline) {
                batteryPluggedValue = OsProtoEnums.BATTERY_PLUGGED_AC;
            } else if (mHealthInfo.chargerUsbOnline) {
                batteryPluggedValue = OsProtoEnums.BATTERY_PLUGGED_USB;
            } else if (mHealthInfo.chargerWirelessOnline) {
                batteryPluggedValue = OsProtoEnums.BATTERY_PLUGGED_WIRELESS;
            } else if (mHealthInfo.chargerDockOnline) {
                batteryPluggedValue = OsProtoEnums.BATTERY_PLUGGED_DOCK;
            }
            proto.write(BatteryServiceDumpProto.PLUGGED, batteryPluggedValue);
            proto.write(
                    BatteryServiceDumpProto.MAX_CHARGING_CURRENT,
                    mHealthInfo.maxChargingCurrentMicroamps);
            proto.write(
                    BatteryServiceDumpProto.MAX_CHARGING_VOLTAGE,
                    mHealthInfo.maxChargingVoltageMicrovolts);
            proto.write(
                    BatteryServiceDumpProto.CHARGE_COUNTER, mHealthInfo.batteryChargeCounterUah);
            proto.write(BatteryServiceDumpProto.STATUS, mHealthInfo.batteryStatus);
            proto.write(BatteryServiceDumpProto.HEALTH, mHealthInfo.batteryHealth);
            proto.write(BatteryServiceDumpProto.IS_PRESENT, mHealthInfo.batteryPresent);
            proto.write(BatteryServiceDumpProto.LEVEL, mHealthInfo.batteryLevel);
            proto.write(BatteryServiceDumpProto.SCALE, BATTERY_SCALE);
            proto.write(BatteryServiceDumpProto.VOLTAGE, mHealthInfo.batteryVoltageMillivolts);
            proto.write(
                    BatteryServiceDumpProto.TEMPERATURE,
                    mHealthInfo.batteryTemperatureTenthsCelsius);
            proto.write(BatteryServiceDumpProto.TECHNOLOGY, mHealthInfo.batteryTechnology);
        }
        proto.flush();
    }

    private static void traceBegin(String name) {
        Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, name);
    }

    private static void traceEnd() {
        Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
    }

    private final class Led {
        // must match: config_notificationsBatteryLowBehavior in config.xml
        static final int LOW_BATTERY_BEHAVIOR_DEFAULT = 0;
        static final int LOW_BATTERY_BEHAVIOR_SOLID = 1;
        static final int LOW_BATTERY_BEHAVIOR_FLASHING = 2;

        private final LogicalLight mBatteryLight;

        private final int mBatteryLowARGB;
        private final int mBatteryMediumARGB;
        private final int mBatteryFullARGB;
        private final int mBatteryLedOn;
        private final int mBatteryLedOff;
        private final int mBatteryLowBehavior;

        public Led(Context context, LightsManager lights) {
            mBatteryLight = lights.getLight(LightsManager.LIGHT_ID_BATTERY);

            mBatteryLowARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLowARGB);
            mBatteryMediumARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryMediumARGB);
            mBatteryFullARGB = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryFullARGB);
            mBatteryLedOn = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLedOn);
            mBatteryLedOff = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLedOff);
            mBatteryNearlyFullLevel = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryNearlyFullLevel);
            mBatteryLowBehavior = context.getResources().getInteger(
                    com.android.internal.R.integer.config_notificationsBatteryLowBehavior);
        }

        /**
         * Synchronize on BatteryService.
         */
        public void updateLightsLocked() {
            if (mBatteryLight == null) {
                return;
            }
            final int level = mHealthInfo.batteryLevel;
            final int status = mHealthInfo.batteryStatus;
            if (level < mLowBatteryWarningLevel) {
                switch (mBatteryLowBehavior) {
                    case LOW_BATTERY_BEHAVIOR_SOLID:
                        // Solid red when low battery
                        mBatteryLight.setColor(mBatteryLowARGB);
                        break;
                    case LOW_BATTERY_BEHAVIOR_FLASHING:
                        // Flash red when battery is low and not charging
                        mBatteryLight.setFlashing(mBatteryLowARGB, LogicalLight.LIGHT_FLASH_TIMED,
                                mBatteryLedOn, mBatteryLedOff);
                        break;
                    default:
                        if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
                            // Solid red when battery is charging
                            mBatteryLight.setColor(mBatteryLowARGB);
                        } else {
                            // Flash red when battery is low and not charging
                            mBatteryLight.setFlashing(mBatteryLowARGB,
                                    LogicalLight.LIGHT_FLASH_TIMED, mBatteryLedOn, mBatteryLedOff);
                        }
                        break;
                }
            } else if (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL) {
                if (status == BatteryManager.BATTERY_STATUS_FULL
                        || level >= mBatteryNearlyFullLevel) {
                    // Solid green when full or charging and nearly full
                    mBatteryLight.setColor(mBatteryFullARGB);
                } else {
                    // Solid orange when charging and halfway full
                    mBatteryLight.setColor(mBatteryMediumARGB);
                }
            } else {
                // No lights if not charging and not low
                mBatteryLight.turnOff();
            }
        }
    }

    private final class BinderService extends Binder {
        @Override protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            if (args.length > 0 && "--proto".equals(args[0])) {
                dumpProto(fd);
            } else {
                dumpInternal(fd, pw, args);
            }
        }

        @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err, String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            (new Shell()).exec(this, in, out, err, args, callback, resultReceiver);
        }
    }

    // Reduced IBatteryPropertiesRegistrar that implements getProperty for usage
    // in BatteryManager and enforce permissions.
    private final class BatteryPropertiesRegistrar extends IBatteryPropertiesRegistrar.Stub {
        @Override
        public int getProperty(int id, final BatteryProperty prop) throws RemoteException {
            switch (id) {
                case BatteryManager.BATTERY_PROPERTY_STATE_OF_HEALTH:
                    if (stateOfHealthPublic()) {
                        break;
                    }

                case BatteryManager.BATTERY_PROPERTY_MANUFACTURING_DATE:
                case BatteryManager.BATTERY_PROPERTY_FIRST_USAGE_DATE:
                case BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY:
                case BatteryManager.BATTERY_PROPERTY_SERIAL_NUMBER:
                case BatteryManager.BATTERY_PROPERTY_PART_STATUS:
                    mContext.enforceCallingPermission(
                            android.Manifest.permission.BATTERY_STATS, null);
                    break;
            }
            return mHealthServiceWrapper.getProperty(id, prop);
        }
        @Override
        public void scheduleUpdate() throws RemoteException {
            mHealthServiceWrapper.scheduleUpdate();
        }
    }

    private final class LocalService extends BatteryManagerInternal {
        @Override
        public boolean isPowered(int plugTypeSet) {
            synchronized (mLock) {
                return isPoweredLocked(plugTypeSet);
            }
        }

        @Override
        public int getPlugType() {
            synchronized (mLock) {
                return mPlugType;
            }
        }

        @Override
        public int getBatteryLevel() {
            synchronized (mLock) {
                return mHealthInfo.batteryLevel;
            }
        }

        @Override
        public int getBatteryChargeCounter() {
            synchronized (mLock) {
                return mHealthInfo.batteryChargeCounterUah;
            }
        }

        @Override
        public int getBatteryFullCharge() {
            synchronized (mLock) {
                return mHealthInfo.batteryFullChargeUah;
            }
        }

        @Override
        public int getBatteryHealth() {
            synchronized (mLock) {
                return mHealthInfo.batteryHealth;
            }
        }

        @Override
        public boolean getBatteryLevelLow() {
            synchronized (mLock) {
                return mBatteryLevelLow;
            }
        }

        @Override
        public void registerChargingPolicyChangeListener(
                BatteryManagerInternal.ChargingPolicyChangeListener listener) {
            mChargingPolicyChangeListeners.add(listener);
        }

        @Override
        public int getChargingPolicy() {
            synchronized (mLock) {
                return mLastChargingPolicy;
            }
        }

        @Override
        public int getInvalidCharger() {
            synchronized (mLock) {
                return mInvalidCharger;
            }
        }

        @Override
        public void setChargerAcOnline(boolean online, boolean forceUpdate) {
            BatteryService.this.setChargerAcOnline(online, forceUpdate);
        }

        @Override
        public void setBatteryLevel(int level, boolean forceUpdate) {
            BatteryService.this.setBatteryLevel(level, forceUpdate);
        }

        @Override
        public void unplugBattery(boolean forceUpdate) {
            BatteryService.this.unplugBattery(forceUpdate, /* printWriter= */ null);
        }

        @Override
        public void resetBattery(boolean forceUpdate) {
            BatteryService.this.resetBattery(forceUpdate, /* printWriter= */ null);
        }

        @Override
        public void suspendBatteryInput() {
            BatteryService.this.suspendBatteryInput();
        }
    }
}
