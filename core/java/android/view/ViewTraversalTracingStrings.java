/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view;

import android.os.Trace;

/**
 * Keeps and caches strings used to trace {@link View} traversals.
 * <p>
 * This is done to avoid expensive computations of them every time, which can improve performance.
 */
class ViewTraversalTracingStrings {

    /** {@link Trace} tag used to mark {@link View#onMeasure(int, int)}. */
    public final String onMeasure;

    /** {@link Trace} tag used to mark {@link View#onLayout(boolean, int, int, int, int)}. */
    public final String onLayout;

    /** Caches the view simple name to avoid re-computations. */
    public final String classSimpleName;

    /** Prefix for request layout stacktraces output in logs. */
    public final String requestLayoutStacktracePrefix;

    /** {@link Trace} tag used to mark {@link View#onMeasure(int, int)} happening before layout. */
    public final String onMeasureBeforeLayout;

    /**
     * @param v {@link View} from where to get the class name.
     */
    ViewTraversalTracingStrings(View v) {
        String className = v.getClass().getSimpleName();
        classSimpleName = className;
        onMeasureBeforeLayout = getTraceName("onMeasureBeforeLayout", className, v);
        onMeasure = getTraceName("onMeasure", className, v);
        onLayout = getTraceName("onLayout", className, v);
        requestLayoutStacktracePrefix = "requestLayout " + className;
    }

    private String getTraceName(String sectionName, String className, View v) {
        StringBuilder out = new StringBuilder();
        out.append(sectionName);
        out.append(" ");
        out.append(className);
        v.appendId(out);
        return out.substring(0, Math.min(out.length(), Trace.MAX_SECTION_NAME_LEN));
    }
}
