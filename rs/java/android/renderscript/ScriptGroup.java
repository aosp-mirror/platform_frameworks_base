/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.renderscript;

import android.util.Log;
import android.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A group of kernels that are executed
 * together with one execution call as if they were a single kernel
 * <p>
 * In addition to kernels, a script group may contain invocable functions as well.
 * A script group may take inputs and generate outputs, which are consumed and
 * produced by its member kernels.
 * Inside a script group, outputs from one kernel can be passed to another kernel as inputs.
 * The API disallows cyclic dependencies among kernels in a script group,
 * effectively making it a directed acyclic graph (DAG) of kernels.
 * <p>
 * Grouping kernels together allows for more efficient execution. For example,
 * runtime and compiler optimization can be applied to reduce computation and
 * communication overhead, and to make better use of the CPU and the GPU.
 **/
public final class ScriptGroup extends BaseObj {
    private static final String TAG = "ScriptGroup";
    IO mOutputs[];
    IO mInputs[];

    static class IO {
        Script.KernelID mKID;
        Allocation mAllocation;

        IO(Script.KernelID s) {
            mKID = s;
        }
    }

    static class ConnectLine {
        ConnectLine(Type t, Script.KernelID from, Script.KernelID to) {
            mFrom = from;
            mToK = to;
            mAllocationType = t;
        }

        ConnectLine(Type t, Script.KernelID from, Script.FieldID to) {
            mFrom = from;
            mToF = to;
            mAllocationType = t;
        }

        Script.FieldID mToF;
        Script.KernelID mToK;
        Script.KernelID mFrom;
        Type mAllocationType;
    }

    static class Node {
        Script mScript;
        ArrayList<Script.KernelID> mKernels = new ArrayList<Script.KernelID>();
        ArrayList<ConnectLine> mInputs = new ArrayList<ConnectLine>();
        ArrayList<ConnectLine> mOutputs = new ArrayList<ConnectLine>();
        int dagNumber;

        Node mNext;

        Node(Script s) {
            mScript = s;
        }
    }


    /**
     * An opaque class for closures
     * <p>
     * A closure represents a function call to a kernel or invocable function,
     * combined with arguments and values for global variables. A closure is
     * created using the {@link android.renderscript.ScriptGroup.Builder2#addKernel} or
     * {@link android.renderscript.ScriptGroup.Builder2#addInvoke}
     * method.
     */

    public static final class Closure extends BaseObj {
        private Object[] mArgs;
        private Allocation mReturnValue;
        private Map<Script.FieldID, Object> mBindings;

        private Future mReturnFuture;
        private Map<Script.FieldID, Future> mGlobalFuture;

        private FieldPacker mFP;

        private static final String TAG = "Closure";

        Closure(long id, RenderScript rs) {
            super(id, rs);
        }

        Closure(RenderScript rs, Script.KernelID kernelID, Type returnType,
                       Object[] args, Map<Script.FieldID, Object> globals) {
            super(0, rs);

            mArgs = args;
            mReturnValue = Allocation.createTyped(rs, returnType);
            mBindings = globals;
            mGlobalFuture = new HashMap<Script.FieldID, Future>();

            int numValues = args.length + globals.size();

            long[] fieldIDs = new long[numValues];
            long[] values = new long[numValues];
            int[] sizes = new int[numValues];
            long[] depClosures = new long[numValues];
            long[] depFieldIDs = new long[numValues];

            int i;
            for (i = 0; i < args.length; i++) {
                fieldIDs[i] = 0;
                retrieveValueAndDependenceInfo(rs, i, null, args[i],
                                               values, sizes, depClosures, depFieldIDs);
            }
            for (Map.Entry<Script.FieldID, Object> entry : globals.entrySet()) {
                Object obj = entry.getValue();
                Script.FieldID fieldID = entry.getKey();
                fieldIDs[i] = fieldID.getID(rs);
                retrieveValueAndDependenceInfo(rs, i, fieldID, obj,
                                               values, sizes, depClosures, depFieldIDs);
                i++;
            }

            long id = rs.nClosureCreate(kernelID.getID(rs), mReturnValue.getID(rs),
                                        fieldIDs, values, sizes, depClosures, depFieldIDs);

            setID(id);
        }

