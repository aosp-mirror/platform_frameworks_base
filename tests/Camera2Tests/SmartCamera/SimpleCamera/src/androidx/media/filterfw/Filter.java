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

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Filters are the processing nodes of the filter graphs.
 *
 * Filters may have any number of input and output ports, through which the data frames flow.
 * TODO: More documentation on filter life-cycle, port and type checking, GL and RenderScript, ...
 */
public abstract class Filter {

    private static class State {
        private static final int STATE_UNPREPARED = 1;
        private static final int STATE_PREPARED = 2;
        private static final int STATE_OPEN = 3;
        private static final int STATE_CLOSED = 4;
        private static final int STATE_DESTROYED = 5;

        public int current = STATE_UNPREPARED;

        public synchronized boolean check(int state) {
            return current == state;
        }

    }

    private final int REQUEST_FLAG_NONE = 0;
    private final int REQUEST_FLAG_CLOSE = 1;

    private String mName;
    private MffContext mContext;
    private FilterGraph mFilterGraph;

    private State mState = new State();
    private int mRequests = REQUEST_FLAG_NONE;

    private int mMinimumAvailableInputs = 1;
    private int mMinimumAvailableOutputs = 1;

    private int mScheduleCount = 0;
    private long mLastScheduleTime = 0;

    private boolean mIsActive = true;
    private AtomicBoolean mIsSleeping = new AtomicBoolean(false);

    private long mCurrentTimestamp = Frame.TIMESTAMP_NOT_SET;

    private HashMap<String, InputPort> mConnectedInputPorts = new HashMap<String, InputPort>();
    private HashMap<String, OutputPort> mConnectedOutputPorts = new HashMap<String, OutputPort>();

    private InputPort[] mConnectedInputPortArray = null;
    private OutputPort[] mConnectedOutputPortArray = null;

    private ArrayList<Frame> mAutoReleaseFrames = new ArrayList<Frame>();


    /**
     * Constructs a new filter.
     * A filter is bound to a specific MffContext. Its name can be any String value, but it must
     * be unique within the filter graph.
     *
     * Note that names starting with "$" are reserved for internal use, and should not be used.
     *
     * @param context The MffContext in which the filter will live.
     * @param name The name of the filter.
     */
    protected Filter(MffContext context, String name) {
        mName = name;
        mContext = context;
    }

    /**
     * Checks whether the filter class is available on this platform.
     * Some filters may not be installed on all platforms and can therefore not be instantiated.
     * Before instantiating a filter, check if it is available by using this method.
     *
     * This method uses the shared FilterFactory to check whether the filter class is available.
     *
     * @param filterClassName The fully qualified class name of the Filter class.
     * @return true, if filters of the specified class name are available.
     */
    public static final boolean isAvailable(String filterClassName) {
        return FilterFactory.sharedFactory().isFilterAvailable(filterClassName);
    }

    /**
     * Returns the name of this filter.
     *
     * @return the name of the filter (specified during construction).
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the signature of this filter.
     *
     * Subclasses should override this and return their filter signature. The default
     * implementation returns a generic signature with no constraints.
     *
     * This method may be called at any time.
     *
     * @return the Signature instance for this filter.
     */
    public Signature getSignature() {
        return new Signature();
    }

    /**
     * Returns the MffContext that the filter resides in.
     *
     * @return the MffContext of the filter.
     */
    public MffContext getContext() {
        return mContext;
    }

    /**
     * Returns true, if the filter is active.
     * TODO: thread safety?
     *
     * @return true, if the filter is active.
     */
    public boolean isActive() {
        return mIsActive;
    }

    /**
     * Activates the current filter.
     * Only active filters can be scheduled for execution. This method can only be called if the
     * GraphRunner that is executing the filter is stopped or paused.
     */
    public void activate() {
        assertIsPaused();
        if (!mIsActive) {
            mIsActive = true;
        }
    }

    /**
     * Deactivates the current filter.
     * Only active filters can be scheduled for execution. This method can only be called if the
     * GraphRunner that is executing the filter is stopped or paused.
     */
    public void deactivate() {
        // TODO: Support close-on-deactivate (must happen in processing thread).
        assertIsPaused();
        if (mIsActive) {
            mIsActive = false;
        }
    }

    /**
     * Returns the filter's set of input ports.
     * Note that this contains only the *connected* input ports. To retrieve all
     * input ports that this filter accepts, one has to go via the filter's Signature.
     *
     * @return An array containing all connected input ports.
     */
    public final InputPort[] getConnectedInputPorts() {
        return mConnectedInputPortArray;
    }

