/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.content;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.net.Uri;
import android.os.MemoryFile;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Size;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ContentResolverTest {

    private ContentResolver mResolver;
    private IContentProvider mProvider;
    private ContentProviderClient mClient;

    private int mSize = 256_000;
    private MemoryFile mImage;

    @Before
    public void setUp() throws Exception {
        mResolver = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getContentResolver();
        mProvider = mock(IContentProvider.class);
        mClient = new ContentProviderClient(mResolver, mProvider, false);

        mImage = new MemoryFile("temp.png", mSize);
    }

    @After
    public void tearDown() throws Exception {
        mImage.close();
        mImage = null;
    }

    private void initImage(int width, int height) throws Exception {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);

        canvas.drawColor(Color.RED);

        final Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, width / 2, height / 2, paint);

        bitmap.compress(Bitmap.CompressFormat.PNG, 90, mImage.getOutputStream());

        final AssetFileDescriptor afd = new AssetFileDescriptor(
                new ParcelFileDescriptor(mImage.getFileDescriptor()), 0, mSize, null);
        when(mProvider.openTypedAssetFile(any(), any(), any(), any(), any())).thenReturn(
                afd);
    }

    private static void assertImageAspectAndContents(int width, int height, Bitmap bitmap) {
        // And correct aspect ratio
        final int before = (100 * width) / height;
        final int after = (100 * bitmap.getWidth()) / bitmap.getHeight();
        assertEquals(before, after);

        // And scaled correctly
        final int halfX = bitmap.getWidth() / 2;
        final int halfY = bitmap.getHeight() / 2;
        assertEquals(Color.BLUE, bitmap.getPixel(halfX - 5, halfY - 5));
        assertEquals(Color.RED, bitmap.getPixel(halfX + 5, halfY - 5));
        assertEquals(Color.RED, bitmap.getPixel(halfX - 5, halfY + 5));
        assertEquals(Color.RED, bitmap.getPixel(halfX + 5, halfY + 5));
    }

    @Test
    public void testLoadThumbnail_Normal() throws Exception {
        initImage(1280, 960);

        Bitmap res = ContentResolver.loadThumbnail(mClient,
                Uri.parse("content://com.example/"), new Size(1280, 960), null,
                ImageDecoder.ALLOCATOR_SOFTWARE);

        // Size should be untouched
        assertEquals(1280, res.getWidth());
        assertEquals(960, res.getHeight());

        assertImageAspectAndContents(1280, 960, res);
    }

    @Test
    public void testLoadThumbnail_Scaling() throws Exception {
        initImage(1280, 960);

        Bitmap res = ContentResolver.loadThumbnail(mClient,
                Uri.parse("content://com.example/"), new Size(320, 240), null,
                ImageDecoder.ALLOCATOR_SOFTWARE);

        // Size should be much smaller
        assertTrue(res.getWidth() <= 640);
        assertTrue(res.getHeight() <= 480);

        assertImageAspectAndContents(1280, 960, res);
    }

    @Test
    public void testLoadThumbnail_Aspect() throws Exception {
        initImage(1280, 960);

        Bitmap res = ContentResolver.loadThumbnail(mClient,
                Uri.parse("content://com.example/"), new Size(240, 320), null,
                ImageDecoder.ALLOCATOR_SOFTWARE);

        // Size should be much smaller
        assertTrue(res.getWidth() <= 640);
        assertTrue(res.getHeight() <= 480);

        assertImageAspectAndContents(1280, 960, res);
    }

    @Test
    public void testLoadThumbnail_Tiny() throws Exception {
        initImage(32, 24);

        Bitmap res = ContentResolver.loadThumbnail(mClient,
                Uri.parse("content://com.example/"), new Size(320, 240), null,
                ImageDecoder.ALLOCATOR_SOFTWARE);

        // Size should be untouched
        assertEquals(32, res.getWidth());
        assertEquals(24, res.getHeight());

        assertImageAspectAndContents(32, 24, res);
    }

    @Test
    public void testLoadThumbnail_Large() throws Exception {
        // Test very large and extreme ratio image
        initImage(1080, 30000);

        Bitmap res = ContentResolver.loadThumbnail(mClient,
                Uri.parse("content://com.example/"), new Size(1080, 540), null,
                ImageDecoder.ALLOCATOR_SOFTWARE);

        // Size should be much smaller
        assertTrue(res.getWidth() <= 2160);
        assertTrue(res.getHeight() <= 1080);

        assertImageAspectAndContents(1080, 30000, res);
    }

    @Test
    public void testTranslateDeprecatedDataPath() throws Exception {
        assertTranslate(Uri.parse("content://com.example/path/?foo=bar&baz=meow"));
        assertTranslate(Uri.parse("content://com.example/path/subpath/12/"));
        assertTranslate(Uri.parse("content://com.example/path/subpath/12"));
        assertTranslate(Uri.parse("content://com.example/path/12"));
        assertTranslate(Uri.parse("content://com.example/"));
        assertTranslate(Uri.parse("content://com.example"));
    }

    private static void assertTranslate(Uri uri) {
        assertEquals(uri, ContentResolver
                .translateDeprecatedDataPath(ContentResolver.translateDeprecatedDataPath(uri)));
    }

    @Test
    public void testGetType_localProvider() {
        // This provider is running in the same process as the test and is already registered with
        // the ContentResolver when the application starts, see
        // ActivityThread#installContentProviders. This allows ContentResolver to follow a
        // streamlined code path.
        String type = mResolver.getType(Uri.parse("content://android.content.FakeProviderLocal"));
        assertEquals("fake/local", type);
    }

    @Test
    public void testGetType_remoteProvider() {
        // This provider is running in a different process, which will need to be started
        // in order to acquire the provider
        String type = mResolver.getType(Uri.parse("content://android.content.FakeProviderRemote"));
        assertEquals("fake/remote", type);
    }

    @Test
    public void testGetType_slowProvider() {
        // This provider is running in a different process and is intentionally slow to start.
        // We are trying to confirm that it does not cause an ANR
        long start = SystemClock.uptimeMillis();
        String type = mResolver.getType(Uri.parse("content://android.content.SlowProvider"));
        long end = SystemClock.uptimeMillis();
        assertEquals("slow", type);
        assertThat(end).isLessThan(start + 5000);
    }

    @Test
    public void testGetType_unknownProvider() {
        // This provider does not exist.
        // We are trying to confirm that getType returns null and does not cause an ANR
        long start = SystemClock.uptimeMillis();
        String type = mResolver.getType(Uri.parse("content://android.content.NonexistentProvider"));
        long end = SystemClock.uptimeMillis();
        assertThat(type).isNull();
        assertThat(end).isLessThan(start + 5000);
    }

    @Test
    public void testGetType_providerException() {
        String type =
                mResolver.getType(Uri.parse("content://android.content.FakeProviderRemote/error"));
        assertThat(type).isNull();
    }

    @Test
    public void testCanonicalize() {
        Uri canonical = mResolver.canonicalize(
                Uri.parse("content://android.content.FakeProviderRemote/something"));
        assertThat(canonical).isEqualTo(
                Uri.parse("content://android.content.FakeProviderRemote/canonical"));
    }

    @Test
    public void testCanonicalize_providerException() {
        try {
            mResolver.canonicalize(
                    Uri.parse("content://android.content.FakeProviderRemote/error"));
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // Expected
        }
    }

    @Test
    public void testUncanonicalize() {
        Uri uncanonical = mResolver.uncanonicalize(
                Uri.parse("content://android.content.FakeProviderRemote/something"));
        assertThat(uncanonical).isEqualTo(
                Uri.parse("content://android.content.FakeProviderRemote/uncanonical"));
    }
}