        Closure(RenderScript rs, Script.InvokeID invokeID,
                       Object[] args, Map<Script.FieldID, Object> globals) {
            super(0, rs);
            mFP = FieldPacker.createFromArray(args);

            mArgs = args;
            mBindings = globals;
            mGlobalFuture = new HashMap<Script.FieldID, Future>();

            int numValues = globals.size();

            long[] fieldIDs = new long[numValues];
            long[] values = new long[numValues];
            int[] sizes = new int[numValues];
            long[] depClosures = new long[numValues];
            long[] depFieldIDs = new long[numValues];

            int i = 0;
            for (Map.Entry<Script.FieldID, Object> entry : globals.entrySet()) {
                Object obj = entry.getValue();
                Script.FieldID fieldID = entry.getKey();
                fieldIDs[i] = fieldID.getID(rs);
                retrieveValueAndDependenceInfo(rs, i, fieldID, obj, values,
                                               sizes, depClosures, depFieldIDs);
                i++;
            }

            long id = rs.nInvokeClosureCreate(invokeID.getID(rs), mFP.getData(), fieldIDs,
                                              values, sizes);

            setID(id);
        }

        private void retrieveValueAndDependenceInfo(RenderScript rs,
                                                    int index, Script.FieldID fid, Object obj,
                                                    long[] values, int[] sizes,
                                                    long[] depClosures,
                                                    long[] depFieldIDs) {

            if (obj instanceof Future) {
                Future f = (Future)obj;
                obj = f.getValue();
                depClosures[index] = f.getClosure().getID(rs);
                Script.FieldID fieldID = f.getFieldID();
                depFieldIDs[index] = fieldID != null ? fieldID.getID(rs) : 0;
            } else {
                depClosures[index] = 0;
                depFieldIDs[index] = 0;
            }

            if (obj instanceof Input) {
                Input unbound = (Input)obj;
                if (index < mArgs.length) {
                    unbound.addReference(this, index);
                } else {
                    unbound.addReference(this, fid);
                }
                values[index] = 0;
                sizes[index] = 0;
            } else {
                ValueAndSize vs = new ValueAndSize(rs, obj);
                values[index] = vs.value;
                sizes[index] = vs.size;
            }
        }

        /**
         * Returns the future for the return value
         *
         * @return a future
         */

        public Future getReturn() {
            if (mReturnFuture == null) {
                mReturnFuture = new Future(this, null, mReturnValue);
            }

            return mReturnFuture;
        }

        /**
         * Returns the future for a global variable
         *
         * @param field the field ID for the global variable
         * @return a future
         */

        public Future getGlobal(Script.FieldID field) {
            Future f = mGlobalFuture.get(field);

            if (f == null) {
                // If the field is not bound to this closure, this will return a future
                // without an associated value (reference). So this is not working for
                // cross-module (cross-script) linking in this case where a field not
                // explicitly bound.
                Object obj = mBindings.get(field);
                if (obj instanceof Future) {
                    obj = ((Future)obj).getValue();
                }
                f = new Future(this, field, obj);
                mGlobalFuture.put(field, f);
            }

            return f;
        }

        void setArg(int index, Object obj) {
            if (obj instanceof Future) {
                obj = ((Future)obj).getValue();
            }
            mArgs[index] = obj;
            ValueAndSize vs = new ValueAndSize(mRS, obj);
            mRS.nClosureSetArg(getID(mRS), index, vs.value, vs.size);
        }

        void setGlobal(Script.FieldID fieldID, Object obj) {
            if (obj instanceof Future) {
                obj = ((Future)obj).getValue();
            }
            mBindings.put(fieldID, obj);
            ValueAndSize vs = new ValueAndSize(mRS, obj);
            mRS.nClosureSetGlobal(getID(mRS), fieldID.getID(mRS), vs.value, vs.size);
        }

