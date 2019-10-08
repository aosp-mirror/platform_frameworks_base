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
 * limitations under the License
 */

package android.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WallpaperColorsTest {

    @Test
    public void supportsDarkTextOverrideTest() {
        final Color color = Color.valueOf(Color.WHITE);
        // Default should not support dark text!
        WallpaperColors colors = new WallpaperColors(color, null, null, 0);
        Assert.assertTrue("Default behavior is not to support dark text.",
                (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0);

        // Override it
        colors = new WallpaperColors(color, null, null, WallpaperColors.HINT_SUPPORTS_DARK_TEXT);
        Assert.assertFalse("Forcing dark text support doesn't work.",
                (colors.getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) == 0);
    }

    /**
     * Sanity check to guarantee that white supports dark text and black doesn't
     */
    @Test
    public void colorHintsTest() {
        Bitmap image = Bitmap.createBitmap(30, 30, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);

        canvas.drawColor(Color.WHITE);
        int hints = WallpaperColors.fromBitmap(image).getColorHints();
        boolean supportsDarkText = (hints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        boolean supportsDarkTheme = (hints & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0;
        boolean fromBitmap = (hints & WallpaperColors.HINT_FROM_BITMAP) != 0;
        Assert.assertTrue("White surface should support dark text.", supportsDarkText);
        Assert.assertFalse("White surface shouldn't support dark theme.", supportsDarkTheme);
        Assert.assertTrue("From bitmap should be true if object was created "
                + "using WallpaperColors#fromBitmap.", fromBitmap);

        canvas.drawColor(Color.BLACK);
        hints = WallpaperColors.fromBitmap(image).getColorHints();
        supportsDarkText = (hints & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        supportsDarkTheme = (hints & WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0;
        Assert.assertFalse("Black surface shouldn't support dark text.", supportsDarkText);
        Assert.assertTrue("Black surface should support dark theme.", supportsDarkTheme);

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        canvas.drawColor(Color.WHITE);
        canvas.drawRect(0, 0, 8, 8, paint);
        supportsDarkText = (WallpaperColors.fromBitmap(image)
                .getColorHints() & WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0;
        Assert.assertFalse("Light surface shouldn't support dark text "
                + "when it contains dark pixels.", supportsDarkText);

        WallpaperColors colors = new WallpaperColors(Color.valueOf(Color.GREEN), null, null);
        fromBitmap = (colors.getColorHints() & WallpaperColors.HINT_FROM_BITMAP) != 0;
        Assert.assertFalse("Object created from public constructor should not contain "
                + "HINT_FROM_BITMAP.", fromBitmap);
    }

    /**
     * WallpaperColors should not recycle bitmaps that it didn't create.
     */
    @Test
    public void wallpaperRecycleBitmapTest() {
        Bitmap image = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        WallpaperColors.fromBitmap(image);
        Canvas canvas = new Canvas();
        // This would crash:
        canvas.drawBitmap(image, 0, 0, new Paint());
    }
}
