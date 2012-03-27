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

import android.filterfw.core.Filter;
import android.filterfw.core.FilterFactory;
import android.filterfw.core.FilterFunction;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameManager;

/**
 * A FilterFunctionEnvironment provides a simple functional front-end to manually executing
 * filters. Use this environment if a graph-based approach is not convenient for your case.
 * Typically, a FilterFunctionEnvironment is used as follows:
 *   1. Instantiate a new FilterFunctionEnvironment instance.
 *   2. Perform any configuration, such as setting a GL environment.
 *   3. Wrap Filters into FilterFunctions by calling createFunction().
 *   4. Execute FilterFunctions individually and use the results for further processing.
 * Additionally, there is a convenience method to execute a number of filters in sequence.
 * @hide
 */
public class FilterFunctionEnvironment extends MffEnvironment {

    /**
     * Create a new FilterFunctionEnvironment with the default components.
     */
    public FilterFunctionEnvironment() {
        super(null);
    }

    /**
     * Create a new FilterFunctionEnvironment with a custom FrameManager. Pass null to auto-create
     * a FrameManager.
     *
     * @param frameManager The FrameManager to use, or null to auto-create one.
     */
    public FilterFunctionEnvironment(FrameManager frameManager) {
        super(frameManager);
    }

    /**
     * Create a new FilterFunction from a specific filter class. The function is initialized with
     * the given key-value list of parameters. Note, that this function uses the default shared
     * FilterFactory to create the filter instance.
     *
     * @param filterClass   The class of the filter to wrap. This must be a Filter subclass.
     * @param parameters    An argument list of alternating key-value filter parameters.
     * @return             A new FilterFunction instance.
     */
    public FilterFunction createFunction(Class filterClass, Object... parameters) {
        String filterName = "FilterFunction(" + filterClass.getSimpleName() + ")";
        Filter filter = FilterFactory.sharedFactory().createFilterByClass(filterClass, filterName);
        filter.initWithAssignmentList(parameters);
        return new FilterFunction(getContext(), filter);
    }

    /**
     * Convenience method to execute a sequence of filter functions. Note that every function in
     * the list MUST have one input and one output port, except the first filter (which must not
     * have any input ports) and the last filter (which may not have any output ports).
     *
     * @param functions A list of filter functions. The first filter must be a source filter.
     * @return         The result of the last filter executed, or null if the last filter did not
                        produce any output.
     *
    public Frame executeSequence(FilterFunction[] functions) {
        Frame oldFrame = null;
        Frame newFrame = null;
        for (FilterFunction filterFunction : functions) {
            if (oldFrame == null) {
                newFrame = filterFunction.executeWithArgList();
            } else {
                newFrame = filterFunction.executeWithArgList(oldFrame);
                oldFrame.release();
            }
            oldFrame = newFrame;
        }
        if (oldFrame != null) {
            oldFrame.release();
        }
        return newFrame;
    }*/

}
