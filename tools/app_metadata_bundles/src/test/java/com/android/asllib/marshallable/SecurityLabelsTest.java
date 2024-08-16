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
import org.w3c.dom.Element;

import java.nio.file.Paths;
import java.util.List;

@RunWith(JUnit4.class)
public class SecurityLabelsTest {
    private static final String SECURITY_LABELS_HR_PATH = "com/android/asllib/securitylabels/hr";
    private static final String SECURITY_LABELS_OD_PATH = "com/android/asllib/securitylabels/od";

    public static final List<String> OPTIONAL_FIELD_NAMES =
            List.of("isDataDeletable", "isDataEncrypted");
    public static final List<String> OPTIONAL_FIELD_NAMES_OD =
            List.of("is_data_deletable", "is_data_encrypted");

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
        testHrToOdSecurityLabels(ALL_FIELDS_VALID_FILE_NAME);
        testOdToHrSecurityLabels(ALL_FIELDS_VALID_FILE_NAME);
    }

    /** Tests missing optional fields passes. */
    @Test
    public void testMissingOptionalFields() throws Exception {
        for (String optField : OPTIONAL_FIELD_NAMES) {
            var ele =
                    TestUtils.getElementFromResource(
                            Paths.get(SECURITY_LABELS_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            ele.removeAttribute(optField);
            SecurityLabels securityLabels = new SecurityLabelsFactory().createFromHrElement(ele);
            var unused = securityLabels.toOdDomElement(TestUtils.document());
        }
        for (String optField : OPTIONAL_FIELD_NAMES_OD) {
            var ele =
                    TestUtils.getElementFromResource(
                            Paths.get(SECURITY_LABELS_OD_PATH, ALL_FIELDS_VALID_FILE_NAME));
            TestUtils.removeOdChildEleWithName(ele, optField);
            SecurityLabels securityLabels = new SecurityLabelsFactory().createFromOdElement(ele);
            var unused = securityLabels.toHrDomElement(TestUtils.document());
        }
    }

    private void testHrToOdSecurityLabels(String fileName) throws Exception {
        var doc = TestUtils.document();
        SecurityLabels securityLabels =
                new SecurityLabelsFactory()
                        .createFromHrElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(SECURITY_LABELS_HR_PATH, fileName)));
        Element ele = securityLabels.toOdDomElement(doc);
        doc.appendChild(ele);
        TestUtils.testFormatToFormat(doc, Paths.get(SECURITY_LABELS_OD_PATH, fileName));
    }

    private void testOdToHrSecurityLabels(String fileName) throws Exception {
        var doc = TestUtils.document();
        SecurityLabels securityLabels =
                new SecurityLabelsFactory()
                        .createFromOdElement(
                                TestUtils.getElementFromResource(
                                        Paths.get(SECURITY_LABELS_OD_PATH, fileName)));
        Element ele = securityLabels.toHrDomElement(doc);
        doc.appendChild(ele);
        TestUtils.testFormatToFormat(doc, Paths.get(SECURITY_LABELS_HR_PATH, fileName));
    }
}
