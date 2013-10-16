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

import androidx.media.filterfw.*;

// TODO: In the future this could be done with a meta-filter that simply "hard-codes" the crop
// parameters.
public class ResizeFilter extends CropFilter {

    public ResizeFilter(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        FrameType imageIn = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.READ_GPU);
        FrameType imageOut = FrameType.image2D(FrameType.ELEMENT_RGBA8888, FrameType.WRITE_GPU);
        return new Signature()
            .addInputPort("image", Signature.PORT_REQUIRED, imageIn)
            .addInputPort("outputWidth", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .addInputPort("outputHeight", Signature.PORT_OPTIONAL, FrameType.single(int.class))
            .addInputPort("useMipmaps", Signature.PORT_OPTIONAL, FrameType.single(boolean.class))
            .addOutputPort("image", Signature.PORT_REQUIRED, imageOut)
            .disallowOtherPorts();
    }
}
