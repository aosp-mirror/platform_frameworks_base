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
import static junit.framework.Assert.assertTrue;

import android.os.SystemProperties;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class FeatureFlagUtilsTest {

    private static final String TEST_FEATURE_NAME = "feature_foobar";

    @Before
    public void setUp() {
        cleanup();
    }

    @After
    public void tearDown() {
        cleanup();
    }

    private void cleanup() {
        SystemProperties.set(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, "");
        SystemProperties.set(FeatureFlagUtils.FFLAG_OVERRIDE_PREFIX + TEST_FEATURE_NAME, "");
    }

    @Test
    public void testGetFlag_enabled_shouldReturnTrue() {
        SystemProperties.set(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, "true");

        assertTrue(FeatureFlagUtils.isEnabled(TEST_FEATURE_NAME));
    }

    @Test
    public void testGetFlag_override_shouldReturnTrue() {
        SystemProperties.set(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, "false");
        SystemProperties.set(FeatureFlagUtils.FFLAG_OVERRIDE_PREFIX + TEST_FEATURE_NAME, "true");

        assertTrue(FeatureFlagUtils.isEnabled(TEST_FEATURE_NAME));
    }

    @Test
    public void testSetEnabled_shouldSetOverrideFlag() {
        assertFalse(FeatureFlagUtils.isEnabled(TEST_FEATURE_NAME));

        FeatureFlagUtils.setEnabled(TEST_FEATURE_NAME, true);

        assertEquals(SystemProperties.get(FeatureFlagUtils.FFLAG_PREFIX + TEST_FEATURE_NAME, null),
                "");
        assertTrue(Boolean.parseBoolean(SystemProperties.get(
                FeatureFlagUtils.FFLAG_OVERRIDE_PREFIX + TEST_FEATURE_NAME, "")));
    }

    @Test
    public void testGetFlag_notSet_shouldReturnFalse() {
        assertFalse(FeatureFlagUtils.isEnabled(TEST_FEATURE_NAME));
    }

}