        private static final class ValueAndSize {
            public ValueAndSize(RenderScript rs, Object obj) {
                if (obj instanceof Allocation) {
                    value = ((Allocation)obj).getID(rs);
                    size = -1;
                } else if (obj instanceof Boolean) {
                    value = ((Boolean)obj).booleanValue() ? 1 : 0;
                    size = 4;
                } else if (obj instanceof Integer) {
                    value = ((Integer)obj).longValue();
                    size = 4;
                } else if (obj instanceof Long) {
                    value = ((Long)obj).longValue();
                    size = 8;
                } else if (obj instanceof Float) {
                    value = ((Float)obj).longValue();
                    size = 4;
                } else if (obj instanceof Double) {
                    value = ((Double)obj).longValue();
                    size = 8;
                }
            }
            public long value;
            public int size;
        }
    }

    /**
     * An opaque class for futures
     * <p>
     * A future represents an output of a closure, either the return value of
     * the function, or the value of a global variable written by the function.
     * A future is created by calling the {@link Closure#getReturn}  or
     * {@link Closure#getGlobal} method.
     */

    public static final class Future {
        Closure mClosure;
        Script.FieldID mFieldID;
        Object mValue;

        Future(Closure closure, Script.FieldID fieldID, Object value) {
            mClosure = closure;
            mFieldID = fieldID;
            mValue = value;
        }

        Closure getClosure() { return mClosure; }
        Script.FieldID getFieldID() { return mFieldID; }
        Object getValue() { return mValue; }
    }

    /**
     * An opaque class for script group inputs
     * <p>
     * Created by calling the {@link Builder2#addInput} method. The value
     * is assigned in {@link ScriptGroup#execute(Object...)} method as
     * one of its arguments. Arguments to the execute method should be in
     * the same order as intputs are added using the addInput method.
     */

    public static final class Input {
        // Either mFieldID or mArgIndex should be set but not both.
        List<Pair<Closure, Script.FieldID>> mFieldID;
        // -1 means unset. Legal values are 0 .. n-1, where n is the number of
        // arguments for the referencing closure.
        List<Pair<Closure, Integer>> mArgIndex;
        Object mValue;

        Input() {
            mFieldID = new ArrayList<Pair<Closure, Script.FieldID>>();
            mArgIndex = new ArrayList<Pair<Closure, Integer>>();
        }

        void addReference(Closure closure, int index) {
            mArgIndex.add(Pair.create(closure, Integer.valueOf(index)));
        }

        void addReference(Closure closure, Script.FieldID fieldID) {
            mFieldID.add(Pair.create(closure, fieldID));
        }

        void set(Object value) {
            mValue = value;
            for (Pair<Closure, Integer> p : mArgIndex) {
                Closure closure = p.first;
                int index = p.second.intValue();
                closure.setArg(index, value);
            }
            for (Pair<Closure, Script.FieldID> p : mFieldID) {
                Closure closure = p.first;
                Script.FieldID fieldID = p.second;
                closure.setGlobal(fieldID, value);
            }
        }

        Object get() { return mValue; }
    }

    private String mName;
    private List<Closure> mClosures;
    private List<Input> mInputs2;
    private Future[] mOutputs2;

    ScriptGroup(long id, RenderScript rs) {
        super(id, rs);
    }

    ScriptGroup(RenderScript rs, String name, List<Closure> closures,
                 List<Input> inputs, Future[] outputs) {
        super(0, rs);
        mName = name;
        mClosures = closures;
        mInputs2 = inputs;
        mOutputs2 = outputs;

        long[] closureIDs = new long[closures.size()];
        for (int i = 0; i < closureIDs.length; i++) {
            closureIDs[i] = closures.get(i).getID(rs);
        }
        long id = rs.nScriptGroup2Create(name, ScriptC.mCachePath, closureIDs);
        setID(id);
    }

