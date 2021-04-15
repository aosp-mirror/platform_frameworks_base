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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.app.compat.ChangeIdStateCache;
import android.app.compat.PackageOverride;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.CompatibilityOverrideConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
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

    private String readFile(File file) throws IOException {
        return new String(Files.readAllBytes(Paths.get(file.toURI())));
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        // Assume userdebug/eng non-final build
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(true);
        when(mBuildClassifier.isFinalBuild()).thenReturn(false);
        when(mBuildClassifier.platformTargetSdk()).thenReturn(30);
        ChangeIdStateCache.disable();
        when(mPackageManager.getApplicationInfo(anyString(), anyInt()))
                .thenThrow(new NameNotFoundException());
    }

    @Test
    public void testUnknownChangeEnabled() throws Exception {
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);
        compatConfig.forceNonDebuggableFinalForTest(false);

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
                .addEnableAfterSdkChangeWithId(2, 1234L)
                .build();

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(2).build()))
            .isFalse();
    }

    @Test
    public void testTargetSdkChangeEnabled() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnableAfterSdkChangeWithId(2, 1234L)
                .build();

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(3).build())).isTrue();
    }

    @Test
    public void testDisabledOverrideTargetSdkChange() throws Exception {
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnableAfterSdkChangeWithIdDefaultDisabled(2, 1234L)
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
        ApplicationInfo info = ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build();
        when(mPackageManager.getApplicationInfo(eq("com.some.package"), anyInt()))
                .thenReturn(info);

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
        ApplicationInfo info = ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build();
        when(mPackageManager.getApplicationInfo(eq("com.some.package"), anyInt()))
                .thenReturn(info);

        compatConfig.addOverride(1234L, "com.some.package", false);

        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.other.package").build())).isTrue();
    }

    @Test
    public void testPackageOverrideUnknownPackage() throws Exception {
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);
        compatConfig.forceNonDebuggableFinalForTest(false);

        ApplicationInfo info = ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build();
        when(mPackageManager.getApplicationInfo(eq("com.some.package"), anyInt()))
                .thenReturn(info);

        compatConfig.addOverride(1234L, "com.some.package", false);

        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.some.package").build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1234L, ApplicationInfoBuilder.create()
                .withPackageName("com.other.package").build())).isTrue();
    }

    @Test
    public void testIsChangeEnabledForInvalidApp() throws Exception {
        final long disabledChangeId = 1234L;
        final long enabledChangeId = 1235L;
        final long targetSdkChangeId = 1236L;
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(enabledChangeId)
                .addDisabledChangeWithId(disabledChangeId)
                .addEnableSinceSdkChangeWithId(42, targetSdkChangeId)
                .build();

        assertThat(compatConfig.isChangeEnabled(enabledChangeId, null)).isTrue();
        assertThat(compatConfig.isChangeEnabled(disabledChangeId, null)).isFalse();
        assertThat(compatConfig.isChangeEnabled(targetSdkChangeId, null)).isTrue();
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
    public void testInstallerCanSetOverrides() throws Exception {
        final long changeId = 1234L;
        final int installerUid = 23;
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addOverridableChangeWithId(1234L)
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

        CompatibilityOverrideConfig config = new CompatibilityOverrideConfig(
                Collections.singletonMap(1234L,
                        new PackageOverride.Builder()
                                .setMaxVersionCode(99L)
                                .setEnabled(true)
                                .build()));

        compatConfig.addOverrides(config, "com.some.package");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isTrue();
    }

    @Test
    public void testApplyDeferredOverridesAfterInstallingApp() throws Exception {
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.notinstalled.foo")
                .debuggable().build();
        when(mPackageManager.getApplicationInfo(eq("com.notinstalled.foo"), anyInt()))
                .thenThrow(new NameNotFoundException());
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L).build();
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);

        // Add override before the app is available.
        compatConfig.addOverride(1234L, "com.notinstalled.foo", true);
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isFalse();

        // Pretend the app is now installed.
        when(mPackageManager.getApplicationInfo(eq("com.notinstalled.foo"), anyInt()))
                .thenReturn(applicationInfo);

        compatConfig.recheckOverrides("com.notinstalled.foo");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isTrue();
    }

    @Test
    public void testOverrideWithAppVersion() throws Exception {
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.installed.foo")
                .withVersionCode(100L)
                .debuggable().build();
        when(mPackageManager.getApplicationInfo(eq("com.installed.foo"), anyInt()))
                .thenReturn(applicationInfo);
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L).build();
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);

        // Add override that doesn't include the installed app version
        CompatibilityOverrideConfig config = new CompatibilityOverrideConfig(
                Collections.singletonMap(1234L,
                        new PackageOverride.Builder()
                                .setMaxVersionCode(99L)
                                .setEnabled(true)
                                .build()));
        compatConfig.addOverrides(config, "com.installed.foo");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isFalse();

        // Add override that does include the installed app version
        config = new CompatibilityOverrideConfig(
                Collections.singletonMap(1234L,
                        new PackageOverride.Builder()
                                .setMinVersionCode(100L)
                                .setMaxVersionCode(100L)
                                .setEnabled(true)
                                .build()));
        compatConfig.addOverrides(config, "com.installed.foo");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isTrue();
    }

    @Test
    public void testApplyDeferredOverridesAfterInstallingAppVersion() throws Exception {
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.notinstalled.foo")
                .withVersionCode(100L)
                .debuggable().build();
        when(mPackageManager.getApplicationInfo(eq("com.notinstalled.foo"), anyInt()))
                .thenThrow(new NameNotFoundException());
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L).build();
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);

        // Add override before the app is available.
        CompatibilityOverrideConfig config = new CompatibilityOverrideConfig(
                Collections.singletonMap(1234L, new PackageOverride.Builder()
                        .setMaxVersionCode(99L)
                        .setEnabled(true)
                        .build()));
        compatConfig.addOverrides(config, "com.notinstalled.foo");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isFalse();

        // Pretend the app is now installed.
        when(mPackageManager.getApplicationInfo(eq("com.notinstalled.foo"), anyInt()))
                .thenReturn(applicationInfo);

        compatConfig.recheckOverrides("com.notinstalled.foo");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isFalse();
    }

    @Test
    public void testApplyDeferredOverrideClearsOverrideAfterUninstall() throws Exception {
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("com.installedapp.foo")
                .debuggable().build();
        when(mPackageManager.getApplicationInfo(eq("com.installedapp.foo"), anyInt()))
                .thenReturn(applicationInfo);

        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L).build();
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);

        // Add override when app is installed.
        compatConfig.addOverride(1234L, "com.installedapp.foo", true);
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isTrue();

        // Pretend the app is now uninstalled.
        when(mPackageManager.getApplicationInfo(eq("com.installedapp.foo"), anyInt()))
                .thenThrow(new NameNotFoundException());

        compatConfig.recheckOverrides("com.installedapp.foo");
        assertThat(compatConfig.isChangeEnabled(1234L, applicationInfo)).isFalse();
    }

    @Test
    public void testApplyDeferredOverrideClearsOverrideAfterChange() throws Exception {
        ApplicationInfo debuggableApp = ApplicationInfoBuilder.create()
                .withPackageName("com.installedapp.foo")
                .debuggable().build();
        ApplicationInfo releaseApp = ApplicationInfoBuilder.create()
                .withPackageName("com.installedapp.foo")
                .build();
        when(mPackageManager.getApplicationInfo(eq("com.installedapp.foo"), anyInt()))
                .thenReturn(debuggableApp);

        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1234L).build();
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(false);
        when(mBuildClassifier.isFinalBuild()).thenReturn(true);

        // Add override for debuggable app.
        compatConfig.addOverride(1234L, "com.installedapp.foo", true);
        assertThat(compatConfig.isChangeEnabled(1234L, debuggableApp)).isTrue();

        // Pretend the app now is no longer debuggable, but has the same package.
        when(mPackageManager.getApplicationInfo(eq("com.installedapp.foo"), anyInt()))
                .thenReturn(releaseApp);

        compatConfig.recheckOverrides("com.installedapp.foo");
        assertThat(compatConfig.isChangeEnabled(1234L, releaseApp)).isFalse();
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
        when(mBuildClassifier.isFinalBuild()).thenReturn(false);
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
        when(mPackageManager.getApplicationInfo(eq("com.some.package"), anyInt()))
                .thenReturn(applicationInfo);

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
                .addEnableSinceSdkChangeWithId(3, 3L)
                .addEnableSinceSdkChangeWithId(4, 4L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("foo.bar")
                .withTargetSdk(2)
                .build();
        when(mPackageManager.getApplicationInfo(eq("foo.bar"), anyInt()))
                .thenReturn(applicationInfo);

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
                .addEnableSinceSdkChangeWithId(3, 3L)
                .addEnableSinceSdkChangeWithId(4, 4L)
                .build();
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("foo.bar")
                .withTargetSdk(2)
                .build();
        when(mPackageManager.getApplicationInfo(eq("foo.bar"), anyInt()))
                .thenReturn(applicationInfo);
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
        compatConfig.forceNonDebuggableFinalForTest(false);

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
        compatConfig.forceNonDebuggableFinalForTest(false);

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
    public void testReadApexConfig() throws IOException {
        String configXml = "<config>"
                + "<compat-change id=\"1234\" name=\"MY_CHANGE1\" enableAfterTargetSdk=\"2\" />"
                + "<compat-change id=\"1235\" name=\"MY_CHANGE2\" disabled=\"true\" />"
                + "<compat-change id=\"1236\" name=\"MY_CHANGE3\" />"
                + "<compat-change id=\"1237\" name=\"MY_CHANGE4\" enableSinceTargetSdk=\"31\" />"
                + "</config>";

        File dir = createTempDir();
        writeToFile(dir, "platform_compat_config.xml", configXml);
        CompatConfig compatConfig = new CompatConfig(mBuildClassifier, mContext);
        compatConfig.forceNonDebuggableFinalForTest(false);

        compatConfig.initConfigFromLib(dir);

        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(1).build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1234L,
            ApplicationInfoBuilder.create().withTargetSdk(3).build())).isTrue();
        assertThat(compatConfig.isChangeEnabled(1235L,
            ApplicationInfoBuilder.create().withTargetSdk(5).build())).isFalse();
        assertThat(compatConfig.isChangeEnabled(1236L,
            ApplicationInfoBuilder.create().withTargetSdk(1).build())).isTrue();
        assertThat(compatConfig.isChangeEnabled(1237L,
            ApplicationInfoBuilder.create().withTargetSdk(31).build())).isTrue();
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
        compatConfig.forceNonDebuggableFinalForTest(false);

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
    public void testSaveOverrides() throws Exception {
        File overridesFile = new File(createTempDir(), "overrides.xml");
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1L)
                .addEnableSinceSdkChangeWithId(2, 2L)
                .build();
        compatConfig.forceNonDebuggableFinalForTest(true);
        compatConfig.initOverrides(overridesFile, new File(""));
        when(mPackageManager.getApplicationInfo(eq("foo.bar"), anyInt()))
                .thenReturn(ApplicationInfoBuilder.create()
                        .withPackageName("foo.bar")
                        .debuggable()
                        .build());
        when(mPackageManager.getApplicationInfo(eq("bar.baz"), anyInt()))
                .thenThrow(new NameNotFoundException());
        compatConfig.addOverrides(
                new CompatibilityOverrideConfig(
                        Collections.singletonMap(
                                1L,
                                new PackageOverride.Builder().setEnabled(true).build())),
                "foo.bar");
        compatConfig.addOverrides(
                new CompatibilityOverrideConfig(
                        Collections.singletonMap(
                                2L,
                                new PackageOverride.Builder().setEnabled(false).build())),
                "bar.baz");

        assertThat(readFile(overridesFile)).isEqualTo("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<overrides>\n"
                + "    <change-overrides changeId=\"1\">\n"
                + "        <validated>\n"
                + "            <override-value packageName=\"foo.bar\" enabled=\"true\">\n"
                + "            </override-value>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"foo.bar\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "    <change-overrides changeId=\"2\">\n"
                + "        <validated>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"bar.baz\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"false\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "</overrides>\n");
    }

    @Test
    public void testSaveOverridesWithRanges() throws Exception {
        File overridesFile = new File(createTempDir(), "overrides.xml");
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1L)
                .addEnableSinceSdkChangeWithId(2, 2L)
                .build();
        compatConfig.forceNonDebuggableFinalForTest(true);
        compatConfig.initOverrides(overridesFile, new File(""));

        compatConfig.addOverrides(new CompatibilityOverrideConfig(Collections.singletonMap(1L,
                new PackageOverride.Builder()
                        .setMinVersionCode(99L)
                        .setMaxVersionCode(101L)
                        .setEnabled(true)
                        .build())), "foo.bar");

        assertThat(readFile(overridesFile)).isEqualTo("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<overrides>\n"
                + "    <change-overrides changeId=\"1\">\n"
                + "        <validated>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"foo.bar\" "
                + "minVersionCode=\"99\" maxVersionCode=\"101\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "</overrides>\n");
    }

    @Test
    public void testInitOverridesRaw() throws Exception {
        File tempDir = createTempDir();
        File overridesFile = new File(tempDir, "overrides.xml");
        // Change 1 is enabled for foo.bar (validated)
        // Change 2 is disabled for bar.baz (raw)
        String xmlData = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<overrides>\n"
                + "    <change-overrides changeId=\"1\">\n"
                + "        <validated>\n"
                + "            <override-value packageName=\"foo.bar\" enabled=\"true\">\n"
                + "            </override-value>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"foo.bar\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "    <change-overrides changeId=\"2\">\n"
                + "        <validated>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"bar.baz\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"false\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "</overrides>\n";
        writeToFile(tempDir, "overrides.xml", xmlData);
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1L)
                .addEnableSinceSdkChangeWithId(2, 2L)
                .build();
        compatConfig.forceNonDebuggableFinalForTest(true);
        compatConfig.initOverrides(overridesFile, new File(""));
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("foo.bar")
                .withVersionCode(100L)
                .debuggable()
                .build();
        when(mPackageManager.getApplicationInfo(eq("foo.bar"), anyInt()))
                .thenReturn(applicationInfo);
        when(mPackageManager.getApplicationInfo(eq("bar.baz"), anyInt()))
                .thenThrow(new NameNotFoundException());

        assertThat(compatConfig.isChangeEnabled(1L, applicationInfo)).isTrue();
        assertThat(compatConfig.willChangeBeEnabled(2L, "bar.baz")).isFalse();

        compatConfig.recheckOverrides("foo.bar");
        assertThat(compatConfig.isChangeEnabled(1L, applicationInfo)).isTrue();
    }

    @Test
    public void testInitOverridesDeferred() throws Exception {
        File tempDir = createTempDir();
        File overridesFile = new File(tempDir, "overrides.xml");
        // Change 1 is enabled for foo.bar (validated)
        // Change 2 is disabled for bar.baz (deferred)
        String xmlData = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                       + "<overrides>"
                       +    "<change-overrides changeId=\"1\">"
                       +        "<deferred/>"
                       +        "<validated>"
                       +            "<override-value packageName=\"foo.bar\" enabled=\"true\"/>"
                       +        "</validated>"
                       +    "</change-overrides>"
                       +    "<change-overrides changeId=\"2\">"
                       +        "<deferred>"
                       +           "<override-value packageName=\"bar.baz\" enabled=\"false\"/>"
                       +        "</deferred>"
                       +        "<validated/>"
                       +    "</change-overrides>"
                       + "</overrides>";
        writeToFile(tempDir, "overrides.xml", xmlData);
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1L)
                .addEnableSinceSdkChangeWithId(2, 2L)
                .build();
        compatConfig.forceNonDebuggableFinalForTest(true);
        compatConfig.initOverrides(overridesFile, new File(""));
        ApplicationInfo applicationInfo = ApplicationInfoBuilder.create()
                .withPackageName("foo.bar")
                .debuggable()
                .build();
        when(mPackageManager.getApplicationInfo(eq("foo.bar"), anyInt()))
                .thenReturn(applicationInfo);
        when(mPackageManager.getApplicationInfo(eq("bar.baz"), anyInt()))
                .thenThrow(new NameNotFoundException());

        assertThat(compatConfig.isChangeEnabled(1L, applicationInfo)).isTrue();
        assertThat(compatConfig.willChangeBeEnabled(2L, "bar.baz")).isFalse();
    }

    @Test
    public void testInitOverridesWithStaticFile() throws Exception {
        File tempDir = createTempDir();
        File dynamicOverridesFile = new File(tempDir, "dynamic_overrides.xml");
        File staticOverridesFile = new File(tempDir, "static_overrides.xml");
        // Change 1 is enabled for foo.bar (raw)
        // Change 2 is disabled for bar.baz (raw)
        String dynamicXmlData = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<overrides>"
                +    "<change-overrides changeId=\"1\">"
                +        "<raw>"
                + "            <raw-override-value packageName=\"foo.bar\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                +        "</raw>"
                +    "</change-overrides>"
                +    "<change-overrides changeId=\"2\">"
                +        "<raw>"
                + "            <raw-override-value packageName=\"bar.baz\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"false\">\n"
                + "            </raw-override-value>\n"
                +        "</raw>"
                +    "</change-overrides>"
                + "</overrides>";
        writeToFile(tempDir, "dynamic_overrides.xml", dynamicXmlData);
        // Change 2 is enabled for foo.bar and bar.baz (raw)
        // Change 3 is enabled for bar.baz (raw)
        String staticXmlData = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<overrides>"
                +    "<change-overrides changeId=\"2\">"
                +        "<raw>"
                + "            <raw-override-value packageName=\"foo.bar\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                + "            <raw-override-value packageName=\"bar.baz\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                +        "</raw>"
                +    "</change-overrides>"
                +    "<change-overrides changeId=\"3\">"
                +        "<raw>"
                + "            <raw-override-value packageName=\"bar.baz\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                +        "</raw>"
                +    "</change-overrides>"
                + "</overrides>";
        writeToFile(tempDir, "static_overrides.xml", staticXmlData);
        CompatConfig compatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addDisabledChangeWithId(1L)
                .addDisabledChangeWithId(2L)
                .addDisabledChangeWithId(3L)
                .build();
        compatConfig.forceNonDebuggableFinalForTest(true);
        // Adding an override that will be cleared after initOverrides is called.
        compatConfig.addOverride(1L, "bar.baz", true);
        compatConfig.initOverrides(dynamicOverridesFile, staticOverridesFile);
        when(mPackageManager.getApplicationInfo(eq("foo.bar"), anyInt()))
                .thenThrow(new NameNotFoundException());
        when(mPackageManager.getApplicationInfo(eq("bar.baz"), anyInt()))
                .thenThrow(new NameNotFoundException());

        assertThat(compatConfig.willChangeBeEnabled(1L, "foo.bar")).isTrue();
        assertThat(compatConfig.willChangeBeEnabled(2L, "foo.bar")).isTrue();
        assertThat(compatConfig.willChangeBeEnabled(2L, "bar.baz")).isFalse();
        assertThat(compatConfig.willChangeBeEnabled(3L, "bar.baz")).isTrue();
        assertThat(readFile(dynamicOverridesFile))
                .isEqualTo("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<overrides>\n"
                + "    <change-overrides changeId=\"1\">\n"
                + "        <validated>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"foo.bar\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "    <change-overrides changeId=\"2\">\n"
                + "        <validated>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"foo.bar\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                + "            <raw-override-value packageName=\"bar.baz\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"false\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "    <change-overrides changeId=\"3\">\n"
                + "        <validated>\n"
                + "        </validated>\n"
                + "        <raw>\n"
                + "            <raw-override-value packageName=\"bar.baz\" "
                + "minVersionCode=\"-9223372036854775808\" "
                + "maxVersionCode=\"9223372036854775807\" enabled=\"true\">\n"
                + "            </raw-override-value>\n"
                + "        </raw>\n"
                + "    </change-overrides>\n"
                + "</overrides>\n");
    }
}
