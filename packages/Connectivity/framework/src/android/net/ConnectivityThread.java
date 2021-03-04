/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net;

import android.os.HandlerThread;
import android.os.Looper;

/**
 * Shared singleton connectivity thread for the system.  This is a thread for
 * connectivity operations such as AsyncChannel connections to system services.
 * Various connectivity manager objects can use this singleton as a common
 * resource for their handlers instead of creating separate threads of their own.
 * @hide
 */
public final class ConnectivityThread extends HandlerThread {

    // A class implementing the lazy holder idiom: the unique static instance
    // of ConnectivityThread is instantiated in a thread-safe way (guaranteed by
    // the language specs) the first time that Singleton is referenced in get()
    // or getInstanceLooper().
    private static class Singleton {
        private static final ConnectivityThread INSTANCE = createInstance();
    }

    private ConnectivityThread() {
        super("ConnectivityThread");
    }

    private static ConnectivityThread createInstance() {
        ConnectivityThread t = new ConnectivityThread();
        t.start();
        return t;
    }

    public static ConnectivityThread get() {
        return Singleton.INSTANCE;
    }

    public static Looper getInstanceLooper() {
        return Singleton.INSTANCE.getLooper();
    }
}
