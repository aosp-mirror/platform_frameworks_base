/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.compat;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.compat.AndroidBuildClassifier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class CompatConfigTest {

    @Mock
    private Context mContext;
    @Mock
    PackageManager mPackageManager;
    @Mock
    private AndroidBuildClassifier mBuildClassifier;

    private File createTempDir() {
        String base = System.getProperty("java.io.tmpdir");
        File dir = new File(base, UUID.randomUUID().toString());
        assertThat(dir.mkdirs()).isTrue();
        return dir;
    }

    private void writeToFile(File dir, String filename, String content) throws IOException {
        OutputStream os = new FileOutputStream(new File(dir, filename));
        os.write(content.getBytes());
        os.close();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        // Assume userdebug/eng non-final build
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(true);
        when(mBuildClassifier.isFinalBuild()).thenReturn(false);
    }

    @Test
    public void testUnknownChangeEnabled() throws Exception {
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);
        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create().build()))
            .isTrue();
    }

    @Test
    public void testDisabledChangeDisabled() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L)
                .build();

        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create().build()))
            .isFalse();
    }

    @Test
    public void testTargetSdkChangeDisabled() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addTargetSdkChangeWithId(2, 1234L)
                .build();

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(2).build()))
            .isFalse();
    }

    @Test
    public void testTargetSdkChangeEnabled() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addTargetSdkChangeWithId(2, 1234L)
                .build();

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(3).build())).isTrue();
    }

    @Test
    public void testDisabledOverrideTargetSdkChange() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addTargetSdkDisabledChangeWithId(2, 1234L)
                .build();

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(3).build())).isFalse();
    }

    @Test
    public void testGetDisabledChanges() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L)
                .addEnabledChangeWithId(2345L)
                .build();

        assertThat(compatConfig.getDisabledChanges(
            ApplicationInfoBuilder.create().build())).asList().containsExactly(1234L);
    }

    @Test
    public void testGetDisabledChangesSorted() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L)
                .addDisabledChangeWithId(123L)
                .addDisabledChangeWithId(12L)
                .build();

        assertThat(compatConfig.getDisabledChanges(ApplicationInfoBuilder.create().build()))
            .asList().containsExactly(12L, 123L, 1234L);
    }

    @Test
    public void testPackageOverrideEnabled() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L)
                .build();

        compatConfig.addOverride(1234L, "com.some.package", true);

        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build())).isTrue();
        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.other.package").build())).isFalse();
    }

    @Test
    public void testPackageOverrideDisabled() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1234L)
                .build();

        compatConfig.addOverride(1234L, "com.some.package", false);

        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.other.package").build())).isTrue();
    }

    @Test
    public void testPackageOverrideUnknownPackage() throws Exception {
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);

        compatConfig.addOverride(1234L, "com.some.package", false);

        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.other.package").build())).isTrue();
    }

    @Test
    public void testPreventAddOverride() throws Exception {
        final long changeId = 1234L;
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.some.package")
                .build();
        PackageManager packageManager = mock(PackageManager.class);
        when(mContext.getPackageManager()).thenReturn(packageManager);
        when(packageManager.getApplicationInfo(eq("com.some.package"), anyInt()))
            .thenReturn(applicationInfo);

        // Force the validator to prevent overriding the change by using a user build.
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);

        assertThrows(SecurityException.class,
                () -> compatConfig.addOverride(1234L, "com.some.package", true)
        );
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isFalse();
    }

    @Test
    public void testLoggingOnlyChangePreventAddOverride() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addLoggingOnlyChangeWithId(1234L)
                .build();

        assertThrows(SecurityException.class,
                () -> compatConfig.addOverride(1234L, "com.some.package", true)
        );
    }

    @Test
    public void testPreventRemoveOverride() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.some.package")
                .build();
        when(mPackageManager.getApplicationInfo(eq("com.some.package"), anyInt()))
            .thenReturn(applicationInfo);
        // Assume the override was allowed to be added.
        compatConfig.addOverride(1234L, "com.some.package", true);

        // Validator allows turning on the change.
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isTrue();

        // Reject all override attempts.
        // Force the validator to prevent overriding the change by using a user build.
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);
        // Try to turn off change, but validator prevents it.
        assertThrows(SecurityException.class,
                () -> compatConfig.removeOverride(1234L, "com.some.package"));
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isTrue();
    }

    @Test
    public void testAllowRemoveOverrideNoOverride() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L)
                .addLoggingOnlyChangeWithId(2L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.some.package")
                .build();
        when(mPackageManager.getApplicationInfo(eq("com.some.package"), anyInt()))
                .thenReturn(applicationInfo);

        // Reject all override attempts.
        // Force the validator to prevent overriding the change by using a user build.
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);
        // Try to remove a non existing override, and it doesn't fail.
        assertThat(compatConfig.removeOverride(1234L, "com.some.package")).isFalse();
        assertThat(compatConfig.removeOverride(2L, "com.some.package")).isFalse();
        compatConfig.removePackageOverrides("com.some.package");
    }

    @Test
    public void testRemovePackageOverride() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1234L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.some.package")
                .build();

        assertThat(compatConfig.addOverride(1234L, "com.some.package", false)).isTrue();
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isFalse();

        compatConfig.removeOverride(1234L, "com.some.package");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isTrue();
    }

    @Test
    public void testEnableTargetSdkChangesForPackage() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1L)
                .addDisabledChangeWithId(2L)
                .addTargetSdkChangeWithId(3, 3L)
                .addTargetSdkChangeWithId(4, 4L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("foo.bar")
                .withTargetSdk(2)
                .build();

        assertThat(compatConfig.isChangeEnabled(3, applicationInfo)).isFalse();
        assertThat(compatConfig.isChangeEnabled(4, applicationInfo)).isFalse();

        assertThat(compatConfig.enableTargetSdkChangesForPackage("foo.bar", 3)).isEqualTo(1);
        assertThat(compatConfig.isChangeEnabled(3, applicationInfo)).isTrue();
        assertThat(compatConfig.isChangeEnabled(4, applicationInfo)).isFalse();
    }

    @Test
    public void testDisableTargetSdkChangesForPackage() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1L)
                .addDisabledChangeWithId(2L)
                .addTargetSdkChangeWithId(3, 3L)
                .addTargetSdkChangeWithId(4, 4L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("foo.bar")
                .withTargetSdk(2)
                .build();

        assertThat(compatConfig.enableTargetSdkChangesForPackage("foo.bar", 3)).isEqualTo(1);
        assertThat(compatConfig.isChangeEnabled(3, applicationInfo)).isTrue();
        assertThat(compatConfig.isChangeEnabled(4, applicationInfo)).isFalse();

        assertThat(compatConfig.disableTargetSdkChangesForPackage("foo.bar", 3)).isEqualTo(1);
        assertThat(compatConfig.isChangeEnabled(3, applicationInfo)).isFalse();
        assertThat(compatConfig.isChangeEnabled(4, applicationInfo)).isFalse();
    }

    @Test
    public void testLookupChangeId() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithIdAndName(1234L, "MY_CHANGE")
                .addEnabledChangeWithIdAndName(2345L, "MY_OTHER_CHANGE")
                .build();

        assertThat(compatConfig.lookupChangeId("MY_CHANGE")).isEqualTo(1234L);
    }

    @Test
    public void testLookupChangeIdNotPresent() throws Exception {
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);
        assertThat(compatConfig.lookupChangeId("MY_CHANGE")).isEqualTo(-1L);
    }

    @Test
    public void testReadConfig() throws IOException {
        String configXml = "<config>"
                + "<compat-change id=\"1234\" name=\"MY_CHANGE1\" enableAfterTargetSdk=\"2\" />"
                + "<compat-change id=\"1235\" name=\"MY_CHANGE2\" disabled=\"true\" />"
                + "<compat-change id=\"1236\" name=\"MY_CHANGE3\" />"
                + "</config>";

        File dir = createTempDir();
        writeToFile(dir, "platform_compat_config.xml", configXml);
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);
        compatConfig.initConfigFromLib(dir);

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(1).build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(3).build())).isTrue();
        assertThat(compatConfig.isChangeEnabled(1235L,
            ApplicationInfoBuilder.create().withTargetSdk(5).build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1236L,
            ApplicationInfoBuilder.create().withTargetSdk(1).build())).isTrue();
    }

    @Test
    public void testReadConfigMultipleFiles() throws IOException {
        String configXml1 = "<config>"
                + "<compat-change id=\"1234\" name=\"MY_CHANGE1\" enableAfterTargetSdk=\"2\" />"
                + "</config>";
        String configXml2 = "<config>"
                + "<compat-change id=\"1235\" name=\"MY_CHANGE2\" disabled=\"true\" />"
                + "<compat-change id=\"1236\" name=\"MY_CHANGE3\" />"
                + "</config>";

        File dir = createTempDir();
        writeToFile(dir, "libcore_platform_compat_config.xml", configXml1);
        writeToFile(dir, "frameworks_platform_compat_config.xml", configXml2);
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);
        compatConfig.initConfigFromLib(dir);

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(1).build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(3).build())).isTrue();
        assertThat(compatConfig.isChangeEnabled(1235L,
            ApplicationInfoBuilder.create().withTargetSdk(5).build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1236L,
            ApplicationInfoBuilder.create().withTargetSdk(1).build())).isTrue();
    }
}