    /**
     * Executes a script group
     *
     * @param inputs Values for inputs to the script group, in the order as the
     *        inputs are added via {@link Builder2#addInput}.
     * @return Outputs of the script group as an array of objects, in the order
     *         as futures are passed to {@link Builder2#create}.
     */

    public Object[] execute(Object... inputs) {
        if (inputs.length < mInputs2.size()) {
            Log.e(TAG, this.toString() + " receives " + inputs.length + " inputs, " +
                  "less than expected " + mInputs2.size());
            return null;
        }

        if (inputs.length > mInputs2.size()) {
            Log.i(TAG, this.toString() + " receives " + inputs.length + " inputs, " +
                  "more than expected " + mInputs2.size());
        }

        for (int i = 0; i < mInputs2.size(); i++) {
            Object obj = inputs[i];
            if (obj instanceof Future || obj instanceof Input) {
                Log.e(TAG, this.toString() + ": input " + i +
                      " is a future or unbound value");
                return null;
            }
            Input unbound = mInputs2.get(i);
            unbound.set(obj);
        }

        mRS.nScriptGroup2Execute(getID(mRS));

        Object[] outputObjs = new Object[mOutputs2.length];
        int i = 0;
        for (Future f : mOutputs2) {
            Object output = f.getValue();
            if (output instanceof Input) {
                output = ((Input)output).get();
            }
            outputObjs[i++] = output;
        }
        return outputObjs;
    }

    /**
     * Sets an input of the ScriptGroup. This specifies an
     * Allocation to be used for kernels that require an input
     * Allocation provided from outside of the ScriptGroup.
     *
     * @deprecated Set arguments to {@link #execute(Object...)} instead.
     *
     * @param s The ID of the kernel where the allocation should be
     *          connected.
     * @param a The allocation to connect.
     */
    public void setInput(Script.KernelID s, Allocation a) {
        for (int ct=0; ct < mInputs.length; ct++) {
            if (mInputs[ct].mKID == s) {
                mInputs[ct].mAllocation = a;
                mRS.nScriptGroupSetInput(getID(mRS), s.getID(mRS), mRS.safeID(a));
                return;
            }
        }
        throw new RSIllegalArgumentException("Script not found");
    }

    /**
     * Sets an output of the ScriptGroup. This specifies an
     * Allocation to be used for the kernels that require an output
     * Allocation visible after the ScriptGroup is executed.
     *
     * @deprecated Use return value of {@link #execute(Object...)} instead.
     *
     * @param s The ID of the kernel where the allocation should be
     *          connected.
     * @param a The allocation to connect.
     */
    public void setOutput(Script.KernelID s, Allocation a) {
        for (int ct=0; ct < mOutputs.length; ct++) {
            if (mOutputs[ct].mKID == s) {
                mOutputs[ct].mAllocation = a;
                mRS.nScriptGroupSetOutput(getID(mRS), s.getID(mRS), mRS.safeID(a));
                return;
            }
        }
        throw new RSIllegalArgumentException("Script not found");
    }

    /**
     * Execute the ScriptGroup.  This will run all the kernels in
     * the ScriptGroup.  No internal connection results will be visible
     * after execution of the ScriptGroup.
     *
     * @deprecated Use {@link #execute} instead.
     *
     */
    public void execute() {
        mRS.nScriptGroupExecute(getID(mRS));
    }


    /**
     * Helper class to build a ScriptGroup. A ScriptGroup is
     * created in two steps.
     * <p>
     * First, all kernels to be used by the ScriptGroup should be added.
     * <p>
     * Second, add connections between kernels. There are two types
     * of connections: kernel to kernel and kernel to field.
     * Kernel to kernel allows a kernel's output to be passed to
     * another kernel as input. Kernel to field allows the output of
     * one kernel to be bound as a script global. Kernel to kernel is
     * higher performance and should be used where possible.
     * <p>
     * A ScriptGroup must contain a single directed acyclic graph (DAG); it
     * cannot contain cycles. Currently, all kernels used in a ScriptGroup
     * must come from different Script objects. Additionally, all kernels
     * in a ScriptGroup must have at least one input, output, or internal
     * connection.
     * <p>
     * Once all connections are made, a call to {@link #create} will
     * return the ScriptGroup object.
     *
     * @deprecated Use {@link Builder2} instead.
     *
     */
    public static final class Builder {
        private RenderScript mRS;
        private ArrayList<Node> mNodes = new ArrayList<Node>();
        private ArrayList<ConnectLine> mLines = new ArrayList<ConnectLine>();
        private int mKernelCount;

