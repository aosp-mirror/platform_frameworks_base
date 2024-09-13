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
public class TransparencyInfoTest {
    private static final long DEFAULT_VERSION = 2L;

    private static final String TRANSPARENCY_INFO_HR_PATH =
            "com/android/asllib/transparencyinfo/hr";
    private static final String TRANSPARENCY_INFO_OD_PATH =
            "com/android/asllib/transparencyinfo/od";
    private static final String WITH_APP_INFO_FILE_NAME = "with-app-info.xml";
    private static final String VALID_EMPTY_V1_FILE_NAME = "valid-empty-v1.xml";
    private static final String VALID_DEV_INFO_V1_FILE_NAME = "valid-dev-info-v1.xml";
    private static final String WITH_APP_INFO_AND_DEV_INFO_FILE_NAME =
            "with-app-info-v2-and-dev-info-v1.xml";

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for transparency info with app info. */
    @Test
    public void testTransparencyInfoWithAppInfo() throws Exception {
        System.out.println("starting testTransparencyInfoWithAppInfo.");
        testHrToOdTransparencyInfo(WITH_APP_INFO_FILE_NAME, DEFAULT_VERSION);
        testOdToHrTransparencyInfo(WITH_APP_INFO_FILE_NAME, DEFAULT_VERSION);
    }

    /** Test for testMissingAppInfoFailsInV2. */
    @Test
    public void testMissingAppInfoFailsInV2() throws Exception {
        System.out.println("starting testMissingAppInfoFailsInV2.");
        odToHrExpectException(VALID_EMPTY_V1_FILE_NAME, 2L);
    }

    /** Test for testMissingAppInfoPassesInV1. */
    @Test
    public void testMissingAppInfoPassesInV1() throws Exception {
        System.out.println("starting testMissingAppInfoPassesInV1.");
        testParseOdTransparencyInfo(VALID_EMPTY_V1_FILE_NAME, 1L);
    }

    /** Test for testDeveloperInfoExistencePassesInV1. */
    @Test
    public void testDeveloperInfoExistencePassesInV1() throws Exception {
        System.out.println("starting testDeveloperInfoExistencePassesInV1.");
        testParseOdTransparencyInfo(VALID_DEV_INFO_V1_FILE_NAME, 1L);
    }

    /** Test for testDeveloperInfoExistenceFailsInV2. */
    @Test
    public void testDeveloperInfoExistenceFailsInV2() throws Exception {
        System.out.println("starting testDeveloperInfoExistenceFailsInV2.");
        odToHrExpectException(WITH_APP_INFO_AND_DEV_INFO_FILE_NAME, 2L);
    }

    private void testHrToOdTransparencyInfo(String fileName, long version) throws Exception {
        var doc = TestUtils.document();
        TransparencyInfo transparencyInfo =
                new TransparencyInfoFactory()
                        .createFromHrElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(TRANSPARENCY_INFO_HR_PATH, fileName)),
                                version);
        Element resultingEle = transparencyInfo.toOdDomElement(doc);
        doc.appendChild(resultingEle);
        TestUtils.testFormatToFormat(doc, Paths.get(TRANSPARENCY_INFO_OD_PATH, fileName));
    }

    private void testParseOdTransparencyInfo(String fileName, long version) throws Exception {
        var unused =
                new TransparencyInfoFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(TRANSPARENCY_INFO_OD_PATH, fileName)),
                                version);
    }

    private void testOdToHrTransparencyInfo(String fileName, long version) throws Exception {
        var doc = TestUtils.document();
        TransparencyInfo transparencyInfo =
                new TransparencyInfoFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(TRANSPARENCY_INFO_OD_PATH, fileName)),
                                version);
        Element resultingEle = transparencyInfo.toHrDomElement(doc);
        doc.appendChild(resultingEle);
        TestUtils.testFormatToFormat(doc, Paths.get(TRANSPARENCY_INFO_HR_PATH, fileName));
    }

    private void odToHrExpectException(String fileName, long version)
            throws ParserConfigurationException, IOException, SAXException {
        var ele = TestUtils.getElementFromResource(Paths.get(TRANSPARENCY_INFO_OD_PATH, fileName));
        assertThrows(
                MalformedXmlException.class,
                () -> new TransparencyInfoFactory().createFromOdElement(ele, version));
    }
}
