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

import java.util.ArrayList;
import java.util.List;

// All reference Wu implementations are based on the original C code by Wu.
// Comments on methods are the same as in the original implementation, and the comment below
// is the original class header.

/**
 * Wu's Color Quantizer (v. 2) (see Graphics Gems vol. II, pp. 126-133) Author: Xiaolin Wu
 *
 * <p>Algorithm: Greedy orthogonal bipartition of RGB space for variance minimization aided by
 * inclusion-exclusion tricks. For speed no nearest neighbor search is done. Slightly better
 * performance can be expected by more sophisticated but more expensive versions.
 */
public class WuQuantizer implements Quantizer {
    private static final int MAX_COLORS = 256;
    private static final int RED = 2;
    private static final int GREEN = 1;
    private static final int BLUE = 0;

    private static final int QUANT_SIZE = 33;
    private final List<Palette.Swatch> mSwatches = new ArrayList<>();

    @Override
    public List<Palette.Swatch> getQuantizedColors() {
        return mSwatches;
    }

    private static final class Box {
        int mR0; /* min value, exclusive */
        int mR1; /* max value, inclusive */
        int mG0;
        int mG1;
        int mB0;
        int mB1;
        int mVol;
    }

    private final int mSize; /* image size, in bytes. */
    private int mMaxColors;
    private int[] mQadd;
    private final int[] mPixels;

    private final double[][][] mM2 = new double[QUANT_SIZE][QUANT_SIZE][QUANT_SIZE];
    private final long[][][] mWt = new long[QUANT_SIZE][QUANT_SIZE][QUANT_SIZE];
    private final long[][][] mMr = new long[QUANT_SIZE][QUANT_SIZE][QUANT_SIZE];
    private final long[][][] mMg = new long[QUANT_SIZE][QUANT_SIZE][QUANT_SIZE];
    private final long[][][] mMb = new long[QUANT_SIZE][QUANT_SIZE][QUANT_SIZE];

    public WuQuantizer(int[] pixels, int maxColorCount) {
        if (pixels == null) {
            pixels = new int[]{};
        }
        this.mPixels = pixels;
        this.mSize = pixels.length;
    }

    @Override
    public void quantize(int[] colors, int maxColorCount) {
        // All of the sample Wu implementations are reimplementations of a snippet of C code from
        // the early 90s. They all cap the maximum # of colors at 256, and it is impossible to tell
        // if this is a requirement, a consequence of QUANT_SIZE, or arbitrary.
        this.mMaxColors = Math.min(MAX_COLORS, maxColorCount);
        Box[] cube = new Box[mMaxColors];
        int red, green, blue;

        int next, i, k;
        long weight;
        double[] vv = new double[mMaxColors];
        double temp;

        compute3DHistogram(mWt, mMr, mMg, mMb, mM2);
        computeMoments(mWt, mMr, mMg, mMb, mM2);

        for (i = 0; i < mMaxColors; i++) {
            cube[i] = new Box();
        }

        cube[0].mR0 = cube[0].mG0 = cube[0].mB0 = 0;
        cube[0].mR1 = cube[0].mG1 = cube[0].mB1 = QUANT_SIZE - 1;
        next = 0;

        for (i = 1; i < mMaxColors; ++i) {
            if (cut(cube[next], cube[i])) {
                vv[next] = (cube[next].mVol > 1) ? getVariance(cube[next]) : 0.0f;
                vv[i] = (cube[i].mVol > 1) ? getVariance(cube[i]) : 0.0f;
            } else {
                vv[next] = 0.0f;
                i--;
            }
            next = 0;
            temp = vv[0];
            for (k = 1; k <= i; ++k) {
                if (vv[k] > temp) {
                    temp = vv[k];
                    next = k;
                }
            }
            if (temp <= 0.0f) {
                break;
            }
        }

        // If extraction is run on a set of pixels whose count is less than the
        // number of max colors, then colors.length < max colors, and accesses
        // to colors[index] inside the for loop throw an ArrayOutOfBoundsException.
        int numColorsToCreate = (int) Math.min(mMaxColors, colors.length);
        for (k = 0; k < numColorsToCreate; ++k) {
            weight = getVolume(cube[k], mWt);
            if (weight > 0) {
                red = (int) (getVolume(cube[k], mMr) / weight);
                green = (int) (getVolume(cube[k], mMg) / weight);
                blue = (int) (getVolume(cube[k], mMb) / weight);
                colors[k] = ((red & 0x0ff) << 16) | ((green & 0x0ff) << 8) | (blue & 0x0ff);
            } else {
                colors[k] = 0;
            }
        }

        int bitsPerPixel = 0;
        while ((1 << bitsPerPixel) < mMaxColors) {
            bitsPerPixel++;
        }

        List<Palette.Swatch> swatches = new ArrayList<>();
        for (int l = 0; l < k; l++) {
            int pixel = colors[l];
            if (pixel == 0) {
                continue;
            }
            swatches.add(new Palette.Swatch(pixel, 0));
        }
        mSwatches.clear();
        mSwatches.addAll(swatches);
    }

