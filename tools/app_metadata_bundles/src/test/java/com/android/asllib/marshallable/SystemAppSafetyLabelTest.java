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
public class SystemAppSafetyLabelTest {
    private static final String SYSTEM_APP_SAFETY_LABEL_HR_PATH =
            "com/android/asllib/systemappsafetylabel/hr";
    private static final String SYSTEM_APP_SAFETY_LABEL_OD_PATH =
            "com/android/asllib/systemappsafetylabel/od";

    private static final String VALID_FILE_NAME = "valid.xml";
    private static final String MISSING_URL_FILE_NAME = "missing-url.xml";

    /** Logic for setting up tests (empty if not yet needed). */
    public static void main(String[] params) throws Exception {}

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for valid. */
    @Test
    public void testValid() throws Exception {
        System.out.println("starting testValid.");
        testHrToOdSystemAppSafetyLabel(VALID_FILE_NAME);
        testOdToHrSystemAppSafetyLabel(VALID_FILE_NAME);
    }

    /** Tests missing url. */
    @Test
    public void testMissingUrl() throws Exception {
        System.out.println("starting testMissingUrl.");
        hrToOdExpectException(MISSING_URL_FILE_NAME);
        odToHrExpectException(MISSING_URL_FILE_NAME);
    }

    private void hrToOdExpectException(String fileName) {
        TestUtils.hrToOdExpectException(
                new SystemAppSafetyLabelFactory(), SYSTEM_APP_SAFETY_LABEL_HR_PATH, fileName);
    }

    private void odToHrExpectException(String fileName) {
        TestUtils.odToHrExpectException(
                new SystemAppSafetyLabelFactory(), SYSTEM_APP_SAFETY_LABEL_OD_PATH, fileName);
    }

    private void testHrToOdSystemAppSafetyLabel(String fileName) throws Exception {
        TestUtils.testHrToOd(
                TestUtils.document(),
                new SystemAppSafetyLabelFactory(),
                SYSTEM_APP_SAFETY_LABEL_HR_PATH,
                SYSTEM_APP_SAFETY_LABEL_OD_PATH,
                fileName);
    }

    private void testOdToHrSystemAppSafetyLabel(String fileName) throws Exception {
        TestUtils.testOdToHr(
                TestUtils.document(),
                new SystemAppSafetyLabelFactory(),
                SYSTEM_APP_SAFETY_LABEL_OD_PATH,
                SYSTEM_APP_SAFETY_LABEL_HR_PATH,
                fileName);
    }
}
