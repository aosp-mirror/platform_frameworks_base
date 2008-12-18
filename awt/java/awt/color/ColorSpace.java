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
package java.awt.color;

import java.io.Serializable;

import org.apache.harmony.awt.gl.color.LUTColorConverter;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The ColorSpace class defines a color space type for a Color and provides
 * methods for arrays of color component operations.
 * 
 * @since Android 1.0
 */
public abstract class ColorSpace implements Serializable {

    /** The Constant serialVersionUID. */
    private static final long serialVersionUID = -409452704308689724L;

    /**
     * The Constant TYPE_XYZ indicates XYZ color space type.
     */
    public static final int TYPE_XYZ = 0;

    /**
     * The Constant TYPE_Lab indicates Lab color space type.
     */
    public static final int TYPE_Lab = 1;

    /**
     * The Constant TYPE_Luv indicates Luv color space type.
     */
    public static final int TYPE_Luv = 2;

    /**
     * The Constant TYPE_YCbCr indicates YCbCr color space type.
     */
    public static final int TYPE_YCbCr = 3;

    /**
     * The Constant TYPE_Yxy indicates Yxy color space type.
     */
    public static final int TYPE_Yxy = 4;

    /**
     * The Constant TYPE_RGB indicates RGB color space type.
     */
    public static final int TYPE_RGB = 5;

    /**
     * The Constant TYPE_GRAY indicates Gray color space type.
     */
    public static final int TYPE_GRAY = 6;

    /**
     * The Constant TYPE_HSV indicates HSV color space type.
     */
    public static final int TYPE_HSV = 7;

    /**
     * The Constant TYPE_HLS indicates HLS color space type.
     */
    public static final int TYPE_HLS = 8;

    /**
     * The Constant TYPE_CMYK indicates CMYK color space type.
     */
    public static final int TYPE_CMYK = 9;

    /**
     * The Constant TYPE_CMY indicates CMY color space type.
     */
    public static final int TYPE_CMY = 11;

    /**
     * The Constant TYPE_2CLR indicates color spaces with 2 components.
     */
    public static final int TYPE_2CLR = 12;

    /**
     * The Constant TYPE_3CLR indicates color spaces with 3 components.
     */
    public static final int TYPE_3CLR = 13;

    /**
     * The Constant TYPE_4CLR indicates color spaces with 4 components.
     */
    public static final int TYPE_4CLR = 14;

    /**
     * The Constant TYPE_5CLR indicates color spaces with 5 components.
     */
    public static final int TYPE_5CLR = 15;

    /**
     * The Constant TYPE_6CLR indicates color spaces with 6 components.
     */
    public static final int TYPE_6CLR = 16;

    /**
     * The Constant TYPE_7CLR indicates color spaces with 7 components.
     */
    public static final int TYPE_7CLR = 17;

    /**
     * The Constant TYPE_8CLR indicates color spaces with 8 components.
     */
    public static final int TYPE_8CLR = 18;

    /**
     * The Constant TYPE_9CLR indicates color spaces with 9 components.
     */
    public static final int TYPE_9CLR = 19;

    /**
     * The Constant TYPE_ACLR indicates color spaces with 10 components.
     */
    public static final int TYPE_ACLR = 20;

    /**
     * The Constant TYPE_BCLR indicates color spaces with 11 components.
     */
    public static final int TYPE_BCLR = 21;

    /**
     * The Constant TYPE_CCLR indicates color spaces with 12 components.
     */
    public static final int TYPE_CCLR = 22;

    /**
     * The Constant TYPE_DCLR indicates color spaces with 13 components.
     */
    public static final int TYPE_DCLR = 23;

    /**
     * The Constant TYPE_ECLR indicates color spaces with 14 components.
     */
    public static final int TYPE_ECLR = 24;

    /**
     * The Constant TYPE_FCLR indicates color spaces with 15 components.
     */
    public static final int TYPE_FCLR = 25;

    /**
     * The Constant CS_sRGB indicates standard RGB color space.
     */
    public static final int CS_sRGB = 1000;

    /**
     * The Constant CS_LINEAR_RGB indicates linear RGB color space.
     */
    public static final int CS_LINEAR_RGB = 1004;

    /**
     * The Constant CS_CIEXYZ indicates CIEXYZ conversion color space.
     */
    public static final int CS_CIEXYZ = 1001;

    /**
     * The Constant CS_PYCC indicates Photo YCC conversion color space.
     */
    public static final int CS_PYCC = 1002;

    /**
     * The Constant CS_GRAY indicates linear gray scale color space.
     */
    public static final int CS_GRAY = 1003;

    /**
     * The cs_ gray.
     */
    private static ColorSpace cs_Gray = null;
    
    /**
     * The cs_ pycc.
     */
    private static ColorSpace cs_PYCC = null;
    
    /**
     * The cs_ ciexyz.
     */
    private static ColorSpace cs_CIEXYZ = null;
    
    /**
     * The cs_ lrgb.
     */
    private static ColorSpace cs_LRGB = null;
    
    /**
     * The cs_s rgb.
     */
    private static ColorSpace cs_sRGB = null;

    /**
     * The type.
     */
    private int type;
    
    /**
     * The num components.
     */
    private int numComponents;

    /**
     * Instantiates a ColorSpace with the specified ColorSpace type and number
     * of components.
     * 
     * @param type
     *            the type of color space.
     * @param numcomponents
     *            the number of components.
     */
    protected ColorSpace(int type, int numcomponents) {
        this.numComponents = numcomponents;
        this.type = type;
    }

