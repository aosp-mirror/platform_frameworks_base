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

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
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
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.Sandman;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.internal.R;
import com.android.internal.app.DisableCarModeActivity;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

final class UiModeManagerService extends SystemService {
    private static final String TAG = UiModeManager.class.getSimpleName();
    private static final boolean LOG = false;

    // Enable launching of applications when entering the dock.
    private static final boolean ENABLE_LAUNCH_CAR_DOCK_APP = true;
    private static final boolean ENABLE_LAUNCH_DESK_DOCK_APP = true;

    final Object mLock = new Object();
    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private int mLastBroadcastState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    int mNightMode = UiModeManager.MODE_NIGHT_NO;

    private boolean mCarModeEnabled = false;
    private boolean mCharging = false;
    private int mDefaultUiModeType;
    private boolean mCarModeKeepsScreenOn;
    private boolean mDeskModeKeepsScreenOn;
    private boolean mTelevision;
    private boolean mWatch;
    private boolean mComputedNightMode;
    private int mCarModeEnableFlags;

    int mCurUiMode = 0;
    private int mSetUiMode = 0;
    private boolean mHoldingConfiguration = false;

    private Configuration mConfiguration = new Configuration();
    boolean mSystemReady;

    private final Handler mHandler = new Handler();

    private TwilightManager mTwilightManager;
    private NotificationManager mNotificationManager;
    private StatusBarManager mStatusBarManager;

    private PowerManager.WakeLock mWakeLock;

