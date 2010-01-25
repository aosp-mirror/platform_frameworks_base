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
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IUiModeManager;
import android.app.KeyguardManager;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Binder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Settings;
import android.server.BluetoothService;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils;

import java.io.FileNotFoundException;
import java.io.FileReader;

/**
 * <p>DockObserver monitors for a docking station.
 */
class DockObserver extends UEventObserver {
    private static final String TAG = DockObserver.class.getSimpleName();
    private static final boolean LOG = false;

    private static final String DOCK_UEVENT_MATCH = "DEVPATH=/devices/virtual/switch/dock";
    private static final String DOCK_STATE_PATH = "/sys/class/switch/dock/state";

    public static final int MODE_NIGHT_AUTO = Configuration.UI_MODE_NIGHT_MASK >> 4;
    public static final int MODE_NIGHT_NO = Configuration.UI_MODE_NIGHT_NO >> 4;
    public static final int MODE_NIGHT_YES = Configuration.UI_MODE_NIGHT_YES >> 4;

    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private int mPreviousDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private int mNightMode = MODE_NIGHT_NO;
    private boolean mCarModeEnabled = false;

    private boolean mSystemReady;

    private final Context mContext;

    private PowerManagerService mPowerManager;

    private KeyguardManager.KeyguardLock mKeyguardLock;
    private boolean mKeyguardDisabled;
    private LockPatternUtils mLockPatternUtils;

    private StatusBarManager mStatusBarManager;    

    // The broadcast receiver which receives the result of the ordered broadcast sent when
    // the dock state changes. The original ordered broadcast is sent with an initial result
    // code of RESULT_OK. If any of the registered broadcast receivers changes this value, e.g.,
    // to RESULT_CANCELED, then the intent to start a dock app will not be sent.
    private final BroadcastReceiver mResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (getResultCode() != Activity.RESULT_OK) {
                return;
            }