        /**
         * Create a Builder for generating a ScriptGroup.
         *
         *
         * @param rs The RenderScript context.
         */
        public Builder(RenderScript rs) {
            mRS = rs;
        }

        // do a DFS from original node, looking for original node
        // any cycle that could be created must contain original node
        private void validateCycle(Node target, Node original) {
            for (int ct = 0; ct < target.mOutputs.size(); ct++) {
                final ConnectLine cl = target.mOutputs.get(ct);
                if (cl.mToK != null) {
                    Node tn = findNode(cl.mToK.mScript);
                    if (tn.equals(original)) {
                        throw new RSInvalidStateException("Loops in group not allowed.");
                    }
                    validateCycle(tn, original);
                }
                if (cl.mToF != null) {
                    Node tn = findNode(cl.mToF.mScript);
                    if (tn.equals(original)) {
                        throw new RSInvalidStateException("Loops in group not allowed.");
                    }
                    validateCycle(tn, original);
                }
            }
        }

        private void mergeDAGs(int valueUsed, int valueKilled) {
            for (int ct=0; ct < mNodes.size(); ct++) {
                if (mNodes.get(ct).dagNumber == valueKilled)
                    mNodes.get(ct).dagNumber = valueUsed;
            }
        }

        private void validateDAGRecurse(Node n, int dagNumber) {
            // combine DAGs if this node has been seen already
            if (n.dagNumber != 0 && n.dagNumber != dagNumber) {
                mergeDAGs(n.dagNumber, dagNumber);
                return;
            }

            n.dagNumber = dagNumber;
            for (int ct=0; ct < n.mOutputs.size(); ct++) {
                final ConnectLine cl = n.mOutputs.get(ct);
                if (cl.mToK != null) {
                    Node tn = findNode(cl.mToK.mScript);
                    validateDAGRecurse(tn, dagNumber);
                }
                if (cl.mToF != null) {
                    Node tn = findNode(cl.mToF.mScript);
                    validateDAGRecurse(tn, dagNumber);
                }
            }
        }

        private void validateDAG() {
            for (int ct=0; ct < mNodes.size(); ct++) {
                Node n = mNodes.get(ct);
                if (n.mInputs.size() == 0) {
                    if (n.mOutputs.size() == 0 && mNodes.size() > 1) {
                        String msg = "Groups cannot contain unconnected scripts";
                        throw new RSInvalidStateException(msg);
                    }
                    validateDAGRecurse(n, ct+1);
                }
            }
            int dagNumber = mNodes.get(0).dagNumber;
            for (int ct=0; ct < mNodes.size(); ct++) {
                if (mNodes.get(ct).dagNumber != dagNumber) {
                    throw new RSInvalidStateException("Multiple DAGs in group not allowed.");
                }
            }
        }

        private Node findNode(Script s) {
            for (int ct=0; ct < mNodes.size(); ct++) {
                if (s == mNodes.get(ct).mScript) {
                    return mNodes.get(ct);
                }
            }
            return null;
        }

        private Node findNode(Script.KernelID k) {
            for (int ct=0; ct < mNodes.size(); ct++) {
                Node n = mNodes.get(ct);
                for (int ct2=0; ct2 < n.mKernels.size(); ct2++) {
                    if (k == n.mKernels.get(ct2)) {
                        return n;
                    }
                }
            }
            return null;
        }

