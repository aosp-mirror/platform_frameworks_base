/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static android.content.pm.UserInfo.FLAG_PRIMARY;
import static android.os.UserManager.USER_TYPE_FULL_SYSTEM;
import static android.os.UserManager.USER_TYPE_PROFILE_MANAGED;

import static com.android.server.devicepolicy.DevicePolicyManagerService.DEVICE_POLICIES_XML;
import static com.android.server.devicepolicy.DevicePolicyManagerService.POLICIES_VERSION_XML;

import static com.google.common.truth.Truth.assertThat;

import android.app.admin.DeviceAdminInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.IpcDataCache;
import android.os.Parcel;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.JournaledFile;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.server.SystemService;

import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.xml.parsers.DocumentBuilderFactory;

@RunWith(JUnit4.class)
public class PolicyVersionUpgraderTest extends DpmTestBase {
    // NOTE: Only change this value if the corresponding CL also adds a test to test the upgrade
    // to the new version.
    private static final int LATEST_TESTED_VERSION = 6;
    public static final String PERMISSIONS_TAG = "admin-can-grant-sensors-permissions";
    public static final String DEVICE_OWNER_XML = "device_owner_2.xml";
    private ComponentName mFakeAdmin;

    private class FakePolicyUpgraderDataProvider implements PolicyUpgraderDataProvider {
        Map<ComponentName, DeviceAdminInfo> mComponentToDeviceAdminInfo = new HashMap<>();
        ArrayList<String> mPlatformSuspendedPackages = new ArrayList<>();
        int[] mUsers;

        private JournaledFile makeJournaledFile(int userId, String fileName) {
            File parentDir = getServices().environment.getUserSystemDirectory(userId);

            final String base = new File(parentDir, fileName).getAbsolutePath();
            return new JournaledFile(new File(base), new File(base + ".tmp"));
        }

        @Override
        public JournaledFile makeDevicePoliciesJournaledFile(int userId) {
            return makeJournaledFile(userId, DEVICE_POLICIES_XML);
        }

        @Override
        public JournaledFile makePoliciesVersionJournaledFile(int userId) {
            return makeJournaledFile(userId, POLICIES_VERSION_XML);
        }

        @Override
        public Function<ComponentName, DeviceAdminInfo> getAdminInfoSupplier(int userId) {
            return componentName -> mComponentToDeviceAdminInfo.get(componentName);
        }

        @Override
        public int[] getUsersForUpgrade() {
            return mUsers;
        }

        @Override
        public List<String> getPlatformSuspendedPackages(int userId) {
            return mPlatformSuspendedPackages;
        }
    }

    private final Context mRealTestContext = InstrumentationRegistry.getTargetContext();
    private FakePolicyUpgraderDataProvider mProvider;
    private PolicyVersionUpgrader mUpgrader;

    @Before
    public void setUp() {
        // Disable caches in this test process. This must happen early, since some of the
        // following initialization steps invalidate caches.
        IpcDataCache.disableForTestMode();

        mProvider = new FakePolicyUpgraderDataProvider();
        mUpgrader = new PolicyVersionUpgrader(mProvider, getServices().pathProvider);
        mFakeAdmin = new ComponentName(
                "com.android.frameworks.servicestests",
                "com.android.server.devicepolicy.DummyDeviceAdmins$Admin1");
        ActivityInfo activityInfo = createActivityInfo(mFakeAdmin);
        DeviceAdminInfo dai = createDeviceAdminInfo(activityInfo);
        mProvider.mComponentToDeviceAdminInfo.put(mFakeAdmin, dai);
        mProvider.mUsers = new int[]{0};
    }

    @Test
    public void testSameVersionDoesNothing() throws IOException {
        writeVersionToXml(DevicePolicyManagerService.DPMS_VERSION);
        final int userId = mProvider.mUsers[0];
        preparePoliciesFile(userId, "device_policies.xml");
        String oldContents = readPoliciesFile(userId);

        mUpgrader.upgradePolicy(DevicePolicyManagerService.DPMS_VERSION);

        String newContents = readPoliciesFile(0);
        assertThat(newContents).isEqualTo(oldContents);
    }

