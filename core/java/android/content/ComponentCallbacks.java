/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.content.res.Configuration;

/**
 * The set of callback APIs that are common to all application components
 * ({@link android.app.Activity}, {@link android.app.Service},
 * {@link ContentProvider}, and {@link android.app.Application}).
 */
public interface ComponentCallbacks {
    /**
     * Called by the system when the device configuration changes while your
     * component is running.  Note that, unlike activities, other components
     * are never restarted when a configuration changes: they must always deal
     * with the results of the change, such as by re-retrieving resources.
     * 
     * <p>At the time that this function has been called, your Resources
     * object will have been updated to return resource values matching the
     * new configuration.
     * 
     * @param newConfig The new device configuration.
     */
    void onConfigurationChanged(Configuration newConfig);
    
    /**
     * This is called when the overall system is running low on memory, and
     * would like actively running process to try to tighten their belt.  While
     * the exact point at which this will be called is not defined, generally
     * it will happen around the time all background process have been killed,
     * that is before reaching the point of killing processes hosting
     * service and foreground UI that we would like to avoid killing.
     * 
     * <p>Applications that want to be nice can implement this method to release
     * any caches or other unnecessary resources they may be holding on to.
     * The system will perform a gc for you after returning from this method.
     */
    void onLowMemory();

    /** @hide */
    static final int TRIM_MEMORY_COMPLETE = 80;

    /** @hide */
    static final int TRIM_MEMORY_MODERATE = 50;

    /** @hide */
    static final int TRIM_MEMORY_BACKGROUND = 20;
}