        /**
         * Adds a Kernel to the group.
         *
         *
         * @param k The kernel to add.
         *
         * @return Builder Returns this.
         */
        public Builder addKernel(Script.KernelID k) {
            if (mLines.size() != 0) {
                throw new RSInvalidStateException(
                    "Kernels may not be added once connections exist.");
            }

            //android.util.Log.v("RSR", "addKernel 1 k=" + k);
            if (findNode(k) != null) {
                return this;
            }
            //android.util.Log.v("RSR", "addKernel 2 ");
            mKernelCount++;
            Node n = findNode(k.mScript);
            if (n == null) {
                //android.util.Log.v("RSR", "addKernel 3 ");
                n = new Node(k.mScript);
                mNodes.add(n);
            }
            n.mKernels.add(k);
            return this;
        }

        /**
         * Adds a connection to the group.
         *
         *
         * @param t The type of the connection. This is used to
         *          determine the kernel launch sizes on the source side
         *          of this connection.
         * @param from The source for the connection.
         * @param to The destination of the connection.
         *
         * @return Builder Returns this
         */
        public Builder addConnection(Type t, Script.KernelID from, Script.FieldID to) {
            //android.util.Log.v("RSR", "addConnection " + t +", " + from + ", " + to);

            Node nf = findNode(from);
            if (nf == null) {
                throw new RSInvalidStateException("From script not found.");
            }

            Node nt = findNode(to.mScript);
            if (nt == null) {
                throw new RSInvalidStateException("To script not found.");
            }

            ConnectLine cl = new ConnectLine(t, from, to);
            mLines.add(new ConnectLine(t, from, to));

            nf.mOutputs.add(cl);
            nt.mInputs.add(cl);

            validateCycle(nf, nf);
            return this;
        }

        /**
         * Adds a connection to the group.
         *
         *
         * @param t The type of the connection. This is used to
         *          determine the kernel launch sizes for both sides of
         *          this connection.
         * @param from The source for the connection.
         * @param to The destination of the connection.
         *
         * @return Builder Returns this
         */
        public Builder addConnection(Type t, Script.KernelID from, Script.KernelID to) {
            //android.util.Log.v("RSR", "addConnection " + t +", " + from + ", " + to);

            Node nf = findNode(from);
            if (nf == null) {
                throw new RSInvalidStateException("From script not found.");
            }

            Node nt = findNode(to);
            if (nt == null) {
                throw new RSInvalidStateException("To script not found.");
            }

            ConnectLine cl = new ConnectLine(t, from, to);
            mLines.add(new ConnectLine(t, from, to));

            nf.mOutputs.add(cl);
            nt.mInputs.add(cl);

            validateCycle(nf, nf);
            return this;
        }



