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

import android.util.Log;

/**
 * <p>This class is a container for geometric data displayed with
 * Renderscript. Internally, a mesh is a collection of allocations that
 * represent vertex data (positions, normals, texture
 * coordinates) and index data such as triangles and lines. </p>
 * <p>
 * Vertex data could either be interleaved within one
 * allocation that is provided separately, as multiple allocation
 * objects, or done as a combination of both. When a
 * vertex channel name matches an input in the vertex program,
 * Renderscript automatically connects the two together.
 * </p>
 * <p>
 *  Parts of the mesh can be rendered with either explicit
 *  index sets or primitive types.
 * </p>
 **/
public class Mesh extends BaseObj {

    /**
    * Describes the way mesh vertex data is interpreted when rendering
    *
    **/
    public enum Primitive {
        /**
        * Vertex data will be rendered as a series of points
        */
        POINT (0),
        /**
        * Vertex pairs will be rendered as lines
        */
        LINE (1),
        /**
        * Vertex data will be rendered as a connected line strip
        */
        LINE_STRIP (2),
        /**
        * Vertices will be rendered as individual triangles
        */
        TRIANGLE (3),
        /**
        * Vertices will be rendered as a connected triangle strip
        * defined by the first three vertices with each additional
        * triangle defined by a new vertex
        */
        TRIANGLE_STRIP (4),
        /**
        * Vertices will be rendered as a sequence of triangles that all
        * share first vertex as the origin
        */
        TRIANGLE_FAN (5);

        int mID;
        Primitive(int id) {
            mID = id;
        }
    }

    Allocation[] mVertexBuffers;
    Allocation[] mIndexBuffers;
    Primitive[] mPrimitives;

    Mesh(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
    * @return number of allocations containing vertex data
    *
    **/
    public int getVertexAllocationCount() {
        if(mVertexBuffers == null) {
            return 0;
        }
        return mVertexBuffers.length;
    }
    /**
    * @param slot index in the list of allocations to return
    * @return vertex data allocation at the given index
    *
    **/
    public Allocation getVertexAllocation(int slot) {
        return mVertexBuffers[slot];
    }

    /**
    * @return number of primitives or index sets in the mesh
    *
    **/
    public int getPrimitiveCount() {
        if(mIndexBuffers == null) {
            return 0;
        }
        return mIndexBuffers.length;
    }

    /**
    * @param slot locaton within the list of index set allocation
    * @return allocation containing primtive index data or null if
    *         the index data is not specified explicitly
    *
    **/
    public Allocation getIndexSetAllocation(int slot) {
        return mIndexBuffers[slot];
    }
    /**
    * @param slot locaiton within the list of index set primitives
    * @return index set primitive type
    *
    **/
    public Primitive getPrimitive(int slot) {
        return mPrimitives[slot];
    }

    @Override
    void updateFromNative() {
        super.updateFromNative();
        int vtxCount = mRS.nMeshGetVertexBufferCount(getID());
        int idxCount = mRS.nMeshGetIndexCount(getID());

        int[] vtxIDs = new int[vtxCount];
        int[] idxIDs = new int[idxCount];
        int[] primitives = new int[idxCount];

        mRS.nMeshGetVertices(getID(), vtxIDs, vtxCount);
        mRS.nMeshGetIndices(getID(), idxIDs, primitives, idxCount);

        mVertexBuffers = new Allocation[vtxCount];
        mIndexBuffers = new Allocation[idxCount];
        mPrimitives = new Primitive[idxCount];

        for(int i = 0; i < vtxCount; i ++) {
            if(vtxIDs[i] != 0) {
                mVertexBuffers[i] = new Allocation(vtxIDs[i], mRS, null, Allocation.USAGE_SCRIPT);
                mVertexBuffers[i].updateFromNative();
            }
        }

        for(int i = 0; i < idxCount; i ++) {
            if(idxIDs[i] != 0) {
                mIndexBuffers[i] = new Allocation(idxIDs[i], mRS, null, Allocation.USAGE_SCRIPT);
                mIndexBuffers[i].updateFromNative();
            }
            mPrimitives[i] = Primitive.values()[primitives[i]];
        }
    }

    /**
    * Mesh builder object. It starts empty and requires you to
    * add the types necessary to create vertex and index
    * allocations.
    *
    */
    public static class Builder {
        RenderScript mRS;
        int mUsage;

        class Entry {
            Type t;
            Element e;
            int size;
            Primitive prim;
            int usage;
        }

        int mVertexTypeCount;
        Entry[] mVertexTypes;
        Vector mIndexTypes;

        /**
        * Creates builder object
        * @param rs Context to which the mesh will belong.
        * @param usage specifies how the mesh allocations are to be
        *              handled, whether they need to be uploaded to a
        *              buffer on the gpu, maintain a cpu copy, etc
        */
        public Builder(RenderScript rs, int usage) {
            mRS = rs;
            mUsage = usage;
            mVertexTypeCount = 0;
            mVertexTypes = new Entry[16];
            mIndexTypes = new Vector();
        }

        /**
        * @return internal index of the last vertex buffer type added to
        *         builder
        **/
        public int getCurrentVertexTypeIndex() {
            return mVertexTypeCount - 1;
        }

        /**
        * @return internal index of the last index set added to the
        *         builder
        **/
        public int getCurrentIndexSetIndex() {
            return mIndexTypes.size() - 1;
        }

        /**
        * Adds a vertex data type to the builder object
        *
        * @param t type of the vertex data allocation to be created
        *
        * @return this
        **/
        public Builder addVertexType(Type t) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].t = t;
            mVertexTypes[mVertexTypeCount].e = null;
            mVertexTypeCount++;
            return this;
        }

