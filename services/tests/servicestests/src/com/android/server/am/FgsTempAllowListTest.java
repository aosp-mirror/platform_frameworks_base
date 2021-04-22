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

package com.android.server.am;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:TempAllowListTest
 */
@Presubmit
public class FgsTempAllowListTest {

    /**
     * This case tests get(), isAllowed(), remove() interfaces.
     */
    @Test
    public void testIsAllowed() {
        FgsTempAllowList<Integer, String> allowList = new FgsTempAllowList();
        allowList.add(10001, 2000, "description1");
        allowList.add(10002, 2000, "description2");

        assertTrue(allowList.isAllowed(10001));
        Pair<Long, String> entry1 = allowList.get(10001);
        assertNotNull(entry1);
        assertEquals(entry1.second, "description1");

        assertTrue(allowList.isAllowed(10002));
        Pair<Long, String> entry2 = allowList.get(10002);
        assertNotNull(entry2);
        assertEquals(entry2.second, "description2");

        allowList.remove(10001);
        assertFalse(allowList.isAllowed(10001));
        assertNull(allowList.get(10001));
    }

    /**
     * This case tests temp allowlist entry can expire.
     */
    @Test
    public void testExpired() {
        FgsTempAllowList<Integer, String> allowList = new FgsTempAllowList();
        // temp allow for 2000ms.
        allowList.add(10001, 2000, "uid1-2000ms");
        // sleep for 3000ms.
        SystemClock.sleep(3000);
        // entry expired.
        assertFalse(allowList.isAllowed(10001));
        assertNull(allowList.get(10001));
    }
}