    @Test
    public void testUpgrade0To1RemovesPasswordMetrics() throws IOException, XmlPullParserException {
        final String activePasswordTag = "active-password";
        mProvider.mUsers = new int[]{0, 10};
        getServices().addUser(10, /* flags= */ 0, USER_TYPE_PROFILE_MANAGED);
        writeVersionToXml(0);
        for (int userId : mProvider.mUsers) {
            preparePoliciesFile(userId, "device_policies.xml");
        }
        // Validate test set-up.
        assertThat(isTagPresent(readPoliciesFileToStream(0), activePasswordTag)).isTrue();

        mUpgrader.upgradePolicy(1);

        assertThat(readVersionFromXml()).isAtLeast(1);
        for (int user : mProvider.mUsers) {
            assertThat(isTagPresent(readPoliciesFileToStream(user), activePasswordTag)).isFalse();
        }
    }

    @Test
    public void testUpgrade1To2MarksDoForPermissionControl()
            throws IOException, XmlPullParserException {
        final int ownerUser = 10;
        mProvider.mUsers = new int[]{0, ownerUser};
        getServices().addUser(ownerUser, FLAG_PRIMARY, USER_TYPE_FULL_SYSTEM);
        writeVersionToXml(1);
        for (int userId : mProvider.mUsers) {
            preparePoliciesFile(userId, "device_policies.xml");
        }
        prepareDeviceOwnerFile(ownerUser, "device_owner_2.xml");

        mUpgrader.upgradePolicy(2);

        assertThat(readVersionFromXml()).isAtLeast(2);
        assertThat(getBooleanValueTag(readPoliciesFileToStream(mProvider.mUsers[0]),
                PERMISSIONS_TAG)).isFalse();
        assertThat(getBooleanValueTag(readPoliciesFileToStream(ownerUser),
                PERMISSIONS_TAG)).isTrue();
    }

    @Test
    public void testNoStaleDataInCacheAfterUpgrade() throws Exception {
        final int ownerUser = 0;
        getServices().addUser(ownerUser, FLAG_PRIMARY, USER_TYPE_FULL_SYSTEM);
        setUpPackageManagerForAdmin(admin1, UserHandle.getUid(ownerUser, 123 /* admin app ID */));
        writeVersionToXml(0);
        preparePoliciesFile(ownerUser, "device_policies.xml");
        prepareDeviceOwnerFile(ownerUser, "device_owner_2.xml");

        DevicePolicyManagerServiceTestable dpms;
        final long ident = getContext().binder.clearCallingIdentity();
        try {
            dpms = new DevicePolicyManagerServiceTestable(getServices(), getContext());

            // Simulate access that would cause policy data to be cached in mUserData.
            dpms.isCommonCriteriaModeEnabled(null);

            dpms.systemReady(SystemService.PHASE_LOCK_SETTINGS_READY);
        } finally {
            getContext().binder.restoreCallingIdentity(ident);
        }

        assertThat(readVersionFromXml()).isEqualTo(DevicePolicyManagerService.DPMS_VERSION);

        // DO should be marked as able to grant sensors permission during upgrade and should be
        // reported as such via the API.
        assertThat(dpms.canAdminGrantSensorsPermissions()).isTrue();
    }

    /**
     * Up to Android R DO protected packages were stored in DevicePolicyData, verify that they are
     * moved to ActiveAdmin.
     */
    @Test
    public void testUserControlDisabledPackagesFromR() throws Exception {
        final String oldTag = "protected-packages";
        final String newTag = "protected_packages";
        final int ownerUser = 0;
        mProvider.mUsers = new int[]{0};
        getServices().addUser(ownerUser, FLAG_PRIMARY, USER_TYPE_FULL_SYSTEM);
        writeVersionToXml(2);
        preparePoliciesFile(ownerUser, "protected_packages_device_policies.xml");
        prepareDeviceOwnerFile(ownerUser, "device_owner_2.xml");

        // Validate the setup.
        assertThat(isTagPresent(readPoliciesFileToStream(ownerUser), oldTag)).isTrue();
        assertThat(isTagPresent(readPoliciesFileToStream(ownerUser), newTag)).isFalse();

        mUpgrader.upgradePolicy(3);

        assertThat(readVersionFromXml()).isAtLeast(3);
        assertThat(isTagPresent(readPoliciesFileToStream(ownerUser), oldTag)).isFalse();
        assertThat(isTagPresent(readPoliciesFileToStream(ownerUser), newTag)).isTrue();
    }

