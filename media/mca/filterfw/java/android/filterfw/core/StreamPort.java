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
public class StreamPort extends InputPort {

    private Frame mFrame;
    private boolean mPersistent;

    public StreamPort(Filter filter, String name) {
        super(filter, name);
    }

    @Override
    public void clear() {
        if (mFrame != null) {
            mFrame.release();
            mFrame = null;
        }
    }

    @Override
    public void setFrame(Frame frame) {
        assignFrame(frame, true);
    }

    @Override
    public void pushFrame(Frame frame) {
        assignFrame(frame, false);
    }

    protected synchronized void assignFrame(Frame frame, boolean persistent) {
        assertPortIsOpen();
        checkFrameType(frame, persistent);

        if (persistent) {
            if (mFrame != null) {
                mFrame.release();
            }
        } else if (mFrame != null) {
            throw new RuntimeException(
                "Attempting to push more than one frame on port: " + this + "!");
        }
        mFrame = frame.retain();
        mFrame.markReadOnly();
        mPersistent = persistent;
    }

    @Override
    public synchronized Frame pullFrame() {
        // Make sure we have a frame
        if (mFrame == null) {
            throw new RuntimeException("No frame available to pull on port: " + this + "!");
        }

        // Return a retained result
        Frame result = mFrame;
        if (mPersistent) {
            mFrame.retain();
        } else {
            mFrame = null;
        }
        return result;
    }

    @Override
    public synchronized boolean hasFrame() {
        return mFrame != null;
    }

    @Override
    public String toString() {
        return "input " + super.toString();
    }

    @Override
    public synchronized void transfer(FilterContext context) {
        if (mFrame != null) {
            checkFrameManager(mFrame, context);
        }
    }
}
