/*
 * Copyright (C) 2023 The Android Open Source Project
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
package android.platform.test.ravenwood;

import static com.android.ravenwood.common.RavenwoodCommonUtils.ReflectedMethod.reflectMethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;

import com.android.ravenwood.common.RavenwoodCommonUtils;
import com.android.ravenwood.common.SneakyThrow;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Utilities for writing (bivalent) ravenwood tests.
 */
public class RavenwoodUtils {
    private RavenwoodUtils() {
    }

    /**
     * Load a JNI library respecting {@code java.library.path}
     * (which reflects {@code LD_LIBRARY_PATH}).
     *
     * <p>{@code libname} must be the library filename without:
     * - directory
     * - "lib" prefix
     * - and the ".so" extension
     *
     * <p>For example, in order to load "libmyjni.so", then pass "myjni".
     *
     * <p>This is basically the same thing as Java's {@link System#loadLibrary(String)},
     * but this API works slightly different on ART and on the desktop Java, namely
     * the desktop Java version uses a different entry point method name
     * {@code JNI_OnLoad_libname()} (note the included "libname")
     * while ART always seems to use {@code JNI_OnLoad()}.
     *
     * <p>This method provides the same behavior on both the device side and on Ravenwood --
     * it uses {@code JNI_OnLoad()} as the entry point name on both.
     */
    public static void loadJniLibrary(String libname) {
        RavenwoodCommonUtils.loadJniLibrary(libname);
    }

    private class MainHandlerHolder {
        static Handler sMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Returns the main thread handler.
     */
    public static Handler getMainHandler() {
        return MainHandlerHolder.sMainHandler;
    }

    /**
     * Run a Callable on Handler and wait for it to complete.
     */
    @Nullable
    public static <T> T runOnHandlerSync(@NonNull Handler h, @NonNull Callable<T> c) {
        var result = new AtomicReference<T>();
        var thrown = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        h.post(() -> {
            try {
                result.set(c.call());
            } catch (Throwable th) {
                thrown.set(th);
            }
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while waiting on the Runnable", e);
        }
        var th = thrown.get();
        if (th != null) {
            SneakyThrow.sneakyThrow(th);
        }
        return result.get();
    }


    /**
     * Run a Runnable on Handler and wait for it to complete.
     */
    @Nullable
    public static void runOnHandlerSync(@NonNull Handler h, @NonNull Runnable r) {
        runOnHandlerSync(h, () -> {
            r.run();
            return null;
        });
    }

    /**
     * Run a Callable on main thread and wait for it to complete.
     */
    @Nullable
    public static <T> T runOnMainThreadSync(@NonNull Callable<T> c) {
        return runOnHandlerSync(getMainHandler(), c);
    }

    /**
     * Run a Runnable on main thread and wait for it to complete.
     */
    @Nullable
    public static void runOnMainThreadSync(@NonNull Runnable r) {
        runOnHandlerSync(getMainHandler(), r);
    }

    public static class MockitoHelper {
        private MockitoHelper() {
        }

        /**
         * Allow verifyZeroInteractions to work on ravenwood. It was replaced with a different
         * method on. (Maybe we should do it in Ravenizer.)
         */
        public static void verifyZeroInteractions(Object... mocks) {
            if (RavenwoodRule.isOnRavenwood()) {
                // Mockito 4 or later
                reflectMethod("org.mockito.Mockito", "verifyNoInteractions", Object[].class)
                        .callStatic(new Object[]{mocks});
            } else {
                // Mockito 2
                reflectMethod("org.mockito.Mockito", "verifyZeroInteractions", Object[].class)
                        .callStatic(new Object[]{mocks});
            }
        }
    }


    /**
     * Wrap the given {@link Supplier} to become memoized.
     *
     * The underlying {@link Supplier} will only be invoked once, and that result will be cached
     * and returned for any future requests.
     */
    static <T> Supplier<T> memoize(ThrowingSupplier<T> supplier) {
        return new Supplier<>() {
            private T mInstance;

            @Override
            public T get() {
                synchronized (this) {
                    if (mInstance == null) {
                        mInstance = create();
                    }
                    return mInstance;
                }
            }

            private T create() {
                try {
                    return supplier.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /** Used by {@link #memoize(ThrowingSupplier)}  */
    public interface ThrowingSupplier<T> {
        /** */
        T get() throws Exception;
    }
}
