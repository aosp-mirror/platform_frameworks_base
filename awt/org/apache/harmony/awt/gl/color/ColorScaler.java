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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */
package org.apache.harmony.awt.gl.color;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;

/**
 * This class provides functionality for scaling color data when
 * ranges of the source and destination color values differs. 
 */
public class ColorScaler {
    private static final float MAX_SHORT = 0xFFFF;
    private static final float MAX_SIGNED_SHORT = 0x7FFF;

    private static final float MAX_XYZ = 1f + (32767f/32768f);

    // Cached values for scaling color data
    private float[] channelMinValues = null;
    private float[] channelMulipliers = null; // for scale
    private float[] invChannelMulipliers = null; // for unscale

    int nColorChannels = 0;

    // For scaling rasters, false if transfer type is double or float
    boolean isTTypeIntegral = false;

    /**
     * Loads scaling data for raster. Note, if profile pf is null,
     * for non-integral data types multipliers are not initialized.
     * @param r - raster
     * @param pf - profile which helps to determine the ranges of the color data
     */
    public void loadScalingData(Raster r, ICC_Profile pf) {
        boolean isSrcTTypeIntegral =
            r.getTransferType() != DataBuffer.TYPE_FLOAT &&
            r.getTransferType() != DataBuffer.TYPE_DOUBLE;
        if (isSrcTTypeIntegral)
            loadScalingData(r.getSampleModel());
        else if (pf != null)
            loadScalingData(pf);
    }

    /**
     * Use this method only for integral transfer types.
     * Extracts min/max values from the sample model
     * @param sm - sample model
     */
    public void loadScalingData(SampleModel sm) {
        // Supposing integral transfer type
        isTTypeIntegral = true;

        nColorChannels = sm.getNumBands();

        channelMinValues = new float[nColorChannels];
        channelMulipliers = new float[nColorChannels];
        invChannelMulipliers = new float[nColorChannels];

        boolean isSignedShort =
            (sm.getTransferType() == DataBuffer.TYPE_SHORT);

        float maxVal;
        for (int i=0; i<nColorChannels; i++) {
            channelMinValues[i] = 0;
            if (isSignedShort) {
                channelMulipliers[i] = MAX_SHORT / MAX_SIGNED_SHORT;
                invChannelMulipliers[i] = MAX_SIGNED_SHORT / MAX_SHORT;
            } else {
                maxVal = ((1 << sm.getSampleSize(i)) - 1);
                channelMulipliers[i] = MAX_SHORT / maxVal;
                invChannelMulipliers[i] = maxVal / MAX_SHORT;
            }
        }
    }

    /**
     * Use this method only for double of float transfer types.
     * Extracts scaling data from the color space signature
     * and other tags, stored in the profile
     * @param pf - ICC profile
     */
    public void loadScalingData(ICC_Profile pf) {
        // Supposing double or float transfer type
        isTTypeIntegral = false;

        nColorChannels = pf.getNumComponents();

        // Get min/max values directly from the profile
        // Very much like fillMinMaxValues in ICC_ColorSpace
        float maxValues[] = new float[nColorChannels];
        float minValues[] = new float[nColorChannels];

        switch (pf.getColorSpaceType()) {
            case ColorSpace.TYPE_XYZ:
                minValues[0] = 0;
                minValues[1] = 0;
                minValues[2] = 0;
                maxValues[0] = MAX_XYZ;
                maxValues[1] = MAX_XYZ;
                maxValues[2] = MAX_XYZ;
                break;
            case ColorSpace.TYPE_Lab:
                minValues[0] = 0;
                minValues[1] = -128;
                minValues[2] = -128;
                maxValues[0] = 100;
                maxValues[1] = 127;
                maxValues[2] = 127;
                break;
            default:
                for (int i=0; i<nColorChannels; i++) {
                    minValues[i] = 0;
                    maxValues[i] = 1;
                }
        }

        channelMinValues = minValues;
        channelMulipliers = new float[nColorChannels];
        invChannelMulipliers = new float[nColorChannels];

        for (int i = 0; i < nColorChannels; i++) {
            channelMulipliers[i] =
                MAX_SHORT / (maxValues[i] - channelMinValues[i]);

            invChannelMulipliers[i] =
                (maxValues[i] - channelMinValues[i]) / MAX_SHORT;
        }
    }

    /**
     * Extracts scaling data from the color space
     * @param cs - color space
     */
    public void loadScalingData(ColorSpace cs) {
        nColorChannels = cs.getNumComponents();

        channelMinValues = new float[nColorChannels];
        channelMulipliers = new float[nColorChannels];
        invChannelMulipliers = new float[nColorChannels];

        for (int i = 0; i < nColorChannels; i++) {
            channelMinValues[i] = cs.getMinValue(i);

            channelMulipliers[i] =
                MAX_SHORT / (cs.getMaxValue(i) - channelMinValues[i]);

            invChannelMulipliers[i] =
                (cs.getMaxValue(i) - channelMinValues[i]) / MAX_SHORT;
        }
    }

