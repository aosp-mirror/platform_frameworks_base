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

import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.TreeSet;
import java.io.Serializable;

/**
 * A Dalvik process.
 */
class Proc implements Serializable {

    private static final long serialVersionUID = 0;

    /**
     * Default percentage of time to cut off of app class loading times.
     */
    static final int PERCENTAGE_TO_PRELOAD = 75;

    /**
     * Maximum number of classes to preload for a given process.
     */
    static final int MAX_TO_PRELOAD = 100;

    /** Parent process. */
    final Proc parent;

    /** Process ID. */
    final int id;

    /**
     * Name of this process. We may not have the correct name at first, i.e.
     * some classes could have been loaded before the process name was set.
     */
    String name;

    /** Child processes. */
    final List<Proc> children = new ArrayList<Proc>();

    /** Maps thread ID to operation stack. */
    transient final Map<Integer, LinkedList<Operation>> stacks
            = new HashMap<Integer, LinkedList<Operation>>();

    /** Number of operations. */
    int operationCount;

    /** Sequential list of operations that happened in this process. */
    final List<Operation> operations = new ArrayList<Operation>();

    /** List of past process names. */
    final List<String> nameHistory = new ArrayList<String>();

    /** Constructs a new process. */
    Proc(Proc parent, int id) {
        this.parent = parent;
        this.id = id;
    }

    /** Sets name of this process. */
    void setName(String name) {
        if (!name.equals(this.name)) {
            if (this.name != null) {
                nameHistory.add(this.name);
            }
            this.name = name;
        }
    }

    /**
     * Returns the percentage of time we should cut by preloading for this
     * app.
     */
    int percentageToPreload() {
        return PERCENTAGE_TO_PRELOAD;
    }

    /**
     * Returns a list of classes which should be preloaded.
     * 
     * @param takeAllClasses forces all classes to be taken (irrespective of ranking)
     */
    List<LoadedClass> highestRankedClasses(boolean takeAllClasses) {
        if (!isApplication()) {
            return Collections.emptyList();
        }

        // Sort by rank.
        Operation[] ranked = new Operation[operations.size()];
        ranked = operations.toArray(ranked);
        Arrays.sort(ranked, new ClassRank());

        // The percentage of time to save by preloading.
        int timeToSave = totalTimeMicros() * percentageToPreload() / 100;
        int timeSaved = 0;

        boolean service = Policy.isService(this.name);

        List<LoadedClass> highest = new ArrayList<LoadedClass>();
        for (Operation operation : ranked) {
            
            // These are actual ranking decisions, which can be overridden
            if (!takeAllClasses) {
                if (highest.size() >= MAX_TO_PRELOAD) {
                    System.out.println(name + " got " 
                            + (timeSaved * 100 / timeToSave) + "% through");
                    break;
                }
    
                if (timeSaved >= timeToSave) {
                    break;
                }
            }

            // The remaining rules apply even to wired-down processes
            if (!Policy.isPreloadableClass(operation.loadedClass.name)) {
                continue;
            }
            
            if (!operation.loadedClass.systemClass) {
                continue;
            }

            // Only load java.* class for services.
            if (!service || operation.loadedClass.name.startsWith("java.")) {
                highest.add(operation.loadedClass);
            }

            // For services, still count the time even if it's not in java.* 
            timeSaved += operation.medianExclusiveTimeMicros();
        }

        return highest;
    }

    /**
     * Total time spent class loading and initializing.
     */
    int totalTimeMicros() {
        int totalTime = 0;
        for (Operation operation : operations) {
            totalTime += operation.medianExclusiveTimeMicros();
        }
        return totalTime;
    }

    /** 
     * Returns true if this process is an app.
     *      
     * TODO: Replace the hardcoded list with a walk up the parent chain looking for zygote.
     */
    public boolean isApplication() {
        return Policy.isFromZygote(name);
    }

    /**
     * Starts an operation.
     *
     * @param threadId thread the operation started in
     * @param loadedClass class operation happened to
     * @param time the operation started
     */
    void startOperation(int threadId, LoadedClass loadedClass, long time,
            Operation.Type type) {
        Operation o = new Operation(
                this, loadedClass, time, operationCount++, type);
        operations.add(o);

        LinkedList<Operation> stack = stacks.get(threadId);
        if (stack == null) {
            stack = new LinkedList<Operation>();
            stacks.put(threadId, stack);
        }

        if (!stack.isEmpty()) {
            stack.getLast().subops.add(o);
        }

        stack.add(o);
    }

    /**
     * Ends an operation.
     *
     * @param threadId thread the operation ended in
     * @param loadedClass class operation happened to
     * @param time the operation ended
     */
    Operation endOperation(int threadId, String className,
            LoadedClass loadedClass, long time) {
        LinkedList<Operation> stack = stacks.get(threadId);

        if (stack == null || stack.isEmpty()) {
            didNotStart(className);
            return null;
        }

        Operation o = stack.getLast();
        if (loadedClass != o.loadedClass) {
            didNotStart(className);
            return null;
        }

        stack.removeLast();

        o.endTimeNanos = time;
        return o;
    }

    /**
     * Prints an error indicating that we saw the end of an operation but not
     * the start. A bug in the logging framework which results in dropped logs
     * causes this.
     */
    private static void didNotStart(String name) {
        System.err.println("Warning: An operation ended on " + name
            + " but it never started!");
    }

    /**
     * Prints this process tree to stdout.
     */
    void print() {
        print("");
    }

    /**
     * Prints a child proc to standard out.
     */
    private void print(String prefix) {
        System.out.println(prefix + "id=" + id + ", name=" + name);
        for (Proc child : children) {
            child.print(prefix + "    ");
        }
    }

    @Override
    public String toString() {
        return this.name;
    }
}
