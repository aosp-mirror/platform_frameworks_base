/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.display;

/**
 * @hide
 */
public final class DisplayedContentSamplingAttributes {
    private int mPixelFormat;
    private int mDataspace;
    private int mComponentMask;

    /* Creates the attributes reported by the display hardware about what capabilities
     * are present.
     *
     * NOTE: the format and ds constants must match the values from graphics/common/x.x/types.hal
     * @param format the format that the display hardware samples in.
     * @param ds the dataspace in use when sampling.
     * @param componentMask a mask of which of the format components are supported.
    */
    public DisplayedContentSamplingAttributes(int format, int ds, int componentMask) {
        mPixelFormat = format;
        mDataspace = ds;
        mComponentMask = componentMask;
    }

    /* Returns the pixel format that the display hardware uses when sampling.
     *
     * NOTE: the returned constant matches the values from graphics/common/x.x/types.hal
     * @return the format that the samples were collected in.
     */
    public int getPixelFormat() {
        return mPixelFormat;
    }

    /* Returns the dataspace that the display hardware uses when sampling.
     *
     * NOTE: the returned constant matches the values from graphics/common/x.x/types.hal
     * @return the dataspace that the samples were collected in.
     */
    public int getDataspace() {
        return mDataspace;
    }

    /* Returns a mask of which components can be collected by the sampling engine.
     *
     * @return a mask of the components which are supported by the engine. The lowest
     * bit corresponds to the lowest component (ie, 0x1 corresponds to A for RGBA).
     */
    public int getComponentMask() {
        return mComponentMask;
    }
}
