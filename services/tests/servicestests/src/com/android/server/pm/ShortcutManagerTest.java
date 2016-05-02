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
package com.android.server.pm;

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDynamic;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllDynamicOrPinned;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIcon;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIconFile;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIconResId;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveIntents;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllHaveTitle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllKeyFieldsOnly;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllNotHaveIntents;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllNotHaveTitle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllNotKeyFieldsOnly;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllPinned;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertAllUnique;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertBitmapSize;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertBundleEmpty;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertCallbackNotReceived;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertCallbackReceived;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertDynamicAndPinned;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertDynamicOnly;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertExpectException;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.assertShortcutIds;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.findShortcut;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.hashSet;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.makeBundle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.pfdToBitmap;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.resetAll;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest.permission;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.IUidObserver;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ILauncherApps;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.InstrumentationTestCase;
import android.test.MoreAsserts;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.frameworks.servicestests.R;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.LauncherAppsService.LauncherAppsImpl;
import com.android.server.pm.ShortcutService.ConfigConstants;
import com.android.server.pm.ShortcutService.FileOutputStreamWithPath;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import org.junit.Assert;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Tests for ShortcutService and ShortcutManager.
 *
 m FrameworksServicesTests &&
 adb install \
 -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksServicesTests/FrameworksServicesTests.apk &&
 adb shell am instrument -e class com.android.server.pm.ShortcutManagerTest \
 -w com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner

 * TODO: Add checks with assertAllNotHaveIcon()
 * TODO: Detailed test for hasShortcutPermissionInner().
 * TODO: Add tests for the command line functions too.
 */
@SmallTest
public class ShortcutManagerTest extends InstrumentationTestCase {
    private static final String TAG = "ShortcutManagerTest";

    /**
     * Whether to enable dump or not.  Should be only true when debugging to avoid bugs where
     * dump affecting the behavior.
     */
    private static final boolean ENABLE_DUMP = false; // DO NOT SUBMIT WITH true

    private static final boolean DUMP_IN_TEARDOWN = false; // DO NOT SUBMIT WITH true

    private static final String[] EMPTY_STRINGS = new String[0]; // Just for readability.

