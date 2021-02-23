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

package com.android.internal.graphics.palette;

/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.graphics.Color;
import android.util.TimingLogger;

import com.android.internal.graphics.palette.Palette.Swatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Copied from: frameworks/support/v7/palette/src/main/java/android/support/v7/
 * graphics/ColorCutQuantizer.java
 *
 * An color quantizer based on the Median-cut algorithm, but optimized for picking out distinct
 * colors rather than representation colors.
 *
 * The color space is represented as a 3-dimensional cube with each dimension being an RGB
 * component. The cube is then repeatedly divided until we have reduced the color space to the
 * requested number of colors. An average color is then generated from each cube.
 *
 * What makes this different to median-cut is that median-cut divided cubes so that all of the cubes
 * have roughly the same population, where this quantizer divides boxes based on their color volume.
 * This means that the color space is divided into distinct colors, rather than representative
 * colors.
 */
final class ColorCutQuantizer implements Quantizer {

    private static final String LOG_TAG = "ColorCutQuantizer";
    private static final boolean LOG_TIMINGS = false;

    static final int COMPONENT_RED = -3;
    static final int COMPONENT_GREEN = -2;
    static final int COMPONENT_BLUE = -1;

    private static final int QUANTIZE_WORD_WIDTH = 5;
    private static final int QUANTIZE_WORD_MASK = (1 << QUANTIZE_WORD_WIDTH) - 1;

    int[] mColors;
    int[] mHistogram;
    List<Swatch> mQuantizedColors;
    TimingLogger mTimingLogger;

    private final float[] mTempHsl = new float[3];

    /**
     * Execute color quantization.
     *
     * @param pixels    histogram representing an image's pixel data
     * @param maxColors The maximum number of colors that should be in the result palette.
     */
    public void quantize(final int[] pixels, final int maxColors) {
        mTimingLogger = LOG_TIMINGS ? new TimingLogger(LOG_TAG, "Creation") : null;

        final int[] hist = mHistogram = new int[1 << (QUANTIZE_WORD_WIDTH * 3)];
        for (int i = 0; i < pixels.length; i++) {
            final int quantizedColor = quantizeFromRgb888(pixels[i]);
            // Now update the pixel value to the quantized value
            pixels[i] = quantizedColor;
            // And update the histogram
            hist[quantizedColor]++;
        }

        if (LOG_TIMINGS) {
            mTimingLogger.addSplit("Histogram created");
        }

        // Now let's count the number of distinct colors
        int distinctColorCount = 0;
        for (int color = 0; color < hist.length; color++) {
            if (hist[color] > 0) {
                // If the color has population, increase the distinct color count
                distinctColorCount++;
            }
        }

        if (LOG_TIMINGS) {
            mTimingLogger.addSplit("Filtered colors and distinct colors counted");
        }

        // Now lets go through create an array consisting of only distinct colors
        final int[] colors = mColors = new int[distinctColorCount];
        int distinctColorIndex = 0;
        for (int color = 0; color < hist.length; color++) {
            if (hist[color] > 0) {
                colors[distinctColorIndex++] = color;
            }
        }

        if (LOG_TIMINGS) {
            mTimingLogger.addSplit("Distinct colors copied into array");
        }

        if (distinctColorCount <= maxColors) {
            // The image has fewer colors than the maximum requested, so just return the colors
            mQuantizedColors = new ArrayList<>();
            for (int color : colors) {
                mQuantizedColors.add(new Swatch(approximateToRgb888(color), hist[color]));
            }

            if (LOG_TIMINGS) {
                mTimingLogger.addSplit("Too few colors present. Copied to Swatches");
                mTimingLogger.dumpToLog();
            }
        } else {
            // We need use quantization to reduce the number of colors
            mQuantizedColors = quantizePixels(maxColors);

            if (LOG_TIMINGS) {
                mTimingLogger.addSplit("Quantized colors computed");
                mTimingLogger.dumpToLog();
            }
        }
    }

