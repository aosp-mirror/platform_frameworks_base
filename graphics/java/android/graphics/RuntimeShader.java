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
import android.view.Window;

import libcore.util.NativeAllocationRegistry;

/**
 * <p>A {@link RuntimeShader} calculates a per-pixel color based on the output of a user defined
 * Android Graphics Shading Language (AGSL) function.</p>
 *
 * <h3>Android Graphics Shading Language</h3>
 * <p>The AGSL syntax is very similar to OpenGL ES Shading Language, but there are some important
 * differences that are highlighted here. Most of these differences are summed up in one basic fact:
 * <b>With GPU shading languages, you are programming a stage of the GPU pipeline. With AGSL, you
 * are programming a stage of the {@link Canvas} or {@link RenderNode} drawing pipeline.</b></p>
 *
 * <p>In particular, a GLSL fragment shader controls the entire behavior of the GPU between the
 * rasterizer and the blending hardware. That shader does all of the work to compute a color, and
 * the color it generates is exactly what is fed to the blending stage of the pipeline.</p>
 *
 * <p>In contrast, AGSL functions exist as part of a larger pipeline. When you issue a
 * {@link Canvas} drawing operation, Android (generally) assembles a single GPU fragment shader to
 * do all of the required work. This shader typically includes several pieces. For example, it might
 * include:</p>
 * <ul>
 *  <li>Evaluating whether a pixel falls inside or outside of the shape being drawn (or on the
 *  border, where it might apply antialiasing).</li>
 *  <li>Evaluating whether a pixel falls inside or outside of the clipping region (again, with
 *  possible antialiasing logic for border pixels).</li>
 *  <li>Logic for the {@link Shader}, {@link ColorFilter}, and {@link BlendMode} on the
 *  {@link Paint}.</li>
 *  <li>Color space conversion code, as part of Android’s color management.</li>
 * </ul>
 *
 * <p>A {@link RuntimeShader}, like other {@link Shader} types, effectively contributes a function
 * to the GPU’s fragment shader.</p>
 *
 * <h3>AGSL Shader Execution</h3>
 * <p>Just like a GLSL shader, an AGSL shader begins execution in a main function. Unlike GLSL, the
 * function receives as an input parameter the position of the pixel within the {@link Canvas} or
 * {@link RenderNode} coordinate space (similar to gl_fragCoord) and returns the color to be shaded
 * as a vec4 (similar to out vec4 color or gl_FragColor in GLSL).</p>
 *
 * <pre class="prettyprint">
 * vec4 main(vec2 canvas_coordinates);
 * </pre>
 *
 * <p>AGSL and GLSL use different coordinate spaces by default. In GLSL, the fragment coordinate
 * (fragCoord) is relative to the lower left. AGSL matches the screen coordinate system of the
 * Android {@link Canvas} which has its origin as the upper left corner. This means that the
 * coordinates provided as a parameter in the main function are local to the canvas with the
 * exception of any {@link Shader#getLocalMatrix(Matrix)} transformations applied to this shader.
 * Additionally, if the shader is invoked by another using {@link #setInputShader(String, Shader)},
 * then that parent shader may modify the input coordinates arbitrarily.</p>
 *
 * <h3>AGSL and Color Spaces</h3>
 * <p>Android Graphics and by extension {@link RuntimeShader} are color managed.  The working
 * {@link ColorSpace} for an AGSL shader is defined to be the color space of the destination, which
 * in most cases is determined by {@link Window#setColorMode(int)}.</p>
 *
 * <p>When authoring an AGSL shader, you won’t know what the working color space is. For many
 * effects, this is fine because by default color inputs are automatically converted into the
 * working color space. For certain effects, it may be important to do some math in a fixed, known
 * color space. A common example is lighting – to get physically accurate lighting, math should be
 * done in a linear color space. To help with this, AGSL provides two intrinsic functions that
 * convert colors between the working color space and the
 * {@link ColorSpace.Named#LINEAR_EXTENDED_SRGB} color space:
 *
 * <pre class="prettyprint">
 * vec3 toLinearSrgb(vec3 color);
 * vec3 fromLinearSrgb(vec3 color);</pre>
 *
 * <h3>AGSL and Premultiplied Alpha</h3>
 * <p>When dealing with transparent colors, there are two (common) possible representations:
 * straight (unassociated) alpha and premultiplied (associated) alpha. In ASGL the color returned
 * by the main function is expected to be premultiplied.  AGSL’s use of premultiplied alpha
 * implies:
 * </p>
 *
 * <ul>
 *  <li>If your AGSL shader will return transparent colors, be sure to multiply the RGB by A.  The
 *  resulting color should be [R*A, G*A, B*A, A], not [R, G, B, A].</li>
 *  <li>For more complex shaders, you must understand which of your colors are premultiplied vs.
 *  straight. Many operations don’t make sense if you mix both kinds of color together.</li>
 * </ul>
 *
 * <h3>Uniforms</h3>
 * <p>AGSL, like GLSL, exposes the concept of uniforms. An AGSL uniform is defined as a read-only,
 * global variable that is accessible by the AGSL code and is initialized by a number of setter
 * methods on {@link RuntimeShader}. AGSL exposes two primitive uniform data types (float, int) and
 * two specialized types (colors, shaders) that are outlined below.</p>
 *
 * <h4>Primitive Uniforms</h4>
 * <p>There are two primitive uniform types supported by AGSL, float and int. For these types and
 * uniforms representing a grouping of these types, like arrays and matrices, there are
 * corresponding {@link RuntimeShader} methods to initialize them.
 * <table border="2" width="85%" align="center" cellpadding="5">
 *     <thead>
 *         <tr><th>Java Type</th> <th>AGSL Type</th> <th>Method</th> </tr>
 *     </thead>
 *
 *     <tbody>
 *     <tr>
 *         <td rowspan="4">Floats</td>
 *         <td>float</td>
 *         <td>{@link RuntimeShader#setFloatUniform(String, float)}</td>
 *     </tr>
 *     <tr>
 *         <td>vec2</td>
 *         <td>{@link RuntimeShader#setFloatUniform(String, float, float)}</td>
 *     </tr>
 *     <tr>
 *         <td>vec3</td>
 *         <td>{@link RuntimeShader#setFloatUniform(String, float, float, float)}</td>
 *     </tr>
 *     <tr>
 *         <td>vec4</td>
 *         <td>{@link RuntimeShader#setFloatUniform(String, float, float, float, float)}</td>
 *     </tr>
 *     <tr>
 *         <td rowspan="4">Integers</td>
 *         <td>int</td>
 *         <td>{@link RuntimeShader#setIntUniform(String, int)}</td>
 *     </tr>
 *     <tr>
 *         <td>ivec2</td>
 *         <td>{@link RuntimeShader#setIntUniform(String, int, int)}</td>
 *     </tr>
 *     <tr>
 *         <td>ivec3</td>
 *         <td>{@link RuntimeShader#setIntUniform(String, int, int, int)}</td>
 *     </tr>
 *     <tr>
 *         <td>ivec4</td>
 *         <td>{@link RuntimeShader#setIntUniform(String, int, int, int, int)}</td>
 *     </tr>
 *     <tr>
 *         <td rowspan="2">Matrices and Arrays</td>
 *         <td>mat2, mat3, and mat4, and float[]</td>
 *         <td>{@link RuntimeShader#setFloatUniform(String, float[])}</td>
 *     </tr>
 *     <tr>
 *         <td>int[]</td>
 *         <td>{@link RuntimeShader#setIntUniform(String, int[])}</td>
 *     </tr>
 *     </tbody>
 * </table>
 *
 * For example, a simple AGSL shader making use of a float uniform to modulate the transparency
 * of the output color would look like:</p>
 *
 * <pre class="prettyprint">
 * uniform float alpha;
 * vec4 main(vec2 canvas_coordinates) {
 *     vec3 red = vec3(1.0, 0.0, 0.0);
 *     return vec4(red * alpha, alpha);
 * }</pre>
 *
 * <p>After creating a {@link RuntimeShader} with that program the uniform can then be initialized
 * and updated per frame by calling {@link RuntimeShader#setFloatUniform(String, float)} with the
 * value of alpha.  The value of a primitive uniform defaults to 0 if it is declared in the AGSL
 * shader but not initialized.</p>
 *
 * <h4>Color Uniforms</h4>
 * <p>AGSL doesn't know if uniform variables contain colors, it won't automatically convert them to
 * the working colorspace of the shader at runtime.  However, you can label your vec4 uniform with
 * the "layout(color)" qualifier which lets Android know that the uniform will be used as a color.
 * Doing so allows AGSL to transform the uniform value to the working color space. In AGSL, declare
 * the uniform like this:
 *
 * <pre class="prettyprint">
 * layout(color) uniform vec4 inputColorA;
 * layout(color) uniform vec4 inputColorB;
 * vec4 main(vec2 canvas_coordinates) {
 *     // blend the two colors together and return the resulting color
 *     return mix(inputColorA, inputColorB, 0.5);
 * }</pre>
 *
 * <p>After creating a {@link RuntimeShader} with that program the uniforms can
 * then be initialized and updated per frame by calling
 * {@link RuntimeShader#setColorUniform(String, int)},
 * {@link RuntimeShader#setColorUniform(String, long)}, or
 * {@link RuntimeShader#setColorUniform(String, Color)} with the desired colors.  The value of a
 * color uniform is undefined if it is declared in the AGSL shader but not initialized.</p>
 *
 * <h4>Shader Uniforms</h4>
 * In GLSL, a fragment shader can sample a texture. For AGSL instead of sampling textures you can
 * sample from any {@link Shader}, which includes but is not limited to {@link BitmapShader}. To
 * make it clear that you are operating on an {@link Shader} object there is no "sample"
 * method. Instead, the shader uniform has an "eval()" method. This distinction enables AGSL shaders
 * to sample from existing bitmap and gradient shaders as well as other {@link RuntimeShader}
 * objects.  In AGSL, declare the uniform like this:
 *
 * <pre class="prettyprint">
 * uniform shader myShader;
 * vec4 main(vec2 canvas_coordinates) {
 *     // swap the red and blue color channels when sampling from myShader
 *     return myShader.eval(canvas_coordinates).bgra;
 * }</pre>
 *
 * <p>After creating a {@link RuntimeShader} with that program the shader uniform can
 * then be initialized and updated per frame by calling
 * {@link RuntimeShader#setInputShader(String, Shader)} with the desired shader. The value of a
 * shader uniform is undefined if it is declared in the AGSL shader but not initialized.</p>
 *
 * <p>Although most {@link BitmapShader}s contain colors that should be color managed, some contain
 * data that isn’t actually colors. This includes bitmaps storing normals, material properties
 * (e.g. roughness), heightmaps, or any other purely mathematical data that happens to be stored in
 * a bitmap. When using these kinds of shaders in AGSL, you probably want to initialize them with
 * {@link #setInputBuffer(String, BitmapShader)}. Shaders initialized this way work much like
 * a regular {@link BitmapShader} (including filtering and tiling), with a few major differences:
 * <ul>
 *  <li>No color space transformation is applied (the color space of the bitmap is ignored).</li>
 *  <li>Bitmaps that return false for {@link Bitmap#isPremultiplied()} are not automatically
 *  premultiplied.</li>
 * </ul>
 *
 * <p>In addition, when sampling from a {@link BitmapShader} be aware that the shader does not use
 * normalized coordinates (like a texture in GLSL). It uses (0, 0) in the upper-left corner, and
 * (width, height) in the bottom-right corner. Normally, this is exactly what you want. If you’re
 * evaluating the shader with coordinates based on the ones passed to your AGSL program, the scale
 * is correct. However, if you want to adjust those coordinates (to do some kind of re-mapping of
 * the bitmap), remember that the coordinates are local to the canvas.</p>
 *
 */
