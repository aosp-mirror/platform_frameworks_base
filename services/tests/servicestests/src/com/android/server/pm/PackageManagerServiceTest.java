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

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.File;

// runtest -c com.android.server.pm.PackageManagerServiceTest frameworks-services

@SmallTest
public class PackageManagerServiceTest extends AndroidTestCase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testPackageRemoval() throws Exception {
      class PackageSenderImpl implements PackageSender {
        public void sendPackageBroadcast(final String action, final String pkg,
            final Bundle extras, final int flags, final String targetPkg,
            final IIntentReceiver finishedReceiver, final int[] userIds) {
        }

        public void sendPackageAddedForNewUsers(String packageName,
            boolean sendBootComplete, boolean includeStopped, int appId, int... userIds) {
        }
      }

      PackageSenderImpl sender = new PackageSenderImpl();
      PackageSetting setting = null;
      PackageManagerService.PackageRemovedInfo pri =
          new PackageManagerService.PackageRemovedInfo(sender);

      // Initial conditions: nothing there
      assertNull(pri.removedUsers);
      assertNull(pri.broadcastUsers);

      // populateUsers with nothing leaves nothing
      pri.populateUsers(null, setting);
      assertNull(pri.broadcastUsers);

      // Create a real (non-null) PackageSetting and confirm that the removed
      // users are copied properly
      setting = new PackageSetting("name", "realName", new File("codePath"),
          new File("resourcePath"), "legacyNativeLibraryPathString",
          "primaryCpuAbiString", "secondaryCpuAbiString",
          "cpuAbiOverrideString", 0, 0, 0, "parentPackageName", null, 0,
          null, null);
      pri.populateUsers(new int[] {1, 2, 3, 4, 5}, setting);
      assertNotNull(pri.broadcastUsers);
      assertEquals(5, pri.broadcastUsers.length);

      // Exclude a user
      pri.broadcastUsers = null;
      final int EXCLUDED_USER_ID = 4;
      setting.setInstantApp(true, EXCLUDED_USER_ID);
      pri.populateUsers(new int[] {1, 2, 3, EXCLUDED_USER_ID, 5}, setting);
      assertNotNull(pri.broadcastUsers);
      assertEquals(5 - 1, pri.broadcastUsers.length);

      // TODO: test that sendApplicationHiddenForUser() actually fills in
      // broadcastUsers
    }
}
