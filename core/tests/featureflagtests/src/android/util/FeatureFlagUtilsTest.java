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

package android.util;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class FeatureFlagUtilsTest {

    private static final String TEST_FEATURE_NAME = "feature_foobar";

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        cleanup();
    }

    @After
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        Settings.Global.putString(mContext.getContentResolver(), TEST_FEATURE_NAME, "");
        SystemProperties.set(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, "");
        SystemProperties.set(FeatureFlagUtils.FFLAG_OVERRIDE_PREFIX + TEST_FEATURE_NAME, "");
    }

    @Test
    public void testGetFlag_enabled_shouldReturnTrue() {
        FeatureFlagUtils.getAllFeatureFlags().put(TEST_FEATURE_NAME, "true");

        assertTrue(FeatureFlagUtils.isEnabled(mContext, TEST_FEATURE_NAME));
    }

    @Test
    public void testGetFlag_adb_override_shouldReturnTrue() {
        SystemProperties.set(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, "false");
        SystemProperties.set(FeatureFlagUtils.FFLAG_OVERRIDE_PREFIX + TEST_FEATURE_NAME, "true");

        assertTrue(FeatureFlagUtils.isEnabled(mContext, TEST_FEATURE_NAME));
    }

    @Test
    public void testGetFlag_settings_override_shouldReturnTrue() {
        SystemProperties.set(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, "false");
        SystemProperties.set(FeatureFlagUtils.FFLAG_OVERRIDE_PREFIX + TEST_FEATURE_NAME, "false");

        Settings.Global.putString(mContext.getContentResolver(), TEST_FEATURE_NAME, "true");

        assertTrue(FeatureFlagUtils.isEnabled(mContext, TEST_FEATURE_NAME));
    }

    @Test
    public void testSetEnabled_shouldSetOverrideFlag() {
        assertFalse(FeatureFlagUtils.isEnabled(mContext, TEST_FEATURE_NAME));

        FeatureFlagUtils.setEnabled(null /* context */, TEST_FEATURE_NAME, true);

        assertEquals(SystemProperties.get(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, null),
                "");
        assertTrue(Boolean.parseBoolean(SystemProperties.get(
                FeatureFlagUtils.FFLAG_OVERRIDE_PREFIX + TEST_FEATURE_NAME, "")));
    }

    @Test
    public void testGetFlag_notSet_shouldReturnFalse() {
        assertFalse(FeatureFlagUtils.isEnabled(mContext, TEST_FEATURE_NAME + "does_not_exist"));
    }

    @Test
    public void getAllFeatureFlags_shouldNotBeNull() {
        assertNotNull(FeatureFlagUtils.getAllFeatureFlags());
    }
}
