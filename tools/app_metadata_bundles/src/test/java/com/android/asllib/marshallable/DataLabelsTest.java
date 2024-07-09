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
    private static final String COLLECTED_EPHEMERAL_FILE_NAME =
            "data-labels-collected-ephemeral.xml";
    private static final String COLLECTED_EPHEMERAL_COLLISION_FILE_NAME =
            "data-labels-collected-ephemeral-collision.xml";
    private static final String COLLECTED_INVALID_BOOL_FILE_NAME =
            "data-labels-collected-invalid-bool.xml";
    private static final String SHARED_VALID_BOOL_FILE_NAME = "data-labels-shared-valid-bool.xml";
    private static final String SHARED_INVALID_BOOL_FILE_NAME =
            "data-labels-shared-invalid-bool.xml";

    private static final String ACTIONS_IN_APP_FILE_NAME = "data-category-actions-in-app.xml";
    private static final String APP_PERFORMANCE_FILE_NAME = "data-category-app-performance.xml";
    private static final String AUDIO_FILE_NAME = "data-category-audio.xml";
    private static final String CALENDAR_FILE_NAME = "data-category-calendar.xml";
    private static final String CONTACTS_FILE_NAME = "data-category-contacts.xml";
    private static final String EMAIL_TEXT_MESSAGE_FILE_NAME =
            "data-category-email-text-message.xml";
    private static final String FINANCIAL_FILE_NAME = "data-category-financial.xml";
    private static final String HEALTH_FITNESS_FILE_NAME = "data-category-health-fitness.xml";
    private static final String IDENTIFIERS_FILE_NAME = "data-category-identifiers.xml";
    private static final String LOCATION_FILE_NAME = "data-category-location.xml";
    private static final String PERSONAL_FILE_NAME = "data-category-personal.xml";
    private static final String PERSONAL_PARTIAL_FILE_NAME = "data-category-personal-partial.xml";
    private static final String PHOTO_VIDEO_FILE_NAME = "data-category-photo-video.xml";
    private static final String SEARCH_AND_BROWSING_FILE_NAME =
            "data-category-search-and-browsing.xml";
    private static final String STORAGE_FILE_NAME = "data-category-storage.xml";
    private static final String PERSONAL_MISSING_PURPOSE_FILE_NAME =
            "data-category-personal-missing-purpose.xml";
    private static final String PERSONAL_EMPTY_PURPOSE_FILE_NAME =
            "data-category-personal-empty-purpose.xml";
    private static final String UNRECOGNIZED_FILE_NAME = "data-category-unrecognized.xml";
    private static final String UNRECOGNIZED_TYPE_FILE_NAME = "data-category-unrecognized-type.xml";

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for data labels collected valid bool. */
    @Test
    public void testDataLabelsCollectedValidBool() throws Exception {
        System.out.println("starting testDataLabelsCollectedValidBool.");
        testHrToOdDataLabels(COLLECTED_VALID_BOOL_FILE_NAME);
        testOdToHrDataLabels(COLLECTED_VALID_BOOL_FILE_NAME);
    }

    /** Test for data labels collected ephemeral. */
    @Test
    public void testDataLabelsCollectedEphemeral() throws Exception {
        System.out.println("starting testDataLabelsCollectedEphemeral.");
        testHrToOdDataLabels(COLLECTED_EPHEMERAL_FILE_NAME);
        testOdToHrDataLabels(COLLECTED_EPHEMERAL_FILE_NAME);
    }

    /** Test for data labels ephemeral collision. */
    @Test
    public void testDataLabelsCollectedEphemeralCollision() throws Exception {
        System.out.println("starting testDataLabelsCollectedEphemeralCollision.");
        hrToOdExpectException(COLLECTED_EPHEMERAL_COLLISION_FILE_NAME);
    }

    /** Test for data labels collected invalid bool. */
    @Test
    public void testDataLabelsCollectedInvalidBool() throws Exception {
        System.out.println("starting testDataLabelsCollectedInvalidBool.");
        hrToOdExpectException(COLLECTED_INVALID_BOOL_FILE_NAME);
        odToHrExpectException(COLLECTED_INVALID_BOOL_FILE_NAME);
    }

    /** Test for data labels shared valid bool. */
    @Test
    public void testDataLabelsSharedValidBool() throws Exception {
        System.out.println("starting testDataLabelsSharedValidBool.");
        testHrToOdDataLabels(SHARED_VALID_BOOL_FILE_NAME);
        testOdToHrDataLabels(SHARED_VALID_BOOL_FILE_NAME);
    }

    /** Test for data labels shared invalid bool. */
    @Test
    public void testDataLabelsSharedInvalidBool() throws Exception {
        System.out.println("starting testDataLabelsSharedInvalidBool.");
        hrToOdExpectException(SHARED_INVALID_BOOL_FILE_NAME);
    }

    /* Data categories bidirectional tests... */

    /** Test for data labels actions in app. */
    @Test
    public void testDataLabelsActionsInApp() throws Exception {
        System.out.println("starting testDataLabelsActionsInApp.");
        testHrToOdDataLabels(ACTIONS_IN_APP_FILE_NAME);
        testOdToHrDataLabels(ACTIONS_IN_APP_FILE_NAME);
    }

    /** Test for data labels app performance. */
    @Test
    public void testDataLabelsAppPerformance() throws Exception {
        System.out.println("starting testDataLabelsAppPerformance.");
        testHrToOdDataLabels(APP_PERFORMANCE_FILE_NAME);
        testOdToHrDataLabels(APP_PERFORMANCE_FILE_NAME);
    }

    /** Test for data labels audio. */
    @Test
    public void testDataLabelsAudio() throws Exception {
        System.out.println("starting testDataLabelsAudio.");
        testHrToOdDataLabels(AUDIO_FILE_NAME);
        testOdToHrDataLabels(AUDIO_FILE_NAME);
    }

    /** Test for data labels calendar. */
    @Test
    public void testDataLabelsCalendar() throws Exception {
        System.out.println("starting testDataLabelsCalendar.");
        testHrToOdDataLabels(CALENDAR_FILE_NAME);
        testOdToHrDataLabels(CALENDAR_FILE_NAME);
    }

    /** Test for data labels contacts. */
    @Test
    public void testDataLabelsContacts() throws Exception {
        System.out.println("starting testDataLabelsContacts.");
        testHrToOdDataLabels(CONTACTS_FILE_NAME);
        testOdToHrDataLabels(CONTACTS_FILE_NAME);
    }

    /** Test for data labels email text message. */
    @Test
    public void testDataLabelsEmailTextMessage() throws Exception {
        System.out.println("starting testDataLabelsEmailTextMessage.");
        testHrToOdDataLabels(EMAIL_TEXT_MESSAGE_FILE_NAME);
        testOdToHrDataLabels(EMAIL_TEXT_MESSAGE_FILE_NAME);
    }

    /** Test for data labels financial. */
    @Test
    public void testDataLabelsFinancial() throws Exception {
        System.out.println("starting testDataLabelsFinancial.");
        testHrToOdDataLabels(FINANCIAL_FILE_NAME);
        testOdToHrDataLabels(FINANCIAL_FILE_NAME);
    }

    /** Test for data labels health fitness. */
    @Test
    public void testDataLabelsHealthFitness() throws Exception {
        System.out.println("starting testDataLabelsHealthFitness.");
        testHrToOdDataLabels(HEALTH_FITNESS_FILE_NAME);
        testOdToHrDataLabels(HEALTH_FITNESS_FILE_NAME);
    }

    /** Test for data labels identifiers. */
    @Test
    public void testDataLabelsIdentifiers() throws Exception {
        System.out.println("starting testDataLabelsIdentifiers.");
        testHrToOdDataLabels(IDENTIFIERS_FILE_NAME);
        testOdToHrDataLabels(IDENTIFIERS_FILE_NAME);
    }

    /** Test for data labels location. */
    @Test
    public void testDataLabelsLocation() throws Exception {
        System.out.println("starting testDataLabelsLocation.");
        testHrToOdDataLabels(LOCATION_FILE_NAME);
        testOdToHrDataLabels(LOCATION_FILE_NAME);
    }

    /** Test for data labels personal. */
    @Test
    public void testDataLabelsPersonal() throws Exception {
        System.out.println("starting testDataLabelsPersonal.");
        testHrToOdDataLabels(PERSONAL_FILE_NAME);
        testOdToHrDataLabels(PERSONAL_FILE_NAME);
    }

    /** Test for data labels personal partial. */
    @Test
    public void testDataLabelsPersonalPartial() throws Exception {
        System.out.println("starting testDataLabelsPersonalPartial.");
        testHrToOdDataLabels(PERSONAL_PARTIAL_FILE_NAME);
        testOdToHrDataLabels(PERSONAL_PARTIAL_FILE_NAME);
    }

    /** Test for data labels photo video. */
    @Test
    public void testDataLabelsPhotoVideo() throws Exception {
        System.out.println("starting testDataLabelsPhotoVideo.");
        testHrToOdDataLabels(PHOTO_VIDEO_FILE_NAME);
        testOdToHrDataLabels(PHOTO_VIDEO_FILE_NAME);
    }

    /** Test for data labels search and browsing. */
    @Test
    public void testDataLabelsSearchAndBrowsing() throws Exception {
        System.out.println("starting testDataLabelsSearchAndBrowsing.");
        testHrToOdDataLabels(SEARCH_AND_BROWSING_FILE_NAME);
        testOdToHrDataLabels(SEARCH_AND_BROWSING_FILE_NAME);
    }

    /** Test for data labels storage. */
    @Test
    public void testDataLabelsStorage() throws Exception {
        System.out.println("starting testDataLabelsStorage.");
        testHrToOdDataLabels(STORAGE_FILE_NAME);
        testOdToHrDataLabels(STORAGE_FILE_NAME);
    }

    /** Test for data labels hr unrecognized data category. */
    @Test
    public void testDataLabelsHrUnrecognizedDataCategory() throws Exception {
        System.out.println("starting testDataLabelsHrUnrecognizedDataCategory.");
        hrToOdExpectException(UNRECOGNIZED_FILE_NAME);
    }

    /** Test for data labels hr unrecognized data type. */
    @Test
    public void testDataLabelsHrUnrecognizedDataType() throws Exception {
        System.out.println("starting testDataLabelsHrUnrecognizedDataType.");
        hrToOdExpectException(UNRECOGNIZED_TYPE_FILE_NAME);
    }

    /** Test for data labels hr missing purpose. */
    @Test
    public void testDataLabelsHrMissingPurpose() throws Exception {
        System.out.println("starting testDataLabelsHrMissingPurpose.");
        hrToOdExpectException(PERSONAL_MISSING_PURPOSE_FILE_NAME);
    }

    /** Test for data labels hr empty purpose. */
    @Test
    public void testDataLabelsHrEmptyPurpose() throws Exception {
        System.out.println("starting testDataLabelsHrEmptyPurpose.");
        hrToOdExpectException(PERSONAL_EMPTY_PURPOSE_FILE_NAME);
    }

    /** Test for data labels od unrecognized data category. */
    @Test
    public void testDataLabelsOdUnrecognizedDataCategory() throws Exception {
        System.out.println("starting testDataLabelsOdUnrecognizedDataCategory.");
        odToHrExpectException(UNRECOGNIZED_FILE_NAME);
    }

    /** Test for data labels od unrecognized data type. */
    @Test
    public void testDataLabelsOdUnrecognizedDataType() throws Exception {
        System.out.println("starting testDataLabelsOdUnrecognizedDataCategory.");
        odToHrExpectException(UNRECOGNIZED_TYPE_FILE_NAME);
    }

    /** Test for data labels od missing purpose. */
    @Test
    public void testDataLabelsOdMissingPurpose() throws Exception {
        System.out.println("starting testDataLabelsOdMissingPurpose.");
        odToHrExpectException(PERSONAL_MISSING_PURPOSE_FILE_NAME);
    }

    /** Test for data labels od empty purpose. */
    @Test
    public void testDataLabelsOdEmptyPurpose() throws Exception {
        System.out.println("starting testDataLabelsOdEmptyPurpose.");
        odToHrExpectException(PERSONAL_EMPTY_PURPOSE_FILE_NAME);
    }

    private void hrToOdExpectException(String fileName) {
        TestUtils.hrToOdExpectException(new DataLabelsFactory(), DATA_LABELS_HR_PATH, fileName);
    }

    private void odToHrExpectException(String fileName) {
        TestUtils.odToHrExpectException(new DataLabelsFactory(), DATA_LABELS_OD_PATH, fileName);
    }

    private void testHrToOdDataLabels(String fileName) throws Exception {
        TestUtils.testHrToOd(
                TestUtils.document(),
                new DataLabelsFactory(),
                DATA_LABELS_HR_PATH,
                DATA_LABELS_OD_PATH,
                fileName);
    }

    private void testOdToHrDataLabels(String fileName) throws Exception {
        TestUtils.testOdToHr(
                TestUtils.document(),
                new DataLabelsFactory(),
                DATA_LABELS_OD_PATH,
                DATA_LABELS_HR_PATH,
                fileName);
    }
}