    // public for mockito
    public class BaseContext extends MockContext {
        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.USER_SERVICE:
                    return mMockUserManager;
            }
            throw new UnsupportedOperationException();
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            return getTestContext().getSystemServiceName(serviceClass);
        }

        @Override
        public PackageManager getPackageManager() {
            return mMockPackageManager;
        }

        @Override
        public Resources getResources() {
            return getTestContext().getResources();
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            // ignore.
            return null;
        }
    }

    /** Context used in the client side */
    public class ClientContext extends BaseContext {
        @Override
        public String getPackageName() {
            return mInjectedClientPackage;
        }

        @Override
        public int getUserId() {
            return getCallingUserId();
        }
    }

    /** Context used in the service side */
    public class ServiceContext extends BaseContext {
        long injectClearCallingIdentity() {
            final int prevCallingUid = mInjectedCallingUid;
            mInjectedCallingUid = Process.SYSTEM_UID;
            return prevCallingUid;
        }

        void injectRestoreCallingIdentity(long token) {
            mInjectedCallingUid = (int) token;
        }

        @Override
        public void startActivityAsUser(@RequiresPermission Intent intent, @Nullable Bundle options,
                UserHandle userId) {
        }

        @Override
        public int getUserId() {
            return UserHandle.USER_SYSTEM;
        }
    }

    /** ShortcutService with injection override methods. */
    private final class ShortcutServiceTestable extends ShortcutService {
        final ServiceContext mContext;
        IUidObserver mUidObserver;

        public ShortcutServiceTestable(ServiceContext context, Looper looper) {
            super(context, looper);
            mContext = context;
        }

        @Override
        String injectShortcutManagerConstants() {
            return ConfigConstants.KEY_RESET_INTERVAL_SEC + "=" + (INTERVAL / 1000) + ","
                    + ConfigConstants.KEY_MAX_SHORTCUTS + "=" + MAX_SHORTCUTS + ","
                    + ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL + "="
                    + MAX_UPDATES_PER_INTERVAL + ","
                    + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=" + MAX_ICON_DIMENSION + ","
                    + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "="
                    + MAX_ICON_DIMENSION_LOWRAM + ","
                    + ConfigConstants.KEY_ICON_FORMAT + "=PNG,"
                    + ConfigConstants.KEY_ICON_QUALITY + "=100";
        }

        @Override
        long injectClearCallingIdentity() {
            return mContext.injectClearCallingIdentity();
        }

        @Override
        void injectRestoreCallingIdentity(long token) {
            mContext.injectRestoreCallingIdentity(token);
        }

        @Override
        int injectDipToPixel(int dip) {
            return dip;
        }

        @Override
        long injectCurrentTimeMillis() {
            return mInjectedCurrentTimeLillis;
        }

        @Override
        long injectElapsedRealtime() {
            // TODO This should be kept separately from mInjectedCurrentTimeLillis, since
            // this should increase even if we rewind mInjectedCurrentTimeLillis in some tests.
            return mInjectedCurrentTimeLillis - START_TIME;
        }

        @Override
        int injectBinderCallingUid() {
            return mInjectedCallingUid;
        }

        @Override
        int injectGetPackageUid(String packageName, int userId) {
            return getInjectedPackageInfo(packageName, userId, false).applicationInfo.uid;
        }

        @Override
        File injectSystemDataPath() {
            return new File(mInjectedFilePathRoot, "system");
        }

        @Override
        File injectUserDataPath(@UserIdInt int userId) {
            return new File(mInjectedFilePathRoot, "user-" + userId);
        }

        @Override
        void injectValidateIconResPackage(ShortcutInfo shortcut, Icon icon) {
            // Can't check
        }

        @Override
        boolean injectIsLowRamDevice() {
            return mInjectedIsLowRamDevice;
        }

        @Override
        void injectRegisterUidObserver(IUidObserver observer, int which) {
            mUidObserver = observer;
        }

        @Override
        PackageManagerInternal injectPackageManagerInternal() {
            return mMockPackageManagerInternal;
        }

        @Override
        boolean hasShortcutHostPermission(@NonNull String callingPackage, int userId) {
            return mDefaultLauncherChecker.test(callingPackage, userId);
        }

        @Override
        PackageInfo injectPackageInfo(String packageName, @UserIdInt int userId,
                boolean getSignatures) {
            return getInjectedPackageInfo(packageName, userId, getSignatures);
        }

        @Override
        ApplicationInfo injectApplicationInfo(String packageName, @UserIdInt int userId) {
            PackageInfo pi = injectPackageInfo(packageName, userId, /* getSignatures= */ false);
            return pi != null ? pi.applicationInfo : null;
        }

        @Override
        void postToHandler(Runnable r) {
            final long token = mContext.injectClearCallingIdentity();
            r.run();
            mContext.injectRestoreCallingIdentity(token);
        }

        @Override
        void injectEnforceCallingPermission(String permission, String message) {
            if (!mCallerPermissions.contains(permission)) {
                throw new SecurityException("Missing permission: " + permission);
            }
        }

        @Override
        void wtf(String message, Exception e) {
            // During tests, WTF is fatal.
            fail(message + "  exception: " + e);
        }
    }

    /** ShortcutManager with injection override methods. */
    private class ShortcutManagerTestable extends ShortcutManager {
        public ShortcutManagerTestable(Context context, ShortcutServiceTestable service) {
            super(context, service);
        }

        @Override
        protected int injectMyUserId() {
            return UserHandle.getUserId(mInjectedCallingUid);
        }
    }

    private class LauncherAppImplTestable extends LauncherAppsImpl {
        final ServiceContext mContext;

        public LauncherAppImplTestable(ServiceContext context) {
            super(context);
            mContext = context;
        }

        @Override
        public void verifyCallingPackage(String callingPackage) {
            // SKIP
        }

        @Override
        void postToPackageMonitorHandler(Runnable r) {
            final long token = mContext.injectClearCallingIdentity();
            r.run();
            mContext.injectRestoreCallingIdentity(token);
        }

        @Override
        int injectBinderCallingUid() {
            return mInjectedCallingUid;
        }

        @Override
        long injectClearCallingIdentity() {
            final int prevCallingUid = mInjectedCallingUid;
            mInjectedCallingUid = Process.SYSTEM_UID;
            return prevCallingUid;
        }

        @Override
        void injectRestoreCallingIdentity(long token) {
            mInjectedCallingUid = (int) token;
        }
    }

    private class LauncherAppsTestable extends LauncherApps {
        public LauncherAppsTestable(Context context, ILauncherApps service) {
            super(context, service);
        }
    }

    public static class ShortcutActivity extends Activity {
    }

    public static class ShortcutActivity2 extends Activity {
    }

    public static class ShortcutActivity3 extends Activity {
    }

    private ServiceContext mServiceContext;
    private ClientContext mClientContext;

    private ShortcutServiceTestable mService;
    private ShortcutManagerTestable mManager;
    private ShortcutServiceInternal mInternal;

    private LauncherAppImplTestable mLauncherAppImpl;

    // LauncherApps has per-instace state, so we need a differnt instance for each launcher.
    private final Map<Pair<Integer, String>, LauncherAppsTestable>
            mLauncherAppsMap = new HashMap<>();
    private LauncherAppsTestable mLauncherApps; // Current one

    private File mInjectedFilePathRoot;

    private long mInjectedCurrentTimeLillis;

    private boolean mInjectedIsLowRamDevice;

    private int mInjectedCallingUid;
    private String mInjectedClientPackage;

    private Map<String, PackageInfo> mInjectedPackages;

    private Set<PackageWithUser> mUninstalledPackages;

    private PackageManager mMockPackageManager;
    private PackageManagerInternal mMockPackageManagerInternal;
    private UserManager mMockUserManager;

    private static final String CALLING_PACKAGE_1 = "com.android.test.1";
    private static final int CALLING_UID_1 = 10001;

    private static final String CALLING_PACKAGE_2 = "com.android.test.2";
    private static final int CALLING_UID_2 = 10002;

    private static final String CALLING_PACKAGE_3 = "com.android.test.3";
    private static final int CALLING_UID_3 = 10003;

    private static final String CALLING_PACKAGE_4 = "com.android.test.4";
    private static final int CALLING_UID_4 = 10004;

    private static final String LAUNCHER_1 = "com.android.launcher.1";
    private static final int LAUNCHER_UID_1 = 10011;

    private static final String LAUNCHER_2 = "com.android.launcher.2";
    private static final int LAUNCHER_UID_2 = 10012;

    private static final String LAUNCHER_3 = "com.android.launcher.3";
    private static final int LAUNCHER_UID_3 = 10013;

    private static final String LAUNCHER_4 = "com.android.launcher.4";
    private static final int LAUNCHER_UID_4 = 10014;

    private static final int USER_0 = UserHandle.USER_SYSTEM;
    private static final int USER_10 = 10;
    private static final int USER_11 = 11;
    private static final int USER_P0 = 20; // profile of user 0

    private static final UserHandle HANDLE_USER_0 = UserHandle.of(USER_0);
    private static final UserHandle HANDLE_USER_10 = UserHandle.of(USER_10);
    private static final UserHandle HANDLE_USER_11 = UserHandle.of(USER_11);
    private static final UserHandle HANDLE_USER_P0 = UserHandle.of(USER_P0);

    private static final UserInfo USER_INFO_0 = withProfileGroupId(
            new UserInfo(USER_0, "user0",
                    UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY | UserInfo.FLAG_INITIALIZED), 10);

    private static final UserInfo USER_INFO_10 =
            new UserInfo(USER_10, "user10", UserInfo.FLAG_INITIALIZED);

    private static final UserInfo USER_INFO_11 =
            new UserInfo(USER_11, "user11", UserInfo.FLAG_INITIALIZED);

    private static final UserInfo USER_INFO_P0 = withProfileGroupId(
            new UserInfo(USER_P0, "userP0",
                    UserInfo.FLAG_MANAGED_PROFILE), 10);

    private BiPredicate<String, Integer> mDefaultLauncherChecker =
            (callingPackage, userId) ->
            LAUNCHER_1.equals(callingPackage) || LAUNCHER_2.equals(callingPackage)
            || LAUNCHER_3.equals(callingPackage) || LAUNCHER_4.equals(callingPackage);

    private static final long START_TIME = 1440000000101L;

    private static final long INTERVAL = 10000;

    private static final int MAX_SHORTCUTS = 10;

    private static final int MAX_UPDATES_PER_INTERVAL = 3;

    private static final int MAX_ICON_DIMENSION = 128;

    private static final int MAX_ICON_DIMENSION_LOWRAM = 32;

    private static final ShortcutQuery QUERY_ALL = new ShortcutQuery();

    private final ArrayList<String> mCallerPermissions = new ArrayList<>();

    static {
        QUERY_ALL.setQueryFlags(
                ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mServiceContext = spy(new ServiceContext());
        mClientContext = new ClientContext();

        mMockPackageManager = mock(PackageManager.class);
        mMockPackageManagerInternal = mock(PackageManagerInternal.class);
        mMockUserManager = mock(UserManager.class);

        // Prepare injection values.

        mInjectedCurrentTimeLillis = START_TIME;

        mInjectedPackages = new HashMap<>();;
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 1);
        addPackage(CALLING_PACKAGE_2, CALLING_UID_2, 2);
        addPackage(CALLING_PACKAGE_3, CALLING_UID_3, 3);
        addPackage(CALLING_PACKAGE_4, CALLING_UID_4, 10);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 4);
        addPackage(LAUNCHER_2, LAUNCHER_UID_2, 5);
        addPackage(LAUNCHER_3, LAUNCHER_UID_3, 6);
        addPackage(LAUNCHER_4, LAUNCHER_UID_4, 10);

        // CALLING_PACKAGE_3 / LAUNCHER_3 are not backup target.
        updatePackageInfo(CALLING_PACKAGE_3,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);
        updatePackageInfo(LAUNCHER_3,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        mUninstalledPackages = new HashSet<>();

        mInjectedFilePathRoot = new File(getTestContext().getCacheDir(), "test-files");

        deleteAllSavedFiles();

        // Set up users.
        doAnswer(inv -> {
                assertSystem();
                return USER_INFO_0;
        }).when(mMockUserManager).getUserInfo(eq(USER_0));

        doAnswer(inv -> {
                assertSystem();
                return USER_INFO_10;
        }).when(mMockUserManager).getUserInfo(eq(USER_10));

        doAnswer(inv -> {
                assertSystem();
                return USER_INFO_11;
        }).when(mMockUserManager).getUserInfo(eq(USER_11));

        doAnswer(inv -> {
                assertSystem();
                return USER_INFO_P0;
        }).when(mMockUserManager).getUserInfo(eq(USER_P0));

        // User 0 is always running.
        when(mMockUserManager.isUserRunning(eq(USER_0))).thenReturn(true);

        initService();
        setCaller(CALLING_PACKAGE_1);

        // In order to complicate the situation, we set mLocaleChangeSequenceNumber to 1 by
        // calling this.  Running test with mLocaleChangeSequenceNumber == 0 might make us miss
        // some edge cases.
        mInternal.onSystemLocaleChangedNoLock();
    }

    private static UserInfo withProfileGroupId(UserInfo in, int groupId) {
        in.profileGroupId = groupId;
        return in;
    }

    @Override
    protected void tearDown() throws Exception {
        if (DUMP_IN_TEARDOWN) dumpsysOnLogcat("Teardown");

        shutdownServices();

        super.tearDown();
    }

    private Context getTestContext() {
        return getInstrumentation().getContext();
    }

    private void deleteAllSavedFiles() {
        // Empty the data directory.
        if (mInjectedFilePathRoot.exists()) {
            Assert.assertTrue("failed to delete dir",
                    FileUtils.deleteContents(mInjectedFilePathRoot));
        }
        mInjectedFilePathRoot.mkdirs();
    }

    /** (Re-) init the manager and the service. */
    private void initService() {
        shutdownServices();

        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        // Instantiate targets.
        mService = new ShortcutServiceTestable(mServiceContext, Looper.getMainLooper());
        mManager = new ShortcutManagerTestable(mClientContext, mService);

        mInternal = LocalServices.getService(ShortcutServiceInternal.class);

        mLauncherAppImpl = new LauncherAppImplTestable(mServiceContext);
        mLauncherApps = null;
        mLauncherAppsMap.clear();

        // Load the setting file.
        mService.onBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);
    }

    private void shutdownServices() {
        if (mService != null) {
            // Flush all the unsaved data from the previous instance.
            mService.saveDirtyInfo();
        }
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        mService = null;
        mManager = null;
        mInternal = null;
        mLauncherAppImpl = null;
        mLauncherApps = null;
        mLauncherAppsMap.clear();
    }

    private void addPackage(String packageName, int uid, int version) {
        addPackage(packageName, uid, version, packageName);
    }

    private Signature[] genSignatures(String... signatures) {
        final Signature[] sigs = new Signature[signatures.length];
        for (int i = 0; i < signatures.length; i++){
            sigs[i] = new Signature(signatures[i].getBytes());
        }
        return sigs;
    }

    private PackageInfo genPackage(String packageName, int uid, int version, String... signatures) {
        final PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = uid;
        pi.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
                | ApplicationInfo.FLAG_ALLOW_BACKUP;
        pi.versionCode = version;
        pi.applicationInfo.versionCode = version;
        pi.signatures = genSignatures(signatures);

        return pi;
    }

    private void addPackage(String packageName, int uid, int version, String... signatures) {
        mInjectedPackages.put(packageName, genPackage(packageName, uid, version, signatures));
    }

    private void updatePackageInfo(String packageName, Consumer<PackageInfo> c) {
        c.accept(mInjectedPackages.get(packageName));
    }

    private void updatePackageVersion(String packageName, int increment) {
        updatePackageInfo(packageName, pi -> {
            pi.versionCode += increment;
            pi.applicationInfo.versionCode += increment;
        });
    }

    private void uninstallPackage(int userId, String packageName) {
        if (ENABLE_DUMP) {
            Log.i(TAG, "Unnstall package " + packageName + " / " + userId);
        }
        mUninstalledPackages.add(PackageWithUser.of(userId, packageName));
    }

    private void installPackage(int userId, String packageName) {
        if (ENABLE_DUMP) {
            Log.i(TAG, "Install package " + packageName + " / " + userId);
        }
        mUninstalledPackages.remove(PackageWithUser.of(userId, packageName));
    }

    PackageInfo getInjectedPackageInfo(String packageName, @UserIdInt int userId,
            boolean getSignatures) {
        final PackageInfo pi = mInjectedPackages.get(packageName);
        if (pi == null) return null;

        final PackageInfo ret = new PackageInfo();
        ret.packageName = pi.packageName;
        ret.versionCode = pi.versionCode;
        ret.applicationInfo = new ApplicationInfo(pi.applicationInfo);
        ret.applicationInfo.uid = UserHandle.getUid(userId, pi.applicationInfo.uid);
        if (mUninstalledPackages.contains(PackageWithUser.of(userId, packageName))) {
            ret.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }

        if (getSignatures) {
            ret.signatures = pi.signatures;
        }

        return ret;
    }

    /** Replace the current calling package */
    private void setCaller(String packageName, int userId) {
        mInjectedClientPackage = packageName;
        mInjectedCallingUid =
                Preconditions.checkNotNull(getInjectedPackageInfo(packageName, userId, false),
                        "Unknown package").applicationInfo.uid;

        // Set up LauncherApps for this caller.
        final Pair<Integer, String> key = Pair.create(userId, packageName);
        if (!mLauncherAppsMap.containsKey(key)) {
            mLauncherAppsMap.put(key, new LauncherAppsTestable(mClientContext, mLauncherAppImpl));
        }
        mLauncherApps = mLauncherAppsMap.get(key);
    }

    private void setCaller(String packageName) {
        setCaller(packageName, UserHandle.USER_SYSTEM);
    }

    private String getCallingPackage() {
        return mInjectedClientPackage;
    }

    private void setDefaultLauncherChecker(BiPredicate<String, Integer> p) {
        mDefaultLauncherChecker = p;
    }

    private void runWithCaller(String packageName, int userId, Runnable r) {
        final String previousPackage = mInjectedClientPackage;
        final int previousUserId = UserHandle.getUserId(mInjectedCallingUid);

        setCaller(packageName, userId);

        r.run();

        setCaller(previousPackage, previousUserId);
    }

    private int getCallingUserId() {
        return UserHandle.getUserId(mInjectedCallingUid);
    }

    private UserHandle getCallingUser() {
        return UserHandle.of(getCallingUserId());
    }

    /** For debugging */
    private void dumpsysOnLogcat() {
        dumpsysOnLogcat("");
    }

    private void dumpsysOnLogcat(String message) {
        dumpsysOnLogcat(message, false);
    }

    private void dumpsysOnLogcat(String message, boolean force) {
        if (force || !ENABLE_DUMP) return;

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintWriter pw = new PrintWriter(out);
        mService.dumpInner(pw, null);
        pw.close();

        Log.e(TAG, "Dumping ShortcutService: " + message);
        for (String line : out.toString().split("\n")) {
            Log.e(TAG, line);
        }
    }

    /**
     * For debugging, dump arbitrary file on logcat.
     */
    private void dumpFileOnLogcat(String path) {
        dumpFileOnLogcat(path, "");
    }

    private void dumpFileOnLogcat(String path, String message) {
        if (!ENABLE_DUMP) return;

        Log.i(TAG, "Dumping file: " + path + " " + message);
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                Log.i(TAG, line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't read file", e);
            fail("Exception " + e);
        }
    }

    /**
     * For debugging, dump the main state file on logcat.
     */
    private void dumpBaseStateFile() {
        mService.saveDirtyInfo();
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/system/" + ShortcutService.FILENAME_BASE_STATE);
    }

    /**
     * For debugging, dump per-user state file on logcat.
     */
    private void dumpUserFile(int userId) {
        dumpUserFile(userId, "");
    }

    private void dumpUserFile(int userId, String message) {
        mService.saveDirtyInfo();
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/user-" + userId
                + "/" + ShortcutService.FILENAME_USER_PACKAGES, message);
    }

    private void waitOnMainThread() throws Throwable {
        runTestOnUiThread(() -> {});
    }

    /**
     * Make a shortcut with an ID.
     */
    private ShortcutInfo makeShortcut(String id) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
    }

    /**
     * Make a shortcut with an ID and timestamp.
     */
    private ShortcutInfo makeShortcutWithTimestamp(String id, long timestamp) {
        final ShortcutInfo s = makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
        s.setTimestamp(timestamp);
        return s;
    }

    /**
     * Make a shortcut with an ID and icon.
     */
    private ShortcutInfo makeShortcutWithIcon(String id, Icon icon) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, icon,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
    }

    private ShortcutInfo makePackageShortcut(String packageName, String id) {
        String origCaller = getCallingPackage();

        setCaller(packageName);
        ShortcutInfo s = makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* weight =*/ 0);
        setCaller(origCaller); // restore the caller

        return s;
    }

    /**
     * Make multiple shortcuts with IDs.
     */
    private List<ShortcutInfo> makeShortcuts(String... ids) {
        final ArrayList<ShortcutInfo> ret = new ArrayList();
        for (String id : ids) {
            ret.add(makeShortcut(id));
        }
        return ret;
    }

    private ShortcutInfo.Builder makeShortcutBuilder() {
        return new ShortcutInfo.Builder(mClientContext);
    }

    /**
     * Make a shortcut with details.
     */
    private ShortcutInfo makeShortcut(String id, String title, ComponentName activity,
            Icon icon, Intent intent, int weight) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext)
                .setId(id)
                .setTitle(title)
                .setWeight(weight)
                .setIntent(intent);
        if (icon != null) {
            b.setIcon(icon);
        }
        if (activity != null) {
            b.setActivityComponent(activity);
        }
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeLillis); // HACK

        return s;
    }

    /**
     * Make an intent.
     */
    private Intent makeIntent(String action, Class<?> clazz, Object... bundleKeysAndValues) {
        final Intent intent = new Intent(action);
        intent.setComponent(makeComponent(clazz));
        intent.replaceExtras(makeBundle(bundleKeysAndValues));
        return intent;
    }

    /**
     * Make an component name, with the client context.
     */
    @NonNull
    private ComponentName makeComponent(Class<?> clazz) {
        return new ComponentName(mClientContext, clazz);
    }

    @NonNull
    private ShortcutInfo findById(List<ShortcutInfo> list, String id) {
        for (ShortcutInfo s : list) {
            if (s.getId().equals(id)) {
                return s;
            }
        }
        fail("Shortcut with id " + id + " not found");
        return null;
    }

    private void assertSystem() {
        assertEquals("Caller must be system", Process.SYSTEM_UID, mInjectedCallingUid);
    }

    private void assertResetTimes(long expectedLastResetTime, long expectedNextResetTime) {
        assertEquals(expectedLastResetTime, mService.getLastResetTimeLocked());
        assertEquals(expectedNextResetTime, mService.getNextResetTimeLocked());
    }

    public static List<ShortcutInfo> assertAllNotHaveIcon(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertNull("ID " + s.getId(), s.getIcon());
        }
        return actualShortcuts;
    }

    @NonNull
    private List<ShortcutInfo> assertAllHaveFlags(@NonNull List<ShortcutInfo> actualShortcuts,
            int shortcutFlags) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId() + " doesn't have flags " + shortcutFlags,
                    s.hasFlags(shortcutFlags));
        }
        return actualShortcuts;
    }

    private ShortcutInfo getPackageShortcut(String packageName, String shortcutId, int userId) {
        return mService.getPackageShortcutForTest(packageName, shortcutId, userId);
    }

    private void assertShortcutExists(String packageName, String shortcutId, int userId) {
        assertTrue(getPackageShortcut(packageName, shortcutId, userId) != null);
    }

    private void assertShortcutNotExists(String packageName, String shortcutId, int userId) {
        assertTrue(getPackageShortcut(packageName, shortcutId, userId) == null);
    }

    private Intent launchShortcutAndGetIntent(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        reset(mServiceContext);
        assertTrue(mLauncherApps.startShortcut(packageName, shortcutId, null, null,
                UserHandle.of(userId)));

        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mServiceContext).startActivityAsUser(
                intentCaptor.capture(),
                any(Bundle.class),
                eq(UserHandle.of(userId)));
        return intentCaptor.getValue();
    }

    private Intent launchShortcutAndGetIntent_withShortcutInfo(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        reset(mServiceContext);

        assertTrue(mLauncherApps.startShortcut(
                getShortcutInfoAsLauncher(packageName, shortcutId, userId), null, null));

        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mServiceContext).startActivityAsUser(
                intentCaptor.capture(),
                any(Bundle.class),
                eq(UserHandle.of(userId)));
        return intentCaptor.getValue();
    }

    private void assertShortcutLaunchable(@NonNull String packageName, @NonNull String shortcutId,
            int userId) {
        assertNotNull(launchShortcutAndGetIntent(packageName, shortcutId, userId));
        assertNotNull(launchShortcutAndGetIntent_withShortcutInfo(packageName, shortcutId, userId));
    }

    private void assertShortcutNotLaunchable(@NonNull String packageName,
            @NonNull String shortcutId, int userId) {
        try {
            final boolean ok = mLauncherApps.startShortcut(packageName, shortcutId, null, null,
                    UserHandle.of(userId));
            if (!ok) {
                return; // didn't launch, okay.
            }
            fail();
        } catch (SecurityException expected) {
            // security exception is okay too.
        }
    }

    private void assertBitmapDirectories(int userId, String... expectedDirectories) {
        final Set<String> expected = hashSet(set(expectedDirectories));

        final Set<String> actual = new HashSet<>();

        final File[] files = mService.getUserBitmapFilePath(userId).listFiles();
        if (files != null) {
            for (File child : files) {
                if (child.isDirectory()) {
                    actual.add(child.getName());
                }
            }
        }

        assertEquals(expected, actual);
    }

    private void assertBitmapFiles(int userId, String packageName, String... expectedFiles) {
        final Set<String> expected = hashSet(set(expectedFiles));

        final Set<String> actual = new HashSet<>();

        final File[] files = new File(mService.getUserBitmapFilePath(userId), packageName)
                .listFiles();
        if (files != null) {
            for (File child : files) {
                if (child.isFile()) {
                    actual.add(child.getName());
                }
            }
        }

        assertEquals(expected, actual);
    }

    private String getBitmapFilename(int userId, String packageName, String shortcutId) {
        final ShortcutInfo si = mService.getPackageShortcutForTest(packageName, shortcutId, userId);
        if (si == null) {
            return null;
        }
        return new File(si.getBitmapPath()).getName();
    }

    private ShortcutInfo getPackageShortcut(String packageName, String shortcutId) {
        return getPackageShortcut(packageName, shortcutId, getCallingUserId());
    }

    private ShortcutInfo getCallerShortcut(String shortcutId) {
        return getPackageShortcut(getCallingPackage(), shortcutId, getCallingUserId());
    }

    private List<ShortcutInfo> getLauncherShortcuts(String launcher, int userId, int queryFlags) {
        final List<ShortcutInfo>[] ret = new List[1];
        runWithCaller(launcher, userId, () -> {
            final ShortcutQuery q = new ShortcutQuery();
            q.setQueryFlags(queryFlags);
            ret[0] = mLauncherApps.getShortcuts(q, UserHandle.of(userId));
        });
        return ret[0];
    }

    private List<ShortcutInfo> getLauncherPinnedShortcuts(String launcher, int userId) {
        return getLauncherShortcuts(launcher, userId, ShortcutQuery.FLAG_GET_PINNED);
    }

    private ShortcutInfo getShortcutInfoAsLauncher(String packageName, String shortcutId,
            int userId) {
        final List<ShortcutInfo> infoList =
                mLauncherApps.getShortcutInfo(packageName, list(shortcutId),
                        UserHandle.of(userId));
        assertEquals("No shortcutInfo found (or too many of them)", 1, infoList.size());
        return infoList.get(0);
    }

    private Intent genPackageDeleteIntent(String pakcageName, int userId) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        i.setData(Uri.parse("package:" + pakcageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return i;
    }

    private Intent genPackageUpdateIntent(String pakcageName, int userId) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_ADDED);
        i.setData(Uri.parse("package:" + pakcageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        i.putExtra(Intent.EXTRA_REPLACING, true);
        return i;
    }
    private Intent genPackageDataClear(String packageName, int userId) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED);
        i.setData(Uri.parse("package:" + packageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return i;
    }

    private ShortcutInfo parceled(ShortcutInfo si) {
        Parcel p = Parcel.obtain();
        p.writeParcelable(si, 0);
        p.setDataPosition(0);
        ShortcutInfo si2 = p.readParcelable(getClass().getClassLoader());
        p.recycle();
        return si2;
    }

    /**
     * Test for the first launch path, no settings file available.
     */
    public void testFirstInitialize() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);
    }

    /**
     * Test for {@link ShortcutService#getLastResetTimeLocked()} and
     * {@link ShortcutService#getNextResetTimeLocked()}.
     */
    public void testUpdateAndGetNextResetTimeLocked() {
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeLillis += 100;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock, almost the reset time.
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;

        // Shouldn't have changed.
        assertResetTimes(START_TIME, START_TIME + INTERVAL);

        // Advance clock.
        mInjectedCurrentTimeLillis += 1;

        assertResetTimes(START_TIME + INTERVAL, START_TIME + 2 * INTERVAL);

        // Advance further; 4 hours since start.
        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from saved file.
     */
    public void testInitializeFromSavedFile() {

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;
        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);

        mService.saveBaseStateLocked();

        dumpBaseStateFile();

        mService.saveDirtyInfo();

        // Restore.
        initService();

        assertResetTimes(START_TIME + 4 * INTERVAL, START_TIME + 5 * INTERVAL);
    }

    /**
     * Test for the restoration from restored file.
     */
    public void testLoadFromBrokenFile() {
        // TODO Add various broken cases.
    }

    public void testLoadConfig() {
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_RESET_INTERVAL_SEC + "=123,"
                        + ConfigConstants.KEY_MAX_SHORTCUTS + "=4,"
                        + ConfigConstants.KEY_MAX_UPDATES_PER_INTERVAL + "=5,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=100,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "=50,"
                        + ConfigConstants.KEY_ICON_FORMAT + "=WEBP,"
                        + ConfigConstants.KEY_ICON_QUALITY + "=75");
        assertEquals(123000, mService.getResetIntervalForTest());
        assertEquals(4, mService.getMaxDynamicShortcutsForTest());
        assertEquals(5, mService.getMaxUpdatesPerIntervalForTest());
        assertEquals(100, mService.getMaxIconDimensionForTest());
        assertEquals(CompressFormat.WEBP, mService.getIconPersistFormatForTest());
        assertEquals(75, mService.getIconPersistQualityForTest());

        mInjectedIsLowRamDevice = true;
        mService.updateConfigurationLocked(
                ConfigConstants.KEY_MAX_ICON_DIMENSION_DP + "=100,"
                        + ConfigConstants.KEY_MAX_ICON_DIMENSION_DP_LOWRAM + "=50,"
                        + ConfigConstants.KEY_ICON_FORMAT + "=JPEG");
        assertEquals(ShortcutService.DEFAULT_RESET_INTERVAL_SEC * 1000,
                mService.getResetIntervalForTest());

        assertEquals(ShortcutService.DEFAULT_MAX_SHORTCUTS_PER_APP,
                mService.getMaxDynamicShortcutsForTest());

        assertEquals(ShortcutService.DEFAULT_MAX_UPDATES_PER_INTERVAL,
                mService.getMaxUpdatesPerIntervalForTest());

        assertEquals(50, mService.getMaxIconDimensionForTest());

        assertEquals(CompressFormat.JPEG, mService.getIconPersistFormatForTest());

        assertEquals(ShortcutService.DEFAULT_ICON_PERSIST_QUALITY,
                mService.getIconPersistQualityForTest());
    }

    // === Test for app side APIs ===

    /** Test for {@link android.content.pm.ShortcutManager#getMaxDynamicShortcutCount()} */
    public void testGetMaxDynamicShortcutCount() {
        assertEquals(MAX_SHORTCUTS, mManager.getMaxDynamicShortcutCount());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRemainingCallCount()} */
    public void testGetRemainingCallCount() {
        assertEquals(MAX_UPDATES_PER_INTERVAL, mManager.getRemainingCallCount());
    }

    /** Test for {@link android.content.pm.ShortcutManager#getRateLimitResetTime()} */
    public void testGetRateLimitResetTime() {
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL + 50;

        assertEquals(START_TIME + 5 * INTERVAL, mManager.getRateLimitResetTime());
    }

    public void testSetDynamicShortcuts() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.icon1);
        final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.icon2));

        final ShortcutInfo si1 = makeShortcut(
                "shortcut1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                icon1,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo si2 = makeShortcut(
                "shortcut2",
                "Title 2",
                /* activity */ null,
                icon2,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2");
        assertEquals(2, mManager.getRemainingCallCount());

        // TODO: Check fields

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1");
        assertEquals(1, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list()));
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getRemainingCallCount());

        dumpsysOnLogcat();

        mInjectedCurrentTimeLillis++; // Need to advance the clock for reset to work.
        mService.resetThrottlingInner(UserHandle.USER_SYSTEM);

        dumpsysOnLogcat();

        assertTrue(mManager.setDynamicShortcuts(list(si2, si3)));
        assertEquals(2, mManager.getDynamicShortcuts().size());

        // TODO Check max number

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(makeShortcut("s1"))));
        });
    }

    public void testAddDynamicShortcuts() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1");

        assertTrue(mManager.addDynamicShortcuts(list(si2, si3)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        // This should not crash.  It'll still consume the quota.
        assertTrue(mManager.addDynamicShortcuts(list()));
        assertEquals(0, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        mInjectedCurrentTimeLillis += INTERVAL; // reset

        // Add with the same ID
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("shortcut1"))));
        assertEquals(2, mManager.getRemainingCallCount());
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        // TODO Check max number

        // TODO Check fields.

        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s1"))));
        });
    }

    public void testDeleteDynamicShortcuts() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");
        final ShortcutInfo si4 = makeShortcut("shortcut4");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3, si4)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3", "shortcut4");

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.removeDynamicShortcuts(list("shortcut1"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3", "shortcut4");

        mManager.removeDynamicShortcuts(list("shortcut1"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3", "shortcut4");

        mManager.removeDynamicShortcuts(list("shortcutXXX"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut2", "shortcut3", "shortcut4");

        mManager.removeDynamicShortcuts(list("shortcut2", "shortcut4"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut3");

        mManager.removeDynamicShortcuts(list("shortcut3"));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()));

        // Still 2 calls left.
        assertEquals(2, mManager.getRemainingCallCount());
    }

    public void testDeleteAllDynamicShortcuts() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");
        final ShortcutInfo si2 = makeShortcut("shortcut2");
        final ShortcutInfo si3 = makeShortcut("shortcut3");

        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mManager.getDynamicShortcuts()),
                "shortcut1", "shortcut2", "shortcut3");

        assertEquals(2, mManager.getRemainingCallCount());

        mManager.removeAllDynamicShortcuts();
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(2, mManager.getRemainingCallCount());

        // Note delete shouldn't affect throttling, so...
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());
        assertEquals(0, mManager.getDynamicShortcuts().size());

        // This should still work.
        assertTrue(mManager.setDynamicShortcuts(list(si1, si2, si3)));
        assertEquals(3, mManager.getDynamicShortcuts().size());

        // Still 1 call left
        assertEquals(1, mManager.getRemainingCallCount());
    }

    public void testThrottling() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Reached the max

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Still throttled
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Now it should work.
        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1))); // fail
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 2, mManager.getRateLimitResetTime());

        // 4 hours later...
        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 5, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 5, mManager.getRateLimitResetTime());

        // Make sure getRemainingCallCount() itself gets reset without calling setDynamicShortcuts().
        mInjectedCurrentTimeLillis = START_TIME + 8 * INTERVAL;
        assertEquals(3, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 9, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL * 9, mManager.getRateLimitResetTime());
    }

    public void testThrottling_rewind() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        mInjectedCurrentTimeLillis = 12345; // Clock reset!

        // Since the clock looks invalid, the counter shouldn't have reset.
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Forward again.  Still haven't reset yet.
        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertEquals(2, mManager.getRemainingCallCount());
        assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());

        // Now rewind -- this will reset the counters.
        mInjectedCurrentTimeLillis = START_TIME - 100000;
        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        // Forward again, should be reset now.
        mInjectedCurrentTimeLillis += INTERVAL;
        assertEquals(3, mManager.getRemainingCallCount());
    }

    public void testThrottling_perPackage() {
        final ShortcutInfo si1 = makeShortcut("shortcut1");

        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(1, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Reached the max

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        // Try from a different caller.
        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        // Need to create a new one wit the updated package name.
        final ShortcutInfo si2 = makeShortcut("shortcut1");

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertEquals(2, mManager.getRemainingCallCount());

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertEquals(1, mManager.getRemainingCallCount());

        // Back to the original caller, still throttled.
        mInjectedClientPackage = CALLING_PACKAGE_1;
        mInjectedCallingUid = CALLING_UID_1;

        mInjectedCurrentTimeLillis = START_TIME + INTERVAL - 1;
        assertEquals(0, mManager.getRemainingCallCount());
        assertFalse(mManager.setDynamicShortcuts(list(si1)));
        assertEquals(0, mManager.getRemainingCallCount());

        // Now it should work.
        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis++;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis++;
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        mInjectedCurrentTimeLillis = START_TIME + 4 * INTERVAL;
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertTrue(mManager.setDynamicShortcuts(list(si1)));
        assertFalse(mManager.setDynamicShortcuts(list(si1)));

        mInjectedClientPackage = CALLING_PACKAGE_2;
        mInjectedCallingUid = CALLING_UID_2;

        assertEquals(3, mManager.getRemainingCallCount());

        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertTrue(mManager.setDynamicShortcuts(list(si2)));
        assertFalse(mManager.setDynamicShortcuts(list(si2)));
    }

    public void testIcons() throws IOException {
        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);
        final Icon res64x64 = Icon.createWithResource(getTestContext(), R.drawable.black_64x64);
        final Icon res512x512 = Icon.createWithResource(getTestContext(), R.drawable.black_512x512);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        final Icon bmp64x64 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_64x64));
        final Icon bmp512x512 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_512x512));

        // Set from package 1
        setCaller(CALLING_PACKAGE_1);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res32x32),
                makeShortcutWithIcon("res64x64", res64x64),
                makeShortcutWithIcon("bmp32x32", bmp32x32),
                makeShortcutWithIcon("bmp64x64", bmp64x64),
                makeShortcutWithIcon("bmp512x512", bmp512x512),
                makeShortcut("none")
        )));

        // getDynamicShortcuts() shouldn't return icons, thus assertAllNotHaveIcon().
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "bmp32x32",
                "bmp64x64",
                "bmp512x512",
                "none");

        // Call from another caller with the same ID, just to make sure storage is per-package.
        setCaller(CALLING_PACKAGE_2);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res512x512),
                makeShortcutWithIcon("res64x64", res512x512),
                makeShortcutWithIcon("none", res512x512)
        )));
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "res64x64",
                "none");

        // Different profile.  Note the names and the contents don't match.
        setCaller(CALLING_PACKAGE_1, USER_P0);
        assertTrue(mManager.setDynamicShortcuts(list(
                makeShortcutWithIcon("res32x32", res512x512),
                makeShortcutWithIcon("bmp32x32", bmp512x512)
        )));
        assertShortcutIds(assertAllNotHaveIcon(mManager.getDynamicShortcuts()),
                "res32x32",
                "bmp32x32");

        // Re-initialize and load from the files.
        mService.saveDirtyInfo();
        initService();

        // Load from launcher.
        Bitmap bmp;

        setCaller(LAUNCHER_1);
        // Check hasIconResource()/hasIconFile().
        assertShortcutIds(assertAllHaveIconResId(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_0))),
                "res32x32");

        assertShortcutIds(assertAllHaveIconResId(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res64x64", USER_0))),
                "res64x64");

        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_0))),
                "bmp32x32");

        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp64x64", USER_0))),
                "bmp64x64");

        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp512x512", USER_0))),
                "bmp512x512");

        assertShortcutIds(assertAllHaveIconResId(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_P0))),
                "res32x32");
        assertShortcutIds(assertAllHaveIconFile(
                list(getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_P0))),
                "bmp32x32");

        // Check
        assertEquals(
                R.drawable.black_32x32,
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_0)));

        assertEquals(
                R.drawable.black_64x64,
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res64x64", USER_0)));

        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_0)));
        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp64x64", USER_0)));
        assertEquals(
                0, // because it's not a resource
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp512x512", USER_0)));

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_0)));
        assertBitmapSize(32, 32, bmp);

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp64x64", USER_0)));
        assertBitmapSize(64, 64, bmp);

        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp512x512", USER_0)));
        assertBitmapSize(128, 128, bmp);

        assertEquals(
                R.drawable.black_512x512,
                mLauncherApps.getShortcutIconResId(
                        getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "res32x32", USER_P0)));
        // Should be 512x512, so shrunk.
        bmp = pfdToBitmap(mLauncherApps.getShortcutIconFd(
                getShortcutInfoAsLauncher(CALLING_PACKAGE_1, "bmp32x32", USER_P0)));
        assertBitmapSize(128, 128, bmp);

        // Also check the overload APIs too.
        assertEquals(
                R.drawable.black_32x32,
                mLauncherApps.getShortcutIconResId(CALLING_PACKAGE_1, "res32x32", HANDLE_USER_0));
        assertEquals(
                R.drawable.black_64x64,
                mLauncherApps.getShortcutIconResId(CALLING_PACKAGE_1, "res64x64", HANDLE_USER_0));
        assertEquals(
                R.drawable.black_512x512,
                mLauncherApps.getShortcutIconResId(CALLING_PACKAGE_1, "res32x32", HANDLE_USER_P0));
        bmp = pfdToBitmap(
                mLauncherApps.getShortcutIconFd(CALLING_PACKAGE_1, "bmp32x32", HANDLE_USER_P0));
        assertBitmapSize(128, 128, bmp);
    }

    private File makeFile(File baseDirectory, String... paths) {
        File ret = baseDirectory;

        for (String path : paths) {
            ret = new File(ret, path);
        }

        return ret;
    }

    public void testCleanupDanglingBitmaps() throws Exception {
        assertBitmapDirectories(USER_0, EMPTY_STRINGS);
        assertBitmapDirectories(USER_10, EMPTY_STRINGS);

        // Make some shortcuts with bitmap icons.
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", bmp32x32),
                    makeShortcutWithIcon("s2", bmp32x32),
                    makeShortcutWithIcon("s3", bmp32x32)
            ));
        });

        // Increment the time (which actually we don't have to), which is used for filenames.
        mInjectedCurrentTimeLillis++;

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s4", bmp32x32),
                    makeShortcutWithIcon("s5", bmp32x32),
                    makeShortcutWithIcon("s6", bmp32x32)
            ));
        });

        // Increment the time, which is used for filenames.
        mInjectedCurrentTimeLillis++;

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            mManager.setDynamicShortcuts(list(
            ));
        });

        // For USER-10, let's try without updating the times.
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("10s1", bmp32x32),
                    makeShortcutWithIcon("10s2", bmp32x32),
                    makeShortcutWithIcon("10s3", bmp32x32)
            ));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("10s4", bmp32x32),
                    makeShortcutWithIcon("10s5", bmp32x32),
                    makeShortcutWithIcon("10s6", bmp32x32)
            ));
        });
        runWithCaller(CALLING_PACKAGE_3, USER_10, () -> {
            mManager.setDynamicShortcuts(list(
            ));
        });

        dumpsysOnLogcat();

        // Check files and directories.
        // Package 3 has no bitmaps, so we don't create a directory.
        assertBitmapDirectories(USER_0, CALLING_PACKAGE_1, CALLING_PACKAGE_2);
        assertBitmapDirectories(USER_10, CALLING_PACKAGE_1, CALLING_PACKAGE_2);

        assertBitmapFiles(USER_0, CALLING_PACKAGE_1,
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s1"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s2"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s3")
                );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_2,
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s4"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s5"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s6")
        );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_1,
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s1"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s2"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s3")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_2,
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s4"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s5"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s6")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );

        // Then create random directories and files.
        makeFile(mService.getUserBitmapFilePath(USER_0), "a.b.c").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_0), "d.e.f").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_0), "d.e.f", "123").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), "d.e.f", "456").createNewFile();

        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_3).mkdir();

        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "1").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "2").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "3").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_0), CALLING_PACKAGE_1, "4").createNewFile();

        makeFile(mService.getUserBitmapFilePath(USER_10), "10a.b.c").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_10), "10d.e.f").mkdir();
        makeFile(mService.getUserBitmapFilePath(USER_10), "10d.e.f", "123").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), "10d.e.f", "456").createNewFile();

        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "1").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "2").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "3").createNewFile();
        makeFile(mService.getUserBitmapFilePath(USER_10), CALLING_PACKAGE_2, "4").createNewFile();

        assertBitmapDirectories(USER_0, CALLING_PACKAGE_1, CALLING_PACKAGE_2, CALLING_PACKAGE_3,
                "a.b.c", "d.e.f");

        // Save and load.  When a user is loaded, we do the cleanup.
        mService.saveDirtyInfo();
        initService();

        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_10);
        mService.handleUnlockUser(20); // Make sure the logic will still work for nonexistent user.

        // The below check is the same as above, except this time USER_0 use the CALLING_PACKAGE_3
        // directory.

        assertBitmapDirectories(USER_0, CALLING_PACKAGE_1, CALLING_PACKAGE_2, CALLING_PACKAGE_3);
        assertBitmapDirectories(USER_10, CALLING_PACKAGE_1, CALLING_PACKAGE_2);

        assertBitmapFiles(USER_0, CALLING_PACKAGE_1,
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s1"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s2"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_1, "s3")
        );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_2,
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s4"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s5"),
                getBitmapFilename(USER_0, CALLING_PACKAGE_2, "s6")
        );
        assertBitmapFiles(USER_0, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_1,
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s1"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s2"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_1, "10s3")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_2,
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s4"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s5"),
                getBitmapFilename(USER_10, CALLING_PACKAGE_2, "10s6")
        );
        assertBitmapFiles(USER_10, CALLING_PACKAGE_3,
                EMPTY_STRINGS
        );
    }

    private void checkShrinkBitmap(int expectedWidth, int expectedHeight, int resId, int maxSize) {
        assertBitmapSize(expectedWidth, expectedHeight,
                ShortcutService.shrinkBitmap(BitmapFactory.decodeResource(
                        getTestContext().getResources(), resId),
                        maxSize));
    }

    public void testShrinkBitmap() {
        checkShrinkBitmap(32, 32, R.drawable.black_512x512, 32);
        checkShrinkBitmap(511, 511, R.drawable.black_512x512, 511);
        checkShrinkBitmap(512, 512, R.drawable.black_512x512, 512);

        checkShrinkBitmap(1024, 4096, R.drawable.black_1024x4096, 4096);
        checkShrinkBitmap(1024, 4096, R.drawable.black_1024x4096, 4100);
        checkShrinkBitmap(512, 2048, R.drawable.black_1024x4096, 2048);

        checkShrinkBitmap(4096, 1024, R.drawable.black_4096x1024, 4096);
        checkShrinkBitmap(4096, 1024, R.drawable.black_4096x1024, 4100);
        checkShrinkBitmap(2048, 512, R.drawable.black_4096x1024, 2048);
    }

    private File openIconFileForWriteAndGetPath(int userId, String packageName)
            throws IOException {
        // Shortcut IDs aren't used in the path, so just pass the same ID.
        final FileOutputStreamWithPath out =
                mService.openIconFileForWrite(userId, makePackageShortcut(packageName, "id"));
        out.close();
        return out.getFile();
    }

    public void testOpenIconFileForWrite() throws IOException {
        mInjectedCurrentTimeLillis = 1000;

        final File p10_1_1 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_2 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);

        final File p10_2_1 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);
        final File p10_2_2 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);

        final File p11_1_1 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);
        final File p11_1_2 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);

        mInjectedCurrentTimeLillis++;

        final File p10_1_3 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_4 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);
        final File p10_1_5 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_1);

        final File p10_2_3 = openIconFileForWriteAndGetPath(10, CALLING_PACKAGE_2);
        final File p11_1_3 = openIconFileForWriteAndGetPath(11, CALLING_PACKAGE_1);

        // Make sure their paths are all unique
        assertAllUnique(list(
                p10_1_1,
                p10_1_2,
                p10_1_3,
                p10_1_4,
                p10_1_5,

                p10_2_1,
                p10_2_2,
                p10_2_3,

                p11_1_1,
                p11_1_2,
                p11_1_3
        ));

        // Check each set has the same parent.
        assertEquals(p10_1_1.getParent(), p10_1_2.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_3.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_4.getParent());
        assertEquals(p10_1_1.getParent(), p10_1_5.getParent());

        assertEquals(p10_2_1.getParent(), p10_2_2.getParent());
        assertEquals(p10_2_1.getParent(), p10_2_3.getParent());

        assertEquals(p11_1_1.getParent(), p11_1_2.getParent());
        assertEquals(p11_1_1.getParent(), p11_1_3.getParent());

        // Check the parents are still unique.
        assertAllUnique(list(
                p10_1_1.getParent(),
                p10_2_1.getParent(),
                p11_1_1.getParent()
        ));

        // All files created at the same time for the same package/user, expcet for the first ones,
        // will have "_" in the path.
        assertFalse(p10_1_1.getName().contains("_"));
        assertTrue(p10_1_2.getName().contains("_"));
        assertFalse(p10_1_3.getName().contains("_"));
        assertTrue(p10_1_4.getName().contains("_"));
        assertTrue(p10_1_5.getName().contains("_"));

        assertFalse(p10_2_1.getName().contains("_"));
        assertTrue(p10_2_2.getName().contains("_"));
        assertFalse(p10_2_3.getName().contains("_"));

        assertFalse(p11_1_1.getName().contains("_"));
        assertTrue(p11_1_2.getName().contains("_"));
        assertFalse(p11_1_3.getName().contains("_"));
    }

    public void testUpdateShortcuts() {
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5")
            )));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcut("s2"),
                    makeShortcut("s3"),
                    makeShortcut("s4"),
                    makeShortcut("s5")
            )));
        });
        runWithCaller(LAUNCHER_1, UserHandle.USER_SYSTEM, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2", "s3"),
                    getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s4", "s5"),
                    getCallingUser());
        });
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.removeDynamicShortcuts(list("s1"));
            mManager.removeDynamicShortcuts(list("s2"));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            mManager.removeDynamicShortcuts(list("s1"));
            mManager.removeDynamicShortcuts(list("s3"));
            mManager.removeDynamicShortcuts(list("s5"));
        });
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s3", "s4", "s5");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s2", "s4");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s4", "s5");
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            ShortcutInfo s2 = makeShortcutBuilder()
                    .setId("s2")
                    .setIcon(Icon.createWithResource(getTestContext(), R.drawable.black_32x32))
                    .build();

            ShortcutInfo s4 = makeShortcutBuilder()
                    .setId("s4")
                    .setTitle("new title")
                    .build();

            mManager.updateShortcuts(list(s2, s4));
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            ShortcutInfo s2 = makeShortcutBuilder()
                    .setId("s2")
                    .setIntent(makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                            "key1", "val1"))
                    .build();

            ShortcutInfo s4 = makeShortcutBuilder()
                    .setId("s4")
                    .setIntent(new Intent(Intent.ACTION_ALL_APPS))
                    .build();

            mManager.updateShortcuts(list(s2, s4));
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s3", "s4", "s5");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");

            ShortcutInfo s = getCallerShortcut("s2");
            assertTrue(s.hasIconResource());
            assertEquals(R.drawable.black_32x32, s.getIconResourceId());
            assertEquals("Title-s2", s.getTitle());

            s = getCallerShortcut("s4");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("new title", s.getTitle());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(
                    mManager.getDynamicShortcuts()),
                    "s2", "s4");
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s4", "s5");

            ShortcutInfo s = getCallerShortcut("s2");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("Title-s2", s.getTitle());
            assertEquals(Intent.ACTION_ANSWER, s.getIntent().getAction());
            assertEquals(1, s.getIntent().getExtras().size());

            s = getCallerShortcut("s4");
            assertFalse(s.hasIconResource());
            assertEquals(0, s.getIconResourceId());
            assertEquals("Title-s4", s.getTitle());
            assertEquals(Intent.ACTION_ALL_APPS, s.getIntent().getAction());
            assertBundleEmpty(s.getIntent().getExtras());
        });
        // TODO Check with other fields too.

        // TODO Check bitmap removal too.

        runWithCaller(CALLING_PACKAGE_2, USER_11, () -> {
            mManager.updateShortcuts(list());
        });
    }

    // === Test for launcher side APIs ===

    private static ShortcutQuery buildQuery(long changedSince,
            String packageName, ComponentName componentName,
            /* @ShortcutQuery.QueryFlags */ int flags) {
        return buildQuery(changedSince, packageName, null, componentName, flags);
    }

    private static ShortcutQuery buildQuery(long changedSince,
            String packageName, List<String> shortcutIds, ComponentName componentName,
            /* @ShortcutQuery.QueryFlags */ int flags) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setChangedSince(changedSince);
        q.setPackage(packageName);
        q.setShortcutIds(shortcutIds);
        q.setActivity(componentName);
        q.setQueryFlags(flags);
        return q;
    }

    private static ShortcutQuery buildAllQuery(String packageName) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setQueryFlags(ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED);
        return q;
    }

    private static ShortcutQuery buildPinnedQuery(String packageName) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setQueryFlags(ShortcutQuery.FLAG_GET_PINNED);
        return q;
    }

    public void testGetShortcuts() {

        // Set up shortcuts.

        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 5000);
        final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 1000);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
        final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
        final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
        assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));

        setCaller(CALLING_PACKAGE_3);
        final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s3", START_TIME + 5000);
        assertTrue(mManager.setDynamicShortcuts(list(s3_2)));

        setCaller(LAUNCHER_1);

        // Get dynamic
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                "s1", "s2"))));

        // Get pinned
        assertShortcutIds(
                mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED), getCallingUser())
                /* none */);

        // Get both, with timestamp
        assertAllDynamic(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC),
                        getCallingUser())),
                "s2", "s3"))));

        // FLAG_GET_KEY_FIELDS_ONLY
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s2", "s3"))));

        // With ID.
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3"),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s3"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3", "s2", "ss"),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser())),
                "s2", "s3"))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list("s3x", "s2x"),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser()))
                /* empty */))));
        assertAllDynamic(assertAllNotHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2, list(),
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_KEY_FIELDS_ONLY),
                        getCallingUser()))
                /* empty */))));

        // Pin some shortcuts.
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                list("s3", "s4"), getCallingUser());

        // Pinned ones only
        assertAllPinned(assertAllHaveTitle(assertAllNotHaveIntents(assertShortcutIds(
                assertAllNotKeyFieldsOnly(mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 1000, CALLING_PACKAGE_2,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_PINNED),
                        getCallingUser())),
                "s3"))));

        // All packages.
        assertShortcutIds(assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcuts(buildQuery(
                        /* time =*/ 5000, /* package= */ null,
                        /* activity =*/ null,
                        ShortcutQuery.FLAG_GET_DYNAMIC | ShortcutQuery.FLAG_GET_PINNED),
                        getCallingUser())),
                "s1", "s3");

        assertExpectException(
                IllegalArgumentException.class, "package name must also be set", () -> {
            mLauncherApps.getShortcuts(buildQuery(
                    /* time =*/ 0, /* package= */ null, list("id"),
                    /* activity =*/ null, /* flags */ 0), getCallingUser());
        });

        // TODO More tests: pinned but dynamic, filter by activity
    }

    public void testGetShortcutInfo() {
        // Create shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));
        dumpsysOnLogcat();

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity2.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(list(s2_1)));
        dumpsysOnLogcat();

        // Pin some.
        setCaller(LAUNCHER_1);

        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                list("s2"), getCallingUser());

        dumpsysOnLogcat();

        // Delete some.
        setCaller(CALLING_PACKAGE_1);
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
        mManager.removeDynamicShortcuts(list("s2"));
        assertShortcutIds(mManager.getPinnedShortcuts(), "s2");

        dumpsysOnLogcat();

        setCaller(LAUNCHER_1);
        List<ShortcutInfo> list;

        // Note we don't guarantee the orders.
        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                assertAllNotKeyFieldsOnly(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_1,
                list("s2", "s1", "s3", null), getCallingUser())))),
                "s1", "s2");
        assertEquals("Title 1", findById(list, "s1").getTitle());
        assertEquals("Title 2", findById(list, "s2").getTitle());

        assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_1,
                        list("s3"), getCallingUser())))
                /* none */);

        list = assertShortcutIds(assertAllHaveTitle(assertAllNotHaveIntents(
                mLauncherApps.getShortcutInfo(CALLING_PACKAGE_2,
                        list("s1", "s2", "s3"), getCallingUser()))),
                "s1");
        assertEquals("ABC", findById(list, "s1").getTitle());
    }

    public void testPinShortcutAndGetPinnedShortcuts() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            final ShortcutInfo s1_1 = makeShortcutWithTimestamp("s1", 1000);
            final ShortcutInfo s1_2 = makeShortcutWithTimestamp("s2", 2000);

            assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            final ShortcutInfo s2_2 = makeShortcutWithTimestamp("s2", 1500);
            final ShortcutInfo s2_3 = makeShortcutWithTimestamp("s3", 3000);
            final ShortcutInfo s2_4 = makeShortcutWithTimestamp("s4", 500);
            assertTrue(mManager.setDynamicShortcuts(list(s2_2, s2_3, s2_4)));
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            final ShortcutInfo s3_2 = makeShortcutWithTimestamp("s2", 1000);
            assertTrue(mManager.setDynamicShortcuts(list(s3_2)));
        });

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2", "s3"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3", "s4", "s5"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3,
                    list("s3"), getCallingUser());  // Note ID doesn't exist
        });

        // Delete some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
            mManager.removeDynamicShortcuts(list("s2"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s2");
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");
            mManager.removeDynamicShortcuts(list("s3"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3", "s4");
        });

        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);
            mManager.removeDynamicShortcuts(list("s2"));
            assertShortcutIds(mManager.getPinnedShortcuts() /* none */);
        });

        // Get pinned shortcuts from launcher
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            // CALLING_PACKAGE_1 deleted s2, but it's pinned, so it still exists.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s2");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3", "s4");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_3,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);
        });
    }

    public void testPinShortcutAndGetPinnedShortcuts_multi() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        dumpsysOnLogcat();

        // Pin some.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3", "s4"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2", "s4"), getCallingUser());
        });

        dumpsysOnLogcat();

        // Delete some.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3");
            mManager.removeDynamicShortcuts(list("s3"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s3");
        });

        dumpsysOnLogcat();

        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(mManager.getPinnedShortcuts(), "s1", "s2");
            mManager.removeDynamicShortcuts(list("s1"));
            mManager.removeDynamicShortcuts(list("s3"));
            assertShortcutIds(mManager.getPinnedShortcuts(), "s1", "s2");
        });

        dumpsysOnLogcat();

        // Get pinned shortcuts from launcher
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");

            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
        });

        dumpsysOnLogcat();

        runWithCaller(LAUNCHER_2, USER_0, () -> {
            // Launcher2 still has no pinned ones.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser())))
                    /* none */);

            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");

            // Now pin some.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1", "s2"), getCallingUser());

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2"), getCallingUser());

            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");

            // S1 was not visible to it, so shouldn't be pinned.
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");
        });

        // Re-initialize and load from the files.
        mService.saveDirtyInfo();
        initService();

        // Load from file.
        mService.handleUnlockUser(USER_0);

        // Make sure package info is restored too.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s1", "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED
                                    | ShortcutQuery.FLAG_GET_DYNAMIC), getCallingUser())),
                    "s2");
        });

        // Delete all dynamic.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();

            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2", "s3");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();

            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s2", "s1");
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2");

            // from all packages.
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, null,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s2", "s3");

            // Update pined.  Note s2 and s3 are actually available, but not visible to this
            // launcher, so still can't be pinned.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3", "s4"),
                    getCallingUser());

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");
        });
        // Re-publish s1.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s1"))));

            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2", "s3");
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s3");

            // Now "s1" is visible, so can be pinned.
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s2", "s3", "s4"),
                    getCallingUser());

            assertShortcutIds(assertAllPinned(assertAllNotKeyFieldsOnly(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()))),
                    "s1", "s3");
        });

        // Now clear pinned shortcuts.  First, from launcher 1.
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list(), getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list(), getCallingUser());

            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s1", "s2");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()), "s2");
        });

        // Clear all pins from launcher 2.
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list(), getCallingUser());
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list(), getCallingUser());

            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), getCallingUser()).size());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()), "s1");
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });
    }

    public void testPinShortcutAndGetPinnedShortcuts_crossProfile_plusLaunch() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });

        // Pin some shortcuts and see the result.

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1", "s2", "s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s2", "s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_2, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1", "s2", "s3"), HANDLE_USER_10);
        });

        // Cross profile pinning.
        final int PIN_AND_DYNAMIC = ShortcutQuery.FLAG_GET_PINNED | ShortcutQuery.FLAG_GET_DYNAMIC;

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_10)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3", "s4", "s5", "s6");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3", "s4", "s5", "s6");
        });

        // Remove some dynamic shortcuts.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_10)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_10)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_10)),
                    "s1", "s2", "s3");

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });

        // Save & load and make sure we still have the same information.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s2", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_P0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_1,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_PINNED), HANDLE_USER_0)),
                    "s3");
            assertShortcutIds(assertAllDynamic(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, ShortcutQuery.FLAG_GET_DYNAMIC), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllDynamicOrPinned(
                    mLauncherApps.getShortcuts(buildQuery(/* time =*/ 0, CALLING_PACKAGE_2,
                    /* activity =*/ null, PIN_AND_DYNAMIC), HANDLE_USER_0)),
                    "s1", "s3");

            assertShortcutLaunchable(CALLING_PACKAGE_1, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_1, "s3", USER_0);

            assertShortcutLaunchable(CALLING_PACKAGE_2, "s1", USER_0);
            assertShortcutNotLaunchable(CALLING_PACKAGE_2, "s2", USER_0);
            assertShortcutLaunchable(CALLING_PACKAGE_2, "s3", USER_0);

            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s1", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s2", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s3", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s4", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s5", USER_10);
            assertShortcutNotLaunchable(CALLING_PACKAGE_1, "s6", USER_10);
        });
    }

    public void testStartShortcut() {
        // Create some shortcuts.
        setCaller(CALLING_PACKAGE_1);
        final ShortcutInfo s1_1 = makeShortcut(
                "s1",
                "Title 1",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);

        final ShortcutInfo s1_2 = makeShortcut(
                "s2",
                "Title 2",
                /* activity */ null,
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                /* weight */ 12);

        assertTrue(mManager.setDynamicShortcuts(list(s1_1, s1_2)));

        setCaller(CALLING_PACKAGE_2);
        final ShortcutInfo s2_1 = makeShortcut(
                "s1",
                "ABC",
                makeComponent(ShortcutActivity.class),
                /* icon =*/ null,
                makeIntent(Intent.ACTION_ANSWER, ShortcutActivity.class,
                        "key1", "val1", "nest", makeBundle("key", 123)),
                /* weight */ 10);
        assertTrue(mManager.setDynamicShortcuts(list(s2_1)));

        // Pin all.
        setCaller(LAUNCHER_1);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                list("s1", "s2"), getCallingUser());

        mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                list("s1"), getCallingUser());

        // Just to make it complicated, delete some.
        setCaller(CALLING_PACKAGE_1);
        mManager.removeDynamicShortcuts(list("s2"));

        // intent and check.
        setCaller(LAUNCHER_1);

        Intent intent;
        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s1", USER_0);
        assertEquals(ShortcutActivity2.class.getName(), intent.getComponent().getClassName());


        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_1, "s2", USER_0);
        assertEquals(ShortcutActivity3.class.getName(), intent.getComponent().getClassName());

        intent = launchShortcutAndGetIntent(CALLING_PACKAGE_2, "s1", USER_0);
        assertEquals(ShortcutActivity.class.getName(), intent.getComponent().getClassName());

        // TODO Check extra, etc
    }

    public void testLauncherCallback() throws Throwable {
        LauncherApps.Callback c0 = mock(LauncherApps.Callback.class);

        // Set listeners

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerCallback(c0, new Handler(Looper.getMainLooper()));
        });

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        waitOnMainThread();
        ArgumentCaptor<List> shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3");

        // From different package.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_2),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3");

        // Different user, callback shouldn't be called.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        waitOnMainThread();
        verify(c0, times(0)).onShortcutsChanged(
                anyString(),
                any(List.class),
                any(UserHandle.class)
        );

        // Test for addDynamicShortcuts.
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            dumpsysOnLogcat("before addDynamicShortcuts");
            assertTrue(mManager.addDynamicShortcuts(list(makeShortcut("s4"))));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s1", "s2", "s3", "s4");

        // Test for remove
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.removeDynamicShortcuts(list("s1"));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s2", "s3", "s4");

        // Test for update
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertTrue(mManager.updateShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"))));
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertShortcutIds(assertAllDynamic(shortcuts.getValue()),
                "s2", "s3", "s4");

        // Test for deleteAll
        reset(c0);
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            mManager.removeAllDynamicShortcuts();
        });

        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertEquals(0, shortcuts.getValue().size());

        // Remove CALLING_PACKAGE_2
        reset(c0);
        uninstallPackage(USER_0, CALLING_PACKAGE_2);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_0, USER_0);

        // Should get a callback with an empty list.
        waitOnMainThread();
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_2),
                shortcuts.capture(),
                eq(HANDLE_USER_0)
        );
        assertEquals(0, shortcuts.getValue().size());
    }

    public void testLauncherCallback_crossProfile() throws Throwable {
        prepareCrossProfileDataSet();

        final Handler h = new Handler(Looper.getMainLooper());

        final LauncherApps.Callback c0_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_2 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_3 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c0_4 = mock(LauncherApps.Callback.class);

        final LauncherApps.Callback cP0_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c10_1 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c10_2 = mock(LauncherApps.Callback.class);
        final LauncherApps.Callback c11_1 = mock(LauncherApps.Callback.class);

        final List<LauncherApps.Callback> all =
                list(c0_1, c0_2, c0_3, c0_4, cP0_1, c10_1, c11_1);

        setDefaultLauncherChecker((pkg, userId) -> {
            switch (userId) {
                case USER_0:
                    return LAUNCHER_2.equals(pkg);
                case USER_P0:
                    return LAUNCHER_1.equals(pkg);
                case USER_10:
                    return LAUNCHER_1.equals(pkg);
                case USER_11:
                    return LAUNCHER_1.equals(pkg);
                default:
                    return false;
            }
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> mLauncherApps.registerCallback(c0_1, h));
        runWithCaller(LAUNCHER_2, USER_0, () -> mLauncherApps.registerCallback(c0_2, h));
        runWithCaller(LAUNCHER_3, USER_0, () -> mLauncherApps.registerCallback(c0_3, h));
        runWithCaller(LAUNCHER_4, USER_0, () -> mLauncherApps.registerCallback(c0_4, h));
        runWithCaller(LAUNCHER_1, USER_P0, () -> mLauncherApps.registerCallback(cP0_1, h));
        runWithCaller(LAUNCHER_1, USER_10, () -> mLauncherApps.registerCallback(c10_1, h));
        runWithCaller(LAUNCHER_2, USER_10, () -> mLauncherApps.registerCallback(c10_2, h));
        runWithCaller(LAUNCHER_1, USER_11, () -> mLauncherApps.registerCallback(c11_1, h));

        // User 0.

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c0_2, HANDLE_USER_0, CALLING_PACKAGE_1, "s1", "s2", "s3");
        assertCallbackReceived(cP0_1, HANDLE_USER_0, CALLING_PACKAGE_1, "s1", "s2", "s3", "s4");

        // User 0, different package.

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c0_2, HANDLE_USER_0, CALLING_PACKAGE_3, "s1", "s2", "s3", "s4");
        assertCallbackReceived(cP0_1, HANDLE_USER_0, CALLING_PACKAGE_3,
                "s1", "s2", "s3", "s4", "s5", "s6");

        // Work profile, but not running, so don't send notifications.

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_2);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(cP0_1);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);

        // Work profile, now running.

        when(mMockUserManager.isUserRunning(anyInt())).thenReturn(false);
        when(mMockUserManager.isUserRunning(eq(USER_P0))).thenReturn(true);

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(c10_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c0_2, HANDLE_USER_P0, CALLING_PACKAGE_1, "s1", "s2", "s3", "s5");
        assertCallbackReceived(cP0_1, HANDLE_USER_P0, CALLING_PACKAGE_1, "s1", "s2", "s3", "s4");

        // Normal secondary user.

        when(mMockUserManager.isUserRunning(anyInt())).thenReturn(false);
        when(mMockUserManager.isUserRunning(eq(USER_10))).thenReturn(true);

        resetAll(all);
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mManager.removeDynamicShortcuts(list());
        });
        waitOnMainThread();

        assertCallbackNotReceived(c0_1);
        assertCallbackNotReceived(c0_2);
        assertCallbackNotReceived(c0_3);
        assertCallbackNotReceived(c0_4);
        assertCallbackNotReceived(cP0_1);
        assertCallbackNotReceived(c10_2);
        assertCallbackNotReceived(c11_1);
        assertCallbackReceived(c10_1, HANDLE_USER_10, CALLING_PACKAGE_1,
                "x1", "x2", "x3", "x4", "x5");
    }

    // === Test for persisting ===

    public void testSaveAndLoadUser_empty() {
        assertTrue(mManager.setDynamicShortcuts(list()));

        Log.i(TAG, "Saved state");
        dumpsysOnLogcat();
        dumpUserFile(0);

        // Restore.
        mService.saveDirtyInfo();
        initService();

        assertEquals(0, mManager.getDynamicShortcuts().size());
    }

    /**
     * Try save and load, also stop/start the user.
     */
    public void testSaveAndLoadUser() {
        // First, create some shortcuts and save.
        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_64x16);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title1-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title1-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_16x64);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title2-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title2-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            final Icon icon1 = Icon.createWithResource(getTestContext(), R.drawable.black_64x64);
            final Icon icon2 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                    getTestContext().getResources(), R.drawable.icon2));

            final ShortcutInfo si1 = makeShortcut(
                    "s1",
                    "title10-1-1",
                    makeComponent(ShortcutActivity.class),
                    icon1,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity2.class,
                            "key1", "val1", "nest", makeBundle("key", 123)),
                        /* weight */ 10);

            final ShortcutInfo si2 = makeShortcut(
                    "s2",
                    "title10-1-2",
                        /* activity */ null,
                    icon2,
                    makeIntent(Intent.ACTION_ASSIST, ShortcutActivity3.class),
                        /* weight */ 12);

            assertTrue(mManager.setDynamicShortcuts(list(si1, si2)));

            assertEquals(START_TIME + INTERVAL, mManager.getRateLimitResetTime());
            assertEquals(2, mManager.getRemainingCallCount());
        });

        mService.getShortcutsForTest().get(UserHandle.USER_SYSTEM).setLauncherComponent(
                mService, new ComponentName("pkg1", "class"));

        // Restore.
        mService.saveDirtyInfo();
        initService();

        // Before the load, the map should be empty.
        assertEquals(0, mService.getShortcutsForTest().size());

        // this will pre-load the per-user info.
        mService.handleUnlockUser(UserHandle.USER_SYSTEM);

        // Now it's loaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        runWithCaller(CALLING_PACKAGE_1, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title1-1", getCallerShortcut("s1").getTitle());
            assertEquals("title1-2", getCallerShortcut("s2").getTitle());
        });
        runWithCaller(CALLING_PACKAGE_2, UserHandle.USER_SYSTEM, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title2-1", getCallerShortcut("s1").getTitle());
            assertEquals("title2-2", getCallerShortcut("s2").getTitle());
        });

        assertEquals("pkg1", mService.getShortcutsForTest().get(UserHandle.USER_SYSTEM)
                .getLauncherComponent().getPackageName());

        // Start another user
        mService.handleUnlockUser(USER_10);

        // Now the size is 2.
        assertEquals(2, mService.getShortcutsForTest().size());

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertShortcutIds(assertAllDynamic(assertAllHaveIntents(assertAllHaveIcon(
                    mManager.getDynamicShortcuts()))), "s1", "s2");
            assertEquals(2, mManager.getRemainingCallCount());

            assertEquals("title10-1-1", getCallerShortcut("s1").getTitle());
            assertEquals("title10-1-2", getCallerShortcut("s2").getTitle());
        });
        assertNull(mService.getShortcutsForTest().get(USER_10).getLauncherComponent());

        // Try stopping the user
        mService.handleCleanupUser(USER_10);

        // Now it's unloaded.
        assertEquals(1, mService.getShortcutsForTest().size());

        // TODO Check all other fields
    }

    public void testCleanupPackage() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s0_1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s0_2"))));
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s0_1"),
                    HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s0_2"),
                    HANDLE_USER_0);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s0_1"),
                    HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s0_2"),
                    HANDLE_USER_0);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s10_1"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s10_2"))));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s10_1"),
                    HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s10_2"),
                    HANDLE_USER_10);
        });
        runWithCaller(LAUNCHER_2, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s10_1"),
                    HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s10_2"),
                    HANDLE_USER_10);
        });

        // Remove all dynamic shortcuts; now all shortcuts are just pinned.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.removeAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            mManager.removeAllDynamicShortcuts();
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            mManager.removeAllDynamicShortcuts();
        });


        final SparseArray<ShortcutUser> users =  mService.getShortcutsForTest();
        assertEquals(2, users.size());
        assertEquals(USER_0, users.keyAt(0));
        assertEquals(USER_10, users.keyAt(1));

        final ShortcutUser user0 =  users.get(USER_0);
        final ShortcutUser user10 =  users.get(USER_10);


        // Check the registered packages.
        dumpsysOnLogcat();
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Nonexistent package.
        uninstallPackage(USER_0, "abc");
        mService.cleanUpPackageLocked("abc", USER_0, USER_0);

        // No changes.
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_1", "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a package.
        uninstallPackage(USER_0, CALLING_PACKAGE_1);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_0, USER_0);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_1),
                        PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_10),
                "s10_1", "s10_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a launcher.
        uninstallPackage(USER_10, LAUNCHER_1);
        mService.cleanUpPackageLocked(LAUNCHER_1, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1, CALLING_PACKAGE_2),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1", "s10_2");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove a package.
        uninstallPackage(USER_10, CALLING_PACKAGE_2);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_2, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_10, LAUNCHER_2)),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_10),
                "s10_1");
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // Remove the other launcher from user 10 too.
        uninstallPackage(USER_10, LAUNCHER_2);
        mService.cleanUpPackageLocked(LAUNCHER_2, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(CALLING_PACKAGE_1),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(
                set(),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");

        // Note the pinned shortcuts on user-10 no longer referred, so they should both be removed.
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutNotExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();

        // More remove.
        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        mService.cleanUpPackageLocked(CALLING_PACKAGE_1, USER_10, USER_10);

        assertEquals(set(CALLING_PACKAGE_2),
                hashSet(user0.getAllPackagesForTest().keySet()));
        assertEquals(set(),
                hashSet(user10.getAllPackagesForTest().keySet()));
        assertEquals(
                set(PackageWithUser.of(USER_0, LAUNCHER_1),
                        PackageWithUser.of(USER_0, LAUNCHER_2)),
                hashSet(user0.getAllLaunchersForTest().keySet()));
        assertEquals(set(),
                hashSet(user10.getAllLaunchersForTest().keySet()));
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_1, USER_0),
                "s0_2");
        assertShortcutIds(getLauncherPinnedShortcuts(LAUNCHER_2, USER_0),
                "s0_2");

        // Note the pinned shortcuts on user-10 no longer referred, so they should both be removed.
        assertShortcutNotExists(CALLING_PACKAGE_1, "s0_1", USER_0);
        assertShortcutExists(CALLING_PACKAGE_2, "s0_2", USER_0);
        assertShortcutNotExists(CALLING_PACKAGE_1, "s10_1", USER_10);
        assertShortcutNotExists(CALLING_PACKAGE_2, "s10_2", USER_10);

        mService.saveDirtyInfo();
    }

    public void testHandleGonePackage_crossProfile() {
        // Create some shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Pin some.

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s1"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s3"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s2"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), UserHandle.of(USER_P0));

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2,
                    list("s1"), HANDLE_USER_0);
        });

        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1,
                    list("s3"), HANDLE_USER_10);
        });

        // Check the state.

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Make sure all the information is persisted.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Start uninstalling.
        uninstallPackage(USER_10, LAUNCHER_1);
        mService.checkPackageChanges(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Uninstall.
        uninstallPackage(USER_10, CALLING_PACKAGE_1);
        mService.checkPackageChanges(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_P0, LAUNCHER_1);
        mService.checkPackageChanges(USER_0);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        mService.checkPackageChanges(USER_P0);
        
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_P0, CALLING_PACKAGE_1);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicAndPinned(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        // Uninstall
        uninstallPackage(USER_0, LAUNCHER_1);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));

        uninstallPackage(USER_0, CALLING_PACKAGE_2);

        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_0);
        mService.handleUnlockUser(USER_P0);
        mService.handleUnlockUser(USER_10);

        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_0));
        assertDynamicOnly(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_P0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_P0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s2", USER_0));
        assertNull(getPackageShortcut(CALLING_PACKAGE_2, "s3", USER_0));

        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s2", USER_10));
        assertNull(getPackageShortcut(CALLING_PACKAGE_1, "s3", USER_10));
    }

    private void checkCanRestoreTo(boolean expected, ShortcutPackageInfo spi,
            int version, String... signatures) {
        assertEquals(expected, spi.canRestoreTo(mService, genPackage(
                "dummy", /* uid */ 0, version, signatures)));
    }

    public void testCanRestoreTo() {
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "sig1");
        addPackage(CALLING_PACKAGE_2, CALLING_UID_1, 10, "sig1", "sig2");

        final ShortcutPackageInfo spi1 = ShortcutPackageInfo.generateForInstalledPackage(
                mService, CALLING_PACKAGE_1, USER_0);
        final ShortcutPackageInfo spi2 = ShortcutPackageInfo.generateForInstalledPackage(
                mService, CALLING_PACKAGE_2, USER_0);

        checkCanRestoreTo(true, spi1, 10, "sig1");
        checkCanRestoreTo(true, spi1, 10, "x", "sig1");
        checkCanRestoreTo(true, spi1, 10, "sig1", "y");
        checkCanRestoreTo(true, spi1, 10, "x", "sig1", "y");
        checkCanRestoreTo(true, spi1, 11, "sig1");

        checkCanRestoreTo(false, spi1, 10 /* empty */);
        checkCanRestoreTo(false, spi1, 10, "x");
        checkCanRestoreTo(false, spi1, 10, "x", "y");
        checkCanRestoreTo(false, spi1, 10, "x");
        checkCanRestoreTo(false, spi1, 9, "sig1");

        checkCanRestoreTo(true, spi2, 10, "sig1", "sig2");
        checkCanRestoreTo(true, spi2, 10, "sig2", "sig1");
        checkCanRestoreTo(true, spi2, 10, "x", "sig1", "sig2");
        checkCanRestoreTo(true, spi2, 10, "x", "sig2", "sig1");
        checkCanRestoreTo(true, spi2, 10, "sig1", "sig2", "y");
        checkCanRestoreTo(true, spi2, 10, "sig2", "sig1", "y");
        checkCanRestoreTo(true, spi2, 10, "x", "sig1", "sig2", "y");
        checkCanRestoreTo(true, spi2, 10, "x", "sig2", "sig1", "y");
        checkCanRestoreTo(true, spi2, 11, "x", "sig2", "sig1", "y");

        checkCanRestoreTo(false, spi2, 10, "sig1", "sig2x");
        checkCanRestoreTo(false, spi2, 10, "sig2", "sig1x");
        checkCanRestoreTo(false, spi2, 10, "x", "sig1x", "sig2");
        checkCanRestoreTo(false, spi2, 10, "x", "sig2x", "sig1");
        checkCanRestoreTo(false, spi2, 10, "sig1", "sig2x", "y");
        checkCanRestoreTo(false, spi2, 10, "sig2", "sig1x", "y");
        checkCanRestoreTo(false, spi2, 10, "x", "sig1x", "sig2", "y");
        checkCanRestoreTo(false, spi2, 10, "x", "sig2x", "sig1", "y");
        checkCanRestoreTo(false, spi2, 11, "x", "sig2x", "sig1", "y");
    }

    private boolean bitmapDirectoryExists(String packageName, int userId) {
        final File path = new File(mService.getUserBitmapFilePath(userId), packageName);
        return path.isDirectory();
    }

    public void testHandlePackageDelete() {
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        setCaller(CALLING_PACKAGE_1, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(
                makeShortcutWithIcon("s1", bmp32x32), makeShortcutWithIcon("s2", bmp32x32)
        )));

        setCaller(CALLING_PACKAGE_2, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_1, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_2, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        uninstallPackage(USER_0, CALLING_PACKAGE_1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDeleteIntent(CALLING_PACKAGE_1, USER_0));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        uninstallPackage(USER_10, CALLING_PACKAGE_2);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDeleteIntent(CALLING_PACKAGE_2, USER_10));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mInjectedPackages.remove(CALLING_PACKAGE_1);
        mInjectedPackages.remove(CALLING_PACKAGE_3);

        mService.handleUnlockUser(USER_0);

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mService.handleUnlockUser(USER_10);

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));
    }

    /** Almost ame as testHandlePackageDelete, except it doesn't uninstall packages. */
    public void testHandlePackageClearData() {
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));
        setCaller(CALLING_PACKAGE_1, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(
                makeShortcutWithIcon("s1", bmp32x32), makeShortcutWithIcon("s2", bmp32x32)
        )));

        setCaller(CALLING_PACKAGE_2, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_0);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_1, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_2, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        setCaller(CALLING_PACKAGE_3, USER_10);
        assertTrue(mManager.addDynamicShortcuts(list(makeShortcutWithIcon("s1", bmp32x32))));

        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDataClear(CALLING_PACKAGE_1, USER_0));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageDataClear(CALLING_PACKAGE_2, USER_10));

        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_0));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "s1", USER_10));
        assertNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_2, "s1", USER_10));
        assertNotNull(mService.getPackageShortcutForTest(CALLING_PACKAGE_3, "s1", USER_10));

        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_0));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_1, USER_10));
        assertFalse(bitmapDirectoryExists(CALLING_PACKAGE_2, USER_10));
        assertTrue(bitmapDirectoryExists(CALLING_PACKAGE_3, USER_10));
    }

    public void testHandlePackageUpdate() throws Throwable {

        // Set up shortcuts and launchers.

        final Icon res32x32 = Icon.createWithResource(getTestContext(), R.drawable.black_32x32);
        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcutWithIcon("s2", res32x32),
                    makeShortcutWithIcon("s3", res32x32),
                    makeShortcutWithIcon("s4", bmp32x32))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"),
                    makeShortcutWithIcon("s2", bmp32x32))));
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", res32x32))));
        });

        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", res32x32),
                    makeShortcutWithIcon("s2", res32x32))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcutWithIcon("s1", bmp32x32),
                    makeShortcutWithIcon("s2", bmp32x32))));
        });

        LauncherApps.Callback c0 = mock(LauncherApps.Callback.class);
        LauncherApps.Callback c10 = mock(LauncherApps.Callback.class);

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.registerCallback(c0, new Handler(Looper.getMainLooper()));
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.registerCallback(c10, new Handler(Looper.getMainLooper()));
        });

        mInjectedCurrentTimeLillis = START_TIME + 100;

        ArgumentCaptor<List> shortcuts;

        // First, call the event without updating the versions.
        reset(c0);
        reset(c10);

        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_1, USER_0));
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_1, USER_10));

        waitOnMainThread();

        // Version not changed, so no callback.
        verify(c0, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));
        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        // Next, update the version info for package 1.
        reset(c0);
        reset(c10);
        updatePackageVersion(CALLING_PACKAGE_1, 1);

        // Then send the broadcast, to only user-0.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_1, USER_0));

        waitOnMainThread();

        // User-0 should get the notification.
        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_0));

        // User-10 shouldn't yet get the notification.
        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));
        assertShortcutIds(shortcuts.getValue(), "s1", "s2", "s3", "s4");
        assertEquals(START_TIME,
                findShortcut(shortcuts.getValue(), "s1").getLastChangedTimestamp());
        assertEquals(START_TIME + 100,
                findShortcut(shortcuts.getValue(), "s2").getLastChangedTimestamp());
        assertEquals(START_TIME + 100,
                findShortcut(shortcuts.getValue(), "s3").getLastChangedTimestamp());
        assertEquals(START_TIME,
                findShortcut(shortcuts.getValue(), "s4").getLastChangedTimestamp());

        // Next, send unlock even on user-10.  Now we scan packages on this user and send a
        // notification to the launcher.
        mInjectedCurrentTimeLillis = START_TIME + 200;

        when(mMockUserManager.isUserRunning(eq(USER_10))).thenReturn(true);

        reset(c0);
        reset(c10);
        mService.handleUnlockUser(USER_10);

        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        verify(c10).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                shortcuts.capture(),
                eq(HANDLE_USER_10));

        assertShortcutIds(shortcuts.getValue(), "s1", "s2");
        assertEquals(START_TIME + 200,
                findShortcut(shortcuts.getValue(), "s1").getLastChangedTimestamp());
        assertEquals(START_TIME + 200,
                findShortcut(shortcuts.getValue(), "s2").getLastChangedTimestamp());


        // Do the same thing for package 2, which doesn't have resource icons.
        mInjectedCurrentTimeLillis = START_TIME + 300;

        reset(c0);
        reset(c10);
        updatePackageVersion(CALLING_PACKAGE_2, 10);

        // Then send the broadcast, to only user-0.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_2, USER_0));
        mService.handleUnlockUser(USER_10);

        waitOnMainThread();

        verify(c0, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_1),
                any(List.class),
                any(UserHandle.class));

        // Do the same thing for package 3
        mInjectedCurrentTimeLillis = START_TIME + 400;

        reset(c0);
        reset(c10);
        updatePackageVersion(CALLING_PACKAGE_3, 100);

        // Then send the broadcast, to only user-0.
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageUpdateIntent(CALLING_PACKAGE_3, USER_0));
        mService.handleUnlockUser(USER_10);

        waitOnMainThread();

        shortcuts = ArgumentCaptor.forClass(List.class);
        verify(c0).onShortcutsChanged(
                eq(CALLING_PACKAGE_3),
                shortcuts.capture(),
                eq(HANDLE_USER_0));

        // User 10 doesn't have package 3, so no callback.
        verify(c10, times(0)).onShortcutsChanged(
                eq(CALLING_PACKAGE_3),
                any(List.class),
                any(UserHandle.class));

        assertShortcutIds(shortcuts.getValue(), "s1");
        assertEquals(START_TIME + 400,
                findShortcut(shortcuts.getValue(), "s1").getLastChangedTimestamp());
    }

    private void backupAndRestore() {
        int prevUid = mInjectedCallingUid;

        mInjectedCallingUid = Process.SYSTEM_UID; // Only system can call it.

        dumpsysOnLogcat("Before backup");

        final byte[] payload =  mService.getBackupPayload(USER_0);
        if (ENABLE_DUMP) {
            final String xml = new String(payload);
            Log.i(TAG, "Backup payload:");
            for (String line : xml.split("\n")) {
                Log.i(TAG, line);
            }
        }

        // Before doing anything else, uninstall all packages.
        for (int userId : list(USER_0, USER_P0)) {
            for (String pkg : list(CALLING_PACKAGE_1, CALLING_PACKAGE_2, CALLING_PACKAGE_3,
                    LAUNCHER_1, LAUNCHER_2, LAUNCHER_3)) {
                uninstallPackage(userId, pkg);
            }
        }

        shutdownServices();

        deleteAllSavedFiles();

        initService();
        mService.applyRestore(payload, USER_0);

        // handleUnlockUser will perform the gone package check, but it shouldn't remove
        // shadow information.
        mService.handleUnlockUser(USER_0);

        dumpsysOnLogcat("After restore");

        mInjectedCallingUid = prevUid;
    }

    private void prepareCrossProfileDataSet() {
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list()));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("x1"), makeShortcut("x2"), makeShortcut("x3"),
                    makeShortcut("x4"), makeShortcut("x5"), makeShortcut("x6"))));
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s1", "s2"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3, list("s1", "s2", "s3"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s1", "s4"), HANDLE_USER_P0);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s2", "s3"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3, list("s2", "s3", "s4"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s2", "s5"), HANDLE_USER_P0);
        });
        runWithCaller(LAUNCHER_3, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s3", "s4"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3, list("s3", "s4", "s5"), HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3", "s6"), HANDLE_USER_P0);
        });
        runWithCaller(LAUNCHER_4, USER_0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list(), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list(), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3, list(), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_4, list(), HANDLE_USER_0);
        });

        // Launcher on a managed profile is referring ot user 0!
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s3", "s4"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("s3", "s4", "s5"), HANDLE_USER_0);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3, list("s3", "s4", "s5", "s6"),
                    HANDLE_USER_0);

            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("s4", "s1"), HANDLE_USER_P0);
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("x4", "x5"), HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_2, list("x4", "x5", "x6"), HANDLE_USER_10);
            mLauncherApps.pinShortcuts(CALLING_PACKAGE_3, list("x4", "x5", "x6", "x1"),
                    HANDLE_USER_10);
        });

        // Then remove some dynamic shortcuts.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list()));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"))));
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("x1"), makeShortcut("x2"), makeShortcut("x3"))));
        });
    }

    private void prepareForBackupTest() {

        prepareCrossProfileDataSet();

        backupAndRestore();
    }

    private void assertExistsAndShadow(ShortcutPackageItem spi) {
        assertNotNull(spi);
        assertTrue(spi.getPackageInfo().isShadow());
    }

    /**
     * Make sure the backup data doesn't have the following information:
     * - Launchers on other users.
     * - Non-backup app information.
     *
     * But restores all other infomation.
     *
     * It also omits the following pieces of information, but that's tested in
     * {@link #testShortcutInfoSaveAndLoad_forBackup}.
     * - Unpinned dynamic shortcuts
     * - Bitmaps
     */
    public void testBackupAndRestore() {
        prepareForBackupTest();

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_backupRestoreTwice() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        dumpsysOnLogcat("Before second backup");

        backupAndRestore();

        dumpsysOnLogcat("After second backup");

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_backupRestoreMultiple() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        // This also shouldn't affect the result.
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertTrue(mManager.setDynamicShortcuts(list(
                    makeShortcut("s1"), makeShortcut("s2"), makeShortcut("s3"),
                    makeShortcut("s4"), makeShortcut("s5"), makeShortcut("s6"))));
        });

        backupAndRestore();

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_restoreToNewVersion() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 2);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 5);

        checkBackupAndRestore_success();
    }

    public void testBackupAndRestore_restoreToSuperSetSignatures() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        // Change package signatures.
        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 1, "sigx", CALLING_PACKAGE_1);
        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 4, LAUNCHER_1, "sigy");

        checkBackupAndRestore_success();
    }

    private void checkBackupAndRestore_success() {
        // Make sure non-system user is not restored.
        final ShortcutUser userP0 = mService.getUserShortcutsLocked(USER_P0);
        assertEquals(0, userP0.getAllPackagesForTest().size());
        assertEquals(0, userP0.getAllLaunchersForTest().size());

        // Make sure only "allowBackup" apps are restored, and are shadow.
        final ShortcutUser user0 = mService.getUserShortcutsLocked(USER_0);
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_1));
        assertExistsAndShadow(user0.getAllPackagesForTest().get(CALLING_PACKAGE_2));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_1)));
        assertExistsAndShadow(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_2)));

        assertNull(user0.getAllPackagesForTest().get(CALLING_PACKAGE_3));
        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_0, LAUNCHER_3)));
        assertNull(user0.getAllLaunchersForTest().get(PackageWithUser.of(USER_P0, LAUNCHER_1)));

        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty, not restored */ );
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty, not restored */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty, not restored */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });

        // 3 shouldn't be backed up, so no pinned shortcuts.
        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        // Launcher on a different profile shouldn't be restored.
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)
                    .size());
            assertEquals(0,
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)
                            .size());
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* wasn't restored, so still empty */ );
        });

        // Package on a different profile, no restore.
        installPackage(USER_P0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        // Restore launcher 2 on user 0.
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* wasn't restored, so still empty */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });


        // Restoration of launcher2 shouldn't affect other packages; so do the same checks and
        // make sure they still have the same result.
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s1");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* wasn't restored, so still empty */ );

            assertEquals(0, mLauncherApps.getShortcuts(QUERY_ALL, HANDLE_USER_P0).size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });
    }

    public void testBackupAndRestore_publisherLowerVersion() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 0); // Lower version

        checkBackupAndRestore_publisherNotRestored();
    }

    public void testBackupAndRestore_publisherWrongSignature() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(CALLING_PACKAGE_1, CALLING_UID_1, 10, "sigx"); // different signature

        checkBackupAndRestore_publisherNotRestored();
    }

    public void testBackupAndRestore_publisherNoLongerBackupTarget() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        updatePackageInfo(CALLING_PACKAGE_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_publisherNotRestored();
    }

    private void checkBackupAndRestore_publisherNotRestored() {
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s1", "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
    }

    public void testBackupAndRestore_launcherLowerVersion() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 0); // Lower version

        checkBackupAndRestore_launcherNotRestored();
    }

    public void testBackupAndRestore_launcherWrongSignature() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        addPackage(LAUNCHER_1, LAUNCHER_UID_1, 10, "sigx"); // different signature

        checkBackupAndRestore_launcherNotRestored();
    }

    public void testBackupAndRestore_launcherNoLongerBackupTarget() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        updatePackageInfo(LAUNCHER_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_launcherNotRestored();
    }

    private void checkBackupAndRestore_launcherNotRestored() {
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());

            // s1 was pinned by launcher 1, which is not restored, yet, so we still see "s1" here.
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2");
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        // Now we try to restore launcher 1.  Then we realize it's not restorable, so L1 has no pinned
        // shortcuts.
        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());

            // Now CALLING_PACKAGE_1 realizes "s1" is no longer pinned.
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2");
        });

        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0)),
                    "s2");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
    }

    public void testBackupAndRestore_launcherAndPackageNoLongerBackupTarget() {
        prepareForBackupTest();

        // Note doing a backup & restore again here shouldn't affect the result.
        backupAndRestore();

        updatePackageInfo(CALLING_PACKAGE_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        updatePackageInfo(LAUNCHER_1,
                pi -> pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_ALLOW_BACKUP);

        checkBackupAndRestore_publisherAndLauncherNotRestored();
    }

    private void checkBackupAndRestore_publisherAndLauncherNotRestored() {
        installPackage(USER_0, CALLING_PACKAGE_1);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        installPackage(USER_0, CALLING_PACKAGE_2);
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3");
        });

        installPackage(USER_0, LAUNCHER_1);
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        installPackage(USER_0, LAUNCHER_2);
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });

        // Because launcher 1 wasn't restored, "s1" is no longer pinned.
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertShortcutIds(assertAllPinned(
                    mManager.getPinnedShortcuts()),
                    "s2", "s3");
        });

        installPackage(USER_0, CALLING_PACKAGE_3);
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(0, mManager.getDynamicShortcuts().size());
            assertEquals(0, mManager.getPinnedShortcuts().size());
        });

        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0))
                    /* empty */);
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_2), HANDLE_USER_0)),
                    "s2", "s3");
            assertShortcutIds(assertAllPinned(
                    mLauncherApps.getShortcuts(buildAllQuery(CALLING_PACKAGE_3), HANDLE_USER_0))
                    /* empty */);
        });
    }

    public void testSaveAndLoad_crossProfile() {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4", "s5");
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4", "s5", "s6");
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts())
                    /* empty */);
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts())
                    /* empty */);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "s1", "s2", "s3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "s1", "s2", "s3", "s4", "s5", "s6");
        });
        runWithCaller(CALLING_PACKAGE_2, USER_P0, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts())
                    /* empty */);
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts())
                    /* empty */);
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertShortcutIds(assertAllDynamic(mManager.getDynamicShortcuts()),
                    "x1", "x2", "x3");
            assertShortcutIds(assertAllPinned(mManager.getPinnedShortcuts()),
                    "x4", "x5");
        });
        runWithCaller(LAUNCHER_1, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s1");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s1", "s2");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s1", "s2", "s3");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s1", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
            assertExpectException(
                    SecurityException.class, "", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_10);
                    });
        });
        runWithCaller(LAUNCHER_2, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s2");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s2", "s3");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s2", "s3", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s2", "s5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
        });
        runWithCaller(LAUNCHER_3, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s3");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s3", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s3", "s4", "s5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s3", "s6");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
        });
        runWithCaller(LAUNCHER_4, USER_0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_4), HANDLE_USER_0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_P0)
                    /* empty */);
        });
        runWithCaller(LAUNCHER_1, USER_P0, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_0),
                    "s3", "s4");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_0),
                    "s3", "s4", "s5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_0),
                    "s3", "s4", "s5", "s6");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_P0),
                    "s1", "s4");
            assertExpectException(
                    SecurityException.class, "unrelated profile", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_10);
                    });
        });
        runWithCaller(LAUNCHER_1, USER_10, () -> {
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_1), HANDLE_USER_10),
                    "x4", "x5");
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_2), HANDLE_USER_10)
                    /* empty */);
            assertShortcutIds(
                    mLauncherApps.getShortcuts(buildPinnedQuery(CALLING_PACKAGE_3), HANDLE_USER_10)
                    /* empty */);
            assertExpectException(
                    SecurityException.class, "unrelated profile", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_0);
                    });
            assertExpectException(
                    SecurityException.class, "unrelated profile", () -> {
                        mLauncherApps.getShortcuts(
                                buildAllQuery(CALLING_PACKAGE_1), HANDLE_USER_P0);
                    });
        });
    }

    public void testThrottling_localeChanges() {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        final long origSequenceNumber = mService.getLocaleChangeSequenceNumber();

        mInternal.onSystemLocaleChangedNoLock();

        assertEquals(origSequenceNumber + 1, mService.getLocaleChangeSequenceNumber());

        // Note at this point only user-0 is loaded, and the counters are reset for this user,
        // but it will work for other users too, because we persist when

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });

        mService.saveDirtyInfo();
        initService();

        // Make sure the counter is persisted.
        assertEquals(origSequenceNumber + 1, mService.getLocaleChangeSequenceNumber());
    }

    public void testThrottling_foreground() throws Exception {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        // We need to update the current time from time to time, since some of the internal checks
        // rely on the time being correctly incremented.
        mInjectedCurrentTimeLillis++;

        // First, all packages have less than 3 (== initial value) remaining calls.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeLillis++;

        // State changed, but not foreground, so no resetting.
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_1, ActivityManager.PROCESS_STATE_TOP_SLEEPING);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeLillis++;

        // State changed, package1 foreground, reset.
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_1, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_1, ActivityManager.PROCESS_STATE_TOP_SLEEPING);

        mInjectedCurrentTimeLillis++;

        // Different app comes to foreground briefly, and goes back to background.
        // Now, make sure package 2's counter is reset, even in this case.
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_TOP_SLEEPING);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeLillis++;

        // Do the same thing one more time.  This would catch the bug with mixuing up
        // the current time and the elapsed time.
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            mManager.updateShortcuts(list(makeShortcut("s")));
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        mService.mUidObserver.onUidStateChanged(
                CALLING_UID_2, ActivityManager.PROCESS_STATE_TOP_SLEEPING);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mInjectedCurrentTimeLillis++;

        // Package 1 on user-10 comes to foreground.
        // Now, also try calling some APIs and make sure foreground apps don't get throttled.
        mService.mUidObserver.onUidStateChanged(
                UserHandle.getUid(USER_10, CALLING_UID_1),
                ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE);
        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(0, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());

            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));
            mManager.setDynamicShortcuts(list(makeShortcut("s")));

            assertEquals(3, mManager.getRemainingCallCount()); // Still 3!
        });
    }


    public void testThrottling_resetByInternalCall() throws Exception {
        prepareCrossProfileDataSet();

        dumpsysOnLogcat("Before save & load");

        mService.saveDirtyInfo();
        initService();

        // First, all packages have less than 3 (== initial value) remaining calls.

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        // Simulate a call from sys UI.
        mCallerPermissions.add(permission.RESET_SHORTCUT_MANAGER_THROTTLING);
        mService.onApplicationActive(CALLING_PACKAGE_1, USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mService.onApplicationActive(CALLING_PACKAGE_3, USER_0);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });

        mService.onApplicationActive(CALLING_PACKAGE_1, USER_10);

        runWithCaller(CALLING_PACKAGE_1, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_2, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_3, USER_0, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_4, USER_0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_P0, () -> {
            MoreAsserts.assertNotEqual(3, mManager.getRemainingCallCount());
        });
        runWithCaller(CALLING_PACKAGE_1, USER_10, () -> {
            assertEquals(3, mManager.getRemainingCallCount());
        });
    }

    public void testOnApplicationActive_permission() {
        assertExpectException(SecurityException.class, "Missing permission", () ->
            mService.onApplicationActive(CALLING_PACKAGE_1, USER_0));

        // Has permission, now it should pass.
        mCallerPermissions.add(permission.RESET_SHORTCUT_MANAGER_THROTTLING);
        mService.onApplicationActive(CALLING_PACKAGE_1, USER_0);
    }

    // ShortcutInfo tests

    public void testShortcutInfoMissingMandatoryFields() {
        assertExpectException(
                IllegalArgumentException.class,
                "ID must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).build());
        assertExpectException(
                IllegalArgumentException.class,
                "title must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).setId("id").build()
                        .enforceMandatoryFields());
        assertExpectException(
                NullPointerException.class,
                "Intent must be provided",
                () -> new ShortcutInfo.Builder(getTestContext()).setId("id").setTitle("x").build()
                        .enforceMandatoryFields());
    }

    public void testShortcutInfoParcel() {
        setCaller(CALLING_PACKAGE_1, USER_10);
        ShortcutInfo si = parceled(new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setTitle("title")
                .setIntent(makeIntent("action", ShortcutActivity.class))
                .build());
        assertEquals(mClientContext.getPackageName(), si.getPackageName());
        assertEquals(USER_10, si.getUserId());
        assertEquals(HANDLE_USER_10, si.getUserHandle());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals("action", si.getIntent().getAction());

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);

        si = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setActivityComponent(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitle("title")
                .setText("text")
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setWeight(123)
                .setExtras(pb)
                .build();
        si.addFlags(ShortcutInfo.FLAG_PINNED);
        si.setBitmapPath("abc");
        si.setIconResourceId(456);

        si = parceled(si);

        assertEquals(getTestContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals(123, si.getIcon().getResId());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());
    }

    public void testShortcutInfoClone() {
        setCaller(CALLING_PACKAGE_1, USER_11);

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivityComponent(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitle("title")
                .setText("text")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setWeight(123)
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        ShortcutInfo si = sorig.clone(/* clone flags*/ 0);

        assertEquals(USER_11, si.getUserId());
        assertEquals(HANDLE_USER_11, si.getUserHandle());
        assertEquals(mClientContext.getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals(123, si.getIcon().getResId());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals("abc", si.getBitmapPath());
        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR);

        assertEquals(mClientContext.getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

        assertEquals(mClientContext.getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(new ComponentName("a", "b"), si.getActivityComponent());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(mClientContext.getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(null, si.getActivityComponent());
        assertEquals(null, si.getIcon());
        assertEquals(null, si.getTitle());
        assertEquals(null, si.getText());
        assertEquals(null, si.getCategories());
        assertEquals(null, si.getIntent());
        assertEquals(0, si.getWeight());
        assertEquals(null, si.getExtras());

        assertEquals(ShortcutInfo.FLAG_PINNED | ShortcutInfo.FLAG_KEY_FIELDS_ONLY, si.getFlags());
        assertEquals(null, si.getBitmapPath());

        assertEquals(456, si.getIconResourceId());
    }

    public void testShortcutInfoClone_minimum() {
        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setTitle("title")
                .setIntent(makeIntent("action", ShortcutActivity.class))
                .build();
        ShortcutInfo si = sorig.clone(/* clone flags*/ 0);

        assertEquals(getTestContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals("action", si.getIntent().getAction());
        assertEquals(null, si.getCategories());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_CREATOR);

        assertEquals(getTestContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals("action", si.getIntent().getAction());
        assertEquals(null, si.getCategories());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_FOR_LAUNCHER);

        assertEquals(getTestContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals("title", si.getTitle());
        assertEquals(null, si.getIntent());
        assertEquals(null, si.getCategories());

        si = sorig.clone(ShortcutInfo.CLONE_REMOVE_NON_KEY_INFO);

        assertEquals(getTestContext().getPackageName(), si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(null, si.getTitle());
        assertEquals(null, si.getIntent());
        assertEquals(null, si.getCategories());
    }

    public void testShortcutInfoCopyNonNullFieldsFrom() throws InterruptedException {
        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(getTestContext())
                .setId("id")
                .setActivityComponent(new ComponentName("a", "b"))
                .setIcon(Icon.createWithResource(mClientContext, 123))
                .setTitle("title")
                .setText("text")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setWeight(123)
                .setExtras(pb)
                .build();
        sorig.addFlags(ShortcutInfo.FLAG_PINNED);
        sorig.setBitmapPath("abc");
        sorig.setIconResourceId(456);

        ShortcutInfo si;

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setActivityComponent(new ComponentName("x", "y")).build());
        assertEquals("text", si.getText());
        assertEquals(new ComponentName("x", "y"), si.getActivityComponent());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIcon(Icon.createWithResource(mClientContext, 456)).build());
        assertEquals("text", si.getText());
        assertEquals(456, si.getIcon().getResId());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitle("xyz").build());
        assertEquals("text", si.getText());
        assertEquals("xyz", si.getTitle());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setText("xxx").build());
        assertEquals(123, si.getWeight());
        assertEquals("xxx", si.getText());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setCategories(set()).build());
        assertEquals("text", si.getText());
        assertEquals(set(), si.getCategories());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setCategories(set("x")).build());
        assertEquals("text", si.getText());
        assertEquals(set("x"), si.getCategories());

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIntent(makeIntent("action2", ShortcutActivity.class)).build());
        assertEquals("text", si.getText());
        assertEquals("action2", si.getIntent().getAction());
        assertEquals(null, si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setIntent(makeIntent("action3", ShortcutActivity.class, "key", "x")).build());
        assertEquals("text", si.getText());
        assertEquals("action3", si.getIntent().getAction());
        assertEquals("x", si.getIntent().getStringExtra("key"));

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setWeight(999).build());
        assertEquals("text", si.getText());
        assertEquals(999, si.getWeight());


        PersistableBundle pb2 = new PersistableBundle();
        pb2.putInt("x", 99);

        si = sorig.clone(/* flags=*/ 0);
        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setExtras(pb2).build());
        assertEquals("text", si.getText());
        assertEquals(99, si.getExtras().getInt("x"));

        // Make sure the timestamp gets updated too.

        final long timestamp = si.getLastChangedTimestamp();
        Thread.sleep(2);

        si.copyNonNullFieldsFrom(new ShortcutInfo.Builder(getTestContext()).setId("id")
                .setTitle("xyz").build());

        assertTrue(si.getLastChangedTimestamp() > timestamp);
    }

    public void testShortcutInfoSaveAndLoad() throws InterruptedException {
        setCaller(CALLING_PACKAGE_1, USER_10);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivityComponent(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(bmp32x32)
                .setTitle("title")
                .setText("text")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setWeight(123)
                .setExtras(pb)
                .build();

        mManager.addDynamicShortcuts(list(sorig));

        Thread.sleep(2);
        final long now = System.currentTimeMillis();

        // Save and load.
        mService.saveDirtyInfo();
        initService();
        mService.handleUnlockUser(USER_10);

        ShortcutInfo si;
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id", USER_10);

        assertEquals(USER_10, si.getUserId());
        assertEquals(HANDLE_USER_10, si.getUserHandle());
        assertEquals(CALLING_PACKAGE_1, si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(ShortcutActivity2.class.getName(), si.getActivityComponent().getClassName());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_DYNAMIC | ShortcutInfo.FLAG_HAS_ICON_FILE, si.getFlags());
        assertNotNull(si.getBitmapPath()); // Something should be set.
        assertEquals(0, si.getIconResourceId());
        assertTrue(si.getLastChangedTimestamp() < now);
    }

    public void testShortcutInfoSaveAndLoad_forBackup() {
        setCaller(CALLING_PACKAGE_1, USER_0);

        final Icon bmp32x32 = Icon.createWithBitmap(BitmapFactory.decodeResource(
                getTestContext().getResources(), R.drawable.black_32x32));

        PersistableBundle pb = new PersistableBundle();
        pb.putInt("k", 1);
        ShortcutInfo sorig = new ShortcutInfo.Builder(mClientContext)
                .setId("id")
                .setActivityComponent(new ComponentName(mClientContext, ShortcutActivity2.class))
                .setIcon(bmp32x32)
                .setTitle("title")
                .setText("text")
                .setCategories(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"))
                .setIntent(makeIntent("action", ShortcutActivity.class, "key", "val"))
                .setWeight(123)
                .setExtras(pb)
                .build();

        mManager.addDynamicShortcuts(list(sorig));

        // Dynamic shortcuts won't be backed up, so we need to pin it.
        setCaller(LAUNCHER_1, USER_0);
        mLauncherApps.pinShortcuts(CALLING_PACKAGE_1, list("id"), HANDLE_USER_0);

        // Do backup & restore.
        backupAndRestore();

        mService.handleUnlockUser(USER_0); // Load user-0.

        ShortcutInfo si;
        si = mService.getPackageShortcutForTest(CALLING_PACKAGE_1, "id", USER_0);

        assertEquals(CALLING_PACKAGE_1, si.getPackageName());
        assertEquals("id", si.getId());
        assertEquals(ShortcutActivity2.class.getName(), si.getActivityComponent().getClassName());
        assertEquals(null, si.getIcon());
        assertEquals("title", si.getTitle());
        assertEquals("text", si.getText());
        assertEquals(set(ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION, "xyz"), si.getCategories());
        assertEquals("action", si.getIntent().getAction());
        assertEquals("val", si.getIntent().getStringExtra("key"));
        assertEquals(123, si.getWeight());
        assertEquals(1, si.getExtras().getInt("k"));

        assertEquals(ShortcutInfo.FLAG_PINNED, si.getFlags());
        assertNull(si.getBitmapPath()); // No icon.
        assertEquals(0, si.getIconResourceId());
    }

    public void testDumpsys_crossProfile() {
        prepareCrossProfileDataSet();
        dumpsysOnLogcat("test1", /* force= */ true);
    }

    public void testDumpsys_withIcons() throws IOException {
        testIcons();
        // Dump after having some icons.
        dumpsysOnLogcat("test1", /* force= */ true);
    }
}
