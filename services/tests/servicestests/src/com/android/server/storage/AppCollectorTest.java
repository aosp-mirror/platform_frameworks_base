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

package com.android.server.storage;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.pm.UserInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.VolumeInfo;
import android.test.AndroidTestCase;
import android.util.ArrayMap;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class AppCollectorTest extends AndroidTestCase {
    private static final long TIMEOUT = TimeUnit.MINUTES.toMillis(1);
    @Mock private Context mContext;
    @Mock private PackageManager mPm;
    @Mock private UserManager mUm;
    @Mock private StorageStatsManager mSsm;
    private List<UserInfo> mUsers;
    private Map<Integer, List<ApplicationInfo>> mUserApps;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPm);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUm);
        when(mContext.getSystemService(Context.STORAGE_STATS_SERVICE)).thenReturn(mSsm);

        // Set up the app list.
        doAnswer((InvocationOnMock invocation) -> {
            Integer userId = (Integer) invocation.getArguments()[1];
            return mUserApps.get(userId);
        }).when(mPm).getInstalledApplicationsAsUser(anyInt(), anyInt());

        // Set up the user list with a single user (0).
        mUsers = new ArrayList<>();
        mUsers.add(new UserInfo(0, "", 0));

        mUserApps = new ArrayMap<>();
        mUserApps.put(0, new ArrayList<>());
        when(mUm.getUsers()).thenReturn(mUsers);
    }

    @Test
    public void testNoApps() throws Exception {
        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        volume.fsUuid = "testuuid";
        AppCollector collector = new AppCollector(mContext, volume);

        assertThat(collector.getPackageStats(TIMEOUT)).isEmpty();
    }

    @Test
    public void testAppOnExternalVolume() throws Exception {
        addApplication("com.test.app", "differentuuid", 0);
        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        volume.fsUuid = "testuuid";
        AppCollector collector = new AppCollector(mContext, volume);

        assertThat(collector.getPackageStats(TIMEOUT)).isEmpty();
    }

    @Test
    public void testOneValidApp() throws Exception {
        addApplication("com.test.app", "testuuid", 0);
        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        volume.fsUuid = "testuuid";
        AppCollector collector = new AppCollector(mContext, volume);
        PackageStats stats = new PackageStats("com.test.app");

        when(mSsm.queryStatsForPackage(eq("testuuid"),
                eq("com.test.app"), eq(UserHandle.of(0)))).thenReturn(new StorageStats());
        assertThat(collector.getPackageStats(TIMEOUT)).containsExactly(stats);
    }

    @Test
    public void testMultipleUsersOneApp() throws Exception {
        addApplication("com.test.app", "testuuid", 0);
        mUserApps.put(1, new ArrayList<>());
        addApplication("com.test.app", "testuuid", 1);
        mUsers.add(new UserInfo(1, "", 0));

        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        volume.fsUuid = "testuuid";
        AppCollector collector = new AppCollector(mContext, volume);
        PackageStats stats = new PackageStats("com.test.app");
        PackageStats otherStats = new PackageStats("com.test.app");
        otherStats.userHandle = 1;

        when(mSsm.queryStatsForPackage(eq("testuuid"),
                eq("com.test.app"), eq(UserHandle.of(0)))).thenReturn(new StorageStats());
        when(mSsm.queryStatsForPackage(eq("testuuid"),
                eq("com.test.app"), eq(UserHandle.of(1)))).thenReturn(new StorageStats());
        assertThat(collector.getPackageStats(TIMEOUT)).containsExactly(stats, otherStats);
    }

    @Test(expected=NullPointerException.class)
    public void testNullVolumeShouldCauseNPE() throws Exception {
        AppCollector collector = new AppCollector(mContext, null);
    }

    @Test
    public void testAppNotFoundDoesntCauseCrash() throws Exception {
        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        addApplication("com.test.app", "uuid", 0);
        mUsers.add(new UserInfo(1, "", 0));
        mUserApps.put(1, new ArrayList<>());
        AppCollector collector = new AppCollector(mContext, volume);
        when(mSsm.queryStatsForPackage(anyString(), anyString(), any(UserHandle.class))).thenThrow(
                new IllegalStateException());

        assertThat(collector.getPackageStats(TIMEOUT)).isEmpty();
    }

    private void addApplication(String packageName, String uuid, int userId) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.volumeUuid = uuid;
        List<ApplicationInfo> userApps = mUserApps.get(userId);
        if (userApps == null) {
            userApps = new ArrayList<>();
            mUserApps.put(userId, userApps);
        }
        userApps.add(info);
    }
}
