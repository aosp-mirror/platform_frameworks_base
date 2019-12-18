/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *            http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package android.filterfw.core;

import android.compat.annotation.UnsupportedAppUsage;
import android.filterfw.format.ObjectFormat;
import android.filterfw.io.GraphIOException;
import android.filterfw.io.TextGraphReader;
import android.util.Log;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

/**
 * @hide
 */
public abstract class Filter {

    static final int STATUS_PREINIT               = 0;
    static final int STATUS_UNPREPARED            = 1;
    static final int STATUS_PREPARED              = 2;
    static final int STATUS_PROCESSING            = 3;
    static final int STATUS_SLEEPING              = 4;
    static final int STATUS_FINISHED              = 5;
    static final int STATUS_ERROR                 = 6;
    static final int STATUS_RELEASED              = 7;

    private String mName;

    private int mInputCount = -1;
    private int mOutputCount = -1;

    private HashMap<String, InputPort> mInputPorts;
    private HashMap<String, OutputPort> mOutputPorts;

    private HashSet<Frame> mFramesToRelease;
    private HashMap<String, Frame> mFramesToSet;

    private int mStatus = 0;
    private boolean mIsOpen = false;
    private int mSleepDelay;

    private long mCurrentTimestamp;

    private boolean mLogVerbose;
    private static final String TAG = "Filter";