        /**
        * Adds a vertex data type to the builder object
        *
        * @param e element describing the vertex data layout
        * @param size number of elements in the buffer
        *
        * @return this
        **/
        public Builder addVertexType(Element e, int size) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].t = null;
            mVertexTypes[mVertexTypeCount].e = e;
            mVertexTypes[mVertexTypeCount].size = size;
            mVertexTypeCount++;
            return this;
        }

        /**
        * Adds an index set data type to the builder object
        *
        * @param t type of the index set data, could be null
        * @param p primitive type
        *
        * @return this
        **/
        public Builder addIndexSetType(Type t, Primitive p) {
            Entry indexType = new Entry();
            indexType.t = t;
            indexType.e = null;
            indexType.size = 0;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return this;
        }

        /**
        * Adds an index set primitive type to the builder object
        *
        * @param p primitive type
        *
        * @return this
        **/
        public Builder addIndexSetType(Primitive p) {
            Entry indexType = new Entry();
            indexType.t = null;
            indexType.e = null;
            indexType.size = 0;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return this;
        }

        /**
        * Adds an index set data type to the builder object
        *
        * @param e element describing the index set data layout
        * @param size number of elements in the buffer
        * @param p primitive type
        *
        * @return this
        **/
        public Builder addIndexSetType(Element e, int size, Primitive p) {
            Entry indexType = new Entry();
            indexType.t = null;
            indexType.e = e;
            indexType.size = size;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return this;
        }

        Type newType(Element e, int size) {
            Type.Builder tb = new Type.Builder(mRS, e);
            tb.setX(size);
            return tb.create();
        }

        /**
        * Create a Mesh object from the current state of the builder
        *
        **/
        public Mesh create() {
            mRS.validate();
            int[] vtx = new int[mVertexTypeCount];
            int[] idx = new int[mIndexTypes.size()];
            int[] prim = new int[mIndexTypes.size()];

            Allocation[] vertexBuffers = new Allocation[mVertexTypeCount];
            Allocation[] indexBuffers = new Allocation[mIndexTypes.size()];
            Primitive[] primitives = new Primitive[mIndexTypes.size()];

            for(int ct = 0; ct < mVertexTypeCount; ct ++) {
                Allocation alloc = null;
                Entry entry = mVertexTypes[ct];
                if (entry.t != null) {
                    alloc = Allocation.createTyped(mRS, entry.t, mUsage);
                } else if(entry.e != null) {
                    alloc = Allocation.createSized(mRS, entry.e, entry.size, mUsage);
                }
                vertexBuffers[ct] = alloc;
                vtx[ct] = alloc.getID();
            }

            for(int ct = 0; ct < mIndexTypes.size(); ct ++) {
                Allocation alloc = null;
                Entry entry = (Entry)mIndexTypes.elementAt(ct);
                if (entry.t != null) {
                    alloc = Allocation.createTyped(mRS, entry.t, mUsage);
                } else if(entry.e != null) {
                    alloc = Allocation.createSized(mRS, entry.e, entry.size, mUsage);
                }
                int allocID = (alloc == null) ? 0 : alloc.getID();
                indexBuffers[ct] = alloc;
                primitives[ct] = entry.prim;

                idx[ct] = allocID;
                prim[ct] = entry.prim.mID;
            }

            int id = mRS.nMeshCreate(vtx, idx, prim);
            Mesh newMesh = new Mesh(id, mRS);
            newMesh.mVertexBuffers = vertexBuffers;
            newMesh.mIndexBuffers = indexBuffers;
            newMesh.mPrimitives = primitives;

            return newMesh;
        }
    }

    /**
    * Mesh builder object. It starts empty and requires the user to
    * add all the vertex and index allocations that comprise the
    * mesh
    *
    */
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

        /**
        * @return internal index of the last vertex buffer type added to
        *         builder
        **/
        public int getCurrentVertexTypeIndex() {
            return mVertexTypeCount - 1;
        }

        /**
        * @return internal index of the last index set added to the
        *         builder
        **/
        public int getCurrentIndexSetIndex() {
            return mIndexTypes.size() - 1;
        }

        /**
        * Adds an allocation containing vertex buffer data to the
        * builder
        *
        * @param a vertex data allocation
        *
        * @return this
        **/
        public AllocationBuilder addVertexAllocation(Allocation a) throws IllegalStateException {
            if (mVertexTypeCount >= mVertexTypes.length) {
                throw new IllegalStateException("Max vertex types exceeded.");
            }

            mVertexTypes[mVertexTypeCount] = new Entry();
            mVertexTypes[mVertexTypeCount].a = a;
            mVertexTypeCount++;
            return this;
        }

        /**
        * Adds an allocation containing index buffer data and index type
        * to the builder
        *
        * @param a index set data allocation, could be null
        * @param p index set primitive type
        *
        * @return this
        **/
        public AllocationBuilder addIndexSetAllocation(Allocation a, Primitive p) {
            Entry indexType = new Entry();
            indexType.a = a;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return this;
        }

        /**
        * Adds an index set type to the builder
        *
        * @param p index set primitive type
        *
        * @return this
        **/
        public AllocationBuilder addIndexSetType(Primitive p) {
            Entry indexType = new Entry();
            indexType.a = null;
            indexType.prim = p;
            mIndexTypes.addElement(indexType);
            return this;
        }

        /**
        * Create a Mesh object from the current state of the builder
        *
        **/
        public Mesh create() {
            mRS.validate();

            int[] vtx = new int[mVertexTypeCount];
            int[] idx = new int[mIndexTypes.size()];
            int[] prim = new int[mIndexTypes.size()];

            Allocation[] indexBuffers = new Allocation[mIndexTypes.size()];
            Primitive[] primitives = new Primitive[mIndexTypes.size()];
            Allocation[] vertexBuffers = new Allocation[mVertexTypeCount];

            for(int ct = 0; ct < mVertexTypeCount; ct ++) {
                Entry entry = mVertexTypes[ct];
                vertexBuffers[ct] = entry.a;
                vtx[ct] = entry.a.getID();
            }

            for(int ct = 0; ct < mIndexTypes.size(); ct ++) {
                Entry entry = (Entry)mIndexTypes.elementAt(ct);
                int allocID = (entry.a == null) ? 0 : entry.a.getID();
                indexBuffers[ct] = entry.a;
                primitives[ct] = entry.prim;

                idx[ct] = allocID;
                prim[ct] = entry.prim.mID;
            }

            int id = mRS.nMeshCreate(vtx, idx, prim);
            Mesh newMesh = new Mesh(id, mRS);
            newMesh.mVertexBuffers = vertexBuffers;
            newMesh.mIndexBuffers = indexBuffers;
            newMesh.mPrimitives = primitives;

            return newMesh;
        }
    }

    /**
    * Builder that allows creation of a mesh object point by point
    * and triangle by triangle
    *
    **/
    public static class TriangleMeshBuilder {
        float mVtxData[];
        int mVtxCount;
        int mMaxIndex;
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

        /**
        * @param rs Context to which the mesh will belong.
        * @param vtxSize specifies whether the vertex is a float2 or
        *                float3
        * @param flags bitfield that is a combination of COLOR, NORMAL,
        *              and TEXTURE_0 that specifies what vertex data
        *              channels are present in the mesh
        *
        **/
        public TriangleMeshBuilder(RenderScript rs, int vtxSize, int flags) {
            mRS = rs;
            mVtxCount = 0;
            mMaxIndex = 0;
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
                makeSpace(4);
                mVtxData[mVtxCount++] = mNX;
                mVtxData[mVtxCount++] = mNY;
                mVtxData[mVtxCount++] = mNZ;
                mVtxData[mVtxCount++] = 0.0f;
            }
            mMaxIndex ++;
        }

        /**
        * Adds a float2 vertex to the mesh
        *
        * @param x position x
        * @param y position y
        *
        * @return this
        *
        **/
        public TriangleMeshBuilder addVertex(float x, float y) {
            if (mVtxSize != 2) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            makeSpace(2);
            mVtxData[mVtxCount++] = x;
            mVtxData[mVtxCount++] = y;
            latch();
            return this;
        }

        /**
        * Adds a float3 vertex to the mesh
        *
        * @param x position x
        * @param y position y
        * @param z position z
        *
        * @return this
        *
        **/
        public TriangleMeshBuilder addVertex(float x, float y, float z) {
            if (mVtxSize != 3) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            makeSpace(4);
            mVtxData[mVtxCount++] = x;
            mVtxData[mVtxCount++] = y;
            mVtxData[mVtxCount++] = z;
            mVtxData[mVtxCount++] = 1.0f;
            latch();
            return this;
        }

        /**
        * Sets the texture coordinate for the last added vertex
        *
        * @param s texture coordinate s
        * @param t texture coordinate t
        *
        * @return this
        **/
        public TriangleMeshBuilder setTexture(float s, float t) {
            if ((mFlags & TEXTURE_0) == 0) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            mS0 = s;
            mT0 = t;
            return this;
        }

        /**
        * Sets the normal vector for the last added vertex
        *
        * @param x normal vector x
        * @param y normal vector y
        * @param z normal vector z
        *
        * @return this
        **/
        public TriangleMeshBuilder setNormal(float x, float y, float z) {
            if ((mFlags & NORMAL) == 0) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            mNX = x;
            mNY = y;
            mNZ = z;
            return this;
        }

        /**
        * Sets the color for the last added vertex
        *
        * @param r red component
        * @param g green component
        * @param b blue component
        * @param a alpha component
        *
        * @return this
        **/
        public TriangleMeshBuilder setColor(float r, float g, float b, float a) {
            if ((mFlags & COLOR) == 0) {
                throw new IllegalStateException("add mistmatch with declared components.");
            }
            mR = r;
            mG = g;
            mB = b;
            mA = a;
            return this;
        }

        /**
        * Adds a new triangle to the mesh builder
        *
        * @param idx1 index of the first vertex in the triangle
        * @param idx2 index of the second vertex in the triangle
        * @param idx3 index of the third vertex in the triangle
        *
        * @return this
        **/
        public TriangleMeshBuilder addTriangle(int idx1, int idx2, int idx3) {
            if((idx1 >= mMaxIndex) || (idx1 < 0) ||
               (idx2 >= mMaxIndex) || (idx2 < 0) ||
               (idx3 >= mMaxIndex) || (idx3 < 0)) {
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
            return this;
        }

        /**
        * Creates the mesh object from the current state of the builder
        *
        * @param uploadToBufferObject specifies whether the vertex data
        *                             is to be uploaded into the buffer
        *                             object indicating that it's likely
        *                             not going to be modified and
        *                             rendered many times.
        *                             Alternatively, it indicates the
        *                             mesh data will be updated
        *                             frequently and remain in script
        *                             accessible memory
        *
        **/
        public Mesh create(boolean uploadToBufferObject) {
            Element.Builder b = new Element.Builder(mRS);
            b.add(Element.createVector(mRS,
                                       Element.DataType.FLOAT_32,
                                       mVtxSize), "position");
            if ((mFlags & COLOR) != 0) {
                b.add(Element.F32_4(mRS), "color");
            }
            if ((mFlags & TEXTURE_0) != 0) {
                b.add(Element.F32_2(mRS), "texture0");
            }
            if ((mFlags & NORMAL) != 0) {
                b.add(Element.F32_3(mRS), "normal");
            }
            mElement = b.create();

            int usage = Allocation.USAGE_SCRIPT;
            if (uploadToBufferObject) {
                usage |= Allocation.USAGE_GRAPHICS_VERTEX;
            }

            Builder smb = new Builder(mRS, usage);
            smb.addVertexType(mElement, mMaxIndex);
            smb.addIndexSetType(Element.U16(mRS), mIndexCount, Primitive.TRIANGLE);

            Mesh sm = smb.create();

            sm.getVertexAllocation(0).copy1DRangeFromUnchecked(0, mMaxIndex, mVtxData);
            if(uploadToBufferObject) {
                if (uploadToBufferObject) {
                    sm.getVertexAllocation(0).syncAll(Allocation.USAGE_SCRIPT);
                }
            }

            sm.getIndexSetAllocation(0).copy1DRangeFromUnchecked(0, mIndexCount, mIndexData);
            if (uploadToBufferObject) {
                sm.getIndexSetAllocation(0).syncAll(Allocation.USAGE_SCRIPT);
            }

            return sm;
        }
    }
}

