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

import java.lang.reflect.Method;

/**
 * @hide
 **/
public class ScriptGroup extends BaseObj {
    Node mNodes[];
    Connection mConnections[];
    Node mFirstNode;
    IO mOutputs[];
    IO mInputs[];

    static class IO {
        Script mScript;
        Allocation mAllocation;
        String mName;

        IO(Script s) {
            mScript = s;
        }
        IO(Script s, String n) {
            mScript = s;
            mName = n;
        }
    }

    static class Connection {
        Node mTo[];
        String mToName[];
        Node mFrom;
        Type mAllocationType;
        Allocation mInternalAllocation;

        Connection(Node out, Type t) {
            mFrom = out;
            mAllocationType = t;
        }

        void addTo(Node n, String name) {
            if (mTo == null) {
                mTo = new Node[1];
                mToName = new String[1];
            } else {
                Node nt[] = new Node[mTo.length + 1];
                String ns[] = new String[mTo.length + 1];
                System.arraycopy(mTo, 0, nt, 0, mTo.length);
                System.arraycopy(mToName, 0, ns, 0, mTo.length);
                mTo = nt;
                mToName = ns;
            }
            mTo[mTo.length - 1] = n;
            mToName[mTo.length - 1] = name;
        }
    }

    static class Node {
        Script mScript;
        Connection mInput[] = new Connection[8];
        Connection mOutput[] = new Connection[1];
        int mInputCount;
        int mOutputCount;
        int mDepth;
        boolean mSeen;

        Node mNext;

        Node(Script s) {
            mScript = s;
        }

        void addInput(Connection c) {
            if (mInput.length <= mInputCount) {
                Connection[] nc = new Connection[mInput.length + 8];
                System.arraycopy(mInput, 0, nc, 0, mInputCount);
                mInput = nc;
            }
            mInput[mInputCount++] = c;
        }

        void addOutput(Connection c) {
            if (mOutput.length <= mOutputCount) {
                Connection[] nc = new Connection[mOutput.length + 8];
                System.arraycopy(mOutput, 0, nc, 0, mOutputCount);
                mOutput = nc;
            }
            mOutput[mOutputCount++] = c;
        }
    }


    ScriptGroup(int id, RenderScript rs) {
        super(id, rs);
    }

    void init(int nodeCount, int connectionCount) {
        mNodes = new Node[nodeCount];
        mConnections = new Connection[connectionCount];

        android.util.Log.v("RSR", "init" + nodeCount + ", " + connectionCount);

        // Count outputs and create array.
        Node n = mFirstNode;
        int outputCount = 0;
        int inputCount = 0;
        int connectionIndex = 0;
        int nodeNum = 0;
        while (n != null) {
            mNodes[nodeNum++] = n;

            // Look for unattached kernel inputs
            boolean hasInput = false;
            for (int ct=0; ct < n.mInput.length; ct++) {
                if (n.mInput[ct] != null) {
                    if (n.mInput[ct].mToName == null) {
                        hasInput = true;
                    }
                }
            }
            if (!hasInput) {
                if (mInputs == null) {
                    mInputs = new IO[1];
                }
                if (mInputs.length <= inputCount) {
                    IO t[] = new IO[mInputs.length + 1];
                    System.arraycopy(mInputs, 0, t, 0, mInputs.length);
                    mInputs = t;
                }
                mInputs[inputCount++] = new IO(n.mScript);
            }

            // Look for unattached kernel outputs
            boolean hasOutput = false;
            for (int ct=0; ct < n.mOutput.length; ct++) {
                if (n.mOutput[ct] != null) {
                    hasOutput = true;
                }
            }
            if (!hasOutput) {
                if (mOutputs == null) {
                    mOutputs = new IO[1];
                }
                if (mOutputs.length <= outputCount) {
                    IO t[] = new IO[mOutputs.length + 1];
                    System.arraycopy(mOutputs, 0, t, 0, mOutputs.length);
                    mOutputs = t;
                }
                mOutputs[outputCount++] = new IO(n.mScript);
            }

            // Make allocations for internal connections
            // Since script outputs are unique, use those to avoid duplicates.
            for (int ct=0; ct < n.mOutput.length; ct++) {
                android.util.Log.v("RSR", "init out2 " + n.mOutput[ct]);
                if (n.mOutput[ct] != null) {
                    Connection t = n.mOutput[ct];
                    mConnections[connectionIndex++] = t;
                    t.mInternalAllocation = Allocation.createTyped(mRS, t.mAllocationType);
                }
            }

            n = n.mNext;
        }
    }

    public void setInput(Script s, Allocation a) {
        for (int ct=0; ct < mInputs.length; ct++) {
            if (mInputs[ct].mScript == s) {
                mInputs[ct].mAllocation = a;
                return;
            }
        }
        throw new RSIllegalArgumentException("Script not found");
    }

    public void setOutput(Script s, Allocation a) {
        for (int ct=0; ct < mOutputs.length; ct++) {
            if (mOutputs[ct].mScript == s) {
                mOutputs[ct].mAllocation = a;
                return;
            }
        }
        throw new RSIllegalArgumentException("Script not found");
    }

