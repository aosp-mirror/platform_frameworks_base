/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import android.util.Log;

import java.net.MalformedURLException;
import java.net.URL;

// TODO: Move these to a better place.
/* package */ abstract class WebTextView {

    private static final String LOGTAG = "WebTextView";

    // Types used with setType.  Keep in sync with CachedInput.h
    static final int NORMAL_TEXT_FIELD = 0;
    static final int TEXT_AREA = 1;
    static final int PASSWORD = 2;
    static final int SEARCH = 3;
    static final int EMAIL = 4;
    static final int NUMBER = 5;
    static final int TELEPHONE = 6;
    static final int URL = 7;

    static final int FORM_NOT_AUTOFILLABLE = -1;

    static String urlForAutoCompleteData(String urlString) {
        // Remove any fragment or query string.
        URL url = null;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            Log.e(LOGTAG, "Unable to parse URL "+url);
        }

        return url != null ? url.getProtocol() + "://" + url.getHost() + url.getPath() : null;
    }

}
