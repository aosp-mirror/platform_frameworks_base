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

import libcore.util.NativeAllocationRegistry;

import java.nio.Buffer;
import java.nio.ShortBuffer;

/**
 * Class representing a mesh object.
 *
 * This class generates Mesh objects via the
 * {@link #make(MeshSpecification, Mode, Buffer, int, Rect)} and
 * {@link #makeIndexed(MeshSpecification, Mode, Buffer, int, ShortBuffer, Rect)} methods,
 * where a {@link MeshSpecification} is required along with various attributes for
 * detailing the mesh object, including a mode, vertex buffer, optional index buffer, and bounds
 * for the mesh.
 *
 * @hide
 */
public class Mesh {
    private long mNativeMeshWrapper;
    private boolean mIsIndexed;

    /**
     * Enum to determine how the mesh is represented.
     */
    public enum Mode {Triangles, TriangleStrip}

    private static class MeshHolder {
        public static final NativeAllocationRegistry MESH_SPECIFICATION_REGISTRY =
                NativeAllocationRegistry.createMalloced(
                        MeshSpecification.class.getClassLoader(), nativeGetFinalizer());
    }

    /**
     * Generates a {@link Mesh} object.
     *
     * @param meshSpec     {@link MeshSpecification} used when generating the mesh.
     * @param mode         {@link Mode} enum
     * @param vertexBuffer vertex buffer representing through {@link Buffer}.
     * @param vertexCount  the number of vertices represented in the vertexBuffer.
     * @param bounds       bounds of the mesh object.
     * @return a new Mesh object.
     */
    public static Mesh make(MeshSpecification meshSpec, Mode mode, Buffer vertexBuffer,
            int vertexCount, Rect bounds) {
        long nativeMesh = nativeMake(meshSpec.mNativeMeshSpec, mode.ordinal(), vertexBuffer,
                vertexBuffer.isDirect(), vertexCount, vertexBuffer.position(), bounds.left,
                bounds.top, bounds.right, bounds.bottom);
        if (nativeMesh == 0) {
            throw new IllegalArgumentException("Mesh construction failed.");
        }
        return new Mesh(nativeMesh, false);
    }

