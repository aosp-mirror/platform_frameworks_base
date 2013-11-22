/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package androidx.media.filterfw;

import android.os.ConditionVariable;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A GraphRunner schedules and executes the filter nodes of a graph.
 *
 * Typically, you create a GraphRunner given a FilterGraph instance, and execute it by calling
 * {@link #start(FilterGraph)}.
 *
 * The scheduling strategy determines how the filter nodes are selected
 * for scheduling. More precisely, given the set of nodes that can be scheduled, the scheduling
 * strategy determines which node of this set to select for execution. For instance, an LFU
 * scheduler (the default) chooses the node that has been executed the least amount of times.
 */
public final class GraphRunner {

    private static int PRIORITY_SLEEP = -1;
    private static int PRIORITY_STOP = -2;

    private static final Event BEGIN_EVENT = new Event(Event.BEGIN, null);
    private static final Event FLUSH_EVENT = new Event(Event.FLUSH, null);
    private static final Event HALT_EVENT = new Event(Event.HALT, null);
    private static final Event KILL_EVENT = new Event(Event.KILL, null);
    private static final Event PAUSE_EVENT = new Event(Event.PAUSE, null);
    private static final Event RELEASE_FRAMES_EVENT = new Event(Event.RELEASE_FRAMES, null);
    private static final Event RESTART_EVENT = new Event(Event.RESTART, null);
    private static final Event RESUME_EVENT = new Event(Event.RESUME, null);
    private static final Event STEP_EVENT = new Event(Event.STEP, null);
    private static final Event STOP_EVENT = new Event(Event.STOP, null);

    private static class State {
        public static final int STOPPED = 1;
        public static final int PREPARING = 2;
        public static final int RUNNING = 4;
        public static final int PAUSED = 8;
        public static final int HALTED = 16;

        private int mCurrent = STOPPED;

        public synchronized void setState(int newState) {
            mCurrent = newState;
        }

        public synchronized boolean check(int state) {
            return ((mCurrent & state) == state);
        }

        public synchronized boolean addState(int state) {
            if ((mCurrent & state) != state) {
                mCurrent |= state;
                return true;
            }
            return false;
        }

        public synchronized boolean removeState(int state) {
            boolean result = (mCurrent & state) == state;
            mCurrent &= (~state);
            return result;
        }

        public synchronized int current() {
            return mCurrent;
        }
    }

    private static class Event {
        public static final int PREPARE = 1;
        public static final int BEGIN = 2;
        public static final int STEP = 3;
        public static final int STOP = 4;
        public static final int PAUSE = 6;
        public static final int HALT = 7;
        public static final int RESUME = 8;
        public static final int RESTART = 9;
        public static final int FLUSH = 10;
        public static final int TEARDOWN = 11;
        public static final int KILL = 12;
        public static final int RELEASE_FRAMES = 13;

        public int code;
        public Object object;

        public Event(int code, Object object) {
            this.code = code;
            this.object = object;
        }
    }

    private final class GraphRunLoop implements Runnable {

        private State mState = new State();
        private final boolean mAllowOpenGL;
        private RenderTarget mRenderTarget = null;
        private LinkedBlockingQueue<Event> mEventQueue = new LinkedBlockingQueue<Event>();
        private Exception mCaughtException = null;
        private boolean mClosedSuccessfully = true;
        private Stack<Filter[]> mFilters = new Stack<Filter[]>();
        private Stack<SubListener> mSubListeners = new Stack<SubListener>();
        private Set<FilterGraph> mOpenedGraphs = new HashSet<FilterGraph>();
        public ConditionVariable mStopCondition = new ConditionVariable(true);

        private void loop() {
            boolean killed = false;
            while (!killed) {
                try {
                    Event event = nextEvent();
                    if (event == null) continue;
                    switch (event.code) {
                        case Event.PREPARE:
                            onPrepare((FilterGraph)event.object);
                            break;
                        case Event.BEGIN:
                            onBegin();
                            break;
                        case Event.STEP:
                            onStep();
                            break;
                        case Event.STOP:
                            onStop();
                            break;
                        case Event.PAUSE:
                            onPause();
                            break;
                        case Event.HALT:
                            onHalt();
                            break;
                        case Event.RESUME:
                            onResume();
                            break;
                        case Event.RESTART:
                            onRestart();
                            break;
                        case Event.FLUSH:
                            onFlush();
                            break;
                        case Event.TEARDOWN:
                            onTearDown((FilterGraph)event.object);
                            break;
                        case Event.KILL:
                            killed = true;
                            break;
                        case Event.RELEASE_FRAMES:
                            onReleaseFrames();
                            break;
                    }
                } catch (Exception e) {
                    if (mCaughtException == null) {
                        mCaughtException = e;
                        mClosedSuccessfully = true;
                        e.printStackTrace();
                        pushEvent(STOP_EVENT);
                    } else {
                        // Exception during exception recovery? Abort all processing. Do not
                        // overwrite the original exception.
                        mClosedSuccessfully = false;
                        mEventQueue.clear();
                        cleanUp();
                    }
                }
            }
        }

        public GraphRunLoop(boolean allowOpenGL) {
            mAllowOpenGL = allowOpenGL;
        }

        @Override
        public void run() {
            onInit();
            loop();
            onDestroy();
        }

        public void enterSubGraph(FilterGraph graph, SubListener listener) {
            if (mState.check(State.RUNNING)) {
                onOpenGraph(graph);
                mSubListeners.push(listener);
            }
        }

        public void pushWakeEvent(Event event) {
            // This is of course not race-condition proof. The worst case is that the event
            // is pushed even though the queue was not empty, which is acceptible for our cases.
            if (mEventQueue.isEmpty()) {
                pushEvent(event);
            }
        }

        public void pushEvent(Event event) {
            mEventQueue.offer(event);
        }

        public void pushEvent(int eventId, Object object) {
            mEventQueue.offer(new Event(eventId, object));
        }

        public boolean checkState(int state) {
            return mState.check(state);
        }

        public ConditionVariable getStopCondition() {
            return mStopCondition;
        }

        public boolean isOpenGLAllowed() {
            // Does not need synchronization as mAllowOpenGL flag is final.
            return mAllowOpenGL;
        }

        private Event nextEvent() {
            try {
                return mEventQueue.take();
            } catch (InterruptedException e) {
                // Ignore and keep going.
                Log.w("GraphRunner", "Event queue processing was interrupted.");
                return null;
            }
        }

        private void onPause() {
            mState.addState(State.PAUSED);
        }

        private void onResume() {
            if (mState.removeState(State.PAUSED)) {
                if (mState.current() == State.RUNNING) {
                    pushEvent(STEP_EVENT);
                }
            }
        }

        private void onHalt() {
            if (mState.addState(State.HALTED) && mState.check(State.RUNNING)) {
                closeAllFilters();
            }
        }

        private void onRestart() {
            if (mState.removeState(State.HALTED)) {
                if (mState.current() == State.RUNNING) {
                    pushEvent(STEP_EVENT);
                }
            }
        }

        private void onDestroy() {
            mFrameManager.destroyBackings();
            if (mRenderTarget != null) {
                mRenderTarget.release();
                mRenderTarget = null;
            }
        }

        private void onReleaseFrames() {
            mFrameManager.destroyBackings();
        }

        private void onInit() {
            mThreadRunner.set(GraphRunner.this);
            if (getContext().isOpenGLSupported()) {
                mRenderTarget = RenderTarget.newTarget(1, 1);
                mRenderTarget.focus();
            }
        }

        private void onPrepare(FilterGraph graph) {
            if (mState.current() == State.STOPPED) {
                mState.setState(State.PREPARING);
                mCaughtException = null;
                onOpenGraph(graph);
            }
        }

        private void onOpenGraph(FilterGraph graph) {
            loadFilters(graph);
            mOpenedGraphs.add(graph);
            mScheduler.prepare(currentFilters());
            pushEvent(BEGIN_EVENT);
        }

        private void onBegin() {
            if (mState.current() == State.PREPARING) {
                mState.setState(State.RUNNING);
                pushEvent(STEP_EVENT);
            }
        }

        private void onStarve() {
            mFilters.pop();
            if (mFilters.empty()) {
                onStop();
            } else {
                SubListener listener = mSubListeners.pop();
                if (listener != null) {
                    listener.onSubGraphRunEnded(GraphRunner.this);
                }
                mScheduler.prepare(currentFilters());
                pushEvent(STEP_EVENT);
            }
        }

        private void onStop() {
            if (mState.check(State.RUNNING)) {
                // Close filters if not already halted (and already closed)
                if (!mState.check(State.HALTED)) {
                    closeAllFilters();
                }
                cleanUp();
            }
        }

        private void cleanUp() {
            mState.setState(State.STOPPED);
            if (flushOnClose()) {
                onFlush();
            }
            mOpenedGraphs.clear();
            mFilters.clear();
            onRunnerStopped(mCaughtException, mClosedSuccessfully);
            mStopCondition.open();
        }

        private void onStep() {
            if (mState.current() == State.RUNNING) {
                Filter bestFilter = null;
                long maxPriority = PRIORITY_STOP;
                mScheduler.beginStep();
                Filter[] filters = currentFilters();
                for (int i = 0; i < filters.length; ++i) {
                    Filter filter = filters[i];
                    long priority = mScheduler.priorityForFilter(filter);
                    if (priority > maxPriority) {
                        maxPriority = priority;
                        bestFilter = filter;
                    }
                }
                if (maxPriority == PRIORITY_SLEEP) {
                    // NOOP: When going into sleep mode, we simply do not schedule another node.
                    // If some other event (such as a resume()) does schedule, then we may schedule
                    // during sleeping. This is an edge case an irrelevant. (On the other hand,
                    // going into a dedicated "sleep state" requires highly complex synchronization
                    // to not "miss" a wake-up event. Thus we choose the more defensive approach
                    // here).
                } else if (maxPriority == PRIORITY_STOP) {
                    onStarve();
                } else {
                    scheduleFilter(bestFilter);
                    pushEvent(STEP_EVENT);
                }
            } else {
                Log.w("GraphRunner", "State is not running! (" + mState.current() + ")");
            }
        }

        private void onFlush() {
           if (mState.check(State.HALTED) || mState.check(State.STOPPED)) {
               for (FilterGraph graph : mOpenedGraphs) {
                   graph.flushFrames();
               }
           }
        }

        private void onTearDown(FilterGraph graph) {
            for (Filter filter : graph.getAllFilters()) {
                filter.performTearDown();
            }
            graph.wipe();
        }

        private void loadFilters(FilterGraph graph) {
            Filter[] filters = graph.getAllFilters();
            mFilters.push(filters);
        }

        private void closeAllFilters() {
            for (FilterGraph graph : mOpenedGraphs) {
                closeFilters(graph);
            }
        }

        private void closeFilters(FilterGraph graph) {
            // [Non-iterator looping]
            Log.v("GraphRunner", "CLOSING FILTERS");
            Filter[] filters = graph.getAllFilters();
            boolean isVerbose = isVerbose();
            for (int i = 0; i < filters.length; ++i) {
                if (isVerbose) {
                    Log.i("GraphRunner", "Closing Filter " + filters[i] + "!");
                }
                filters[i].softReset();
            }
        }

        private Filter[] currentFilters() {
            return mFilters.peek();
        }

        private void scheduleFilter(Filter filter) {
            long scheduleTime = 0;
            if (isVerbose()) {
                scheduleTime = SystemClock.elapsedRealtime();
                Log.i("GraphRunner", scheduleTime + ": Scheduling " + filter + "!");
            }
            filter.execute();
            if (isVerbose()) {
                long nowTime = SystemClock.elapsedRealtime();
                Log.i("GraphRunner",
                        "-> Schedule time (" + filter + ") = " + (nowTime - scheduleTime) + " ms.");
            }
        }

    }

    // GraphRunner.Scheduler classes ///////////////////////////////////////////////////////////////
    private interface Scheduler {
        public void prepare(Filter[] filters);

        public int getStrategy();

        public void beginStep();

        public long priorityForFilter(Filter filter);

    }

    private class LruScheduler implements Scheduler {

        private long mNow;

        @Override
        public void prepare(Filter[] filters) {
        }

        @Override
        public int getStrategy() {
            return STRATEGY_LRU;
        }

        @Override
        public void beginStep() {
            // TODO(renn): We could probably do this with a simple GraphRunner counter that would
            // represent GraphRunner local time. This would allow us to use integers instead of
            // longs, and save us calls to the system clock.
            mNow = SystemClock.elapsedRealtime();
        }

        @Override
        public long priorityForFilter(Filter filter) {
            if (filter.isSleeping()) {
                return PRIORITY_SLEEP;
            } else if (filter.canSchedule()) {
                return mNow - filter.getLastScheduleTime();
            } else {
                return PRIORITY_STOP;
            }
        }

    }

    private class LfuScheduler implements Scheduler {

        private final int MAX_PRIORITY = Integer.MAX_VALUE;

        @Override
        public void prepare(Filter[] filters) {
            // [Non-iterator looping]
            for (int i = 0; i < filters.length; ++i) {
                filters[i].resetScheduleCount();
            }
        }

        @Override
        public int getStrategy() {
            return STRATEGY_LFU;
        }

        @Override
        public void beginStep() {
        }

        @Override
        public long priorityForFilter(Filter filter) {
            return filter.isSleeping() ? PRIORITY_SLEEP
                    : (filter.canSchedule() ? (MAX_PRIORITY - filter.getScheduleCount())
                            : PRIORITY_STOP);
        }

    }

    private class OneShotScheduler extends LfuScheduler {
        private int mCurCount = 1;

        @Override
        public void prepare(Filter[] filters) {
            // [Non-iterator looping]
            for (int i = 0; i < filters.length; ++i) {
                filters[i].resetScheduleCount();
            }
        }

        @Override
        public int getStrategy() {
            return STRATEGY_ONESHOT;
        }

        @Override
        public void beginStep() {
        }

        @Override
        public long priorityForFilter(Filter filter) {
            return filter.getScheduleCount() < mCurCount ? super.priorityForFilter(filter)
                    : PRIORITY_STOP;
        }

    }

    // GraphRunner.Listener callback class /////////////////////////////////////////////////////////
    public interface Listener {
        /**
         * Callback method that is called when the runner completes a run. This method is called
         * only if the graph completed without an error.
         */
        public void onGraphRunnerStopped(GraphRunner runner);

        /**
         * Callback method that is called when runner encounters an error.
         *
         *  Any exceptions thrown in the GraphRunner's thread will cause the run to abort. The
         * thrown exception is passed to the listener in this method. If no listener is set, the
         * exception message is logged to the error stream. You will not receive an
         * {@link #onGraphRunnerStopped(GraphRunner)} callback in case of an error.
         *
         * @param exception the exception that was thrown.
         * @param closedSuccessfully true, if the graph was closed successfully after the error.
         */
        public void onGraphRunnerError(Exception exception, boolean closedSuccessfully);
    }

    public interface SubListener {
        public void onSubGraphRunEnded(GraphRunner runner);
    }

    /**
     * Config class to setup a GraphRunner with a custom configuration.
     *
     * The configuration object is passed to the constructor. Any changes to it will not affect
     * the created GraphRunner instance.
     */
    public static class Config {
        /** The runner's thread priority. */
        public int threadPriority = Thread.NORM_PRIORITY;
        /** Whether to allow filters to use OpenGL or not. */
        public boolean allowOpenGL = true;
    }

    /** Parameters shared between run-thread and GraphRunner frontend. */
    private class RunParameters {
        public Listener listener = null;
        public boolean isVerbose = false;
        public boolean flushOnClose = true;
    }

    // GraphRunner implementation //////////////////////////////////////////////////////////////////
    /** Schedule strategy: From set of candidates, pick a random one. */
    public static final int STRATEGY_RANDOM = 1;
    /** Schedule strategy: From set of candidates, pick node executed least recently executed. */
    public static final int STRATEGY_LRU = 2;
    /** Schedule strategy: From set of candidates, pick node executed least number of times. */
    public static final int STRATEGY_LFU = 3;
    /** Schedule strategy: Schedules no node more than once. */
    public static final int STRATEGY_ONESHOT = 4;

    private final MffContext mContext;

    private FilterGraph mRunningGraph = null;
    private Set<FilterGraph> mGraphs = new HashSet<FilterGraph>();

    private Scheduler mScheduler;

    private GraphRunLoop mRunLoop;

    private Thread mRunThread = null;

    private FrameManager mFrameManager = null;

    private static ThreadLocal<GraphRunner> mThreadRunner = new ThreadLocal<GraphRunner>();

    private RunParameters mParams = new RunParameters();

    /**
     * Creates a new GraphRunner with the default configuration. You must attach FilterGraph
     * instances to this runner before you can execute any of these graphs.
     *
     * @param context The MffContext instance for this runner.
     */
    public GraphRunner(MffContext context) {
        mContext = context;
        init(new Config());
    }

    /**
     * Creates a new GraphRunner with the specified configuration. You must attach FilterGraph
     * instances to this runner before you can execute any of these graphs.
     *
     * @param context The MffContext instance for this runner.
     * @param config A Config instance with the configuration of this runner.
     */
    public GraphRunner(MffContext context, Config config) {
        mContext = context;
        init(config);
    }

    /**
     * Returns the currently running graph-runner.
     * @return The currently running graph-runner.
     */
    public static GraphRunner current() {
        return mThreadRunner.get();
    }

    /**
     * Returns the graph that this runner is currently executing. Returns null if no graph is
     * currently being executed by this runner.
     *
     * @return the FilterGraph instance that this GraphRunner is executing.
     */
    public synchronized FilterGraph getRunningGraph() {
        return mRunningGraph;
    }

    /**
     * Returns the context that this runner is bound to.
     *
     * @return the MffContext instance that this runner is bound to.
     */
    public MffContext getContext() {
        return mContext;
    }

    /**
     * Begins graph execution. The graph filters are scheduled and executed until processing
     * finishes or is stopped.
     */
    public synchronized void start(FilterGraph graph) {
        if (graph.mRunner != this) {
            throw new IllegalArgumentException("Graph must be attached to runner!");
        }
        mRunningGraph = graph;
        mRunLoop.getStopCondition().close();
        mRunLoop.pushEvent(Event.PREPARE, graph);
    }

    /**
     * Begin executing a sub-graph. This only succeeds if the current runner is already
     * executing.
     */
    public void enterSubGraph(FilterGraph graph, SubListener listener) {
        if (Thread.currentThread() != mRunThread) {
            throw new RuntimeException("enterSubGraph must be called from the runner's thread!");
        }
        mRunLoop.enterSubGraph(graph, listener);
    }

    /**
     * Waits until graph execution has finished or stopped with an error.
     * Care must be taken when using this method to not block the UI thread. This is typically
     * used when a graph is run in one-shot mode to compute a result.
     */
    public void waitUntilStop() {
        mRunLoop.getStopCondition().block();
    }

    /**
     * Pauses graph execution.
     */
    public void pause() {
        mRunLoop.pushEvent(PAUSE_EVENT);
    }

    /**
     * Resumes graph execution after pausing.
     */
    public void resume() {
        mRunLoop.pushEvent(RESUME_EVENT);
    }

    /**
     * Stops graph execution.
     */
    public void stop() {
        mRunLoop.pushEvent(STOP_EVENT);
    }

    /**
     * Returns whether the graph is currently being executed. A graph is considered to be running,
     * even if it is paused or in the process of being stopped.
     *
     * @return true, if the graph is currently being executed.
     */
    public boolean isRunning() {
        return !mRunLoop.checkState(State.STOPPED);
    }

    /**
     * Returns whether the graph is currently paused.
     *
     * @return true, if the graph is currently paused.
     */
    public boolean isPaused() {
        return mRunLoop.checkState(State.PAUSED);
    }

    /**
     * Returns whether the graph is currently stopped.
     *
     * @return true, if the graph is currently stopped.
     */
    public boolean isStopped() {
        return mRunLoop.checkState(State.STOPPED);
    }

    /**
     * Sets the filter scheduling strategy. This method can not be called when the GraphRunner is
     * running.
     *
     * @param strategy a constant specifying which scheduler strategy to use.
     * @throws RuntimeException if the GraphRunner is running.
     * @throws IllegalArgumentException if invalid strategy is specified.
     * @see #getSchedulerStrategy()
     */
    public void setSchedulerStrategy(int strategy) {
        if (isRunning()) {
            throw new RuntimeException(
                    "Attempting to change scheduling strategy on running " + "GraphRunner!");
        }
        createScheduler(strategy);
    }

    /**
     * Returns the current scheduling strategy.
     *
     * @return the scheduling strategy used by this GraphRunner.
     * @see #setSchedulerStrategy(int)
     */
    public int getSchedulerStrategy() {
        return mScheduler.getStrategy();
    }

    /**
     * Set whether or not the runner is verbose. When set to true, the runner will output individual
     * scheduling steps that may help identify and debug problems in the graph structure. The
     * default is false.
     *
     * @param isVerbose true, if the GraphRunner should log scheduling details.
     * @see #isVerbose()
     */
    public void setIsVerbose(boolean isVerbose) {
        synchronized (mParams) {
            mParams.isVerbose = isVerbose;
        }
    }

    /**
     * Returns whether the GraphRunner is verbose.
     *
     * @return true, if the GraphRunner logs scheduling details.
     * @see #setIsVerbose(boolean)
     */
    public boolean isVerbose() {
        synchronized (mParams) {
            return mParams.isVerbose;
        }
    }

    /**
     * Returns whether Filters of this GraphRunner can use OpenGL.
     *
     * Filters may use OpenGL if the MffContext supports OpenGL and the GraphRunner allows it.
     *
     * @return true, if Filters are allowed to use OpenGL.
     */
    public boolean isOpenGLSupported() {
        return mRunLoop.isOpenGLAllowed() && mContext.isOpenGLSupported();
    }

    /**
     * Enable flushing all frames from the graph when running completes.
     *
     * If this is set to false, then frames may remain in the pipeline even after running completes.
     * The default value is true.
     *
     * @param flush true, if the GraphRunner should flush the graph when running completes.
     * @see #flushOnClose()
     */
    public void setFlushOnClose(boolean flush) {
        synchronized (mParams) {
            mParams.flushOnClose = flush;
        }
    }

    /**
     * Returns whether the GraphRunner flushes frames when running completes.
     *
     * @return true, if the GraphRunner flushes frames when running completes.
     * @see #setFlushOnClose(boolean)
     */
    public boolean flushOnClose() {
        synchronized (mParams) {
            return mParams.flushOnClose;
        }
    }

    /**
     * Sets the listener for receiving runtime events. A GraphRunner.Listener instance can be used
     * to determine when certain events occur during graph execution (and react on them). See the
     * {@link GraphRunner.Listener} class for details.
     *
     * @param listener the GraphRunner.Listener instance to set.
     * @see #getListener()
     */
    public void setListener(Listener listener) {
        synchronized (mParams) {
            mParams.listener = listener;
        }
    }

    /**
     * Returns the currently assigned GraphRunner.Listener.
     *
     * @return the currently assigned GraphRunner.Listener instance.
     * @see #setListener(Listener)
     */
    public Listener getListener() {
        synchronized (mParams) {
            return mParams.listener;
        }
    }

    /**
     * Returns the FrameManager that manages the runner's frames.
     *
     * @return the FrameManager instance that manages the runner's frames.
     */
    public FrameManager getFrameManager() {
        return mFrameManager;
    }

    /**
     * Tear down a GraphRunner and all its resources.
     * <p>
     * You must make sure that before calling this, no more graphs are attached to this runner.
     * Typically, graphs are removed from runners when they are torn down.
     *
     * @throws IllegalStateException if there are still graphs attached to this runner.
     */
    public void tearDown() {
        synchronized (mGraphs) {
            if (!mGraphs.isEmpty()) {
                throw new IllegalStateException("Attempting to tear down runner with "
                        + mGraphs.size() + " graphs still attached!");
            }
        }
        mRunLoop.pushEvent(KILL_EVENT);
        // Wait for thread to complete, so that everything is torn down by the time we return.
        try {
            mRunThread.join();
        } catch (InterruptedException e) {
            Log.e("GraphRunner", "Error waiting for runner thread to finish!");
        }
    }

    /**
     * Release all frames managed by this runner.
     * <p>
     * Note, that you must make sure no graphs are attached to this runner before calling this
     * method, as otherwise Filters in the graph may reference frames that are now released.
     *
     * TODO: Eventually, this method should be removed. Instead we should have better analysis
     * that catches leaking frames from filters.
     *
     * @throws IllegalStateException if there are still graphs attached to this runner.
     */
    public void releaseFrames() {
        synchronized (mGraphs) {
            if (!mGraphs.isEmpty()) {
                throw new IllegalStateException("Attempting to release frames with "
                        + mGraphs.size() + " graphs still attached!");
            }
        }
        mRunLoop.pushEvent(RELEASE_FRAMES_EVENT);
    }

    // Core internal methods ///////////////////////////////////////////////////////////////////////
    void attachGraph(FilterGraph graph) {
        synchronized (mGraphs) {
            mGraphs.add(graph);
        }
    }

    void signalWakeUp() {
        mRunLoop.pushWakeEvent(STEP_EVENT);
    }

    void begin() {
        mRunLoop.pushEvent(BEGIN_EVENT);
    }

    /** Like pause(), but closes all filters. Can be resumed using restart(). */
    void halt() {
        mRunLoop.pushEvent(HALT_EVENT);
    }

    /** Resumes a previously halted runner, and restores it to its non-halted state. */
    void restart() {
        mRunLoop.pushEvent(RESTART_EVENT);
    }

    /**
     * Tears down the specified graph.
     *
     * The graph must be attached to this runner.
     */
    void tearDownGraph(FilterGraph graph) {
        if (graph.getRunner() != this) {
            throw new IllegalArgumentException("Attempting to tear down graph with foreign "
                    + "GraphRunner!");
        }
        mRunLoop.pushEvent(Event.TEARDOWN, graph);
        synchronized (mGraphs) {
            mGraphs.remove(graph);
        }
    }

    /**
     * Remove all frames that are waiting to be processed.
     *
     * Removes and releases frames that are waiting in the graph connections of the currently
     * halted graphs, i.e. frames that are waiting to be processed. This does not include frames
     * that may be held or cached by filters themselves.
     *
     * TODO: With the new sub-graph architecture, this can now be simplified and made public.
     * It can then no longer rely on opened graphs, and instead flush a graph and all its
     * sub-graphs.
     */
    void flushFrames() {
        mRunLoop.pushEvent(FLUSH_EVENT);
    }

    // Private methods /////////////////////////////////////////////////////////////////////////////
    private void init(Config config) {
        mFrameManager = new FrameManager(this, FrameManager.FRAME_CACHE_LRU);
        createScheduler(STRATEGY_LRU);
        mRunLoop = new GraphRunLoop(config.allowOpenGL);
        mRunThread = new Thread(mRunLoop);
        mRunThread.setPriority(config.threadPriority);
        mRunThread.start();
        mContext.addRunner(this);
    }

    private void createScheduler(int strategy) {
        switch (strategy) {
            case STRATEGY_LRU:
                mScheduler = new LruScheduler();
                break;
            case STRATEGY_LFU:
                mScheduler = new LfuScheduler();
                break;
            case STRATEGY_ONESHOT:
                mScheduler = new OneShotScheduler();
                break;
            default:
                throw new IllegalArgumentException(
                        "Unknown schedule-strategy constant " + strategy + "!");
        }
    }

    // Called within the runner's thread
    private void onRunnerStopped(final Exception exception, final boolean closed) {
        mRunningGraph = null;
        synchronized (mParams) {
            if (mParams.listener != null) {
                getContext().postRunnable(new Runnable() {
                    @Override
                    public void run() {
                        if (exception == null) {
                            mParams.listener.onGraphRunnerStopped(GraphRunner.this);
                        } else {
                            mParams.listener.onGraphRunnerError(exception, closed);
                        }
                    }
                });
            } else if (exception != null) {
                Log.e("GraphRunner",
                        "Uncaught exception during graph execution! Stack Trace: ");
                exception.printStackTrace();
            }
        }
    }
}
