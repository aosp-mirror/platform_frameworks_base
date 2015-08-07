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

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.IUserSwitchObserver;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.telecom.TelecomManager;
import android.util.Log;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.systemui.R;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.BluetoothController.Callback;
import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.CastDevice;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * This class contains all of the policy about which icons are installed in the status
 * bar at boot time.  It goes through the normal API for icons, even though it probably
 * strictly doesn't need to.
 */
public class PhoneStatusBarPolicy implements Callback {
    private static final String TAG = "PhoneStatusBarPolicy";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String SLOT_CAST = "cast";
    private static final String SLOT_HOTSPOT = "hotspot";
    private static final String SLOT_BLUETOOTH = "bluetooth";
    private static final String SLOT_TTY = "tty";
    private static final String SLOT_ZEN = "zen";
    private static final String SLOT_VOLUME = "volume";
    private static final String SLOT_ALARM_CLOCK = "alarm_clock";
    private static final String SLOT_MANAGED_PROFILE = "managed_profile";

    private final Context mContext;
    private final StatusBarManager mService;
    private final Handler mHandler = new Handler();
    private final CastController mCast;
    private final HotspotController mHotspot;
    private final AlarmManager mAlarmManager;
    private final UserInfoController mUserInfoController;

    // Assume it's all good unless we hear otherwise.  We don't always seem
    // to get broadcasts that it *is* there.
    IccCardConstants.State mSimState = IccCardConstants.State.READY;

    private boolean mZenVisible;
    private boolean mVolumeVisible;
    private boolean mCurrentUserSetup;

    private int mZen;

    private boolean mManagedProfileFocused = false;
    private boolean mManagedProfileIconVisible = true;

