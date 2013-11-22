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
 * finer-grained memory management. This interface is available in all application components
 * ({@link android.app.Activity}, {@link android.app.Service},
 * {@link ContentProvider}, and {@link android.app.Application}).
 *
 * <p>You should implement {@link #onTrimMemory} to incrementally release memory based on current
 * system constraints. Using this callback to release your resources helps provide a more
 * responsive system overall, but also directly benefits the user experience for
 * your app by allowing the system to keep your process alive longer. That is,
 * if you <em>don't</em> trim your resources based on memory levels defined by this callback,
 * the system is more likely to kill your process while it is cached in the least-recently used
 * (LRU) list, thus requiring your app to restart and restore all state when the user returns to it.
 *
 * <p>The values provided by {@link #onTrimMemory} do not represent a single linear progression of
 * memory limits, but provide you different types of clues about memory availability:</p>
 * <ul>
 * <li>When your app is running:
 *  <ol>
 *  <li>{@link #TRIM_MEMORY_RUNNING_MODERATE} <br>The device is beginning to run low on memory.
 * Your app is running and not killable.
 *  <li>{@link #TRIM_MEMORY_RUNNING_LOW} <br>The device is running much lower on memory.
 * Your app is running and not killable, but please release unused resources to improve system
 * performance (which directly impacts your app's performance).
 *  <li>{@link #TRIM_MEMORY_RUNNING_CRITICAL} <br>The device is running extremely low on memory.
 * Your app is not yet considered a killable process, but the system will begin killing
 * background processes if apps do not release resources, so you should release non-critical
 * resources now to prevent performance degradation.
 *  </ol>
 * </li>
 * <li>When your app's visibility changes:
 *  <ol>
 *  <li>{@link #TRIM_MEMORY_UI_HIDDEN} <br>Your app's UI is no longer visible, so this is a good
 * time to release large resources that are used only by your UI.
 *  </ol>
 * </li>
 * <li>When your app's process resides in the background LRU list:
 *  <ol>
 *  <li>{@link #TRIM_MEMORY_BACKGROUND} <br>The system is running low on memory and your process is
 * near the beginning of the LRU list. Although your app process is not at a high risk of being
 * killed, the system may already be killing processes in the LRU list, so you should release
 * resources that are easy to recover so your process will remain in the list and resume
 * quickly when the user returns to your app.
 *  <li>{@link #TRIM_MEMORY_MODERATE} <br>The system is running low on memory and your process is
 * near the middle of the LRU list. If the system becomes further constrained for memory, there's a
 * chance your process will be killed.
 *  <li>{@link #TRIM_MEMORY_COMPLETE} <br>The system is running low on memory and your process is
 * one of the first to be killed if the system does not recover memory now. You should release
 * absolutely everything that's not critical to resuming your app state.
 *   <p>To support API levels lower than 14, you can use the {@link #onLowMemory} method as a
 * fallback that's roughly equivalent to the {@link ComponentCallbacks2#TRIM_MEMORY_COMPLETE} level.
 *  </li>
 *  </ol>
 * <p class="note"><strong>Note:</strong> When the system begins
 * killing processes in the LRU list, although it primarily works bottom-up, it does give some
 * consideration to which processes are consuming more memory and will thus provide more gains in
 * memory if killed. So the less memory you consume while in the LRU list overall, the better
 * your chances are to remain in the list and be able to quickly resume.</p>
 * </li>
 * </ul>
 * <p>More information about the different stages of a process lifecycle (such as what it means
 * to be placed in the background LRU list) is provided in the <a
 * href="{@docRoot}guide/components/processes-and-threads.html#Lifecycle">Processes and Threads</a>
 * document.
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
     * Level for {@link #onTrimMemory(int)}: the process is not an expendable
     * background process, but the device is running extremely low on memory
     * and is about to not be able to keep any background processes running.
     * Your running process should free up as many non-critical resources as it
     * can to allow that memory to be used elsewhere.  The next thing that
     * will happen after this is {@link #onLowMemory()} called to report that
     * nothing at all can be kept in the background, a situation that can start
     * to notably impact the user.
     */
    static final int TRIM_MEMORY_RUNNING_CRITICAL = 15;

    /**
     * Level for {@link #onTrimMemory(int)}: the process is not an expendable
     * background process, but the device is running low on memory.
     * Your running process should free up unneeded resources to allow that
     * memory to be used elsewhere.
     */
    static final int TRIM_MEMORY_RUNNING_LOW = 10;


    /**
     * Level for {@link #onTrimMemory(int)}: the process is not an expendable
     * background process, but the device is running moderately low on memory.
     * Your running process may want to release some unneeded resources for
     * use elsewhere.
     */
    static final int TRIM_MEMORY_RUNNING_MODERATE = 5;

    /**
     * Called when the operating system has determined that it is a good
     * time for a process to trim unneeded memory from its process.  This will
     * happen for example when it goes in the background and there is not enough
     * memory to keep as many background processes running as desired.  You
     * should never compare to exact values of the level, since new intermediate
     * values may be added -- you will typically want to compare if the value
     * is greater or equal to a level you are interested in.
     *
     * <p>To retrieve the processes current trim level at any point, you can
     * use {@link android.app.ActivityManager#getMyMemoryState
     * ActivityManager.getMyMemoryState(RunningAppProcessInfo)}.
     *
     * @param level The context of the trim, giving a hint of the amount of
     * trimming the application may like to perform.  May be
     * {@link #TRIM_MEMORY_COMPLETE}, {@link #TRIM_MEMORY_MODERATE},
     * {@link #TRIM_MEMORY_BACKGROUND}, {@link #TRIM_MEMORY_UI_HIDDEN},
     * {@link #TRIM_MEMORY_RUNNING_CRITICAL}, {@link #TRIM_MEMORY_RUNNING_LOW},
     * or {@link #TRIM_MEMORY_RUNNING_MODERATE}.
     */
    void onTrimMemory(int level);
}
