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

/**
 * Extended {@link ComponentCallbacks} interface with a new callback for
 * finer-grained memory management.
 */
public interface ComponentCallbacks2 extends ComponentCallbacks {

    /**
     * Level for {@link #onTrimMemory(int)}: the process is nearing the end
     * of the background LRU list, and if more memory isn't found soon it will
     * be killed.
     */
    static final int TRIM_MEMORY_COMPLETE = 80;
    
    /**
     * Level for {@link #onTrimMemory(int)}: the process is around the middle
     * of the background LRU list; freeing memory can help the system keep
     * other processes running later in the list for better overall performance.
     */
    static final int TRIM_MEMORY_MODERATE = 60;
    
    /**
     * Level for {@link #onTrimMemory(int)}: the process has gone on to the
     * LRU list.  This is a good opportunity to clean up resources that can
     * efficiently and quickly be re-built if the user returns to the app.
     */
    static final int TRIM_MEMORY_BACKGROUND = 40;
    
    /**
     * Level for {@link #onTrimMemory(int)}: the process had been showing
     * a user interface, and is no longer doing so.  Large allocations with
     * the UI should be released at this point to allow memory to be better
     * managed.
     */
    static final int TRIM_MEMORY_UI_HIDDEN = 20;

    /**
     * Called when the operating system has determined that it is a good
     * time for a process to trim unneeded memory from its process.  This will
     * happen for example when it goes in the background and there is not enough
     * memory to keep as many background processes running as desired.
     * 
     * @param level The context of the trim, giving a hint of the amount of
     * trimming the application may like to perform.  May be
     * {@link #TRIM_MEMORY_COMPLETE}, {@link #TRIM_MEMORY_MODERATE},
     * {@link #TRIM_MEMORY_BACKGROUND}, or {@link #TRIM_MEMORY_UI_HIDDEN}.
     */
    void onTrimMemory(int level);
}
