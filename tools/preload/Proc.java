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
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.io.Serializable;

/**
 * A Dalvik process.
 */
class Proc implements Serializable {

    private static final long serialVersionUID = 0;

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
     * Returns true if this process comes from the zygote.
     */
    public boolean fromZygote() {
        return parent != null && parent.name.equals("zygote")
                && !name.equals("com.android.development");
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
