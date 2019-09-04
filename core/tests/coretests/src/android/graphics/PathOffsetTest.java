/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap.Config;
import android.graphics.Path.Direction;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PathOffsetTest {

    private static final int SQUARE = 10;
    private static final int WIDTH = 100;
    private static final int HEIGHT = 100;
    private static final int START_X = 10;
    private static final int START_Y = 20;
    private static final int OFFSET_X = 30;
    private static final int OFFSET_Y = 40;

    @Test
    @SmallTest
    public void testPathOffset() {
        Path actualPath = new Path();
        actualPath.addRect(START_X, START_Y, START_X + SQUARE, START_Y + SQUARE, Direction.CW);
        assertTrue(actualPath.isSimplePath);
        actualPath.offset(OFFSET_X, OFFSET_Y);
        assertTrue(actualPath.isSimplePath);

        Path expectedPath = new Path();
        expectedPath.addRect(START_X + OFFSET_X, START_Y + OFFSET_Y, START_X + OFFSET_X + SQUARE,
                START_Y + OFFSET_Y + SQUARE, Direction.CW);

        assertPaths(actualPath, expectedPath);
    }

    @Test
    @SmallTest
    public void testPathOffsetWithDestination() {
        Path initialPath = new Path();
        initialPath.addRect(START_X, START_Y, START_X + SQUARE, START_Y + SQUARE, Direction.CW);
        Path actualPath = new Path();
        assertTrue(initialPath.isSimplePath);
        assertTrue(actualPath.isSimplePath);
        initialPath.offset(OFFSET_X, OFFSET_Y, actualPath);
        assertTrue(actualPath.isSimplePath);

        Path expectedPath = new Path();
        expectedPath.addRect(START_X + OFFSET_X, START_Y + OFFSET_Y, START_X + OFFSET_X + SQUARE,
                START_Y + OFFSET_Y + SQUARE, Direction.CW);

        assertPaths(actualPath, expectedPath);
    }

    private static void assertPaths(Path actual, Path expected) {
        Bitmap actualBitmap = drawAndGetBitmap(actual);
        Bitmap expectedBitmap = drawAndGetBitmap(expected);
        assertTrue(actualBitmap.sameAs(expectedBitmap));
    }

    private static Bitmap drawAndGetBitmap(Path path) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Config.ARGB_8888);
        bitmap.eraseColor(Color.BLACK);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawPath(path, paint);
        return bitmap;
    }

}
