/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.retaildemo;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RetailDemoModeServiceInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.KeyValueListParser;
import android.util.Slog;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.BackgroundThread;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.PreloadsFileCacheExpirationJobService;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.am.ActivityManagerService;
import com.android.server.retaildemo.UserInactivityCountdownDialog.OnCountDownExpiredListener;

import java.io.File;
import java.util.ArrayList;

public class RetailDemoModeService extends SystemService {
    private static final boolean DEBUG = false;

    private static final String TAG = RetailDemoModeService.class.getSimpleName();
    private static final String DEMO_USER_NAME = "Demo";
    private static final String ACTION_RESET_DEMO =
            "com.android.server.retaildemo.ACTION_RESET_DEMO";
    @VisibleForTesting
    static final String SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED = "sys.retaildemo.enabled";

    private static final int MSG_TURN_SCREEN_ON = 0;
    private static final int MSG_INACTIVITY_TIME_OUT = 1;
    private static final int MSG_START_NEW_SESSION = 2;

    private static final long SCREEN_WAKEUP_DELAY = 2500;
    private static final long USER_INACTIVITY_TIMEOUT_MIN = 10000;
    private static final long USER_INACTIVITY_TIMEOUT_DEFAULT = 90000;
    private static final long WARNING_DIALOG_TIMEOUT_DEFAULT = 0;
    private static final long MILLIS_PER_SECOND = 1000;

    @VisibleForTesting
    static final int[] VOLUME_STREAMS_TO_MUTE = {
            AudioSystem.STREAM_RING,
            AudioSystem.STREAM_MUSIC
    };

    // Tron Vars
    private static final String DEMO_SESSION_COUNT = "retail_demo_session_count";
    private static final String DEMO_SESSION_DURATION = "retail_demo_session_duration";

    boolean mDeviceInDemoMode;
    boolean mIsCarrierDemoMode;
    int mCurrentUserId = UserHandle.USER_SYSTEM;
    long mUserInactivityTimeout;
    long mWarningDialogTimeout;
    private Injector mInjector;
    Handler mHandler;
    private ServiceThread mHandlerThread;
    private String[] mCameraIdsWithFlash;
    private PreloadAppsInstaller mPreloadAppsInstaller;

    final Object mActivityLock = new Object();
    // Whether the newly created demo user has interacted with the screen yet
    @GuardedBy("mActivityLock")
    boolean mUserUntouched;
    @GuardedBy("mActivityLock")
    long mFirstUserActivityTime;
    @GuardedBy("mActivityLock")
    long mLastUserActivityTime;

    private boolean mSafeBootRestrictionInitialState;
    private int mPackageVerifierEnableInitialState;

    private IntentReceiver mBroadcastReceiver = null;

