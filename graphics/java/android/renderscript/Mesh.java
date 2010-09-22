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

package android.renderscript;

import java.util.Vector;

import android.util.Config;
import android.util.Log;

/**
 * @hide
 *
 **/
public class Mesh extends BaseObj {

    Allocation[] mVertexBuffers;
    Allocation[] mIndexBuffers;
    Primitive[] mPrimitives;

    Mesh(int id, RenderScript rs) {
        super(id, rs);
    }

    public int getVertexAllocationCount() {
        if(mVertexBuffers == null) {
            return 0;
        }
        return mVertexBuffers.length;
    }
    public Allocation getVertexAllocation(int slot) {
        return mVertexBuffers[slot];
    }

    public int getPrimitiveCount() {
        if(mIndexBuffers == null) {
            return 0;
        }
        return mIndexBuffers.length;
    }
    public Allocation getIndexAllocation(int slot) {
        return mIndexBuffers[slot];
    }
    public Primitive getPrimitive(int slot) {
        return mPrimitives[slot];
    }

    @Override
    void updateFromNative() {
        mName = mRS.nGetName(mID);
        int vtxCount = mRS.nMeshGetVertexBufferCount(mID);
        int idxCount = mRS.nMeshGetIndexCount(mID);

        int[] vtxIDs = new int[vtxCount];
        int[] idxIDs = new int[idxCount];
        int[] primitives = new int[idxCount];

        mRS.nMeshGetVertices(mID, vtxIDs, vtxCount);
        mRS.nMeshGetIndices(mID, idxIDs, primitives, vtxCount);

        mVertexBuffers = new Allocation[vtxCount];
        mIndexBuffers = new Allocation[idxCount];
        mPrimitives = new Primitive[idxCount];

        for(int i = 0; i < vtxCount; i ++) {
            if(vtxIDs[i] != 0) {
                mVertexBuffers[i] = new Allocation(vtxIDs[i], mRS);
                mVertexBuffers[i].updateFromNative();
            }
        }

        for(int i = 0; i < idxCount; i ++) {
            if(idxIDs[i] != 0) {
                mIndexBuffers[i] = new Allocation(idxIDs[i], mRS);
                mIndexBuffers[i].updateFromNative();
            }
            mPrimitives[i] = Primitive.values()[primitives[i]];
        }
    }

    public static class Builder {
        RenderScript mRS;

        class Entry {
            Type t;
            Element e;
            int size;
            Primitive prim;
        }

        int mVertexTypeCount;
        Entry[] mVertexTypes;
        Vector mIndexTypes;

        public Builder(RenderScript rs) {
            mRS = rs;
            mVertexTypeCount = 0;
            mVertexTypes = new Entry[16];
            mIndexTypes = new Vector();
        }

