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

package com.android.systemui.statusbar.phone;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;

import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.SynchronousUserSwitchObserver;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.telecom.TelecomManager;
import android.text.format.DateFormat;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.Dependency;
import com.android.systemui.DockedStackExistsListener;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.qs.tiles.RotationLockTile;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothController.Callback;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DataSaverController.Listener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.NotificationChannels;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;

/**
 * This class contains all of the policy about which icons are installed in the status
 * bar at boot time.  It goes through the normal API for icons, even though it probably
 * strictly doesn't need to.
 */
public class PhoneStatusBarPolicy implements Callback, Callbacks,
        RotationLockControllerCallback, Listener, ZenModeController.Callback,
        DeviceProvisionedListener, KeyguardMonitor.Callback, PrivacyItemController.Callback {
    private static final String TAG = "PhoneStatusBarPolicy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final int LOCATION_STATUS_ICON_ID = R.drawable.stat_sys_location;
    public static final int NUM_TASKS_FOR_INSTANT_APP_INFO = 5;

    private final String mSlotCast;
    private final String mSlotHotspot;
    private final String mSlotBluetooth;
    private final String mSlotTty;
    private final String mSlotZen;
    private final String mSlotVolume;
    private final String mSlotAlarmClock;
    private final String mSlotManagedProfile;
    private final String mSlotRotate;
    private final String mSlotHeadset;
    private final String mSlotDataSaver;
    private final String mSlotLocation;
    private final String mSlotMicrophone;
    private final String mSlotCamera;

    private final Context mContext;
    private final Handler mHandler = new Handler();
    private final CastController mCast;
    private final HotspotController mHotspot;
    private final NextAlarmController mNextAlarmController;
    private final AlarmManager mAlarmManager;
    private final UserInfoController mUserInfoController;
    private final UserManager mUserManager;
    private final StatusBarIconController mIconController;
    private final RotationLockController mRotationLockController;
    private final DataSaverController mDataSaver;
    private final ZenModeController mZenController;
    private final DeviceProvisionedController mProvisionedController;
    private final KeyguardMonitor mKeyguardMonitor;
    private final LocationController mLocationController;
    private final PrivacyItemController mPrivacyItemController;
    private final ArraySet<Pair<String, Integer>> mCurrentNotifs = new ArraySet<>();
    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);

    // Assume it's all good unless we hear otherwise.  We don't always seem
    // to get broadcasts that it *is* there.
    IccCardConstants.State mSimState = IccCardConstants.State.READY;

    private boolean mZenVisible;
    private boolean mVolumeVisible;
    private boolean mCurrentUserSetup;
    private boolean mDockedStackExists;

    private boolean mManagedProfileIconVisible = false;

    private BluetoothController mBluetooth;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    public PhoneStatusBarPolicy(Context context, StatusBarIconController iconController) {
        mContext = context;
        mIconController = iconController;
        mCast = Dependency.get(CastController.class);
        mHotspot = Dependency.get(HotspotController.class);
        mBluetooth = Dependency.get(BluetoothController.class);
        mNextAlarmController = Dependency.get(NextAlarmController.class);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mUserInfoController = Dependency.get(UserInfoController.class);
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mRotationLockController = Dependency.get(RotationLockController.class);
        mDataSaver = Dependency.get(DataSaverController.class);
        mZenController = Dependency.get(ZenModeController.class);
        mProvisionedController = Dependency.get(DeviceProvisionedController.class);
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mLocationController = Dependency.get(LocationController.class);
        mPrivacyItemController = Dependency.get(PrivacyItemController.class);

        mSlotCast = context.getString(com.android.internal.R.string.status_bar_cast);
        mSlotHotspot = context.getString(com.android.internal.R.string.status_bar_hotspot);
        mSlotBluetooth = context.getString(com.android.internal.R.string.status_bar_bluetooth);
        mSlotTty = context.getString(com.android.internal.R.string.status_bar_tty);
        mSlotZen = context.getString(com.android.internal.R.string.status_bar_zen);
        mSlotVolume = context.getString(com.android.internal.R.string.status_bar_volume);
        mSlotAlarmClock = context.getString(com.android.internal.R.string.status_bar_alarm_clock);
        mSlotManagedProfile = context.getString(
                com.android.internal.R.string.status_bar_managed_profile);
        mSlotRotate = context.getString(com.android.internal.R.string.status_bar_rotate);
        mSlotHeadset = context.getString(com.android.internal.R.string.status_bar_headset);
        mSlotDataSaver = context.getString(com.android.internal.R.string.status_bar_data_saver);
        mSlotLocation = context.getString(com.android.internal.R.string.status_bar_location);
        mSlotMicrophone = context.getString(com.android.internal.R.string.status_bar_microphone);
        mSlotCamera = context.getString(com.android.internal.R.string.status_bar_camera);

        // listen for broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
        filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);

        // listen for user / profile change.
        try {
            ActivityManager.getService().registerUserSwitchObserver(mUserSwitchListener, TAG);
        } catch (RemoteException e) {
            // Ignore
        }

        // TTY status
        updateTTY();

        // bluetooth status
        updateBluetooth();

        // Alarm clock
        mIconController.setIcon(mSlotAlarmClock, R.drawable.stat_sys_alarm, null);
        mIconController.setIconVisibility(mSlotAlarmClock, false);

        // zen
        mIconController.setIcon(mSlotZen, R.drawable.stat_sys_zen_important, null);
        mIconController.setIconVisibility(mSlotZen, false);

        // volume
        mIconController.setIcon(mSlotVolume, R.drawable.stat_sys_ringer_vibrate, null);
        mIconController.setIconVisibility(mSlotVolume, false);
        updateVolumeZen();

        // cast
        mIconController.setIcon(mSlotCast, R.drawable.stat_sys_cast, null);
        mIconController.setIconVisibility(mSlotCast, false);

        // hotspot
        mIconController.setIcon(mSlotHotspot, R.drawable.stat_sys_hotspot,
                mContext.getString(R.string.accessibility_status_bar_hotspot));
        mIconController.setIconVisibility(mSlotHotspot, mHotspot.isHotspotEnabled());

        // managed profile
        mIconController.setIcon(mSlotManagedProfile, R.drawable.stat_sys_managed_profile_status,
                mContext.getString(R.string.accessibility_managed_profile));
        mIconController.setIconVisibility(mSlotManagedProfile, mManagedProfileIconVisible);

        // data saver
        mIconController.setIcon(mSlotDataSaver, R.drawable.stat_sys_data_saver,
                context.getString(R.string.accessibility_data_saver_on));
        mIconController.setIconVisibility(mSlotDataSaver, false);

        // privacy items
        mIconController.setIcon(mSlotMicrophone, R.drawable.stat_sys_mic_none, null);
        mIconController.setIconVisibility(mSlotMicrophone, false);
        mIconController.setIcon(mSlotCamera, R.drawable.stat_sys_camera, null);
        mIconController.setIconVisibility(mSlotCamera, false);
        mIconController.setIcon(mSlotLocation, LOCATION_STATUS_ICON_ID,
                mContext.getString(R.string.accessibility_location_active));
        mIconController.setIconVisibility(mSlotLocation, false);

        mRotationLockController.addCallback(this);
        mBluetooth.addCallback(this);
        mProvisionedController.addCallback(this);
        mZenController.addCallback(this);
        mCast.addCallback(mCastCallback);
        mHotspot.addCallback(mHotspotCallback);
        mNextAlarmController.addCallback(mNextAlarmCallback);
        mDataSaver.addCallback(this);
        mKeyguardMonitor.addCallback(this);
        mPrivacyItemController.addCallback(this);

        SysUiServiceProvider.getComponent(mContext, CommandQueue.class).addCallback(this);
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mTaskListener);

        // Clear out all old notifications on startup (only present in the case where sysui dies)
        NotificationManager noMan = mContext.getSystemService(NotificationManager.class);
        for (StatusBarNotification notification : noMan.getActiveNotifications()) {
            if (notification.getId() == SystemMessage.NOTE_INSTANT_APPS) {
                noMan.cancel(notification.getTag(), notification.getId());
            }
        }
        DockedStackExistsListener.register(exists -> {
            mDockedStackExists = exists;
            updateForegroundInstantApps();
        });
    }

    @Override
    public void onZenChanged(int zen) {
        updateVolumeZen();
    }

    @Override
    public void onConfigChanged(ZenModeConfig config) {
        updateVolumeZen();
    }

    private void updateAlarm() {
        final AlarmClockInfo alarm = mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        final boolean hasAlarm = alarm != null && alarm.getTriggerTime() > 0;
        int zen = mZenController.getZen();
        final boolean zenNone = zen == Global.ZEN_MODE_NO_INTERRUPTIONS;
        mIconController.setIcon(mSlotAlarmClock, zenNone ? R.drawable.stat_sys_alarm_dim
                : R.drawable.stat_sys_alarm, buildAlarmContentDescription());
        mIconController.setIconVisibility(mSlotAlarmClock, mCurrentUserSetup && hasAlarm);
    }

    private String buildAlarmContentDescription() {
        if (mNextAlarm == null) {
            return mContext.getString(R.string.status_bar_alarm);
        }
        return formatNextAlarm(mNextAlarm, mContext);
    }

    private static String formatNextAlarm(AlarmManager.AlarmClockInfo info, Context context) {
        if (info == null) {
            return "";
        }
        String skeleton = DateFormat.is24HourFormat(
                context, ActivityManager.getCurrentUser()) ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        String dateString = DateFormat.format(pattern, info.getTriggerTime()).toString();

        return context.getString(R.string.accessibility_quick_settings_alarm, dateString);
    }

    private final void updateSimState(Intent intent) {
        String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
        if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
            mSimState = IccCardConstants.State.ABSENT;
        } else if (IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR.equals(stateExtra)) {
            mSimState = IccCardConstants.State.CARD_IO_ERROR;
        } else if (IccCardConstants.INTENT_VALUE_ICC_CARD_RESTRICTED.equals(stateExtra)) {
            mSimState = IccCardConstants.State.CARD_RESTRICTED;
        } else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
            mSimState = IccCardConstants.State.READY;
        } else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
            final String lockedReason =
                    intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
            if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PIN_REQUIRED;
            } else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PUK_REQUIRED;
            } else {
                mSimState = IccCardConstants.State.NETWORK_LOCKED;
            }
        } else {
            mSimState = IccCardConstants.State.UNKNOWN;
        }
    }

    private final void updateVolumeZen() {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        boolean zenVisible = false;
        int zenIconId = 0;
        String zenDescription = null;

        boolean volumeVisible = false;
        int volumeIconId = 0;
        String volumeDescription = null;
        int zen = mZenController.getZen();

        if (DndTile.isVisible(mContext) || DndTile.isCombinedIcon(mContext)) {
            zenVisible = zen != Global.ZEN_MODE_OFF;
            zenIconId = R.drawable.stat_sys_dnd;
            zenDescription = mContext.getString(R.string.quick_settings_dnd_label);
        } else if (zen == Global.ZEN_MODE_NO_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_zen_none;
            zenDescription = mContext.getString(R.string.interruption_level_none);
        } else if (zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_zen_important;
            zenDescription = mContext.getString(R.string.interruption_level_priority);
        }

        if (!ZenModeConfig.isZenOverridingRinger(zen, mZenController.getConsolidatedPolicy())) {
            if (audioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_VIBRATE) {
                volumeVisible = true;
                volumeIconId = R.drawable.stat_sys_ringer_vibrate;
                volumeDescription = mContext.getString(R.string.accessibility_ringer_vibrate);
            } else if (audioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_SILENT) {
                volumeVisible = true;
                volumeIconId = R.drawable.stat_sys_ringer_silent;
                volumeDescription = mContext.getString(R.string.accessibility_ringer_silent);
            }
        }

        if (zenVisible) {
            mIconController.setIcon(mSlotZen, zenIconId, zenDescription);
        }
        if (zenVisible != mZenVisible) {
            mIconController.setIconVisibility(mSlotZen, zenVisible);
            mZenVisible = zenVisible;
        }

        if (volumeVisible) {
            mIconController.setIcon(mSlotVolume, volumeIconId, volumeDescription);
        }
        if (volumeVisible != mVolumeVisible) {
            mIconController.setIconVisibility(mSlotVolume, volumeVisible);
            mVolumeVisible = volumeVisible;
        }
        updateAlarm();
    }

    @Override
    public void onBluetoothDevicesChanged() {
        updateBluetooth();
    }

    @Override
    public void onBluetoothStateChange(boolean enabled) {
        updateBluetooth();
    }

    private final void updateBluetooth() {
        int iconId = R.drawable.stat_sys_data_bluetooth;
        String contentDescription =
                mContext.getString(R.string.accessibility_quick_settings_bluetooth_on);
        boolean bluetoothVisible = false;
        if (mBluetooth != null) {
            if (mBluetooth.isBluetoothConnected()) {
                iconId = R.drawable.stat_sys_data_bluetooth_connected;
                contentDescription = mContext.getString(R.string.accessibility_bluetooth_connected);
                bluetoothVisible = mBluetooth.isBluetoothEnabled();
            }
        }

        mIconController.setIcon(mSlotBluetooth, iconId, contentDescription);
        mIconController.setIconVisibility(mSlotBluetooth, bluetoothVisible);
    }

    private final void updateTTY() {
        TelecomManager telecomManager =
                (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
        if (telecomManager == null) {
            updateTTY(TelecomManager.TTY_MODE_OFF);
        } else {
            updateTTY(telecomManager.getCurrentTtyMode());
        }
    }

    private final void updateTTY(int currentTtyMode) {
        boolean enabled = currentTtyMode != TelecomManager.TTY_MODE_OFF;

        if (DEBUG) Log.v(TAG, "updateTTY: enabled: " + enabled);

        if (enabled) {
            // TTY is on
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY on");
            mIconController.setIcon(mSlotTty, R.drawable.stat_sys_tty_mode,
                    mContext.getString(R.string.accessibility_tty_enabled));
            mIconController.setIconVisibility(mSlotTty, true);
        } else {
            // TTY is off
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY off");
            mIconController.setIconVisibility(mSlotTty, false);
        }
    }

    private void updateCast() {
        boolean isCasting = false;
        for (CastDevice device : mCast.getCastDevices()) {
            if (device.state == CastDevice.STATE_CONNECTING
                    || device.state == CastDevice.STATE_CONNECTED) {
                isCasting = true;
                break;
            }
        }
        if (DEBUG) Log.v(TAG, "updateCast: isCasting: " + isCasting);
        mHandler.removeCallbacks(mRemoveCastIconRunnable);
        if (isCasting) {
            mIconController.setIcon(mSlotCast, R.drawable.stat_sys_cast,
                    mContext.getString(R.string.accessibility_casting));
            mIconController.setIconVisibility(mSlotCast, true);
        } else {
            // don't turn off the screen-record icon for a few seconds, just to make sure the user
            // has seen it
            if (DEBUG) Log.v(TAG, "updateCast: hiding icon in 3 sec...");
            mHandler.postDelayed(mRemoveCastIconRunnable, 3000);
        }
    }

    private void updateManagedProfile() {
        // getLastResumedActivityUserId needds to acquire the AM lock, which may be contended in
        // some cases. Since it doesn't really matter here whether it's updated in this frame
        // or in the next one, we call this method from our UI offload thread.
        mUiOffloadThread.submit(() -> {
            final int userId;
            try {
                userId = ActivityTaskManager.getService().getLastResumedActivityUserId();
                boolean isManagedProfile = mUserManager.isManagedProfile(userId);
                mHandler.post(() -> {
                    final boolean showIcon;
                    if (isManagedProfile &&
                            (!mKeyguardMonitor.isShowing() || mKeyguardMonitor.isOccluded())) {
                        showIcon = true;
                        mIconController.setIcon(mSlotManagedProfile,
                                R.drawable.stat_sys_managed_profile_status,
                                mContext.getString(R.string.accessibility_managed_profile));
                    } else {
                        showIcon = false;
                    }
                    if (mManagedProfileIconVisible != showIcon) {
                        mIconController.setIconVisibility(mSlotManagedProfile, showIcon);
                        mManagedProfileIconVisible = showIcon;
                    }
                });
            } catch (RemoteException e) {
                Log.w(TAG, "updateManagedProfile: ", e);
            }
        });
    }

    private void updateForegroundInstantApps() {
        NotificationManager noMan = mContext.getSystemService(NotificationManager.class);
        ArraySet<Pair<String, Integer>> notifs = new ArraySet<>(mCurrentNotifs);
        IPackageManager pm = AppGlobals.getPackageManager();
        mCurrentNotifs.clear();
        mUiOffloadThread.submit(() -> {
            try {
                final StackInfo focusedStack =
                        ActivityTaskManager.getService().getFocusedStackInfo();
                if (focusedStack != null) {
                    final int windowingMode =
                            focusedStack.configuration.windowConfiguration.getWindowingMode();
                    if (windowingMode == WINDOWING_MODE_FULLSCREEN
                            || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                        checkStack(focusedStack, notifs, noMan, pm);
                    }
                }
                if (mDockedStackExists) {
                    checkStack(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_UNDEFINED,
                            notifs, noMan, pm);
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            // Cancel all the leftover notifications that don't have a foreground process anymore.
            notifs.forEach(v -> noMan.cancelAsUser(v.first, SystemMessage.NOTE_INSTANT_APPS,
                    new UserHandle(v.second)));
        });
    }

    private void checkStack(int windowingMode, int activityType,
            ArraySet<Pair<String, Integer>> notifs, NotificationManager noMan, IPackageManager pm) {
        try {
            final StackInfo info =
                    ActivityTaskManager.getService().getStackInfo(windowingMode, activityType);
            checkStack(info, notifs, noMan, pm);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }
    private void checkStack(StackInfo info, ArraySet<Pair<String, Integer>> notifs,
            NotificationManager noMan, IPackageManager pm) {
        try {
            if (info == null || info.topActivity == null) return;
            String pkg = info.topActivity.getPackageName();
            if (!hasNotif(notifs, pkg, info.userId)) {
                // TODO: Optimize by not always needing to get application info.
                // Maybe cache non-ephemeral packages?
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES, info.userId);
                if (appInfo.isInstantApp()) {
                    postEphemeralNotif(pkg, info.userId, appInfo, noMan, info.taskIds[info.taskIds.length - 1]);
                }
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private void postEphemeralNotif(String pkg, int userId, ApplicationInfo appInfo,
            NotificationManager noMan, int taskId) {
        final Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                mContext.getString(R.string.instant_apps));
        mCurrentNotifs.add(new Pair<>(pkg, userId));

        String helpUrl = mContext.getString(R.string.instant_apps_help_url);
        boolean hasHelpUrl = !helpUrl.isEmpty();
        String message = mContext.getString(hasHelpUrl
                ? R.string.instant_apps_message_with_help
                : R.string.instant_apps_message);

        UserHandle user = UserHandle.of(userId);
        PendingIntent appInfoAction = PendingIntent.getActivityAsUser(mContext, 0,
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", pkg, null)), 0, null, user);
        Action action = new Notification.Action.Builder(null, mContext.getString(R.string.app_info),
                appInfoAction).build();
        PendingIntent helpCenterIntent = hasHelpUrl
                ? PendingIntent.getActivityAsUser(mContext, 0,
                new Intent(Intent.ACTION_VIEW).setData(Uri.parse(
                        helpUrl)),
                0, null, user)
                : null;

        Intent browserIntent = getTaskIntent(taskId, userId);
        Notification.Builder builder = new Notification.Builder(mContext,
                NotificationChannels.GENERAL);
        if (browserIntent != null && browserIntent.isWebIntent()) {
            // Make sure that this doesn't resolve back to an instant app
            browserIntent.setComponent(null)
                    .setPackage(null)
                    .addFlags(Intent.FLAG_IGNORE_EPHEMERAL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            PendingIntent pendingIntent = PendingIntent.getActivityAsUser(mContext,
                    0 /* requestCode */, browserIntent, 0 /* flags */, null, user);
            ComponentName aiaComponent = null;
            try {
                aiaComponent = AppGlobals.getPackageManager().getInstantAppInstallerComponent();
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            Intent goToWebIntent = new Intent()
                    .setComponent(aiaComponent)
                    .setAction(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addCategory("unique:" + System.currentTimeMillis())
                    .putExtra(Intent.EXTRA_PACKAGE_NAME, appInfo.packageName)
                    .putExtra(Intent.EXTRA_VERSION_CODE, (int) (appInfo.versionCode & 0x7fffffff))
                    .putExtra(Intent.EXTRA_LONG_VERSION_CODE, appInfo.versionCode)
                    .putExtra(Intent.EXTRA_INSTANT_APP_FAILURE, pendingIntent);

            PendingIntent webPendingIntent = PendingIntent.getActivityAsUser(mContext, 0,
                    goToWebIntent, 0, null, user);
            Action webAction = new Notification.Action.Builder(null,
                    mContext.getString(R.string.go_to_web),
                    webPendingIntent).build();
            builder.addAction(webAction);
        }

        noMan.notifyAsUser(pkg, SystemMessage.NOTE_INSTANT_APPS, builder
                        .addExtras(extras)
                        .addAction(action)
                        .setContentIntent(helpCenterIntent)
                        .setColor(mContext.getColor(R.color.instant_apps_color))
                        .setContentTitle(mContext.getString(R.string.instant_apps_title,
                                appInfo.loadLabel(mContext.getPackageManager())))
                        .setLargeIcon(Icon.createWithResource(pkg, appInfo.icon))
                        .setSmallIcon(Icon.createWithResource(mContext.getPackageName(),
                                R.drawable.instant_icon))
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setOngoing(true)
                        .build(),
                new UserHandle(userId));
    }

    private Intent getTaskIntent(int taskId, int userId) {
        try {
            final List<ActivityManager.RecentTaskInfo> tasks =
                    ActivityTaskManager.getService().getRecentTasks(
                            NUM_TASKS_FOR_INSTANT_APP_INFO, 0, userId).getList();
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id == taskId) {
                    return tasks.get(i).baseIntent;
                }
            }
        } catch (RemoteException e) {
            // Fall through
        }
        return null;
    }

    private boolean hasNotif(ArraySet<Pair<String, Integer>> notifs, String pkg, int userId) {
        Pair<String, Integer> key = new Pair<>(pkg, userId);
        if (notifs.remove(key)) {
            mCurrentNotifs.add(key);
            return true;
        }
        return false;
    }

    private final SynchronousUserSwitchObserver mUserSwitchListener =
            new SynchronousUserSwitchObserver() {
                @Override
                public void onUserSwitching(int newUserId) throws RemoteException {
                    mHandler.post(() -> mUserInfoController.reloadUserInfo());
                }

                @Override
                public void onUserSwitchComplete(int newUserId) throws RemoteException {
                    mHandler.post(() -> {
                        updateAlarm();
                        updateManagedProfile();
                        updateForegroundInstantApps();
                    });
                }
            };

    private final HotspotController.Callback mHotspotCallback = new HotspotController.Callback() {
        @Override
        public void onHotspotChanged(boolean enabled, int numDevices) {
            mIconController.setIconVisibility(mSlotHotspot, enabled);
        }
    };

    private final CastController.Callback mCastCallback = new CastController.Callback() {
        @Override
        public void onCastDevicesChanged() {
            updateCast();
        }
    };

    private final NextAlarmController.NextAlarmChangeCallback mNextAlarmCallback =
            new NextAlarmController.NextAlarmChangeCallback() {
                @Override
                public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
                    mNextAlarm = nextAlarm;
                    updateAlarm();
                }
            };

    @Override
    public void appTransitionStarting(int displayId, long startTime, long duration,
            boolean forced) {
        if (mContext.getDisplayId() == displayId) {
            updateManagedProfile();
            updateForegroundInstantApps();
        }
    }

    @Override
    public void onKeyguardShowingChanged() {
        updateManagedProfile();
        updateForegroundInstantApps();
    }

    @Override
    public void onUserSetupChanged() {
        boolean userSetup = mProvisionedController.isUserSetup(
                mProvisionedController.getCurrentUser());
        if (mCurrentUserSetup == userSetup) return;
        mCurrentUserSetup = userSetup;
        updateAlarm();
    }

    @Override
    public void preloadRecentApps() {
        updateForegroundInstantApps();
    }

    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        boolean portrait = RotationLockTile.isCurrentOrientationLockPortrait(
                mRotationLockController, mContext);
        if (rotationLocked) {
            if (portrait) {
                mIconController.setIcon(mSlotRotate, R.drawable.stat_sys_rotate_portrait,
                        mContext.getString(R.string.accessibility_rotation_lock_on_portrait));
            } else {
                mIconController.setIcon(mSlotRotate, R.drawable.stat_sys_rotate_landscape,
                        mContext.getString(R.string.accessibility_rotation_lock_on_landscape));
            }
            mIconController.setIconVisibility(mSlotRotate, true);
        } else {
            mIconController.setIconVisibility(mSlotRotate, false);
        }
    }

    private void updateHeadsetPlug(Intent intent) {
        boolean connected = intent.getIntExtra("state", 0) != 0;
        boolean hasMic = intent.getIntExtra("microphone", 0) != 0;
        if (connected) {
            String contentDescription = mContext.getString(hasMic
                    ? R.string.accessibility_status_bar_headset
                    : R.string.accessibility_status_bar_headphones);
            mIconController.setIcon(mSlotHeadset, hasMic ? R.drawable.ic_headset_mic
                    : R.drawable.ic_headset, contentDescription);
            mIconController.setIconVisibility(mSlotHeadset, true);
        } else {
            mIconController.setIconVisibility(mSlotHeadset, false);
        }
    }

    @Override
    public void onDataSaverChanged(boolean isDataSaving) {
        mIconController.setIconVisibility(mSlotDataSaver, isDataSaving);
    }

    @Override  // PrivacyItemController.Callback
    public void privacyChanged(List<PrivacyItem> privacyItems) {
        updatePrivacyItems(privacyItems);
    }

    private void updatePrivacyItems(List<PrivacyItem> items) {
        boolean showCamera = false;
        boolean showMicrophone = false;
        boolean showLocation = false;
        for (PrivacyItem item : items) {
            if (item == null /* b/124234367 */) {
                if (DEBUG) {
                    Log.e(TAG, "updatePrivacyItems - null item found");
                    StringWriter out = new StringWriter();
                    mPrivacyItemController.dump(null, new PrintWriter(out), null);
                    Log.e(TAG, out.toString());
                }
                continue;
            }
            switch (item.getPrivacyType()) {
                case TYPE_CAMERA:
                    showCamera = true;
                    break;
                case TYPE_LOCATION:
                    showLocation = true;
                    break;
                case TYPE_MICROPHONE:
                    showMicrophone = true;
                    break;
            }
        }

        mIconController.setIconVisibility(mSlotCamera, showCamera);
        mIconController.setIconVisibility(mSlotMicrophone, showMicrophone);
        mIconController.setIconVisibility(mSlotLocation, showLocation);
    }

    private final TaskStackChangeListener mTaskListener = new TaskStackChangeListener() {
        @Override
        public void onTaskStackChanged() {
            // Listen for changes to stacks and then check which instant apps are foreground.
            updateForegroundInstantApps();
        }
    };

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case AudioManager.RINGER_MODE_CHANGED_ACTION:
                case AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION:
                    updateVolumeZen();
                    break;
                case TelephonyIntents.ACTION_SIM_STATE_CHANGED:
                    // Avoid rebroadcast because SysUI is direct boot aware.
                    if (intent.getBooleanExtra(TelephonyIntents.EXTRA_REBROADCAST_ON_UNLOCK,
                            false)) {
                        break;
                    }
                    updateSimState(intent);
                    break;
                case TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED:
                    updateTTY(intent.getIntExtra(TelecomManager.EXTRA_CURRENT_TTY_MODE,
                            TelecomManager.TTY_MODE_OFF));
                    break;
                case Intent.ACTION_MANAGED_PROFILE_AVAILABLE:
                case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
                case Intent.ACTION_MANAGED_PROFILE_REMOVED:
                    updateManagedProfile();
                    break;
                case AudioManager.ACTION_HEADSET_PLUG:
                    updateHeadsetPlug(intent);
                    break;
            }
        }
    };

    private Runnable mRemoveCastIconRunnable = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.v(TAG, "updateCast: hiding icon NOW");
            mIconController.setIconVisibility(mSlotCast, false);
        }
    };
}
