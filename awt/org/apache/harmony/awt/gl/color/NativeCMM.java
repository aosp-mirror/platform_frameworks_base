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
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;

/**
 * This class is a wrapper for the native CMM library
 */
public class NativeCMM {

    /**
     * Storage for profile handles, since they are private
     * in ICC_Profile, but we need access to them.
     */
    private static HashMap<ICC_Profile, Long> profileHandles = new HashMap<ICC_Profile, Long>();

    private static boolean isCMMLoaded;

    public static void addHandle(ICC_Profile key, long handle) {
        profileHandles.put(key, new Long(handle));
    }

    public static void removeHandle(ICC_Profile key) {
        profileHandles.remove(key);
    }

    public static long getHandle(ICC_Profile key) {
        return profileHandles.get(key).longValue();
    }

    /* ICC profile management */
    public static native long cmmOpenProfile(byte[] data);
    public static native void cmmCloseProfile(long profileID);
    public static native int cmmGetProfileSize(long profileID);
    public static native void cmmGetProfile(long profileID, byte[] data);
    public static native int cmmGetProfileElementSize(long profileID, int signature);
    public static native void cmmGetProfileElement(long profileID, int signature,
                                           byte[] data);
    public static native void cmmSetProfileElement(long profileID, int tagSignature,
                                           byte[] data);


    /* ICC transforms */
    public static native long cmmCreateMultiprofileTransform(
            long[] profileHandles,
            int[] renderingIntents
        );
    public static native void cmmDeleteTransform(long transformHandle);
    public static native void cmmTranslateColors(long transformHandle,
            NativeImageFormat src,
            NativeImageFormat dest);

    static void loadCMM() {
        if (!isCMMLoaded) {
            AccessController.doPrivileged(
                  new PrivilegedAction<Void>() {
                    public Void run() {
                        System.loadLibrary("lcmm"); //$NON-NLS-1$
                        return null;
                    }
            } );
            isCMMLoaded = true;
        }
    }

    /* load native CMM library */
    static {
        loadCMM();
    }
}
