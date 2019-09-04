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

package android.graphics.drawable;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Path.Direction;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.test.AndroidTestCase;
import android.util.Log;
import android.util.PathParser;

import androidx.test.filters.LargeTest;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

@LargeTest
public class AdaptiveIconDrawableTest extends AndroidTestCase {

    public static final String TAG = AdaptiveIconDrawableTest.class.getSimpleName();
    public static void L(String s, Object... parts) {
        Log.d(TAG, (parts.length == 0) ? s : String.format(s, parts));
    }
    private Drawable mBackgroundDrawable;
    private Drawable mForegroundDrawable;
    private AdaptiveIconDrawable mIconDrawable;
    private File mDir;

    /**
     * When setBound isn't called before draw method is called.
     * Nothing is drawn.
     */
    @Test
    public void testDraw_withoutBounds() throws Exception {
        mBackgroundDrawable = new ColorDrawable(Color.BLUE);
        mForegroundDrawable = new ColorDrawable(Color.RED);
        mIconDrawable = new AdaptiveIconDrawable(mBackgroundDrawable, mForegroundDrawable);
        mDir = getContext().getExternalFilesDir(null);
        L("writing temp bitmaps to %s...", mDir);

        final Bitmap bm_test = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888);
        final Bitmap bm_org = bm_test.copy(Config.ARGB_8888, false);
        final Canvas can1 = new Canvas(bm_test);

