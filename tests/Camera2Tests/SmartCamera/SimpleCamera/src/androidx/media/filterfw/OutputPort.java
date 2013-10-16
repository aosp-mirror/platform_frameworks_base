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

/**
 * Output ports are the data emitting ports of filters.
 * <p>
 * Filters push data frames onto output-ports, which in turn push them onto their connected input
 * ports. Output ports must be connected to an input port before data can be pushed onto them.
 * Input and output ports share their Frame slot, meaning that when a frame is waiting on an output
 * port, it is also waiting on the connected input port.
 * </p><p>
 * Only one frame can be pushed onto an output port at a time. In other words, a Frame must first
 * be consumed by the target filter before a new frame can be pushed on the output port. If the
 * output port is set to wait until it becomes free (see {@link #setWaitsUntilAvailable(boolean)}),
 * it is guaranteed to be available when {@code onProcess()} is called. This is the default setting.
 * </p>
 */
public final class OutputPort {

    private Filter mFilter;
    private String mName;
    private Signature.PortInfo mInfo;
    private FrameQueue.Builder mQueueBuilder = null;
    private FrameQueue mQueue = null;
    private boolean mWaitsUntilAvailable = true;
    private InputPort mTarget = null;

    /**
     * Returns true, if this port is connected to a target port.
     * @return true, if this port is connected to a target port.
     */
    public boolean isConnected() {
        return mTarget != null;
    }

    /**
     * Returns true, if there is no frame waiting on this port.
     * @return true, if no Frame instance is waiting on this port.
     */
    public boolean isAvailable() {
        return mQueue == null || mQueue.canPush();
    }

    /**
     * Returns a frame for writing.
     *
     * Call this method to fetch a new frame to write into. When you have finished writing the
     * frame data, you can push it into the output queue using {@link #pushFrame(Frame)}. Note,
     * that the Frame returned is owned by the queue. If you wish to hold on to the frame, you
     * must detach it.
     *
     * @param dimensions the size of the Frame you wish to obtain.
     * @return a writable Frame instance.
     */
    public Frame fetchAvailableFrame(int[] dimensions) {
        Frame frame = getQueue().fetchAvailableFrame(dimensions);
        if (frame != null) {
            //Log.i("OutputPort", "Adding frame " + frame + " to auto-release pool");
            mFilter.addAutoReleaseFrame(frame);
        }
        return frame;
    }

    /**
     * Pushes a frame onto this output port.
     *
     * This is typically a Frame instance you obtained by previously calling
     * {@link #fetchAvailableFrame(int[])}, but may come from other sources such as an input port
     * that is attached to this output port.
     *
     * Once you have pushed a frame to an output, you may no longer modify it as it may be shared
     * among other filters.
     *
     * @param frame the frame to push to the output queue.
     */
    public void pushFrame(Frame frame) {
        // Some queues allow pushing without fetching, so we need to make sure queue is open
        // before pushing!
        long timestamp = frame.getTimestamp();
        if (timestamp == Frame.TIMESTAMP_NOT_SET)
            frame.setTimestamp(mFilter.getCurrentTimestamp());
        getQueue().pushFrame(frame);
    }

    /**
     * Sets whether to wait until this port becomes available before processing.
     * When set to true, the Filter will not be scheduled for processing unless there is no Frame
     * waiting on this port. The default value is true.
     *
     * @param wait true, if filter should wait for the port to become available before processing.
     * @see #waitsUntilAvailable()
     */
    public void setWaitsUntilAvailable(boolean wait) {
        mWaitsUntilAvailable = wait;
    }

    /**
     * Returns whether the filter waits until this port is available before processing.
     * @return true, if the filter waits until this port is available before processing.
     * @see #setWaitsUntilAvailable(boolean)
     */
    public boolean waitsUntilAvailable() {
        return mWaitsUntilAvailable;
    }

    /**
     * Returns the output port's name.
     * This is the name that was specified when the output port was connected.
     *
     * @return the output port's name.
     */
    public String getName() {
        return mName;
    }

    /**
     * Return the filter object that this port belongs to.
     *
     * @return the output port's filter.
     */
    public Filter getFilter() {
        return mFilter;
    }

    @Override
    public String toString() {
        return mFilter.getName() + ":" + mName;
    }

    OutputPort(Filter filter, String name, Signature.PortInfo info) {
        mFilter = filter;
        mName = name;
        mInfo = info;
    }

    void setTarget(InputPort target) {
        mTarget = target;
    }

    /**
     * Return the (input) port that this output port is connected to.
     *
     * @return the connected port, null if not connected.
     */
    public InputPort getTarget() {
        return mTarget;
    }

    FrameQueue getQueue() {
        return mQueue;
    }

    void setQueue(FrameQueue queue) {
        mQueue = queue;
        mQueueBuilder = null;
    }

    void onOpen(FrameQueue.Builder builder) {
        mQueueBuilder = builder;
        mQueueBuilder.setWriteType(mInfo.type);
        mFilter.onOutputPortOpen(this);
    }

    boolean isOpen() {
        return mQueue != null;
    }

    final boolean conditionsMet() {
        return !mWaitsUntilAvailable || isAvailable();
    }

    void clear() {
        if (mQueue != null) {
            mQueue.clear();
        }
    }
}

