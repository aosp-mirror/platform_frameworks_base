/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.text;

import static android.text.Layout.Alignment.ALIGN_NORMAL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests DynamicLayout updateBlocks method.
 *
 * Requires disabling access checks in the vm since this calls package-private APIs.
 *
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DynamicLayoutBlocksTest {
    private DynamicLayout dl = new DynamicLayout("", new TextPaint(), 0, ALIGN_NORMAL, 0, 0, false);
    private static final int ___ = DynamicLayout.INVALID_BLOCK_INDEX;

    private int[] initialBlockEnds;
    private int[] initialBlockIndices;

    private void defineInitialState(int[] ends, int[] indices) {
        initialBlockEnds = ends;
        initialBlockIndices = indices;
        assertEquals(initialBlockEnds.length, initialBlockIndices.length);
    }

    public void printBlocks(String message) {
        System.out.print(message);
        for (int i = 0; i < dl.getNumberOfBlocks(); i++) {
            System.out.print("  " + Integer.toString(dl.getBlockEndLines()[i]));
        }
        System.out.println();
    }

    public void checkInvariants() {
        assertTrue(dl.getNumberOfBlocks() > 0);
        assertTrue(dl.getNumberOfBlocks() <= dl.getBlockEndLines().length);
        assertEquals(dl.getBlockEndLines().length, dl.getBlockIndices().length);

        for (int i = 1; i < dl.getNumberOfBlocks(); i++) {
            assertTrue(dl.getBlockEndLines()[i] > dl.getBlockEndLines()[i-1]);
        }
    }

    private void update(int startLine, int endLine, int newLineCount) {
        final int totalLines = initialBlockEnds[initialBlockEnds.length - 1]
                + newLineCount - endLine + startLine;
        dl.setBlocksDataForTest(
                initialBlockEnds, initialBlockIndices, initialBlockEnds.length, totalLines);
        checkInvariants();
        dl.updateBlocks(startLine, endLine, newLineCount);
    }

    private void assertState(int[] sizes, int[] indices) {
        checkInvariants();

        assertEquals(sizes.length, dl.getNumberOfBlocks());
        assertEquals(indices.length, dl.getNumberOfBlocks());

        int[] ends = new int[sizes.length];
        for (int i = 0; i < ends.length; i++) {
            ends[i] = i == 0 ? (sizes[0] == 0 ? 0 : sizes[0] - 1) : ends[i - 1] + sizes[i];
        }

        for (int i = 0; i < dl.getNumberOfBlocks(); i++) {
            assertEquals(ends[i], dl.getBlockEndLines()[i]);
            assertEquals(indices[i], dl.getBlockIndices()[i]);
        }
    }

    private void assertState(int[] sizes) {
        int[] ids = new int[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            ids[i] = DynamicLayout.INVALID_BLOCK_INDEX;
        }
        assertState(sizes, ids);
    }

    @Test
    public void testFrom0() {
        defineInitialState( new int[] { 0 }, new int[] { 123 });

        update(0, 0, 0);
        assertState( new int[] { 0 } );

        update(0, 0, 1);
        assertState( new int[] { 0 } );

        update(0, 0, 10);
        assertState( new int[] { 10 } );
    }

    @Test
    public void testFrom1ReplaceByEmpty() {
        defineInitialState( new int[] { 100 }, new int[] { 123 });

        update(0, 0, 0);
        assertState( new int[] { 100 } );

        update(0, 10, 0);
        assertState( new int[] { 90 } );

        update(0, 100, 0);
        assertState( new int[] { 0 } );

        update(20, 30, 0);
        assertState( new int[] { 20, 70 } );

        update(20, 20, 0);
        assertState( new int[] { 20, 80 } );

        update(40, 100, 0);
        assertState( new int[] { 40 } );

        update(100, 100, 0);
        assertState( new int[] { 100 } );
    }

    @Test
    public void testFrom1ReplaceFromFirstLine() {
        defineInitialState( new int[] { 100 }, new int[] { 123 });

        update(0, 0, 1);
        assertState( new int[] { 0, 100 } );

        update(0, 0, 10);
        assertState( new int[] { 10, 100 } );

        update(0, 30, 31);
        assertState( new int[] { 31, 70 } );

        update(0, 100, 20);
        assertState( new int[] { 20 } );
    }

    @Test
    public void testFrom1ReplaceFromCenter() {
        defineInitialState( new int[] { 100 }, new int[] { 123 });

        update(20, 20, 1);
        assertState( new int[] { 20, 1, 80 } );

        update(20, 20, 10);
        assertState( new int[] { 20, 10, 80 } );

        update(20, 30, 50);
        assertState( new int[] { 20, 50, 70 } );

        update(20, 100, 50);
        assertState( new int[] { 20, 50 } );
    }

    @Test
    public void testFrom1ReplaceFromEnd() {
        defineInitialState( new int[] { 100 }, new int[] { 123 });

        update(100, 100, 0);
        assertState( new int[] { 100 } );

        update(100, 100, 1);
        assertState( new int[] { 100, 1 } );

        update(100, 100, 10);
        assertState( new int[] { 100, 10 } );
    }

    @Test
    public void testFrom2ReplaceFromFirstLine() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(0, 4, 50);
        assertState( new int[] { 50, 10-4, 20-10 }, new int[] { ___, ___, 456 } );

        update(0, 10, 50);
        assertState( new int[] { 50, 20-10 }, new int[] { ___, 456 } );

        update(0, 15, 50);
        assertState( new int[] { 50, 20-15 }, new int[] { ___, ___ } );

        update(0, 20, 50);
        assertState( new int[] { 50 }, new int[] { ___ } );
    }

    @Test
    public void testFrom2ReplaceFromFirstBlock() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(3, 7, 50);
        assertState( new int[] { 3, 50, 10-7, 20-10 }, new int[] { ___, ___, ___, 456 } );

        update(3, 10, 50);
        assertState( new int[] { 3, 50, 20-10 }, new int[] { ___, ___, 456 } );

        update(3, 14, 50);
        assertState( new int[] { 3, 50, 20-14 }, new int[] { ___, ___, ___ } );

        update(3, 20, 50);
        assertState( new int[] { 3, 50 }, new int[] { ___, ___ } );
    }

    @Test
    public void testFrom2ReplaceFromBottomBoundary() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(10, 10, 50);
        assertState( new int[] { 10, 50, 20-10 }, new int[] { ___, ___, 456 } );

        update(10, 14, 50);
        assertState( new int[] { 10, 50, 20-14 }, new int[] { ___, ___, ___ } );

        update(10, 20, 50);
        assertState( new int[] { 10, 50 }, new int[] { ___, ___ } );
    }

    @Test
    public void testFrom2ReplaceFromTopBoundary() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(11, 11, 50);
        assertState( new int[] { 11, 50, 20-11 }, new int[] { 123, ___, ___ } );

        update(11, 14, 50);
        assertState( new int[] { 11, 50, 20-14 }, new int[] { 123, ___, ___ } );

        update(11, 20, 50);
        assertState( new int[] { 11, 50 }, new int[] { 123, ___ } );
    }

    @Test
    public void testFrom2ReplaceFromSecondBlock() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(14, 14, 50);
        assertState( new int[] { 11, 14-11, 50, 20-14 }, new int[] { 123, ___, ___, ___ } );

        update(14, 17, 50);
        assertState( new int[] { 11, 14-11, 50, 20-17 }, new int[] { 123, ___, ___, ___ } );

        update(14, 20, 50);
        assertState( new int[] { 11, 14-11, 50 }, new int[] { 123, ___, ___ } );
    }

    @Test
    public void testFrom2RemoveFromFirst() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(0, 4, 0);
        assertState( new int[] { 10-4, 20-10 }, new int[] { ___, 456 } );

        update(0, 10, 0);
        assertState( new int[] { 20-10 }, new int[] { 456 } );

        update(0, 14, 0);
        assertState( new int[] { 20-14 }, new int[] { ___ } );

        update(0, 20, 0);
        assertState( new int[] { 0 }, new int[] { ___ } );
    }

    @Test
    public void testFrom2RemoveFromFirstBlock() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(4, 7, 0);
        assertState( new int[] { 4, 10-7, 20-10 }, new int[] { ___, ___, 456 } );

        update(4, 10, 0);
        assertState( new int[] { 4, 20-10 }, new int[] { ___, 456 } );

        update(4, 14, 0);
        assertState( new int[] { 4, 20-14 }, new int[] { ___, ___ } );

        update(4, 20, 0);
        assertState( new int[] { 4 }, new int[] { ___ } );
    }

    @Test
    public void testFrom2RemoveFromSecondBlock() {
        defineInitialState( new int[] { 10, 20 }, new int[] { 123, 456 });

        update(14, 17, 0);
        assertState( new int[] { 11, 14-11, 20-17 }, new int[] { 123, ___, ___ } );

        update(14, 20, 0);
        assertState( new int[] { 11, 14-11 }, new int[] { 123, ___ } );
    }

    @Test
    public void testFrom3ReplaceFromFirstBlock() {
        defineInitialState( new int[] { 10, 30, 60 }, new int[] { 123, 456, 789 });

        update(3, 7, 50);
        assertState( new int[] { 3, 50, 10-7, 30-10, 60-30 }, new int[] { ___, ___, ___, 456, 789 } );

        update(3, 10, 50);
        assertState( new int[] { 3, 50, 30-10, 60-30 }, new int[] { ___, ___, 456, 789 } );

        update(3, 17, 50);
        assertState( new int[] { 3, 50, 30-17, 60-30 }, new int[] { ___, ___, ___, 789 } );

        update(3, 30, 50);
        assertState( new int[] { 3, 50, 60-30 }, new int[] { ___, ___, 789 } );

        update(3, 40, 50);
        assertState( new int[] { 3, 50, 60-40 }, new int[] { ___, ___, ___ } );

        update(3, 60, 50);
        assertState( new int[] { 3, 50 }, new int[] { ___, ___ } );
    }

    @Test
    public void testFrom3ReplaceFromSecondBlock() {
        defineInitialState( new int[] { 10, 30, 60 }, new int[] { 123, 456, 789 });

        update(13, 17, 50);
        assertState( new int[] { 11, 2, 50, 30-17, 60-30 }, new int[] { 123, ___, ___, ___, 789 } );

        update(13, 30, 50);
        assertState( new int[] { 11, 2, 50, 60-30 }, new int[] { 123, ___, ___, 789 } );

        update(13, 40, 50);
        assertState( new int[] { 11, 2, 50, 60-40 }, new int[] { 123, ___, ___, ___ } );

        update(13, 60, 50);
        assertState( new int[] { 11, 2, 50 }, new int[] { 123, ___, ___ } );
    }
}