    /**
     * @return the list of quantized colors
     */
    public List<Swatch> getQuantizedColors() {
        return mQuantizedColors;
    }

    private List<Swatch> quantizePixels(int maxColors) {
        // Create the priority queue which is sorted by volume descending. This means we always
        // split the largest box in the queue
        final PriorityQueue<Vbox> pq = new PriorityQueue<>(maxColors, VBOX_COMPARATOR_VOLUME);

        // To start, offer a box which contains all of the colors
        pq.offer(new Vbox(0, mColors.length - 1));

        // Now go through the boxes, splitting them until we have reached maxColors or there are no
        // more boxes to split
        splitBoxes(pq, maxColors);

        // Finally, return the average colors of the color boxes
        return generateAverageColors(pq);
    }

    /**
     * Iterate through the {@link java.util.Queue}, popping
     * {@link ColorCutQuantizer.Vbox} objects from the queue
     * and splitting them. Once split, the new box and the remaining box are offered back to the
     * queue.
     *
     * @param queue   {@link java.util.PriorityQueue} to poll for boxes
     * @param maxSize Maximum amount of boxes to split
     */
    private void splitBoxes(final PriorityQueue<Vbox> queue, final int maxSize) {
        while (queue.size() < maxSize) {
            final Vbox vbox = queue.poll();

            if (vbox != null && vbox.canSplit()) {
                // First split the box, and offer the result
                queue.offer(vbox.splitBox());

                if (LOG_TIMINGS) {
                    mTimingLogger.addSplit("Box split");
                }
                // Then offer the box back
                queue.offer(vbox);
            } else {
                if (LOG_TIMINGS) {
                    mTimingLogger.addSplit("All boxes split");
                }
                // If we get here then there are no more boxes to split, so return
                return;
            }
        }
    }

    private List<Swatch> generateAverageColors(Collection<Vbox> vboxes) {
        ArrayList<Swatch> colors = new ArrayList<>(vboxes.size());
        for (Vbox vbox : vboxes) {
            Swatch swatch = vbox.getAverageColor();
            colors.add(swatch);
        }
        return colors;
    }

    /**
     * Represents a tightly fitting box around a color space.
     */
    private class Vbox {
        // lower and upper index are inclusive
        private final int mLowerIndex;
        private int mUpperIndex;
        // Population of colors within this box
        private int mPopulation;

        private int mMinRed, mMaxRed;
        private int mMinGreen, mMaxGreen;
        private int mMinBlue, mMaxBlue;

        Vbox(int lowerIndex, int upperIndex) {
            mLowerIndex = lowerIndex;
            mUpperIndex = upperIndex;
            fitBox();
        }

        final int getVolume() {
            return (mMaxRed - mMinRed + 1) * (mMaxGreen - mMinGreen + 1) *
                    (mMaxBlue - mMinBlue + 1);
        }

        final boolean canSplit() {
            return getColorCount() > 1;
        }

        final int getColorCount() {
            return 1 + mUpperIndex - mLowerIndex;
        }

        /**
         * Recomputes the boundaries of this box to tightly fit the colors within the box.
         */
        final void fitBox() {
            final int[] colors = mColors;
            final int[] hist = mHistogram;

            // Reset the min and max to opposite values
            int minRed, minGreen, minBlue;
            minRed = minGreen = minBlue = Integer.MAX_VALUE;
            int maxRed, maxGreen, maxBlue;
            maxRed = maxGreen = maxBlue = Integer.MIN_VALUE;
            int count = 0;

            for (int i = mLowerIndex; i <= mUpperIndex; i++) {
                final int color = colors[i];
                count += hist[color];

                final int r = quantizedRed(color);
                final int g = quantizedGreen(color);
                final int b = quantizedBlue(color);
                if (r > maxRed) {
                    maxRed = r;
                }
                if (r < minRed) {
                    minRed = r;
                }
                if (g > maxGreen) {
                    maxGreen = g;
                }
                if (g < minGreen) {
                    minGreen = g;
                }
                if (b > maxBlue) {
                    maxBlue = b;
                }
                if (b < minBlue) {
                    minBlue = b;
                }
            }

            mMinRed = minRed;
            mMaxRed = maxRed;
            mMinGreen = minGreen;
            mMaxGreen = maxGreen;
            mMinBlue = minBlue;
            mMaxBlue = maxBlue;
            mPopulation = count;
        }

