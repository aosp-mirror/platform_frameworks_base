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

@RunWith(JUnit4.class)
public class DataLabelsTest {
    private static final String DATA_LABELS_HR_PATH = "com/android/asllib/datalabels/hr";
    private static final String DATA_LABELS_OD_PATH = "com/android/asllib/datalabels/od";

    private static final String ACCESSED_VALID_BOOL_FILE_NAME =
            "data-labels-accessed-valid-bool.xml";
    private static final String ACCESSED_INVALID_BOOL_FILE_NAME =
            "data-labels-accessed-invalid-bool.xml";
    private static final String COLLECTED_VALID_BOOL_FILE_NAME =
            "data-labels-collected-valid-bool.xml";
    private static final String COLLECTED_INVALID_BOOL_FILE_NAME =
            "data-labels-collected-invalid-bool.xml";
    private static final String SHARED_VALID_BOOL_FILE_NAME = "data-labels-shared-valid-bool.xml";
    private static final String SHARED_INVALID_BOOL_FILE_NAME =
            "data-labels-shared-invalid-bool.xml";

    private Document mDoc = null;

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
        mDoc = TestUtils.document();
    }

    /** Test for data labels accessed valid bool. */
    @Test
    public void testDataLabelsAccessedValidBool() throws Exception {
        System.out.println("starting testDataLabelsAccessedValidBool.");
        testHrToOdDataLabels(ACCESSED_VALID_BOOL_FILE_NAME);
    }

    /** Test for data labels accessed invalid bool. */
    @Test
    public void testDataLabelsAccessedInvalidBool() throws Exception {
        System.out.println("starting testDataLabelsAccessedInvalidBool.");
        hrToOdExpectException(ACCESSED_INVALID_BOOL_FILE_NAME);
    }

    /** Test for data labels collected valid bool. */
    @Test
    public void testDataLabelsCollectedValidBool() throws Exception {
        System.out.println("starting testDataLabelsCollectedValidBool.");
        testHrToOdDataLabels(COLLECTED_VALID_BOOL_FILE_NAME);
    }

    /** Test for data labels collected invalid bool. */
    @Test
    public void testDataLabelsCollectedInvalidBool() throws Exception {
        System.out.println("starting testDataLabelsCollectedInvalidBool.");
        hrToOdExpectException(COLLECTED_INVALID_BOOL_FILE_NAME);
    }

    /** Test for data labels shared valid bool. */
    @Test
    public void testDataLabelsSharedValidBool() throws Exception {
        System.out.println("starting testDataLabelsSharedValidBool.");
        testHrToOdDataLabels(SHARED_VALID_BOOL_FILE_NAME);
    }

    /** Test for data labels shared invalid bool. */
    @Test
    public void testDataLabelsSharedInvalidBool() throws Exception {
        System.out.println("starting testDataLabelsSharedInvalidBool.");
        hrToOdExpectException(SHARED_INVALID_BOOL_FILE_NAME);
    }

    private void hrToOdExpectException(String fileName) {
        TestUtils.hrToOdExpectException(new DataLabelsFactory(), DATA_LABELS_HR_PATH, fileName);
    }

    private void testHrToOdDataLabels(String fileName) throws Exception {
        TestUtils.testHrToOd(
                mDoc, new DataLabelsFactory(), DATA_LABELS_HR_PATH, DATA_LABELS_OD_PATH, fileName);
    }
}
