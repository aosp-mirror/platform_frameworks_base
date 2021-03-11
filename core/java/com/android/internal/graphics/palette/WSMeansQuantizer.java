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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;


/**
 * A color quantizer based on the Kmeans algorithm.
 *
 * This is an implementation of Kmeans based on Celebi's 2011 paper,
 * "Improving the Performance of K-Means for Color Quantization". In the paper, this algorithm is
 * referred to as "WSMeans", or, "Weighted Square Means" The main advantages of this Kmeans
 * implementation are taking advantage of triangle properties to avoid distance calculations, as
 * well as indexing colors by their count, thus minimizing the number of points to move around.
 *
 * Celebi's paper also stabilizes results and guarantees high quality by using starting centroids
 * from Wu's quantization algorithm. See CelebiQuantizer for more info.
 */
public class WSMeansQuantizer implements Quantizer {
    Mean[] mMeans;
    private final Map<Integer, Integer> mCountByColor = new HashMap<>();
    private final Map<Integer, Integer> mMeanIndexByColor = new HashMap<>();
    private final Set<Integer> mUniqueColors = new HashSet<>();
    private final List<Palette.Swatch> mSwatches = new ArrayList<>();
    private final CentroidProvider mCentroidProvider;

    public WSMeansQuantizer(
            float[][] means, CentroidProvider centroidProvider, int[] pixels, int maxColors) {
        if (pixels == null) {
            pixels = new int[]{};
        }
        mCentroidProvider = centroidProvider;
        mMeans = new Mean[maxColors];
        for (int i = 0; i < means.length; i++) {
            mMeans[i] = new Mean(means[i]);
        }

        if (maxColors > means.length) {
            // Always initialize Random with the same seed. Ensures the results of quantization
            // are consistent, even when random centroids are required.
            Random random = new Random(0x42688);
            int randomMeansToCreate = maxColors - means.length;
            for (int i = 0; i < randomMeansToCreate; i++) {
                mMeans[means.length + i] = new Mean(100, random);
            }
        }

        for (int pixel : pixels) {
            Integer currentCount = mCountByColor.get(pixel);
            if (currentCount == null) {
                currentCount = 0;
                mUniqueColors.add(pixel);
            }
            mCountByColor.put(pixel, currentCount + 1);
        }
        for (int color : mUniqueColors) {
            int closestMeanIndex = -1;
            double closestMeanDistance = -1;
            float[] centroid = mCentroidProvider.getCentroid(color);
            for (int i = 0; i < mMeans.length; i++) {
                double distance = mCentroidProvider.distance(centroid, mMeans[i].center);
                if (closestMeanIndex == -1 || distance < closestMeanDistance) {
                    closestMeanIndex = i;
                    closestMeanDistance = distance;
                }
            }
            mMeanIndexByColor.put(color, closestMeanIndex);
        }

        if (pixels.length == 0) {
            return;
        }

        predict(maxColors, 0);
    }

    /** Create starting centroids for K-means from a set of colors. */
    public static float[][] createStartingCentroids(CentroidProvider centroidProvider,
            List<Palette.Swatch> swatches) {
        float[][] startingCentroids = new float[swatches.size()][];
        for (int i = 0; i < swatches.size(); i++) {
            startingCentroids[i] = centroidProvider.getCentroid(swatches.get(i).getInt());
        }
        return startingCentroids;
    }

    /** Create random starting centroids for K-means. */
    public static float[][] randomMeans(int maxColors, int upperBound) {
        float[][] means = new float[maxColors][];

        // Always initialize Random with the same seed. Ensures the results of quantization
        // are consistent, even when random centroids are required.
        Random random = new Random(0x42688);
        for (int i = 0; i < maxColors; i++) {
            means[i] = new Mean(upperBound, random).center;
        }
        return means;
    }


    @Override
    public void quantize(int[] pixels, int maxColors) {

    }

    @Override
    public List<Palette.Swatch> getQuantizedColors() {
        return mSwatches;
    }