            // Launch a dock activity
            String category;
            if (mCarModeEnabled || mDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                category = Intent.CATEGORY_CAR_DOCK;
            } else if (mDockState == Intent.EXTRA_DOCK_STATE_DESK) {
                category = Intent.CATEGORY_DESK_DOCK;
            } else {
                category = null;
            }
            if (category != null) {
                intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(category);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                try {
                    mContext.startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    Log.w(TAG, e.getCause());
                }
            }
        }
    };

    public DockObserver(Context context, PowerManagerService pm) {
        mContext = context;
        mPowerManager = pm;
        mLockPatternUtils = new LockPatternUtils(context);
        init();  // set initial status

        ServiceManager.addService("uimode", mBinder);

        startObserving(DOCK_UEVENT_MATCH);
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Dock UEVENT: " + event.toString());
        }

        synchronized (this) {
            try {
                int newState = Integer.parseInt(event.get("SWITCH_STATE"));
                if (newState != mDockState) {
                    mPreviousDockState = mDockState;
                    mDockState = newState;
                    boolean carModeEnabled = mDockState == Intent.EXTRA_DOCK_STATE_CAR;
                    if (mCarModeEnabled != carModeEnabled) {
                        try {
                            setCarMode(carModeEnabled);
                        } catch (RemoteException e1) {
                            Log.w(TAG, "Unable to change car mode.", e1);
                        }
                    }
                    if (mSystemReady) {
                        // Don't force screen on when undocking from the desk dock.
                        // The change in power state will do this anyway.
                        // FIXME - we should be configurable.
                        if (mPreviousDockState != Intent.EXTRA_DOCK_STATE_DESK ||
                                mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                            mPowerManager.userActivityWithForce(SystemClock.uptimeMillis(),
                                    false, true);
                        }
                        update();
                    }
                }
            } catch (NumberFormatException e) {
                Log.e(TAG, "Could not parse switch state from event " + event);
            }
        }
    }

    private final void init() {
        char[] buffer = new char[1024];

        try {
            FileReader file = new FileReader(DOCK_STATE_PATH);
            int len = file.read(buffer, 0, 1024);
            mPreviousDockState = mDockState = Integer.valueOf((new String(buffer, 0, len)).trim());

        } catch (FileNotFoundException e) {
            Log.w(TAG, "This kernel does not have dock station support");
        } catch (Exception e) {
            Log.e(TAG, "" , e);
        }
    }

    void systemReady() {
        synchronized (this) {
            KeyguardManager keyguardManager =
                    (KeyguardManager)mContext.getSystemService(Context.KEYGUARD_SERVICE);
            mKeyguardLock = keyguardManager.newKeyguardLock(TAG);

            // don't bother broadcasting undocked here
            if (mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                update();
            }
            mSystemReady = true;
        }
    }

    private final void update() {
        mHandler.sendEmptyMessage(0);
    }

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            synchronized (this) {
                Log.i(TAG, "Dock state changed: " + mDockState);

                final ContentResolver cr = mContext.getContentResolver();

                if (Settings.Secure.getInt(cr,
                        Settings.Secure.DEVICE_PROVISIONED, 0) == 0) {
                    Log.i(TAG, "Device not provisioned, skipping dock broadcast");
                    return;
                }
                // Pack up the values and broadcast them to everyone
                Intent intent = new Intent(Intent.ACTION_DOCK_EVENT);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                if (mCarModeEnabled && mDockState != Intent.EXTRA_DOCK_STATE_CAR) {
                    // Pretend to be in DOCK_STATE_CAR.
                    intent.putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_CAR);
                } else {
                    intent.putExtra(Intent.EXTRA_DOCK_STATE, mDockState);
                }
                intent.putExtra(Intent.EXTRA_CAR_MODE_ENABLED, mCarModeEnabled);

                // Check if this is Bluetooth Dock
                String address = BluetoothService.readDockBluetoothAddress();
                if (address != null)
                    intent.putExtra(BluetoothDevice.EXTRA_DEVICE,
                            BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address));

                // User feedback to confirm dock connection. Particularly
                // useful for flaky contact pins...
                if (Settings.System.getInt(cr,
                        Settings.System.DOCK_SOUNDS_ENABLED, 1) == 1)
                {
                    String whichSound = null;
                    if (mDockState == Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                        if (mPreviousDockState == Intent.EXTRA_DOCK_STATE_DESK) {
                            whichSound = Settings.System.DESK_UNDOCK_SOUND;
                        } else if (mPreviousDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                            whichSound = Settings.System.CAR_UNDOCK_SOUND;
                        }
                    } else {
                        if (mDockState == Intent.EXTRA_DOCK_STATE_DESK) {
                            whichSound = Settings.System.DESK_DOCK_SOUND;
                        } else if (mDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                            whichSound = Settings.System.CAR_DOCK_SOUND;
                        }
                    }

                    if (whichSound != null) {
                        final String soundPath = Settings.System.getString(cr, whichSound);
                        if (soundPath != null) {
                            final Uri soundUri = Uri.parse("file://" + soundPath);
                            if (soundUri != null) {
                                final Ringtone sfx = RingtoneManager.getRingtone(mContext, soundUri);
                                if (sfx != null) sfx.play();
                            }
                        }
                    }
                }

                // Send the ordered broadcast; the result receiver will receive after all
                // broadcasts have been sent. If any broadcast receiver changes the result
                // code from the initial value of RESULT_OK, then the result receiver will
                // not launch the corresponding dock application. This gives apps a chance
                // to override the behavior and stay in their app even when the device is
                // placed into a dock.
                mContext.sendStickyOrderedBroadcast(
                        intent, mResultReceiver, null, Activity.RESULT_OK, null, null);
            }
        }
    };

    private void setCarMode(boolean enabled) throws RemoteException {
        mCarModeEnabled = enabled;
        if (enabled) {
            setMode(Configuration.UI_MODE_TYPE_CAR, mNightMode);
        } else {
            // Disabling the car mode clears the night mode.
            setMode(Configuration.UI_MODE_TYPE_NORMAL, MODE_NIGHT_NO);
        }

        if (mStatusBarManager == null) {
            mStatusBarManager = (StatusBarManager) mContext.getSystemService(Context.STATUS_BAR_SERVICE);
        }

        // Fear not: StatusBarService manages a list of requests to disable
        // features of the status bar; these are ORed together to form the
        // active disabled list. So if (for example) the device is locked and
        // the status bar should be totally disabled, the calls below will
        // have no effect until the device is unlocked.
        if (mStatusBarManager != null) {
            mStatusBarManager.disable(enabled 
                ? StatusBarManager.DISABLE_NOTIFICATION_TICKER
                : StatusBarManager.DISABLE_NONE);
        }
    }

    private void setMode(int modeType, int modeNight) throws RemoteException {
        final IActivityManager am = ActivityManagerNative.getDefault();
        Configuration config = am.getConfiguration();

        if (config.uiMode != (modeType | modeNight)) {
            config.uiMode = modeType | modeNight;
            long ident = Binder.clearCallingIdentity();
            am.updateConfiguration(config);
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setNightMode(int mode) throws RemoteException {
        mNightMode = mode;
        switch (mode) {
            case MODE_NIGHT_NO:
            case MODE_NIGHT_YES:
                setMode(Configuration.UI_MODE_TYPE_CAR, mode << 4);
                break;
            case MODE_NIGHT_AUTO:
                // FIXME: not yet supported, this functionality will be
                // added in a separate change.
                break;
            default:
                setMode(Configuration.UI_MODE_TYPE_CAR, MODE_NIGHT_NO << 4);
                break;
        }
    }

    /**
     * Wrapper class implementing the IUiModeManager interface.
     */
    private final IUiModeManager.Stub mBinder = new IUiModeManager.Stub() {

        public void disableCarMode() throws RemoteException {
            if (mCarModeEnabled) {
                setCarMode(false);
                update();
            }
        }

        public void enableCarMode() throws RemoteException {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ENABLE_CAR_MODE,
                    "Need ENABLE_CAR_MODE permission");
            if (!mCarModeEnabled) {
                setCarMode(true);
                update();
            }
        }

        public void setNightMode(int mode) throws RemoteException {
            if (mCarModeEnabled) {
                DockObserver.this.setNightMode(mode);
            }
        }

        public int getNightMode() throws RemoteException {
            return mNightMode;
        }
    };
}
