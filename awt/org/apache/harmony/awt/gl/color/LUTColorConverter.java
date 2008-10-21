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
/*
 * Created on 02.11.2004
 *
 */
package org.apache.harmony.awt.gl.color;

import java.awt.color.ColorSpace;

public class LUTColorConverter {

    private static byte from8lRGBtosRGB_LUT[];

    private static byte from16lRGBtosRGB_LUT[];

    private static byte fromsRGBto8lRGB_LUT[];

    private static short fromsRGBto16lRGB_LUT[];

    private static byte fromsRGBto8sRGB_LUTs[][];

    public static ColorSpace LINEAR_RGB_CS;

    public static ColorSpace LINEAR_GRAY_CS;

    public static ColorSpace sRGB_CS;

    public LUTColorConverter() {
    }

    /*
     * This class prepared and returned lookup tables for conversion color 
     * values from Linear RGB Color Space to sRGB and vice versa.
     * Conversion is producing according to sRGB Color Space definition.
     * "A Standard Default Color Space for the Internet - sRGB",
     *  Michael Stokes (Hewlett-Packard), Matthew Anderson (Microsoft), 
     * Srinivasan Chandrasekar (Microsoft), Ricardo Motta (Hewlett-Packard) 
     * Version 1.10, November 5, 1996 
     * This document is available: http://www.w3.org/Graphics/Color/sRGB
     */
    public static byte[] getFrom8lRGBtosRGB_LUT() {
        if (from8lRGBtosRGB_LUT == null) {
            from8lRGBtosRGB_LUT = new byte[256];
            float v;
            for (int i = 0; i < 256; i++) {
                v = (float)i / 255;
                v = (v <= 0.04045f) ? v / 12.92f :
                    (float) Math.pow((v + 0.055) / 1.055, 2.4);
                from8lRGBtosRGB_LUT[i] = (byte) Math.round(v * 255.0f);
            }
        }
        return from8lRGBtosRGB_LUT;
    }

    public static byte[] getFrom16lRGBtosRGB_LUT() {
        if (from16lRGBtosRGB_LUT == null) {
            from16lRGBtosRGB_LUT = new byte[65536];
            float v;
            for (int i = 0; i < 65536; i++) {
                v = (float) i / 65535;
                v = (v <= 0.04045f) ? v / 12.92f :
                    (float) Math.pow((v + 0.055) / 1.055, 2.4);
                from16lRGBtosRGB_LUT[i] = (byte) Math.round(v * 255.0f);
            }
        }
        return from16lRGBtosRGB_LUT;
    }

    public static byte[] getFromsRGBto8lRGB_LUT() {
        if (fromsRGBto8lRGB_LUT == null) {
            fromsRGBto8lRGB_LUT = new byte[256];
            float v;
            for (int i = 0; i < 256; i++) {
                v = (float) i / 255;
                v = (v <= 0.0031308f) ? v * 12.92f :
                    ((float) Math.pow(v, 1.0 / 2.4)) * 1.055f - 0.055f;
                fromsRGBto8lRGB_LUT[i] = (byte) Math.round(v * 255.0f);
            }
        }
        return fromsRGBto8lRGB_LUT;
    }

    public static short[] getFromsRGBto16lRGB_LUT() {
        if (fromsRGBto16lRGB_LUT == null) {
            fromsRGBto16lRGB_LUT = new short[256];
            float v;
            for (int i = 0; i < 256; i++) {
                v = (float) i / 255;
                v = (v <= 0.0031308f) ? v * 12.92f :
                    ((float) Math.pow(v, 1.0 / 2.4)) * 1.055f - 0.055f;
                fromsRGBto16lRGB_LUT[i] = (short) Math.round(v * 65535.0f);
            }
        }
        return fromsRGBto16lRGB_LUT;
    }

    public static byte[] getsRGBLUT(int bits) {
        if (bits < 1) return null;
        int idx = bits -1;
        if(fromsRGBto8sRGB_LUTs == null) fromsRGBto8sRGB_LUTs = new byte[16][];

        if(fromsRGBto8sRGB_LUTs[idx] == null){
            fromsRGBto8sRGB_LUTs[idx] = createLUT(bits);
        }
        return fromsRGBto8sRGB_LUTs[idx];
    }

    private static byte[] createLUT(int bits) {
        int lutSize = (1 << bits);
        byte lut[] = new byte[lutSize];
        for (int i = 0; i < lutSize; i++) {
            lut[i] = (byte) (255.0f / (lutSize - 1) + 0.5f);
        }
        return lut;
    }

    public static boolean is_LINEAR_RGB_CS(ColorSpace cs) {
        return (cs == LINEAR_RGB_CS);
    }

    public static boolean is_LINEAR_GRAY_CS(ColorSpace cs) {
        return (cs == LINEAR_GRAY_CS);
    }

    public static boolean is_sRGB_CS(ColorSpace cs) {
        return (cs == sRGB_CS);
    }

}