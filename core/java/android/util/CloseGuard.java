/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodReplace;

/**
 * CloseGuard is a mechanism for flagging implicit finalizer cleanup of
 * resources that should have been cleaned up by explicit close
 * methods (aka "explicit termination methods" in Effective Java).
 * <p>
 * A simple example: <pre>   {@code
 *   class Foo {
 *
 *       private final CloseGuard guard = new CloseGuard();
 *
 *       ...
 *
 *       public Foo() {
 *           ...;
 *           guard.open("cleanup");
 *       }
 *
 *       public void cleanup() {
 *          guard.close();
 *          ...;
 *          if (Build.VERSION.SDK_INT >= 28) {
 *              Reference.reachabilityFence(this);
 *          }
 *          // For full correctness in the absence of a close() call, other methods may also need
 *          // reachabilityFence() calls.
 *       }
 *
 *       protected void finalize() throws Throwable {
 *           try {
 *               // Note that guard could be null if the constructor threw.
 *               if (guard != null) {
 *                   guard.warnIfOpen();
 *               }
 *               cleanup();
 *           } finally {
 *               super.finalize();
 *           }
 *       }
 *   }
 * }</pre>
 *
 * In usage where the resource to be explicitly cleaned up is
 * allocated after object construction, CloseGuard protection can
 * be deferred. For example: <pre>   {@code
 *   class Bar {
 *
 *       private final CloseGuard guard = new CloseGuard();
 *
 *       ...
 *
 *       public Bar() {
 *           ...;
 *       }
 *
 *       public void connect() {
 *          ...;
 *          guard.open("cleanup");
 *       }
 *
 *       public void cleanup() {
 *          guard.close();
 *          ...;
 *          if (Build.VERSION.SDK_INT >= 28) {
 *              Reference.reachabilityFence(this);
 *          }
 *          // For full correctness in the absence of a close() call, other methods may also need
 *          // reachabilityFence() calls.
 *       }
 *
 *       protected void finalize() throws Throwable {
 *           try {
 *               // Note that guard could be null if the constructor threw.
 *               if (guard != null) {
 *                   guard.warnIfOpen();
 *               }
 *               cleanup();
 *           } finally {
 *               super.finalize();
 *           }
 *       }
 *   }
 * }</pre>
 *
 * When used in a constructor, calls to {@code open} should occur at
 * the end of the constructor since an exception that would cause
 * abrupt termination of the constructor will mean that the user will
 * not have a reference to the object to cleanup explicitly. When used
 * in a method, the call to {@code open} should occur just after
 * resource acquisition.
 */
@RavenwoodKeepWholeClass
public final class CloseGuard {
    private final dalvik.system.CloseGuard mImpl;

    /**
     * Constructs a new CloseGuard instance.
     * {@link #open(String)} can be used to set up the instance to warn on failure to close.
     *
     * @hide
     */
    public static CloseGuard get() {
        return new CloseGuard();
    }

    /**
     * Constructs a new CloseGuard instance.
     * {@link #open(String)} can be used to set up the instance to warn on failure to close.
     */
    public CloseGuard() {
        mImpl = getImpl();
    }

    @RavenwoodReplace
    private dalvik.system.CloseGuard getImpl() {
        return dalvik.system.CloseGuard.get();
    }

    private dalvik.system.CloseGuard getImpl$ravenwood() {
        return null;
    }

    /**
     * Initializes the instance with a warning that the caller should have explicitly called the
     * {@code closeMethodName} method instead of relying on finalization.
     *
     * @param closeMethodName non-null name of explicit termination method. Printed by warnIfOpen.
     * @throws NullPointerException if closeMethodName is null.
     */
    public void open(@NonNull String closeMethodName) {
        if (mImpl != null) {
            mImpl.open(closeMethodName);
        }
    }

    /** Marks this CloseGuard instance as closed to avoid warnings on finalization. */
    public void close() {
        if (mImpl != null) {
            mImpl.close();
        }
    }

    /**
     * Logs a warning if the caller did not properly cleanup by calling an explicit close method
     * before finalization.
     */
    public void warnIfOpen() {
        if (mImpl != null) {
            mImpl.warnIfOpen();
        }
    }
}
