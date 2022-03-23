/*
 * Copyright 2019 The Android Open Source Project
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

import android.annotation.NonNull;

import libcore.util.NativeAllocationRegistry;

/**
 * Shader that calculates pixel output with a program (fragment shader) running on a GPU.
 * @hide
 */
public class RuntimeShader extends Shader {

    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                RuntimeShader.class.getClassLoader(), nativeGetFinalizer());
    }

    private boolean mIsOpaque;

    /**
     * Current native shader builder instance.
     */
    private long mNativeInstanceRuntimeShaderBuilder;

    /**
     * Creates a new RuntimeShader.
     *
     * @param sksl The text of SKSL program to run on the GPU.
     * @param uniforms Array of parameters passed by the SKSL shader. Array size depends
     *                 on number of uniforms declared by sksl.
     * @param isOpaque True if all pixels have alpha 1.0f.
     */
    public RuntimeShader(@NonNull String sksl, boolean isOpaque) {
        super(ColorSpace.get(ColorSpace.Named.SRGB));
        mIsOpaque = isOpaque;
        mNativeInstanceRuntimeShaderBuilder = nativeCreateBuilder(sksl);
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(
                this, mNativeInstanceRuntimeShaderBuilder);
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than float then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the SKSL shader
     * @param value
     */
    public void setUniform(@NonNull String uniformName, float value) {
        setUniform(uniformName, new float[] {value});
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than float2/vec2 then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the SKSL shader
     * @param value1
     * @param value2
     */
    public void setUniform(@NonNull String uniformName, float value1, float value2) {
        setUniform(uniformName, new float[] {value1, value2});
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than a vecN/floatN where N is
     * the size of the values array then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the SKSL shader
     * @param values
     */
    public void setUniform(@NonNull String uniformName, float[] values) {
        nativeUpdateUniforms(mNativeInstanceRuntimeShaderBuilder, uniformName, values);
        discardNativeInstance();
    }

    /**
     * Sets the uniform shader that is declares as input to this shader.  If the shader does not
     * have a uniform shader with that name then an IllegalArgumentException is thrown.
     *
     * @param shaderName name matching the uniform declared in the SKSL shader
     * @param shader shader passed into the SKSL shader for sampling
     */
    public void setInputShader(@NonNull String shaderName, @NonNull Shader shader) {
        nativeUpdateShader(
                    mNativeInstanceRuntimeShaderBuilder, shaderName, shader.getNativeInstance());
        discardNativeInstance();
    }

    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        return nativeCreateShader(mNativeInstanceRuntimeShaderBuilder, nativeMatrix, mIsOpaque);
    }

    public long getNativeShaderBuilder() {
        return mNativeInstanceRuntimeShaderBuilder;
    }

    public boolean isOpaque() {
        return mIsOpaque;
    }

    private static native long nativeGetFinalizer();
    private static native long nativeCreateBuilder(String sksl);
    private static native long nativeCreateShader(
            long shaderBuilder, long matrix, boolean isOpaque);
    private static native void nativeUpdateUniforms(
            long shaderBuilder, String uniformName, float[] uniforms);
    private static native void nativeUpdateShader(
            long shaderBuilder, String shaderName, long shader);
}

