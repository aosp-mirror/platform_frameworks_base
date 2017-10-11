/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.graphics;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;


public class BitmapTest extends TestCase {

    @SmallTest
    public void testBasic() throws Exception {
        Bitmap bm1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        Bitmap bm2 = Bitmap.createBitmap(100, 200, Bitmap.Config.RGB_565);
        Bitmap bm3 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_4444);

        assertTrue("mutability", bm1.isMutable());
        assertTrue("mutability", bm2.isMutable());
        assertTrue("mutability", bm3.isMutable());

        assertEquals("width", 100, bm1.getWidth());
        assertEquals("width", 100, bm2.getWidth());
        assertEquals("width", 100, bm3.getWidth());

        assertEquals("rowbytes", 400, bm1.getRowBytes());
        assertEquals("rowbytes", 200, bm2.getRowBytes());
        assertEquals("rowbytes", 400, bm3.getRowBytes());

        assertEquals("byteCount", 80000, bm1.getByteCount());
        assertEquals("byteCount", 40000, bm2.getByteCount());
        assertEquals("byteCount", 80000, bm3.getByteCount());

        assertEquals("height", 200, bm1.getHeight());
        assertEquals("height", 200, bm2.getHeight());
        assertEquals("height", 200, bm3.getHeight());

        assertTrue("hasAlpha", bm1.hasAlpha());
        assertFalse("hasAlpha", bm2.hasAlpha());
        assertTrue("hasAlpha", bm3.hasAlpha());

