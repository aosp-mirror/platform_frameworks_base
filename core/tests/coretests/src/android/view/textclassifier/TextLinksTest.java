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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.os.Bundle;
import android.os.LocaleList;
import android.os.Parcel;
import android.util.ArrayMap;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextLinksTest {
    private static final String BUNDLE_KEY = "key";
    private static final String BUNDLE_VALUE = "value";
    private static final Bundle BUNDLE = new Bundle();
    static {
        BUNDLE.putString(BUNDLE_KEY, BUNDLE_VALUE);
    }

    private Map<String, Float> getEntityScores(float address, float phone, float other) {
        final Map<String, Float> result = new ArrayMap<>();
        if (address > 0.f) {
            result.put(TextClassifier.TYPE_ADDRESS, address);
        }
        if (phone > 0.f) {
            result.put(TextClassifier.TYPE_PHONE, phone);
        }
        if (other > 0.f) {
            result.put(TextClassifier.TYPE_OTHER, other);
        }
        return result;
    }

    @Test
    public void testParcel() {
        final String fullText = "this is just a test";
        final TextLinks reference = new TextLinks.Builder(fullText)
                .addLink(0, 4, getEntityScores(0.f, 0.f, 1.f), BUNDLE)
                .addLink(5, 12, getEntityScores(.8f, .1f, .5f))
                .setExtras(BUNDLE)
                .build();

        // Parcel and unparcel.
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextLinks result = TextLinks.CREATOR.createFromParcel(parcel);
        final List<TextLinks.TextLink> resultList = new ArrayList<>(result.getLinks());

        assertEquals(fullText, result.getText());
        assertEquals(2, resultList.size());
        assertEquals(0, resultList.get(0).getStart());
        assertEquals(4, resultList.get(0).getEnd());
        assertEquals(1, resultList.get(0).getEntityCount());
        assertEquals(TextClassifier.TYPE_OTHER, resultList.get(0).getEntity(0));
        assertEquals(1.f, resultList.get(0).getConfidenceScore(TextClassifier.TYPE_OTHER), 1e-7f);
        assertEquals(BUNDLE_VALUE, resultList.get(0).getExtras().getString(BUNDLE_KEY));
        assertEquals(5, resultList.get(1).getStart());
        assertEquals(12, resultList.get(1).getEnd());
        assertEquals(3, resultList.get(1).getEntityCount());
        assertEquals(TextClassifier.TYPE_ADDRESS, resultList.get(1).getEntity(0));
        assertEquals(TextClassifier.TYPE_OTHER, resultList.get(1).getEntity(1));
        assertEquals(TextClassifier.TYPE_PHONE, resultList.get(1).getEntity(2));
        assertEquals(.8f, resultList.get(1).getConfidenceScore(TextClassifier.TYPE_ADDRESS), 1e-7f);
        assertEquals(.5f, resultList.get(1).getConfidenceScore(TextClassifier.TYPE_OTHER), 1e-7f);
        assertEquals(.1f, resultList.get(1).getConfidenceScore(TextClassifier.TYPE_PHONE), 1e-7f);
        assertEquals(BUNDLE_VALUE, result.getExtras().getString(BUNDLE_KEY));
    }

    @Test
    public void testParcelOptions() {
        final String packageName = "packageName";
        final TextClassifier.EntityConfig entityConfig = TextClassifier.EntityConfig.create(
                Arrays.asList(TextClassifier.HINT_TEXT_IS_EDITABLE),
                Arrays.asList("a", "b", "c"),
                Arrays.asList("b"));
        final ZonedDateTime referenceTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(1000L),
                ZoneId.of("UTC"));
        final TextLinks.Request reference = new TextLinks.Request.Builder("text")
                .setDefaultLocales(new LocaleList(Locale.US, Locale.GERMANY))
                .setEntityConfig(entityConfig)
                .setExtras(BUNDLE)
                .setReferenceTime(referenceTime)
                .build();
        final SystemTextClassifierMetadata systemTcMetadata =
                new SystemTextClassifierMetadata(packageName, 1, false);
        reference.setSystemTextClassifierMetadata(systemTcMetadata);

        // Parcel and unparcel.
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextLinks.Request result = TextLinks.Request.CREATOR.createFromParcel(parcel);

        assertEquals("text", result.getText());
        assertEquals("en-US,de-DE", result.getDefaultLocales().toLanguageTags());
        assertEquals(new String[]{TextClassifier.HINT_TEXT_IS_EDITABLE},
                result.getEntityConfig().getHints().toArray());
        assertEquals(new HashSet<String>(Arrays.asList("a", "c")),
                result.getEntityConfig().resolveEntityListModifications(Collections.emptyList()));
        assertEquals(BUNDLE_VALUE, result.getExtras().getString(BUNDLE_KEY));
        assertEquals(packageName, result.getCallingPackageName());
        assertEquals(referenceTime, result.getReferenceTime());
        final SystemTextClassifierMetadata resultSystemTcMetadata =
                result.getSystemTextClassifierMetadata();
        assertNotNull(resultSystemTcMetadata);
        assertEquals(packageName, resultSystemTcMetadata.getCallingPackageName());
        assertEquals(1, resultSystemTcMetadata.getUserId());
        assertFalse(resultSystemTcMetadata.useDefaultTextClassifier());
    }
}
