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

@RunWith(JUnit4.class)
public class SafetyLabelsTest {
    private static final long DEFAULT_VERSION = 2L;

    private static final String SAFETY_LABELS_HR_PATH = "com/android/asllib/safetylabels/hr";
    private static final String SAFETY_LABELS_OD_PATH = "com/android/asllib/safetylabels/od";

    private static final String VALID_EMPTY_FILE_NAME = "valid-empty.xml";
    private static final String WITH_DATA_LABELS_FILE_NAME = "with-data-labels.xml";
    private static final String VALID_V1_FILE_NAME = "valid-v1.xml";
    private static final String UNRECOGNIZED_FIELD_V2_FILE_NAME = "unrecognized-field-v2.xml";

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for safety labels valid empty. */
    @Test
    public void testSafetyLabelsValidEmptyFile() throws Exception {
        System.out.println("starting testSafetyLabelsValidEmptyFile.");
        testHrToOdSafetyLabels(VALID_EMPTY_FILE_NAME, DEFAULT_VERSION);
        testOdToHrSafetyLabels(VALID_EMPTY_FILE_NAME, DEFAULT_VERSION);
    }

    /** Test for safety labels with data labels. */
    @Test
    public void testSafetyLabelsWithDataLabels() throws Exception {
        System.out.println("starting testSafetyLabelsWithDataLabels.");
        testHrToOdSafetyLabels(WITH_DATA_LABELS_FILE_NAME, DEFAULT_VERSION);
        testOdToHrSafetyLabels(WITH_DATA_LABELS_FILE_NAME, DEFAULT_VERSION);
    }

    /** Tests valid fields v1. */
    @Test
    public void testValidFieldsV1() throws Exception {
        var ele =
                TestUtils.getElementFromResource(
                        Paths.get(SAFETY_LABELS_OD_PATH, VALID_V1_FILE_NAME));
        var unused = new SafetyLabelsFactory().createFromOdElement(ele, 1L);
    }

    /** Tests unrecognized field v2. */
    @Test
    public void testUnrecognizedFieldV2() throws Exception {
        var ele =
                TestUtils.getElementFromResource(
                        Paths.get(SAFETY_LABELS_OD_PATH, VALID_V1_FILE_NAME));
        assertThrows(
                MalformedXmlException.class,
                () -> new SafetyLabelsFactory().createFromOdElement(ele, 2L));
    }

    private void testHrToOdSafetyLabels(String fileName, long version) throws Exception {
        var doc = TestUtils.document();
        SafetyLabels safetyLabels =
                new SafetyLabelsFactory()
                        .createFromHrElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(SAFETY_LABELS_HR_PATH, fileName)),
                                version);
        Element appInfoEle = safetyLabels.toOdDomElement(doc);
        doc.appendChild(appInfoEle);
        TestUtils.testFormatToFormat(doc, Paths.get(SAFETY_LABELS_OD_PATH, fileName));
    }

    private void testOdToHrSafetyLabels(String fileName, long version) throws Exception {
        var doc = TestUtils.document();
        SafetyLabels safetyLabels =
                new SafetyLabelsFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(SAFETY_LABELS_OD_PATH, fileName)),
                                version);
        Element appInfoEle = safetyLabels.toHrDomElement(doc);
        doc.appendChild(appInfoEle);
        TestUtils.testFormatToFormat(doc, Paths.get(SAFETY_LABELS_HR_PATH, fileName));
    }
}