        public int addVertexType(Type t) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            int addedIndex = mVertexTypeCount;
            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].t = t;
            mVertexTypes[mVertexTypeCount].e = null;
            mVertexTypeCount++;
            return addedIndex;
        }

        public int addVertexType(Element e, int size) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            int addedIndex = mVertexTypeCount;
            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].t = null;
            mVertexTypes[mVertexTypeCount].e = e;
            mVertexTypes[mVertexTypeCount].size = size;
            mVertexTypeCount++;
            return addedIndex;
        }

        public int addIndexType(Type t, Primitive p) {
            int addedIndex  = mIndexTypes.size();
            Entry indexType = new Entry();
            indexType.t = t;
            indexType.e = null;
            indexType.size = 0;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return addedIndex;
        }

        public int addIndexType(Primitive p) {
            int addedIndex  = mIndexTypes.size();
            Entry indexType = new Entry();
            indexType.t = null;
            indexType.e = null;
            indexType.size = 0;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return addedIndex;
        }

        public int addIndexType(Element e, int size, Primitive p) {
            int addedIndex  = mIndexTypes.size();
            Entry indexType = new Entry();
            indexType.t = null;
            indexType.e = e;
            indexType.size = size;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return addedIndex;
        }

        Type newType(Element e, int size) {
            Type.Builder tb = new Type.Builder(mRS, e);
            tb.add(Dimension.X, size);
            return tb.create();
        }

        static synchronized Mesh internalCreate(RenderScript rs, Builder b) {

            int id = rs.nMeshCreate(b.mVertexTypeCount, b.mIndexTypes.size());
            Mesh newMesh = new Mesh(id, rs);
            newMesh.mIndexBuffers = new Allocation[b.mIndexTypes.size()];
            newMesh.mPrimitives = new Primitive[b.mIndexTypes.size()];
            newMesh.mVertexBuffers = new Allocation[b.mVertexTypeCount];

            for(int ct = 0; ct < b.mIndexTypes.size(); ct ++) {
                Allocation alloc = null;
                Entry entry = (Entry)b.mIndexTypes.elementAt(ct);
                if (entry.t != null) {
                    alloc = Allocation.createTyped(rs, entry.t);
                }
                else if(entry.e != null) {
                    alloc = Allocation.createSized(rs, entry.e, entry.size);
                }
                int allocID = (alloc == null) ? 0 : alloc.getID();
                rs.nMeshBindIndex(id, allocID, entry.prim.mID, ct);
                newMesh.mIndexBuffers[ct] = alloc;
                newMesh.mPrimitives[ct] = entry.prim;
            }

            for(int ct = 0; ct < b.mVertexTypeCount; ct ++) {
                Allocation alloc = null;
                Entry entry = b.mVertexTypes[ct];
                if (entry.t != null) {
                    alloc = Allocation.createTyped(rs, entry.t);
                } else if(entry.e != null) {
                    alloc = Allocation.createSized(rs, entry.e, entry.size);
                }
                rs.nMeshBindVertex(id, alloc.getID(), ct);
                newMesh.mVertexBuffers[ct] = alloc;
            }

            return newMesh;
        }

        public Mesh create() {
            mRS.validate();
            Mesh sm = internalCreate(mRS, this);
            return sm;
        }
    }

    public static class AllocationBuilder {
        RenderScript mRS;

        class Entry {
            Allocation a;
            Primitive prim;
        }

        int mVertexTypeCount;
        Entry[] mVertexTypes;

        Vector mIndexTypes;

        public AllocationBuilder(RenderScript rs) {
            mRS = rs;
            mVertexTypeCount = 0;
            mVertexTypes = new Entry[16];
            mIndexTypes = new Vector();
        }

        public int addVertexAllocation(Allocation a) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            int addedIndex = mVertexTypeCount;
            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].a = a;
            mVertexTypeCount++;
            return addedIndex;
        }

        public int addIndexAllocation(Allocation a, Primitive p) {
            int addedIndex  = mIndexTypes.size();
            Entry indexType = new Entry();
            indexType.a = a;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return addedIndex;
        }

        public int addIndexType(Primitive p) {
            int addedIndex  = mIndexTypes.size();
            Entry indexType = new Entry();
            indexType.a = null;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return addedIndex;
        }

        static synchronized Mesh internalCreate(RenderScript rs, AllocationBuilder b) {

            int id = rs.nMeshCreate(b.mVertexTypeCount, b.mIndexTypes.size());
            Mesh newMesh = new Mesh(id, rs);
            newMesh.mIndexBuffers = new Allocation[b.mIndexTypes.size()];
            newMesh.mPrimitives = new Primitive[b.mIndexTypes.size()];
            newMesh.mVertexBuffers = new Allocation[b.mVertexTypeCount];

            for(int ct = 0; ct < b.mIndexTypes.size(); ct ++) {
                Entry entry = (Entry)b.mIndexTypes.elementAt(ct);
                int allocID = (entry.a == null) ? 0 : entry.a.getID();
                rs.nMeshBindIndex(id, allocID, entry.prim.mID, ct);
                newMesh.mIndexBuffers[ct] = entry.a;
                newMesh.mPrimitives[ct] = entry.prim;
            }

            for(int ct = 0; ct < b.mVertexTypeCount; ct ++) {
                Entry entry = b.mVertexTypes[ct];
                rs.nMeshBindVertex(id, entry.a.mID, ct);
                newMesh.mVertexBuffers[ct] = entry.a;
            }

            return newMesh;
        }

        public Mesh create() {
            mRS.validate();
            Mesh sm = internalCreate(mRS, this);
            return sm;
        }
    }


    public static class TriangleMeshBuilder {
        float mVtxData[];
        int mVtxCount;
        short mIndexData[];
        int mIndexCount;
        RenderScript mRS;
        Element mElement;

        float mNX = 0;
        float mNY = 0;
        float mNZ = -1;
        float mS0 = 0;
        float mT0 = 0;
        float mR = 1;
        float mG = 1;
        float mB = 1;
        float mA = 1;

        int mVtxSize;
        int mFlags;

        public static final int COLOR = 0x0001;
        public static final int NORMAL = 0x0002;
        public static final int TEXTURE_0 = 0x0100;

        public TriangleMeshBuilder(RenderScript rs, int vtxSize, int flags) {
            mRS = rs;
            mVtxCount = 0;
            mIndexCount = 0;
            mVtxData = new float[128];
            mIndexData = new short[128];
            mVtxSize = vtxSize;
            mFlags = flags;

            if (vtxSize < 2 || vtxSize > 3) {
                throw new IllegalArgumentException("Vertex size out of range.");
            }
        }

        private void makeSpace(int count) {
            if ((mVtxCount + count) >= mVtxData.length) {
                float t[] = new float[mVtxData.length * 2];
                System.arraycopy(mVtxData, 0, t, 0, mVtxData.length);
                mVtxData = t;
            }
        }

        private void latch() {
            if ((mFlags & COLOR) != 0) {
                makeSpace(4);
                mVtxData[mVtxCount++] = mR;
                mVtxData[mVtxCount++] = mG;
                mVtxData[mVtxCount++] = mB;
                mVtxData[mVtxCount++] = mA;
            }
            if ((mFlags & TEXTURE_0) != 0) {
                makeSpace(2);
                mVtxData[mVtxCount++] = mS0;
                mVtxData[mVtxCount++] = mT0;
            }
            if ((mFlags & NORMAL) != 0) {
                makeSpace(3);
                mVtxData[mVtxCount++] = mNX;
                mVtxData[mVtxCount++] = mNY;
                mVtxData[mVtxCount++] = mNZ;
            }
        }

        public void addVertex(float x, float y) {
            if (mVtxSize != 2) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            makeSpace(2);
            mVtxData[mVtxCount++] = x;
            mVtxData[mVtxCount++] = y;
            latch();
        }

        public void addVertex(float x, float y, float z) {
            if (mVtxSize != 3) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            makeSpace(3);
            mVtxData[mVtxCount++] = x;
            mVtxData[mVtxCount++] = y;
            mVtxData[mVtxCount++] = z;
            latch();
        }

        public void setTexture(float s, float t) {
            if ((mFlags & TEXTURE_0) == 0) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            mS0 = s;
            mT0 = t;
        }

        public void setNormal(float x, float y, float z) {
            if ((mFlags & NORMAL) == 0) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            mNX = x;
            mNY = y;
            mNZ = z;
        }

        public void setColor(float r, float g, float b, float a) {
            if ((mFlags & COLOR) == 0) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            mR = r;
            mG = g;
            mB = b;
            mA = a;
        }

        public void addTriangle(int idx1, int idx2, int idx3) {
            if((idx1 >= mVtxCount) || (idx1 < 0) ||
               (idx2 >= mVtxCount) || (idx2 < 0) ||
               (idx3 >= mVtxCount) || (idx3 < 0)) {
               throw new IllegalStateException("Index provided greater than vertex count.");
            }
            if ((mIndexCount + 3) >= mIndexData.length) {
                short t[] = new short[mIndexData.length * 2];
                System.arraycopy(mIndexData, 0, t, 0, mIndexData.length);
                mIndexData = t;
            }
            mIndexData[mIndexCount++] = (short)idx1;
            mIndexData[mIndexCount++] = (short)idx2;
            mIndexData[mIndexCount++] = (short)idx3;
        }

        public Mesh create(boolean uploadToBufferObject) {
            Element.Builder b = new Element.Builder(mRS);
            int floatCount = mVtxSize;
            b.add(Element.createVector(mRS,
                                       Element.DataType.FLOAT_32,
                                       mVtxSize), "position");
            if ((mFlags & COLOR) != 0) {
                floatCount += 4;
                b.add(Element.F32_4(mRS), "color");
            }
            if ((mFlags & TEXTURE_0) != 0) {
                floatCount += 2;
                b.add(Element.F32_2(mRS), "texture0");
            }
            if ((mFlags & NORMAL) != 0) {
                floatCount += 3;
                b.add(Element.F32_3(mRS), "normal");
            }
            mElement = b.create();

            Builder smb = new Builder(mRS);
            smb.addVertexType(mElement, mVtxCount / floatCount);
            smb.addIndexType(Element.U16(mRS), mIndexCount, Primitive.TRIANGLE);

            Mesh sm = smb.create();

            sm.getVertexAllocation(0).data(mVtxData);
            if(uploadToBufferObject) {
                sm.getVertexAllocation(0).uploadToBufferObject();
            }

            sm.getIndexAllocation(0).data(mIndexData);
            sm.getIndexAllocation(0).uploadToBufferObject();

            return sm;
        }
    }
}

