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

import static com.google.common.truth.Truth.assertThat;

import android.app.admin.DeviceAdminInfo;
import android.content.ComponentName;
import android.content.Context;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.JournaledFile;

import com.google.common.io.Files;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@RunWith(JUnit4.class)
public class PolicyVersionUpgraderTest {
    // NOTE: Only change this value if the corresponding CL also adds a test to test the upgrade
    // to the new version.
    private static final int LATEST_TESTED_VERSION = 1;

    private static class FakePolicyUpgraderDataProvider implements PolicyUpgraderDataProvider {
        int mDeviceOwnerUserId;
        boolean mIsFileBasedEncryptionEnabled;
        Map<Integer, ComponentName> mUserToComponent = new HashMap<>();
        Map<ComponentName, DeviceAdminInfo> mComponentToDeviceAdminInfo;
        File mDataDir;

        @Override
        public boolean isUserDeviceOwner(int userId) {
            return userId == mDeviceOwnerUserId;
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
    }

    private final Context mRealTestContext = InstrumentationRegistry.getTargetContext();
    private FakePolicyUpgraderDataProvider mProvider;
    private PolicyVersionUpgrader mUpgrader;
    private File mDataDir;

    @Before
    public void setUp() {
        mProvider = new FakePolicyUpgraderDataProvider();
        mUpgrader = new PolicyVersionUpgrader(mProvider);
        mDataDir = new File(mRealTestContext.getCacheDir(), "test-data");
        mDataDir.getParentFile().mkdirs();
        mProvider.mDataDir = mDataDir;
    }

    @Test
    public void testSameVersionDoesNothing() throws IOException {
        int[] users = new int[] {0};
        writeVersionToXml(DevicePolicyManagerService.DPMS_VERSION);
        preparePoliciesFile(users[0]);
        String oldContents = readPoliciesFile(0);

        mUpgrader.upgradePolicy(users, DevicePolicyManagerService.DPMS_VERSION);

        String newContents = readPoliciesFile(0);
        assertThat(newContents).isEqualTo(oldContents);
    }

    @Test
    public void testUpgrade0To1RemovesPasswordMetrics() throws IOException {
        int[] users = new int[] {0, 10};
        writeVersionToXml(0);
        for (int userId : users) {
            preparePoliciesFile(userId);
        }

        String oldContents = readPoliciesFile(0);
        assertThat(oldContents).contains("active-password");

        mUpgrader.upgradePolicy(users, 1);

        assertThat(readVersionFromXml()).isEqualTo(1);
        assertThat(readPoliciesFile(users[0])).doesNotContain("active-password");
        assertThat(readPoliciesFile(users[1])).doesNotContain("active-password");
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
        return new String(Files.asByteSource(policiesFile).read());
    }
}
