/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.graphics.palette;

import static java.lang.System.arraycopy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Wu's quantization algorithm is a box-cut quantizer that minimizes variance. It takes longer to
 * run than, say, median color cut, but provides the highest quality results currently known.
 *
 * Prefer `QuantizerCelebi`: coupled with Kmeans, this provides the best-known results for image
 * quantization.
 *
 * Seemingly all Wu implementations are based off of one C code snippet that cites a book from 1992
 * Graphics Gems vol. II, pp. 126-133. As a result, it is very hard to understand the mechanics of
 * the algorithm, beyond the commentary provided in the C code. Comments on the methods of this
 * class are avoided in favor of finding another implementation and reading the commentary there,
 * avoiding perpetuating the same incomplete and somewhat confusing commentary here.
 */
public final class WuQuantizer implements Quantizer {
    // A histogram of all the input colors is constructed. It has the shape of a
    // cube. The cube would be too large if it contained all 16 million colors:
    // historical best practice is to use 5 bits  of the 8 in each channel,
    // reducing the histogram to a volume of ~32,000.
    private static final int BITS = 5;
    private static final int MAX_INDEX = 32;
    private static final int SIDE_LENGTH = 33;
    private static final int TOTAL_SIZE = 35937;

    private int[] mWeights;
    private int[] mMomentsR;
    private int[] mMomentsG;
    private int[] mMomentsB;
    private double[] mMoments;
    private Box[] mCubes;
    private Palette mPalette;
    private int[] mColors;
    private Map<Integer, Integer> mInputPixelToCount;

    @Override
    public List<Palette.Swatch> getQuantizedColors() {
        return mPalette.getSwatches();
    }

    @Override
    public void quantize(@NonNull int[] pixels, int colorCount) {
        assert (pixels.length > 0);

        QuantizerMap quantizerMap = new QuantizerMap();
        quantizerMap.quantize(pixels, colorCount);
        mInputPixelToCount = quantizerMap.getColorToCount();
        // Extraction should not be run on using a color count higher than the number of colors
        // in the pixels. The algorithm doesn't expect that to be the case, unexpected results and
        // exceptions may occur.
        Set<Integer> uniqueColors = mInputPixelToCount.keySet();
        if (uniqueColors.size() <= colorCount) {
            mColors = new int[mInputPixelToCount.keySet().size()];
            int index = 0;
            for (int color : uniqueColors) {
                mColors[index++] = color;
            }
        } else {
            constructHistogram(mInputPixelToCount);
            createMoments();
            CreateBoxesResult createBoxesResult = createBoxes(colorCount);
            mColors = createResult(createBoxesResult.mResultCount);
        }

        List<Palette.Swatch> swatches = new ArrayList<>();
        for (int color : mColors) {
            swatches.add(new Palette.Swatch(color, 0));
        }
        mPalette = Palette.from(swatches);
    }

    @Nullable
    public int[] getColors() {
        return mColors;
    }

    /** Keys are color ints, values are the number of pixels in the image matching that color int */
    @Nullable
    public Map<Integer, Integer> inputPixelToCount() {
        return mInputPixelToCount;
    }

    private static int getIndex(int r, int g, int b) {
        return (r << 10) + (r << 6) + (g << 5) + r + g + b;
    }

    private void constructHistogram(Map<Integer, Integer> pixels) {
        mWeights = new int[TOTAL_SIZE];
        mMomentsR = new int[TOTAL_SIZE];
        mMomentsG = new int[TOTAL_SIZE];
        mMomentsB = new int[TOTAL_SIZE];
        mMoments = new double[TOTAL_SIZE];

        for (Map.Entry<Integer, Integer> pair : pixels.entrySet()) {
            int pixel = pair.getKey();
            int count = pair.getValue();
            int red = Color.red(pixel);
            int green = Color.green(pixel);
            int blue = Color.blue(pixel);
            int bitsToRemove = 8 - BITS;
            int iR = (red >> bitsToRemove) + 1;
            int iG = (green >> bitsToRemove) + 1;
            int iB = (blue >> bitsToRemove) + 1;
            int index = getIndex(iR, iG, iB);
            mWeights[index] += count;
            mMomentsR[index] += (red * count);
            mMomentsG[index] += (green * count);
            mMomentsB[index] += (blue * count);
            mMoments[index] += (count * ((red * red) + (green * green) + (blue * blue)));
        }
    }