    /**
     * Returns the filter's set of output ports.
     * Note that this contains only the *connected* output ports. To retrieve all
     * output ports that this filter provides, one has to go via the filter's Signature.
     *
     * @return An array containing all connected output ports.
     */
    public final OutputPort[] getConnectedOutputPorts() {
        return mConnectedOutputPortArray;
    }

    /**
     * Returns the input port with the given name.
     * Note that this can only access the *connected* input ports. To retrieve all
     * input ports that this filter accepts, one has to go via the filter's Signature.
     *
     * @return the input port with the specified name, or null if no connected input port
     *  with this name exists.
     */
    public final InputPort getConnectedInputPort(String name) {
        return mConnectedInputPorts.get(name);
    }

    /**
     * Returns the output port with the given name.
     * Note that this can only access the *connected* output ports. To retrieve all
     * output ports that this filter provides, one has to go via the filter's Signature.
     *
     * @return the output port with the specified name, or null if no connected output port
     *  with this name exists.
     */
    public final OutputPort getConnectedOutputPort(String name) {
        return mConnectedOutputPorts.get(name);
    }

    /**
     * Called when an input port has been attached in the graph.
     * Override this method, in case you want to be informed of any connected input ports, or make
     * modifications to them. Note that you may not assume that any other ports have been attached
     * already. If you have dependencies on other ports, override
     * {@link #onInputPortOpen(InputPort)}. The default implementation does nothing.
     *
     * @param port The InputPort instance that was attached.
     */
    protected void onInputPortAttached(InputPort port) {
    }

    /**
     * Called when an output port has been attached in the graph.
     * Override this method, in case you want to be informed of any connected output ports, or make
     * modifications to them. Note that you may not assume that any other ports have been attached
     * already. If you have dependencies on other ports, override
     * {@link #onOutputPortOpen(OutputPort)}. The default implementation does nothing.
     *
     * @param port The OutputPort instance that was attached.
     */
    protected void onOutputPortAttached(OutputPort port) {
    }

    /**
     * Called when an input port is opened on this filter.
     * Input ports are opened by the data produce, that is the filter that is connected to an
     * input port. Override this if you need to make modifications to the port before processing
     * begins. Note, that this is only called if the connected filter is scheduled. You may assume
     * that all ports are attached when this is called.
     *
     * @param port The InputPort instance that was opened.
     */
    protected void onInputPortOpen(InputPort port) {
    }

    /**
     * Called when an output port is opened on this filter.
     * Output ports are opened when the filter they are attached to is opened. Override this if you
     * need to make modifications to the port before processing begins. Note, that this is only
     * called if the filter is scheduled. You may assume that all ports are attached when this is
     * called.
     *
     * @param port The OutputPort instance that was opened.
     */
    protected void onOutputPortOpen(OutputPort port) {
    }

    /**
     * Returns true, if the filter is currently open.
     * @return true, if the filter is currently open.
     */
    public final boolean isOpen() {
        return mState.check(State.STATE_OPEN);
    }

    @Override
    public String toString() {
        return mName + " (" + getClass().getSimpleName() + ")";
    }

    /**
     * Called when filter is prepared.
     * Subclasses can override this to prepare the filter for processing. This method gets called
     * once only just before the filter is scheduled for processing the first time.
     *
     * @see #onTearDown()
     */
    protected void onPrepare() {
    }

    /**
     * Called when the filter is opened.
     * Subclasses can override this to perform any kind of initialization just before processing
     * starts. This method may be called any number of times, but is always balanced with an
     * {@link #onClose()} call.
     *
     * @see #onClose()
     */
    protected void onOpen() {
    }

    /**
     * Called to perform processing on Frame data.
     * This is the only method subclasses must override. It is called every time the filter is
     * ready for processing. Typically this is when there is input data to process and available
     * output ports, but may differ depending on the port configuration.
     */
    protected abstract void onProcess();

    /**
     * Called when the filter is closed.
     * Subclasses can override this to perform any kind of post-processing steps. Processing will
     * not resume until {@link #onOpen()} is called again. This method is only called if the filter
     * is open.
     *
     * @see #onOpen()
     */
    protected void onClose() {
    }

    /**
     * Called when the filter is torn down.
     * Subclasses can override this to perform clean-up tasks just before the filter is disposed of.
     * It is called when the filter graph that the filter belongs to is disposed.
     *
     * @see #onPrepare()
     */
    protected void onTearDown() {
    }

