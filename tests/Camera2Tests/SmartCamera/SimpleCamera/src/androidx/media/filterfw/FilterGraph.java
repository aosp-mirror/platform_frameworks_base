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

import android.util.Log;
import android.view.View;
import androidx.media.filterpacks.base.BranchFilter;
import androidx.media.filterpacks.base.FrameSlotSource;
import androidx.media.filterpacks.base.FrameSlotTarget;
import androidx.media.filterpacks.base.GraphInputSource;
import androidx.media.filterpacks.base.GraphOutputTarget;
import androidx.media.filterpacks.base.ValueTarget;
import androidx.media.filterpacks.base.ValueTarget.ValueListener;
import androidx.media.filterpacks.base.VariableSource;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

/**
 * A graph of Filter nodes.
 *
 * A FilterGraph instance contains a set of Filter instances connected by their output and input
 * ports. Every filter belongs to exactly one graph and cannot be moved to another graph.
 *
 * FilterGraphs may contain sub-graphs that are dependent on the parent graph. These are typically
 * used when inserting sub-graphs into MetaFilters. When a parent graph is torn down so are its
 * sub-graphs. The same applies to flushing frames of a graph.
 */
public class FilterGraph {

    private final static boolean DEBUG = false;

    /** The context that this graph lives in */
    private MffContext mContext;

    /** Map from name of filter to the filter instance */
    private HashMap<String, Filter> mFilterMap = new HashMap<String, Filter>();

    /** Allows quick access to array of all filters. */
    private Filter[] mAllFilters = null;

    /** The GraphRunner currently attached to this graph */
    GraphRunner mRunner;

    /** The set of sub-graphs of this graph */
    HashSet<FilterGraph> mSubGraphs = new HashSet<FilterGraph>();

    /** The parent graph of this graph, or null it this graph is a root graph. */
    private FilterGraph mParentGraph;

    public static class Builder {

        /** The context that this builder lives in */
        private MffContext mContext;

        /** Map from name of filter to the filter instance */
        private HashMap<String, Filter> mFilterMap = new HashMap<String, Filter>();

        /**
         * Creates a new builder for specifying a graph structure.
         * @param context The context the graph will live in.
         */
        public Builder(MffContext context) {
            mContext = context;
        }

        /**
         * Add a filter to the graph.
         *
         * Adds the specified filter to the set of filters of this graph. The filter must not be in
         * the graph already, and the filter's name must be unique within the graph.
         *
         * @param filter the filter to add to the graph.
         * @throws IllegalArgumentException if the filter is in the graph already, or its name is
         *                                  is already taken.
         */
        public void addFilter(Filter filter) {
            if (mFilterMap.values().contains(filter)) {
                throw new IllegalArgumentException("Attempting to add filter " + filter + " that "
                    + "is in the graph already!");
            } else if (mFilterMap.containsKey(filter.getName())) {
                throw new IllegalArgumentException("Graph contains filter with name '"
                    + filter.getName() + "' already!");
            } else {
                mFilterMap.put(filter.getName(), filter);
            }
        }

        /**
         * Adds a variable to the graph.
         *
         * TODO: More documentation.
         *
         * @param name the name of the variable.
         * @param value the value of the variable or null if no value is to be set yet.
         * @return the VariableSource filter that holds the value of this variable.
         */
        public VariableSource addVariable(String name, Object value) {
            if (getFilter(name) != null) {
                throw new IllegalArgumentException("Filter named '" + name + "' exists already!");
            }
            VariableSource valueSource = new VariableSource(mContext, name);
            addFilter(valueSource);
            if (value != null) {
                valueSource.setValue(value);
            }
            return valueSource;
        }

        public FrameSlotSource addFrameSlotSource(String name, String slotName) {
            FrameSlotSource filter = new FrameSlotSource(mContext, name, slotName);
            addFilter(filter);
            return filter;
        }

        public FrameSlotTarget addFrameSlotTarget(String name, String slotName) {
            FrameSlotTarget filter = new FrameSlotTarget(mContext, name, slotName);
            addFilter(filter);
            return filter;
        }

