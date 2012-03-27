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


package android.filterfw.core;

import android.os.ConditionVariable;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @hide
 */
public class SyncRunner extends GraphRunner {

    private Scheduler mScheduler = null;

    private OnRunnerDoneListener mDoneListener = null;
    private ScheduledThreadPoolExecutor mWakeExecutor = new ScheduledThreadPoolExecutor(1);
    private ConditionVariable mWakeCondition = new ConditionVariable();

    private StopWatchMap mTimer = null;

    private final boolean mLogVerbose;
    private final static String TAG = "SyncRunner";

    // TODO: Provide factory based constructor?
    public SyncRunner(FilterContext context, FilterGraph graph, Class schedulerClass) {
        super(context);

        mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

        if (mLogVerbose) Log.v(TAG, "Initializing SyncRunner");

        // Create the scheduler
        if (Scheduler.class.isAssignableFrom(schedulerClass)) {
            try {
                Constructor schedulerConstructor = schedulerClass.getConstructor(FilterGraph.class);
                mScheduler = (Scheduler)schedulerConstructor.newInstance(graph);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Scheduler does not have constructor <init>(FilterGraph)!", e);
            } catch (InstantiationException e) {
                throw new RuntimeException("Could not instantiate the Scheduler instance!", e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access Scheduler constructor!", e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException("Scheduler constructor threw an exception", e);
            } catch (Exception e) {
                throw new RuntimeException("Could not instantiate Scheduler", e);
            }
        } else {
            throw new IllegalArgumentException("Class provided is not a Scheduler subclass!");
        }

        // Associate this runner and the graph with the context
        mFilterContext = context;
        mFilterContext.addGraph(graph);

        mTimer = new StopWatchMap();

        if (mLogVerbose) Log.v(TAG, "Setting up filters");

        // Setup graph filters
        graph.setupFilters();
    }

    @Override
    public FilterGraph getGraph() {
        return mScheduler != null ? mScheduler.getGraph() : null;
    }

    public int step() {
        assertReadyToStep();
        if (!getGraph().isReady() ) {
            throw new RuntimeException("Trying to process graph that is not open!");
        }
        return performStep() ? RESULT_RUNNING : determinePostRunState();
    }

    public void beginProcessing() {
        mScheduler.reset();
        getGraph().beginProcessing();
    }

    public void close() {
        // Close filters
        if (mLogVerbose) Log.v(TAG, "Closing graph.");
        getGraph().closeFilters(mFilterContext);
        mScheduler.reset();
    }

    @Override
    public void run() {
        if (mLogVerbose) Log.v(TAG, "Beginning run.");

        assertReadyToStep();

        // Preparation
        beginProcessing();
        boolean glActivated = activateGlContext();

        // Run
        boolean keepRunning = true;
        while (keepRunning) {
            keepRunning = performStep();
        }

        // Cleanup
        if (glActivated) {
            deactivateGlContext();
        }

        // Call completion callback if set
        if (mDoneListener != null) {
            if (mLogVerbose) Log.v(TAG, "Calling completion listener.");
            mDoneListener.onRunnerDone(determinePostRunState());
        }
        if (mLogVerbose) Log.v(TAG, "Run complete");
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public void setDoneCallback(OnRunnerDoneListener listener) {
        mDoneListener = listener;
    }

    @Override
    public void stop() {
        throw new RuntimeException("SyncRunner does not support stopping a graph!");
    }

    @Override
    synchronized public Exception getError() {
        return null;
    }

    protected void waitUntilWake() {
        mWakeCondition.block();
    }

    protected void processFilterNode(Filter filter) {
        if (mLogVerbose) Log.v(TAG, "Processing filter node");
        filter.performProcess(mFilterContext);
        if (filter.getStatus() == Filter.STATUS_ERROR) {
            throw new RuntimeException("There was an error executing " + filter + "!");
        } else if (filter.getStatus() == Filter.STATUS_SLEEPING) {
            if (mLogVerbose) Log.v(TAG, "Scheduling filter wakeup");
            scheduleFilterWake(filter, filter.getSleepDelay());
        }
    }

    protected void scheduleFilterWake(Filter filter, int delay) {
        // Close the wake condition
        mWakeCondition.close();

        // Schedule the wake-up
        final Filter filterToSchedule = filter;
        final ConditionVariable conditionToWake = mWakeCondition;

        mWakeExecutor.schedule(new Runnable() {
          @Override
          public void run() {
                filterToSchedule.unsetStatus(Filter.STATUS_SLEEPING);
                conditionToWake.open();
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    protected int determinePostRunState() {
        boolean isBlocked = false;
        for (Filter filter : mScheduler.getGraph().getFilters()) {
            if (filter.isOpen()) {
                if (filter.getStatus() == Filter.STATUS_SLEEPING) {
                    // If ANY node is sleeping, we return our state as sleeping
                    return RESULT_SLEEPING;
                } else {
                    // If a node is still open, it is blocked (by input or output)
                    return RESULT_BLOCKED;
                }
            }
        }
        return RESULT_FINISHED;
    }

    // Core internal methods ///////////////////////////////////////////////////////////////////////
    boolean performStep() {
        if (mLogVerbose) Log.v(TAG, "Performing one step.");
        Filter filter = mScheduler.scheduleNextNode();
        if (filter != null) {
            mTimer.start(filter.getName());
            processFilterNode(filter);
            mTimer.stop(filter.getName());
            return true;
        } else {
            return false;
        }
    }

    void assertReadyToStep() {
        if (mScheduler == null) {
            throw new RuntimeException("Attempting to run schedule with no scheduler in place!");
        } else if (getGraph() == null) {
            throw new RuntimeException("Calling step on scheduler with no graph in place!");
        }
    }
}
