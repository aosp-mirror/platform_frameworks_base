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
import com.android.asllib.util.XmlUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.w3c.dom.Element;

import java.nio.file.Paths;
import java.util.List;

@RunWith(JUnit4.class)
public class AppInfoTest {
    private static final long DEFAULT_VERSION = 2L;
    private static final String APP_INFO_HR_PATH = "com/android/asllib/appinfo/hr";
    private static final String APP_INFO_OD_PATH = "com/android/asllib/appinfo/od";
    public static final List<String> REQUIRED_FIELD_NAMES =
            List.of("apsCompliant", "privacyPolicy");
    public static final List<String> REQUIRED_FIELD_NAMES_OD =
            List.of("aps_compliant", "privacy_policy");
    public static final List<String> REQUIRED_CHILD_NAMES =
            List.of("first-party-endpoints", "service-provider-endpoints");
    public static final List<String> REQUIRED_CHILD_NAMES_OD =
            List.of("first_party_endpoints", "service_provider_endpoints");
    public static final List<String> OPTIONAL_FIELD_NAMES = List.of();
    public static final List<String> OPTIONAL_FIELD_NAMES_OD = List.of();

    public static final List<String> REQUIRED_FIELD_NAMES_OD_V1 =
            List.of(
                    "title",
                    "description",
                    "contains_ads",
                    "obey_aps",
                    "ads_fingerprinting",
                    "security_fingerprinting",
                    "privacy_policy",
                    "security_endpoints",
                    "first_party_endpoints",
                    "service_provider_endpoints",
                    "category");
    public static final List<String> OPTIONAL_FIELD_NAMES_OD_V1 = List.of("website", "email");

