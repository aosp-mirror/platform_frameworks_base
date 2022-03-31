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

import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.anyOrNull;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.anyStringOrNull;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.cloneShortcutList;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.hashSet;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.list;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.makeBundle;
import static com.android.server.pm.shortcutmanagertest.ShortcutManagerTestUtils.set;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IUidObserver;
import android.app.PendingIntent;
import android.app.Person;
import android.app.admin.DevicePolicyManager;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.usage.UsageStatsManagerInternal;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.LocusId;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ILauncherApps;
import android.content.pm.LauncherApps;
import android.content.pm.LauncherApps.ShortcutQuery;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.InstrumentationTestCase;
import android.test.mock.MockContext;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.infra.AndroidFuture;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.LauncherAppsService.LauncherAppsImpl;
import com.android.server.pm.ShortcutUser.PackageWithUser;
import com.android.server.uri.UriGrantsManagerInternal;
import com.android.server.uri.UriPermissionOwner;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.Assert;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class BaseShortcutManagerTest extends InstrumentationTestCase {
    protected static final String TAG = "ShortcutManagerTest";

    protected static final boolean DUMP_IN_TEARDOWN = false; // DO NOT SUBMIT WITH true

    /**
     * Whether to enable dump or not.  Should be only true when debugging to avoid bugs where
     * dump affecting the behavior.
     */
    protected static final boolean ENABLE_DUMP = false // DO NOT SUBMIT WITH true
            || DUMP_IN_TEARDOWN || ShortcutService.DEBUG;

    protected static final String[] EMPTY_STRINGS = new String[0]; // Just for readability.

    protected static final String MAIN_ACTIVITY_CLASS = "MainActivity";
    protected static final String PIN_CONFIRM_ACTIVITY_CLASS = "PinConfirmActivity";

    // public for mockito
    public class BaseContext extends MockContext {
        @Override
        public Object getSystemService(String name) {
            switch (name) {
                case Context.USER_SERVICE:
                    return mMockUserManager;
                case Context.DEVICE_POLICY_SERVICE:
                    return mMockDevicePolicyManager;
                case Context.APP_SEARCH_SERVICE:
                case Context.ROLE_SERVICE:
                    // RoleManager is final and cannot be mocked, so we only override the inject
                    // accessor methods in ShortcutService.
                    return getTestContext().getSystemService(name);
            }
            throw new UnsupportedOperationException("Couldn't find system service: " + name);
        }

        @Override
        public String getOpPackageName() {
            return getTestContext().getOpPackageName();
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
        public Context createContextAsUser(UserHandle user, int flags) {
            when(mMockPackageManager.getUserId()).thenReturn(user.getIdentifier());
            return this;
        }

        @Override
        public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
                throws PackageManager.NameNotFoundException {
            // ignore.
            return this;
        }

        @Override
        public Intent registerReceiverAsUser(BroadcastReceiver receiver, UserHandle user,
                IntentFilter filter, String broadcastPermission, Handler scheduler) {
            // ignore.
            return null;
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
            // ignore.
        }

        @Override
        public void startActivityAsUser(Intent intent, UserHandle user) {
            // ignore, use spy to intercept it.
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
        public Context createContextAsUser(UserHandle user, int flags) {
            super.createContextAsUser(user, flags);
            final ServiceContext ctx = spy(new ServiceContext());
            when(ctx.getUser()).thenReturn(user);
            when(ctx.getUserId()).thenReturn(user.getIdentifier());
            return ctx;
        }

        @Override
        public int getUserId() {
            return UserHandle.USER_SYSTEM;
        }

        public PackageInfo injectGetActivitiesWithMetadata(
                String packageName, @UserIdInt int userId) {
            return BaseShortcutManagerTest.this.injectGetActivitiesWithMetadata(packageName, userId);
        }

        public XmlResourceParser injectXmlMetaData(ActivityInfo activityInfo, String key) {
            return BaseShortcutManagerTest.this.injectXmlMetaData(activityInfo, key);
        }

        public void sendIntentSender(IntentSender intent) {
            // Placeholder for spying.
        }

        @Override
        public String getPackageName() {
            return SYSTEM_PACKAGE_NAME;
        }
    }

    /** ShortcutService with injection override methods. */
    protected final class ShortcutServiceTestable extends ShortcutService {
        final ServiceContext mContext;
        IUidObserver mUidObserver;

        public ShortcutServiceTestable(ServiceContext context, Looper looper) {
            super(context, looper, /* onyForPackageManagerApis */ false);
            mContext = context;
        }

        @Override
        public String injectGetLocaleTagsForUser(@UserIdInt int userId) {
            return mInjectedLocale.toLanguageTag();
        }

        @Override
        boolean injectShouldPerformVerification() {
            return true; // Always verify during unit tests.
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
            return mInjectedCurrentTimeMillis;
        }

        @Override
        long injectElapsedRealtime() {
            // TODO This should be kept separately from mInjectedCurrentTimeMillis, since
            // this should increase even if we rewind mInjectedCurrentTimeMillis in some tests.
            return mInjectedCurrentTimeMillis - START_TIME;
        }

        @Override
        long injectUptimeMillis() {
            return mInjectedCurrentTimeMillis - START_TIME - mDeepSleepTime;
        }

        @Override
        int injectBinderCallingUid() {
            return mInjectedCallingUid;
        }

        @Override
        int injectBinderCallingPid() {
            // Note it's not used in tests, so just return a "random" value.
            return mInjectedCallingUid * 123;
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
        boolean hasShortcutHostPermission(@NonNull String callingPackage, int userId,
                int callingPid, int callingUid) {
            return mDefaultLauncherChecker.test(callingPackage, userId);
        }

        @Override
        boolean injectHasUnlimitedShortcutsApiCallsPermission(int callingPid, int callingUid) {
            return mInjectHasUnlimitedShortcutsApiCallsPermission;
        }

        @Override
        void injectRegisterRoleHoldersListener(OnRoleHoldersChangedListener listener) {
            // Do nothing.
        }

        @Override
        String injectGetHomeRoleHolderAsUser(@UserIdInt int userId) {
            final String packageName = mHomeRoleHolderAsUser.get(userId);
            if (packageName != null) {
                return packageName;
            }
            return super.injectGetHomeRoleHolderAsUser(userId);
        }

        @Override
        String getDefaultLauncher(@UserIdInt int userId) {
            final String packageName = mDefaultLauncher.get(userId);
            if (packageName != null) {
                return packageName;
            }
            return super.getDefaultLauncher(userId);
        }

        @Override
        PackageInfo injectPackageInfoWithUninstalled(String packageName, @UserIdInt int userId,
                boolean getSignatures) {
            return getInjectedPackageInfo(packageName, userId, getSignatures);
        }

        @Override
        ApplicationInfo injectApplicationInfoWithUninstalled(
                String packageName, @UserIdInt int userId) {
            PackageInfo pi = injectPackageInfoWithUninstalled(
                    packageName, userId, /* getSignatures= */ false);
            return pi != null ? pi.applicationInfo : null;
        }

        @Override
        List<PackageInfo> injectGetPackagesWithUninstalled(@UserIdInt int userId) {
            return BaseShortcutManagerTest.this.getInstalledPackagesWithUninstalled(userId);
        }

        @Override
        ActivityInfo injectGetActivityInfoWithMetadataWithUninstalled(ComponentName activity,
                @UserIdInt int userId) {
            final PackageInfo pi = mContext.injectGetActivitiesWithMetadata(
                    activity.getPackageName(), userId);
            if (pi == null || pi.activities == null) {
                return null;
            }
            for (ActivityInfo ai : pi.activities) {
                if (!mEnabledActivityChecker.test(ai.getComponentName(), userId)) {
                    continue;
                }
                if (activity.equals(ai.getComponentName())) {
                    return ai;
                }
            }
            return null;
        }

        @Override
        boolean injectIsMainActivity(@NonNull ComponentName activity, int userId) {
            if (!mEnabledActivityChecker.test(activity, userId)) {
                return false;
            }
            return mMainActivityChecker.test(activity, userId);
        }

        @Override
        List<ResolveInfo> injectGetMainActivities(@NonNull String packageName, int userId) {
            final PackageInfo pi = mContext.injectGetActivitiesWithMetadata(
                    packageName, userId);
            if (pi == null || pi.activities == null) {
                return null;
            }
            final ArrayList<ResolveInfo> ret = new ArrayList<>(pi.activities.length);
            for (int i = 0; i < pi.activities.length; i++) {
                if (!mEnabledActivityChecker.test(pi.activities[i].getComponentName(), userId)) {
                    continue;
                }
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = pi.activities[i];
                ret.add(ri);
            }

            return ret;
        }

        @Override
        ComponentName injectGetDefaultMainActivity(@NonNull String packageName, int userId) {
            return mMainActivityFetcher.apply(packageName, userId);
        }

        @Override
        ComponentName injectGetPinConfirmationActivity(@NonNull String launcherPackageName,
                int launcherUserId, int requestType) {
            return mPinConfirmActivityFetcher.apply(launcherPackageName, launcherUserId);
        }

        @Override
        boolean injectIsActivityEnabledAndExported(ComponentName activity, @UserIdInt int userId) {
            return mEnabledActivityChecker.test(activity, userId);
        }

        @Override
        XmlResourceParser injectXmlMetaData(ActivityInfo activityInfo, String key) {
            return mContext.injectXmlMetaData(activityInfo, key);
        }

        @Override
        void injectPostToHandler(Runnable r) {
            runOnHandler(r);
        }

        @Override
        void injectRunOnNewThread(Runnable r) {
            runOnHandler(r);
        }

        @Override
        void injectEnforceCallingPermission(String permission, String message) {
            if (!mCallerPermissions.contains(permission)) {
                throw new SecurityException("Missing permission: " + permission);
            }
        }

        @Override
        boolean injectIsSafeModeEnabled() {
            return mSafeMode;
        }

        @Override
        String injectBuildFingerprint() {
            return mInjectedBuildFingerprint;
        }

        @Override
        void injectSendIntentSender(IntentSender intent, Intent extras) {
            mContext.sendIntentSender(intent);
        }

        @Override
        boolean injectHasAccessShortcutsPermission(int callingPid, int callingUid) {
            return mInjectCheckAccessShortcutsPermission;
        }

        @Override
        void wtf(String message, Throwable th) {
            // During tests, WTF is fatal.
            fail(message + "  exception: " + th + "\n" + Log.getStackTraceString(th));
        }
    }

    /** ShortcutManager with injection override methods. */
    protected class ShortcutManagerTestable extends ShortcutManager {
        public ShortcutManagerTestable(Context context, ShortcutServiceTestable service) {
            super(context, service);
        }

        @Override
        protected int injectMyUserId() {
            return UserHandle.getUserId(mInjectedCallingUid);
        }

        @Override
        public boolean setDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
            // Note to simulate the binder RPC, we need to clone the incoming arguments.
            // Otherwise bad things will happen because they're mutable.
            return super.setDynamicShortcuts(cloneShortcutList(shortcutInfoList));
        }

        @Override
        public boolean addDynamicShortcuts(@NonNull List<ShortcutInfo> shortcutInfoList) {
            // Note to simulate the binder RPC, we need to clone the incoming arguments.
            return super.addDynamicShortcuts(cloneShortcutList(shortcutInfoList));
        }

        @Override
        public boolean updateShortcuts(List<ShortcutInfo> shortcutInfoList) {
            // Note to simulate the binder RPC, we need to clone the incoming arguments.
            return super.updateShortcuts(cloneShortcutList(shortcutInfoList));
        }
    }

    protected class LauncherAppImplTestable extends LauncherAppsImpl {
        final ServiceContext mContext;

        public LauncherAppImplTestable(ServiceContext context) {
            super(context);
            mContext = context;
        }

        @Override
        public void verifyCallingPackage(String callingPackage, int callerUid) {
            // SKIP
        }

        @Override
        void postToPackageMonitorHandler(Runnable r) {
            runOnHandler(r);
        }

        @Override
        int injectBinderCallingUid() {
            return mInjectedCallingUid;
        }

        @Override
        int injectBinderCallingPid() {
            // Note it's not used in tests, so just return a "random" value.
            return mInjectedCallingUid * 123;
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

        @Override
        boolean injectHasAccessShortcutsPermission(int callingPid, int callingUid) {
            return mInjectCheckAccessShortcutsPermission;
        }

        @Override
        boolean injectHasInteractAcrossUsersFullPermission(int callingPid, int callingUid) {
            return false;
        }

        @Override
        PendingIntent injectCreatePendingIntent(int requestCode, @NonNull Intent[] intents,
                int flags, Bundle options, String ownerPackage, int ownerUserId) {
            return new PendingIntent(mock(IIntentSender.class));
        }
    }

    protected class LauncherAppsTestable extends LauncherApps {
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

    protected Looper mLooper;
    protected Handler mHandler;

    protected ServiceContext mServiceContext;
    protected ClientContext mClientContext;

    protected ShortcutServiceTestable mService;
    protected ShortcutManagerTestable mManager;
    protected ShortcutServiceInternal mInternal;

    protected LauncherAppImplTestable mLauncherAppImpl;

    // LauncherApps has per-instace state, so we need a differnt instance for each launcher.
    protected final Map<Pair<Integer, String>, LauncherAppsTestable>
            mLauncherAppsMap = new HashMap<>();
    protected LauncherAppsTestable mLauncherApps; // Current one

    protected File mInjectedFilePathRoot;

    protected boolean mSafeMode;

    protected long mInjectedCurrentTimeMillis;
    protected long mDeepSleepTime; // Used to calculate "uptimeMillis".

    protected boolean mInjectedIsLowRamDevice;

    protected Locale mInjectedLocale = Locale.ENGLISH;

    protected int mInjectedCallingUid;
    protected String mInjectedClientPackage;

    protected Map<String, PackageInfo> mInjectedPackages;

    protected Set<PackageWithUser> mUninstalledPackages;
    protected Set<PackageWithUser> mDisabledPackages;
    protected Set<PackageWithUser> mEphemeralPackages;
    protected Set<String> mSystemPackages;

    protected PackageManager mMockPackageManager;
    protected PackageManagerInternal mMockPackageManagerInternal;
    protected UserManager mMockUserManager;
    protected DevicePolicyManager mMockDevicePolicyManager;
    protected UserManagerInternal mMockUserManagerInternal;
    protected UsageStatsManagerInternal mMockUsageStatsManagerInternal;
    protected ActivityManagerInternal mMockActivityManagerInternal;
    protected ActivityTaskManagerInternal mMockActivityTaskManagerInternal;
    protected UriGrantsManagerInternal mMockUriGrantsManagerInternal;

    protected UriPermissionOwner mUriPermissionOwner;

    protected static final String SYSTEM_PACKAGE_NAME = "android";

    protected static final String CALLING_PACKAGE_1 = "com.android.test.1";
    protected static final int CALLING_UID_1 = 10001;

    protected static final String CALLING_PACKAGE_2 = "com.android.test.2";
    protected static final int CALLING_UID_2 = 10002;

    protected static final String CALLING_PACKAGE_3 = "com.android.test.3";
    protected static final int CALLING_UID_3 = 10003;

    protected static final String CALLING_PACKAGE_4 = "com.android.test.4";
    protected static final int CALLING_UID_4 = 10004;

    protected static final String LAUNCHER_1 = "com.android.launcher.1";
    protected static final int LAUNCHER_UID_1 = 10011;

    protected static final String LAUNCHER_2 = "com.android.launcher.2";
    protected static final int LAUNCHER_UID_2 = 10012;

    protected static final String LAUNCHER_3 = "com.android.launcher.3";
    protected static final int LAUNCHER_UID_3 = 10013;

    protected static final String LAUNCHER_4 = "com.android.launcher.4";
    protected static final int LAUNCHER_UID_4 = 10014;

    protected static final int USER_0 = UserHandle.USER_SYSTEM;
    protected static final int USER_10 = 10;
    protected static final int USER_11 = 11;
    protected static final int USER_P0 = 20; // profile of user 0 (MANAGED_PROFILE *not* set)
    protected static final int USER_P1 = 21; // another profile of user 0 (MANAGED_PROFILE set)

    protected static final UserHandle HANDLE_USER_0 = UserHandle.of(USER_0);
    protected static final UserHandle HANDLE_USER_10 = UserHandle.of(USER_10);
    protected static final UserHandle HANDLE_USER_11 = UserHandle.of(USER_11);
    protected static final UserHandle HANDLE_USER_P0 = UserHandle.of(USER_P0);
    protected static final UserHandle HANDLE_USER_P1 = UserHandle.of(USER_P1);

    protected static final UserInfo USER_INFO_0 = withProfileGroupId(
            new UserInfo(USER_0, "user0",
                    UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY | UserInfo.FLAG_INITIALIZED), 0);

    protected static final UserInfo USER_INFO_10 =
            new UserInfo(USER_10, "user10", UserInfo.FLAG_INITIALIZED);

    protected static final UserInfo USER_INFO_11 =
            new UserInfo(USER_11, "user11", UserInfo.FLAG_INITIALIZED);

    /*
     * Cheat: USER_P0 is a sub profile of USER_0, but it doesn't have the MANAGED_PROFILE flag set.
     * Due to a change made to LauncherApps (b/34340531), work profile apps a no longer able
     * to see the main profile, which would break tons of unit tests.  We avoid it by not setting
     * MANAGED_PROFILE for P0.
     * We cover this negative case in CTS. (i.e. CTS has tests to make sure maanged profile
     * can't access main profile's shortcuts.)
     */
    protected static final UserInfo USER_INFO_P0 = withProfileGroupId(
            new UserInfo(USER_P0, "userP0", UserInfo.FLAG_INITIALIZED), 0);

    protected static final UserInfo USER_INFO_P1 = withProfileGroupId(
            new UserInfo(USER_P1, "userP1",
                    UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_MANAGED_PROFILE), 0);

    protected BiPredicate<String, Integer> mDefaultLauncherChecker =
            (callingPackage, userId) ->
            LAUNCHER_1.equals(callingPackage) || LAUNCHER_2.equals(callingPackage)
            || LAUNCHER_3.equals(callingPackage) || LAUNCHER_4.equals(callingPackage);

    private final Map<Integer, String> mDefaultLauncher = new ArrayMap<>();

    protected BiPredicate<ComponentName, Integer> mMainActivityChecker =
            (activity, userId) -> true;

    protected BiFunction<String, Integer, ComponentName> mMainActivityFetcher =
            (packageName, userId) -> new ComponentName(packageName, MAIN_ACTIVITY_CLASS);

    protected BiFunction<String, Integer, ComponentName> mPinConfirmActivityFetcher =
            (packageName, userId) -> new ComponentName(packageName, PIN_CONFIRM_ACTIVITY_CLASS);

    protected BiPredicate<ComponentName, Integer> mEnabledActivityChecker
            = (activity, userId) -> true; // all activities are enabled.

    protected static final long START_TIME = 1440000000101L;

    protected static final long INTERVAL = 10000;

    // This doesn't need to match the max shortcuts limit in the framework, and tests should either
    // use this or set their own limit for testing, without assuming any particular max value.
    protected static final int MAX_SHORTCUTS = 10;

    protected static final int MAX_UPDATES_PER_INTERVAL = 3;

    protected static final int MAX_ICON_DIMENSION = 128;

    protected static final int MAX_ICON_DIMENSION_LOWRAM = 32;

    protected static final ShortcutQuery QUERY_ALL = new ShortcutQuery();

    protected final ArrayList<String> mCallerPermissions = new ArrayList<>();

    protected final HashMap<String, LinkedHashMap<ComponentName, Integer>> mActivityMetadataResId
            = new HashMap<>();

    protected final Map<Integer, UserInfo> mUserInfos = new HashMap<>();
    protected final Map<Integer, Boolean> mRunningUsers = new HashMap<>();
    protected final Map<Integer, Boolean> mUnlockedUsers = new HashMap<>();

    protected static final String PACKAGE_SYSTEM_LAUNCHER = "com.android.systemlauncher";
    protected static final String PACKAGE_SYSTEM_LAUNCHER_NAME = "systemlauncher_name";
    protected static final int PACKAGE_SYSTEM_LAUNCHER_PRIORITY = 0;

    protected static final String PACKAGE_FALLBACK_LAUNCHER = "com.android.settings";
    protected static final String PACKAGE_FALLBACK_LAUNCHER_NAME = "fallback";
    protected static final int PACKAGE_FALLBACK_LAUNCHER_PRIORITY = -999;

    protected String mInjectedBuildFingerprint = "build1";

    protected boolean mInjectCheckAccessShortcutsPermission = false;

    protected boolean mInjectHasUnlimitedShortcutsApiCallsPermission = false;

    private final Map<Integer, String> mHomeRoleHolderAsUser = new ArrayMap<>();

    static {
        QUERY_ALL.setQueryFlags(
                ShortcutQuery.FLAG_GET_ALL_KINDS);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mLooper = Looper.getMainLooper();
        mHandler = new Handler(mLooper);

        mServiceContext = spy(new ServiceContext());
        mClientContext = new ClientContext();

        mMockPackageManager = mock(PackageManager.class);
        mMockPackageManagerInternal = mock(PackageManagerInternal.class);
        mMockUserManager = mock(UserManager.class);
        mMockDevicePolicyManager = mock(DevicePolicyManager.class);
        mMockUserManagerInternal = mock(UserManagerInternal.class);
        mMockUsageStatsManagerInternal = mock(UsageStatsManagerInternal.class);
        mMockActivityManagerInternal = mock(ActivityManagerInternal.class);
        mMockActivityTaskManagerInternal = mock(ActivityTaskManagerInternal.class);
        mMockUriGrantsManagerInternal = mock(UriGrantsManagerInternal.class);

        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mMockPackageManagerInternal);
        LocalServices.removeServiceForTest(UsageStatsManagerInternal.class);
        LocalServices.addService(UsageStatsManagerInternal.class, mMockUsageStatsManagerInternal);
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
        LocalServices.addService(ActivityManagerInternal.class, mMockActivityManagerInternal);
        LocalServices.removeServiceForTest(ActivityTaskManagerInternal.class);
        LocalServices.addService(ActivityTaskManagerInternal.class, mMockActivityTaskManagerInternal);
        LocalServices.removeServiceForTest(UserManagerInternal.class);
        LocalServices.addService(UserManagerInternal.class, mMockUserManagerInternal);
        LocalServices.removeServiceForTest(UriGrantsManagerInternal.class);
        LocalServices.addService(UriGrantsManagerInternal.class, mMockUriGrantsManagerInternal);

        mUriPermissionOwner = new UriPermissionOwner(mMockUriGrantsManagerInternal, TAG);

        // Prepare injection values.

        mInjectedCurrentTimeMillis = START_TIME;

        mInjectedPackages = new HashMap<>();
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
        mDisabledPackages = new HashSet<>();
        mSystemPackages = new HashSet<>();
        mEphemeralPackages = new HashSet<>();

        mInjectedFilePathRoot = new File(getTestContext().getCacheDir(), "test-files");

        deleteAllSavedFiles();

        // Set up users.
        mUserInfos.put(USER_0, USER_INFO_0);
        mUserInfos.put(USER_10, USER_INFO_10);
        mUserInfos.put(USER_11, USER_INFO_11);
        mUserInfos.put(USER_P0, USER_INFO_P0);
        mUserInfos.put(USER_P1, USER_INFO_P1);

        when(mMockUserManagerInternal.isUserUnlockingOrUnlocked(anyInt()))
                .thenAnswer(inv -> {
                    final int userId = (Integer) inv.getArguments()[0];
                    return b(mRunningUsers.get(userId)) && b(mUnlockedUsers.get(userId));
        });
        when(mMockUserManagerInternal.getProfileParentId(anyInt()))
                .thenAnswer(inv -> {
                    final int userId = (Integer) inv.getArguments()[0];
                    final UserInfo ui = mUserInfos.get(userId);
                    assertNotNull(ui);
                    if (ui.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                        return userId;
                    }
                    final UserInfo parent = mUserInfos.get(ui.profileGroupId);
                    assertNotNull(parent);
                    return parent.id;
                });

        when(mMockUserManagerInternal.isProfileAccessible(anyInt(), anyInt(), anyString(),
                anyBoolean())).thenAnswer(inv -> {
                    final int callingUserId = (Integer) inv.getArguments()[0];
                    final int targetUserId = (Integer) inv.getArguments()[1];
                    if (targetUserId == callingUserId) {
                        return true;
                    }
                    final UserInfo callingUserInfo = mUserInfos.get(callingUserId);
                    final UserInfo targetUserInfo = mUserInfos.get(targetUserId);
                    if (callingUserInfo == null || callingUserInfo.isManagedProfile()
                            || targetUserInfo == null || !targetUserInfo.isEnabled()) {
                        return false;
                    }
                    if (targetUserInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                            && targetUserInfo.profileGroupId == callingUserInfo.profileGroupId) {
                        return true;
                    }
                    final boolean isExternal = (Boolean) inv.getArguments()[3];
                    if (!isExternal) {
                        return false;
                    }
                    throw new SecurityException(inv.getArguments()[2] + " for unrelated profile "
                            + targetUserId);
                });

        when(mMockUserManager.getUserInfo(anyInt())).thenAnswer(new AnswerWithSystemCheck<>(
                inv -> mUserInfos.get((Integer) inv.getArguments()[0])));
        when(mMockActivityManagerInternal.getUidProcessState(anyInt())).thenReturn(
                ActivityManager.PROCESS_STATE_CACHED_EMPTY);

        // User 0 and P0 are always running
        mRunningUsers.put(USER_0, true);
        mRunningUsers.put(USER_10, false);
        mRunningUsers.put(USER_11, false);
        mRunningUsers.put(USER_P0, true);
        mRunningUsers.put(USER_P1, true);

        // Unlock all users by default.
        mUnlockedUsers.put(USER_0, true);
        mUnlockedUsers.put(USER_10, true);
        mUnlockedUsers.put(USER_11, true);
        mUnlockedUsers.put(USER_P0, true);
        mUnlockedUsers.put(USER_P1, true);

        // Set up resources
        setUpAppResources();

        // Start the service.
        initService();
        setCaller(CALLING_PACKAGE_1);

        if (ENABLE_DUMP) {
            Log.d(TAG, "setUp done");
        }
    }

    /**
     * Returns a boolean but also checks if the current UID is SYSTEM_UID.
     */
    protected class AnswerWithSystemCheck<T> implements Answer<T> {
        private final Function<InvocationOnMock, T> mChecker;

        public AnswerWithSystemCheck(Function<InvocationOnMock, T> checker) {
            mChecker = checker;
        }

        @Override
        public T answer(InvocationOnMock invocation) throws Throwable {
            assertEquals("Must be called on SYSTEM UID.",
                    Process.SYSTEM_UID, mInjectedCallingUid);
            return mChecker.apply(invocation);
        }
    }

    private static boolean b(Boolean value) {
        return (value != null && value);
    }

    protected void setUpAppResources() throws Exception {
        setUpAppResources(/* offset = */ 0);
    }

    protected void setUpAppResources(int ressIdOffset) throws Exception {
        // ressIdOffset is used to adjust resource IDs to emulate the case where an updated app
        // has resource IDs changed.

        doAnswer(pmInvocation -> {
            assertEquals(Process.SYSTEM_UID, mInjectedCallingUid);

            final String packageName = (String) pmInvocation.getArguments()[0];
            final int userId =  mMockPackageManager.getUserId();

            final Resources res = mock(Resources.class);

            doAnswer(resInvocation -> {
                final int argResId = (Integer) resInvocation.getArguments()[0];

                return "string-" + packageName + "-user:" + userId + "-res:" + argResId
                        + "/" + mInjectedLocale;
            }).when(res).getString(anyInt());

            doAnswer(resInvocation -> {
                final int resId = (Integer) resInvocation.getArguments()[0];

                // Always use the "string" resource type.  The type doesn't matter during the test.
                return packageName + ":string/r" + resId;
            }).when(res).getResourceName(anyInt());

            doAnswer(resInvocation -> {
                final String argResName = (String) resInvocation.getArguments()[0];
                final String argType = (String) resInvocation.getArguments()[1];
                final String argPackageName = (String) resInvocation.getArguments()[2];

                // See the above code.  getResourceName() will just use "r" + res ID as the entry
                // name.
                String entryName = argResName;
                if (entryName.contains("/")) {
                    entryName = ShortcutInfo.getResourceEntryName(entryName);
                }
                return Integer.parseInt(entryName.substring(1)) + ressIdOffset;
            }).when(res).getIdentifier(anyStringOrNull(), anyStringOrNull(), anyStringOrNull());
            return res;
        }).when(mMockPackageManager).getResourcesForApplication(anyString());
    }

    protected static UserInfo withProfileGroupId(UserInfo in, int groupId) {
        in.profileGroupId = groupId;
        return in;
    }

    @Override
    protected void tearDown() throws Exception {
        if (DUMP_IN_TEARDOWN) dumpsysOnLogcat("Teardown");

        shutdownServices();

        super.tearDown();
    }

    protected Context getTestContext() {
        return getInstrumentation().getContext();
    }

    protected Context getClientContext() {
        return mClientContext;
    }

    protected ShortcutManager getManager() {
        return mManager;
    }

    protected void deleteAllSavedFiles() {
        // Empty the data directory.
        if (mInjectedFilePathRoot.exists()) {
            Assert.assertTrue("failed to delete dir",
                    FileUtils.deleteContents(mInjectedFilePathRoot));
        }
        mInjectedFilePathRoot.mkdirs();
    }

    /** (Re-) init the manager and the service. */
    protected void initService() {
        shutdownServices();

        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        // Instantiate targets.
        mService = new ShortcutServiceTestable(mServiceContext, mLooper);
        mManager = new ShortcutManagerTestable(mClientContext, mService);

        mInternal = LocalServices.getService(ShortcutServiceInternal.class);

        mLauncherAppImpl = new LauncherAppImplTestable(mServiceContext);
        mLauncherApps = null;
        mLauncherAppsMap.clear();

        // Send boot sequence events.
        mService.onBootPhase(SystemService.PHASE_LOCK_SETTINGS_READY);

        mService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    protected void shutdownServices() {
        if (mService != null) {
            // Flush all the unsaved data from the previous instance.
            mService.saveDirtyInfo();

            // Make sure everything is consistent.
            mService.verifyStates();
        }
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);

        mService = null;
        mManager = null;
        mInternal = null;
        mLauncherAppImpl = null;
        mLauncherApps = null;
        mLauncherAppsMap.clear();
    }

    protected void runOnHandler(Runnable r) {
        final long token = mServiceContext.injectClearCallingIdentity();
        try {
            r.run();
        } finally {
            mServiceContext.injectRestoreCallingIdentity(token);
        }
    }

    protected void addPackage(String packageName, int uid, int version) {
        addPackage(packageName, uid, version, packageName);
    }

    protected Signature[] genSignatures(String... signatures) {
        final Signature[] sigs = new Signature[signatures.length];
        for (int i = 0; i < signatures.length; i++){
            sigs[i] = new Signature(signatures[i].getBytes());
        }
        return sigs;
    }

    protected PackageInfo genPackage(String packageName, int uid, int version, String... signatures) {
        final PackageInfo pi = new PackageInfo();
        pi.packageName = packageName;
        pi.applicationInfo = new ApplicationInfo();
        pi.applicationInfo.uid = uid;
        pi.applicationInfo.flags = ApplicationInfo.FLAG_INSTALLED
                | ApplicationInfo.FLAG_ALLOW_BACKUP;
        pi.versionCode = version;
        pi.applicationInfo.setVersionCode(version);
        pi.signatures = null;
        pi.signingInfo = new SigningInfo(
                new SigningDetails(
                        genSignatures(signatures),
                        SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                        null,
                        null));
        return pi;
    }

    protected void addPackage(String packageName, int uid, int version, String... signatures) {
        mInjectedPackages.put(packageName, genPackage(packageName, uid, version, signatures));
    }

    protected void updatePackageInfo(String packageName, Consumer<PackageInfo> c) {
        c.accept(mInjectedPackages.get(packageName));
    }

    protected void updatePackageVersion(String packageName, int increment) {
        updatePackageInfo(packageName, pi -> {
            pi.versionCode += increment;
            pi.applicationInfo.setVersionCode(pi.applicationInfo.longVersionCode + increment);
        });
    }

    protected void updatePackageLastUpdateTime(String packageName, long increment) {
        updatePackageInfo(packageName, pi -> {
            pi.lastUpdateTime += increment;
        });
    }

    protected void setPackageLastUpdateTime(String packageName, long value) {
        updatePackageInfo(packageName, pi -> {
            pi.lastUpdateTime = value;
        });
    }

    protected void uninstallPackage(int userId, String packageName) {
        if (ENABLE_DUMP) {
            Log.v(TAG, "Uninstall package " + packageName + " / " + userId);
        }
        mUninstalledPackages.add(PackageWithUser.of(userId, packageName));
    }

    protected void installPackage(int userId, String packageName) {
        if (ENABLE_DUMP) {
            Log.v(TAG, "Install package " + packageName + " / " + userId);
        }
        mUninstalledPackages.remove(PackageWithUser.of(userId, packageName));
    }

    protected void disablePackage(int userId, String packageName) {
        if (ENABLE_DUMP) {
            Log.v(TAG, "Disable package " + packageName + " / " + userId);
        }
        mDisabledPackages.add(PackageWithUser.of(userId, packageName));
    }

    protected void enablePackage(int userId, String packageName) {
        if (ENABLE_DUMP) {
            Log.v(TAG, "Enable package " + packageName + " / " + userId);
        }
        mDisabledPackages.remove(PackageWithUser.of(userId, packageName));
    }

    PackageInfo getInjectedPackageInfo(String packageName, @UserIdInt int userId,
            boolean getSignatures) {
        final PackageInfo pi = mInjectedPackages.get(packageName);
        if (pi == null) return null;

        final PackageInfo ret = new PackageInfo();
        ret.packageName = pi.packageName;
        ret.versionCode = pi.versionCode;
        ret.versionCodeMajor = pi.versionCodeMajor;
        ret.lastUpdateTime = pi.lastUpdateTime;

        ret.applicationInfo = new ApplicationInfo(pi.applicationInfo);
        ret.applicationInfo.uid = UserHandle.getUid(userId, pi.applicationInfo.uid);
        ret.applicationInfo.packageName = pi.packageName;

        if (mUninstalledPackages.contains(PackageWithUser.of(userId, packageName))) {
            ret.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }
        if (mEphemeralPackages.contains(PackageWithUser.of(userId, packageName))) {
            ret.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_INSTANT;
        }
        if (mSystemPackages.contains(packageName)) {
            ret.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        ret.applicationInfo.enabled =
                !mDisabledPackages.contains(PackageWithUser.of(userId, packageName));

        if (getSignatures) {
            ret.signatures = null;
            ret.signingInfo = pi.signingInfo;
        }

        return ret;
    }

    protected void addApplicationInfo(PackageInfo pi, List<ApplicationInfo> list) {
        if (pi != null && pi.applicationInfo != null) {
            list.add(pi.applicationInfo);
        }
    }

    protected List<ApplicationInfo> getInstalledApplications(int userId) {
        final ArrayList<ApplicationInfo> ret = new ArrayList<>();

        addApplicationInfo(getInjectedPackageInfo(CALLING_PACKAGE_1, userId, false), ret);
        addApplicationInfo(getInjectedPackageInfo(CALLING_PACKAGE_2, userId, false), ret);
        addApplicationInfo(getInjectedPackageInfo(CALLING_PACKAGE_3, userId, false), ret);
        addApplicationInfo(getInjectedPackageInfo(CALLING_PACKAGE_4, userId, false), ret);
        addApplicationInfo(getInjectedPackageInfo(LAUNCHER_1, userId, false), ret);
        addApplicationInfo(getInjectedPackageInfo(LAUNCHER_2, userId, false), ret);
        addApplicationInfo(getInjectedPackageInfo(LAUNCHER_3, userId, false), ret);
        addApplicationInfo(getInjectedPackageInfo(LAUNCHER_4, userId, false), ret);

        return ret;
    }

    private void addPackageInfo(PackageInfo pi, List<PackageInfo> list) {
        if (pi != null) {
            list.add(pi);
        }
    }

    private List<PackageInfo> getInstalledPackagesWithUninstalled(int userId) {
        final ArrayList<PackageInfo> ret = new ArrayList<>();

        addPackageInfo(getInjectedPackageInfo(CALLING_PACKAGE_1, userId, false), ret);
        addPackageInfo(getInjectedPackageInfo(CALLING_PACKAGE_2, userId, false), ret);
        addPackageInfo(getInjectedPackageInfo(CALLING_PACKAGE_3, userId, false), ret);
        addPackageInfo(getInjectedPackageInfo(CALLING_PACKAGE_4, userId, false), ret);
        addPackageInfo(getInjectedPackageInfo(LAUNCHER_1, userId, false), ret);
        addPackageInfo(getInjectedPackageInfo(LAUNCHER_2, userId, false), ret);
        addPackageInfo(getInjectedPackageInfo(LAUNCHER_3, userId, false), ret);
        addPackageInfo(getInjectedPackageInfo(LAUNCHER_4, userId, false), ret);

        return ret;
    }

    protected void addManifestShortcutResource(ComponentName activity, int resId) {
        final String packageName = activity.getPackageName();
        LinkedHashMap<ComponentName, Integer> map = mActivityMetadataResId.get(packageName);
        if (map == null) {
            map = new LinkedHashMap<>();
            mActivityMetadataResId.put(packageName, map);
        }
        map.put(activity, resId);
    }

    protected PackageInfo injectGetActivitiesWithMetadata(String packageName, @UserIdInt int userId) {
        final PackageInfo ret = getInjectedPackageInfo(packageName, userId,
                /* getSignatures=*/ false);

        final HashMap<ComponentName, Integer> activities = mActivityMetadataResId.get(packageName);
        if (activities != null) {
            final ArrayList<ActivityInfo> list = new ArrayList<>();

            for (ComponentName cn : activities.keySet()) {
                ActivityInfo ai = new ActivityInfo();
                ai.packageName = cn.getPackageName();
                ai.name = cn.getClassName();
                ai.metaData = new Bundle();
                ai.metaData.putInt(ShortcutParser.METADATA_KEY, activities.get(cn));
                ai.applicationInfo = ret.applicationInfo;
                list.add(ai);
            }
            ret.activities = list.toArray(new ActivityInfo[list.size()]);
        }
        return ret;
    }

    protected XmlResourceParser injectXmlMetaData(ActivityInfo activityInfo, String key) {
        if (!ShortcutParser.METADATA_KEY.equals(key) || activityInfo.metaData == null) {
            return null;
        }
        final int resId = activityInfo.metaData.getInt(key);
        return getTestContext().getResources().getXml(resId);
    }

    /** Replace the current calling package */
    protected void setCaller(String packageName, int userId) {
        mInjectedClientPackage = packageName;
        mInjectedCallingUid =
                Objects.requireNonNull(getInjectedPackageInfo(packageName, userId, false),
                        "Unknown package").applicationInfo.uid;

        // Set up LauncherApps for this caller.
        final Pair<Integer, String> key = Pair.create(userId, packageName);
        if (!mLauncherAppsMap.containsKey(key)) {
            mLauncherAppsMap.put(key, new LauncherAppsTestable(mClientContext, mLauncherAppImpl));
        }
        mLauncherApps = mLauncherAppsMap.get(key);
    }

    protected void setCaller(String packageName) {
        setCaller(packageName, UserHandle.USER_SYSTEM);
    }

    protected String getCallingPackage() {
        return mInjectedClientPackage;
    }

    /**
     * This controls {@link ShortcutService#hasShortcutHostPermission}, but
     * not {@link ShortcutService#getDefaultLauncher(int)}.  To control the later, use
     * {@link #setDefaultLauncher(int, String)}.
     */
    protected void setDefaultLauncherChecker(BiPredicate<String, Integer> p) {
        mDefaultLauncherChecker = p;
    }

    /**
     * Set the default launcher.  This will update {@link #mDefaultLauncherChecker} set by
     * {@link #setDefaultLauncherChecker} too.
     */
    protected void setDefaultLauncher(int userId, String launcherPackage) {
        mDefaultLauncher.put(userId, launcherPackage);

        final BiPredicate<String, Integer> oldChecker = mDefaultLauncherChecker;
        mDefaultLauncherChecker = (checkPackageName, checkUserId) -> {
            if ((checkUserId == userId) && (launcherPackage !=  null)) {
                return launcherPackage.equals(checkPackageName);
            }
            return oldChecker.test(checkPackageName, checkUserId);
        };
    }

    protected void runWithCaller(String packageName, int userId, Runnable r) {
        final String previousPackage = mInjectedClientPackage;
        final int previousUserId = UserHandle.getUserId(mInjectedCallingUid);

        setCaller(packageName, userId);

        r.run();

        setCaller(previousPackage, previousUserId);
    }

    protected void runWithSystemUid(Runnable r) {
        final int origUid = mInjectedCallingUid;
        mInjectedCallingUid = Process.SYSTEM_UID;
        r.run();
        mInjectedCallingUid = origUid;
    }

    protected void lookupAndFillInResourceNames(ShortcutInfo si) {
        runWithSystemUid(() -> si.lookupAndFillInResourceNames(
                mService.injectGetResourcesForApplicationAsUser(si.getPackage(), si.getUserId())));
    }

    protected int getCallingUserId() {
        return UserHandle.getUserId(mInjectedCallingUid);
    }

    protected UserHandle getCallingUser() {
        return UserHandle.of(getCallingUserId());
    }

    /** For debugging */
    protected void dumpsysOnLogcat() {
        dumpsysOnLogcat("");
    }

    protected void dumpsysOnLogcat(String message) {
        dumpsysOnLogcat(message, false);
    }

    protected void dumpsysOnLogcat(String message, boolean force) {
        if (force || !ENABLE_DUMP) return;

        Log.v(TAG, "Dumping ShortcutService: " + message);
        for (String line : dumpsys("-u").split("\n")) {
            Log.v(TAG, line);
        }
    }

    protected String dumpCheckin() {
        return dumpsys("--checkin");
    }

    protected String dumpsys(String... args) {
        final ArrayList<String> origPermissions = new ArrayList<>(mCallerPermissions);
        mCallerPermissions.add(android.Manifest.permission.DUMP);
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final PrintWriter pw = new PrintWriter(out);
            mService.dumpNoCheck(/* fd */ null, pw, args);
            pw.close();

            return out.toString();
        } finally {
            mCallerPermissions.clear();
            mCallerPermissions.addAll(origPermissions);
        }
    }

    /**
     * For debugging, dump arbitrary file on logcat.
     */
    protected void dumpFileOnLogcat(String path) {
        dumpFileOnLogcat(path, "");
    }

    protected void dumpFileOnLogcat(String path, String message) {
        if (!ENABLE_DUMP) return;

        Log.v(TAG, "Dumping file: " + path + " " + message);
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                Log.v(TAG, line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Couldn't read file", e);
            fail("Exception " + e);
        }
    }

    /**
     * For debugging, dump the main state file on logcat.
     */
    protected void dumpBaseStateFile() {
        mService.saveDirtyInfo();
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/system/" + ShortcutService.FILENAME_BASE_STATE);
    }

    /**
     * For debugging, dump per-user state file on logcat.
     */
    protected void dumpUserFile(int userId) {
        dumpUserFile(userId, "");
    }

    protected void dumpUserFile(int userId, String message) {
        mService.saveDirtyInfo();
        dumpFileOnLogcat(mInjectedFilePathRoot.getAbsolutePath()
                + "/user-" + userId
                + "/" + ShortcutService.FILENAME_USER_PACKAGES, message);
    }

    /**
     * Make a shortcut with an ID only.
     */
    protected ShortcutInfo makeShortcutIdOnly(String id) {
        return new ShortcutInfo.Builder(mClientContext, id).build();
    }

    /**
     * Make a shortcut with an ID.
     */
    protected ShortcutInfo makeShortcut(String id) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
    }

    /**
     * Make a hidden shortcut with an ID.
     */
    protected ShortcutInfo makeShortcutExcludedFromLauncher(String id) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, id)
                .setActivity(new ComponentName(mClientContext.getPackageName(), "main"))
                .setShortLabel("Title-" + id)
                .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class))
                .setExcludedFromSurfaces(ShortcutInfo.SURFACE_LAUNCHER);
        final ShortcutInfo s = b.build();
        s.setTimestamp(mInjectedCurrentTimeMillis);
        return s;
    }

    @Deprecated // Title was renamed to short label.
    protected ShortcutInfo makeShortcutWithTitle(String id, String title) {
        return makeShortcut(
                id, title, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
    }

    protected ShortcutInfo makeShortcutWithShortLabel(String id, String shortLabel) {
        return makeShortcut(
                id, shortLabel, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
    }

    /**
     * Make a shortcut with an ID and timestamp.
     */
    protected ShortcutInfo makeShortcutWithTimestamp(String id, long timestamp) {
        final ShortcutInfo s = makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
        s.setTimestamp(timestamp);
        return s;
    }

    /**
     * Make a shortcut with an ID, a timestamp and an activity component
     */
    protected ShortcutInfo makeShortcutWithTimestampWithActivity(String id, long timestamp,
            ComponentName activity) {
        final ShortcutInfo s = makeShortcut(
                id, "Title-" + id, activity, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
        s.setTimestamp(timestamp);
        return s;
    }

    /**
     * Make a shortcut with an ID and icon.
     */
    protected ShortcutInfo makeShortcutWithIcon(String id, Icon icon) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, icon,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
    }

    protected ShortcutInfo makePackageShortcut(String packageName, String id) {
        String origCaller = getCallingPackage();

        setCaller(packageName);
        ShortcutInfo s = makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
        setCaller(origCaller); // restore the caller

        return s;
    }

    /**
     * Make multiple shortcuts with IDs.
     */
    protected List<ShortcutInfo> makeShortcuts(String... ids) {
        final ArrayList<ShortcutInfo> ret = new ArrayList();
        for (String id : ids) {
            ret.add(makeShortcut(id));
        }
        return ret;
    }

    protected ShortcutInfo.Builder makeShortcutBuilder() {
        return new ShortcutInfo.Builder(mClientContext);
    }

    protected ShortcutInfo makeShortcutWithActivity(String id, ComponentName activity) {
        return makeShortcut(
                id, "Title-" + id, activity, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
    }

    protected ShortcutInfo makeShortcutWithIntent(String id, Intent intent) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                intent, /* rank =*/ 0);
    }

    protected ShortcutInfo makeShortcutWithActivityAndTitle(String id, ComponentName activity,
            String title) {
        return makeShortcut(
                id, title, activity, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), /* rank =*/ 0);
    }

    protected ShortcutInfo makeShortcutWithActivityAndRank(String id, ComponentName activity,
            int rank) {
        return makeShortcut(
                id, "Title-" + id, activity, /* icon =*/ null,
                makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class), rank);
    }

    /**
     * Make a shortcut with details.
     */
    protected ShortcutInfo makeShortcut(String id, String title, ComponentName activity,
            Icon icon, Intent intent, int rank) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, id)
                .setActivity(new ComponentName(mClientContext.getPackageName(), "main"))
                .setShortLabel(title)
                .setRank(rank)
                .setIntent(intent);
        if (icon != null) {
            b.setIcon(icon);
        }
        if (activity != null) {
            b.setActivity(activity);
        }
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeMillis); // HACK

        return s;
    }

    protected ShortcutInfo makeShortcutWithIntents(String id, Intent... intents) {
        return makeShortcut(
                id, "Title-" + id, /* activity =*/ null, /* icon =*/ null,
                intents, /* rank =*/ 0);
    }

    /**
     * Make a shortcut with details.
     */
    protected ShortcutInfo makeShortcut(String id, String title, ComponentName activity,
            Icon icon, Intent[] intents, int rank) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, id)
                .setActivity(new ComponentName(mClientContext.getPackageName(), "main"))
                .setShortLabel(title)
                .setRank(rank)
                .setIntents(intents);
        if (icon != null) {
            b.setIcon(icon);
        }
        if (activity != null) {
            b.setActivity(activity);
        }
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeMillis); // HACK

        return s;
    }

    /**
     * Make a shortcut with details.
     */
    protected ShortcutInfo makeShortcutWithExtras(String id, Intent intent,
            PersistableBundle extras) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, id)
                .setActivity(new ComponentName(mClientContext.getPackageName(), "main"))
                .setShortLabel("title-" + id)
                .setExtras(extras)
                .setIntent(intent);
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeMillis); // HACK

        return s;
    }

    /**
     * Make a shortcut with an ID and Category.
     */
    protected ShortcutInfo makeShortcutWithCategory(String id, Set<String> categories) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, id)
                .setActivity(new ComponentName(mClientContext.getPackageName(), "main"))
                .setShortLabel("title-" + id)
                .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class))
                .setCategories(categories);
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeMillis); // HACK

        return s;
    }

    /**
     * Make a shortcut with an ID and a locus ID.
     */
    protected ShortcutInfo makeShortcutWithLocusId(String id, LocusId locusId) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, id)
                .setActivity(new ComponentName(mClientContext.getPackageName(), "main"))
                .setShortLabel("title-" + id)
                .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class))
                .setLocusId(locusId);
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeMillis); // HACK

        return s;
    }

    /**
     * Make a long lived shortcut with an ID.
     */
    protected ShortcutInfo makeLongLivedShortcut(String id) {
        final ShortcutInfo.Builder  b = new ShortcutInfo.Builder(mClientContext, id)
                .setActivity(new ComponentName(mClientContext.getPackageName(), "main"))
                .setShortLabel("title-" + id)
                .setIntent(makeIntent(Intent.ACTION_VIEW, ShortcutActivity.class))
                .setLongLived(true);
        final ShortcutInfo s = b.build();

        s.setTimestamp(mInjectedCurrentTimeMillis); // HACK

        return s;
    }

    /**
     * Make an intent.
     */
    protected Intent makeIntent(String action, Class<?> clazz, Object... bundleKeysAndValues) {
        final Intent intent = new Intent(action);
        intent.setComponent(makeComponent(clazz));
        intent.replaceExtras(makeBundle(bundleKeysAndValues));
        return intent;
    }

    /**
     * Make a Person.
     */
    protected Person makePerson(CharSequence name, String key, String uri) {
        final Person.Builder builder = new Person.Builder();
        return builder.setName(name).setKey(key).setUri(uri).build();
    }

    /**
     * Make a LocusId.
     */
    protected LocusId makeLocusId(String id) {
        return new LocusId(id);
    }

    /**
     * Make an component name, with the client context.
     */
    @NonNull
    protected ComponentName makeComponent(Class<?> clazz) {
        return new ComponentName(mClientContext, clazz);
    }

    @NonNull
    protected ShortcutInfo findById(List<ShortcutInfo> list, String id) {
        for (ShortcutInfo s : list) {
            if (s.getId().equals(id)) {
                return s;
            }
        }
        fail("Shortcut with id " + id + " not found");
        return null;
    }

    protected void assertSystem() {
        assertEquals("Caller must be system", Process.SYSTEM_UID, mInjectedCallingUid);
    }

    protected void assertResetTimes(long expectedLastResetTime, long expectedNextResetTime) {
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
    protected List<ShortcutInfo> assertAllHaveFlags(@NonNull List<ShortcutInfo> actualShortcuts,
            int shortcutFlags) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId() + " doesn't have flags " + shortcutFlags,
                    s.hasFlags(shortcutFlags));
        }
        return actualShortcuts;
    }

    protected ShortcutInfo getPackageShortcut(String packageName, String shortcutId, int userId) {
        return mService.getPackageShortcutForTest(packageName, shortcutId, userId);
    }

    protected void updatePackageShortcut(String packageName, String shortcutId, int userId,
            Consumer<ShortcutInfo> cb) {
        mService.updatePackageShortcutForTest(packageName, shortcutId, userId, cb);
    }

    protected void assertShortcutExists(String packageName, String shortcutId, int userId) {
        assertTrue(getPackageShortcut(packageName, shortcutId, userId) != null);
    }

    protected void assertShortcutNotExists(String packageName, String shortcutId, int userId) {
        assertTrue(getPackageShortcut(packageName, shortcutId, userId) == null);
    }

    protected Intent[] launchShortcutAndGetIntentsInner(Runnable shortcutStarter,
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        reset(mMockActivityTaskManagerInternal);
        shortcutStarter.run();

        final ArgumentCaptor<Intent[]> intentsCaptor = ArgumentCaptor.forClass(Intent[].class);
        verify(mMockActivityTaskManagerInternal).startActivitiesAsPackage(
                eq(packageName),
                isNull(),
                eq(userId),
                intentsCaptor.capture(),
                anyOrNull(Bundle.class));
        return intentsCaptor.getValue();
    }

    protected Intent[] launchShortcutAndGetIntents(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        return launchShortcutAndGetIntentsInner(
                () -> {
                    mLauncherApps.startShortcut(packageName, shortcutId, null, null,
                            UserHandle.of(userId));
                }, packageName, shortcutId, userId
        );
    }

    protected Intent launchShortcutAndGetIntent(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        final Intent[] intents = launchShortcutAndGetIntents(packageName, shortcutId, userId);
        assertEquals(1, intents.length);
        return intents[0];
    }

    protected Intent[] launchShortcutAndGetIntents_withShortcutInfo(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        return launchShortcutAndGetIntentsInner(
                () -> {
                    mLauncherApps.startShortcut(
                            getShortcutInfoAsLauncher(packageName, shortcutId, userId), null, null);
                }, packageName, shortcutId, userId
        );
    }

    protected Intent launchShortcutAndGetIntent_withShortcutInfo(
            @NonNull String packageName, @NonNull String shortcutId, int userId) {
        final Intent[] intents = launchShortcutAndGetIntents_withShortcutInfo(
                packageName, shortcutId, userId);
        assertEquals(1, intents.length);
        return intents[0];
    }

    protected void assertShortcutLaunchable(@NonNull String packageName, @NonNull String shortcutId,
            int userId) {
        assertNotNull(launchShortcutAndGetIntent(packageName, shortcutId, userId));
    }

    protected void assertShortcutNotLaunched(@NonNull String packageName,
            @NonNull String shortcutId, int userId) {
        reset(mMockActivityTaskManagerInternal);
        try {
            mLauncherApps.startShortcut(packageName, shortcutId, null, null,
                    UserHandle.of(userId));
            fail("ActivityNotFoundException was not thrown");
        } catch (ActivityNotFoundException expected) {
        }
        // This shouldn't have been called.
        verify(mMockActivityTaskManagerInternal, times(0)).startActivitiesAsPackage(
                anyString(),
                isNull(),
                anyInt(),
                any(Intent[].class),
                anyOrNull(Bundle.class));
    }

    protected void assertStartShortcutThrowsException(@NonNull String packageName,
            @NonNull String shortcutId, int userId, Class<?> expectedException) {
        Exception thrown = null;
        try {
            mLauncherApps.startShortcut(packageName, shortcutId, null, null,
                    UserHandle.of(userId));
        } catch (Exception e) {
            thrown = e;
        }
        assertNotNull("Exception was not thrown", thrown);
        assertEquals("Exception type different", expectedException, thrown.getClass());
    }

    protected void assertThrown(@NonNull final Class<?> expectedException,
            @NonNull final Runnable fn) {
        Exception thrown = null;
        try {
            fn.run();
        } catch (Exception e) {
            thrown = e;
        }
        assertNotNull("Exception was not thrown", thrown);
        assertEquals("Exception type different", expectedException, thrown.getClass());
    }

    protected void assertBitmapDirectories(int userId, String... expectedDirectories) {
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

    protected void assertBitmapFiles(int userId, String packageName, String... expectedFiles) {
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

    protected String getBitmapFilename(int userId, String packageName, String shortcutId) {
        final ShortcutInfo si = mService.getPackageShortcutForTest(packageName, shortcutId, userId);
        if (si == null) {
            return null;
        }
        mService.waitForBitmapSavesForTest();
        return new File(si.getBitmapPath()).getName();
    }

    protected String getBitmapAbsPath(int userId, String packageName, String shortcutId) {
        final ShortcutInfo si = mService.getPackageShortcutForTest(packageName, shortcutId, userId);
        if (si == null) {
            return null;
        }
        mService.waitForBitmapSavesForTest();
        return new File(si.getBitmapPath()).getAbsolutePath();
    }

    /**
     * @return all shortcuts stored internally for the caller.  This reflects the *internal* view
     * of shortcuts, which may be different from what {@link #getCallerVisibleShortcuts} would
     * return, because getCallerVisibleShortcuts() will get shortcuts from the proper "front door"
     * which performs some extra checks, like {@link ShortcutPackage#onRestored}.
     */
    protected List<ShortcutInfo> getCallerShortcuts() {
        final ShortcutPackage p = mService.getPackageShortcutForTest(
                getCallingPackage(), getCallingUserId());
        return p == null ? null : p.getAllShortcutsForTest();
    }

    /**
     * @return all share targets stored internally for the caller.
     */
    protected List<ShareTargetInfo> getCallerShareTargets() {
        final ShortcutPackage p = mService.getPackageShortcutForTest(
                getCallingPackage(), getCallingUserId());
        return p == null ? null : p.getAllShareTargetsForTest();
    }

    protected void resetPersistedShortcuts() {
        final ShortcutPackage p = mService.getPackageShortcutForTest(
                getCallingPackage(), getCallingUserId());
        p.removeAllShortcutsAsync();
    }

    protected void getPersistedShortcut(AndroidFuture<List<ShortcutInfo>> cb) {
        final ShortcutPackage p = mService.getPackageShortcutForTest(
                getCallingPackage(), getCallingUserId());
        p.getTopShortcutsFromPersistence(cb);
    }

    /**
     * @return the number of shortcuts stored internally for the caller that can be used as a share
     * target in the ShareSheet. Such shortcuts have a matching category with at least one of the
     * defined ShareTargets from the app's Xml resource.
     */
    protected int getCallerSharingShortcutCount() {
        final ShortcutPackage p = mService.getPackageShortcutForTest(
                getCallingPackage(), getCallingUserId());
        return p == null ? 0 : p.getSharingShortcutCount();
    }

    /**
     * @return all shortcuts owned by caller that are actually visible via ShortcutManager.
     * See also {@link #getCallerShortcuts}.
     */
    protected List<ShortcutInfo> getCallerVisibleShortcuts() {
        final ArrayList<ShortcutInfo> ret = new ArrayList<>();
        ret.addAll(mManager.getDynamicShortcuts());
        ret.addAll(mManager.getPinnedShortcuts());
        ret.addAll(mManager.getManifestShortcuts());
        return ret;
    }

    protected ShortcutInfo getCallerShortcut(String shortcutId) {
        return getPackageShortcut(getCallingPackage(), shortcutId, getCallingUserId());
    }

    protected void updateCallerShortcut(String shortcutId, Consumer<ShortcutInfo> cb) {
        updatePackageShortcut(getCallingPackage(), shortcutId, getCallingUserId(), cb);
    }

    protected List<ShortcutInfo> getLauncherShortcuts(String launcher, int userId, int queryFlags) {
        final List<ShortcutInfo>[] ret = new List[1];
        runWithCaller(launcher, userId, () -> {
            final ShortcutQuery q = new ShortcutQuery();
            q.setQueryFlags(queryFlags);
            ret[0] = mLauncherApps.getShortcuts(q, UserHandle.of(userId));
        });
        return ret[0];
    }

    protected List<ShortcutInfo> getLauncherPinnedShortcuts(String launcher, int userId) {
        return getLauncherShortcuts(launcher, userId, ShortcutQuery.FLAG_GET_PINNED);
    }

    protected List<ShortcutInfo> getShortcutAsLauncher(int targetUserId) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setQueryFlags(ShortcutQuery.FLAG_MATCH_DYNAMIC | ShortcutQuery.FLAG_MATCH_PINNED);
        return mLauncherApps.getShortcuts(q, UserHandle.of(targetUserId));
    }

    protected ShortcutInfo getShortcutInfoAsLauncher(String packageName, String shortcutId,
            int userId) {
        final List<ShortcutInfo> infoList =
                mLauncherApps.getShortcutInfo(packageName, list(shortcutId),
                        UserHandle.of(userId));
        assertEquals("No shortcutInfo found (or too many of them)", 1, infoList.size());
        return infoList.get(0);
    }

    protected Intent genPackageAddIntent(String packageName, int userId) {
        installPackage(userId, packageName);

        Intent i = new Intent(Intent.ACTION_PACKAGE_ADDED);
        i.setData(Uri.parse("package:" + packageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return i;
    }

    protected Intent genPackageDeleteIntent(String pakcageName, int userId) {
        uninstallPackage(userId, pakcageName);

        Intent i = new Intent(Intent.ACTION_PACKAGE_REMOVED);
        i.setData(Uri.parse("package:" + pakcageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return i;
    }

    protected Intent genPackageUpdateIntent(String pakcageName, int userId) {
        installPackage(userId, pakcageName);

        Intent i = new Intent(Intent.ACTION_PACKAGE_ADDED);
        i.setData(Uri.parse("package:" + pakcageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        i.putExtra(Intent.EXTRA_REPLACING, true);
        return i;
    }

    protected Intent genPackageChangedIntent(String pakcageName, int userId) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_CHANGED);
        i.setData(Uri.parse("package:" + pakcageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return i;
    }

    protected Intent genPackageDataClear(String packageName, int userId) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED);
        i.setData(Uri.parse("package:" + packageName));
        i.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        return i;
    }

    protected void assertExistsAndShadow(ShortcutPackageItem spi) {
        assertNotNull(spi);
        assertTrue(spi.getPackageInfo().isShadow());
    }

    protected File makeFile(File baseDirectory, String... paths) {
        File ret = baseDirectory;

        for (String path : paths) {
            ret = new File(ret, path);
        }

        return ret;
    }

    protected boolean bitmapDirectoryExists(String packageName, int userId) {
        mService.waitForBitmapSavesForTest();
        final File path = new File(mService.getUserBitmapFilePath(userId), packageName);
        return path.isDirectory();
    }
    protected static ShortcutQuery buildQuery(long changedSince,
            String packageName, ComponentName componentName,
            /* @ShortcutQuery.QueryFlags */ int flags) {
        return buildQuery(changedSince, packageName, null, null, componentName, flags);
    }

    protected static ShortcutQuery buildQuery(long changedSince,
            String packageName, List<String> shortcutIds, List<LocusId> locusIds,
            ComponentName componentName, /* @ShortcutQuery.QueryFlags */ int flags) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setChangedSince(changedSince);
        q.setPackage(packageName);
        q.setShortcutIds(shortcutIds);
        q.setLocusIds(locusIds);
        q.setActivity(componentName);
        q.setQueryFlags(flags);
        return q;
    }

    protected static ShortcutQuery buildAllQuery(String packageName) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setQueryFlags(ShortcutQuery.FLAG_GET_ALL_KINDS);
        return q;
    }

    protected static ShortcutQuery buildPinnedQuery(String packageName) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setPackage(packageName);
        q.setQueryFlags(ShortcutQuery.FLAG_GET_PINNED);
        return q;
    }

    protected static ShortcutQuery buildQueryWithFlags(int queryFlags) {
        final ShortcutQuery q = new ShortcutQuery();
        q.setQueryFlags(queryFlags);
        return q;
    }

    protected void backupAndRestore() {
        int prevUid = mInjectedCallingUid;

        mInjectedCallingUid = Process.SYSTEM_UID; // Only system can call it.

        dumpsysOnLogcat("Before backup");

        final byte[] payload =  mService.getBackupPayload(USER_0);
        if (ENABLE_DUMP) {
            final String xml = new String(payload);
            Log.v(TAG, "Backup payload:");
            for (String line : xml.split("\n")) {
                Log.v(TAG, line);
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

    protected void prepareCrossProfileDataSet() {
        mRunningUsers.put(USER_10, true); // this test needs user 10.

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

        // Note LAUNCHER_3 has allowBackup=false.
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

    public static List<ShortcutInfo> assertAllHaveIconResId(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId() + " not have icon res ID", s.hasIconResource());
            assertFalse("ID " + s.getId() + " shouldn't have icon FD", s.hasIconFile());
            assertFalse("ID " + s.getId() + " shouldn't have icon URI", s.hasIconUri());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveIconFile(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId() + " shouldn't have icon res ID", s.hasIconResource());
            assertTrue("ID " + s.getId() + " not have icon FD", s.hasIconFile());
            assertFalse("ID " + s.getId() + " shouldn't have icon URI", s.hasIconUri());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveIconUri(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertFalse("ID " + s.getId() + " shouldn't have icon res ID", s.hasIconResource());
            assertFalse("ID " + s.getId() + " shouldn't have have icon FD", s.hasIconFile());
            assertTrue("ID " + s.getId() + " not have icon URI", s.hasIconUri());
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllHaveIcon(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId() + " has no icon ",
                    s.hasIconFile() || s.hasIconResource() || s.getIcon() != null);
        }
        return actualShortcuts;
    }

    public static List<ShortcutInfo> assertAllStringsResolved(
            List<ShortcutInfo> actualShortcuts) {
        for (ShortcutInfo s : actualShortcuts) {
            assertTrue("ID " + s.getId(), s.hasStringResourcesResolved());
        }
        return actualShortcuts;
    }

    public String readTestAsset(String assetPath) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        getTestContext().getResources().getAssets().open(assetPath)))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    protected void prepareGetRoleHoldersAsUser(String homeRoleHolder, int userId) {
        mHomeRoleHolderAsUser.put(userId, homeRoleHolder);
        mService.handleOnDefaultLauncherChanged(userId);
    }

    // Used for get-default-launcher command which is deprecated. Will remove later.
    protected void prepareGetHomeActivitiesAsUser(ComponentName preferred,
            List<ResolveInfo> candidates, int userId) {
        doAnswer(inv -> {
            ((List) inv.getArguments()[0]).addAll(candidates);
            return preferred;
        }).when(mMockPackageManagerInternal).getHomeActivitiesAsUser(any(List.class), eq(userId));
    }

    protected void prepareIntentActivities(ComponentName cn) {
        when(mMockPackageManagerInternal.queryIntentActivities(
                anyOrNull(Intent.class), anyStringOrNull(), anyLong(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(
                        ri(cn.getPackageName(), cn.getClassName(), false, 0)));
    }

    protected static ComponentName cn(String packageName, String name) {
        return new ComponentName(packageName, name);
    }

    protected static ResolveInfo ri(String packageName, String name, boolean isSystem, int priority) {
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.applicationInfo = new ApplicationInfo();

        ri.activityInfo.packageName = packageName;
        ri.activityInfo.name = name;
        if (isSystem) {
            ri.activityInfo.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
        }
        ri.priority = priority;
        return ri;
    }

    protected static ResolveInfo getSystemLauncher() {
        return ri(PACKAGE_SYSTEM_LAUNCHER, PACKAGE_SYSTEM_LAUNCHER_NAME, true,
                PACKAGE_SYSTEM_LAUNCHER_PRIORITY);
    }

    protected static ResolveInfo getFallbackLauncher() {
        return ri(PACKAGE_FALLBACK_LAUNCHER, PACKAGE_FALLBACK_LAUNCHER_NAME, true,
                PACKAGE_FALLBACK_LAUNCHER_PRIORITY);
    }

    protected void makeUidForeground(int uid) {
        try {
            mService.mUidObserver.onUidStateChanged(
                    uid, ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE, 0,
                    ActivityManager.PROCESS_CAPABILITY_NONE);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    protected void makeCallerForeground() {
        makeUidForeground(mInjectedCallingUid);
    }

    protected void makeUidBackground(int uid) {
        try {
            mService.mUidObserver.onUidStateChanged(
                    uid, ActivityManager.PROCESS_STATE_TOP_SLEEPING, 0,
                    ActivityManager.PROCESS_CAPABILITY_NONE);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    protected void makeCallerBackground() {
        makeUidBackground(mInjectedCallingUid);
    }

    protected void publishManifestShortcutsAsCaller(int resId) {
        addManifestShortcutResource(
                new ComponentName(getCallingPackage(), ShortcutActivity.class.getName()),
                resId);
        updatePackageVersion(getCallingPackage(), 1);
        mService.mPackageMonitor.onReceive(getTestContext(),
                genPackageAddIntent(getCallingPackage(), getCallingUserId()));
    }

    protected void assertFileNotExists(String path) {
        final File f = new File(mInjectedFilePathRoot, path);
        assertFalse("File shouldn't exist: " + f.getAbsolutePath(), f.exists());
    }

    protected void assertFileExistsWithContent(String path) {
        final File f = new File(mInjectedFilePathRoot, path);
        assertTrue("File should exist: " + f.getAbsolutePath(), f.exists());
        assertTrue("File should be larger than 0b: " + f.getAbsolutePath(), f.length() > 0);
    }
}
