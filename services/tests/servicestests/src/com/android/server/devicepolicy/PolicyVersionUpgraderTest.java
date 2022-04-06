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

import static android.os.UserHandle.USER_SYSTEM;

import static com.android.server.devicepolicy.DevicePolicyManagerService.POLICIES_VERSION_XML;
import static com.android.server.devicepolicy.DpmTestUtils.writeInputStreamToFile;

import static com.google.common.truth.Truth.assertThat;

import android.app.admin.DeviceAdminInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.IpcDataCache;
import android.os.Parcel;
import android.os.UserHandle;
import android.util.TypedXmlPullParser;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;

import com.android.frameworks.servicestests.R;
import com.android.internal.util.JournaledFile;
import com.android.server.SystemService;

import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@RunWith(JUnit4.class)
public class PolicyVersionUpgraderTest extends DpmTestBase {
    // NOTE: Only change this value if the corresponding CL also adds a test to test the upgrade
    // to the new version.
    private static final int LATEST_TESTED_VERSION = 2;
    public static final String PERMISSIONS_TAG = "admin-can-grant-sensors-permissions";
    private ComponentName mFakeAdmin;

    private static class FakePolicyUpgraderDataProvider implements PolicyUpgraderDataProvider {
        int mDeviceOwnerUserId;
        ComponentName mDeviceOwnerComponent = new ComponentName("", "");
        boolean mIsFileBasedEncryptionEnabled;
        Map<Integer, ComponentName> mUserToComponent = new HashMap<>();
        Map<ComponentName, DeviceAdminInfo> mComponentToDeviceAdminInfo = new HashMap<>();
        File mDataDir;
        int[] mUsers;

        @Override
        public boolean isDeviceOwner(int userId, ComponentName who) {
            return userId == mDeviceOwnerUserId && mDeviceOwnerComponent.equals(who);
        }

        @Override
        public boolean storageManagerIsFileBasedEncryptionEnabled() {
            return mIsFileBasedEncryptionEnabled;
        }

        private JournaledFile makeJournaledFile(int userId, String fileName) {
            File parentDir = new File(mDataDir, String.format("user%d", userId));
            if (!parentDir.exists()) {
                parentDir.mkdirs();
            }

            final String base = new File(parentDir, fileName).getAbsolutePath();
            return new JournaledFile(new File(base), new File(base + ".tmp"));
        }

        @Override
        public JournaledFile makeDevicePoliciesJournaledFile(int userId) {
            return makeJournaledFile(userId, DevicePolicyManagerService.DEVICE_POLICIES_XML);
        }

        @Override
        public JournaledFile makePoliciesVersionJournaledFile(int userId) {
            return makeJournaledFile(userId, DevicePolicyManagerService.POLICIES_VERSION_XML);
        }

        @Override
        public ComponentName getOwnerComponent(int userId) {
            return mUserToComponent.get(userId);
        }

        @Override
        public Function<ComponentName, DeviceAdminInfo> getAdminInfoSupplier(int userId) {
            return componentName -> mComponentToDeviceAdminInfo.get(componentName);
        }

        @Override
        public int[] getUsersForUpgrade() {
            return mUsers;
        }
    }

    private final Context mRealTestContext = InstrumentationRegistry.getTargetContext();
    private FakePolicyUpgraderDataProvider mProvider;
    private PolicyVersionUpgrader mUpgrader;
    private File mDataDir;

    @Before
    public void setUp() {
        // Disable caches in this test process. This must happen early, since some of the
        // following initialization steps invalidate caches.
        IpcDataCache.disableForTestMode();

        mProvider = new FakePolicyUpgraderDataProvider();
        mUpgrader = new PolicyVersionUpgrader(mProvider);
        mDataDir = new File(mRealTestContext.getCacheDir(), "test-data");
        mDataDir.getParentFile().mkdirs();
        // Prepare provider.
        mProvider.mDataDir = mDataDir;
        mFakeAdmin = new ComponentName(
                "com.android.frameworks.servicestests",
                        "com.android.server.devicepolicy.DummyDeviceAdmins$Admin1");
        ActivityInfo activityInfo = createActivityInfo(mFakeAdmin);
        DeviceAdminInfo dai = createDeviceAdminInfo(activityInfo);
        mProvider.mComponentToDeviceAdminInfo.put(mFakeAdmin, dai);
        mProvider.mUsers = new int[] {0};
    }

