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

import static com.google.common.truth.Truth.assertThat;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SuggestionTest {
    private static final String TEST_ID = "id";
    private static final String TEST_TITLE = "title";
    private static final String TEST_SUMMARY = "summary";
    private PendingIntent mTestIntent;


    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getContext();
        mTestIntent = PendingIntent.getActivity(context, 0 /* requestCode */,
                new Intent(), 0 /* flags */);
    }

    @Test
    public void buildSuggestion_allFieldsShouldBeSet() {
        final Suggestion suggestion = new Suggestion.Builder(TEST_ID)
                .setTitle(TEST_TITLE)
                .setSummary(TEST_SUMMARY)
                .setPendingIntent(mTestIntent)
                .build();

        assertThat(suggestion.getId()).isEqualTo(TEST_ID);
        assertThat(suggestion.getTitle()).isEqualTo(TEST_TITLE);
        assertThat(suggestion.getSummary()).isEqualTo(TEST_SUMMARY);
        assertThat(suggestion.getPendingIntent()).isEqualTo(mTestIntent);
    }

    @Test(expected = IllegalArgumentException.class)
    public void buildSuggestion_emptyKey_shouldCrash() {
        new Suggestion.Builder(null)
                .setTitle(TEST_TITLE)
                .setSummary(TEST_SUMMARY)
                .setPendingIntent(mTestIntent)
                .build();
    }

    @Test
    public void buildSuggestion_fromParcelable() {
        final Parcel parcel = Parcel.obtain();
        final Suggestion oldSuggestion = new Suggestion.Builder(TEST_ID)
                .setTitle(TEST_TITLE)
                .setSummary(TEST_SUMMARY)
                .setPendingIntent(mTestIntent)
                .build();

        oldSuggestion.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);
        final Suggestion newSuggestion = Suggestion.CREATOR.createFromParcel(parcel);

        assertThat(newSuggestion.getId()).isEqualTo(TEST_ID);
        assertThat(newSuggestion.getTitle()).isEqualTo(TEST_TITLE);
        assertThat(newSuggestion.getSummary()).isEqualTo(TEST_SUMMARY);
        assertThat(newSuggestion.getPendingIntent()).isEqualTo(mTestIntent);
    }
}