        /**
         * Creates the Script group.
         *
         *
         * @return ScriptGroup The new ScriptGroup
         */
        public ScriptGroup create() {

            if (mNodes.size() == 0) {
                throw new RSInvalidStateException("Empty script groups are not allowed");
            }

            // reset DAG numbers in case we're building a second group
            for (int ct=0; ct < mNodes.size(); ct++) {
                mNodes.get(ct).dagNumber = 0;
            }
            validateDAG();

            ArrayList<IO> inputs = new ArrayList<IO>();
            ArrayList<IO> outputs = new ArrayList<IO>();

            long[] kernels = new long[mKernelCount];
            int idx = 0;
            for (int ct=0; ct < mNodes.size(); ct++) {
                Node n = mNodes.get(ct);
                for (int ct2=0; ct2 < n.mKernels.size(); ct2++) {
                    final Script.KernelID kid = n.mKernels.get(ct2);
                    kernels[idx++] = kid.getID(mRS);

                    boolean hasInput = false;
                    boolean hasOutput = false;
                    for (int ct3=0; ct3 < n.mInputs.size(); ct3++) {
                        if (n.mInputs.get(ct3).mToK == kid) {
                            hasInput = true;
                        }
                    }
                    for (int ct3=0; ct3 < n.mOutputs.size(); ct3++) {
                        if (n.mOutputs.get(ct3).mFrom == kid) {
                            hasOutput = true;
                        }
                    }
                    if (!hasInput) {
                        inputs.add(new IO(kid));
                    }
                    if (!hasOutput) {
                        outputs.add(new IO(kid));
                    }

                }
            }
            if (idx != mKernelCount) {
                throw new RSRuntimeException("Count mismatch, should not happen.");
            }

            long[] src = new long[mLines.size()];
            long[] dstk = new long[mLines.size()];
            long[] dstf = new long[mLines.size()];
            long[] types = new long[mLines.size()];

            for (int ct=0; ct < mLines.size(); ct++) {
                ConnectLine cl = mLines.get(ct);
                src[ct] = cl.mFrom.getID(mRS);
                if (cl.mToK != null) {
                    dstk[ct] = cl.mToK.getID(mRS);
                }
                if (cl.mToF != null) {
                    dstf[ct] = cl.mToF.getID(mRS);
                }
                types[ct] = cl.mAllocationType.getID(mRS);
            }

            long id = mRS.nScriptGroupCreate(kernels, src, dstk, dstf, types);
            if (id == 0) {
                throw new RSRuntimeException("Object creation error, should not happen.");
            }

            ScriptGroup sg = new ScriptGroup(id, mRS);
            sg.mOutputs = new IO[outputs.size()];
            for (int ct=0; ct < outputs.size(); ct++) {
                sg.mOutputs[ct] = outputs.get(ct);
            }

            sg.mInputs = new IO[inputs.size()];
            for (int ct=0; ct < inputs.size(); ct++) {
                sg.mInputs[ct] = inputs.get(ct);
            }

            return sg;
        }

    }

    /**
     * Represents a binding of a value to a global variable in a
     * kernel or invocable function. Used in closure creation.
     */

    public static final class Binding {
        private final Script.FieldID mField;
        private final Object mValue;

        /**
         * Returns a Binding object that binds value to field
         *
         * @param field the Script.FieldID of the global variable
         * @param value the value
         */

        public Binding(Script.FieldID field, Object value) {
            mField = field;
            mValue = value;
        }

        /**
         * Returns the field ID
         */

        Script.FieldID getField() { return mField; }

        /**
         * Returns the value
         */

        Object getValue() { return mValue; }
    }

    /**
     * The builder class for creating script groups
     * <p>
     * A script group is created using closures (see class {@link Closure}).
     * A closure is a function call to a kernel or
     * invocable function. Each function argument or global variable accessed inside
     * the function is bound to 1) a known value, 2) a script group input
     * (see class {@link Input}), or 3) a
     * future (see class {@link Future}).
     * A future is the output of a closure, either the return value of the
     * function or a global variable written by that function.
     * <p>
     * Closures are created using the {@link #addKernel} or {@link #addInvoke}
     * methods.
     * When a closure is created, futures from previously created closures
     * can be used as its inputs.
     * External script group inputs can be used as inputs to individual closures as well.
     * An external script group input is created using the {@link #addInput} method.
     * A script group is created by a call to the {@link #create} method, which
     * accepts an array of futures as the outputs for the script group.
     * <p>
     * Closures in a script group can be evaluated in any order as long as the
     * following conditions are met:
     * 1) a closure must be evaluated before any other closures that take its
     * futures as inputs;
     * 2) all closures added before an invoke closure must be evaluated
     * before it;
     * and 3) all closures added after an invoke closure must be evaluated after
     * it.
     * As a special case, the order that the closures are added is a legal
     * evaluation order. However, other evaluation orders are possible, including
     * concurrently evaluating independent closures.
     */

    public static final class Builder2 {
        RenderScript mRS;
        List<Closure> mClosures;
        List<Input> mInputs;
        private static final String TAG = "ScriptGroup.Builder2";

        /**
         * Returns a Builder object
         *
         * @param rs the RenderScript context
         */
        public Builder2(RenderScript rs) {
            mRS = rs;
            mClosures = new ArrayList<Closure>();
            mInputs = new ArrayList<Input>();
        }

