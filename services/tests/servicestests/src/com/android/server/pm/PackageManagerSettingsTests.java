/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND;
import static android.content.pm.parsing.ParsingPackageUtils.parsePublicKey;
import static android.content.res.Resources.ID_NULL;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.PropertyInvalidatedCache;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageUserState;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.os.BaseBundle;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.LongSparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.permission.persistence.RuntimePermissionsPersistence;
import com.android.server.LocalServices;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.permission.LegacyPermissionDataProvider;
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal;
import com.android.server.utils.WatchableTester;
import com.android.server.utils.WatchedArrayMap;

import com.google.common.truth.Truth;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PackageManagerSettingsTests {
    private static final String TAG = "PackageManagerSettingsTests";
    private static final String PACKAGE_NAME_1 = "com.android.app1";
    private static final String PACKAGE_NAME_2 = "com.android.app2";
    private static final String PACKAGE_NAME_3 = "com.android.app3";
    private static final int TEST_RESOURCE_ID = 2131231283;

    @Mock
    RuntimePermissionsPersistence mRuntimePermissionsPersistence;
    @Mock
    LegacyPermissionDataProvider mPermissionDataProvider;
    @Mock
    DomainVerificationManagerInternal mDomainVerificationManager;

    @Before
    public void initializeMocks() {
        MockitoAnnotations.initMocks(this);
        when(mDomainVerificationManager.generateNewId())
                .thenAnswer(invocation -> UUID.randomUUID());
    }

    @Before
    public void setup() {
        // Disable binder caches in this process.
        PropertyInvalidatedCache.disableForTestMode();
    }

    /** make sure our initialized KeySetManagerService metadata matches packages.xml */
    @Test
    public void testReadKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        /* write out files and read */
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        verifyKeySetMetaData(settings);
    }

    /** read in data, write it out, and read it back in.  Verify same. */
    @Test
    public void testWriteKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {
        // write out files and read
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(createFakeUsers()), is(true));

        // write out, read back in and verify the same
        settings.writeLPr();
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        verifyKeySetMetaData(settings);
    }

    @Test
    public void testSettingsReadOld() {
        // Write delegateshellthe package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        assertThat(settings.getPackageLPr(PACKAGE_NAME_3), is(notNullValue()));
        assertThat(settings.getPackageLPr(PACKAGE_NAME_1), is(notNullValue()));

        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_1);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DEFAULT));
        assertThat(ps.getNotLaunched(0), is(true));

        ps = settings.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getStopped(0), is(false));
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    @Test
    public void testNewPackageRestrictionsFile() throws ReflectiveOperationException {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = makeSettings();
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        settings.writeLPr();

        // Create Settings again to make it read from the new files
        settings = makeSettings();
        assertThat(settings.readLPw(createFakeUsers()), is(true));

        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));

        // Verify that the snapshot passes the same test
        Settings snapshot = settings.snapshot();
        ps = snapshot.getPackageLPr(PACKAGE_NAME_2);
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED_USER));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_DEFAULT));
    }

    private static PersistableBundle createPersistableBundle(String packageName, long longVal,
            double doubleVal, boolean boolVal, String textVal) {
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putString(packageName + ".TEXT_VALUE", textVal);
        bundle.putLong(packageName + ".LONG_VALUE", longVal);
        bundle.putBoolean(packageName + ".BOOL_VALUE", boolVal);
        bundle.putDouble(packageName + ".DOUBLE_VALUE", doubleVal);
        return bundle;
    }

    @Test
    public void testReadPackageRestrictions_noSuspendingPackage() {
        writePackageRestrictions_noSuspendingPackageXml(0);
        Settings settingsUnderTest = makeSettings();
        final WatchableTester watcher =
                new WatchableTester(settingsUnderTest, "noSuspendingPackage");
        watcher.register();
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        settingsUnderTest.readPackageRestrictionsLPr(0);
        watcher.verifyChangeReported("put package 1");
        // Collect a snapshot at the midway point (package 2 has not been added)
        final Settings snapshot = settingsUnderTest.snapshot();
        watcher.verifyNoChangeReported("snapshot");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        watcher.verifyChangeReported("put package 2");
        settingsUnderTest.readPackageRestrictionsLPr(0);

        PackageSetting ps1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1);
        PackageUserState packageUserState1 = ps1.readUserState(0);
        assertThat(packageUserState1.suspended, is(true));
        assertThat(packageUserState1.suspendParams.size(), is(1));
        assertThat(packageUserState1.suspendParams.keyAt(0), is("android"));
        assertThat(packageUserState1.suspendParams.valueAt(0), is(nullValue()));

        // Verify that the snapshot returns the same answers
        ps1 = snapshot.mPackages.get(PACKAGE_NAME_1);
        packageUserState1 = ps1.readUserState(0);
        assertThat(packageUserState1.suspended, is(true));
        assertThat(packageUserState1.suspendParams.size(), is(1));
        assertThat(packageUserState1.suspendParams.keyAt(0), is("android"));
        assertThat(packageUserState1.suspendParams.valueAt(0), is(nullValue()));

        PackageSetting ps2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2);
        PackageUserState packageUserState2 = ps2.readUserState(0);
        assertThat(packageUserState2.suspended, is(false));
        assertThat(packageUserState2.suspendParams, is(nullValue()));

        // Verify that the snapshot returns different answers
        ps2 = snapshot.mPackages.get(PACKAGE_NAME_2);
        assertTrue(ps2 == null);
    }

    @Test
    public void testReadPackageRestrictions_noSuspendParamsMap() {
        writePackageRestrictions_noSuspendParamsMapXml(0);
        final Settings settingsUnderTest = makeSettings();
        final WatchableTester watcher =
                new WatchableTester(settingsUnderTest, "noSuspendParamsMap");
        watcher.register();
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        watcher.verifyChangeReported("put package 1");
        settingsUnderTest.readPackageRestrictionsLPr(0);
        watcher.verifyChangeReported("readPackageRestrictions");

        final PackageSetting ps1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1);
        watcher.verifyNoChangeReported("get package 1");
        final PackageUserState packageUserState1 = ps1.readUserState(0);
        watcher.verifyNoChangeReported("readUserState");
        assertThat(packageUserState1.suspended, is(true));
        assertThat(packageUserState1.suspendParams.size(), is(1));
        assertThat(packageUserState1.suspendParams.keyAt(0), is(PACKAGE_NAME_3));
        final PackageUserState.SuspendParams params = packageUserState1.suspendParams.valueAt(0);
        watcher.verifyNoChangeReported("fetch user state");
        assertThat(params, is(notNullValue()));
        assertThat(params.appExtras.size(), is(1));
        assertThat(params.appExtras.getString("app_extra_string"), is("value"));
        assertThat(params.launcherExtras.size(), is(1));
        assertThat(params.launcherExtras.getLong("launcher_extra_long"), is(4L));
        assertThat(params.dialogInfo, is(notNullValue()));
        assertThat(params.dialogInfo.getDialogMessage(), is("Dialog Message"));
        assertThat(params.dialogInfo.getTitleResId(), is(ID_NULL));
        assertThat(params.dialogInfo.getIconResId(), is(TEST_RESOURCE_ID));
        assertThat(params.dialogInfo.getNeutralButtonTextResId(), is(ID_NULL));
        assertThat(params.dialogInfo.getNeutralButtonAction(), is(BUTTON_ACTION_MORE_DETAILS));
        assertThat(params.dialogInfo.getDialogMessageResId(), is(ID_NULL));
    }

    @Test
    public void testReadWritePackageRestrictions_suspendInfo() {
        final Settings settingsUnderTest = makeSettings();
        final WatchableTester watcher = new WatchableTester(settingsUnderTest, "suspendInfo");
        watcher.register();
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        final PackageSetting ps3 = createPackageSetting(PACKAGE_NAME_3);

        final PersistableBundle appExtras1 = createPersistableBundle(
                PACKAGE_NAME_1, 1L, 0.01, true, "appString1");
        final PersistableBundle appExtras2 = createPersistableBundle(
                PACKAGE_NAME_2, 2L, 0.02, true, "appString2");

        final PersistableBundle launcherExtras1 = createPersistableBundle(
                PACKAGE_NAME_1, 10L, 0.1, false, "launcherString1");
        final PersistableBundle launcherExtras2 = createPersistableBundle(
                PACKAGE_NAME_2, 20L, 0.2, false, "launcherString2");

        final SuspendDialogInfo dialogInfo1 = new SuspendDialogInfo.Builder()
                .setIcon(0x11220001)
                .setTitle("String Title")
                .setMessage("1st message")
                .setNeutralButtonText(0x11220003)
                .setNeutralButtonAction(BUTTON_ACTION_MORE_DETAILS)
                .build();
        final SuspendDialogInfo dialogInfo2 = new SuspendDialogInfo.Builder()
                .setIcon(0x22220001)
                .setTitle(0x22220002)
                .setMessage("2nd message")
                .setNeutralButtonText("String button text")
                .setNeutralButtonAction(BUTTON_ACTION_UNSUSPEND)
                .build();

        ps1.addOrUpdateSuspension("suspendingPackage1", dialogInfo1, appExtras1, launcherExtras1,
                0);
        ps1.addOrUpdateSuspension("suspendingPackage2", dialogInfo2, appExtras2, launcherExtras2,
                0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);
        watcher.verifyChangeReported("put package 1");

        ps2.addOrUpdateSuspension("suspendingPackage3", null, appExtras1, null, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);
        watcher.verifyChangeReported("put package 2");

        ps3.removeSuspension("irrelevant", 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, ps3);
        watcher.verifyChangeReported("put package 3");

        settingsUnderTest.writePackageRestrictionsLPr(0);
        watcher.verifyChangeReported("writePackageRestrictions");

        settingsUnderTest.mPackages.clear();
        watcher.verifyChangeReported("clear packages");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        watcher.verifyChangeReported("put package 1");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        watcher.verifyChangeReported("put package 2");
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, createPackageSetting(PACKAGE_NAME_3));
        watcher.verifyChangeReported("put package 3");
        // now read and verify
        settingsUnderTest.readPackageRestrictionsLPr(0);
        watcher.verifyChangeReported("readPackageRestrictions");
        final PackageUserState readPus1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1)
                .readUserState(0);
        watcher.verifyNoChangeReported("package get 1");
        assertThat(readPus1.suspended, is(true));
        assertThat(readPus1.suspendParams.size(), is(2));
        watcher.verifyNoChangeReported("read package param");

        assertThat(readPus1.suspendParams.keyAt(0), is("suspendingPackage1"));
        final PackageUserState.SuspendParams params11 = readPus1.suspendParams.valueAt(0);
        watcher.verifyNoChangeReported("read package param");
        assertThat(params11, is(notNullValue()));
        assertThat(params11.dialogInfo, is(dialogInfo1));
        assertThat(BaseBundle.kindofEquals(params11.appExtras, appExtras1), is(true));
        assertThat(BaseBundle.kindofEquals(params11.launcherExtras, launcherExtras1),
                is(true));
        watcher.verifyNoChangeReported("read package param");

        assertThat(readPus1.suspendParams.keyAt(1), is("suspendingPackage2"));
        final PackageUserState.SuspendParams params12 = readPus1.suspendParams.valueAt(1);
        assertThat(params12, is(notNullValue()));
        assertThat(params12.dialogInfo, is(dialogInfo2));
        assertThat(BaseBundle.kindofEquals(params12.appExtras, appExtras2), is(true));
        assertThat(BaseBundle.kindofEquals(params12.launcherExtras, launcherExtras2),
                is(true));
        watcher.verifyNoChangeReported("read package param");

        final PackageUserState readPus2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2)
                .readUserState(0);
        assertThat(readPus2.suspended, is(true));
        assertThat(readPus2.suspendParams.size(), is(1));
        assertThat(readPus2.suspendParams.keyAt(0), is("suspendingPackage3"));
        final PackageUserState.SuspendParams params21 = readPus2.suspendParams.valueAt(0);
        assertThat(params21, is(notNullValue()));
        assertThat(params21.dialogInfo, is(nullValue()));
        assertThat(BaseBundle.kindofEquals(params21.appExtras, appExtras1), is(true));
        assertThat(params21.launcherExtras, is(nullValue()));
        watcher.verifyNoChangeReported("read package param");

        final PackageUserState readPus3 = settingsUnderTest.mPackages.get(PACKAGE_NAME_3)
                .readUserState(0);
        assertThat(readPus3.suspended, is(false));
        assertThat(readPus3.suspendParams, is(nullValue()));
        watcher.verifyNoChangeReported("package get 3");
    }

    @Test
    public void testPackageRestrictionsSuspendedDefault() {
        final PackageSetting defaultSetting = createPackageSetting(PACKAGE_NAME_1);
        assertThat(defaultSetting.getSuspended(0), is(false));
    }

    @Test
    public void testReadWritePackageRestrictions_distractionFlags() {
        final Settings settingsUnderTest = makeSettings();
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        final PackageSetting ps3 = createPackageSetting(PACKAGE_NAME_3);

        final int distractionFlags1 = PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS;
        ps1.setDistractionFlags(distractionFlags1, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);

        final int distractionFlags2 = PackageManager.RESTRICTION_HIDE_NOTIFICATIONS
                | PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS;
        ps2.setDistractionFlags(distractionFlags2, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);

        final int distractionFlags3 = PackageManager.RESTRICTION_NONE;
        ps3.setDistractionFlags(distractionFlags3, 0);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, ps3);

        settingsUnderTest.writePackageRestrictionsLPr(0);

        settingsUnderTest.mPackages.clear();
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, createPackageSetting(PACKAGE_NAME_1));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, createPackageSetting(PACKAGE_NAME_2));
        settingsUnderTest.mPackages.put(PACKAGE_NAME_3, createPackageSetting(PACKAGE_NAME_3));
        // now read and verify
        settingsUnderTest.readPackageRestrictionsLPr(0);
        final PackageUserState readPus1 = settingsUnderTest.mPackages.get(PACKAGE_NAME_1)
                .readUserState(0);
        assertThat(readPus1.distractionFlags, is(distractionFlags1));

        final PackageUserState readPus2 = settingsUnderTest.mPackages.get(PACKAGE_NAME_2)
                .readUserState(0);
        assertThat(readPus2.distractionFlags, is(distractionFlags2));

        final PackageUserState readPus3 = settingsUnderTest.mPackages.get(PACKAGE_NAME_3)
                .readUserState(0);
        assertThat(readPus3.distractionFlags, is(distractionFlags3));
    }

    @Test
    public void testWriteReadUsesStaticLibraries() {
        final Settings settingsUnderTest = makeSettings();
        final PackageSetting ps1 = createPackageSetting(PACKAGE_NAME_1);
        ps1.appId = Process.FIRST_APPLICATION_UID;
        ps1.pkg = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME_1).hideAsParsed())
                .setUid(ps1.appId)
                .setSystem(true)
                .hideAsFinal();
        final PackageSetting ps2 = createPackageSetting(PACKAGE_NAME_2);
        ps2.appId = Process.FIRST_APPLICATION_UID + 1;
        ps2.pkg = ((ParsedPackage) PackageImpl.forTesting(PACKAGE_NAME_2).hideAsParsed())
                .setUid(ps2.appId)
                .hideAsFinal();

        ps1.usesStaticLibraries = new String[] { "com.example.shared.one" };
        ps1.usesStaticLibrariesVersions = new long[] { 12 };
        ps1.setFlags(ps1.pkgFlags | ApplicationInfo.FLAG_SYSTEM);
        settingsUnderTest.mPackages.put(PACKAGE_NAME_1, ps1);
        assertThat(settingsUnderTest.disableSystemPackageLPw(PACKAGE_NAME_1, false), is(true));

        ps2.usesStaticLibraries = new String[] { "com.example.shared.two" };
        ps2.usesStaticLibrariesVersions = new long[] { 34 };
        settingsUnderTest.mPackages.put(PACKAGE_NAME_2, ps2);

        settingsUnderTest.writeLPr();

        settingsUnderTest.mPackages.clear();
        settingsUnderTest.mDisabledSysPackages.clear();

        assertThat(settingsUnderTest.readLPw(createFakeUsers()), is(true));

        PackageSetting readPs1 = settingsUnderTest.getPackageLPr(PACKAGE_NAME_1);
        PackageSetting readPs2 = settingsUnderTest.getPackageLPr(PACKAGE_NAME_2);

        Truth.assertThat(readPs1).isNotNull();
        Truth.assertThat(readPs1.usesStaticLibraries).isNotNull();
        Truth.assertThat(readPs1.usesStaticLibrariesVersions).isNotNull();
        Truth.assertThat(readPs2).isNotNull();
        Truth.assertThat(readPs2.usesStaticLibraries).isNotNull();
        Truth.assertThat(readPs2.usesStaticLibrariesVersions).isNotNull();

        List<Long> ps1VersionsAsList = new ArrayList<>();
        for (long version : ps1.usesStaticLibrariesVersions) {
            ps1VersionsAsList.add(version);
        }

        List<Long> ps2VersionsAsList = new ArrayList<>();
        for (long version : ps2.usesStaticLibrariesVersions) {
            ps2VersionsAsList.add(version);
        }

        Truth.assertThat(readPs1.usesStaticLibraries).asList()
                .containsExactlyElementsIn(ps1.usesStaticLibraries).inOrder();

        Truth.assertThat(readPs1.usesStaticLibrariesVersions).asList()
                .containsExactlyElementsIn(ps1VersionsAsList).inOrder();

        Truth.assertThat(readPs2.usesStaticLibraries).asList()
                .containsExactlyElementsIn(ps2.usesStaticLibraries).inOrder();

        Truth.assertThat(readPs2.usesStaticLibrariesVersions).asList()
                .containsExactlyElementsIn(ps2VersionsAsList).inOrder();
    }

    @Test
    public void testPackageRestrictionsDistractionFlagsDefault() {
        final PackageSetting defaultSetting = createPackageSetting(PACKAGE_NAME_1);
        assertThat(defaultSetting.getDistractionFlags(0), is(PackageManager.RESTRICTION_NONE));
    }

    @Test
    public void testEnableDisable() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = makeSettings();
        final WatchableTester watcher = new WatchableTester(settings, "testEnableDisable");
        watcher.register();
        assertThat(settings.readLPw(createFakeUsers()), is(true));
        watcher.verifyChangeReported("readLPw");

        // Enable/Disable a package
        PackageSetting ps = settings.getPackageLPr(PACKAGE_NAME_1);
        watcher.verifyNoChangeReported("getPackageLPr");
        assertThat(ps.getEnabled(0), is(not(COMPONENT_ENABLED_STATE_DISABLED)));
        assertThat(ps.getEnabled(1), is(not(COMPONENT_ENABLED_STATE_ENABLED)));
        ps.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0, null);
        watcher.verifyChangeReported("setEnabled DISABLED");
        ps.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 1, null);
        watcher.verifyChangeReported("setEnabled ENABLED");
        assertThat(ps.getEnabled(0), is(COMPONENT_ENABLED_STATE_DISABLED));
        assertThat(ps.getEnabled(1), is(COMPONENT_ENABLED_STATE_ENABLED));
        watcher.verifyNoChangeReported("getEnabled");

        // Enable/Disable a component
        ArraySet<String> components = new ArraySet<String>();
        String component1 = PACKAGE_NAME_1 + "/.Component1";
        components.add(component1);
        ps.setDisabledComponents(components, 0);
        ArraySet<String> componentsDisabled = ps.getDisabledComponents(0);
        assertThat(componentsDisabled.size(), is(1));
        assertThat(componentsDisabled.toArray()[0], is(component1));
        boolean hasEnabled =
                ps.getEnabledComponents(0) != null && ps.getEnabledComponents(1).size() > 0;
        assertThat(hasEnabled, is(false));

        // User 1 should not have any disabled components
        boolean hasDisabled =
                ps.getDisabledComponents(1) != null && ps.getDisabledComponents(1).size() > 0;
        assertThat(hasDisabled, is(false));
        ps.setEnabledComponents(components, 1);
        assertThat(ps.getEnabledComponents(1).size(), is(1));
        hasEnabled = ps.getEnabledComponents(0) != null && ps.getEnabledComponents(0).size() > 0;
        assertThat(hasEnabled, is(false));
    }

    private static final String PACKAGE_NAME = "com.android.bar";
    private static final String REAL_PACKAGE_NAME = "com.android.foo";
    private static final File INITIAL_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-1");
    private static final File UPDATED_CODE_PATH =
            new File(InstrumentationRegistry.getContext().getFilesDir(), "com.android.bar-2");
    private static final long INITIAL_VERSION_CODE = 10023L;
    private static final long UPDATED_VERSION_CODE = 10025L;

    @Test
    public void testPackageStateCopy01() {
        final PackageSetting origPkgSetting01 = new PackageSetting(
                PACKAGE_NAME,
                REAL_PACKAGE_NAME,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_HAS_CODE,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED|ApplicationInfo.PRIVATE_FLAG_HIDDEN,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        final PackageSetting testPkgSetting01 = new PackageSetting(origPkgSetting01);
        verifySettingCopy(origPkgSetting01, testPkgSetting01);
    }

    @Test
    public void testPackageStateCopy02() {
        final PackageSetting origPkgSetting01 = new PackageSetting(
                PACKAGE_NAME /*pkgName*/,
                REAL_PACKAGE_NAME /*realPkgName*/,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_HAS_CODE,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED|ApplicationInfo.PRIVATE_FLAG_HIDDEN,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        final PackageSetting testPkgSetting01 = new PackageSetting(
                PACKAGE_NAME /*pkgName*/,
                REAL_PACKAGE_NAME /*realPkgName*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                null /*primaryCpuAbiString*/,
                null /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                UPDATED_VERSION_CODE,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        testPkgSetting01.copyFrom(origPkgSetting01);
        verifySettingCopy(origPkgSetting01, testPkgSetting01);
    }

    /** Update package */
    @Test
    public void testUpdatePackageSetting01() throws PackageManagerException {
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        testPkgSetting01.setInstalled(false /*installed*/, 0 /*userId*/);
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageSetting oldPkgSetting01 = new PackageSetting(testPkgSetting01);
        Settings.updatePackageSetting(
                testPkgSetting01,
                null /*disabledPkg*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(0);
        verifyUserState(userState, oldUserState, false /*userStateChanged*/, false /*notLaunched*/,
                false /*stopped*/, false /*installed*/);
    }

    /** Update package; package now on /system, install for user '0' */
    @Test
    public void testUpdatePackageSetting02() throws PackageManagerException {
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        testPkgSetting01.setInstalled(false /*installed*/, 0 /*userId*/);
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        final PackageSetting oldPkgSetting01 = new PackageSetting(testPkgSetting01);
        Settings.updatePackageSetting(
                testPkgSetting01,
                null /*disabledPkg*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                ApplicationInfo.FLAG_SYSTEM /*pkgFlags*/,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED /*pkgPrivateFlags*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertThat(testPkgSetting01.pkgFlags, is(ApplicationInfo.FLAG_SYSTEM));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        final PackageUserState oldUserState = oldPkgSetting01.readUserState(0);
        // WARNING: When creating a shallow copy of the PackageSetting we do NOT create
        // new contained objects. For example, this means that changes to the user state
        // in testPkgSetting01 will also change the user state in its copy.
        verifyUserState(userState, oldUserState, false /*userStateChanged*/, false /*notLaunched*/,
                false /*stopped*/, true /*installed*/);
    }

    /** Update package; changing shared user throws exception */
    @Test
    public void testUpdatePackageSetting03() {
        Settings settings = makeSettings();
        final SharedUserSetting testUserSetting01 = createSharedUserSetting(
                settings, "TestUser", 10064, 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/);
        final PackageSetting testPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        try {
            Settings.updatePackageSetting(
                    testPkgSetting01,
                    null /*disabledPkg*/,
                    testUserSetting01 /*sharedUser*/,
                    UPDATED_CODE_PATH /*codePath*/,
                    null /*legacyNativeLibraryPath*/,
                    "arm64-v8a" /*primaryCpuAbi*/,
                    "armeabi" /*secondaryCpuAbi*/,
                    0 /*pkgFlags*/,
                    0 /*pkgPrivateFlags*/,
                    UserManagerService.getInstance(),
                    null /*usesStaticLibraries*/,
                    null /*usesStaticLibrariesVersions*/,
                    null /*mimeGroups*/,
                    UUID.randomUUID());
            fail("Expected a PackageManagerException");
        } catch (PackageManagerException expected) {
        }
    }

    /** Create a new PackageSetting based on an original package setting */
    @Test
    public void testCreateNewSetting01() {
        final PackageSetting originalPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        final PackageSignatures originalSignatures = originalPkgSetting01.signatures;
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                REAL_PACKAGE_NAME,
                originalPkgSetting01 /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                ApplicationInfo.FLAG_SYSTEM /*pkgFlags*/,
                ApplicationInfo.PRIVATE_FLAG_PRIVILEGED /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.getPath(), is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(ApplicationInfo.FLAG_SYSTEM));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(ApplicationInfo.PRIVATE_FLAG_PRIVILEGED));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        // signatures object must be different
        assertNotSame(testPkgSetting01.signatures, originalSignatures);
        assertThat(testPkgSetting01.versionCode, is(UPDATED_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    /** Create a new non-system PackageSetting */
    @Test
    public void testCreateNewSetting02() {
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                INITIAL_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                UserHandle.SYSTEM /*installUser*/,
                true /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.appId, is(0));
        assertThat(testPkgSetting01.getPath(), is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("x86_64"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("x86"));
        assertThat(testPkgSetting01.versionCode, is(INITIAL_VERSION_CODE));
        // by default, the package is considered stopped
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                true /*notLaunched*/, true /*stopped*/, true /*installed*/);
    }

    /** Create PackageSetting for a shared user */
    @Test
    public void testCreateNewSetting03() {
        Settings settings = makeSettings();
        final SharedUserSetting testUserSetting01 = createSharedUserSetting(
                settings, "TestUser", 10064, 0 /*pkgFlags*/, 0 /*pkgPrivateFlags*/);
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                null /*disabledPkg*/,
                null /*realPkgName*/,
                testUserSetting01 /*sharedUser*/,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                INITIAL_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.appId, is(10064));
        assertThat(testPkgSetting01.getPath(), is(INITIAL_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("x86_64"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("x86"));
        assertThat(testPkgSetting01.versionCode, is(INITIAL_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    /** Create a new PackageSetting based on a disabled package setting */
    @Test
    public void testCreateNewSetting04() {
        final PackageSetting disabledPkgSetting01 =
                createPackageSetting(0 /*sharedUserId*/, 0 /*pkgFlags*/);
        disabledPkgSetting01.appId = 10064;
        final PackageSignatures disabledSignatures = disabledPkgSetting01.signatures;
        final PackageSetting testPkgSetting01 = Settings.createNewSetting(
                PACKAGE_NAME,
                null /*originalPkg*/,
                disabledPkgSetting01 /*disabledPkg*/,
                null /*realPkgName*/,
                null /*sharedUser*/,
                UPDATED_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPath*/,
                "arm64-v8a" /*primaryCpuAbi*/,
                "armeabi" /*secondaryCpuAbi*/,
                UPDATED_VERSION_CODE /*versionCode*/,
                0 /*pkgFlags*/,
                0 /*pkgPrivateFlags*/,
                null /*installUser*/,
                false /*allowInstall*/,
                false /*instantApp*/,
                false /*virtualPreload*/,
                UserManagerService.getInstance(),
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
        assertThat(testPkgSetting01.appId, is(10064));
        assertThat(testPkgSetting01.getPath(), is(UPDATED_CODE_PATH));
        assertThat(testPkgSetting01.name, is(PACKAGE_NAME));
        assertThat(testPkgSetting01.pkgFlags, is(0));
        assertThat(testPkgSetting01.pkgPrivateFlags, is(0));
        assertThat(testPkgSetting01.primaryCpuAbiString, is("arm64-v8a"));
        assertThat(testPkgSetting01.secondaryCpuAbiString, is("armeabi"));
        assertNotSame(testPkgSetting01.signatures, disabledSignatures);
        assertThat(testPkgSetting01.versionCode, is(UPDATED_VERSION_CODE));
        final PackageUserState userState = testPkgSetting01.readUserState(0);
        verifyUserState(userState, null /*oldUserState*/, false /*userStateChanged*/,
                false /*notLaunched*/, false /*stopped*/, true /*installed*/);
    }

    private <T> void assertArrayEquals(T[] a, T[] b) {
        assertTrue("Expected: " + Arrays.toString(a) + ", actual: " + Arrays.toString(b),
                Arrays.equals(a, b));
    }

    private void assertArrayEquals(int[] a, int[] b) {
        assertTrue("Expected: " + Arrays.toString(a) + ", actual: " + Arrays.toString(b),
                Arrays.equals(a, b));
    }

    private void assertArrayEquals(long[] a, long[] b) {
        assertTrue("Expected: " + Arrays.toString(a) + ", actual: " + Arrays.toString(b),
                Arrays.equals(a, b));
    }

    private void verifyUserState(PackageUserState userState, PackageUserState oldUserState,
            boolean userStateChanged) {
        verifyUserState(userState, oldUserState, userStateChanged, false /*notLaunched*/,
                false /*stopped*/, true /*installed*/);
    }

    private void verifyUserState(PackageUserState userState, PackageUserState oldUserState,
            boolean userStateChanged, boolean notLaunched, boolean stopped, boolean installed) {
        assertThat(userState.enabled, is(0));
        assertThat(userState.hidden, is(false));
        assertThat(userState.installed, is(installed));
        assertThat(userState.notLaunched, is(notLaunched));
        assertThat(userState.stopped, is(stopped));
        assertThat(userState.suspended, is(false));
        assertThat(userState.distractionFlags, is(0));
        if (oldUserState != null) {
            assertThat(userState.equals(oldUserState), is(not(userStateChanged)));
        }
    }

    private void verifySettingCopy(PackageSetting origPkgSetting, PackageSetting testPkgSetting) {
        assertThat(origPkgSetting, is(not(testPkgSetting)));
        assertThat(origPkgSetting.appId, is(testPkgSetting.appId));
        assertSame(origPkgSetting.getPath(), testPkgSetting.getPath());
        assertThat(origPkgSetting.getPath(), is(testPkgSetting.getPath()));
        assertSame(origPkgSetting.getPathString(), testPkgSetting.getPathString());
        assertThat(origPkgSetting.getPathString(), is(testPkgSetting.getPathString()));
        assertSame(origPkgSetting.cpuAbiOverrideString, testPkgSetting.cpuAbiOverrideString);
        assertThat(origPkgSetting.cpuAbiOverrideString, is(testPkgSetting.cpuAbiOverrideString));
        assertThat(origPkgSetting.getDomainSetId(), is(testPkgSetting.getDomainSetId()));
        assertThat(origPkgSetting.firstInstallTime, is(testPkgSetting.firstInstallTime));
        assertSame(origPkgSetting.installSource, testPkgSetting.installSource);
        assertThat(origPkgSetting.installPermissionsFixed,
                is(testPkgSetting.installPermissionsFixed));
        assertSame(origPkgSetting.keySetData, testPkgSetting.keySetData);
        assertThat(origPkgSetting.keySetData, is(testPkgSetting.keySetData));
        assertThat(origPkgSetting.lastUpdateTime, is(testPkgSetting.lastUpdateTime));
        assertSame(origPkgSetting.legacyNativeLibraryPathString,
                testPkgSetting.legacyNativeLibraryPathString);
        assertThat(origPkgSetting.legacyNativeLibraryPathString,
                is(testPkgSetting.legacyNativeLibraryPathString));
        if (origPkgSetting.mimeGroups != null) {
            assertNotSame(origPkgSetting.mimeGroups, testPkgSetting.mimeGroups);
        }
        assertThat(origPkgSetting.mimeGroups, is(testPkgSetting.mimeGroups));
        assertNotSame(origPkgSetting.mLegacyPermissionsState,
                testPkgSetting.mLegacyPermissionsState);
        assertThat(origPkgSetting.mLegacyPermissionsState,
                is(testPkgSetting.mLegacyPermissionsState));
        assertThat(origPkgSetting.name, is(testPkgSetting.name));
        // mOldCodePaths is _not_ copied
        // assertNotSame(origPkgSetting.mOldCodePaths, testPkgSetting.mOldCodePaths);
        // assertThat(origPkgSetting.mOldCodePaths, is(not(testPkgSetting.mOldCodePaths)));
        assertSame(origPkgSetting.pkg, testPkgSetting.pkg);
        // No equals() method for this object
        // assertThat(origPkgSetting.pkg, is(testPkgSetting.pkg));
        assertThat(origPkgSetting.pkgFlags, is(testPkgSetting.pkgFlags));
        assertThat(origPkgSetting.pkgPrivateFlags, is(testPkgSetting.pkgPrivateFlags));
        assertSame(origPkgSetting.primaryCpuAbiString, testPkgSetting.primaryCpuAbiString);
        assertThat(origPkgSetting.primaryCpuAbiString, is(testPkgSetting.primaryCpuAbiString));
        assertThat(origPkgSetting.realName, is(testPkgSetting.realName));
        assertSame(origPkgSetting.secondaryCpuAbiString, testPkgSetting.secondaryCpuAbiString);
        assertThat(origPkgSetting.secondaryCpuAbiString, is(testPkgSetting.secondaryCpuAbiString));
        assertSame(origPkgSetting.sharedUser, testPkgSetting.sharedUser);
        assertThat(origPkgSetting.sharedUser, is(testPkgSetting.sharedUser));
        assertSame(origPkgSetting.signatures, testPkgSetting.signatures);
        assertThat(origPkgSetting.signatures, is(testPkgSetting.signatures));
        assertThat(origPkgSetting.timeStamp, is(testPkgSetting.timeStamp));
        assertNotSame(origPkgSetting.getUserState(), is(testPkgSetting.getUserState()));
        // No equals() method for SparseArray object
        // assertThat(origPkgSetting.getUserState(), is(testPkgSetting.getUserState()));
        assertThat(origPkgSetting.versionCode, is(testPkgSetting.versionCode));
        assertSame(origPkgSetting.volumeUuid, testPkgSetting.volumeUuid);
        assertThat(origPkgSetting.volumeUuid, is(testPkgSetting.volumeUuid));
    }

    private SharedUserSetting createSharedUserSetting(Settings settings, String userName,
            int sharedUserId, int pkgFlags, int pkgPrivateFlags) {
        return settings.addSharedUserLPw(
                userName,
                sharedUserId,
                pkgFlags,
                pkgPrivateFlags);
    }
    private PackageSetting createPackageSetting(int sharedUserId, int pkgFlags) {
        return new PackageSetting(
                PACKAGE_NAME,
                REAL_PACKAGE_NAME,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                pkgFlags,
                0 /*privateFlags*/,
                sharedUserId,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
    }

    private PackageSetting createPackageSetting(String packageName) {
        return new PackageSetting(
                packageName,
                packageName,
                INITIAL_CODE_PATH /*codePath*/,
                null /*legacyNativeLibraryPathString*/,
                "x86_64" /*primaryCpuAbiString*/,
                "x86" /*secondaryCpuAbiString*/,
                null /*cpuAbiOverrideString*/,
                INITIAL_VERSION_CODE,
                0,
                0 /*privateFlags*/,
                0,
                null /*usesStaticLibraries*/,
                null /*usesStaticLibrariesVersions*/,
                null /*mimeGroups*/,
                UUID.randomUUID());
    }

    private @NonNull List<UserInfo> createFakeUsers() {
        ArrayList<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(UserHandle.USER_SYSTEM, "test user", UserInfo.FLAG_INITIALIZED));
        return users;
    }

    private void writeFile(File file, byte[] data) {
        file.mkdirs();
        try {
            AtomicFile aFile = new AtomicFile(file);
            FileOutputStream fos = aFile.startWrite();
            fos.write(data);
            aFile.finishWrite(fos);
        } catch (IOException ioe) {
            Log.e(TAG, "Cannot write file " + file.getPath());
        }
    }

    private void writePackagesXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<packages>"
                + "<last-platform-version internal=\"15\" external=\"0\" fingerprint=\"foo\" />"
                + "<permission-trees>"
                + "<item name=\"com.google.android.permtree\" package=\"com.google.android.permpackage\" />"
                + "</permission-trees>"
                + "<permissions>"
                + "<item name=\"android.permission.WRITE_CALL_LOG\" package=\"android\" protection=\"1\" />"
                + "<item name=\"android.permission.ASEC_ACCESS\" package=\"android\" protection=\"2\" />"
                + "<item name=\"android.permission.REBOOT\" package=\"android\" protection=\"18\" />"
                + "</permissions>"
                + "<package name=\"com.android.app1\" codePath=\"/system/app/app1.apk\" nativeLibraryPath=\"/data/data/com.android.app1/lib\" flags=\"1\" ft=\"1360e2caa70\" it=\"135f2f80d08\" ut=\"1360e2caa70\" version=\"1109\" sharedUserId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" key=\"" + KeySetStrings.ctsKeySetCertA + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "</package>"
                + "<package name=\"com.android.app2\" codePath=\"/system/app/app2.apk\" nativeLibraryPath=\"/data/data/com.android.app2/lib\" flags=\"1\" ft=\"1360e578718\" it=\"135f2f80d08\" ut=\"1360e578718\" version=\"15\" enabled=\"3\" userId=\"11001\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "<defined-keyset alias=\"AB\" identifier=\"4\" />"
                + "</package>"
                + "<package name=\"com.android.app3\" codePath=\"/system/app/app3.apk\" nativeLibraryPath=\"/data/data/com.android.app3/lib\" flags=\"1\" ft=\"1360e577b60\" it=\"135f2f80d08\" ut=\"1360e577b60\" version=\"15\" userId=\"11030\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" key=\"" + KeySetStrings.ctsKeySetCertB + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"2\" />"
                + "<upgrade-keyset identifier=\"3\" />"
                + "<defined-keyset alias=\"C\" identifier=\"3\" />"
                + "</package>"
                + "<shared-user name=\"com.android.shared1\" userId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" />"
                + "</sigs>"
                + "<perms>"
                + "<item name=\"android.permission.REBOOT\" />"
                + "</perms>"
                + "</shared-user>"
                + "<keyset-settings version=\"1\">"
                + "<keys>"
                + "<public-key identifier=\"1\" value=\"" + KeySetStrings.ctsKeySetPublicKeyA + "\" />"
                + "<public-key identifier=\"2\" value=\"" + KeySetStrings.ctsKeySetPublicKeyB + "\" />"
                + "<public-key identifier=\"3\" value=\"" + KeySetStrings.ctsKeySetPublicKeyC + "\" />"
                + "</keys>"
                + "<keysets>"
                + "<keyset identifier=\"1\">"
                + "<key-id identifier=\"1\" />"
                + "</keyset>"
                + "<keyset identifier=\"2\">"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "<keyset identifier=\"3\">"
                + "<key-id identifier=\"3\" />"
                + "</keyset>"
                + "<keyset identifier=\"4\">"
                + "<key-id identifier=\"1\" />"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "</keysets>"
                + "<lastIssuedKeyId value=\"3\" />"
                + "<lastIssuedKeySetId value=\"4\" />"
                + "</keyset-settings>"
                + "</packages>").getBytes());
    }

    private void writePackageRestrictions_noSuspendingPackageXml(final int userId) {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/users/"
                        + userId + "/package-restrictions.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<package-restrictions>\n"
                        + "    <pkg name=\"" + PACKAGE_NAME_1 + "\" suspended=\"true\" />"
                        + "    <pkg name=\"" + PACKAGE_NAME_2 + "\" suspended=\"false\" />"
                        + "    <preferred-activities />\n"
                        + "    <persistent-preferred-activities />\n"
                        + "    <crossProfile-intent-filters />\n"
                        + "    <default-apps />\n"
                        + "</package-restrictions>\n")
                        .getBytes());
    }

    private void writePackageRestrictions_noSuspendParamsMapXml(final int userId) {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/users/"
                        + userId + "/package-restrictions.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                        + "<package-restrictions>\n"
                        + "    <pkg name=\"" + PACKAGE_NAME_1 + "\" "
                        + "     suspended=\"true\" suspending-package=\"" + PACKAGE_NAME_3 + "\">\n"
                        + "        <suspended-dialog-info dialogMessage=\"Dialog Message\""
                        + "         iconResId=\"" + TEST_RESOURCE_ID + "\"/>\n"
                        + "        <suspended-app-extras>\n"
                        + "            <string name=\"app_extra_string\">value</string>\n"
                        + "        </suspended-app-extras>\n"
                        + "        <suspended-launcher-extras>\n"
                        + "            <long name=\"launcher_extra_long\" value=\"4\" />\n"
                        + "        </suspended-launcher-extras>\n"
                        + "    </pkg>\n"
                        + "    <preferred-activities />\n"
                        + "    <persistent-preferred-activities />\n"
                        + "    <crossProfile-intent-filters />\n"
                        + "    <default-apps />\n"
                        + "</package-restrictions>\n")
                        .getBytes());
    }

    private void writeStoppedPackagesXml() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages-stopped.xml"),
                ( "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<stopped-packages>"
                + "<pkg name=\"com.android.app1\" nl=\"1\" />"
                + "<pkg name=\"com.android.app3\" nl=\"1\" />"
                + "</stopped-packages>")
                .getBytes());
    }

    private void writePackagesList() {
        writeFile(new File(InstrumentationRegistry.getContext().getFilesDir(), "system/packages.list"),
                ( "com.android.app1 11000 0 /data/data/com.android.app1 seinfo1"
                + "com.android.app2 11001 0 /data/data/com.android.app2 seinfo2"
                + "com.android.app3 11030 0 /data/data/com.android.app3 seinfo3")
                .getBytes());
    }

    private void deleteSystemFolder() {
        File systemFolder = new File(InstrumentationRegistry.getContext().getFilesDir(), "system");
        deleteFolder(systemFolder);
    }

    private static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }

    private void writeOldFiles() {
        deleteSystemFolder();
        writePackagesXml();
        writeStoppedPackagesXml();
        writePackagesList();
    }

    @Before
    public void createUserManagerServiceRef() throws ReflectiveOperationException {
        InstrumentationRegistry.getInstrumentation().runOnMainSync((Runnable) () -> {
            try {
                // unregister the user manager from the local service
                LocalServices.removeServiceForTest(UserManagerInternal.class);
                new UserManagerService(InstrumentationRegistry.getContext());
            } catch (Exception e) {
                e.printStackTrace();
                fail("Could not create user manager service; " + e);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        deleteFolder(InstrumentationRegistry.getTargetContext().getFilesDir());
    }

    private Settings makeSettings() {
        return new Settings(InstrumentationRegistry.getContext().getFilesDir(),
                mRuntimePermissionsPersistence, mPermissionDataProvider,
                mDomainVerificationManager, new PackageManagerTracedLock());
    }

    private void verifyKeySetMetaData(Settings settings)
            throws ReflectiveOperationException, IllegalAccessException {
        WatchedArrayMap<String, PackageSetting> packages = settings.mPackages;
        KeySetManagerService ksms = settings.getKeySetManagerService();

        /* verify keyset and public key ref counts */
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 1), is(2));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 2), is(1));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 3), is(1));
        assertThat(KeySetUtils.getKeySetRefCount(ksms, 4), is(1));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 1), is(2));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 2), is(2));
        assertThat(KeySetUtils.getPubKeyRefCount(ksms, 3), is(1));

        /* verify public keys properly read */
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        PublicKey keyC = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyC);
        assertThat(KeySetUtils.getPubKey(ksms, 1), is(keyA));
        assertThat(KeySetUtils.getPubKey(ksms, 2), is(keyB));
        assertThat(KeySetUtils.getPubKey(ksms, 3), is(keyC));

        /* verify mapping is correct (ks -> pub keys) */
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(ksms);
        ArraySet<Long> mapping = ksMapping.get(1);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(1)), is(true));
        mapping = ksMapping.get(2);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(2)), is(true));
        mapping = ksMapping.get(3);
        assertThat(mapping.size(), is(1));
        assertThat(mapping.contains(new Long(3)), is(true));
        mapping = ksMapping.get(4);
        assertThat(mapping.size(), is(2));
        assertThat(mapping.contains(new Long(1)), is(true));
        assertThat(mapping.contains(new Long(2)), is(true));

        /* verify lastIssuedIds are consistent */
        assertThat(KeySetUtils.getLastIssuedKeyId(ksms), is(3L));
        assertThat(KeySetUtils.getLastIssuedKeySetId(ksms), is(4L));

        /* verify packages have been given the appropriate information */
        PackageSetting ps = packages.get("com.android.app1");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(1L));
        ps = packages.get("com.android.app2");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(1L));
        assertThat(ps.keySetData.getAliases().get("AB"), is(4L));
        ps = packages.get("com.android.app3");
        assertThat(ps.keySetData.getProperSigningKeySet(), is(2L));
        assertThat(ps.keySetData.getAliases().get("C"), is(3L));
        assertThat(ps.keySetData.getUpgradeKeySets().length, is(1));
        assertThat(ps.keySetData.getUpgradeKeySets()[0], is(3L));
    }
}
