/*
 * Copyright (C) 2013 The Android Open Source Project
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

import androidx.media.filterfw.GraphRunner.Listener;
import androidx.media.filterfw.Signature.PortInfo;

import com.google.common.util.concurrent.SettableFuture;

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link TestCase} for testing single MFF filter runs. Implementers should extend this class and
 * implement the {@link #createFilter(MffContext)} method to create the filter under test. Inside
 * each test method, the implementer should supply one or more frames for all the filter inputs
 * (calling {@link #injectInputFrame(String, Frame)}) and then invoke {@link #process()}. Once the
 * processing finishes, one should call {@link #getOutputFrame(String)} to get and inspect the
 * output frames.
 *
 * TODO: extend this to deal with filters that push multiple output frames.
 * TODO: relax the requirement that all output ports should be pushed (the implementer should be
 *       able to tell which ports to wait for before process() returns).
 * TODO: handle undeclared inputs and outputs.
 */
public abstract class MffFilterTestCase extends MffTestCase {

    private static final long DEFAULT_TIMEOUT_MS = 1000;

    private FilterGraph mGraph;
    private GraphRunner mRunner;
    private Map<String, Frame> mOutputFrames;
    private Set<String> mEmptyOutputPorts;

    private SettableFuture<Void> mProcessResult;

    protected abstract Filter createFilter(MffContext mffContext);

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MffContext mffContext = getMffContext();
        FilterGraph.Builder graphBuilder = new FilterGraph.Builder(mffContext);
        Filter filterUnderTest = createFilter(mffContext);
        graphBuilder.addFilter(filterUnderTest);

        connectInputPorts(mffContext, graphBuilder, filterUnderTest);
        connectOutputPorts(mffContext, graphBuilder, filterUnderTest);

        mGraph = graphBuilder.build();
        mRunner = mGraph.getRunner();
        mRunner.setListener(new Listener() {
            @Override
            public void onGraphRunnerStopped(GraphRunner runner) {
                mProcessResult.set(null);
            }

            @Override
            public void onGraphRunnerError(Exception exception, boolean closedSuccessfully) {
                mProcessResult.setException(exception);
            }
        });

        mOutputFrames = new HashMap<String, Frame>();
        mProcessResult = SettableFuture.create();
    }

    @Override
    protected void tearDown() throws Exception {
        for (Frame frame : mOutputFrames.values()) {
            frame.release();
        }
        mOutputFrames = null;

        mRunner.stop();
        mRunner = null;
        mGraph = null;

        mProcessResult = null;
        super.tearDown();
    }

    protected void injectInputFrame(String portName, Frame frame) {
        FrameSourceFilter filter = (FrameSourceFilter) mGraph.getFilter("in_" + portName);
        filter.injectFrame(frame);
    }

    /**
     * Returns the frame pushed out by the filter under test. Should only be called after
     * {@link #process(long)} has returned.
     */
    protected Frame getOutputFrame(String outputPortName) {
        return mOutputFrames.get("out_" + outputPortName);
    }

    protected void process(long timeoutMs)
            throws ExecutionException, TimeoutException, InterruptedException {
        mRunner.start(mGraph);
        mProcessResult.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    protected void process() throws ExecutionException, TimeoutException, InterruptedException {
        process(DEFAULT_TIMEOUT_MS);
    }

    /**
     * This method should be called to create the input frames inside the test cases (instead of
     * {@link Frame#create(FrameType, int[])}). This is required to work around a requirement for
     * the latter method to be called on the MFF thread.
     */
    protected Frame createFrame(FrameType type, int[] dimensions) {
        return new Frame(type, dimensions, mRunner.getFrameManager());
    }

    private void connectInputPorts(
            MffContext mffContext, FilterGraph.Builder graphBuilder, Filter filter) {
        Signature signature = filter.getSignature();
        for (Entry<String, PortInfo> inputPortEntry : signature.getInputPorts().entrySet()) {
            Filter inputFilter = new FrameSourceFilter(mffContext, "in_" + inputPortEntry.getKey());
            graphBuilder.addFilter(inputFilter);
            graphBuilder.connect(inputFilter, "output", filter, inputPortEntry.getKey());
        }
    }

    private void connectOutputPorts(
            MffContext mffContext, FilterGraph.Builder graphBuilder, Filter filter) {
        Signature signature = filter.getSignature();
        mEmptyOutputPorts = new HashSet<String>();
        OutputFrameListener outputFrameListener = new OutputFrameListener();
        for (Entry<String, PortInfo> outputPortEntry : signature.getOutputPorts().entrySet()) {
            FrameTargetFilter outputFilter = new FrameTargetFilter(
                    mffContext, "out_" + outputPortEntry.getKey());
            graphBuilder.addFilter(outputFilter);
            graphBuilder.connect(filter, outputPortEntry.getKey(), outputFilter, "input");
            outputFilter.setListener(outputFrameListener);
            mEmptyOutputPorts.add("out_" + outputPortEntry.getKey());
        }
    }

    private class OutputFrameListener implements FrameTargetFilter.Listener {

        @Override
        public void onFramePushed(String filterName, Frame frame) {
            mOutputFrames.put(filterName, frame);
            boolean alreadyPushed = !mEmptyOutputPorts.remove(filterName);
            if (alreadyPushed) {
                throw new IllegalStateException(
                        "A frame has been pushed twice to the same output port.");
            }
            if (mEmptyOutputPorts.isEmpty()) {
                // All outputs have been pushed, stop the graph.
                mRunner.stop();
            }
        }

    }

}
