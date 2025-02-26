/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.ravenwood.common;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.ravenwood.common.divergence.RavenwoodDivergence;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

public class RavenwoodCommonUtils {
    public static final String TAG = "Ravenwood";

    private RavenwoodCommonUtils() {
    }

    /**
     * If set to "1", we enable the verbose logging.
     *
     * (See also InitLogging() in http://ac/system/libbase/logging.cpp)
     */
    public static final boolean RAVENWOOD_VERBOSE_LOGGING = "1".equals(System.getenv(
            "RAVENWOOD_VERBOSE"));

    /** Directory name of `out/host/linux-x86/testcases/ravenwood-runtime` */
    private static final String RAVENWOOD_RUNTIME_DIR_NAME = "ravenwood-runtime";

    private static boolean sEnableExtraRuntimeCheck =
            "1".equals(System.getenv("RAVENWOOD_ENABLE_EXTRA_RUNTIME_CHECK"));

    private static final boolean IS_ON_RAVENWOOD = RavenwoodDivergence.isOnRavenwood();

    private static final String RAVENWOOD_RUNTIME_PATH = getRavenwoodRuntimePathInternal();

    public static final String RAVENWOOD_SYSPROP = "ro.is_on_ravenwood";

    public static final String RAVENWOOD_RESOURCE_APK = "ravenwood-res-apks/ravenwood-res.apk";
    public static final String RAVENWOOD_INST_RESOURCE_APK =
            "ravenwood-res-apks/ravenwood-inst-res.apk";

    public static final String RAVENWOOD_EMPTY_RESOURCES_APK =
            RAVENWOOD_RUNTIME_PATH + "ravenwood-data/ravenwood-empty-res.apk";

    public static final String RAVENWOOD_VERSION_JAVA_SYSPROP = "android.ravenwood.version";

    /**
     * @return if we're running on Ravenwood.
     */
    public static boolean isOnRavenwood() {
        return IS_ON_RAVENWOOD;
    }

    /**
     * Throws if the runtime is not Ravenwood.
     */
    public static void ensureOnRavenwood() {
        if (!isOnRavenwood()) {
            throw new RavenwoodRuntimeException("This is only supposed to be used on Ravenwood");
        }
    }

    /**
     * @return if the various extra runtime check should be enabled.
     */
    public static boolean shouldEnableExtraRuntimeCheck() {
        return sEnableExtraRuntimeCheck;
    }

    /** Simple logging method. */
    public static void log(String tag, String message) {
        // Avoid using Android's Log class, which could be broken for various reasons.
        // (e.g. the JNI file doesn't exist for whatever reason)
        System.out.print(tag + ": " + message + "\n");
    }

    /** Simple logging method. */
    private void log(String tag, String format, Object... args) {
        log(tag, String.format(format, args));
    }

    /**
     * Internal implementation of
     * {@link android.platform.test.ravenwood.RavenwoodUtils#loadJniLibrary(String)}
     */
    public static void loadJniLibrary(String libname) {
        if (RavenwoodCommonUtils.isOnRavenwood()) {
            System.load(getJniLibraryPath(libname));
        } else {
            System.loadLibrary(libname);
        }
    }

    /**
     * Find the shared library path from java.library.path.
     */
    public static String getJniLibraryPath(String libname) {
        var path = System.getProperty("java.library.path");
        var filename = "lib" + libname + ".so";

        System.out.println("Looking for library " + libname + ".so in java.library.path:" + path);

        try {
            if (path == null) {
                throw new UnsatisfiedLinkError("Cannot find library " + libname + "."
                        + " Property java.library.path not set!");
            }
            for (var dir : path.split(":")) {
                var file = new File(dir + "/" + filename);
                if (file.exists()) {
                    return file.getAbsolutePath();
                }
            }
        } catch (Throwable e) {
            dumpFiles(System.out);
            throw e;
        }
        throw new UnsatisfiedLinkError("Library " + libname + " not found in "
                + "java.library.path: " + path);
    }

    private static void dumpFiles(PrintStream out) {
        try {
            var path = System.getProperty("java.library.path");
            out.println("# java.library.path=" + path);

            for (var dir : path.split(":")) {
                listFiles(out, new File(dir), "");

                var gparent = new File((new File(dir)).getAbsolutePath() + "../../..")
                        .getCanonicalFile();
                if (gparent.getName().contains("testcases")) {
                    // Special case: if we found this directory, dump its contents too.
                    listFiles(out, gparent, "");
                }
            }

            var gparent = new File("../..").getCanonicalFile();
            out.println("# ../..=" + gparent);
            listFiles(out, gparent, "");
        } catch (Throwable th) {
            out.println("Error: " + th.toString());
            th.printStackTrace(out);
        }
    }

    private static void listFiles(PrintStream out, File dir, String prefix) {
        if (!dir.isDirectory()) {
            out.println(prefix + dir.getAbsolutePath() + " is not a directory!");
            return;
        }
        out.println(prefix + ":" + dir.getAbsolutePath() + "/");
        // First, list the files.
        for (var file : Arrays.stream(dir.listFiles()).sorted().toList()) {
            out.println(prefix + "  " + file.getName() + "" + (file.isDirectory() ? "/" : ""));
        }

        // Then recurse.
        if (dir.getAbsolutePath().startsWith("/usr") || dir.getAbsolutePath().startsWith("/lib")) {
            // There would be too many files, so don't recurse.
            return;
        }
        for (var file : Arrays.stream(dir.listFiles()).sorted().toList()) {
            if (file.isDirectory()) {
                listFiles(out, file, prefix + "  ");
            }
        }
    }

