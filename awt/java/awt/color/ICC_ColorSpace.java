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

import org.apache.harmony.awt.gl.color.ColorConverter;
import org.apache.harmony.awt.gl.color.ColorScaler;
import org.apache.harmony.awt.gl.color.ICC_Transform;
import org.apache.harmony.awt.internal.nls.Messages;

import java.io.*;

/**
 * This class implements the abstract class ColorSpace and represents device
 * independent and device dependent color spaces. This color space is based on
 * the International Color Consortium Specification (ICC) File Format for Color
 * Profiles: <a href="http://www.color.org">http://www.color.org</a>
 * 
 * @since Android 1.0
 */
public class ICC_ColorSpace extends ColorSpace {
    
    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 3455889114070431483L;

    // Need to keep compatibility with serialized form
    /**
     * The Constant serialPersistentFields.
     */
    private static final ObjectStreamField[]
      serialPersistentFields = {
        new ObjectStreamField("thisProfile", ICC_Profile.class), //$NON-NLS-1$
        new ObjectStreamField("minVal", float[].class), //$NON-NLS-1$
        new ObjectStreamField("maxVal", float[].class), //$NON-NLS-1$
        new ObjectStreamField("diffMinMax", float[].class), //$NON-NLS-1$
        new ObjectStreamField("invDiffMinMax", float[].class), //$NON-NLS-1$
        new ObjectStreamField("needScaleInit", Boolean.TYPE) //$NON-NLS-1$
    };


   /**
     * According to ICC specification (from http://www.color.org) "For the
     * CIEXYZ encoding, each component (X, Y, and Z) is encoded as a
     * u1Fixed15Number". This means that max value for this encoding is 1 +
     * (32767/32768)
     */
    private static final float MAX_XYZ = 1f + (32767f/32768f);
    
    /**
     * The Constant MAX_SHORT.
     */
    private static final float MAX_SHORT = 65535f;
    
    /**
     * The Constant INV_MAX_SHORT.
     */
    private static final float INV_MAX_SHORT = 1f/MAX_SHORT;
    
    /**
     * The Constant SHORT2XYZ_FACTOR.
     */
    private static final float SHORT2XYZ_FACTOR = MAX_XYZ/MAX_SHORT;
    
    /**
     * The Constant XYZ2SHORT_FACTOR.
     */
    private static final float XYZ2SHORT_FACTOR = MAX_SHORT/MAX_XYZ;

    /**
     * The profile.
     */
    private ICC_Profile profile = null;
    
    /**
     * The min values.
     */
    private float minValues[] = null;
    
    /**
     * The max values.
     */
    private float maxValues[] = null;

    // cache transforms here - performance gain
    /**
     * The to rgb transform.
     */
    private ICC_Transform toRGBTransform = null;
    
    /**
     * The from rgb transform.
     */
    private ICC_Transform fromRGBTransform = null;
    
    /**
     * The to xyz transform.
     */
    private ICC_Transform toXYZTransform = null;
    
    /**
     * The from xyz transform.
     */
    private ICC_Transform fromXYZTransform = null;

    /**
     * The converter.
     */
    private final ColorConverter converter = new ColorConverter();
    
    /**
     * The scaler.
     */
    private final ColorScaler scaler = new ColorScaler();
    
    /**
     * The scaling data loaded.
     */
    private boolean scalingDataLoaded = false;

    /**
     * The resolved deserialized inst.
     */
    private ICC_ColorSpace resolvedDeserializedInst;

    /**
     * Instantiates a new ICC color space from an ICC_Profile object.
     * 
     * @param pf
     *            the ICC_Profile object.
     */
    public ICC_ColorSpace(ICC_Profile pf) {
        super(pf.getColorSpaceType(), pf.getNumComponents());

        int pfClass = pf.getProfileClass();

        switch (pfClass) {
            case ICC_Profile.CLASS_COLORSPACECONVERSION:
            case ICC_Profile.CLASS_DISPLAY:
            case ICC_Profile.CLASS_OUTPUT:
            case ICC_Profile.CLASS_INPUT:
                break; // OK, it is color conversion profile
            default:
                // awt.168=Invalid profile class.
                throw new IllegalArgumentException(Messages.getString("awt.168")); //$NON-NLS-1$
        }

        profile = pf;
        fillMinMaxValues();
    }