    public UiModeManagerService(Context context) {
        super(context);
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
            mCharging = (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
            synchronized (mLock) {
                if (mSystemReady) {
                    updateLocked(0, 0);
                }
            }
        }
    };

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged() {
            updateTwilight();
        }
    };

    @Override
    public void onStart() {
        final Context context = getContext();
        mTwilightManager = getLocalService(TwilightManager.class);
        final PowerManager powerManager =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);

        context.registerReceiver(mDockModeReceiver,
                new IntentFilter(Intent.ACTION_DOCK_EVENT));
        context.registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        mConfiguration.setToDefaults();

        mDefaultUiModeType = context.getResources().getInteger(
                com.android.internal.R.integer.config_defaultUiModeType);
        mCarModeKeepsScreenOn = (context.getResources().getInteger(
                com.android.internal.R.integer.config_carDockKeepsScreenOn) == 1);
        mDeskModeKeepsScreenOn = (context.getResources().getInteger(
                com.android.internal.R.integer.config_deskDockKeepsScreenOn) == 1);
        mTelevision = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TELEVISION) ||
            context.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_LEANBACK);
        mWatch = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);

        mNightMode = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.UI_NIGHT_MODE, UiModeManager.MODE_NIGHT_AUTO);

        mTwilightManager.registerListener(mTwilightListener, mHandler);

        publishBinderService(Context.UI_MODE_SERVICE, mService);
    }

    private final IBinder mService = new IUiModeManager.Stub() {
        @Override
        public void enableCarMode(int flags) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    setCarModeLocked(true, flags);
                    if (mSystemReady) {
                        updateLocked(flags, 0);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void disableCarMode(int flags) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    setCarModeLocked(false, 0);
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
            switch (mode) {
                case UiModeManager.MODE_NIGHT_NO:
                case UiModeManager.MODE_NIGHT_YES:
                case UiModeManager.MODE_NIGHT_AUTO:
                    break;
                default:
                    throw new IllegalArgumentException("Unknown mode: " + mode);
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (isDoingNightModeLocked() && mNightMode != mode) {
                        Settings.Secure.putInt(getContext().getContentResolver(),
                                Settings.Secure.UI_NIGHT_MODE, mode);
                        mNightMode = mode;
                        updateLocked(0, 0);
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
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {

                pw.println("Permission Denial: can't dump uimode service from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            dumpImpl(pw);
        }
    };

    void dumpImpl(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Current UI Mode Service state:");
            pw.print("  mDockState="); pw.print(mDockState);
                    pw.print(" mLastBroadcastState="); pw.println(mLastBroadcastState);
            pw.print("  mNightMode="); pw.print(mNightMode);
                    pw.print(" mCarModeEnabled="); pw.print(mCarModeEnabled);
                    pw.print(" mComputedNightMode="); pw.print(mComputedNightMode);
                    pw.print(" mCarModeEnableFlags="); pw.println(mCarModeEnableFlags);
            pw.print("  mCurUiMode=0x"); pw.print(Integer.toHexString(mCurUiMode));
                    pw.print(" mSetUiMode=0x"); pw.println(Integer.toHexString(mSetUiMode));
            pw.print("  mHoldingConfiguration="); pw.print(mHoldingConfiguration);
                    pw.print(" mSystemReady="); pw.println(mSystemReady);
            pw.print("  mTwilightService.getCurrentState()=");
                    pw.println(mTwilightManager.getCurrentState());
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            synchronized (mLock) {
                mSystemReady = true;
                mCarModeEnabled = mDockState == Intent.EXTRA_DOCK_STATE_CAR;
                updateComputedNightModeLocked();
                updateLocked(0, 0);
            }
        }
    }

    boolean isDoingNightModeLocked() {
        return mCarModeEnabled || mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED;
    }

    void setCarModeLocked(boolean enabled, int flags) {
        if (mCarModeEnabled != enabled) {
            mCarModeEnabled = enabled;
        }
        mCarModeEnableFlags = flags;
    }

    private void updateDockState(int newState) {
        synchronized (mLock) {
            if (newState != mDockState) {
                mDockState = newState;
                setCarModeLocked(mDockState == Intent.EXTRA_DOCK_STATE_CAR, 0);
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

    private void updateConfigurationLocked() {
        int uiMode = mDefaultUiModeType;
        if (mTelevision) {
            uiMode = Configuration.UI_MODE_TYPE_TELEVISION;
        } else if (mWatch) {
            uiMode = Configuration.UI_MODE_TYPE_WATCH;
        } else if (mCarModeEnabled) {
            uiMode = Configuration.UI_MODE_TYPE_CAR;
        } else if (isDeskDockState(mDockState)) {
            uiMode = Configuration.UI_MODE_TYPE_DESK;
        }
        if (mCarModeEnabled) {
            if (mNightMode == UiModeManager.MODE_NIGHT_AUTO) {
                updateComputedNightModeLocked();
                uiMode |= mComputedNightMode ? Configuration.UI_MODE_NIGHT_YES
                        : Configuration.UI_MODE_NIGHT_NO;
            } else {
                uiMode |= mNightMode << 4;
            }
        } else {
            // Disabling the car mode clears the night mode.
            uiMode = (uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        }

        if (LOG) {
            Slog.d(TAG,
                "updateConfigurationLocked: mDockState=" + mDockState
                + "; mCarMode=" + mCarModeEnabled
                + "; mNightMode=" + mNightMode
                + "; uiMode=" + uiMode);
        }

        mCurUiMode = uiMode;
        if (!mHoldingConfiguration) {
            mConfiguration.uiMode = uiMode;
        }
    }

    private void sendConfigurationLocked() {
        if (mSetUiMode != mConfiguration.uiMode) {
            mSetUiMode = mConfiguration.uiMode;

            try {
                ActivityManagerNative.getDefault().updateConfiguration(mConfiguration);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure communicating with activity manager", e);
            }
        }
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
                    getContext().sendBroadcastAsUser(new Intent(oldAction), UserHandle.ALL);
                }
                mLastBroadcastState = Intent.EXTRA_DOCK_STATE_CAR;
                action = UiModeManager.ACTION_ENTER_CAR_MODE;
            }
        } else if (isDeskDockState(mDockState)) {
            if (!isDeskDockState(mLastBroadcastState)) {
                if (oldAction != null) {
                    getContext().sendBroadcastAsUser(new Intent(oldAction), UserHandle.ALL);
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
                if (ENABLE_LAUNCH_CAR_DOCK_APP
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

    private void updateAfterBroadcastLocked(String action, int enableFlags, int disableFlags) {
        // Launch a dock activity
        String category = null;
        if (UiModeManager.ACTION_ENTER_CAR_MODE.equals(action)) {
            // Only launch car home when car mode is enabled and the caller
            // has asked us to switch to it.
            if (ENABLE_LAUNCH_CAR_DOCK_APP
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
                    int result = ActivityManagerNative.getDefault().startActivityWithConfig(
                            null, null, homeIntent, null, null, null, 0, 0,
                            mConfiguration, null, UserHandle.USER_CURRENT);
                    if (result >= ActivityManager.START_SUCCESS) {
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
        sendConfigurationLocked();

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

                Notification n = new Notification();
                n.icon = R.drawable.stat_notify_car_mode;
                n.defaults = Notification.DEFAULT_LIGHTS;
                n.flags = Notification.FLAG_ONGOING_EVENT;
                n.when = 0;
                n.setLatestEventInfo(
                        context,
                        context.getString(R.string.car_mode_disable_notification_title),
                        context.getString(R.string.car_mode_disable_notification_message),
                        PendingIntent.getActivityAsUser(context, 0, carModeOffIntent, 0,
                                null, UserHandle.CURRENT));
                mNotificationManager.notifyAsUser(null,
                        R.string.car_mode_disable_notification_title, n, UserHandle.ALL);
            } else {
                mNotificationManager.cancelAsUser(null,
                        R.string.car_mode_disable_notification_title, UserHandle.ALL);
            }
        }
    }

    void updateTwilight() {
        synchronized (mLock) {
            if (isDoingNightModeLocked() && mNightMode == UiModeManager.MODE_NIGHT_AUTO) {
                updateComputedNightModeLocked();
                updateLocked(0, 0);
            }
        }
    }

    private void updateComputedNightModeLocked() {
        TwilightState state = mTwilightManager.getCurrentState();
        if (state != null) {
            mComputedNightMode = state.isNight();
        }
    }


}
