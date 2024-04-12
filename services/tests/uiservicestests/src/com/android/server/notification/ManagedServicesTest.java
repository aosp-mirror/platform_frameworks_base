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

import static android.content.Context.DEVICE_POLICY_SERVICE;
import static android.app.Flags.FLAG_LIFETIME_EXTENSION_REFACTOR;
import static android.os.UserManager.USER_TYPE_FULL_SECONDARY;
import static android.os.UserManager.USER_TYPE_PROFILE_CLONE;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;
import static android.service.notification.NotificationListenerService.META_DATA_DEFAULT_AUTOBIND;

import static com.android.server.notification.ManagedServices.APPROVAL_BY_COMPONENT;
import static com.android.server.notification.ManagedServices.APPROVAL_BY_PACKAGE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.UiServiceTestCase;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class ManagedServicesTest extends UiServiceTestCase {

    @Mock
    private IPackageManager mIpm;
    @Mock
    private PackageManager mPm;
    @Mock
    private UserManager mUm;
    @Mock
    private ManagedServices.UserProfiles mUserProfiles;
    @Mock private DevicePolicyManager mDpm;
    Object mLock = new Object();

    UserInfo mZero = new UserInfo(0, "zero", 0);
    UserInfo mTen = new UserInfo(10, "ten", 0);
    private String mDefaultsString;
    private String mVersionString;
    private final Set<ComponentName> mDefaults = new ArraySet();
    private ManagedServices mService;
    private String mUserSetString;

    private static final String SETTING = "setting";
    private static final String SECONDARY_SETTING = "secondary_setting";

    private ArrayMap<Integer, String> mExpectedPrimaryPackages;
    private ArrayMap<Integer, String> mExpectedPrimaryComponentNames;
    private ArrayMap<Integer, String> mExpectedSecondaryPackages;
    private ArrayMap<Integer, String> mExpectedSecondaryComponentNames;

    // type : user : list of approved
    private ArrayMap<Integer, ArrayMap<Integer, String>> mExpectedPrimary;
    private ArrayMap<Integer, ArrayMap<Integer, String>> mExpectedSecondary;

    private UserHandle mUser;
    private String mPkg;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext.setMockPackageManager(mPm);
        mContext.addMockSystemService(Context.USER_SERVICE, mUm);
        mContext.addMockSystemService(DEVICE_POLICY_SERVICE, mDpm);
        mUser = mContext.getUser();
        mPkg = mContext.getPackageName();

        List<UserInfo> users = new ArrayList<>();
        users.add(mZero);
        users.add(mTen);
        users.add(new UserInfo(11, "11", 0));
        users.add(new UserInfo(12, "12", 0));
        users.add(new UserInfo(13, "13", 0));
        for (UserInfo user : users) {
            when(mUm.getUserInfo(eq(user.id))).thenReturn(user);
        }
        when(mUm.getUsers()).thenReturn(users);
        IntArray profileIds = new IntArray();
        profileIds.add(0);
        profileIds.add(11);
        profileIds.add(10);
        profileIds.add(12);
        profileIds.add(13);
        when(mUserProfiles.getCurrentProfileIds()).thenReturn(profileIds);

        mVersionString = "4";
        mExpectedPrimary = new ArrayMap<>();
        mExpectedSecondary = new ArrayMap<>();
        mExpectedPrimaryPackages = new ArrayMap<>();
        mExpectedPrimaryPackages.put(0, "this.is.a.package.name:another.package");
        mExpectedPrimaryPackages.put(10, "this.is.another.package");
        mExpectedPrimaryPackages.put(11, "");
        mExpectedPrimaryPackages.put(12, "bananas!");
        mExpectedPrimaryPackages.put(13, "non.user.set.package");
        mExpectedPrimaryComponentNames = new ArrayMap<>();
        mExpectedPrimaryComponentNames.put(0, "this.is.a.package.name/Ba:another.package/B1");
        mExpectedPrimaryComponentNames.put(10, "this.is.another.package/M1");
        mExpectedPrimaryComponentNames.put(11, "");
        mExpectedPrimaryComponentNames.put(12, "bananas!/Bananas!");
        mExpectedPrimaryComponentNames.put(13, "non.user.set.package/M1");
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
        mService = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm, APPROVAL_BY_COMPONENT);
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

            for (int userId : backupSecondary.get(approvalLevel).keySet()) {
                service.onSettingRestored(service.getConfig().secondarySettingName,
                        backupSecondary.get(approvalLevel).get(userId),
                        Build.VERSION_CODES.N_MR1, userId);
            }
            // both sets of approved entries should be allowed
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
            TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(new BufferedInputStream(new ByteArrayInputStream(new byte[]{})),
                    null);
            writeExpectedValuesToSettings(approvalLevel);

            service.migrateToXml();

            verifyExpectedApprovedEntries(service);
        }
    }

    @Test
    public void testReadXml_noLongerMigrateFromSettings() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServicesNoSettings(getContext(), mLock,
                    mUserProfiles, mIpm, approvalLevel);

            // approved services aren't in xml
            TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(new BufferedInputStream(new ByteArrayInputStream(new byte[]{})),
                    null);
            writeExpectedValuesToSettings(approvalLevel);

            service.migrateToXml();
            // No crash? success

            ArrayMap<Integer, String> verifyMap = approvalLevel == APPROVAL_BY_COMPONENT
                    ? mExpectedPrimary.get(service.mApprovalLevel)
                    : mExpectedSecondary.get(service.mApprovalLevel);
            for (int userId : verifyMap.keySet()) {
                for (String verifyValue : verifyMap.get(userId).split(":")) {
                    if (!TextUtils.isEmpty(verifyValue)) {
                        assertFalse("service type " + service.mApprovalLevel + ":"
                                        + verifyValue + " is allowed for user " + userId,
                                service.isPackageOrComponentAllowed(verifyValue, userId));
                    }
                }
            }
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
            TypedXmlPullParser parser =
                    getParserWithEntries(service, getXmlEntry(resolvedValue, 0, true));

            service.readXml(parser, null, true, 10);

            assertFalse(service.isPackageOrComponentAllowed(resolvedValue, 0));
            assertTrue(service.isPackageOrComponentAllowed(resolvedValue, 10));
        }
    }

    /** Test that restore correctly parses the user_set attribute. */
    @Test
    public void testReadXml_restoresUserSet() throws Exception {
        mVersionString = "4";
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service =
                    new TestManagedServices(
                            getContext(), mLock, mUserProfiles, mIpm, approvalLevel);
            String testPackage = "user.test.package";
            String testComponent = "user.test.component/C1";
            String resolvedValue =
                    (approvalLevel == APPROVAL_BY_COMPONENT) ? testComponent : testPackage;
            String xmlEntry = getXmlEntry(resolvedValue, 0, true, false);
            TypedXmlPullParser parser = getParserWithEntries(service, xmlEntry);

            service.readXml(parser, null, true, 0);

            assertFalse("Failed while parsing xml:\n" + xmlEntry,
                    service.isPackageOrComponentUserSet(resolvedValue, 0));

            xmlEntry = getXmlEntry(resolvedValue, 0, true, true);
            parser = getParserWithEntries(service, xmlEntry);

            service.readXml(parser, null, true, 0);

            assertTrue("Failed while parsing xml:\n" + xmlEntry,
                    service.isPackageOrComponentUserSet(resolvedValue, 0));
        }
    }

    /** Test that restore ignores the user id attribute and applies the data to the target user. */
    @Test
    public void testWriteReadXml_writeReadDefaults() throws Exception {
        // setup
        ManagedServices service1 =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        ManagedServices service2 =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedOutputStream outStream = new BufferedOutputStream(baos);
        serializer.setOutput(outStream, "utf-8");

        //data setup
        service1.addDefaultComponentOrPackage("package/class");
        serializer.startDocument(null, true);
        service1.writeXml(serializer, false, 0);
        serializer.endDocument();
        outStream.flush();

        final TypedXmlPullParser parser = Xml.newFastPullParser();
        BufferedInputStream input = new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray()));

        parser.setInput(input, StandardCharsets.UTF_8.name());
        XmlUtils.beginDocument(parser, "test");
        service2.readXml(parser, null, false, 0);
        ArraySet<ComponentName> defaults = service2.getDefaultComponents();

        assertEquals(1, defaults.size());
        assertEquals(new ComponentName("package", "class"), defaults.valueAt(0));
    }

    @Test
    public void resetPackage_enableDefaultsOnly() {
        // setup
        ManagedServices service =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        service.addApprovedList(
                "package/not-default:another-package/not-default:package2/default",
                0, true);
        service.addDefaultComponentOrPackage("package/default");
        service.addDefaultComponentOrPackage("package2/default");

        ArrayMap<Boolean, ArrayList<ComponentName>> componentsToActivate =
                service.resetComponents("package", 0);

        assertEquals(1, componentsToActivate.get(true).size());
        assertEquals(new ComponentName("package", "default"),
                componentsToActivate.get(true).get(0));
    }


    @Test
    public void resetPackage_nonDefaultsRemoved() {
        // setup
        ManagedServices service =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        service.addApprovedList(
                "package/not-default:another-package/not-default:package2/default",
                0, true);
        service.addDefaultComponentOrPackage("package/default");
        service.addDefaultComponentOrPackage("package2/default");

        ArrayMap<Boolean, ArrayList<ComponentName>> componentsToActivate =
                service.resetComponents("package", 0);

        assertEquals(1, componentsToActivate.get(true).size());
        assertEquals(new ComponentName("package", "not-default"),
                componentsToActivate.get(false).get(0));
    }

    @Test
    public void resetPackage_onlyDefaultsOnly() {
        // setup
        ManagedServices service =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        service.addApprovedList(
                "package/not-default:another-package/not-default:package2/default",
                0, true);
        service.addDefaultComponentOrPackage("package/default");
        service.addDefaultComponentOrPackage("package2/default");

        assertEquals(3, service.getAllowedComponents(0).size());

        service.resetComponents("package", 0);

        List<ComponentName> components =  service.getAllowedComponents(0);
        assertEquals(3, components.size());
        assertTrue(components.contains(new ComponentName("package", "default")));
    }

    @Test
    public void resetPackage_affectCurrentUserOnly() {
        // setup
        ManagedServices service =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        service.addApprovedList(
                "package/not-default:another-package/not-default:package2/default",
                0, true);
        service.addApprovedList(
                "package/not-default:another-package/not-default:package2/default",
                1, true);
        service.addDefaultComponentOrPackage("package/default");
        service.addDefaultComponentOrPackage("package2/default");

        service.resetComponents("package", 0);

        List<ComponentName> components =  service.getAllowedComponents(1);
        assertEquals(3, components.size());
    }

    @Test
    public void resetPackage_samePackageMultipleClasses() {
        // setup
        ManagedServices service =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        service.addApprovedList(
                "package/not-default:another-package/not-default:package2/default",
                0, true);
        service.addApprovedList(
                "package/class:another-package/class:package2/class",
                0, true);
        service.addDefaultComponentOrPackage("package/default");
        service.addDefaultComponentOrPackage("package2/default");

        service.resetComponents("package", 0);

        List<ComponentName> components =  service.getAllowedComponents(0);
        assertEquals(5, components.size());
        assertTrue(components.contains(new ComponentName("package", "default")));
    }

    @Test
    public void resetPackage_clearsUserSet() {
        // setup
        ManagedServices service =
                new TestManagedServices(
                        getContext(), mLock, mUserProfiles, mIpm, APPROVAL_BY_COMPONENT);
        String componentName = "package/user-allowed";
        service.addApprovedList(componentName, 0, true);

        service.resetComponents("package", 0);

        assertFalse(service.isPackageOrComponentUserSet(componentName, 0));
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
            TypedXmlPullParser parser =
                    getParserWithEntries(
                            service,
                            getXmlEntry(resolvedValue0, 0, true),
                            getXmlEntry(resolvedValue10, 10, true));
            service.readXml(parser, null, false, UserHandle.USER_ALL);

            // Write backup.
            TypedXmlSerializer serializer = Xml.newFastSerializer();
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
            TypedXmlPullParser restoreParser = Xml.newFastPullParser();
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

            TypedXmlSerializer serializer = Xml.newFastSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
            serializer.startDocument(null, true);
            for (UserInfo userInfo : mUm.getUsers()) {
                service.writeXml(serializer, true, userInfo.id);
            }
            serializer.endDocument();
            serializer.flush();

            TypedXmlPullParser parser = Xml.newFastPullParser();
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
            ManagedServices service = new TestManagedServicesSettings(getContext(), mLock,
                    mUserProfiles, mIpm, approvalLevel);
            loadXml(service);

            TypedXmlSerializer serializer = Xml.newFastSerializer();
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
    public void testWriteXml_doesNotWriteSetting() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);

            for (int userId : mUserProfiles.getCurrentProfileIds().toArray()) {
                Settings.Secure.putStringForUser(
                        getContext().getContentResolver(),
                        service.getConfig().secureSettingName, null, userId);
            }
            loadXml(service);

            TypedXmlSerializer serializer = Xml.newFastSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
            serializer.startDocument(null, true);
            service.writeXml(serializer, false, UserHandle.USER_ALL);
            serializer.endDocument();
            serializer.flush();

            for (int userId : mUserProfiles.getCurrentProfileIds().toArray()) {
                String actual = Settings.Secure.getStringForUser(
                        getContext().getContentResolver(),
                        service.getConfig().secureSettingName, userId);
                assertTrue(TextUtils.isEmpty(actual));
            }
        }
    }

    @Test
    public void testWriteXml_writesUserSet() throws Exception {
        for (int approvalLevel : new int[] {APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            loadXml(service);

            TypedXmlSerializer serializer = Xml.newFastSerializer();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
            serializer.startDocument(null, true);
            service.writeXml(serializer, false, UserHandle.USER_ALL);
            serializer.endDocument();
            serializer.flush();

            TypedXmlPullParser parser = Xml.newFastPullParser();
            byte[] rawOutput = baos.toByteArray();
            parser.setInput(new BufferedInputStream(
                    new ByteArrayInputStream(rawOutput)), null);
            parser.nextTag();
            for (UserInfo userInfo : mUm.getUsers()) {
                service.readXml(parser, null, true, userInfo.id);
            }

            String resolvedUserSetComponent = approvalLevel == APPROVAL_BY_PACKAGE
                    ? mExpectedPrimaryPackages.get(10)
                    : mExpectedPrimaryComponentNames.get(10);
            String resolvedNonUserSetComponent = approvalLevel == APPROVAL_BY_PACKAGE
                    ? mExpectedPrimaryPackages.get(13)
                    : mExpectedPrimaryComponentNames.get(13);

            try {
                assertFalse(service.isPackageOrComponentUserSet(resolvedNonUserSetComponent, 13));
                assertTrue(service.isPackageOrComponentUserSet(resolvedUserSetComponent, 10));
            } catch (AssertionError e) {
                throw new AssertionError(
                        "Assertion failed while parsing xml:\n" + new String(rawOutput), e);
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
                    eq(unapprovedAdditionalComponent), anyLong(), anyInt());
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
    public void reregisterService_checksAppIsApproved_pkg() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_PACKAGE);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        mockServiceInfoWithMetaData(List.of(cn), service, new ArrayMap<>());
        service.addApprovedList("a", 0, true);

        service.reregisterService(cn, 0);

        assertTrue(service.isBound(cn, 0));
    }

    @Test
    public void reregisterService_checksAppIsApproved_pkg_secondary() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_PACKAGE);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        mockServiceInfoWithMetaData(List.of(cn), service, new ArrayMap<>());
        service.addApprovedList("a", 0, false);

        service.reregisterService(cn, 0);

        assertTrue(service.isBound(cn, 0));
    }

    @Test
    public void reregisterService_checksAppIsApproved_cn() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        mockServiceInfoWithMetaData(List.of(cn), service, new ArrayMap<>());
        service.addApprovedList("a/a", 0, true);

        service.reregisterService(cn, 0);

        assertTrue(service.isBound(cn, 0));
    }

    @Test
    public void reregisterService_checksAppIsApproved_cn_secondary() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        mockServiceInfoWithMetaData(List.of(cn), service, new ArrayMap<>());
        service.addApprovedList("a/a", 0, false);

        service.reregisterService(cn, 0);

        assertTrue(service.isBound(cn, 0));
    }

    @Test
    public void reregisterService_checksAppIsNotApproved_cn_secondary() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        service.addApprovedList("b/b", 0, false);

        service.reregisterService(cn, 0);

        assertFalse(service.isBound(cn, 0));
    }

    @Test
    public void unbindOtherUserServices() throws PackageManager.NameNotFoundException {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        service.registerService(cn, 0);
        service.registerService(cn, 10);
        service.registerService(cn, 11);
        service.unbindOtherUserServices(11);

        assertFalse(service.isBound(cn, 0));
        assertFalse(service.isBound(cn, 10));
        assertTrue(service.isBound(cn, 11));
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
    public void testPackageUninstall_componentNoLongerUserSetList() throws Exception {
        final String pkg = "this.is.a.package.name";
        final String component = pkg + "/Ba";
        for (int approvalLevel : new int[] { APPROVAL_BY_COMPONENT, APPROVAL_BY_PACKAGE}) {
            ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles,
                    mIpm, approvalLevel);
            writeExpectedValuesToSettings(approvalLevel);
            service.migrateToXml();

            final String verifyValue = (approvalLevel == APPROVAL_BY_COMPONENT) ? component : pkg;

            assertThat(service.isPackageOrComponentAllowed(verifyValue, 0)).isTrue();
            assertThat(service.isPackageOrComponentUserSet(verifyValue, 0)).isTrue();

            service.onPackagesChanged(true, new String[]{pkg}, new int[]{103});
            assertThat(service.isPackageOrComponentUserSet(verifyValue, 0)).isFalse();
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
                    eq(unapprovedAdditionalComponent), anyLong(), anyInt());
        }
    }

    @Test
    public void testUpgradeAppNoPermissionNoRebind() throws Exception {
        Context context = spy(getContext());
        doReturn(true).when(context).bindServiceAsUser(any(), any(), anyInt(), any());

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles,
                mIpm,
                APPROVAL_BY_COMPONENT);

        List<String> packages = new ArrayList<>();
        packages.add("package");
        addExpectedServices(service, packages, 0);

        final ComponentName unapprovedComponent = ComponentName.unflattenFromString("package/C1");
        final ComponentName approvedComponent = ComponentName.unflattenFromString("package/C2");

        // Both components are approved initially
        mExpectedPrimaryComponentNames.clear();
        mExpectedPrimaryPackages.clear();
        mExpectedPrimaryComponentNames.put(0, "package/C1:package/C2");
        mExpectedSecondaryComponentNames.clear();
        mExpectedSecondaryPackages.clear();

        loadXml(service);

        //Component package/C1 loses bind permission
        when(mIpm.getServiceInfo(any(), anyLong(), anyInt())).thenAnswer(
                (Answer<ServiceInfo>) invocation -> {
                    ComponentName invocationCn = invocation.getArgument(0);
                    if (invocationCn != null) {
                        ServiceInfo serviceInfo = new ServiceInfo();
                        serviceInfo.packageName = invocationCn.getPackageName();
                        serviceInfo.name = invocationCn.getClassName();
                        if (invocationCn.equals(unapprovedComponent)) {
                            serviceInfo.permission = "none";
                        } else {
                            serviceInfo.permission = service.getConfig().bindPermission;
                        }
                        serviceInfo.metaData = null;
                        return serviceInfo;
                    }
                    return null;
                }
        );

        // Trigger package update
        service.onPackagesChanged(false, new String[]{"package"}, new int[]{0});

        assertFalse(service.isComponentEnabledForCurrentProfiles(unapprovedComponent));
        assertTrue(service.isComponentEnabledForCurrentProfiles(approvedComponent));
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

            assertThat(service.getAllowedPackages(0)).containsExactly("this.is.a.package.name",
                    "another.package", "secondary");
            assertThat(service.getAllowedPackages(10)).containsExactly("this.is.another.package",
                    "package", "this.is.another.package", "component");
            assertThat(service.getAllowedPackages(999)).isEmpty();
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
        services.registerSystemService(service, null, 10, 1000);
        ManagedServices.ManagedServiceInfo info = services.checkServiceTokenLocked(service);
        info.isSystem = true;

        assertFalse(services.isSameUser(service, 0));
        assertTrue(services.isSameUser(service, 10));
        assertTrue(services.isSameUser(service, UserHandle.USER_ALL));
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
        ArraySet<ComponentName> expected13 = new ArraySet<>();
        expected13.add(ComponentName.unflattenFromString("non.user.set.package/M1"));
        expected.put(13, expected13);

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
                mock(ServiceConnection.class), 26, 34);
        ManagedServices.ManagedServiceInfo service10 = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("b/b"), 10, false,
                mock(ServiceConnection.class), 26, 345);
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
                mock(ServiceConnection.class), 26, 345);
        ManagedServices.ManagedServiceInfo service0a = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("c/c"), 0, false,
                mock(ServiceConnection.class), 26, 3456);
        ManagedServices.ManagedServiceInfo service10 = service.new ManagedServiceInfo(
                iInterface, ComponentName.unflattenFromString("b/b"), 10, false,
                mock(ServiceConnection.class), 26, 34567);
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
    public void testOnNullBinding() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        ArgumentCaptor<ServiceConnection> captor = ArgumentCaptor.forClass(ServiceConnection.class);
        when(context.bindServiceAsUser(any(), captor.capture(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    captor.getValue().onNullBinding(cn);
                    return true;
                });

        service.registerSystemService(cn, 0);
        verify(context).unbindService(captor.getValue());
    }

    @Test
    public void testOnServiceConnected() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        service.registerSystemService(cn, 0);
        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        service.registerSystemService(cn, 0);
        assertTrue(service.isBound(cn, 0));
    }

    @Test
    public void testSetComponentState() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        service.registerSystemService(cn, 0);
        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        service.registerSystemService(cn, mZero.id);

        service.setComponentState(cn, mZero.id, false);
        verify(context).unbindService(any());
    }

    @Test
    public void testSetComponentState_workProfile() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        service.registerSystemService(cn, 0);
        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });

        service.registerSystemService(cn, mZero.id);

        service.setComponentState(cn, mTen.id, false);
        verify(context, never()).unbindService(any());
    }

    @Test
    public void testSetComponentState_differentUsers() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        ComponentName cn = ComponentName.unflattenFromString("a/a");

        addExpectedServices(service, Arrays.asList("a"), mZero.id);
        addExpectedServices(service, Arrays.asList("a"), mTen.id);
        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn, mock(IBinder.class));
            return true;
        });
        service.addApprovedList("a/a", 0, true);
        service.addApprovedList("a/a", 10, false);

        service.registerService(cn, mZero.id);
        assertTrue(service.isBound(cn, mZero.id));

        service.onUserSwitched(mTen.id);
        assertFalse(service.isBound(cn, mZero.id));
        service.registerService(cn, mTen.id);
        assertTrue(service.isBound(cn, mTen.id));

        service.setComponentState(cn, mTen.id, false);
        assertFalse(service.isBound(cn, mZero.id));
        assertFalse(service.isBound(cn, mTen.id));

        // Service should be rebound on user 0, since it was only disabled for user 10.
        service.onUserSwitched(mZero.id);
        assertTrue(service.isBound(cn, mZero.id));
        assertFalse(service.isBound(cn, mTen.id));

        // Service should stay unbound on going back to user 10.
        service.onUserSwitched(mTen.id);
        assertFalse(service.isBound(cn, mZero.id));
        assertFalse(service.isBound(cn, mTen.id));
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

    @Test
    public void loadDefaults_noVersionNoDefaults() throws Exception {
        resetComponentsAndPackages();
        loadXml(mService);
        assertEquals(mService.getDefaultComponents().size(), 0);
    }

    @Test
    public void loadDefaults_noVersionNoDefaultsOneActive() throws Exception {
        resetComponentsAndPackages();
        mService.addDefaultComponentOrPackage("package/class");
        loadXml(mService);
        assertEquals(1, mService.getDefaultComponents().size());
        assertTrue(mService.getDefaultComponents()
                .contains(ComponentName.unflattenFromString("package/class")));
    }

    @Test
    public void loadDefaults_noVersionWithDefaults() throws Exception {
        resetComponentsAndPackages();
        mDefaults.add(new ComponentName("default", "class"));
        mVersionString = null;
        loadXml(mService);
        assertEquals(mService.getDefaultComponents(), mDefaults);
    }

    @Test
    public void loadDefaults_versionOneWithDefaultsWithActive() throws Exception {
        resetComponentsAndPackages();
        mDefaults.add(new ComponentName("default", "class"));
        mExpectedPrimaryComponentNames.put(0, "package/class");
        mVersionString = "1";
        loadXml(mService);
        assertEquals(mService.getDefaultComponents(),
                new ArraySet(Arrays.asList(new ComponentName("package", "class"))));
    }

    @Test
    public void loadDefaults_versionTwoWithDefaultsWithActive() throws Exception {
        resetComponentsAndPackages();
        mDefaults.add(new ComponentName("default", "class"));
        mDefaultsString = "default/class";
        mExpectedPrimaryComponentNames.put(0, "package/class");
        mVersionString = "2";
        loadXml(mService);
        assertEquals(1, mService.getDefaultComponents().size());
        mDefaults.forEach(pkg -> {
            assertTrue(mService.getDefaultComponents().contains(pkg));
        });
    }

    @Test
    public void loadDefaults_versionOneWithXMLDefaultsWithActive() throws Exception {
        resetComponentsAndPackages();
        mDefaults.add(new ComponentName("default", "class"));
        mDefaultsString = "xml/class";
        mExpectedPrimaryComponentNames.put(0, "package/class");
        mVersionString = "1";
        loadXml(mService);
        assertEquals(mService.getDefaultComponents(),
                new ArraySet(Arrays.asList(new ComponentName("xml", "class"))));
    }

    @Test
    public void loadDefaults_versionTwoWithXMLDefaultsWithActive() throws Exception {
        resetComponentsAndPackages();
        mDefaults.add(new ComponentName("default", "class"));
        mDefaultsString = "xml/class";
        mExpectedPrimaryComponentNames.put(0, "package/class");
        mVersionString = "2";
        loadXml(mService);
        assertEquals(mService.getDefaultComponents(),
                new ArraySet(Arrays.asList(new ComponentName("xml", "class"))));
    }

    @Test
    public void loadDefaults_versionLatest_NoLoadDefaults() throws Exception {
        resetComponentsAndPackages();
        mDefaults.add(new ComponentName("default", "class"));
        mDefaultsString = "xml/class";
        loadXml(mService);
        assertEquals(mService.getDefaultComponents(),
                new ArraySet(Arrays.asList(new ComponentName("xml", "class"))));
    }

    @Test
    public void upgradeUserSet_versionThree() throws Exception {
        resetComponentsAndPackages();

        List<UserInfo> users = new ArrayList<>();
        users.add(new UserInfo(98, "98", 0));
        users.add(new UserInfo(99, "99", 0));
        for (UserInfo user : users) {
            when(mUm.getUserInfo(eq(user.id))).thenReturn(user);
        }

        mDefaultsString = "xml/class";
        mVersionString = "3";
        mUserSetString = "xml/class";
        loadXml(mService);

        //Test services without overriding upgradeUserSet() remain unchanged
        assertEquals(new ArraySet(Arrays.asList(mUserSetString)),
                mService.mUserSetServices.get(98));
        assertEquals(new ArraySet(Arrays.asList(mUserSetString)),
                mService.mUserSetServices.get(99));
        assertEquals(new ArrayMap(), mService.mIsUserChanged);
    }

    @Test
    public void testInfoIsPermittedForProfile_notProfile() {
        when(mUserProfiles.isProfileUser(anyInt())).thenReturn(false);

        IInterface service = mock(IInterface.class);
        when(service.asBinder()).thenReturn(mock(IBinder.class));
        ManagedServices services = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm, APPROVAL_BY_PACKAGE);
        services.registerSystemService(service, null, 10, 1000);
        ManagedServices.ManagedServiceInfo info = services.checkServiceTokenLocked(service);

        assertTrue(info.isPermittedForProfile(0));
    }

    @Test
    public void testInfoIsPermittedForProfile_profileAndDpmAllows() {
        when(mUserProfiles.isProfileUser(anyInt())).thenReturn(true);
        when(mDpm.isNotificationListenerServicePermitted(anyString(), anyInt())).thenReturn(true);

        IInterface service = mock(IInterface.class);
        when(service.asBinder()).thenReturn(mock(IBinder.class));
        ManagedServices services = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm, APPROVAL_BY_PACKAGE);
        services.registerSystemService(service, null, 10, 1000);
        ManagedServices.ManagedServiceInfo info = services.checkServiceTokenLocked(service);
        info.component = new ComponentName("a","b");

        assertTrue(info.isPermittedForProfile(0));
    }

    @Test
    public void testInfoIsPermittedForProfile_profileAndDpmDenies() {
        when(mUserProfiles.isProfileUser(anyInt())).thenReturn(true);
        when(mDpm.isNotificationListenerServicePermitted(anyString(), anyInt())).thenReturn(false);

        IInterface service = mock(IInterface.class);
        when(service.asBinder()).thenReturn(mock(IBinder.class));
        ManagedServices services = new TestManagedServices(getContext(), mLock, mUserProfiles,
                mIpm, APPROVAL_BY_PACKAGE);
        services.registerSystemService(service, null, 10, 1000);
        ManagedServices.ManagedServiceInfo info = services.checkServiceTokenLocked(service);
        info.component = new ComponentName("a","b");

        assertFalse(info.isPermittedForProfile(0));
    }

    @Test
    public void testUserProfiles_canProfileUseBoundServices_managedProfile() {
        List<UserInfo> users = new ArrayList<>();
        UserInfo profile = new UserInfo(ActivityManager.getCurrentUser(), "current", 0);
        profile.userType = USER_TYPE_FULL_SECONDARY;
        users.add(profile);
        UserInfo managed = new UserInfo(12, "12", 0);
        managed.userType = USER_TYPE_PROFILE_MANAGED;
        users.add(managed);
        UserInfo clone = new UserInfo(13, "13", 0);
        clone.userType = USER_TYPE_PROFILE_CLONE;
        users.add(clone);
        when(mUm.getProfiles(ActivityManager.getCurrentUser())).thenReturn(users);

        ManagedServices.UserProfiles profiles = new ManagedServices.UserProfiles();
        profiles.updateCache(mContext);

        assertFalse(profiles.isProfileUser(ActivityManager.getCurrentUser()));
        assertTrue(profiles.isProfileUser(12));
        assertTrue(profiles.isProfileUser(13));
    }

    @Test
    public void rebindServices_onlyBindsIfAutobindMetaDataTrue() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        final ComponentName cn_allowed = ComponentName.unflattenFromString("anotherPackage/C1");
        final ComponentName cn_disallowed = ComponentName.unflattenFromString("package/C1");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn_allowed, mock(IBinder.class));
            return true;
        });

        List<ComponentName> componentNames = new ArrayList<>();
        componentNames.add(cn_allowed);
        componentNames.add(cn_disallowed);
        ArrayMap<ComponentName, Bundle> metaDatas = new ArrayMap<>();
        Bundle metaDataAutobindDisallow = new Bundle();
        metaDataAutobindDisallow.putBoolean(META_DATA_DEFAULT_AUTOBIND, false);
        metaDatas.put(cn_disallowed, metaDataAutobindDisallow);
        Bundle metaDataAutobindAllow = new Bundle();
        metaDataAutobindAllow.putBoolean(META_DATA_DEFAULT_AUTOBIND, true);
        metaDatas.put(cn_allowed, metaDataAutobindAllow);

        mockServiceInfoWithMetaData(componentNames, service, metaDatas);

        service.addApprovedList(cn_allowed.flattenToString(), 0, true);
        service.addApprovedList(cn_disallowed.flattenToString(), 0, true);

        service.rebindServices(true, 0);

        assertTrue(service.isBound(cn_allowed, 0));
        assertFalse(service.isBound(cn_disallowed, 0));
    }

    @Test
    public void rebindServices_bindsIfAutobindMetaDataFalseWhenServiceBound() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        final ComponentName cn_disallowed = ComponentName.unflattenFromString("package/C1");

        // mock isBoundOrRebinding => consider listener service bound
        service = spy(service);
        when(service.isBoundOrRebinding(cn_disallowed, 0)).thenReturn(true);

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn_disallowed, mock(IBinder.class));
            return true;
        });

        List<ComponentName> componentNames = new ArrayList<>();
        componentNames.add(cn_disallowed);
        ArrayMap<ComponentName, Bundle> metaDatas = new ArrayMap<>();
        Bundle metaDataAutobindDisallow = new Bundle();
        metaDataAutobindDisallow.putBoolean(META_DATA_DEFAULT_AUTOBIND, false);
        metaDatas.put(cn_disallowed, metaDataAutobindDisallow);

        mockServiceInfoWithMetaData(componentNames, service, metaDatas);

        service.addApprovedList(cn_disallowed.flattenToString(), 0, true);

        // Listener service should be bound by rebindService when forceRebind is false
        service.rebindServices(false, 0);
        assertTrue(service.isBound(cn_disallowed, 0));
    }

    @Test
    public void setComponentState_ignoresAutobindMetaData() throws Exception {
        Context context = mock(Context.class);
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo ai = new ApplicationInfo();
        ai.targetSdkVersion = Build.VERSION_CODES.CUR_DEVELOPMENT;

        when(context.getPackageName()).thenReturn(mPkg);
        when(context.getUserId()).thenReturn(mUser.getIdentifier());
        when(context.getPackageManager()).thenReturn(pm);
        when(pm.getApplicationInfo(anyString(), anyInt())).thenReturn(ai);

        ManagedServices service = new TestManagedServices(context, mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);
        final ComponentName cn_disallowed = ComponentName.unflattenFromString("package/C1");

        when(context.bindServiceAsUser(any(), any(), anyInt(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ServiceConnection sc = (ServiceConnection) args[1];
            sc.onServiceConnected(cn_disallowed, mock(IBinder.class));
            return true;
        });

        List<ComponentName> componentNames = new ArrayList<>();
        componentNames.add(cn_disallowed);
        ArrayMap<ComponentName, Bundle> metaDatas = new ArrayMap<>();
        Bundle metaDataAutobindDisallow = new Bundle();
        metaDataAutobindDisallow.putBoolean(META_DATA_DEFAULT_AUTOBIND, false);
        metaDatas.put(cn_disallowed, metaDataAutobindDisallow);

        mockServiceInfoWithMetaData(componentNames, service, metaDatas);

        service.addApprovedList(cn_disallowed.flattenToString(), 0, true);

        // add component to snoozing list
        service.setComponentState(cn_disallowed, 0, false);

        // Test that setComponentState overrides the meta-data and service is bound
        service.setComponentState(cn_disallowed, 0, true);
        assertTrue(service.isBound(cn_disallowed, 0));
    }

    @Test
    public void isComponentEnabledForCurrentProfiles_isThreadSafe() throws InterruptedException {
        for (UserInfo userInfo : mUm.getUsers()) {
            mService.addApprovedList("pkg1/cmp1:pkg2/cmp2:pkg3/cmp3", userInfo.id, true);
        }
        testThreadSafety(() -> {
            mService.rebindServices(false, 0);
            assertThat(mService.isComponentEnabledForCurrentProfiles(
                    new ComponentName("pkg1", "cmp1"))).isTrue();
        }, 20, 30);
    }

    @Test
    public void isComponentEnabledForCurrentProfiles_profileUserId() {
        final int profileUserId = 10;
        when(mUserProfiles.isProfileUser(profileUserId)).thenReturn(true);
        // Only approve for parent user (0)
        mService.addApprovedList("pkg1/cmp1:pkg2/cmp2:pkg3/cmp3", 0, true);

        // Test that the component is enabled after calling rebindServices with profile userId (10)
        mService.rebindServices(false, profileUserId);
        assertThat(mService.isComponentEnabledForCurrentProfiles(
                new ComponentName("pkg1", "cmp1"))).isTrue();
    }

    @Test
    public void isComponentEnabledForCurrentProfiles_profileUserId_NAS() {
        final int profileUserId = 10;
        when(mUserProfiles.isProfileUser(profileUserId)).thenReturn(true);
        // Do not rebind for parent users (NAS use-case)
        ManagedServices service = spy(mService);
        when(service.allowRebindForParentUser()).thenReturn(false);

        // Only approve for parent user (0)
        service.addApprovedList("pkg1/cmp1:pkg2/cmp2:pkg3/cmp3", 0, true);

        // Test that the component is disabled after calling rebindServices with profile userId (10)
        service.rebindServices(false, profileUserId);
        assertThat(service.isComponentEnabledForCurrentProfiles(
                new ComponentName("pkg1", "cmp1"))).isFalse();
    }

    @Test
    @EnableFlags(FLAG_LIFETIME_EXTENSION_REFACTOR)
    public void testManagedServiceInfoIsSystemUi() {
        ManagedServices service = new TestManagedServices(getContext(), mLock, mUserProfiles, mIpm,
                APPROVAL_BY_COMPONENT);

        ManagedServices.ManagedServiceInfo service0 = service.new ManagedServiceInfo(
                mock(IInterface.class), ComponentName.unflattenFromString("a/a"), 0, false,
                mock(ServiceConnection.class), 26, 34);

        service0.isSystemUi = true;
        assertThat(service0.isSystemUi()).isTrue();
        service0.isSystemUi = false;
        assertThat(service0.isSystemUi()).isFalse();
    }

    private void mockServiceInfoWithMetaData(List<ComponentName> componentNames,
            ManagedServices service, ArrayMap<ComponentName, Bundle> metaDatas)
            throws RemoteException {
        when(mIpm.getServiceInfo(any(), anyLong(), anyInt())).thenAnswer(
                (Answer<ServiceInfo>) invocation -> {
                    ComponentName invocationCn = invocation.getArgument(0);
                    if (invocationCn != null && componentNames.contains(invocationCn)) {
                        ServiceInfo serviceInfo = new ServiceInfo();
                        serviceInfo.packageName = invocationCn.getPackageName();
                        serviceInfo.name = invocationCn.getClassName();
                        serviceInfo.permission = service.getConfig().bindPermission;
                        serviceInfo.metaData = metaDatas.get(invocationCn);
                        return serviceInfo;
                    }
                    return null;
                }
        );
    }

    private void resetComponentsAndPackages() {
        ArrayMap<Integer, ArrayMap<Integer, String>> empty = new ArrayMap(1);
        ArrayMap<Integer, String> emptyPkgs = new ArrayMap(0);
        empty.append(mService.mApprovalLevel, emptyPkgs);
        mExpectedPrimary = empty;
        mExpectedPrimaryComponentNames = emptyPkgs;
        mExpectedPrimaryPackages = emptyPkgs;
        mExpectedSecondary = empty;
        mExpectedSecondaryComponentNames = emptyPkgs;
        mExpectedSecondaryPackages = emptyPkgs;
    }

    private void loadXml(ManagedServices service) throws Exception {
        String xmlString = createXml(service);
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xmlString.getBytes())), null);
        parser.nextTag();
        service.readXml(parser, null, false, UserHandle.USER_ALL);
    }

    private String createXml(ManagedServices service) {
        final StringBuffer xml = new StringBuffer();
        String xmlTag = service.getConfig().xmlTag;
        xml.append("<" + xmlTag
                + (mDefaultsString != null ? " defaults=\"" + mDefaultsString + "\" " : "")
                + (mVersionString != null ? " version=\"" + mVersionString + "\" " : "")
                + ">\n");
        for (int userId : mExpectedPrimary.get(service.mApprovalLevel).keySet()) {
            String pkgOrCmp = mExpectedPrimary.get(service.mApprovalLevel).get(userId);
            xml.append(getXmlEntry(
                    pkgOrCmp, userId, true,
                    !(pkgOrCmp.startsWith("non.user.set.package"))));
        }
        for (int userId : mExpectedSecondary.get(service.mApprovalLevel).keySet()) {
            xml.append(getXmlEntry(
                    mExpectedSecondary.get(service.mApprovalLevel).get(userId), userId, false));
        }
        xml.append("<" + ManagedServices.TAG_MANAGED_SERVICES + " "
                        + ManagedServices.ATT_USER_ID + "=\"99\" "
                        + ManagedServices.ATT_IS_PRIMARY + "=\"true\" "
                        + ManagedServices.ATT_APPROVED_LIST + "=\"990\" "
                        + (mUserSetString != null ? ManagedServices.ATT_USER_SET + "=\""
                        + mUserSetString + "\" " : "")
                        + "/>\n");
        xml.append("<" + ManagedServices.TAG_MANAGED_SERVICES + " "
                + ManagedServices.ATT_USER_ID + "=\"98\" "
                + ManagedServices.ATT_IS_PRIMARY + "=\"false\" "
                + ManagedServices.ATT_APPROVED_LIST + "=\"981\" "
                + (mUserSetString != null ? ManagedServices.ATT_USER_SET + "=\""
                + mUserSetString + "\" " : "")
                + " />\n");
        xml.append("</" + xmlTag + ">");

        return xml.toString();
    }

    private TypedXmlPullParser getParserWithEntries(ManagedServices service, String... xmlEntries)
            throws Exception {
        final StringBuffer xml = new StringBuffer();
        xml.append("<" + service.getConfig().xmlTag
                + (mVersionString != null ? " version=\"" + mVersionString + "\" " : "")
                + ">\n");
        for (String xmlEntry : xmlEntries) {
            xml.append(xmlEntry);
        }
        xml.append("</" + service.getConfig().xmlTag + ">");

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.toString().getBytes())), null);
        parser.nextTag();
        return parser;
    }

    private void addExpectedServices(final ManagedServices service, final List<String> packages,
            int userId) throws Exception {
        ManagedServices.Config config = service.getConfig();
        when(mPm.queryIntentServicesAsUser(any(), anyInt(), eq(userId))).
                thenAnswer(new Answer<List<ResolveInfo>>() {
                    @Override
                    public List<ResolveInfo> answer(InvocationOnMock invocationOnMock)
                            throws Throwable {
                        Object[] args = invocationOnMock.getArguments();
                        Intent invocationIntent = (Intent) args[0];
                        if (invocationIntent != null) {
                            if (invocationIntent.getAction().equals(
                                    config.serviceInterface)
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

        when(mIpm.getServiceInfo(any(), anyLong(), anyInt())).thenAnswer(
                (Answer<ServiceInfo>) invocation -> {
                    ComponentName invocationCn = invocation.getArgument(0);
                    if (invocationCn != null && packages.contains(invocationCn.getPackageName())) {
                        ServiceInfo serviceInfo = new ServiceInfo();
                        serviceInfo.packageName = invocationCn.getPackageName();
                        serviceInfo.name = invocationCn.getClassName();
                        serviceInfo.permission = service.getConfig().bindPermission;
                        return serviceInfo;
                    }
                    return null;
                }
        );
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
        assertEquals(expected + " : " + actual, expected.size(), actual.size());

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
                                    eq(componentName), anyLong(), anyInt());
                        }
                    } else {
                        ComponentName componentName =
                                ComponentName.unflattenFromString(packageOrComponent);
                        assertTrue(service.isComponentEnabledForCurrentProfiles(componentName));
                        verify(mIpm, times(1)).getServiceInfo(
                                eq(componentName), anyLong(), anyInt());
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
        return getXmlEntry(approved, userId, isPrimary, true);
    }

    private String getXmlEntry(String approved, int userId, boolean isPrimary, boolean userSet) {
        String userSetString = "";
        if (mVersionString.equals("4")) {
            userSetString =
                    ManagedServices.ATT_USER_CHANGED + "=\"" + String.valueOf(userSet) + "\" ";
        } else if (mVersionString.equals("3")) {
            userSetString =
                    ManagedServices.ATT_USER_SET + "=\"" + (userSet ? approved : "") + "\" ";
        }
        return "<" + ManagedServices.TAG_MANAGED_SERVICES + " "
                + ManagedServices.ATT_USER_ID + "=\"" + userId +"\" "
                + ManagedServices.ATT_IS_PRIMARY + "=\"" + isPrimary +"\" "
                + ManagedServices.ATT_APPROVED_LIST + "=\"" + approved +"\" "
                + userSetString + "/>\n";
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
        protected void ensureFilters(ServiceInfo si, int userId) {

        }

        @Override
        protected void loadDefaultsFromConfig() {
            mDefaultComponents.addAll(mDefaults);
        }

        @Override
        protected String getRequiredPermission() {
            return null;
        }

        @Override
        protected boolean allowRebindForParentUser() {
            return true;
        }
    }

    class TestManagedServicesSettings extends TestManagedServices {

        public TestManagedServicesSettings(Context context, Object mutex, UserProfiles userProfiles,
                IPackageManager pm, int approvedServiceType) {
            super(context, mutex, userProfiles, pm, approvedServiceType);
        }

        @Override
        public boolean shouldReflectToSettings() {
            return true;
        }
    }

    class TestManagedServicesNoSettings extends TestManagedServices {

        public TestManagedServicesNoSettings(Context context, Object mutex, UserProfiles userProfiles,
                IPackageManager pm, int approvedServiceType) {
            super(context, mutex, userProfiles, pm, approvedServiceType);
        }

        @Override
        protected Config getConfig() {
            Config c = super.getConfig();
            c.secureSettingName = null;
            c.secondarySettingName = null;
            return c;
        }

        @Override
        public boolean shouldReflectToSettings() {
            return false;
        }
    }

    /**
     * Helper method to test the thread safety of some operations.
     *
     * <p>Runs the supplied {@code operationToTest}, {@code nRunsPerThread} times,
     * concurrently using {@code nThreads} threads, and waits for all of them to finish.
     */
    private static void testThreadSafety(Runnable operationToTest, int nThreads,
            int nRunsPerThread) throws InterruptedException {
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch doneLatch = new CountDownLatch(nThreads);

        for (int i = 0; i < nThreads; i++) {
            Runnable threadRunnable = () -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < nRunsPerThread; j++) {
                        operationToTest.run();
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            };
            new Thread(threadRunnable, "Test Thread #" + i).start();
        }

        // Ready set go
        startLatch.countDown();

        // Wait for all test threads to be done.
        doneLatch.await();
    }
}
