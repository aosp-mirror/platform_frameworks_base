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
import android.annotation.NonNull;

import libcore.util.NativeAllocationRegistry;

import java.util.List;

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
 */
public class MeshSpecification {
    long mNativeMeshSpec;

    /**
     * Constants for {@link #make(List, int, List, String, String)}
     * to determine alpha type. Describes how to interpret the alpha component of a pixel.
     */
    @IntDef({UNKNOWN, OPAQUE, PREMUL, UNPREMULT})
    private @interface AlphaType {}

    /**
     * uninitialized.
     */
    public static final int UNKNOWN = 0;

    /**
     * Pixel is opaque.
     */
    public static final int OPAQUE = 1;

    /**
     * Pixel components are premultiplied by alpha.
     */
    public static final int PREMUL = 2;

    /**
     * Pixel components are independent of alpha.
     */
    public static final int UNPREMULT = 3;

    /**
     * Constants for {@link Attribute} and {@link Varying} for determining the data type.
     */
    @IntDef({FLOAT, FLOAT2, FLOAT3, FLOAT4, UBYTE4})
    private @interface Type {}

    /**
     * Represents one float. Its equivalent shader type is float.
     */
    public static final int FLOAT = 0;

    /**
     * Represents two floats. Its equivalent shader type is float2.
     */
    public static final int FLOAT2 = 1;

    /**
     * Represents three floats. Its equivalent shader type is float3.
     */
    public static final int FLOAT3 = 2;

    /**
     * Represents four floats. Its equivalent shader type is float4.
     */
    public static final int FLOAT4 = 3;

    /**
     * Represents four bytes. Its equivalent shader type is half4.
     */
    public static final int UBYTE4 = 4;

    /**
     * Data class to represent a single attribute in a shader. Note that type parameter must be
     * one of {@link #FLOAT}, {@link #FLOAT2}, {@link #FLOAT3}, {@link #FLOAT4}, or {@link #UBYTE4}.
     *
     * Note that offset is the offset in number of bytes. For example, if we had two attributes
     *
     * Float3 att1
     * Float att2
     *
     * att1 would have an offset of 0, while att2 would have an offset of 12 bytes.
     */
    public static class Attribute {
        @Type
        private int mType;
        private int mOffset;
        private String mName;

        public Attribute(@Type int type, int offset, @NonNull String name) {
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

        public Varying(@Type int type, @NonNull String name) {
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
     * Creates a {@link MeshSpecification} object for use within {@link Mesh}.
     *
     * @param attributes     list of attributes represented by {@link Attribute}. Can hold a max of
     *                       8.
     * @param vertexStride   length of vertex stride in bytes. This should be the size of a single
     *                       vertex' attributes. Max of 1024 is accepted.
     * @param varyings       List of varyings represented by {@link Varying}. Can hold a max of 6.
     *                       Note that `position` is provided by default, does not need to be
     *                       provided in the list, and does not count towards
     *                       the 6 varyings allowed.
     * @param vertexShader   vertex shader to be supplied to the mesh. Ensure that the position
     *                       varying is set within the shader to get proper results.
     * @param fragmentShader fragment shader to be supplied to the mesh.
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    @NonNull
    public static MeshSpecification make(@NonNull List<Attribute> attributes, int vertexStride,
            @NonNull List<Varying> varyings, @NonNull String vertexShader,
            @NonNull String fragmentShader) {
        long nativeMeshSpec = nativeMake(attributes.toArray(new Attribute[attributes.size()]),
                vertexStride, varyings.toArray(new Varying[varyings.size()]), vertexShader,
                fragmentShader);
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
     * @param vertexStride   length of vertex stride in bytes. This should be the size of a single
     *                       vertex' attributes. Max of 1024 is accepted.
     * @param varyings       List of varyings represented by {@link Varying}. Can hold a max of 6.
     *                       Note that `position` is provided by default, does not need to be
     *                       provided in the list, and does not count towards
     *                       the 6 varyings allowed.
     * @param vertexShader   vertex shader to be supplied to the mesh. Ensure that the position
     *                       varying is set within the shader to get proper results.
     * @param fragmentShader fragment shader to be supplied to the mesh.
     * @param colorSpace     {@link ColorSpace} to tell what color space to work in.
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    @NonNull
    public static MeshSpecification make(@NonNull List<Attribute> attributes, int vertexStride,
            @NonNull List<Varying> varyings, @NonNull String vertexShader,
            @NonNull String fragmentShader, @NonNull ColorSpace colorSpace) {
        long nativeMeshSpec = nativeMakeWithCS(attributes.toArray(new Attribute[attributes.size()]),
                vertexStride, varyings.toArray(new Varying[varyings.size()]), vertexShader,
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
     * @param vertexStride   length of vertex stride in bytes. This should be the size of a single
     *                       vertex' attributes. Max of 1024 is accepted.
     * @param varyings       List of varyings represented by {@link Varying}. Can hold a max of 6.
     *                       Note that `position` is provided by default, does not need to be
     *                       provided in the list, and does not count towards
     *                       the 6 varyings allowed.
     * @param vertexShader   vertex shader to be supplied to the mesh. Ensure that the position
     *                       varying is set within the shader to get proper results.
     * @param fragmentShader fragment shader to be supplied to the mesh.
     * @param colorSpace     {@link ColorSpace} to tell what color space to work in.
     * @param alphaType      Describes how to interpret the alpha component for a pixel. Must be
     *                       one of
     *                       {@link MeshSpecification#UNKNOWN},
     *                       {@link MeshSpecification#OPAQUE},
     *                       {@link MeshSpecification#PREMUL}, or
     *                       {@link MeshSpecification#UNPREMULT}
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    @NonNull
    public static MeshSpecification make(@NonNull List<Attribute> attributes, int vertexStride,
            @NonNull List<Varying> varyings, @NonNull String vertexShader,
            @NonNull String fragmentShader, @NonNull ColorSpace colorSpace,
            @AlphaType int alphaType) {
        long nativeMeshSpec =
                nativeMakeWithAlpha(attributes.toArray(new Attribute[attributes.size()]),
                        vertexStride, varyings.toArray(new Varying[varyings.size()]), vertexShader,
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
