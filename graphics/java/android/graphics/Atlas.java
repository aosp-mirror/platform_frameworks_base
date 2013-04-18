/*
 * Copyright (C) 2013 The Android Open Source Project
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

/**
 * @hide
 */
public class Atlas {
    /**
     * This flag indicates whether the packing algorithm will attempt
     * to rotate entries to make them fit better in the atlas.
     */
    public static final int FLAG_ALLOW_ROTATIONS = 0x1;
    /**
     * This flag indicates whether the packing algorithm should leave
     * an empty 1 pixel wide border around each bitmap. This border can
     * be useful if the content of the atlas will be used in OpenGL using
     * bilinear filtering.
     */
    public static final int FLAG_ADD_PADDING = 0x2;
    /**
     * Default flags: allow rotations and add padding.
     */
    public static final int FLAG_DEFAULTS = FLAG_ADD_PADDING;

    /**
     * Each type defines a different packing algorithm that can
     * be used by an {@link Atlas}. The best algorithm to use
     * will depend on the source dataset and the dimensions of
     * the atlas.
     */
    public enum Type {
        SliceMinArea,
        SliceMaxArea,
        SliceShortAxis,
        SliceLongAxis
    }

    /**
     * Represents a bitmap packed in the atlas. Each entry has a location in
     * pixels in the atlas and a rotation flag. If the entry was rotated, the
     * bitmap must be rotated by 90 degrees (in either direction as long as
     * the origin remains the same) before being rendered into the atlas.
     */
    public static class Entry {
        /**
         * Location, in pixels, of the bitmap on the X axis in the atlas.
         */
        public int x;
        /**
         * Location, in pixels, of the bitmap on the Y axis in the atlas.
         */
        public int y;

        /**
         * If true, the bitmap must be rotated 90 degrees in the atlas.
         */
        public boolean rotated;
    }

    private final Policy mPolicy;

    /**
     * Creates a new atlas with the specified algorithm and dimensions
     * in pixels. Calling this constructor is equivalent to calling
     * {@link #Atlas(Atlas.Type, int, int, int)} with {@link #FLAG_DEFAULTS}.
     *
     * @param type The algorithm to use to pack rectangles in the atlas
     * @param width The width of the atlas in pixels
     * @param height The height of the atlas in pixels
     *
     * @see #Atlas(Atlas.Type, int, int, int)
     */
    public Atlas(Type type, int width, int height) {
        this(type, width, height, FLAG_DEFAULTS);
    }

    /**
     * Creates a new atlas with the specified algorithm and dimensions
     * in pixels. A set of flags can also be specified to control the
     * behavior of the atlas.
     *
     * @param type The algorithm to use to pack rectangles in the atlas
     * @param width The width of the atlas in pixels
     * @param height The height of the atlas in pixels
     * @param flags Optional flags to control the behavior of the atlas:
     *              {@link #FLAG_ADD_PADDING}, {@link #FLAG_ALLOW_ROTATIONS}
     *
     * @see #Atlas(Atlas.Type, int, int)
     */
    public Atlas(Type type, int width, int height, int flags) {
        mPolicy = findPolicy(type, width, height, flags);
    }

    /**
     * Packs a rectangle of the specified dimensions in this atlas.
     *
     * @param width The width of the rectangle to pack in the atlas
     * @param height The height of the rectangle to pack in the atlas
     *
     * @return An {@link Entry} instance if the rectangle was packed in
     *         the atlas, or null if the rectangle could not fit
     *
     * @see #pack(int, int, Atlas.Entry)
     */
    public Entry pack(int width, int height) {
        return pack(width, height, null);
    }

    /**
     * Packs a rectangle of the specified dimensions in this atlas.
     *
     * @param width The width of the rectangle to pack in the atlas
     * @param height The height of the rectangle to pack in the atlas
     * @param entry Out parameter that will be filled in with the location
     *              and attributes of the packed rectangle, can be null
     *
     * @return An {@link Entry} instance if the rectangle was packed in
     *         the atlas, or null if the rectangle could not fit
     *
     * @see #pack(int, int)
     */
    public Entry pack(int width, int height, Entry entry) {
        if (entry == null) entry = new Entry();
        return mPolicy.pack(width, height, entry);
    }

