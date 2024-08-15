/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Operation to deal with bitmap data
 * On getting an Image during a draw call the bitmap is compressed and saved
 * in playback the image is decompressed
 */
public class ShaderData implements Operation, VariableSupport {
    int mShaderTextId; // the actual text of a shader
    int mShaderID; // allows shaders to be referenced by number
    HashMap<String, float[]> mUniformRawFloatMap = null;
    HashMap<String, float[]> mUniformFloatMap = null;
    HashMap<String, int[]> mUniformIntMap = null;
    HashMap<String, Integer> mUniformBitmapMap = null;

    public static final int MAX_IMAGE_DIMENSION = 8000;

    public static final Companion COMPANION = new Companion();

    public ShaderData(int shaderID,
                      int shaderTextId,
                      HashMap<String, float[]> floatMap,
                      HashMap<String, int[]> intMap,
                      HashMap<String, Integer> bitmapMap) {
        mShaderID = shaderID;
        mShaderTextId = shaderTextId;
        if (floatMap != null) {
            mUniformFloatMap = new HashMap<>();
            mUniformRawFloatMap = new HashMap<>();

            for (String name : floatMap.keySet()) {
                mUniformRawFloatMap.put(name, floatMap.get(name));
                mUniformFloatMap.put(name, floatMap.get(name));
            }
        }

        if (intMap != null) {
            mUniformIntMap = new HashMap<>();
            for (String name : intMap.keySet()) {
                mUniformIntMap.put(name, intMap.get(name));
            }
        }
        if (bitmapMap != null) {
            mUniformBitmapMap = new HashMap<>();
            for (String name : bitmapMap.keySet()) {
                mUniformBitmapMap.put(name, bitmapMap.get(name));
            }
        }

    }

    public int getShaderTextId() {
        return mShaderTextId;
    }

    /**
     * get names of all known floats
     * @return
     */
    public String[] getUniformFloatNames() {
        if (mUniformFloatMap == null) return new String[0];
        return mUniformFloatMap.keySet().toArray(new String[0]);
    }

    /**
     * Get float values associated with the name
     * @param name
     * @return
     */
    public float[] getUniformFloats(String name) {
        return mUniformFloatMap.get(name);
    }

    /**
     * get the name of all know uniform integers
     * @return
     */
    public String[] getUniformIntegerNames() {
        if (mUniformIntMap == null) return new String[0];
        return mUniformIntMap.keySet().toArray(new String[0]);
    }

    /**
     * Get Int value associated with the name
     * @param name
     * @return
     */
    public int[] getUniformInts(String name) {
        return mUniformIntMap.get(name);
    }

    /**
     * get list of uniform Bitmaps
     * @return
     */
    public String[] getUniformBitmapNames() {
        if (mUniformBitmapMap == null) return new String[0];
        return mUniformBitmapMap.keySet().toArray(new String[0]);
    }

    /**
     * Get a bitmap stored under that name
     * @param name
     * @return
     */
    public int getUniformBitmapId(String name) {
        return mUniformBitmapMap.get(name);
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mShaderID, mShaderTextId,
                mUniformFloatMap, mUniformIntMap, mUniformBitmapMap);
    }

    @Override
    public String toString() {
        return "SHADER DATA " + mShaderID;
    }

    @Override
    public void updateVariables(RemoteContext context) {
        for (String name : mUniformRawFloatMap.keySet()) {
            float[] value = mUniformRawFloatMap.get(name);
            float[] out = null;
            for (int i = 0; i < value.length; i++) {
                if (Float.isNaN(value[i])) {
                    if (out == null) { // need to copy
                        out = Arrays.copyOf(value, value.length);
                    }
                    out[i] = context.getFloat(Utils.idFromNan(value[i]));
                }
            }
            mUniformFloatMap.put(name, out == null ? value : out);
        }
    }

    @Override
    public void registerListening(RemoteContext context) {
        for (String name : mUniformRawFloatMap.keySet()) {
            float[] value = mUniformRawFloatMap.get(name);
            for (int i = 0; i < value.length; i++) {
                if (Float.isNaN(value[i])) {
                    context.listensTo(Utils.idFromNan(value[i]), this);
                }
            }
        }
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "BitmapData";
        }

        @Override
        public int id() {
            return Operations.DATA_SHADER;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param shaderID
         * @param shaderTextId
         * @param floatMap
         * @param intMap
         * @param bitmapMap
         */
        public void apply(WireBuffer buffer, int shaderID, int shaderTextId,
                          HashMap<String, float[]> floatMap,
                          HashMap<String, int[]> intMap,
                          HashMap<String, Integer> bitmapMap) {
            buffer.start(Operations.DATA_SHADER);
            buffer.writeInt(shaderID);

            buffer.writeInt(shaderTextId);
            int floatSize = (floatMap == null) ? 0 : floatMap.size();
            int intSize = (intMap == null) ? 0 : intMap.size();
            int bitmapSize = (bitmapMap == null) ? 0 : bitmapMap.size();
            int sizes = floatSize | (intSize << 8) | (bitmapSize << 16);
            buffer.writeInt(sizes);

            if (floatSize > 0) {

                for (String name : floatMap.keySet()) {
                    buffer.writeUTF8(name);
                    float[] values = floatMap.get(name);
                    buffer.writeInt(values.length);

                    for (int i = 0; i < values.length; i++) {
                        buffer.writeFloat(values[i]);
                    }
                }
            }

            if (intSize > 0) {
                for (String name : intMap.keySet()) {
                    buffer.writeUTF8(name);
                    int[] values = intMap.get(name);
                    buffer.writeInt(values.length);
                    for (int i = 0; i < values.length; i++) {
                        buffer.writeInt(values[i]);
                    }
                }
            }
            if (bitmapSize > 0) {
                for (String name : bitmapMap.keySet()) {
                    buffer.writeUTF8(name);
                    int value = bitmapMap.get(name);
                    buffer.writeInt(value);
                }
            }
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int shaderID = buffer.readInt();
            int shaderTextId = buffer.readInt();
            HashMap<String, float[]> floatMap = null;
            HashMap<String, int[]> intMap = null;
            HashMap<String, Integer> bitmapMap = null;

            int sizes = buffer.readInt();

            int floatMapSize = sizes & 0xFF;
            if (floatMapSize > 0) {
                floatMap = new HashMap<>();
                for (int i = 0; i < floatMapSize; i++) {
                    String name = buffer.readUTF8();
                    int len = buffer.readInt();
                    float[] val = new float[len];

                    for (int j = 0; j < len; j++) {
                        val[j] = buffer.readFloat();
                    }

                    floatMap.put(name, val);
                }
            }
            int intMapSize = (sizes >> 8) & 0xFF;

            if (intMapSize > 0) {

                intMap = new HashMap<>();
                for (int i = 0; i < intMapSize; i++) {
                    String name = buffer.readUTF8();
                    int len = buffer.readInt();
                    int[] val = new int[len];
                    for (int j = 0; j < len; j++) {
                        val[j] = buffer.readInt();
                    }
                    intMap.put(name, val);
                }
            }
            int bitmapMapSize = (sizes >> 16) & 0xFF;

            if (bitmapMapSize > 0) {
                bitmapMap = new HashMap<>();
                for (int i = 0; i < bitmapMapSize; i++) {
                    String name = buffer.readUTF8();
                    int val = buffer.readInt();
                    bitmapMap.put(name, val);
                }
            }
            operations.add(new ShaderData(shaderID, shaderTextId,
                    floatMap, intMap, bitmapMap));
        }
    }

    @Override
    public void apply(RemoteContext context) {
        context.loadShader(mShaderID, this);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