    /* Histogram is in elements 1..HISTSIZE along each axis,
     * element 0 is for base or marginal value
     * NB: these must start out 0!
     */
    private void compute3DHistogram(
            long[][][] vwt, long[][][] vmr, long[][][] vmg, long[][][] vmb, double[][][] m2) {
        // build 3-D color histogram of counts, r/g/b, and c^2
        int r, g, b;
        int i;
        int inr;
        int ing;
        int inb;
        int[] table = new int[256];

        for (i = 0; i < 256; i++) {
            table[i] = i * i;
        }

        mQadd = new int[mSize];

        for (i = 0; i < mSize; ++i) {
            int rgb = mPixels[i];
            // Skip less than opaque pixels. They're not meaningful in the context of palette
            // generation for UI schemes.
            if ((rgb >>> 24) < 0xff) {
                continue;
            }
            r = ((rgb >> 16) & 0xff);
            g = ((rgb >> 8) & 0xff);
            b = (rgb & 0xff);
            inr = (r >> 3) + 1;
            ing = (g >> 3) + 1;
            inb = (b >> 3) + 1;
            mQadd[i] = (inr << 10) + (inr << 6) + inr + (ing << 5) + ing + inb;
            /*[inr][ing][inb]*/
            ++vwt[inr][ing][inb];
            vmr[inr][ing][inb] += r;
            vmg[inr][ing][inb] += g;
            vmb[inr][ing][inb] += b;
            m2[inr][ing][inb] += table[r] + table[g] + table[b];
        }
    }

    /* At conclusion of the histogram step, we can interpret
     *   wt[r][g][b] = sum over voxel of P(c)
     *   mr[r][g][b] = sum over voxel of r*P(c)  ,  similarly for mg, mb
     *   m2[r][g][b] = sum over voxel of c^2*P(c)
     * Actually each of these should be divided by 'size' to give the usual
     * interpretation of P() as ranging from 0 to 1, but we needn't do that here.
     *
     * We now convert histogram into moments so that we can rapidly calculate
     * the sums of the above quantities over any desired box.
     */
    private void computeMoments(
            long[][][] vwt, long[][][] vmr, long[][][] vmg, long[][][] vmb, double[][][] m2) {
        /* compute cumulative moments. */
        int i, r, g, b;
        int line, line_r, line_g, line_b;
        int[] area = new int[QUANT_SIZE];
        int[] area_r = new int[QUANT_SIZE];
        int[] area_g = new int[QUANT_SIZE];
        int[] area_b = new int[QUANT_SIZE];
        double line2;
        double[] area2 = new double[QUANT_SIZE];

        for (r = 1; r < QUANT_SIZE; ++r) {
            for (i = 0; i < QUANT_SIZE; ++i) {
                area2[i] = area[i] = area_r[i] = area_g[i] = area_b[i] = 0;
            }
            for (g = 1; g < QUANT_SIZE; ++g) {
                line2 = line = line_r = line_g = line_b = 0;
                for (b = 1; b < QUANT_SIZE; ++b) {
                    line += vwt[r][g][b];
                    line_r += vmr[r][g][b];
                    line_g += vmg[r][g][b];
                    line_b += vmb[r][g][b];
                    line2 += m2[r][g][b];

                    area[b] += line;
                    area_r[b] += line_r;
                    area_g[b] += line_g;
                    area_b[b] += line_b;
                    area2[b] += line2;

                    vwt[r][g][b] = vwt[r - 1][g][b] + area[b];
                    vmr[r][g][b] = vmr[r - 1][g][b] + area_r[b];
                    vmg[r][g][b] = vmg[r - 1][g][b] + area_g[b];
                    vmb[r][g][b] = vmb[r - 1][g][b] + area_b[b];
                    m2[r][g][b] = m2[r - 1][g][b] + area2[b];
                }
            }
        }
    }

