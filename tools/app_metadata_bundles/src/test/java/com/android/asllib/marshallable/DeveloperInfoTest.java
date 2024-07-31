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
public class DeveloperInfoTest {
    private static final String DEVELOPER_INFO_HR_PATH = "com/android/asllib/developerinfo/hr";
    private static final String DEVELOPER_INFO_OD_PATH = "com/android/asllib/developerinfo/od";
    public static final List<String> REQUIRED_FIELD_NAMES =
            List.of("address", "countryRegion", "email", "name", "relationship");
    public static final List<String> REQUIRED_FIELD_NAMES_OD =
            List.of("address", "country_region", "email", "name", "relationship");
    public static final List<String> OPTIONAL_FIELD_NAMES = List.of("website", "registryId");
    public static final List<String> OPTIONAL_FIELD_NAMES_OD =
            List.of("website", "app_developer_registry_id");

    private static final String ALL_FIELDS_VALID_FILE_NAME = "all-fields-valid.xml";

    /** Logic for setting up tests (empty if not yet needed). */
    public static void main(String[] params) throws Exception {}

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for all fields valid. */
    @Test
    public void testAllFieldsValid() throws Exception {
        System.out.println("starting testAllFieldsValid.");
        testHrToOdDeveloperInfo(ALL_FIELDS_VALID_FILE_NAME);
        testOdToHrDeveloperInfo(ALL_FIELDS_VALID_FILE_NAME);
    }

    /** Tests missing required fields fails. */
    @Test
    public void testMissingRequiredFields() throws Exception {
        System.out.println("Starting testMissingRequiredFields");
        for (String reqField : REQUIRED_FIELD_NAMES) {
            System.out.println("testing missing required field: " + reqField);
            var developerInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(DEVELOPER_INFO_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            developerInfoEle.removeAttribute(reqField);

            assertThrows(
                    MalformedXmlException.class,
                    () -> new DeveloperInfoFactory().createFromHrElement(developerInfoEle));
        }

        for (String reqField : REQUIRED_FIELD_NAMES_OD) {
            System.out.println("testing missing required field od: " + reqField);
            var developerInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(DEVELOPER_INFO_OD_PATH, ALL_FIELDS_VALID_FILE_NAME));
            TestUtils.removeOdChildEleWithName(developerInfoEle, reqField);

            assertThrows(
                    MalformedXmlException.class,
                    () -> new DeveloperInfoFactory().createFromOdElement(developerInfoEle));
        }
    }

    /** Tests missing optional fields passes. */
    @Test
    public void testMissingOptionalFields() throws Exception {
        for (String optField : OPTIONAL_FIELD_NAMES) {
            var developerInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(DEVELOPER_INFO_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            developerInfoEle.removeAttribute(optField);
            DeveloperInfo developerInfo =
                    new DeveloperInfoFactory().createFromHrElement(developerInfoEle);
            var unused = developerInfo.toOdDomElement(TestUtils.document());
        }

        for (String optField : OPTIONAL_FIELD_NAMES_OD) {
            var developerInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(DEVELOPER_INFO_OD_PATH, ALL_FIELDS_VALID_FILE_NAME));
            TestUtils.removeOdChildEleWithName(developerInfoEle, optField);
            DeveloperInfo developerInfo =
                    new DeveloperInfoFactory().createFromOdElement(developerInfoEle);
            var unused = developerInfo.toHrDomElement(TestUtils.document());
        }
    }

    private void testHrToOdDeveloperInfo(String fileName) throws Exception {
        var doc = TestUtils.document();
        DeveloperInfo developerInfo =
                new DeveloperInfoFactory()
                        .createFromHrElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(DEVELOPER_INFO_HR_PATH, fileName)));
        Element developerInfoEle = developerInfo.toOdDomElement(doc);
        doc.appendChild(developerInfoEle);
        TestUtils.testFormatToFormat(doc, Paths.get(DEVELOPER_INFO_OD_PATH, fileName));
    }

    private void testOdToHrDeveloperInfo(String fileName) throws Exception {
        var doc = TestUtils.document();
        DeveloperInfo developerInfo =
                new DeveloperInfoFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(DEVELOPER_INFO_OD_PATH, fileName)));
        Element developerInfoEle = developerInfo.toHrDomElement(doc);
        doc.appendChild(developerInfoEle);
        TestUtils.testFormatToFormat(doc, Paths.get(DEVELOPER_INFO_HR_PATH, fileName));
    }
}
