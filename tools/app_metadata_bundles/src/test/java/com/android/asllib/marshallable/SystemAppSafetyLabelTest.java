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
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Paths;

import javax.xml.parsers.ParserConfigurationException;

@RunWith(JUnit4.class)
public class SystemAppSafetyLabelTest {
    private static final long DEFAULT_VERSION = 2L;

    private static final String SYSTEM_APP_SAFETY_LABEL_HR_PATH =
            "com/android/asllib/systemappsafetylabel/hr";
    private static final String SYSTEM_APP_SAFETY_LABEL_OD_PATH =
            "com/android/asllib/systemappsafetylabel/od";

    private static final String VALID_FILE_NAME = "valid.xml";
    private static final String VALID_V1_FILE_NAME = "valid-v1.xml";
    private static final String MISSING_BOOL_FILE_NAME = "missing-bool.xml";

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
        testHrToOdSystemAppSafetyLabel(VALID_FILE_NAME, DEFAULT_VERSION);
        testOdToHrSystemAppSafetyLabel(VALID_FILE_NAME, DEFAULT_VERSION);
    }

    /** Test for valid v1. */
    @Test
    public void testValidV1() throws Exception {
        System.out.println("starting testValidV1.");
        var doc = TestUtils.document();
        var unused =
                new SystemAppSafetyLabelFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(
                                                SYSTEM_APP_SAFETY_LABEL_OD_PATH,
                                                VALID_V1_FILE_NAME)),
                                1L);
    }

    /** Test for testV1InvalidAsV2. */
    @Test
    public void testV1InvalidAsV2() throws Exception {
        System.out.println("starting testV1InvalidAsV2.");
        odToHrExpectException(VALID_V1_FILE_NAME, 2L);
    }

    /** Tests missing bool. */
    @Test
    public void testMissingBool() throws Exception {
        System.out.println("starting testMissingBool.");
        hrToOdExpectException(MISSING_BOOL_FILE_NAME, DEFAULT_VERSION);
        odToHrExpectException(MISSING_BOOL_FILE_NAME, DEFAULT_VERSION);
    }

    private void hrToOdExpectException(String fileName, long version)
            throws ParserConfigurationException, IOException, SAXException {
        var ele =
                TestUtils.getElementFromResource(
                        Paths.get(SYSTEM_APP_SAFETY_LABEL_HR_PATH, fileName));
        assertThrows(
                MalformedXmlException.class,
                () -> new SystemAppSafetyLabelFactory().createFromHrElement(ele, version));
    }

    private void odToHrExpectException(String fileName, long version)
            throws ParserConfigurationException, IOException, SAXException {
        var ele =
                TestUtils.getElementFromResource(
                        Paths.get(SYSTEM_APP_SAFETY_LABEL_OD_PATH, fileName));
        assertThrows(
                MalformedXmlException.class,
                () -> new SystemAppSafetyLabelFactory().createFromOdElement(ele, version));
    }

    private void testHrToOdSystemAppSafetyLabel(String fileName, long version) throws Exception {
        var doc = TestUtils.document();
        SystemAppSafetyLabel systemAppSafetyLabel =
                new SystemAppSafetyLabelFactory()
                        .createFromHrElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(SYSTEM_APP_SAFETY_LABEL_HR_PATH, fileName)),
                                version);
        Element resultingEle = systemAppSafetyLabel.toOdDomElement(doc);
        doc.appendChild(resultingEle);
        TestUtils.testFormatToFormat(doc, Paths.get(SYSTEM_APP_SAFETY_LABEL_OD_PATH, fileName));
    }

    private void testOdToHrSystemAppSafetyLabel(String fileName, long version) throws Exception {
        var doc = TestUtils.document();
        SystemAppSafetyLabel systemAppSafetyLabel =
                new SystemAppSafetyLabelFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(SYSTEM_APP_SAFETY_LABEL_OD_PATH, fileName)),
                                version);
        Element resultingEle = systemAppSafetyLabel.toHrDomElement(doc);
        doc.appendChild(resultingEle);
        TestUtils.testFormatToFormat(doc, Paths.get(SYSTEM_APP_SAFETY_LABEL_HR_PATH, fileName));
    }
}
