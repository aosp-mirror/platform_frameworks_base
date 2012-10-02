/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.test.hwuicompare;

import com.android.test.hwuicompare.R;
import com.android.test.hwuicompare.ScriptC_errorCalculator;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.util.Log;

public class ErrorCalculator {
    private static final String LOG_TAG = "ErrorCalculator";
    private static final int REGION_SIZE = 8;

    private static final boolean LOG_TIMING = false;
    private static final boolean LOG_CALC = false;

    private final RenderScript mRS;
    private Allocation mIdealPixelsAllocation;
    private Allocation mGivenPixelsAllocation;
    private Allocation mOutputPixelsAllocation;

    private final Allocation mInputRowsAllocation;
    private final Allocation mOutputRegionsAllocation;

    private final ScriptC_errorCalculator mScript;

    private final int[] mOutputRowRegions;

    public ErrorCalculator(Context c, Resources resources) {
        int width = resources.getDimensionPixelSize(R.dimen.layer_width);
        int height = resources.getDimensionPixelSize(R.dimen.layer_height);
        mOutputRowRegions = new int[height / REGION_SIZE];

        mRS = RenderScript.create(c);
        int[] rowIndices = new int[height / REGION_SIZE];
        for (int i = 0; i < rowIndices.length; i++)
            rowIndices[i] = i * REGION_SIZE;

        mScript = new ScriptC_errorCalculator(mRS, resources, R.raw.errorcalculator);
        mScript.set_HEIGHT(height);
        mScript.set_WIDTH(width);
        mScript.set_REGION_SIZE(REGION_SIZE);

        mInputRowsAllocation = Allocation.createSized(mRS, Element.I32(mRS), rowIndices.length,
                Allocation.USAGE_SCRIPT);
        mInputRowsAllocation.copyFrom(rowIndices);
        mOutputRegionsAllocation = Allocation.createSized(mRS, Element.I32(mRS),
                mOutputRowRegions.length, Allocation.USAGE_SCRIPT);
    }


    private static long startMillis, middleMillis;

    public float calcErrorRS(Bitmap ideal, Bitmap given) {
        if (LOG_TIMING) {
            startMillis = System.currentTimeMillis();
        }

        mIdealPixelsAllocation = Allocation.createFromBitmap(mRS, ideal,
                Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        mGivenPixelsAllocation = Allocation.createFromBitmap(mRS, given,
                Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);

        mScript.bind_ideal(mIdealPixelsAllocation);
        mScript.bind_given(mGivenPixelsAllocation);

        mScript.forEach_countInterestingRegions(mInputRowsAllocation, mOutputRegionsAllocation);
        mOutputRegionsAllocation.copyTo(mOutputRowRegions);

        int regionCount = 0;
        for (int region : mOutputRowRegions) {
            regionCount += region;
        }
        int interestingPixels = Math.max(1, regionCount) * REGION_SIZE * REGION_SIZE;

        if (LOG_TIMING) {
            long startMillis2 = System.currentTimeMillis();
        }

        mScript.forEach_accumulateError(mInputRowsAllocation, mOutputRegionsAllocation);
        mOutputRegionsAllocation.copyTo(mOutputRowRegions);
        float totalError = 0;
        for (int row : mOutputRowRegions) {
            totalError += row;
        }
        totalError /= 1024.0f;

        if (LOG_TIMING) {
            long finalMillis = System.currentTimeMillis();
            Log.d(LOG_TAG, "rs: first part took " + (middleMillis - startMillis) + "ms");
            Log.d(LOG_TAG, "rs: last part took " + (finalMillis - middleMillis) + "ms");
        }
        if (LOG_CALC) {
            Log.d(LOG_TAG, "rs: error " + totalError + ", pixels " + interestingPixels);
        }
        return totalError / interestingPixels;
    }

    public void calcErrorHeatmapRS(Bitmap ideal, Bitmap given, Bitmap output) {
        mIdealPixelsAllocation = Allocation.createFromBitmap(mRS, ideal,
                Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        mGivenPixelsAllocation = Allocation.createFromBitmap(mRS, given,
                Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);

        mScript.bind_ideal(mIdealPixelsAllocation);
        mScript.bind_given(mGivenPixelsAllocation);

        mOutputPixelsAllocation = Allocation.createFromBitmap(mRS, output,
                Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
        mScript.forEach_displayDifference(mOutputPixelsAllocation, mOutputPixelsAllocation);
        mOutputPixelsAllocation.copyTo(output);
    }

    public static float calcError(Bitmap ideal, Bitmap given) {
        if (LOG_TIMING) {
            startMillis = System.currentTimeMillis();
        }

        int interestingRegions = 0;
        for (int x = 0; x < ideal.getWidth(); x += REGION_SIZE) {
            for (int y = 0; y < ideal.getWidth(); y += REGION_SIZE) {
                if (inspectRegion(ideal, x, y)) {
                    interestingRegions++;
                }
            }
        }

        int interestingPixels = Math.max(1, interestingRegions) * REGION_SIZE * REGION_SIZE;

        if (LOG_TIMING) {
            long startMillis2 = System.currentTimeMillis();
        }

        float totalError = 0;
        for (int x = 0; x < ideal.getWidth(); x++) {
            for (int y = 0; y < ideal.getHeight(); y++) {
                int idealColor = ideal.getPixel(x, y);
                int givenColor = given.getPixel(x, y);
                if (idealColor == givenColor)
                    continue;
                totalError += Math.abs(Color.red(idealColor) - Color.red(givenColor));
                totalError += Math.abs(Color.green(idealColor) - Color.green(givenColor));
                totalError += Math.abs(Color.blue(idealColor) - Color.blue(givenColor));
                totalError += Math.abs(Color.alpha(idealColor) - Color.alpha(givenColor));
            }
        }
        totalError /= 1024.0f;
        if (LOG_TIMING) {
            long finalMillis = System.currentTimeMillis();
            Log.d(LOG_TAG, "dvk: first part took " + (middleMillis - startMillis) + "ms");
            Log.d(LOG_TAG, "dvk: last part took " + (finalMillis - middleMillis) + "ms");
        }
        if (LOG_CALC) {
            Log.d(LOG_TAG, "dvk: error " + totalError + ", pixels " + interestingPixels);
        }
        return totalError / interestingPixels;
    }

    private static boolean inspectRegion(Bitmap ideal, int x, int y) {
        int regionColor = ideal.getPixel(x, y);
        for (int i = 0; i < REGION_SIZE; i++) {
            for (int j = 0; j < REGION_SIZE; j++) {
                if (ideal.getPixel(x + i, y + j) != regionColor)
                    return true;
            }
        }
        return false;
    }
}