    /**
     * Generates an indexed {@link Mesh} object.
     *
     * @param meshSpec     {@link MeshSpecification} used when generating the mesh.
     * @param mode         {@link Mode} enum
     * @param vertexBuffer vertex buffer representing through {@link Buffer}.
     * @param vertexCount  the number of vertices represented in the vertexBuffer.
     * @param indexBuffer  index buffer representing through {@link ShortBuffer}.
     * @param bounds       bounds of the mesh object.
     * @return a new Mesh object.
     */
    public static Mesh makeIndexed(MeshSpecification meshSpec, Mode mode, Buffer vertexBuffer,
            int vertexCount, ShortBuffer indexBuffer, Rect bounds) {
        long nativeMesh = nativeMakeIndexed(meshSpec.mNativeMeshSpec, mode.ordinal(), vertexBuffer,
                vertexBuffer.isDirect(), vertexCount, vertexBuffer.position(), indexBuffer,
                indexBuffer.isDirect(), indexBuffer.capacity(), indexBuffer.position(), bounds.left,
                bounds.top, bounds.right, bounds.bottom);
        if (nativeMesh == 0) {
            throw new IllegalArgumentException("Mesh construction failed.");
        }
        return new Mesh(nativeMesh, true);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the color uniform declared in the shader program.
     * @param color       the provided sRGB color will be converted into the shader program's output
     *                    colorspace and be available as a vec4 uniform in the program.
     */
    public void setColorUniform(String uniformName, int color) {
        setUniform(uniformName, Color.valueOf(color).getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the color uniform declared in the shader program.
     * @param color       the provided sRGB color will be converted into the shader program's output
     *                    colorspace and be available as a vec4 uniform in the program.
     */
    public void setColorUniform(String uniformName, long color) {
        Color exSRGB = Color.valueOf(color).convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the color uniform declared in the shader program.
     * @param color       the provided sRGB color will be converted into the shader program's output
     *                    colorspace and will be made available as a vec4 uniform in the program.
     */
    public void setColorUniform(String uniformName, Color color) {
        if (color == null) {
            throw new NullPointerException("The color parameter must not be null");
        }

        Color exSRGB = color.convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value       float value corresponding to the float uniform with the given name.
     */
    public void setFloatUniform(String uniformName, float value) {
        setFloatUniform(uniformName, value, 0.0f, 0.0f, 0.0f, 1);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value1      first float value corresponding to the float uniform with the given name.
     * @param value2      second float value corresponding to the float uniform with the given name.
     */
    public void setFloatUniform(String uniformName, float value1, float value2) {
        setFloatUniform(uniformName, value1, value2, 0.0f, 0.0f, 2);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value1      first float value corresponding to the float uniform with the given name.
     * @param value2      second float value corresponding to the float uniform with the given name.
     * @param value3      third float value corresponding to the float unifiform with the given
     *                    name.
     */
    public void setFloatUniform(String uniformName, float value1, float value2, float value3) {
        setFloatUniform(uniformName, value1, value2, value3, 0.0f, 3);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param value1      first float value corresponding to the float uniform with the given name.
     * @param value2      second float value corresponding to the float uniform with the given name.
     * @param value3      third float value corresponding to the float uniform with the given name.
     * @param value4      fourth float value corresponding to the float uniform with the given name.
     */
    public void setFloatUniform(
            String uniformName, float value1, float value2, float value3, float value4) {
        setFloatUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the float uniform declared in the shader program.
     * @param values      float value corresponding to the vec4 float uniform with the given name.
     */
    public void setFloatUniform(String uniformName, float[] values) {
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
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the int uniform delcared in the shader program.
     * @param value       value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(String uniformName, int value) {
        setIntUniform(uniformName, value, 0, 0, 0, 1);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the int uniform delcared in the shader program.
     * @param value1      first value corresponding to the int uniform with the given name.
     * @param value2      second value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(String uniformName, int value1, int value2) {
        setIntUniform(uniformName, value1, value2, 0, 0, 2);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the int uniform delcared in the shader program.
     * @param value1      first value corresponding to the int uniform with the given name.
     * @param value2      second value corresponding to the int uniform with the given name.
     * @param value3      third value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(String uniformName, int value1, int value2, int value3) {
        setIntUniform(uniformName, value1, value2, value3, 0, 3);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the int uniform delcared in the shader program.
     * @param value1      first value corresponding to the int uniform with the given name.
     * @param value2      second value corresponding to the int uniform with the given name.
     * @param value3      third value corresponding to the int uniform with the given name.
     * @param value4      fourth value corresponding to the int uniform with the given name.
     */
    public void setIntUniform(String uniformName, int value1, int value2, int value3, int value4) {
        setIntUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform color value corresponding to the shader assigned to the mesh.
     *
     * @param uniformName name matching the int uniform delcared in the shader program.
     * @param values      int values corresponding to the vec4 int uniform with the given name.
     */
    public void setIntUniform(String uniformName, int[] values) {
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
        nativeUpdateMesh(mNativeMeshWrapper, mIsIndexed);
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

    private Mesh(long nativeMeshWrapper, boolean isIndexed) {
        mNativeMeshWrapper = nativeMeshWrapper;
        this.mIsIndexed = isIndexed;
        MeshHolder.MESH_SPECIFICATION_REGISTRY.registerNativeAllocation(this, mNativeMeshWrapper);
    }

    private static native long nativeGetFinalizer();

    private static native long nativeMake(long meshSpec, int mode, Buffer vertexBuffer,
            boolean isDirect, int vertexCount, int vertexOffset, int left, int top, int right,
            int bottom);

    private static native long nativeMakeIndexed(long meshSpec, int mode, Buffer vertexBuffer,
            boolean isVertexDirect, int vertexCount, int vertexOffset, ShortBuffer indexBuffer,
            boolean isIndexDirect, int indexCount, int indexOffset, int left, int top, int right,
            int bottom);

    private static native void nativeUpdateUniforms(long builder, String uniformName, float value1,
            float value2, float value3, float value4, int count);

    private static native void nativeUpdateUniforms(
            long builder, String uniformName, float[] values, boolean isColor);

    private static native void nativeUpdateUniforms(long builder, String uniformName, int value1,
            int value2, int value3, int value4, int count);

    private static native void nativeUpdateUniforms(long builder, String uniformName, int[] values);

    private static native void nativeUpdateMesh(long nativeMeshWrapper, boolean mIsIndexed);
}
