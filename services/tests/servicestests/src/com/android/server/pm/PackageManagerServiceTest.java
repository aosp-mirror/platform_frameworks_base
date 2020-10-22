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

import static com.google.common.truth.Truth.assertWithMessage;

import static java.lang.reflect.Modifier.isFinal;
import static java.lang.reflect.Modifier.isPublic;
import static java.lang.reflect.Modifier.isStatic;

import android.content.IIntentReceiver;
import android.content.pm.PackageManagerInternal;
import android.os.Bundle;
import android.util.SparseArray;

import androidx.test.runner.AndroidJUnit4;

import com.google.android.collect.Lists;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

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
                    int[] instantUserIds, SparseArray<int[]> broadcastAllowList) {
            }

            public void sendPackageAddedForNewUsers(String packageName,
                    boolean sendBootComplete, boolean includeStopped, int appId,
                    int[] userIds, int[] instantUserIds, int dataLoaderType) {
            }

            @Override
            public void notifyPackageAdded(String packageName, int uid) {
            }

            @Override
            public void notifyPackageChanged(String packageName, int uid) {

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
                "legacyNativeLibraryPathString", "primaryCpuAbiString", "secondaryCpuAbiString",
                "cpuAbiOverrideString", 0, 0, 0, 0,
                null, null, null);
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

    @Test
    public void testPartitions() throws Exception {
        String[] partitions = { "system", "vendor", "odm", "oem", "product", "system_ext" };
        String[] appdir = { "app", "priv-app" };
        for (int i = 0; i < partitions.length; i++) {
            final PackageManagerService.ScanPartition scanPartition =
                    PackageManagerService.SYSTEM_PARTITIONS.get(i);
            for (int j = 0; j < appdir.length; j++) {
                File path = new File(String.format("%s/%s/A.apk", partitions[i], appdir[j]));
                Assert.assertEquals(j == 1 && i != 3, scanPartition.containsPrivApp(path));

                final int scanFlag = scanPartition.scanFlag;
                Assert.assertEquals(i == 1, scanFlag == PackageManagerService.SCAN_AS_VENDOR);
                Assert.assertEquals(i == 2, scanFlag == PackageManagerService.SCAN_AS_ODM);
                Assert.assertEquals(i == 3, scanFlag == PackageManagerService.SCAN_AS_OEM);
                Assert.assertEquals(i == 4, scanFlag == PackageManagerService.SCAN_AS_PRODUCT);
                Assert.assertEquals(i == 5, scanFlag == PackageManagerService.SCAN_AS_SYSTEM_EXT);
            }
        }
    }

    @Test
    public void testKnownPackageToString_shouldNotGetUnknown() {
        final List<String> packageNames = new ArrayList<>();
        for (int i = 0; i <= PackageManagerInternal.LAST_KNOWN_PACKAGE; i++) {
            packageNames.add(PackageManagerInternal.knownPackageToString(i));
        }
        assertWithMessage(
                "The Ids of KnownPackage should be continuous and the string representation "
                        + "should not be unknown.").that(
                packageNames).containsNoneIn(Lists.newArrayList("Unknown"));
    }

    @Test
    public void testKnownPackage_lastKnownPackageIsTheLast() throws Exception {
        final List<Integer> knownPackageIds = getKnownPackageIdsList();
        assertWithMessage(
                "The last KnownPackage Id should be assigned to PackageManagerInternal"
                        + ".LAST_KNOWN_PACKAGE.").that(
                knownPackageIds.get(knownPackageIds.size() - 1)).isEqualTo(
                PackageManagerInternal.LAST_KNOWN_PACKAGE);
    }

    @Test
    public void testKnownPackage_IdsShouldBeUniqueAndContinuous() throws Exception {
        final List<Integer> knownPackageIds = getKnownPackageIdsList();
        for (int i = 0, size = knownPackageIds.size(); i < size - 1; i++) {
            assertWithMessage(
                    "The KnownPackage Ids should be unique and continuous. KnownPackageIds = "
                            + Arrays.toString(knownPackageIds.toArray())).that(
                    knownPackageIds.get(i) + 1).isEqualTo(knownPackageIds.get(i + 1));
        }
    }

    private List<Integer> getKnownPackageIdsList() throws IllegalAccessException {
        final ArrayList<Integer> knownPackageIds = new ArrayList<>();
        final Field[] allFields = PackageManagerInternal.class.getDeclaredFields();
        for (Field field : allFields) {
            final int modifier = field.getModifiers();
            if (isPublic(modifier) && isStatic(modifier) && isFinal(modifier)
                    && Pattern.matches("PACKAGE(_[A-Z]+)+", field.getName())) {
                knownPackageIds.add(field.getInt(null));
            }
        }
        Collections.sort(knownPackageIds);
        return knownPackageIds;
    }
}
