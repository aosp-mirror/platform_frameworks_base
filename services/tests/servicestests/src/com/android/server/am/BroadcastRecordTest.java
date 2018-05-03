/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.am;

import static org.junit.Assert.assertNull;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test class for {@link BroadcastRecord}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:com.android.server.am.BroadcastRecordTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BroadcastRecordTest extends ActivityTestsBase {

    @Test
    public void testCleanupDisabledPackageReceivers() {
        final int user0 = UserHandle.USER_SYSTEM;
        final int user1 = user0 + 1;
        final String pkgToCleanup = "pkg.a";
        final String pkgOther = "pkg.b";

        // Receivers contain multiple-user (contains [pkg.a@u0, pkg.a@u1, pkg.b@u0, pkg.b@u1]).
        final List<ResolveInfo> receiversM = createReceiverInfos(
                new String[] { pkgToCleanup, pkgOther },
                new int[] { user0, user1 });
        // Receivers only contain one user (contains [pkg.a@u0, pkg.b@u0]).
        final List<ResolveInfo> receiversU0 = excludeReceivers(
                receiversM, null /* packageName */, user1);

        // With given package:
        // Send to all users, cleanup a package of all users.
        final BroadcastRecord recordAllAll = createBroadcastRecord(receiversM, UserHandle.USER_ALL);
        cleanupDisabledPackageReceivers(recordAllAll, pkgToCleanup, UserHandle.USER_ALL);
        assertNull(verifyRemaining(recordAllAll, excludeReceivers(receiversM, pkgToCleanup, -1)));

        // Send to all users, cleanup a package of one user.
        final BroadcastRecord recordAllOne = createBroadcastRecord(receiversM, UserHandle.USER_ALL);
        cleanupDisabledPackageReceivers(recordAllOne, pkgToCleanup, user0);
        assertNull(verifyRemaining(recordAllOne,
                excludeReceivers(receiversM, pkgToCleanup, user0)));

        // Send to one user, cleanup a package of all users.
        final BroadcastRecord recordOneAll = createBroadcastRecord(receiversU0, user0);
        cleanupDisabledPackageReceivers(recordOneAll, pkgToCleanup, UserHandle.USER_ALL);
        assertNull(verifyRemaining(recordOneAll, excludeReceivers(receiversU0, pkgToCleanup, -1)));

        // Send to one user, cleanup a package one user.
        final BroadcastRecord recordOneOne = createBroadcastRecord(receiversU0, user0);
        cleanupDisabledPackageReceivers(recordOneOne, pkgToCleanup, user0);
        assertNull(verifyRemaining(recordOneOne, excludeReceivers(receiversU0, pkgToCleanup, -1)));

        // Without given package (e.g. stop user):
        // Send to all users, cleanup one user.
        final BroadcastRecord recordAllM = createBroadcastRecord(receiversM, UserHandle.USER_ALL);
        cleanupDisabledPackageReceivers(recordAllM, null /* packageName */, user1);
        assertNull(verifyRemaining(recordAllM,
                excludeReceivers(receiversM, null /* packageName */, user1)));

        // Send to one user, cleanup one user.
        final BroadcastRecord recordU0 = createBroadcastRecord(receiversU0, user0);
        cleanupDisabledPackageReceivers(recordU0, null /* packageName */, user0);
        assertNull(verifyRemaining(recordU0, Collections.emptyList()));
    }

    private static void cleanupDisabledPackageReceivers(BroadcastRecord record,
            String packageName, int userId) {
        record.cleanupDisabledPackageReceiversLocked(packageName, null /* filterByClasses */,
                userId, true /* doit */);
    }

    private static String verifyRemaining(BroadcastRecord record,
            List<ResolveInfo> expectedRemainingReceivers) {
        final StringBuilder errorMsg = new StringBuilder();

        for (final Object receiver : record.receivers) {
            final ResolveInfo resolveInfo = (ResolveInfo) receiver;
            final ApplicationInfo appInfo = resolveInfo.activityInfo.applicationInfo;

            boolean foundExpected = false;
            for (final ResolveInfo expectedReceiver : expectedRemainingReceivers) {
                final ApplicationInfo expectedAppInfo =
                        expectedReceiver.activityInfo.applicationInfo;
                if (appInfo.packageName.equals(expectedAppInfo.packageName)
                        && UserHandle.getUserId(appInfo.uid) == UserHandle
                                .getUserId(expectedAppInfo.uid)) {
                    foundExpected = true;
                    break;
                }
            }
            if (!foundExpected) {
                errorMsg.append(appInfo.packageName).append("@")
                        .append('u').append(UserHandle.getUserId(appInfo.uid)).append(' ');
            }
        }

        return errorMsg.length() == 0 ? null
                : errorMsg.insert(0, "Contains unexpected receiver: ").toString();
    }

    private static ResolveInfo createResolveInfo(String packageName, int uid) {
        final ResolveInfo resolveInfo = new ResolveInfo();
        final ActivityInfo activityInfo = new ActivityInfo();
        final ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        appInfo.uid = uid;
        activityInfo.applicationInfo = appInfo;
        resolveInfo.activityInfo = activityInfo;
        return resolveInfo;
    }

    /**
     * Generate (packages.length * userIds.length) receivers.
     */
    private static List<ResolveInfo> createReceiverInfos(String[] packages, int[] userIds) {
        final List<ResolveInfo> receivers = new ArrayList<>();
        for (int i = 0; i < packages.length; i++) {
            for (final int userId : userIds) {
                receivers.add(createResolveInfo(packages[i],
                        UserHandle.getUid(userId, Process.FIRST_APPLICATION_UID + i)));
            }
        }
        return receivers;
    }

    /**
     * Create a new list which filters out item if package name or user id is matched.
     * Null package name or user id < 0 will be considered as don't care.
     */
    private static List<ResolveInfo> excludeReceivers(List<ResolveInfo> receivers,
            String packageName, int userId) {
        final List<ResolveInfo> excludedList = new ArrayList<>();
        for (final ResolveInfo receiver : receivers) {
            if ((packageName != null
                    && !packageName.equals(receiver.activityInfo.applicationInfo.packageName))
                    || (userId > -1 && userId != UserHandle
                            .getUserId(receiver.activityInfo.applicationInfo.uid))) {
                excludedList.add(receiver);
            }
        }
        return excludedList;
    }

    private static BroadcastRecord createBroadcastRecord(List<ResolveInfo> receivers, int userId) {
        return new BroadcastRecord(
                null /* queue */,
                new Intent(),
                null /* callerApp */,
                null  /* callerPackage */,
                0 /* callingPid */,
                0 /* callingUid */,
                false /* callerInstantApp */,
                null /* resolvedType */,
                null /* requiredPermissions */,
                0 /* appOp */,
                null /* options */,
                new ArrayList<>(receivers), // Make a copy to not affect the original list.
                null /* resultTo */,
                0 /* resultCode */,
                null /* resultData */,
                null /* resultExtras */,
                false /* serialized */,
                false /* sticky */,
                false /* initialSticky */,
                userId);
    }
}
