/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.asllib;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Constants for determining valid {@link String} data types for usage within {@link SafetyLabels},
 * {@link DataCategory}, and {@link DataType}
 */
public class DataTypeConstants {
    /** Data types for {@link DataCategoryConstants.CATEGORY_PERSONAL} */
    public static final String TYPE_NAME = "name";

    public static final String TYPE_EMAIL_ADDRESS = "email_address";
    public static final String TYPE_PHONE_NUMBER = "phone_number";
    public static final String TYPE_RACE_ETHNICITY = "race_ethnicity";
    public static final String TYPE_POLITICAL_OR_RELIGIOUS_BELIEFS =
            "political_or_religious_beliefs";
    public static final String TYPE_SEXUAL_ORIENTATION_OR_GENDER_IDENTITY =
            "sexual_orientation_or_gender_identity";
    public static final String TYPE_PERSONAL_IDENTIFIERS = "personal_identifiers";
    public static final String TYPE_OTHER = "other";

    /** Data types for {@link DataCategoryConstants.CATEGORY_FINANCIAL} */
    public static final String TYPE_CARD_BANK_ACCOUNT = "card_bank_account";

    public static final String TYPE_PURCHASE_HISTORY = "purchase_history";
    public static final String TYPE_CREDIT_SCORE = "credit_score";
    public static final String TYPE_FINANCIAL_OTHER = "other";

    /** Data types for {@link DataCategoryConstants.CATEGORY_LOCATION} */
    public static final String TYPE_APPROX_LOCATION = "approx_location";

    public static final String TYPE_PRECISE_LOCATION = "precise_location";

    /** Data types for {@link DataCategoryConstants.CATEGORY_EMAIL_TEXT_MESSAGE} */
    public static final String TYPE_EMAILS = "emails";

    public static final String TYPE_TEXT_MESSAGES = "text_messages";
    public static final String TYPE_EMAIL_TEXT_MESSAGE_OTHER = "other";

    /** Data types for {@link DataCategoryConstants.CATEGORY_PHOTO_VIDEO} */
    public static final String TYPE_PHOTOS = "photos";

    public static final String TYPE_VIDEOS = "videos";

    /** Data types for {@link DataCategoryConstants.CATEGORY_AUDIO} */
    public static final String TYPE_SOUND_RECORDINGS = "sound_recordings";

    public static final String TYPE_MUSIC_FILES = "music_files";
    public static final String TYPE_AUDIO_OTHER = "other";

    /** Data types for {@link DataCategoryConstants.CATEGORY_STORAGE} */
    public static final String TYPE_FILES_DOCS = "files_docs";

    /** Data types for {@link DataCategoryConstants.CATEGORY_HEALTH_FITNESS} */
    public static final String TYPE_HEALTH = "health";

    public static final String TYPE_FITNESS = "fitness";

    /** Data types for {@link DataCategoryConstants.CATEGORY_CONTACTS} */
    public static final String TYPE_CONTACTS = "contacts";

    /** Data types for {@link DataCategoryConstants.CATEGORY_CALENDAR} */
    public static final String TYPE_CALENDAR = "calendar";

    /** Data types for {@link DataCategoryConstants.CATEGORY_IDENTIFIERS} */
    public static final String TYPE_IDENTIFIERS_OTHER = "other";

    /** Data types for {@link DataCategoryConstants.CATEGORY_APP_PERFORMANCE} */
    public static final String TYPE_CRASH_LOGS = "crash_logs";

    public static final String TYPE_PERFORMANCE_DIAGNOSTICS = "performance_diagnostics";
    public static final String TYPE_APP_PERFORMANCE_OTHER = "other";

    /** Data types for {@link DataCategoryConstants.CATEGORY_ACTIONS_IN_APP} */
    public static final String TYPE_USER_INTERACTION = "user_interaction";

    public static final String TYPE_IN_APP_SEARCH_HISTORY = "in_app_search_history";
    public static final String TYPE_INSTALLED_APPS = "installed_apps";
    public static final String TYPE_USER_GENERATED_CONTENT = "user_generated_content";
    public static final String TYPE_ACTIONS_IN_APP_OTHER = "other";

    /** Data types for {@link DataCategoryConstants.CATEGORY_SEARCH_AND_BROWSING} */
    public static final String TYPE_WEB_BROWSING_HISTORY = "web_browsing_history";

    /** Set of valid categories */
    public static final Set<String> VALID_TYPES =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    TYPE_NAME,
                                    TYPE_EMAIL_ADDRESS,
                                    TYPE_PHONE_NUMBER,
                                    TYPE_RACE_ETHNICITY,
                                    TYPE_POLITICAL_OR_RELIGIOUS_BELIEFS,
                                    TYPE_SEXUAL_ORIENTATION_OR_GENDER_IDENTITY,
                                    TYPE_PERSONAL_IDENTIFIERS,
                                    TYPE_OTHER,
                                    TYPE_CARD_BANK_ACCOUNT,
                                    TYPE_PURCHASE_HISTORY,
                                    TYPE_CREDIT_SCORE,
                                    TYPE_FINANCIAL_OTHER,
                                    TYPE_APPROX_LOCATION,
                                    TYPE_PRECISE_LOCATION,
                                    TYPE_EMAILS,
                                    TYPE_TEXT_MESSAGES,
                                    TYPE_EMAIL_TEXT_MESSAGE_OTHER,
                                    TYPE_PHOTOS,
                                    TYPE_VIDEOS,
                                    TYPE_SOUND_RECORDINGS,
                                    TYPE_MUSIC_FILES,
                                    TYPE_AUDIO_OTHER,
                                    TYPE_FILES_DOCS,
                                    TYPE_HEALTH,
                                    TYPE_FITNESS,
                                    TYPE_CONTACTS,
                                    TYPE_CALENDAR,
                                    TYPE_IDENTIFIERS_OTHER,
                                    TYPE_CRASH_LOGS,
                                    TYPE_PERFORMANCE_DIAGNOSTICS,
                                    TYPE_APP_PERFORMANCE_OTHER,
                                    TYPE_USER_INTERACTION,
                                    TYPE_IN_APP_SEARCH_HISTORY,
                                    TYPE_INSTALLED_APPS,
                                    TYPE_USER_GENERATED_CONTENT,
                                    TYPE_ACTIONS_IN_APP_OTHER,
                                    TYPE_WEB_BROWSING_HISTORY)));

    /** Returns {@link Set} of valid {@link String} category keys */
    public static Set<String> getValidDataTypes() {
        return VALID_TYPES;
    }

    private DataTypeConstants() {
        /* do nothing - hide constructor */
    }
}