        /**
         * Adds a closure for a kernel
         *
         * @param k Kernel ID for the kernel function
         * @param returnType Allocation type for the return value
         * @param args arguments to the kernel function
         * @param globalBindings bindings for global variables
         * @return a closure
         */

        private Closure addKernelInternal(Script.KernelID k, Type returnType, Object[] args,
                                          Map<Script.FieldID, Object> globalBindings) {
            Closure c = new Closure(mRS, k, returnType, args, globalBindings);
            mClosures.add(c);
            return c;
        }

        /**
         * Adds a closure for an invocable function
         *
         * @param invoke Invoke ID for the invocable function
         * @param args arguments to the invocable function
         * @param globalBindings bindings for global variables
         * @return a closure
         */

        private Closure addInvokeInternal(Script.InvokeID invoke, Object[] args,
                                          Map<Script.FieldID, Object> globalBindings) {
            Closure c = new Closure(mRS, invoke, args, globalBindings);
            mClosures.add(c);
            return c;
        }

        /**
         * Adds a script group input
         *
         * @return a script group input, which can be used as an argument or a value to
         *     a global variable for creating closures
         */
        public Input addInput() {
            Input unbound = new Input();
            mInputs.add(unbound);
            return unbound;
        }

        /**
         * Adds a closure for a kernel
         *
         * @param k Kernel ID for the kernel function
         * @param argsAndBindings arguments followed by bindings for global variables
         * @return a closure
         */

        public Closure addKernel(Script.KernelID k, Type returnType, Object... argsAndBindings) {
            ArrayList<Object> args = new ArrayList<Object>();
            Map<Script.FieldID, Object> bindingMap = new HashMap<Script.FieldID, Object>();
            if (!seperateArgsAndBindings(argsAndBindings, args, bindingMap)) {
                return null;
            }
            return addKernelInternal(k, returnType, args.toArray(), bindingMap);
        }

        /**
         * Adds a closure for an invocable function
         *
         * @param invoke Invoke ID for the invocable function
         * @param argsAndBindings arguments followed by bindings for global variables
         * @return a closure
         */

        public Closure addInvoke(Script.InvokeID invoke, Object... argsAndBindings) {
            ArrayList<Object> args = new ArrayList<Object>();
            Map<Script.FieldID, Object> bindingMap = new HashMap<Script.FieldID, Object>();
            if (!seperateArgsAndBindings(argsAndBindings, args, bindingMap)) {
                return null;
            }
            return addInvokeInternal(invoke, args.toArray(), bindingMap);
        }

        /**
         * Creates a script group
         *
         * @param name name for the script group. Legal names can only contain letters, digits,
         *        '-', or '_'. The name can be no longer than 100 characters.
         *        Try to use unique names, to avoid name conflicts and reduce
         *        the cost of group creation.
         * @param outputs futures intended as outputs of the script group
         * @return a script group
         */

        public ScriptGroup create(String name, Future... outputs) {
            if (name == null || name.isEmpty() || name.length() > 100 ||
                !name.equals(name.replaceAll("[^a-zA-Z0-9-]", "_"))) {
                throw new RSIllegalArgumentException("invalid script group name");
            }
            ScriptGroup ret = new ScriptGroup(mRS, name, mClosures, mInputs, outputs);
            return ret;
        }

        private boolean seperateArgsAndBindings(Object[] argsAndBindings,
                                                ArrayList<Object> args,
                                                Map<Script.FieldID, Object> bindingMap) {
            int i;
            for (i = 0; i < argsAndBindings.length; i++) {
                if (argsAndBindings[i] instanceof Binding) {
                    break;
                }
                args.add(argsAndBindings[i]);
            }

            for (; i < argsAndBindings.length; i++) {
                if (!(argsAndBindings[i] instanceof Binding)) {
                    return false;
                }
                Binding b = (Binding)argsAndBindings[i];
                bindingMap.put(b.getField(), b.getValue());
            }

            return true;
        }

    }

}
