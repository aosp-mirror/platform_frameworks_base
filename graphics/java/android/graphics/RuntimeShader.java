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

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.NonNull;

import libcore.util.NativeAllocationRegistry;

/**
 * Shader that calculates per-pixel color via a user defined Android Graphics Shading Language
 * (AGSL) function.
 */
public class RuntimeShader extends Shader {

    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                RuntimeShader.class.getClassLoader(), nativeGetFinalizer());
    }

    private boolean mForceOpaque;

    /**
     * Current native shader builder instance.
     */
    private long mNativeInstanceRuntimeShaderBuilder;

    /**
     * Creates a new RuntimeShader.
     *
     * @param shader The text of AGSL shader program to run.
     */
    public RuntimeShader(@NonNull String shader) {
        this(shader, false);
    }

    /**
     * Creates a new RuntimeShader.
     *
     * @param shader The text of AGSL shader program to run.
     * @param forceOpaque If true then all pixels produced by the AGSL shader program will have an
     *                    alpha of 1.0f.
     */
    public RuntimeShader(@NonNull String shader, boolean forceOpaque) {
        // colorspace is required, but the RuntimeShader always produces colors in the destination
        // buffer's colorspace regardless of the value specified here.
        super(ColorSpace.get(ColorSpace.Named.SRGB));
        if (shader == null) {
            throw new NullPointerException("RuntimeShader requires a non-null AGSL string");
        }
        mForceOpaque = forceOpaque;
        mNativeInstanceRuntimeShaderBuilder = nativeCreateBuilder(shader);
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(
                this, mNativeInstanceRuntimeShaderBuilder);
    }

    public boolean isForceOpaque() {
        return mForceOpaque;
    }

    /**
     * Sets the uniform color value corresponding to this shader.  If the shader does not have a
     * uniform with that name or if the uniform is declared with a type other than vec3 or vec4 and
     * corresponding layout(color) annotation then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the color uniform declared in the AGSL shader program
     * @param color the provided sRGB color will be transformed into the shader program's output
     *              colorspace and will be available as a vec4 uniform in the program.
     */
    public void setColorUniform(@NonNull String uniformName, @ColorInt int color) {
        setUniform(uniformName, Color.valueOf(color).getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to this shader.  If the shader does not have a
     * uniform with that name or if the uniform is declared with a type other than vec3 or vec4 and
     * corresponding layout(color) annotation then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the color uniform declared in the AGSL shader program
     * @param color the provided sRGB color will be transformed into the shader program's output
     *              colorspace and will be available as a vec4 uniform in the program.
     */
    public void setColorUniform(@NonNull String uniformName, @ColorLong long color) {
        Color exSRGB = Color.valueOf(color).convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to this shader.  If the shader does not have a
     * uniform with that name or if the uniform is declared with a type other than vec3 or vec4 and
     * corresponding layout(color) annotation then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the color uniform declared in the AGSL shader program
     * @param color the provided sRGB color will be transformed into the shader program's output
     *              colorspace and will be available as a vec4 uniform in the program.
     */
    public void setColorUniform(@NonNull String uniformName, @NonNull Color color) {
        if (color == null) {
            throw new NullPointerException("The color parameter must not be null");
        }
        Color exSRGB = color.convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than a float or float[1]
     * then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setFloatUniform(@NonNull String uniformName, float value) {
        setFloatUniform(uniformName, value, 0.0f, 0.0f, 0.0f, 1);
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than vec2 or float[2] then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setFloatUniform(@NonNull String uniformName, float value1, float value2) {
        setFloatUniform(uniformName, value1, value2, 0.0f, 0.0f, 2);

    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than vec3 or float[3] then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setFloatUniform(@NonNull String uniformName, float value1, float value2,
            float value3) {
        setFloatUniform(uniformName, value1, value2, value3, 0.0f, 3);

    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than vec4 or float[4] then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setFloatUniform(@NonNull String uniformName, float value1, float value2,
            float value3, float value4) {
        setFloatUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than a float (for N=1), vecN,
     * or float[N] where N is the length of the values param then an IllegalArgumentException is
     * thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setFloatUniform(@NonNull String uniformName, @NonNull float[] values) {
        setUniform(uniformName, values, false);
    }

    /**
     * Old method signature used by some callers within the platform code
     * @hide
     * @deprecated use setFloatUniform instead
     */
    @Deprecated
    public void setUniform(@NonNull String uniformName, float[] values) {
        setFloatUniform(uniformName, values);
    }

    /**
     * Old method signature used by some callers within the platform code
     * @hide
     * @deprecated use setFloatUniform instead
     */
    @Deprecated
    public void setUniform(@NonNull String uniformName, float value) {
        setFloatUniform(uniformName, value);
    }

    /**
     * Old method signature used by some callers within the platform code
     * @hide
     * @deprecated use setFloatUniform instead
     */
    @Deprecated
    public void setUniform(@NonNull String uniformName, float value1, float value2) {
        setFloatUniform(uniformName, value1, value2);
    }

    private void setFloatUniform(@NonNull String uniformName, float value1, float value2,
            float value3, float value4, int count) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }

        nativeUpdateUniforms(mNativeInstanceRuntimeShaderBuilder, uniformName, value1, value2,
                value3, value4, count);
        discardNativeInstance();
    }

    private void setUniform(@NonNull String uniformName, @NonNull float[] values, boolean isColor) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        if (values == null) {
            throw new NullPointerException("The uniform values parameter must not be null");
        }

        nativeUpdateUniforms(mNativeInstanceRuntimeShaderBuilder, uniformName, values, isColor);
        discardNativeInstance();
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than an int or int[1]
     * then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setIntUniform(@NonNull String uniformName, int value) {
        setIntUniform(uniformName, value, 0, 0, 0, 1);
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than ivec2 or int[2] then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2) {
        setIntUniform(uniformName, value1, value2, 0, 0, 2);

    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than ivec3 or int[3] then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2, int value3) {
        setIntUniform(uniformName, value1, value2, value3, 0, 3);

    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than ivec4 or int[4] then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2,
            int value3, int value4) {
        setIntUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform value corresponding to this shader.  If the shader does not have a uniform
     * with that name or if the uniform is declared with a type other than an int (for N=1), ivecN,
     * or int[N] where N is the length of the values param then an IllegalArgumentException is
     * thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL shader program
     */
    public void setIntUniform(@NonNull String uniformName, @NonNull int[] values) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        if (values == null) {
            throw new NullPointerException("The uniform values parameter must not be null");
        }
        nativeUpdateUniforms(mNativeInstanceRuntimeShaderBuilder, uniformName, values);
        discardNativeInstance();
    }

    private void setIntUniform(@NonNull String uniformName, int value1, int value2, int value3,
            int value4, int count) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }

        nativeUpdateUniforms(mNativeInstanceRuntimeShaderBuilder, uniformName, value1, value2,
                value3, value4, count);
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
        if (shaderName == null) {
            throw new NullPointerException("The shaderName parameter must not be null");
        }
        if (shader == null) {
            throw new NullPointerException("The shader parameter must not be null");
        }
        nativeUpdateShader(
                    mNativeInstanceRuntimeShaderBuilder, shaderName, shader.getNativeInstance());
        discardNativeInstance();
    }

    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        return nativeCreateShader(mNativeInstanceRuntimeShaderBuilder, nativeMatrix, mForceOpaque);
    }

    /** @hide */
    protected long getNativeShaderBuilder() {
        return mNativeInstanceRuntimeShaderBuilder;
    }

    private static native long nativeGetFinalizer();
    private static native long nativeCreateBuilder(String agsl);
    private static native long nativeCreateShader(
            long shaderBuilder, long matrix, boolean isOpaque);
    private static native void nativeUpdateUniforms(
            long shaderBuilder, String uniformName, float[] uniforms, boolean isColor);
    private static native void nativeUpdateUniforms(
            long shaderBuilder, String uniformName, float value1, float value2, float value3,
            float value4, int count);
    private static native void nativeUpdateUniforms(
            long shaderBuilder, String uniformName, int[] uniforms);
    private static native void nativeUpdateUniforms(
            long shaderBuilder, String uniformName, int value1, int value2, int value3,
            int value4, int count);
    private static native void nativeUpdateShader(
            long shaderBuilder, String shaderName, long shader);
}

