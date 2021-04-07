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

package com.android.server;

import android.annotation.IntRange;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.IUiModeManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.service.dreams.Sandman;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.DisableCarModeActivity;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.app.UiModeManager.MODE_NIGHT_AUTO;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM;
import static android.app.UiModeManager.MODE_NIGHT_YES;
import static android.os.UserHandle.USER_SYSTEM;
import static android.util.TimeUtils.isTimeBetween;

final class UiModeManagerService extends SystemService {
    private static final String TAG = UiModeManager.class.getSimpleName();
    private static final boolean LOG = false;

    // Enable launching of applications when entering the dock.
    private static final boolean ENABLE_LAUNCH_DESK_DOCK_APP = true;
    private static final String SYSTEM_PROPERTY_DEVICE_THEME = "persist.sys.theme";

    final Object mLock = new Object();
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private int mLastBroadcastState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private int mNightMode = UiModeManager.MODE_NIGHT_NO;
    private final LocalTime DEFAULT_CUSTOM_NIGHT_START_TIME = LocalTime.of(22, 0);
    private final LocalTime DEFAULT_CUSTOM_NIGHT_END_TIME = LocalTime.of(6, 0);
    private LocalTime mCustomAutoNightModeStartMilliseconds = DEFAULT_CUSTOM_NIGHT_START_TIME;
    private LocalTime mCustomAutoNightModeEndMilliseconds = DEFAULT_CUSTOM_NIGHT_END_TIME;

    private Map<Integer, String> mCarModePackagePriority = new HashMap<>();
    private boolean mCarModeEnabled = false;
    private boolean mCharging = false;
    private boolean mPowerSave = false;
    // Do not change configuration now. wait until screen turns off.
    // This prevents jank and activity restart when the user
    // is actively using the device
    private boolean mWaitForScreenOff = false;
    private int mDefaultUiModeType;
    private boolean mCarModeKeepsScreenOn;
    private boolean mDeskModeKeepsScreenOn;
    private boolean mTelevision;
    private boolean mCar;
    private boolean mWatch;
    private boolean mVrHeadset;
    private boolean mComputedNightMode;
    private int mCarModeEnableFlags;
    private boolean mSetupWizardComplete;

    // flag set by resource, whether to enable Car dock launch when starting car mode.
    private boolean mEnableCarDockLaunch = true;
    // flag set by resource, whether to lock UI mode to the default one or not.
    private boolean mUiModeLocked = false;
    // flag set by resource, whether to night mode change for normal all or not.
    private boolean mNightModeLocked = false;

    int mCurUiMode = 0;
    private int mSetUiMode = 0;
    private boolean mHoldingConfiguration = false;
    private int mCurrentUser;

    private Configuration mConfiguration = new Configuration();
    boolean mSystemReady;

    private final Handler mHandler = new Handler();

    private TwilightManager mTwilightManager;
    private NotificationManager mNotificationManager;
    private StatusBarManager mStatusBarManager;
    private WindowManagerInternal mWindowManager;
    private AlarmManager mAlarmManager;
    private PowerManager mPowerManager;

    // In automatic scheduling, the user is able
    // to override the computed night mode until the two match
    // Example: Activate dark mode in the day time until sunrise the next day
    private boolean mOverrideNightModeOn;
    private boolean mOverrideNightModeOff;
    private int mOverrideNightModeUser = USER_SYSTEM;

    private PowerManager.WakeLock mWakeLock;

    private final LocalService mLocalService = new LocalService();
    private PowerManagerInternal mLocalPowerManager;

    public UiModeManagerService(Context context) {
        super(context);
        mConfiguration.setToDefaults();
    }

    @VisibleForTesting
    protected UiModeManagerService(Context context, boolean setupWizardComplete,
            TwilightManager tm) {
        this(context);
        mSetupWizardComplete = setupWizardComplete;
        mTwilightManager = tm;
    }

    private static Intent buildHomeIntent(String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    // The broadcast receiver which receives the result of the ordered broadcast sent when
    // the dock state changes. The original ordered broadcast is sent with an initial result
    // code of RESULT_OK. If any of the registered broadcast receivers changes this value, e.g.,
    // to RESULT_CANCELED, then the intent to start a dock app will not be sent.
    private final BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getResultCode() != Activity.RESULT_OK) {
                if (LOG) {
                    Slog.v(TAG, "Handling broadcast result for action " + intent.getAction()
                            + ": canceled: " + getResultCode());
                }
                return;
            }