        /**
         * Connect two filters by their ports.
         * The filters specified must have been previously added to the graph builder.
         *
         * @param sourceFilterName The name of the source filter.
         * @param sourcePort The name of the source port.
         * @param targetFilterName The name of the target filter.
         * @param targetPort The name of the target port.
         */
        public void connect(String sourceFilterName, String sourcePort,
                            String targetFilterName, String targetPort) {
            Filter sourceFilter = getFilter(sourceFilterName);
            Filter targetFilter = getFilter(targetFilterName);
            if (sourceFilter == null) {
                throw new IllegalArgumentException("Unknown filter '" + sourceFilterName + "'!");
            } else if (targetFilter == null) {
                throw new IllegalArgumentException("Unknown filter '" + targetFilterName + "'!");
            }
            connect(sourceFilter, sourcePort, targetFilter, targetPort);
        }

        /**
         * Connect two filters by their ports.
         * The filters specified must have been previously added to the graph builder.
         *
         * @param sourceFilter The source filter.
         * @param sourcePort The name of the source port.
         * @param targetFilter The target filter.
         * @param targetPort The name of the target port.
         */
        public void connect(Filter sourceFilter, String sourcePort,
                            Filter targetFilter, String targetPort) {
            sourceFilter.connect(sourcePort, targetFilter, targetPort);
        }

        /**
         * Returns the filter with the specified name.
         *
         * @return the filter with the specified name, or null if no such filter exists.
         */
        public Filter getFilter(String name) {
            return mFilterMap.get(name);
        }

        /**
         * Builds the graph and checks signatures.
         *
         * @return The new graph instance.
         */
        public FilterGraph build() {
            checkSignatures();
            return buildWithParent(null);
        }

        /**
         * Builds the sub-graph and checks signatures.
         *
         * @param parentGraph the parent graph of the built sub-graph.
         * @return The new graph instance.
         */
        public FilterGraph buildSubGraph(FilterGraph parentGraph) {
            if (parentGraph == null) {
                throw new NullPointerException("Parent graph must be non-null!");
            }
            checkSignatures();
            return buildWithParent(parentGraph);
        }

        VariableSource assignValueToFilterInput(Object value, String filterName, String inputName) {
            // Get filter to connect to
            Filter filter = getFilter(filterName);
            if (filter == null) {
                throw new IllegalArgumentException("Unknown filter '" + filterName + "'!");
            }

            // Construct a name for our value source and make sure it does not exist already
            String valueSourceName = filterName + "." + inputName;
            if (getFilter(valueSourceName) != null) {
                throw new IllegalArgumentException("VariableSource for '" + filterName + "' and "
                    + "input '" + inputName + "' exists already!");
            }

            // Create new VariableSource and connect it to the target filter and port
            VariableSource valueSource = new VariableSource(mContext, valueSourceName);
            addFilter(valueSource);
            try {
                ((Filter)valueSource).connect("value", filter, inputName);
            } catch (RuntimeException e) {
                throw new RuntimeException("Could not connect VariableSource to input '" + inputName
                    + "' of filter '" + filterName + "'!", e);
            }

            // Assign the value to the VariableSource
            if (value != null) {
                valueSource.setValue(value);
            }

            return valueSource;
        }

        VariableSource assignVariableToFilterInput(String varName,
                                                   String filterName,
                                                   String inputName) {
            // Get filter to connect to
            Filter filter = getFilter(filterName);
            if (filter == null) {
                throw new IllegalArgumentException("Unknown filter '" + filterName + "'!");
            }

            // Get variable
            Filter variable = getFilter(varName);
            if (variable == null || !(variable instanceof VariableSource)) {
                throw new IllegalArgumentException("Unknown variable '" + varName + "'!");
            }

            // Connect variable (and possibly branch) variable to filter
            try {
                connectAndBranch(variable, "value", filter, inputName);
            } catch (RuntimeException e) {
                throw new RuntimeException("Could not connect VariableSource to input '" + inputName
                    + "' of filter '" + filterName + "'!", e);
            }

            return (VariableSource)variable;
        }

        /**
         * Builds the graph without checking signatures.
         * If parent is non-null, build a sub-graph of the specified parent.
         *
         * @return The new graph instance.
         */
        private FilterGraph buildWithParent(FilterGraph parent) {
            FilterGraph graph = new FilterGraph(mContext, parent);
            graph.mFilterMap = mFilterMap;
            graph.mAllFilters = mFilterMap.values().toArray(new Filter[0]);
            for (Entry<String, Filter> filterEntry : mFilterMap.entrySet()) {
                filterEntry.getValue().insertIntoFilterGraph(graph);
            }
            return graph;
        }

