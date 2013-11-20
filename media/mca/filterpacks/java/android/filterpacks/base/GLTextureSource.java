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


package android.filterpacks.base;

import android.filterfw.core.Filter;
import android.filterfw.core.FilterContext;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.GenerateFieldPort;
import android.filterfw.core.GLFrame;
import android.filterfw.format.ImageFormat;

/**
 * @hide
 */
public class GLTextureSource extends Filter {

    @GenerateFieldPort(name = "texId")
    private int mTexId;

    @GenerateFieldPort(name = "width")
    private int mWidth;

    @GenerateFieldPort(name = "height")
    private int mHeight;

    @GenerateFieldPort(name = "repeatFrame", hasDefault = true)
    private boolean mRepeatFrame = false;

    /* This timestamp will be used for all output frames from this source.  They
     * represent nanoseconds, and should be positive and monotonically
     * increasing.  Set to Frame.TIMESTAMP_UNKNOWN if timestamps are not
     * meaningful for these textures.
     */
    @GenerateFieldPort(name = "timestamp", hasDefault = true)
    private long mTimestamp = Frame.TIMESTAMP_UNKNOWN;

    private Frame mFrame;

    public GLTextureSource(String name) {
        super(name);
    }

    @Override
    public void setupPorts() {
        addOutputPort("frame", ImageFormat.create(ImageFormat.COLORSPACE_RGBA,
                                                  FrameFormat.TARGET_GPU));
    }

    @Override
    public void fieldPortValueUpdated(String name, FilterContext context) {
        // Release frame, so that it is recreated during the next process call
        if (mFrame != null) {
            mFrame.release();
            mFrame = null;
        }
    }

    @Override
    public void process(FilterContext context) {
        // Generate frame if not generated already
        if (mFrame == null) {
            FrameFormat outputFormat = ImageFormat.create(mWidth, mHeight,
                                                          ImageFormat.COLORSPACE_RGBA,
                                                          FrameFormat.TARGET_GPU);
            mFrame = context.getFrameManager().newBoundFrame(outputFormat,
                                                             GLFrame.EXISTING_TEXTURE_BINDING,
                                                             mTexId);
            mFrame.setTimestamp(mTimestamp);
        }

        // Push output
        pushOutput("frame", mFrame);

        if (!mRepeatFrame) {
            // Close output port as we are done here
            closeOutputPort("frame");
        }
    }

    @Override
    public void tearDown(FilterContext context) {
        if (mFrame != null) {
            mFrame.release();
        }
    }

}
