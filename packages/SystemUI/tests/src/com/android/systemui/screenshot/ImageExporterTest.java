/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static java.nio.charset.StandardCharsets.US_ASCII;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.testing.AndroidTestingRunner;
import android.view.Display;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.filters.MediumTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FakeFeatureFlags;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@MediumTest // file I/O
public class ImageExporterTest extends SysuiTestCase {
    /** Executes directly in the caller's thread */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final byte[] EXIF_FILE_TAG = "Exif\u0000\u0000".getBytes(US_ASCII);

    private static final ZonedDateTime CAPTURE_TIME =
            ZonedDateTime.of(LocalDateTime.of(2020, 12, 15, 13, 15), ZoneId.of("America/New_York"));

    private FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    @Mock
    private ContentResolver mMockContentResolver;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testImageFilename() {
        assertEquals("image file name", "Screenshot_20201215-131500.png",
                ImageExporter.createFilename(CAPTURE_TIME, CompressFormat.PNG,
                    Display.DEFAULT_DISPLAY));
    }

    @Test
    public void testImageFilename_secondaryDisplay1() {
        assertEquals("image file name", "Screenshot_20201215-131500-display-1.png",
                ImageExporter.createFilename(CAPTURE_TIME, CompressFormat.PNG, /* displayId= */ 1));
    }

    @Test
    public void testImageFilename_secondaryDisplay2() {
        assertEquals("image file name", "Screenshot_20201215-131500-display-2.png",
                ImageExporter.createFilename(CAPTURE_TIME, CompressFormat.PNG, /* displayId= */ 2));
    }

    @Test
    public void testUpdateExifAttributes_timeZoneUTC() throws IOException {
        ExifInterface exifInterface = new ExifInterface(new ByteArrayInputStream(EXIF_FILE_TAG),
                ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY);
        ImageExporter.updateExifAttributes(exifInterface,
                UUID.fromString("3c11da99-9284-4863-b1d5-6f3684976814"), 100, 100,
                ZonedDateTime.of(LocalDateTime.of(2020, 12, 15, 18, 15), ZoneId.of("UTC")));

        assertEquals("Exif " + ExifInterface.TAG_IMAGE_UNIQUE_ID,
                "3c11da99-9284-4863-b1d5-6f3684976814",
                exifInterface.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID));
        assertEquals("Exif " + ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00",
                exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
    }

    @Test
    public void testImageExport() throws ExecutionException, InterruptedException, IOException {
        ContentResolver contentResolver = mContext.getContentResolver();
        ImageExporter exporter = new ImageExporter(contentResolver, mFeatureFlags);

        UUID requestId = UUID.fromString("3c11da99-9284-4863-b1d5-6f3684976814");
        Bitmap original = createCheckerBitmap(10, 10, 10);

        ListenableFuture<ImageExporter.Result> direct =
                exporter.export(DIRECT_EXECUTOR, requestId, original, CAPTURE_TIME,
                        Process.myUserHandle(), Display.DEFAULT_DISPLAY);
        assertTrue("future should be done", direct.isDone());
        assertFalse("future should not be canceled", direct.isCancelled());
        ImageExporter.Result result = direct.get();

        assertEquals("Result should contain the same request id", requestId, result.requestId);
        assertEquals("Filename should contain the correct filename",
                "Screenshot_20201215-131500.png", result.fileName);
        assertNotNull("CompressFormat should be set", result.format);
        assertEquals("The default CompressFormat should be PNG", CompressFormat.PNG, result.format);
        assertNotNull("Uri should not be null", result.uri);
        assertEquals("Timestamp should match input", CAPTURE_TIME.toInstant().toEpochMilli(),
                result.timestamp);

        Bitmap decoded = null;
        try (InputStream in = contentResolver.openInputStream(result.uri)) {
            decoded = BitmapFactory.decodeStream(in);
            assertNotNull("decoded image should not be null", decoded);
            assertTrue("original and decoded image should be identical", original.sameAs(decoded));

            try (ParcelFileDescriptor pfd = contentResolver.openFile(result.uri, "r", null)) {
                assertNotNull(pfd);
                ExifInterface exifInterface = new ExifInterface(pfd.getFileDescriptor());

                assertEquals("Exif " + ExifInterface.TAG_IMAGE_UNIQUE_ID,
                        "3c11da99-9284-4863-b1d5-6f3684976814",
                        exifInterface.getAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID));

                assertEquals("Exif " + ExifInterface.TAG_SOFTWARE, "Android " + Build.DISPLAY,
                        exifInterface.getAttribute(ExifInterface.TAG_SOFTWARE));

                assertEquals("Exif " + ExifInterface.TAG_IMAGE_WIDTH, 100,
                        exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0));
                assertEquals("Exif " + ExifInterface.TAG_IMAGE_LENGTH, 100,
                        exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0));

                assertEquals("Exif " + ExifInterface.TAG_DATETIME_ORIGINAL, "2020:12:15 13:15:00",
                        exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
                assertEquals("Exif " + ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "000",
                        exifInterface.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL));
                assertEquals("Exif " + ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "-05:00",
                        exifInterface.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL));
            }
        } finally {
            if (decoded != null) {
                decoded.recycle();
            }
            contentResolver.delete(result.uri, null);
        }
    }

    @Test
    public void testMediaStoreMetadata() {
        String name = ImageExporter.createFilename(CAPTURE_TIME, CompressFormat.PNG,
                Display.DEFAULT_DISPLAY);
        ContentValues values = ImageExporter.createMetadata(CAPTURE_TIME, CompressFormat.PNG, name);
        assertEquals("Pictures/Screenshots",
                values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
        assertEquals("Screenshot_20201215-131500.png",
                values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME));
        assertEquals("image/png", values.getAsString(MediaStore.MediaColumns.MIME_TYPE));
        assertEquals(Long.valueOf(1608056100L),
                values.getAsLong(MediaStore.MediaColumns.DATE_ADDED));
        assertEquals(Long.valueOf(1608056100L),
                values.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED));
        assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
        assertEquals(Long.valueOf(1608056100L + 86400L), // +1 day
                values.getAsLong(MediaStore.MediaColumns.DATE_EXPIRES));
    }

    @Test
    public void testSetUser() {
        ImageExporter exporter = new ImageExporter(mMockContentResolver, mFeatureFlags);

        UserHandle imageUserHande = UserHandle.of(10);

        ArgumentCaptor<Uri> uriCaptor = ArgumentCaptor.forClass(Uri.class);
        // Capture the URI and then return null to bail out of export.
        Mockito.when(mMockContentResolver.insert(uriCaptor.capture(), Mockito.any())).thenReturn(
                null);
        exporter.export(DIRECT_EXECUTOR, UUID.fromString("3c11da99-9284-4863-b1d5-6f3684976814"),
                null, CAPTURE_TIME, imageUserHande, Display.DEFAULT_DISPLAY);

        Uri expected = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        expected = ContentProvider.maybeAddUserId(expected, imageUserHande.getIdentifier());

        assertEquals(expected, uriCaptor.getValue());
    }

    @SuppressWarnings("SameParameterValue")
    private Bitmap createCheckerBitmap(int tileSize, int w, int h) {
        Bitmap bitmap = Bitmap.createBitmap(w * tileSize, h * tileSize, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < h; i++) {
            int top = i * tileSize;
            for (int j = 0; j < w; j++) {
                int left = j * tileSize;
                paint.setColor(paint.getColor() == Color.WHITE ? Color.BLACK : Color.WHITE);
                c.drawRect(left, top, left + tileSize, top + tileSize, paint);
            }
        }
        return bitmap;
    }
}
