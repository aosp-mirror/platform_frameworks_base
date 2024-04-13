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
public class DataCategoryTest {
    private static final String DATA_CATEGORY_HR_PATH = "com/android/asllib/datacategory/hr";
    private static final String DATA_CATEGORY_OD_PATH = "com/android/asllib/datacategory/od";

    private static final String VALID_PERSONAL_FILE_NAME = "data-category-personal.xml";
    private static final String VALID_PARTIAL_PERSONAL_FILE_NAME =
            "data-category-personal-partial.xml";
    private static final String VALID_FINANCIAL_FILE_NAME = "data-category-financial.xml";
    private static final String VALID_LOCATION_FILE_NAME = "data-category-location.xml";
    private static final String VALID_EMAIL_TEXT_MESSAGE_FILE_NAME =
            "data-category-email-text-message.xml";
    private static final String VALID_PHOTO_VIDEO_FILE_NAME = "data-category-photo-video.xml";
    private static final String VALID_AUDIO_FILE_NAME = "data-category-audio.xml";
    private static final String VALID_STORAGE_FILE_NAME = "data-category-storage.xml";
    private static final String VALID_HEALTH_FITNESS_FILE_NAME = "data-category-health-fitness.xml";
    private static final String VALID_CONTACTS_FILE_NAME = "data-category-contacts.xml";
    private static final String VALID_CALENDAR_FILE_NAME = "data-category-calendar.xml";
    private static final String VALID_IDENTIFIERS_FILE_NAME = "data-category-identifiers.xml";
    private static final String VALID_APP_PERFORMANCE_FILE_NAME =
            "data-category-app-performance.xml";
    private static final String VALID_ACTIONS_IN_APP_FILE_NAME = "data-category-actions-in-app.xml";
    private static final String VALID_SEARCH_AND_BROWSING_FILE_NAME =
            "data-category-search-and-browsing.xml";

    private static final String EMPTY_PURPOSE_PERSONAL_FILE_NAME =
            "data-category-personal-empty-purpose.xml";
    private static final String MISSING_PURPOSE_PERSONAL_FILE_NAME =
            "data-category-personal-missing-purpose.xml";
    private static final String UNRECOGNIZED_TYPE_PERSONAL_FILE_NAME =
            "data-category-personal-unrecognized-type.xml";
    private static final String UNRECOGNIZED_CATEGORY_FILE_NAME = "data-category-unrecognized.xml";

    /** Logic for setting up tests (empty if not yet needed). */
    public static void main(String[] params) throws Exception {}

    @Before
    public void setUp() throws Exception {
        System.out.println("set up.");
    }

    /** Test for data category personal. */
    @Test
    public void testDataCategoryPersonal() throws Exception {
        System.out.println("starting testDataCategoryPersonal.");
        testHrToOdDataCategory(VALID_PERSONAL_FILE_NAME);
    }

    /** Test for data category financial. */
    @Test
    public void testDataCategoryFinancial() throws Exception {
        System.out.println("starting testDataCategoryFinancial.");
        testHrToOdDataCategory(VALID_FINANCIAL_FILE_NAME);
    }

    /** Test for data category location. */
    @Test
    public void testDataCategoryLocation() throws Exception {
        System.out.println("starting testDataCategoryLocation.");
        testHrToOdDataCategory(VALID_LOCATION_FILE_NAME);
    }

    /** Test for data category email text message. */
    @Test
    public void testDataCategoryEmailTextMessage() throws Exception {
        System.out.println("starting testDataCategoryEmailTextMessage.");
        testHrToOdDataCategory(VALID_EMAIL_TEXT_MESSAGE_FILE_NAME);
    }

    /** Test for data category photo video. */
    @Test
    public void testDataCategoryPhotoVideo() throws Exception {
        System.out.println("starting testDataCategoryPhotoVideo.");
        testHrToOdDataCategory(VALID_PHOTO_VIDEO_FILE_NAME);
    }

    /** Test for data category audio. */
    @Test
    public void testDataCategoryAudio() throws Exception {
        System.out.println("starting testDataCategoryAudio.");
        testHrToOdDataCategory(VALID_AUDIO_FILE_NAME);
    }

