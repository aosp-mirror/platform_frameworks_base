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

import static android.app.admin.DevicePolicyResources.Strings.SystemUi.STATUS_BAR_WORK_ICON_ACCESSIBILITY;

import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.NotificationManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.service.notification.ZenModeConfig;
import android.telecom.TelecomManager;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import androidx.lifecycle.Observer;

import com.android.systemui.Flags;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.display.domain.interactor.ConnectedDisplayInteractor;
import com.android.systemui.privacy.PrivacyItem;
import com.android.systemui.privacy.PrivacyItemController;
import com.android.systemui.privacy.PrivacyType;
import com.android.systemui.privacy.logging.PrivacyLogger;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.qs.tiles.RotationLockTile;
import com.android.systemui.res.R;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.screenrecord.data.model.ScreenRecordModel;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastDevice;
import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DataSaverController.Listener;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;
import com.android.systemui.statusbar.policy.SensorPrivacyController;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.time.DateFormatUtil;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * This class contains all of the policy about which icons are installed in the status bar at boot
 * time. It goes through the normal API for icons, even though it probably strictly doesn't need to.
 */
public class PhoneStatusBarPolicy
        implements BluetoothController.Callback,
                CommandQueue.Callbacks,
                RotationLockControllerCallback,
                Listener,
                ZenModeController.Callback,
                DeviceProvisionedListener,
                KeyguardStateController.Callback,
                PrivacyItemController.Callback,
                LocationController.LocationChangeCallback,
                RecordingController.RecordingStateChangeCallback {
    private static final String TAG = "PhoneStatusBarPolicy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    static final int LOCATION_STATUS_ICON_ID = PrivacyType.TYPE_LOCATION.getIconId();

    private final String mSlotCast;
    private final String mSlotHotspot;
    private final String mSlotBluetooth;
    private final String mSlotTty;
    private final String mSlotZen;
    private final String mSlotMute;
    private final String mSlotVibrate;
    private final String mSlotAlarmClock;
    private final String mSlotManagedProfile;
    private final String mSlotRotate;
    private final String mSlotHeadset;
    private final String mSlotDataSaver;
    private final String mSlotLocation;
    private final String mSlotMicrophone;
    private final String mSlotCamera;
    private final String mSlotSensorsOff;
    private final String mSlotScreenRecord;
    private final String mSlotConnectedDisplay;
    private final int mDisplayId;
    private final SharedPreferences mSharedPreferences;
    private final DateFormatUtil mDateFormatUtil;
    private final JavaAdapter mJavaAdapter;
    private final ConnectedDisplayInteractor mConnectedDisplayInteractor;
    private final TelecomManager mTelecomManager;

    private final Handler mHandler;
    private final CastController mCast;
    private final HotspotController mHotspot;
    private final NextAlarmController mNextAlarmController;
    private final AlarmManager mAlarmManager;
    private final UserInfoController mUserInfoController;
    private final UserManager mUserManager;
    private final UserTracker mUserTracker;
    private final DevicePolicyManager mDevicePolicyManager;
    private final StatusBarIconController mIconController;
    private final CommandQueue mCommandQueue;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final Resources mResources;
    private final RotationLockController mRotationLockController;
    private final DataSaverController mDataSaver;
    private final ZenModeController mZenController;
    private final DeviceProvisionedController mProvisionedController;
    private final KeyguardStateController mKeyguardStateController;
    private final LocationController mLocationController;
    private final PrivacyItemController mPrivacyItemController;
    private final Executor mMainExecutor;
    private final Executor mUiBgExecutor;
    private final SensorPrivacyController mSensorPrivacyController;
    private final RecordingController mRecordingController;
    private final RingerModeTracker mRingerModeTracker;
    private final PrivacyLogger mPrivacyLogger;

    private boolean mZenVisible;
    private boolean mVibrateVisible;
    private boolean mMuteVisible;
    private boolean mCurrentUserSetup;

    private boolean mProfileIconVisible = false;

    private BluetoothController mBluetooth;
    private AlarmManager.AlarmClockInfo mNextAlarm;

    @Inject
    public PhoneStatusBarPolicy(StatusBarIconController iconController,
            CommandQueue commandQueue, BroadcastDispatcher broadcastDispatcher,
            @Main Executor mainExecutor, @UiBackground Executor uiBgExecutor, @Main Looper looper,
            @Main Resources resources, CastController castController,
            HotspotController hotspotController, BluetoothController bluetoothController,
            NextAlarmController nextAlarmController, UserInfoController userInfoController,
            RotationLockController rotationLockController, DataSaverController dataSaverController,
            ZenModeController zenModeController,
            DeviceProvisionedController deviceProvisionedController,
            KeyguardStateController keyguardStateController,
            LocationController locationController,
            SensorPrivacyController sensorPrivacyController, AlarmManager alarmManager,
            UserManager userManager, UserTracker userTracker,
            DevicePolicyManager devicePolicyManager, RecordingController recordingController,
            @Nullable TelecomManager telecomManager, @DisplayId int displayId,
            @Main SharedPreferences sharedPreferences, DateFormatUtil dateFormatUtil,
            RingerModeTracker ringerModeTracker,
            PrivacyItemController privacyItemController,
            PrivacyLogger privacyLogger,
            ConnectedDisplayInteractor connectedDisplayInteractor,
            JavaAdapter javaAdapter
    ) {
        mIconController = iconController;
        mCommandQueue = commandQueue;
        mConnectedDisplayInteractor = connectedDisplayInteractor;
        mBroadcastDispatcher = broadcastDispatcher;
        mHandler = new Handler(looper);
        mResources = resources;
        mCast = castController;
        mHotspot = hotspotController;
        mBluetooth = bluetoothController;
        mNextAlarmController = nextAlarmController;
        mAlarmManager = alarmManager;
        mUserInfoController = userInfoController;
        mUserManager = userManager;
        mUserTracker = userTracker;
        mDevicePolicyManager = devicePolicyManager;
        mRotationLockController = rotationLockController;
        mDataSaver = dataSaverController;
        mZenController = zenModeController;
        mProvisionedController = deviceProvisionedController;
        mKeyguardStateController = keyguardStateController;
        mLocationController = locationController;
        mPrivacyItemController = privacyItemController;
        mSensorPrivacyController = sensorPrivacyController;
        mRecordingController = recordingController;
        mMainExecutor = mainExecutor;
        mUiBgExecutor = uiBgExecutor;
        mTelecomManager = telecomManager;
        mRingerModeTracker = ringerModeTracker;
        mPrivacyLogger = privacyLogger;
        mJavaAdapter = javaAdapter;

        mSlotCast = resources.getString(com.android.internal.R.string.status_bar_cast);
        mSlotConnectedDisplay = resources.getString(
                com.android.internal.R.string.status_bar_connected_display);
        mSlotHotspot = resources.getString(com.android.internal.R.string.status_bar_hotspot);
        mSlotBluetooth = resources.getString(com.android.internal.R.string.status_bar_bluetooth);
        mSlotTty = resources.getString(com.android.internal.R.string.status_bar_tty);
        mSlotZen = resources.getString(com.android.internal.R.string.status_bar_zen);
        mSlotMute = resources.getString(com.android.internal.R.string.status_bar_mute);
        mSlotVibrate = resources.getString(com.android.internal.R.string.status_bar_volume);
        mSlotAlarmClock = resources.getString(com.android.internal.R.string.status_bar_alarm_clock);
        mSlotManagedProfile = resources.getString(
                com.android.internal.R.string.status_bar_managed_profile);
        mSlotRotate = resources.getString(com.android.internal.R.string.status_bar_rotate);
        mSlotHeadset = resources.getString(com.android.internal.R.string.status_bar_headset);
        mSlotDataSaver = resources.getString(com.android.internal.R.string.status_bar_data_saver);
        mSlotLocation = resources.getString(com.android.internal.R.string.status_bar_location);
        mSlotMicrophone = resources.getString(com.android.internal.R.string.status_bar_microphone);
        mSlotCamera = resources.getString(com.android.internal.R.string.status_bar_camera);
        mSlotSensorsOff = resources.getString(com.android.internal.R.string.status_bar_sensors_off);
        mSlotScreenRecord = resources.getString(
                com.android.internal.R.string.status_bar_screen_record);

        mDisplayId = displayId;
        mSharedPreferences = sharedPreferences;
        mDateFormatUtil = dateFormatUtil;
    }

    /** Initialize the object after construction. */
    public void init() {
        // listen for broadcasts
        IntentFilter filter = new IntentFilter();

        filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
        filter.addAction(Intent.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        filter.addAction(Intent.ACTION_PROFILE_REMOVED);
        filter.addAction(Intent.ACTION_PROFILE_ACCESSIBLE);
        filter.addAction(Intent.ACTION_PROFILE_INACCESSIBLE);
        mBroadcastDispatcher.registerReceiverWithHandler(mIntentReceiver, filter, mHandler);
        Observer<Integer> observer = ringer -> mHandler.post(this::updateVolumeZen);

        mRingerModeTracker.getRingerMode().observeForever(observer);
        mRingerModeTracker.getRingerModeInternal().observeForever(observer);

        // listen for user / profile change.
        mUserTracker.addCallback(mUserSwitchListener, mMainExecutor);

        // TTY status
        updateTTY();

        // bluetooth status
        updateBluetooth();

        // Alarm clock
        mIconController.setIcon(mSlotAlarmClock, R.drawable.stat_sys_alarm, null);
        mIconController.setIconVisibility(mSlotAlarmClock, false);

        // zen
        mIconController.setIcon(mSlotZen, R.drawable.stat_sys_dnd, null);
        mIconController.setIconVisibility(mSlotZen, false);

        // vibrate
        mIconController.setIcon(mSlotVibrate, R.drawable.stat_sys_ringer_vibrate,
                mResources.getString(R.string.accessibility_ringer_vibrate));
        mIconController.setIconVisibility(mSlotVibrate, false);
        // mute
        mIconController.setIcon(mSlotMute, R.drawable.stat_sys_ringer_silent,
                mResources.getString(R.string.accessibility_ringer_silent));
        mIconController.setIconVisibility(mSlotMute, false);
        updateVolumeZen();

        // cast
        mIconController.setIcon(mSlotCast, R.drawable.stat_sys_cast, null);
        mIconController.setIconVisibility(mSlotCast, false);

        // connected display
        mIconController.setIcon(mSlotConnectedDisplay, R.drawable.stat_sys_connected_display,
                mResources.getString(R.string.connected_display_icon_desc));
        mIconController.setIconVisibility(mSlotConnectedDisplay, false);

        // hotspot
        mIconController.setIcon(mSlotHotspot, R.drawable.stat_sys_hotspot,
                mResources.getString(R.string.accessibility_status_bar_hotspot));
        mIconController.setIconVisibility(mSlotHotspot, mHotspot.isHotspotEnabled());

        // profile
        updateProfileIcon();

        // data saver
        mIconController.setIcon(mSlotDataSaver, R.drawable.stat_sys_data_saver,
                mResources.getString(R.string.accessibility_data_saver_on));
        mIconController.setIconVisibility(mSlotDataSaver, false);


        // privacy items
        String microphoneString = mResources.getString(PrivacyType.TYPE_MICROPHONE.getNameId());
        String microphoneDesc = mResources.getString(
                R.string.ongoing_privacy_chip_content_multiple_apps, microphoneString);
        mIconController.setIcon(mSlotMicrophone, PrivacyType.TYPE_MICROPHONE.getIconId(),
                microphoneDesc);
        mIconController.setIconVisibility(mSlotMicrophone, false);

        String cameraString = mResources.getString(PrivacyType.TYPE_CAMERA.getNameId());
        String cameraDesc = mResources.getString(
                R.string.ongoing_privacy_chip_content_multiple_apps, cameraString);
        mIconController.setIcon(mSlotCamera, PrivacyType.TYPE_CAMERA.getIconId(),
                cameraDesc);
        mIconController.setIconVisibility(mSlotCamera, false);

        mIconController.setIcon(mSlotLocation, LOCATION_STATUS_ICON_ID,
                mResources.getString(R.string.accessibility_location_active));
        mIconController.setIconVisibility(mSlotLocation, false);

        // sensors off
        mIconController.setIcon(mSlotSensorsOff, R.drawable.stat_sys_sensors_off,
                mResources.getString(R.string.accessibility_sensors_off_active));
        mIconController.setIconVisibility(mSlotSensorsOff,
                mSensorPrivacyController.isSensorPrivacyEnabled());

        // screen record
        mIconController.setIcon(mSlotScreenRecord, R.drawable.stat_sys_screen_record, null);
        mIconController.setIconVisibility(mSlotScreenRecord, false);

        mRotationLockController.addCallback(this);
        mBluetooth.addCallback(this);
        mProvisionedController.addCallback(this);
        mCurrentUserSetup = mProvisionedController.isCurrentUserSetup();
        mZenController.addCallback(this);
        if (!Flags.statusBarScreenSharingChips()) {
            // If the flag is enabled, the cast icon is handled in the new screen sharing chips
            // instead of here so we don't need to listen for events here.
            mCast.addCallback(mCastCallback);
        }
        mHotspot.addCallback(mHotspotCallback);
        mNextAlarmController.addCallback(mNextAlarmCallback);
        mDataSaver.addCallback(this);
        mKeyguardStateController.addCallback(this);
        mPrivacyItemController.addCallback(this);
        mSensorPrivacyController.addCallback(mSensorPrivacyListener);
        mLocationController.addCallback(this);
        if (!Flags.statusBarScreenSharingChips()) {
            // If the flag is enabled, the screen record icon is handled in the new screen sharing
            // chips instead of here so we don't need to listen for events here.
            mRecordingController.addCallback(this);
        }
        mJavaAdapter.alwaysCollectFlow(mConnectedDisplayInteractor.getConnectedDisplayState(),
                this::onConnectedDisplayAvailabilityChanged);

        mCommandQueue.addCallback(this);
    }

    private String getManagedProfileAccessibilityString() {
        return mDevicePolicyManager.getResources().getString(
                STATUS_BAR_WORK_ICON_ACCESSIBILITY,
                () -> mResources.getString(R.string.accessibility_managed_profile));
    }

    @Override
    public void onZenChanged(int zen) {
        updateVolumeZen();
    }

    @Override
    public void onConsolidatedPolicyChanged(NotificationManager.Policy policy) {
        updateVolumeZen();
    }

    private void updateAlarm() {
        final AlarmClockInfo alarm = mAlarmManager.getNextAlarmClock(mUserTracker.getUserId());
        final boolean hasAlarm = alarm != null && alarm.getTriggerTime() > 0;
        int zen = mZenController.getZen();
        final boolean zenNone = zen == Global.ZEN_MODE_NO_INTERRUPTIONS;
        mIconController.setIcon(mSlotAlarmClock, zenNone ? R.drawable.stat_sys_alarm_dim
                : R.drawable.stat_sys_alarm, buildAlarmContentDescription());
        mIconController.setIconVisibility(mSlotAlarmClock, mCurrentUserSetup && hasAlarm);
    }

    private String buildAlarmContentDescription() {
        if (mNextAlarm == null) {
            return mResources.getString(R.string.status_bar_alarm);
        }

        String skeleton = mDateFormatUtil.is24HourFormat() ? "EHm" : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        String dateString = DateFormat.format(pattern, mNextAlarm.getTriggerTime()).toString();

        return mResources.getString(R.string.accessibility_quick_settings_alarm, dateString);
    }

    private final void updateVolumeZen() {
        boolean zenVisible = false;
        int zenIconId = 0;
        String zenDescription = null;

        boolean vibrateVisible = false;
        boolean muteVisible = false;
        int zen = mZenController.getZen();

        if (DndTile.isVisible(mSharedPreferences) || DndTile.isCombinedIcon(mSharedPreferences)) {
            zenVisible = zen != Global.ZEN_MODE_OFF;
            zenIconId = R.drawable.stat_sys_dnd;
            zenDescription = mResources.getString(R.string.quick_settings_dnd_label);
        } else if (zen == Global.ZEN_MODE_NO_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_dnd;
            zenDescription = mResources.getString(R.string.interruption_level_none);
        } else if (zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_dnd;
            zenDescription = mResources.getString(R.string.interruption_level_priority);
        }

        if (!ZenModeConfig.isZenOverridingRinger(zen, mZenController.getConsolidatedPolicy())) {
            final Integer ringerModeInternal =
                    mRingerModeTracker.getRingerModeInternal().getValue();
            if (ringerModeInternal != null) {
                if (ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE) {
                    vibrateVisible = true;
                } else if (ringerModeInternal == AudioManager.RINGER_MODE_SILENT) {
                    muteVisible = true;
                }
            }
        }

        if (zenVisible) {
            mIconController.setIcon(mSlotZen, zenIconId, zenDescription);
        }
        if (zenVisible != mZenVisible) {
            mIconController.setIconVisibility(mSlotZen, zenVisible);
            mZenVisible = zenVisible;
        }

        if (vibrateVisible != mVibrateVisible) {
            mIconController.setIconVisibility(mSlotVibrate, vibrateVisible);
            mVibrateVisible = vibrateVisible;
        }

        if (muteVisible != mMuteVisible) {
            mIconController.setIconVisibility(mSlotMute, muteVisible);
            mMuteVisible = muteVisible;
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
        int iconId = R.drawable.stat_sys_data_bluetooth_connected;
        String contentDescription =
                mResources.getString(R.string.accessibility_quick_settings_bluetooth_on);
        boolean bluetoothVisible = false;
        if (mBluetooth != null) {
            if (mBluetooth.isBluetoothConnected()
                    && (mBluetooth.isBluetoothAudioActive()
                    || !mBluetooth.isBluetoothAudioProfileOnly())) {
                contentDescription = mResources.getString(
                        R.string.accessibility_bluetooth_connected);
                bluetoothVisible = mBluetooth.isBluetoothEnabled();
            }
        }

        mIconController.setIcon(mSlotBluetooth, iconId, contentDescription);
        mIconController.setIconVisibility(mSlotBluetooth, bluetoothVisible);
    }

    private final void updateTTY() {
        if (mTelecomManager == null) {
            updateTTY(TelecomManager.TTY_MODE_OFF);
        } else {
            updateTTY(mTelecomManager.getCurrentTtyMode());
        }
    }

    private final void updateTTY(int currentTtyMode) {
        boolean enabled = currentTtyMode != TelecomManager.TTY_MODE_OFF;

        if (DEBUG) Log.v(TAG, "updateTTY: enabled: " + enabled);

        if (enabled) {
            // TTY is on
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY on");
            mIconController.setIcon(mSlotTty, R.drawable.stat_sys_tty_mode,
                    mResources.getString(R.string.accessibility_tty_enabled));
            mIconController.setIconVisibility(mSlotTty, true);
        } else {
            // TTY is off
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY off");
            mIconController.setIconVisibility(mSlotTty, false);
        }
    }

    private void updateCast() {
        if (Flags.statusBarScreenSharingChips()) {
            // The cast icon is handled in the new screen sharing chips instead of here.
            return;
        }

        boolean isCasting = false;
        for (CastDevice device : mCast.getCastDevices()) {
            if (device.isCasting()) {
                isCasting = true;
                break;
            }
        }
        if (DEBUG) Log.v(TAG, "updateCast: isCasting: " + isCasting);
        mHandler.removeCallbacks(mRemoveCastIconRunnable);
        if (isCasting && !mRecordingController.isRecording()) { // screen record has its own icon
            mIconController.setIcon(mSlotCast, R.drawable.stat_sys_cast,
                    mResources.getString(R.string.accessibility_casting));
            mIconController.setIconVisibility(mSlotCast, true);
        } else {
            // don't turn off the screen-record icon for a few seconds, just to make sure the user
            // has seen it
            if (DEBUG) Log.v(TAG, "updateCast: hiding icon in 3 sec...");
            mHandler.postDelayed(mRemoveCastIconRunnable, 3000);
        }
    }

    private void updateProfileIcon() {
        // getLastResumedActivityUserId needs to acquire the AM lock, which may be contended in
        // some cases. Since it doesn't really matter here whether it's updated in this frame
        // or in the next one, we call this method from our UI offload thread.
        mUiBgExecutor.execute(() -> {
            try {
                final int userId = ActivityTaskManager.getService().getLastResumedActivityUserId();
                final int iconResId = mUserManager.getUserStatusBarIconResId(userId);
                mMainExecutor.execute(() -> {
                    final boolean showIcon;
                    if (iconResId != Resources.ID_NULL && (!mKeyguardStateController.isShowing()
                            || mKeyguardStateController.isOccluded())) {
                        String accessibilityString = "";
                        if (android.os.Flags.allowPrivateProfile()
                                && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
                            try {
                                accessibilityString =
                                        mUserManager.getProfileAccessibilityString(userId);
                            } catch (Resources.NotFoundException nfe) {
                                Log.e(TAG, "Accessibility string not found for userId:"
                                        + userId);
                            }
                        } else {
                            accessibilityString = getManagedProfileAccessibilityString();
                        }
                        showIcon = true;
                        mIconController.setIcon(mSlotManagedProfile,
                                iconResId,
                                accessibilityString);
                    } else {
                        showIcon = false;
                    }
                    if (mProfileIconVisible != showIcon) {
                        mIconController.setIconVisibility(mSlotManagedProfile, showIcon);
                        mProfileIconVisible = showIcon;
                    }
                });
            } catch (RemoteException e) {
                Log.w(TAG, "updateProfileIcon: ", e);
            }
        });
    }

    private final UserTracker.Callback mUserSwitchListener =
            new UserTracker.Callback() {
                @Override
                public void onUserChanging(int newUser, Context userContext) {
                    mHandler.post(() -> mUserInfoController.reloadUserInfo());
                }

                @Override
                public void onUserChanged(int newUser, Context userContext) {
                    mHandler.post(() -> {
                        updateAlarm();
                        updateProfileIcon();
                        onUserSetupChanged();
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

    private final SensorPrivacyController.OnSensorPrivacyChangedListener mSensorPrivacyListener =
            new SensorPrivacyController.OnSensorPrivacyChangedListener() {
                @Override
                public void onSensorPrivacyChanged(boolean enabled) {
                    mHandler.post(() -> {
                        mIconController.setIconVisibility(mSlotSensorsOff, enabled);
                    });
                }
            };

    @Override
    public void appTransitionStarting(int displayId, long startTime, long duration,
            boolean forced) {
        if (mDisplayId == displayId) {
            updateProfileIcon();
        }
    }

    @Override
    public void appTransitionFinished(int displayId) {
        if (mDisplayId == displayId) {
            updateProfileIcon();
        }
    }

    @Override
    public void onKeyguardShowingChanged() {
        updateProfileIcon();
    }

    @Override
    public void onUserSetupChanged() {
        boolean userSetup = mProvisionedController.isCurrentUserSetup();
        if (mCurrentUserSetup == userSetup) return;
        mCurrentUserSetup = userSetup;
        updateAlarm();
    }

    @Override
    public void onRotationLockStateChanged(boolean rotationLocked, boolean affordanceVisible) {
        boolean portrait = RotationLockTile.isCurrentOrientationLockPortrait(
                mRotationLockController, mResources);
        if (rotationLocked) {
            if (portrait) {
                mIconController.setIcon(mSlotRotate, R.drawable.stat_sys_rotate_portrait,
                        mResources.getString(R.string.accessibility_rotation_lock_on_portrait));
            } else {
                mIconController.setIcon(mSlotRotate, R.drawable.stat_sys_rotate_landscape,
                        mResources.getString(R.string.accessibility_rotation_lock_on_landscape));
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
            String contentDescription = mResources.getString(hasMic
                    ? R.string.accessibility_status_bar_headset
                    : R.string.accessibility_status_bar_headphones);
            mIconController.setIcon(mSlotHeadset, hasMic ? R.drawable.stat_sys_headset_mic
                    : R.drawable.stat_sys_headset, contentDescription);
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
    public void onPrivacyItemsChanged(List<PrivacyItem> privacyItems) {
        updatePrivacyItems(privacyItems);
    }

    private void updatePrivacyItems(List<PrivacyItem> items) {
        boolean showCamera = false;
        boolean showMicrophone = false;
        boolean showLocation = false;
        for (PrivacyItem item : items) {
            if (item == null /* b/124234367 */) {
                Log.e(TAG, "updatePrivacyItems - null item found");
                StringWriter out = new StringWriter();
                mPrivacyItemController.dump(new PrintWriter(out), null);
                // Throw so we can look into this
                throw new NullPointerException(out.toString());
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

        // Disabling for now, but keeping the log
        /*
        mIconController.setIconVisibility(mSlotCamera, showCamera);
        mIconController.setIconVisibility(mSlotMicrophone, showMicrophone);
        if (mPrivacyItemController.getLocationAvailable()) {
            mIconController.setIconVisibility(mSlotLocation, showLocation);
        }
         */
        mPrivacyLogger.logStatusBarIconsVisible(showCamera, showMicrophone,  showLocation);
    }

    @Override
    public void onLocationActiveChanged(boolean active) {
        if (!mPrivacyItemController.getLocationAvailable()) {
            updateLocationFromController();
        }
    }

    // Updates the status view based on the current state of location requests.
    private void updateLocationFromController() {
        if (mLocationController.isLocationActive()) {
            mIconController.setIconVisibility(mSlotLocation, true);
        } else {
            mIconController.setIconVisibility(mSlotLocation, false);
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case Intent.ACTION_SIM_STATE_CHANGED:
                    // Avoid rebroadcast because SysUI is direct boot aware.
                    if (intent.getBooleanExtra(Intent.EXTRA_REBROADCAST_ON_UNLOCK, false)) {
                        break;
                    }
                    break;
                case TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED:
                    updateTTY(intent.getIntExtra(TelecomManager.EXTRA_CURRENT_TTY_MODE,
                            TelecomManager.TTY_MODE_OFF));
                    break;
                case Intent.ACTION_MANAGED_PROFILE_AVAILABLE:
                case Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE:
                case Intent.ACTION_PROFILE_REMOVED:
                case Intent.ACTION_PROFILE_ACCESSIBLE:
                case Intent.ACTION_PROFILE_INACCESSIBLE:
                    updateProfileIcon();
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
            if (Flags.statusBarScreenSharingChips()) {
                // The cast icon is handled in the new screen sharing chips instead of here.
                return;
            }
            if (DEBUG) Log.v(TAG, "updateCast: hiding icon NOW");
            mIconController.setIconVisibility(mSlotCast, false);
        }
    };

    // Screen Recording
    @Override
    public void onCountdown(long millisUntilFinished) {
        if (Flags.statusBarScreenSharingChips()) {
            // The screen record icon is handled in the new screen sharing chips instead of here.
            return;
        }
        if (DEBUG) Log.d(TAG, "screenrecord: countdown " + millisUntilFinished);
        int countdown =
                (int) ScreenRecordModel.Starting.Companion.toCountdownSeconds(millisUntilFinished);
        int resourceId = R.drawable.stat_sys_screen_record;
        String description = Integer.toString(countdown);
        switch (countdown) {
            case 1:
                resourceId = R.drawable.stat_sys_screen_record_1;
                break;
            case 2:
                resourceId = R.drawable.stat_sys_screen_record_2;
                break;
            case 3:
                resourceId = R.drawable.stat_sys_screen_record_3;
                break;
        }
        mIconController.setIcon(mSlotScreenRecord, resourceId, description);
        mIconController.setIconVisibility(mSlotScreenRecord, true);
        // Set as assertive so talkback will announce the countdown
        mIconController.setIconAccessibilityLiveRegion(mSlotScreenRecord,
                View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
    }

    @Override
    public void onCountdownEnd() {
        if (Flags.statusBarScreenSharingChips()) {
            // The screen record icon is handled in the new screen sharing chips instead of here.
            return;
        }
        if (DEBUG) Log.d(TAG, "screenrecord: hiding icon during countdown");
        mHandler.post(() -> mIconController.setIconVisibility(mSlotScreenRecord, false));
        // Reset talkback priority
        mHandler.post(() -> mIconController.setIconAccessibilityLiveRegion(mSlotScreenRecord,
                View.ACCESSIBILITY_LIVE_REGION_NONE));
    }

    @Override
    public void onRecordingStart() {
        if (Flags.statusBarScreenSharingChips()) {
            // The screen record icon is handled in the new screen sharing chips instead of here.
            return;
        }
        if (DEBUG) Log.d(TAG, "screenrecord: showing icon");
        mIconController.setIcon(mSlotScreenRecord,
                R.drawable.stat_sys_screen_record,
                mResources.getString(R.string.screenrecord_ongoing_screen_only));
        mHandler.post(() -> mIconController.setIconVisibility(mSlotScreenRecord, true));
    }

    @Override
    public void onRecordingEnd() {
        if (Flags.statusBarScreenSharingChips()) {
            // The screen record icon is handled in the new screen sharing chips instead of here.
            return;
        }
        // Ensure this is on the main thread
        if (DEBUG) Log.d(TAG, "screenrecord: hiding icon");
        mHandler.post(() -> mIconController.setIconVisibility(mSlotScreenRecord, false));
    }

    private void onConnectedDisplayAvailabilityChanged(ConnectedDisplayInteractor.State state) {
        boolean visible = state != ConnectedDisplayInteractor.State.DISCONNECTED;

        if (DEBUG) {
            Log.d(TAG, "connected_display: " + (visible ? "showing" : "hiding") + " icon");
        }

        mIconController.setIconVisibility(mSlotConnectedDisplay, visible);
    }
}
