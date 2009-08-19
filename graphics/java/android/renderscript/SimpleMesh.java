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
        mRS.nSimpleMeshBindVertex(mID, a.mID, slot);
    }

    public void bindIndexAllocation(Allocation a) {
        mRS.nSimpleMeshBindIndex(mID, a.mID);
    }

    public Allocation createVertexAllocation(int slot) {
        return Allocation.createTyped(mRS, mVertexTypes[slot]);
    }

    public Allocation createIndexAllocation() {
        return Allocation.createTyped(mRS, mIndexType);
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
            if(mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            int addedIndex = mVertexTypeCount;
            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].t = t;
            mVertexTypeCount++;
            return addedIndex;
        }

        public int addVertexType(Element e, int size) throws IllegalStateException {
            if(mVertexTypeCount >= mVertexTypes.length) {
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
            if(b.mIndexType.t != null) {
                indexID = b.mIndexType.t.mID;
            } else if(b.mIndexType.size != 0) {
                b.mIndexType.t = b.newType(b.mIndexType.e, b.mIndexType.size);
                indexID = b.mIndexType.t.mID;
                toDestroy[toDestroyCount++] = b.mIndexType.t;
            }

            int[] IDs = new int[b.mVertexTypeCount];
            for(int ct=0; ct < b.mVertexTypeCount; ct++) {
                if(b.mVertexTypes[ct].t != null) {
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
            Log.e("rs", "SimpleMesh create");
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

}

