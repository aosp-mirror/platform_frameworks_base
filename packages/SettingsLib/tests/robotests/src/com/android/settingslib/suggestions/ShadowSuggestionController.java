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

package com.android.settingslib.suggestions;

import android.service.settings.suggestions.Suggestion;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.List;

@Implements(SuggestionController.class)
public class ShadowSuggestionController {

    public static boolean sStartCalled;
    public static boolean sStopCalled;
    public static boolean sGetSuggestionCalled;

    public static List<Suggestion> sSuggestions;

    public static void reset() {
        sStartCalled = false;
        sStopCalled = false;
        sGetSuggestionCalled = false;
        sSuggestions = null;
    }

    @Implementation
    public void start() {
        sStartCalled = true;
    }

    @Implementation
    public void stop() {
        sStopCalled = true;
    }

    public static void setSuggestion(List<Suggestion> suggestions) {
        sSuggestions = suggestions;
    }

    @Implementation
    public List<Suggestion> getSuggestions() {
        sGetSuggestionCalled = true;
        return sSuggestions;
    }
}
