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

/**
 * @hide
 */
public abstract class GraphRunner {

    protected FilterContext mFilterContext = null;

    /** Interface for listeners waiting for the runner to complete. */
    public interface OnRunnerDoneListener {
        /** Callback method to be called when the runner completes a
         * {@link #run()} call.
         *
         * @param result will be RESULT_FINISHED if the graph finished running
         *        on its own, RESULT_STOPPED if the runner was stopped by a call
         *        to stop(), RESULT_BLOCKED if no filters could run due to lack
         *        of inputs or outputs or due to scheduling policies, and
         *        RESULT_SLEEPING if a filter node requested sleep.
         */
        public void onRunnerDone(int result);
    }

    public static final int RESULT_UNKNOWN  = 0;
    public static final int RESULT_RUNNING  = 1;
    public static final int RESULT_FINISHED = 2;
    public static final int RESULT_SLEEPING = 3;
    public static final int RESULT_BLOCKED  = 4;
    public static final int RESULT_STOPPED  = 5;
    public static final int RESULT_ERROR    = 6;

    public GraphRunner(FilterContext context) {
        mFilterContext = context;
    }

    public abstract FilterGraph getGraph();

    public FilterContext getContext() {
        return mFilterContext;
    }

    /**
     * Helper function for subclasses to activate the GL environment before running.
     * @return true, if the GL environment was activated. Returns false, if the GL environment
     *         was already active.
     */
    protected boolean activateGlContext() {
        GLEnvironment glEnv = mFilterContext.getGLEnvironment();
        if (glEnv != null && !glEnv.isActive()) {
            glEnv.activate();
            return true;
        }
        return false;
    }

    /**
     * Helper function for subclasses to deactivate the GL environment after running.
     */
    protected void deactivateGlContext() {
        GLEnvironment glEnv = mFilterContext.getGLEnvironment();
        if (glEnv != null) {
            glEnv.deactivate();
        }
    }

    /** Starts running the graph. Will open the filters in the graph if they are not already open. */
    public abstract void run();

    public abstract void setDoneCallback(OnRunnerDoneListener listener);
    public abstract boolean isRunning();

    /** Stops graph execution. As part of stopping, also closes the graph nodes. */
    public abstract void stop();

    /** Closes the filters in a graph. Can only be called if the graph is not running. */
    public abstract void close();

    /**
     * Returns the last exception that happened during an asynchronous run. Returns null if
     * there is nothing to report.
     */
    public abstract Exception getError();
}