    private static final String ALL_FIELDS_VALID_FILE_NAME = "all-fields-valid.xml";
    private static final String ALL_FIELDS_VALID_V1_FILE_NAME = "all-fields-valid-v1.xml";
    public static final String UNRECOGNIZED_V1_FILE_NAME = "unrecognized-v1.xml";

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
        testHrToOdAppInfo(ALL_FIELDS_VALID_FILE_NAME);
        testOdToHrAppInfo(ALL_FIELDS_VALID_FILE_NAME);
    }

    /** Test for all fields valid v1. */
    @Test
    public void testAllFieldsValidV1() throws Exception {
        System.out.println("starting testAllFieldsValidV1.");
        var unused =
                new AppInfoFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(APP_INFO_OD_PATH, ALL_FIELDS_VALID_V1_FILE_NAME)),
                                1L);
    }

    /** Test for unrecognized field v1. */
    @Test
    public void testUnrecognizedFieldV1() throws Exception {
        System.out.println("starting testUnrecognizedFieldV1.");
        assertThrows(
                MalformedXmlException.class,
                () ->
                        new AppInfoFactory()
                                .createFromOdElement(
                                        TestUtils.getElementFromResource(
                                                Paths.get(
                                                        APP_INFO_OD_PATH,
                                                        UNRECOGNIZED_V1_FILE_NAME)),
                                        1L));
    }

    /** Tests missing required fields fails, V1. */
    @Test
    public void testMissingRequiredFieldsOdV1() throws Exception {
        for (String reqField : REQUIRED_FIELD_NAMES_OD_V1) {
            System.out.println("testing missing required field od v1: " + reqField);
            var appInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_OD_PATH, ALL_FIELDS_VALID_V1_FILE_NAME));
            TestUtils.removeOdChildEleWithName(appInfoEle, reqField);
            assertThrows(
                    MalformedXmlException.class,
                    () -> new AppInfoFactory().createFromOdElement(appInfoEle, 1L));
        }
    }

    /** Tests missing optional fields passes, V1. */
    @Test
    public void testMissingOptionalFieldsOdV1() throws Exception {
        for (String optField : OPTIONAL_FIELD_NAMES_OD_V1) {
            System.out.println("testing missing optional field od v1: " + optField);
            var appInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_OD_PATH, ALL_FIELDS_VALID_V1_FILE_NAME));
            TestUtils.removeOdChildEleWithName(appInfoEle, optField);
            var unused = new AppInfoFactory().createFromOdElement(appInfoEle, 1L);
        }
    }

    /** Tests missing required fields fails. */
    @Test
    public void testMissingRequiredFields() throws Exception {
        System.out.println("Starting testMissingRequiredFields");
        for (String reqField : REQUIRED_FIELD_NAMES) {
            System.out.println("testing missing required field hr: " + reqField);
            var appInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            appInfoEle.removeAttribute(reqField);

            assertThrows(
                    MalformedXmlException.class,
                    () -> new AppInfoFactory().createFromHrElement(appInfoEle, DEFAULT_VERSION));
        }

        for (String reqField : REQUIRED_FIELD_NAMES_OD) {
            System.out.println("testing missing required field od: " + reqField);
            var appInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_OD_PATH, ALL_FIELDS_VALID_FILE_NAME));
            TestUtils.removeOdChildEleWithName(appInfoEle, reqField);
            assertThrows(
                    MalformedXmlException.class,
                    () -> new AppInfoFactory().createFromOdElement(appInfoEle, DEFAULT_VERSION));
        }
    }

    /** Tests missing required child fails. */
    @Test
    public void testMissingRequiredChild() throws Exception {
        System.out.println("Starting testMissingRequiredFields");
        for (String reqChildName : REQUIRED_CHILD_NAMES) {
            System.out.println("testing missing required child hr: " + reqChildName);
            var appInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            var child = XmlUtils.getChildrenByTagName(appInfoEle, reqChildName).get(0);
            appInfoEle.removeChild(child);
            assertThrows(
                    MalformedXmlException.class,
                    () -> new AppInfoFactory().createFromHrElement(appInfoEle, DEFAULT_VERSION));
        }

        for (String reqField : REQUIRED_CHILD_NAMES_OD) {
            System.out.println("testing missing required child od: " + reqField);
            var appInfoEle =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_OD_PATH, ALL_FIELDS_VALID_FILE_NAME));
            TestUtils.removeOdChildEleWithName(appInfoEle, reqField);
            assertThrows(
                    MalformedXmlException.class,
                    () -> new AppInfoFactory().createFromOdElement(appInfoEle, DEFAULT_VERSION));
        }
    }

    /** Tests missing optional fields passes. */
    @Test
    public void testMissingOptionalFields() throws Exception {
        for (String optField : OPTIONAL_FIELD_NAMES) {
            var ele =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            ele.removeAttribute(optField);
            AppInfo appInfo = new AppInfoFactory().createFromHrElement(ele, DEFAULT_VERSION);
            var unused = appInfo.toOdDomElement(TestUtils.document());
        }

        for (String optField : OPTIONAL_FIELD_NAMES_OD) {
            var ele =
                    TestUtils.getElementFromResource(
                            Paths.get(APP_INFO_OD_PATH, ALL_FIELDS_VALID_FILE_NAME));
            TestUtils.removeOdChildEleWithName(ele, optField);
            AppInfo appInfo = new AppInfoFactory().createFromOdElement(ele, DEFAULT_VERSION);
            var unused = appInfo.toHrDomElement(TestUtils.document());
        }
    }

    private void testHrToOdAppInfo(String fileName) throws Exception {
        var doc = TestUtils.document();
        AppInfo appInfo =
                new AppInfoFactory()
                        .createFromHrElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(APP_INFO_HR_PATH, fileName)),
                                DEFAULT_VERSION);
        Element appInfoEle = appInfo.toOdDomElement(doc);
        doc.appendChild(appInfoEle);
        TestUtils.testFormatToFormat(doc, Paths.get(APP_INFO_OD_PATH, fileName));
    }


    private void testOdToHrAppInfo(String fileName) throws Exception {
        testOdToHrAppInfo(fileName, DEFAULT_VERSION);
    }

    private void testOdToHrAppInfo(String fileName, long version) throws Exception {
        var doc = TestUtils.document();
        AppInfo appInfo =
                new AppInfoFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(APP_INFO_OD_PATH, fileName)),
                                version);
        Element appInfoEle = appInfo.toHrDomElement(doc);
        doc.appendChild(appInfoEle);
        TestUtils.testFormatToFormat(doc, Paths.get(APP_INFO_HR_PATH, fileName));
    }
}
