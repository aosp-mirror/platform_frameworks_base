/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.graphics;

import android.annotation.IntDef;

import libcore.util.NativeAllocationRegistry;

/**
 * Class responsible for holding specifications for {@link Mesh} creations. This class
 * generates a {@link MeshSpecification} via the Make method, where multiple parameters to set up
 * the mesh are supplied, including attributes, vertex stride, varyings, and
 * vertex/fragment shaders. There are also additional methods to provide an optional
 * {@link ColorSpace} as well as an alpha type.
 *
 * Note that there are several limitations on various mesh specifications:
 * 1. The max amount of attributes allowed is 8.
 * 2. The offset alignment length is 4 bytes.
 * 2. The max stride length is 1024.
 * 3. The max amount of varyings is 6.
 *
 * These should be kept in mind when generating a mesh specification, as exceeding them will
 * lead to errors.
 *
 * @hide
 */
public class MeshSpecification {
    long mNativeMeshSpec;

    /**
     * Constants for {@link #make(Attribute[], int, Varying[], String, String, ColorSpace, int)}
     * to determine alpha type
     */
    @IntDef({UNKNOWN, OPAQUE, PREMUL, UNPREMULT})
    public @interface AlphaType {
    }

    public static final int UNKNOWN = 0;
    public static final int OPAQUE = 1;
    public static final int PREMUL = 2;
    public static final int UNPREMULT = 3;

    /**
     * Constants for {@link Attribute} and {@link Varying} for determining the data type.
     */
    @IntDef({FLOAT, FLOAT2, FLOAT3, FLOAT4, UBYTE4})
    public @interface Type {
    }

    public static final int FLOAT = 0;
    public static final int FLOAT2 = 1;
    public static final int FLOAT3 = 2;
    public static final int FLOAT4 = 3;
    public static final int UBYTE4 = 4;

    /**
     * Data class to represent a single attribute in a shader. Note that type parameter must be
     * one of {@link #FLOAT}, {@link #FLOAT2}, {@link #FLOAT3}, {@link #FLOAT4}, or {@link #UBYTE4}.
     */
    public static class Attribute {
        @Type
        private int mType;
        private int mOffset;
        private String mName;

        public Attribute(@Type int type, int offset, String name) {
            mType = type;
            mOffset = offset;
            mName = name;
        }
    }

    /**
     * Data class to represent a single varying variable. Note that type parameter must be
     * one of {@link #FLOAT}, {@link #FLOAT2}, {@link #FLOAT3}, {@link #FLOAT4}, or {@link #UBYTE4}.
     */
    public static class Varying {
        @Type
        private int mType;
        private String mName;

        public Varying(@Type int type, String name) {
            mType = type;
            mName = name;
        }
    }

    private static class MeshSpecificationHolder {
        public static final NativeAllocationRegistry MESH_SPECIFICATION_REGISTRY =
                NativeAllocationRegistry.createMalloced(
                        MeshSpecification.class.getClassLoader(), nativeGetFinalizer());
    }

    /**
     * Creates a {@link MeshSpecification} object.
     *
     * @param attributes     list of attributes represented by {@link Attribute}. Can hold a max of
     *                       8.
     * @param vertexStride   length of vertex stride. Max of 1024 is accepted.
     * @param varyings       List of varyings represented by {@link Varying}. Can hold a max of 6.
     * @param vertexShader   vertex shader to be supplied to the mesh.
     * @param fragmentShader fragment shader to be suppied to the mesh.
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    public static MeshSpecification make(Attribute[] attributes, int vertexStride,
            Varying[] varyings, String vertexShader, String fragmentShader) {
        long nativeMeshSpec =
                nativeMake(attributes, vertexStride, varyings, vertexShader, fragmentShader);
        if (nativeMeshSpec == 0) {
            throw new IllegalArgumentException("MeshSpecification construction failed");
        }
        return new MeshSpecification(nativeMeshSpec);
    }

    /**
     * Creates a {@link MeshSpecification} object.
     *
     * @param attributes     list of attributes represented by {@link Attribute}. Can hold a max of
     *                       8.
     * @param vertexStride   length of vertex stride. Max of 1024 is accepted.
     * @param varyings       List of varyings represented by {@link Varying}. Can hold a max of
     *                       6.
     * @param vertexShader   vertex shader to be supplied to the mesh.
     * @param fragmentShader fragment shader to be supplied to the mesh.
     * @param colorSpace     {@link ColorSpace} to tell what color space to work in.
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    public static MeshSpecification make(Attribute[] attributes, int vertexStride,
            Varying[] varyings, String vertexShader, String fragmentShader, ColorSpace colorSpace) {
        long nativeMeshSpec = nativeMakeWithCS(attributes, vertexStride, varyings, vertexShader,
                fragmentShader, colorSpace.getNativeInstance());
        if (nativeMeshSpec == 0) {
            throw new IllegalArgumentException("MeshSpecification construction failed");
        }
        return new MeshSpecification(nativeMeshSpec);
    }

    /**
     * Creates a {@link MeshSpecification} object.
     *
     * @param attributes     list of attributes represented by {@link Attribute}. Can hold a max of
     *                       8.
     * @param vertexStride   length of vertex stride. Max of 1024 is accepted.
     * @param varyings       List of varyings represented by {@link Varying}. Can hold a max of 6.
     * @param vertexShader   vertex shader code to be supplied to the mesh.
     * @param fragmentShader fragment shader code to be suppied to the mesh.
     * @param colorSpace     {@link ColorSpace} to tell what color space to work in.
     * @param alphaType      Describes how to interpret the alpha component for a pixel. Must be
     *                       one of {@link AlphaType} values.
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    public static MeshSpecification make(Attribute[] attributes, int vertexStride,
            Varying[] varyings, String vertexShader, String fragmentShader, ColorSpace colorSpace,
            @AlphaType int alphaType) {
        long nativeMeshSpec = nativeMakeWithAlpha(attributes, vertexStride, varyings, vertexShader,
                fragmentShader, colorSpace.getNativeInstance(), alphaType);
        if (nativeMeshSpec == 0) {
            throw new IllegalArgumentException("MeshSpecification construction failed");
        }
        return new MeshSpecification(nativeMeshSpec);
    }

    private MeshSpecification(long meshSpec) {
        mNativeMeshSpec = meshSpec;
        MeshSpecificationHolder.MESH_SPECIFICATION_REGISTRY.registerNativeAllocation(
                this, meshSpec);
    }

    private static native long nativeGetFinalizer();

    private static native long nativeMake(Attribute[] attributes, int vertexStride,
            Varying[] varyings, String vertexShader, String fragmentShader);

    private static native long nativeMakeWithCS(Attribute[] attributes, int vertexStride,
            Varying[] varyings, String vertexShader, String fragmentShader, long colorSpace);

    private static native long nativeMakeWithAlpha(Attribute[] attributes, int vertexStride,
            Varying[] varyings, String vertexShader, String fragmentShader, long colorSpace,
            int alphaType);
}
