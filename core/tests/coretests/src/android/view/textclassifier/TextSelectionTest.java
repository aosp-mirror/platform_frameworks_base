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

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

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
        final SystemTextClassifierMetadata systemTcMetadata =
                new SystemTextClassifierMetadata(packageName, 1, false);
        reference.setSystemTextClassifierMetadata(systemTcMetadata);

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
        final SystemTextClassifierMetadata resultSystemTcMetadata =
                result.getSystemTextClassifierMetadata();
        assertNotNull(resultSystemTcMetadata);
        assertEquals(packageName, resultSystemTcMetadata.getCallingPackageName());
        assertEquals(1, resultSystemTcMetadata.getUserId());
        assertFalse(resultSystemTcMetadata.useDefaultTextClassifier());
    }

    @Test
    public void testToBuilder() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final int startIndex = 13;
        final int endIndex = 37;
        final String id = "id";
        final Icon icon1 = generateTestIcon(5, 5, Color.RED);

        final TextClassification classification = new TextClassification.Builder()
                .addAction(new RemoteAction(icon1, "title1", "desc1",
                        PendingIntent.getActivity(
                                context, 0, new Intent("action1"), FLAG_IMMUTABLE)))
                .setEntityType(TextClassifier.TYPE_ADDRESS, 1.0f)
                .build();
        final TextSelection textSelection = new TextSelection.Builder(startIndex, endIndex)
                .setId(id)
                .setEntityType(TextClassifier.TYPE_ADDRESS, 1.0f)
                .setExtras(BUNDLE)
                .setTextClassification(classification)
                .build();

        final TextSelection fromBuilder = textSelection.toBuilder().build();

        assertThat(fromBuilder.getId()).isEqualTo(textSelection.getId());
        assertThat(fromBuilder.getSelectionStartIndex())
                .isEqualTo(textSelection.getSelectionStartIndex());
        assertThat(fromBuilder.getSelectionEndIndex())
                .isEqualTo(textSelection.getSelectionEndIndex());
        assertThat(fromBuilder.getTextClassification())
                .isSameInstanceAs(textSelection.getTextClassification());
        assertThat(fromBuilder.getExtras()).isSameInstanceAs(textSelection.getExtras());
    }

    private Icon generateTestIcon(int width, int height, int colorValue) {
        final int numPixels = width * height;
        final int[] colors = new int[numPixels];
        for (int i = 0; i < numPixels; ++i) {
            colors[i] = colorValue;
        }
        final Bitmap bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
        return Icon.createWithBitmap(bitmap);
    }
}
