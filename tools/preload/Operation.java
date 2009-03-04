/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.util.List;
import java.util.ArrayList;
import java.io.Serializable;

/**
 * An operation with a duration. Could represent a class load or initialization.
 */
class Operation implements Serializable {

    private static final long serialVersionUID = 0;
    
    /**
     * Type of operation.
     */
    enum Type {
        LOAD, INIT
    }

    /** Process this operation occurred in. */
    final Proc process;

    /** Start time for this operation. */
    final long startTimeNanos;

    /** Index of this operation relative to its process. */
    final int index;

    /** Type of operation. */
    final Type type;

    /** End time for this operation. */
    long endTimeNanos = -1;

    /** The class that this operation loaded or initialized. */
    final LoadedClass loadedClass;

    /** Other operations that occurred during this one. */
    final List<Operation> subops = new ArrayList<Operation>();

    /** Constructs a new operation. */
    Operation(Proc process, LoadedClass loadedClass, long startTimeNanos,
            int index, Type type) {
        this.process = process;
        this.loadedClass = loadedClass;
        this.startTimeNanos = startTimeNanos;
        this.index = index;
        this.type = type;
    }

    /**
     * Returns how long this class initialization and all the nested class
     * initializations took.
     */
    private long inclusiveTimeNanos() {
        if (endTimeNanos == -1) {
            throw new IllegalStateException("End time hasn't been set yet: "
                    + loadedClass.name);
        }

        return endTimeNanos - startTimeNanos;
    }

    /**
     * Returns how long this class initialization took.
     */
    int exclusiveTimeMicros() {
        long exclusive = inclusiveTimeNanos();

        for (Operation child : subops) {
            exclusive -= child.inclusiveTimeNanos();
        }

        if (exclusive < 0) {
            throw new AssertionError(loadedClass.name);
        }

        return nanosToMicros(exclusive);
    }

    /** Gets the median time that this operation took across all processes. */
    int medianExclusiveTimeMicros() {
        switch (type) {
            case LOAD: return loadedClass.medianLoadTimeMicros();
            case INIT: return loadedClass.medianInitTimeMicros();
            default: throw new AssertionError();
        }
    }

    /**
     * Converts nanoseconds to microseconds.
     *
     * @throws RuntimeException if overflow occurs
     */
    private static int nanosToMicros(long nanos) {
        long micros = nanos / 1000;
        int microsInt = (int) micros;
        if (microsInt != micros) {
            throw new RuntimeException("Integer overflow: " + nanos);
        }
        return microsInt;
    }
    
    /**
     * Primarily for debugger support
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(type.toString());
        sb.append(' ');
        sb.append(loadedClass.toString());
        if (subops.size() > 0) {
            sb.append(" (");
            sb.append(subops.size());
            sb.append(" sub ops)");
        }
        return sb.toString();
    }

}