    /**
     * In Android S DO protected packages were stored in Owners, verify that they are moved to
     * ActiveAdmin.
     */
    @Test
    public void testUserControlDisabledPackagesFromS() throws Exception {
        final String oldTag = "device-owner-protected-packages";
        final String newTag = "protected_packages";
        final int ownerUser = 0;
        mProvider.mUsers = new int[]{0};
        getServices().addUser(ownerUser, FLAG_PRIMARY, USER_TYPE_FULL_SYSTEM);
        writeVersionToXml(2);
        preparePoliciesFile(ownerUser, "device_policies.xml");
        prepareDeviceOwnerFile(ownerUser, "protected_packages_device_owner_2.xml");

        // Validate the setup.
        assertThat(isTagPresent(readDoToStream(), oldTag)).isTrue();
        assertThat(isTagPresent(readPoliciesFileToStream(ownerUser), newTag)).isFalse();

        mUpgrader.upgradePolicy(3);

        assertThat(readVersionFromXml()).isAtLeast(3);
        assertThat(isTagPresent(readDoToStream(), oldTag)).isFalse();
        assertThat(isTagPresent(readPoliciesFileToStream(ownerUser), newTag)).isTrue();
    }

    @Test
    public void testAdminPackageSuspensionSaved() throws Exception {
        final int ownerUser = 0;
        mProvider.mUsers = new int[]{ownerUser};
        getServices().addUser(ownerUser, FLAG_PRIMARY, USER_TYPE_FULL_SYSTEM);
        setUpPackageManagerForAdmin(admin1, UserHandle.getUid(ownerUser, 123 /* admin app ID */));
        writeVersionToXml(3);
        preparePoliciesFile(ownerUser, "device_policies.xml");
        prepareDeviceOwnerFile(ownerUser, "device_owner_2.xml");

        // Pretend package manager thinks these packages are suspended by the platform.
        Set<String> suspendedPkgs = Set.of("com.some.app", "foo.bar.baz");
        mProvider.mPlatformSuspendedPackages.addAll(suspendedPkgs);

        mUpgrader.upgradePolicy(4);

        assertThat(readVersionFromXml()).isAtLeast(4);

        assertAdminSuspendedPackages(ownerUser, suspendedPkgs);
    }

    private void assertAdminSuspendedPackages(int ownerUser, Set<String> suspendedPkgs)
            throws Exception {
        Document policies = readPolicies(ownerUser);
        Element adminElem =
                (Element) policies.getDocumentElement().getElementsByTagName("admin").item(0);
        Element suspendedElem =
                (Element) adminElem.getElementsByTagName("suspended-packages").item(0);
        NodeList pkgsNodes = suspendedElem.getElementsByTagName("item");
        Set<String> storedSuspendedPkgs = new ArraySet<>();
        for (int i = 0; i < pkgsNodes.getLength(); i++) {
            Element item = (Element) pkgsNodes.item(i);
            storedSuspendedPkgs.add(item.getAttribute("value"));
        }
        assertThat(storedSuspendedPkgs).isEqualTo(suspendedPkgs);
    }

    @Test
    public void testEffectiveKeepProfilesRunningSetToFalse4To5() throws Exception {
        writeVersionToXml(4);

        final int userId = UserHandle.USER_SYSTEM;
        mProvider.mUsers = new int[]{userId};
        preparePoliciesFile(userId, "device_policies.xml");

        mUpgrader.upgradePolicy(5);

        assertThat(readVersionFromXml()).isAtLeast(5);

        Document policies = readPolicies(userId);
        Element keepProfilesRunning = (Element) policies.getDocumentElement()
                .getElementsByTagName("keep-profiles-running").item(0);

        // Default value (false) is not serialized.
        assertThat(keepProfilesRunning).isNull();
    }
    @Test
    public void testEffectiveKeepProfilesRunningIsToFalse4To6() throws Exception {
        writeVersionToXml(4);

        final int userId = UserHandle.USER_SYSTEM;
        mProvider.mUsers = new int[]{userId};
        preparePoliciesFile(userId, "device_policies.xml");

        mUpgrader.upgradePolicy(6);

        assertThat(readVersionFromXml()).isAtLeast(6);

        Document policies = readPolicies(userId);
        Element keepProfilesRunning = (Element) policies.getDocumentElement()
                .getElementsByTagName("keep-profiles-running").item(0);

        // Default value (false) is not serialized.
        assertThat(keepProfilesRunning).isNull();
    }

