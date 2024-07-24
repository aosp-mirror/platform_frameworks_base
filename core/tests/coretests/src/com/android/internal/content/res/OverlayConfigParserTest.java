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

package com.android.internal.content.res;

import static com.android.internal.content.om.OverlayConfigParser.SysPropWrapper;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.content.om.OverlayConfigParser;

import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class OverlayConfigParserTest {
    @Test(expected = IllegalStateException.class)
    public void testMergePropNotRoProp() {
        SysPropWrapper sysProp = p -> {
            return "dummy_value";
        };
        OverlayConfigParser.expandProperty("${persist.value}/path", sysProp);
    }

    @Test(expected = IllegalStateException.class)
    public void testMergePropMissingEndBracket() {
        SysPropWrapper sysProp = p -> {
            return "dummy_value";
        };
        OverlayConfigParser.expandProperty("${ro.value/path", sysProp);
    }

    @Test(expected = IllegalStateException.class)
    public void testMergeOnlyPropStart() {
        SysPropWrapper sysProp = p -> {
            return "dummy_value";
        };
        OverlayConfigParser.expandProperty("path/${", sysProp);
    }

    @Test(expected = IllegalStateException.class)
    public void testMergePropInProp() {
        SysPropWrapper sysProp = p -> {
            return "dummy_value";
        };
        OverlayConfigParser.expandProperty("path/${${ro.value}}", sysProp);
    }

    /**
     * The path is only allowed to contain one property.
     */
    @Test(expected = IllegalStateException.class)
    public void testMergePropMultipleProps() {
        SysPropWrapper sysProp = p -> {
            return "dummy_value";
        };
        OverlayConfigParser.expandProperty("${ro.value}/path${ro.value2}/path", sysProp);
    }

    @Test
    public void testMergePropOneProp() {
        final SysPropWrapper sysProp = p -> {
            if ("ro.value".equals(p)) {
                return "dummy_value";
            } else {
                return "invalid";
            }
        };

        // Property in the beginnig of the string
        String result = OverlayConfigParser.expandProperty("${ro.value}/path",
                sysProp);
        assertEquals("dummy_value/path", result);

        // Property in the middle of the string
        result = OverlayConfigParser.expandProperty("path/${ro.value}/file",
                sysProp);
        assertEquals("path/dummy_value/file", result);

        // Property at the of the string
        result = OverlayConfigParser.expandProperty("path/${ro.value}",
                sysProp);
        assertEquals("path/dummy_value", result);

        // Property is the entire string
        result = OverlayConfigParser.expandProperty("${ro.value}",
                sysProp);
        assertEquals("dummy_value", result);
    }

    @Test
    public void testMergePropNoProp() {
        final SysPropWrapper sysProp = p -> {
            return "dummy_value";
        };

        final String path = "no_props/path";
        String result = OverlayConfigParser.expandProperty(path, sysProp);
        assertEquals(path, result);
    }
}