    private boolean mKeyguardVisible = true;
    private BluetoothController mBluetooth;

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)) {
                updateAlarm();
            }
            else if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION) ||
                    action.equals(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION)) {
                updateVolumeZen();
            }
            else if (action.equals(TelephonyIntents.ACTION_SIM_STATE_CHANGED)) {
                updateSimState(intent);
            }
            else if (action.equals(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED)) {
                updateTTY(intent);
            }
        }
    };

    public PhoneStatusBarPolicy(Context context, CastController cast, HotspotController hotspot,
            UserInfoController userInfoController, BluetoothController bluetooth) {
        mContext = context;
        mCast = cast;
        mHotspot = hotspot;
        mBluetooth = bluetooth;
        mBluetooth.addStateChangedCallback(this);
        mService = (StatusBarManager) context.getSystemService(Context.STATUS_BAR_SERVICE);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mUserInfoController = userInfoController;

        // listen for broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
        filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
        filter.addAction(TelecomManager.ACTION_CURRENT_TTY_MODE_CHANGED);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);

        // listen for user / profile change.
        try {
            ActivityManagerNative.getDefault().registerUserSwitchObserver(mUserSwitchListener);
        } catch (RemoteException e) {
            // Ignore
        }

        // TTY status
        mService.setIcon(SLOT_TTY,  R.drawable.stat_sys_tty_mode, 0, null);
        mService.setIconVisibility(SLOT_TTY, false);

        // bluetooth status
        updateBluetooth();

        // Alarm clock
        mService.setIcon(SLOT_ALARM_CLOCK, R.drawable.stat_sys_alarm, 0, null);
        mService.setIconVisibility(SLOT_ALARM_CLOCK, false);

        // zen
        mService.setIcon(SLOT_ZEN, R.drawable.stat_sys_zen_important, 0, null);
        mService.setIconVisibility(SLOT_ZEN, false);

        // volume
        mService.setIcon(SLOT_VOLUME, R.drawable.stat_sys_ringer_vibrate, 0, null);
        mService.setIconVisibility(SLOT_VOLUME, false);
        updateVolumeZen();

        // cast
        mService.setIcon(SLOT_CAST, R.drawable.stat_sys_cast, 0, null);
        mService.setIconVisibility(SLOT_CAST, false);
        mCast.addCallback(mCastCallback);

        // hotspot
        mService.setIcon(SLOT_HOTSPOT, R.drawable.stat_sys_hotspot, 0,
                mContext.getString(R.string.accessibility_status_bar_hotspot));
        mService.setIconVisibility(SLOT_HOTSPOT, mHotspot.isHotspotEnabled());
        mHotspot.addCallback(mHotspotCallback);

        // managed profile
        mService.setIcon(SLOT_MANAGED_PROFILE, R.drawable.stat_sys_managed_profile_status, 0,
                mContext.getString(R.string.accessibility_managed_profile));
        mService.setIconVisibility(SLOT_MANAGED_PROFILE, false);
    }

    public void setZenMode(int zen) {
        mZen = zen;
        updateVolumeZen();
    }

    private void updateAlarm() {
        final AlarmClockInfo alarm = mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        final boolean hasAlarm = alarm != null && alarm.getTriggerTime() > 0;
        final boolean zenNone = mZen == Global.ZEN_MODE_NO_INTERRUPTIONS;
        mService.setIcon(SLOT_ALARM_CLOCK, zenNone ? R.drawable.stat_sys_alarm_dim
                : R.drawable.stat_sys_alarm, 0, null);
        mService.setIconVisibility(SLOT_ALARM_CLOCK, mCurrentUserSetup && hasAlarm);
    }

    private final void updateSimState(Intent intent) {
        String stateExtra = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
        if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(stateExtra)) {
            mSimState = IccCardConstants.State.ABSENT;
        }
        else if (IccCardConstants.INTENT_VALUE_ICC_CARD_IO_ERROR.equals(stateExtra)) {
            mSimState = IccCardConstants.State.CARD_IO_ERROR;
        }
        else if (IccCardConstants.INTENT_VALUE_ICC_READY.equals(stateExtra)) {
            mSimState = IccCardConstants.State.READY;
        }
        else if (IccCardConstants.INTENT_VALUE_ICC_LOCKED.equals(stateExtra)) {
            final String lockedReason =
                    intent.getStringExtra(IccCardConstants.INTENT_KEY_LOCKED_REASON);
            if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PIN.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PIN_REQUIRED;
            }
            else if (IccCardConstants.INTENT_VALUE_LOCKED_ON_PUK.equals(lockedReason)) {
                mSimState = IccCardConstants.State.PUK_REQUIRED;
            }
            else {
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

        if (DndTile.isVisible(mContext) || DndTile.isCombinedIcon(mContext)) {
            zenVisible = mZen != Global.ZEN_MODE_OFF;
            zenIconId = mZen == Global.ZEN_MODE_NO_INTERRUPTIONS
                    ? R.drawable.stat_sys_dnd_total_silence : R.drawable.stat_sys_dnd;
            zenDescription = mContext.getString(R.string.quick_settings_dnd_label);
        } else if (mZen == Global.ZEN_MODE_NO_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_zen_none;
            zenDescription = mContext.getString(R.string.interruption_level_none);
        } else if (mZen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            zenVisible = true;
            zenIconId = R.drawable.stat_sys_zen_important;
            zenDescription = mContext.getString(R.string.interruption_level_priority);
        }

        if (DndTile.isVisible(mContext) && !DndTile.isCombinedIcon(mContext)
                && audioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_SILENT) {
            volumeVisible = true;
            volumeIconId = R.drawable.stat_sys_ringer_silent;
            volumeDescription = mContext.getString(R.string.accessibility_ringer_silent);
        } else if (mZen != Global.ZEN_MODE_NO_INTERRUPTIONS && mZen != Global.ZEN_MODE_ALARMS &&
                audioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_VIBRATE) {
            volumeVisible = true;
            volumeIconId = R.drawable.stat_sys_ringer_vibrate;
            volumeDescription = mContext.getString(R.string.accessibility_ringer_vibrate);
        }

        if (zenVisible) {
            mService.setIcon(SLOT_ZEN, zenIconId, 0, zenDescription);
        }
        if (zenVisible != mZenVisible) {
            mService.setIconVisibility(SLOT_ZEN, zenVisible);
            mZenVisible = zenVisible;
        }

        if (volumeVisible) {
            mService.setIcon(SLOT_VOLUME, volumeIconId, 0, volumeDescription);
        }
        if (volumeVisible != mVolumeVisible) {
            mService.setIconVisibility(SLOT_VOLUME, volumeVisible);
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
        boolean bluetoothEnabled = false;
        if (mBluetooth != null) {
            bluetoothEnabled = mBluetooth.isBluetoothEnabled();
            if (mBluetooth.isBluetoothConnected()) {
                iconId = R.drawable.stat_sys_data_bluetooth_connected;
                contentDescription = mContext.getString(R.string.accessibility_bluetooth_connected);
            }
        }

        mService.setIcon(SLOT_BLUETOOTH, iconId, 0, contentDescription);
        mService.setIconVisibility(SLOT_BLUETOOTH, bluetoothEnabled);
    }

    private final void updateTTY(Intent intent) {
        int currentTtyMode = intent.getIntExtra(TelecomManager.EXTRA_CURRENT_TTY_MODE,
                TelecomManager.TTY_MODE_OFF);
        boolean enabled = currentTtyMode != TelecomManager.TTY_MODE_OFF;

        if (DEBUG) Log.v(TAG, "updateTTY: enabled: " + enabled);

        if (enabled) {
            // TTY is on
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY on");
            mService.setIcon(SLOT_TTY, R.drawable.stat_sys_tty_mode, 0,
                    mContext.getString(R.string.accessibility_tty_enabled));
            mService.setIconVisibility(SLOT_TTY, true);
        } else {
            // TTY is off
            if (DEBUG) Log.v(TAG, "updateTTY: set TTY off");
            mService.setIconVisibility(SLOT_TTY, false);
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
        if (isCasting) {
            mService.setIcon(SLOT_CAST, R.drawable.stat_sys_cast, 0,
                    mContext.getString(R.string.accessibility_casting));
        }
        mService.setIconVisibility(SLOT_CAST, isCasting);
    }

    private void profileChanged(int userId) {
        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        UserInfo user = null;
        if (userId == UserHandle.USER_CURRENT) {
            try {
                user = ActivityManagerNative.getDefault().getCurrentUser();
            } catch (RemoteException e) {
                // Ignore
            }
        } else {
            user = userManager.getUserInfo(userId);
        }

        mManagedProfileFocused = user != null && user.isManagedProfile();
        if (DEBUG) Log.v(TAG, "profileChanged: mManagedProfileFocused: " + mManagedProfileFocused);
        // Actually update the icon later when transition starts.
    }

    private void updateManagedProfile() {
        if (DEBUG) Log.v(TAG, "updateManagedProfile: mManagedProfileFocused: "
                + mManagedProfileFocused
                + " mKeyguardVisible: " + mKeyguardVisible);
        boolean showIcon = mManagedProfileFocused && !mKeyguardVisible;
        if (mManagedProfileIconVisible != showIcon) {
            mService.setIconVisibility(SLOT_MANAGED_PROFILE, showIcon);
            mManagedProfileIconVisible = showIcon;
        }
    }

    private final IUserSwitchObserver.Stub mUserSwitchListener =
            new IUserSwitchObserver.Stub() {
                @Override
                public void onUserSwitching(int newUserId, IRemoteCallback reply) {
                    mUserInfoController.reloadUserInfo();
                }

                @Override
                public void onUserSwitchComplete(int newUserId) throws RemoteException {
                    updateAlarm();
                    profileChanged(newUserId);
                }

                @Override
                public void onForegroundProfileSwitch(int newProfileId) {
                    profileChanged(newProfileId);
                }
            };

    private final HotspotController.Callback mHotspotCallback = new HotspotController.Callback() {
        @Override
        public void onHotspotChanged(boolean enabled) {
            mService.setIconVisibility(SLOT_HOTSPOT, enabled);
        }
    };

    private final CastController.Callback mCastCallback = new CastController.Callback() {
        @Override
        public void onCastDevicesChanged() {
            updateCast();
        }
    };

    public void appTransitionStarting(long startTime, long duration) {
        updateManagedProfile();
    }

    public void setKeyguardShowing(boolean visible) {
        mKeyguardVisible = visible;
        updateManagedProfile();
    }

    public void setCurrentUserSetup(boolean userSetup) {
        if (mCurrentUserSetup == userSetup) return;
        mCurrentUserSetup = userSetup;
        updateAlarm();
    }
}