    private long getVolume(Box cube, long[][][] mmt) {
        /* Compute sum over a box of any given statistic */
        return (mmt[cube.mR1][cube.mG1][cube.mB1]
                - mmt[cube.mR1][cube.mG1][cube.mB0]
                - mmt[cube.mR1][cube.mG0][cube.mB1]
                + mmt[cube.mR1][cube.mG0][cube.mB0]
                - mmt[cube.mR0][cube.mG1][cube.mB1]
                + mmt[cube.mR0][cube.mG1][cube.mB0]
                + mmt[cube.mR0][cube.mG0][cube.mB1]
                - mmt[cube.mR0][cube.mG0][cube.mB0]);
    }

    /* The next two routines allow a slightly more efficient calculation
     * of Vol() for a proposed subbox of a given box.  The sum of Top()
     * and Bottom() is the Vol() of a subbox split in the given direction
     * and with the specified new upper bound.
     */
    private long getBottom(Box cube, int dir, long[][][] mmt) {
        /* Compute part of Vol(cube, mmt) that doesn't depend on r1, g1, or b1 */
        /* (depending on dir) */
        switch (dir) {
            case RED:
                return (-mmt[cube.mR0][cube.mG1][cube.mB1]
                        + mmt[cube.mR0][cube.mG1][cube.mB0]
                        + mmt[cube.mR0][cube.mG0][cube.mB1]
                        - mmt[cube.mR0][cube.mG0][cube.mB0]);
            case GREEN:
                return (-mmt[cube.mR1][cube.mG0][cube.mB1]
                        + mmt[cube.mR1][cube.mG0][cube.mB0]
                        + mmt[cube.mR0][cube.mG0][cube.mB1]
                        - mmt[cube.mR0][cube.mG0][cube.mB0]);
            case BLUE:
                return (-mmt[cube.mR1][cube.mG1][cube.mB0]
                        + mmt[cube.mR1][cube.mG0][cube.mB0]
                        + mmt[cube.mR0][cube.mG1][cube.mB0]
                        - mmt[cube.mR0][cube.mG0][cube.mB0]);
            default:
                return 0;
        }
    }

    private long getTop(Box cube, int dir, int pos, long[][][] mmt) {
        /* Compute remainder of Vol(cube, mmt), substituting pos for */
        /* r1, g1, or b1 (depending on dir) */
        switch (dir) {
            case RED:
                return (mmt[pos][cube.mG1][cube.mB1]
                        - mmt[pos][cube.mG1][cube.mB0]
                        - mmt[pos][cube.mG0][cube.mB1]
                        + mmt[pos][cube.mG0][cube.mB0]);
            case GREEN:
                return (mmt[cube.mR1][pos][cube.mB1]
                        - mmt[cube.mR1][pos][cube.mB0]
                        - mmt[cube.mR0][pos][cube.mB1]
                        + mmt[cube.mR0][pos][cube.mB0]);
            case BLUE:
                return (mmt[cube.mR1][cube.mG1][pos]
                        - mmt[cube.mR1][cube.mG0][pos]
                        - mmt[cube.mR0][cube.mG1][pos]
                        + mmt[cube.mR0][cube.mG0][pos]);
            default:
                return 0;
        }
    }

    private double getVariance(Box cube) {
        /* Compute the weighted variance of a box */
        /* NB: as with the raw statistics, this is really the variance * size */
        double dr, dg, db, xx;
        dr = getVolume(cube, mMr);
        dg = getVolume(cube, mMg);
        db = getVolume(cube, mMb);
        xx =
                mM2[cube.mR1][cube.mG1][cube.mB1]
                        - mM2[cube.mR1][cube.mG1][cube.mB0]
                        - mM2[cube.mR1][cube.mG0][cube.mB1]
                        + mM2[cube.mR1][cube.mG0][cube.mB0]
                        - mM2[cube.mR0][cube.mG1][cube.mB1]
                        + mM2[cube.mR0][cube.mG1][cube.mB0]
                        + mM2[cube.mR0][cube.mG0][cube.mB1]
                        - mM2[cube.mR0][cube.mG0][cube.mB0];
        return xx - (dr * dr + dg * dg + db * db) / getVolume(cube, mWt);
    }