        private void checkSignatures() {
            checkSignaturesForFilters(mFilterMap.values());
        }

        // TODO: Currently this always branches even if the connection is a 1:1 connection. Later
        // we may optimize to pass through directly in the 1:1 case (may require disconnecting
        // ports).
        private void connectAndBranch(Filter sourceFilter,
                                      String sourcePort,
                                      Filter targetFilter,
                                      String targetPort) {
            String branchName = "__" + sourceFilter.getName() + "_" + sourcePort + "Branch";
            Filter branch = getFilter(branchName);
            if (branch == null) {
                branch = new BranchFilter(mContext, branchName, false);
                addFilter(branch);
                sourceFilter.connect(sourcePort, branch, "input");
            }
            String portName = "to" + targetFilter.getName() + "_" + targetPort;
            branch.connect(portName, targetFilter, targetPort);
        }

    }

    /**
     * Attach the graph and its subgraphs to a custom GraphRunner.
     *
     * Call this if you want the graph to be executed by a specific GraphRunner. You must call
     * this before any other runner is set. Note that calls to {@code getRunner()} and
     * {@code run()} auto-create a GraphRunner.
     *
     * @param runner The GraphRunner instance that should execute this graph.
     * @see #getRunner()
     * @see #run()
     */
    public void attachToRunner(GraphRunner runner) {
        if (mRunner == null) {
            for (FilterGraph subGraph : mSubGraphs) {
                subGraph.attachToRunner(runner);
            }
            runner.attachGraph(this);
            mRunner = runner;
        } else if (mRunner != runner) {
            throw new RuntimeException("Cannot attach FilterGraph to GraphRunner that is already "
                + "attached to another GraphRunner!");
        }
    }

    /**
     * Forcibly tear down a filter graph.
     *
     * Call this to release any resources associated with the filter graph, its filters and any of
     * its sub-graphs. This method must not be called if the graph (or any sub-graph) is running.
     *
     * You may no longer access this graph instance or any of its subgraphs after calling this
     * method.
     *
     * Tearing down of sub-graphs is not supported. You must tear down the root graph, which will
     * tear down all of its sub-graphs.
     *
     * @throws IllegalStateException if the graph is still running.
     * @throws RuntimeException if you attempt to tear down a sub-graph.
     */
    public void tearDown() {
        assertNotRunning();
        if (mParentGraph != null) {
            throw new RuntimeException("Attempting to tear down sub-graph!");
        }
        if (mRunner != null) {
            mRunner.tearDownGraph(this);
        }
        for (FilterGraph subGraph : mSubGraphs) {
            subGraph.mParentGraph = null;
            subGraph.tearDown();
        }
        mSubGraphs.clear();
    }

    /**
     * Returns the context of the graph.
     *
     * @return the MffContext instance that this graph is bound to.
     */
    public MffContext getContext() {
        return mContext;
    }

    /**
     * Returns the filter with the specified name.
     *
     * @return the filter with the specified name, or null if no such filter exists.
     */
    public Filter getFilter(String name) {
        return mFilterMap.get(name);
    }

    /**
     * Returns the VariableSource for the specified variable.
     *
     * TODO: More documentation.
     * TODO: More specialized error handling.
     *
     * @param name The name of the VariableSource.
     * @return The VariableSource filter instance with the specified name.
     */
    public VariableSource getVariable(String name) {
        Filter result = mFilterMap.get(name);
        if (result != null && result instanceof VariableSource) {
            return (VariableSource)result;
        } else {
            throw new IllegalArgumentException("Unknown variable '" + name + "' specified!");
        }
    }

    /**
     * Returns the GraphOutputTarget with the specified name.
     *
     * @param name The name of the target.
     * @return The GraphOutputTarget instance with the specified name.
     */
    public GraphOutputTarget getGraphOutput(String name) {
        Filter result = mFilterMap.get(name);
        if (result != null && result instanceof GraphOutputTarget) {
            return (GraphOutputTarget)result;
        } else {
            throw new IllegalArgumentException("Unknown target '" + name + "' specified!");
        }
    }

    /**
     * Returns the GraphInputSource with the specified name.
     *
     * @param name The name of the source.
     * @return The GraphInputSource instance with the specified name.
     */
    public GraphInputSource getGraphInput(String name) {
        Filter result = mFilterMap.get(name);
        if (result != null && result instanceof GraphInputSource) {
            return (GraphInputSource)result;
        } else {
            throw new IllegalArgumentException("Unknown source '" + name + "' specified!");
        }
    }