    /**
     * Check if the input conditions are met in order to schedule this filter.
     *
     * This is used by {@link #canSchedule()} to determine if the input-port conditions given by
     * the filter are met. Subclasses that override scheduling behavior can make use of this
     * function.
     *
     * @return true, if the filter's input conditions are met.
     */
    protected boolean inputConditionsMet() {
        if (mConnectedInputPortArray.length > 0) {
            int inputFrames = 0;
            // [Non-iterator looping]
            for (int i = 0; i < mConnectedInputPortArray.length; ++i) {
                if (!mConnectedInputPortArray[i].conditionsMet()) {
                    return false;
                } else if (mConnectedInputPortArray[i].hasFrame()) {
                    ++inputFrames;
                }
            }
            if (inputFrames < mMinimumAvailableInputs) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the output conditions are met in order to schedule this filter.
     *
     * This is used by {@link #canSchedule()} to determine if the output-port conditions given by
     * the filter are met. Subclasses that override scheduling behavior can make use of this
     * function.
     *
     * @return true, if the filter's output conditions are met.
     */
    protected boolean outputConditionsMet() {
        if (mConnectedOutputPortArray.length > 0) {
            int availableOutputs = 0;
            for (int i = 0; i < mConnectedOutputPortArray.length; ++i) {
                if (!mConnectedOutputPortArray[i].conditionsMet()) {
                    return false;
                } else if (mConnectedOutputPortArray[i].isAvailable()) {
                    ++availableOutputs;
                }
            }
            if (availableOutputs < mMinimumAvailableOutputs) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if the Filter is in a state so that it can be scheduled.
     *
     * When overriding the filter's {@link #canSchedule()} method, you should never allow
     * scheduling a filter that is not in a schedulable state. This will result in undefined
     * behavior.
     *
     * @return true, if the filter is in a schedulable state.
     */
    protected boolean inSchedulableState() {
        return (mIsActive && !mState.check(State.STATE_CLOSED));
    }

    /**
     * Returns true if the filter can be currently scheduled.
     *
     * Filters may override this method if they depend on custom factors that determine whether
     * they can be scheduled or not. The scheduler calls this method to determine whether or not
     * a filter can be scheduled for execution. It does not guarantee that it will be executed.
     * It is strongly recommended to call super's implementation to make sure your filter can be
     * scheduled based on its state, input and output ports.
     *
     * @return true, if the filter can be scheduled.
     */
    protected boolean canSchedule() {
        return inSchedulableState() && inputConditionsMet() && outputConditionsMet();
    }

    /**
     * Returns the current FrameManager instance.
     * @return the current FrameManager instance or null if there is no FrameManager set up yet.
     */
    protected final FrameManager getFrameManager() {
        return mFilterGraph.mRunner != null ? mFilterGraph.mRunner.getFrameManager() : null;
    }

    /**
     * Returns whether the GraphRunner for this filter is running.
     *
     * Generally, this method should not be used for performing operations that need to be carried
     * out before running begins. Use {@link #performPreparation(Runnable)} for this.
     *
     * @return true, if the GraphRunner for this filter is running.
     */
    protected final boolean isRunning() {
        return mFilterGraph != null && mFilterGraph.mRunner != null
                && mFilterGraph.mRunner.isRunning();
    }

    /**
     * Performs operations before the filter is running.
     *
     * Use this method when your filter requires to perform operations while the graph is not
     * running. The filter will not be scheduled for execution until your method has completed
     * execution.
     */
    protected final boolean performPreparation(Runnable runnable) {
        synchronized (mState) {
            if (mState.current == State.STATE_OPEN) {
                return false;
            } else {
                runnable.run();
                return true;
            }
        }
    }

    /**
     * Request that this filter be closed after the current processing step.
     *
     * Implementations may call this within their {@link #onProcess()} calls to indicate that the
     * filter is done processing and wishes to be closed. After such a request the filter will be
     * closed and no longer receive {@link #onProcess()} calls.
     *
     * @see #onClose()
     * @see #onProcess()
     */
    protected final void requestClose() {
        mRequests |= REQUEST_FLAG_CLOSE;
    }

    /**
     * Sets the minimum number of input frames required to process.
     * A filter will not be scheduled unless at least a certain number of input frames are available
     * on the input ports. This is only relevant if the filter has input ports and is not waiting on
     * all ports.
     * The default value is 1.
     *
     * @param count the minimum number of frames required to process.
     * @see #getMinimumAvailableInputs()
     * @see #setMinimumAvailableOutputs(int)
     * @see InputPort#setWaitsForFrame(boolean)
     */
    protected final void setMinimumAvailableInputs(int count) {
        mMinimumAvailableInputs = count;
    }

    /**
     * Returns the minimum number of input frames required to process this filter.
     * The default value is 1.
     *
     * @return the minimum number of input frames required to process.
     * @see #setMinimumAvailableInputs(int)
     */
    protected final int getMinimumAvailableInputs() {
        return mMinimumAvailableInputs;
    }

    /**
     * Sets the minimum number of available output ports required to process.
     * A filter will not be scheduled unless atleast a certain number of output ports are available.
     * This is only relevant if the filter has output ports and is not waiting on all ports. The
     * default value is 1.
     *
     * @param count the minimum number of frames required to process.
     * @see #getMinimumAvailableOutputs()
     * @see #setMinimumAvailableInputs(int)
     * @see OutputPort#setWaitsUntilAvailable(boolean)
     */
    protected final void setMinimumAvailableOutputs(int count) {
        mMinimumAvailableOutputs = count;
    }

    /**
     * Returns the minimum number of available outputs required to process this filter.
     * The default value is 1.
     *
     * @return the minimum number of available outputs required to process.
     * @see #setMinimumAvailableOutputs(int)
     */
    protected final int getMinimumAvailableOutputs() {
        return mMinimumAvailableOutputs;
    }

    /**
     * Puts the filter to sleep so that it is no longer scheduled.
     * To resume scheduling the filter another thread must call wakeUp() on this filter.
     */
    protected final void enterSleepState() {
        mIsSleeping.set(true);
    }

    /**
     * Wakes the filter and resumes scheduling.
     * This is generally called from another thread to signal that this filter should resume
     * processing. Does nothing if filter is not sleeping.
     */
    protected final void wakeUp() {
        if (mIsSleeping.getAndSet(false)) {
            if (isRunning()) {
                mFilterGraph.mRunner.signalWakeUp();
            }
        }
    }

    /**
     * Returns whether this Filter is allowed to use OpenGL.
     *
     * Filters may use OpenGL if the MffContext supports OpenGL and its GraphRunner allows it.
     *
     * @return true, if this Filter is allowed to use OpenGL.
     */
   protected final boolean isOpenGLSupported() {
        return mFilterGraph.mRunner.isOpenGLSupported();
    }

    /**
     * Connect an output port to an input port of another filter.
     * Connects the output port with the specified name to the input port with the specified name
     * of the specified filter. If the input or output ports do not exist already, they are
     * automatically created and added to the respective filter.
     */
    final void connect(String outputName, Filter targetFilter, String inputName) {
        // Make sure not connected already
        if (getConnectedOutputPort(outputName) != null) {
            throw new RuntimeException("Attempting to connect already connected output port '"
                + outputName + "' of filter " + this + "'!");
        } else if (targetFilter.getConnectedInputPort(inputName) != null) {
            throw new RuntimeException("Attempting to connect already connected input port '"
                + inputName + "' of filter " + targetFilter + "'!");
        }

        // Establish connection
        InputPort inputPort = targetFilter.newInputPort(inputName);
        OutputPort outputPort = newOutputPort(outputName);
        outputPort.setTarget(inputPort);

        // Fire attachment callbacks
        targetFilter.onInputPortAttached(inputPort);
        onOutputPortAttached(outputPort);

        // Update array of ports (which is maintained for more efficient access)
        updatePortArrays();
    }

    final Map<String, InputPort> getConnectedInputPortMap() {
        return mConnectedInputPorts;
    }

    final Map<String, OutputPort> getConnectedOutputPortMap() {
        return mConnectedOutputPorts;
    }

    final void execute() {
        synchronized (mState) {
            autoPullInputs();
            mLastScheduleTime = SystemClock.elapsedRealtime();
            if (mState.current == State.STATE_UNPREPARED) {
                onPrepare();
                mState.current = State.STATE_PREPARED;
            }
            if (mState.current == State.STATE_PREPARED) {
                openPorts();
                onOpen();
                mState.current = State.STATE_OPEN;
            }
            if (mState.current == State.STATE_OPEN) {
                onProcess();
                if (mRequests != REQUEST_FLAG_NONE) {
                    processRequests();
                }
            }
        }
        autoReleaseFrames();
        ++mScheduleCount;
    }

    final void performClose() {
        synchronized (mState) {
            if (mState.current == State.STATE_OPEN) {
                onClose();
                mIsSleeping.set(false);
                mState.current = State.STATE_CLOSED;
                mCurrentTimestamp = Frame.TIMESTAMP_NOT_SET;
            }
        }
    }

    final void softReset() {
        synchronized (mState) {
            performClose();
            if (mState.current == State.STATE_CLOSED) {
                mState.current = State.STATE_PREPARED;
            }
        }
    }

    final void performTearDown() {
        synchronized (mState) {
            if (mState.current == State.STATE_OPEN) {
                throw new RuntimeException("Attempting to tear-down filter " + this + " which is "
                    + "in an open state!");
            } else if (mState.current != State.STATE_DESTROYED
                    && mState.current != State.STATE_UNPREPARED) {
                onTearDown();
                mState.current = State.STATE_DESTROYED;
            }
        }
    }

    final void insertIntoFilterGraph(FilterGraph graph) {
        mFilterGraph = graph;
        updatePortArrays();
    }

    final int getScheduleCount() {
        return mScheduleCount;
    }

    final void resetScheduleCount() {
        mScheduleCount = 0;
    }

    final void openPorts() {
        // Opening the output ports will open the connected input ports
        for (OutputPort outputPort : mConnectedOutputPorts.values()) {
            openOutputPort(outputPort);
        }
    }

    final void addAutoReleaseFrame(Frame frame) {
        mAutoReleaseFrames.add(frame);
    }

    final long getCurrentTimestamp() {
        return mCurrentTimestamp;
    }

    final void onPulledFrameWithTimestamp(long timestamp) {
        if (timestamp > mCurrentTimestamp || mCurrentTimestamp == Frame.TIMESTAMP_NOT_SET) {
            mCurrentTimestamp = timestamp;
        }
    }

    final void openOutputPort(OutputPort outPort) {
        if (outPort.getQueue() == null) {
            try {
                FrameQueue.Builder builder = new FrameQueue.Builder();
                InputPort inPort = outPort.getTarget();
                outPort.onOpen(builder);
                inPort.onOpen(builder);
                Filter targetFilter = inPort.getFilter();
                String queueName = mName + "[" + outPort.getName() + "] -> " + targetFilter.mName
                        + "[" + inPort.getName() + "]";
                FrameQueue queue = builder.build(queueName);
                outPort.setQueue(queue);
                inPort.setQueue(queue);
            } catch (RuntimeException e) {
                throw new RuntimeException("Could not open output port " + outPort + "!", e);
            }
        }
    }

    final boolean isSleeping() {
        return mIsSleeping.get();
    }

    final long getLastScheduleTime() {
        return mLastScheduleTime ;
    }

    private final void autoPullInputs() {
        // [Non-iterator looping]
        for (int i = 0; i < mConnectedInputPortArray.length; ++i) {
            InputPort port = mConnectedInputPortArray[i];
            if (port.hasFrame() && port.isAutoPullEnabled()) {
                mConnectedInputPortArray[i].pullFrame();
            }
        }
    }

    private final void autoReleaseFrames() {
        // [Non-iterator looping]
        for (int i = 0; i < mAutoReleaseFrames.size(); ++i) {
            mAutoReleaseFrames.get(i).release();
        }
        mAutoReleaseFrames.clear();
    }

    private final InputPort newInputPort(String name) {
        InputPort result = mConnectedInputPorts.get(name);
        if (result == null) {
            Signature.PortInfo info = getSignature().getInputPortInfo(name);
            result = new InputPort(this, name, info);
            mConnectedInputPorts.put(name, result);
        }
        return result;
    }

    private final OutputPort newOutputPort(String name) {
        OutputPort result = mConnectedOutputPorts.get(name);
        if (result == null) {
            Signature.PortInfo info = getSignature().getOutputPortInfo(name);
            result = new OutputPort(this, name, info);
            mConnectedOutputPorts.put(name, result);
        }
        return result;
    }

    private final void processRequests() {
        if ((mRequests & REQUEST_FLAG_CLOSE) != 0) {
            performClose();
            mRequests = REQUEST_FLAG_NONE;
        }
    }

    private void assertIsPaused() {
        GraphRunner runner = GraphRunner.current();
        if (runner != null && !runner.isPaused() && !runner.isStopped()) {
            throw new RuntimeException("Attempting to modify filter state while runner is "
                + "executing. Please pause or stop the runner first!");
        }
    }

    private final void updatePortArrays() {
        // Copy our port-maps to arrays for faster non-iterator access
        mConnectedInputPortArray = mConnectedInputPorts.values().toArray(new InputPort[0]);
        mConnectedOutputPortArray = mConnectedOutputPorts.values().toArray(new OutputPort[0]);
    }

}

