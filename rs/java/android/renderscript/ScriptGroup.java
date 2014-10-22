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

import java.util.ArrayList;

/**
 * ScriptGroup creates a group of kernels that are executed
 * together with one execution call as if they were a single kernel.
 * The kernels may be connected internally or to an external allocation.
 * The intermediate results for internal connections are not observable
 * after the execution of the script.
 * <p>
 * External connections are grouped into inputs and outputs.
 * All outputs are produced by a script kernel and placed into a
 * user-supplied allocation. Inputs provide the input of a kernel.
 * Inputs bound to script globals are set directly upon the script.
 * <p>
 * A ScriptGroup must contain at least one kernel. A ScriptGroup
 * must contain only a single directed acyclic graph (DAG) of
 * script kernels and connections. Attempting to create a
 * ScriptGroup with multiple DAGs or attempting to create
 * a cycle within a ScriptGroup will throw an exception.
 * <p>
 * Currently, all kernels in a ScriptGroup must be from separate
 * Script objects. Attempting to use multiple kernels from the same
 * Script object will result in an {@link android.renderscript.RSInvalidStateException}.
 *
 **/
public final class ScriptGroup extends BaseObj {
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


    ScriptGroup(long id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Sets an input of the ScriptGroup. This specifies an
     * Allocation to be used for kernels that require an input
     * Allocation provided from outside of the ScriptGroup.
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
                        throw new RSInvalidStateException("Groups cannot contain unconnected scripts");
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


}


