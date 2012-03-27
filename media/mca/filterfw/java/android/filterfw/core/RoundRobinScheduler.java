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

import java.util.Set;

import android.filterfw.core.Filter;
import android.filterfw.core.Scheduler;

/**
 * @hide
 */
public class RoundRobinScheduler extends Scheduler {

    private int mLastPos = -1;

    public RoundRobinScheduler(FilterGraph graph) {
        super(graph);
    }

    @Override
    public void reset() {
        mLastPos = -1;
    }

    @Override
    public Filter scheduleNextNode() {
        Set<Filter> all_filters = getGraph().getFilters();
        if (mLastPos >= all_filters.size()) mLastPos = -1;
        int pos = 0;
        Filter first = null;
        int firstNdx = -1;
        for (Filter filter : all_filters) {
            if (filter.canProcess()) {
                if (pos <= mLastPos) {
                    if (first == null) {
                        // store the first available filter
                        first = filter;
                        firstNdx = pos;
                    }
                } else {
                    // return the next available filter since last
                    mLastPos = pos;
                    return filter;
                }
            }
            pos ++;
        }
        // going around from the beginning
        if (first != null ) {
            mLastPos = firstNdx;
            return first;
        }
        // if there is nothing to be scheduled, still keep the previous
        // position.
        return null;
    }
}