    /**
     * Scales and normalizes the whole raster and returns the result
     * in the float array
     * @param r - source raster
     * @return scaled and normalized raster data
     */
    public float[][] scaleNormalize(Raster r) {
        int width = r.getWidth();
        int height = r.getHeight();
        float result[][] = new float[width*height][nColorChannels];
        float normMultipliers[] = new float[nColorChannels];

        int pos = 0;
        if (isTTypeIntegral) {
            // Change max value from MAX_SHORT to 1f
            for (int i=0; i<nColorChannels; i++) {
                normMultipliers[i] = channelMulipliers[i] / MAX_SHORT;
            }

            int sample;
            for (int row=r.getMinX(); row<width; row++) {
                for (int col=r.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                        sample = r.getSample(row, col, chan);
                        result[pos][chan] = (sample * normMultipliers[chan]);
                    }
                    pos++;
                }
            }
        } else { // Just get the samples...
            for (int row=r.getMinX(); row<width; row++) {
                for (int col=r.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                        result[pos][chan] = r.getSampleFloat(row, col, chan);
                    }
                    pos++;
                }
            }
        }
        return result;
    }

    /**
     * Unscale the whole float array and put the result
     * in the raster
     * @param r - destination raster
     * @param data - input pixels
     */
    public void unscaleNormalized(WritableRaster r, float data[][]) {
        int width = r.getWidth();
        int height = r.getHeight();
        float normMultipliers[] = new float[nColorChannels];

        int pos = 0;
        if (isTTypeIntegral) {
            // Change max value from MAX_SHORT to 1f
            for (int i=0; i<nColorChannels; i++) {
                normMultipliers[i] = invChannelMulipliers[i] * MAX_SHORT;
            }

            int sample;
            for (int row=r.getMinX(); row<width; row++) {
                for (int col=r.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                        sample = (int) (data[pos][chan] * normMultipliers[chan] + 0.5f);
                        r.setSample(row, col, chan, sample);
                    }
                    pos++;
                }
            }
        } else { // Just set the samples...
            for (int row=r.getMinX(); row<width; row++) {
                for (int col=r.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                        r.setSample(row, col, chan, data[pos][chan]);
                    }
                    pos++;
                }
            }
        }
    }

    /**
     * Scales the whole raster to short and returns the result
     * in the array
     * @param r - source raster
     * @return scaled and normalized raster data
     */
    public short[] scale(Raster r) {
        int width = r.getWidth();
        int height = r.getHeight();
        short result[] = new short[width*height*nColorChannels];

        int pos = 0;
        if (isTTypeIntegral) {
            int sample;
            for (int row=r.getMinX(); row<width; row++) {
                for (int col=r.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                        sample = r.getSample(row, col, chan);
                        result[pos++] =
                            (short) (sample * channelMulipliers[chan] + 0.5f);
                    }
                }
            }
        } else {
            float sample;
            for (int row=r.getMinX(); row<width; row++) {
                for (int col=r.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                        sample = r.getSampleFloat(row, col, chan);
                        result[pos++] = (short) ((sample - channelMinValues[chan])
                            * channelMulipliers[chan] + 0.5f);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Unscales the whole data array and puts obtained values to the raster
     * @param data - input data
     * @param wr - destination raster
     */
    public void unscale(short[] data, WritableRaster wr) {
        int width = wr.getWidth();
        int height = wr.getHeight();

        int pos = 0;
        if (isTTypeIntegral) {
            int sample;
            for (int row=wr.getMinX(); row<width; row++) {
                for (int col=wr.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                         sample = (int) ((data[pos++] & 0xFFFF) *
                                invChannelMulipliers[chan] + 0.5f);
                         wr.setSample(row, col, chan, sample);
                    }
                }
            }
        } else {
            float sample;
            for (int row=wr.getMinX(); row<width; row++) {
                for (int col=wr.getMinY(); col<height; col++) {
                    for (int chan = 0; chan < nColorChannels; chan++) {
                         sample = (data[pos++] & 0xFFFF) *
                            invChannelMulipliers[chan] + channelMinValues[chan];
                         wr.setSample(row, col, chan, sample);
                    }
                }
            }
        }
    }

    /**
     * Scales one pixel and puts obtained values to the chanData
     * @param pixelData - input pixel
     * @param chanData - output buffer
     * @param chanDataOffset - output buffer offset
     */
    public void scale(float[] pixelData, short[] chanData, int chanDataOffset) {
        for (int chan = 0; chan < nColorChannels; chan++) {
            chanData[chanDataOffset + chan] =
                    (short) ((pixelData[chan] - channelMinValues[chan]) *
                        channelMulipliers[chan] + 0.5f);
        }
    }

    /**
     * Unscales one pixel and puts obtained values to the pixelData
     * @param pixelData - output pixel
     * @param chanData - input buffer
     * @param chanDataOffset - input buffer offset
     */
    public void unscale(float[] pixelData, short[] chanData, int chanDataOffset) {
        for (int chan = 0; chan < nColorChannels; chan++) {
            pixelData[chan] = (chanData[chanDataOffset + chan] & 0xFFFF)
                * invChannelMulipliers[chan] + channelMinValues[chan];
        }
    }
}