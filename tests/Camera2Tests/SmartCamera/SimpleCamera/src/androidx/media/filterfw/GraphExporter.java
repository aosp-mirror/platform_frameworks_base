/*
 * Copyright (C) 2012 The Android Open Source Project
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

// This class provides functions to export a FilterGraph.

package androidx.media.filterfw;

import android.content.Context;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This class provides functions to export a FilterGraph as a DOT file.
 */
public class GraphExporter {

    /**
     * Exports the graph as DOT (see http://en.wikipedia.org/wiki/DOT_language).
     * Using the exported file, the graph can be visualized e.g. with the command line tool dot.
     * Optionally, one may /exclude/ unconnected optional ports (third parameter = false),
     * since they can quickly clutter the visualization (and, depending on the purpose, may not
     * be interesting).
     *
     * Example workflow:
     *  1. run application on device, make sure it calls exportGraphAsDOT(...);
     *  2. adb pull /data/data/<application name>/files/<graph filename>.gv graph.gv
     *  3. dot -Tpng graph.gv -o graph.png
     *  4. eog graph.png
     */
    static public void exportAsDot(FilterGraph graph, String filename,
            boolean includeUnconnectedOptionalPorts)
            throws java.io.FileNotFoundException, java.io.IOException {
        // Initialize, open file stream
        Context myAppContext = graph.getContext().getApplicationContext();
        Filter[] filters = graph.getAllFilters();
        FileOutputStream fOut = myAppContext.openFileOutput(filename, Context.MODE_PRIVATE);
        OutputStreamWriter dotFile = new OutputStreamWriter(fOut);

        // Write beginning of DOT file
        dotFile.write("digraph graphname {\n");
        dotFile.write("  node [shape=record];\n");

        // N.B. For specification and lots of examples of the DOT language, see
        //   http://www.graphviz.org/Documentation/dotguide.pdf

        // Iterate over all filters of the graph, write corresponding DOT node elements

        for(Filter filter : filters) {
            dotFile.write(getDotName("  " + filter.getName()) + " [label=\"{");

            // Write upper part of element (i.e., input ports)
            Set<String> inputPorts = getInputPorts(filter, includeUnconnectedOptionalPorts);
            if(inputPorts.size() > 0) {
                dotFile.write(" { ");
                int counter = 0;
                for(String p : inputPorts) {
                    dotFile.write("<" + getDotName(p) + "_IN>" + p);
                    if(++counter != inputPorts.size()) dotFile.write(" | ");
                }
                dotFile.write(" } | ");
            }

            // Write center part of element (i.e., element label)
            dotFile.write(filter.getName());

            // Write lower part of element (i.e., output ports)
            Set<String> outputPorts = getOutputPorts(filter, includeUnconnectedOptionalPorts);
            if(outputPorts.size() > 0) {
                dotFile.write(" | { ");
                int counter = 0;
                for(String p : outputPorts) {
                    dotFile.write("<" + getDotName(p) + "_OUT>" + p);
                    if(++counter != outputPorts.size()) dotFile.write(" | ");
                }
                dotFile.write(" } ");
            }

            dotFile.write("}\"];\n");
        }
        dotFile.write("\n");

        // Iterate over all filters again to collect connections and find unconnected ports

        int dummyNodeCounter = 0;
        for(Filter filter : filters) {
            Set<String> outputPorts = getOutputPorts(filter, includeUnconnectedOptionalPorts);
            for(String portName : outputPorts) {
                OutputPort source = filter.getConnectedOutputPort(portName);
                if(source != null) {
                    // Found a connection, draw it
                    InputPort target = source.getTarget();
                    dotFile.write("  " +
                        getDotName(source.getFilter().getName()) + ":" +
                        getDotName(source.getName()) + "_OUT -> " +
                        getDotName(target.getFilter().getName()) + ":" +
                        getDotName(target.getName()) + "_IN;\n" );
                } else {
                    // Found a unconnected output port, add placeholder node
                    String color = filter.getSignature().getOutputPortInfo(portName).isRequired()
                        ? "red" : "blue";  // red for unconnected, required ports
                    dotFile.write("  " +
                        "dummy" + (++dummyNodeCounter) +
                        " [shape=point,label=\"\",color=" + color + "];\n" +
                        "  " + getDotName(filter.getName()) + ":" +
                        getDotName(portName) + "_OUT -> " +
                        "dummy" + dummyNodeCounter + " [color=" + color + "];\n");
                }
            }

            Set<String> inputPorts = getInputPorts(filter, includeUnconnectedOptionalPorts);
            for(String portName : inputPorts) {
                InputPort target = filter.getConnectedInputPort(portName);
                if(target != null) {
                    // Found a connection -- nothing to do, connections have been written out above
                } else {
                    // Found a unconnected input port, add placeholder node
                    String color = filter.getSignature().getInputPortInfo(portName).isRequired()
                        ? "red" : "blue";  // red for unconnected, required ports
                    dotFile.write("  " +
                        "dummy" + (++dummyNodeCounter) +
                        " [shape=point,label=\"\",color=" + color + "];\n" +
                        "  dummy" + dummyNodeCounter + " -> " +
                        getDotName(filter.getName()) + ":" +
                        getDotName(portName) + "_IN [color=" + color + "];\n");
                }
            }
        }

        // Write end of DOT file, close file stream
        dotFile.write("}\n");
        dotFile.flush();
        dotFile.close();
    }

    // Internal methods

    // From element's name in XML, create DOT-allowed element name
    static private String getDotName(String raw) {
        return raw.replaceAll("\\.", "___"); // DOT does not allow . in element names
    }

    // Retrieve all input ports of a filter, including:
    //  unconnected ports (which can not be retrieved from the filter, only from the signature), and
    //  additional (connected) ports not listed in the signature (which is allowed by default,
    //    unless disallowOtherInputs is defined in signature).
    // With second parameter = false, *omit* unconnected optional ports.
    static private Set<String> getInputPorts(Filter filter, boolean includeUnconnectedOptional) {
        // add (connected) ports from filter
        Set<String> ports = new HashSet<String>();
        ports.addAll(filter.getConnectedInputPortMap().keySet());

        // add (unconnected) ports from signature
        HashMap<String, Signature.PortInfo> signaturePorts = filter.getSignature().getInputPorts();
        if(signaturePorts != null){
            for(Entry<String, Signature.PortInfo> e : signaturePorts.entrySet()) {
                if(includeUnconnectedOptional || e.getValue().isRequired()) {
                    ports.add(e.getKey());
                }
            }
        }
        return ports;
    }

    // Retrieve all output ports of a filter (analogous to above function)
    static private Set<String> getOutputPorts(Filter filter, boolean includeUnconnectedOptional) {
        // add (connected) ports from filter
        Set<String> ports = new HashSet<String>();
        ports.addAll(filter.getConnectedOutputPortMap().keySet());

        // add (unconnected) ports from signature
        HashMap<String, Signature.PortInfo> signaturePorts = filter.getSignature().getOutputPorts();
        if(signaturePorts != null){
            for(Entry<String, Signature.PortInfo> e : signaturePorts.entrySet()) {
                if(includeUnconnectedOptional || e.getValue().isRequired()) {
                    ports.add(e.getKey());
                }
            }
        }
        return ports;
    }
}
