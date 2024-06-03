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

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.IntDef;
import android.annotation.NonNull;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.Buffer;
import java.nio.ShortBuffer;

/**
 * Class representing a mesh object.
 *
 * This class represents a Mesh object that can optionally be indexed.
 * A {@link MeshSpecification} is required along with various attributes for
 * detailing the mesh object, including a mode, vertex buffer, optional index buffer, and bounds
 * for the mesh. Once generated, a mesh object can be drawn through
 * {@link Canvas#drawMesh(Mesh, BlendMode, Paint)}
 */
public class Mesh {
    private long mNativeMeshWrapper;
    private boolean mIsIndexed;

    /**
     * Determines how the mesh is represented and will be drawn.
     */
    @IntDef({TRIANGLES, TRIANGLE_STRIP})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Mode {}

    /**
     * The mesh will be drawn with triangles without utilizing shared vertices.
     */
    public static final int TRIANGLES = 0;

    /**
     * The mesh will be drawn with triangles utilizing shared vertices.
     */
    public static final int TRIANGLE_STRIP = 1;

    private static class MeshHolder {
        public static final NativeAllocationRegistry MESH_SPECIFICATION_REGISTRY =
                NativeAllocationRegistry.createMalloced(
                        MeshSpecification.class.getClassLoader(), nativeGetFinalizer());
    }

    /**
     * Constructor for a non-indexed Mesh.
     *
     * @param meshSpec     {@link MeshSpecification} used when generating the mesh.
     * @param mode         Determines what mode to draw the mesh in. Must be one of
     *                     {@link Mesh#TRIANGLES} or {@link Mesh#TRIANGLE_STRIP}
     * @param vertexBuffer vertex buffer representing through {@link Buffer}. This provides the data
     *                     for all attributes provided within the meshSpec for every vertex. That
     *                     is, a vertex buffer should be (attributes size * number of vertices) in
     *                     length to be valid. Note that currently implementation will have a CPU
     *                     backed buffer generated.
     * @param vertexCount  the number of vertices represented in the vertexBuffer and mesh.
     * @param bounds       bounds of the mesh object.
     */
    public Mesh(@NonNull MeshSpecification meshSpec, @Mode int mode,
            @NonNull Buffer vertexBuffer, int vertexCount, @NonNull RectF bounds) {
        if (mode != TRIANGLES && mode != TRIANGLE_STRIP) {
            throw new IllegalArgumentException("Invalid value passed in for mode parameter");
        }
        long nativeMesh = nativeMake(meshSpec.mNativeMeshSpec, mode, vertexBuffer,
                vertexBuffer.isDirect(), vertexCount, vertexBuffer.position(), bounds.left,
                bounds.top, bounds.right, bounds.bottom);
        if (nativeMesh == 0) {
            throw new IllegalArgumentException("Mesh construction failed.");
        }

        meshSetup(nativeMesh, false);
    }

