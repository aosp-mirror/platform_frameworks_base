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

import java.util.List;

/**
 * An implementation of Celebi's WSM quantizer, or, a Kmeans quantizer that starts with centroids
 * from a Wu quantizer to ensure 100% reproducible and quality results, and has some optimizations
 * to the Kmeans algorithm.
 *
 * See Celebi 2011, “Improving the Performance of K-Means for Color Quantization”
 */
public class CelebiQuantizer implements Quantizer {
    private List<Palette.Swatch> mSwatches;

    public CelebiQuantizer() { }

    @Override
    public void quantize(int[] pixels, int maxColors) {
        WuQuantizer wu = new WuQuantizer(pixels, maxColors);
        wu.quantize(pixels, maxColors);
        List<Palette.Swatch> wuSwatches = wu.getQuantizedColors();
        LABCentroid labCentroidProvider = new LABCentroid();
        WSMeansQuantizer kmeans =
                new WSMeansQuantizer(WSMeansQuantizer.createStartingCentroids(labCentroidProvider,
                        wuSwatches), labCentroidProvider, pixels, maxColors);
        kmeans.quantize(pixels, maxColors);
        mSwatches = kmeans.getQuantizedColors();
    }

    @Override
    public List<Palette.Swatch> getQuantizedColors() {
        return mSwatches;
    }
}
