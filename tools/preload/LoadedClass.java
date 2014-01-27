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

import java.io.Serializable;
import java.util.*;

/**
 * A loaded class.
 */
class LoadedClass implements Serializable, Comparable<LoadedClass> {

    private static final long serialVersionUID = 0;

    /** Class name. */
    final String name;

    /** Load operations. */
    final List<Operation> loads = new ArrayList<Operation>();

    /** Static initialization operations. */
    final List<Operation> initializations = new ArrayList<Operation>();

    /** Memory usage gathered by loading only this class in its own VM. */
    MemoryUsage memoryUsage = MemoryUsage.NOT_AVAILABLE;

    /**
     * Whether or not this class was loaded in the system class loader.
     */
    final boolean systemClass;

    /** Whether or not this class will be preloaded. */
    boolean preloaded;

    /** Constructs a new class. */
    LoadedClass(String name, boolean systemClass) {
        this.name = name;
        this.systemClass = systemClass;
    }

    void measureMemoryUsage() {
        this.memoryUsage = MemoryUsage.forClass(name);
    }

    int mlt = -1;

    /** Median time to load this class. */
    int medianLoadTimeMicros() {
        if (mlt != -1) {
            return mlt;
        }

        return mlt = calculateMedian(loads);
    }

    int mit = -1;

    /** Median time to initialize this class. */
    int medianInitTimeMicros() {
        if (mit != -1) {
            return mit;
        }

        return mit = calculateMedian(initializations);
    }

    int medianTimeMicros() {
        return medianInitTimeMicros() + medianLoadTimeMicros();
    }

    /** Calculates the median duration for a list of operations. */
    private static int calculateMedian(List<Operation> operations) {
        int size = operations.size();
        if (size == 0) {
            return 0;
        }

        int[] times = new int[size];
        for (int i = 0; i < size; i++) {
            times[i] = operations.get(i).exclusiveTimeMicros();
        }

        Arrays.sort(times);
        int middle = size / 2;
        if (size % 2 == 1) {
            // Odd
            return times[middle];
        } else {
            // Even -- average the two.
            return (times[middle - 1] + times[middle]) / 2;
        }
    }

    /** Returns names of processes that loaded this class. */
    Set<String> processNames() {
        Set<String> names = new HashSet<String>();
        addProcessNames(loads, names);
        addProcessNames(initializations, names);
        return names;
    }

    private void addProcessNames(List<Operation> ops, Set<String> names) {
        for (Operation operation : ops) {
            if (operation.process.fromZygote()) {
                names.add(operation.process.name);
            }
        }
    }

    public int compareTo(LoadedClass o) {
        return name.compareTo(o.name);
    }

    @Override
    public String toString() {
        return name;
    }
}
