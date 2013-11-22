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

import java.lang.reflect.Field;

/**
 * Input ports are the receiving ports of frames in a filter.
 * <p>
 * InputPort instances receive Frame data from connected OutputPort instances of a previous filter.
 * Frames flow from output ports to input ports. Filters can process frame data by calling
 * {@link #pullFrame()} on an input port. If the input port is set to wait for an input frame
 * (see {@link #setWaitsForFrame(boolean)}), there is guaranteed to be Frame on the port before
 * {@code onProcess()} is called. This is the default setting. Otherwise, calling
 * {@link #pullFrame()} may return a value of {@code null}.
 * <p/><p>
 * InputPorts may be bound to fields of the Filter. When an input port is bound to a field, Frame
 * values will be assigned to the field once a Frame is received on that port. The Frame value must
 * be of a type that is compatible with the field type.
 * </p>
 */
public final class InputPort {

    private Filter mFilter;
    private String mName;
    private Signature.PortInfo mInfo;
    private FrameListener mListener = null;
    private FrameQueue.Builder mQueueBuilder = null;
    private FrameQueue mQueue = null;
    private boolean mWaitForFrame = true;
    private boolean mAutoPullEnabled = false;

    public interface FrameListener {
        public void onFrameReceived(InputPort port, Frame frame);
    }

    private class FieldBinding implements FrameListener {
        private Field mField;

        public FieldBinding(Field field) {
            mField = field;
        }

        @Override
        public void onFrameReceived(InputPort port, Frame frame) {
            try {
                if(port.mInfo.type.getNumberOfDimensions() > 0) {
                    FrameValues frameValues = frame.asFrameValues();
                    mField.set(mFilter, frameValues.getValues());
                } else {
                    FrameValue frameValue = frame.asFrameValue();
                    mField.set(mFilter, frameValue.getValue());
                }
            } catch (Exception e) {
                throw new RuntimeException("Assigning frame " + frame + " to field "
                    + mField + " of filter " + mFilter + " caused exception!", e);
            }
        }
    }

    /**
     * Attach this input port to an output port for frame passing.
     *
     * Use this method whenever you plan on passing a Frame through from an input port to an
     * output port. This must be called from inside
     * {@link Filter#onInputPortAttached(InputPort) onInputPortAttached}.
     *
     * @param outputPort the output port that Frames will be pushed to.
     */
    public void attachToOutputPort(OutputPort outputPort) {
        assertInAttachmentStage();
        mFilter.openOutputPort(outputPort);
        mQueueBuilder.attachQueue(outputPort.getQueue());
    }

    /**
     * Bind this input port to the specified listener.
     *
     * Use this when you wish to be notified of incoming frames. The listener method
     * {@link FrameListener#onFrameReceived(InputPort, Frame)} will be called once a Frame is pulled
     * on this port. Typically this is called from inside
     * {@link Filter#onInputPortAttached(InputPort) onInputPortAttached}, and used in
     * conjunction with {@link #setAutoPullEnabled(boolean)}. Overrides any previous bindings.
     *
     * @param listener the listener to handle incoming Frames.
     */
    public void bindToListener(FrameListener listener) {
        assertInAttachmentStage();
        mListener = listener;
    }

    /**
     * Bind this input port to the specified field.
     *
     * Use this when you wish to pull frames directly into a field of the filter. This requires
     * that the input frames can be interpreted as object-based frames of the field's class.
     * Overrides any previous bindings.
     *
     * This is typically called from inside
     * {@link Filter#onInputPortAttached(InputPort) onInputPortAttached}, and used in
     * conjunction with {@link #setAutoPullEnabled(boolean)}.
     *
     * @param field the field to pull frame data into.
     * @see #bindToFieldNamed(String)
     * @see #setAutoPullEnabled(boolean)
     */
    public void bindToField(Field field) {
        assertInAttachmentStage();
        mListener = new FieldBinding(field);
    }

    /**
     * Bind this input port to the field with the specified name.
     *
     * Use this when you wish to pull frames directly into a field of the filter. This requires
     * that the input frames can be interpreted as object-based frames of the field's class.
     * Overrides any previous bindings.
     *
     * This is typically called from inside
     * {@link Filter#onInputPortAttached(InputPort) onInputPortAttached}, and used in
     * conjunction with {@link #setAutoPullEnabled(boolean)}.
     *
     * @param fieldName the field to pull frame data into.
     * @see #bindToField(Field)
     * @see #setAutoPullEnabled(boolean)
     */
    public void bindToFieldNamed(String fieldName) {
        Field field = findFieldNamed(fieldName, mFilter.getClass());
        if (field == null) {
            throw new IllegalArgumentException("Attempting to bind to unknown field '"
                + fieldName + "'!");
        }
        bindToField(field);
    }