        /**
         * Split this color box at the mid-point along its longest dimension
         *
         * @return the new ColorBox
         */
        final Vbox splitBox() {
            if (!canSplit()) {
                throw new IllegalStateException("Can not split a box with only 1 color");
            }

            // find median along the longest dimension
            final int splitPoint = findSplitPoint();

            Vbox newBox = new Vbox(splitPoint + 1, mUpperIndex);

            // Now change this box's upperIndex and recompute the color boundaries
            mUpperIndex = splitPoint;
            fitBox();

            return newBox;
        }

        /**
         * @return the dimension which this box is largest in
         */
        final int getLongestColorDimension() {
            final int redLength = mMaxRed - mMinRed;
            final int greenLength = mMaxGreen - mMinGreen;
            final int blueLength = mMaxBlue - mMinBlue;

            if (redLength >= greenLength && redLength >= blueLength) {
                return COMPONENT_RED;
            } else if (greenLength >= redLength && greenLength >= blueLength) {
                return COMPONENT_GREEN;
            } else {
                return COMPONENT_BLUE;
            }
        }

        /**
         * Finds the point within this box's lowerIndex and upperIndex index of where to split.
         *
         * This is calculated by finding the longest color dimension, and then sorting the
         * sub-array based on that dimension value in each color. The colors are then iterated over
         * until a color is found with at least the midpoint of the whole box's dimension midpoint.
         *
         * @return the index of the colors array to split from
         */
        final int findSplitPoint() {
            final int longestDimension = getLongestColorDimension();
            final int[] colors = mColors;
            final int[] hist = mHistogram;

            // We need to sort the colors in this box based on the longest color dimension.
            // As we can't use a Comparator to define the sort logic, we modify each color so that
            // its most significant is the desired dimension
            modifySignificantOctet(colors, longestDimension, mLowerIndex, mUpperIndex);

            // Now sort... Arrays.sort uses a exclusive toIndex so we need to add 1
            Arrays.sort(colors, mLowerIndex, mUpperIndex + 1);

            // Now revert all of the colors so that they are packed as RGB again
            modifySignificantOctet(colors, longestDimension, mLowerIndex, mUpperIndex);

            final int midPoint = mPopulation / 2;
            for (int i = mLowerIndex, count = 0; i <= mUpperIndex; i++) {
                count += hist[colors[i]];
                if (count >= midPoint) {
                    // we never want to split on the upperIndex, as this will result in the same
                    // box
                    return Math.min(mUpperIndex - 1, i);
                }
            }

            return mLowerIndex;
        }

        /**
         * @return the average color of this box.
         */
        final Swatch getAverageColor() {
            final int[] colors = mColors;
            final int[] hist = mHistogram;
            int redSum = 0;
            int greenSum = 0;
            int blueSum = 0;
            int totalPopulation = 0;

            for (int i = mLowerIndex; i <= mUpperIndex; i++) {
                final int color = colors[i];
                final int colorPopulation = hist[color];

                totalPopulation += colorPopulation;
                redSum += colorPopulation * quantizedRed(color);
                greenSum += colorPopulation * quantizedGreen(color);
                blueSum += colorPopulation * quantizedBlue(color);
            }

            final int redMean = Math.round(redSum / (float) totalPopulation);
            final int greenMean = Math.round(greenSum / (float) totalPopulation);
            final int blueMean = Math.round(blueSum / (float) totalPopulation);

            return new Swatch(approximateToRgb888(redMean, greenMean, blueMean), totalPopulation);
        }
    }

