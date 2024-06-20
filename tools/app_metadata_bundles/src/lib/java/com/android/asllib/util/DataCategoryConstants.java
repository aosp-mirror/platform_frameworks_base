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

package com.android.asllib.util;

import com.android.asllib.marshallable.DataCategory;
import com.android.asllib.marshallable.DataType;
import com.android.asllib.marshallable.SafetyLabels;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Constants for determining valid {@link String} data types for usage within {@link SafetyLabels},
 * {@link DataCategory}, and {@link DataType}
 */
public class DataCategoryConstants {

    public static final String CATEGORY_PERSONAL = "personal";
    public static final String CATEGORY_FINANCIAL = "financial";
    public static final String CATEGORY_LOCATION = "location";
    public static final String CATEGORY_EMAIL_TEXT_MESSAGE = "email_text_message";
    public static final String CATEGORY_PHOTO_VIDEO = "photo_video";
    public static final String CATEGORY_AUDIO = "audio";
    public static final String CATEGORY_STORAGE = "storage";
    public static final String CATEGORY_HEALTH_FITNESS = "health_fitness";
    public static final String CATEGORY_CONTACTS = "contacts";
    public static final String CATEGORY_CALENDAR = "calendar";
    public static final String CATEGORY_IDENTIFIERS = "identifiers";
    public static final String CATEGORY_APP_PERFORMANCE = "app_performance";
    public static final String CATEGORY_ACTIONS_IN_APP = "actions_in_app";
    public static final String CATEGORY_SEARCH_AND_BROWSING = "search_and_browsing";

    /** Set of valid categories */
    public static final Set<String> VALID_CATEGORIES =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    CATEGORY_PERSONAL,
                                    CATEGORY_FINANCIAL,
                                    CATEGORY_LOCATION,
                                    CATEGORY_EMAIL_TEXT_MESSAGE,
                                    CATEGORY_PHOTO_VIDEO,
                                    CATEGORY_AUDIO,
                                    CATEGORY_STORAGE,
                                    CATEGORY_HEALTH_FITNESS,
                                    CATEGORY_CONTACTS,
                                    CATEGORY_CALENDAR,
                                    CATEGORY_IDENTIFIERS,
                                    CATEGORY_APP_PERFORMANCE,
                                    CATEGORY_ACTIONS_IN_APP,
                                    CATEGORY_SEARCH_AND_BROWSING)));

    /** Returns {@link Set} of valid {@link String} category keys */
    public static Set<String> getValidDataCategories() {
        return VALID_CATEGORIES;
    }

    private DataCategoryConstants() {
        /* do nothing - hide constructor */
    }
}