    /**
     * @return the full directory path that contains the "ravenwood-runtime" files.
     *
     * This method throws if called on the device side.
     */
    public static String getRavenwoodRuntimePath() {
        ensureOnRavenwood();
        return RAVENWOOD_RUNTIME_PATH;
    }

    private static String getRavenwoodRuntimePathInternal() {
        if (!isOnRavenwood()) {
            return null;
        }
        var path = System.getProperty("java.library.path");

        System.out.println("Looking for " + RAVENWOOD_RUNTIME_DIR_NAME + " directory"
                + " in java.library.path:" + path);

        try {
            if (path == null) {
                throw new IllegalStateException("java.library.path shouldn't be null");
            }
            for (var dir : path.split(":")) {

                // For each path, see if the path contains RAVENWOOD_RUNTIME_DIR_NAME.
                var d = new File(dir);
                for (;;) {
                    if (d.getParent() == null) {
                        break; // Root dir, stop.
                    }
                    if (RAVENWOOD_RUNTIME_DIR_NAME.equals(d.getName())) {
                        var ret = d.getAbsolutePath() + "/";
                        System.out.println("Found: " + ret);
                        return ret;
                    }
                    d = d.getParentFile();
                }
            }
            throw new IllegalStateException(RAVENWOOD_RUNTIME_DIR_NAME + " not found");
        } catch (Throwable e) {
            dumpFiles(System.out);
            throw e;
        }
    }

    /** Close an {@link AutoCloseable}. */
    public static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    /** Close a {@link FileDescriptor}. */
    public static void closeQuietly(FileDescriptor fd) {
        var is = new FileInputStream(fd);
        RavenwoodCommonUtils.closeQuietly(is);
    }

    public static void ensureIsPublicVoidMethod(Method method, boolean isStatic) {
        var ok = Modifier.isPublic(method.getModifiers())
                && (Modifier.isStatic(method.getModifiers()) == isStatic)
                && (method.getReturnType() == void.class);
        if (ok) {
            return; // okay
        }
        throw new AssertionError(String.format(
                "Method %s.%s() expected to be public %svoid",
                method.getDeclaringClass().getName(), method.getName(),
                (isStatic ? "static " : "")));
    }

    public static void ensureIsPublicMember(Member member, boolean isStatic) {
        var ok = Modifier.isPublic(member.getModifiers())
                && (Modifier.isStatic(member.getModifiers()) == isStatic);
        if (ok) {
            return; // okay
        }
        throw new AssertionError(String.format(
                "%s.%s expected to be public %s",
                member.getDeclaringClass().getName(), member.getName(),
                (isStatic ? "static" : "")));
    }

    /**
     * Run a supplier and swallow the exception, if any.
     *
     * It's a dangerous function. Only use it in an exception handler where we don't want to crash.
     */
    @Nullable
    public static <T> T runIgnoringException(@NonNull Supplier<T> s) {
        try {
            return s.get();
        } catch (Throwable th) {
            log(TAG, "Warning: Exception detected! " + getStackTraceString(th));
        }
        return null;
    }

    /**
     * Run a runnable and swallow the exception, if any.
     *
     * It's a dangerous function. Only use it in an exception handler where we don't want to crash.
     */
    public static void runIgnoringException(@NonNull Runnable r) {
        runIgnoringException(() -> {
            r.run();
            return null;
        });
    }

    @NonNull
    public static String getStackTraceString(@NonNull Throwable th) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        th.printStackTrace(writer);
        return stringWriter.toString();
    }

    /** Same as {@link Integer#parseInt(String)} but accepts null and returns null. */
    @Nullable
    public static Integer parseNullableInt(@Nullable String value) {
        if (value == null) {
            return null;
        }
        return Integer.parseInt(value);
    }

    /**
     * @return {@code value} if it's non-null. Otherwise, returns {@code def}.
     */
    @Nullable
    public static <T> T withDefault(@Nullable T value, @Nullable T def) {
        return value != null ? value : def;
    }

    /**
     * Utility for calling a method with reflections. Used to call a method by name.
     * Note, this intentionally does _not_ support non-public methods, as we generally
     * shouldn't violate java visibility in ravenwood.
     *
     * @param <TTHIS> class owning the method.
     */
    public static class ReflectedMethod<TTHIS> {
        private final Class<TTHIS> mThisClass;
        private final Method mMethod;

        private ReflectedMethod(Class<TTHIS> thisClass, Method method) {
            mThisClass = thisClass;
            mMethod = method;
        }

        /** Factory method. */
        @SuppressWarnings("unchecked")
        public static <TTHIS> ReflectedMethod<TTHIS> reflectMethod(
                @NonNull Class<TTHIS> clazz, @NonNull String methodName,
                @NonNull Class<?>... argTypes) {
            try {
                return new ReflectedMethod(clazz, clazz.getMethod(methodName, argTypes));
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        /** Factory method. */
        @SuppressWarnings("unchecked")
        public static <TTHIS> ReflectedMethod<TTHIS> reflectMethod(
                @NonNull String className, @NonNull String methodName,
                @NonNull Class<?>... argTypes) {
            try {
                return reflectMethod((Class<TTHIS>) Class.forName(className), methodName, argTypes);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        /** Call the instance method */
        @SuppressWarnings("unchecked")
        public <RET> RET call(@NonNull TTHIS thisObject, @NonNull Object... args) {
            try {
                return (RET) mMethod.invoke(Objects.requireNonNull(thisObject), args);
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        /** Call the static method */
        @SuppressWarnings("unchecked")
        public <RET> RET callStatic(@NonNull Object... args) {
            try {
                return (RET) mMethod.invoke(null, args);
            } catch (InvocationTargetException | IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** Handy method to create an array */
    public static <T> T[] arr(@NonNull T... objects) {
        return objects;
    }
}
