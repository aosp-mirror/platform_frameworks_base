/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.app.wallpaper;

import static android.app.WallpaperManager.ORIENTATION_LANDSCAPE;
import static android.app.WallpaperManager.ORIENTATION_PORTRAIT;
import static android.app.WallpaperManager.ORIENTATION_SQUARE_LANDSCAPE;
import static android.app.WallpaperManager.ORIENTATION_SQUARE_PORTRAIT;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ComponentName;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.util.SparseArray;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(JUnit4.class)
public class WallpaperDescriptionTest {
    private static final Rect DEFAULT_CROP_PORTRAIT = new Rect(1, 2, 3, 4);
    private static final Rect DEFAULT_CROP_LANDSCAPE = new Rect(5, 6, 7, 8);
    private static final Rect DEFAULT_CROP_SQUARE_PORTRAIT = new Rect(9, 10, 11, 12);
    private static final Rect DEFAULT_CROP_SQUARE_LANDSCAPE = new Rect(13, 14, 15, 16);

    private final ComponentName mTestComponent = new ComponentName("fakePackage", "fakeClass");

    @Test
    public void equals_ignoresIrrelevantFields() {
        String id = "fakeId";
        WallpaperDescription desc1 = new WallpaperDescription.Builder()
                .setComponent(mTestComponent).setId(id).setTitle("fake one").build();
        WallpaperDescription desc2 = new WallpaperDescription.Builder()
                .setComponent(mTestComponent).setId(id).setTitle("fake different").build();

        assertThat(desc1).isEqualTo(desc2);
    }

    @Test
    public void hash_ignoresIrrelevantFields() {
        String id = "fakeId";
        WallpaperDescription desc1 = new WallpaperDescription.Builder().setComponent(
                mTestComponent).setId(id).setTitle("fake one").build();
        WallpaperDescription desc2 = new WallpaperDescription.Builder().setComponent(
                mTestComponent).setId(id).setTitle("fake different").build();

        assertThat(desc1.hashCode()).isEqualTo(desc2.hashCode());
    }

