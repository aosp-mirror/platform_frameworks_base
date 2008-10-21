/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.opengl;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.microedition.khronos.opengles.*;

/**
 * {@hide}
 */
public abstract class Object3D {

    private boolean mHasTexcoords = false;

    private float mBoundsMinX = Float.MAX_VALUE;
    private float mBoundsMaxX = Float.MIN_VALUE;
    private float mBoundsMinY = Float.MAX_VALUE;
    private float mBoundsMaxY = Float.MIN_VALUE;
    private float mBoundsMinZ = Float.MAX_VALUE;
    private float mBoundsMaxZ = Float.MIN_VALUE;

    private IntBuffer mVertexBuffer;
    private IntBuffer mNormalBuffer;
    private IntBuffer mTexcoordBuffer;

    // All groups, by name
    private Map<String, Group> mGroups;

    // All materials, by name
    private Map<String, Material> mMaterials;

    // All texture maps, by name
    private Map<String, Texture> mTextures;

    public Object3D() {
        reset();
    }

    /**
     * Override this method with an implementation that contructs
     * and InputStream from the given filename.  For example, if the
     * source files are to be retrieved using an AssetManager,
     * the implementation would use AssetManager.load() to
     * get the input stream.
     */
    public abstract InputStream readFile(String filename) throws IOException;

    private void reset() {
        mVertexBuffer = mNormalBuffer = mTexcoordBuffer = null;

        mGroups = new HashMap<String,Group>();
        mMaterials = new HashMap<String,Material>();
        mTextures = new HashMap<String,Texture>();
    }

    public Material getMaterial(String name) {
        Material mat = mMaterials.get(name);
        return mat;
    }

    public Texture getTexture(String name) {
        return mTextures.get(name);
    }

    public IntBuffer getVertexBuffer() {
        return mVertexBuffer;
    }

    public IntBuffer getNormalBuffer() {
        return mNormalBuffer;
    }

    public IntBuffer getTexcoordBuffer() {
        return mTexcoordBuffer;
    }

    public int getNumTriangles() {
        int numTriangles = 0;
        Iterator<Group> iter = mGroups.values().iterator();
        while (iter.hasNext()) {
            numTriangles += iter.next().getNumTriangles();
        }
        return numTriangles;
    }

    public boolean hasTexcoords() {
        return mHasTexcoords;
    }

    public float getBoundsMinX() {
        return mBoundsMinX;
    }

    public float getBoundsMaxX() {
        return mBoundsMaxX;
    }

    public float getBoundsMinY() {
        return mBoundsMinY;
    }

    public float getBoundsMaxY() {
        return mBoundsMaxY;
    }

    public float getBoundsMinZ() {
        return mBoundsMinZ;
    }

    public float getBoundsMaxZ() {
        return mBoundsMaxZ;
    }

    public void loadTexture(String name) throws IOException {
        InputStream is = readFile(name + ".raw");
        Texture texture = new Texture(is);
        mTextures.put(name, texture);
    }

    private static void verifyByte(DataInputStream dis, int b) 
    throws IOException {
        int x = dis.read() & 0xff;
        if (x != b) {
            throw new RuntimeException("Bad byte: " +
                    x +
                    " (expected " + b + ")");
        }
    }

    public void load(String filename) throws IOException {
        reset();

        DataInputStream dis = new DataInputStream(readFile(filename));
        verifyByte(dis, 'g' + 128);
        verifyByte(dis, 'l');
        verifyByte(dis, 'e');
        verifyByte(dis, 's');

        int numTuples = dis.readInt();

        this.mBoundsMinX = dis.readFloat();
        this.mBoundsMaxX = dis.readFloat();
        this.mBoundsMinY = dis.readFloat();
        this.mBoundsMaxY = dis.readFloat();
        this.mBoundsMinZ = dis.readFloat();
        this.mBoundsMaxZ = dis.readFloat();

        this.mHasTexcoords = dis.readInt() == 1;

        int intsPerTuple = mHasTexcoords ? 8 : 6;
        int numInts = numTuples*intsPerTuple;

        int len = 4*numTuples*(mHasTexcoords ? 8 : 6);

        byte[] tmp = new byte[len];
        int tidx = 0;
        while (tidx < len) {
            tidx += dis.read(tmp, tidx, len - tidx);
        }
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            for (int i = 0; i < len; i += 4) {
                byte tmp0 = tmp[i];
                byte tmp1 = tmp[i + 1];
                byte tmp2 = tmp[i + 2];
                byte tmp3 = tmp[i + 3];
                tmp[i] = tmp3;
                tmp[i + 1] = tmp2;
                tmp[i + 2] = tmp1;
                tmp[i + 3] = tmp0;
            }
        }

        ByteBuffer allbb = ByteBuffer.allocateDirect(len);
        allbb.order(ByteOrder.nativeOrder());
        allbb.put(tmp);

        allbb.position(0);
        allbb.limit(4*3*numTuples);
        ByteBuffer vbb = allbb.slice();
        this.mVertexBuffer = vbb.asIntBuffer();
        mVertexBuffer.position(0);

        if (mHasTexcoords) {
            allbb.position(allbb.limit());
            allbb.limit(allbb.position() + 4*2*numTuples);
            ByteBuffer tbb = allbb.slice();
            this.mTexcoordBuffer = tbb.asIntBuffer();
            mTexcoordBuffer.position(0);
        }

        allbb.position(allbb.limit());
        allbb.limit(allbb.position() + 4*3*numTuples);
        ByteBuffer nbb = allbb.slice();
        this.mNormalBuffer = nbb.asIntBuffer();
        mNormalBuffer.position(0);

        int numMaterials = dis.readInt();
        for (int i = 0; i < numMaterials; i++) {
            Material mat = new Material(this);
            mat.load(dis);
            mMaterials.put(mat.getName(), mat);
        }

        int numGroups = dis.readInt();
        for (int i = 0; i < numGroups; i++) {
            Group g = new Group(this);
            g.load(dis);
            mGroups.put(g.getName(), g);
        }
    }

    public void draw(GL10 gl) {
        Iterator<Group> iter = mGroups.values().iterator();
        while (iter.hasNext()) {
            iter.next().draw(gl);
        }
    }
}