            final int enableFlags = intent.getIntExtra("enableFlags", 0);
            final int disableFlags = intent.getIntExtra("disableFlags", 0);
            synchronized (mLock) {
                updateAfterBroadcastLocked(intent.getAction(), enableFlags, disableFlags);
            }
        }
    };

    private final BroadcastReceiver mDockModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                    Intent.EXTRA_DOCK_STATE_UNDOCKED);
            updateDockState(state);
        }
    };

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_BATTERY_CHANGED:
                    mCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    break;
            }
            synchronized (mLock) {
                if (mSystemReady) {
                    updateLocked(0, 0);
                }
            }
        }
    };

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            synchronized (mLock) {
                if (mNightMode == UiModeManager.MODE_NIGHT_AUTO && mSystemReady) {
                    if (shouldApplyAutomaticChangesImmediately()) {
                        updateLocked(0, 0);
                    } else {
                        registerScreenOffEventLocked();
                    }
                }
            }
        }
    };

    /**
     * DO NOT USE DIRECTLY
     * see register registerScreenOffEvent and unregisterScreenOffEvent
     */
    private final BroadcastReceiver mOnScreenOffHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                // must unregister first before updating
                unregisterScreenOffEventLocked();
                updateLocked(0, 0);
            }
        }
    };

    private final BroadcastReceiver mOnTimeChangedHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                updateCustomTimeLocked();
            }
        }
    };

    private final AlarmManager.OnAlarmListener mCustomTimeListener = () -> {
        synchronized (mLock) {
            updateCustomTimeLocked();
        }
    };

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            synchronized (mLock) {
                mVrHeadset = enabled;
                if (mSystemReady) {
                    updateLocked(0, 0);
                }
            }
        }
    };

    private final ContentObserver mSetupWizardObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                // setup wizard is done now so we can unblock
                if (setupWizardCompleteForCurrentUser() && !selfChange) {
                    mSetupWizardComplete = true;
                    getContext().getContentResolver()
                            .unregisterContentObserver(mSetupWizardObserver);
                    // update night mode
                    Context context = getContext();
                    updateNightModeFromSettingsLocked(context, context.getResources(),
                            UserHandle.getCallingUserId());
                    updateLocked(0, 0);
                }
            }
        }
    };

    private final ContentObserver mDarkThemeObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSystemProperties();
        }
    };

    private void updateSystemProperties() {
        int mode = Secure.getIntForUser(getContext().getContentResolver(), Secure.UI_NIGHT_MODE,
                mNightMode, 0);
        if (mode == MODE_NIGHT_AUTO || mode == MODE_NIGHT_CUSTOM) {
            mode = MODE_NIGHT_YES;
        }
        SystemProperties.set(SYSTEM_PROPERTY_DEVICE_THEME, Integer.toString(mode));
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);
      	mCurrentUser = userHandle;
        getContext().getContentResolver().unregisterContentObserver(mSetupWizardObserver);
        verifySetupWizardCompleted();
        synchronized (mLock) {
            // only update if the value is actually changed
            if (updateNightModeFromSettingsLocked(getContext(), getContext().getResources(),
                   mCurrentUser)) {
                updateLocked(0, 0);
            }
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                final Context context = getContext();
                mSystemReady = true;
                mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);
                mWindowManager = LocalServices.getService(WindowManagerInternal.class);
                mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
                TwilightManager twilightManager = getLocalService(TwilightManager.class);
                if (twilightManager != null) mTwilightManager = twilightManager;
                mLocalPowerManager =
                        LocalServices.getService(PowerManagerInternal.class);
                initPowerSave();
                mCarModeEnabled = mDockState == Intent.EXTRA_DOCK_STATE_CAR;
                registerVrStateListener();
                // register listeners
                context.getContentResolver()
                        .registerContentObserver(Secure.getUriFor(Secure.UI_NIGHT_MODE),
                                false, mDarkThemeObserver, 0);
                context.registerReceiver(mDockModeReceiver,
                        new IntentFilter(Intent.ACTION_DOCK_EVENT));
                IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                context.registerReceiver(mBatteryReceiver, batteryFilter);
                context.registerReceiver(mSettingsRestored,
                        new IntentFilter(Intent.ACTION_SETTING_RESTORED));
                context.registerReceiver(mOnShutdown,
                        new IntentFilter(Intent.ACTION_SHUTDOWN));
                updateConfigurationLocked();
                applyConfigurationExternallyLocked();
            }
        }
    }

    @Override
    public void onStart() {
        final Context context = getContext();
        // If setup isn't complete for this user listen for completion so we can unblock
        // being able to send a night mode configuration change event
        verifySetupWizardCompleted();

        final Resources res = context.getResources();
        mNightMode = res.getInteger(
                com.android.internal.R.integer.config_defaultNightMode);
        mDefaultUiModeType = res.getInteger(
                com.android.internal.R.integer.config_defaultUiModeType);
        mCarModeKeepsScreenOn = (res.getInteger(
                com.android.internal.R.integer.config_carDockKeepsScreenOn) == 1);
        mDeskModeKeepsScreenOn = (res.getInteger(
                com.android.internal.R.integer.config_deskDockKeepsScreenOn) == 1);
        mEnableCarDockLaunch = res.getBoolean(
                com.android.internal.R.bool.config_enableCarDockHomeLaunch);
        mUiModeLocked = res.getBoolean(com.android.internal.R.bool.config_lockUiMode);
        mNightModeLocked = res.getBoolean(com.android.internal.R.bool.config_lockDayNightMode);
        final PackageManager pm = context.getPackageManager();
        mTelevision = pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        mCar = pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        mWatch = pm.hasSystemFeature(PackageManager.FEATURE_WATCH);

        // Update the initial, static configurations.
        SystemServerInitThreadPool.submit(() -> {
            synchronized (mLock) {
                TwilightManager twilightManager = getLocalService(TwilightManager.class);
                if (twilightManager != null) mTwilightManager = twilightManager;
                updateNightModeFromSettingsLocked(context, res, UserHandle.getCallingUserId());
                updateSystemProperties();
            }

        }, TAG + ".onStart");
        publishBinderService(Context.UI_MODE_SERVICE, mService);
        publishLocalService(UiModeManagerInternal.class, mLocalService);
    }

    private final BroadcastReceiver mOnShutdown = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mNightMode == MODE_NIGHT_AUTO) {
                persistComputedNightMode(mCurrentUser);
            }
        }
    };

    private void persistComputedNightMode(int userId) {
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.UI_NIGHT_MODE_LAST_COMPUTED, mComputedNightMode ? 1 : 0,
                userId);
    }

    private final BroadcastReceiver mSettingsRestored = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            List<String> settings = Arrays.asList(
                    Secure.UI_NIGHT_MODE, Secure.DARK_THEME_CUSTOM_START_TIME,
                    Secure.DARK_THEME_CUSTOM_END_TIME);
            if (settings.contains(intent.getExtras().getCharSequence(Intent.EXTRA_SETTING_NAME))) {
                synchronized (mLock) {
                    updateNightModeFromSettingsLocked(context, context.getResources(),
                            UserHandle.getCallingUserId());
                    updateConfigurationLocked();
                }
            }
        }
    };

    private void initPowerSave() {
        mPowerSave =
                mLocalPowerManager.getLowPowerState(ServiceType.NIGHT_MODE)
                        .batterySaverEnabled;
        mLocalPowerManager.registerLowPowerModeObserver(ServiceType.NIGHT_MODE, state -> {
            synchronized (mLock) {
                if (mPowerSave == state.batterySaverEnabled) {
                    return;
                }
                mPowerSave = state.batterySaverEnabled;
                if (mSystemReady) {
                    updateLocked(0, 0);
                }
            }
        });
    }

    @VisibleForTesting
    protected IUiModeManager getService() {
        return mService;
    }

    @VisibleForTesting
    protected Configuration getConfiguration() {
        return mConfiguration;
    }

    // Records whether setup wizard has happened or not and adds an observer for this user if not.
    private void verifySetupWizardCompleted() {
        final Context context = getContext();
        final int userId = UserHandle.getCallingUserId();
        if (!setupWizardCompleteForCurrentUser()) {
            mSetupWizardComplete = false;
            context.getContentResolver().registerContentObserver(
                    Secure.getUriFor(
                            Secure.USER_SETUP_COMPLETE), false, mSetupWizardObserver, userId);
        } else {
            mSetupWizardComplete = true;
        }
    }

    private boolean setupWizardCompleteForCurrentUser() {
        return Secure.getIntForUser(getContext().getContentResolver(),
                Secure.USER_SETUP_COMPLETE, 0, UserHandle.getCallingUserId()) == 1;
    }

    private void updateCustomTimeLocked() {
        if (mNightMode != MODE_NIGHT_CUSTOM) return;
        if (shouldApplyAutomaticChangesImmediately()) {
            updateLocked(0, 0);
        } else {
            registerScreenOffEventLocked();
        }
        scheduleNextCustomTimeListener();
    }

    /**
     * Updates the night mode setting in Settings.Global and returns if the value was successfully
     * changed.
     *
     * @param context A valid context
     * @param res     A valid resource object
     * @param userId  The user to update the setting for
     * @return True if the new value is different from the old value. False otherwise.
     */
    private boolean updateNightModeFromSettingsLocked(Context context, Resources res, int userId) {
        if (mCarModeEnabled || mCar) {
            return false;
        }
        int oldNightMode = mNightMode;
        if (mSetupWizardComplete) {
            mNightMode = Secure.getIntForUser(context.getContentResolver(),
                    Secure.UI_NIGHT_MODE, mNightMode, userId);
            mOverrideNightModeOn = Secure.getIntForUser(context.getContentResolver(),
                    Secure.UI_NIGHT_MODE_OVERRIDE_ON, 0, userId) != 0;
            mOverrideNightModeOff = Secure.getIntForUser(context.getContentResolver(),
                    Secure.UI_NIGHT_MODE_OVERRIDE_OFF, 0, userId) != 0;
            mCustomAutoNightModeStartMilliseconds = LocalTime.ofNanoOfDay(
                    Secure.getLongForUser(context.getContentResolver(),
                            Secure.DARK_THEME_CUSTOM_START_TIME,
                            DEFAULT_CUSTOM_NIGHT_START_TIME.toNanoOfDay() / 1000L, userId) * 1000);
            mCustomAutoNightModeEndMilliseconds = LocalTime.ofNanoOfDay(
                    Secure.getLongForUser(context.getContentResolver(),
                            Secure.DARK_THEME_CUSTOM_END_TIME,
                            DEFAULT_CUSTOM_NIGHT_END_TIME.toNanoOfDay() / 1000L, userId) * 1000);
            if (mNightMode == MODE_NIGHT_AUTO) {
                mComputedNightMode = Secure.getIntForUser(context.getContentResolver(),
                        Secure.UI_NIGHT_MODE_LAST_COMPUTED, 0, userId) != 0;
            }
        }

        return oldNightMode != mNightMode;
    }

    private static long toMilliSeconds(LocalTime t) {
        return t.toNanoOfDay() / 1000;
    }

    private static LocalTime fromMilliseconds(long t) {
        return LocalTime.ofNanoOfDay(t * 1000);
    }

    private void registerScreenOffEventLocked() {
        if (mPowerSave) return;
        mWaitForScreenOff = true;
        final IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiver(mOnScreenOffHandler, intentFilter);
    }

    private void cancelCustomAlarm() {
        mAlarmManager.cancel(mCustomTimeListener);
    }

    private void unregisterScreenOffEventLocked() {
        mWaitForScreenOff = false;
        try {
            getContext().unregisterReceiver(mOnScreenOffHandler);
        } catch (IllegalArgumentException e) {
            // we ignore this exception if the receiver is unregistered already.
        }
    }

    private void registerTimeChangeEvent() {
        final IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_TIME_CHANGED);
        intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        getContext().registerReceiver(mOnTimeChangedHandler, intentFilter);
    }

    private void unregisterTimeChangeEvent() {
        try {
            getContext().unregisterReceiver(mOnTimeChangedHandler);
        } catch (IllegalArgumentException e) {
            // we ignore this exception if the receiver is unregistered already.
        }
    }

    private final IUiModeManager.Stub mService = new IUiModeManager.Stub() {
        @Override
        public void enableCarMode(@UiModeManager.EnableCarMode int flags,
                @IntRange(from = 0) int priority, String callingPackage) {
            if (isUiModeLocked()) {
                Slog.e(TAG, "enableCarMode while UI mode is locked");
                return;
            }

            if (priority != UiModeManager.DEFAULT_PRIORITY
                    && getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.ENTER_CAR_MODE_PRIORITIZED)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Enabling car mode with a priority requires "
                        + "permission ENTER_CAR_MODE_PRIORITIZED");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    setCarModeLocked(true, flags, priority, callingPackage);
                    if (mSystemReady) {
                        updateLocked(flags, 0);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * This method is only kept around for the time being; the AIDL has an UnsupportedAppUsage
         * tag which means this method is technically considered part of the greylist "API".
         * @param flags
         */
        @Override
        public void disableCarMode(@UiModeManager.DisableCarMode int flags) {
            disableCarModeByCallingPackage(flags, null /* callingPackage */);
        }

        /**
         * Handles requests to disable car mode.
         * @param flags Disable car mode flags
         * @param callingPackage
         */
        @Override
        public void disableCarModeByCallingPackage(@UiModeManager.DisableCarMode int flags,
                String callingPackage) {
            if (isUiModeLocked()) {
                Slog.e(TAG, "disableCarMode while UI mode is locked");
                return;
            }

            // If the caller is the system, we will allow the DISABLE_CAR_MODE_ALL_PRIORITIES car
            // mode flag to be specified; this is so that the user can disable car mode at all
            // priorities using the persistent notification.
            boolean isSystemCaller = Binder.getCallingUid() == Process.SYSTEM_UID;
            final int carModeFlags =
                    isSystemCaller ? flags : flags & ~UiModeManager.DISABLE_CAR_MODE_ALL_PRIORITIES;

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    // Determine if the caller has enabled car mode at a priority other than the
                    // default one.  If they have, then attempt to disable at that priority.
                    int priority = mCarModePackagePriority.entrySet()
                            .stream()
                            .filter(e -> e.getValue().equals(callingPackage))
                            .findFirst()
                            .map(Map.Entry::getKey)
                            .orElse(UiModeManager.DEFAULT_PRIORITY);

                    setCarModeLocked(false, carModeFlags, priority, callingPackage);
                    if (mSystemReady) {
                        updateLocked(0, flags);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public int getCurrentModeType() {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    return mCurUiMode & Configuration.UI_MODE_TYPE_MASK;
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setNightMode(int mode) {
            if (isNightModeLocked() && (getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
                    != PackageManager.PERMISSION_GRANTED)) {
                Slog.e(TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission");
                return;
            }
            switch (mode) {
                case UiModeManager.MODE_NIGHT_NO:
                case UiModeManager.MODE_NIGHT_YES:
                case MODE_NIGHT_AUTO:
                case MODE_NIGHT_CUSTOM:
                    break;
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            final int user = UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mNightMode != mode) {
                        if (mNightMode == MODE_NIGHT_AUTO || mNightMode == MODE_NIGHT_CUSTOM) {
                            unregisterScreenOffEventLocked();
                            cancelCustomAlarm();
                        }

                        mNightMode = mode;
                        resetNightModeOverrideLocked();
                        persistNightMode(user);
                        // on screen off will update configuration instead
                        if ((mNightMode != MODE_NIGHT_AUTO && mNightMode != MODE_NIGHT_CUSTOM)
                                || shouldApplyAutomaticChangesImmediately()) {
                            unregisterScreenOffEventLocked();
                            updateLocked(0, 0);
                        } else {
                            registerScreenOffEventLocked();
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public int getNightMode() {
            synchronized (mLock) {
                return mNightMode;
            }
        }

        @Override
        public boolean isUiModeLocked() {
            synchronized (mLock) {
                return mUiModeLocked;
            }
        }

        @Override
        public boolean isNightModeLocked() {
            synchronized (mLock) {
                return mNightModeLocked;
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new Shell(mService).exec(mService, in, out, err, args, callback, resultReceiver);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;
            dumpImpl(pw);
        }

        @Override
        public boolean setNightModeActivated(boolean active) {
            if (isNightModeLocked() && (getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
                    != PackageManager.PERMISSION_GRANTED)) {
                Slog.e(TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission");
                return false;
            }
            final int user = Binder.getCallingUserHandle().getIdentifier();
            if (user != mCurrentUser && getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.e(TAG, "Target user is not current user,"
                        + " INTERACT_ACROSS_USERS permission is required");
                return false;

            }
            synchronized (mLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mNightMode == MODE_NIGHT_AUTO || mNightMode == MODE_NIGHT_CUSTOM) {
                        unregisterScreenOffEventLocked();
                        mOverrideNightModeOff = !active;
                        mOverrideNightModeOn = active;
                        mOverrideNightModeUser = mCurrentUser;
                        persistNightModeOverrides(mCurrentUser);
                    } else if (mNightMode == UiModeManager.MODE_NIGHT_NO
                            && active) {
                        mNightMode = UiModeManager.MODE_NIGHT_YES;
                    } else if (mNightMode == UiModeManager.MODE_NIGHT_YES
                            && !active) {
                        mNightMode = UiModeManager.MODE_NIGHT_NO;
                    }
                    updateConfigurationLocked();
                    applyConfigurationExternallyLocked();
                    persistNightMode(mCurrentUser);
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public long getCustomNightModeStart() {
            return mCustomAutoNightModeStartMilliseconds.toNanoOfDay() / 1000;
        }

        @Override
        public void setCustomNightModeStart(long time) {
            if (isNightModeLocked() && getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.e(TAG, "Set custom time start, requires MODIFY_DAY_NIGHT_MODE permission");
                return;
            }
            final int user = UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try {
                LocalTime newTime = LocalTime.ofNanoOfDay(time * 1000);
                if (newTime == null) return;
                mCustomAutoNightModeStartMilliseconds = newTime;
                persistNightMode(user);
                onCustomTimeUpdated(user);
            } catch (DateTimeException e) {
                unregisterScreenOffEventLocked();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public long getCustomNightModeEnd() {
            return mCustomAutoNightModeEndMilliseconds.toNanoOfDay() / 1000;
        }

        @Override
        public void setCustomNightModeEnd(long time) {
            if (isNightModeLocked() && getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.e(TAG, "Set custom time end, requires MODIFY_DAY_NIGHT_MODE permission");
                return;
            }
            final int user = UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try {
                LocalTime newTime = LocalTime.ofNanoOfDay(time * 1000);
                if (newTime == null) return;
                mCustomAutoNightModeEndMilliseconds = newTime;
                onCustomTimeUpdated(user);
            } catch (DateTimeException e) {
                unregisterScreenOffEventLocked();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    };

    private void onCustomTimeUpdated(int user) {
        persistNightMode(user);
        if (mNightMode != MODE_NIGHT_CUSTOM) return;
        if (shouldApplyAutomaticChangesImmediately()) {
            unregisterScreenOffEventLocked();
            updateLocked(0, 0);
        } else {
            registerScreenOffEventLocked();
        }
    }

    void dumpImpl(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current UI Mode Service state:");
            pw.print("  mDockState="); pw.print(mDockState);
            pw.print(" mLastBroadcastState="); pw.println(mLastBroadcastState);

            pw.print("  mNightMode="); pw.print(mNightMode); pw.print(" (");
            pw.print(Shell.nightModeToStr(mNightMode)); pw.print(") ");
            pw.print(" mOverrideOn/Off="); pw.print(mOverrideNightModeOn);
            pw.print("/"); pw.print(mOverrideNightModeOff);

            pw.print(" mNightModeLocked="); pw.println(mNightModeLocked);

            pw.print("  mCarModeEnabled="); pw.print(mCarModeEnabled);
            pw.print(" (carModeApps=");
            for (Map.Entry<Integer, String> entry : mCarModePackagePriority.entrySet()) {
                pw.print(entry.getKey());
                pw.print(":");
                pw.print(entry.getValue());
                pw.print(" ");
            }
            pw.println("");
            pw.print(" waitScreenOff="); pw.print(mWaitForScreenOff);
            pw.print(" mComputedNightMode="); pw.print(mComputedNightMode);
            pw.print(" customStart="); pw.print(mCustomAutoNightModeStartMilliseconds);
            pw.print(" customEnd"); pw.print(mCustomAutoNightModeEndMilliseconds);
            pw.print(" mCarModeEnableFlags="); pw.print(mCarModeEnableFlags);
            pw.print(" mEnableCarDockLaunch="); pw.println(mEnableCarDockLaunch);

            pw.print("  mCurUiMode=0x"); pw.print(Integer.toHexString(mCurUiMode));
            pw.print(" mUiModeLocked="); pw.print(mUiModeLocked);
            pw.print(" mSetUiMode=0x"); pw.println(Integer.toHexString(mSetUiMode));

            pw.print("  mHoldingConfiguration="); pw.print(mHoldingConfiguration);
            pw.print(" mSystemReady="); pw.println(mSystemReady);

            if (mTwilightManager != null) {
                // We may not have a TwilightManager.
                pw.print("  mTwilightService.getLastTwilightState()=");
                pw.println(mTwilightManager.getLastTwilightState());
            }
        }
    }

    /**
     * Updates the global car mode state.
     * The device is considered to be in car mode if there exists an app at any priority level which
     * has entered car mode.
     *
     * @param enabled {@code true} if the caller wishes to enable car mode, {@code false} otherwise.
     * @param flags Flags used when enabling/disabling car mode.
     * @param priority The priority level for entering or exiting car mode; defaults to
     *                 {@link UiModeManager#DEFAULT_PRIORITY} for callers using
     *                 {@link UiModeManager#enableCarMode(int)}.  Callers using
     *                 {@link UiModeManager#enableCarMode(int, int)} may specify a priority.
     * @param packageName The package name of the app which initiated the request to enable or
     *                    disable car mode.
     */
    void setCarModeLocked(boolean enabled, int flags, int priority, String packageName) {
        if (enabled) {
            enableCarMode(priority, packageName);
        } else {
            disableCarMode(flags, priority, packageName);
        }
        boolean isCarModeNowEnabled = isCarModeEnabled();

        if (mCarModeEnabled != isCarModeNowEnabled) {
            mCarModeEnabled = isCarModeNowEnabled;
            // When exiting car mode, restore night mode from settings
            if (!isCarModeNowEnabled) {
                Context context = getContext();
                updateNightModeFromSettingsLocked(context,
                        context.getResources(),
                        UserHandle.getCallingUserId());
            }
        }
        mCarModeEnableFlags = flags;
    }

    /**
     * Handles disabling car mode.
     * <p>
     * Car mode can be disabled at a priority level if any of the following is true:
     * 1. The priority being disabled is the {@link UiModeManager#DEFAULT_PRIORITY}.
     * 2. The priority level is enabled and the caller is the app who originally enabled it.
     * 3. The {@link UiModeManager#DISABLE_CAR_MODE_ALL_PRIORITIES} flag was specified, meaning all
     *    car mode priorities are disabled.
     *
     * @param flags Car mode flags.
     * @param priority The priority level at which to disable car mode.
     * @param packageName The calling package which initiated the request.
     */
    private void disableCarMode(int flags, int priority, String packageName) {
        boolean isDisableAll = (flags & UiModeManager.DISABLE_CAR_MODE_ALL_PRIORITIES) != 0;
        boolean isPriorityTracked = mCarModePackagePriority.keySet().contains(priority);
        boolean isDefaultPriority = priority == UiModeManager.DEFAULT_PRIORITY;
        boolean isChangeAllowed =
                // Anyone can disable the default priority.
                isDefaultPriority
                // If priority was enabled, only enabling package can disable it.
                || isPriorityTracked && mCarModePackagePriority.get(priority).equals(
                packageName)
                // Disable all priorities flag can disable all regardless.
                || isDisableAll;
        if (isChangeAllowed) {
            Slog.d(TAG, "disableCarMode: disabling, priority=" + priority
                    + ", packageName=" + packageName);
            if (isDisableAll) {
                Set<Map.Entry<Integer, String>> entries =
                        new ArraySet<>(mCarModePackagePriority.entrySet());
                mCarModePackagePriority.clear();

                for (Map.Entry<Integer, String> entry : entries) {
                    notifyCarModeDisabled(entry.getKey(), entry.getValue());
                }
            } else {
                mCarModePackagePriority.remove(priority);
                notifyCarModeDisabled(priority, packageName);
            }
        }
    }

    /**
     * Handles enabling car mode.
     * <p>
     * Car mode can be enabled at any priority if it has not already been enabled at that priority.
     * The calling package is tracked for the first app which enters priority at the
     * {@link UiModeManager#DEFAULT_PRIORITY}, though any app can disable it at that priority.
     *
     * @param priority The priority for enabling car mode.
     * @param packageName The calling package which initiated the request.
     */
    private void enableCarMode(int priority, String packageName) {
        boolean isPriorityTracked = mCarModePackagePriority.containsKey(priority);
        boolean isPackagePresent = mCarModePackagePriority.containsValue(packageName);
        if (!isPriorityTracked && !isPackagePresent) {
            Slog.d(TAG, "enableCarMode: enabled at priority=" + priority + ", packageName="
                    + packageName);
            mCarModePackagePriority.put(priority, packageName);
            notifyCarModeEnabled(priority, packageName);
        } else {
            Slog.d(TAG, "enableCarMode: car mode at priority " + priority + " already enabled.");
        }

    }

    private void notifyCarModeEnabled(int priority, String packageName) {
        Intent intent = new Intent(UiModeManager.ACTION_ENTER_CAR_MODE_PRIORITIZED);
        intent.putExtra(UiModeManager.EXTRA_CALLING_PACKAGE, packageName);
        intent.putExtra(UiModeManager.EXTRA_PRIORITY, priority);
        getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.HANDLE_CAR_MODE_CHANGES);
    }

    private void notifyCarModeDisabled(int priority, String packageName) {
        Intent intent = new Intent(UiModeManager.ACTION_EXIT_CAR_MODE_PRIORITIZED);
        intent.putExtra(UiModeManager.EXTRA_CALLING_PACKAGE, packageName);
        intent.putExtra(UiModeManager.EXTRA_PRIORITY, priority);
        getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                android.Manifest.permission.HANDLE_CAR_MODE_CHANGES);
    }

    /**
     * Determines if car mode is enabled at any priority level.
     * @return {@code true} if car mode is enabled, {@code false} otherwise.
     */
    private boolean isCarModeEnabled() {
        return mCarModePackagePriority.size() > 0;
    }

    private void updateDockState(int newState) {
        synchronized (mLock) {
            if (newState != mDockState) {
                mDockState = newState;
                setCarModeLocked(mDockState == Intent.EXTRA_DOCK_STATE_CAR, 0,
                        UiModeManager.DEFAULT_PRIORITY, "" /* packageName */);
                if (mSystemReady) {
                    updateLocked(UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME, 0);
                }
            }
        }
    }

    private static boolean isDeskDockState(int state) {
        switch (state) {
            case Intent.EXTRA_DOCK_STATE_DESK:
            case Intent.EXTRA_DOCK_STATE_LE_DESK:
            case Intent.EXTRA_DOCK_STATE_HE_DESK:
                return true;
            default:
                return false;
        }
    }

    private void persistNightMode(int user) {
        // Only persist setting if not in car mode
        if (mCarModeEnabled || mCar) return;
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.UI_NIGHT_MODE, mNightMode, user);
        Secure.putLongForUser(getContext().getContentResolver(),
                Secure.DARK_THEME_CUSTOM_START_TIME,
                mCustomAutoNightModeStartMilliseconds.toNanoOfDay() / 1000, user);
        Secure.putLongForUser(getContext().getContentResolver(),
                Secure.DARK_THEME_CUSTOM_END_TIME,
                mCustomAutoNightModeEndMilliseconds.toNanoOfDay() / 1000, user);
    }

    private void persistNightModeOverrides(int user) {
        // Only persist setting if not in car mode
        if (mCarModeEnabled || mCar) return;
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.UI_NIGHT_MODE_OVERRIDE_ON, mOverrideNightModeOn ? 1 : 0, user);
        Secure.putIntForUser(getContext().getContentResolver(),
                Secure.UI_NIGHT_MODE_OVERRIDE_OFF, mOverrideNightModeOff ? 1 : 0, user);
    }

    private void updateConfigurationLocked() {
        int uiMode = mDefaultUiModeType;
        if (mUiModeLocked) {
            // no-op, keeps default one
        } else if (mTelevision) {
            uiMode = Configuration.UI_MODE_TYPE_TELEVISION;
        } else if (mWatch) {
            uiMode = Configuration.UI_MODE_TYPE_WATCH;
        } else if (mCarModeEnabled) {
            uiMode = Configuration.UI_MODE_TYPE_CAR;
        } else if (isDeskDockState(mDockState)) {
            uiMode = Configuration.UI_MODE_TYPE_DESK;
        } else if (mVrHeadset) {
            uiMode = Configuration.UI_MODE_TYPE_VR_HEADSET;
        }

        if (mNightMode == MODE_NIGHT_YES || mNightMode == UiModeManager.MODE_NIGHT_NO) {
            updateComputedNightModeLocked(mNightMode == MODE_NIGHT_YES);
        }

        if (mNightMode == MODE_NIGHT_AUTO) {
            boolean activateNightMode = mComputedNightMode;
            if (mTwilightManager != null) {
                mTwilightManager.registerListener(mTwilightListener, mHandler);
                final TwilightState lastState = mTwilightManager.getLastTwilightState();
                activateNightMode = lastState == null ? mComputedNightMode : lastState.isNight();
            }
            updateComputedNightModeLocked(activateNightMode);
        } else {
            if (mTwilightManager != null) {
                mTwilightManager.unregisterListener(mTwilightListener);
            }
        }

        if (mNightMode == MODE_NIGHT_CUSTOM) {
            registerTimeChangeEvent();
            final boolean activate = computeCustomNightMode();
            updateComputedNightModeLocked(activate);
            scheduleNextCustomTimeListener();
        } else {
            unregisterTimeChangeEvent();
        }

        // Override night mode in power save mode if not in car mode
        if (mPowerSave && !mCarModeEnabled && !mCar) {
            uiMode &= ~Configuration.UI_MODE_NIGHT_NO;
            uiMode |= Configuration.UI_MODE_NIGHT_YES;
        } else {
            uiMode = getComputedUiModeConfiguration(uiMode);
        }

        if (LOG) {
            Slog.d(TAG,
                    "updateConfigurationLocked: mDockState=" + mDockState
                    + "; mCarMode=" + mCarModeEnabled
                    + "; mNightMode=" + mNightMode
                    + "; uiMode=" + uiMode);
        }

        mCurUiMode = uiMode;
        if (!mHoldingConfiguration && (!mWaitForScreenOff || mPowerSave)) {
            mConfiguration.uiMode = uiMode;
        }
    }

    @UiModeManager.NightMode
    private int getComputedUiModeConfiguration(@UiModeManager.NightMode int uiMode) {
        uiMode |= mComputedNightMode ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        uiMode &= mComputedNightMode ? ~Configuration.UI_MODE_NIGHT_NO
                : ~Configuration.UI_MODE_NIGHT_YES;
        return uiMode;
    }

    private boolean computeCustomNightMode() {
        return isTimeBetween(LocalTime.now(),
                mCustomAutoNightModeStartMilliseconds,
                mCustomAutoNightModeEndMilliseconds);
    }

    private void applyConfigurationExternallyLocked() {
        if (mSetUiMode != mConfiguration.uiMode) {
            mSetUiMode = mConfiguration.uiMode;
            // load splash screen instead of screenshot
            mWindowManager.clearSnapshotCache();
            try {
                ActivityTaskManager.getService().updateConfiguration(mConfiguration);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure communicating with activity manager", e);
            } catch (SecurityException e) {
                Slog.e(TAG, "Activity does not have the ", e);
            }
        }
    }

    private boolean shouldApplyAutomaticChangesImmediately() {
        return mCar || !mPowerManager.isInteractive();
    }

    private void scheduleNextCustomTimeListener() {
        cancelCustomAlarm();
        LocalDateTime now = LocalDateTime.now();
        final boolean active = computeCustomNightMode();
        final LocalDateTime next = active
                ? getDateTimeAfter(mCustomAutoNightModeEndMilliseconds, now)
                : getDateTimeAfter(mCustomAutoNightModeStartMilliseconds, now);
        final long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        mAlarmManager.setExact(AlarmManager.RTC, millis, TAG, mCustomTimeListener, null);
    }

    private LocalDateTime getDateTimeAfter(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.toLocalDate(), localTime);

        // Check if the local time has passed, if so return the same time tomorrow.
        return ldt.isBefore(compareTime) ? ldt.plusDays(1) : ldt;
    }

    void updateLocked(int enableFlags, int disableFlags) {
        String action = null;
        String oldAction = null;
        if (mLastBroadcastState == Intent.EXTRA_DOCK_STATE_CAR) {
            adjustStatusBarCarModeLocked();
            oldAction = UiModeManager.ACTION_EXIT_CAR_MODE;
        } else if (isDeskDockState(mLastBroadcastState)) {
            oldAction = UiModeManager.ACTION_EXIT_DESK_MODE;
        }

        if (mCarModeEnabled) {
            if (mLastBroadcastState != Intent.EXTRA_DOCK_STATE_CAR) {
                adjustStatusBarCarModeLocked();
                if (oldAction != null) {
                    sendForegroundBroadcastToAllUsers(oldAction);
                }
                mLastBroadcastState = Intent.EXTRA_DOCK_STATE_CAR;
                action = UiModeManager.ACTION_ENTER_CAR_MODE;
            }
        } else if (isDeskDockState(mDockState)) {
            if (!isDeskDockState(mLastBroadcastState)) {
                if (oldAction != null) {
                    sendForegroundBroadcastToAllUsers(oldAction);
                }
                mLastBroadcastState = mDockState;
                action = UiModeManager.ACTION_ENTER_DESK_MODE;
            }
        } else {
            mLastBroadcastState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
            action = oldAction;
        }

        if (action != null) {
            if (LOG) {
                Slog.v(TAG, String.format(
                    "updateLocked: preparing broadcast: action=%s enable=0x%08x disable=0x%08x",
                    action, enableFlags, disableFlags));
            }

            // Send the ordered broadcast; the result receiver will receive after all
            // broadcasts have been sent. If any broadcast receiver changes the result
            // code from the initial value of RESULT_OK, then the result receiver will
            // not launch the corresponding dock application. This gives apps a chance
            // to override the behavior and stay in their app even when the device is
            // placed into a dock.
            Intent intent = new Intent(action);
            intent.putExtra("enableFlags", enableFlags);
            intent.putExtra("disableFlags", disableFlags);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            getContext().sendOrderedBroadcastAsUser(intent, UserHandle.CURRENT, null,
                    mResultReceiver, null, Activity.RESULT_OK, null, null);

            // Attempting to make this transition a little more clean, we are going
            // to hold off on doing a configuration change until we have finished
            // the broadcast and started the home activity.
            mHoldingConfiguration = true;
            updateConfigurationLocked();
        } else {
            String category = null;
            if (mCarModeEnabled) {
                if (mEnableCarDockLaunch
                        && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                    category = Intent.CATEGORY_CAR_DOCK;
                }
            } else if (isDeskDockState(mDockState)) {
                if (ENABLE_LAUNCH_DESK_DOCK_APP
                        && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                    category = Intent.CATEGORY_DESK_DOCK;
                }
            } else {
                if ((disableFlags & UiModeManager.DISABLE_CAR_MODE_GO_HOME) != 0) {
                    category = Intent.CATEGORY_HOME;
                }
            }

            if (LOG) {
                Slog.v(TAG, "updateLocked: null action, mDockState="
                        + mDockState +", category=" + category);
            }

            sendConfigurationAndStartDreamOrDockAppLocked(category);
        }

        // keep screen on when charging and in car mode
        boolean keepScreenOn = mCharging &&
                ((mCarModeEnabled && mCarModeKeepsScreenOn &&
                (mCarModeEnableFlags & UiModeManager.ENABLE_CAR_MODE_ALLOW_SLEEP) == 0) ||
                (mCurUiMode == Configuration.UI_MODE_TYPE_DESK && mDeskModeKeepsScreenOn));
        if (keepScreenOn != mWakeLock.isHeld()) {
            if (keepScreenOn) {
                mWakeLock.acquire();
            } else {
                mWakeLock.release();
            }
        }
    }

    private void sendForegroundBroadcastToAllUsers(String action) {
        getContext().sendBroadcastAsUser(new Intent(action)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), UserHandle.ALL);
    }

    private void updateAfterBroadcastLocked(String action, int enableFlags, int disableFlags) {
        // Launch a dock activity
        String category = null;
        if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(action)) {
            // Only launch car home when car mode is enabled and the caller
            // has asked us to switch to it.
            if (mEnableCarDockLaunch
                    && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                category = Intent.CATEGORY_CAR_DOCK;
            }
        } else if (UiModeManager.ACTION_ENTER_DESK_MODE.equals(action)) {
            // Only launch car home when desk mode is enabled and the caller
            // has asked us to switch to it.  Currently re-using the car
            // mode flag since we don't have a formal API for "desk mode".
            if (ENABLE_LAUNCH_DESK_DOCK_APP
                    && (enableFlags & UiModeManager.ENABLE_CAR_MODE_GO_CAR_HOME) != 0) {
                category = Intent.CATEGORY_DESK_DOCK;
            }
        } else {
            // Launch the standard home app if requested.
            if ((disableFlags & UiModeManager.DISABLE_CAR_MODE_GO_HOME) != 0) {
                category = Intent.CATEGORY_HOME;
            }
        }

        if (LOG) {
            Slog.v(TAG, String.format(
                    "Handling broadcast result for action %s: enable=0x%08x, disable=0x%08x, "
                            + "category=%s",
                    action, enableFlags, disableFlags, category));
        }

        sendConfigurationAndStartDreamOrDockAppLocked(category);
    }

    private void sendConfigurationAndStartDreamOrDockAppLocked(String category) {
        // Update the configuration but don't send it yet.
        mHoldingConfiguration = false;
        updateConfigurationLocked();

        // Start the dock app, if there is one.
        boolean dockAppStarted = false;
        if (category != null) {
            // Now we are going to be careful about switching the
            // configuration and starting the activity -- we need to
            // do this in a specific order under control of the
            // activity manager, to do it cleanly.  So compute the
            // new config, but don't set it yet, and let the
            // activity manager take care of both the start and config
            // change.
            Intent homeIntent = buildHomeIntent(category);
            if (Sandman.shouldStartDockApp(getContext(), homeIntent)) {
                try {
                    int result = ActivityTaskManager.getService().startActivityWithConfig(
                            null, getContext().getBasePackageName(),
                            getContext().getAttributionTag(), homeIntent, null, null, null, 0, 0,
                            mConfiguration, null, UserHandle.USER_CURRENT);
                    if (ActivityManager.isStartResultSuccessful(result)) {
                        dockAppStarted = true;
                    } else if (result != ActivityManager.START_INTENT_NOT_RESOLVED) {
                        Slog.e(TAG, "Could not start dock app: " + homeIntent
                                + ", startActivityWithConfig result " + result);
                    }
                } catch (RemoteException ex) {
                    Slog.e(TAG, "Could not start dock app: " + homeIntent, ex);
                }
            }
        }

        // Send the new configuration.
        applyConfigurationExternallyLocked();

        // If we did not start a dock app, then start dreaming if supported.
        if (category != null && !dockAppStarted) {
            Sandman.startDreamWhenDockedIfAppropriate(getContext());
        }
    }

    private void adjustStatusBarCarModeLocked() {
        final Context context = getContext();
        if (mStatusBarManager == null) {
            mStatusBarManager = (StatusBarManager)
                    context.getSystemService(Context.STATUS_BAR_SERVICE);
        }

        // Fear not: StatusBarManagerService manages a list of requests to disable
        // features of the status bar; these are ORed together to form the
        // active disabled list. So if (for example) the device is locked and
        // the status bar should be totally disabled, the calls below will
        // have no effect until the device is unlocked.
        if (mStatusBarManager != null) {
            mStatusBarManager.disable(mCarModeEnabled
                    ? StatusBarManager.DISABLE_NOTIFICATION_TICKER
                    : StatusBarManager.DISABLE_NONE);
        }

        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        if (mNotificationManager != null) {
            if (mCarModeEnabled) {
                Intent carModeOffIntent = new Intent(context, DisableCarModeActivity.class);

                Notification.Builder n =
                        new Notification.Builder(context, SystemNotificationChannels.CAR_MODE)
                        .setSmallIcon(R.drawable.stat_notify_car_mode)
                        .setDefaults(Notification.DEFAULT_LIGHTS)
                        .setOngoing(true)
                        .setWhen(0)
                        .setColor(context.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setContentTitle(
                                context.getString(R.string.car_mode_disable_notification_title))
                        .setContentText(
                                context.getString(R.string.car_mode_disable_notification_message))
                        .setContentIntent(
                                PendingIntent.getActivityAsUser(context, 0,
                                        carModeOffIntent, 0,
                                        null, UserHandle.CURRENT));
                mNotificationManager.notifyAsUser(null,
                        SystemMessage.NOTE_CAR_MODE_DISABLE, n.build(), UserHandle.ALL);
            } else {
                mNotificationManager.cancelAsUser(null,
                        SystemMessage.NOTE_CAR_MODE_DISABLE, UserHandle.ALL);
            }
        }
    }

    private void updateComputedNightModeLocked(boolean activate) {
        mComputedNightMode = activate;
        if (mNightMode == MODE_NIGHT_YES || mNightMode == UiModeManager.MODE_NIGHT_NO) {
            return;
        }
        if (mOverrideNightModeOn && !mComputedNightMode) {
            mComputedNightMode = true;
            return;
        }
        if (mOverrideNightModeOff && mComputedNightMode) {
            mComputedNightMode = false;
            return;
        }
        resetNightModeOverrideLocked();
    }

    private boolean resetNightModeOverrideLocked() {
        if (mOverrideNightModeOff || mOverrideNightModeOn) {
            mOverrideNightModeOff = false;
            mOverrideNightModeOn = false;
            persistNightModeOverrides(mOverrideNightModeUser);
            mOverrideNightModeUser = USER_SYSTEM;
            return true;
        }
        return false;
    }

    private void registerVrStateListener() {
        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
        try {
            if (vrManager != null) {
                vrManager.registerListener(mVrStateCallbacks);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register VR mode state listener: " + e);
        }
    }

    /**
     * Handles "adb shell" commands.
     */
    private static class Shell extends ShellCommand {
        public static final String NIGHT_MODE_STR_YES = "yes";
        public static final String NIGHT_MODE_STR_NO = "no";
        public static final String NIGHT_MODE_STR_AUTO = "auto";
        public static final String NIGHT_MODE_STR_CUSTOM = "custom";
        public static final String NIGHT_MODE_STR_UNKNOWN = "unknown";
        private final IUiModeManager mInterface;

        Shell(IUiModeManager iface) {
            mInterface = iface;
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("UiModeManager service (uimode) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  night [yes|no|auto|custom]");
            pw.println("    Set or read night mode.");
            pw.println("  time [start|end] <ISO time>");
            pw.println("    Set custom start/end schedule time"
                    + " (night mode must be set to custom to apply).");
        }

        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }

            try {
                switch (cmd) {
                    case "night":
                        return handleNightMode();
                    case "time":
                        return handleCustomTime();
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (RemoteException e) {
                final PrintWriter err = getErrPrintWriter();
                err.println("Remote exception: " + e);
            }
            return -1;
        }

        private int handleCustomTime() throws RemoteException {
            final String modeStr = getNextArg();
            if (modeStr == null) {
                printCustomTime();
                return 0;
            }
            switch (modeStr) {
                case "start":
                    final String start = getNextArg();
                    mInterface.setCustomNightModeStart(toMilliSeconds(LocalTime.parse(start)));
                    return 0;
                case "end":
                    final String end = getNextArg();
                    mInterface.setCustomNightModeEnd(toMilliSeconds(LocalTime.parse(end)));
                    return 0;
                default:
                    getErrPrintWriter().println("command must be in [start|end]");
                    return -1;
            }
        }

        private void printCustomTime() throws RemoteException {
            getOutPrintWriter().println("start " + fromMilliseconds(
                    mInterface.getCustomNightModeStart()).toString());
            getOutPrintWriter().println("end " + fromMilliseconds(
                    mInterface.getCustomNightModeEnd()).toString());
        }

        private int handleNightMode() throws RemoteException {
            final PrintWriter err = getErrPrintWriter();
            final String modeStr = getNextArg();
            if (modeStr == null) {
                printCurrentNightMode();
                return 0;
            }

            final int mode = strToNightMode(modeStr);
            if (mode >= 0) {
                mInterface.setNightMode(mode);
                printCurrentNightMode();
                return 0;
            } else {
                err.println("Error: mode must be '" + NIGHT_MODE_STR_YES + "', '"
                        + NIGHT_MODE_STR_NO + "', or '" + NIGHT_MODE_STR_AUTO
                        +  "', or '" + NIGHT_MODE_STR_CUSTOM + "'");
                return -1;
            }
        }

        private void printCurrentNightMode() throws RemoteException {
            final PrintWriter pw = getOutPrintWriter();
            final int currMode = mInterface.getNightMode();
            final String currModeStr = nightModeToStr(currMode);
            pw.println("Night mode: " + currModeStr);
        }

        private static String nightModeToStr(int mode) {
            switch (mode) {
                case UiModeManager.MODE_NIGHT_YES:
                    return NIGHT_MODE_STR_YES;
                case UiModeManager.MODE_NIGHT_NO:
                    return NIGHT_MODE_STR_NO;
                case UiModeManager.MODE_NIGHT_AUTO:
                    return NIGHT_MODE_STR_AUTO;
                case MODE_NIGHT_CUSTOM:
                    return NIGHT_MODE_STR_CUSTOM;
                default:
                    return NIGHT_MODE_STR_UNKNOWN;
            }
        }

        private static int strToNightMode(String modeStr) {
            switch (modeStr) {
                case NIGHT_MODE_STR_YES:
                    return UiModeManager.MODE_NIGHT_YES;
                case NIGHT_MODE_STR_NO:
                    return UiModeManager.MODE_NIGHT_NO;
                case NIGHT_MODE_STR_AUTO:
                    return UiModeManager.MODE_NIGHT_AUTO;
                case NIGHT_MODE_STR_CUSTOM:
                    return UiModeManager.MODE_NIGHT_CUSTOM;
                default:
                    return -1;
            }
        }
    }

    public final class LocalService extends UiModeManagerInternal {

        @Override
        public boolean isNightMode() {
            synchronized (mLock) {
                final boolean isIt = (mConfiguration.uiMode & Configuration.UI_MODE_NIGHT_YES) != 0;
                if (LOG) {
                    Slog.d(TAG,
                        "LocalService.isNightMode(): mNightMode=" + mNightMode
                        + "; mComputedNightMode=" + mComputedNightMode
                        + "; uiMode=" + mConfiguration.uiMode
                        + "; isIt=" + isIt);
                }
                return isIt;
            }
        }
    }
}