        assertTrue("getConfig", bm1.getConfig() == Bitmap.Config.ARGB_8888);
        assertTrue("getConfig", bm2.getConfig() == Bitmap.Config.RGB_565);
        assertTrue("getConfig", bm3.getConfig() == Bitmap.Config.ARGB_8888);
    }

    @SmallTest
    public void testMutability() throws Exception {
        Bitmap bm1 = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        Bitmap bm2 = Bitmap.createBitmap(new int[100 * 200], 100, 200,
                                         Bitmap.Config.ARGB_8888);

        assertTrue("mutability", bm1.isMutable());
        assertFalse("mutability", bm2.isMutable());

        bm1.eraseColor(0);

        try {
            bm2.eraseColor(0);
            fail("eraseColor should throw exception");
        } catch (IllegalStateException ex) {
            // safe to catch and ignore this
        }
    }

    @SmallTest
    public void testGetPixelsWithAlpha() throws Exception {
        int[] colors = new int[100];
        for (int i = 0; i < 100; i++) {
            colors[i] = (0xFF << 24) | (i << 16) | (i << 8) | i;
        }

        Bitmap bm = Bitmap.createBitmap(colors, 10, 10,
                                        Bitmap.Config.ARGB_8888);

        int[] pixels = new int[100];
        bm.getPixels(pixels, 0, 10, 0, 0, 10, 10);
        for (int i = 0; i < 100; i++) {
            int p = bm.getPixel(i % 10, i / 10);
            assertEquals("getPixels", p, pixels[i]);
        }

        for (int i = 0; i < 100; i++) {
            int p = bm.getPixel(i % 10, i / 10);
            assertEquals("getPixel", p, colors[i]);
            assertEquals("pixel value", p,
                         ((0xFF << 24) | (i << 16) | (i << 8) | i));
        }

    }

    @SmallTest
    public void testGetPixelsWithoutAlpha() throws Exception {
        int[] colors = new int[100];
        for (int i = 0; i < 100; i++) {
            colors[i] = (0xFF << 24) | (i << 16) | (i << 8) | i;
        }

        Bitmap bm = Bitmap.createBitmap(colors, 10, 10, Bitmap.Config.RGB_565);

        int[] pixels = new int[100];
        bm.getPixels(pixels, 0, 10, 0, 0, 10, 10);
        for (int i = 0; i < 100; i++) {
            int p = bm.getPixel(i % 10, i / 10);
            assertEquals("getPixels", p, pixels[i]);
        }
    }

    @SmallTest
    public void testSetPixelsWithAlpha() throws Exception {
        int[] colors = new int[100];
        for (int i = 0; i < 100; i++) {
            colors[i] = (0xFF << 24) | (i << 16) | (i << 8) | i;
        }

        Bitmap.Config config = Bitmap.Config.ARGB_8888;
        Bitmap bm1 = Bitmap.createBitmap(colors, 10, 10, config);
        Bitmap bm2 = Bitmap.createBitmap(10, 10, config);

        for (int i = 0; i < 100; i++) {
            bm2.setPixel(i % 10, i / 10, colors[i]);
        }

        for (int i = 0; i < 100; i++) {
            assertEquals("setPixel",
                    bm1.getPixel(i % 10, i / 10), bm2.getPixel(i % 10, i / 10));
        }

        for (int i = 0; i < 100; i++) {
            assertEquals("setPixel value",
                         bm1.getPixel(i % 10, i / 10), colors[i]);
        }
    }

    @SmallTest
    public void testSetPixelsWithoutAlpha() throws Exception {
        int[] colors = new int[100];
        for (int i = 0; i < 100; i++) {
            colors[i] = (0xFF << 24) | (i << 16) | (i << 8) | i;
        }

        Bitmap.Config config = Bitmap.Config.RGB_565;
        Bitmap bm1 = Bitmap.createBitmap(colors, 10, 10, config);
        Bitmap bm2 = Bitmap.createBitmap(10, 10, config);

        for (int i = 0; i < 100; i++) {
            bm2.setPixel(i % 10, i / 10, colors[i]);
        }

        for (int i = 0; i < 100; i++) {
            assertEquals("setPixel", bm1.getPixel(i % 10, i / 10),
                         bm2.getPixel(i % 10, i / 10));
        }
    }

    private static int computePrePostMul(int alpha, int comp) {
        if (alpha == 0) {
            return 0;
        }
        int premul = Math.round(alpha * comp / 255.f);
        int unpre = Math.round(255.0f * premul / alpha);
        return unpre;
    }

    @SmallTest
    public void testSetPixelsWithNonOpaqueAlpha() throws Exception {
        int[] colors = new int[256];
        for (int i = 0; i < 256; i++) {
            colors[i] = (i << 24) | (0xFF << 16) | (0x80 << 8) | 0;
        }

        Bitmap.Config config = Bitmap.Config.ARGB_8888;

        // create a bitmap with the color array specified
        Bitmap bm1 = Bitmap.createBitmap(colors, 16, 16, config);

        // create a bitmap with no colors, but then call setPixels
        Bitmap bm2 = Bitmap.createBitmap(16, 16, config);
        bm2.setPixels(colors, 0, 16, 0, 0, 16, 16);

        // now check that we did a good job returning the unpremultiplied alpha
        final int tolerance = 1;
        for (int i = 0; i < 256; i++) {
            int c0 = colors[i];
            int c1 = bm1.getPixel(i % 16, i / 16);
            int c2 = bm2.getPixel(i % 16, i / 16);

            // these two should always be identical
            assertEquals("getPixel", c1, c2);

            // comparing the original (c0) with the returned color is tricky,
            // since it gets premultiplied during the set(), and unpremultiplied
            // by the get().
            int a0 = Color.alpha(c0);
            int a1 = Color.alpha(c1);
            assertEquals("alpha", a0, a1);

            int r0 = Color.red(c0);
            int r1 = Color.red(c1);
            int rr = computePrePostMul(a0, r0);
            assertTrue("red", Math.abs(rr - r1) <= tolerance);

            int g0 = Color.green(c0);
            int g1 = Color.green(c1);
            int gg = computePrePostMul(a0, g0);
            assertTrue("green", Math.abs(gg - g1) <= tolerance);

            int b0 = Color.blue(c0);
            int b1 = Color.blue(c1);
            int bb = computePrePostMul(a0, b0);
            assertTrue("blue", Math.abs(bb - b1) <= tolerance);

            if (false) {
                int cc = Color.argb(a0, rr, gg, bb);
                android.util.Log.d("skia", "original " + Integer.toHexString(c0) +
                                " set+get " + Integer.toHexString(c1) +
                               " local " + Integer.toHexString(cc));
            }
        }
    }

    @SmallTest
    public void testCreateHardwareBitmapFromGraphicBuffer() {
        GraphicBuffer buffer = GraphicBuffer.create(10, 10, PixelFormat.RGBA_8888,
                GraphicBuffer.USAGE_HW_TEXTURE | GraphicBuffer.USAGE_SOFTWARE_MASK);
        Canvas canvas = buffer.lockCanvas();
        canvas.drawColor(Color.YELLOW);
        buffer.unlockCanvasAndPost(canvas);
        Bitmap hardwareBitmap = Bitmap.createHardwareBitmap(buffer);
        assertTrue(hardwareBitmap.isPremultiplied());
        assertFalse(hardwareBitmap.isMutable());
    }
}
