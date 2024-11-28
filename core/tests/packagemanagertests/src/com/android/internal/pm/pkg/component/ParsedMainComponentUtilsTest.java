/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.pm.pkg.component;

import static android.security.Flags.FLAG_ENABLE_INTENT_MATCHING_FLAGS;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class ParsedMainComponentUtilsTest {

    private final Map<String, Integer> mStringToFlagMap = new HashMap<>();

    {
        mStringToFlagMap.put("none", ParsedMainComponentImpl.INTENT_MATCHING_FLAGS_NONE);
        mStringToFlagMap.put("enforceIntentFilter",
                ParsedMainComponentImpl.INTENT_MATCHING_FLAGS_ENFORCE_INTENT_FILTER);
        mStringToFlagMap.put("allowNullAction",
                ParsedMainComponentImpl.INTENT_MATCHING_FLAGS_ALLOW_NULL_ACTION);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_ENABLE_INTENT_MATCHING_FLAGS)
    public void testResolveIntentMatchingFlags() {
        assertResolution("", "", "");
        assertResolution("none", "none", "none");

        assertResolution("none", "enforceIntentFilter", "enforceIntentFilter");
        assertResolution("enforceIntentFilter", "none", "none");
        assertResolution("enforceIntentFilter|allowNullAction", "none",
                "none");
        assertResolution("enforceIntentFilter|allowNullAction", "enforceIntentFilter",
                "enforceIntentFilter");

        assertResolution("none", "", "none");
        assertResolution("enforceIntentFilter", "", "enforceIntentFilter");
        assertResolution("enforceIntentFilter|allowNullAction", "",
                "enforceIntentFilter|allowNullAction");

        assertResolution("", "none", "none");
        assertResolution("", "enforceIntentFilter", "enforceIntentFilter");
        assertResolution("", "enforceIntentFilter|allowNullAction",
                "enforceIntentFilter|allowNullAction");
    }

    private void assertResolution(String applicationStringFlags, String componentStringFlags,
            String expectedStringFlags) {
        int applicationFlag = stringToFlag(applicationStringFlags);
        int componentFlag = stringToFlag(componentStringFlags);

        int expectedFlag = stringToFlag(expectedStringFlags);
        int resolvedFlag = ParsedMainComponentUtils.resolveIntentMatchingFlags(applicationFlag,
                componentFlag);

        assertEquals(expectedFlag, resolvedFlag);
    }

    private int stringToFlag(String flags) {
        int result = 0;
        String[] flagList = flags.split("\\|");
        for (String flag : flagList) {
            String trimmedFlag = flag.trim();
            result |= mStringToFlagMap.getOrDefault(trimmedFlag, 0);
        }
        return result;
    }
}
