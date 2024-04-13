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
public class AndroidSafetyLabelTest {
    private static final String ANDROID_SAFETY_LABEL_HR_PATH =
            "com/android/asllib/androidsafetylabel/hr";
    private static final String ANDROID_SAFETY_LABEL_OD_PATH =
            "com/android/asllib/androidsafetylabel/od";

    private static final String MISSING_VERSION_FILE_NAME = "missing-version.xml";
    private static final String VALID_EMPTY_FILE_NAME = "valid-empty.xml";
    private static final String WITH_SAFETY_LABELS_FILE_NAME = "with-safety-labels.xml";
    private static final String WITH_SYSTEM_APP_SAFETY_LABEL_FILE_NAME =
            "with-system-app-safety-label.xml";
    private static final String WITH_TRANSPARENCY_INFO_FILE_NAME = "with-transparency-info.xml";

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for android safety label missing version. */
    @Test
    public void testAndroidSafetyLabelMissingVersion() throws Exception {
        System.out.println("starting testAndroidSafetyLabelMissingVersion.");
        hrToOdExpectException(MISSING_VERSION_FILE_NAME);
        odToHrExpectException(MISSING_VERSION_FILE_NAME);
    }

    /** Test for android safety label valid empty. */
    @Test
    public void testAndroidSafetyLabelValidEmptyFile() throws Exception {
        System.out.println("starting testAndroidSafetyLabelValidEmptyFile.");
        testHrToOdAndroidSafetyLabel(VALID_EMPTY_FILE_NAME);
        testOdToHrAndroidSafetyLabel(VALID_EMPTY_FILE_NAME);
    }

    /** Test for android safety label with safety labels. */
    @Test
    public void testAndroidSafetyLabelWithSafetyLabels() throws Exception {
        System.out.println("starting testAndroidSafetyLabelWithSafetyLabels.");
        testHrToOdAndroidSafetyLabel(WITH_SAFETY_LABELS_FILE_NAME);
        testOdToHrAndroidSafetyLabel(WITH_SAFETY_LABELS_FILE_NAME);
    }

    /** Test for android safety label with system app safety label. */
    @Test
    public void testAndroidSafetyLabelWithSystemAppSafetyLabel() throws Exception {
        System.out.println("starting testAndroidSafetyLabelWithSystemAppSafetyLabel.");
        testHrToOdAndroidSafetyLabel(WITH_SYSTEM_APP_SAFETY_LABEL_FILE_NAME);
        testOdToHrAndroidSafetyLabel(WITH_SYSTEM_APP_SAFETY_LABEL_FILE_NAME);
    }

    /** Test for android safety label with transparency info. */
    @Test
    public void testAndroidSafetyLabelWithTransparencyInfo() throws Exception {
        System.out.println("starting testAndroidSafetyLabelWithTransparencyInfo.");
        testHrToOdAndroidSafetyLabel(WITH_TRANSPARENCY_INFO_FILE_NAME);
        testOdToHrAndroidSafetyLabel(WITH_TRANSPARENCY_INFO_FILE_NAME);
    }

    private void hrToOdExpectException(String fileName) {
        TestUtils.hrToOdExpectException(
                new AndroidSafetyLabelFactory(), ANDROID_SAFETY_LABEL_HR_PATH, fileName);
    }

    private void odToHrExpectException(String fileName) {
        TestUtils.odToHrExpectException(
                new AndroidSafetyLabelFactory(), ANDROID_SAFETY_LABEL_OD_PATH, fileName);
    }

    private void testHrToOdAndroidSafetyLabel(String fileName) throws Exception {
        TestUtils.testHrToOd(
                TestUtils.document(),
                new AndroidSafetyLabelFactory(),
                ANDROID_SAFETY_LABEL_HR_PATH,
                ANDROID_SAFETY_LABEL_OD_PATH,
                fileName);
    }

    private void testOdToHrAndroidSafetyLabel(String fileName) throws Exception {
        TestUtils.testOdToHr(
                TestUtils.document(),
                new AndroidSafetyLabelFactory(),
                ANDROID_SAFETY_LABEL_OD_PATH,
                ANDROID_SAFETY_LABEL_HR_PATH,
                fileName);
    }
}
