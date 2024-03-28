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
import org.w3c.dom.Document;

import java.nio.file.Paths;
import java.util.List;

@RunWith(JUnit4.class)
public class AppInfoTest {
    private static final String APP_INFO_HR_PATH = "com/android/asllib/appinfo/hr";
    private static final String APP_INFO_OD_PATH = "com/android/asllib/appinfo/od";
    public static final List<String> REQUIRED_FIELD_NAMES =
            List.of(
                    "title",
                    "description",
                    "containsAds",
                    "obeyAps",
                    "adsFingerprinting",
                    "securityFingerprinting",
                    "privacyPolicy",
                    "securityEndpoints",
                    "firstPartyEndpoints",
                    "serviceProviderEndpoints",
                    "category",
                    "email");
    public static final List<String> OPTIONAL_FIELD_NAMES = List.of("website");

    private static final String ALL_FIELDS_VALID_FILE_NAME = "all-fields-valid.xml";

    private Document mDoc = null;

    /** Logic for setting up tests (empty if not yet needed). */
    public static void main(String[] params) throws Exception {}

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
        mDoc = TestUtils.document();
    }

    /** Test for all fields valid. */
    @Test
    public void testAllFieldsValid() throws Exception {
        System.out.println("starting testAllFieldsValid.");
        testHrToOdAppInfo(ALL_FIELDS_VALID_FILE_NAME);
    }

    /** Tests missing required fields fails. */
    @Test
    public void testMissingRequiredFields() throws Exception {
        System.out.println("Starting testMissingRequiredFields");
        for (String reqField : REQUIRED_FIELD_NAMES) {
            System.out.println("testing missing required field: " + reqField);
            var appInfoEle =
                    TestUtils.getElementsFromResource(
                            Paths.get(APP_INFO_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            appInfoEle.get(0).removeAttribute(reqField);

            assertThrows(
                    MalformedXmlException.class,
                    () -> new AppInfoFactory().createFromHrElements(appInfoEle));
        }
    }

    /** Tests missing optional fields passes. */
    @Test
    public void testMissingOptionalFields() throws Exception {
        for (String optField : OPTIONAL_FIELD_NAMES) {
            var ele =
                    TestUtils.getElementsFromResource(
                            Paths.get(APP_INFO_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            ele.get(0).removeAttribute(optField);
            AppInfo appInfo = new AppInfoFactory().createFromHrElements(ele);
            appInfo.toOdDomElements(mDoc);
        }
    }

    private void testHrToOdAppInfo(String fileName) throws Exception {
        TestUtils.testHrToOd(
                mDoc, new AppInfoFactory(), APP_INFO_HR_PATH, APP_INFO_OD_PATH, fileName);
    }
}
