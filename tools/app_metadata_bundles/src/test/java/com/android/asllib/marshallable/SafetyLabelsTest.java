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
public class SafetyLabelsTest {
    private static final String SAFETY_LABELS_HR_PATH = "com/android/asllib/safetylabels/hr";
    private static final String SAFETY_LABELS_OD_PATH = "com/android/asllib/safetylabels/od";

    private static final String MISSING_VERSION_FILE_NAME = "missing-version.xml";
    private static final String VALID_EMPTY_FILE_NAME = "valid-empty.xml";
    private static final String WITH_DATA_LABELS_FILE_NAME = "with-data-labels.xml";
    private static final String WITH_SECURITY_LABELS_FILE_NAME = "with-security-labels.xml";
    private static final String WITH_THIRD_PARTY_VERIFICATION_FILE_NAME =
            "with-third-party-verification.xml";

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for safety labels missing version. */
    @Test
    public void testSafetyLabelsMissingVersion() throws Exception {
        System.out.println("starting testSafetyLabelsMissingVersion.");
        hrToOdExpectException(MISSING_VERSION_FILE_NAME);
        odToHrExpectException(MISSING_VERSION_FILE_NAME);
    }

    /** Test for safety labels valid empty. */
    @Test
    public void testSafetyLabelsValidEmptyFile() throws Exception {
        System.out.println("starting testSafetyLabelsValidEmptyFile.");
        testHrToOdSafetyLabels(VALID_EMPTY_FILE_NAME);
        testOdToHrSafetyLabels(VALID_EMPTY_FILE_NAME);
    }

    /** Test for safety labels with data labels. */
    @Test
    public void testSafetyLabelsWithDataLabels() throws Exception {
        System.out.println("starting testSafetyLabelsWithDataLabels.");
        testHrToOdSafetyLabels(WITH_DATA_LABELS_FILE_NAME);
        testOdToHrSafetyLabels(WITH_DATA_LABELS_FILE_NAME);
    }

    /** Test for safety labels with security labels. */
    @Test
    public void testSafetyLabelsWithSecurityLabels() throws Exception {
        System.out.println("starting testSafetyLabelsWithSecurityLabels.");
        testHrToOdSafetyLabels(WITH_SECURITY_LABELS_FILE_NAME);
        testOdToHrSafetyLabels(WITH_SECURITY_LABELS_FILE_NAME);
    }

    /** Test for safety labels with third party verification. */
    @Test
    public void testSafetyLabelsWithThirdPartyVerification() throws Exception {
        System.out.println("starting testSafetyLabelsWithThirdPartyVerification.");
        testHrToOdSafetyLabels(WITH_THIRD_PARTY_VERIFICATION_FILE_NAME);
        testOdToHrSafetyLabels(WITH_THIRD_PARTY_VERIFICATION_FILE_NAME);
    }

    private void hrToOdExpectException(String fileName) {
        TestUtils.hrToOdExpectException(new SafetyLabelsFactory(), SAFETY_LABELS_HR_PATH, fileName);
    }

    private void odToHrExpectException(String fileName) {
        TestUtils.odToHrExpectException(new SafetyLabelsFactory(), SAFETY_LABELS_OD_PATH, fileName);
    }

    private void testHrToOdSafetyLabels(String fileName) throws Exception {
        TestUtils.testHrToOd(
                TestUtils.document(),
                new SafetyLabelsFactory(),
                SAFETY_LABELS_HR_PATH,
                SAFETY_LABELS_OD_PATH,
                fileName);
    }

    private void testOdToHrSafetyLabels(String fileName) throws Exception {
        TestUtils.testOdToHr(
                TestUtils.document(),
                new SafetyLabelsFactory(),
                SAFETY_LABELS_OD_PATH,
                SAFETY_LABELS_HR_PATH,
                fileName);
    }
}
