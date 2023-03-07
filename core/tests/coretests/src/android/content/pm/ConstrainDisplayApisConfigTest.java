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

package android.content.pm;

import static android.provider.DeviceConfig.NAMESPACE_CONSTRAIN_DISPLAY_APIS;

import static org.junit.Assert.assertEquals;

import android.annotation.Nullable;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test for {@link ConstrainDisplayApisConfig}.
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:ConstrainDisplayApisConfigTest
 */
@SmallTest
@Presubmit
public final class ConstrainDisplayApisConfigTest {

    private Properties mInitialConstrainDisplayApisFlags;

    @Before
    public void setUp() throws Exception {
        mInitialConstrainDisplayApisFlags = DeviceConfig.getProperties(
                NAMESPACE_CONSTRAIN_DISPLAY_APIS);
        DeviceConfig.setProperties(
                new Properties.Builder(NAMESPACE_CONSTRAIN_DISPLAY_APIS).build());
    }

    @After
    public void tearDown() throws Exception {
        DeviceConfig.setProperties(mInitialConstrainDisplayApisFlags);
    }

    @Test
    public void neverConstrainDisplayApis_allPackagesFlagTrue_returnsTrue() {
        setNeverConstrainDisplayApisAllPackagesFlag("true");
        // Setting 'never_constrain_display_apis' as well to make sure it is ignored.
        setNeverConstrainDisplayApisFlag("com.android.other:1:2,com.android.other2::");

        testNeverConstrainDisplayApis("com.android.test", /* version= */ 5, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.other", /* version= */ 0, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.other", /* version= */ 3, /* expected= */ true);
    }

    @Test
    public void neverConstrainDisplayApis_flagsNoSet_returnsFalse() {
        testNeverConstrainDisplayApis("com.android.test", /* version= */ 1, /* expected= */ false);
    }

    @Ignore("b/257375674")
    @Test
    public void neverConstrainDisplayApis_flagsHasSingleEntry_returnsTrueForPackageWithinRange() {
        setNeverConstrainDisplayApisFlag("com.android.test:1:1");

        testNeverConstrainDisplayApis("com.android.other", /* version= */ 5, /* expected= */ false);
        testNeverConstrainDisplayApis("com.android.test", /* version= */ 0, /* expected= */ false);
        testNeverConstrainDisplayApis("com.android.test", /* version= */ 1, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test", /* version= */ 2, /* expected= */ false);
    }

    @Test
    public void neverConstrainDisplayApis_flagHasEntries_returnsTrueForPackagesWithinRange() {
        setNeverConstrainDisplayApisFlag("com.android.test1::,com.android.test2:1:3,"
                + "com.android.test3:5:,com.android.test4::8");

        // Package 'com.android.other'
        testNeverConstrainDisplayApis("com.android.other", /* version= */ 5, /* expected= */ false);
        // Package 'com.android.test1'
        testNeverConstrainDisplayApis("com.android.test1", /* version= */ 5, /* expected= */ true);
        // Package 'com.android.test2'
        testNeverConstrainDisplayApis("com.android.test2", /* version= */ 0, /* expected= */ false);
        testNeverConstrainDisplayApis("com.android.test2", /* version= */ 1, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test2", /* version= */ 2, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test2", /* version= */ 3, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test2", /* version= */ 4, /* expected= */ false);
        // Package 'com.android.test3'
        testNeverConstrainDisplayApis("com.android.test3", /* version= */ 4, /* expected= */ false);
        testNeverConstrainDisplayApis("com.android.test3", /* version= */ 5, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test3", /* version= */ 6, /* expected= */ true);
        // Package 'com.android.test4'
        testNeverConstrainDisplayApis("com.android.test4", /* version= */ 7, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test4", /* version= */ 8, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test4", /* version= */ 9, /* expected= */ false);
    }

    @Ignore("b/257375674")
    @Test
    public void neverConstrainDisplayApis_flagHasInvalidEntries_ignoresInvalidEntries() {
        // We add a valid entry before and after the invalid ones to make sure they are applied.
        setNeverConstrainDisplayApisFlag("com.android.test1::,com.android.test2:1,"
                + "com.android.test3:5:ten,com.android.test4:5::,com.android.test5::");

        testNeverConstrainDisplayApis("com.android.test1", /* version= */ 5, /* expected= */ true);
        testNeverConstrainDisplayApis("com.android.test2", /* version= */ 2, /* expected= */ false);
        testNeverConstrainDisplayApis("com.android.test3", /* version= */ 7, /* expected= */ false);
        testNeverConstrainDisplayApis("com.android.test4", /* version= */ 7, /* expected= */ false);
        testNeverConstrainDisplayApis("com.android.test5", /* version= */ 5, /* expected= */ true);
    }

    @Test
    public void alwaysConstrainDisplayApis_flagsNoSet_returnsFalse() {
        testAlwaysConstrainDisplayApis("com.android.test", /* version= */ 1, /* expected= */ false);
    }

    @Test
    public void alwaysConstrainDisplayApis_flagHasEntries_returnsTrueForPackagesWithinRange() {
        setAlwaysConstrainDisplayApisFlag("com.android.test1::,com.android.test2:1:2");

        // Package 'com.android.other'
        testAlwaysConstrainDisplayApis("com.android.other", /* version= */ 5, /* expected= */
                false);
        // Package 'com.android.test1'
        testAlwaysConstrainDisplayApis("com.android.test1", /* version= */ 5, /* expected= */ true);
        // Package 'com.android.test2'
        testAlwaysConstrainDisplayApis("com.android.test2", /* version= */ 0, /* expected= */
                false);
        testAlwaysConstrainDisplayApis("com.android.test2", /* version= */ 1, /* expected= */ true);
        testAlwaysConstrainDisplayApis("com.android.test2", /* version= */ 2, /* expected= */ true);
        testAlwaysConstrainDisplayApis("com.android.test2", /* version= */ 3, /* expected= */
                false);
    }

    private static void testNeverConstrainDisplayApis(String packageName, long version,
            boolean expected) {
        ConstrainDisplayApisConfig config = new ConstrainDisplayApisConfig();
        assertEquals(expected,
                config.getNeverConstrainDisplayApis(buildApplicationInfo(packageName, version)));
    }

    private static void testAlwaysConstrainDisplayApis(String packageName, long version,
            boolean expected) {
        ConstrainDisplayApisConfig config = new ConstrainDisplayApisConfig();

        assertEquals(expected,
                config.getAlwaysConstrainDisplayApis(buildApplicationInfo(packageName, version)));
    }

    private static ApplicationInfo buildApplicationInfo(String packageName, long version) {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = packageName;
        applicationInfo.longVersionCode = version;
        return applicationInfo;
    }

    private static void setNeverConstrainDisplayApisFlag(@Nullable String value) {
        DeviceConfig.setProperty(NAMESPACE_CONSTRAIN_DISPLAY_APIS, "never_constrain_display_apis",
                value, /* makeDefault= */ false);
    }

    private static void setNeverConstrainDisplayApisAllPackagesFlag(@Nullable String value) {
        DeviceConfig.setProperty(NAMESPACE_CONSTRAIN_DISPLAY_APIS,
                "never_constrain_display_apis_all_packages",
                value, /* makeDefault= */ false);
    }

    private static void setAlwaysConstrainDisplayApisFlag(@Nullable String value) {
        DeviceConfig.setProperty(NAMESPACE_CONSTRAIN_DISPLAY_APIS, "always_constrain_display_apis",
                value, /* makeDefault= */ false);
    }
}
