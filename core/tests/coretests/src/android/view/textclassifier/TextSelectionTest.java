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

package android.view.textclassifier;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextSelectionTest {
    private static final String BUNDLE_KEY = "key";
    private static final String BUNDLE_VALUE = "value";
    private static final Bundle BUNDLE = new Bundle();
    static {
        BUNDLE.putString(BUNDLE_KEY, BUNDLE_VALUE);
    }

    @Test
    public void testParcel() {
        final int startIndex = 13;
        final int endIndex = 37;
        final String id = "id";
        final TextSelection reference = new TextSelection.Builder(startIndex, endIndex)
                .setEntityType(TextClassifier.TYPE_ADDRESS, 0.3f)
                .setEntityType(TextClassifier.TYPE_PHONE, 0.7f)
                .setEntityType(TextClassifier.TYPE_URL, 0.1f)
                .setId(id)
                .setExtras(BUNDLE)
                .build();

        // Parcel and unparcel
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextSelection result = TextSelection.CREATOR.createFromParcel(parcel);

        assertEquals(startIndex, result.getSelectionStartIndex());
        assertEquals(endIndex, result.getSelectionEndIndex());
        assertEquals(id, result.getId());

        assertEquals(3, result.getEntityCount());
        assertEquals(TextClassifier.TYPE_PHONE, result.getEntity(0));
        assertEquals(TextClassifier.TYPE_ADDRESS, result.getEntity(1));
        assertEquals(TextClassifier.TYPE_URL, result.getEntity(2));
        assertEquals(0.7f, result.getConfidenceScore(TextClassifier.TYPE_PHONE), 1e-7f);
        assertEquals(0.3f, result.getConfidenceScore(TextClassifier.TYPE_ADDRESS), 1e-7f);
        assertEquals(0.1f, result.getConfidenceScore(TextClassifier.TYPE_URL), 1e-7f);
        assertEquals(BUNDLE_VALUE, result.getExtras().getString(BUNDLE_KEY));
    }

    @Test
    public void testParcelRequest() {
        final String text = "text";
        final String packageName = "packageName";
        final TextSelection.Request reference =
                new TextSelection.Request.Builder(text, 0, text.length())
                        .setDefaultLocales(new LocaleList(Locale.US, Locale.GERMANY))
                        .setExtras(BUNDLE)
                        .build();
        reference.setCallingPackageName(packageName);

        // Parcel and unparcel.
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextSelection.Request result = TextSelection.Request.CREATOR.createFromParcel(parcel);

        assertEquals(text, result.getText().toString());
        assertEquals(0, result.getStartIndex());
        assertEquals(text.length(), result.getEndIndex());
        assertEquals("en-US,de-DE", result.getDefaultLocales().toLanguageTags());
        assertEquals(BUNDLE_VALUE, result.getExtras().getString(BUNDLE_KEY));
        assertEquals(packageName, result.getCallingPackageName());
    }
}