    /* We want to minimize the sum of the variances of two subboxes.
     * The sum(c^2) terms can be ignored since their sum over both subboxes
     * is the same (the sum for the whole box) no matter where we split.
     * The remaining terms have a minus sign in the variance formula,
     * so we drop the minus sign and MAXIMIZE the sum of the two terms.
     */
    private double maximize(
            Box cube,
            int dir,
            int first,
            int last,
            int[] cut,
            long wholeR,
            long wholeG,
            long wholeB,
            long wholeW) {
        long half_r, half_g, half_b, half_w;
        long base_r, base_g, base_b, base_w;
        int i;
        double temp, max;

        base_r = getBottom(cube, dir, mMr);
        base_g = getBottom(cube, dir, mMg);
        base_b = getBottom(cube, dir, mMb);
        base_w = getBottom(cube, dir, mWt);

        max = 0.0f;
        cut[0] = -1;

        for (i = first; i < last; ++i) {
            half_r = base_r + getTop(cube, dir, i, mMr);
            half_g = base_g + getTop(cube, dir, i, mMg);
            half_b = base_b + getTop(cube, dir, i, mMb);
            half_w = base_w + getTop(cube, dir, i, mWt);
            /* now half_x is sum over lower half of box, if split at i */
            if (half_w == 0) /* subbox could be empty of pixels! */ {
                continue; /* never split into an empty box */
            }
            temp = (half_r * half_r + half_g * half_g + half_b * half_b) / (double) half_w;
            half_r = wholeR - half_r;
            half_g = wholeG - half_g;
            half_b = wholeB - half_b;
            half_w = wholeW - half_w;
            if (half_w == 0) /* subbox could be empty of pixels! */ {
                continue; /* never split into an empty box */
            }
            temp += (half_r * half_r + half_g * half_g + half_b * half_b) / (double) half_w;

            if (temp > max) {
                max = temp;
                cut[0] = i;
            }
        }

        return max;
    }

    private boolean cut(Box set1, Box set2) {
        int dir;
        int[] cutr = new int[1];
        int[] cutg = new int[1];
        int[] cutb = new int[1];
        double maxr, maxg, maxb;
        long whole_r, whole_g, whole_b, whole_w;

        whole_r = getVolume(set1, mMr);
        whole_g = getVolume(set1, mMg);
        whole_b = getVolume(set1, mMb);
        whole_w = getVolume(set1, mWt);

        maxr = maximize(set1, RED, set1.mR0 + 1, set1.mR1, cutr, whole_r, whole_g, whole_b,
                whole_w);
        maxg = maximize(set1, GREEN, set1.mG0 + 1, set1.mG1, cutg, whole_r, whole_g, whole_b,
                whole_w);
        maxb = maximize(set1, BLUE, set1.mB0 + 1, set1.mB1, cutb, whole_r, whole_g, whole_b,
                whole_w);

        if (maxr >= maxg && maxr >= maxb) {
            dir = RED;
            if (cutr[0] < 0) return false; /* can't split the box */
        } else if (maxg >= maxr && maxg >= maxb) {
            dir = GREEN;
        } else {
            dir = BLUE;
        }

        set2.mR1 = set1.mR1;
        set2.mG1 = set1.mG1;
        set2.mB1 = set1.mB1;

        switch (dir) {
            case RED:
                set2.mR0 = set1.mR1 = cutr[0];
                set2.mG0 = set1.mG0;
                set2.mB0 = set1.mB0;
                break;
            case GREEN:
                set2.mG0 = set1.mG1 = cutg[0];
                set2.mR0 = set1.mR0;
                set2.mB0 = set1.mB0;
                break;
            case BLUE:
                set2.mB0 = set1.mB1 = cutb[0];
                set2.mR0 = set1.mR0;
                set2.mG0 = set1.mG0;
                break;
        }
        set1.mVol = (set1.mR1 - set1.mR0) * (set1.mG1 - set1.mG0) * (set1.mB1 - set1.mB0);
        set2.mVol = (set2.mR1 - set2.mR0) * (set2.mG1 - set2.mG0) * (set2.mB1 - set2.mB0);

        return true;
    }
}
