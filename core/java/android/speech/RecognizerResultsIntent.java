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

package android.speech;

import android.os.Bundle;

import java.util.ArrayList;

/**
 * Constants for intents related to showing speech recognition results.
 * 
 * These constants should not be needed for normal utilization of speech recognition. They
 * would only be called if you wanted to trigger a view of voice search results in your
 * application, or implemented if you wanted to offer a different view for voice search results
 * with your application.
 * 
 * The standard behavior here for someone receiving an {@link #ACTION_VOICE_SEARCH_RESULTS} is to
 * first retrieve the list of {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS}, and use any provided
 * HTML for that result in {@link #EXTRA_VOICE_SEARCH_RESULT_HTML}, if available, to display
 * the search results. If that is not available, then the corresponding url for that result in
 * {@link #EXTRA_VOICE_SEARCH_RESULT_URLS} should be used. And if even that is not available,
 * then a search url should be constructed from the actual recognition result string.
 * 
 * @hide for making public in a later release
 */
public class RecognizerResultsIntent {
    private RecognizerResultsIntent() {
        // Not for instantiating.
    }

    /**
     * Intent that can be sent by implementations of voice search to display the results of
     * a search in, for example, a web browser.
     * 
     * This intent should always be accompanied by at least
     * {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS}, and optionally but recommended,
     * {@link #EXTRA_VOICE_SEARCH_RESULT_URLS}, and sometimes
     * {@link #EXTRA_VOICE_SEARCH_RESULT_HTML} and
     * {@link #EXTRA_VOICE_SEARCH_RESULT_HTML_BASE_URLS}.
     * 
     * These are parallel arrays, where a recognition result string at index N of
     * {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS} should be accompanied by a url to use for
     * searching based on that string at index N of {@link #EXTRA_VOICE_SEARCH_RESULT_URLS},
     * and, possibly, the full html to display for that result at index N of
     * {@link #EXTRA_VOICE_SEARCH_RESULT_HTML}. If full html is provided, a base url (or
     * list of base urls) should be provided with {@link #EXTRA_VOICE_SEARCH_RESULT_HTML_BASE_URLS}.
     * 
     * @hide for making public in a later release
     */
    public static final String ACTION_VOICE_SEARCH_RESULTS =
            "android.speech.action.VOICE_SEARCH_RESULTS";
    
    /**
     * The key to an extra {@link ArrayList} of {@link String}s that contains the list of
     * recognition alternates from voice search, in order from highest to lowest confidence.
     * 
     * @hide for making public in a later release
     */
    public static final String EXTRA_VOICE_SEARCH_RESULT_STRINGS =
            "android.speech.extras.VOICE_SEARCH_RESULT_STRINGS";
    
    /**
     * The key to an extra {@link ArrayList} of {@link String}s that contains the search urls
     * to use, if available, for the recognition alternates provided in
     * {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS}. This list should always be the same size as the
     * one provided in {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS} - if a result cannot provide a
     * search url, that entry in this ArrayList should be <code>null</code>, and the implementor of
     * {@link #ACTION_VOICE_SEARCH_RESULTS} should execute a search of its own choosing,
     * based on the recognition result string.
     * 
     * @hide for making public in a later release
     */
    public static final String EXTRA_VOICE_SEARCH_RESULT_URLS =
            "android.speech.extras.VOICE_SEARCH_RESULT_URLS";
    
    /**
     * The key to an extra {@link ArrayList} of {@link String}s that contains the html content to
     * use, if available, for the recognition alternates provided in
     * {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS}. This list should always be the same size as the
     * one provided in {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS} - if a result cannot provide
     * html, that entry in this list should be <code>null</code>, and the implementor of
     * {@link #ACTION_VOICE_SEARCH_RESULTS} should back off to the corresponding url provided in
     * {@link #EXTRA_VOICE_SEARCH_RESULT_URLS}, if available, or else should execute a search of
     * its own choosing, based on the recognition result string.
     * 
     * Currently this html content should be expected in the form of a uri with scheme
     * {@link #URI_SCHEME_INLINE} for the Browser. In the future this may change to a "content://"
     * uri or some other identifier. Anyone who reads this extra should confirm that a result is
     * in fact an "inline:" uri and back off to the urls or strings gracefully if it is not, thus
     * maintaining future backwards compatibility if this changes.
     * 
     * @hide not to be exposed immediately as the implementation details may change
     */
    public static final String EXTRA_VOICE_SEARCH_RESULT_HTML =
            "android.speech.extras.VOICE_SEARCH_RESULT_HTML";
    
    /**
     * The key to an extra {@link ArrayList} of {@link String}s that contains the base url to
     * assume when interpreting html provided in {@link #EXTRA_VOICE_SEARCH_RESULT_HTML}.
     * 
     * A list of size 1 may be provided to apply the same base url to all html results.
     * A list of the same size as {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS} may be provided
     * to apply different base urls to each different html result in the
     * {@link #EXTRA_VOICE_SEARCH_RESULT_HTML} list.
     * 
     * @hide not to be exposed immediately as the implementation details may change
     */
    public static final String EXTRA_VOICE_SEARCH_RESULT_HTML_BASE_URLS =
            "android.speech.extras.VOICE_SEARCH_RESULT_HTML_BASE_URLS";

    /**
     * The key to an extra {@link ArrayList} of {@link Bundle}s that contains key/value pairs.
     * All the values and the keys are {@link String}s. Each key/value pair represents an extra HTTP
     * header. The keys can't be the standard HTTP headers as they are set by the WebView.
     *
     * A list of size 1 may be provided to apply the same HTTP headers to all web results. A
     * list of the same size as {@link #EXTRA_VOICE_SEARCH_RESULT_STRINGS} may be provided to
     * apply different HTTP headers to each different web result in the list. These headers will
     * only be used in the case that the url for a particular web result (from
     * {@link #EXTRA_VOICE_SEARCH_RESULT_URLS}) is loaded.
     *
     * @hide not to be exposed immediately as the implementation details may change
     */
    public static final String EXTRA_VOICE_SEARCH_RESULT_HTTP_HEADERS =
            "android.speech.extras.EXTRA_VOICE_SEARCH_RESULT_HTTP_HEADERS";

    /**
     * The scheme used currently for html content in {@link #EXTRA_VOICE_SEARCH_RESULT_HTML}.
     * 
     * @hide not to be exposed immediately as the implementation details may change
     */
    public static final String URI_SCHEME_INLINE = "inline";
}