    private void predict(int maxColors, int iterationsCompleted) {
        double[][] centroidDistance = new double[maxColors][maxColors];
        for (int i = 0; i <= maxColors; i++) {
            for (int j = i + 1; j < maxColors; j++) {
                float[] meanI = mMeans[i].center;
                float[] meanJ = mMeans[j].center;
                double distance = mCentroidProvider.distance(meanI, meanJ);
                centroidDistance[i][j] = distance;
                centroidDistance[j][i] = distance;
            }
        }

        // Construct a K×K matrix M in which row i is a permutation of
        // 1,2,…,K that represents the clusters in increasing order of
        // distance of their centers from ci;
        int[][] distanceMatrix = new int[maxColors][maxColors];
        for (int i = 0; i < maxColors; i++) {
            double[] distancesFromIToAnotherMean = centroidDistance[i];
            double[] sortedByDistanceAscending = distancesFromIToAnotherMean.clone();
            Arrays.sort(sortedByDistanceAscending);
            int[] outputRow = new int[maxColors];
            for (int j = 0; j < maxColors; j++) {
                outputRow[j] = findIndex(distancesFromIToAnotherMean, sortedByDistanceAscending[j]);
            }
            distanceMatrix[i] = outputRow;
        }

        //   for (i=1;i≤N′;i=i+ 1) do
        //   Let Sp be the cluster that xi was assigned to in the previous
        //   iteration;
        //   p=m[i];
        //   min_dist=prev_dist=jjxi−cpjj2;
        boolean anyColorMoved = false;
        for (int intColor : mUniqueColors) {
            float[] color = mCentroidProvider.getCentroid(intColor);
            int indexOfCurrentMean = mMeanIndexByColor.get(intColor);
            Mean currentMean = mMeans[indexOfCurrentMean];
            double minDistance = mCentroidProvider.distance(color, currentMean.center);
            for (int j = 1; j < maxColors; j++) {
                int indexOfClusterFromCurrentToJ = distanceMatrix[indexOfCurrentMean][j];
                double distanceBetweenJAndCurrent =
                        centroidDistance[indexOfCurrentMean][indexOfClusterFromCurrentToJ];
                if (distanceBetweenJAndCurrent >= (4 * minDistance)) {
                    break;
                }
                double distanceBetweenJAndColor = mCentroidProvider.distance(mMeans[j].center,
                        color);
                if (distanceBetweenJAndColor < minDistance) {
                    minDistance = distanceBetweenJAndColor;
                    mMeanIndexByColor.remove(intColor);
                    mMeanIndexByColor.put(intColor, j);
                    anyColorMoved = true;
                }
            }
        }

        List<MeanBucket> buckets = new ArrayList<>();
        for (int i = 0; i < maxColors; i++) {
            buckets.add(new MeanBucket());
        }

        for (int intColor : mUniqueColors) {
            int meanIndex = mMeanIndexByColor.get(intColor);
            MeanBucket meanBucket = buckets.get(meanIndex);
            meanBucket.add(mCentroidProvider.getCentroid(intColor), intColor,
                    mCountByColor.get(intColor));
        }

        List<Palette.Swatch> swatches = new ArrayList<>();
        boolean done = !anyColorMoved && iterationsCompleted > 0 || iterationsCompleted >= 100;
        if (done) {
            for (int i = 0; i < buckets.size(); i++) {
                MeanBucket a = buckets.get(i);
                if (a.mCount <= 0) {
                    continue;
                }
                List<MeanBucket> bucketsToMerge = new ArrayList<>();
                for (int j = i + 1; j < buckets.size(); j++) {
                    MeanBucket b = buckets.get(j);
                    if (b.mCount == 0) {
                        continue;
                    }
                    float[] bCentroid = b.getCentroid();
                    assert (a.mCount > 0);
                    assert (a.getCentroid() != null);

                    assert (bCentroid != null);
                    if (mCentroidProvider.distance(a.getCentroid(), b.getCentroid()) < 5) {
                        bucketsToMerge.add(b);
                    }
                }

                for (MeanBucket bucketToMerge : bucketsToMerge) {
                    float[] centroid = bucketToMerge.getCentroid();
                    a.add(centroid, mCentroidProvider.getColor(centroid), bucketToMerge.mCount);
                    buckets.remove(bucketToMerge);
                }
            }

            for (MeanBucket bucket : buckets) {
                float[] centroid = bucket.getCentroid();
                if (centroid == null) {
                    continue;
                }

                int rgb = mCentroidProvider.getColor(centroid);
                swatches.add(new Palette.Swatch(rgb, bucket.mCount));
                mSwatches.clear();
                mSwatches.addAll(swatches);
            }
        } else {
            List<MeanBucket> emptyBuckets = new ArrayList<>();
            for (int i = 0; i < buckets.size(); i++) {
                MeanBucket bucket = buckets.get(i);
                if ((bucket.getCentroid() == null) || (bucket.mCount == 0)) {
                    emptyBuckets.add(bucket);
                    for (Integer color : mUniqueColors) {
                        int meanIndex = mMeanIndexByColor.get(color);
                        if (meanIndex > i) {
                            mMeanIndexByColor.put(color, meanIndex--);
                        }
                    }
                }
            }

            Mean[] newMeans = new Mean[buckets.size()];
            for (int i = 0; i < buckets.size(); i++) {
                float[] centroid = buckets.get(i).getCentroid();
                newMeans[i] = new Mean(centroid);
            }

            predict(buckets.size(), iterationsCompleted + 1);
        }

    }

    private static int findIndex(double[] list, double element) {
        for (int i = 0; i < list.length; i++) {
            if (list[i] == element) {
                return i;
            }
        }
        throw new IllegalArgumentException("Element not in list");
    }
}