    /**
     * Binds a filter to a view.
     *
     * ViewFilter instances support visualizing their data to a view. See the specific filter
     * documentation for details. Views may be bound only if the graph is not running.
     *
     * @param filterName the name of the filter to bind.
     * @param view the view to bind to.
     * @throws IllegalStateException if the filter is in an illegal state.
     * @throws IllegalArgumentException if no such view-filter exists.
     */
    public void bindFilterToView(String filterName, View view) {
        Filter filter = mFilterMap.get(filterName);
        if (filter != null && filter instanceof ViewFilter) {
            ((ViewFilter)filter).bindToView(view);
        } else {
            throw new IllegalArgumentException("Unknown view filter '" + filterName + "'!");
        }
    }

    /**
     * TODO: Documentation.
     */
    public void bindValueTarget(String filterName, ValueListener listener, boolean onCallerThread) {
        Filter filter = mFilterMap.get(filterName);
        if (filter != null && filter instanceof ValueTarget) {
            ((ValueTarget)filter).setListener(listener, onCallerThread);
        } else {
            throw new IllegalArgumentException("Unknown ValueTarget filter '" + filterName + "'!");
        }
    }

    // Running Graphs //////////////////////////////////////////////////////////////////////////////
    /**
     * Convenience method to run the graph.
     *
     * Creates a new runner for this graph in the specified mode and executes it. Returns the
     * runner to allow control of execution.
     *
     * @throws IllegalStateException if the graph is already running.
     * @return the GraphRunner instance that was used for execution.
     */
    public GraphRunner run() {
        GraphRunner runner = getRunner();
        runner.setIsVerbose(false);
        runner.start(this);
        return runner;
    }

    /**
     * Returns the GraphRunner for this graph.
     *
     * Every FilterGraph instance has a GraphRunner instance associated with it for executing the
     * graph.
     *
     * @return the GraphRunner instance for this graph.
     */
    public GraphRunner getRunner() {
        if (mRunner == null) {
            GraphRunner runner = new GraphRunner(mContext);
            attachToRunner(runner);
        }
        return mRunner;
    }

    /**
     * Returns whether the graph is currently running.
     *
     * @return true if the graph is currently running.
     */
    public boolean isRunning() {
        return mRunner != null && mRunner.isRunning();
    }

    /**
     * Check each filter's signatures if all requirements are fulfilled.
     *
     * This will throw a RuntimeException if any unfulfilled requirements are found.
     * Note that FilterGraph.Builder also has a function checkSignatures(), which allows
     * to do the same /before/ the FilterGraph is built.
     */
    public void checkSignatures() {
        checkSignaturesForFilters(mFilterMap.values());
    }

    // MFF Internal Methods ////////////////////////////////////////////////////////////////////////
    Filter[] getAllFilters() {
        return mAllFilters;
    }

    static void checkSignaturesForFilters(Collection<Filter> filters) {
        for (Filter filter : filters) {
            if (DEBUG) {
                Log.d("FilterGraph", "Checking filter " + filter.getName() + "...");
            }
            Signature signature = filter.getSignature();
            signature.checkInputPortsConform(filter);
            signature.checkOutputPortsConform(filter);
        }
    }

    /**
     * Wipes the filter references in this graph, so that they may be collected.
     *
     * This must be called only after a tearDown as this will make the FilterGraph invalid.
     */
    void wipe() {
        mAllFilters = null;
        mFilterMap = null;
    }

    void flushFrames() {
        for (Filter filter : mFilterMap.values()) {
            for (InputPort inputPort : filter.getConnectedInputPorts()) {
                inputPort.clear();
            }
            for (OutputPort outputPort : filter.getConnectedOutputPorts()) {
                outputPort.clear();
            }
        }
    }

    Set<FilterGraph> getSubGraphs() {
        return mSubGraphs;
    }

    // Internal Methods ////////////////////////////////////////////////////////////////////////////
    private FilterGraph(MffContext context, FilterGraph parentGraph) {
        mContext = context;
        mContext.addGraph(this);
        if (parentGraph != null) {
            mParentGraph = parentGraph;
            mParentGraph.mSubGraphs.add(this);
        }
    }

    private void assertNotRunning() {
        if (isRunning()) {
            throw new IllegalStateException("Attempting to modify running graph!");
        }
    }
}

