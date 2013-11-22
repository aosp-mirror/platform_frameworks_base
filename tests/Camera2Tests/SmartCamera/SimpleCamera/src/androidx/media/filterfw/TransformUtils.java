/*
 * Copyright (C) 2011 The Android Open Source Project
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

package androidx.media.filterpacks.transform;

import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameImage2D;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.TextureSource;

import java.util.Arrays;

/** Internal class that contains utility functions used by the transform filters. **/
class TransformUtils {

    public static int powOf2(int x) {
        --x;
        // Fill with 1s
        x |= x >> 1;
        x |= x >> 2;
        x |= x >> 4;
        x |= x >> 8;
        x |= x >> 16;
        // Next int is now pow-of-2
        return x + 1;
    }

    public static FrameImage2D makeMipMappedFrame(FrameImage2D current, int[] dimensions) {
        // Note: Future versions of GLES will support NPOT mipmapping. When these become more
        // widely used, we can add a check here to disable frame expansion on such devices.
        int[] pow2Dims = new int[] { powOf2(dimensions[0]), powOf2(dimensions[1]) };
        if (current == null) {
            FrameType imageType = FrameType.image2D(FrameType.ELEMENT_RGBA8888,
                                                    FrameType.READ_GPU | FrameType.WRITE_GPU);
            current = Frame.create(imageType, pow2Dims).asFrameImage2D();
        } else if (!Arrays.equals(dimensions, current.getDimensions())) {
            current.resize(pow2Dims);
        }
        return current;
    }

    public static FrameImage2D makeTempFrame(FrameImage2D current, int[] dimensions) {
        if (current == null) {
            FrameType imageType = FrameType.image2D(FrameType.ELEMENT_RGBA8888,
                                                    FrameType.READ_GPU | FrameType.WRITE_GPU);
            current = Frame.create(imageType, dimensions).asFrameImage2D();
        } else if (!Arrays.equals(dimensions, current.getDimensions())) {
            current.resize(dimensions);
        }
        return current;
    }

    public static void generateMipMaps(FrameImage2D frame) {
        TextureSource texture = frame.lockTextureSource();
        texture.generateMipmaps();
        frame.unlock();
    }

    public static void setTextureParameter(FrameImage2D frame, int param, int value) {
        TextureSource texture = frame.lockTextureSource();
        texture.setParameter(param, value);
        frame.unlock();
    }

}