    private void createMoments() {
        for (int r = 1; r < SIDE_LENGTH; ++r) {
            int[] area = new int[SIDE_LENGTH];
            int[] areaR = new int[SIDE_LENGTH];
            int[] areaG = new int[SIDE_LENGTH];
            int[] areaB = new int[SIDE_LENGTH];
            double[] area2 = new double[SIDE_LENGTH];

            for (int g = 1; g < SIDE_LENGTH; ++g) {
                int line = 0;
                int lineR = 0;
                int lineG = 0;
                int lineB = 0;

                double line2 = 0.0;
                for (int b = 1; b < SIDE_LENGTH; ++b) {
                    int index = getIndex(r, g, b);
                    line += mWeights[index];
                    lineR += mMomentsR[index];
                    lineG += mMomentsG[index];
                    lineB += mMomentsB[index];
                    line2 += mMoments[index];

                    area[b] += line;
                    areaR[b] += lineR;
                    areaG[b] += lineG;
                    areaB[b] += lineB;
                    area2[b] += line2;

                    int previousIndex = getIndex(r - 1, g, b);
                    mWeights[index] = mWeights[previousIndex] + area[b];
                    mMomentsR[index] = mMomentsR[previousIndex] + areaR[b];
                    mMomentsG[index] = mMomentsG[previousIndex] + areaG[b];
                    mMomentsB[index] = mMomentsB[previousIndex] + areaB[b];
                    mMoments[index] = mMoments[previousIndex] + area2[b];
                }
            }
        }
    }

    private CreateBoxesResult createBoxes(int maxColorCount) {
        mCubes = new Box[maxColorCount];
        for (int i = 0; i < maxColorCount; i++) {
            mCubes[i] = new Box();
        }
        double[] volumeVariance = new double[maxColorCount];
        Box firstBox = mCubes[0];
        firstBox.r1 = MAX_INDEX;
        firstBox.g1 = MAX_INDEX;
        firstBox.b1 = MAX_INDEX;

        int generatedColorCount = 0;
        int next = 0;

        for (int i = 1; i < maxColorCount; i++) {
            if (cut(mCubes[next], mCubes[i])) {
                volumeVariance[next] = (mCubes[next].vol > 1) ? variance(mCubes[next]) : 0.0;
                volumeVariance[i] = (mCubes[i].vol > 1) ? variance(mCubes[i]) : 0.0;
            } else {
                volumeVariance[next] = 0.0;
                i--;
            }

            next = 0;

            double temp = volumeVariance[0];
            for (int k = 1; k <= i; k++) {
                if (volumeVariance[k] > temp) {
                    temp = volumeVariance[k];
                    next = k;
                }
            }
            generatedColorCount = i + 1;
            if (temp <= 0.0) {
                break;
            }
        }

        return new CreateBoxesResult(maxColorCount, generatedColorCount);
    }

    private int[] createResult(int colorCount) {
        int[] colors = new int[colorCount];
        int nextAvailableIndex = 0;
        for (int i = 0; i < colorCount; ++i) {
            Box cube = mCubes[i];
            int weight = volume(cube, mWeights);
            if (weight > 0) {
                int r = (volume(cube, mMomentsR) / weight);
                int g = (volume(cube, mMomentsG) / weight);
                int b = (volume(cube, mMomentsB) / weight);
                int color = Color.rgb(r, g, b);
                colors[nextAvailableIndex++] = color;
            }
        }
        int[] resultArray = new int[nextAvailableIndex];
        arraycopy(colors, 0, resultArray, 0, nextAvailableIndex);
        return resultArray;
    }