    /**
     * Gets the ICC_Profile for this ICC_ColorSpace.
     * 
     * @return the ICC_Profile for this ICC_ColorSpace.
     */
    public ICC_Profile getProfile() {
        if (profile instanceof ICC_ProfileStub) {
            profile = ((ICC_ProfileStub) profile).loadProfile();
        }

        return profile;
    }

    /**
     * Performs the transformation of a color from this ColorSpace into the RGB
     * color space.
     * 
     * @param colorvalue
     *            the color value in this ColorSpace.
     * @return the float array with color components in the RGB color space.
     */
    @Override
    public float[] toRGB(float[] colorvalue) {
        if (toRGBTransform == null) {
            ICC_Profile sRGBProfile =
                ((ICC_ColorSpace) ColorSpace.getInstance(CS_sRGB)).getProfile();
            ICC_Profile[] profiles = {getProfile(), sRGBProfile};
            toRGBTransform = new ICC_Transform(profiles);
            if (!scalingDataLoaded) {
                scaler.loadScalingData(this);
                scalingDataLoaded = true;
            }
        }

        short[] data = new short[getNumComponents()];

        scaler.scale(colorvalue, data, 0);

        short[] converted =
            converter.translateColor(toRGBTransform, data, null);

        // unscale to sRGB
        float[] res = new float[3];

        res[0] = ((converted[0] & 0xFFFF)) * INV_MAX_SHORT;
        res[1] = ((converted[1] & 0xFFFF)) * INV_MAX_SHORT;
        res[2] = ((converted[2] & 0xFFFF)) * INV_MAX_SHORT;

        return res;
    }

    /**
     * Performs the transformation of a color from this ColorSpace into the
     * CS_CIEXYZ color space.
     * 
     * @param colorvalue
     *            the color value in this ColorSpace.
     * @return the float array with color components in the CS_CIEXYZ color
     *         space.
     */
    @Override
    public float[] toCIEXYZ(float[] colorvalue) {
        if (toXYZTransform == null) {
            ICC_Profile xyzProfile =
                ((ICC_ColorSpace) ColorSpace.getInstance(CS_CIEXYZ)).getProfile();
            ICC_Profile[] profiles = {getProfile(), xyzProfile};
            try {
                int[] intents = {
                        ICC_Profile.icRelativeColorimetric,
                        ICC_Profile.icPerceptual};
                toXYZTransform = new ICC_Transform(profiles, intents);
            } catch (CMMException e) { // No such tag, use what we can
                toXYZTransform = new ICC_Transform(profiles);
            }

            if (!scalingDataLoaded) {
                scaler.loadScalingData(this);
                scalingDataLoaded = true;
            }
        }

        short[] data = new short[getNumComponents()];

        scaler.scale(colorvalue, data, 0);

        short[] converted =
            converter.translateColor(toXYZTransform, data, null);

        // unscale to XYZ
        float[] res = new float[3];

        res[0] = ((converted[0] & 0xFFFF)) * SHORT2XYZ_FACTOR;
        res[1] = ((converted[1] & 0xFFFF)) * SHORT2XYZ_FACTOR;
        res[2] = ((converted[2] & 0xFFFF)) * SHORT2XYZ_FACTOR;

        return res;
    }

    /**
     * Performs the transformation of a color from the RGB color space into this
     * ColorSpace.
     * 
     * @param rgbvalue
     *            the float array representing a color in the RGB color space.
     * @return the float array with the transformed color components.
     */
    @Override
    public float[] fromRGB(float[] rgbvalue) {
        if (fromRGBTransform == null) {
            ICC_Profile sRGBProfile =
                ((ICC_ColorSpace) ColorSpace.getInstance(CS_sRGB)).getProfile();
            ICC_Profile[] profiles = {sRGBProfile, getProfile()};
            fromRGBTransform = new ICC_Transform(profiles);
            if (!scalingDataLoaded) {
                scaler.loadScalingData(this);
                scalingDataLoaded = true;
            }
        }

        // scale rgb value to short
        short[] scaledRGBValue = new short[3];
        scaledRGBValue[0] = (short)(rgbvalue[0] * MAX_SHORT + 0.5f);
        scaledRGBValue[1] = (short)(rgbvalue[1] * MAX_SHORT + 0.5f);
        scaledRGBValue[2] = (short)(rgbvalue[2] * MAX_SHORT + 0.5f);

        short[] converted =
            converter.translateColor(fromRGBTransform, scaledRGBValue, null);

        float[] res = new float[getNumComponents()];

        scaler.unscale(res, converted, 0);

        return res;
    }