    /**
     * Verify correct behaviour when upgrading from Android 13
     */
    @Test
    public void testEffectiveKeepProfilesRunningIsToFalse3To6() throws Exception {
        writeVersionToXml(3);

        final int userId = UserHandle.USER_SYSTEM;
        mProvider.mUsers = new int[]{userId};
        preparePoliciesFile(userId, "device_policies.xml");

        mUpgrader.upgradePolicy(6);

        assertThat(readVersionFromXml()).isAtLeast(6);

        Document policies = readPolicies(userId);
        Element keepProfilesRunning = (Element) policies.getDocumentElement()
                .getElementsByTagName("keep-profiles-running").item(0);

        // Default value (false) is not serialized.
        assertThat(keepProfilesRunning).isNull();
    }

    @Test
    public void testEffectiveKeepProfilesRunningMissingInV5() throws Exception {
        writeVersionToXml(5);

        final int userId = UserHandle.USER_SYSTEM;
        mProvider.mUsers = new int[]{userId};
        preparePoliciesFile(userId, "device_policies.xml");

        mUpgrader.upgradePolicy(6);

        assertThat(readVersionFromXml()).isAtLeast(6);

        Document policies = readPolicies(userId);
        Element keepProfilesRunning = (Element) policies.getDocumentElement()
                .getElementsByTagName("keep-profiles-running").item(0);
        assertThat(keepProfilesRunning.getAttribute("value")).isEqualTo("true");
    }

    @Test
    public void testEffectiveKeepProfilesRunningTrueInV5() throws Exception {
        writeVersionToXml(5);

        final int userId = UserHandle.USER_SYSTEM;
        mProvider.mUsers = new int[]{userId};
        preparePoliciesFile(userId, "device_policies_keep_profiles_running_true.xml");

        mUpgrader.upgradePolicy(6);

        assertThat(readVersionFromXml()).isAtLeast(6);

        Document policies = readPolicies(userId);
        Element keepProfilesRunning = (Element) policies.getDocumentElement()
                .getElementsByTagName("keep-profiles-running").item(0);
        assertThat(keepProfilesRunning.getAttribute("value")).isEqualTo("true");
    }

    @Test
    public void testEffectiveKeepProfilesRunningFalseInV5() throws Exception {
        writeVersionToXml(5);

        final int userId = UserHandle.USER_SYSTEM;
        mProvider.mUsers = new int[]{userId};
        preparePoliciesFile(userId, "device_policies_keep_profiles_running_false.xml");

        mUpgrader.upgradePolicy(6);

        assertThat(readVersionFromXml()).isAtLeast(6);

        Document policies = readPolicies(userId);
        Element keepProfilesRunning = (Element) policies.getDocumentElement()
                .getElementsByTagName("keep-profiles-running").item(0);

        // Default value (false) is not serialized.
        assertThat(keepProfilesRunning).isNull();
    }


    @Test
    public void isLatestVersionTested() {
        assertThat(DevicePolicyManagerService.DPMS_VERSION).isEqualTo(LATEST_TESTED_VERSION);
    }

    /**
     * Reads ABX binary XML, converts it to text, and returns as an input stream.
     */
    private InputStream abxToXmlStream(File file) throws Exception {
        FileInputStream fileIn = new FileInputStream(file);
        XmlPullParser in = Xml.newBinaryPullParser();
        in.setInput(fileIn, StandardCharsets.UTF_8.name());

        ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        XmlSerializer out = Xml.newSerializer();
        out.setOutput(byteOut, StandardCharsets.UTF_8.name());

        Xml.copy(in, out);
        out.flush();

        return new ByteArrayInputStream(byteOut.toByteArray());
    }

