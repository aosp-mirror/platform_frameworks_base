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

import android.filterfw.core.FilterContext;
import android.filterfw.core.GLFrame;
import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.FrameManager;
import android.filterfw.format.ImageFormat;

/**
 * The FilterEffect class is the base class for all Effects based on Filters from the Mobile
 * Filter Framework (MFF).
 * @hide
 */
public abstract class FilterEffect extends Effect {

    protected EffectContext mEffectContext;
    private String mName;

    /**
     * Protected constructor as FilterEffects should be created by Factory.
     */
    protected FilterEffect(EffectContext context, String name) {
        mEffectContext = context;
        mName = name;
    }

    /**
     * Get the effect name.
     *
     * Returns the unique name of the effect, which matches the name used for instantiating this
     * effect by the EffectFactory.
     *
     * @return The name of the effect.
     */
    @Override
    public String getName() {
        return mName;
    }

    // Helper Methods for subclasses ///////////////////////////////////////////////////////////////
    /**
     * Call this before manipulating the GL context. Will assert that the GL environment is in a
     * valid state, and save it.
     */
    protected void beginGLEffect() {
        mEffectContext.assertValidGLState();
        mEffectContext.saveGLState();
    }

    /**
     * Call this after manipulating the GL context. Restores the previous GL state.
     */
    protected void endGLEffect() {
        mEffectContext.restoreGLState();
    }

    /**
     * Returns the active filter context for this effect.
     */
    protected FilterContext getFilterContext() {
        return mEffectContext.mFilterContext;
    }

    /**
     * Converts a texture into a Frame.
     */
    protected Frame frameFromTexture(int texId, int width, int height) {
        FrameManager manager = getFilterContext().getFrameManager();
        FrameFormat format = ImageFormat.create(width, height,
                                                ImageFormat.COLORSPACE_RGBA,
                                                FrameFormat.TARGET_GPU);
        Frame frame = manager.newBoundFrame(format,
                                            GLFrame.EXISTING_TEXTURE_BINDING,
                                            texId);
        frame.setTimestamp(Frame.TIMESTAMP_UNKNOWN);
        return frame;
    }

}

