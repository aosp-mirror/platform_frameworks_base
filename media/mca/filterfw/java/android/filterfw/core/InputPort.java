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

/**
 * @hide
 */
public abstract class InputPort extends FilterPort {

    protected OutputPort mSourcePort;

    public InputPort(Filter filter, String name) {
        super(filter, name);
    }

    public void setSourcePort(OutputPort source) {
        if (mSourcePort != null) {
            throw new RuntimeException(this + " already connected to " + mSourcePort + "!");
        }
        mSourcePort = source;
    }

    public boolean isConnected() {
        return mSourcePort != null;
    }

    public void open() {
        super.open();
        if (mSourcePort != null && !mSourcePort.isOpen()) {
            mSourcePort.open();
        }
    }

    public void close() {
        if (mSourcePort != null && mSourcePort.isOpen()) {
            mSourcePort.close();
        }
        super.close();
    }

    public OutputPort getSourcePort() {
        return mSourcePort;
    }

    public Filter getSourceFilter() {
        return mSourcePort == null ? null : mSourcePort.getFilter();
    }

    public FrameFormat getSourceFormat() {
        return mSourcePort != null ? mSourcePort.getPortFormat() : getPortFormat();
    }

    public Object getTarget() {
        return null;
    }

    public boolean filterMustClose() {
        return !isOpen() && isBlocking() && !hasFrame();
    }

    public boolean isReady() {
        return hasFrame() || !isBlocking();
    }

    public boolean acceptsFrame() {
        return !hasFrame();
    }

    public abstract void transfer(FilterContext context);
}
