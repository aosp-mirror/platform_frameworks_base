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

import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.LocaleList;
import android.os.Parcel;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationTest {

    public BitmapDrawable generateTestDrawable(int width, int height, int colorValue) {
        final int numPixels = width * height;
        final int[] colors = new int[numPixels];
        for (int i = 0; i < numPixels; ++i) {
            colors[i] = colorValue;
        }
        final Bitmap bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
        final BitmapDrawable drawable = new BitmapDrawable(Resources.getSystem(), bitmap);
        drawable.setTargetDensity(bitmap.getDensity());
        return drawable;
    }

    @Test
    public void testParcel() {
        final String text = "text";
        final BitmapDrawable primaryIcon = generateTestDrawable(16, 16, Color.RED);
        final String primaryLabel = "primarylabel";
        final Intent primaryIntent = new Intent("primaryintentaction");
        final View.OnClickListener primaryOnClick = v -> { };
        final BitmapDrawable secondaryIcon0 = generateTestDrawable(32, 288, Color.GREEN);
        final String secondaryLabel0 = "secondarylabel0";
        final Intent secondaryIntent0 = new Intent("secondaryintentaction0");
        final BitmapDrawable secondaryIcon1 = generateTestDrawable(576, 288, Color.BLUE);
        final String secondaryLabel1 = "secondaryLabel1";
        final Intent secondaryIntent1 = null;
        final BitmapDrawable secondaryIcon2 = null;
        final String secondaryLabel2 = null;
        final Intent secondaryIntent2 = new Intent("secondaryintentaction2");
        final ColorDrawable secondaryIcon3 = new ColorDrawable(Color.CYAN);
        final String secondaryLabel3 = null;
        final Intent secondaryIntent3 = null;
        final String signature = "signature";
        final TextClassification reference = new TextClassification.Builder()
                .setText(text)
                .setPrimaryAction(primaryIntent, primaryLabel, primaryIcon)
                .setOnClickListener(primaryOnClick)
                .addSecondaryAction(null, null, null)  // ignored
                .addSecondaryAction(secondaryIntent0, secondaryLabel0, secondaryIcon0)
                .addSecondaryAction(secondaryIntent1, secondaryLabel1, secondaryIcon1)
                .addSecondaryAction(secondaryIntent2, secondaryLabel2, secondaryIcon2)
                .addSecondaryAction(secondaryIntent3, secondaryLabel3, secondaryIcon3)
                .setEntityType(TextClassifier.TYPE_ADDRESS, 0.3f)
                .setEntityType(TextClassifier.TYPE_PHONE, 0.7f)
                .setSignature(signature)
                .build();

        // Parcel and unparcel
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextClassification result = TextClassification.CREATOR.createFromParcel(parcel);

        assertEquals(text, result.getText());
        assertEquals(signature, result.getSignature());
        assertEquals(4, result.getSecondaryActionsCount());

        // Primary action (re-use existing icon).
        final Bitmap resPrimaryIcon = ((BitmapDrawable) result.getIcon()).getBitmap();
        assertEquals(primaryIcon.getBitmap().getPixel(0, 0), resPrimaryIcon.getPixel(0, 0));
        assertEquals(16, resPrimaryIcon.getWidth());
        assertEquals(16, resPrimaryIcon.getHeight());
        assertEquals(primaryLabel, result.getLabel());
        assertEquals(primaryIntent.getAction(), result.getIntent().getAction());
        assertEquals(null, result.getOnClickListener());  // Non-parcelable.

        // Secondary action 0 (scale with  height limit).
        final Bitmap resSecondaryIcon0 = ((BitmapDrawable) result.getSecondaryIcon(0)).getBitmap();
        assertEquals(secondaryIcon0.getBitmap().getPixel(0, 0), resSecondaryIcon0.getPixel(0, 0));
        assertEquals(16, resSecondaryIcon0.getWidth());
        assertEquals(144, resSecondaryIcon0.getHeight());
        assertEquals(secondaryLabel0, result.getSecondaryLabel(0));
        assertEquals(secondaryIntent0.getAction(), result.getSecondaryIntent(0).getAction());

        // Secondary action 1 (scale with width limit).
        final Bitmap resSecondaryIcon1 = ((BitmapDrawable) result.getSecondaryIcon(1)).getBitmap();
        assertEquals(secondaryIcon1.getBitmap().getPixel(0, 0), resSecondaryIcon1.getPixel(0, 0));
        assertEquals(144, resSecondaryIcon1.getWidth());
        assertEquals(72, resSecondaryIcon1.getHeight());
        assertEquals(secondaryLabel1, result.getSecondaryLabel(1));
        assertEquals(null, result.getSecondaryIntent(1));

        // Secondary action 2 (no icon).
        assertEquals(null, result.getSecondaryIcon(2));
        assertEquals(null, result.getSecondaryLabel(2));
        assertEquals(secondaryIntent2.getAction(), result.getSecondaryIntent(2).getAction());

        // Secondary action 3 (convert non-bitmap drawable with negative size).
        final Bitmap resSecondaryIcon3 = ((BitmapDrawable) result.getSecondaryIcon(3)).getBitmap();
        assertEquals(secondaryIcon3.getColor(), resSecondaryIcon3.getPixel(0, 0));
        assertEquals(1, resSecondaryIcon3.getWidth());
        assertEquals(1, resSecondaryIcon3.getHeight());
        assertEquals(null, result.getSecondaryLabel(3));
        assertEquals(null, result.getSecondaryIntent(3));

        // Entities.
        assertEquals(2, result.getEntityCount());
        assertEquals(TextClassifier.TYPE_PHONE, result.getEntity(0));
        assertEquals(TextClassifier.TYPE_ADDRESS, result.getEntity(1));
        assertEquals(0.7f, result.getConfidenceScore(TextClassifier.TYPE_PHONE), 1e-7f);
        assertEquals(0.3f, result.getConfidenceScore(TextClassifier.TYPE_ADDRESS), 1e-7f);
    }

    @Test
    public void testParcelOptions() {
        Calendar referenceTime = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        referenceTime.setTimeInMillis(946771200000L);  // 2000-01-02

        TextClassification.Options reference = new TextClassification.Options();
        reference.setDefaultLocales(new LocaleList(Locale.US, Locale.GERMANY));
        reference.setReferenceTime(referenceTime);

        // Parcel and unparcel.
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        TextClassification.Options result = TextClassification.Options.CREATOR.createFromParcel(
                parcel);

        assertEquals("en-US,de-DE", result.getDefaultLocales().toLanguageTags());
        assertEquals(referenceTime, result.getReferenceTime());
    }
}
