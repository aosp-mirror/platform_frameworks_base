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
 * limitations under the License.
 */

package com.android.internal.ml.clustering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.annotation.SuppressLint;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class KMeansTest {

    // Error tolerance (epsilon)
    private static final double EPS = 0.01;

    private KMeans mKMeans;

    @Before
    public void setUp() {
        // Setup with a random seed to have predictable results
        mKMeans = new KMeans(new Random(0), 30, 0);
    }

    @Test
    public void getCheckDataSanityTest() {
        try {
            mKMeans.checkDataSetSanity(new float[][] {
                    {0, 1, 2},
                    {1, 2, 3}
            });
        } catch (IllegalArgumentException e) {
            Assert.fail("Valid data didn't pass sanity check");
        }

        try {
            mKMeans.checkDataSetSanity(new float[][] {
                    null,
                    {1, 2, 3}
            });
            Assert.fail("Data has null items and passed");
        } catch (IllegalArgumentException e) {}

        try {
            mKMeans.checkDataSetSanity(new float[][] {
                    {0, 1, 2, 4},
                    {1, 2, 3}
            });
            Assert.fail("Data has invalid shape and passed");
        } catch (IllegalArgumentException e) {}

        try {
            mKMeans.checkDataSetSanity(null);
            Assert.fail("Null data should throw exception");
        } catch (IllegalArgumentException e) {}
    }

    @Test
    public void sqDistanceTest() {
        float a[] = {4, 10};
        float b[] = {5, 2};
        float sqDist = (float) (Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2));

        assertEquals("Squared distance not valid", mKMeans.sqDistance(a, b), sqDist, EPS);
    }

    @Test
    public void nearestMeanTest() {
        KMeans.Mean meanA = new KMeans.Mean(0, 1);
        KMeans.Mean meanB = new KMeans.Mean(1, 1);
        List<KMeans.Mean> means = Arrays.asList(meanA, meanB);

        KMeans.Mean nearest = mKMeans.nearestMean(new float[] {1, 1}, means);

        assertEquals("Unexpected nearest mean for point {1, 1}", nearest, meanB);
    }

    @SuppressLint("DefaultLocale")
    @Test
    public void scoreTest() {
        List<KMeans.Mean> closeMeans = Arrays.asList(new KMeans.Mean(0, 0.1f, 0.1f),
                new KMeans.Mean(0, 0.1f, 0.15f),
                new KMeans.Mean(0.1f, 0.2f, 0.1f));
        List<KMeans.Mean> farMeans = Arrays.asList(new KMeans.Mean(0, 0, 0),
                new KMeans.Mean(0, 0.5f, 0.5f),
                new KMeans.Mean(1, 0.9f, 0.9f));

        double closeScore = KMeans.score(closeMeans);
        double farScore = KMeans.score(farMeans);
        assertTrue(String.format("Score of well distributed means should be greater than "
                + "close means but got: %f, %f", farScore, closeScore), farScore > closeScore);
    }

    @Test
    public void predictTest() {
        float[] expectedCentroid1 = {1, 1, 1};
        float[] expectedCentroid2 = {0, 0, 0};
        float[][] X = new float[][] {
                {1, 1, 1},
                {1, 1, 1},
                {1, 1, 1},
                {0, 0, 0},
                {0, 0, 0},
                {0, 0, 0},
        };

        final int numClusters = 2;

        // Here we assume that we won't get stuck into a local optima.
        // It's fine because we're seeding a random, we won't ever have
        // unstable results but in real life we need multiple initialization
        // and score comparison
        List<KMeans.Mean> means = mKMeans.predict(numClusters, X);

        assertEquals("Expected number of clusters is invalid", numClusters, means.size());

        boolean exists1 = false, exists2 = false;
        for (KMeans.Mean mean : means) {
            if (Arrays.equals(mean.getCentroid(), expectedCentroid1)) {
                exists1 = true;
            } else if (Arrays.equals(mean.getCentroid(), expectedCentroid2)) {
                exists2 = true;
            } else {
                throw new AssertionError("Unexpected mean: " + mean);
            }
        }
        assertTrue("Expected means were not predicted, got: " + means,
                exists1 && exists2);
    }
}
