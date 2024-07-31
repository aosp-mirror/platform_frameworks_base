/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.icu.util.ULocale;
import android.os.Bundle;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for TextLanguage.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public final class TextLanguageTest {

    private static final float EPSILON = 0.000001f;

    @Test
    public void testParcel() throws Exception {
        final String bundleKey = "experiment.int";
        final Bundle bundle = new Bundle();
        bundle.putInt(bundleKey, 1234);

        final TextLanguage reference = new TextLanguage.Builder()
                .setId("id")
                .setExtras(bundle)
                .putLocale(ULocale.ENGLISH, 0.8f)
                .putLocale(ULocale.GERMAN, 0.2f)
                .build();

        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final TextLanguage result = TextLanguage.CREATOR.createFromParcel(parcel);

        assertEquals("id", result.getId());
        assertEquals(1234, result.getExtras().getInt(bundleKey));
        assertEquals(2, result.getLocaleHypothesisCount());
        assertEquals(ULocale.ENGLISH, result.getLocale(0));
        assertEquals(0.8f, result.getConfidenceScore(ULocale.ENGLISH), EPSILON);
        assertEquals(ULocale.GERMAN, result.getLocale(1));
        assertEquals(0.2f, result.getConfidenceScore(ULocale.GERMAN), EPSILON);
    }

    @Test
    public void testRequestParcel() throws Exception {
        final String text = "This is random text";
        final String bundleKey = "experiment.str";
        final Bundle bundle = new Bundle();
        bundle.putString(bundleKey, "bundle");
        final String packageName = "packageName";

        final TextLanguage.Request reference = new TextLanguage.Request.Builder(text)
                .setExtras(bundle)
                .build();
        final SystemTextClassifierMetadata systemTcMetadata =
                new SystemTextClassifierMetadata(packageName, 1, false);
        reference.setSystemTextClassifierMetadata(systemTcMetadata);

        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final TextLanguage.Request result = TextLanguage.Request.CREATOR.createFromParcel(parcel);

        assertEquals(text, result.getText());
        assertEquals("bundle", result.getExtras().getString(bundleKey));
        assertEquals(packageName, result.getCallingPackageName());
        final SystemTextClassifierMetadata resultSystemTcMetadata =
                result.getSystemTextClassifierMetadata();
        assertNotNull(resultSystemTcMetadata);
        assertEquals(packageName, resultSystemTcMetadata.getCallingPackageName());
        assertEquals(1, resultSystemTcMetadata.getUserId());
        assertFalse(resultSystemTcMetadata.useDefaultTextClassifier());
    }
}
