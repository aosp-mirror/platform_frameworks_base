/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.net.watchlist;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.FileUtils;
import android.os.Looper;
import android.os.UserManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * runtest frameworks-services -c com.android.server.net.watchlist.WatchlistLoggingHandlerTests
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class WatchlistLoggingHandlerTests {

    private static final String APK_A = "A.apk";
    private static final String APK_B = "B.apk";
    private static final String APK_C = "C.apk";
    private static final String APK_A_CONTENT = "AAA";
    private static final String APK_B_CONTENT = "BBB";
    private static final String APK_C_CONTENT = "CCC";
    // Sha256 of "AAA"
    private static final String APK_A_CONTENT_HASH =
            "CB1AD2119D8FAFB69566510EE712661F9F14B83385006EF92AEC47F523A38358";
    // Sha256 of "BBB"
    private static final String APK_B_CONTENT_HASH =
            "DCDB704109A454784B81229D2B05F368692E758BFA33CB61D04C1B93791B0273";

    private Context mServiceContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        final Context context = InstrumentationRegistry.getContext();
        final UserManager mockUserManager = mock(UserManager.class);
        final PackageManager mockPackageManager = mock(PackageManager.class);

        // Context that will be used by WatchlistLoggingHandler
        mServiceContext = new ContextWrapper(context) {
            @Override
            public PackageManager getPackageManager() {
                return mockPackageManager;
            }

            @Override
            public Object getSystemService(String name) {
                switch (name) {
                    case Context.USER_SERVICE:
                        return mockUserManager;
                    default:
                        return super.getSystemService(name);
                }
            }
        };

        // Returns 2 users, user 0 and user 10
        doAnswer((InvocationOnMock invocation) -> {
            final ArrayList<UserInfo> info = new ArrayList<>();
            info.add(new UserInfo(0, "user1", 0));
            info.add(new UserInfo(10, "user2", 0));
            return info;
        }).when(mockUserManager).getUsers();

        // Returns 2 apps, with uid 1 and uid 2
        doAnswer((InvocationOnMock invocation) -> {
            final List<ApplicationInfo> result = new ArrayList<>();
            ApplicationInfo info1 = new ApplicationInfo();
            info1.uid = 1;
            result.add(info1);
            ApplicationInfo info2 = new ApplicationInfo();
            info2.uid = 2;
            result.add(info2);
            return result;
        }).when(mockPackageManager).getInstalledApplications(anyInt());

        // Uid 1 app is installed in primary user only and package name is "A"
        // Uid 2 app is installed in both primary user and secondary user, package name is "B"
        // Uid 3 app is installed in secondary user and package name is "C"
        doAnswer((InvocationOnMock invocation) -> {
            int uid = (int) invocation.getArguments()[0];
            if (uid == 1) {
                return new String[]{"A"};
            } else if (uid == 1000001) {
                return null;
            } else if (uid == 2) {
                return new String[]{"B"};
            } else if (uid == 1000002) {
                return new String[]{"B"};
            } else if (uid == 3) {
                return null;
            } else if (uid == 1000002) {
                return new String[]{"C"};
            }
            return null;
        }).when(mockPackageManager).getPackagesForUid(anyInt());

        String fileDir = InstrumentationRegistry.getContext().getFilesDir().getAbsolutePath();
        // Simulate app's apk file path
        doAnswer((InvocationOnMock invocation) -> {
            String pkg = (String) invocation.getArguments()[0];
            PackageInfo result = new PackageInfo();
            result.applicationInfo = new ApplicationInfo();
            result.applicationInfo.publicSourceDir = fileDir + "/" + pkg + ".apk";
            return result;
        }).when(mockPackageManager).getPackageInfoAsUser(anyString(), anyInt(), anyInt());

        FileUtils.bytesToFile(fileDir + "/" + APK_A, APK_A_CONTENT.getBytes());
        FileUtils.bytesToFile(fileDir + "/" + APK_B, APK_B_CONTENT.getBytes());
    }

    @After
    public void tearDown() {
        String fileDir = InstrumentationRegistry.getContext().getFilesDir().getAbsolutePath();
        new File(fileDir, APK_A).delete();
        new File(fileDir, APK_B).delete();
    }

    @Test
    public void testWatchlistLoggingHandler_getAllDigestsForReportWithMultiUsers()
            throws Exception {
        List<String> result = new WatchlistLoggingHandler(mServiceContext,
                Looper.getMainLooper()).getAllDigestsForReport(
                new WatchlistReportDbHelper.AggregatedResult(new HashSet<String>(), null,
                        new HashMap<String, String>()));
        assertEquals(2, result.size());
        assertEquals(APK_A_CONTENT_HASH, result.get(0));
        assertEquals(APK_B_CONTENT_HASH, result.get(1));
    }

    @Test
    public void testWatchlistLoggingHandler_getAllSubDomains() throws Exception {
        String[] subDomains = WatchlistLoggingHandler.getAllSubDomains("abc.def.gh.i.jkl.mm");
        assertTrue(Arrays.equals(subDomains, new String[]{"abc.def.gh.i.jkl.mm",
                "def.gh.i.jkl.mm", "gh.i.jkl.mm", "i.jkl.mm", "jkl.mm", "mm"}));
        subDomains = WatchlistLoggingHandler.getAllSubDomains(null);
        assertNull(subDomains);
        subDomains = WatchlistLoggingHandler.getAllSubDomains("jkl.mm");
        assertTrue(Arrays.equals(subDomains, new String[]{"jkl.mm", "mm"}));
        subDomains = WatchlistLoggingHandler.getAllSubDomains("abc");
        assertTrue(Arrays.equals(subDomains, new String[]{"abc"}));
        subDomains = WatchlistLoggingHandler.getAllSubDomains("jkl.mm.");
        assertTrue(Arrays.equals(subDomains, new String[]{"jkl.mm.", "mm."}));
    }
}
