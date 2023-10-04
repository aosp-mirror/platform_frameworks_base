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

package com.android.internal.content.res;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackagePartitions;
import android.os.FileUtils;
import android.os.SystemProperties;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;
import com.android.internal.content.om.OverlayConfig;
import com.android.internal.content.om.OverlayConfig.IdmapInvocation;
import com.android.internal.content.om.OverlayConfigParser.OverlayPartition;
import com.android.internal.content.om.OverlayScanner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class OverlayConfigTest {
    private static final String TEST_APK_PACKAGE_NAME =
            "com.android.frameworks.coretests.overlay_config";

    private ExpectedException mExpectedException = ExpectedException.none();
    private OverlayConfigIterationRule mScannerRule = new OverlayConfigIterationRule();
    private TemporaryFolder mTestFolder = new TemporaryFolder();

    @Rule
    public RuleChain chain = RuleChain.outerRule(mExpectedException)
            .around(mTestFolder).around(mScannerRule);

    private OverlayConfig createConfigImpl() throws IOException {
        return new OverlayConfig(mTestFolder.getRoot().getCanonicalFile(),
                mScannerRule.getScannerFactory(), mScannerRule.getPackageProvider());
    }

    private File createFile(String fileName) throws IOException {
        return createFile(fileName, "");
    }

    private File createFile(String fileName, String content) throws IOException {
        final File f = new File(String.format("%s/%s", mTestFolder.getRoot(), fileName));
        if (!f.getParentFile().equals(mTestFolder.getRoot())) {
            f.getParentFile().mkdirs();
        }
        FileUtils.stringToFile(f.getPath(), content);
        return f;
    }

    private static void assertConfig(OverlayConfig overlayConfig, String packageName,
            boolean mutable, boolean enabled, int configIndex) {
        final OverlayConfig.Configuration config = overlayConfig.getConfiguration(packageName);
        assertNotNull(config);
        assertEquals(mutable, config.parsedConfig.mutable);
        assertEquals(enabled, config.parsedConfig.enabled);
        assertEquals(configIndex, config.configIndex);
    }

    private String generatePartitionOrderString(List<OverlayPartition> partitions) {
        StringBuilder partitionOrder = new StringBuilder();
        for (int i = 0; i < partitions.size(); i++) {
            partitionOrder.append(partitions.get(i).getName());
            if (i < partitions.size() - 1) {
                partitionOrder.append(", ");
            }
        }
        return partitionOrder.toString();
    }

    @Test
    public void testImmutableAfterNonImmutableFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("immutable overlays must precede mutable overlays");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"true\" />"
                        + "  <overlay package=\"two\" mutable=\"false\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one");
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two");
        createConfigImpl();
    }

    @Test
    public void testConfigureAbsentPackageFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("not present in partition");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"true\" />"
                        + "</config>");

        createConfigImpl();
    }

    @Test
    public void testConfigurePackageTwiceFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("configured multiple times in a single partition");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"true\" />"
                        + "  <overlay package=\"one\" mutable=\"false\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one");
        createConfigImpl();
    }

    @Test
    public void testConfigureOverlayAcrossPartitionsFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("not present in partition");

        createFile("/vendor/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one");
        createConfigImpl();
    }

    @Test
    public void testConfigureOverlayOutsideOverlayDirFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("not present in partition");

        createFile("/vendor/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/app/one.apk"), "one");
        createConfigImpl();
    }

    @Test
    public void testMergeOAbsolutePathFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("must be relative to the directory");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <merge path=\"/product/overlay/config/auto-generated-config.xml\" />"
                        + "</config>");

        createConfigImpl();
    }

    @Test
    public void testMergeOutsideDirFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("outside of configuration directory");

        createFile("/product/overlay/auto-generated-config.xml");
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <merge path=\"../auto-generated-config.xml\" />"
                        + "</config>");

        createConfigImpl();
    }

    @Test
    public void testMergeOutsidePartitionFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("outside of configuration directory");

        createFile("/vendor/overlay/config/config2.xml");
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <merge path=\"../../../vendor/overlay/config/config2.xml\" />"
                        + "</config>");

        createConfigImpl();
    }

    @Test
    public void testMergeCircularFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("Maximum <merge> depth exceeded");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <merge path=\"config2.xml\" />"
                        + "</config>");
        createFile("/product/overlay/config/config2.xml",
                "<config>"
                        + "  <merge path=\"config.xml\" />"
                        + "</config>");

        createConfigImpl();
    }

    @Test
    public void testMergeMissingFileFails() throws IOException {
        mExpectedException.expect(IllegalStateException.class);
        mExpectedException.expectMessage("does not exist");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <merge path=\"config2.xml\" />"
                        + "</config>");
        createConfigImpl();
    }

    @Test
    public void testProductOverridesVendor() throws IOException {
        createFile("/vendor/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"false\" />"
                        + "</config>");
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one");
        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one");

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", true, true, 1);
    }

    @Test
    public void testPartitionPrecedence() throws IOException {
        createFile("/vendor/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" enabled=\"true\" />"
                        + "</config>");
        createFile("/odm/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"two\" enabled=\"true\" />"
                        + "</config>");
        createFile("/oem/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"three\" enabled=\"true\" />"
                        + "</config>");
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"four\" enabled=\"true\" />"
                        + "</config>");
        createFile("/system_ext/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"five\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one");
        mScannerRule.addOverlay(createFile("/odm/overlay/two.apk"), "two");
        mScannerRule.addOverlay(createFile("/oem/overlay/three.apk"), "three");
        mScannerRule.addOverlay(createFile("/product/overlay/four.apk"), "four");
        mScannerRule.addOverlay(createFile("/system_ext/overlay/five.apk"), "five");

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", true, true, 0);
        assertConfig(overlayConfig, "two", true, true, 1);
        assertConfig(overlayConfig, "three", true, true, 2);
        assertConfig(overlayConfig, "four", true, true, 3);
        assertConfig(overlayConfig, "five", true, true, 4);
    }

    @Test
    public void testPartialConfigPartitionPrecedence() throws IOException {
        createFile("/odm/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"two\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "android", 0, true,
                1);
        mScannerRule.addOverlay(createFile("/odm/overlay/two.apk"), "two");
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three", "android", 0,
                true, 0);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 0);
        assertConfig(overlayConfig, "two", true, true, 1);
        assertConfig(overlayConfig, "three", false, true, 2);
    }

    @Test
    public void testNoConfigPartitionPrecedence() throws IOException {
        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "android", 0, true,
                1);
        mScannerRule.addOverlay(createFile("/odm/overlay/two.apk"), "two", "android", 0, true, 2);
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three", "android", 0,
                true, 0);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 0);
        assertConfig(overlayConfig, "two", false, true, 1);
        assertConfig(overlayConfig, "three", false, true, 2);
    }

    @Test
    public void testImmutable() throws IOException {
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" mutable=\"false\" />"
                        + "  <overlay package=\"two\" />"
                        + "  <overlay package=\"three\" mutable=\"true\" />"
                        + "</config>");


        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one");
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two");
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three");

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, false, 0);
        assertConfig(overlayConfig, "two", true, false, 1);
        assertConfig(overlayConfig, "three", true, false, 2);
    }

    @Test
    public void testEnabled() throws IOException {
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" />"
                        + "  <overlay package=\"two\" enabled=\"true\" />"
                        + "  <overlay package=\"three\" enabled=\"false\" />"
                        + "</config>");


        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one");
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two");
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three");

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", true, false, 0);
        assertConfig(overlayConfig, "two", true, true, 1);
        assertConfig(overlayConfig, "three", true, false, 2);
    }

    @Test
    public void testMerge() throws IOException {
        createFile("/product/overlay/config/auto-generated-config.xml",
                "<config>"
                        + "  <overlay package=\"two\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"three\" mutable=\"false\" enabled=\"true\" />"
                        + "</config>");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" mutable=\"false\" enabled=\"true\" />"
                        + "  <merge path=\"auto-generated-config.xml\" />"
                        + "  <overlay package=\"four\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one");
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two");
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three");
        mScannerRule.addOverlay(createFile("/product/overlay/four.apk"), "four");

        final OverlayConfig overlayConfig = createConfigImpl();
        OverlayConfig.Configuration o1 = overlayConfig.getConfiguration("one");
        assertNotNull(o1);
        assertFalse(o1.parsedConfig.mutable);
        assertTrue(o1.parsedConfig.enabled);
        assertEquals(0, o1.configIndex);

        OverlayConfig.Configuration o2 = overlayConfig.getConfiguration("two");
        assertNotNull(o2);
        assertFalse(o2.parsedConfig.mutable);
        assertTrue(o2.parsedConfig.enabled);
        assertEquals(1, o2.configIndex);

        OverlayConfig.Configuration o3 = overlayConfig.getConfiguration("three");
        assertNotNull(o3);
        assertFalse(o3.parsedConfig.mutable);
        assertTrue(o3.parsedConfig.enabled);
        assertEquals(2, o3.configIndex);

        OverlayConfig.Configuration o4 = overlayConfig.getConfiguration("four");
        assertNotNull(o4);
        assertTrue(o4.parsedConfig.mutable);
        assertTrue(o4.parsedConfig.enabled);
        assertEquals(3, o4.configIndex);
    }

    @Test
    public void testIdmapInvocationsFrameworkImmutable() throws IOException {
        createFile("/vendor/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"two\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"three\" enabled=\"true\" />"
                        + "</config>");

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"four\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"five\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"six\" mutable=\"false\" enabled=\"false\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "android");
        mScannerRule.addOverlay(createFile("/vendor/overlay/two.apk"), "two", "android");
        mScannerRule.addOverlay(createFile("/vendor/overlay/three.apk"), "three", "android");
        mScannerRule.addOverlay(createFile("/product/overlay/four.apk"), "four", "android");
        mScannerRule.addOverlay(createFile("/product/overlay/five.apk"), "five");
        mScannerRule.addOverlay(createFile("/product/overlay/six.apk"), "six", "android");

        final OverlayConfig overlayConfig = createConfigImpl();
        if (mScannerRule.getIteration() == OverlayConfigIterationRule.Iteration.ZYGOTE) {
            final ArrayList<IdmapInvocation> idmapInvocations =
                    overlayConfig.getImmutableFrameworkOverlayIdmapInvocations();
            assertEquals(2, idmapInvocations.size());

            final IdmapInvocation i0 = idmapInvocations.get(0);
            assertTrue(i0.enforceOverlayable);
            assertEquals("vendor", i0.policy);
            assertEquals(2, i0.overlayPaths.size());
            assertTrue(i0.overlayPaths.get(0).endsWith("/vendor/overlay/one.apk"));
            assertTrue(i0.overlayPaths.get(1).endsWith("/vendor/overlay/two.apk"));

            final IdmapInvocation i1 = idmapInvocations.get(1);
            assertTrue(i1.enforceOverlayable);
            assertEquals("product", i1.policy);
            assertEquals(1, i1.overlayPaths.size());
            assertTrue(i1.overlayPaths.get(0).endsWith("/product/overlay/four.apk"));
        }
    }

    @Test
    public void testIdmapInvocationsDifferentTargetSdk() throws IOException {
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"two\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"three\" mutable=\"false\" enabled=\"true\" />"
                        + "  <overlay package=\"four\" mutable=\"false\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one", "android");
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two", "android");
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three", "android", 28);
        mScannerRule.addOverlay(createFile("/product/overlay/four.apk"), "four", "android");

        final OverlayConfig overlayConfig = createConfigImpl();

        if (mScannerRule.getIteration() == OverlayConfigIterationRule.Iteration.ZYGOTE) {
            final ArrayList<IdmapInvocation> idmapInvocations =
                    overlayConfig.getImmutableFrameworkOverlayIdmapInvocations();
            assertEquals(3, idmapInvocations.size());

            final IdmapInvocation i0 = idmapInvocations.get(0);
            assertTrue(i0.enforceOverlayable);
            assertEquals(2, i0.overlayPaths.size());
            assertTrue(i0.overlayPaths.get(0).endsWith("/product/overlay/one.apk"));
            assertTrue(i0.overlayPaths.get(1).endsWith("/product/overlay/two.apk"));

            final IdmapInvocation i1 = idmapInvocations.get(1);
            assertFalse(i1.enforceOverlayable);
            assertEquals(1, i1.overlayPaths.size());
            assertTrue(i1.overlayPaths.get(0).endsWith("/product/overlay/three.apk"));

            final IdmapInvocation i2 = idmapInvocations.get(2);
            assertTrue(i2.enforceOverlayable);
            assertEquals(1, i2.overlayPaths.size());
            assertTrue(i2.overlayPaths.get(0).endsWith("/product/overlay/four.apk"));
        }
    }

    @Test
    public void testNoConfigIsStatic() throws IOException {
        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one", "android", 28, true,
                1);
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two", "android", 28, false,
                0);
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three", "android", 28,
                true, 0);
        mScannerRule.addOverlay(createFile("/product/overlay/four.apk"), "four", "android", 28,
                false, 2);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 1);
        assertConfig(overlayConfig, "three", false, true, 0);

    }

    @Test
    public void testVendorStaticPrecedesProductImmutable() throws IOException {
        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"two\" mutable=\"false\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "android", 0, true,
                1);
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two", "android", 0, true,
                0);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 0);
        assertConfig(overlayConfig, "two", false, true, 1);
    }

    @Test
    public void testVendorImmutablePrecededProductStatic() throws IOException {
        createFile("/vendor/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" mutable=\"false\" enabled=\"true\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "android", 0, true,
                1);
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two", "android", 0, true,
                0);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 0);
        assertConfig(overlayConfig, "two", false, true, 1);
    }

    @Test
    public void testStaticOverlayOutsideOverlayDir() throws IOException {
        mScannerRule.addOverlay(createFile("/product/app/one.apk"), "one", "android", 0, true, 0);

        final OverlayConfig overlayConfig = createConfigImpl();
        if (mScannerRule.getIteration() == OverlayConfigIterationRule.Iteration.SYSTEM_SERVER) {
            assertConfig(overlayConfig, "one", false, true, 0);
        }
    }

    @Test
    public void testSortStaticOverlaysDifferentTargets() throws IOException {
        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "other", 0, true, 0);
        mScannerRule.addOverlay(createFile("/vendor/overlay/two.apk"), "two", "android", 0, true,
                0);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 1);
        assertConfig(overlayConfig, "two", false, true, 0);
    }

    @Test
    public void testSortStaticOverlaysDifferentPartitions() throws IOException {
        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "android", 0, true,
                2);
        mScannerRule.addOverlay(createFile("/vendor/overlay/two.apk"), "two", "android", 0, true,
                3);
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three", "android", 0,
                true, 0);
        mScannerRule.addOverlay(createFile("/product/overlay/four.apk"), "four", "android", 0,
                true, 1);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 0);
        assertConfig(overlayConfig, "two", false, true, 1);
        assertConfig(overlayConfig, "three", false, true, 2);
        assertConfig(overlayConfig, "four", false, true, 3);
    }

    @Test
    public void testSortStaticOverlaysSamePriority() throws IOException {
        mScannerRule.addOverlay(createFile("/vendor/overlay/one.apk"), "one", "android", 0, true,
                0);
        mScannerRule.addOverlay(createFile("/vendor/overlay/two.apk"), "two", "android", 0, true,
                0);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertConfig(overlayConfig, "one", false, true, 0);
        assertConfig(overlayConfig, "two", false, true, 1);
    }

    @Test
    public void testNonSystemOverlayCannotBeStatic() throws IOException {
        mScannerRule.addOverlay(createFile("/data/overlay/one.apk"), "one", "android", 0, true,
                0);

        final OverlayConfig overlayConfig = createConfigImpl();
        assertTrue(overlayConfig.isMutable("one"));
        assertFalse(overlayConfig.isEnabled("one"));
        assertEquals(Integer.MAX_VALUE, overlayConfig.getPriority("one"));
    }

    @Test
    public void testGetOverlayInfo() throws IOException {
        if (mScannerRule.getIteration() != OverlayConfigIterationRule.Iteration.ZYGOTE) {
            // Run only one iteration of the test.
            return;
        }

        final InputStream is = InstrumentationRegistry.getContext().getResources()
                .openRawResource(R.raw.overlay_config);
        final File partitionDir = mTestFolder.newFolder("product", "overlay");
        final File testApk = new File(partitionDir, "test.apk");
        FileUtils.copy(is, new FileOutputStream(testApk));

        final OverlayScanner scanner = new OverlayScanner();
        scanner.scanDir(partitionDir);

        final OverlayScanner.ParsedOverlayInfo info = scanner.getParsedInfo(TEST_APK_PACKAGE_NAME);
        assertNotNull(info);
        assertEquals(TEST_APK_PACKAGE_NAME, info.packageName);
        assertEquals("android", info.targetPackageName);
        assertEquals(testApk.getPath(), info.path.getPath());
        assertEquals(21, info.targetSdkVersion);
    }

    @Test
    public void testOverlayManifest_withRequiredSystemPropertyAndValueNotMatched()
            throws IOException {
        final String systemPropertyName = "foo.name";
        final String systemPropertyValue = "foo.value";

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" />"
                        + "  <overlay package=\"two\" />"
                        + "  <overlay package=\"three\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one", "android", 0,
                true, 1, systemPropertyName, systemPropertyValue);
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two", "android", 0,
                true, 1, systemPropertyName, systemPropertyValue);
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three");

        final OverlayConfig overlayConfig = createConfigImpl();
        OverlayConfig.Configuration o1 = overlayConfig.getConfiguration("one");
        assertNull(o1);

        OverlayConfig.Configuration o2 = overlayConfig.getConfiguration("two");
        assertNull(o2);

        OverlayConfig.Configuration o3 = overlayConfig.getConfiguration("three");
        assertNotNull(o3);
    }

    @Test
    public void testOverlayManifest_withRequiredSystemPropertyAndValueMatched()
            throws IOException {
        final String systemPropertyName = "ro.build.version.sdk";
        final String systemPropertyValue = SystemProperties.get(systemPropertyName, null);
        assertNotNull(systemPropertyValue);

        createFile("/product/overlay/config/config.xml",
                "<config>"
                        + "  <overlay package=\"one\" />"
                        + "  <overlay package=\"two\" />"
                        + "  <overlay package=\"three\" />"
                        + "</config>");

        mScannerRule.addOverlay(createFile("/product/overlay/one.apk"), "one", "android", 0,
                true, 1, systemPropertyName, systemPropertyValue);
        mScannerRule.addOverlay(createFile("/product/overlay/two.apk"), "two", "android", 0,
                true, 1, systemPropertyName, systemPropertyValue);
        mScannerRule.addOverlay(createFile("/product/overlay/three.apk"), "three");

        final OverlayConfig overlayConfig = createConfigImpl();
        OverlayConfig.Configuration o1 = overlayConfig.getConfiguration("one");
        assertNotNull(o1);

        OverlayConfig.Configuration o2 = overlayConfig.getConfiguration("two");
        assertNotNull(o2);

        OverlayConfig.Configuration o3 = overlayConfig.getConfiguration("three");
        assertNotNull(o3);
    }

    @Test
    public void testSortPartitionsWithoutXml() throws IOException {
        ArrayList<OverlayPartition> partitions = new ArrayList<>(
                PackagePartitions.getOrderedPartitions(OverlayPartition::new));

        final OverlayConfig overlayConfig = createConfigImpl();
        String partitionOrderFilePath = String.format("%s/%s", mTestFolder.getRoot(),
                "/product/overlay/partition_order.xml");
        assertEquals(false, overlayConfig.sortPartitions(partitionOrderFilePath, partitions));
        assertEquals("system, vendor, odm, oem, product, system_ext",
                generatePartitionOrderString(partitions));
    }

    @Test
    public void testSortPartitionsWithInvalidXmlRootElement() throws IOException {
        ArrayList<OverlayPartition> partitions = new ArrayList<>(
                PackagePartitions.getOrderedPartitions(OverlayPartition::new));
        createFile("/product/overlay/partition_order.xml",
                "<partition-list>\n"
                        + "  <partition name=\"system_ext\"/>\n"
                        + "  <partition name=\"vendor\"/>\n"
                        + "  <partition name=\"oem\"/>\n"
                        + "  <partition name=\"odm\"/>\n"
                        + "  <partition name=\"product\"/>\n"
                        + "  <partition name=\"system\"/>\n"
                        + "</partition-list>\n");
        final OverlayConfig overlayConfig = createConfigImpl();
        String partitionOrderFilePath = String.format("%s/%s", mTestFolder.getRoot(),
                "/product/overlay/partition_order.xml");
        assertEquals(false, overlayConfig.sortPartitions(partitionOrderFilePath, partitions));
        assertEquals("system, vendor, odm, oem, product, system_ext",
                generatePartitionOrderString(partitions));
    }

    @Test
    public void testSortPartitionsWithInvalidPartition() throws IOException {
        ArrayList<OverlayPartition> partitions = new ArrayList<>(
                PackagePartitions.getOrderedPartitions(OverlayPartition::new));
        createFile("/product/overlay/partition_order.xml",
                "<partition-order>\n"
                        + "  <partition name=\"INVALID\"/>\n"
                        + "  <partition name=\"vendor\"/>\n"
                        + "  <partition name=\"oem\"/>\n"
                        + "  <partition name=\"odm\"/>\n"
                        + "  <partition name=\"product\"/>\n"
                        + "  <partition name=\"system\"/>\n"
                        + "</partition-order>\n");
        final OverlayConfig overlayConfig = createConfigImpl();
        String partitionOrderFilePath = String.format("%s/%s", mTestFolder.getRoot(),
                "/product/overlay/partition_order.xml");
        assertEquals(false, overlayConfig.sortPartitions(partitionOrderFilePath, partitions));
        assertEquals("system, vendor, odm, oem, product, system_ext",
                generatePartitionOrderString(partitions));
    }

    @Test
    public void testSortPartitionsWithDuplicatePartition() throws IOException {
        ArrayList<OverlayPartition> partitions = new ArrayList<>(
                PackagePartitions.getOrderedPartitions(OverlayPartition::new));
        createFile("/product/overlay/partition_order.xml",
                "<partition-order>\n"
                        + "  <partition name=\"system_ext\"/>\n"
                        + "  <partition name=\"system\"/>\n"
                        + "  <partition name=\"vendor\"/>\n"
                        + "  <partition name=\"oem\"/>\n"
                        + "  <partition name=\"odm\"/>\n"
                        + "  <partition name=\"product\"/>\n"
                        + "  <partition name=\"system\"/>\n"
                        + "</partition-order>\n");
        final OverlayConfig overlayConfig = createConfigImpl();
        String partitionOrderFilePath = String.format("%s/%s", mTestFolder.getRoot(),
                "/product/overlay/partition_order.xml");
        assertEquals(false, overlayConfig.sortPartitions(partitionOrderFilePath, partitions));
        assertEquals("system, vendor, odm, oem, product, system_ext",
                generatePartitionOrderString(partitions));
    }

    @Test
    public void testSortPartitionsWithMissingPartition() throws IOException {
        ArrayList<OverlayPartition> partitions = new ArrayList<>(
                PackagePartitions.getOrderedPartitions(OverlayPartition::new));
        createFile("/product/overlay/partition_order.xml",
                "<partition-order>\n"
                        + "  <partition name=\"vendor\"/>\n"
                        + "  <partition name=\"oem\"/>\n"
                        + "  <partition name=\"odm\"/>\n"
                        + "  <partition name=\"product\"/>\n"
                        + "  <partition name=\"system\"/>\n"
                        + "</partition-order>\n");
        final OverlayConfig overlayConfig = createConfigImpl();
        String partitionOrderFilePath = String.format("%s/%s", mTestFolder.getRoot(),
                "/product/overlay/partition_order.xml");
        assertEquals(false, overlayConfig.sortPartitions(partitionOrderFilePath, partitions));
        assertEquals("system, vendor, odm, oem, product, system_ext",
                generatePartitionOrderString(partitions));
    }

    @Test
    public void testSortPartitionsWithCorrectPartitionOrderXml() throws IOException {
        ArrayList<OverlayPartition> partitions = new ArrayList<>(
                PackagePartitions.getOrderedPartitions(OverlayPartition::new));
        createFile("/product/overlay/partition_order.xml",
                "<partition-order>\n"
                        + "  <partition name=\"system_ext\"/>\n"
                        + "  <partition name=\"vendor\"/>\n"
                        + "  <partition name=\"oem\"/>\n"
                        + "  <partition name=\"odm\"/>\n"
                        + "  <partition name=\"product\"/>\n"
                        + "  <partition name=\"system\"/>\n"
                        + "</partition-order>\n");
        final OverlayConfig overlayConfig = createConfigImpl();
        String partitionOrderFilePath = String.format("%s/%s", mTestFolder.getRoot(),
                "/product/overlay/partition_order.xml");
        assertEquals(true, overlayConfig.sortPartitions(partitionOrderFilePath, partitions));
        assertEquals("system_ext, vendor, oem, odm, product, system",
                generatePartitionOrderString(partitions));
    }
}
