/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.speech;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

/**
 * Constants for supporting speech recognition through starting an {@link Intent}
 * 
 * @hide {pending API council review}
 */
public class RecognizerIntent {
    private RecognizerIntent() {
        // Not for instantiating.
    }

    /**
     * Starts an activity that will prompt the user for speech and sends it through a
     * speech recognizer.
     * 
     * <p>Required extras:
     * <ul>
     *   <li>{@link #EXTRA_LANGUAGE_MODEL}
     * </ul>
     * 
     * <p>Optional extras:
     * <ul>
     *   <li>{@link Intent#EXTRA_PROMPT}
     *   <li>{@link #EXTRA_LANGUAGE}
     *   <li>{@link #EXTRA_MAX_RESULTS}
     * </ul>
     * 
     * <p> Result extras:
     * <ul>
     *   <li>{@link #EXTRA_RESULTS}
     * </ul>
     * 
     * <p>NOTE: There may not be any applications installed to handle this action, so you should
     * make sure to catch {@link ActivityNotFoundException}.
     */
    public static final String ACTION_RECOGNIZE_SPEECH = "android.speech.action.RECOGNIZE_SPEECH";

    /**
     * Informs the recognizer which speech model to prefer when performing
     * {@link #ACTION_RECOGNIZE_SPEECH}. The recognizer uses this
     * information to fine tune the results. This extra is required. Activities implementing
     * {@link #ACTION_RECOGNIZE_SPEECH} may interpret the values as they see fit.
     * 
     *  @see #LANGUAGE_MODEL_FREE_FORM
     *  @see #LANGUAGE_MODEL_WEB_SEARCH
     */
    public static final String EXTRA_LANGUAGE_MODEL = "language_model";

    /** Free form speech recognition */
    public static final String LANGUAGE_MODEL_FREE_FORM = "free_form";
    /** Use a language model based on web search terms */
    public static final String LANGUAGE_MODEL_WEB_SEARCH = "web_search";

    /** Optional text prompt to show to the user when asking them to speak. */
    public static final String EXTRA_PROMPT = "prompt";

    /**
     * Optional language override to inform the recognizer that it should expect speech in
     * a language different than the one set in the {@link java.util.Locale#getDefault()}. 
     */
    public static final String EXTRA_LANGUAGE = "lang";

    /** 
     * Optional limit on the maximum number of results to return. If omitted the recognizer
     * will choose how many results to return. Must be an integer.
     */
    public static final String EXTRA_MAX_RESULTS = "max_results";

    /** Result code returned when no matches are found for the given speech */
    public static final int RESULT_NO_MATCH = Activity.RESULT_FIRST_USER;
    /** Result code returned when there is a generic client error */
    public static final int RESULT_CLIENT_ERROR = Activity.RESULT_FIRST_USER + 1;
    /** Result code returned when the recognition server returns an error */
    public static final int RESULT_SERVER_ERROR = Activity.RESULT_FIRST_USER + 2;
    /** Result code returned when a network error was encountered */
    public static final int RESULT_NETWORK_ERROR = Activity.RESULT_FIRST_USER + 3;
    /** Result code returned when an audio error was encountered */
    public static final int RESULT_AUDIO_ERROR = Activity.RESULT_FIRST_USER + 4;

    /**
     * An ArrayList<String> of the potential results when performing
     * {@link #ACTION_RECOGNIZE_SPEECH}. Only present when {@link Activity#RESULT_OK} is returned.
     */
    public static final String EXTRA_RESULTS = "results";
}
