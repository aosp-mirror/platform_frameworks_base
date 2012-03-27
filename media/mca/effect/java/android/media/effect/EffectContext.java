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

import android.filterfw.core.CachedFrameManager;
import android.filterfw.core.FilterContext;
import android.filterfw.core.FilterFactory;
import android.filterfw.core.GLEnvironment;
import android.filterfw.core.GLFrame;
import android.filterfw.core.FrameManager;
import android.opengl.GLES20;

/**
 * <p>An EffectContext keeps all necessary state information to run Effects within a Open GL ES 2.0
 * context.</p>
 *
 * <p>Every EffectContext is bound to one GL context. The application is responsible for creating
 * this EGL context, and making it current before applying any effect. If your EGL context is
 * destroyed, the EffectContext becomes invalid and any effects bound to this context can no longer
 * be used. If you switch to another EGL context, you must create a new EffectContext. Each Effect
 * is bound to a single EffectContext, and can only be executed in that context.</p>
 */
public class EffectContext {

    private final int GL_STATE_FBO          = 0;
    private final int GL_STATE_PROGRAM      = 1;
    private final int GL_STATE_ARRAYBUFFER  = 2;
    private final int GL_STATE_COUNT        = 3;

    FilterContext mFilterContext;

    private EffectFactory mFactory;

    private int[] mOldState = new int[GL_STATE_COUNT];

    /**
     * Creates a context within the current GL context.
     *
     * <p>Binds the EffectContext to the current OpenGL context. All subsequent calls to the
     * EffectContext must be made in the GL context that was active during creation.
     * When you have finished using a context, you must call {@link #release()}. to dispose of all
     * resources associated with this context.</p>
     */
    public static EffectContext createWithCurrentGlContext() {
        EffectContext result = new EffectContext();
        result.initInCurrentGlContext();
        return result;
    }

    /**
     * Returns the EffectFactory for this context.
     *
     * <p>The EffectFactory returned from this method allows instantiating new effects within this
     * context.</p>
     *
     * @return The EffectFactory instance for this context.
     */
    public EffectFactory getFactory() {
        return mFactory;
    }

    /**
     * Releases the context.
     *
     * <p>Releases all the resources and effects associated with the EffectContext. This renders the
     * context and all the effects bound to this context invalid. You must no longer use the context
     * or any of its bound effects after calling release().</p>
     *
     * <p>Note that this method must be called with the proper EGL context made current, as the
     * EffectContext and its effects may release internal GL resources.</p>
     */
    public void release() {
        mFilterContext.tearDown();
        mFilterContext = null;
    }

    private EffectContext() {
        mFilterContext = new FilterContext();
        mFilterContext.setFrameManager(new CachedFrameManager());
        mFactory = new EffectFactory(this);
    }

    private void initInCurrentGlContext() {
        if (!GLEnvironment.isAnyContextActive()) {
            throw new RuntimeException("Attempting to initialize EffectContext with no active "
                + "GL context!");
        }
        GLEnvironment glEnvironment = new GLEnvironment();
        glEnvironment.initWithCurrentContext();
        mFilterContext.initGLEnvironment(glEnvironment);
    }

    final void assertValidGLState() {
        GLEnvironment glEnv = mFilterContext.getGLEnvironment();
        if (glEnv == null || !glEnv.isContextActive()) {
            if (GLEnvironment.isAnyContextActive()) {
                throw new RuntimeException("Applying effect in wrong GL context!");
            } else {
                throw new RuntimeException("Attempting to apply effect without valid GL context!");
            }
        }
    }

    final void saveGLState() {
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, mOldState, GL_STATE_FBO);
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, mOldState, GL_STATE_PROGRAM);
        GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, mOldState, GL_STATE_ARRAYBUFFER);
    }

    final void restoreGLState() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mOldState[GL_STATE_FBO]);
        GLES20.glUseProgram(mOldState[GL_STATE_PROGRAM]);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mOldState[GL_STATE_ARRAYBUFFER]);
    }
}

