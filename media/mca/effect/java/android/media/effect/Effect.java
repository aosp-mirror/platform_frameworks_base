/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.media.effect;


/**
 * <p>Effects are high-performance transformations that can be applied to image frames. These are
 * passed in the form of OpenGL ES 2.0 texture names. Typical frames could be images loaded from
 * disk, or frames from the camera or other video streams.</p>
 *
 * <p>To create an Effect you must first create an EffectContext. You can obtain an instance of the
 * context's EffectFactory by calling
 * {@link android.media.effect.EffectContext#getFactory() getFactory()}. The EffectFactory allows
 * you to instantiate specific Effects.</p>
 *
 * <p>The application is responsible for creating an EGL context, and making it current before
 * applying an effect. An effect is bound to a single EffectContext, which in turn is bound to a
 * single EGL context. If your EGL context is destroyed, the EffectContext becomes invalid and any
 * effects bound to this context can no longer be used.</p>
 *
 */
public abstract class Effect {

    /**
     * Get the effect name.
     *
     * Returns the unique name of the effect, which matches the name used for instantiating this
     * effect by the EffectFactory.
     *
     * @return The name of the effect.
     */
    public abstract String getName();

    /**
     * Apply an effect to GL textures.
     *
     * <p>Apply the Effect on the specified input GL texture, and write the result into the
     * output GL texture. The texture names passed must be valid in the current GL context.</p>
     *
     * <p>The input texture must be a valid texture name with the given width and height and must be
     * bound to a GL_TEXTURE_2D texture image (usually done by calling the glTexImage2D() function).
     * Multiple mipmap levels may be provided.</p>
     *
     * <p>If the output texture has not been bound to a texture image, it will be automatically
     * bound by the effect as a GL_TEXTURE_2D. It will contain one mipmap level (0), which will have
     * the same size as the input. No other mipmap levels are defined. If the output texture was
     * bound already, and its size does not match the input texture size, the result may be clipped
     * or only partially fill the texture.</p>
     *
     * <p>Note, that regardless of whether a texture image was originally provided or not, both the
     * input and output textures are owned by the caller. That is, the caller is responsible for
     * calling glDeleteTextures() to deallocate the input and output textures.</p>
     *
     * @param inputTexId The GL texture name of a valid and bound input texture.
     * @param width The width of the input texture in pixels.
     * @param height The height of the input texture in pixels.
     * @param outputTexId The GL texture name of the output texture.
     */
    public abstract void apply(int inputTexId, int width, int height, int outputTexId);

    /**
     * Set a filter parameter.
     *
     * Consult the effect documentation for a list of supported parameter keys for each effect.
     *
     * @param parameterKey The name of the parameter to adjust.
     * @param value The new value to set the parameter to.
     * @throws InvalidArgumentException if parameterName is not a recognized name, or the value is
     *         not a valid value for this parameter.
     */
    public abstract void setParameter(String parameterKey, Object value);

    /**
     * Set an effect listener.
     *
     * Some effects may report state changes back to the host, if a listener is set. Consult the
     * individual effect documentation for more details.
     *
     * @param listener The listener to receive update callbacks on.
     */
    public void setUpdateListener(EffectUpdateListener listener) {
    }

    /**
     * Release an effect.
     *
     * <p>Releases the effect and any resources associated with it. You may call this if you need to
     * make sure acquired resources are no longer held by the effect. Releasing an effect makes it
     * invalid for reuse.</p>
     *
     * <p>Note that this method must be called with the EffectContext and EGL context current, as
     * the effect may release internal GL resources.</p>
     */
    public abstract void release();
}

