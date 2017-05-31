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
 * limitations under the License.
 */

package com.android.server.retaildemo;

import static com.android.server.retaildemo.RetailDemoModeService.SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.ActivityManagerInternal;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.RetailDemoModeServiceInternal;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.CallLog;
import android.provider.MediaStore;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.util.ArrayMap;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.SystemService;
import com.android.server.retaildemo.RetailDemoModeService.Injector;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.compat.ArgumentMatcher;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RetailDemoModeServiceTest {
    private static final int TEST_DEMO_USER = 111;
    private static final long SETUP_COMPLETE_TIMEOUT_MS = 2000; // 2 sec
    private static final String TEST_CAMERA_PKG = "test.cameraapp";
    private static final String TEST_PRELOADS_DIR_NAME = "test_preloads";

    private Context mContext;
    private @Mock UserManager mUm;
    private @Mock PackageManager mPm;
    private @Mock IPackageManager mIpm;
    private @Mock NotificationManager mNm;
    private @Mock ActivityManagerInternal mAmi;
    private @Mock AudioManager mAudioManager;
    private @Mock WifiManager mWifiManager;
    private @Mock LockPatternUtils mLockPatternUtils;
    private @Mock JobScheduler mJobScheduler;
    private MockPreloadAppsInstaller mPreloadAppsInstaller;
    private MockContentResolver mContentResolver;
    private MockContactsProvider mContactsProvider;
    private Configuration mConfiguration;
    private File mTestPreloadsDir;
    private CountDownLatch mLatch;

    private RetailDemoModeService mService;
    private TestInjector mInjector;

    @BeforeClass
    @AfterClass
    public static void clearSettingsProvider() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = Mockito.spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        when(mContext.getSystemServiceName(eq(JobScheduler.class))).thenReturn(
                Context.JOB_SCHEDULER_SERVICE);
        when(mContext.getSystemService(Context.JOB_SCHEDULER_SERVICE)).thenReturn(mJobScheduler);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUm);
        mContentResolver = new MockContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        mContactsProvider = new MockContactsProvider(mContext);
        mContentResolver.addProvider(CallLog.AUTHORITY, mContactsProvider);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        mPreloadAppsInstaller = new MockPreloadAppsInstaller(mContext);
        mConfiguration = new Configuration();
        mTestPreloadsDir = new File(InstrumentationRegistry.getContext().getFilesDir(),
                TEST_PRELOADS_DIR_NAME);

        Settings.Global.putString(mContentResolver, Settings.Global.RETAIL_DEMO_MODE_CONSTANTS, "");
        Settings.Global.putInt(mContentResolver, Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Global.putInt(mContentResolver, Settings.Global.DEVICE_DEMO_MODE, 1);

        // Initialize RetailDemoModeService
        mInjector = new TestInjector();
        mService = new RetailDemoModeService(mInjector);
        mService.onStart();
    }

    @After
    public void tearDown() {
        if (mTestPreloadsDir != null) {
            FileUtils.deleteContentsAndDir(mTestPreloadsDir);
        }
    }

    @Test
    public void testDemoUserSetup() throws Exception {
        mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        mLatch = new CountDownLatch(1);
        final UserInfo userInfo = new UserInfo();
        userInfo.id = TEST_DEMO_USER;
        when(mUm.createUser(anyString(), anyInt())).thenReturn(userInfo);

        setCameraPackage(TEST_CAMERA_PKG);
        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
        assertEquals(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED + " property not set",
                "1", mInjector.systemPropertiesGet(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED));

        final ArgumentCaptor<IntentFilter> intentFilter =
                ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiver(any(BroadcastReceiver.class), intentFilter.capture());
        assertTrue("Not registered for " + Intent.ACTION_SCREEN_OFF,
                intentFilter.getValue().hasAction(Intent.ACTION_SCREEN_OFF));

        // Wait for the setup to complete.
        mLatch.await(SETUP_COMPLETE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ArgumentCaptor<Integer> flags = ArgumentCaptor.forClass(Integer.class);
        verify(mUm).createUser(anyString(), flags.capture());
        assertTrue("FLAG_DEMO not set during user creation",
                (flags.getValue() & UserInfo.FLAG_DEMO) != 0);
        assertTrue("FLAG_EPHEMERAL not set during user creation",
                (flags.getValue() & UserInfo.FLAG_EPHEMERAL) != 0);
        // Verify if necessary restrictions are being set.
        final UserHandle user = UserHandle.of(TEST_DEMO_USER);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, true, user);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true, user);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, true, user);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER, true, user);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true, user);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH, true, user);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS, false, user);
        verify(mUm).setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, true, UserHandle.SYSTEM);
        // Verify if necessary settings are updated.
        assertEquals("SKIP_FIRST_USE_HINTS setting is not set for demo user",
                Settings.Secure.getIntForUser(mContentResolver,
                        Settings.Secure.SKIP_FIRST_USE_HINTS, TEST_DEMO_USER),
                1);
        assertEquals("PACKAGE_VERIFIER_ENABLE settings should be set to 0 for demo user",
                Settings.Global.getInt(mContentResolver,
                        Settings.Global.PACKAGE_VERIFIER_ENABLE),
                0);
        // Verify if camera is granted location permission.
        verify(mPm).grantRuntimePermission(TEST_CAMERA_PKG,
                Manifest.permission.ACCESS_FINE_LOCATION, user);
        // Verify call logs are cleared.
        assertTrue("Call logs should be deleted", mContactsProvider.isCallLogDeleted());
    }

    @Test
    public void testSettingsObserver_disableDemoMode() throws Exception {
        final RetailDemoModeService.SettingsObserver observer =
                mService.new SettingsObserver(new Handler(Looper.getMainLooper()));
        final Uri deviceDemoModeUri = Settings.Global.getUriFor(Settings.Global.DEVICE_DEMO_MODE);
        when(mUm.hasUserRestriction(UserManager.DISALLOW_SAFE_BOOT, UserHandle.SYSTEM))
                .thenReturn(false);
        Settings.Global.putInt(mContentResolver, Settings.Global.PACKAGE_VERIFIER_ENABLE, 1);
        // Settings.Global.DEVICE_DEMO_MODE has been set to 1 initially.
        observer.onChange(false, deviceDemoModeUri);
        final ArgumentCaptor<BroadcastReceiver> receiver =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext).registerReceiver(receiver.capture(), any(IntentFilter.class));

        Settings.Global.putInt(mContentResolver, Settings.Global.PACKAGE_VERIFIER_ENABLE, 0);
        new File(mTestPreloadsDir, "dir1").mkdirs();
        new File(mTestPreloadsDir, "file1").createNewFile();
        Settings.Global.putInt(mContentResolver, Settings.Global.DEVICE_DEMO_MODE, 0);
        observer.onChange(false, deviceDemoModeUri);
        verify(mContext).unregisterReceiver(receiver.getValue());
        verify(mUm).setUserRestriction(UserManager.DISALLOW_SAFE_BOOT, false, UserHandle.SYSTEM);
        assertEquals("Package verifier enable value has not been reset", 1,
                Settings.Global.getInt(mContentResolver, Settings.Global.PACKAGE_VERIFIER_ENABLE));
        Thread.sleep(20); // Wait for the deletion to complete.
        // verify that the preloaded directory is emptied.
        assertEquals("Preloads directory is not emptied",
                0, mTestPreloadsDir.list().length);
        // Verify that the expiration job was scheduled
        verify(mJobScheduler).schedule(any(JobInfo.class));
    }

    @Test
    public void testSettingsObserver_enableDemoMode() throws Exception {
        final RetailDemoModeService.SettingsObserver observer =
                mService.new SettingsObserver(new Handler(Looper.getMainLooper()));
        final Uri deviceDemoModeUri = Settings.Global.getUriFor(Settings.Global.DEVICE_DEMO_MODE);
        // Settings.Global.DEVICE_DEMO_MODE has been set to 1 initially.
        observer.onChange(false, deviceDemoModeUri);
        assertEquals(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED + " property not set",
                "1", mInjector.systemPropertiesGet(SYSTEM_PROPERTY_RETAIL_DEMO_ENABLED));

        final ArgumentCaptor<IntentFilter> intentFilter =
                ArgumentCaptor.forClass(IntentFilter.class);
        verify(mContext).registerReceiver(any(BroadcastReceiver.class), intentFilter.capture());
        assertTrue("Not registered for " + Intent.ACTION_SCREEN_OFF,
                intentFilter.getValue().hasAction(Intent.ACTION_SCREEN_OFF));
    }

    @Test
    public void testSwitchToDemoUser() {
        // To make the RetailDemoModeService update it's internal state.
        mService.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
        final RetailDemoModeService.SettingsObserver observer =
                mService.new SettingsObserver(new Handler(Looper.getMainLooper()));
        observer.onChange(false, Settings.Global.getUriFor(Settings.Global.DEVICE_DEMO_MODE));

        final UserInfo userInfo = new UserInfo(TEST_DEMO_USER, "demo_user",
                UserInfo.FLAG_DEMO | UserInfo.FLAG_EPHEMERAL);
        when(mUm.getUserInfo(TEST_DEMO_USER)).thenReturn(userInfo);
        when(mWifiManager.isWifiEnabled()).thenReturn(false);
        final int minVolume = -111;
        for (int stream : RetailDemoModeService.VOLUME_STREAMS_TO_MUTE) {
            when(mAudioManager.getStreamMinVolume(stream)).thenReturn(minVolume);
        }

        mService.onSwitchUser(TEST_DEMO_USER);
        verify(mAmi).updatePersistentConfigurationForUser(mConfiguration, TEST_DEMO_USER);
        for (int stream : RetailDemoModeService.VOLUME_STREAMS_TO_MUTE) {
            verify(mAudioManager).setStreamVolume(stream, minVolume, 0);
        }
        verify(mLockPatternUtils).setLockScreenDisabled(true, TEST_DEMO_USER);
        verify(mWifiManager).setWifiEnabled(true);
    }

    private void setCameraPackage(String pkgName) {
        final ResolveInfo ri = new ResolveInfo();
        final ActivityInfo ai = new ActivityInfo();
        ai.packageName = pkgName;
        ri.activityInfo = ai;
        when(mPm.resolveActivityAsUser(
                argThat(new IntentMatcher(MediaStore.ACTION_IMAGE_CAPTURE)),
                anyInt(),
                eq(TEST_DEMO_USER))).thenReturn(ri);
    }

    private class IntentMatcher extends ArgumentMatcher<Intent> {
        private final Intent mIntent;

        IntentMatcher(String action) {
            mIntent = new Intent(action);
        }

        @Override
        public boolean matchesObject(Object argument) {
            if (argument instanceof Intent) {
                return ((Intent) argument).filterEquals(mIntent);
            }
            return false;
        }

        @Override
        public String toString() {
            return "Expected: " + mIntent;
        }
    }

    private class MockContactsProvider extends MockContentProvider {
        private boolean mCallLogDeleted;

        MockContactsProvider(Context context) {
            super(context);
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            if (CallLog.Calls.CONTENT_URI.equals(uri)) {
                mCallLogDeleted = true;
            }
            return 0;
        }

        public boolean isCallLogDeleted() {
            return mCallLogDeleted;
        }
    }

    private class MockPreloadAppsInstaller extends PreloadAppsInstaller {
        MockPreloadAppsInstaller(Context context) {
            super(context);
        }

        @Override
        public void installApps(int userId) {
        }
    }

    private class TestInjector extends Injector {
        private ArrayMap<String, String> mSystemProperties = new ArrayMap<>();

        TestInjector() {
            super(mContext);
        }

        @Override
        Context getContext() {
            return mContext;
        }

        @Override
        UserManager getUserManager() {
            return mUm;
        }

        @Override
        WifiManager getWifiManager() {
            return mWifiManager;
        }

        @Override
        void switchUser(int userId) {
            if (mLatch != null) {
                mLatch.countDown();
            }
        }

        @Override
        AudioManager getAudioManager() {
            return mAudioManager;
        }

        @Override
        NotificationManager getNotificationManager() {
            return mNm;
        }

        @Override
        ActivityManagerInternal getActivityManagerInternal() {
            return mAmi;
        }

        @Override
        PackageManager getPackageManager() {
            return mPm;
        }

        @Override
        IPackageManager getIPackageManager() {
            return mIpm;
        }

        @Override
        ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        PreloadAppsInstaller getPreloadAppsInstaller() {
            return mPreloadAppsInstaller;
        }

        @Override
        void systemPropertiesSet(String key, String value) {
            mSystemProperties.put(key, value);
        }

        @Override
        void turnOffAllFlashLights(String[] cameraIdsWithFlash) {
        }

        @Override
        void initializeWakeLock() {
        }

        @Override
        void destroyWakeLock() {
        }

        @Override
        boolean isWakeLockHeld() {
            return false;
        }

        @Override
        void acquireWakeLock() {
        }

        @Override
        void releaseWakeLock() {
        }

        @Override
        void logSessionDuration(int duration) {
        }

        @Override
        void logSessionCount(int count) {
        }

        @Override
        Configuration getSystemUsersConfiguration() {
            return mConfiguration;
        }

        @Override
        LockPatternUtils getLockPatternUtils() {
            return mLockPatternUtils;
        }

        @Override
        Notification createResetNotification() {
            return null;
        }

        @Override
        File getDataPreloadsDirectory() {
            return mTestPreloadsDir;
        }

        @Override
        File getDataPreloadsFileCacheDirectory() {
            return new File(mTestPreloadsDir, "file_cache");
        }

        @Override
        void publishLocalService(RetailDemoModeService service,
                RetailDemoModeServiceInternal localService) {
        }

        String systemPropertiesGet(String key) {
            return mSystemProperties.get(key);
        }
    }
}