    private Document readPolicies(int userId) throws Exception {
        File policiesFile = mProvider.makeDevicePoliciesJournaledFile(userId).chooseForRead();
        InputStream is = abxToXmlStream(policiesFile);
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
    }

    private void writeVersionToXml(int dpmsVersion) throws IOException {
        JournaledFile versionFile = mProvider.makePoliciesVersionJournaledFile(0);
        Files.asCharSink(versionFile.chooseForWrite(), Charset.defaultCharset()).write(
                String.format("%d\n", dpmsVersion));
        versionFile.commit();
    }

    private int readVersionFromXml() throws IOException {
        File versionFile = mProvider.makePoliciesVersionJournaledFile(0).chooseForRead();
        String versionString = Files.asCharSource(versionFile,
                Charset.defaultCharset()).readFirstLine();
        return Integer.parseInt(versionString);
    }

    private void preparePoliciesFile(int userId, String assetFile) throws IOException {
        JournaledFile policiesFile = mProvider.makeDevicePoliciesJournaledFile(userId);
        DpmTestUtils.writeToFile(
                policiesFile.chooseForWrite(),
                DpmTestUtils.readAsset(mRealTestContext, "PolicyVersionUpgraderTest/" + assetFile));
        policiesFile.commit();
    }

    private void prepareDeviceOwnerFile(int userId, String assetFile) throws IOException {
        File doFilePath = getDoFilePath();
        String doFileContent = DpmTestUtils.readAsset(mRealTestContext,
                        "PolicyVersionUpgraderTest/" + assetFile)
                // Substitute the right DO userId, XML in resources has 0
                .replace("userId=\"0\"", "userId=\"" + userId + "\"");
        DpmTestUtils.writeToFile(doFilePath, doFileContent);
    }

    private File getDoFilePath() {
        File parentDir = getServices().pathProvider.getDataSystemDirectory();
        File doFilePath = (new File(parentDir, DEVICE_OWNER_XML)).getAbsoluteFile();
        return doFilePath;
    }

    private String readPoliciesFile(int userId) throws IOException {
        File policiesFile = mProvider.makeDevicePoliciesJournaledFile(userId).chooseForRead();
        return new String(Files.asByteSource(policiesFile).read(), Charset.defaultCharset());
    }

    private InputStream readDoToStream() throws IOException {
        return new FileInputStream(getDoFilePath());
    }

    private InputStream readPoliciesFileToStream(int userId) throws IOException {
        File policiesFile = mProvider.makeDevicePoliciesJournaledFile(userId).chooseForRead();
        return new FileInputStream(policiesFile);
    }

    private boolean getBooleanValueTag(InputStream inputXml, String tagName)
            throws IOException, XmlPullParserException {
        TypedXmlPullParser parser = Xml.resolvePullParser(inputXml);

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (tagName.equals(tag)) {
                    String res = parser.getAttributeValue(null, "value");
                    return Boolean.parseBoolean(res);
                }
            }
            eventType = parser.next();
        }

        throw new IllegalStateException("Could not find " + tagName);
    }

    private boolean isTagPresent(InputStream inputXml, String tagName)
            throws IOException, XmlPullParserException {
        TypedXmlPullParser parser = Xml.resolvePullParser(inputXml);

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if (tagName.equals(tag)) {
                    return true;
                }
            }
            eventType = parser.next();
        }

        return false;
    }

    private ActivityInfo createActivityInfo(ComponentName admin) {
        ActivityInfo ai = new ActivityInfo();
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.className = admin.getClassName();
        applicationInfo.uid = 2222;
        ai.applicationInfo = applicationInfo;
        ai.name = admin.getClassName();
        ai.packageName = admin.getPackageName();
        return ai;
    }

    private DeviceAdminInfo createDeviceAdminInfo(ActivityInfo activityInfo) {
        Parcel parcel = Parcel.obtain();
        activityInfo.writeToParcel(parcel, 0);
        parcel.writeInt(0);
        parcel.writeBoolean(true);
        parcel.setDataPosition(0);

        return DeviceAdminInfo.CREATOR.createFromParcel(parcel);
    }
}
