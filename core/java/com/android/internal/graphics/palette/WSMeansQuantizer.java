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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * A color quantizer based on the Kmeans algorithm. Prefer using QuantizerCelebi.
 *
 * This is an implementation of Kmeans based on Celebi's 2011 paper,
 * "Improving the Performance of K-Means for Color Quantization". In the paper, this algorithm is
 * referred to as "WSMeans", or, "Weighted Square Means" The main advantages of this Kmeans
 * implementation are taking advantage of triangle properties to avoid distance calculations, as
 * well as indexing colors by their count, thus minimizing the number of points to move around.
 *
 * Celebi's paper also stabilizes results and guarantees high quality by using starting centroids
 * from Wu's quantization algorithm. See QuantizerCelebi for more info.
 */
public final class WSMeansQuantizer implements Quantizer {
    private static final String TAG = "QuantizerWsmeans";
    private static final boolean DEBUG = false;
    private static final int MAX_ITERATIONS = 10;
    // Points won't be moved to a closer cluster, if the closer cluster is within
    // this distance. 3.0 used because L*a*b* delta E < 3 is considered imperceptible.
    private static final float MIN_MOVEMENT_DISTANCE = 3.0f;

    private final PointProvider mPointProvider;
    private @Nullable Map<Integer, Integer> mInputPixelToCount;
    private float[][] mClusters;
    private int[] mClusterPopulations;
    private float[][] mPoints;
    private int[] mPixels;
    private int[] mClusterIndices;
    private int[][] mIndexMatrix = {};
    private float[][] mDistanceMatrix = {};

    private Palette mPalette;

    public WSMeansQuantizer(int[] inClusters, PointProvider pointProvider,
            @Nullable Map<Integer, Integer> inputPixelToCount) {
        mPointProvider = pointProvider;

        mClusters = new float[inClusters.length][3];
        int index = 0;
        for (int cluster : inClusters) {
            float[] point = pointProvider.fromInt(cluster);
            mClusters[index++] = point;
        }

        mInputPixelToCount = inputPixelToCount;
    }

    @Override
    public List<Palette.Swatch> getQuantizedColors() {
        return mPalette.getSwatches();
    }

    @Override
    public void quantize(@NonNull int[] pixels, int maxColors) {
        assert (pixels.length > 0);

        if (mInputPixelToCount == null) {
            QuantizerMap mapQuantizer = new QuantizerMap();
            mapQuantizer.quantize(pixels, maxColors);
            mInputPixelToCount = mapQuantizer.getColorToCount();
        }

        mPoints = new float[mInputPixelToCount.size()][3];
        mPixels = new int[mInputPixelToCount.size()];
        int index = 0;
        for (int pixel : mInputPixelToCount.keySet()) {
            mPixels[index] = pixel;
            mPoints[index] = mPointProvider.fromInt(pixel);
            index++;
        }
        if (mClusters.length > 0) {
            // This implies that the constructor was provided starting clusters. If that was the
            // case, we limit the number of clusters to the number of starting clusters and don't
            // initialize random clusters.
            maxColors = Math.min(maxColors, mClusters.length);
        }
        maxColors = Math.min(maxColors, mPoints.length);

        initializeClusters(maxColors);
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            calculateClusterDistances(maxColors);
            if (!reassignPoints(maxColors)) {
                break;
            }
            recalculateClusterCenters(maxColors);
        }

