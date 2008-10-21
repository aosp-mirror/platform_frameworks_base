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

import java.awt.color.ICC_Profile;

/**
 * Includes utility methods for reading ICC profile data.
 * Created to provide public access to ICC_Profile methods
 * for classes outside of java.awt.color
 */
public class ICC_ProfileHelper {
    /**
     * Utility method.
     * Gets integer value from the byte array
     * @param byteArray - byte array
     * @param idx - byte offset
     * @return integer value
     */
    public static int getIntFromByteArray(byte[] byteArray, int idx) {
        return (byteArray[idx] & 0xFF)|
               ((byteArray[idx+1] & 0xFF) << 8) |
               ((byteArray[idx+2] & 0xFF) << 16)|
               ((byteArray[idx+3] & 0xFF) << 24);
    }

    /**
     * Utility method.
     * Gets big endian integer value from the byte array
     * @param byteArray - byte array
     * @param idx - byte offset
     * @return integer value
     */
    public static int getBigEndianFromByteArray(byte[] byteArray, int idx) {
        return ((byteArray[idx] & 0xFF) << 24)   |
               ((byteArray[idx+1] & 0xFF) << 16) |
               ((byteArray[idx+2] & 0xFF) << 8)  |
               ( byteArray[idx+3] & 0xFF);
    }

    /**
     * Utility method.
     * Gets short value from the byte array
     * @param byteArray - byte array
     * @param idx - byte offset
     * @return short value
     */
    public static short getShortFromByteArray(byte[] byteArray, int idx) {
        return (short) ((byteArray[idx] & 0xFF) |
                       ((byteArray[idx+1] & 0xFF) << 8));
    }

    /**
     * Used in ICC_Transform class to check the rendering intent of the profile
     * @param profile - ICC profile
     * @return rendering intent
     */
    public static int getRenderingIntent(ICC_Profile profile) {
        return getIntFromByteArray(
                profile.getData(ICC_Profile.icSigHead), // pf header
                ICC_Profile.icHdrRenderingIntent
            );
    }
}
