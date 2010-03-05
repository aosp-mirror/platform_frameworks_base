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
import android.app.AlarmManager;
import android.app.IActivityManager;
import android.app.IUiModeManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Binder;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Settings;
import android.server.BluetoothService;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.app.DisableCarModeActivity;
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

    private static final String KEY_LAST_UPDATE_INTERVAL = "LAST_UPDATE_INTERVAL";

    private static final int MSG_DOCK_STATE = 0;
    private static final int MSG_UPDATE_TWILIGHT = 1;
    private static final int MSG_ENABLE_LOCATION_UPDATES = 2;

    public static final int MODE_NIGHT_AUTO = Configuration.UI_MODE_NIGHT_MASK >> 4;
    public static final int MODE_NIGHT_NO = Configuration.UI_MODE_NIGHT_NO >> 4;
    public static final int MODE_NIGHT_YES = Configuration.UI_MODE_NIGHT_YES >> 4;

    private static final long LOCATION_UPDATE_MS = 30 * DateUtils.MINUTE_IN_MILLIS;
    private static final float LOCATION_UPDATE_DISTANCE_METER = 1000 * 20;
    private static final long LOCATION_UPDATE_ENABLE_INTERVAL_MIN = 5000;
    private static final long LOCATION_UPDATE_ENABLE_INTERVAL_MAX = 5 * DateUtils.MINUTE_IN_MILLIS;
    private static final double FACTOR_GMT_OFFSET_LONGITUDE = 1000.0 * 360.0 / DateUtils.DAY_IN_MILLIS;

    private static final String ACTION_UPDATE_NIGHT_MODE = "com.android.server.action.UPDATE_NIGHT_MODE";

    private int mDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;
    private int mPreviousDockState = Intent.EXTRA_DOCK_STATE_UNDOCKED;

    private int mNightMode = MODE_NIGHT_NO;
    private boolean mCarModeEnabled = false;

    private boolean mSystemReady;

    private final Context mContext;

    private PowerManagerService mPowerManager;
    private NotificationManager mNotificationManager;

    private KeyguardManager.KeyguardLock mKeyguardLock;
    private boolean mKeyguardDisabled;
    private LockPatternUtils mLockPatternUtils;

    private AlarmManager mAlarmManager;

    private LocationManager mLocationManager;
    private Location mLocation;
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
            if (mCarModeEnabled) {
                // Only launch car home when car mode is enabled.
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
                    Slog.w(TAG, e.getCause());
                }
            }
        }
    };

    private final BroadcastReceiver mTwilightUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mCarModeEnabled && mNightMode == MODE_NIGHT_AUTO) {
                mHandler.sendEmptyMessage(MSG_UPDATE_TWILIGHT);
            }
        }
    };

    // A LocationListener to initialize the network location provider. The location updates
    // are handled through the passive location provider.
    private final LocationListener mEmptyLocationListener =  new LocationListener() {
        public void onLocationChanged(Location location) {
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    private final LocationListener mLocationListener = new LocationListener() {

        public void onLocationChanged(Location location) {
            final boolean hasMoved = hasMoved(location);
            if (hasMoved || hasBetterAccuracy(location)) {
                synchronized (this) {
                    mLocation = location;
                }
                if (hasMoved && mCarModeEnabled && mNightMode == MODE_NIGHT_AUTO) {
                    mHandler.sendEmptyMessage(MSG_UPDATE_TWILIGHT);
                }
            }
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        private boolean hasBetterAccuracy(Location location) {
            if (location == null) {
                return false;
            }
            if (mLocation == null) {
                return true;
            }
            return location.getAccuracy() < mLocation.getAccuracy();
        }

        /*
         * The user has moved if the accuracy circles of the two locations
         * don't overlap.
         */
        private boolean hasMoved(Location location) {
            if (location == null) {
                return false;
            }
            if (mLocation == null) {
                return true;
            }

            /* if new location is older than the current one, the devices hasn't
             * moved.
             */
            if (location.getTime() < mLocation.getTime()) {
                return false;
            }

            /* Get the distance between the two points */
            float distance = mLocation.distanceTo(location);

            /* Get the total accuracy radius for both locations */
            float totalAccuracy = mLocation.getAccuracy() + location.getAccuracy();

            /* If the distance is greater than the combined accuracy of the two
             * points then they can't overlap and hence the user has moved.
             */
            return distance >= totalAccuracy;
        }
    };

    public DockObserver(Context context, PowerManagerService pm) {
        mContext = context;
        mPowerManager = pm;
        mLockPatternUtils = new LockPatternUtils(context);
        init();  // set initial status

        ServiceManager.addService("uimode", mBinder);

        mAlarmManager =
            (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        mLocationManager =
            (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
        mContext.registerReceiver(mTwilightUpdateReceiver,
                new IntentFilter(ACTION_UPDATE_NIGHT_MODE));

        startObserving(DOCK_UEVENT_MATCH);
    }

    @Override
    public void onUEvent(UEventObserver.UEvent event) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, "Dock UEVENT: " + event.toString());
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
                            Slog.w(TAG, "Unable to change car mode.", e1);
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
                Slog.e(TAG, "Could not parse switch state from event " + event);
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
            Slog.w(TAG, "This kernel does not have dock station support");
        } catch (Exception e) {
            Slog.e(TAG, "" , e);
        }
    }

    void systemReady() {
        synchronized (this) {
            KeyguardManager keyguardManager =
                    (KeyguardManager)mContext.getSystemService(Context.KEYGUARD_SERVICE);
            mKeyguardLock = keyguardManager.newKeyguardLock(TAG);

            final boolean enableCarMode = mDockState == Intent.EXTRA_DOCK_STATE_CAR;
            if (enableCarMode) {
                try {
                    setCarMode(enableCarMode);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to change car mode.", e);
                }
            }
            // don't bother broadcasting undocked here
            if (mDockState != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                update();
            }
            mSystemReady = true;
            mHandler.sendEmptyMessage(MSG_ENABLE_LOCATION_UPDATES);
        }
    }

    private final void update() {
        mHandler.sendEmptyMessage(MSG_DOCK_STATE);
    }

    private final Handler mHandler = new Handler() {

        boolean mPassiveListenerEnabled;
        boolean mNetworkListenerEnabled;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DOCK_STATE:
                    synchronized (this) {
                        Slog.i(TAG, "Dock state changed: " + mDockState);

                        final ContentResolver cr = mContext.getContentResolver();

                        if (Settings.Secure.getInt(cr,
                                Settings.Secure.DEVICE_PROVISIONED, 0) == 0) {
                            Slog.i(TAG, "Device not provisioned, skipping dock broadcast");
                            return;
                        }
                        // Pack up the values and broadcast them to everyone
                        Intent intent = new Intent(Intent.ACTION_DOCK_EVENT);
                        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                        if (mCarModeEnabled && mDockState != Intent.EXTRA_DOCK_STATE_CAR) {
                            // Pretend to be in DOCK_STATE_CAR.
                            intent.putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_CAR);
                        } else if (!mCarModeEnabled && mDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                            // Pretend to be in DOCK_STATE_UNDOCKED.
                            intent.putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED);
                        } else {
                            intent.putExtra(Intent.EXTRA_DOCK_STATE, mDockState);
                        }
                        intent.putExtra(Intent.EXTRA_PHYSICAL_DOCK_STATE, mDockState);
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
                                        if (sfx != null) {
                                            sfx.setStreamType(AudioManager.STREAM_SYSTEM);
                                            sfx.play();
                                        }
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
                    break;
                case MSG_UPDATE_TWILIGHT:
                    synchronized (this) {
                        if (mCarModeEnabled && mLocation != null && mNightMode == MODE_NIGHT_AUTO) {
                            try {
                                DockObserver.this.updateTwilight();
                            } catch (RemoteException e) {
                                Slog.w(TAG, "Unable to change night mode.", e);
                            }
                        }
                    }
                    break;
                case MSG_ENABLE_LOCATION_UPDATES:
                   // enable passive provider to receive updates from location fixes (gps
                   // and network).
                   boolean passiveLocationEnabled;
                    try {
                        passiveLocationEnabled =
                            mLocationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
                    } catch (Exception e) {
                        // we may get IllegalArgumentException if passive location provider
                        // does not exist or is not yet installed.
                        passiveLocationEnabled = false;
                    }
                    if (!mPassiveListenerEnabled && passiveLocationEnabled) {
                        mPassiveListenerEnabled = true;
                        mLocationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                                0, LOCATION_UPDATE_DISTANCE_METER , mLocationListener);
                    }
                    // enable network provider to receive at least location updates for a given
                    // distance.
                    boolean networkLocationEnabled;
                    try {
                        networkLocationEnabled =
                            mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                    } catch (Exception e) {
                        // we may get IllegalArgumentException if network location provider
                        // does not exist or is not yet installed.
                        networkLocationEnabled = false;
                    }
                    if (!mNetworkListenerEnabled && networkLocationEnabled) {
                        mNetworkListenerEnabled = true;
                        mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                                LOCATION_UPDATE_MS, 0, mEmptyLocationListener);

                        retrieveLocation();
                        if (mCarModeEnabled && mLocation != null && mNightMode == MODE_NIGHT_AUTO) {
                            try {
                                DockObserver.this.updateTwilight();
                            } catch (RemoteException e) {
                                Slog.w(TAG, "Unable to change night mode.", e);
                            }
                        }
                    }
                    if (!(mNetworkListenerEnabled && mPassiveListenerEnabled)) {
                        long interval = msg.getData().getLong(KEY_LAST_UPDATE_INTERVAL);
                        interval *= 1.5;
                        if (interval == 0) {
                            interval = LOCATION_UPDATE_ENABLE_INTERVAL_MIN;
                        } else if (interval > LOCATION_UPDATE_ENABLE_INTERVAL_MAX) {
                            interval = LOCATION_UPDATE_ENABLE_INTERVAL_MAX;
                        }
                        Bundle bundle = new Bundle();
                        bundle.putLong(KEY_LAST_UPDATE_INTERVAL, interval);
                        Message newMsg = mHandler.obtainMessage(MSG_ENABLE_LOCATION_UPDATES);
                        newMsg.setData(bundle);
                        mHandler.sendMessageDelayed(newMsg, interval);
                    }
                    break;
            }
        }

        private void retrieveLocation() {
            final Location gpsLocation =
                mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location location;
            Criteria criteria = new Criteria();
            criteria.setSpeedRequired(false);
            criteria.setAltitudeRequired(false);
            criteria.setBearingRequired(false);
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            final String bestProvider = mLocationManager.getBestProvider(criteria, true);
            location = mLocationManager.getLastKnownLocation(bestProvider);
            // In the case there is no location available (e.g. GPS fix or network location
            // is not available yet), the longitude of the location is estimated using the timezone,
            // latitude and accuracy are set to get a good average.
            if (location == null) {
                Time currentTime = new Time();
                currentTime.set(System.currentTimeMillis());
                double lngOffset = FACTOR_GMT_OFFSET_LONGITUDE *
                        (currentTime.gmtoff - (currentTime.isDst > 0 ? 3600 : 0));
                location = new Location("fake");
                location.setLongitude(lngOffset);
                location.setLatitude(0);
                location.setAccuracy(417000.0f);
                location.setTime(System.currentTimeMillis());
            }
            synchronized (this) {
                mLocation = location;
            }
        }
    };

    private void adjustStatusBarCarMode() {
        if (mStatusBarManager == null) {
            mStatusBarManager = (StatusBarManager) mContext.getSystemService(Context.STATUS_BAR_SERVICE);
        }

        // Fear not: StatusBarService manages a list of requests to disable
        // features of the status bar; these are ORed together to form the
        // active disabled list. So if (for example) the device is locked and
        // the status bar should be totally disabled, the calls below will
        // have no effect until the device is unlocked.
        if (mStatusBarManager != null) {
            long ident = Binder.clearCallingIdentity();
            mStatusBarManager.disable(mCarModeEnabled
                ? StatusBarManager.DISABLE_NOTIFICATION_TICKER
                : StatusBarManager.DISABLE_NONE);
            Binder.restoreCallingIdentity(ident);
        }

        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager)
                    mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        if (mNotificationManager != null) {
            long ident = Binder.clearCallingIdentity();
            if (mCarModeEnabled) {
                Intent carModeOffIntent = new Intent(mContext, DisableCarModeActivity.class);

                Notification n = new Notification();
                n.icon = R.drawable.stat_notify_car_mode;
                n.defaults = Notification.DEFAULT_LIGHTS;
                n.flags = Notification.FLAG_ONGOING_EVENT;
                n.when = 0;
                n.setLatestEventInfo(
                        mContext,
                        mContext.getString(R.string.car_mode_disable_notification_title),
                        mContext.getString(R.string.car_mode_disable_notification_message),
                        PendingIntent.getActivity(mContext, 0, carModeOffIntent, 0));
                mNotificationManager.notify(0, n);
            } else {
                mNotificationManager.cancel(0);
            }
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void setCarMode(boolean enabled) throws RemoteException {
        mCarModeEnabled = enabled;
        if (enabled) {
            if (mNightMode == MODE_NIGHT_AUTO) {
                updateTwilight();
            } else {
                setMode(Configuration.UI_MODE_TYPE_CAR, mNightMode << 4);
            }
        } else {
            // Disabling the car mode clears the night mode.
            setMode(Configuration.UI_MODE_TYPE_NORMAL,
                    Configuration.UI_MODE_NIGHT_UNDEFINED);
        }
        adjustStatusBarCarMode();
    }

    private void setMode(int modeType, int modeNight) throws RemoteException {
        long ident = Binder.clearCallingIdentity();
        final IActivityManager am = ActivityManagerNative.getDefault();
        Configuration config = am.getConfiguration();
        if (config.uiMode != (modeType | modeNight)) {
            config.uiMode = modeType | modeNight;
            am.updateConfiguration(config);
        }
        Binder.restoreCallingIdentity(ident);
    }

    private void setNightMode(int mode) throws RemoteException {
        if (mNightMode != mode) {
            mNightMode = mode;
            switch (mode) {
                case MODE_NIGHT_NO:
                case MODE_NIGHT_YES:
                    setMode(Configuration.UI_MODE_TYPE_CAR, mode << 4);
                    break;
                case MODE_NIGHT_AUTO:
                    long ident = Binder.clearCallingIdentity();
                    updateTwilight();
                    Binder.restoreCallingIdentity(ident);
                    break;
                default:
                    setMode(Configuration.UI_MODE_TYPE_CAR, MODE_NIGHT_NO << 4);
                    break;
            }
        }
    }

    private void updateTwilight() throws RemoteException {
        synchronized (this) {
            if (mLocation == null) {
                return;
            }
            final long currentTime = System.currentTimeMillis();
            int nightMode;
            // calculate current twilight
            TwilightCalculator tw = new TwilightCalculator();
            tw.calculateTwilight(currentTime,
                    mLocation.getLatitude(), mLocation.getLongitude());
            if (tw.mState == TwilightCalculator.DAY) {
                nightMode = MODE_NIGHT_NO;
            } else {
                nightMode =  MODE_NIGHT_YES;
            }

            // schedule next update
            long nextUpdate = 0;
            if (tw.mSunrise == -1 || tw.mSunset == -1) {
                // In the case the day or night never ends the update is scheduled 12 hours later.
                nextUpdate = currentTime + 12 * DateUtils.HOUR_IN_MILLIS;
            } else {
                final int mLastTwilightState = tw.mState;
                // add some extra time to be on the save side.
                nextUpdate += DateUtils.MINUTE_IN_MILLIS;
                if (currentTime > tw.mSunset) {
                    // next update should be on the following day
                    tw.calculateTwilight(currentTime
                            + DateUtils.DAY_IN_MILLIS, mLocation.getLatitude(),
                            mLocation.getLongitude());
                }

                if (mLastTwilightState == TwilightCalculator.NIGHT) {
                    nextUpdate += tw.mSunrise;
                } else {
                    nextUpdate += tw.mSunset;
                }
            }

            Intent updateIntent = new Intent(ACTION_UPDATE_NIGHT_MODE);
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, updateIntent, 0);
            mAlarmManager.cancel(pendingIntent);
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, nextUpdate, pendingIntent);

            // Make sure that we really set the new mode only if we're in car mode and
            // automatic switching is enables.
            if (mCarModeEnabled && mNightMode == MODE_NIGHT_AUTO) {
                setMode(Configuration.UI_MODE_TYPE_CAR, nightMode << 4);
            }
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