    @UnsupportedAppUsage
    public Filter(String name) {
        mName = name;
        mFramesToRelease = new HashSet<Frame>();
        mFramesToSet = new HashMap<String, Frame>();
        mStatus = STATUS_PREINIT;

        mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);
    }

    /** Tests to see if a given filter is installed on the system. Requires
     * full filter package name, including filterpack.
     */
    @UnsupportedAppUsage
    public static final boolean isAvailable(String filterName) {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        Class filterClass;
        // First see if a class of that name exists
        try {
            filterClass = contextClassLoader.loadClass(filterName);
        } catch (ClassNotFoundException e) {
            return false;
        }
        // Then make sure it's a subclass of Filter.
        try {
            filterClass.asSubclass(Filter.class);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    public final void initWithValueMap(KeyValueMap valueMap) {
        // Initialization
        initFinalPorts(valueMap);

        // Setup remaining ports
        initRemainingPorts(valueMap);

        // This indicates that final ports can no longer be set
        mStatus = STATUS_UNPREPARED;
    }

    public final void initWithAssignmentString(String assignments) {
        try {
            KeyValueMap valueMap = new TextGraphReader().readKeyValueAssignments(assignments);
            initWithValueMap(valueMap);
        } catch (GraphIOException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    public final void initWithAssignmentList(Object... keyValues) {
        KeyValueMap valueMap = new KeyValueMap();
        valueMap.setKeyValues(keyValues);
        initWithValueMap(valueMap);
    }

    public final void init() throws ProtocolException {
        KeyValueMap valueMap = new KeyValueMap();
        initWithValueMap(valueMap);
    }

    public String getFilterClassName() {
        return getClass().getSimpleName();
    }

    public final String getName() {
        return mName;
    }

    public boolean isOpen() {
        return mIsOpen;
    }

    public void setInputFrame(String inputName, Frame frame) {
        FilterPort port = getInputPort(inputName);
        if (!port.isOpen()) {
            port.open();
        }
        port.setFrame(frame);
    }

    @UnsupportedAppUsage
    public final void setInputValue(String inputName, Object value) {
        setInputFrame(inputName, wrapInputValue(inputName, value));
    }

    protected void prepare(FilterContext context) {
    }

    protected void parametersUpdated(Set<String> updated) {
    }

    protected void delayNextProcess(int millisecs) {
        mSleepDelay = millisecs;
        mStatus = STATUS_SLEEPING;
    }

    public abstract void setupPorts();

    public FrameFormat getOutputFormat(String portName, FrameFormat inputFormat) {
        return null;
    }

    public final FrameFormat getInputFormat(String portName) {
        InputPort inputPort = getInputPort(portName);
        return inputPort.getSourceFormat();
    }

    public void open(FilterContext context) {
    }

    public abstract void process(FilterContext context);

    public final int getSleepDelay() {
        return 250;
    }

    public void close(FilterContext context) {
    }

    public void tearDown(FilterContext context) {
    }

    public final int getNumberOfConnectedInputs() {
        int c = 0;
        for (InputPort inputPort : mInputPorts.values()) {
            if (inputPort.isConnected()) {
                ++c;
            }
        }
        return c;
    }

    public final int getNumberOfConnectedOutputs() {
        int c = 0;
        for (OutputPort outputPort : mOutputPorts.values()) {
            if (outputPort.isConnected()) {
                ++c;
            }
        }
        return c;
    }

    public final int getNumberOfInputs() {
        return mOutputPorts == null ? 0 : mInputPorts.size();
    }

    public final int getNumberOfOutputs() {
        return mInputPorts == null ? 0 : mOutputPorts.size();
    }

    public final InputPort getInputPort(String portName) {
        if (mInputPorts == null) {
            throw new NullPointerException("Attempting to access input port '" + portName
                + "' of " + this + " before Filter has been initialized!");
        }
        InputPort result = mInputPorts.get(portName);
        if (result == null) {
            throw new IllegalArgumentException("Unknown input port '" + portName + "' on filter "
                + this + "!");
        }
        return result;
    }

    public final OutputPort getOutputPort(String portName) {
        if (mInputPorts == null) {
            throw new NullPointerException("Attempting to access output port '" + portName
                + "' of " + this + " before Filter has been initialized!");
        }
        OutputPort result = mOutputPorts.get(portName);
        if (result == null) {
            throw new IllegalArgumentException("Unknown output port '" + portName + "' on filter "
                + this + "!");
        }
        return result;
    }

    protected final void pushOutput(String name, Frame frame) {
        if (frame.getTimestamp() == Frame.TIMESTAMP_NOT_SET) {
            if (mLogVerbose) Log.v(TAG, "Default-setting output Frame timestamp on port " + name + " to " + mCurrentTimestamp);
            frame.setTimestamp(mCurrentTimestamp);
        }
        getOutputPort(name).pushFrame(frame);
    }

    protected final Frame pullInput(String name) {
        Frame result = getInputPort(name).pullFrame();
        if (mCurrentTimestamp == Frame.TIMESTAMP_UNKNOWN) {
            mCurrentTimestamp = result.getTimestamp();
            if (mLogVerbose) Log.v(TAG, "Default-setting current timestamp from input port " + name + " to " + mCurrentTimestamp);
        }
        // As result is retained, we add it to the release pool here
        mFramesToRelease.add(result);

        return result;
    }

    public void fieldPortValueUpdated(String name, FilterContext context) {
    }

    /**
     * Transfers any frame from an input port to its destination. This is useful to force a
     * transfer from a FieldPort or ProgramPort to its connected target (field or program variable).
     */
    protected void transferInputPortFrame(String name, FilterContext context) {
        getInputPort(name).transfer(context);
    }

    /**
     * Assigns all program variables to the ports they are connected to. Call this after
     * constructing a Program instance with attached ProgramPorts.
     */
    protected void initProgramInputs(Program program, FilterContext context) {
        if (program != null) {
            for (InputPort inputPort : mInputPorts.values()) {
                if (inputPort.getTarget() == program) {
                    inputPort.transfer(context);
                }
            }
        }
    }

    /**
     * Adds an input port to the filter. You should call this from within setupPorts, if your
     * filter has input ports. No type-checking is performed on the input. If you would like to
     * check against a type mask, use
     * {@link #addMaskedInputPort(String, FrameFormat) addMaskedInputPort} instead.
     *
     * @param name the name of the input port
     */
    protected void addInputPort(String name) {
        addMaskedInputPort(name, null);
    }

    /**
     * Adds an input port to the filter. You should call this from within setupPorts, if your
     * filter has input ports. When type-checking is performed, the input format is
     * checked against the provided format mask. An exception is thrown in case of a conflict.
     *
     * @param name the name of the input port
     * @param formatMask a format mask, which filters the allowable input types
     */
    protected void addMaskedInputPort(String name, FrameFormat formatMask) {
        InputPort port = new StreamPort(this, name);
        if (mLogVerbose) Log.v(TAG, "Filter " + this + " adding " + port);
        mInputPorts.put(name, port);
        port.setPortFormat(formatMask);
    }

    /**
     * Adds an output port to the filter with a fixed output format. You should call this from
     * within setupPorts, if your filter has output ports. You cannot use this method, if your
     * output format depends on the input format (e.g. in a pass-through filter). In this case, use
     * {@link #addOutputBasedOnInput(String, String) addOutputBasedOnInput} instead.
     *
     * @param name the name of the output port
     * @param format the fixed output format of this port
     */
    protected void addOutputPort(String name, FrameFormat format) {
        OutputPort port = new OutputPort(this, name);
        if (mLogVerbose) Log.v(TAG, "Filter " + this + " adding " + port);
        port.setPortFormat(format);
        mOutputPorts.put(name, port);
    }

    /**
     * Adds an output port to the filter. You should call this from within setupPorts, if your
     * filter has output ports. Using this method indicates that the output format for this
     * particular port, depends on the format of an input port. You MUST also override
     * {@link #getOutputFormat(String, FrameFormat) getOutputFormat} to specify what format your
     * filter will output for a given input. If the output format of your filter port does not
     * depend on the input, use {@link #addOutputPort(String, FrameFormat) addOutputPort} instead.
     *
     * @param outputName the name of the output port
     * @param inputName the name of the input port, that this output depends on
     */
    protected void addOutputBasedOnInput(String outputName, String inputName) {
        OutputPort port = new OutputPort(this, outputName);
        if (mLogVerbose) Log.v(TAG, "Filter " + this + " adding " + port);
        port.setBasePort(getInputPort(inputName));
        mOutputPorts.put(outputName, port);
    }

    protected void addFieldPort(String name,
                                Field field,
                                boolean hasDefault,
                                boolean isFinal) {
        // Make sure field is accessible
        field.setAccessible(true);

        // Create port for this input
        InputPort fieldPort = isFinal
            ? new FinalPort(this, name, field, hasDefault)
            : new FieldPort(this, name, field, hasDefault);

        // Create format for this input
        if (mLogVerbose) Log.v(TAG, "Filter " + this + " adding " + fieldPort);
        MutableFrameFormat format = ObjectFormat.fromClass(field.getType(),
                                                           FrameFormat.TARGET_SIMPLE);
        fieldPort.setPortFormat(format);

        // Add port
        mInputPorts.put(name, fieldPort);
    }

    protected void addProgramPort(String name,
                                  String varName,
                                  Field field,
                                  Class varType,
                                  boolean hasDefault) {
        // Make sure field is accessible
        field.setAccessible(true);

        // Create port for this input
        InputPort programPort = new ProgramPort(this, name, varName, field, hasDefault);

        // Create format for this input
        if (mLogVerbose) Log.v(TAG, "Filter " + this + " adding " + programPort);
        MutableFrameFormat format = ObjectFormat.fromClass(varType,
                                                           FrameFormat.TARGET_SIMPLE);
        programPort.setPortFormat(format);

        // Add port
        mInputPorts.put(name, programPort);
    }

    protected void closeOutputPort(String name) {
        getOutputPort(name).close();
    }

    /**
     * Specifies whether the filter should not be scheduled until a frame is available on that
     * input port. Note, that setting this to false, does not block a new frame from coming in
     * (though there is no necessity to pull that frame for processing).
     * @param portName the name of the input port.
     * @param waits true, if the filter should wait for a frame on this port.
     */
    protected void setWaitsOnInputPort(String portName, boolean waits) {
        getInputPort(portName).setBlocking(waits);
    }

    /**
     * Specifies whether the filter should not be scheduled until the output port is free, i.e.
     * there is no frame waiting on that output.
     * @param portName the name of the output port.
     * @param waits true, if the filter should wait for the port to become free.
     */
    protected void setWaitsOnOutputPort(String portName, boolean waits) {
        getOutputPort(portName).setBlocking(waits);
    }

    public String toString() {
        return "'" + getName() + "' (" + getFilterClassName() + ")";
    }

    // Core internal methods ///////////////////////////////////////////////////////////////////////
    final Collection<InputPort> getInputPorts() {
        return mInputPorts.values();
    }

    final Collection<OutputPort> getOutputPorts() {
        return mOutputPorts.values();
    }

    final synchronized int getStatus() {
        return mStatus;
    }

    final synchronized void unsetStatus(int flag) {
        mStatus &= ~flag;
    }

    final synchronized void performOpen(FilterContext context) {
        if (!mIsOpen) {
            if (mStatus == STATUS_UNPREPARED) {
                if (mLogVerbose) Log.v(TAG, "Preparing " + this);
                prepare(context);
                mStatus = STATUS_PREPARED;
            }
            if (mStatus == STATUS_PREPARED) {
                if (mLogVerbose) Log.v(TAG, "Opening " + this);
                open(context);
                mStatus = STATUS_PROCESSING;
            }
            if (mStatus != STATUS_PROCESSING) {
                throw new RuntimeException("Filter " + this + " was brought into invalid state during "
                    + "opening (state: " + mStatus + ")!");
            }
            mIsOpen = true;
        }
    }

    final synchronized void performProcess(FilterContext context) {
        if (mStatus == STATUS_RELEASED) {
            throw new RuntimeException("Filter " + this + " is already torn down!");
        }
        transferInputFrames(context);
        if (mStatus < STATUS_PROCESSING) {
            performOpen(context);
        }
        if (mLogVerbose) Log.v(TAG, "Processing " + this);
        mCurrentTimestamp = Frame.TIMESTAMP_UNKNOWN;
        process(context);
        releasePulledFrames(context);
        if (filterMustClose()) {
            performClose(context);
        }
    }

    final synchronized void performClose(FilterContext context) {
        if (mIsOpen) {
            if (mLogVerbose) Log.v(TAG, "Closing " + this);
            mIsOpen = false;
            mStatus = STATUS_PREPARED;
            close(context);
            closePorts();
        }
    }

    final synchronized void performTearDown(FilterContext context) {
        performClose(context);
        if (mStatus != STATUS_RELEASED) {
            tearDown(context);
            mStatus = STATUS_RELEASED;
        }
    }

    synchronized final boolean canProcess() {
        if (mLogVerbose) Log.v(TAG, "Checking if can process: " + this + " (" + mStatus + ").");
        if (mStatus <= STATUS_PROCESSING) {
            return inputConditionsMet() && outputConditionsMet();
        } else {
            return false;
        }
    }

    final void openOutputs() {
        if (mLogVerbose) Log.v(TAG, "Opening all output ports on " + this + "!");
        for (OutputPort outputPort : mOutputPorts.values()) {
            if (!outputPort.isOpen()) {
                outputPort.open();
            }
        }
    }

    final void clearInputs() {
        for (InputPort inputPort : mInputPorts.values()) {
            inputPort.clear();
        }
    }

    final void clearOutputs() {
        for (OutputPort outputPort : mOutputPorts.values()) {
            outputPort.clear();
        }
    }

    final void notifyFieldPortValueUpdated(String name, FilterContext context) {
        if (mStatus == STATUS_PROCESSING || mStatus == STATUS_PREPARED) {
            fieldPortValueUpdated(name, context);
        }
    }

    final synchronized void pushInputFrame(String inputName, Frame frame) {
        FilterPort port = getInputPort(inputName);
        if (!port.isOpen()) {
            port.open();
        }
        port.pushFrame(frame);
    }

    final synchronized void pushInputValue(String inputName, Object value) {
        pushInputFrame(inputName, wrapInputValue(inputName, value));
    }

    // Filter internal methods /////////////////////////////////////////////////////////////////////
    private final void initFinalPorts(KeyValueMap values) {
        mInputPorts = new HashMap<String, InputPort>();
        mOutputPorts = new HashMap<String, OutputPort>();
        addAndSetFinalPorts(values);
    }

    private final void initRemainingPorts(KeyValueMap values) {
        addAnnotatedPorts();
        setupPorts();   // TODO: rename to addFilterPorts() ?
        setInitialInputValues(values);
    }

    private final void addAndSetFinalPorts(KeyValueMap values) {
        Class filterClass = getClass();
        Annotation annotation;
        for (Field field : filterClass.getDeclaredFields()) {
            if ((annotation = field.getAnnotation(GenerateFinalPort.class)) != null) {
                GenerateFinalPort generator = (GenerateFinalPort)annotation;
                String name = generator.name().isEmpty() ? field.getName() : generator.name();
                boolean hasDefault = generator.hasDefault();
                addFieldPort(name, field, hasDefault, true);
                if (values.containsKey(name)) {
                    setImmediateInputValue(name, values.get(name));
                    values.remove(name);
                } else if (!generator.hasDefault()) {
                    throw new RuntimeException("No value specified for final input port '"
                        + name + "' of filter " + this + "!");
                }
            }
        }
    }

    private final void addAnnotatedPorts() {
        Class filterClass = getClass();
        Annotation annotation;
        for (Field field : filterClass.getDeclaredFields()) {
            if ((annotation = field.getAnnotation(GenerateFieldPort.class)) != null) {
                GenerateFieldPort generator = (GenerateFieldPort)annotation;
                addFieldGenerator(generator, field);
            } else if ((annotation = field.getAnnotation(GenerateProgramPort.class)) != null) {
                GenerateProgramPort generator = (GenerateProgramPort)annotation;
                addProgramGenerator(generator, field);
            } else if ((annotation = field.getAnnotation(GenerateProgramPorts.class)) != null) {
                GenerateProgramPorts generators = (GenerateProgramPorts)annotation;
                for (GenerateProgramPort generator : generators.value()) {
                    addProgramGenerator(generator, field);
                }
            }
        }
    }

    private final void addFieldGenerator(GenerateFieldPort generator, Field field) {
        String name = generator.name().isEmpty() ? field.getName() : generator.name();
        boolean hasDefault = generator.hasDefault();
        addFieldPort(name, field, hasDefault, false);
    }

    private final void addProgramGenerator(GenerateProgramPort generator, Field field) {
        String name = generator.name();
        String varName = generator.variableName().isEmpty() ? name
                                                            : generator.variableName();
        Class varType = generator.type();
        boolean hasDefault = generator.hasDefault();
        addProgramPort(name, varName, field, varType, hasDefault);
    }

    private final void setInitialInputValues(KeyValueMap values) {
        for (Entry<String, Object> entry : values.entrySet()) {
            setInputValue(entry.getKey(), entry.getValue());
        }
    }

    private final void setImmediateInputValue(String name, Object value) {
        if (mLogVerbose) Log.v(TAG, "Setting immediate value " + value + " for port " + name + "!");
        FilterPort port = getInputPort(name);
        port.open();
        port.setFrame(SimpleFrame.wrapObject(value, null));
    }

    private final void transferInputFrames(FilterContext context) {
        for (InputPort inputPort : mInputPorts.values()) {
            inputPort.transfer(context);
        }
    }

    private final Frame wrapInputValue(String inputName, Object value) {
        MutableFrameFormat inputFormat = ObjectFormat.fromObject(value, FrameFormat.TARGET_SIMPLE);
        if (value == null) {
            // If the value is null, the format cannot guess the class, so we adjust it to the
            // class of the input port here
            FrameFormat portFormat = getInputPort(inputName).getPortFormat();
            Class portClass = (portFormat == null) ? null : portFormat.getObjectClass();
            inputFormat.setObjectClass(portClass);
        }

        // Serialize if serializable, and type is not an immutable primitive.
        boolean shouldSerialize = !(value instanceof Number)
            && !(value instanceof Boolean)
            && !(value instanceof String)
            && value instanceof Serializable;

        // Create frame wrapper
        Frame frame = shouldSerialize
            ? new SerializedFrame(inputFormat, null)
            : new SimpleFrame(inputFormat, null);
        frame.setObjectValue(value);
        return frame;
    }

    private final void releasePulledFrames(FilterContext context) {
        for (Frame frame : mFramesToRelease) {
            context.getFrameManager().releaseFrame(frame);
        }
        mFramesToRelease.clear();
    }

    private final boolean inputConditionsMet() {
        for (FilterPort port : mInputPorts.values()) {
            if (!port.isReady()) {
                if (mLogVerbose) Log.v(TAG, "Input condition not met: " + port + "!");
                return false;
            }
        }
        return true;
    }

    private final boolean outputConditionsMet() {
        for (FilterPort port : mOutputPorts.values()) {
            if (!port.isReady()) {
                if (mLogVerbose) Log.v(TAG, "Output condition not met: " + port + "!");
                return false;
            }
        }
        return true;
    }

    private final void closePorts() {
        if (mLogVerbose) Log.v(TAG, "Closing all ports on " + this + "!");
        for (InputPort inputPort : mInputPorts.values()) {
            inputPort.close();
        }
        for (OutputPort outputPort : mOutputPorts.values()) {
            outputPort.close();
        }
    }

    private final boolean filterMustClose() {
        for (InputPort inputPort : mInputPorts.values()) {
            if (inputPort.filterMustClose()) {
                if (mLogVerbose) Log.v(TAG, "Filter " + this + " must close due to port " + inputPort);
                return true;
            }
        }
        for (OutputPort outputPort : mOutputPorts.values()) {
            if (outputPort.filterMustClose()) {
                if (mLogVerbose) Log.v(TAG, "Filter " + this + " must close due to port " + outputPort);
                return true;
            }
        }
        return false;
    }
}
