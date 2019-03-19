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

package com.android.server.pm;

import android.content.IIntentReceiver;
import android.os.Bundle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

// runtest -c com.android.server.pm.PackageManagerServiceTest frameworks-services
// bit FrameworksServicesTests:com.android.server.pm.PackageManagerServiceTest
@RunWith(AndroidJUnit4.class)
public class PackageManagerServiceTest {
    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testPackageRemoval() throws Exception {
        class PackageSenderImpl implements PackageSender {
            public void sendPackageBroadcast(final String action, final String pkg,
                    final Bundle extras, final int flags, final String targetPkg,
                    final IIntentReceiver finishedReceiver, final int[] userIds,
                    int[] instantUserIds) {
            }

            public void sendPackageAddedForNewUsers(String packageName,
                    boolean sendBootComplete, boolean includeStopped, int appId,
                    int[] userIds, int[] instantUserIds) {
            }

            @Override
            public void notifyPackageAdded(String packageName, int uid) {
            }

            @Override
            public void notifyPackageRemoved(String packageName, int uid) {
            }
        }

        PackageSenderImpl sender = new PackageSenderImpl();
        PackageSetting setting = null;
        PackageManagerService.PackageRemovedInfo pri =
                new PackageManagerService.PackageRemovedInfo(sender);

        // Initial conditions: nothing there
        Assert.assertNull(pri.removedUsers);
        Assert.assertNull(pri.broadcastUsers);

        // populateUsers with nothing leaves nothing
        pri.populateUsers(null, setting);
        Assert.assertNull(pri.broadcastUsers);

        // Create a real (non-null) PackageSetting and confirm that the removed
        // users are copied properly
        setting = new PackageSetting("name", "realName", new File("codePath"),
                new File("resourcePath"), "legacyNativeLibraryPathString",
                "primaryCpuAbiString", "secondaryCpuAbiString",
                "cpuAbiOverrideString", 0, 0, 0, "parentPackageName", null, 0,
                null, null);
        pri.populateUsers(new int[] {
                1, 2, 3, 4, 5
        }, setting);
        Assert.assertNotNull(pri.broadcastUsers);
        Assert.assertEquals(5, pri.broadcastUsers.length);
        Assert.assertNotNull(pri.instantUserIds);
        Assert.assertEquals(0, pri.instantUserIds.length);

        // Exclude a user
        pri.broadcastUsers = null;
        final int EXCLUDED_USER_ID = 4;
        setting.setInstantApp(true, EXCLUDED_USER_ID);
        pri.populateUsers(new int[] {
                1, 2, 3, EXCLUDED_USER_ID, 5
        }, setting);
        Assert.assertNotNull(pri.broadcastUsers);
        Assert.assertEquals(4, pri.broadcastUsers.length);
        Assert.assertNotNull(pri.instantUserIds);
        Assert.assertEquals(1, pri.instantUserIds.length);

        // TODO: test that sendApplicationHiddenForUser() actually fills in
        // broadcastUsers
    }
}
