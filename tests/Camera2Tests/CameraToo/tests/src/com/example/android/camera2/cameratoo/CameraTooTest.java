/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.camera2.cameratoo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.Image;
import android.os.Environment;
import android.util.Size;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import com.example.android.camera2.cameratoo.CameraTooActivity;
import org.junit.Test;

public class CameraTooTest {
    private <T> void assertComparatorEq(T lhs, T rhs, Comparator<T> rel) {
        assertEquals(String.format("%s should be equal to %s", lhs, rhs), rel.compare(lhs, rhs), 0);
        assertEquals(String.format("%s should be equal to %s (reverse check)", lhs, rhs),
                rel.compare(rhs, lhs), 0);
    }

    private <T> void assertComparatorLt(T lhs, T rhs, Comparator<T> rel) {
        assertTrue(String.format("%s should be less than %s", lhs, rhs), rel.compare(lhs, rhs) < 0);
        assertTrue(String.format("%s should be less than %s (reverse check)", lhs, rhs),
                rel.compare(rhs, lhs) > 0);
    }

    @Test
    public void compareSizesByArea() {
        Size empty = new Size(0, 0), fatAndFlat = new Size(100, 0), tallAndThin = new Size(0, 100);
        Size smallSquare = new Size(4, 4), horizRect = new Size(8, 2), vertRect = new Size(2, 8);
        Size largeSquare = new Size(5, 5);
        Comparator<Size> rel = new CameraTooActivity.CompareSizesByArea();

        assertComparatorEq(empty, fatAndFlat, rel);
        assertComparatorEq(empty, tallAndThin, rel);
        assertComparatorEq(fatAndFlat, empty, rel);
        assertComparatorEq(fatAndFlat, tallAndThin, rel);
        assertComparatorEq(tallAndThin, empty, rel);
        assertComparatorEq(tallAndThin, fatAndFlat, rel);

        assertComparatorEq(smallSquare, horizRect, rel);
        assertComparatorEq(smallSquare, vertRect, rel);
        assertComparatorEq(horizRect, smallSquare, rel);
        assertComparatorEq(horizRect, vertRect, rel);
        assertComparatorEq(vertRect, smallSquare, rel);
        assertComparatorEq(vertRect, horizRect, rel);

        assertComparatorLt(empty, smallSquare, rel);
        assertComparatorLt(empty, horizRect, rel);
        assertComparatorLt(empty, vertRect, rel);

        assertComparatorLt(fatAndFlat, smallSquare, rel);
        assertComparatorLt(fatAndFlat, horizRect, rel);
        assertComparatorLt(fatAndFlat, vertRect, rel);

        assertComparatorLt(tallAndThin, smallSquare, rel);
        assertComparatorLt(tallAndThin, horizRect, rel);
        assertComparatorLt(tallAndThin, vertRect, rel);

        assertComparatorLt(empty, largeSquare, rel);
        assertComparatorLt(fatAndFlat, largeSquare, rel);
        assertComparatorLt(tallAndThin, largeSquare, rel);
        assertComparatorLt(smallSquare, largeSquare, rel);
        assertComparatorLt(horizRect, largeSquare, rel);
        assertComparatorLt(vertRect, largeSquare, rel);
    }

    private void assertOptimalSize(Size[] options, int minWidth, int minHeight, Size expected) {
        Size verdict = CameraTooActivity.chooseBigEnoughSize(options, minWidth, minHeight);
        assertEquals(String.format("Expected optimal size %s but got %s", expected, verdict),
                verdict, expected);
    }

    @Test
    public void chooseBigEnoughSize() {
        Size empty = new Size(0, 0), fatAndFlat = new Size(100, 0), tallAndThin = new Size(0, 100);
        Size smallSquare = new Size(4, 4), horizRect = new Size(8, 2), vertRect = new Size(2, 8);
        Size largeSquare = new Size(5, 5);
        Size[] siz =
                { empty, fatAndFlat, tallAndThin, smallSquare, horizRect, vertRect, largeSquare };

        assertOptimalSize(siz, 0, 0, empty);

        assertOptimalSize(siz, 1, 0, fatAndFlat);
        assertOptimalSize(siz, 0, 1, tallAndThin);

        assertOptimalSize(siz, 4, 4, smallSquare);
        assertOptimalSize(siz, 1, 1, smallSquare);
        assertOptimalSize(siz, 2, 1, smallSquare);
        assertOptimalSize(siz, 1, 2, smallSquare);
        assertOptimalSize(siz, 3, 4, smallSquare);
        assertOptimalSize(siz, 4, 3, smallSquare);

        assertOptimalSize(siz, 8, 2, horizRect);
        assertOptimalSize(siz, 5, 1, horizRect);
        assertOptimalSize(siz, 5, 2, horizRect);

        assertOptimalSize(siz, 2, 8, vertRect);
        assertOptimalSize(siz, 1, 5, vertRect);
        assertOptimalSize(siz, 2, 5, vertRect);

        assertOptimalSize(siz, 5, 5, largeSquare);
        assertOptimalSize(siz, 3, 5, largeSquare);
        assertOptimalSize(siz, 5, 3, largeSquare);
    }

    private static final FilenameFilter OUTPUT_FILE_DECIDER = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String filename) {
            return filename.indexOf("cameratoo") == 0 &&
                    filename.indexOf(".jpg") == filename.length() - ".jpg".length();
        }};

    private static <T> Set<T> newlyAddedElements(Set<T> before, Set<T> after) {
        Set<T> result = new HashSet<T>(after);
        result.removeAll(before);
        return result;
    }

    @Test
    public void capturedImageSaver() throws FileNotFoundException, IOException {
        ByteBuffer buf = ByteBuffer.allocate(25);
        for(int index = 0; index < buf.capacity(); ++index)
            buf.put(index, (byte) index);

        Image.Plane plane = mock(Image.Plane.class);
        when(plane.getBuffer()).thenReturn(buf);
        when(plane.getPixelStride()).thenReturn(1);
        when(plane.getRowStride()).thenReturn(5);

        Image.Plane[] onlyPlaneThatMatters = { plane };
        Image image = mock(Image.class);
        when(image.getPlanes()).thenReturn(onlyPlaneThatMatters);
        when(image.getWidth()).thenReturn(5);
        when(image.getHeight()).thenReturn(5);

        File picturesFolder =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        Set<File> preListing =
                new HashSet<File>(Arrays.asList(picturesFolder.listFiles(OUTPUT_FILE_DECIDER)));

        CameraTooActivity.CapturedImageSaver saver =
                new CameraTooActivity.CapturedImageSaver(image);
        saver.run();

        Set<File> postListing =
                new HashSet<File>(Arrays.asList(picturesFolder.listFiles(OUTPUT_FILE_DECIDER)));
        Set<File> newFiles = newlyAddedElements(preListing, postListing);

        assertEquals(newFiles.size(), 1);

        File picture = newFiles.iterator().next();
        FileInputStream istream = new FileInputStream(picture);

        for(int count = 0; count < buf.capacity(); ++count) {
            assertEquals(istream.read(), buf.get(count));
        }
        assertEquals(istream.read(), -1);
        assertTrue(picture.delete());
    }
}