    private static Policy findPolicy(Type type, int width, int height, int flags) {
        switch (type) {
            case SliceMinArea:
                return new SlicePolicy(width, height, flags,
                        new SlicePolicy.MinAreaSplitDecision());
            case SliceMaxArea:
                return new SlicePolicy(width, height, flags,
                        new SlicePolicy.MaxAreaSplitDecision());
            case SliceShortAxis:
                return new SlicePolicy(width, height, flags,
                        new SlicePolicy.ShorterFreeAxisSplitDecision());
            case SliceLongAxis:
                return new SlicePolicy(width, height, flags,
                        new SlicePolicy.LongerFreeAxisSplitDecision());
        }
        return null;
    }

    /**
     * A policy defines how the atlas performs the packing operation.
     */
    private static abstract class Policy {
        abstract Entry pack(int width, int height, Entry entry);
    }

    /**
     * The Slice algorightm divides the remaining empty space either
     * horizontally or vertically after a bitmap is placed in the atlas.
     *
     * NOTE: the algorithm is explained below using a tree but is
     * implemented using a linked list instead for performance reasons.
     *
     * The algorithm starts with a single empty cell covering the entire
     * atlas:
     *
     *  -----------------------
     * |                       |
     * |                       |
     * |                       |
     * |      Empty space      |
     * |          (C0)         |
     * |                       |
     * |                       |
     * |                       |
     *  -----------------------
     *
     * The tree of cells looks like this:
     *
     * N0(free)
     *
     * The algorithm then places a bitmap B1, if possible:
     *
     *  -----------------------
     * |        |              |
     * |   B1   |              |
     * |        |              |
     * |--------               |
     * |                       |
     * |                       |
     * |                       |
     * |                       |
     *  -----------------------
     *
     *  After placing a bitmap in an empty cell, the algorithm splits
     *  the remaining space in two new empty cells. The split can occur
     *  vertically or horizontally (this is controlled by the "split
     *  decision" parameter of the algorithm.)
     *
     *  Here is for the instance the result of a vertical split:
     *
     *  -----------------------
     * |        |              |
     * |   B1   |              |
     * |        |              |
     * |--------|      C2      |
     * |        |              |
     * |        |              |
     * |   C1   |              |
     * |        |              |
     *  -----------------------
     *
     * The cells tree now looks like this:
     *
     *       C0(occupied)
     *           / \
     *          /   \
     *         /     \
     *        /       \
     *    C1(free)  C2(free)
     *
     * For each bitmap to place in the atlas, the Slice algorithm
     * will visit the free cells until it finds one where a bitmap can
     * fit. It will then split the now occupied cell and proceed onto
     * the next bitmap.
     */
    private static class SlicePolicy extends Policy {
        private final Cell mRoot = new Cell();

        private final SplitDecision mSplitDecision;

        private final boolean mAllowRotation;
        private final int mPadding;

        /**
         * A cell represents a sub-rectangle of the atlas. A cell is
         * a node in a linked list representing the available free
         * space in the atlas.
         */
        private static class Cell {
            int x;
            int y;

            int width;
            int height;

            Cell next;

            @Override
            public String toString() {
                return String.format("cell[x=%d y=%d width=%d height=%d", x, y, width, height);
            }
        }

        SlicePolicy(int width, int height, int flags, SplitDecision splitDecision) {
            mAllowRotation = (flags & FLAG_ALLOW_ROTATIONS) != 0;
            mPadding = (flags & FLAG_ADD_PADDING) != 0 ? 1 : 0;

            // The entire atlas is empty at first, minus padding
            Cell first = new Cell();
            first.x = first.y = mPadding;
            first.width = width - 2 * mPadding;
            first.height = height - 2 * mPadding;

            mRoot.next = first;
            mSplitDecision = splitDecision;
        }

        @Override
        Entry pack(int width, int height, Entry entry) {
            Cell cell = mRoot.next;
            Cell prev = mRoot;

            while (cell != null) {
                if (insert(cell, prev, width, height, entry)) {
                    return entry;
                }

                prev = cell;
                cell = cell.next;
            }

            return null;
        }

