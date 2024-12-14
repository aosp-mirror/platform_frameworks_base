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

import static org.junit.Assert.assertThrows;

import com.android.asllib.testutils.TestUtils;
import com.android.asllib.util.MalformedXmlException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Element;

import java.nio.file.Paths;
import java.util.List;

@RunWith(JUnit4.class)
public class AndroidSafetyLabelTest {
    private static final String ANDROID_SAFETY_LABEL_HR_PATH =
            "com/android/asllib/androidsafetylabel/hr";
    private static final String ANDROID_SAFETY_LABEL_OD_PATH =
            "com/android/asllib/androidsafetylabel/od";

    private static final String MISSING_VERSION_FILE_NAME = "missing-version.xml";
    private static final String VALID_V2_FILE_NAME = "valid-empty.xml";
    private static final String VALID_V1_FILE_NAME = "valid-v1.xml";
    private static final String WITH_SAFETY_LABELS_FILE_NAME = "with-safety-labels.xml";
    private static final String WITH_SYSTEM_APP_SAFETY_LABEL_FILE_NAME =
            "with-system-app-safety-label.xml";
    private static final String WITH_TRANSPARENCY_INFO_FILE_NAME = "with-transparency-info.xml";

    public static final List<String> REQUIRED_FIELD_NAMES_OD_V2 =
            List.of("system_app_safety_label", "transparency_info");

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

    /** Test for android safety label valid v2. */
    @Test
    public void testAndroidSafetyLabelValidV2File() throws Exception {
        System.out.println("starting testAndroidSafetyLabelValidV2File.");
        testHrToOdAndroidSafetyLabel(VALID_V2_FILE_NAME);
        testOdToHrAndroidSafetyLabel(VALID_V2_FILE_NAME);
    }

    /** Test for android safety label with safety labels. */
    @Test
    public void testAndroidSafetyLabelWithSafetyLabels() throws Exception {
        System.out.println("starting testAndroidSafetyLabelWithSafetyLabels.");
        testHrToOdAndroidSafetyLabel(WITH_SAFETY_LABELS_FILE_NAME);
        testOdToHrAndroidSafetyLabel(WITH_SAFETY_LABELS_FILE_NAME);
    }

    /** Tests missing required fields fails, V2. */
    @Test
    public void testMissingRequiredFieldsOdV2() throws Exception {
        for (String reqField : REQUIRED_FIELD_NAMES_OD_V2) {
            System.out.println("testing missing required field od v2: " + reqField);
            var ele =
                    TestUtils.getElementFromResource(
                            Paths.get(ANDROID_SAFETY_LABEL_OD_PATH, VALID_V2_FILE_NAME));
            TestUtils.removeOdChildEleWithName(ele, reqField);
            assertThrows(
                    MalformedXmlException.class,
                    () -> new AndroidSafetyLabelFactory().createFromOdElement(ele));
        }
    }

    /** Tests missing optional fields succeeds, V1. */
    @Test
    public void testMissingOptionalFieldsOdV1() throws Exception {
        for (String reqField : REQUIRED_FIELD_NAMES_OD_V2) {
            System.out.println("testing missing optional field od v1: " + reqField);
            var ele =
                    TestUtils.getElementFromResource(
                            Paths.get(ANDROID_SAFETY_LABEL_OD_PATH, VALID_V1_FILE_NAME));
            TestUtils.removeOdChildEleWithName(ele, reqField);
            var unused = new AndroidSafetyLabelFactory().createFromOdElement(ele);
        }
    }

    private void hrToOdExpectException(String fileName) {
        assertThrows(
                MalformedXmlException.class,
                () -> {
                    new AndroidSafetyLabelFactory()
                            .createFromHrElement(
                                    TestUtils.getElementFromResource(
                                            Paths.get(ANDROID_SAFETY_LABEL_HR_PATH, fileName)));
                });
    }

    private void odToHrExpectException(String fileName) {
        assertThrows(
                MalformedXmlException.class,
                () -> {
                    new AndroidSafetyLabelFactory()
                            .createFromOdElement(
                                    TestUtils.getElementFromResource(
                                            Paths.get(ANDROID_SAFETY_LABEL_OD_PATH, fileName)));
                });
    }

    private void testHrToOdAndroidSafetyLabel(String fileName) throws Exception {
        var doc = TestUtils.document();
        AndroidSafetyLabel asl =
                new AndroidSafetyLabelFactory()
                        .createFromHrElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(ANDROID_SAFETY_LABEL_HR_PATH, fileName)));
        Element aslEle = asl.toOdDomElement(doc);
        doc.appendChild(aslEle);
        TestUtils.testFormatToFormat(doc, Paths.get(ANDROID_SAFETY_LABEL_OD_PATH, fileName));
    }

    private void testOdToHrAndroidSafetyLabel(String fileName) throws Exception {
        var doc = TestUtils.document();
        AndroidSafetyLabel asl =
                new AndroidSafetyLabelFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(ANDROID_SAFETY_LABEL_OD_PATH, fileName)));
        Element aslEle = asl.toHrDomElement(doc);
        doc.appendChild(aslEle);
        TestUtils.testFormatToFormat(doc, Paths.get(ANDROID_SAFETY_LABEL_HR_PATH, fileName));
    }
}
