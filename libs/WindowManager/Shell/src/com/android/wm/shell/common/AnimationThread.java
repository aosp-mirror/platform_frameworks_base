/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.common;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;

import android.annotation.NonNull;
import android.os.HandlerThread;
import android.util.Singleton;

/**
 * A singleton thread for Shell to run animations on.
 */
public class AnimationThread extends HandlerThread {
    private ShellExecutor mExecutor;

    private AnimationThread() {
        super("wmshell.anim", THREAD_PRIORITY_DISPLAY);
    }

    /** Get the singleton instance of this thread */
    public static AnimationThread instance() {
        return sAnimationThreadSingleton.get();
    }

    /**
     * @return a shared {@link ShellExecutor} associated with this thread
     * @hide
     */
    @NonNull
    public ShellExecutor getExecutor() {
        if (mExecutor == null) {
            mExecutor = new HandlerExecutor(getThreadHandler());
        }
        return mExecutor;
    }

    private static final Singleton<AnimationThread> sAnimationThreadSingleton =
            new Singleton<AnimationThread>() {
                @Override
                protected AnimationThread create() {
                    final AnimationThread animThread = new AnimationThread();
                    animThread.start();
                    return animThread;
                }
            };
}
