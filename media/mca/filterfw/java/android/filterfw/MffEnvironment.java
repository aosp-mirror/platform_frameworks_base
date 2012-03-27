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


package android.filterfw;

import android.filterfw.core.CachedFrameManager;
import android.filterfw.core.FilterContext;
import android.filterfw.core.FrameManager;
import android.filterfw.core.GLEnvironment;

/**
 * Base class for mobile filter framework (MFF) frontend environments. These convenience classes
 * allow using the filter framework without the requirement of performing manual setup of its
 * required components.
 *
 * @hide
 */
public class MffEnvironment {

    private FilterContext mContext;

    /**
     * Protected constructor to initialize the environment's essential components. These are the
     * frame-manager and the filter-context. Passing in null for the frame-manager causes this
     * to be auto-created.
     *
     * @param frameManager The FrameManager to use or null to auto-create one.
     */
    protected MffEnvironment(FrameManager frameManager) {
        // Get or create the frame manager
        if (frameManager == null) {
            frameManager = new CachedFrameManager();
        }

        // Setup the environment
        mContext = new FilterContext();
        mContext.setFrameManager(frameManager);

    }

    /**
     * Returns the environment's filter-context.
     */
    public FilterContext getContext() {
        return mContext;
    }

    /**
     * Set the environment's GL environment to the specified environment. This does not activate
     * the environment.
     */
    public void setGLEnvironment(GLEnvironment glEnvironment) {
        mContext.initGLEnvironment(glEnvironment);
    }

    /**
     * Create and activate a new GL environment for use in this filter context.
     */
    public void createGLEnvironment() {
        GLEnvironment glEnvironment = new GLEnvironment();
        glEnvironment.initWithNewContext();
        setGLEnvironment(glEnvironment);
    }

    /**
     * Activate the GL environment for use in the current thread. A GL environment must have been
     * previously set or created using setGLEnvironment() or createGLEnvironment()! Call this after
     * having switched to a new thread for GL filter execution.
     */
    public void activateGLEnvironment() {
        GLEnvironment glEnv = mContext.getGLEnvironment();
        if (glEnv != null) {
            mContext.getGLEnvironment().activate();
        } else {
            throw new NullPointerException("No GLEnvironment in place to activate!");
        }
    }

    /**
     * Deactivate the GL environment from use in the current thread. A GL environment must have been
     * previously set or created using setGLEnvironment() or createGLEnvironment()! Call this before
     * running GL filters in another thread.
     */
    public void deactivateGLEnvironment() {
        GLEnvironment glEnv = mContext.getGLEnvironment();
        if (glEnv != null) {
            mContext.getGLEnvironment().deactivate();
        } else {
            throw new NullPointerException("No GLEnvironment in place to deactivate!");
        }
    }
}