    @Test
    public void xml_roundTripSucceeds() throws IOException, XmlPullParserException {
        final Uri thumbnail = Uri.parse("http://www.bogus.com/thumbnail");
        final List<CharSequence> description = List.of("line1", "line2");
        final Uri contextUri = Uri.parse("http://www.bogus.com/contextUri");
        final PersistableBundle content = makeDefaultContent();
        final SparseArray<Rect> cropHints = makeDefaultCropHints();
        final float sampleSize = 0.9f;
        WallpaperDescription source = new WallpaperDescription.Builder()
                .setComponent(mTestComponent)
                .setId("fakeId")
                .setThumbnail(thumbnail)
                .setTitle("Fake title")
                .setDescription(description)
                .setContextUri(contextUri)
                .setContextDescription("Context description")
                .setContent(content)
                .setCropHints(cropHints)
                .setSampleSize(sampleSize)
                .build();

        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(ostream, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        serializer.startTag(null, "test");
        source.saveToXml(serializer);
        serializer.endTag(null, "test");
        serializer.endDocument();
        ostream.close();

        WallpaperDescription destination = null;
        ByteArrayInputStream istream = new ByteArrayInputStream(ostream.toByteArray());
        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(istream, StandardCharsets.UTF_8.name());
        int type;
        do {
            type = parser.next();
            if (type == XmlPullParser.START_TAG && "test".equals(parser.getName())) {
                destination = WallpaperDescription.restoreFromXml(parser);
            }
        } while (type != XmlPullParser.END_DOCUMENT);

        assertThat(destination).isNotNull();
        assertThat(destination.getComponent()).isEqualTo(source.getComponent());
        assertThat(destination.getId()).isEqualTo(source.getId());
        assertThat(destination.getThumbnail()).isEqualTo(source.getThumbnail());
        assertWithMessage("title mismatch").that(
                CharSequence.compare(destination.getTitle(), source.getTitle())).isEqualTo(0);
        assertThat(destination.getDescription()).hasSize(source.getDescription().size());
        for (int i = 0; i < destination.getDescription().size(); i++) {
            CharSequence strDest = destination.getDescription().get(i);
            CharSequence strSrc = source.getDescription().get(i);
            assertWithMessage("description string mismatch")
                    .that(CharSequence.compare(strDest, strSrc)).isEqualTo(0);
        }
        assertThat(destination.getContextUri()).isEqualTo(source.getContextUri());
        assertWithMessage("context description mismatch").that(
                CharSequence.compare(destination.getContextDescription(),
                source.getContextDescription())).isEqualTo(0);
        assertThat(destination.getContent()).isNotNull();
        assertThat(destination.getContent().getString("ckey")).isEqualTo(
                source.getContent().getString("ckey"));
        assertThat(destination.getCropHints()).isNotNull();
        assertThat(destination.getCropHints().get(ORIENTATION_PORTRAIT)).isEqualTo(
                DEFAULT_CROP_PORTRAIT);
        assertThat(destination.getCropHints().get(ORIENTATION_LANDSCAPE)).isEqualTo(
                DEFAULT_CROP_LANDSCAPE);
        assertThat(destination.getCropHints().get(ORIENTATION_SQUARE_PORTRAIT)).isEqualTo(
                DEFAULT_CROP_SQUARE_PORTRAIT);
        assertThat(destination.getCropHints().get(ORIENTATION_SQUARE_LANDSCAPE)).isEqualTo(
                DEFAULT_CROP_SQUARE_LANDSCAPE);
        assertThat(destination.getSampleSize()).isEqualTo(sampleSize);
    }

    @Test
    public void xml_roundTripSucceeds_withNulls() throws IOException, XmlPullParserException {
        WallpaperDescription source = new WallpaperDescription.Builder().build();

        ByteArrayOutputStream ostream = new ByteArrayOutputStream();
        TypedXmlSerializer serializer = Xml.newBinarySerializer();
        serializer.setOutput(ostream, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        serializer.startTag(null, "test");
        source.saveToXml(serializer);
        serializer.endTag(null, "test");
        serializer.endDocument();
        ostream.close();

        WallpaperDescription destination = null;
        ByteArrayInputStream istream = new ByteArrayInputStream(ostream.toByteArray());
        TypedXmlPullParser parser = Xml.newBinaryPullParser();
        parser.setInput(istream, StandardCharsets.UTF_8.name());
        int type;
        do {
            type = parser.next();
            if (type == XmlPullParser.START_TAG && "test".equals(parser.getName())) {
                destination = WallpaperDescription.restoreFromXml(parser);
            }
        } while (type != XmlPullParser.END_DOCUMENT);

        assertThat(destination).isNotNull();
        assertThat(destination.getComponent()).isEqualTo(source.getComponent());
        assertThat(destination.getId()).isEqualTo(source.getId());
        assertThat(destination.getThumbnail()).isEqualTo(source.getThumbnail());
        assertThat(destination.getTitle()).isNull();
        assertThat(destination.getDescription()).hasSize(0);
        assertThat(destination.getContextUri()).isEqualTo(source.getContextUri());
        assertThat(destination.getContextDescription()).isNull();
        assertThat(destination.getContent()).isNotNull();
        assertThat(destination.getContent().keySet()).isEmpty();
        assertThat(destination.getCropHints()).isNotNull();
        assertThat(destination.getCropHints().size()).isEqualTo(0);
        assertThat(destination.getSampleSize()).isEqualTo(source.getSampleSize());
    }

    @Test
    public void parcel_roundTripSucceeds() {
        final Uri thumbnail = Uri.parse("http://www.bogus.com/thumbnail");
        final List<CharSequence> description = List.of("line1", "line2");
        final Uri contextUri = Uri.parse("http://www.bogus.com/contextUri");
        final PersistableBundle content = makeDefaultContent();
        final SparseArray<Rect> cropHints = makeDefaultCropHints();
        final float sampleSize = 0.9f;
        WallpaperDescription source = new WallpaperDescription.Builder()
                .setComponent(mTestComponent)
                .setId("fakeId")
                .setThumbnail(thumbnail)
                .setTitle("Fake title")
                .setDescription(description)
                .setContextUri(contextUri)
                .setContextDescription("Context description")
                .setContent(content)
                .setCropHints(cropHints)
                .setSampleSize(sampleSize)
                .build();

        Parcel parcel = Parcel.obtain();
        source.writeToParcel(parcel, 0);
        // Reset parcel for reading
        parcel.setDataPosition(0);
        WallpaperDescription destination = WallpaperDescription.CREATOR.createFromParcel(parcel);

        assertThat(destination.getComponent()).isEqualTo(source.getComponent());
        assertThat(destination.getId()).isEqualTo(source.getId());
        assertThat(destination.getThumbnail()).isEqualTo(source.getThumbnail());
        assertWithMessage("title mismatch").that(
                CharSequence.compare(destination.getTitle(), source.getTitle())).isEqualTo(0);
        assertThat(destination.getDescription()).hasSize(source.getDescription().size());
        for (int i = 0; i < destination.getDescription().size(); i++) {
            CharSequence strDest = destination.getDescription().get(i);
            CharSequence strSrc = source.getDescription().get(i);
            assertWithMessage("description string mismatch")
                    .that(CharSequence.compare(strDest, strSrc)).isEqualTo(0);
        }
        assertThat(destination.getContextUri()).isEqualTo(source.getContextUri());
        assertWithMessage("context description mismatch").that(
                CharSequence.compare(destination.getContextDescription(),
                        source.getContextDescription())).isEqualTo(0);
        assertThat(destination.getContent()).isNotNull();
        assertThat(destination.getContent().getString("ckey")).isEqualTo(
                source.getContent().getString("ckey"));
        assertThat(destination.getCropHints()).isNotNull();
        assertThat(destination.getCropHints().get(ORIENTATION_PORTRAIT)).isEqualTo(
                DEFAULT_CROP_PORTRAIT);
        assertThat(destination.getCropHints().get(ORIENTATION_LANDSCAPE)).isEqualTo(
                DEFAULT_CROP_LANDSCAPE);
        assertThat(destination.getCropHints().get(ORIENTATION_SQUARE_PORTRAIT)).isEqualTo(
                DEFAULT_CROP_SQUARE_PORTRAIT);
        assertThat(destination.getCropHints().get(ORIENTATION_SQUARE_LANDSCAPE)).isEqualTo(
                DEFAULT_CROP_SQUARE_LANDSCAPE);
        assertThat(destination.getSampleSize()).isEqualTo(sampleSize);
    }

    @Test
    public void parcel_roundTripSucceeds_withNulls() {
        WallpaperDescription source = new WallpaperDescription.Builder().build();

        Parcel parcel = Parcel.obtain();
        source.writeToParcel(parcel, 0);
        // Reset parcel for reading
        parcel.setDataPosition(0);
        WallpaperDescription destination = WallpaperDescription.CREATOR.createFromParcel(parcel);

        assertThat(destination.getComponent()).isEqualTo(source.getComponent());
        assertThat(destination.getId()).isEqualTo(source.getId());
        assertThat(destination.getThumbnail()).isEqualTo(source.getThumbnail());
        assertThat(destination.getTitle()).isNull();
        assertThat(destination.getDescription()).hasSize(0);
        assertThat(destination.getContextUri()).isEqualTo(source.getContextUri());
        assertThat(destination.getContextDescription()).isNull();
        assertThat(destination.getContent()).isNotNull();
        assertThat(destination.getContent().keySet()).isEmpty();
        assertThat(destination.getCropHints()).isNotNull();
        assertThat(destination.getCropHints().size()).isEqualTo(0);
        assertThat(destination.getSampleSize()).isEqualTo(source.getSampleSize());
    }

    @Test
    public void toBuilder_succeeds() {
        final String sourceId = "sourceId";
        final Uri thumbnail = Uri.parse("http://www.bogus.com/thumbnail");
        final List<CharSequence> description = List.of("line1", "line2");
        final Uri contextUri = Uri.parse("http://www.bogus.com/contextUri");
        final PersistableBundle content = makeDefaultContent();
        final SparseArray<Rect> cropHints = makeDefaultCropHints();
        final float sampleSize = 1.1f;
        final String destinationId = "destinationId";
        WallpaperDescription source = new WallpaperDescription.Builder()
                .setComponent(mTestComponent)
                .setId(sourceId)
                .setThumbnail(thumbnail)
                .setTitle("Fake title")
                .setDescription(description)
                .setContextUri(contextUri)
                .setContextDescription("Context description")
                .setContent(content)
                .setCropHints(cropHints)
                .setSampleSize(sampleSize)
                .build();

        WallpaperDescription destination = source.toBuilder().setId(destinationId).build();

        assertThat(destination.getComponent()).isEqualTo(source.getComponent());
        assertThat(destination.getId()).isEqualTo(destinationId);
        assertThat(destination.getThumbnail()).isEqualTo(source.getThumbnail());
        assertWithMessage("title mismatch").that(
                CharSequence.compare(destination.getTitle(), source.getTitle())).isEqualTo(0);
        assertThat(destination.getDescription()).hasSize(source.getDescription().size());
        for (int i = 0; i < destination.getDescription().size(); i++) {
            CharSequence strDest = destination.getDescription().get(i);
            CharSequence strSrc = source.getDescription().get(i);
            assertWithMessage("description string mismatch")
                    .that(CharSequence.compare(strDest, strSrc)).isEqualTo(0);
        }
        assertThat(destination.getContextUri()).isEqualTo(source.getContextUri());
        assertWithMessage("context description mismatch").that(
                CharSequence.compare(destination.getContextDescription(),
                        source.getContextDescription())).isEqualTo(0);
        assertThat(destination.getContent()).isNotNull();
        assertThat(destination.getContent().getString("ckey")).isEqualTo(
                source.getContent().getString("ckey"));
        assertThat(destination.getCropHints().get(ORIENTATION_PORTRAIT)).isEqualTo(
                DEFAULT_CROP_PORTRAIT);
        assertThat(destination.getCropHints().get(ORIENTATION_LANDSCAPE)).isEqualTo(
                DEFAULT_CROP_LANDSCAPE);
        assertThat(destination.getCropHints().get(ORIENTATION_SQUARE_PORTRAIT)).isEqualTo(
                DEFAULT_CROP_SQUARE_PORTRAIT);
        assertThat(destination.getCropHints().get(ORIENTATION_SQUARE_LANDSCAPE)).isEqualTo(
                DEFAULT_CROP_SQUARE_LANDSCAPE);
        assertThat(destination.getSampleSize()).isEqualTo(sampleSize);
    }

    private static PersistableBundle makeDefaultContent() {
        final PersistableBundle content = new PersistableBundle();
        content.putString("ckey", "cvalue");
        return content;
    }

    private static SparseArray<Rect> makeDefaultCropHints() {
        final SparseArray<Rect> cropHints = new SparseArray<>();
        cropHints.put(ORIENTATION_PORTRAIT, DEFAULT_CROP_PORTRAIT);
        cropHints.put(ORIENTATION_LANDSCAPE, DEFAULT_CROP_LANDSCAPE);
        cropHints.put(ORIENTATION_SQUARE_PORTRAIT, DEFAULT_CROP_SQUARE_PORTRAIT);
        cropHints.put(ORIENTATION_SQUARE_LANDSCAPE, DEFAULT_CROP_SQUARE_LANDSCAPE);
        return cropHints;
    }
}
