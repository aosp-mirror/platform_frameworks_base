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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import android.content.pm.PackageManager;
import android.content.pm.PackageUserState;
import android.content.pm.SuspendDialogInfo;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.pkg.PackageStateUnserialized;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageUserStateTest {

    @Test
    public void testPackageUserState01() {
        final PackageUserState testUserState = new PackageUserState();
        PackageUserState oldUserState;

        oldUserState = new PackageUserState();
        assertThat(testUserState.equals(null), is(false));
        assertThat(testUserState.equals(testUserState), is(true));
        assertThat(testUserState.equals(oldUserState), is(true));

        oldUserState = new PackageUserState();
        oldUserState.appLinkGeneration = 6;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.ceDataInode = 4000L;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.domainVerificationStatus = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.enabled = COMPONENT_ENABLED_STATE_ENABLED;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.hidden = true;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.installed = false;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.notLaunched = true;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.stopped = true;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.suspended = true;
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserState();
        oldUserState.uninstallReason = PackageManager.UNINSTALL_REASON_USER_TYPE;
        assertThat(testUserState.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState02() {
        final PackageUserState testUserState01 = new PackageUserState();
        PackageUserState oldUserState;

        oldUserState = new PackageUserState();
        oldUserState.lastDisableAppCaller = "unit_test";
        assertThat(testUserState01.equals(oldUserState), is(false));

        final PackageUserState testUserState02 = new PackageUserState();
        testUserState02.lastDisableAppCaller = "unit_test";
        assertThat(testUserState02.equals(oldUserState), is(true));

        final PackageUserState testUserState03 = new PackageUserState();
        testUserState03.lastDisableAppCaller = "unit_test_00";
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState03() {
        final PackageUserState oldUserState = new PackageUserState();

        // only new user state has array defined; different
        final PackageUserState testUserState01 = new PackageUserState();
        testUserState01.disabledComponents = new ArraySet<>();
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserState testUserState02 = new PackageUserState();
        oldUserState.disabledComponents = new ArraySet<>();
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserState testUserState03 = new PackageUserState();
        testUserState03.disabledComponents = new ArraySet<>();
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.disabledComponents.add("com.android.unit_test01");
        testUserState03.disabledComponents.add("com.android.unit_test02");
        testUserState03.disabledComponents.add("com.android.unit_test03");
        oldUserState.disabledComponents.add("com.android.unit_test03");
        oldUserState.disabledComponents.add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.disabledComponents.add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.disabledComponents.add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.disabledComponents.add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState04() {
        final PackageUserState oldUserState = new PackageUserState();

        // only new user state has array defined; different
        final PackageUserState testUserState01 = new PackageUserState();
        testUserState01.enabledComponents = new ArraySet<>();
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserState testUserState02 = new PackageUserState();
        oldUserState.enabledComponents = new ArraySet<>();
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserState testUserState03 = new PackageUserState();
        testUserState03.enabledComponents = new ArraySet<>();
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.enabledComponents.add("com.android.unit_test01");
        testUserState03.enabledComponents.add("com.android.unit_test02");
        testUserState03.enabledComponents.add("com.android.unit_test03");
        oldUserState.enabledComponents.add("com.android.unit_test03");
        oldUserState.enabledComponents.add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.enabledComponents.add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.enabledComponents.add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.enabledComponents.add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    private static PackageUserState.SuspendParams createSuspendParams(SuspendDialogInfo dialogInfo,
            PersistableBundle appExtras, PersistableBundle launcherExtras) {
        PackageUserState.SuspendParams obj = PackageUserState.SuspendParams.getInstanceOrNull(
                dialogInfo, appExtras, launcherExtras);
        return obj;
    }

    private static PersistableBundle createPersistableBundle(String lKey, long lValue, String sKey,
            String sValue, String dKey, double dValue) {
        final PersistableBundle result = new PersistableBundle(3);
        if (lKey != null) {
            result.putLong("com.unit_test." + lKey, lValue);
        }
        if (sKey != null) {
            result.putString("com.unit_test." + sKey, sValue);
        }
        if (dKey != null) {
            result.putDouble("com.unit_test." + dKey, dValue);
        }
        return result;
    }

    @Test
    public void testPackageUserState05() {
        final PersistableBundle appExtras1 = createPersistableBundle("appExtraId", 1, null, null,
                null, 0);
        final PersistableBundle appExtras2 = createPersistableBundle("appExtraId", 2, null, null,
                null, 0);

        final PersistableBundle launcherExtras1 = createPersistableBundle(null, 0, "name",
                "launcherExtras1", null, 0);
        final PersistableBundle launcherExtras2 = createPersistableBundle(null, 0, "name",
                "launcherExtras2", null, 0);

        final String suspendingPackage1 = "package1";
        final String suspendingPackage2 = "package2";

        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage1")
                .build();
        final SuspendDialogInfo dialogInfo2 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage2")
                .build();

        final ArrayMap<String, PackageUserState.SuspendParams> paramsMap1 = new ArrayMap<>();
        paramsMap1.put(suspendingPackage1, createSuspendParams(dialogInfo1, appExtras1,
                launcherExtras1));
        final ArrayMap<String, PackageUserState.SuspendParams> paramsMap2 = new ArrayMap<>();
        paramsMap2.put(suspendingPackage2, createSuspendParams(dialogInfo2,
                appExtras2, launcherExtras2));


        final PackageUserState testUserState1 = new PackageUserState();
        testUserState1.suspended = true;
        testUserState1.suspendParams = paramsMap1;

        PackageUserState testUserState2 = new PackageUserState(testUserState1);
        assertThat(testUserState1.equals(testUserState2), is(true));
        testUserState2.suspendParams = paramsMap2;
        // Should not be equal since suspendParams maps are different
        assertThat(testUserState1.equals(testUserState2), is(false));
    }

    @Test
    public void testPackageUserState06() {
        final PackageUserState userState1 = new PackageUserState();
        assertThat(userState1.distractionFlags, is(PackageManager.RESTRICTION_NONE));
        userState1.distractionFlags = PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS;

        final PackageUserState copyOfUserState1 = new PackageUserState(userState1);
        assertThat(userState1.distractionFlags, is(copyOfUserState1.distractionFlags));
        assertThat(userState1.equals(copyOfUserState1), is(true));

        final PackageUserState userState2 = new PackageUserState(userState1);
        userState2.distractionFlags = PackageManager.RESTRICTION_HIDE_NOTIFICATIONS;
        assertThat(userState1.equals(userState2), is(false));
    }

    @Test
    public void testPackageUserState07() {
        final PersistableBundle appExtras1 = createPersistableBundle("appExtraId", 1, null, null,
                null, 0);
        final PersistableBundle appExtras2 = createPersistableBundle("appExtraId", 2, null, null,
                null, 0);

        final PersistableBundle launcherExtras1 = createPersistableBundle(null, 0, "name",
                "launcherExtras1", null, 0);
        final PersistableBundle launcherExtras2 = createPersistableBundle(null, 0, "name",
                "launcherExtras2", null, 0);

        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage1")
                .build();
        final SuspendDialogInfo dialogInfo2 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage2")
                .build();

        final PackageUserState.SuspendParams params1;
        PackageUserState.SuspendParams params2;
        params1 = createSuspendParams(dialogInfo1, appExtras1, launcherExtras1);
        params2 = createSuspendParams(dialogInfo1, appExtras1, launcherExtras1);
        // Everything is same
        assertThat(params1.equals(params2), is(true));

        params2 = createSuspendParams(dialogInfo2, appExtras1, launcherExtras1);
        // DialogInfo is different
        assertThat(params1.equals(params2), is(false));

        params2 = createSuspendParams(dialogInfo1, appExtras2, launcherExtras1);
        // app extras are different
        assertThat(params1.equals(params2), is(false));

        params2 = createSuspendParams(dialogInfo1, appExtras1, launcherExtras2);
        // Launcher extras are different
        assertThat(params1.equals(params2), is(false));

        params2 = createSuspendParams(dialogInfo2, appExtras2, launcherExtras2);
        // Everything is different
        assertThat(params1.equals(params2), is(false));
    }

    /**
     * Test fix for b/149772100.
     */
    private static void assertLastPackageUsageUnset(
            PackageStateUnserialized state) throws Exception {
        for (int i = state.getLastPackageUsageTimeInMills().length - 1; i >= 0; --i) {
            assertEquals(0L, state.getLastPackageUsageTimeInMills()[i]);
        }
    }
    private static void assertLastPackageUsageSet(
            PackageStateUnserialized state, int reason, long value) throws Exception {
        for (int i = state.getLastPackageUsageTimeInMills().length - 1; i >= 0; --i) {
            if (i == reason) {
                assertEquals(value, state.getLastPackageUsageTimeInMills()[i]);
            } else {
                assertEquals(0L, state.getLastPackageUsageTimeInMills()[i]);
            }
        }
    }
    @Test
    public void testPackageUseReasons() throws Exception {
        final PackageStateUnserialized testState1 = new PackageStateUnserialized();
        testState1.setLastPackageUsageTimeInMills(-1, 10L);
        assertLastPackageUsageUnset(testState1);

        final PackageStateUnserialized testState2 = new PackageStateUnserialized();
        testState2.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT, 20L);
        assertLastPackageUsageUnset(testState2);

        final PackageStateUnserialized testState3 = new PackageStateUnserialized();
        testState3.setLastPackageUsageTimeInMills(Integer.MAX_VALUE, 30L);
        assertLastPackageUsageUnset(testState3);

        final PackageStateUnserialized testState4 = new PackageStateUnserialized();
        testState4.setLastPackageUsageTimeInMills(0, 40L);
        assertLastPackageUsageSet(testState4, 0, 40L);

        final PackageStateUnserialized testState5 = new PackageStateUnserialized();
        testState5.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER, 50L);
        assertLastPackageUsageSet(
                testState5, PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER, 50L);

        final PackageStateUnserialized testState6 = new PackageStateUnserialized();
        testState6.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT - 1, 60L);
        assertLastPackageUsageSet(
                testState6, PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT - 1, 60L);
    }
}
