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

/**
 * The ICC_ProfileGray class represent profiles with TYPE_GRAY color space type,
 * and includes the grayTRCTag and mediaWhitePointTag tags. The gray component
 * can be transformed from a GRAY device profile color space to the CIEXYZ
 * Profile through the tone reproduction curve (TRC):
 * <p>
 * PCSY = grayTRC[deviceGray]
 * 
 * @since Android 1.0
 */
public class ICC_ProfileGray extends ICC_Profile {
    
    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -1124721290732002649L;

    /**
     * Instantiates a new iC c_ profile gray.
     * 
     * @param profileHandle
     *            the profile handle
     */
    ICC_ProfileGray(long profileHandle) {
        super(profileHandle);
    }

    /**
     * Gets the TRC as an array of shorts.
     * 
     * @return the short array of the TRC.
     */
    public short[] getTRC() {
        return super.getTRC(icSigGrayTRCTag);
    }

    /**
     * Gets the media white point.
     * 
     * @return the media white point
     */
    @Override
    public float[] getMediaWhitePoint() {
        return super.getMediaWhitePoint();
    }

    /**
     * Gets a gamma value representing the tone reproduction curve (TRC).
     * 
     * @return the gamma value representing the tone reproduction curve (TRC).
     */
    public float getGamma() {
        return super.getGamma(icSigGrayTRCTag);
    }
}

