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


package android.filterfw.core;

import android.compat.annotation.UnsupportedAppUsage;

/**
 * @hide
 */
public abstract class FrameManager {

    private FilterContext mContext;

    @UnsupportedAppUsage
    public abstract Frame newFrame(FrameFormat format);

    @UnsupportedAppUsage
    public abstract Frame newBoundFrame(FrameFormat format, int bindingType, long bindingId);

    @UnsupportedAppUsage
    public Frame duplicateFrame(Frame frame) {
        Frame result = newFrame(frame.getFormat());
        result.setDataFromFrame(frame);
        return result;
    }

    public Frame duplicateFrameToTarget(Frame frame, int newTarget) {
        MutableFrameFormat newFormat = frame.getFormat().mutableCopy();
        newFormat.setTarget(newTarget);
        Frame result = newFrame(newFormat);
        result.setDataFromFrame(frame);
        return result;
    }

    public abstract Frame retainFrame(Frame frame);

    public abstract Frame releaseFrame(Frame frame);

    public FilterContext getContext() {
        return mContext;
    }

    public GLEnvironment getGLEnvironment() {
        return mContext != null ? mContext.getGLEnvironment() : null;
    }

    public void tearDown() {
    }

    void setContext(FilterContext context) {
        mContext = context;
    }
}
