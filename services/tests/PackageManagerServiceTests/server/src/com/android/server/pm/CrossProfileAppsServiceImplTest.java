package com.android.server.pm;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.IApplicationThread;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.AttributionSourceState;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.CrossProfileAppsInternal;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;
import android.permission.PermissionCheckerManager;
import android.permission.PermissionManager;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.SparseArray;

import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.FunctionalUtils.ThrowingSupplier;
import com.android.server.LocalServices;
import com.android.server.pm.permission.PermissionManagerService;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Build/Install/Run:
 * atest PackageManagerServiceServerTests:com.android.server.pm.CrossProfileAppsServiceImplTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class CrossProfileAppsServiceImplTest {
    private static final String PACKAGE_ONE = "com.one";
    private static final String FEATURE_ID = "feature.one";
    private static final int PACKAGE_ONE_UID = 1111;
    private static final ComponentName ACTIVITY_COMPONENT =
            new ComponentName("com.one", "test");

    private static final String PACKAGE_TWO = "com.two";
    private static final int PACKAGE_TWO_UID = 2222;

    private static final int PRIMARY_USER = 0;
    private static final int PROFILE_OF_PRIMARY_USER = 10;
    private static final int SECONDARY_USER = 11;

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private ActivityManagerInternal mActivityManagerInternal;
    @Mock
    private ActivityTaskManagerInternal mActivityTaskManagerInternal;
    @Mock
    private IPackageManager mIPackageManager;
    @Mock
    private DevicePolicyManagerInternal mDevicePolicyManagerInternal;

    private TestInjector mTestInjector;
    private ActivityInfo mActivityInfo;
    private CrossProfileAppsServiceImpl mCrossProfileAppsServiceImpl;
    private IApplicationThread mIApplicationThread;

    private SparseArray<Boolean> mUserEnabled = new SparseArray<>();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public void initCrossProfileAppsServiceImpl() {
        mTestInjector = new TestInjector();
        LocalServices.removeServiceForTest(CrossProfileAppsInternal.class);
        mCrossProfileAppsServiceImpl = new CrossProfileAppsServiceImpl(mContext, mTestInjector);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
    }

    @Before
    public void setupEnabledProfiles() {
        mUserEnabled.put(PRIMARY_USER, true);
        mUserEnabled.put(PROFILE_OF_PRIMARY_USER, true);
        mUserEnabled.put(SECONDARY_USER, true);
        mSetFlagsRule.enableFlags(android.multiuser.Flags.FLAG_ENABLE_HIDING_PROFILES);

        when(mUserManager.getProfileIdsExcludingHidden(anyInt(), eq(true))).thenAnswer(
                invocation -> {
                    List<Integer> users = new ArrayList<>();
                    final int targetUser = invocation.getArgument(0);
                    users.add(targetUser);

                    int profileUserId = -1;
                    if (targetUser == PRIMARY_USER) {
                        profileUserId = PROFILE_OF_PRIMARY_USER;
                    } else if (targetUser == PROFILE_OF_PRIMARY_USER) {
                        profileUserId = PRIMARY_USER;
                    }

                    if (profileUserId != -1 && mUserEnabled.get(profileUserId)) {
                        users.add(profileUserId);
                    }
                    return users.stream().mapToInt(i -> i).toArray();
                });
    }

    @Before
    public void setupCaller() {
        mTestInjector.setCallingUid(PACKAGE_ONE_UID);
        mTestInjector.setCallingUserId(PRIMARY_USER);
    }

    @Before
    public void setupPackage() throws Exception {
        // PACKAGE_ONE are installed in all users.
        mockAppsInstalled(PACKAGE_ONE, PRIMARY_USER, true);
        mockAppsInstalled(PACKAGE_ONE, PROFILE_OF_PRIMARY_USER, true);
        mockAppsInstalled(PACKAGE_ONE, SECONDARY_USER, true);

        // Packages are resolved to their corresponding UID.
        doAnswer(invocation -> {
            final int uid = invocation.getArgument(0);
            final String packageName = invocation.getArgument(1);
            if (uid == PACKAGE_ONE_UID && PACKAGE_ONE.equals(packageName)) {
                return null;
            } else if (uid ==PACKAGE_TWO_UID && PACKAGE_TWO.equals(packageName)) {
                return null;
            }
            throw new SecurityException("Not matching");
        }).when(mAppOpsManager).checkPackage(anyInt(), anyString());

        // The intent is resolved to the ACTIVITY_COMPONENT.
        mockActivityLaunchIntentResolvedTo(ACTIVITY_COMPONENT);
    }

    @Test
    public void getTargetUserProfiles_fromPrimaryUser_installed() throws Exception {
        List<UserHandle> targetProfiles =
                mCrossProfileAppsServiceImpl.getTargetUserProfiles(PACKAGE_ONE);
        assertThat(targetProfiles).containsExactly(UserHandle.of(PROFILE_OF_PRIMARY_USER));
    }

    @Test
    public void getTargetUserProfiles_fromPrimaryUser_notInstalled() throws Exception {
        mockAppsInstalled(PACKAGE_ONE, PROFILE_OF_PRIMARY_USER, false);

        List<UserHandle> targetProfiles =
                mCrossProfileAppsServiceImpl.getTargetUserProfiles(PACKAGE_ONE);
        assertThat(targetProfiles).isEmpty();
    }

    @Test
    public void getTargetUserProfiles_fromPrimaryUser_userNotEnabled() throws Exception {
        mUserEnabled.put(PROFILE_OF_PRIMARY_USER, false);

        List<UserHandle> targetProfiles =
                mCrossProfileAppsServiceImpl.getTargetUserProfiles(PACKAGE_ONE);
        assertThat(targetProfiles).isEmpty();
    }

    @Test
    public void getTargetUserProfiles_fromSecondaryUser() throws Exception {
        mTestInjector.setCallingUserId(SECONDARY_USER);

        List<UserHandle> targetProfiles =
                mCrossProfileAppsServiceImpl.getTargetUserProfiles(PACKAGE_ONE);
        assertThat(targetProfiles).isEmpty();
    }

    @Test
    public void getTargetUserProfiles_fromProfile_installed() throws Exception {
        mTestInjector.setCallingUserId(PROFILE_OF_PRIMARY_USER);

        List<UserHandle> targetProfiles =
                mCrossProfileAppsServiceImpl.getTargetUserProfiles(PACKAGE_ONE);
        assertThat(targetProfiles).containsExactly(UserHandle.of(PRIMARY_USER));
    }

    @Test
    public void getTargetUserProfiles_fromProfile_notInstalled() throws Exception {
        mTestInjector.setCallingUserId(PROFILE_OF_PRIMARY_USER);
        mockAppsInstalled(PACKAGE_ONE, PRIMARY_USER, false);

        List<UserHandle> targetProfiles =
                mCrossProfileAppsServiceImpl.getTargetUserProfiles(PACKAGE_ONE);
        assertThat(targetProfiles).isEmpty();
    }

    @Test(expected = SecurityException.class)
    public void getTargetUserProfiles_fakeCaller() throws Exception {
        mCrossProfileAppsServiceImpl.getTargetUserProfiles(PACKAGE_TWO);
    }

    @Test
    public void startActivityAsUser_currentUser() throws Exception {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PRIMARY_USER).getIdentifier(),
                                true,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startAnyActivityAsUser_currentUser() {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PRIMARY_USER).getIdentifier(),
                                false,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startActivityAsUser_profile_notInstalled() throws Exception {
        mockAppsInstalled(PACKAGE_ONE, PROFILE_OF_PRIMARY_USER, false);

        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                true,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startAnyActivityAsUser_profile_notInstalled() {
        mockAppsInstalled(PACKAGE_ONE, PROFILE_OF_PRIMARY_USER, false);

        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                false,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startActivityAsUser_profile_fakeCaller() throws Exception {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_TWO,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                true,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startAnyActivityAsUser_profile_fakeCaller() {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_TWO,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                false,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startActivityAsUser_profile_notExported() throws Exception {
        mActivityInfo.exported = false;

        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                true,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startAnyActivityAsUser_profile_notExported() {
        try {
            when(mPackageManager.getPermissionInfo(anyString(), anyInt()))
                    .thenReturn(new PermissionInfo());
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        mActivityInfo.exported = false;


        // There's a bug in static mocking if the APK is large - so here is the next best thing...
        doReturn(Context.PERMISSION_CHECKER_SERVICE).when(mContext)
                .getSystemServiceName(PermissionCheckerManager.class);
        PermissionCheckerManager permissionCheckerManager = mock(PermissionCheckerManager.class);
        doReturn(PermissionChecker.PERMISSION_HARD_DENIED).when(permissionCheckerManager)
                .checkPermission(eq(Manifest.permission.INTERACT_ACROSS_PROFILES), any(
                        AttributionSourceState.class), anyString(), anyBoolean(), anyBoolean(),
                        anyBoolean(), anyInt());
        doReturn(permissionCheckerManager).when(mContext).getSystemService(
                Context.PERMISSION_CHECKER_SERVICE);

        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                false,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startActivityAsUser_profile_anotherPackage() throws Exception {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                new ComponentName(PACKAGE_TWO, "test"),
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                true,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startAnyActivityAsUser_profile_anotherPackage() {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                new ComponentName(PACKAGE_TWO, "test"),
                                UserHandle.of(PROFILE_OF_PRIMARY_USER).getIdentifier(),
                                false,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startActivityAsUser_secondaryUser() throws Exception {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(SECONDARY_USER).getIdentifier(),
                                true,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startAnyActivityAsUser_secondaryUser() {
        assertThrows(
                SecurityException.class,
                () ->
                        mCrossProfileAppsServiceImpl.startActivityAsUser(
                                mIApplicationThread,
                                PACKAGE_ONE,
                                FEATURE_ID,
                                ACTIVITY_COMPONENT,
                                UserHandle.of(SECONDARY_USER).getIdentifier(),
                                false,
                                /* targetTask */ null,
                                /* options */ null));

        verify(mActivityTaskManagerInternal, never())
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        anyString(),
                        nullable(String.class),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        anyInt());
    }

    @Test
    public void startActivityAsUser_fromProfile_success() throws Exception {
        mTestInjector.setCallingUserId(PROFILE_OF_PRIMARY_USER);

        mCrossProfileAppsServiceImpl.startActivityAsUser(
                mIApplicationThread,
                PACKAGE_ONE,
                FEATURE_ID,
                ACTIVITY_COMPONENT,
                UserHandle.of(PRIMARY_USER).getIdentifier(),
                true,
                /* targetTask */ null,
                /* options */ null);

        verify(mActivityTaskManagerInternal)
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        eq(PACKAGE_ONE),
                        eq(FEATURE_ID),
                        any(Intent.class),
                        nullable(IBinder.class),
                        anyInt(),
                        nullable(Bundle.class),
                        eq(PRIMARY_USER));
    }

    @Test
    public void startActivityAsUser_sameTask_fromProfile_success() throws Exception {
        mTestInjector.setCallingUserId(PROFILE_OF_PRIMARY_USER);

        Bundle options = ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle();
        Binder targetTask = new Binder();
        mCrossProfileAppsServiceImpl.startActivityAsUser(
                mIApplicationThread,
                PACKAGE_ONE,
                FEATURE_ID,
                ACTIVITY_COMPONENT,
                UserHandle.of(PRIMARY_USER).getIdentifier(),
                true,
                targetTask,
                options);
        verify(mActivityTaskManagerInternal)
                .startActivityAsUser(
                        nullable(IApplicationThread.class),
                        eq(PACKAGE_ONE),
                        eq(FEATURE_ID),
                        any(Intent.class),
                        eq(targetTask),
                        anyInt(),
                        eq(options),
                        eq(PRIMARY_USER));
    }

    private void mockAppsInstalled(String packageName, int user, boolean installed) {
        when(mPackageManagerInternal.getPackageInfo(
                eq(packageName),
                anyLong(),
                anyInt(),
                eq(user)))
                .thenReturn(installed ? createInstalledPackageInfo() : null);
    }

    private PackageInfo createInstalledPackageInfo() {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.enabled = true;
        return packageInfo;
    }

    private void mockActivityLaunchIntentResolvedTo(ComponentName componentName) {
        ResolveInfo resolveInfo = new ResolveInfo();
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = componentName.getPackageName();
        activityInfo.name = componentName.getClassName();
        activityInfo.exported = true;
        resolveInfo.activityInfo = activityInfo;
        mActivityInfo = activityInfo;

        when(mPackageManagerInternal.queryIntentActivities(
                any(Intent.class), nullable(String.class), anyLong(), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(resolveInfo));
    }

    private class TestInjector implements CrossProfileAppsServiceImpl.Injector {
        private int mCallingUid;
        private int mCallingUserId;
        private int mCallingPid;

        public void setCallingUid(int uid) {
            mCallingUid = uid;
        }

        public void setCallingPid(int pid) {
            mCallingPid = pid;
        }

        public void setCallingUserId(int userId) {
            mCallingUserId = userId;
        }

        @Override
        public int getCallingUid() {
            return mCallingUid;
        }

        @Override
        public int getCallingPid() {
            return mCallingPid;
        }

        @Override
        public int getCallingUserId() {
            return mCallingUserId;
        }

        @Override
        public UserHandle getCallingUserHandle() {
            return UserHandle.of(mCallingUserId);
        }

        @Override
        public long clearCallingIdentity() {
            return 0;
        }

        @Override
        public void restoreCallingIdentity(long token) {
        }

        @Override
        public void withCleanCallingIdentity(ThrowingRunnable action) {
            action.run();
        }

        @Override
        public <T> T withCleanCallingIdentity(ThrowingSupplier<T> action) {
            return action.get();
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public PackageManagerInternal getPackageManagerInternal() {
            return mPackageManagerInternal;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public AppOpsManager getAppOpsManager() {
            return mAppOpsManager;
        }

        @Override
        public ActivityManagerInternal getActivityManagerInternal() {
            return mActivityManagerInternal;
        }

        @Override
        public ActivityTaskManagerInternal getActivityTaskManagerInternal() {
            return mActivityTaskManagerInternal;
        }

        @Override
        public IPackageManager getIPackageManager() {
            return mIPackageManager;
        }

        @Override
        public DevicePolicyManagerInternal getDevicePolicyManagerInternal() {
            return mDevicePolicyManagerInternal;
        }

        @Override
        public void sendBroadcastAsUser(Intent intent, UserHandle user) {
            mContext.sendBroadcastAsUser(intent, user);
        }

        @Override
        public int checkComponentPermission(
                String permission, int uid, int owningUid, boolean exported) {
            return ActivityManager.checkComponentPermission(permission, uid, owningUid, exported);
        }

        @Override
        public void killUid(int uid) {
            PermissionManagerService.killUid(
                    UserHandle.getAppId(uid),
                    UserHandle.getUserId(uid),
                    PermissionManager.KILL_APP_REASON_PERMISSIONS_REVOKED);
        }
    }
}
