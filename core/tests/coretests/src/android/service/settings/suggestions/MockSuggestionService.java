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

package android.service.settings.suggestions;

import android.support.annotation.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

public class MockSuggestionService extends SuggestionService {

    @VisibleForTesting
    static boolean sOnSuggestionLaunchedCalled;
    @VisibleForTesting
    static boolean sOnSuggestionDismissedCalled;

    public static void reset() {
        sOnSuggestionLaunchedCalled = false;
        sOnSuggestionDismissedCalled = false;
    }

    @Override
    public List<Suggestion> onGetSuggestions() {
        final List<Suggestion> data = new ArrayList<>();

        data.add(new Suggestion.Builder("test")
                .setTitle("title")
                .setSummary("summary")
                .build());
        return data;
    }

    @Override
    public void onSuggestionDismissed(Suggestion suggestion) {
        sOnSuggestionDismissedCalled = true;
    }

    @Override
    public void onSuggestionLaunched(Suggestion suggestion) {
        sOnSuggestionLaunchedCalled = true;
    }
}
