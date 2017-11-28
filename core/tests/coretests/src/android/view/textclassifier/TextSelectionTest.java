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
import static org.junit.Assert.assertTrue;

import android.os.LocaleList;
import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextSelectionTest {

    @Test
    public void testParcel() {
        final int startIndex = 13;
        final int endIndex = 37;
        final String signature = "signature";
        final TextSelection reference = new TextSelection.Builder(startIndex, endIndex)
                .setEntityType(TextClassifier.TYPE_ADDRESS, 0.3f)
                .setEntityType(TextClassifier.TYPE_PHONE, 0.7f)
                .setEntityType(TextClassifier.TYPE_URL, 0.1f)
                .setSignature(signature)
                .build();

        // Parcel and unparcel using ParcelableWrapper.
        final TextSelection.ParcelableWrapper parcelableReference = new TextSelection
                .ParcelableWrapper(reference);
        final Parcel parcel = Parcel.obtain();
        parcelableReference.writeToParcel(parcel, parcelableReference.describeContents());
        parcel.setDataPosition(0);
        final TextSelection result =
                TextSelection.ParcelableWrapper.CREATOR.createFromParcel(
                        parcel).getTextSelection();

        assertEquals(startIndex, result.getSelectionStartIndex());
        assertEquals(endIndex, result.getSelectionEndIndex());
        assertEquals(signature, result.getSignature());

        assertEquals(3, result.getEntityCount());
        assertEquals(TextClassifier.TYPE_PHONE, result.getEntity(0));
        assertEquals(TextClassifier.TYPE_ADDRESS, result.getEntity(1));
        assertEquals(TextClassifier.TYPE_URL, result.getEntity(2));
        assertEquals(0.7f, result.getConfidenceScore(TextClassifier.TYPE_PHONE), 1e-7f);
        assertEquals(0.3f, result.getConfidenceScore(TextClassifier.TYPE_ADDRESS), 1e-7f);
        assertEquals(0.1f, result.getConfidenceScore(TextClassifier.TYPE_URL), 1e-7f);
    }

    @Test
    public void testParcelOptions() {
        TextSelection.Options reference = new TextSelection.Options();
        reference.setDefaultLocales(new LocaleList(Locale.US, Locale.GERMANY));
        reference.setDarkLaunchAllowed(true);

        // Parcel and unparcel.
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        TextSelection.Options result = TextSelection.Options.CREATOR.createFromParcel(parcel);

        assertEquals("en-US,de-DE", result.getDefaultLocales().toLanguageTags());
        assertTrue(result.isDarkLaunchAllowed());
    }
}