    private double variance(Box cube) {
        int dr = volume(cube, mMomentsR);
        int dg = volume(cube, mMomentsG);
        int db = volume(cube, mMomentsB);
        double xx =
                mMoments[getIndex(cube.r1, cube.g1, cube.b1)]
                        - mMoments[getIndex(cube.r1, cube.g1, cube.b0)]
                        - mMoments[getIndex(cube.r1, cube.g0, cube.b1)]
                        + mMoments[getIndex(cube.r1, cube.g0, cube.b0)]
                        - mMoments[getIndex(cube.r0, cube.g1, cube.b1)]
                        + mMoments[getIndex(cube.r0, cube.g1, cube.b0)]
                        + mMoments[getIndex(cube.r0, cube.g0, cube.b1)]
                        - mMoments[getIndex(cube.r0, cube.g0, cube.b0)];

        int hypotenuse = (dr * dr + dg * dg + db * db);
        int volume2 = volume(cube, mWeights);
        double variance2 = xx - ((double) hypotenuse / (double) volume2);
        return variance2;
    }

    private boolean cut(Box one, Box two) {
        int wholeR = volume(one, mMomentsR);
        int wholeG = volume(one, mMomentsG);
        int wholeB = volume(one, mMomentsB);
        int wholeW = volume(one, mWeights);

        MaximizeResult maxRResult =
                maximize(one, Direction.RED, one.r0 + 1, one.r1, wholeR, wholeG, wholeB, wholeW);
        MaximizeResult maxGResult =
                maximize(one, Direction.GREEN, one.g0 + 1, one.g1, wholeR, wholeG, wholeB, wholeW);
        MaximizeResult maxBResult =
                maximize(one, Direction.BLUE, one.b0 + 1, one.b1, wholeR, wholeG, wholeB, wholeW);
        Direction cutDirection;
        double maxR = maxRResult.mMaximum;
        double maxG = maxGResult.mMaximum;
        double maxB = maxBResult.mMaximum;
        if (maxR >= maxG && maxR >= maxB) {
            if (maxRResult.mCutLocation < 0) {
                return false;
            }
            cutDirection = Direction.RED;
        } else if (maxG >= maxR && maxG >= maxB) {
            cutDirection = Direction.GREEN;
        } else {
            cutDirection = Direction.BLUE;
        }

        two.r1 = one.r1;
        two.g1 = one.g1;
        two.b1 = one.b1;

        switch (cutDirection) {
            case RED:
                one.r1 = maxRResult.mCutLocation;
                two.r0 = one.r1;
                two.g0 = one.g0;
                two.b0 = one.b0;
                break;
            case GREEN:
                one.g1 = maxGResult.mCutLocation;
                two.r0 = one.r0;
                two.g0 = one.g1;
                two.b0 = one.b0;
                break;
            case BLUE:
                one.b1 = maxBResult.mCutLocation;
                two.r0 = one.r0;
                two.g0 = one.g0;
                two.b0 = one.b1;
                break;
            default:
                throw new IllegalArgumentException("unexpected direction " + cutDirection);
        }

        one.vol = (one.r1 - one.r0) * (one.g1 - one.g0) * (one.b1 - one.b0);
        two.vol = (two.r1 - two.r0) * (two.g1 - two.g0) * (two.b1 - two.b0);

        return true;
    }

    private MaximizeResult maximize(
            Box cube,
            Direction direction,
            int first,
            int last,
            int wholeR,
            int wholeG,
            int wholeB,
            int wholeW) {
        int baseR = bottom(cube, direction, mMomentsR);
        int baseG = bottom(cube, direction, mMomentsG);
        int baseB = bottom(cube, direction, mMomentsB);
        int baseW = bottom(cube, direction, mWeights);

        double max = 0.0;
        int cut = -1;
        for (int i = first; i < last; i++) {
            int halfR = baseR + top(cube, direction, i, mMomentsR);
            int halfG = baseG + top(cube, direction, i, mMomentsG);
            int halfB = baseB + top(cube, direction, i, mMomentsB);
            int halfW = baseW + top(cube, direction, i, mWeights);

            if (halfW == 0) {
                continue;
            }
            double tempNumerator = halfR * halfR + halfG * halfG + halfB * halfB;
            double tempDenominator = halfW;
            double temp = tempNumerator / tempDenominator;

            halfR = wholeR - halfR;
            halfG = wholeG - halfG;
            halfB = wholeB - halfB;
            halfW = wholeW - halfW;
            if (halfW == 0) {
                continue;
            }

            tempNumerator = halfR * halfR + halfG * halfG + halfB * halfB;
            tempDenominator = halfW;
            temp += (tempNumerator / tempDenominator);
            if (temp > max) {
                max = temp;
                cut = i;
            }
        }
        return new MaximizeResult(cut, max);
    }

