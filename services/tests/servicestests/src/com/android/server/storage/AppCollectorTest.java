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

import android.content.pm.UserInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageStatsObserver;
import android.content.pm.PackageManager;
import android.content.pm.PackageStats;
import android.os.UserManager;
import android.os.storage.VolumeInfo;
import android.test.AndroidTestCase;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class AppCollectorTest extends AndroidTestCase {
    private static final long TIMEOUT = TimeUnit.MINUTES.toMillis(1);
    @Mock private Context mContext;
    @Mock private PackageManager mPm;
    @Mock private UserManager mUm;
    private List<ApplicationInfo> mApps;
    private List<UserInfo> mUsers;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        mApps = new ArrayList<>();
        when(mContext.getPackageManager()).thenReturn(mPm);
        when(mContext.getSystemService(Context.USER_SERVICE)).thenReturn(mUm);

        // Set up the app list.
        when(mPm.getInstalledApplications(anyInt())).thenReturn(mApps);

        // Set up the user list with a single user (0).
        mUsers = new ArrayList<>();
        mUsers.add(new UserInfo(0, "", 0));
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
        addApplication("com.test.app", "differentuuid");
        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        volume.fsUuid = "testuuid";
        AppCollector collector = new AppCollector(mContext, volume);

        assertThat(collector.getPackageStats(TIMEOUT)).isEmpty();
    }

    @Test
    public void testOneValidApp() throws Exception {
        addApplication("com.test.app", "testuuid");
        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        volume.fsUuid = "testuuid";
        AppCollector collector = new AppCollector(mContext, volume);
        PackageStats stats = new PackageStats("com.test.app");

        // Set up this to handle the asynchronous call to the PackageManager. This returns the
        // package info for the specified package.
        doAnswer(new Answer<Void>() {
             @Override
             public Void answer(InvocationOnMock invocation) {
                 try {
                     ((IPackageStatsObserver.Stub) invocation.getArguments()[2])
                             .onGetStatsCompleted(stats, true);
                 } catch (Exception e) {
                     // We fail instead of just letting the exception fly because throwing
                     // out of the callback like this on the background thread causes the test
                     // runner to crash, rather than reporting the failure.
                     fail();
                 }
                 return null;
             }
        }).when(mPm).getPackageSizeInfoAsUser(eq("com.test.app"), eq(0), any());


        // Because getPackageStats is a blocking call, we block execution of the test until the
        // call finishes. In order to finish the call, we need the above answer to execute.
        List<PackageStats> myStats = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                myStats.addAll(collector.getPackageStats(TIMEOUT));
                latch.countDown();
            }
        }).start();
        latch.await();

        assertThat(myStats).containsExactly(stats);
    }

    @Test
    public void testMultipleUsersOneApp() throws Exception {
        addApplication("com.test.app", "testuuid");
        ApplicationInfo otherUsersApp = new ApplicationInfo();
        otherUsersApp.packageName = "com.test.app";
        otherUsersApp.volumeUuid = "testuuid";
        otherUsersApp.uid = 1;
        mUsers.add(new UserInfo(1, "", 0));

        VolumeInfo volume = new VolumeInfo("testuuid", 0, null, null);
        volume.fsUuid = "testuuid";
        AppCollector collector = new AppCollector(mContext, volume);
        PackageStats stats = new PackageStats("com.test.app");
        PackageStats otherStats = new PackageStats("com.test.app");
        otherStats.userHandle = 1;

        // Set up this to handle the asynchronous call to the PackageManager. This returns the
        // package info for our packages.
        doAnswer(new Answer<Void>() {
             @Override
             public Void answer(InvocationOnMock invocation) {
                 try {
                     ((IPackageStatsObserver.Stub) invocation.getArguments()[2])
                             .onGetStatsCompleted(stats, true);

                     // Now callback for the other uid.
                     ((IPackageStatsObserver.Stub) invocation.getArguments()[2])
                             .onGetStatsCompleted(otherStats, true);
                 } catch (Exception e) {
                     // We fail instead of just letting the exception fly because throwing
                     // out of the callback like this on the background thread causes the test
                     // runner to crash, rather than reporting the failure.
                     fail();
                 }
                 return null;
             }
        }).when(mPm).getPackageSizeInfoAsUser(eq("com.test.app"), eq(0), any());


        // Because getPackageStats is a blocking call, we block execution of the test until the
        // call finishes. In order to finish the call, we need the above answer to execute.
        List<PackageStats> myStats = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        new Thread(new Runnable() {
            @Override
            public void run() {
                myStats.addAll(collector.getPackageStats(TIMEOUT));
                latch.countDown();
            }
        }).start();
        latch.await();

        assertThat(myStats).containsAllOf(stats, otherStats);
    }

    @Test(expected=NullPointerException.class)
    public void testNullVolumeShouldCauseNPE() throws Exception {
        AppCollector collector = new AppCollector(mContext, null);
    }

    private void addApplication(String packageName, String uuid) {
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = packageName;
        info.volumeUuid = uuid;
        mApps.add(info);
    }

}