    /**
     * Performs the transformation of a color from the CS_CIEXYZ color space
     * into this ColorSpace.
     * 
     * @param xyzvalue
     *            the float array representing a color in the CS_CIEXYZ color
     *            space.
     * @return the float array with the transformed color components.
     */
    @Override
    public float[] fromCIEXYZ(float[] xyzvalue) {
        if (fromXYZTransform == null) {
            ICC_Profile xyzProfile =
                ((ICC_ColorSpace) ColorSpace.getInstance(CS_CIEXYZ)).getProfile();
            ICC_Profile[] profiles = {xyzProfile, getProfile()};
            try {
                int[] intents = {
                        ICC_Profile.icPerceptual,
                        ICC_Profile.icRelativeColorimetric};
                fromXYZTransform = new ICC_Transform(profiles, intents);
            } catch (CMMException e) { // No such tag, use what we can
                fromXYZTransform = new ICC_Transform(profiles);
            }

            if (!scalingDataLoaded) {
                scaler.loadScalingData(this);
                scalingDataLoaded = true;
            }

        }

        // scale xyz value to short
        short[] scaledXYZValue = new short[3];
        scaledXYZValue[0] = (short)(xyzvalue[0] * XYZ2SHORT_FACTOR + 0.5f);
        scaledXYZValue[1] = (short)(xyzvalue[1] * XYZ2SHORT_FACTOR + 0.5f);
        scaledXYZValue[2] = (short)(xyzvalue[2] * XYZ2SHORT_FACTOR + 0.5f);

        short[] converted =
            converter.translateColor(fromXYZTransform, scaledXYZValue, null);

        float[] res = new float[getNumComponents()];

        scaler.unscale(res, converted, 0);

        return res;
    }

    /**
     * Gets the minimum normalized color component value for the specified
     * component.
     * 
     * @param component
     *            the component to determine the minimum value.
     * @return the minimum normalized value of the component.
     */
    @Override
    public float getMinValue(int component) {
        if ((component < 0) || (component > this.getNumComponents() - 1)) {
            // awt.169=Component index out of range
            throw new IllegalArgumentException(Messages.getString("awt.169")); //$NON-NLS-1$
        }

        return minValues[component];
    }

    /**
     * Gets the maximum normalized color component value for the specified
     * component.
     * 
     * @param component
     *            the component to determine the maximum value.
     * @return the maximum normalized value of the component.
     */
    @Override
    public float getMaxValue(int component) {
        if ((component < 0) || (component > this.getNumComponents() - 1)) {
            // awt.169=Component index out of range
            throw new IllegalArgumentException(Messages.getString("awt.169")); //$NON-NLS-1$
        }

        return maxValues[component];
    }

    /**
     * Fill min max values.
     */
    private void fillMinMaxValues() {
        int n = getNumComponents();
        maxValues = new float[n];
        minValues = new float[n];
        switch (getType()) {
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
                for(int i=0; i<n; i++) {
                    minValues[i] = 0;
                    maxValues[i] = 1;
                }
        }
    }

    /**
     * Write object.
     * 
     * @param out
     *            the out
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        ObjectOutputStream.PutField fields = out.putFields();

        fields.put("thisProfile", profile); //$NON-NLS-1$
        fields.put("minVal", null); //$NON-NLS-1$
        fields.put("maxVal", null); //$NON-NLS-1$
        fields.put("diffMinMax", null); //$NON-NLS-1$
        fields.put("invDiffMinMax", null); //$NON-NLS-1$
        fields.put("needScaleInit", true); //$NON-NLS-1$

        out.writeFields();
    }

    /**
     * Read object.
     * 
     * @param in
     *            the in
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     * @throws ClassNotFoundException
     *             the class not found exception
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        ObjectInputStream.GetField fields = in.readFields();
        resolvedDeserializedInst =
                new ICC_ColorSpace((ICC_Profile) fields.get("thisProfile", null)); //$NON-NLS-1$
    }

    /**
     * Read resolve.
     * 
     * @return the object
     * @throws ObjectStreamException
     *             the object stream exception
     */
    Object readResolve() throws ObjectStreamException {
        return resolvedDeserializedInst;
    }
}