    /**
     * Constructor for an indexed Mesh.
     *
     * @param meshSpec     {@link MeshSpecification} used when generating the mesh.
     * @param mode         Determines what mode to draw the mesh in. Must be one of
     *                     {@link Mesh#TRIANGLES} or {@link Mesh#TRIANGLE_STRIP}
     * @param vertexBuffer vertex buffer representing through {@link Buffer}. This provides the data
     *                     for all attributes provided within the meshSpec for every vertex. That
     *                     is, a vertex buffer should be (attributes size * number of vertices) in
     *                     length to be valid. Note that currently implementation will have a CPU
     *                     backed buffer generated.
     * @param vertexCount  the number of vertices represented in the vertexBuffer and mesh.
     * @param indexBuffer  index buffer representing through {@link ShortBuffer}. Indices are
     *                     required to be 16 bits, so ShortBuffer is necessary. Note that
     *                     currently implementation will have a CPU
     *                     backed buffer generated.
     * @param bounds       bounds of the mesh object.
     */
    public Mesh(@NonNull MeshSpecification meshSpec, @Mode int mode,
            @NonNull Buffer vertexBuffer, int vertexCount, @NonNull ShortBuffer indexBuffer,
            @NonNull RectF bounds) {
        if (mode != TRIANGLES && mode != TRIANGLE_STRIP) {
            throw new IllegalArgumentException("Invalid value passed in for mode parameter");
        }
        long nativeMesh = nativeMakeIndexed(meshSpec.mNativeMeshSpec, mode, vertexBuffer,
                vertexBuffer.isDirect(), vertexCount, vertexBuffer.position(), indexBuffer,
                indexBuffer.isDirect(), indexBuffer.capacity(), indexBuffer.position(), bounds.left,
                bounds.top, bounds.right, bounds.bottom);
        if (nativeMesh == 0) {
            throw new IllegalArgumentException("Mesh construction failed.");
        }

        meshSetup(nativeMesh, true);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh. If the shader
     * does not have a uniform with that name or if the uniform is declared with a type other than
     * vec3 or vec4 and corresponding layout(color) annotation then an IllegalArgumentExcepton is
     * thrown.
     *
     * @param uniformName name matching the color uniform declared in the shader program.
     * @param color       the provided sRGB color will be converted into the shader program's output
     *                    colorspace and be available as a vec4 uniform in the program.
     */
    public void setColorUniform(@NonNull String uniformName, @ColorInt int color) {
        setUniform(uniformName, Color.valueOf(color).getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh. If the shader
     * does not have a uniform with that name or if the uniform is declared with a type other than
     * vec3 or vec4 and corresponding layout(color) annotation then an IllegalArgumentExcepton is
     * thrown.
     *
     * @param uniformName name matching the color uniform declared in the shader program.
     * @param color       the provided sRGB color will be converted into the shader program's output
     *                    colorspace and be available as a vec4 uniform in the program.
     */
    public void setColorUniform(@NonNull String uniformName, @ColorLong long color) {
        Color exSRGB = Color.valueOf(color).convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh. If the shader
     * does not have a uniform with that name or if the uniform is declared with a type other than
     * vec3 or vec4 and corresponding layout(color) annotation then an IllegalArgumentExcepton is
     * thrown.
     *
     * @param uniformName name matching the color uniform declared in the shader program.
     * @param color       the provided sRGB color will be converted into the shader program's output
     *                    colorspace and will be made available as a vec4 uniform in the program.
     */
    public void setColorUniform(@NonNull String uniformName, @NonNull Color color) {
        if (color == null) {
            throw new NullPointerException("The color parameter must not be null");
        }

        Color exSRGB = color.convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than a
     * float or float[1] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value       float value corresponding to the float uniform with the given name.
     */
    public void setFloatUniform(@NonNull String uniformName, float value) {
        setFloatUniform(uniformName, value, 0.0f, 0.0f, 0.0f, 1);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than a
     * vec2 or float[2] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value1      first float value corresponding to the float uniform with the given name.
     * @param value2      second float value corresponding to the float uniform with the given name.
     */
    public void setFloatUniform(@NonNull String uniformName, float value1, float value2) {
        setFloatUniform(uniformName, value1, value2, 0.0f, 0.0f, 2);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than a
     * vec3 or float[3] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value1      first float value corresponding to the float uniform with the given name.
     * @param value2      second float value corresponding to the float uniform with the given name.
     * @param value3      third float value corresponding to the float unifiform with the given
     *                    name.
     */
    public void setFloatUniform(
            @NonNull String uniformName, float value1, float value2, float value3) {
        setFloatUniform(uniformName, value1, value2, value3, 0.0f, 3);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than a
     * vec4 or float[4] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value1      first float value corresponding to the float uniform with the given name.
     * @param value2      second float value corresponding to the float uniform with the given name.
     * @param value3      third float value corresponding to the float uniform with the given name.
     * @param value4      fourth float value corresponding to the float uniform with the given name.
     */
    public void setFloatUniform(
            @NonNull String uniformName, float value1, float value2, float value3, float value4) {
        setFloatUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than a
     * float (for N=1), vecN, or float[N], where N is the length of the values param, then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param values      float value corresponding to the vec4 float uniform with the given name.
     */
    public void setFloatUniform(@NonNull String uniformName, @NonNull float[] values) {
        setUniform(uniformName, values, false);
    }

    private void setFloatUniform(
            String uniformName, float value1, float value2, float value3, float value4, int count) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        nativeUpdateUniforms(
                mNativeMeshWrapper, uniformName, value1, value2, value3, value4, count);
    }

    private void setUniform(String uniformName, float[] values, boolean isColor) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        if (values == null) {
            throw new NullPointerException("The uniform values parameter must not be null");
        }

        nativeUpdateUniforms(mNativeMeshWrapper, uniformName, values, isColor);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than int
     * or int[1] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the int uniform declared in the shader program.
     * @param value       value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(@NonNull String uniformName, int value) {
        setIntUniform(uniformName, value, 0, 0, 0, 1);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than ivec2
     * or int[2] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the int uniform declared in the shader program.
     * @param value1      first value corresponding to the int uniform with the given name.
     * @param value2      second value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2) {
        setIntUniform(uniformName, value1, value2, 0, 0, 2);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than ivec3
     * or int[3] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the int uniform declared in the shader program.
     * @param value1      first value corresponding to the int uniform with the given name.
     * @param value2      second value corresponding to the int uniform with the given name.
     * @param value3      third value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2, int value3) {
        setIntUniform(uniformName, value1, value2, value3, 0, 3);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than ivec4
     * or int[4] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the int uniform declared in the shader program.
     * @param value1      first value corresponding to the int uniform with the given name.
     * @param value2      second value corresponding to the int uniform with the given name.
     * @param value3      third value corresponding to the int uniform with the given name.
     * @param value4      fourth value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(
            @NonNull String uniformName, int value1, int value2, int value3, int value4) {
        setIntUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform value corresponding to the shader assigned to the mesh. If the shader does
     * not have a uniform with that name or if the uniform is declared with a type other than an
     * int (for N=1), ivecN, or int[N], where N is the length of the values param, then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the int uniform declared in the shader program.
     * @param values      int values corresponding to the vec4 int uniform with the given name.
     */
    public void setIntUniform(@NonNull String uniformName, @NonNull int[] values) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        if (values == null) {
            throw new NullPointerException("The uniform values parameter must not be null");
        }
        nativeUpdateUniforms(mNativeMeshWrapper, uniformName, values);
    }

    /**
     * @hide so only calls from module can utilize it
     */
    long getNativeWrapperInstance() {
        return mNativeMeshWrapper;
    }

    private void setIntUniform(
            String uniformName, int value1, int value2, int value3, int value4, int count) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }

        nativeUpdateUniforms(
                mNativeMeshWrapper, uniformName, value1, value2, value3, value4, count);
    }

    private void meshSetup(long nativeMeshWrapper, boolean isIndexed) {
        mNativeMeshWrapper = nativeMeshWrapper;
        this.mIsIndexed = isIndexed;
        MeshHolder.MESH_SPECIFICATION_REGISTRY.registerNativeAllocation(this, mNativeMeshWrapper);
    }

    private static native long nativeGetFinalizer();

    private static native long nativeMake(long meshSpec, int mode, Buffer vertexBuffer,
            boolean isDirect, int vertexCount, int vertexOffset, float left, float top, float right,
            float bottom);

    private static native long nativeMakeIndexed(long meshSpec, int mode, Buffer vertexBuffer,
            boolean isVertexDirect, int vertexCount, int vertexOffset, ShortBuffer indexBuffer,
            boolean isIndexDirect, int indexCount, int indexOffset, float left, float top,
            float right, float bottom);

    private static native void nativeUpdateUniforms(long builder, String uniformName, float value1,
            float value2, float value3, float value4, int count);

    private static native void nativeUpdateUniforms(
            long builder, String uniformName, float[] values, boolean isColor);

    private static native void nativeUpdateUniforms(long builder, String uniformName, int value1,
            int value2, int value3, int value4, int count);

    private static native void nativeUpdateUniforms(long builder, String uniformName, int[] values);

}