    /**
     * Set whether the InputPort automatically pulls frames.
     * This is typically only used when the port is bound to another target.
     * @param enabled true, if frames should be automatically pulled on this port.
     */
    public void setAutoPullEnabled(boolean enabled) {
        mAutoPullEnabled = enabled;
    }

    /**
     * Returns whether the InputPort automatically pulls frames.
     * @return true, if frames are automatically pulled on this port.
     */
    public boolean isAutoPullEnabled() {
        return mAutoPullEnabled;
    }

    /**
     * Pull a waiting a frame from the port.
     *
     * Call this to pull a frame from the input port for processing. If no frame is waiting on the
     * input port, returns null. After this call the port will have no Frame waiting (empty port).
     * Note, that this returns a frame owned by the input queue. You must detach the frame if you
     * wish to hold on to it.
     *
     * @return Frame instance, or null if no frame is available for pulling.
     */
    public synchronized Frame pullFrame() {
        if (mQueue == null) {
            throw new IllegalStateException("Cannot pull frame from closed input port!");
        }
        Frame frame = mQueue.pullFrame();
        if (frame != null) {
            if (mListener != null) {
                mListener.onFrameReceived(this, frame);
            }
            //Log.i("InputPort", "Adding frame " + frame + " to auto-release pool");
            mFilter.addAutoReleaseFrame(frame);
            long timestamp = frame.getTimestamp();
            if (timestamp != Frame.TIMESTAMP_NOT_SET) {
                mFilter.onPulledFrameWithTimestamp(frame.getTimestamp());
            }
        }
        return frame;
    }

    public synchronized Frame peek() {
        if (mQueue == null) {
            throw new IllegalStateException("Cannot pull frame from closed input port!");
        }
        return mQueue.peek();
    }

    /**
     * Returns true, if the port is connected.
     * @return true, if there is an output port that connects to this port.
     */
    public boolean isConnected() {
        return mQueue != null;
    }

    /**
     * Returns true, if there is a frame waiting on this port.
     * @return true, if there is a frame waiting on this port.
     */
    public synchronized boolean hasFrame() {
        return mQueue != null && mQueue.canPull();
    }

    /**
     * Sets whether to wait for a frame on this port before processing.
     * When set to true, the Filter will not be scheduled for processing unless there is a Frame
     * waiting on this port. The default value is true.
     *
     * @param wait true, if the Filter should wait for a Frame before processing.
     * @see #waitsForFrame()
     */
    public void setWaitsForFrame(boolean wait) {
        mWaitForFrame = wait;
    }

    /**
     * Returns whether the filter waits for a frame on this port before processing.
     * @return true, if the filter waits for a frame on this port before processing.
     * @see #setWaitsForFrame(boolean)
     */
    public boolean waitsForFrame() {
        return mWaitForFrame;
    }

    /**
     * Returns the input port's name.
     * This is the name that was specified when the input port was connected.
     *
     * @return the input port's name.
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the FrameType of this port.
     * This is the type that was specified when the input port was declared.
     *
     * @return the input port's FrameType.
     */
    public FrameType getType() {
        return getQueue().getType();
    }

    /**
     * Return the filter object that this port belongs to.
     *
     * @return the input port's filter.
     */
    public Filter getFilter() {
        return mFilter;
    }

    @Override
    public String toString() {
        return mFilter.getName() + ":" + mName;
    }

    // Internal only ///////////////////////////////////////////////////////////////////////////////
    InputPort(Filter filter, String name, Signature.PortInfo info) {
        mFilter = filter;
        mName = name;
        mInfo = info;
    }

    boolean conditionsMet() {
        return !mWaitForFrame || hasFrame();
    }

    void onOpen(FrameQueue.Builder builder) {
        mQueueBuilder = builder;
        mQueueBuilder.setReadType(mInfo.type);
        mFilter.onInputPortOpen(this);
    }

    void setQueue(FrameQueue queue) {
        mQueue = queue;
        mQueueBuilder = null;
    }

    FrameQueue getQueue() {
        return mQueue;
    }

    void clear() {
        if (mQueue != null) {
            mQueue.clear();
        }
    }

    private void assertInAttachmentStage() {
        if (mQueueBuilder == null) {
            throw new IllegalStateException("Attempting to attach port while not in attachment "
                + "stage!");
        }
    }

    private Field findFieldNamed(String fieldName, Class<?> clazz) {
        Field field = null;
        try {
            field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                field = findFieldNamed(fieldName, superClass);
            }
        }
        return field;
    }
}

