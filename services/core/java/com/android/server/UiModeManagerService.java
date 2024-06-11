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

import static android.app.Flags.modesApi;
import static android.app.Flags.enableNightModeBinderCache;
import static android.app.UiModeManager.ContrastUtils.CONTRAST_DEFAULT_VALUE;
import static android.app.UiModeManager.DEFAULT_PRIORITY;
import static android.app.UiModeManager.MODE_ATTENTION_THEME_OVERLAY_OFF;
import static android.app.UiModeManager.MODE_NIGHT_AUTO;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM_TYPE_BEDTIME;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM_TYPE_SCHEDULE;
import static android.app.UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN;
import static android.app.UiModeManager.MODE_NIGHT_NO;
import static android.app.UiModeManager.MODE_NIGHT_YES;
import static android.app.UiModeManager.PROJECTION_TYPE_AUTOMOTIVE;
import static android.app.UiModeManager.PROJECTION_TYPE_NONE;
import static android.os.UserHandle.USER_SYSTEM;
import static android.os.UserHandle.getCallingUserId;
import static android.os.UserManager.isVisibleBackgroundUsersEnabled;
import static android.provider.Settings.Secure.CONTRAST_LEVEL;
import static android.util.TimeUtils.isTimeBetween;

import static com.android.internal.util.FunctionalUtils.ignoreRemoteException;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.IOnProjectionStateChangedListener;
import android.app.IUiModeManager;
import android.app.IUiModeManagerCallback;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.UiModeManager;
import android.app.UiModeManager.AttentionModeThemeOverlayType;
import android.app.UiModeManager.NightModeCustomReturnType;
import android.app.UiModeManager.NightModeCustomType;
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
import android.os.IBinder;
import android.os.PermissionEnforcer;
import android.os.PowerManager;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.dreams.DreamManagerInternal;
import android.service.dreams.Sandman;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.DisableCarModeActivity;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.server.pm.UserManagerService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class UiModeManagerService extends SystemService {
    private static final String TAG = UiModeManager.class.getSimpleName();
    private static final boolean LOG = false;

    // Enable launching of applications when entering the dock.
    private static final boolean ENABLE_LAUNCH_DESK_DOCK_APP = true;
    private static final String SYSTEM_PROPERTY_DEVICE_THEME = "persist.sys.theme";
    @VisibleForTesting
    public static final Set<Integer> SUPPORTED_NIGHT_MODE_CUSTOM_TYPES = new ArraySet(
            new Integer[]{MODE_NIGHT_CUSTOM_TYPE_SCHEDULE, MODE_NIGHT_CUSTOM_TYPE_BEDTIME});

    private final Injector mInjector;
    private final Object mLock = new Object();

    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private int mLastBroadcastState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private final NightMode mNightMode = new NightMode(){
        private int mNightModeValue = UiModeManager.MODE_NIGHT_NO;

        @Override
        public int get() {
            return mNightModeValue;
        }

        @Override
        public void set(int mode) {
            mNightModeValue = mode;
            if (enableNightModeBinderCache()) {
                UiModeManager.invalidateNightModeCache();
            }
        }
    };
    private int mNightModeCustomType = UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN;
    private int mAttentionModeThemeOverlay = UiModeManager.MODE_ATTENTION_THEME_OVERLAY_OFF;
    private final LocalTime DEFAULT_CUSTOM_NIGHT_START_TIME = LocalTime.of(22, 0);
    private final LocalTime DEFAULT_CUSTOM_NIGHT_END_TIME = LocalTime.of(6, 0);
    private LocalTime mCustomAutoNightModeStartMilliseconds = DEFAULT_CUSTOM_NIGHT_START_TIME;
    private LocalTime mCustomAutoNightModeEndMilliseconds = DEFAULT_CUSTOM_NIGHT_END_TIME;

    private Map<Integer, String> mCarModePackagePriority = new HashMap<>();
    private boolean mCarModeEnabled = false;
    private boolean mCharging = false;
    private boolean mPowerSave = false;
    // Do not change configuration now. wait until the device is inactive (eg screen off, dreaming)
    // This prevents jank and activity restart when the user
    // is actively using the device
    private boolean mWaitForDeviceInactive = false;
    private int mDefaultUiModeType;
    private boolean mCarModeKeepsScreenOn;
    private boolean mDeskModeKeepsScreenOn;
    private boolean mTelevision;
    private boolean mCar;
    private boolean mWatch;
    private boolean mVrHeadset;
    private boolean mComputedNightMode;
    private boolean mLastBedtimeRequestedNightMode = false;
    private int mCarModeEnableFlags;
    private boolean mSetupWizardComplete;

    // flag set by resource, whether to start dream immediately upon docking even if unlocked.
    private boolean mStartDreamImmediatelyOnDock = true;
    // flag set by resource, whether to disable dreams when ambient mode suppression is enabled.
    private boolean mDreamsDisabledByAmbientModeSuppression = false;
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
    private ActivityTaskManagerInternal mActivityTaskManager;
    private AlarmManager mAlarmManager;
    private PowerManager mPowerManager;
    private KeyguardManager mKeyguardManager;

    // In automatic scheduling, the user is able
    // to override the computed night mode until the two match
    // Example: Activate dark mode in the day time until sunrise the next day
    private boolean mOverrideNightModeOn;
    private boolean mOverrideNightModeOff;
    private int mOverrideNightModeUser = USER_SYSTEM;

    private PowerManager.WakeLock mWakeLock;

    private final LocalService mLocalService = new LocalService();
    private PowerManagerInternal mLocalPowerManager;
    private DreamManagerInternal mDreamManagerInternal;

    private final IUiModeManager.Stub mService;

    @GuardedBy("mLock")
    private final SparseArray<RemoteCallbackList<IUiModeManagerCallback>> mUiModeManagerCallbacks =
            new SparseArray<>();

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<List<ProjectionHolder>> mProjectionHolders;
    @GuardedBy("mLock")
    @Nullable
    private SparseArray<RemoteCallbackList<IOnProjectionStateChangedListener>> mProjectionListeners;

    @GuardedBy("mLock")
    private final SparseArray<Float> mContrasts = new SparseArray<>();

    public UiModeManagerService(Context context) {
        this(context, /* setupWizardComplete= */ false, /* tm= */ null, new Injector());
    }

    @VisibleForTesting
    protected UiModeManagerService(Context context, boolean setupWizardComplete,
            TwilightManager tm, Injector injector) {
        super(context);
        mService = new Stub(context);
        mConfiguration.setToDefaults();
        mSetupWizardComplete = setupWizardComplete;
        mTwilightManager = tm;
        mInjector = injector;
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
                if (mNightMode.get() == UiModeManager.MODE_NIGHT_AUTO && mSystemReady) {
                    if (shouldApplyAutomaticChangesImmediately()) {
                        updateLocked(0, 0);
                    } else {
                        registerDeviceInactiveListenerLocked();
                    }
                }
            }
        }
    };

    /**
     * DO NOT USE DIRECTLY
     * see register registerScreenOffEvent and unregisterScreenOffEvent
     */
    private final BroadcastReceiver mDeviceInactiveListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                // must unregister first before updating
                unregisterDeviceInactiveListenerLocked();
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

    private final ContentObserver mContrastObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            synchronized (mLock) {
                if (updateContrastLocked()) {
                    float contrast = getContrastLocked();
                    mUiModeManagerCallbacks.get(mCurrentUser, new RemoteCallbackList<>())
                            .broadcast(ignoreRemoteException(
                                    callback -> callback.notifyContrastChanged(contrast)));
                }
            }
        }
    };

    private void updateSystemProperties() {
        int mode = Secure.getIntForUser(getContext().getContentResolver(), Secure.UI_NIGHT_MODE,
                mNightMode.get(), 0);
        if (mode == MODE_NIGHT_AUTO || mode == MODE_NIGHT_CUSTOM) {
            mode = MODE_NIGHT_YES;
        }
        SystemProperties.set(SYSTEM_PROPERTY_DEVICE_THEME, Integer.toString(mode));
    }

    @VisibleForTesting
    void setStartDreamImmediatelyOnDock(boolean startDreamImmediatelyOnDock) {
        mStartDreamImmediatelyOnDock = startDreamImmediatelyOnDock;
    }

    @VisibleForTesting
    void setDreamsDisabledByAmbientModeSuppression(boolean disabledByAmbientModeSuppression) {
        mDreamsDisabledByAmbientModeSuppression = disabledByAmbientModeSuppression;
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        mCurrentUser = to.getUserIdentifier();
        if (mNightMode.get() == MODE_NIGHT_AUTO) persistComputedNightMode(from.getUserIdentifier());
        getContext().getContentResolver().unregisterContentObserver(mSetupWizardObserver);
        verifySetupWizardCompleted();
        synchronized (mLock) {
            updateNightModeFromSettingsLocked(getContext(), getContext().getResources(),
                    to.getUserIdentifier());
            updateLocked(0, 0);
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                final Context context = getContext();
                mSystemReady = true;
                mKeyguardManager = context.getSystemService(KeyguardManager.class);
                mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                mWakeLock = mPowerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);
                mWindowManager = LocalServices.getService(WindowManagerInternal.class);
                mActivityTaskManager = LocalServices.getService(ActivityTaskManagerInternal.class);
                mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
                TwilightManager twilightManager = getLocalService(TwilightManager.class);
                if (twilightManager != null) mTwilightManager = twilightManager;
                mLocalPowerManager =
                        LocalServices.getService(PowerManagerInternal.class);
                mDreamManagerInternal = LocalServices.getService(DreamManagerInternal.class);
                initPowerSave();
                mCarModeEnabled = mDockState == Intent.EXTRA_DOCK_STATE_CAR;
                registerVrStateListener();
                // register listeners
                context.getContentResolver()
                        .registerContentObserver(Secure.getUriFor(Secure.UI_NIGHT_MODE),
                                false, mDarkThemeObserver, 0);
                context.getContentResolver().registerContentObserver(
                        Secure.getUriFor(Secure.CONTRAST_LEVEL), false,
                        mContrastObserver, UserHandle.USER_ALL);
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
        mNightMode.set(res.getInteger(
                com.android.internal.R.integer.config_defaultNightMode));
        mStartDreamImmediatelyOnDock = res.getBoolean(
                com.android.internal.R.bool.config_startDreamImmediatelyOnDock);
        mDreamsDisabledByAmbientModeSuppression = res.getBoolean(
                com.android.internal.R.bool.config_dreamsDisabledByAmbientModeSuppressionConfig);
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
            if (mNightMode.get() == MODE_NIGHT_AUTO) {
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
        if (mNightMode.get() != MODE_NIGHT_CUSTOM) return;
        if (shouldApplyAutomaticChangesImmediately()) {
            updateLocked(0, 0);
        } else {
            registerDeviceInactiveListenerLocked();
        }
        scheduleNextCustomTimeListener();
    }

    /**
     * Updates the night mode setting in Settings.Secure
     *
     * @param context A valid context
     * @param res     A valid resource object
     * @param userId  The user to update the setting for
     */
    private void updateNightModeFromSettingsLocked(Context context, Resources res, int userId) {
        if (mCarModeEnabled || mCar) {
            return;
        }
        if (mSetupWizardComplete) {
            mNightMode.set(Secure.getIntForUser(context.getContentResolver(),
                    Secure.UI_NIGHT_MODE, res.getInteger(
                            com.android.internal.R.integer.config_defaultNightMode), userId));
            mNightModeCustomType = Secure.getIntForUser(context.getContentResolver(),
                    Secure.UI_NIGHT_MODE_CUSTOM_TYPE, MODE_NIGHT_CUSTOM_TYPE_UNKNOWN, userId);
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
            if (mNightMode.get() == MODE_NIGHT_AUTO) {
                mComputedNightMode = Secure.getIntForUser(context.getContentResolver(),
                        Secure.UI_NIGHT_MODE_LAST_COMPUTED, 0, userId) != 0;
            }
        }
    }

    private static long toMilliSeconds(LocalTime t) {
        return t.toNanoOfDay() / 1000;
    }

    private static LocalTime fromMilliseconds(long t) {
        return LocalTime.ofNanoOfDay(t * 1000);
    }

    private void registerDeviceInactiveListenerLocked() {
        if (mPowerSave) return;
        mWaitForDeviceInactive = true;
        final IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_DREAMING_STARTED);
        getContext().registerReceiver(mDeviceInactiveListener, intentFilter);
    }

    private void cancelCustomAlarm() {
        mAlarmManager.cancel(mCustomTimeListener);
    }

    private void unregisterDeviceInactiveListenerLocked() {
        mWaitForDeviceInactive = false;
        try {
            getContext().unregisterReceiver(mDeviceInactiveListener);
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

    private final class Stub extends IUiModeManager.Stub {
        Stub(Context context) {
            super(PermissionEnforcer.fromContext(context));
        }

        @Override
        public void addCallback(IUiModeManagerCallback callback) {
            int userId = getCallingUserId();
            synchronized (mLock) {
                if (!mUiModeManagerCallbacks.contains(userId)) {
                    mUiModeManagerCallbacks.put(userId, new RemoteCallbackList<>());
                }
                mUiModeManagerCallbacks.get(userId).register(callback);
            }
        }

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

            // Allow the user to enable car mode using the shell,
            // e.g. 'adb shell cmd uimode car yes'
            boolean isShellCaller = mInjector.getCallingUid() == Process.SHELL_UID;
            if (!isShellCaller) {
              assertLegit(callingPackage);
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
            //
            // We also allow the user to disable car mode using the shell,
            // e.g. 'adb shell cmd uimode car no'
            int callingUid = mInjector.getCallingUid();
            boolean isSystemCaller = callingUid == Process.SYSTEM_UID;
            boolean isShellCaller = callingUid == Process.SHELL_UID;
            if (!isSystemCaller && !isShellCaller) {
                assertLegit(callingPackage);
            }
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
            // MODE_NIGHT_CUSTOM_TYPE_SCHEDULE is the default for MODE_NIGHT_CUSTOM.
            int customModeType = mode == MODE_NIGHT_CUSTOM
                    ? MODE_NIGHT_CUSTOM_TYPE_SCHEDULE
                    : MODE_NIGHT_CUSTOM_TYPE_UNKNOWN;
            setNightModeInternal(mode, customModeType);
        }

        private void setNightModeInternal(int mode, int customModeType) {
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
                    break;
                case MODE_NIGHT_CUSTOM:
                    if (SUPPORTED_NIGHT_MODE_CUSTOM_TYPES.contains(customModeType)) {
                        break;
                    }
                    throw new IllegalArgumentException(
                            "Can't set the custom type to " + customModeType);
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            final int user = UserHandle.getCallingUserId();
            enforceValidCallingUser(user);

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (mNightMode.get() != mode || mNightModeCustomType != customModeType) {
                        if (mNightMode.get() == MODE_NIGHT_AUTO
                                || mNightMode.get() == MODE_NIGHT_CUSTOM) {
                            unregisterDeviceInactiveListenerLocked();
                            cancelCustomAlarm();
                        }
                        mNightModeCustomType = mode == MODE_NIGHT_CUSTOM
                                ? customModeType
                                : MODE_NIGHT_CUSTOM_TYPE_UNKNOWN;
                        mNightMode.set(mode);
                        //deactivates AttentionMode if user toggles DarkTheme
                        mAttentionModeThemeOverlay = MODE_ATTENTION_THEME_OVERLAY_OFF;
                        resetNightModeOverrideLocked();
                        persistNightMode(user);
                        // on screen off will update configuration instead
                        if ((mNightMode.get() != MODE_NIGHT_AUTO
                                && mNightMode.get() != MODE_NIGHT_CUSTOM)
                                || shouldApplyAutomaticChangesImmediately()) {
                            unregisterDeviceInactiveListenerLocked();
                            updateLocked(0, 0);
                        } else {
                            registerDeviceInactiveListenerLocked();
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
                return mNightMode.get();
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
        @Override
        public void setNightModeCustomType(@NightModeCustomType int nightModeCustomType) {
            setNightModeCustomType_enforcePermission();
            setNightModeInternal(MODE_NIGHT_CUSTOM, nightModeCustomType);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
        @Override
        public  @NightModeCustomReturnType int getNightModeCustomType() {
            getNightModeCustomType_enforcePermission();
            synchronized (mLock) {
                return mNightModeCustomType;
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
        @Override
            public void setAttentionModeThemeOverlay(
                @AttentionModeThemeOverlayType int attentionModeThemeOverlayType) {
            setAttentionModeThemeOverlay_enforcePermission();

            enforceValidCallingUser(UserHandle.getCallingUserId());

            synchronized (mLock) {
                if (mAttentionModeThemeOverlay != attentionModeThemeOverlayType) {
                    mAttentionModeThemeOverlay = attentionModeThemeOverlayType;
                    Binder.withCleanCallingIdentity(()-> updateLocked(0, 0));
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
        @Override
        public @AttentionModeThemeOverlayType int getAttentionModeThemeOverlay() {
            getAttentionModeThemeOverlay_enforcePermission();
            synchronized (mLock) {
                return mAttentionModeThemeOverlay;
            }
        }

        @Override
        public void setApplicationNightMode(@UiModeManager.NightMode int mode) {
            switch (mode) {
                case UiModeManager.MODE_NIGHT_NO:
                case UiModeManager.MODE_NIGHT_YES:
                case UiModeManager.MODE_NIGHT_AUTO:
                case UiModeManager.MODE_NIGHT_CUSTOM:
                    break;
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }
            final int configNightMode;
            switch (mode) {
                case MODE_NIGHT_YES:
                    configNightMode = Configuration.UI_MODE_NIGHT_YES;
                    break;
                case MODE_NIGHT_NO:
                    configNightMode = Configuration.UI_MODE_NIGHT_NO;
                    break;
                default:
                    configNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED;
            }
            final ActivityTaskManagerInternal.PackageConfigurationUpdater updater =
                    mActivityTaskManager.createPackageConfigurationUpdater();
            updater.setNightMode(configNightMode);
            updater.commit();
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
        public boolean setNightModeActivatedForCustomMode(int modeNightCustomType, boolean active) {
            return setNightModeActivatedForModeInternal(modeNightCustomType, active);
        }

        @Override
        public boolean setNightModeActivated(boolean active) {
            return setNightModeActivatedForModeInternal(mNightModeCustomType, active);
        }

        private boolean setNightModeActivatedForModeInternal(int modeCustomType, boolean active) {
            if (getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.MODIFY_DAY_NIGHT_MODE)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.e(TAG, "Night mode locked, requires MODIFY_DAY_NIGHT_MODE permission");
                return false;
            }
            final int user = Binder.getCallingUserHandle().getIdentifier();
            enforceValidCallingUser(user);

            if (user != mCurrentUser && getContext().checkCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.e(TAG, "Target user is not current user,"
                        + " INTERACT_ACROSS_USERS permission is required");
                return false;

            }
            // Store the last requested bedtime night mode state so that we don't need to notify
            // anyone if the user decides to switch to the night mode to bedtime.
            if (modeCustomType == MODE_NIGHT_CUSTOM_TYPE_BEDTIME) {
                mLastBedtimeRequestedNightMode = active;
            }
            if (modeCustomType != mNightModeCustomType) {
                return false;
            }
            synchronized (mLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mNightMode.get() == MODE_NIGHT_AUTO
                            || mNightMode.get() == MODE_NIGHT_CUSTOM) {
                        unregisterDeviceInactiveListenerLocked();
                        mOverrideNightModeOff = !active;
                        mOverrideNightModeOn = active;
                        mOverrideNightModeUser = mCurrentUser;
                        persistNightModeOverrides(mCurrentUser);
                    } else if (mNightMode.get() == UiModeManager.MODE_NIGHT_NO
                            && active) {
                        mNightMode.set(UiModeManager.MODE_NIGHT_YES);
                    } else if (mNightMode.get() == UiModeManager.MODE_NIGHT_YES
                            && !active) {
                        mNightMode.set(UiModeManager.MODE_NIGHT_NO);
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
            enforceValidCallingUser(user);

            final long ident = Binder.clearCallingIdentity();
            try {
                LocalTime newTime = LocalTime.ofNanoOfDay(time * 1000);
                if (newTime == null) return;
                mCustomAutoNightModeStartMilliseconds = newTime;
                persistNightMode(user);
                onCustomTimeUpdated(user);
            } catch (DateTimeException e) {
                unregisterDeviceInactiveListenerLocked();
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
            enforceValidCallingUser(user);

            final long ident = Binder.clearCallingIdentity();
            try {
                LocalTime newTime = LocalTime.ofNanoOfDay(time * 1000);
                if (newTime == null) return;
                mCustomAutoNightModeEndMilliseconds = newTime;
                onCustomTimeUpdated(user);
            } catch (DateTimeException e) {
                unregisterDeviceInactiveListenerLocked();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public boolean requestProjection(IBinder binder,
                @UiModeManager.ProjectionType int projectionType,
                @NonNull String callingPackage) {
            assertLegit(callingPackage);
            assertSingleProjectionType(projectionType);
            enforceProjectionTypePermissions(projectionType);
            enforceValidCallingUser(getCallingUserId());

            synchronized (mLock) {
                if (mProjectionHolders == null) {
                    mProjectionHolders = new SparseArray<>(1);
                }
                if (!mProjectionHolders.contains(projectionType)) {
                    mProjectionHolders.put(projectionType, new ArrayList<>(1));
                }
                List<ProjectionHolder> currentHolders = mProjectionHolders.get(projectionType);

                // For all projection types, it's a noop if already held.
                for (int i = 0; i < currentHolders.size(); ++i) {
                    if (callingPackage.equals(currentHolders.get(i).mPackageName)) {
                        return true;
                    }
                }

                // Enforce projection type-specific restrictions here.

                // Automotive projection can only be set if it is currently unset. The case where it
                // is already set by the calling package is taken care of above.
                if (projectionType == PROJECTION_TYPE_AUTOMOTIVE && !currentHolders.isEmpty()) {
                    return false;
                }

                ProjectionHolder projectionHolder = new ProjectionHolder(callingPackage,
                        projectionType, binder,
                        UiModeManagerService.this::releaseProjectionUnchecked);
                if (!projectionHolder.linkToDeath()) {
                    return false;
                }
                currentHolders.add(projectionHolder);
                Slog.d(TAG, "Package " + callingPackage + " set projection type "
                        + projectionType + ".");
                onProjectionStateChangedLocked(projectionType);
            }
            return true;
        }

        @Override
        public boolean releaseProjection(@UiModeManager.ProjectionType int projectionType,
                @NonNull String callingPackage) {
            assertLegit(callingPackage);
            assertSingleProjectionType(projectionType);
            enforceProjectionTypePermissions(projectionType);
            enforceValidCallingUser(getCallingUserId());

            return releaseProjectionUnchecked(projectionType, callingPackage);
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.READ_PROJECTION_STATE)
        @Override
        public @UiModeManager.ProjectionType int getActiveProjectionTypes() {
            getActiveProjectionTypes_enforcePermission();
            @UiModeManager.ProjectionType int projectionTypeFlag = PROJECTION_TYPE_NONE;
            synchronized (mLock) {
                if (mProjectionHolders != null) {
                    for (int i = 0; i < mProjectionHolders.size(); ++i) {
                        if (!mProjectionHolders.valueAt(i).isEmpty()) {
                            projectionTypeFlag = projectionTypeFlag | mProjectionHolders.keyAt(i);
                        }
                    }
                }
            }
            return projectionTypeFlag;
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.READ_PROJECTION_STATE)
        @Override
        public List<String> getProjectingPackages(
                @UiModeManager.ProjectionType int projectionType) {
            getProjectingPackages_enforcePermission();
            synchronized (mLock) {
                List<String> packageNames = new ArrayList<>();
                populateWithRelevantActivePackageNames(projectionType, packageNames);
                return packageNames;
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.READ_PROJECTION_STATE)
        public void addOnProjectionStateChangedListener(IOnProjectionStateChangedListener listener,
                @UiModeManager.ProjectionType int projectionType) {
            addOnProjectionStateChangedListener_enforcePermission();
            if (projectionType == PROJECTION_TYPE_NONE) {
                return;
            }

            enforceValidCallingUser(getCallingUserId());

            synchronized (mLock) {
                if (mProjectionListeners == null) {
                    mProjectionListeners = new SparseArray<>(1);
                }
                if (!mProjectionListeners.contains(projectionType)) {
                    mProjectionListeners.put(projectionType, new RemoteCallbackList<>());
                }
                if (mProjectionListeners.get(projectionType).register(listener)) {
                    // If any of those types are active, send a callback immediately.
                    List<String> packageNames = new ArrayList<>();
                    @UiModeManager.ProjectionType int activeProjectionTypes =
                            populateWithRelevantActivePackageNames(projectionType, packageNames);
                    if (!packageNames.isEmpty()) {
                        try {
                            listener.onProjectionStateChanged(activeProjectionTypes, packageNames);
                        } catch (RemoteException e) {
                            Slog.w(TAG,
                                    "Failed a call to onProjectionStateChanged() during listener "
                                            + "registration.");
                        }
                    }
                }
            }
        }


        @android.annotation.EnforcePermission(android.Manifest.permission.READ_PROJECTION_STATE)
        public void removeOnProjectionStateChangedListener(
                IOnProjectionStateChangedListener listener) {
            removeOnProjectionStateChangedListener_enforcePermission();
            synchronized (mLock) {
                if (mProjectionListeners != null) {
                    for (int i = 0; i < mProjectionListeners.size(); ++i) {
                        mProjectionListeners.valueAt(i).unregister(listener);
                    }
                }
            }
        }

        @Override
        public float getContrast() {
            synchronized (mLock) {
                return getContrastLocked();
            }
        }
    };

    // This method validates whether calling user is valid in visible background users
    // feature. Valid user is the current user or the system or in the same profile group as
    // the current user.
    private void enforceValidCallingUser(int userId) {
        if (!isVisibleBackgroundUsersEnabled()) {
            return;
        }
        if (LOG) {
            Slog.d(TAG, "enforceValidCallingUser: userId=" + userId
                    + " isSystemUser=" + (userId == USER_SYSTEM) + " current user=" + mCurrentUser
                    + " callingPid=" + Binder.getCallingPid()
                    + " callingUid=" + mInjector.getCallingUid());
        }
        long ident = Binder.clearCallingIdentity();
        try {
            if (userId != USER_SYSTEM && userId != mCurrentUser
                    && !UserManagerService.getInstance().isSameProfileGroup(userId, mCurrentUser)) {
                throw new SecurityException(
                        "Calling user is not valid for level-1 compatibility in MUMD. "
                                + "callingUserId=" + userId + " currentUserId=" + mCurrentUser);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void enforceProjectionTypePermissions(@UiModeManager.ProjectionType int p) {
        if ((p & PROJECTION_TYPE_AUTOMOTIVE) != 0) {
            getContext().enforceCallingPermission(
                    android.Manifest.permission.TOGGLE_AUTOMOTIVE_PROJECTION,
                    "toggleProjection");
        }
    }

    private static void assertSingleProjectionType(@UiModeManager.ProjectionType int p) {
        // To be a single projection type it must be non-zero and an exact power of two.
        boolean projectionTypeIsPowerOfTwoOrZero = (p & p - 1) == 0;
        if (p == 0 || !projectionTypeIsPowerOfTwoOrZero) {
            throw new IllegalArgumentException("Must specify exactly one projection type.");
        }
    }

    private static List<String> toPackageNameList(Collection<ProjectionHolder> c) {
        List<String> packageNames = new ArrayList<>();
        for (ProjectionHolder p : c) {
            packageNames.add(p.mPackageName);
        }
        return packageNames;
    }

    /**
     * Populates a list with the package names that have set any of the given projection types.
     * @param projectionType the projection types to include
     * @param packageNames the list to populate with package names
     * @return the active projection types
     */
    @GuardedBy("mLock")
    @UiModeManager.ProjectionType
    private int populateWithRelevantActivePackageNames(
            @UiModeManager.ProjectionType int projectionType, List<String> packageNames) {
        packageNames.clear();
        @UiModeManager.ProjectionType int projectionTypeFlag = PROJECTION_TYPE_NONE;
        if (mProjectionHolders != null) {
            for (int i = 0; i < mProjectionHolders.size(); ++i) {
                int key = mProjectionHolders.keyAt(i);
                List<ProjectionHolder> holders = mProjectionHolders.valueAt(i);
                if ((projectionType & key) != 0) {
                    if (packageNames.addAll(toPackageNameList(holders))) {
                        projectionTypeFlag = projectionTypeFlag | key;
                    }
                }
            }
        }
        return projectionTypeFlag;
    }

    private boolean releaseProjectionUnchecked(@UiModeManager.ProjectionType int projectionType,
            @NonNull String pkg) {
        synchronized (mLock) {
            boolean removed = false;
            if (mProjectionHolders != null) {
                List<ProjectionHolder> holders = mProjectionHolders.get(projectionType);
                if (holders != null) {
                    // Iterate backward so we can safely remove while iterating.
                    for (int i = holders.size() - 1; i >= 0; --i) {
                        ProjectionHolder holder = holders.get(i);
                        if (pkg.equals(holder.mPackageName)) {
                            holder.unlinkToDeath();
                            Slog.d(TAG, "Projection type " + projectionType + " released by "
                                    + pkg + ".");
                            holders.remove(i);
                            removed = true;
                        }
                    }
                }
            }
            if (removed) {
                onProjectionStateChangedLocked(projectionType);
            } else {
                Slog.w(TAG, pkg + " tried to release projection type " + projectionType
                        + " but was not set by that package.");
            }
            return removed;
        }
    }

    /**
     * Return the contrast for the current user. If not cached, fetch it from the settings.
     */
    @GuardedBy("mLock")
    private float getContrastLocked() {
        if (!mContrasts.contains(mCurrentUser)) updateContrastLocked();
        return mContrasts.get(mCurrentUser);
    }

    /**
     * Read the contrast setting for the current user and update {@link #mContrasts}
     * if the contrast changed. Returns true if {@link #mContrasts} was updated.
     */
    @GuardedBy("mLock")
    private boolean updateContrastLocked() {
        float contrast = Settings.Secure.getFloatForUser(getContext().getContentResolver(),
                CONTRAST_LEVEL, CONTRAST_DEFAULT_VALUE, mCurrentUser);
        if (Math.abs(mContrasts.get(mCurrentUser, Float.MAX_VALUE) - contrast) >= 1e-10) {
            mContrasts.put(mCurrentUser, contrast);
            return true;
        }
        return false;
    }

    private static class ProjectionHolder implements IBinder.DeathRecipient {
        private final String mPackageName;
        private final @UiModeManager.ProjectionType int mProjectionType;
        private final IBinder mBinder;
        private final ProjectionReleaser mProjectionReleaser;

        private ProjectionHolder(String packageName,
                @UiModeManager.ProjectionType int projectionType, IBinder binder,
                ProjectionReleaser projectionReleaser) {
            mPackageName = packageName;
            mProjectionType = projectionType;
            mBinder = binder;
            mProjectionReleaser = projectionReleaser;
        }

        private boolean linkToDeath() {
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath failed for projection requester: " + mPackageName + ".",
                        e);
                return false;
            }
            return true;
        }

        private void unlinkToDeath() {
            mBinder.unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            Slog.w(TAG, "Projection holder " + mPackageName
                    + " died. Releasing projection type " + mProjectionType + ".");
            mProjectionReleaser.release(mProjectionType, mPackageName);
        }

        private interface ProjectionReleaser {
            boolean release(@UiModeManager.ProjectionType int projectionType,
                    @NonNull String packageName);
        }
    }

    private void assertLegit(@NonNull String packageName) {
        if (!doesPackageHaveCallingUid(packageName)) {
            throw new SecurityException("Caller claimed bogus packageName: " + packageName + ".");
        }
    }

    private boolean doesPackageHaveCallingUid(@NonNull String packageName) {
        int callingUid = mInjector.getCallingUid();
        int callingUserId = UserHandle.getUserId(callingUid);
        final long ident = Binder.clearCallingIdentity();
        try {
            return getContext().getPackageManager().getPackageUidAsUser(packageName,
                    callingUserId) == callingUid;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mLock")
    private void onProjectionStateChangedLocked(
            @UiModeManager.ProjectionType int changedProjectionType) {
        if (mProjectionListeners == null) {
            return;
        }
        for (int i = 0; i < mProjectionListeners.size(); ++i) {
            int listenerProjectionType = mProjectionListeners.keyAt(i);
            // Every listener that is affected must be called back with all the state they are
            // listening for.
            if ((changedProjectionType & listenerProjectionType) != 0) {
                RemoteCallbackList<IOnProjectionStateChangedListener> listeners =
                        mProjectionListeners.valueAt(i);
                List<String> packageNames = new ArrayList<>();
                @UiModeManager.ProjectionType int activeProjectionTypes =
                        populateWithRelevantActivePackageNames(listenerProjectionType,
                                packageNames);
                int listenerCount = listeners.beginBroadcast();
                for (int j = 0; j < listenerCount; ++j) {
                    try {
                        listeners.getBroadcastItem(j).onProjectionStateChanged(
                                activeProjectionTypes, packageNames);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed a call to onProjectionStateChanged().");
                    }
                }
                listeners.finishBroadcast();
            }
        }
    }

    private void onCustomTimeUpdated(int user) {
        persistNightMode(user);
        if (mNightMode.get() != MODE_NIGHT_CUSTOM) return;
        if (shouldApplyAutomaticChangesImmediately()) {
            unregisterDeviceInactiveListenerLocked();
            updateLocked(0, 0);
        } else {
            registerDeviceInactiveListenerLocked();
        }
    }

    void dumpImpl(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current UI Mode Service state:");
            pw.print("  mDockState="); pw.print(mDockState);
            pw.print(" mLastBroadcastState="); pw.println(mLastBroadcastState);

            pw.print(" mStartDreamImmediatelyOnDock="); pw.print(mStartDreamImmediatelyOnDock);

            pw.print("  mNightMode="); pw.print(mNightMode.get()); pw.print(" (");
            pw.print(Shell.nightModeToStr(mNightMode.get(), mNightModeCustomType)); pw.print(") ");
            pw.print(" mOverrideOn/Off="); pw.print(mOverrideNightModeOn);
            pw.print("/"); pw.print(mOverrideNightModeOff);
            pw.print("  mAttentionModeThemeOverlay="); pw.print(mAttentionModeThemeOverlay);
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
            pw.print(" mWaitForDeviceInactive="); pw.print(mWaitForDeviceInactive);
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
                Secure.UI_NIGHT_MODE, mNightMode.get(), user);
        Secure.putLongForUser(getContext().getContentResolver(),
                Secure.UI_NIGHT_MODE_CUSTOM_TYPE, mNightModeCustomType, user);
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

        if (mNightMode.get() == MODE_NIGHT_YES || mNightMode.get() == UiModeManager.MODE_NIGHT_NO) {
            updateComputedNightModeLocked(mNightMode.get() == MODE_NIGHT_YES);
        }

        if (mNightMode.get() == MODE_NIGHT_AUTO) {
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

        if (mNightMode.get() == MODE_NIGHT_CUSTOM) {
            if (mNightModeCustomType == MODE_NIGHT_CUSTOM_TYPE_BEDTIME) {
                updateComputedNightModeLocked(mLastBedtimeRequestedNightMode);
            } else {
                registerTimeChangeEvent();
                final boolean activate = computeCustomNightMode();
                updateComputedNightModeLocked(activate);
                scheduleNextCustomTimeListener();
            }
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
                    + "; mNightModeCustomType=" + mNightModeCustomType
                    + "; uiMode=" + uiMode);
        }

        mCurUiMode = uiMode;
        if (!mHoldingConfiguration && (!mWaitForDeviceInactive || mPowerSave)) {
            mConfiguration.uiMode = uiMode;
        }
    }

    @UiModeManager.NightMode
    private int getComputedUiModeConfiguration(int uiMode) {
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
        return mCar || !mPowerManager.isInteractive()
                || mNightModeCustomType == MODE_NIGHT_CUSTOM_TYPE_BEDTIME
                || mDreamManagerInternal.isDreaming();
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

        final boolean dreamsSuppressed = mDreamsDisabledByAmbientModeSuppression
                && mLocalPowerManager.isAmbientDisplaySuppressed();

        // If we did not start a dock app, then start dreaming if appropriate.
        if (category != null && !dockAppStarted && !dreamsSuppressed && (
                mStartDreamImmediatelyOnDock
                        || mWindowManager.isKeyguardShowingAndNotOccluded()
                        || !mPowerManager.isInteractive())) {
            mInjector.startDreamWhenDockedIfAppropriate(getContext());
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
                                // TODO(b/173744200) Please replace FLAG_MUTABLE_UNAUDITED below
                                // with either FLAG_IMMUTABLE (recommended) or FLAG_MUTABLE.
                                PendingIntent.getActivityAsUser(context, 0,
                                        carModeOffIntent, PendingIntent.FLAG_MUTABLE,
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
        boolean newComputedValue = activate;
        if (mNightMode.get() != MODE_NIGHT_YES && mNightMode.get() != UiModeManager.MODE_NIGHT_NO) {
            if (mOverrideNightModeOn && !newComputedValue) {
                newComputedValue = true;
            } else if (mOverrideNightModeOff && newComputedValue) {
                newComputedValue = false;
            }
        }

        if (modesApi()) {
            // Computes final night mode values based on Attention Mode.
            mComputedNightMode = switch (mAttentionModeThemeOverlay) {
                case (UiModeManager.MODE_ATTENTION_THEME_OVERLAY_NIGHT) -> true;
                case (UiModeManager.MODE_ATTENTION_THEME_OVERLAY_DAY) -> false;
                default -> newComputedValue; // case OFF
            };
        } else {
            mComputedNightMode = newComputedValue;
        }

        if (mNightMode.get() != MODE_NIGHT_AUTO || (mTwilightManager != null
                && mTwilightManager.getLastTwilightState() != null)) {
            resetNightModeOverrideLocked();
        }
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
        public static final String NIGHT_MODE_STR_CUSTOM_SCHEDULE = "custom_schedule";
        public static final String NIGHT_MODE_STR_CUSTOM_BEDTIME = "custom_bedtime";
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
            pw.println("  night [yes|no|auto|custom_schedule|custom_bedtime]");
            pw.println("    Set or read night mode.");
            pw.println("  car [yes|no]");
            pw.println("    Set or read car mode.");
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
                    case "car":
                        return handleCarMode();
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
            final int customType = strToNightModeCustomType(modeStr);
            if (mode >= 0) {
                mInterface.setNightMode(mode);
                if (mode == UiModeManager.MODE_NIGHT_CUSTOM) {
                    mInterface.setNightModeCustomType(customType);
                }
                printCurrentNightMode();
                return 0;
            } else {
                err.println("Error: mode must be '" + NIGHT_MODE_STR_YES + "', '"
                        + NIGHT_MODE_STR_NO + "', or '" + NIGHT_MODE_STR_AUTO
                        +  "', or '" + NIGHT_MODE_STR_CUSTOM_SCHEDULE + "', or '"
                        + NIGHT_MODE_STR_CUSTOM_BEDTIME + "'");
                return -1;
            }
        }

        private void printCurrentNightMode() throws RemoteException {
            final PrintWriter pw = getOutPrintWriter();
            final int currMode = mInterface.getNightMode();
            final int customType = mInterface.getNightModeCustomType();
            final String currModeStr = nightModeToStr(currMode, customType);
            pw.println("Night mode: " + currModeStr);
        }

        private static String nightModeToStr(int mode, int customType) {
            switch (mode) {
                case UiModeManager.MODE_NIGHT_YES:
                    return NIGHT_MODE_STR_YES;
                case UiModeManager.MODE_NIGHT_NO:
                    return NIGHT_MODE_STR_NO;
                case UiModeManager.MODE_NIGHT_AUTO:
                    return NIGHT_MODE_STR_AUTO;
                case MODE_NIGHT_CUSTOM:
                    if (customType == UiModeManager.MODE_NIGHT_CUSTOM_TYPE_SCHEDULE) {
                        return NIGHT_MODE_STR_CUSTOM_SCHEDULE;
                    }
                    if (customType == UiModeManager.MODE_NIGHT_CUSTOM_TYPE_BEDTIME) {
                        return NIGHT_MODE_STR_CUSTOM_BEDTIME;
                    }
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
                case NIGHT_MODE_STR_CUSTOM_SCHEDULE:
                case NIGHT_MODE_STR_CUSTOM_BEDTIME:
                    return UiModeManager.MODE_NIGHT_CUSTOM;
                default:
                    return -1;
            }
        }

        private static int strToNightModeCustomType(String customTypeStr) {
            switch (customTypeStr) {
                case NIGHT_MODE_STR_CUSTOM_BEDTIME:
                    return UiModeManager.MODE_NIGHT_CUSTOM_TYPE_BEDTIME;
                case NIGHT_MODE_STR_CUSTOM_SCHEDULE:
                    return UiModeManager.MODE_NIGHT_CUSTOM_TYPE_SCHEDULE;
                default:
                    return -1;
            }
        }

        private int handleCarMode() throws RemoteException {
            final PrintWriter err = getErrPrintWriter();
            final String modeStr = getNextArg();
            if (modeStr == null) {
                printCurrentCarMode();
                return 0;
            }

            if (modeStr.equals("yes")) {
                mInterface.enableCarMode(0 /* flags */, DEFAULT_PRIORITY, "" /* package */);
                printCurrentCarMode();
                return 0;
            } else if (modeStr.equals("no")) {
                mInterface.disableCarMode(0 /* flags */);
                printCurrentCarMode();
                return 0;
            } else {
                err.println("Error: mode must be 'yes', or 'no'");
                return -1;
            }
        }

        private void printCurrentCarMode() throws RemoteException {
            final PrintWriter pw = getOutPrintWriter();
            final int currMode = mInterface.getCurrentModeType();
            pw.println("Car mode: " + (currMode == Configuration.UI_MODE_TYPE_CAR ? "yes" : "no"));
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

    @VisibleForTesting
    public static class Injector {
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

        public void startDreamWhenDockedIfAppropriate(Context context) {
            Sandman.startDreamWhenDockedIfAppropriate(context);
        }
    }

    /**
     * Interface to contain the value for system night mode. We make the night mode accessible
     * through this class to ensure that the reassignment of this value invalidates the cache.
     */
    private interface NightMode {
        int get();
        void set(int mode);
    }
}
