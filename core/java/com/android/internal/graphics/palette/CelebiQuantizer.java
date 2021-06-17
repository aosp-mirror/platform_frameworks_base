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
 * An implementation of Celebi's quantization method.
 * See Celebi 2011, “Improving the Performance of K-Means for Color Quantization”
 *
 * First, Wu's quantizer runs. The results are used as starting points for a subsequent Kmeans
 * run. Using Wu's quantizer ensures 100% reproducible quantization results, because the starting
 * centroids are always the same. It also ensures high quality results, Wu is a box-cutting
 * quantization algorithm, much like medican color cut. It minimizes variance, much like Kmeans.
 * Wu is shown to be the highest quality box-cutting quantization algorithm.
 *
 * Second, a Kmeans quantizer tweaked for performance is run. Celebi calls this a weighted
 * square means quantizer, or WSMeans. Optimizations include operating on a map of image pixels
 * rather than all image pixels, and avoiding excess color distance calculations by using a
 * matrix and geometrical properties to know when there won't be any cluster closer to a pixel.
 */
public class CelebiQuantizer implements Quantizer {
    private List<Palette.Swatch> mSwatches;

    public CelebiQuantizer() {
    }

    @Override
    public void quantize(int[] pixels, int maxColors) {
        WuQuantizer wu = new WuQuantizer();
        wu.quantize(pixels, maxColors);
        WSMeansQuantizer kmeans = new WSMeansQuantizer(wu.getColors(), new LABPointProvider(),
                wu.inputPixelToCount());
        kmeans.quantize(pixels, maxColors);
        mSwatches = kmeans.getQuantizedColors();
    }

    @Override
    public List<Palette.Swatch> getQuantizedColors() {
        return mSwatches;
    }
}
