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

import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import java.util.List;

/**
 * This is the base class for implementing suggestion service. A suggestion service is responsible
 * to provide a collection of {@link Suggestion}s for the current user when queried.
 *
 * @hide
 */
@SystemApi
public abstract class SuggestionService extends Service {

    private static final String TAG = "SuggestionService";
    private static final boolean DEBUG = false;

    @Override
    public IBinder onBind(Intent intent) {
        return new ISuggestionService.Stub() {
            @Override
            public List<Suggestion> getSuggestions() {
                if (DEBUG) {
                    Log.d(TAG, "getSuggestions() " + getPackageName());
                }
                return onGetSuggestions();
            }

            @Override
            public void dismissSuggestion(Suggestion suggestion) {
                if (DEBUG) {
                    Log.d(TAG, "dismissSuggestion() " + getPackageName());
                }
                onSuggestionDismissed(suggestion);
            }

            @Override
            public void launchSuggestion(Suggestion suggestion) {
                if (DEBUG) {
                    Log.d(TAG, "launchSuggestion() " + getPackageName());
                }
                onSuggestionLaunched(suggestion);
            }
        };
    }

    /**
     * Return all available suggestions.
     */
    public abstract List<Suggestion> onGetSuggestions();

    /**
     * Dismiss a suggestion. The suggestion will not be included in future
     * {@link #onGetSuggestions()} calls.
     */
    public abstract void onSuggestionDismissed(Suggestion suggestion);

    /**
     * This is the opposite signal to {@link #onSuggestionDismissed}, indicating a suggestion has
     * been launched.
     */
    public abstract void onSuggestionLaunched(Suggestion suggestion);
}
