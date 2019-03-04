package com.android.settingslib.location;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.PackageOps;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import android.util.LongSparseLongArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
public class RecentLocationAccessesTest {

    private static final int TEST_UID = 1234;
    private static final long NOW = 1_000_000_000;  // Approximately 9/8/2001
    private static final long ONE_MIN_AGO = NOW - TimeUnit.MINUTES.toMillis(1);
    private static final long TWENTY_THREE_HOURS_AGO = NOW - TimeUnit.HOURS.toMillis(23);
    private static final long TWO_DAYS_AGO = NOW - TimeUnit.DAYS.toMillis(2);
    private static final String[] TEST_PACKAGE_NAMES =
            {"package_1MinAgo", "package_14MinAgo", "package_20MinAgo"};

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private Clock mClock;
    private Context mContext;
    private int mTestUserId;
    private RecentLocationAccesses mRecentLocationAccesses;

    @Before
    public void setUp() throws NameNotFoundException {
        MockitoAnnotations.initMocks(this);
        mContext = spy(RuntimeEnvironment.application);

        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUserManager);
        when(mPackageManager.getApplicationLabel(isA(ApplicationInfo.class)))
                .thenReturn("testApplicationLabel");
        when(mPackageManager.getUserBadgedLabel(isA(CharSequence.class), isA(UserHandle.class)))
                .thenReturn("testUserBadgedLabel");
        mTestUserId = UserHandle.getUserId(TEST_UID);
        when(mUserManager.getUserProfiles())
                .thenReturn(Collections.singletonList(new UserHandle(mTestUserId)));

        long[] testRequestTime = {ONE_MIN_AGO, TWENTY_THREE_HOURS_AGO, TWO_DAYS_AGO};
        List<PackageOps> appOps = createTestPackageOpsList(TEST_PACKAGE_NAMES, testRequestTime);
        when(mAppOpsManager.getPackagesForOps(RecentLocationAccesses.LOCATION_OPS)).thenReturn(
                appOps);
        mockTestApplicationInfos(mTestUserId, TEST_PACKAGE_NAMES);

        when(mClock.millis()).thenReturn(NOW);
        mRecentLocationAccesses = new RecentLocationAccesses(mContext, mClock);
    }

    @Test
    public void testGetAppList_shouldFilterRecentAccesses() {
        List<RecentLocationAccesses.Access> requests = mRecentLocationAccesses.getAppList();
        // Only two of the apps have requested location within 15 min.
        assertThat(requests).hasSize(2);
        // Make sure apps are ordered by recency
        assertThat(requests.get(0).packageName).isEqualTo(TEST_PACKAGE_NAMES[0]);
        assertThat(requests.get(0).accessFinishTime).isEqualTo(ONE_MIN_AGO);
        assertThat(requests.get(1).packageName).isEqualTo(TEST_PACKAGE_NAMES[1]);
        assertThat(requests.get(1).accessFinishTime).isEqualTo(TWENTY_THREE_HOURS_AGO);
    }

    @Test
    public void testGetAppList_shouldNotShowAndroidOS() throws NameNotFoundException {
        // Add android OS to the list of apps.
        PackageOps androidSystemPackageOps =
                createPackageOps(
                        RecentLocationAccesses.ANDROID_SYSTEM_PACKAGE_NAME,
                        Process.SYSTEM_UID,
                        AppOpsManager.OP_FINE_LOCATION,
                        ONE_MIN_AGO);
        long[] testRequestTime =
                {ONE_MIN_AGO, TWENTY_THREE_HOURS_AGO, TWO_DAYS_AGO, ONE_MIN_AGO};
        List<PackageOps> appOps = createTestPackageOpsList(TEST_PACKAGE_NAMES, testRequestTime);
        appOps.add(androidSystemPackageOps);
        when(mAppOpsManager.getPackagesForOps(RecentLocationAccesses.LOCATION_OPS)).thenReturn(
                appOps);
        mockTestApplicationInfos(
                Process.SYSTEM_UID, RecentLocationAccesses.ANDROID_SYSTEM_PACKAGE_NAME);

        List<RecentLocationAccesses.Access> requests = mRecentLocationAccesses.getAppList();
        // Android OS shouldn't show up in the list of apps.
        assertThat(requests).hasSize(2);
        // Make sure apps are ordered by recency
        assertThat(requests.get(0).packageName).isEqualTo(TEST_PACKAGE_NAMES[0]);
        assertThat(requests.get(0).accessFinishTime).isEqualTo(ONE_MIN_AGO);
        assertThat(requests.get(1).packageName).isEqualTo(TEST_PACKAGE_NAMES[1]);
        assertThat(requests.get(1).accessFinishTime).isEqualTo(TWENTY_THREE_HOURS_AGO);
    }

    private void mockTestApplicationInfos(int userId, String... packageNameList)
            throws NameNotFoundException {
        for (String packageName : packageNameList) {
            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = packageName;
            when(mPackageManager.getApplicationInfoAsUser(
                    packageName, PackageManager.GET_META_DATA, userId)).thenReturn(appInfo);
        }
    }

    private List<PackageOps> createTestPackageOpsList(String[] packageNameList, long[] time) {
        List<PackageOps> packageOpsList = new ArrayList<>();
        for (int i = 0; i < packageNameList.length; i++) {
            PackageOps packageOps = createPackageOps(
                    packageNameList[i],
                    TEST_UID,
                    AppOpsManager.OP_FINE_LOCATION,
                    time[i]);
            packageOpsList.add(packageOps);
        }
        return packageOpsList;
    }

    private PackageOps createPackageOps(String packageName, int uid, int op, long time) {
        return new PackageOps(
                packageName,
                uid,
                Collections.singletonList(createOpEntryWithTime(op, time)));
    }

    private OpEntry createOpEntryWithTime(int op, long time) {
        // Slot for background access timestamp.
        final LongSparseLongArray accessTimes = new LongSparseLongArray();
        accessTimes.put(AppOpsManager.makeKey(AppOpsManager.UID_STATE_BACKGROUND,
            AppOpsManager.OP_FLAG_SELF), time);

        return new OpEntry(op, false, AppOpsManager.MODE_ALLOWED, accessTimes, null /*durations*/,
            null /*rejectTimes*/, null /* proxyUids */, null /* proxyPackages */);
    }
}
