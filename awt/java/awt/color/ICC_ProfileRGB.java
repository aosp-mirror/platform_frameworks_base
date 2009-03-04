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

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The ICC_ProfileRGB class represents profiles with RGB color space type and
 * contains the redColorantTag, greenColorantTag, blueColorantTag, redTRCTag,
 * greenTRCTag, blueTRCTag, and mediaWhitePointTag tags.
 * 
 * @since Android 1.0
 */
public class ICC_ProfileRGB extends ICC_Profile {
    
    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 8505067385152579334L;

    /**
     * Instantiates a new RGB ICC_Profile.
     * 
     * @param profileHandle
     *            the profile handle
     */
    ICC_ProfileRGB(long profileHandle) {
        super(profileHandle);
    }

    /**
     * The Constant REDCOMPONENT indicates the red component.
     */
    public static final int REDCOMPONENT = 0;

    /**
     * The Constant GREENCOMPONENT indicates the green component.
     */
    public static final int GREENCOMPONENT = 1;

    /**
     * The Constant BLUECOMPONENT indicates the blue component.
     */
    public static final int BLUECOMPONENT = 2;

    // awt.15E=Unknown component. Must be REDCOMPONENT, GREENCOMPONENT or BLUECOMPONENT.
    /**
     * The Constant UNKNOWN_COMPONENT_MSG.
     */
    private static final String UNKNOWN_COMPONENT_MSG = Messages
            .getString("awt.15E"); //$NON-NLS-1$

    /**
     * Gets the TRC.
     * 
     * @param component
     *            the tag signature.
     * @return the TRC value.
     */
    @Override
    public short[] getTRC(int component) {
        switch (component) {
            case REDCOMPONENT:
                return super.getTRC(icSigRedTRCTag);
            case GREENCOMPONENT:
                return super.getTRC(icSigGreenTRCTag);
            case BLUECOMPONENT:
                return super.getTRC(icSigBlueTRCTag);
            default:
        }

        throw new IllegalArgumentException(UNKNOWN_COMPONENT_MSG);
    }

    /**
     * Gets the gamma.
     * 
     * @param component
     *            the tag signature.
     * @return the gamma value.
     */
    @Override
    public float getGamma(int component) {
        switch (component) {
            case REDCOMPONENT:
                return super.getGamma(icSigRedTRCTag);
            case GREENCOMPONENT:
                return super.getGamma(icSigGreenTRCTag);
            case BLUECOMPONENT:
                return super.getGamma(icSigBlueTRCTag);
            default:
        }

        throw new IllegalArgumentException(UNKNOWN_COMPONENT_MSG);
    }

    /**
     * Gets a float matrix which contains the X, Y, and Z components of the
     * profile's redColorantTag, greenColorantTag, and blueColorantTag.
     * 
     * @return the float matrix which contains the X, Y, and Z components of the
     *         profile's redColorantTag, greenColorantTag, and blueColorantTag.
     */
    public float[][] getMatrix() {
        float [][] m = new float[3][3]; // The matrix

        float[] redXYZ = getXYZValue(icSigRedColorantTag);
        float[] greenXYZ = getXYZValue(icSigGreenColorantTag);
        float[] blueXYZ = getXYZValue(icSigBlueColorantTag);

        m[0][0] = redXYZ[0];
        m[1][0] = redXYZ[1];
        m[2][0] = redXYZ[2];

        m[0][1] = greenXYZ[0];
        m[1][1] = greenXYZ[1];
        m[2][1] = greenXYZ[2];

        m[0][2] = blueXYZ[0];
        m[1][2] = blueXYZ[1];
        m[2][2] = blueXYZ[2];

        return m;
    }

    /**
     * Gets the media white point.
     * 
     * @return the media white point.
     */
    @Override
    public float[] getMediaWhitePoint() {
        return super.getMediaWhitePoint();
    }
}

