/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.ravenwoodtest.coretest;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.os.SystemProperties;

import org.junit.Test;

public class RavenwoodSystemPropertiesTest {
    @Test
    public void testRead() {
        assertThat(SystemProperties.get("ro.board.first_api_level")).isEqualTo("1");
    }

    @Test
    public void testWrite() {
        SystemProperties.set("debug.xxx", "5");
        assertThat(SystemProperties.get("debug.xxx")).isEqualTo("5");
    }

    private static void assertException(String expectedMessage, Runnable r) {
        try {
            r.run();
            fail("Excepted exception with message '" + expectedMessage + "' but wasn't thrown");
        } catch (RuntimeException e) {
            if (e.getMessage().contains(expectedMessage)) {
                return;
            }
            fail("Excepted exception with message '" + expectedMessage + "' but was '"
                    + e.getMessage() +  "'");
        }
    }


    @Test
    public void testReadDisallowed() {
        assertException("Read access to system property 'nonexisitent' denied", () -> {
            SystemProperties.get("nonexisitent");
        });
    }

    @Test
    public void testWriteDisallowed() {
        assertException("failed to set system property \"ro.board.first_api_level\" ", () -> {
            SystemProperties.set("ro.board.first_api_level", "2");
        });
    }
}
