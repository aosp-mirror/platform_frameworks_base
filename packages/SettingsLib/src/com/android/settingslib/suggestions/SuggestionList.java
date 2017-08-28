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

import android.content.Intent;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.settingslib.drawer.Tile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SuggestionList {
    // Category -> list of suggestion map
    private final Map<SuggestionCategory, List<Tile>> mSuggestions;

    // A flatten list of all suggestions.
    private List<Tile> mSuggestionList;

    public SuggestionList() {
        mSuggestions = new ArrayMap<>();
    }

    public void addSuggestions(SuggestionCategory category, List<Tile> suggestions) {
        mSuggestions.put(category, suggestions);
    }

    public List<Tile> getSuggestions() {
        if (mSuggestionList != null) {
            return mSuggestionList;
        }
        mSuggestionList = new ArrayList<>();
        for (List<Tile> suggestions : mSuggestions.values()) {
            mSuggestionList.addAll(suggestions);
        }
        dedupeSuggestions(mSuggestionList);
        return mSuggestionList;
    }

    public boolean isExclusiveSuggestionCategory() {
        if (mSuggestions.size() != 1) {
            // If there is no category, or more than 1 category, it's not exclusive by definition.
            return false;
        }
        for (SuggestionCategory category : mSuggestions.keySet()) {
            if (category.exclusive) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter suggestions list so they are all unique.
     */
    private void dedupeSuggestions(List<Tile> suggestions) {
        final Set<String> intents = new ArraySet<>();
        for (int i = suggestions.size() - 1; i >= 0; i--) {
            final Tile suggestion = suggestions.get(i);
            final String intentUri = suggestion.intent.toUri(Intent.URI_INTENT_SCHEME);
            if (intents.contains(intentUri)) {
                suggestions.remove(i);
            } else {
                intents.add(intentUri);
            }
        }
    }
}
