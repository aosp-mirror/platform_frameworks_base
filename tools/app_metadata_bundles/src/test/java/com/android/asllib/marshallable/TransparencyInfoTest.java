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

package com.android.asllib.marshallable;

import com.android.asllib.testutils.TestUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TransparencyInfoTest {
    private static final String TRANSPARENCY_INFO_HR_PATH =
            "com/android/asllib/transparencyinfo/hr";
    private static final String TRANSPARENCY_INFO_OD_PATH =
            "com/android/asllib/transparencyinfo/od";

    private static final String VALID_EMPTY_FILE_NAME = "valid-empty.xml";
    private static final String WITH_DEVELOPER_INFO_FILE_NAME = "with-developer-info.xml";
    private static final String WITH_APP_INFO_FILE_NAME = "with-app-info.xml";

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for transparency info valid empty. */
    @Test
    public void testTransparencyInfoValidEmptyFile() throws Exception {
        System.out.println("starting testTransparencyInfoValidEmptyFile.");
        testHrToOdTransparencyInfo(VALID_EMPTY_FILE_NAME);
        testOdToHrTransparencyInfo(VALID_EMPTY_FILE_NAME);
    }

    /** Test for transparency info with developer info. */
    @Test
    public void testTransparencyInfoWithDeveloperInfo() throws Exception {
        System.out.println("starting testTransparencyInfoWithDeveloperInfo.");
        testHrToOdTransparencyInfo(WITH_DEVELOPER_INFO_FILE_NAME);
        testOdToHrTransparencyInfo(WITH_DEVELOPER_INFO_FILE_NAME);
    }

    /** Test for transparency info with app info. */
    @Test
    public void testTransparencyInfoWithAppInfo() throws Exception {
        System.out.println("starting testTransparencyInfoWithAppInfo.");
        testHrToOdTransparencyInfo(WITH_APP_INFO_FILE_NAME);
        testOdToHrTransparencyInfo(WITH_APP_INFO_FILE_NAME);
    }

    private void testHrToOdTransparencyInfo(String fileName) throws Exception {
        TestUtils.testHrToOd(
                TestUtils.document(),
                new TransparencyInfoFactory(),
                TRANSPARENCY_INFO_HR_PATH,
                TRANSPARENCY_INFO_OD_PATH,
                fileName);
    }

    private void testOdToHrTransparencyInfo(String fileName) throws Exception {
        TestUtils.testOdToHr(
                TestUtils.document(),
                new TransparencyInfoFactory(),
                TRANSPARENCY_INFO_OD_PATH,
                TRANSPARENCY_INFO_HR_PATH,
                fileName);
    }
}
