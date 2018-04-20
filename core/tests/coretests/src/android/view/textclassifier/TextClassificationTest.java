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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.LocaleList;
import android.os.Parcel;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TextClassificationTest {

    public Icon generateTestIcon(int width, int height, int colorValue) {
        final int numPixels = width * height;
        final int[] colors = new int[numPixels];
        for (int i = 0; i < numPixels; ++i) {
            colors[i] = colorValue;
        }
        final Bitmap bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
        return Icon.createWithBitmap(bitmap);
    }

    @Test
    public void testParcel() {
        final Context context = InstrumentationRegistry.getTargetContext();
        final String text = "text";

        final Icon primaryIcon = generateTestIcon(576, 288, Color.BLUE);
        final String primaryLabel = "primaryLabel";
        final String primaryDescription = "primaryDescription";
        final Intent primaryIntent = new Intent("primaryIntentAction");
        final PendingIntent primaryPendingIntent = PendingIntent.getActivity(context, 0,
                primaryIntent, 0);
        final RemoteAction remoteAction0 = new RemoteAction(primaryIcon, primaryLabel,
                primaryDescription, primaryPendingIntent);

        final Icon secondaryIcon = generateTestIcon(32, 288, Color.GREEN);
        final String secondaryLabel = "secondaryLabel";
        final String secondaryDescription = "secondaryDescription";
        final Intent secondaryIntent = new Intent("secondaryIntentAction");
        final PendingIntent secondaryPendingIntent = PendingIntent.getActivity(context, 0,
                secondaryIntent, 0);
        final RemoteAction remoteAction1 = new RemoteAction(secondaryIcon, secondaryLabel,
                secondaryDescription, secondaryPendingIntent);

        final String id = "id";
        final TextClassification reference = new TextClassification.Builder()
                .setText(text)
                .addAction(remoteAction0)
                .addAction(remoteAction1)
                .setEntityType(TextClassifier.TYPE_ADDRESS, 0.3f)
                .setEntityType(TextClassifier.TYPE_PHONE, 0.7f)
                .setId(id)
                .build();

        // Parcel and unparcel
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextClassification result = TextClassification.CREATOR.createFromParcel(parcel);

        assertEquals(text, result.getText());
        assertEquals(id, result.getId());
        assertEquals(2, result.getActions().size());

        // Primary action.
        final RemoteAction primaryAction = result.getActions().get(0);
        assertEquals(primaryLabel, primaryAction.getTitle());
        assertEquals(primaryDescription, primaryAction.getContentDescription());
        assertEquals(primaryPendingIntent, primaryAction.getActionIntent());

        // Secondary action.
        final RemoteAction secondaryAction = result.getActions().get(1);
        assertEquals(secondaryLabel, secondaryAction.getTitle());
        assertEquals(secondaryDescription, secondaryAction.getContentDescription());
        assertEquals(secondaryPendingIntent, secondaryAction.getActionIntent());

        // Entities.
        assertEquals(2, result.getEntityCount());
        assertEquals(TextClassifier.TYPE_PHONE, result.getEntity(0));
        assertEquals(TextClassifier.TYPE_ADDRESS, result.getEntity(1));
        assertEquals(0.7f, result.getConfidenceScore(TextClassifier.TYPE_PHONE), 1e-7f);
        assertEquals(0.3f, result.getConfidenceScore(TextClassifier.TYPE_ADDRESS), 1e-7f);
    }

    @Test
    public void testParcelLegacy() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();

        final int legacyIconWidth = 192;
        final int legacyIconHeight = 96;
        final int legacyIconColor = Color.BLUE;
        final Drawable legacyIcon = generateTestIcon(
                legacyIconWidth, legacyIconHeight, legacyIconColor)
                .loadDrawable(context);
        final String legacyLabel = "legacyLabel";
        final Intent legacyIntent = new Intent("ACTION_LEGACY");
        final View.OnClickListener legacyOnClick = null;

        final int width = 384;
        final int height = 192;
        final int iconColor = Color.RED;
        final String label = "label";
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, new Intent("ACTION_0"), 0);
        final RemoteAction remoteAction = new RemoteAction(
                generateTestIcon(width, height, iconColor),
                label,
                "description",
                pendingIntent);

        final TextClassification reference = new TextClassification.Builder()
                .setIcon(legacyIcon)
                .setLabel(legacyLabel)
                .setIntent(legacyIntent)
                .setOnClickListener(legacyOnClick)
                .addAction(remoteAction)
                .build();

        // Parcel and unparcel
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextClassification result = TextClassification.CREATOR.createFromParcel(parcel);

        // Legacy fields excluding legacyIntent are replaced by first remoteAction.
        assertNull(result.getIntent());
        final Bitmap resultIcon = ((BitmapDrawable) result.getIcon()).getBitmap();
        assertEquals(iconColor, resultIcon.getPixel(0, 0));
        assertEquals(width, resultIcon.getWidth());
        assertEquals(height, resultIcon.getHeight());
        assertEquals(label, result.getLabel());
        assertNotNull(result.getOnClickListener());
    }

    @Test
    public void testParcelParcel() {
        final ZonedDateTime referenceTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(946771200000L),  // 2000-01-02
                ZoneId.of("UTC"));
        final String text = "text";

        final TextClassification.Request reference =
                new TextClassification.Request.Builder(text, 0, text.length())
                        .setDefaultLocales(new LocaleList(Locale.US, Locale.GERMANY))
                        .setReferenceTime(referenceTime)
                        .build();

        // Parcel and unparcel.
        final Parcel parcel = Parcel.obtain();
        reference.writeToParcel(parcel, reference.describeContents());
        parcel.setDataPosition(0);
        final TextClassification.Request result =
                TextClassification.Request.CREATOR.createFromParcel(parcel);

        assertEquals(text, result.getText());
        assertEquals(0, result.getStartIndex());
        assertEquals(text.length(), result.getEndIndex());
        assertEquals(referenceTime, result.getReferenceTime());
        assertEquals("en-US,de-DE", result.getDefaultLocales().toLanguageTags());
        assertEquals(referenceTime, result.getReferenceTime());
    }
}