        List<Palette.Swatch> swatches = new ArrayList<>();
        for (int i = 0; i < maxColors; i++) {
            float[] cluster = mClusters[i];
            int colorInt = mPointProvider.toInt(cluster);
            swatches.add(new Palette.Swatch(colorInt, mClusterPopulations[i]));
        }
        mPalette = Palette.from(swatches);
    }


    private void initializeClusters(int maxColors) {
        boolean hadInputClusters = mClusters.length > 0;
        if (!hadInputClusters) {
            int additionalClustersNeeded = maxColors - mClusters.length;
            if (DEBUG) {
                Log.d(TAG, "have " + mClusters.length + " clusters, want " + maxColors
                        + " results, so need " + additionalClustersNeeded + " additional clusters");
            }

            Random random = new Random(0x42688);
            List<float[]> additionalClusters = new ArrayList<>(additionalClustersNeeded);
            Set<Integer> clusterIndicesUsed = new HashSet<>();
            for (int i = 0; i < additionalClustersNeeded; i++) {
                int index = random.nextInt(mPoints.length);
                while (clusterIndicesUsed.contains(index)
                        && clusterIndicesUsed.size() < mPoints.length) {
                    index = random.nextInt(mPoints.length);
                }
                clusterIndicesUsed.add(index);
                additionalClusters.add(mPoints[index]);
            }

            float[][] newClusters = (float[][]) additionalClusters.toArray();
            float[][] clusters = Arrays.copyOf(mClusters, maxColors);
            System.arraycopy(newClusters, 0, clusters, clusters.length, newClusters.length);
            mClusters = clusters;
        }

        mClusterIndices = new int[mPixels.length];
        mClusterPopulations = new int[mPixels.length];
        Random random = new Random(0x42688);
        for (int i = 0; i < mPixels.length; i++) {
            int clusterIndex = random.nextInt(maxColors);
            mClusterIndices[i] = clusterIndex;
            mClusterPopulations[i] = mInputPixelToCount.get(mPixels[i]);
        }
    }

    void calculateClusterDistances(int maxColors) {
        if (mDistanceMatrix.length != maxColors) {
            mDistanceMatrix = new float[maxColors][maxColors];
        }

        for (int i = 0; i <= maxColors; i++) {
            for (int j = i + 1; j < maxColors; j++) {
                float distance = mPointProvider.distance(mClusters[i], mClusters[j]);
                mDistanceMatrix[j][i] = distance;
                mDistanceMatrix[i][j] = distance;
            }
        }

        if (mIndexMatrix.length != maxColors) {
            mIndexMatrix = new int[maxColors][maxColors];
        }

        for (int i = 0; i < maxColors; i++) {
            ArrayList<Distance> distances = new ArrayList<>(maxColors);
            for (int index = 0; index < maxColors; index++) {
                distances.add(new Distance(index, mDistanceMatrix[i][index]));
            }
            distances.sort(
                    (a, b) -> Float.compare(a.getDistance(), b.getDistance()));

            for (int j = 0; j < maxColors; j++) {
                mIndexMatrix[i][j] = distances.get(j).getIndex();
            }
        }
    }

    boolean reassignPoints(int maxColors) {
        boolean colorMoved = false;
        for (int i = 0; i < mPoints.length; i++) {
            float[] point = mPoints[i];
            int previousClusterIndex = mClusterIndices[i];
            float[] previousCluster = mClusters[previousClusterIndex];
            float previousDistance = mPointProvider.distance(point, previousCluster);

            float minimumDistance = previousDistance;
            int newClusterIndex = -1;
            for (int j = 1; j < maxColors; j++) {
                int t = mIndexMatrix[previousClusterIndex][j];
                if (mDistanceMatrix[previousClusterIndex][t] >= 4 * previousDistance) {
                    // Triangle inequality proves there's can be no closer center.
                    break;
                }
                float distance = mPointProvider.distance(point, mClusters[t]);
                if (distance < minimumDistance) {
                    minimumDistance = distance;
                    newClusterIndex = t;
                }
            }
            if (newClusterIndex != -1) {
                float distanceChange = (float)
                        Math.abs((Math.sqrt(minimumDistance) - Math.sqrt(previousDistance)));
                if (distanceChange > MIN_MOVEMENT_DISTANCE) {
                    colorMoved = true;
                    mClusterIndices[i] = newClusterIndex;
                }
            }
        }
        return colorMoved;
    }

    void recalculateClusterCenters(int maxColors) {
        mClusterPopulations = new int[maxColors];
        float[] aSums = new float[maxColors];
        float[] bSums = new float[maxColors];
        float[] cSums = new float[maxColors];
        for (int i = 0; i < mPoints.length; i++) {
            int clusterIndex = mClusterIndices[i];
            float[] point = mPoints[i];
            int pixel = mPixels[i];
            int count =  mInputPixelToCount.get(pixel);
            mClusterPopulations[clusterIndex] += count;
            aSums[clusterIndex] += point[0] * count;
            bSums[clusterIndex] += point[1] * count;
            cSums[clusterIndex] += point[2] * count;

        }
        for (int i = 0; i < maxColors; i++) {
            int count = mClusterPopulations[i];
            float aSum = aSums[i];
            float bSum = bSums[i];
            float cSum = cSums[i];
            mClusters[i][0] = aSum / count;
            mClusters[i][1] = bSum / count;
            mClusters[i][2] = cSum / count;
        }
    }

    private static class Distance {
        private final int mIndex;
        private final float mDistance;

        int getIndex() {
            return mIndex;
        }

        float getDistance() {
            return mDistance;
        }

        Distance(int index, float distance) {
            mIndex = index;
            mDistance = distance;
        }
    }
}