public class RuntimeShader extends Shader {

    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                RuntimeShader.class.getClassLoader(), nativeGetFinalizer());
    }

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
        // colorspace is required, but the RuntimeShader always produces colors in the destination
        // buffer's colorspace regardless of the value specified here.
        super(ColorSpace.get(ColorSpace.Named.SRGB));
        if (shader == null) {
            throw new NullPointerException("RuntimeShader requires a non-null AGSL string");
        }
        mNativeInstanceRuntimeShaderBuilder = nativeCreateBuilder(shader);
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(
                this, mNativeInstanceRuntimeShaderBuilder);
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
     * Assigns the uniform shader to the provided shader parameter.  If the shader program does not
     * have a uniform shader with that name then an IllegalArgumentException is thrown.
     *
     * @param shaderName name matching the uniform declared in the AGSL shader program
     * @param shader shader passed into the AGSL shader program for sampling
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

    /**
     * Assigns the uniform shader to the provided shader parameter.  If the shader program does not
     * have a uniform shader with that name then an IllegalArgumentException is thrown.
     *
     * Unlike setInputShader this method returns samples directly from the bitmap's buffer. This
     * means that there will be no transformation of the sampled pixels, such as colorspace
     * conversion or alpha premultiplication.
     */
    public void setInputBuffer(@NonNull String shaderName, @NonNull BitmapShader shader) {
        if (shaderName == null) {
            throw new NullPointerException("The shaderName parameter must not be null");
        }
        if (shader == null) {
            throw new NullPointerException("The shader parameter must not be null");
        }

        nativeUpdateShader(mNativeInstanceRuntimeShaderBuilder, shaderName,
                shader.getNativeInstanceWithDirectSampling());
        discardNativeInstance();
    }


    /** @hide */
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        return nativeCreateShader(mNativeInstanceRuntimeShaderBuilder, nativeMatrix);
    }

    /** @hide */
    protected long getNativeShaderBuilder() {
        return mNativeInstanceRuntimeShaderBuilder;
    }

    private static native long nativeGetFinalizer();
    private static native long nativeCreateBuilder(String agsl);
    private static native long nativeCreateShader(long shaderBuilder, long matrix);
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

