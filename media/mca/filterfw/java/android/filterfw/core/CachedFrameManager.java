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

import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.SimpleFrameManager;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * @hide
 */
public class CachedFrameManager extends SimpleFrameManager {

    private SortedMap<Integer, Frame> mAvailableFrames;
    private int mStorageCapacity = 24 * 1024 * 1024; // Cap default storage to 24MB
    private int mStorageSize = 0;
    private int mTimeStamp = 0;

    public CachedFrameManager() {
        super();
        mAvailableFrames = new TreeMap<Integer, Frame>();
    }

    @Override
    public Frame newFrame(FrameFormat format) {
        Frame result = findAvailableFrame(format, Frame.NO_BINDING, 0);
        if (result == null) {
            result = super.newFrame(format);
        }
        result.setTimestamp(Frame.TIMESTAMP_NOT_SET);
        return result;
    }

    @Override
    public Frame newBoundFrame(FrameFormat format, int bindingType, long bindingId) {
        Frame result = findAvailableFrame(format, bindingType, bindingId);
        if (result == null) {
            result = super.newBoundFrame(format, bindingType, bindingId);
        }
        result.setTimestamp(Frame.TIMESTAMP_NOT_SET);
        return result;
    }

    @Override
    public Frame retainFrame(Frame frame) {
        return super.retainFrame(frame);
    }

    @Override
    public Frame releaseFrame(Frame frame) {
        if (frame.isReusable()) {
            int refCount = frame.decRefCount();
            if (refCount == 0 && frame.hasNativeAllocation()) {
                if (!storeFrame(frame)) {
                    frame.releaseNativeAllocation();
                }
                return null;
            } else if (refCount < 0) {
                throw new RuntimeException("Frame reference count dropped below 0!");
            }
        } else {
            super.releaseFrame(frame);
        }
        return frame;
    }

    public void clearCache() {
        for (Frame frame : mAvailableFrames.values()) {
            frame.releaseNativeAllocation();
        }
        mAvailableFrames.clear();
    }

    @Override
    public void tearDown() {
        clearCache();
    }

    private boolean storeFrame(Frame frame) {
        synchronized(mAvailableFrames) {
            // Make sure this frame alone does not exceed capacity
            int frameSize = frame.getFormat().getSize();
            if (frameSize > mStorageCapacity) {
                return false;
            }

            // Drop frames if adding this frame would exceed capacity
            int newStorageSize = mStorageSize + frameSize;
            while (newStorageSize > mStorageCapacity) {
                dropOldestFrame();
                newStorageSize = mStorageSize + frameSize;
            }

            // Store new frame
            frame.onFrameStore();
            mStorageSize = newStorageSize;
            mAvailableFrames.put(mTimeStamp, frame);
            ++mTimeStamp;
            return true;
        }
    }

    private void dropOldestFrame() {
        int oldest = mAvailableFrames.firstKey();
        Frame frame = mAvailableFrames.get(oldest);
        mStorageSize -= frame.getFormat().getSize();
        frame.releaseNativeAllocation();
        mAvailableFrames.remove(oldest);
    }

    private Frame findAvailableFrame(FrameFormat format, int bindingType, long bindingId) {
        // Look for a frame that is compatible with the requested format
        synchronized(mAvailableFrames) {
            for (Map.Entry<Integer, Frame> entry : mAvailableFrames.entrySet()) {
                Frame frame = entry.getValue();
                // Check that format is compatible
                if (frame.getFormat().isReplaceableBy(format)) {
                    // Check that binding is compatible (if frame is bound)
                    if ((bindingType == frame.getBindingType())
                        && (bindingType == Frame.NO_BINDING || bindingId == frame.getBindingId())) {
                        // We found one! Take it out of the set of available frames and attach the
                        // requested format to it.
                        super.retainFrame(frame);
                        mAvailableFrames.remove(entry.getKey());
                        frame.onFrameFetch();
                        frame.reset(format);
                        mStorageSize -= format.getSize();
                        return frame;
                    }
                }
            }
        }
        return null;
    }

}
