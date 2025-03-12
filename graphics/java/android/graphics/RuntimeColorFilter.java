/*
 * Copyright 2024 The Android Open Source Project
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
import android.annotation.FlaggedApi;
import android.annotation.NonNull;

import com.android.graphics.hwui.flags.Flags;


/**
 * <p>A {@link RuntimeColorFilter} calculates a per-pixel color based on the output of a user
 *  * defined Android Graphics Shading Language (AGSL) function.</p>
 *
 * <p>This AGSL function takes in an input color to be operated on. This color is in sRGB and the
 *  * output is also interpreted as sRGB. The AGSL function signature expects a single input
 *  * of color (packed as a half4 or float4 or vec4).</p>
 *
 * <pre class="prettyprint">
 * vec4 main(half4 in_color);
 * </pre>
 */
@FlaggedApi(Flags.FLAG_RUNTIME_COLOR_FILTERS_BLENDERS)
public class RuntimeColorFilter extends ColorFilter {

    private String mAgsl;

    /**
     * Creates a new RuntimeColorFilter.
     *
     * @param agsl The text of AGSL color filter program to run.
     */
    public RuntimeColorFilter(@NonNull String agsl) {
        if (agsl == null) {
            throw new NullPointerException("RuntimeColorFilter requires a non-null AGSL string");
        }
        mAgsl = agsl;
        // call to parent class to register native RuntimeColorFilter
        // TODO: find way to get super class to create native instance without requiring the storage
        // of agsl string
        getNativeInstance();

    }
    /**
     * Sets the uniform color value corresponding to this color filter.  If the effect does not have
     * a uniform with that name or if the uniform is declared with a type other than vec3 or vec4
     * and corresponding layout(color) annotation then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the color uniform declared in the AGSL program
     * @param color the provided sRGB color
     */
    public void setColorUniform(@NonNull String uniformName, @ColorInt int color) {
        setUniform(uniformName, Color.valueOf(color).getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to this color filter.  If the effect does not have
     * a uniform with that name or if the uniform is declared with a type other than vec3 or vec4
     * and corresponding layout(color) annotation then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the color uniform declared in the AGSL program
     * @param color the provided sRGB color
     */
    public void setColorUniform(@NonNull String uniformName, @ColorLong long color) {
        Color exSRGB = Color.valueOf(color).convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform color value corresponding to this color filter.  If the effect does not have
     * a uniform with that name or if the uniform is declared with a type other than vec3 or vec4
     * and corresponding layout(color) annotation then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the color uniform declared in the AGSL program
     * @param color the provided sRGB color
     */
    public void setColorUniform(@NonNull String uniformName, @NonNull Color color) {
        if (color == null) {
            throw new NullPointerException("The color parameter must not be null");
        }
        Color exSRGB = color.convert(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB));
        setUniform(uniformName, exSRGB.getComponents(), true);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than a float or
     * float[1] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setFloatUniform(@NonNull String uniformName, float value) {
        setFloatUniform(uniformName, value, 0.0f, 0.0f, 0.0f, 1);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than a vec2 or
     * float[2] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setFloatUniform(@NonNull String uniformName, float value1, float value2) {
        setFloatUniform(uniformName, value1, value2, 0.0f, 0.0f, 2);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than a vec3 or
     * float[3] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setFloatUniform(@NonNull String uniformName, float value1, float value2,
            float value3) {
        setFloatUniform(uniformName, value1, value2, value3, 0.0f, 3);

    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than a vec4 or
     * float[4] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setFloatUniform(@NonNull String uniformName, float value1, float value2,
            float value3, float value4) {
        setFloatUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than a float
     * (for N=1), vecN, or float[N] where N is the length of the values param then an
     * IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setFloatUniform(@NonNull String uniformName, @NonNull float[] values) {
        setUniform(uniformName, values, false);
    }

    private void setFloatUniform(@NonNull String uniformName, float value1, float value2,
            float value3, float value4, int count) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        nativeUpdateUniforms(getNativeInstance(), uniformName, value1, value2, value3, value4,
                count);
    }

    private void setUniform(@NonNull String uniformName, @NonNull float[] values, boolean isColor) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        if (values == null) {
            throw new NullPointerException("The uniform values parameter must not be null");
        }
        nativeUpdateUniforms(getNativeInstance(), uniformName, values, isColor);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than an int or int[1]
     * then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setIntUniform(@NonNull String uniformName, int value) {
        setIntUniform(uniformName, value, 0, 0, 0, 1);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than an ivec2 or
     * int[2] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2) {
        setIntUniform(uniformName, value1, value2, 0, 0, 2);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than an ivec3 or
     * int[3] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2, int value3) {
        setIntUniform(uniformName, value1, value2, value3, 0, 3);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than an ivec4 or
     * int[4] then an IllegalArgumentException is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setIntUniform(@NonNull String uniformName, int value1, int value2,
            int value3, int value4) {
        setIntUniform(uniformName, value1, value2, value3, value4, 4);
    }

    /**
     * Sets the uniform value corresponding to this color filter.  If the effect does not have a
     * uniform with that name or if the uniform is declared with a type other than an int (for N=1),
     * ivecN, or int[N] where N is the length of the values param then an IllegalArgumentException
     * is thrown.
     *
     * @param uniformName name matching the uniform declared in the AGSL program
     */
    public void setIntUniform(@NonNull String uniformName, @NonNull int[] values) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        if (values == null) {
            throw new NullPointerException("The uniform values parameter must not be null");
        }
        nativeUpdateUniforms(getNativeInstance(), uniformName, values);
    }

    private void setIntUniform(@NonNull String uniformName, int value1, int value2, int value3,
            int value4, int count) {
        if (uniformName == null) {
            throw new NullPointerException("The uniformName parameter must not be null");
        }
        nativeUpdateUniforms(getNativeInstance(), uniformName, value1, value2, value3, value4,
                count);
    }

    /**
     * Assigns the uniform shader to the provided shader parameter.  If the shader program does not
     * have a uniform shader with that name then an IllegalArgumentException is thrown.
     *
     * @param shaderName name matching the uniform declared in the AGSL program
     * @param shader shader passed into the AGSL program for sampling
     */
    public void setInputShader(@NonNull String shaderName, @NonNull Shader shader) {
        if (shaderName == null) {
            throw new NullPointerException("The shaderName parameter must not be null");
        }
        if (shader == null) {
            throw new NullPointerException("The shader parameter must not be null");
        }
        nativeUpdateChild(getNativeInstance(), shaderName, shader.getNativeInstance());
    }

    /**
     * Assigns the uniform color filter to the provided color filter parameter.  If the shader
     * program does not have a uniform color filter with that name then an IllegalArgumentException
     * is thrown.
     *
     * @param filterName name matching the uniform declared in the AGSL program
     * @param colorFilter filter passed into the AGSL program for sampling
     */
    public void setInputColorFilter(@NonNull String filterName, @NonNull ColorFilter colorFilter) {
        if (filterName == null) {
            throw new NullPointerException("The filterName parameter must not be null");
        }
        if (colorFilter == null) {
            throw new NullPointerException("The colorFilter parameter must not be null");
        }
        nativeUpdateInputColorFilter(getNativeInstance(), filterName,
                colorFilter.getNativeInstance());
    }

    /**
     * Assigns the uniform xfermode to the provided xfermode parameter.  If the shader program does
     * not have a uniform xfermode with that name then an IllegalArgumentException is thrown.
     *
     * @param xfermodeName name matching the uniform declared in the AGSL program
     * @param xfermode filter passed into the AGSL program for sampling
     */
    public void setInputXfermode(@NonNull String xfermodeName, @NonNull RuntimeXfermode xfermode) {
        if (xfermodeName == null) {
            throw new NullPointerException("The xfermodeName parameter must not be null");
        }
        if (xfermode == null) {
            throw new NullPointerException("The xfermode parameter must not be null");
        }
        nativeUpdateChild(getNativeInstance(), xfermodeName, xfermode.createNativeInstance());
    }

    /** @hide */
    @Override
    protected long createNativeInstance() {
        return nativeCreateRuntimeColorFilter(mAgsl);
    }

    private static native long nativeCreateRuntimeColorFilter(String agsl);
    private static native void nativeUpdateUniforms(
            long colorFilter, String uniformName, float[] uniforms, boolean isColor);
    private static native void nativeUpdateUniforms(
            long colorFilter, String uniformName, float value1, float value2, float value3,
            float value4, int count);
    private static native void nativeUpdateUniforms(
            long colorFilter, String uniformName, int[] uniforms);
    private static native void nativeUpdateUniforms(
            long colorFilter, String uniformName, int value1, int value2, int value3,
            int value4, int count);
    private static native void nativeUpdateChild(long colorFilter, String childName, long child);
    private static native void nativeUpdateInputColorFilter(long colorFilter, String childName,
            long inputFilter);
}
