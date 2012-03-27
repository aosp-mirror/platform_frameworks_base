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

import android.filterfw.core.Filter;
import android.filterfw.core.FilterGraph;

/**
 * @hide
 */
public abstract class Scheduler {
    // All methods are core internal methods as Scheduler internals are only used by the GraphRunner.

    private FilterGraph mGraph;

    Scheduler(FilterGraph graph) {
        mGraph = graph;
    }

    FilterGraph getGraph() {
        return mGraph;
    }

    abstract void reset();

    abstract Filter scheduleNextNode();

    boolean finished() {
        // TODO: Check that the state of all nodes is FINISHED.
        return true;
    }
}
