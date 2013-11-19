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


package android.filterpacks.imageproc;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.format.ImageFormat;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;

import java.io.OutputStream;

/**
 * @hide
 */
public class ImageEncoder extends Filter {

    @GenerateFieldPort(name = "stream")
    private OutputStream mOutputStream;

    @GenerateFieldPort(name = "quality", hasDefault = true)
    private int mQuality = 80;

    public ImageEncoder(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addMaskedInputPort("image", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                       FrameFormat.TARGET_UNSPECIFIED));
    }

    @Override
    public void process(FilterContext env) {
        Frame input = pullInput("image");
        Bitmap bitmap = input.getBitmap();
        bitmap.compress(CompressFormat.JPEG, mQuality, mOutputStream);
    }

}
