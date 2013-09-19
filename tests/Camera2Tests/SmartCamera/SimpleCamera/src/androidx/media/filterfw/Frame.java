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

package androidx.media.filterfw;

import java.util.Arrays;

/**
 * Frames are the data containers that are transported between Filters.
 *
 * Frames may be used only within a Filter during filter graph execution. Accessing Frames outside
 * of graph execution may cause unexpected results.
 *
 * There are two ways to obtain new Frame instances. You can call
 * {@link OutputPort#fetchAvailableFrame(int[])} on an OutputPort to obtain a Frame to pass to an
 * output. You can also call {@link #create(FrameType, int[])} to obtain
 * a detached Frame instance that you may hold onto in your filter. If you need to hold on to a
 * Frame that is owned by an input or output queue, you must call
 * {@link #retain()} on it.
 *
 * When you are done using a detached Frame, you must release it yourself.
 *
 * To access frame data, call any of the {@code lock}-methods. This will give you access to the
 * frame data in the desired format. You must pass in a {@code mode} indicating whether you wish
 * to read or write to the data. Writing to a read-locked Frame may produce unexpected results and
 * interfere with other filters. When you are done reading or writing to the data, you must call
 * {@link #unlock()}. Note, that a Frame must be unlocked before you push it into an output queue.
 *
 * Generally, any type of access format to a Frame's data will be granted. However, it is strongly
 * recommended to specify the access format that you intend to use in your filter's signature or
 * in the access flags passed to {@code newFrame()}. This will allow the Frame to allocate
 * the most efficient backings for the intended type of access.
 *
 * A frame can be be pushed to an OutputPort by calling the {@link OutputPort#pushFrame(Frame)}
 * method. Frames that have been pushed become read-only, and can no longer be modified.
 *
 * On the other end, a Filter can pull in an input Frame by calling {@link InputPort#pullFrame()}
 * on the desired InputPort. Such frames are always read-only.
 */
public class Frame {

    /** Special timestamp value indicating that no time-stamp was set. */
    public static final long TIMESTAMP_NOT_SET = -1;

    /** Frame data access mode: Read */
    public static final int MODE_READ = 1;
    /** Frame data access mode: Write */
    public static final int MODE_WRITE = 2;

    BackingStore mBackingStore;
    boolean mReadOnly = false;

    // Public API //////////////////////////////////////////////////////////////////////////////////
    /**
     * Returns the frame's type.
     * @return A FrameType instance describing the frame data-type.
     */
    public final FrameType getType() {
        return mBackingStore.getFrameType();
    }

    public final int getElementCount() {
        return mBackingStore.getElementCount();
    }

    /**
     * Set the frame's timestamp in nanoseconds.
     *
     * @param timestamp the timestamp of this frame in nanoseconds.
     */
    public final void setTimestamp(long timestamp) {
        mBackingStore.setTimestamp(timestamp);
    }

    /**
     * @return the frame's timestamp in nanoseconds.
     */
    public final long getTimestamp() {
        return mBackingStore.getTimestamp();
    }

    /**
     * @return the frame's timestamp in milliseconds.
     */
    public final long getTimestampMillis() {
        return mBackingStore.getTimestamp() / 1000000L;
    }

    public final boolean isReadOnly() {
        return mReadOnly;
    }

    public final FrameValue asFrameValue() {
        return FrameValue.create(mBackingStore);
    }

    public final FrameValues asFrameValues() {
        return FrameValues.create(mBackingStore);
    }

    public final FrameBuffer1D asFrameBuffer1D() {
        return FrameBuffer1D.create(mBackingStore);
    }

    public final FrameBuffer2D asFrameBuffer2D() {
        return FrameBuffer2D.create(mBackingStore);
    }

    public final FrameImage2D asFrameImage2D() {
        return FrameImage2D.create(mBackingStore);
    }

    @Override
    public String toString() {
        return "Frame[" + getType().toString() + "]: " + mBackingStore;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof Frame && ((Frame)object).mBackingStore == mBackingStore;
    }

    public static Frame create(FrameType type, int[] dimensions) {
        FrameManager manager = FrameManager.current();
        if (manager == null) {
            throw new IllegalStateException("Attempting to create new Frame outside of "
                + "FrameManager context!");
        }
        return new Frame(type, dimensions, manager);
    }

    public final Frame release() {
        mBackingStore = mBackingStore.release();
        return mBackingStore != null ? this : null;
    }

    public final Frame retain() {
        mBackingStore = mBackingStore.retain();
        return this;
    }

    public void unlock() {
        if (!mBackingStore.unlock()) {
            throw new RuntimeException("Attempting to unlock frame that is not locked!");
        }
    }

    public int[] getDimensions() {
        int[] dim = mBackingStore.getDimensions();
        return dim != null ? Arrays.copyOf(dim, dim.length) : null;
    }

    Frame(FrameType type, int[] dimensions, FrameManager manager) {
        mBackingStore = new BackingStore(type, dimensions, manager);
    }

    Frame(BackingStore backingStore) {
        mBackingStore = backingStore;
    }

    final void assertAccessible(int mode) {
        // Make sure frame is in write-mode
        if (mReadOnly && mode == MODE_WRITE) {
            throw new RuntimeException("Attempting to write to read-only frame " + this + "!");
        }
    }

    final void setReadOnly(boolean readOnly) {
        mReadOnly = readOnly;
    }

    void resize(int[] newDims) {
        int[] oldDims = mBackingStore.getDimensions();
        int oldCount = oldDims == null ? 0 : oldDims.length;
        int newCount = newDims == null ? 0 : newDims.length;
        if (oldCount != newCount) {
            throw new IllegalArgumentException("Cannot resize " + oldCount + "-dimensional "
                + "Frame to " + newCount + "-dimensional Frame!");
        } else if (newDims != null && !Arrays.equals(oldDims, newDims)) {
            mBackingStore.resize(newDims);
        }
    }

    Frame makeCpuCopy(FrameManager frameManager) {
        Frame frame = new Frame(getType(), getDimensions(), frameManager);
        frame.mBackingStore.importStore(mBackingStore);
        return frame;
    }
}