    @Test
    public void testSameVersionDoesNothing() throws IOException {
        writeVersionToXml(DevicePolicyManagerService.DPMS_VERSION);
        final int userId = mProvider.mUsers[0];
        preparePoliciesFile(userId);
        String oldContents = readPoliciesFile(userId);

        mUpgrader.upgradePolicy(DevicePolicyManagerService.DPMS_VERSION);

        String newContents = readPoliciesFile(0);
        assertThat(newContents).isEqualTo(oldContents);
    }

    @Test
    public void testUpgrade0To1RemovesPasswordMetrics() throws IOException, XmlPullParserException {
        final String activePasswordTag = "active-password";
        mProvider.mUsers = new int[] {0, 10};
        writeVersionToXml(0);
        for (int userId : mProvider.mUsers) {
            preparePoliciesFile(userId);
        }
        // Validate test set-up.
        assertThat(isTagPresent(readPoliciesFileToStream(0), activePasswordTag)).isTrue();

        mUpgrader.upgradePolicy(1);

        assertThat(readVersionFromXml()).isGreaterThan(1);
        for (int user: mProvider.mUsers) {
            assertThat(isTagPresent(readPoliciesFileToStream(user), activePasswordTag)).isFalse();
        }
    }

    @Test
    public void testUpgrade1To2MarksDoForPermissionControl()
            throws IOException, XmlPullParserException {
        final int ownerUser = 10;
        mProvider.mUsers = new int[] {0, ownerUser};
        writeVersionToXml(1);
        for (int userId : mProvider.mUsers) {
            preparePoliciesFile(userId);
        }
        mProvider.mDeviceOwnerUserId = ownerUser;
        mProvider.mDeviceOwnerComponent = mFakeAdmin;
        mProvider.mUserToComponent.put(ownerUser, mFakeAdmin);

        mUpgrader.upgradePolicy(2);

        assertThat(readVersionFromXml()).isEqualTo(2);
        assertThat(getBooleanValueTag(readPoliciesFileToStream(mProvider.mUsers[0]),
                PERMISSIONS_TAG)).isFalse();
        assertThat(getBooleanValueTag(readPoliciesFileToStream(ownerUser),
                PERMISSIONS_TAG)).isTrue();
    }

    @Test
    public void testNoStaleDataInCacheAfterUpgrade() throws Exception {
        setUpPackageManagerForAdmin(admin1, UserHandle.getUid(USER_SYSTEM, 123 /* admin app ID */));
        // Reusing COPE migration policy files there, only DO on user 0 is needed.
        writeInputStreamToFile(getRawStream(R.raw.comp_policies_primary),
                new File(getServices().systemUserDataDir, "device_policies.xml")
                        .getAbsoluteFile());
        writeInputStreamToFile(getRawStream(R.raw.comp_device_owner),
                new File(getServices().dataDir, "device_owner_2.xml")
                        .getAbsoluteFile());

        // Write policy version 0
        File versionFilePath =
                new File(getServices().systemUserDataDir, POLICIES_VERSION_XML).getAbsoluteFile();
        DpmTestUtils.writeToFile(versionFilePath, "0\n");

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

        // DO should be marked as able to grant sensors permission during upgrade and should be
        // reported as such via the API.
        assertThat(dpms.canAdminGrantSensorsPermissionsForUser(/* userId= */0)).isTrue();
    }

    @Test
    public void isLatestVersionTested() {
        assertThat(DevicePolicyManagerService.DPMS_VERSION).isEqualTo(LATEST_TESTED_VERSION);
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

    private void preparePoliciesFile(int userId) throws IOException {
        JournaledFile policiesFile = mProvider.makeDevicePoliciesJournaledFile(userId);
        DpmTestUtils.writeToFile(
                policiesFile.chooseForWrite(),
                DpmTestUtils.readAsset(mRealTestContext,
                        "PolicyVersionUpgraderTest/device_policies.xml"));
        policiesFile.commit();
    }

    private String readPoliciesFile(int userId) throws IOException {
        File policiesFile = mProvider.makeDevicePoliciesJournaledFile(userId).chooseForRead();
        FileReader reader = new FileReader(policiesFile);
        return new String(Files.asByteSource(policiesFile).read(), Charset.defaultCharset());
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
