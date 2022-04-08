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

import android.util.Log;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.ml.clustering.KMeans;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A quantizer that uses k-means
 */
public class VariationalKMeansQuantizer implements Quantizer {

    private static final String TAG = "KMeansQuantizer";
    private static final boolean DEBUG = false;

    /**
     * Clusters closer than this value will me merged.
     */
    private final float mMinClusterSqDistance;

    /**
     * K-means can get stuck in local optima, this can be avoided by
     * repeating it and getting the "best" execution.
     */
    private final int mInitializations;

    /**
     * Initialize KMeans with a fixed random state to have
     * consistent results across multiple runs.
     */
    private final KMeans mKMeans = new KMeans(new Random(0), 30, 0);

    private List<Palette.Swatch> mQuantizedColors;

    public VariationalKMeansQuantizer() {
        this(0.25f /* cluster distance */);
    }

    public VariationalKMeansQuantizer(float minClusterDistance) {
        this(minClusterDistance, 1 /* initializations */);
    }

    public VariationalKMeansQuantizer(float minClusterDistance, int initializations) {
        mMinClusterSqDistance = minClusterDistance * minClusterDistance;
        mInitializations = initializations;
    }

    /**
     * K-Means quantizer.
     *
     * @param pixels Pixels to quantize.
     * @param maxColors Maximum number of clusters to extract.
     * @param filters Colors that should be ignored
     */
    @Override
    public void quantize(int[] pixels, int maxColors, Palette.Filter[] filters) {
        // Start by converting all colors to HSL.
        // HLS is way more meaningful for clustering than RGB.
        final float[] hsl = {0, 0, 0};
        final float[][] hslPixels = new float[pixels.length][3];
        for (int i = 0; i < pixels.length; i++) {
            ColorUtils.colorToHSL(pixels[i], hsl);
            // Normalize hue so all values go from 0 to 1.
            hslPixels[i][0] = hsl[0] / 360f;
            hslPixels[i][1] = hsl[1];
            hslPixels[i][2] = hsl[2];
        }

        final List<KMeans.Mean> optimalMeans = getOptimalKMeans(maxColors, hslPixels);

        // Ideally we should run k-means again to merge clusters but it would be too expensive,
        // instead we just merge all clusters that are closer than a threshold.
        for (int i = 0; i < optimalMeans.size(); i++) {
            KMeans.Mean current = optimalMeans.get(i);
            float[] currentCentroid = current.getCentroid();
            for (int j = i + 1; j < optimalMeans.size(); j++) {
                KMeans.Mean compareTo = optimalMeans.get(j);
                float[] compareToCentroid = compareTo.getCentroid();
                float sqDistance = KMeans.sqDistance(currentCentroid, compareToCentroid);
                // Merge them
                if (sqDistance < mMinClusterSqDistance) {
                    optimalMeans.remove(compareTo);
                    current.getItems().addAll(compareTo.getItems());
                    for (int k = 0; k < currentCentroid.length; k++) {
                        currentCentroid[k] += (compareToCentroid[k] - currentCentroid[k]) / 2.0;
                    }
                    j--;
                }
            }
        }

        // Convert data to final format, de-normalizing the hue.
        mQuantizedColors = new ArrayList<>();
        for (KMeans.Mean mean : optimalMeans) {
            if (mean.getItems().size() == 0) {
                continue;
            }
            float[] centroid = mean.getCentroid();
            mQuantizedColors.add(new Palette.Swatch(new float[]{
                    centroid[0] * 360f,
                    centroid[1],
                    centroid[2]
            }, mean.getItems().size()));
        }
    }

    private List<KMeans.Mean> getOptimalKMeans(int k, float[][] inputData) {
        List<KMeans.Mean> optimal = null;
        double optimalScore = -Double.MAX_VALUE;
        int runs = mInitializations;
        while (runs > 0) {
            if (DEBUG) {
                Log.d(TAG, "k-means run: " + runs);
            }
            List<KMeans.Mean> means = mKMeans.predict(k, inputData);
            double score = KMeans.score(means);
            if (optimal == null || score > optimalScore) {
                if (DEBUG) {
                    Log.d(TAG, "\tnew optimal score: " + score);
                }
                optimalScore = score;
                optimal = means;
            }
            runs--;
        }

        return optimal;
    }

    @Override
    public List<Palette.Swatch> getQuantizedColors() {
        return mQuantizedColors;
    }
}