    /**
     * Modify the significant octet in a packed color int. Allows sorting based on the value of a
     * single color component. This relies on all components being the same word size.
     *
     * @see Vbox#findSplitPoint()
     */
    static void modifySignificantOctet(final int[] a, final int dimension,
            final int lower, final int upper) {
        switch (dimension) {
            case COMPONENT_RED:
                // Already in RGB, no need to do anything
                break;
            case COMPONENT_GREEN:
                // We need to do a RGB to GRB swap, or vice-versa
                for (int i = lower; i <= upper; i++) {
                    final int color = a[i];
                    a[i] = quantizedGreen(color) << (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)
                            | quantizedRed(color) << QUANTIZE_WORD_WIDTH
                            | quantizedBlue(color);
                }
                break;
            case COMPONENT_BLUE:
                // We need to do a RGB to BGR swap, or vice-versa
                for (int i = lower; i <= upper; i++) {
                    final int color = a[i];
                    a[i] = quantizedBlue(color) << (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)
                            | quantizedGreen(color) << QUANTIZE_WORD_WIDTH
                            | quantizedRed(color);
                }
                break;
        }
    }

    /**
     * Comparator which sorts {@link Vbox} instances based on their volume, in descending order
     */
    private static final Comparator<Vbox> VBOX_COMPARATOR_VOLUME = new Comparator<Vbox>() {
        @Override
        public int compare(Vbox lhs, Vbox rhs) {
            return rhs.getVolume() - lhs.getVolume();
        }
    };

    /**
     * Quantized a RGB888 value to have a word width of {@value #QUANTIZE_WORD_WIDTH}.
     */
    private static int quantizeFromRgb888(int color) {
        int r = modifyWordWidth(Color.red(color), 8, QUANTIZE_WORD_WIDTH);
        int g = modifyWordWidth(Color.green(color), 8, QUANTIZE_WORD_WIDTH);
        int b = modifyWordWidth(Color.blue(color), 8, QUANTIZE_WORD_WIDTH);
        return r << (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH) | g << QUANTIZE_WORD_WIDTH | b;
    }

    /**
     * Quantized RGB888 values to have a word width of {@value #QUANTIZE_WORD_WIDTH}.
     */
    static int approximateToRgb888(int r, int g, int b) {
        return Color.rgb(modifyWordWidth(r, QUANTIZE_WORD_WIDTH, 8),
                modifyWordWidth(g, QUANTIZE_WORD_WIDTH, 8),
                modifyWordWidth(b, QUANTIZE_WORD_WIDTH, 8));
    }

    private static int approximateToRgb888(int color) {
        return approximateToRgb888(quantizedRed(color), quantizedGreen(color),
                quantizedBlue(color));
    }

    /**
     * @return red component of the quantized color
     */
    static int quantizedRed(int color) {
        return (color >> (QUANTIZE_WORD_WIDTH + QUANTIZE_WORD_WIDTH)) & QUANTIZE_WORD_MASK;
    }

    /**
     * @return green component of a quantized color
     */
    static int quantizedGreen(int color) {
        return (color >> QUANTIZE_WORD_WIDTH) & QUANTIZE_WORD_MASK;
    }

    /**
     * @return blue component of a quantized color
     */
    static int quantizedBlue(int color) {
        return color & QUANTIZE_WORD_MASK;
    }

    private static int modifyWordWidth(int value, int currentWidth, int targetWidth) {
        final int newValue;
        if (targetWidth > currentWidth) {
            // If we're approximating up in word width, we'll shift up
            newValue = value << (targetWidth - currentWidth);
        } else {
            // Else, we will just shift and keep the MSB
            newValue = value >> (currentWidth - targetWidth);
        }
        return newValue & ((1 << targetWidth) - 1);
    }

}