        /**
         * Defines how the remaining empty space should be split up:
         * vertically or horizontally.
         */
        private static interface SplitDecision {
            /**
             * Returns true if the remaining space defined by
             * <code>freeWidth</code> and <code>freeHeight</code>
             * should be split horizontally.
             *
             * @param freeWidth The rectWidth of the free space after packing a rectangle
             * @param freeHeight The rectHeight of the free space after packing a rectangle
             * @param rectWidth The rectWidth of the rectangle that was packed in a cell
             * @param rectHeight The rectHeight of the rectangle that was packed in a cell
             */
            boolean splitHorizontal(int freeWidth, int freeHeight,
                    int rectWidth, int rectHeight);
        }

        // Splits the free area horizontally to minimize the horizontal section area
        private static class MinAreaSplitDecision implements SplitDecision {
            @Override
            public boolean splitHorizontal(int freeWidth, int freeHeight,
                    int rectWidth, int rectHeight) {
                return rectWidth * freeHeight > freeWidth * rectHeight;
            }
        }

        // Splits the free area horizontally to maximize the horizontal section area
        private static class MaxAreaSplitDecision implements SplitDecision {
            @Override
            public boolean splitHorizontal(int freeWidth, int freeHeight,
                    int rectWidth, int rectHeight) {
                return rectWidth * freeHeight <= freeWidth * rectHeight;
            }
        }

        // Splits the free area horizontally if the horizontal axis is shorter
        private static class ShorterFreeAxisSplitDecision implements SplitDecision {
            @Override
            public boolean splitHorizontal(int freeWidth, int freeHeight,
                    int rectWidth, int rectHeight) {
                return freeWidth <= freeHeight;
            }
        }

        // Splits the free area horizontally if the vertical axis is shorter
        private static class LongerFreeAxisSplitDecision implements SplitDecision {
            @Override
            public boolean splitHorizontal(int freeWidth, int freeHeight,
                    int rectWidth, int rectHeight) {
                return freeWidth > freeHeight;
            }
        }

        /**
         * Attempts to pack a rectangle of specified dimensions in the available
         * empty space.
         *
         * @param cell The cell representing free space in which to pack the rectangle
         * @param prev The previous cell in the free space linked list
         * @param width The width of the rectangle to pack
         * @param height The height of the rectangle to pack
         * @param entry Stores the location of the packged rectangle, if it fits
         *
         * @return True if the rectangle was packed in the atlas, false otherwise
         */
        @SuppressWarnings("SuspiciousNameCombination")
        private boolean insert(Cell cell, Cell prev, int width, int height, Entry entry) {
            boolean rotated = false;

            // If the rectangle doesn't fit we'll try to rotate it
            // if possible before giving up
            if (cell.width < width || cell.height < height) {
                if (mAllowRotation) {
                    if (cell.width < height || cell.height < width) {
                        return false;
                    }

                    // Rotate the rectangle
                    int temp = width;
                    width = height;
                    height = temp;
                    rotated = true;
                } else {
                    return false;
                }
            }

            // Remaining free space after packing the rectangle
            int deltaWidth = cell.width - width;
            int deltaHeight = cell.height - height;

            // Split the remaining free space into two new cells
            Cell first = new Cell();
            Cell second = new Cell();

            first.x = cell.x + width + mPadding;
            first.y = cell.y;
            first.width = deltaWidth - mPadding;

            second.x = cell.x;
            second.y = cell.y + height + mPadding;
            second.height = deltaHeight - mPadding;

            if (mSplitDecision.splitHorizontal(deltaWidth, deltaHeight,
                    width, height)) {
                first.height = height;
                second.width = cell.width;
            } else {
                first.height = cell.height;
                second.width = width;

                // The order of the cells matters for efficient packing
                // We want to give priority to the cell chosen by the
                // split decision heuristic
                Cell temp = first;
                first = second;
                second = temp;
            }

            // Remove degenerate cases to keep the free list as small as possible
            if (first.width > 0 && first.height > 0) {
                prev.next = first;
                prev = first;
            }

            if (second.width > 0 && second.height > 0) {
                prev.next = second;
                second.next = cell.next;
            } else {
                prev.next = cell.next;
            }

            // The cell is now completely removed from the free list
            cell.next = null;

            // Return the location and rotation of the packed rectangle
            entry.x = cell.x;
            entry.y = cell.y;
            entry.rotated = rotated;

            return true;
        }
    }
}