    /** Test for data category storage. */
    @Test
    public void testDataCategoryStorage() throws Exception {
        System.out.println("starting testDataCategoryStorage.");
        testHrToOdDataCategory(VALID_STORAGE_FILE_NAME);
    }

    /** Test for data category health fitness. */
    @Test
    public void testDataCategoryHealthFitness() throws Exception {
        System.out.println("starting testDataCategoryHealthFitness.");
        testHrToOdDataCategory(VALID_HEALTH_FITNESS_FILE_NAME);
    }

    /** Test for data category contacts. */
    @Test
    public void testDataCategoryContacts() throws Exception {
        System.out.println("starting testDataCategoryContacts.");
        testHrToOdDataCategory(VALID_CONTACTS_FILE_NAME);
    }

    /** Test for data category calendar. */
    @Test
    public void testDataCategoryCalendar() throws Exception {
        System.out.println("starting testDataCategoryCalendar.");
        testHrToOdDataCategory(VALID_CALENDAR_FILE_NAME);
    }

    /** Test for data category identifiers. */
    @Test
    public void testDataCategoryIdentifiers() throws Exception {
        System.out.println("starting testDataCategoryIdentifiers.");
        testHrToOdDataCategory(VALID_IDENTIFIERS_FILE_NAME);
    }

    /** Test for data category app performance. */
    @Test
    public void testDataCategoryAppPerformance() throws Exception {
        System.out.println("starting testDataCategoryAppPerformance.");
        testHrToOdDataCategory(VALID_APP_PERFORMANCE_FILE_NAME);
    }

    /** Test for data category actions in app. */
    @Test
    public void testDataCategoryActionsInApp() throws Exception {
        System.out.println("starting testDataCategoryActionsInApp.");
        testHrToOdDataCategory(VALID_ACTIONS_IN_APP_FILE_NAME);
    }

    /** Test for data category search and browsing. */
    @Test
    public void testDataCategorySearchAndBrowsing() throws Exception {
        System.out.println("starting testDataCategorySearchAndBrowsing.");
        testHrToOdDataCategory(VALID_SEARCH_AND_BROWSING_FILE_NAME);
    }

    /** Test for data category search and browsing. */
    @Test
    public void testMissingOptionalsAllowed() throws Exception {
        System.out.println("starting testMissingOptionalsAllowed.");
        testHrToOdDataCategory(VALID_PARTIAL_PERSONAL_FILE_NAME);
    }

    /** Test for empty purposes. */
    @Test
    public void testEmptyPurposesNotAllowed() throws Exception {
        System.out.println("starting testEmptyPurposesNotAllowed.");
        hrToOdExpectException(EMPTY_PURPOSE_PERSONAL_FILE_NAME);
    }

    /** Test for missing purposes. */
    @Test
    public void testMissingPurposesNotAllowed() throws Exception {
        System.out.println("starting testMissingPurposesNotAllowed.");
        hrToOdExpectException(MISSING_PURPOSE_PERSONAL_FILE_NAME);
    }

    /** Test for unrecognized type. */
    @Test
    public void testUnrecognizedTypeNotAllowed() throws Exception {
        System.out.println("starting testUnrecognizedTypeNotAllowed.");
        hrToOdExpectException(UNRECOGNIZED_TYPE_PERSONAL_FILE_NAME);
    }

    /** Test for unrecognized category. */
    @Test
    public void testUnrecognizedCategoryNotAllowed() throws Exception {
        System.out.println("starting testUnrecognizedCategoryNotAllowed.");
        hrToOdExpectException(UNRECOGNIZED_CATEGORY_FILE_NAME);
    }

    private void hrToOdExpectException(String fileName) {
        TestUtils.hrToOdExpectException(new DataCategoryFactory(), DATA_CATEGORY_HR_PATH, fileName);
    }

    private void testHrToOdDataCategory(String fileName) throws Exception {
        TestUtils.testHrToOd(
                TestUtils.document(),
                new DataCategoryFactory(),
                DATA_CATEGORY_HR_PATH,
                DATA_CATEGORY_OD_PATH,
                fileName);
    }
}
