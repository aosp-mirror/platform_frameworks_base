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

package android.view;

import static android.view.InsetsState.FIRST_TYPE;
import static android.view.InsetsState.LAST_TYPE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.view.InsetsState.InternalInsetsType;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link InsetsVisibilities}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsVisibilities
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsVisibilitiesTest {

    @Test
    public void testEquals() {
        final InsetsVisibilities v1 = new InsetsVisibilities();
        final InsetsVisibilities v2 = new InsetsVisibilities();
        final InsetsVisibilities v3 = new InsetsVisibilities();
        assertEquals(v1, v2);
        assertEquals(v1, v3);

        for (@InternalInsetsType int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            v1.setVisibility(type, false);
            v2.setVisibility(type, false);
        }
        assertEquals(v1, v2);
        assertNotEquals(v1, v3);

        for (@InternalInsetsType int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            v1.setVisibility(type, true);
            v2.setVisibility(type, true);
        }
        assertEquals(v1, v2);
        assertNotEquals(v1, v3);
    }

    @Test
    public void testSet() {
        for (@InternalInsetsType int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            final InsetsVisibilities v1 = new InsetsVisibilities();
            final InsetsVisibilities v2 = new InsetsVisibilities();

            v1.setVisibility(type, true);
            assertNotEquals(v1, v2);

            v2.set(v1);
            assertEquals(v1, v2);

            v2.setVisibility(type, false);
            assertNotEquals(v1, v2);

            v1.set(v2);
            assertEquals(v1, v2);
        }
    }

    @Test
    public void testCopyConstructor() {
        for (@InternalInsetsType int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            final InsetsVisibilities v1 = new InsetsVisibilities();
            v1.setVisibility(type, true);
            final InsetsVisibilities v2 = new InsetsVisibilities(v1);
            assertEquals(v1, v2);

            v2.setVisibility(type, false);
            assertNotEquals(v1, v2);
        }
    }

    @Test
    public void testGetterAndSetter() {
        final InsetsVisibilities v1 = new InsetsVisibilities();
        final InsetsVisibilities v2 = new InsetsVisibilities();

        for (@InternalInsetsType int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            assertEquals(InsetsState.getDefaultVisibility(type), v1.getVisibility(type));
        }

        for (@InternalInsetsType int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            v1.setVisibility(type, true);
            assertTrue(v1.getVisibility(type));

            v2.setVisibility(type, false);
            assertFalse(v2.getVisibility(type));
        }
    }
}