    private final class IntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mDeviceInDemoMode) {
                return;
            }
            final String action = intent.getAction();
            switch (action) {
                case Intent.ACTION_SCREEN_OFF:
                    mHandler.removeMessages(MSG_TURN_SCREEN_ON);
                    mHandler.sendEmptyMessageDelayed(MSG_TURN_SCREEN_ON, SCREEN_WAKEUP_DELAY);
                    break;
                case ACTION_RESET_DEMO:
                    mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
                    break;
            }
        }
    };

    final class MainHandler extends Handler {

        MainHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            if (!mDeviceInDemoMode) {
                return;
            }
            switch (msg.what) {
                case MSG_TURN_SCREEN_ON:
                    if (mInjector.isWakeLockHeld()) {
                        mInjector.releaseWakeLock();
                    }
                    mInjector.acquireWakeLock();
                    break;
                case MSG_INACTIVITY_TIME_OUT:
                    if (!mIsCarrierDemoMode && isDemoLauncherDisabled()) {
                        Slog.i(TAG, "User inactivity timeout reached");
                        showInactivityCountdownDialog();
                    }
                    break;
                case MSG_START_NEW_SESSION:
                    if (DEBUG) {
                        Slog.d(TAG, "Switching to a new demo user");
                    }
                    removeMessages(MSG_START_NEW_SESSION);
                    removeMessages(MSG_INACTIVITY_TIME_OUT);
                    if (!mIsCarrierDemoMode && mCurrentUserId != UserHandle.USER_SYSTEM) {
                        logSessionDuration();
                    }

                    final UserManager um = mInjector.getUserManager();
                    UserInfo demoUser = null;
                    if (mIsCarrierDemoMode) {
                        // Re-use the existing demo user in carrier demo mode.
                        for (UserInfo user : um.getUsers()) {
                            if (user.isDemo()) {
                                demoUser = user;
                                break;
                            }
                        }
                    }

                    if (demoUser == null) {
                        // User in carrier demo mode should survive reboots.
                        final int flags = UserInfo.FLAG_DEMO
                                | (mIsCarrierDemoMode ? 0 : UserInfo.FLAG_EPHEMERAL);
                        demoUser = um.createUser(DEMO_USER_NAME, flags);
                    }

                    if (demoUser != null && mCurrentUserId != demoUser.id) {
                        setupDemoUser(demoUser);
                        mInjector.switchUser(demoUser.id);
                    }
                    break;
            }
        }
    }

    @VisibleForTesting
    class SettingsObserver extends ContentObserver {

        private final static String KEY_USER_INACTIVITY_TIMEOUT = "user_inactivity_timeout_ms";
        private final static String KEY_WARNING_DIALOG_TIMEOUT = "warning_dialog_timeout_ms";

        private final Uri mDeviceDemoModeUri = Settings.Global
                .getUriFor(Settings.Global.DEVICE_DEMO_MODE);
        private final Uri mDeviceProvisionedUri = Settings.Global
                .getUriFor(Settings.Global.DEVICE_PROVISIONED);
        private final Uri mRetailDemoConstantsUri = Settings.Global
                .getUriFor(Settings.Global.RETAIL_DEMO_MODE_CONSTANTS);

        private final KeyValueListParser mParser = new KeyValueListParser(',');

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void register() {
            final ContentResolver cr = mInjector.getContentResolver();
            cr.registerContentObserver(mDeviceDemoModeUri, false, this, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mDeviceProvisionedUri, false, this, UserHandle.USER_SYSTEM);
            cr.registerContentObserver(mRetailDemoConstantsUri, false, this,
                    UserHandle.USER_SYSTEM);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mRetailDemoConstantsUri.equals(uri)) {
                refreshTimeoutConstants();
                return;
            }

            // If device is provisioned and left demo mode - run the cleanup in demo folder
            if (isDeviceProvisioned()) {
                if (UserManager.isDeviceInDemoMode(getContext())) {
                    startDemoMode();
                } else {
                    mInjector.systemPropertiesSet(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED, "0");

                    // Run on the bg thread to not block the fg thread
                    BackgroundThread.getHandler().post(() -> {
                        if (!deletePreloadsFolderContents()) {
                            Slog.w(TAG, "Failed to delete preloads folder contents");
                        }
                        PreloadsFileCacheExpirationJobService.schedule(mInjector.getContext());
                    });

                    stopDemoMode();

                    if (mInjector.isWakeLockHeld()) {
                        mInjector.releaseWakeLock();
                    }
                }
            }
        }

        private void refreshTimeoutConstants() {
            try {
                mParser.setString(Settings.Global.getString(mInjector.getContentResolver(),
                        Settings.Global.RETAIL_DEMO_MODE_CONSTANTS));
            } catch (IllegalArgumentException exc) {
                Slog.e(TAG, "Invalid string passed to KeyValueListParser");
                // Consuming the exception to fall back to default values.
            }
            mWarningDialogTimeout = mParser.getLong(KEY_WARNING_DIALOG_TIMEOUT,
                    WARNING_DIALOG_TIMEOUT_DEFAULT);
            mUserInactivityTimeout = mParser.getLong(KEY_USER_INACTIVITY_TIMEOUT,
                    USER_INACTIVITY_TIMEOUT_DEFAULT);
            mUserInactivityTimeout = Math.max(mUserInactivityTimeout, USER_INACTIVITY_TIMEOUT_MIN);
        }
    }

    private void showInactivityCountdownDialog() {
        UserInactivityCountdownDialog dialog = new UserInactivityCountdownDialog(getContext(),
                mWarningDialogTimeout, MILLIS_PER_SECOND);
        dialog.setNegativeButtonClickListener(null);
        dialog.setPositiveButtonClickListener(new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
            }
        });
        dialog.setOnCountDownExpiredListener(new OnCountDownExpiredListener() {
            @Override
            public void onCountDownExpired() {
                mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);
            }
        });
        dialog.show();
    }

    public RetailDemoModeService(Context context) {
        this(new Injector(context));
    }

    @VisibleForTesting
    RetailDemoModeService(Injector injector) {
        super(injector.getContext());

        mInjector = injector;
        synchronized (mActivityLock) {
            mFirstUserActivityTime = mLastUserActivityTime = SystemClock.uptimeMillis();
        }
    }

    boolean isDemoLauncherDisabled() {
        int enabledState = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        try {
            final IPackageManager iPm = mInjector.getIPackageManager();
            final String demoLauncherComponent =
                    getContext().getString(R.string.config_demoModeLauncherComponent);
            enabledState = iPm.getComponentEnabledSetting(
                    ComponentName.unflattenFromString(demoLauncherComponent), mCurrentUserId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Error retrieving demo launcher enabled setting", re);
        }
        return enabledState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void setupDemoUser(UserInfo userInfo) {
        final UserManager um = mInjector.getUserManager();
        final UserHandle user = UserHandle.of(userInfo.id);
        um.setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, true, user);
        um.setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true, user);
        um.setUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, true, user);
        um.setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, true, user);
        um.setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true, user);
        um.setUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH, true, user);
        // Set this to false because the default is true on user creation
        um.setUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS, false, user);
        // Disallow rebooting in safe mode - controlled by user 0
        um.setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, true, UserHandle.SYSTEM);
        if (mIsCarrierDemoMode) {
            // Enable SMS in carrier demo mode.
            um.setUserRestriction(UserManager.DISALLOW_SMS, false, user);
        }

        Settings.Secure.putIntForUser(mInjector.getContentResolver(),
                Settings.Secure.SKIP_FIRST_USE_HINTS, 1, userInfo.id);
        Settings.Global.putInt(mInjector.getContentResolver(),
                Settings.Global.PACKAGE_VERIFIER_ENABLE, 0);

        grantRuntimePermissionToCamera(user);
        clearPrimaryCallLog();

        if (!mIsCarrierDemoMode) {
            // Enable demo launcher.
            final String demoLauncher = getContext().getString(
                    R.string.config_demoModeLauncherComponent);
            if (!TextUtils.isEmpty(demoLauncher)) {
                final ComponentName componentToEnable =
                        ComponentName.unflattenFromString(demoLauncher);
                final String packageName = componentToEnable.getPackageName();
                try {
                    final IPackageManager iPm = AppGlobals.getPackageManager();
                    iPm.setComponentEnabledSetting(componentToEnable,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, userInfo.id);
                    iPm.setApplicationEnabledSetting(packageName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, userInfo.id, null);
                } catch (RemoteException re) {
                    // Internal, shouldn't happen
                }
            }
        } else {
            // Set the carrier demo mode setting for the demo user.
            final String carrierDemoModeSetting = getContext().getString(
                    R.string.config_carrierDemoModeSetting);
            Settings.Secure.putIntForUser(getContext().getContentResolver(),
                    carrierDemoModeSetting, 1, userInfo.id);

            // Enable packages for carrier demo mode.
            final String packageList = getContext().getString(
                    R.string.config_carrierDemoModePackages);
            final String[] packageNames = packageList == null ? new String[0]
                    : TextUtils.split(packageList, ",");
            final IPackageManager iPm = AppGlobals.getPackageManager();
            for (String packageName : packageNames) {
                try {
                    iPm.setApplicationEnabledSetting(packageName,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0, userInfo.id, null);
                } catch (RemoteException re) {
                    Slog.e(TAG, "Error enabling application: " + packageName, re);
                }
            }
        }
    }

    private void grantRuntimePermissionToCamera(UserHandle user) {
        final Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        final PackageManager pm = mInjector.getPackageManager();
        final ResolveInfo handler = pm.resolveActivityAsUser(cameraIntent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                user.getIdentifier());
        if (handler == null || handler.activityInfo == null) {
            return;
        }
        try {
            pm.grantRuntimePermission(handler.activityInfo.packageName,
                    Manifest.permission.ACCESS_FINE_LOCATION, user);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void clearPrimaryCallLog() {
        final ContentResolver resolver = mInjector.getContentResolver();

        // Deleting primary user call log so that it doesn't get copied to the new demo user
        final Uri uri = CallLog.Calls.CONTENT_URI;
        try {
            resolver.delete(uri, null, null);
        } catch (Exception e) {
            Slog.w(TAG, "Deleting call log failed: " + e);
        }
    }

    void logSessionDuration() {
        final int sessionDuration;
        synchronized (mActivityLock) {
            sessionDuration = (int) ((mLastUserActivityTime - mFirstUserActivityTime) / 1000);
        }
        mInjector.logSessionDuration(sessionDuration);
    }

    private boolean isDeviceProvisioned() {
        return Settings.Global.getInt(
                mInjector.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0) != 0;
    }

    /**
     * Deletes contents of {@link Environment#getDataPreloadsDirectory()},
     * but leave {@link Environment#getDataPreloadsFileCacheDirectory()}
     * @return true if contents was sucessfully deleted
     */
    private boolean deletePreloadsFolderContents() {
        final File dir = mInjector.getDataPreloadsDirectory();
        final File[] files = FileUtils.listFilesOrEmpty(dir);
        final File fileCacheDirectory = mInjector.getDataPreloadsFileCacheDirectory();
        Slog.i(TAG, "Deleting contents of " + dir);
        boolean success = true;
        for (File file : files) {
            if (file.isFile()) {
                if (!file.delete()) {
                    success = false;
                    Slog.w(TAG, "Cannot delete file " + file);
                }
            } else {
                // Do not remove file_cache dir
                if (!file.equals(fileCacheDirectory)) {
                    if (!FileUtils.deleteContentsAndDir(file)) {
                        success = false;
                        Slog.w(TAG, "Cannot delete dir and its content " + file);
                    }
                } else {
                    Slog.i(TAG, "Skipping directory with file cache " + file);
                }
            }
        }
        return success;
    }

    private void registerBroadcastReceiver() {
        if (mBroadcastReceiver != null) {
            return;
        }

        final IntentFilter filter = new IntentFilter();
        if (!mIsCarrierDemoMode) {
            filter.addAction(Intent.ACTION_SCREEN_OFF);
        }
        filter.addAction(ACTION_RESET_DEMO);
        mBroadcastReceiver = new IntentReceiver();
        getContext().registerReceiver(mBroadcastReceiver, filter);
    }

    private void unregisterBroadcastReceiver() {
        if (mBroadcastReceiver != null) {
            getContext().unregisterReceiver(mBroadcastReceiver);
            mBroadcastReceiver = null;
        }
    }

    private String[] getCameraIdsWithFlash() {
        ArrayList<String> cameraIdsList = new ArrayList<String>();
        final CameraManager cm = mInjector.getCameraManager();
        if (cm != null) {
            try {
                for (String cameraId : cm.getCameraIdList()) {
                    CameraCharacteristics c = cm.getCameraCharacteristics(cameraId);
                    if (Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
                        cameraIdsList.add(cameraId);
                    }
                }
            } catch (CameraAccessException e) {
                Slog.e(TAG, "Unable to access camera while getting camera id list", e);
            }
        }
        return cameraIdsList.toArray(new String[cameraIdsList.size()]);
    }

    private void muteVolumeStreams() {
        for (int stream : VOLUME_STREAMS_TO_MUTE) {
            mInjector.getAudioManager().setStreamVolume(stream,
                    mInjector.getAudioManager().getStreamMinVolume(stream), 0);
        }
    }

    private void startDemoMode() {
        mDeviceInDemoMode = true;

        mPreloadAppsInstaller = mInjector.getPreloadAppsInstaller();
        mInjector.initializeWakeLock();
        if (mCameraIdsWithFlash == null) {
            mCameraIdsWithFlash = getCameraIdsWithFlash();
        }
        registerBroadcastReceiver();

        final String carrierDemoModeSetting =
                getContext().getString(R.string.config_carrierDemoModeSetting);
        mIsCarrierDemoMode = !TextUtils.isEmpty(carrierDemoModeSetting)
                && (Settings.Secure.getInt(getContext().getContentResolver(),
                        carrierDemoModeSetting, 0) == 1);

        mInjector.systemPropertiesSet(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED, "1");
        mHandler.sendEmptyMessage(MSG_START_NEW_SESSION);

        mSafeBootRestrictionInitialState = mInjector.getUserManager().hasUserRestriction(
                UserManager.DISALLOW_SAFE_BOOT, UserHandle.SYSTEM);
        mPackageVerifierEnableInitialState = Settings.Global.getInt(mInjector.getContentResolver(),
                Settings.Global.PACKAGE_VERIFIER_ENABLE, 1);
    }

    private void stopDemoMode() {
        mPreloadAppsInstaller = null;
        mCameraIdsWithFlash = null;
        mInjector.destroyWakeLock();
        unregisterBroadcastReceiver();

        if (mDeviceInDemoMode) {
            mInjector.getUserManager().setUserRestriction(UserManager.DISALLOW_SAFE_BOOT,
                    mSafeBootRestrictionInitialState, UserHandle.SYSTEM);
            Settings.Global.putInt(mInjector.getContentResolver(),
                        Settings.Global.PACKAGE_VERIFIER_ENABLE,
                        mPackageVerifierEnableInitialState);
        }

        mDeviceInDemoMode = false;
        mIsCarrierDemoMode = false;
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.d(TAG, "Service starting up");
        }
        mHandlerThread = new ServiceThread(TAG, android.os.Process.THREAD_PRIORITY_FOREGROUND,
                false);
        mHandlerThread.start();
        mHandler = new MainHandler(mHandlerThread.getLooper());
        mInjector.publishLocalService(this, mLocalService);
    }

    @Override
    public void onBootPhase(int bootPhase) {
        switch (bootPhase) {
            case PHASE_THIRD_PARTY_APPS_CAN_START:
                final SettingsObserver settingsObserver = new SettingsObserver(mHandler);
                settingsObserver.register();
                settingsObserver.refreshTimeoutConstants();
                break;
            case PHASE_BOOT_COMPLETED:
                if (UserManager.isDeviceInDemoMode(getContext())) {
                    startDemoMode();
                }
                break;
        }
    }

    @Override
    public void onSwitchUser(int userId) {
        if (!mDeviceInDemoMode) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "onSwitchUser: " + userId);
        }
        final UserInfo ui = mInjector.getUserManager().getUserInfo(userId);
        if (!ui.isDemo()) {
            Slog.wtf(TAG, "Should not allow switch to non-demo user in demo mode");
            return;
        }
        if (!mIsCarrierDemoMode && !mInjector.isWakeLockHeld()) {
            mInjector.acquireWakeLock();
        }
        mCurrentUserId = userId;
        mInjector.getActivityManagerInternal().updatePersistentConfigurationForUser(
                mInjector.getSystemUsersConfiguration(), userId);

        mInjector.turnOffAllFlashLights(mCameraIdsWithFlash);
        muteVolumeStreams();
        if (!mInjector.getWifiManager().isWifiEnabled()) {
            mInjector.getWifiManager().setWifiEnabled(true);
        }

        // Disable lock screen for demo users.
        mInjector.getLockPatternUtils().setLockScreenDisabled(true, userId);

        if (!mIsCarrierDemoMode) {
            // Show reset notification (except in carrier demo mode).
            mInjector.getNotificationManager().notifyAsUser(TAG, SystemMessage.NOTE_RETAIL_RESET,
                    mInjector.createResetNotification(), UserHandle.of(userId));

            synchronized (mActivityLock) {
                mUserUntouched = true;
            }
            mInjector.logSessionCount(1);
            mHandler.removeMessages(MSG_INACTIVITY_TIME_OUT);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mPreloadAppsInstaller.installApps(userId);
                }
            });
        }
    }

    private RetailDemoModeServiceInternal mLocalService = new RetailDemoModeServiceInternal() {
        private static final long USER_ACTIVITY_DEBOUNCE_TIME = 2000;

        @Override
        public void onUserActivity() {
            if (!mDeviceInDemoMode || mIsCarrierDemoMode) {
                return;
            }
            long timeOfActivity = SystemClock.uptimeMillis();
            synchronized (mActivityLock) {
                if (timeOfActivity < mLastUserActivityTime + USER_ACTIVITY_DEBOUNCE_TIME) {
                    return;
                }
                mLastUserActivityTime = timeOfActivity;
                if (mUserUntouched && isDemoLauncherDisabled()) {
                    Slog.d(TAG, "retail_demo first touch");
                    mUserUntouched = false;
                    mFirstUserActivityTime = timeOfActivity;
                }
            }
            mHandler.removeMessages(MSG_INACTIVITY_TIME_OUT);
            mHandler.sendEmptyMessageDelayed(MSG_INACTIVITY_TIME_OUT, mUserInactivityTimeout);
        }
    };

    static class Injector {
        private Context mContext;
        private UserManager mUm;
        private PackageManager mPm;
        private NotificationManager mNm;
        private ActivityManagerService mAms;
        private ActivityManagerInternal mAmi;
        private AudioManager mAudioManager;
        private PowerManager mPowerManager;
        private CameraManager mCameraManager;
        private PowerManager.WakeLock mWakeLock;
        private WifiManager mWifiManager;
        private Configuration mSystemUserConfiguration;
        private PendingIntent mResetDemoPendingIntent;
        private PreloadAppsInstaller mPreloadAppsInstaller;

        Injector(Context context) {
            mContext = context;
        }

        Context getContext() {
            return mContext;
        }

        WifiManager getWifiManager() {
            if (mWifiManager == null) {
                mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            }
            return mWifiManager;
        }

        UserManager getUserManager() {
            if (mUm == null) {
                mUm = getContext().getSystemService(UserManager.class);
            }
            return mUm;
        }

        void switchUser(int userId) {
            if (mAms == null) {
                mAms = (ActivityManagerService) ActivityManager.getService();
            }
            mAms.switchUser(userId);
        }

        AudioManager getAudioManager() {
            if (mAudioManager == null) {
                mAudioManager = getContext().getSystemService(AudioManager.class);
            }
            return mAudioManager;
        }

        private PowerManager getPowerManager() {
            if (mPowerManager == null) {
                mPowerManager = (PowerManager) getContext().getSystemService(
                        Context.POWER_SERVICE);
            }
            return mPowerManager;
        }

        NotificationManager getNotificationManager() {
            if (mNm == null) {
                mNm = NotificationManager.from(getContext());
            }
            return mNm;
        }

        ActivityManagerInternal getActivityManagerInternal() {
            if (mAmi == null) {
                mAmi = LocalServices.getService(ActivityManagerInternal.class);
            }
            return mAmi;
        }

        CameraManager getCameraManager() {
            if (mCameraManager == null) {
                mCameraManager = (CameraManager) getContext().getSystemService(
                        Context.CAMERA_SERVICE);
            }
            return mCameraManager;
        }

        PackageManager getPackageManager() {
            if (mPm == null) {
                mPm = getContext().getPackageManager();
            }
            return mPm;
        }

        IPackageManager getIPackageManager() {
            return AppGlobals.getPackageManager();
        }

        ContentResolver getContentResolver() {
            return getContext().getContentResolver();
        }

        PreloadAppsInstaller getPreloadAppsInstaller() {
            if (mPreloadAppsInstaller == null) {
                mPreloadAppsInstaller = new PreloadAppsInstaller(getContext());
            }
            return mPreloadAppsInstaller;
        }

        void systemPropertiesSet(String key, String value) {
            SystemProperties.set(key, value);
        }

        void turnOffAllFlashLights(String[] cameraIdsWithFlash) {
            for (String cameraId : cameraIdsWithFlash) {
                try {
                    getCameraManager().setTorchMode(cameraId, false);
                } catch (CameraAccessException e) {
                    Slog.e(TAG, "Unable to access camera " + cameraId
                            + " while turning off flash", e);
                }
            }
        }

        void initializeWakeLock() {
            if (mWakeLock == null) {
                mWakeLock = getPowerManager().newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
            }
        }

        void destroyWakeLock() {
            mWakeLock = null;
        }

        boolean isWakeLockHeld() {
            return mWakeLock != null && mWakeLock.isHeld();
        }

        void acquireWakeLock() {
            mWakeLock.acquire();
        }

        void releaseWakeLock() {
            mWakeLock.release();
        }

        void logSessionDuration(int duration) {
            MetricsLogger.histogram(getContext(), DEMO_SESSION_DURATION, duration);
        }

        void logSessionCount(int count) {
            MetricsLogger.count(getContext(), DEMO_SESSION_COUNT, count);
        }

        Configuration getSystemUsersConfiguration() {
            if (mSystemUserConfiguration == null) {
                Settings.System.getConfiguration(getContentResolver(),
                        mSystemUserConfiguration = new Configuration());
            }
            return mSystemUserConfiguration;
        }

        LockPatternUtils getLockPatternUtils() {
            return new LockPatternUtils(getContext());
        }

        Notification createResetNotification() {
            return new Notification.Builder(getContext(), SystemNotificationChannels.RETAIL_MODE)
                    .setContentTitle(getContext().getString(R.string.reset_retail_demo_mode_title))
                    .setContentText(getContext().getString(R.string.reset_retail_demo_mode_text))
                    .setOngoing(true)
                    .setSmallIcon(R.drawable.platlogo)
                    .setShowWhen(false)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setContentIntent(getResetDemoPendingIntent())
                    .setColor(getContext().getColor(R.color.system_notification_accent_color))
                    .build();
        }

        private PendingIntent getResetDemoPendingIntent() {
            if (mResetDemoPendingIntent == null) {
                Intent intent = new Intent(ACTION_RESET_DEMO);
                mResetDemoPendingIntent = PendingIntent.getBroadcast(getContext(), 0, intent, 0);
            }
            return mResetDemoPendingIntent;
        }

        File getDataPreloadsDirectory() {
            return Environment.getDataPreloadsDirectory();
        }

        File getDataPreloadsFileCacheDirectory() {
            return Environment.getDataPreloadsFileCacheDirectory();
        }

        void publishLocalService(RetailDemoModeService service,
                RetailDemoModeServiceInternal localService) {
            service.publishLocalService(RetailDemoModeServiceInternal.class, localService);
        }
    }
}
