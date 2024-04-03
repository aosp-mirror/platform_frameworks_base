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
import org.w3c.dom.Document;

import java.nio.file.Paths;
import java.util.List;

@RunWith(JUnit4.class)
public class SecurityLabelsTest {
    private static final String SECURITY_LABELS_HR_PATH = "com/android/asllib/securitylabels/hr";
    private static final String SECURITY_LABELS_OD_PATH = "com/android/asllib/securitylabels/od";

    public static final List<String> OPTIONAL_FIELD_NAMES =
            List.of("isDataDeletable", "isDataEncrypted");

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
        testHrToOdSecurityLabels(ALL_FIELDS_VALID_FILE_NAME);
    }

    /** Tests missing optional fields passes. */
    @Test
    public void testMissingOptionalFields() throws Exception {
        for (String optField : OPTIONAL_FIELD_NAMES) {
            var ele =
                    TestUtils.getElementsFromResource(
                            Paths.get(SECURITY_LABELS_HR_PATH, ALL_FIELDS_VALID_FILE_NAME));
            ele.get(0).removeAttribute(optField);
            SecurityLabels securityLabels = new SecurityLabelsFactory().createFromHrElements(ele);
            securityLabels.toOdDomElements(mDoc);
        }
    }

    private void testHrToOdSecurityLabels(String fileName) throws Exception {
        TestUtils.testHrToOd(
                mDoc,
                new SecurityLabelsFactory(),
                SECURITY_LABELS_HR_PATH,
                SECURITY_LABELS_OD_PATH,
                fileName);
    }
}