        // Even when setBounds is not called, should not crash
        mIconDrawable.draw(can1);
        // Draws nothing! Hence same as original.
        if (!equalBitmaps(bm_test, bm_org)) {
            findBitmapDifferences(bm_test, bm_org);
            fail("bm differs, check " + mDir);
        }
    }

    /**
     * When setBound is called, translate accordingly.
     */
    @Test
    public void testDraw_withBounds() throws Exception {
        int dpi = 4 ;
        int top = 18 * dpi;
        int left = 18 * dpi;
        int right = 90 * dpi;
        int bottom = 90 * dpi;
        int width = right - left;
        int height = bottom - top;

        mIconDrawable = (AdaptiveIconDrawable) getContext().getResources().getDrawable(android.R.drawable.sym_def_app_icon);
        mDir = getContext().getExternalFilesDir(null);
        L("writing temp bitmaps to %s...", mDir);
        final Bitmap bm_org = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas can_org = new Canvas(bm_org);
        mIconDrawable.setBounds(0, 0, width, height);
        mIconDrawable.draw(can_org);

        // Tested bitmap is drawn from the adaptive icon drawable.
        final Bitmap bm_test = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas can_test = new Canvas(bm_test);

        mIconDrawable.setBounds(left, top, right, bottom);
        can_test.translate(-left, -top);
        mIconDrawable.draw(can_test);
        can_test.translate(left, top);


        bm_org.compress(Bitmap.CompressFormat.PNG, 100,
            new FileOutputStream(new File(mDir, "adaptive-bm-original.png")));
        bm_test.compress(Bitmap.CompressFormat.PNG, 100,
            new FileOutputStream(new File(mDir, "adaptive-bm-test.png")));
        Region region = new Region(new Rect(0, 0, width, height));

        Path circle = new Path();
        circle.addCircle(width / 2, height / 2,  (right - left)/2 -10 /* room for anti-alias */, Direction.CW);

        region.setPath(circle, region);
        if (!equalBitmaps(bm_test, bm_org, region)) {
            findBitmapDifferences(bm_test, bm_org);
            fail("bm differs, check " + mDir);
        }
    }

    /**
     * When setBound isn't called before getIconMask method is called.
     * default device config mask is returned.
     */
    @Test
    public void testGetIconMask_withoutBounds() throws Exception {
        mIconDrawable = new AdaptiveIconDrawable(mBackgroundDrawable, mForegroundDrawable);
        Path pathFromDrawable = mIconDrawable.getIconMask();
        Path pathFromDeviceConfig = PathParser.createPathFromPathData(
            Resources.getSystem().getString(com.android.internal.R.string.config_icon_mask));

        RectF boundFromDrawable = new RectF();
        pathFromDrawable.computeBounds(boundFromDrawable, true);

        RectF boundFromDeviceConfig = new RectF();
        pathFromDeviceConfig.computeBounds(boundFromDeviceConfig, true);

        double delta = 0.01;
        assertEquals("left", boundFromDrawable.left, boundFromDeviceConfig.left, delta);
        assertEquals("top", boundFromDrawable.top, boundFromDeviceConfig.top, delta);
        assertEquals("right", boundFromDrawable.right, boundFromDeviceConfig.right, delta);
        assertEquals("bottom", boundFromDrawable.bottom, boundFromDeviceConfig.bottom, delta);

        assertTrue("path from device config is convex.", pathFromDeviceConfig.isConvex());
        assertTrue("path from drawable is convex.", pathFromDrawable.isConvex());
    }

    @Test
    public void testGetIconMaskAfterSetBounds() throws Exception {
        int dpi = 4;
        int top = 18 * dpi;
        int left = 18 * dpi;
        int right = 90 * dpi;
        int bottom = 90 * dpi;

        mIconDrawable = new AdaptiveIconDrawable(mBackgroundDrawable, mForegroundDrawable);
        mIconDrawable.setBounds(left, top, right, bottom);
        RectF maskBounds = new RectF();

        mIconDrawable.getIconMask().computeBounds(maskBounds, true);

        double delta = 0.01;
        assertEquals("left", left, maskBounds.left, delta);
        assertEquals("top", top, maskBounds.top, delta);
        assertEquals("right", right, maskBounds.right, delta);
        assertEquals("bottom", bottom, maskBounds.bottom, delta);

        assertTrue(mIconDrawable.getIconMask().isConvex());
    }

    @Test
    public void testGetOutline_withBounds() throws Exception {
        int dpi = 4;
        int top = 18 * dpi;
        int left = 18 * dpi;
        int right = 90 * dpi;
        int bottom = 90 * dpi;

        mIconDrawable = new AdaptiveIconDrawable(mBackgroundDrawable, mForegroundDrawable);
        mIconDrawable.setBounds(left, top, right, bottom);
        Outline outline = new Outline();
        mIconDrawable.getOutline(outline);
        assertTrue("outline path should be convex", outline.mPath.isConvex());
    }

    @Test
    public void testSetAlpha() throws Exception {
        mIconDrawable = new AdaptiveIconDrawable(mBackgroundDrawable, mForegroundDrawable);
        mIconDrawable.setBounds(0, 0, 100, 100);

        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        mIconDrawable.draw(canvas);
        assertEquals(255, Color.alpha(bitmap.getPixel(50, 50)));

        mIconDrawable.setAlpha(200);
        bitmap.eraseColor(Color.TRANSPARENT);
        mIconDrawable.draw(canvas);
        assertEquals(200, Color.alpha(bitmap.getPixel(50, 50)));

        mIconDrawable.setAlpha(100);
        bitmap.eraseColor(Color.TRANSPARENT);
        mIconDrawable.draw(canvas);
        assertEquals(100, Color.alpha(bitmap.getPixel(50, 50)));
    }

    //
    // Utils
    //

    boolean equalBitmaps(Bitmap a, Bitmap b) {
      return equalBitmaps(a, b, null);
    }

    boolean equalBitmaps(Bitmap a, Bitmap b, Region region) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;

        final int w = a.getWidth();
        final int h = a.getHeight();
        int[] aPix = new int[w * h];
        int[] bPix = new int[w * h];

        if (region != null) {
            for (int i = 0; i < w; i++) {
                for (int j = 0; j < h; j++) {
                    int ra = (a.getPixel(i, j) >> 16) & 0xff;
                    int ga = (a.getPixel(i, j) >> 8) & 0xff;
                    int ba = a.getPixel(i, j) & 0xff;
                    int rb = (b.getPixel(i, j) >> 16) & 0xff;
                    int gb = (b.getPixel(i, j) >> 8) & 0xff;
                    int bb = b.getPixel(i, j) & 0xff;
                    if (region.contains(i, j) && a.getPixel(i, j) != b.getPixel(i, j) ) {
                        return false;
                    }
                }
            }
            return true;
        } else {
            a.getPixels(aPix, 0, w, 0, 0, w, h);
            b.getPixels(bPix, 0, w, 0, 0, w, h);
            return Arrays.equals(aPix, bPix);
        }
    }

    void findBitmapDifferences(Bitmap a, Bitmap b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            L("different sizes: %dx%d vs %dx%d",
                a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight());
            return;
        }

        final int w = a.getWidth();
        final int h = a.getHeight();
        int[] aPix = new int[w * h];
        int[] bPix = new int[w * h];

        a.getPixels(aPix, 0, w, 0, 0, w, h);
        b.getPixels(bPix, 0, w, 0, 0, w, h);

        L("bitmap a (%dx%d)", w, h);
        printBits(aPix, w, h);
        L("bitmap b (%dx%d)", w, h);
        printBits(bPix, w, h);

        StringBuffer sb = new StringBuffer("Different pixels: ");
        for (int i=0; i<w; i++) {
            for (int j=0; j<h; j++) {
                if (aPix[i+w*j] != bPix[i+w*j]) {
                    sb.append(" ").append(i).append(",").append(j).append("<")
                        .append(aPix[i+w*j]).append(",").append(bPix[i+w*j]).append(">");
                }
            }
        }
        L(sb.toString());
    }

    static void printBits(int[] a, int w, int h) {
        final StringBuilder sb = new StringBuilder();
        for (int i=0; i<w; i++) {
            for (int j=0; j<h; j++) {
                sb.append(colorToChar(a[i+w*j]));
            }
            sb.append('\n');
        }
        L(sb.toString());
    }

    static char colorToChar(int color) {
        int sum = ((color >> 16) & 0xff)
            + ((color >> 8)  & 0xff)
            + ((color)       & 0xff);
        return GRADIENT[sum * (GRADIENT.length-1) / (3*0xff)];
    }
    static final char[] GRADIENT = " .:;+=xX$#".toCharArray();
}
