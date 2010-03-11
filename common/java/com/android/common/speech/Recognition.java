/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.common.speech;

/**
 * Utilities for voice recognition implementations.
 *
 * @see android.speech.RecognitionService
 * @see android.speech.RecognizerIntent
 */
public class Recognition {
    
    /**
     * The key to the extra in the Bundle returned by
     * android.speech.RecognizerIntent#ACTION_GET_LANGUAGE_DETAILS
     * which is an ArrayList of CharSequences which are hints that can be shown to
     * the user for voice actions currently supported by voice search for the user's current
     * language preference for voice search (i.e., the one defined in the extra
     * android.speech.RecognizerIntent#EXTRA_LANGUAGE_PREFERENCE).
     *
     * If this is paired with EXTRA_HINT_CONTEXT, should return a set of hints that are
     * appropriate for the provided context.
     *
     * The CharSequences are SpannedStrings and will contain segments wrapped in
     * <annotation action="true"></annotation>. This is to indicate the section of the text
     * which represents the voice action, to be highlighted in the UI if so desired.
     */
    public static final String EXTRA_HINT_STRINGS = "android.speech.extra.HINT_STRINGS";
    
    /**
     * The key to an extra to be included in the request intent for
     * android.speech.RecognizerIntent#ACTION_GET_LANGUAGE_DETAILS.
     * Should be an int of one of the values defined below. If an
     * unknown int value is provided, it should be ignored.
     */
    public static final String EXTRA_HINT_CONTEXT = "android.speech.extra.HINT_CONTEXT";
    
    /**
     * A set of values for EXTRA_HINT_CONTEXT.
     */
    public static final int HINT_CONTEXT_UNKNOWN = 0;
    public static final int HINT_CONTEXT_VOICE_SEARCH_HELP = 1;
    public static final int HINT_CONTEXT_CAR_HOME = 2;
    public static final int HINT_CONTEXT_LAUNCHER = 3;

    private Recognition() { }   // don't instantiate
}