    public void execute() {
        android.util.Log.v("RSR", "execute");
        boolean more = true;
        int depth = 0;
        while (more) {
            more = false;
            for (int ct=0; ct < mNodes.length; ct++) {
                if (mNodes[ct].mDepth == depth) {
                    more = true;

                    Allocation kernelIn = null;
                    for (int ct2=0; ct2 < mNodes[ct].mInputCount; ct2++) {
                        android.util.Log.v("RSR", " kin " + ct2 + ", to " + mNodes[ct].mInput[ct2].mTo[0] + ", name " + mNodes[ct].mInput[ct2].mToName[0]);
                        if (mNodes[ct].mInput[ct2].mToName[0] == null) {
                            kernelIn = mNodes[ct].mInput[ct2].mInternalAllocation;
                            break;
                        }
                    }

                    Allocation kernelOut= null;
                    for (int ct2=0; ct2 < mNodes[ct].mOutputCount; ct2++) {
                        android.util.Log.v("RSR", " kout " + ct2 + ", from " + mNodes[ct].mOutput[ct2].mFrom);
                        if (mNodes[ct].mOutput[ct2].mFrom != null) {
                            kernelOut = mNodes[ct].mOutput[ct2].mInternalAllocation;
                            break;
                        }
                    }
                    if (kernelOut == null) {
                        for (int ct2=0; ct2 < mOutputs.length; ct2++) {
                            if (mOutputs[ct2].mScript == mNodes[ct].mScript) {
                                kernelOut = mOutputs[ct2].mAllocation;
                                break;
                            }
                        }
                    }

                    android.util.Log.v("RSR", "execute calling " + mNodes[ct] + ", with " + kernelIn);
                    if (kernelIn != null) {
                        try {

                            Method m = mNodes[ct].mScript.getClass().getMethod("forEach_root",
                                          new Class[] { Allocation.class, Allocation.class });
                            m.invoke(mNodes[ct].mScript, new Object[] {kernelIn, kernelOut} );
                        } catch (Throwable t) {
                            android.util.Log.e("RSR", "execute error " + t);
                        }
                    } else {
                        try {
                            Method m = mNodes[ct].mScript.getClass().getMethod("forEach_root",
                                          new Class[] { Allocation.class });
                            m.invoke(mNodes[ct].mScript, new Object[] {kernelOut} );
                        } catch (Throwable t) {
                            android.util.Log.e("RSR", "execute error " + t);
                        }
                    }

                }
            }
            depth ++;
        }

    }


    public static class Builder {
        RenderScript mRS;
        Node mFirstNode;
        int mConnectionCount = 0;
        int mNodeCount = 0;

        public Builder(RenderScript rs) {
            mRS = rs;
        }

        private void validateRecurse(Node n, int depth) {
            n.mSeen = true;
            if (depth > n.mDepth) {
                n.mDepth = depth;
            }

            android.util.Log.v("RSR", " validateRecurse outputCount " + n.mOutputCount);
            for (int ct=0; ct < n.mOutputCount; ct++) {
                for (int ct2=0; ct2 < n.mOutput[ct].mTo.length; ct2++) {
                    if (n.mOutput[ct].mTo[ct2].mSeen) {
                        throw new RSInvalidStateException("Loops in group not allowed.");
                    }
                    validateRecurse(n.mOutput[ct].mTo[ct2], depth + 1);
                }
            }
        }

        private void validate() {
            android.util.Log.v("RSR", "validate");
            Node n = mFirstNode;
            while (n != null) {
                n.mSeen = false;
                n.mDepth = 0;
                n = n.mNext;
            }

            n = mFirstNode;
            while (n != null) {
                android.util.Log.v("RSR", "validate n= " + n);
                if ((n.mSeen == false) && (n.mInputCount == 0)) {
                    android.util.Log.v("RSR", " recursing " + n);
                    validateRecurse(n, 0);
                }
                n = n.mNext;
            }
        }

        private Node findScript(Script s) {
            Node n = mFirstNode;
            while (n != null) {
                if (n.mScript == s) {
                    return n;
                }
                n = n.mNext;
            }
            return null;
        }

        private void addNode(Node n) {
            n.mNext = mFirstNode;
            mFirstNode = n;
        }

        public Builder addConnection(Type t, Script output, Script input, String inputName) {
            android.util.Log.v("RSR", "addConnection " + t +", " + output + ", " + input);

            // Look for existing output
            Node nout = findScript(output);
            Connection c;
            if (nout == null) {
                // Make new node
                android.util.Log.v("RSR", "addConnection new output node");
                nout = new Node(output);
                mNodeCount++;
                c = new Connection(nout, t);
                mConnectionCount++;
                nout.addOutput(c);
                addNode(nout);
            } else {
                // Add to existing node
                android.util.Log.v("RSR", "addConnection reuse output node");
                if (nout.mOutput[0] != null) {
                    if (nout.mOutput[0].mFrom.mScript != output) {
                        throw new RSInvalidStateException("Changed output of existing node");
                    }
                    if (nout.mOutput[0].mAllocationType != t) {
                        throw new RSInvalidStateException("Changed output type of existing node");
                    }
                }
                c = nout.mOutput[0];
            }
            // At this point we should have a connection attached to a script ouput.

            // Find input
            Node nin = findScript(input);
            if (nin == null) {
                android.util.Log.v("RSR", "addConnection new input node");
                nin = new Node(input);
                mNodeCount++;
                addNode(nin);
            }
            c.addTo(nin, inputName);
            nin.addInput(c);

            validate();
            return this;
        }

        public ScriptGroup create() {
            ScriptGroup sg = new ScriptGroup(0, mRS);
            sg.mFirstNode = mFirstNode;
            mFirstNode = null;

            android.util.Log.v("RSR", "create nodes= " + mNodeCount + ", Connections= " + mConnectionCount);

            sg.init(mNodeCount, mConnectionCount);
            return sg;
        }

    }


}


