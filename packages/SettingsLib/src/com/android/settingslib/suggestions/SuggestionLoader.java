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

import android.content.Context;
import android.service.settings.suggestions.Suggestion;
import android.util.Log;

import com.android.settingslib.utils.AsyncLoader;

import java.util.List;

public class SuggestionLoader extends AsyncLoader<List<Suggestion>> {

    public static final int LOADER_ID_SUGGESTIONS = 42;
    private static final String TAG = "SuggestionLoader";

    private final SuggestionController mSuggestionController;

    public SuggestionLoader(Context context, SuggestionController controller) {
        super(context);
        mSuggestionController = controller;
    }

    @Override
    protected void onDiscardResult(List<Suggestion> result) {

    }

    @Override
    public List<Suggestion> loadInBackground() {
        final List<Suggestion> data = mSuggestionController.getSuggestions();
        if (data == null) {
            Log.d(TAG, "data is null");
        } else {
            Log.d(TAG, "data size " + data.size());
        }
        return data;
    }
}