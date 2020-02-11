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


package android.filterfw;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.filterfw.core.AsyncRunner;
import android.filterfw.core.FilterContext;
import android.filterfw.core.FilterGraph;
import android.filterfw.core.FrameManager;
import android.filterfw.core.GraphRunner;
import android.filterfw.core.RoundRobinScheduler;
import android.filterfw.core.SyncRunner;
import android.filterfw.io.GraphIOException;
import android.filterfw.io.GraphReader;
import android.filterfw.io.TextGraphReader;

import java.util.ArrayList;

/**
 * A GraphEnvironment provides a simple front-end to filter graph setup and execution using the
 * mobile filter framework. Typically, you use a GraphEnvironment in the following fashion:
 *   1. Instantiate a new GraphEnvironment instance.
 *   2. Perform any configuration, such as adding graph references and setting a GL environment.
 *   3. Load a graph file using loadGraph() or add a graph using addGraph().
 *   4. Obtain a GraphRunner instance using getRunner().
 *   5. Execute the obtained runner.
 * Note that it is possible to add multiple graphs and runners to a single GraphEnvironment.
 *
 * @hide
 */
public class GraphEnvironment extends MffEnvironment {

    public static final int MODE_ASYNCHRONOUS = 1;
    public static final int MODE_SYNCHRONOUS  = 2;

    private GraphReader mGraphReader;
    private ArrayList<GraphHandle> mGraphs = new ArrayList<GraphHandle>();

    private class GraphHandle {
        private FilterGraph mGraph;
        private AsyncRunner mAsyncRunner;
        private SyncRunner mSyncRunner;

        public GraphHandle(FilterGraph graph) {
            mGraph = graph;
        }

        public FilterGraph getGraph() {
            return mGraph;
        }

        public AsyncRunner getAsyncRunner(FilterContext environment) {
            if (mAsyncRunner == null) {
                mAsyncRunner = new AsyncRunner(environment, RoundRobinScheduler.class);
                mAsyncRunner.setGraph(mGraph);
            }
            return mAsyncRunner;
        }

        public GraphRunner getSyncRunner(FilterContext environment) {
            if (mSyncRunner == null) {
                mSyncRunner = new SyncRunner(environment, mGraph, RoundRobinScheduler.class);
            }
            return mSyncRunner;
        }
    }

    /**
     * Create a new GraphEnvironment with default components.
     */
    @UnsupportedAppUsage
    public GraphEnvironment() {
        super(null);
    }

    /**
     * Create a new GraphEnvironment with a custom FrameManager and GraphReader. Specifying null
     * for either of these, will auto-create a default instance.
     *
     * @param frameManager The FrameManager to use, or null to auto-create one.
     * @param reader        The GraphReader to use for graph loading, or null to auto-create one.
     *                      Note, that the reader will not be created until it is required. Pass
     *                      null if you will not load any graph files.
     */
    public GraphEnvironment(FrameManager frameManager, GraphReader reader) {
        super(frameManager);
        mGraphReader = reader;
    }

    /**
     * Returns the used graph reader. This will create one, if a reader has not been set already.
     */
    public GraphReader getGraphReader() {
        if (mGraphReader == null) {
            mGraphReader = new TextGraphReader();
        }
        return mGraphReader;
    }

    /**
     * Add graph references to resolve during graph reading. The references added here are shared
     * among all graphs.
     *
     * @param references An alternating argument list of keys (Strings) and values.
     */
    @UnsupportedAppUsage
    public void addReferences(Object... references) {
        getGraphReader().addReferencesByKeysAndValues(references);
    }

    /**
     * Loads a graph file from the specified resource and adds it to this environment.
     *
     * @param context       The context in which to read the resource.
     * @param resourceId    The ID of the graph resource to load.
     * @return              A unique ID for the graph.
     */
    @UnsupportedAppUsage
    public int loadGraph(Context context, int resourceId) {
        // Read the file into a graph
        FilterGraph graph = null;
        try {
            graph = getGraphReader().readGraphResource(context, resourceId);
        } catch (GraphIOException e) {
            throw new RuntimeException("Could not read graph: " + e.getMessage());
        }

        // Add graph to our list of graphs
        return addGraph(graph);
    }

    /**
     * Add a graph to the environment. Consider using loadGraph() if you are loading a graph from
     * a graph file.
     *
     * @param graph The graph to add to the environment.
     * @return      A unique ID for the added graph.
     */
    public int addGraph(FilterGraph graph) {
        GraphHandle graphHandle = new GraphHandle(graph);
        mGraphs.add(graphHandle);
        return mGraphs.size() - 1;
    }

    /**
     * Access a specific graph of this environment given a graph ID (previously returned from
     * loadGraph() or addGraph()). Throws an InvalidArgumentException if no graph with the
     * specified ID could be found.
     *
     * @param graphId   The ID of the graph to get.
     * @return          The graph with the specified ID.
     */
    public FilterGraph getGraph(int graphId) {
        if (graphId < 0 || graphId >= mGraphs.size()) {
            throw new IllegalArgumentException(
                "Invalid graph ID " + graphId + " specified in runGraph()!");
        }
        return mGraphs.get(graphId).getGraph();
    }

    /**
     * Get a GraphRunner instance for the graph with the specified ID. The GraphRunner instance can
     * be used to execute the graph. Throws an InvalidArgumentException if no graph with the
     * specified ID could be found.
     *
     * @param graphId       The ID of the graph to get.
     * @param executionMode The mode of graph execution. Currently this can be either
                            MODE_SYNCHRONOUS or MODE_ASYNCHRONOUS.
     * @return              A GraphRunner instance for this graph.
     */
    @UnsupportedAppUsage
    public GraphRunner getRunner(int graphId, int executionMode) {
        switch (executionMode) {
            case MODE_ASYNCHRONOUS:
                return mGraphs.get(graphId).getAsyncRunner(getContext());

            case MODE_SYNCHRONOUS:
                return mGraphs.get(graphId).getSyncRunner(getContext());

            default:
                throw new RuntimeException(
                    "Invalid execution mode " + executionMode + " specified in getRunner()!");
        }
    }

}
