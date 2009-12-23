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

import android.util.Config;
import android.util.Log;

/**
 * @hide
 *
 **/
public class SimpleMesh extends BaseObj {
    Type[] mVertexTypes;
    Type mIndexType;
    //Type mBatcheType;
    Primitive mPrimitive;

    SimpleMesh(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void bindVertexAllocation(Allocation a, int slot) {
        mRS.validate();
        mRS.nSimpleMeshBindVertex(mID, a.mID, slot);
    }

    public void bindIndexAllocation(Allocation a) {
        mRS.validate();
        mRS.nSimpleMeshBindIndex(mID, a.mID);
    }

    public Allocation createVertexAllocation(int slot) {
        mRS.validate();
        return Allocation.createTyped(mRS, mVertexTypes[slot]);
    }

    public Allocation createIndexAllocation() {
        mRS.validate();
        return Allocation.createTyped(mRS, mIndexType);
    }

    public Type getVertexType(int slot) {
        return mVertexTypes[slot];
    }

    public Type getIndexType() {
        return mIndexType;
    }

    public static class Builder {
        RenderScript mRS;

        class Entry {
            Type t;
            Element e;
            int size;
        }

        int mVertexTypeCount;
        Entry[] mVertexTypes;
        Entry mIndexType;
        //Entry mBatchType;
        Primitive mPrimitive;


        public Builder(RenderScript rs) {
            mRS = rs;
            mVertexTypeCount = 0;
            mVertexTypes = new Entry[16];
            mIndexType = new Entry();
        }

        public int addVertexType(Type t) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            int addedIndex = mVertexTypeCount;
            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].t = t;
            mVertexTypeCount++;
            return addedIndex;
        }

        public int addVertexType(Element e, int size) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            int addedIndex = mVertexTypeCount;
            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].e = e;
            mVertexTypes[mVertexTypeCount].size = size;
            mVertexTypeCount++;
            return addedIndex;
        }

        public void setIndexType(Type t) {
            mIndexType.t = t;
            mIndexType.e = null;
            mIndexType.size = 0;
        }

        public void setIndexType(Element e, int size) {
            mIndexType.t = null;
            mIndexType.e = e;
            mIndexType.size = size;
        }

        public void setPrimitive(Primitive p) {
            mPrimitive = p;
        }


        Type newType(Element e, int size) {
            Type.Builder tb = new Type.Builder(mRS, e);
            tb.add(Dimension.X, size);
            return tb.create();
        }

        static synchronized SimpleMesh internalCreate(RenderScript rs, Builder b) {
            Type[] toDestroy = new Type[18];
            int toDestroyCount = 0;

            int indexID = 0;
            if (b.mIndexType.t != null) {
                indexID = b.mIndexType.t.mID;
            } else if (b.mIndexType.size != 0) {
                b.mIndexType.t = b.newType(b.mIndexType.e, b.mIndexType.size);
                indexID = b.mIndexType.t.mID;
                toDestroy[toDestroyCount++] = b.mIndexType.t;
            }

            int[] IDs = new int[b.mVertexTypeCount];
            for(int ct=0; ct < b.mVertexTypeCount; ct++) {
                if (b.mVertexTypes[ct].t != null) {
                    IDs[ct] = b.mVertexTypes[ct].t.mID;
                } else {
                    b.mVertexTypes[ct].t = b.newType(b.mVertexTypes[ct].e, b.mVertexTypes[ct].size);
                    IDs[ct] = b.mVertexTypes[ct].t.mID;
                    toDestroy[toDestroyCount++] = b.mVertexTypes[ct].t;
                }
            }

            int id = rs.nSimpleMeshCreate(0, indexID, IDs, b.mPrimitive.mID);
            for(int ct=0; ct < toDestroyCount; ct++) {
                toDestroy[ct].destroy();
            }

            return new SimpleMesh(id, rs);
        }

        public SimpleMesh create() {
            mRS.validate();
            SimpleMesh sm = internalCreate(mRS, this);
            sm.mVertexTypes = new Type[mVertexTypeCount];
            for(int ct=0; ct < mVertexTypeCount; ct++) {
                sm.mVertexTypes[ct] = mVertexTypes[ct].t;
            }
            sm.mIndexType = mIndexType.t;
            sm.mPrimitive = mPrimitive;
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

        public SimpleMesh create() {
            Element.Builder b = new Element.Builder(mRS);
            int floatCount = mVtxSize;
            b.add(Element.createAttrib(mRS,
                                       Element.DataType.FLOAT_32,
                                       Element.DataKind.POSITION,
                                       mVtxSize), "position");
            if ((mFlags & COLOR) != 0) {
                floatCount += 4;
                b.add(Element.createAttrib(mRS,
                                           Element.DataType.FLOAT_32,
                                           Element.DataKind.COLOR,
                                           4), "color");
            }
            if ((mFlags & TEXTURE_0) != 0) {
                floatCount += 2;
                b.add(Element.createAttrib(mRS,
                                           Element.DataType.FLOAT_32,
                                           Element.DataKind.TEXTURE,
                                           2), "texture");
            }
            if ((mFlags & NORMAL) != 0) {
                floatCount += 3;
                b.add(Element.createAttrib(mRS,
                                           Element.DataType.FLOAT_32,
                                           Element.DataKind.NORMAL,
                                           3), "normal");
            }
            mElement = b.create();

            Builder smb = new Builder(mRS);
            smb.addVertexType(mElement, mVtxCount / floatCount);
            smb.setIndexType(Element.createIndex(mRS), mIndexCount);
            smb.setPrimitive(Primitive.TRIANGLE);
            SimpleMesh sm = smb.create();

            Allocation vertexAlloc = sm.createVertexAllocation(0);
            Allocation indexAlloc = sm.createIndexAllocation();
            sm.bindVertexAllocation(vertexAlloc, 0);
            sm.bindIndexAllocation(indexAlloc);

            vertexAlloc.data(mVtxData);
            vertexAlloc.uploadToBufferObject();

            indexAlloc.data(mIndexData);
            indexAlloc.uploadToBufferObject();

            return sm;
        }
    }
}

