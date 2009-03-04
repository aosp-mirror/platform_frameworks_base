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

import org.apache.harmony.awt.gl.color.NativeCMM;

/**
 * This class encapsulates native ICC transform object, is responsible for its
 * creation, destruction and passing its handle to the native CMM.
 */
public class ICC_Transform {
    private long transformHandle;
    private int numInputChannels;
    private int numOutputChannels;
    private ICC_Profile src;
    private ICC_Profile dst;


    /**
     * @return Returns the number of input channels.
     */
    public int getNumInputChannels() {
        return numInputChannels;
    }

    /**
     * @return Returns the number of output channels.
     */
    public int getNumOutputChannels() {
        return numOutputChannels;
    }

    /**
     * @return Returns the dst.
     */
    public ICC_Profile getDst() {
        return dst;
    }

    /**
     * @return Returns the src.
     */
    public ICC_Profile getSrc() {
        return src;
    }

    /**
     * Constructs a multiprofile ICC transform
     * @param profiles - list of ICC profiles
     * @param renderIntents - only hints for CMM
     */
    public ICC_Transform(ICC_Profile[] profiles, int[] renderIntents) {
        int numProfiles = profiles.length;

        long[] profileHandles = new long[numProfiles];
        for (int i=0; i<numProfiles; i++) {
            profileHandles[i] = NativeCMM.getHandle(profiles[i]);
        }

        transformHandle = NativeCMM.cmmCreateMultiprofileTransform(
                profileHandles,
                renderIntents);

        src = profiles[0];
        dst = profiles[numProfiles-1];
        numInputChannels = src.getNumComponents();
        numOutputChannels = dst.getNumComponents();
    }

    /**
     * This constructor is able to set intents by default
     * @param profiles - list of ICC profiles
     */
    public ICC_Transform(ICC_Profile[] profiles) {
        int numProfiles = profiles.length;
        int[] renderingIntents = new int[numProfiles];

        // Default is perceptual
        int currRenderingIntent = ICC_Profile.icPerceptual;

        // render as colorimetric for output device
        if (profiles[0].getProfileClass() == ICC_Profile.CLASS_OUTPUT) {
            currRenderingIntent = ICC_Profile.icRelativeColorimetric;
        }

        // get the transforms from each profile
        for (int i = 0; i < numProfiles; i++) {
            // first or last profile cannot be abstract
            // if profile is abstract, the only possible way is
            // use AToB0Tag (perceptual), see ICC spec
            if (i != 0 &&
               i != numProfiles - 1 &&
               profiles[i].getProfileClass() == ICC_Profile.CLASS_ABSTRACT
            ) {
                currRenderingIntent = ICC_Profile.icPerceptual;
            }

            renderingIntents[i] = currRenderingIntent;
            // use current rendering intent
            // to select LUT from the next profile (chaining)
            currRenderingIntent =
                ICC_ProfileHelper.getRenderingIntent(profiles[i]);
        }

        // Get the profile handles and go ahead
        long[] profileHandles = new long[numProfiles];
        for (int i=0; i<numProfiles; i++) {
            profileHandles[i] = NativeCMM.getHandle(profiles[i]);
        }

        transformHandle = NativeCMM.cmmCreateMultiprofileTransform(
                profileHandles,
                renderingIntents);

        src = profiles[0];
        dst = profiles[numProfiles-1];
        numInputChannels = src.getNumComponents();
        numOutputChannels = dst.getNumComponents();
    }

    @Override
    protected void finalize() {
        if (transformHandle != 0) {
            NativeCMM.cmmDeleteTransform(transformHandle);
        }
    }

    /**
     * Invokes native color conversion
     * @param src - source image format
     * @param dst - destination image format
     */
    public void translateColors(NativeImageFormat src, NativeImageFormat dst) {
        NativeCMM.cmmTranslateColors(transformHandle, src, dst);
    }
}