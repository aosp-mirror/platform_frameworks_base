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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Size;
import android.annotation.SuppressLint;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class responsible for holding specifications for {@link Mesh} creations. This class generates a
 * {@link MeshSpecification} via the
 * {@link MeshSpecification#make(Attribute[], int, Varying[], String, String)} method,
 * where multiple parameters to set up the mesh are supplied, including attributes, vertex stride,
 * {@link Varying}, and vertex/fragment shaders. There are also additional methods to provide an
 * optional {@link ColorSpace} as well as an alpha type.
 *
 * For example a vertex shader that leverages a {@link Varying} may look like the following:
 *
 * <pre>
 *        Varyings main(const Attributes attributes) {
 *             Varyings varyings;
 *             varyings.position = attributes.position;
 *             return varyings;
 *        }
 * </pre>
 *
 * The corresponding fragment shader that may consume the varying look like the following:
 *
 * <pre>
 *      float2 main(const Varyings varyings, out float4 color) {
 *             color = vec4(1.0, 0.0, 0.0, 1.0);
 *             return varyings.position;
 *      }
 * </pre>
 *
 * The color returned from this fragment shader is blended with the other parameters that are
 * configured on the Paint object (ex. {@link Paint#setBlendMode(BlendMode)} used to draw the mesh.
 *
 * The position returned in the fragment shader can be consumed by any following fragment shaders in
 * the shader chain.
 *
 * See https://developer.android.com/develop/ui/views/graphics/agsl for more information
 * regarding Android Graphics Shader Language.
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
     * Constants for {@link #make(Attribute[], int, Varying[], String, String)}
     * to determine alpha type. Describes how to interpret the alpha component of a pixel.
     *
     * @hide
     */
    @IntDef(
        prefix = {"ALPHA_TYPE_"},
        value = {ALPHA_TYPE_UNKNOWN, ALPHA_TYPE_OPAQUE, ALPHA_TYPE_PREMULTIPLIED,
                ALPHA_TYPE_UNPREMULTIPLIED}
    )
    @Retention(RetentionPolicy.SOURCE)
    private @interface AlphaType {}

    /**
     * uninitialized.
     */
    public static final int ALPHA_TYPE_UNKNOWN = 0;

    /**
     * Pixel is opaque.
     */
    public static final int ALPHA_TYPE_OPAQUE = 1;

    /**
     * Pixel components are premultiplied by alpha.
     */
    public static final int ALPHA_TYPE_PREMULTIPLIED = 2;

    /**
     * Pixel components are independent of alpha.
     */
    public static final int ALPHA_TYPE_UNPREMULTIPLIED = 3;

    /**
     * Constants for {@link Attribute} and {@link Varying} for determining the data type.
     *
     * @hide
     */
    @IntDef(
        prefix = {"TYPE_"},
        value = {TYPE_FLOAT, TYPE_FLOAT2, TYPE_FLOAT3, TYPE_FLOAT4, TYPE_UBYTE4}
    )
    @Retention(RetentionPolicy.SOURCE)
    private @interface Type {}

    /**
     * Represents one float. Its equivalent shader type is float.
     */
    public static final int TYPE_FLOAT = 0;

    /**
     * Represents two floats. Its equivalent shader type is float2.
     */
    public static final int TYPE_FLOAT2 = 1;

    /**
     * Represents three floats. Its equivalent shader type is float3.
     */
    public static final int TYPE_FLOAT3 = 2;

    /**
     * Represents four floats. Its equivalent shader type is float4.
     */
    public static final int TYPE_FLOAT4 = 3;

    /**
     * Represents four bytes. Its equivalent shader type is half4.
     */
    public static final int TYPE_UBYTE4 = 4;

    /**
     * Data class to represent a single attribute in a shader. An attribute is a variable that
     * accompanies a vertex, this can be a color or texture coordinates.
     *
     * See https://developer.android.com/develop/ui/views/graphics/agsl for more information
     * regarding Android Graphics Shader Language.
     *
     * Note that offset is the offset in number of bytes. For example, if we had two attributes
     *
     * <pre>
     * Float3 att1
     * Float att2
     * </pre>
     *
     * att1 would have an offset of 0, while att2 would have an offset of 12 bytes.
     *
     * This is consumed as part of
     * {@link MeshSpecification#make(Attribute[], int, Varying[], String, String, ColorSpace, int)}
     * to create a {@link MeshSpecification} instance.
     */
    public static class Attribute {
        @Type
        private final int mType;
        private final int mOffset;
        private final String mName;

        public Attribute(@Type int type, int offset, @NonNull String name) {
            mType = type;
            mOffset = offset;
            mName = name;
        }

        /**
         * Return the corresponding data type for this {@link Attribute}.
         */
        @Type
        public int getType() {
            return mType;
        }

        /**
         * Return the offset of the attribute in bytes
         */
        public int getOffset() {
            return mOffset;
        }

        /**
         * Return the name of this {@link Attribute}
         */
        @NonNull
        public String getName() {
            return mName;
        }

        @Override
        public String toString() {
            return "Attribute{"
                    + "mType=" + mType
                    + ", mOffset=" + mOffset
                    + ", mName='" + mName + '\''
                    + '}';
        }
    }

    /**
     * Data class to represent a single varying variable. A Varying variable can be altered by the
     * vertex shader defined on the mesh but not by the fragment shader defined by AGSL.
     *
     * See https://developer.android.com/develop/ui/views/graphics/agsl for more information
     * regarding Android Graphics Shader Language.
     *
     * This is consumed as part of
     * {@link MeshSpecification#make(Attribute[], int, Varying[], String, String, ColorSpace, int)}
     * to create a {@link MeshSpecification} instance.
     */
    public static class Varying {
        @Type
        private final int mType;
        private final String mName;

        public Varying(@Type int type, @NonNull String name) {
            mType = type;
            mName = name;
        }

        /**
         * Return the corresponding data type for this {@link Varying}.
         */
        @Type
        public int getType() {
            return mType;
        }

        /**
         * Return the name of this {@link Varying}
         */
        @NonNull
        public String getName() {
            return mName;
        }

        @Override
        public String toString() {
            return "Varying{"
                    + "mType=" + mType
                    + ", mName='" + mName + '\''
                    + '}';
        }
    }

    private static class MeshSpecificationHolder {
        public static final NativeAllocationRegistry MESH_SPECIFICATION_REGISTRY =
                NativeAllocationRegistry.createMalloced(
                        MeshSpecification.class.getClassLoader(), nativeGetFinalizer());
    }

    /**
     * Creates a {@link MeshSpecification} object for use within {@link Mesh}. This uses a default
     * color space of {@link ColorSpace.Named#SRGB} and alphaType of
     * {@link #ALPHA_TYPE_PREMULTIPLIED}.
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
     *                       See {@link MeshSpecification} for an example vertex shader
     *                       implementation
     * @param fragmentShader fragment shader to be supplied to the mesh.
     *                       See {@link MeshSpecification} for an example fragment shader
     *                       implementation
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    @NonNull
    public static MeshSpecification make(
            @SuppressLint("ArrayReturn") @NonNull @Size(max = 8) Attribute[] attributes,
            @IntRange(from = 1, to = 1024) int vertexStride,
            @SuppressLint("ArrayReturn") @NonNull @Size(max = 6) Varying[] varyings,
            @NonNull String vertexShader,
            @NonNull String fragmentShader) {
        long nativeMeshSpec = nativeMake(attributes,
                vertexStride, varyings, vertexShader,
                fragmentShader);
        if (nativeMeshSpec == 0) {
            throw new IllegalArgumentException("MeshSpecification construction failed");
        }
        return new MeshSpecification(nativeMeshSpec);
    }

    /**
     * Creates a {@link MeshSpecification} object.  This uses a default alphaType of
     * {@link #ALPHA_TYPE_PREMULTIPLIED}.
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
     *                       See {@link MeshSpecification} for an example vertex shader
     *                       implementation
     * @param fragmentShader fragment shader to be supplied to the mesh.
     *                       See {@link MeshSpecification} for an example fragment shader
     *                       implementation
     * @param colorSpace     {@link ColorSpace} to tell what color space to work in.
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    @NonNull
    public static MeshSpecification make(
            @SuppressLint("ArrayReturn") @NonNull @Size(max = 8) Attribute[] attributes,
            @IntRange(from = 1, to = 1024) int vertexStride,
            @SuppressLint("ArrayReturn") @NonNull @Size(max = 6) Varying[] varyings,
            @NonNull String vertexShader,
            @NonNull String fragmentShader,
            @NonNull ColorSpace colorSpace
    ) {
        long nativeMeshSpec = nativeMakeWithCS(attributes,
                vertexStride, varyings, vertexShader,
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
     *                       See {@link MeshSpecification} for an example vertex shader
     *                       implementation
     * @param fragmentShader fragment shader to be supplied to the mesh.
     *                       See {@link MeshSpecification} for an example fragment shader
     *                       implementation
     * @param colorSpace     {@link ColorSpace} to tell what color space to work in.
     * @param alphaType      Describes how to interpret the alpha component for a pixel. Must be
     *                       one of
     *                       {@link MeshSpecification#ALPHA_TYPE_UNKNOWN},
     *                       {@link MeshSpecification#ALPHA_TYPE_OPAQUE},
     *                       {@link MeshSpecification#ALPHA_TYPE_PREMULTIPLIED}, or
     *                       {@link MeshSpecification#ALPHA_TYPE_UNPREMULTIPLIED}
     * @return {@link MeshSpecification} object for use when creating {@link Mesh}
     */
    @NonNull
    public static MeshSpecification make(
            @SuppressLint("ArrayReturn") @NonNull @Size(max = 8) Attribute[] attributes,
            @IntRange(from = 1, to = 1024) int vertexStride,
            @SuppressLint("ArrayReturn") @NonNull @Size(max = 6) Varying[] varyings,
            @NonNull String vertexShader,
            @NonNull String fragmentShader,
            @NonNull ColorSpace colorSpace,
            @AlphaType int alphaType) {
        long nativeMeshSpec =
                nativeMakeWithAlpha(attributes, vertexStride, varyings, vertexShader,
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
