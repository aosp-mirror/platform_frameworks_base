/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Igor V. Stolyarov
 * @version $Revision$
 */

package java.awt.image;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The PixelInterleavedSampleModel class represents image data as represented as
 * interleaved pixels and for which each sample of a pixel takes one data
 * element of the DataBuffer.
 * 
 * @since Android 1.0
 */
public class PixelInterleavedSampleModel extends ComponentSampleModel {

    /**
     * Instantiates a new PixelInterleavedSampleModel with the specified
     * parameters.
     * 
     * @param dataType
     *            the data type of the samples.
     * @param w
     *            the width of the image data.
     * @param h
     *            the height of the image data.
     * @param pixelStride
     *            the pixel stride of the image data.
     * @param scanlineStride
     *            the scanline stride of the of the image data.
     * @param bandOffsets
     *            the array of the band offsets.
     */
    public PixelInterleavedSampleModel(int dataType, int w, int h, int pixelStride,
            int scanlineStride, int bandOffsets[]) {

        super(dataType, w, h, pixelStride, scanlineStride, bandOffsets);

        int maxOffset = bandOffsets[0];
        int minOffset = bandOffsets[0];
        for (int i = 1; i < bandOffsets.length; i++) {
            if (bandOffsets[i] > maxOffset) {
                maxOffset = bandOffsets[i];
            }
            if (bandOffsets[i] < minOffset) {
                minOffset = bandOffsets[i];
            }
        }

        maxOffset -= minOffset;

        if (maxOffset > scanlineStride) {
            // awt.241=Any offset between bands is greater than the Scanline
            // stride
            throw new IllegalArgumentException(Messages.getString("awt.241")); //$NON-NLS-1$
        }

        if (maxOffset > pixelStride) {
            // awt.242=Pixel stride is less than any offset between bands
            throw new IllegalArgumentException(Messages.getString("awt.242")); //$NON-NLS-1$
        }

        if (pixelStride * w > scanlineStride) {
            // awt.243=Product of Pixel stride and w is greater than Scanline
            // stride
            throw new IllegalArgumentException(Messages.getString("awt.243")); //$NON-NLS-1$
        }

    }

    @Override
    public SampleModel createSubsetSampleModel(int bands[]) {
        int newOffsets[] = new int[bands.length];
        for (int i = 0; i < bands.length; i++) {
            newOffsets[i] = bandOffsets[bands[i]];
        }

        return new PixelInterleavedSampleModel(dataType, width, height, pixelStride,
                scanlineStride, newOffsets);
    }

    @Override
    public SampleModel createCompatibleSampleModel(int w, int h) {
        int newOffsets[];
        int minOffset = bandOffsets[0];

        for (int i = 1; i < numBands; i++) {
            if (bandOffsets[i] < minOffset) {
                minOffset = bandOffsets[i];
            }
        }

        if (minOffset > 0) {
            newOffsets = new int[numBands];
            for (int i = 0; i < numBands; i++) {
                newOffsets[i] = bandOffsets[i] - minOffset;
            }
        } else {
            newOffsets = bandOffsets;
        }

        return new PixelInterleavedSampleModel(dataType, w, h, pixelStride, pixelStride * w,
                newOffsets);
    }

    @Override
    public int hashCode() {
        int hash = super.hashCode();
        int tmp = hash >>> 8;
        hash <<= 8;
        hash |= tmp;

        return hash ^ 0x66;
    }

}
