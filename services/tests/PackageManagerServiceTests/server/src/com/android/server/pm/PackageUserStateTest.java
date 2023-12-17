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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserPackage;
import android.content.pm.overlay.OverlayPaths;
import android.os.PersistableBundle;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.pm.pkg.ArchiveState;
import com.android.server.pm.pkg.PackageStateUnserialized;
import com.android.server.pm.pkg.PackageUserStateImpl;
import com.android.server.pm.pkg.SuspendParams;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageUserStateTest {

    @Test
    public void testPackageUserState01() {
        final PackageUserStateImpl testUserState = new PackageUserStateImpl();
        PackageUserStateImpl oldUserState;

        oldUserState = new PackageUserStateImpl();
        assertThat(testUserState.equals(null), is(false));
        assertThat(testUserState.equals(testUserState), is(true));
        assertThat(testUserState.equals(oldUserState), is(true));

        oldUserState = new PackageUserStateImpl();
        oldUserState.setCeDataInode(4000L);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateImpl();
        oldUserState.setEnabledState(COMPONENT_ENABLED_STATE_ENABLED);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateImpl();
        oldUserState.setHidden(true);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateImpl();
        oldUserState.setInstalled(false);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateImpl();
        oldUserState.setNotLaunched(true);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateImpl();
        oldUserState.setStopped(true);
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateImpl();
        oldUserState.putSuspendParams(UserPackage.of(0, "suspendingPackage"),
                new SuspendParams(null, new PersistableBundle(), null));
        assertThat(testUserState.equals(oldUserState), is(false));

        oldUserState = new PackageUserStateImpl();
        oldUserState.setUninstallReason(PackageManager.UNINSTALL_REASON_USER_TYPE);
        assertThat(testUserState.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState02() {
        final PackageUserStateImpl testUserState01 = new PackageUserStateImpl();
        PackageUserStateImpl oldUserState;

        oldUserState = new PackageUserStateImpl();
        oldUserState.setLastDisableAppCaller("unit_test");
        assertThat(testUserState01.equals(oldUserState), is(false));

        final PackageUserStateImpl testUserState02 = new PackageUserStateImpl();
        testUserState02.setLastDisableAppCaller("unit_test");
        assertThat(testUserState02.equals(oldUserState), is(true));

        final PackageUserStateImpl testUserState03 = new PackageUserStateImpl();
        testUserState03.setLastDisableAppCaller("unit_test_00");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState03() {
        final PackageUserStateImpl oldUserState = new PackageUserStateImpl();

        // only new user state has array defined; different
        final PackageUserStateImpl testUserState01 = new PackageUserStateImpl();
        testUserState01.setDisabledComponents(new ArraySet<>());
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserStateImpl testUserState02 = new PackageUserStateImpl();
        oldUserState.setDisabledComponents(new ArraySet<>());
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserStateImpl testUserState03 = new PackageUserStateImpl();
        testUserState03.setDisabledComponents(new ArraySet<>());
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test01");
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test02");
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.getDisabledComponentsNoCopy().add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.getDisabledComponentsNoCopy().add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    @Test
    public void testPackageUserState04() {
        final PackageUserStateImpl oldUserState = new PackageUserStateImpl();

        // only new user state has array defined; different
        final PackageUserStateImpl testUserState01 = new PackageUserStateImpl();
        testUserState01.setEnabledComponents(new ArraySet<>());
        assertThat(testUserState01.equals(oldUserState), is(false));

        // only old user state has array defined; different
        final PackageUserStateImpl testUserState02 = new PackageUserStateImpl();
        oldUserState.setEnabledComponents(new ArraySet<>());
        assertThat(testUserState02.equals(oldUserState), is(false));

        // both states have array defined; not different
        final PackageUserStateImpl testUserState03 = new PackageUserStateImpl();
        testUserState03.setEnabledComponents(new ArraySet<>());
        assertThat(testUserState03.equals(oldUserState), is(true));
        // fewer elements in old user state; different
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test01");
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test02");
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test03");
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test02");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // same elements in old user state; not different
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test01");
        assertThat(testUserState03.equals(oldUserState), is(true));
        // more elements in old user state; different
        oldUserState.getEnabledComponentsNoCopy().add("com.android.unit_test04");
        assertThat(testUserState03.equals(oldUserState), is(false));
        // different elements in old user state; different
        testUserState03.getEnabledComponentsNoCopy().add("com.android.unit_test_04");
        assertThat(testUserState03.equals(oldUserState), is(false));
    }

    private static SuspendParams createSuspendParams(SuspendDialogInfo dialogInfo,
            PersistableBundle appExtras, PersistableBundle launcherExtras) {
        return new SuspendParams(dialogInfo, appExtras, launcherExtras);
    }

    private static PersistableBundle createPersistableBundle(
            String lKey, long lValue, String sKey, String sValue, String dKey, double dValue) {
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

        final int suspendingUser1 = 0;
        final int suspendingUser2 = 10;
        final String suspendingPackage1 = "package1";
        final String suspendingPackage2 = "package2";

        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage1")
                .build();
        final SuspendDialogInfo dialogInfo2 = new SuspendDialogInfo.Builder()
                .setMessage("dialogMessage2")
                .build();

        final ArrayMap<UserPackage, SuspendParams> paramsMap1 = new ArrayMap<>();
        paramsMap1.put(UserPackage.of(suspendingUser1, suspendingPackage1),
                createSuspendParams(dialogInfo1, appExtras1, launcherExtras1));
        final ArrayMap<UserPackage, SuspendParams> paramsMap2 = new ArrayMap<>();
        paramsMap2.put(UserPackage.of(suspendingUser2, suspendingPackage2),
                createSuspendParams(dialogInfo2, appExtras2, launcherExtras2));


        final PackageUserStateImpl testUserState1 = new PackageUserStateImpl();
        testUserState1.setSuspendParams(paramsMap1);

        PackageUserStateImpl testUserState2 =
                new PackageUserStateImpl(null, testUserState1);
        assertThat(testUserState1.equals(testUserState2), is(true));
        try {
            testUserState2.setSuspendParams(paramsMap2);
            Assert.fail("Changing sealed snapshot of suspendParams should throw");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage().contains("attempt to change a sealed object"), is(true));
        }
    }

    @Test
    public void testPackageUserState06() {
        final PackageUserStateImpl userState1 = new PackageUserStateImpl();
        assertThat(userState1.getDistractionFlags(), is(PackageManager.RESTRICTION_NONE));
        userState1.setDistractionFlags(PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS);

        final PackageUserStateImpl copyOfUserState1 =
                new PackageUserStateImpl(null, userState1);
        assertThat(userState1.getDistractionFlags(), is(copyOfUserState1.getDistractionFlags()));
        assertThat(userState1.equals(copyOfUserState1), is(true));

        final PackageUserStateImpl userState2 =
                new PackageUserStateImpl(null, userState1);
        userState2.setDistractionFlags(PackageManager.RESTRICTION_HIDE_NOTIFICATIONS);
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

        final SuspendParams params1;
        SuspendParams params2;
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
        PackageSetting packageSetting = Mockito.mock(PackageSetting.class);
        final PackageStateUnserialized testState1 = new PackageStateUnserialized(packageSetting);
        testState1.setLastPackageUsageTimeInMills(-1, 10L);
        assertLastPackageUsageUnset(testState1);

        final PackageStateUnserialized testState2 = new PackageStateUnserialized(packageSetting);
        testState2.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT, 20L);
        assertLastPackageUsageUnset(testState2);

        final PackageStateUnserialized testState3 = new PackageStateUnserialized(packageSetting);
        testState3.setLastPackageUsageTimeInMills(Integer.MAX_VALUE, 30L);
        assertLastPackageUsageUnset(testState3);

        final PackageStateUnserialized testState4 = new PackageStateUnserialized(packageSetting);
        testState4.setLastPackageUsageTimeInMills(0, 40L);
        assertLastPackageUsageSet(testState4, 0, 40L);

        final PackageStateUnserialized testState5 = new PackageStateUnserialized(packageSetting);
        testState5.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER, 50L);
        assertLastPackageUsageSet(
                testState5, PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER, 50L);

        final PackageStateUnserialized testState6 = new PackageStateUnserialized(packageSetting);
        testState6.setLastPackageUsageTimeInMills(
                PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT - 1, 60L);
        assertLastPackageUsageSet(
                testState6, PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT - 1, 60L);
    }

    @Test
    public void testOverlayPaths() {
        final PackageUserStateImpl testState = new PackageUserStateImpl();
        assertFalse(testState.setOverlayPaths(null));
        assertFalse(testState.setOverlayPaths(new OverlayPaths.Builder().build()));

        assertTrue(testState.setOverlayPaths(new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));
        assertFalse(testState.setOverlayPaths(new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));

        assertTrue(testState.setOverlayPaths(new OverlayPaths.Builder().build()));
        assertFalse(testState.setOverlayPaths(null));
    }

    @Test
    public void testSharedLibOverlayPaths() {
        final PackageUserStateImpl testState = new PackageUserStateImpl();
        final String LIB_ONE = "lib1";
        final String LIB_TW0 = "lib2";
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE, null));
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE,
                new OverlayPaths.Builder().build()));

        assertTrue(testState.setSharedLibraryOverlayPaths(LIB_ONE, new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE, new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));
        assertTrue(testState.setSharedLibraryOverlayPaths(LIB_TW0, new OverlayPaths.Builder()
                .addApkPath("/path/to/some.apk").build()));

        assertTrue(testState.setSharedLibraryOverlayPaths(LIB_ONE,
                new OverlayPaths.Builder().build()));
        assertFalse(testState.setSharedLibraryOverlayPaths(LIB_ONE, null));
    }

    @Test
    public void archiveState() {
        final long currentTimeMillis = System.currentTimeMillis();
        PackageUserStateImpl packageUserState = new PackageUserStateImpl();
        ArchiveState.ArchiveActivityInfo archiveActivityInfo =
                new ArchiveState.ArchiveActivityInfo(
                        "appTitle",
                        new ComponentName("pkg", "class"),
                        Path.of("/path1"),
                        Path.of("/path2"));
        ArchiveState archiveState = new ArchiveState(List.of(archiveActivityInfo),
                "installerTitle");
        packageUserState.setArchiveState(archiveState);
        assertEquals(archiveState, packageUserState.getArchiveState());
        assertTrue(archiveState.getArchiveTimeMillis() > currentTimeMillis);
    }

    @Test
    public void archiveStateWithTimestamp() {
        final long currentTimeMillis = System.currentTimeMillis();
        PackageUserStateImpl packageUserState = new PackageUserStateImpl();
        ArchiveState.ArchiveActivityInfo archiveActivityInfo =
                new ArchiveState.ArchiveActivityInfo(
                        "appTitle",
                        new ComponentName("pkg", "class"),
                        Path.of("/path1"),
                        Path.of("/path2"));
        ArchiveState archiveState = new ArchiveState(List.of(archiveActivityInfo),
                "installerTitle", currentTimeMillis);
        packageUserState.setArchiveState(archiveState);
        assertEquals(archiveState, packageUserState.getArchiveState());
        assertEquals(archiveState.getArchiveTimeMillis(), currentTimeMillis);
    }
}
