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
package com.android.server.notification;

import static com.android.server.notification.ManagedServices.APPROVAL_BY_COMPONENT;
import static com.android.server.notification.ManagedServices.APPROVAL_BY_PACKAGE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.UiServiceTestCase;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ManagedServicesTest extends UiServiceTestCase {

    @Mock
    private IPackageManager mIpm;
    @Mock
    private PackageManager mPm;
    @Mock
    private UserManager mUm;
    @Mock
    private ManagedServices.UserProfiles mUserProfiles;
    Object mLock = new Object();

    UserInfo mZero = new UserInfo(0, "zero", 0);
    UserInfo mTen = new UserInfo(10, "ten", 0);

    private static final String SETTING = "setting";
    private static final String SECONDARY_SETTING = "secondary_setting";

    private ArrayMap<Integer, String> mExpectedPrimaryPackages;
    private ArrayMap<Integer, String> mExpectedPrimaryComponentNames;
    private ArrayMap<Integer, String> mExpectedSecondaryPackages;
    private ArrayMap<Integer, String> mExpectedSecondaryComponentNames;

    // type : user : list of approved
    private ArrayMap<Integer, ArrayMap<Integer, String>> mExpectedPrimary = new ArrayMap<>();
    private ArrayMap<Integer, ArrayMap<Integer, String>> mExpectedSecondary = new ArrayMap<>();

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        getContext().setMockPackageManager(mPm);
        getContext().addMockSystemService(Context.USER_SERVICE, mUm);

        List<UserInfo> users = new ArrayList<>();
        users.add(mZero);
        users.add(mTen);
        users.add(new UserInfo(11, "11", 0));
        users.add(new UserInfo(12, "12", 0));
        for (UserInfo user : users) {
            when(mUm.getUserInfo(eq(user.id))).thenReturn(user);
        }
        when(mUm.getUsers()).thenReturn(users);
        IntArray profileIds = new IntArray();
        profileIds.add(0);
        profileIds.add(11);
        profileIds.add(10);
        profileIds.add(12);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);

        mExpectedPrimaryPackages = new ArrayMap<>();
        mExpectedPrimaryPackages.put(0, "this.is.a.package.name:another.package");
        mExpectedPrimaryPackages.put(10, "this.is.another.package");
        mExpectedPrimaryPackages.put(11, "");
        mExpectedPrimaryPackages.put(12, "bananas!");
        mExpectedPrimaryComponentNames = new ArrayMap<>();
        mExpectedPrimaryComponentNames.put(0, "this.is.a.package.name/Ba:another.package/B1");
        mExpectedPrimaryComponentNames.put(10, "this.is.another.package/M1");
        mExpectedPrimaryComponentNames.put(11, "");
        mExpectedPrimaryComponentNames.put(12, "bananas!/Bananas!");
        mExpectedPrimary.put(APPROVAL_BY_PACKAGE, mExpectedPrimaryPackages);
        mExpectedPrimary.put(APPROVAL_BY_COMPONENT, mExpectedPrimaryComponentNames);

        mExpectedSecondaryComponentNames = new ArrayMap<>();
        mExpectedSecondaryComponentNames.put(0, "secondary/component.Name");
        mExpectedSecondaryComponentNames.put(10,
                "this.is.another.package/with.Component:component/2:package/component2");
        mExpectedSecondaryPackages = new ArrayMap<>();
        mExpectedSecondaryPackages.put(0, "secondary");
        mExpectedSecondaryPackages.put(10,
                "this.is.another.package:component:package");
        mExpectedSecondary.put(APPROVAL_BY_PACKAGE, mExpectedSecondaryPackages);
        mExpectedSecondary.put(APPROVAL_BY_COMPONENT, mExpectedSecondaryComponentNames);
    }

    @Test
    public void testBackupAndRestore_migration() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);

            for (int userId : mExpectedPrimary.get(approvalLevel).keySet()) {
                service.onSettingRestored(
                        service.getConfig().secureSettingName,
                        mExpectedPrimary.get(approvalLevel).get(userId),
                        Build.VERSION_CODES.O, userId);
            }
            verifyExpectedApprovedEntries(service, true);

            for (int userId : mExpectedSecondary.get(approvalLevel).keySet()) {
                service.onSettingRestored(service.getConfig().secondarySettingName,
                        mExpectedSecondary.get(approvalLevel).get(userId), Build.VERSION_CODES.O,
                        userId);
            }
            verifyExpectedApprovedEntries(service);
        }
    }

    @Test
    public void testBackupAndRestore_migration_preO() throws Exception {
        ArrayMap<Integer, String> backupPrimaryPackages = new ArrayMap<>();
        backupPrimaryPackages.put(0, "backup.0:backup:0a");
        backupPrimaryPackages.put(10, "10.backup");
        backupPrimaryPackages.put(11, "eleven");
        backupPrimaryPackages.put(12, "");
        ArrayMap<Integer, String> backupPrimaryComponentNames = new ArrayMap<>();
        backupPrimaryComponentNames.put(0, "backup.first/whatever:a/b");
        backupPrimaryComponentNames.put(10, "again/M1");
        backupPrimaryComponentNames.put(11, "orange/youglad:itisnot/banana");
        backupPrimaryComponentNames.put(12, "");
        ArrayMap<Integer, ArrayMap<Integer, String>> backupPrimary = new ArrayMap<>();
        backupPrimary.put(APPROVAL_BY_PACKAGE, backupPrimaryPackages);
        backupPrimary.put(APPROVAL_BY_COMPONENT, backupPrimaryComponentNames);

        ArrayMap<Integer, String> backupSecondaryComponentNames = new ArrayMap<>();
        backupSecondaryComponentNames.put(0, "secondary.1/component.Name");
        backupSecondaryComponentNames.put(10,
                "this.is.another.package.backup/with.Component:component.backup/2");
        ArrayMap<Integer, String> backupSecondaryPackages = new ArrayMap<>();
        backupSecondaryPackages.put(0, "");
        backupSecondaryPackages.put(10,
                "this.is.another.package.backup:package.backup");
        ArrayMap<Integer, ArrayMap<Integer, String>> backupSecondary = new ArrayMap<>();
        backupSecondary.put(APPROVAL_BY_PACKAGE, backupSecondaryPackages);
        backupSecondary.put(APPROVAL_BY_COMPONENT, backupSecondaryComponentNames);

        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);

            // not an expected flow but a way to get data into the settings
            for (int userId : mExpectedPrimary.get(approvalLevel).keySet()) {
                service.onSettingRestored(
                        service.getConfig().secureSettingName,
                        mExpectedPrimary.get(approvalLevel).get(userId),
                        Build.VERSION_CODES.O, userId);
            }

            for (int userId : mExpectedSecondary.get(approvalLevel).keySet()) {
                service.onSettingRestored(service.getConfig().secondarySettingName,
                        mExpectedSecondary.get(approvalLevel).get(userId), Build.VERSION_CODES.O,
                        userId);
            }

            // actual test
            for (int userId : backupPrimary.get(approvalLevel).keySet()) {
                service.onSettingRestored(
                        service.getConfig().secureSettingName,
                        backupPrimary.get(approvalLevel).get(userId),
                        Build.VERSION_CODES.N_MR1, userId);
            }
            verifyExpectedApprovedEntries(service, true);

            for (int userId : backupSecondary.get(approvalLevel).keySet()) {
                service.onSettingRestored(service.getConfig().secondarySettingName,
                        backupSecondary.get(approvalLevel).get(userId),
                        Build.VERSION_CODES.N_MR1, userId);
            }
            verifyExpectedApprovedEntries(service);
            verifyExpectedApprovedEntries(service, backupPrimary.get(approvalLevel));
            verifyExpectedApprovedEntries(service, backupSecondary.get(approvalLevel));
        }
    }

    @Test
    public void testReadXml_migrationFromSettings() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);

            // approved services aren't in xml
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new BufferedInputStream(new ByteArrayInputStream(new byte[]{})),
                    null);
            writeExpectedValuesToSettings(approvalLevel);

            service.migrateToXml();

            verifyExpectedApprovedEntries(service);
        }
    }

    @Test
    public void testReadXml() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);

            loadXml(service);

            verifyExpectedApprovedEntries(service);

            int[] invalidUsers = new int[] {98, 99};
            for (int invalidUser : invalidUsers) {
                assertFalse("service type " + service.mApprovalLevel + ":"
                                + invalidUser + " is allowed for user " + invalidUser,
                        service.isPackageOrComponentAllowed(
                                String.valueOf(invalidUser), invalidUser));
            }
        }
    }

    @Test
    public void testReadXml_appendsListOfApprovedComponents() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);

            String preApprovedPackage = "some.random.package";
            String preApprovedComponent = "some.random.package/C1";

            List<String> packages = new ArrayList<>();
            packages.add(preApprovedPackage);
            addExpectedServices(service, packages, 0);

            service.setPackageOrComponentEnabled(preApprovedComponent, 0, true, true);

            loadXml(service);

            verifyExpectedApprovedEntries(service);

            String verifyValue  = (approvalLevel == APPROVAL_BY_COMPONENT)
                    ? preApprovedComponent
                    : preApprovedPackage;
            assertTrue(service.isPackageOrComponentAllowed(verifyValue, 0));
        }
    }

    /** Test that restore ignores the user id attribute and applies the data to the target user. */
    @Test
    public void testReadXml_onlyRestoresForTargetUser() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service =
                    new TestManagedServices(
                            getContext(), mLock, mUserProfiles, mIpm, approvalLevel);
            String testPackage = "user.test.package";
            String testComponent = "user.test.component/C1";
            String resolvedValue =
                    (approvalLevel == APPROVAL_BY_COMPONENT) ? testComponent : testPackage;
            XmlPullParser parser =
                    getParserWithEntries(service, getXmlEntry(resolvedValue, 0, true));

            service.readXml(parser, null, true, 10);

            assertFalse(service.isPackageOrComponentAllowed(resolvedValue, 0));
            assertTrue(service.isPackageOrComponentAllowed(resolvedValue, 10));
        }
    }

    /** Test that backup only writes packages/components that belong to the target user. */
    @Test
    public void testWriteXml_onlyBackupsForTargetUser() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service =
                    new TestManagedServices(
                            getContext(), mLock, mUserProfiles, mIpm, approvalLevel);
            // Set up components.
            String testPackage0 = "user0.test.package";
            String testComponent0 = "user0.test.component/C1";
            String testPackage10 = "user10.test.package";
            String testComponent10 = "user10.test.component/C1";
            String resolvedValue0 =
                    (approvalLevel == APPROVAL_BY_COMPONENT) ? testComponent0 : testPackage0;
            String resolvedValue10 =
                    (approvalLevel == APPROVAL_BY_COMPONENT) ? testComponent10 : testPackage10;
            addExpectedServices(
                    service, Collections.singletonList(service.getPackageName(resolvedValue0)), 0);
            addExpectedServices(
                    service,
                    Collections.singletonList(service.getPackageName(resolvedValue10)),
                    10);
            XmlPullParser parser =
                    getParserWithEntries(
                            service,
                            getXmlEntry(resolvedValue0, 0, true),
                            getXmlEntry(resolvedValue10, 10, true));
            service.readXml(parser, null, false, UserHandle.USER_ALL);

            // Write backup.
            XmlSerializer serializer = new FastXmlSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
            serializer.startDocument(null, true);
            service.writeXml(serializer, true, 10);
            serializer.endDocument();
            serializer.flush();

            // Reset values.
            service.setPackageOrComponentEnabled(resolvedValue0, 0, true, false);
            service.setPackageOrComponentEnabled(resolvedValue10, 10, true, false);

            // Parse backup via restore.
            XmlPullParser restoreParser = Xml.newPullParser();
            restoreParser.setInput(
                    new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())), null);
            restoreParser.nextTag();
            service.readXml(restoreParser, null, true, 10);

            assertFalse(service.isPackageOrComponentAllowed(resolvedValue0, 0));
            assertFalse(service.isPackageOrComponentAllowed(resolvedValue0, 10));
            assertTrue(service.isPackageOrComponentAllowed(resolvedValue10, 10));
        }
    }

    @Test
    public void testWriteXml_trimsMissingServices() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            loadXml(service);

            // remove missing
            mExpectedPrimaryPackages.put(0, "another.package");
            mExpectedPrimaryPackages.remove(12);
            mExpectedPrimaryComponentNames.put(0, "another.package/B1");
            mExpectedPrimaryComponentNames.remove(12);
            mExpectedSecondaryPackages.put(10, "this.is.another.package:component");
            mExpectedSecondaryComponentNames.put(
                    10, "this.is.another.package/with.Component:component/2");

            for (UserInfo userInfo : mUm.getUsers()) {
                List<String> entriesExpectedToHaveServices = new ArrayList<>();
                if (mExpectedPrimary.get(approvalLevel).containsKey(userInfo.id)) {
                    for (String packageOrComponent :
                            mExpectedPrimary.get(approvalLevel).get(userInfo.id).split(":")) {
                        if (!TextUtils.isEmpty(packageOrComponent)) {
                            entriesExpectedToHaveServices.add(
                                    service.getPackageName(packageOrComponent));
                        }
                    }
                }
                if (mExpectedSecondary.get(approvalLevel).containsKey(userInfo.id)) {
                    for (String packageOrComponent :
                            mExpectedSecondary.get(approvalLevel).get(userInfo.id).split(":")) {
                        if (!TextUtils.isEmpty(packageOrComponent)) {
                            entriesExpectedToHaveServices.add(
                                    service.getPackageName(packageOrComponent));
                        }
                    }
                }
                addExpectedServices(service, entriesExpectedToHaveServices, userInfo.id);
            }

            XmlSerializer serializer = new FastXmlSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
            serializer.startDocument(null, true);
            for (UserInfo userInfo : mUm.getUsers()) {
                service.writeXml(serializer, true, userInfo.id);
            }
            serializer.endDocument();
            serializer.flush();

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new BufferedInputStream(
                    new ByteArrayInputStream(baos.toByteArray())), null);
            parser.nextTag();
            for (UserInfo userInfo : mUm.getUsers()) {
                service.readXml(parser, null, true, userInfo.id);
            }

            verifyExpectedApprovedEntries(service);
            assertFalse(service.isPackageOrComponentAllowed("this.is.a.package.name", 0));
            assertFalse(service.isPackageOrComponentAllowed("bananas!", 12));
            assertFalse(service.isPackageOrComponentAllowed("package/component2", 10));
        }
    }

    @Test
    public void testWriteXml_writesSetting() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            loadXml(service);

            XmlSerializer serializer = new FastXmlSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
            serializer.startDocument(null, true);
            service.writeXml(serializer, false, UserHandle.USER_ALL);
            serializer.endDocument();
            serializer.flush();

            for (int userId : mUserProfiles.getCurrentProfileIds().toArray()) {
                List<String> expected =
                        stringToList(mExpectedPrimary.get(approvalLevel).get(userId));
                List<String> actual = stringToList(Settings.Secure.getStringForUser(
                        getContext().getContentResolver(),
                        service.getConfig().secureSettingName, userId));
                assertContentsInAnyOrder(actual, expected);
            }
        }
    }

    @Test
    public void rebindServices_onlyBindsExactMatchesIfComponent() throws Exception {
        // If the primary and secondary lists contain component names, only those components within
        // the package should be matched
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm,
                ManagedServices.APPROVAL_BY_COMPONENT);

        List<String> packages = new ArrayList<>();
        packages.add("package");
        packages.add("anotherPackage");
        addExpectedServices(service, packages, 0);

        // only 2 components are approved per package
        mExpectedPrimaryComponentNames.clear();
        mExpectedPrimaryComponentNames.put(0, "package/C1:package/C2");
        mExpectedSecondaryComponentNames.clear();
        mExpectedSecondaryComponentNames.put(0, "anotherPackage/C1:anotherPackage/C2");

        loadXml(service);

        // verify the 2 components per package are enabled (bound)
        verifyExpectedBoundEntries(service, true);
        verifyExpectedBoundEntries(service, false);

        // verify the last component per package is not enabled/we don't try to bind to it
        for (String pkg : packages) {
            ComponentName unapprovedAdditionalComponent =
                    ComponentName.unflattenFromString(pkg + "/C3");
            assertFalse(
                    service.isComponentEnabledForCurrentProfiles(
                            unapprovedAdditionalComponent));
            verify(mIpm, never()).getServiceInfo(
                    eq(unapprovedAdditionalComponent), anyInt(), anyInt());
        }
    }

    @Test
    public void rebindServices_bindsEverythingInAPackage() throws Exception {
        // If the primary and secondary lists contain packages, all components within those packages
        // should be bound
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_PACKAGE);

        List<String> packages = new ArrayList<>();
        packages.add("package");
        packages.add("packagea");
        addExpectedServices(service, packages, 0);

        // 2 approved packages
        mExpectedPrimaryPackages.clear();
        mExpectedPrimaryPackages.put(0, "package");
        mExpectedSecondaryPackages.clear();
        mExpectedSecondaryPackages.put(0, "packagea");

        loadXml(service);

        // verify the 3 components per package are enabled (bound)
        verifyExpectedBoundEntries(service, true);
        verifyExpectedBoundEntries(service, false);
    }

    @Test
    public void testPackageUninstall_packageNoLongerInApprovedList() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            writeExpectedValuesToSettings(approvalLevel);
            service.migrateToXml();

            mExpectedPrimaryPackages.put(0, "another.package");
            mExpectedPrimaryComponentNames.put(0, "another.package/B1");
            service.onPackagesChanged(true, new String[]{"this.is.a.package.name"}, new int[]{103});

            verifyExpectedApprovedEntries(service);
        }
    }

    @Test
    public void testPackageUninstall_componentNoLongerInApprovedList() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            writeExpectedValuesToSettings(approvalLevel);
            service.migrateToXml();

            mExpectedSecondaryComponentNames.put(10, "component/2");
            mExpectedSecondaryPackages.put(10, "component");
            service.onPackagesChanged(true, new String[]{"this.is.another.package"}, new int[]{
                    UserHandle.PER_USER_RANGE + 1});

            verifyExpectedApprovedEntries(service);
        }
    }

    @Test
    public void testIsPackageAllowed() {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            writeExpectedValuesToSettings(approvalLevel);
            service.migrateToXml();

            verifyExpectedApprovedPackages(service);
        }
    }

    @Test
    public void testUpgradeAppBindsNewServices() throws Exception {
        // If the primary and secondary lists contain component names, only those components within
        // the package should be matched
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm,
                ManagedServices.APPROVAL_BY_PACKAGE);

        List<String> packages = new ArrayList<>();
        packages.add("package");
        addExpectedServices(service, packages, 0);

        // only 2 components are approved per package
        mExpectedPrimaryComponentNames.clear();
        mExpectedPrimaryPackages.clear();
        mExpectedPrimaryComponentNames.put(0, "package/C1:package/C2");
        mExpectedSecondaryComponentNames.clear();
        mExpectedSecondaryPackages.clear();

        loadXml(service);

        // new component expected
        mExpectedPrimaryComponentNames.put(0, "package/C1:package/C2:package/C3");

        service.onPackagesChanged(false, new String[]{"package"}, new int[]{0});

        // verify the 3 components per package are enabled (bound)
        verifyExpectedBoundEntries(service, true);

        // verify the last component per package is not enabled/we don't try to bind to it
        for (String pkg : packages) {
            ComponentName unapprovedAdditionalComponent =
                    ComponentName.unflattenFromString(pkg + "/C3");
            assertFalse(
                    service.isComponentEnabledForCurrentProfiles(
                            unapprovedAdditionalComponent));
            verify(mIpm, never()).getServiceInfo(
                    eq(unapprovedAdditionalComponent), anyInt(), anyInt());
        }
    }

    @Test
    public void testSetPackageOrComponentEnabled() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            ArrayMap<Integer, ArrayList<String>> expectedEnabled = new ArrayMap<>();
            expectedEnabled.put(0,
                    Lists.newArrayList(new String[]{"package/Comp", "package/C2", "again/M4"}));
            expectedEnabled.put(10,
                    Lists.newArrayList(new String[]{"user10package/B", "user10/Component",
                            "user10package1/K", "user10.3/Component", "user10package2/L",
                            "user10.4/Component"}));

            for (int userId : expectedEnabled.keySet()) {
                ArrayList<String> expectedForUser = expectedEnabled.get(userId);
                for (int i = 0; i < expectedForUser.size(); i++) {
                    boolean primary = i % 2 == 0;
                    service.setPackageOrComponentEnabled(expectedForUser.get(i), userId, primary,
                            true);
                }
            }

            // verify everything added is approved
            for (int userId : expectedEnabled.keySet()) {
                ArrayList<String> expectedForUser = expectedEnabled.get(userId);
                for (int i = 0; i < expectedForUser.size(); i++) {
                    String verifyValue  = (approvalLevel == APPROVAL_BY_COMPONENT)
                            ? expectedForUser.get(i)
                            : service.getPackageName(expectedForUser.get(i));
                    assertTrue("Not allowed: user: " + userId + " entry: " + verifyValue
                            + " for approval level " + approvalLevel,
                            service.isPackageOrComponentAllowed(verifyValue, userId));
                }
            }

            ArrayMap<Integer, ArrayList<String>> expectedNoAccess = new ArrayMap<>();
            for (int userId : expectedEnabled.keySet()) {
                ArrayList<String> expectedForUser = expectedEnabled.get(userId);
                for (int i = expectedForUser.size() - 1; i >= 0; i--) {
                    ArrayList<String> removed = new ArrayList<>();
                    if (i % 3 == 0) {
                        String revokeAccessFor = expectedForUser.remove(i);
                        removed.add(revokeAccessFor);
                        service.setPackageOrComponentEnabled(
                                revokeAccessFor, userId, i % 2 == 0, false);
                    }
                    expectedNoAccess.put(userId, removed);
                }
            }

            // verify everything still there is approved
            for (int userId : expectedEnabled.keySet()) {
                ArrayList<String> expectedForUser = expectedEnabled.get(userId);
                for (int i = 0; i < expectedForUser.size(); i++) {
                    String verifyValue  = (approvalLevel == APPROVAL_BY_COMPONENT)
                            ? expectedForUser.get(i)
                            : service.getPackageName(expectedForUser.get(i));
                    assertTrue("Not allowed: user: " + userId + " entry: " + verifyValue,
                            service.isPackageOrComponentAllowed(verifyValue, userId));
                }
            }
            // verify everything removed isn't
            for (int userId : expectedNoAccess.keySet()) {
                ArrayList<String> notExpectedForUser = expectedNoAccess.get(userId);
                for (int i = 0; i < notExpectedForUser.size(); i++) {
                    assertFalse(
                            "Is allowed: user: " + userId + " entry: " + notExpectedForUser.get(i),
                            service.isPackageOrComponentAllowed(notExpectedForUser.get(i), userId));
                }
            }
        }
    }

    @Test
    public void testGetAllowedPackages_byUser() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            loadXml(service);

            List<String> allowedPackagesForUser0 = new ArrayList<>();
            allowedPackagesForUser0.add("this.is.a.package.name");
            allowedPackagesForUser0.add("another.package");
            allowedPackagesForUser0.add("secondary");

            List<String> actual = service.getAllowedPackages(0);
            assertEquals(3, actual.size());
            for (String pkg : allowedPackagesForUser0) {
                assertTrue(actual.contains(pkg));
            }

            List<String> allowedPackagesForUser10 = new ArrayList<>();
            allowedPackagesForUser10.add("this.is.another.package");
            allowedPackagesForUser10.add("package");
            allowedPackagesForUser10.add("this.is.another.package");
            allowedPackagesForUser10.add("component");

            actual = service.getAllowedPackages(10);
            assertEquals(4, actual.size());
            for (String pkg : allowedPackagesForUser10) {
                assertTrue(actual.contains(pkg));
            }
        }
    }

    @Test
    public void testGetAllowedComponentsByUser() throws Exception {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        loadXml(service);

        List<ComponentName> expected = new ArrayList<>();
        expected.add(ComponentName.unflattenFromString("this.is.another.package/M1"));
        expected.add(ComponentName.unflattenFromString("this.is.another.package/with.Component"));
        expected.add(ComponentName.unflattenFromString("component/2"));
        expected.add(ComponentName.unflattenFromString("package/component2"));

        List<ComponentName> actual = service.getAllowedComponents(10);

        assertContentsInAnyOrder(expected, actual);

        assertEquals(expected.size(), actual.size());

        for (ComponentName cn : expected) {
            assertTrue("Actual missing " + cn, actual.contains(cn));
        }

        for (ComponentName cn : actual) {
            assertTrue("Actual contains extra " + cn, expected.contains(cn));
        }
    }

    @Test
    public void testGetAllowedComponents_approvalByPackage() throws Exception {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_PACKAGE);
        loadXml(service);

        assertEquals(0, service.getAllowedComponents(10).size());
    }

    @Test
    public void testGetAllowedPackages() throws Exception {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm, APPROVAL_BY_COMPONENT);
        loadXml(service);
        service.mApprovalLevel = APPROVAL_BY_PACKAGE;
        loadXml(service);

        List<String> allowedPackages = new ArrayList<>();
        allowedPackages.add("this.is.a.package.name");
        allowedPackages.add("another.package");
        allowedPackages.add("secondary");
        allowedPackages.add("this.is.another.package");
        allowedPackages.add("package");
        allowedPackages.add("component");
        allowedPackages.add("bananas!");

        Set<String> actual = service.getAllowedPackages();
        assertEquals(allowedPackages.size(), actual.size());
        for (String pkg : allowedPackages) {
            assertTrue(actual.contains(pkg));
        }
    }

    @Test
    public void testOnUserRemoved() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            loadXml(service);

            ArrayMap<Integer, String> verifyMap = mExpectedPrimary.get(service.mApprovalLevel);
            String user0 = verifyMap.remove(0);
            verifyMap = mExpectedSecondary.get(service.mApprovalLevel);
            user0 = user0 + ":" + verifyMap.remove(0);

            service.onUserRemoved(0);

            for (String verifyValue : user0.split(":")) {
                if (!TextUtils.isEmpty(verifyValue)) {
                    assertFalse("service type " + service.mApprovalLevel + ":" + verifyValue
                            + " is still allowed",
                            service.isPackageOrComponentAllowed(verifyValue, 0));
                }
            }

            verifyExpectedApprovedEntries(service);
        }
    }

    @Test
    public void testIsSameUser() {
        IInterface service = mock(IInterface.class);
        when(service.asBinder()).thenReturn(mock(IBinder.class));
        ManagedServices services = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm, APPROVAL_BY_PACKAGE);
        services.registerService(service, null, 10);
        ManagedServices.ManagedServiceInfo info = services.checkServiceTokenLocked(service);
        info.isSystem = true;

        assertFalse(services.isSameUser(service, 0));
        assertTrue(services.isSameUser(service, 10));
    }

    @Test
    public void testGetAllowedComponents() throws Exception {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        loadXml(service);

        SparseArray<ArraySet<ComponentName>> expected = new SparseArray<>();

        ArraySet<ComponentName> expected10 = new ArraySet<>();
        expected10.add(ComponentName.unflattenFromString("this.is.another.package/M1"));
        expected10.add(ComponentName.unflattenFromString("this.is.another.package/with.Component"));
        expected10.add(ComponentName.unflattenFromString("component/2"));
        expected10.add(ComponentName.unflattenFromString("package/component2"));
        expected.put(10, expected10);
        ArraySet<ComponentName> expected0 = new ArraySet<>();
        expected0.add(ComponentName.unflattenFromString("secondary/component.Name"));
        expected0.add(ComponentName.unflattenFromString("this.is.a.package.name/Ba"));
        expected0.add(ComponentName.unflattenFromString("another.package/B1"));
        expected.put(0, expected0);
        ArraySet<ComponentName> expected12 = new ArraySet<>();
        expected12.add(ComponentName.unflattenFromString("bananas!/Bananas!"));
        expected.put(12, expected12);
        expected.put(11, new ArraySet<>());

        SparseArray<ArraySet<ComponentName>> actual =
                service.getAllowedComponents(mUserProfiles.getCurrentProfileIds());

        assertContentsInAnyOrder(expected, actual);
    }

    @Test
    public void testPopulateComponentsToUnbind_forceRebind() {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);

        IInterface iInterface = mock(IInterface.class);
        when(iInterface.asBinder()).thenReturn(mock(IBinder.class));

        ManagedServices.ManagedServiceInfo service0 = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("a/a"), 0, false,
                mock(ServiceConnection.class), 26);
        ManagedServices.ManagedServiceInfo service10 = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("b/b"), 10, false,
                mock(ServiceConnection.class), 26);
        Set<ManagedServices.ManagedServiceInfo> removableBoundServices = new ArraySet<>();
        removableBoundServices.add(service0);
        removableBoundServices.add(service10);

        SparseArray<Set<ComponentName>> allowedComponentsToBind = new SparseArray<>();
        Set<ComponentName> allowed0 = new ArraySet<>();
        allowed0.add(ComponentName.unflattenFromString("a/a"));
        allowedComponentsToBind.put(0, allowed0);
        Set<ComponentName> allowed10 = new ArraySet<>();
        allowed10.add(ComponentName.unflattenFromString("b/b"));
        allowedComponentsToBind.put(10, allowed10);

        SparseArray<Set<ComponentName>> componentsToUnbind = new SparseArray<>();

        service.populateComponentsToUnbind(true, removableBoundServices, allowedComponentsToBind,
                componentsToUnbind);

        assertEquals(2, componentsToUnbind.size());
        assertTrue(componentsToUnbind.get(0).contains(ComponentName.unflattenFromString("a/a")));
        assertTrue(componentsToUnbind.get(10).contains(ComponentName.unflattenFromString("b/b")));
    }

    @Test
    public void testPopulateComponentsToUnbind() {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);

        IInterface iInterface = mock(IInterface.class);
        when(iInterface.asBinder()).thenReturn(mock(IBinder.class));

        ManagedServices.ManagedServiceInfo service0 = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("a/a"), 0, false,
                mock(ServiceConnection.class), 26);
        ManagedServices.ManagedServiceInfo service0a = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("c/c"), 0, false,
                mock(ServiceConnection.class), 26);
        ManagedServices.ManagedServiceInfo service10 = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("b/b"), 10, false,
                mock(ServiceConnection.class), 26);
        Set<ManagedServices.ManagedServiceInfo> removableBoundServices = new ArraySet<>();
        removableBoundServices.add(service0);
        removableBoundServices.add(service0a);
        removableBoundServices.add(service10);

        SparseArray<Set<ComponentName>> allowedComponentsToBind = new SparseArray<>();
        Set<ComponentName> allowed0 = new ArraySet<>();
        allowed0.add(ComponentName.unflattenFromString("a/a"));
        allowedComponentsToBind.put(0, allowed0);
        Set<ComponentName> allowed10 = new ArraySet<>();
        allowed10.add(ComponentName.unflattenFromString("b/b"));
        allowedComponentsToBind.put(10, allowed10);

        SparseArray<Set<ComponentName>> componentsToUnbind = new SparseArray<>();

        service.populateComponentsToUnbind(false, removableBoundServices, allowedComponentsToBind,
                componentsToUnbind);

        assertEquals(1, componentsToUnbind.size());
        assertEquals(1, componentsToUnbind.get(0).size());
        assertTrue(componentsToUnbind.get(0).contains(ComponentName.unflattenFromString("c/c")));
    }

    @Test
    public void populateComponentsToBind() {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);

        SparseArray<ArraySet<ComponentName>> approvedComponentsByUser = new SparseArray<>();
        ArraySet<ComponentName> allowed0 = new ArraySet<>();
        allowed0.add(ComponentName.unflattenFromString("a/a"));
        approvedComponentsByUser.put(0, allowed0);
        ArraySet<ComponentName> allowed10 = new ArraySet<>();
        allowed10.add(ComponentName.unflattenFromString("b/b"));
        allowed10.add(ComponentName.unflattenFromString("c/c"));
        approvedComponentsByUser.put(10, allowed10);
        ArraySet<ComponentName> allowed15 = new ArraySet<>();
        allowed15.add(ComponentName.unflattenFromString("d/d"));
        approvedComponentsByUser.put(15, allowed15);

        IntArray users = new IntArray();
        users.add(10);
        users.add(0);

        SparseArray<Set<ComponentName>> componentsToBind = new SparseArray<>();

        service.populateComponentsToBind(componentsToBind, users, approvedComponentsByUser);

        assertEquals(2, componentsToBind.size());
        assertEquals(1, componentsToBind.get(0).size());
        assertTrue(componentsToBind.get(0).contains(ComponentName.unflattenFromString("a/a")));
        assertEquals(2, componentsToBind.get(10).size());
        assertTrue(componentsToBind.get(10).contains(ComponentName.unflattenFromString("b/b")));
        assertTrue(componentsToBind.get(10).contains(ComponentName.unflattenFromString("c/c")));
    }

    @Test
    public void testOnPackagesChanged_nullValuesPassed_noNullPointers() {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            // null uid list
            service.onPackagesChanged(true, new String[]{"this.is.a.package.name"}, null);

            // null package list
            service.onPackagesChanged(true, null, new int[]{103});
        }
    }

    private void loadXml(ManagedServices service) throws Exception {
        final StringBuffer xml = new StringBuffer();
        xml.append("<" + service.getConfig().xmlTag + ">\n");
        for (int userId : mExpectedPrimary.get(service.mApprovalLevel).keySet()) {
            xml.append(getXmlEntry(
                    mExpectedPrimary.get(service.mApprovalLevel).get(userId), userId, true));
        }
        for (int userId : mExpectedSecondary.get(service.mApprovalLevel).keySet()) {
            xml.append(getXmlEntry(
                    mExpectedSecondary.get(service.mApprovalLevel).get(userId), userId, false));
        }
        xml.append("<" + ManagedServices.TAG_MANAGED_SERVICES + " "
                        + ManagedServices.ATT_USER_ID + "=\"99\" "
                        + ManagedServices.ATT_IS_PRIMARY + "=\"true\" "
                        + ManagedServices.ATT_APPROVED_LIST + "=\"99\" />\n");
        xml.append("<" + ManagedServices.TAG_MANAGED_SERVICES + " "
                + ManagedServices.ATT_USER_ID + "=\"98\" "
                + ManagedServices.ATT_IS_PRIMARY + "=\"false\" "
                + ManagedServices.ATT_APPROVED_LIST + "=\"98\" />\n");
        xml.append("</" + service.getConfig().xmlTag + ">");

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);
        parser.nextTag();
        service.readXml(parser, null, false, UserHandle.USER_ALL);
    }

    private XmlPullParser getParserWithEntries(ManagedServices service, String... xmlEntries)
            throws Exception {
        final StringBuffer xml = new StringBuffer();
        xml.append("<" + service.getConfig().xmlTag + ">\n");
        for (String xmlEntry : xmlEntries) {
            xml.append(xmlEntry);
        }
        xml.append("</" + service.getConfig().xmlTag + ">");

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);
        parser.nextTag();
        return parser;
    }

    private void addExpectedServices(final ManagedServices service, final List<String> packages,
            int userId) {
        when(mPm.queryIntentServicesAsUser(any(), anyInt(), eq(userId))).
                thenAnswer(new Answer<List<ResolveInfo>>() {
                    @Override
                    public List<ResolveInfo> answer(InvocationOnMock invocationOnMock)
                            throws Throwable {
                        Object[] args = invocationOnMock.getArguments();
                        Intent invocationIntent = (Intent) args[0];
                        if (invocationIntent != null) {
                            if (invocationIntent.getAction().equals(
                                    service.getConfig().serviceInterface)
                                    && packages.contains(invocationIntent.getPackage())) {
                                List<ResolveInfo> dummyServices = new ArrayList<>();
                                for (int i = 1; i <= 3; i ++) {
                                    ResolveInfo resolveInfo = new ResolveInfo();
                                    ServiceInfo serviceInfo = new ServiceInfo();
                                    serviceInfo.packageName = invocationIntent.getPackage();
                                    serviceInfo.name = "C"+i;
                                    serviceInfo.permission = service.getConfig().bindPermission;
                                    resolveInfo.serviceInfo = serviceInfo;
                                    dummyServices.add(resolveInfo);
                                }
                                return dummyServices;
                            }
                        }
                        return new ArrayList<>();
                    }
                });
    }

    private List<String> stringToList(String list) {
        if (list == null) {
            list = "";
        }
        return new ArrayList<>(Lists.newArrayList(list.split(
                ManagedServices.ENABLED_SERVICES_SEPARATOR)));
    }

    private void assertContentsInAnyOrder(Collection<?> expected, Collection<?> actual) {
        assertNotNull(actual);
        assertEquals(expected.size(), actual.size());

        for (Object o : expected) {
            assertTrue("Actual missing " + o, actual.contains(o));
        }

        for (Object o : actual) {
            assertTrue("Actual contains extra " + o, expected.contains(o));
        }
    }

    private void assertContentsInAnyOrder(SparseArray<ArraySet<ComponentName>> expected,
            SparseArray<ArraySet<ComponentName>> actual) throws Exception {
        assertEquals(expected.size(), actual.size());

        for (int i = 0; i < expected.size(); i++) {
            int key = expected.keyAt(i);
            assertTrue(actual.indexOfKey(key) >= 0);
            try {
                assertContentsInAnyOrder(expected.valueAt(i), actual.get(key));
            } catch (Throwable t) {
                throw new Exception("Error validating " + key, t);
            }
        }
    }

    private void verifyExpectedBoundEntries(ManagedServices service, boolean primary)
            throws Exception {
        ArrayMap<Integer, String> verifyMap = primary ? mExpectedPrimary.get(service.mApprovalLevel)
                : mExpectedSecondary.get(service.mApprovalLevel);
        for (int userId : verifyMap.keySet()) {
            for (String packageOrComponent : verifyMap.get(userId).split(":")) {
                if (!TextUtils.isEmpty(packageOrComponent)) {
                    if (service.mApprovalLevel == APPROVAL_BY_PACKAGE) {
                        assertTrue(packageOrComponent,
                                service.isComponentEnabledForPackage(packageOrComponent));
                        for (int i = 1; i <= 3; i++) {
                            ComponentName componentName = ComponentName.unflattenFromString(
                                    packageOrComponent +"/C" + i);
                            assertTrue(service.isComponentEnabledForCurrentProfiles(
                                    componentName));
                            verify(mIpm, times(1)).getServiceInfo(
                                    eq(componentName), anyInt(), anyInt());
                        }
                    } else {
                        ComponentName componentName =
                                ComponentName.unflattenFromString(packageOrComponent);
                        assertTrue(service.isComponentEnabledForCurrentProfiles(componentName));
                        verify(mIpm, times(1)).getServiceInfo(
                                eq(componentName), anyInt(), anyInt());
                    }
                }
            }
        }
    }

    private void verifyExpectedApprovedEntries(ManagedServices service) {
        verifyExpectedApprovedEntries(service, true);
        verifyExpectedApprovedEntries(service, false);
    }

    private void verifyExpectedApprovedEntries(ManagedServices service, boolean primary) {
        ArrayMap<Integer, String> verifyMap = primary
                ? mExpectedPrimary.get(service.mApprovalLevel)
                : mExpectedSecondary.get(service.mApprovalLevel);
        verifyExpectedApprovedEntries(service, verifyMap);
    }

    private void verifyExpectedApprovedEntries(ManagedServices service,
            ArrayMap<Integer, String> verifyMap) {
        for (int userId : verifyMap.keySet()) {
            for (String verifyValue : verifyMap.get(userId).split(":")) {
                if (!TextUtils.isEmpty(verifyValue)) {
                    assertTrue("service type " + service.mApprovalLevel + ":"
                                    + verifyValue + " is not allowed for user " + userId,
                            service.isPackageOrComponentAllowed(verifyValue, userId));
                }
            }
        }
    }


    private void verifyExpectedApprovedPackages(ManagedServices service) {
        verifyExpectedApprovedPackages(service, true);
        verifyExpectedApprovedPackages(service, false);
    }

    private void verifyExpectedApprovedPackages(ManagedServices service, boolean primary) {
        ArrayMap<Integer, String> verifyMap = primary
                ? mExpectedPrimary.get(service.mApprovalLevel)
                : mExpectedSecondary.get(service.mApprovalLevel);
        verifyExpectedApprovedPackages(service, verifyMap);
    }

    private void verifyExpectedApprovedPackages(ManagedServices service,
            ArrayMap<Integer, String> verifyMap) {
        for (int userId : verifyMap.keySet()) {
            for (String verifyValue : verifyMap.get(userId).split(":")) {
                if (!TextUtils.isEmpty(verifyValue)) {
                    ComponentName component = ComponentName.unflattenFromString(verifyValue);
                    if (component != null ) {
                        assertTrue("service type " + service.mApprovalLevel + ":"
                                        + verifyValue + " is not allowed for user " + userId,
                                service.isPackageAllowed(component.getPackageName(), userId));
                    } else {
                        assertTrue("service type " + service.mApprovalLevel + ":"
                                        + verifyValue + " is not allowed for user " + userId,
                                service.isPackageAllowed(verifyValue, userId));
                    }
                }
            }
        }
    }

    private void writeExpectedValuesToSettings(int approvalLevel) {
        for (int userId : mExpectedPrimary.get(approvalLevel).keySet()) {
            Settings.Secure.putStringForUser(getContext().getContentResolver(), SETTING,
                    mExpectedPrimary.get(approvalLevel).get(userId), userId);
        }
        for (int userId : mExpectedSecondary.get(approvalLevel).keySet()) {
            Settings.Secure.putStringForUser(getContext().getContentResolver(), SECONDARY_SETTING,
                    mExpectedSecondary.get(approvalLevel).get(userId), userId);
        }
    }

    private String getXmlEntry(String approved, int userId, boolean isPrimary) {
        return "<" + ManagedServices.TAG_MANAGED_SERVICES + " "
                + ManagedServices.ATT_USER_ID + "=\"" + userId +"\" "
                + ManagedServices.ATT_IS_PRIMARY + "=\"" + isPrimary +"\" "
                + ManagedServices.ATT_APPROVED_LIST + "=\"" + approved +"\" "
                + "/>\n";
    }

    class TestManagedServices extends ManagedServices {

        public TestManagedServices(Context context, Object mutex, UserProfiles userProfiles,
                IPackageManager pm, int approvedServiceType) {
            super(context, mutex, userProfiles, pm);
            mApprovalLevel = approvedServiceType;
        }

        @Override
        protected Config getConfig() {
            final Config c = new Config();
            c.xmlTag = "test";
            c.secureSettingName = SETTING;
            c.secondarySettingName = SECONDARY_SETTING;
            c.bindPermission = "permission";
            c.serviceInterface = "serviceInterface";
            return c;
        }

        @Override
        protected IInterface asInterface(IBinder binder) {
            return null;
        }

        @Override
        protected boolean checkType(IInterface service) {
            return true;
        }

        @Override
        protected void onServiceAdded(ManagedServiceInfo info) {

        }

        @Override
        protected String getRequiredPermission() {
            return null;
        }
    }
}