    private static int volume(Box cube, int[] moment) {
        return (moment[getIndex(cube.r1, cube.g1, cube.b1)]
                - moment[getIndex(cube.r1, cube.g1, cube.b0)]
                - moment[getIndex(cube.r1, cube.g0, cube.b1)]
                + moment[getIndex(cube.r1, cube.g0, cube.b0)]
                - moment[getIndex(cube.r0, cube.g1, cube.b1)]
                + moment[getIndex(cube.r0, cube.g1, cube.b0)]
                + moment[getIndex(cube.r0, cube.g0, cube.b1)]
                - moment[getIndex(cube.r0, cube.g0, cube.b0)]);
    }

    private static int bottom(Box cube, Direction direction, int[] moment) {
        switch (direction) {
            case RED:
                return -moment[getIndex(cube.r0, cube.g1, cube.b1)]
                        + moment[getIndex(cube.r0, cube.g1, cube.b0)]
                        + moment[getIndex(cube.r0, cube.g0, cube.b1)]
                        - moment[getIndex(cube.r0, cube.g0, cube.b0)];
            case GREEN:
                return -moment[getIndex(cube.r1, cube.g0, cube.b1)]
                        + moment[getIndex(cube.r1, cube.g0, cube.b0)]
                        + moment[getIndex(cube.r0, cube.g0, cube.b1)]
                        - moment[getIndex(cube.r0, cube.g0, cube.b0)];
            case BLUE:
                return -moment[getIndex(cube.r1, cube.g1, cube.b0)]
                        + moment[getIndex(cube.r1, cube.g0, cube.b0)]
                        + moment[getIndex(cube.r0, cube.g1, cube.b0)]
                        - moment[getIndex(cube.r0, cube.g0, cube.b0)];
            default:
                throw new IllegalArgumentException("unexpected direction " + direction);
        }
    }

    private static int top(Box cube, Direction direction, int position, int[] moment) {
        switch (direction) {
            case RED:
                return (moment[getIndex(position, cube.g1, cube.b1)]
                        - moment[getIndex(position, cube.g1, cube.b0)]
                        - moment[getIndex(position, cube.g0, cube.b1)]
                        + moment[getIndex(position, cube.g0, cube.b0)]);
            case GREEN:
                return (moment[getIndex(cube.r1, position, cube.b1)]
                        - moment[getIndex(cube.r1, position, cube.b0)]
                        - moment[getIndex(cube.r0, position, cube.b1)]
                        + moment[getIndex(cube.r0, position, cube.b0)]);
            case BLUE:
                return (moment[getIndex(cube.r1, cube.g1, position)]
                        - moment[getIndex(cube.r1, cube.g0, position)]
                        - moment[getIndex(cube.r0, cube.g1, position)]
                        + moment[getIndex(cube.r0, cube.g0, position)]);
            default:
                throw new IllegalArgumentException("unexpected direction " + direction);
        }
    }

    private enum Direction {
        RED,
        GREEN,
        BLUE
    }

    private static class MaximizeResult {
        // < 0 if cut impossible
        final int mCutLocation;
        final double mMaximum;

        MaximizeResult(int cut, double max) {
            mCutLocation = cut;
            mMaximum = max;
        }
    }

    private static class CreateBoxesResult {
        final int mRequestedCount;
        final int mResultCount;

        CreateBoxesResult(int requestedCount, int resultCount) {
            mRequestedCount = requestedCount;
            mResultCount = resultCount;
        }
    }

    private static class Box {
        public int r0 = 0;
        public int r1 = 0;
        public int g0 = 0;
        public int g1 = 0;
        public int b0 = 0;
        public int b1 = 0;
        public int vol = 0;
    }
}