    /**
     * Gets the name of the component for the specified component index.
     * 
     * @param idx
     *            the index of the component.
     * @return the name of the component.
     */
    public String getName(int idx) {
        if (idx < 0 || idx > numComponents - 1) {
            // awt.16A=Invalid component index: {0}
            throw new IllegalArgumentException(Messages.getString("awt.16A", idx)); //$NON-NLS-1$
        }

      return "Unnamed color component #" + idx; //$NON-NLS-1$
    }

    /**
     * Performs the transformation of a color from this ColorSpace into the RGB
     * color space.
     * 
     * @param colorvalue
     *            the color value in this ColorSpace.
     * @return the float array with color components in the RGB color space.
     */
    public abstract float[] toRGB(float[] colorvalue);

    /**
     * Performs the transformation of a color from this ColorSpace into the
     * CS_CIEXYZ color space.
     * 
     * @param colorvalue
     *            the color value in this ColorSpace.
     * @return the float array with color components in the CS_CIEXYZ color
     *         space.
     */
    public abstract float[] toCIEXYZ(float[] colorvalue);

    /**
     * Performs the transformation of a color from the RGB color space into this
     * ColorSpace.
     * 
     * @param rgbvalue
     *            the float array representing a color in the RGB color space.
     * @return the float array with the transformed color components.
     */
    public abstract float[] fromRGB(float[] rgbvalue);

    /**
     * Performs the transformation of a color from the CS_CIEXYZ color space
     * into this ColorSpace.
     * 
     * @param colorvalue
     *            the float array representing a color in the CS_CIEXYZ color
     *            space.
     * @return the float array with the transformed color components.
     */
    public abstract float[] fromCIEXYZ(float[] colorvalue);

    /**
     * Gets the minimum normalized color component value for the specified
     * component.
     * 
     * @param component
     *            the component to determine the minimum value.
     * @return the minimum normalized value of the component.
     */
    public float getMinValue(int component) {
        if (component < 0 || component > numComponents - 1) {
            // awt.16A=Invalid component index: {0}
            throw new IllegalArgumentException(Messages.getString("awt.16A", component)); //$NON-NLS-1$
        }
        return 0;
    }

    /**
     * Gets the maximum normalized color component value for the specified
     * component.
     * 
     * @param component
     *            the component to determine the maximum value.
     * @return the maximum normalized value of the component.
     */
    public float getMaxValue(int component) {
        if (component < 0 || component > numComponents - 1) {
            // awt.16A=Invalid component index: {0}
            throw new IllegalArgumentException(Messages.getString("awt.16A", component)); //$NON-NLS-1$
        }
        return 1;
    }

    /**
     * Checks if this ColorSpace has CS_sRGB type or not.
     * 
     * @return true, if this ColorSpace has CS_sRGB type, false otherwise.
     */
    public boolean isCS_sRGB() {
        // If our color space is sRGB, then cs_sRGB
        // is already initialized
        return (this == cs_sRGB);
    }

    /**
     * Gets the type of the ColorSpace.
     * 
     * @return the type of the ColorSpace.
     */
    public int getType() {
        return type;
    }

    /**
     * Gets the number of components for this ColorSpace.
     * 
     * @return the number of components.
     */
    public int getNumComponents() {
        return numComponents;
    }


    /**
     * Gets the single instance of ColorSpace with the specified ColorSpace:
     * CS_sRGB, CS_LINEAR_RGB, CS_CIEXYZ, CS_GRAY, or CS_PYCC.
     * 
     * @param colorspace
     *            the identifier of the specified Colorspace.
     * @return the single instance of the desired ColorSpace.
     */
    public static ColorSpace getInstance(int colorspace) {
        switch (colorspace) {
            case CS_sRGB:
                if (cs_sRGB == null) {
                    cs_sRGB = new ICC_ColorSpace(
                            new ICC_ProfileStub(CS_sRGB));
                    LUTColorConverter.sRGB_CS = cs_sRGB;
                            //ICC_Profile.getInstance (CS_sRGB));
                }
                return cs_sRGB;
            case CS_CIEXYZ:
                if (cs_CIEXYZ == null) {
                    cs_CIEXYZ = new ICC_ColorSpace(
                            new ICC_ProfileStub(CS_CIEXYZ));
                            //ICC_Profile.getInstance (CS_CIEXYZ));
                }
                return cs_CIEXYZ;
            case CS_GRAY:
                if (cs_Gray == null) {
                    cs_Gray = new ICC_ColorSpace(
                            new ICC_ProfileStub(CS_GRAY));
                    LUTColorConverter.LINEAR_GRAY_CS = cs_Gray;
                            //ICC_Profile.getInstance (CS_GRAY));
                }
                return cs_Gray;
            case CS_PYCC:
                if (cs_PYCC == null) {
                    cs_PYCC = new ICC_ColorSpace(
                            new ICC_ProfileStub(CS_PYCC));
                            //ICC_Profile.getInstance (CS_PYCC));
                }
                return cs_PYCC;
            case CS_LINEAR_RGB:
                if (cs_LRGB == null) {
                    cs_LRGB = new ICC_ColorSpace(
                            new ICC_ProfileStub(CS_LINEAR_RGB));
                    LUTColorConverter.LINEAR_GRAY_CS = cs_Gray;
                            //ICC_Profile.getInstance (CS_LINEAR_RGB));
                }
                return cs_LRGB;
            default:
        }

        // Unknown argument passed
        // awt.16B=Not a predefined colorspace
        throw new IllegalArgumentException(Messages.getString("Not a predefined colorspace")); //$NON-NLS-1$
    }

}