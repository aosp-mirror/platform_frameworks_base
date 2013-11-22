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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A Signature holds the specification for a filter's input and output ports.
 *
 * A Signature instance must be returned by the filter's {@link Filter#getSignature()} method. It
 * specifies the number and names of the filter's input and output ports, whether or not they
 * are required, how data for those ports are accessed, and more. A Signature does not change over
 * time. This makes Signatures useful for understanding how a filter can be integrated into a
 * graph.
 *
 * There are a number of flags that can be specified for each input and output port. The flag
 * {@code PORT_REQUIRED} indicates that the user must connect the specified port. On the other hand,
 * {@code PORT_OPTIONAL} indicates that a port may be connected by the user.
 *
 * If ports other than the ones in the Signature are allowed, they default to the most generic
 * format, that allows passing in any type of Frame. Thus, if more granular access is needed to
 * a frame's data, it must be specified in the Signature.
 */
public class Signature {

    private HashMap<String, PortInfo> mInputPorts = null;
    private HashMap<String, PortInfo> mOutputPorts = null;
    private boolean mAllowOtherInputs = true;
    private boolean mAllowOtherOutputs = true;

    static class PortInfo {
        public int flags;
        public FrameType type;

        public PortInfo() {
            flags = 0;
            type = FrameType.any();
        }

        public PortInfo(int flags, FrameType type) {
            this.flags = flags;
            this.type = type;
        }

        public boolean isRequired() {
            return (flags & PORT_REQUIRED) != 0;
        }

        public String toString(String ioMode, String name) {
            String ioName = ioMode + " " + name;
            String modeName = isRequired() ? "required" : "optional";
            return modeName + " " + ioName + ": " + type.toString();
        }
    }

    /** Indicates that the port must be connected in the graph. */
    public static final int PORT_REQUIRED = 0x02;
    /** Indicates that the port may be connected in the graph . */
    public static final int PORT_OPTIONAL = 0x01;

    /**
     * Creates a new empty Signature.
     */
    public Signature() {
    }

    /**
     * Adds an input port to the Signature.
     *
     * @param name the name of the input port. Must be unique among input port names.
     * @param flags a combination of port flags.
     * @param type the type of the input frame.
     * @return this Signature instance.
     */
    public Signature addInputPort(String name, int flags, FrameType type) {
        addInputPort(name, new PortInfo(flags, type));
        return this;
    }

    /**
     * Adds an output port to the Signature.
     *
     * @param name the name of the output port. Must be unique among output port names.
     * @param flags a combination of port flags.
     * @param type the type of the output frame.
     * @return this Signature instance.
     */
    public Signature addOutputPort(String name, int flags, FrameType type) {
        addOutputPort(name, new PortInfo(flags, type));
        return this;
    }

    /**
     * Disallows the user from adding any other input ports.
     * Adding any input port not explicitly specified in this Signature will cause an error.
     * @return this Signature instance.
     */
    public Signature disallowOtherInputs() {
        mAllowOtherInputs = false;
        return this;
    }

    /**
     * Disallows the user from adding any other output ports.
     * Adding any output port not explicitly specified in this Signature will cause an error.
     * @return this Signature instance.
     */
    public Signature disallowOtherOutputs() {
        mAllowOtherOutputs = false;
        return this;
    }

    /**
     * Disallows the user from adding any other ports.
     * Adding any input or output port not explicitly specified in this Signature will cause an
     * error.
     * @return this Signature instance.
     */
    public Signature disallowOtherPorts() {
        mAllowOtherInputs = false;
        mAllowOtherOutputs = false;
        return this;
    }

    @Override
    public String toString() {
        StringBuffer stringBuffer = new StringBuffer();
        for (Entry<String, PortInfo> entry : mInputPorts.entrySet()) {
            stringBuffer.append(entry.getValue().toString("input", entry.getKey()) + "\n");
        }
        for (Entry<String, PortInfo> entry : mOutputPorts.entrySet()) {
            stringBuffer.append(entry.getValue().toString("output", entry.getKey()) + "\n");
        }
        if (!mAllowOtherInputs) {
            stringBuffer.append("disallow other inputs\n");
        }
        if (!mAllowOtherOutputs) {
            stringBuffer.append("disallow other outputs\n");
        }
        return stringBuffer.toString();
    }

    PortInfo getInputPortInfo(String name) {
        PortInfo result = mInputPorts != null ? mInputPorts.get(name) : null;
        return result != null ? result : new PortInfo();
    }

    PortInfo getOutputPortInfo(String name) {
        PortInfo result = mOutputPorts != null ? mOutputPorts.get(name) : null;
        return result != null ? result : new PortInfo();
    }

    void checkInputPortsConform(Filter filter) {
        Set<String> filterInputs = new HashSet<String>();
        filterInputs.addAll(filter.getConnectedInputPortMap().keySet());
        if (mInputPorts != null) {
            for (Entry<String, PortInfo> entry : mInputPorts.entrySet()) {
                String portName = entry.getKey();
                PortInfo portInfo = entry.getValue();
                InputPort inputPort = filter.getConnectedInputPort(portName);
                if (inputPort == null && portInfo.isRequired()) {
                    throw new RuntimeException("Filter " + filter + " does not have required "
                        + "input port '" + portName + "'!");
                }
                filterInputs.remove(portName);
            }
        }
        if (!mAllowOtherInputs && !filterInputs.isEmpty()) {
            throw new RuntimeException("Filter " + filter + " has invalid input ports: "
                + filterInputs + "!");
        }
    }

    void checkOutputPortsConform(Filter filter) {
        Set<String> filterOutputs = new HashSet<String>();
        filterOutputs.addAll(filter.getConnectedOutputPortMap().keySet());
        if (mOutputPorts != null) {
            for (Entry<String, PortInfo> entry : mOutputPorts.entrySet()) {
                String portName = entry.getKey();
                PortInfo portInfo = entry.getValue();
                OutputPort outputPort = filter.getConnectedOutputPort(portName);
                if (outputPort == null && portInfo.isRequired()) {
                    throw new RuntimeException("Filter " + filter + " does not have required "
                        + "output port '" + portName + "'!");
                }
                filterOutputs.remove(portName);
            }
        }
        if (!mAllowOtherOutputs && !filterOutputs.isEmpty()) {
            throw new RuntimeException("Filter " + filter + " has invalid output ports: "
                + filterOutputs + "!");
        }
    }

    HashMap<String, PortInfo> getInputPorts() {
        return mInputPorts;
    }

    HashMap<String, PortInfo> getOutputPorts() {
        return mOutputPorts;
    }

    private void addInputPort(String name, PortInfo portInfo) {
        if (mInputPorts == null) {
            mInputPorts = new HashMap<String, PortInfo>();
        }
        if (mInputPorts.containsKey(name)) {
            throw new RuntimeException("Attempting to add duplicate input port '" + name + "'!");
        }
        mInputPorts.put(name, portInfo);
    }

    private void addOutputPort(String name, PortInfo portInfo) {
        if (mOutputPorts == null) {
            mOutputPorts = new HashMap<String, PortInfo>();
        }
        if (mOutputPorts.containsKey(name)) {
            throw new RuntimeException("Attempting to add duplicate output port '" + name + "'!");
        }
        mOutputPorts.put(name, portInfo);
    }
}

