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

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_INST_RESOURCE_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_RESOURCE_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERBOSE_LOGGING;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERSION_JAVA_SYSPROP;
import static com.android.ravenwood.common.RavenwoodCommonUtils.getRavenwoodRuntimePath;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.ResourcesManager;
import android.app.UiAutomation;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.hoststubgen.hosthelper.HostTestUtils;
import com.android.internal.os.RuntimeInit;
import com.android.ravenwood.RavenwoodRuntimeNative;
import com.android.ravenwood.common.RavenwoodCommonUtils;
import com.android.ravenwood.common.RavenwoodRuntimeException;
import com.android.ravenwood.common.SneakyThrow;
import com.android.server.LocalServices;

import org.junit.runner.Description;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Responsible for initializing and de-initializing the environment, according to a
 * {@link RavenwoodConfig}.
 */
public class RavenwoodRuntimeEnvironmentController {
    private static final String TAG = "RavenwoodRuntimeEnvironmentController";

    private RavenwoodRuntimeEnvironmentController() {
    }

    private static final String MAIN_THREAD_NAME = "RavenwoodMain";
    private static final String RAVENWOOD_NATIVE_SYSPROP_NAME = "ravenwood_sysprop";
    private static final String RAVENWOOD_NATIVE_RUNTIME_NAME = "ravenwood_runtime";
    private static final String RAVENWOOD_BUILD_PROP =
            getRavenwoodRuntimePath() + "ravenwood-data/build.prop";

    /**
     * When enabled, attempt to dump all thread stacks just before we hit the
     * overall Tradefed timeout, to aid in debugging deadlocks.
     */
    private static final boolean ENABLE_TIMEOUT_STACKS =
            "1".equals(System.getenv("RAVENWOOD_ENABLE_TIMEOUT_STACKS"));

    private static final int TIMEOUT_MILLIS = 9_000;

    private static final ScheduledExecutorService sTimeoutExecutor =
            Executors.newScheduledThreadPool(1);

    private static ScheduledFuture<?> sPendingTimeout;

    private static long sOriginalIdentityToken = -1;

    /**
     * When enabled, attempt to detect uncaught exceptions from background threads.
     */
    private static final boolean ENABLE_UNCAUGHT_EXCEPTION_DETECTION =
            "1".equals(System.getenv("RAVENWOOD_ENABLE_UNCAUGHT_EXCEPTION_DETECTION"));

    /**
     * When set, an unhandled exception was discovered (typically on a background thread), and we
     * capture it here to ensure it's reported as a test failure.
     */
    private static final AtomicReference<Throwable> sPendingUncaughtException =
            new AtomicReference<>();

    private static final Thread.UncaughtExceptionHandler sUncaughtExceptionHandler =
            (thread, throwable) -> {
                // Remember the first exception we discover
                sPendingUncaughtException.compareAndSet(null, throwable);
            };

    // TODO: expose packCallingIdentity function in libbinder and use it directly
    // See: packCallingIdentity in frameworks/native/libs/binder/IPCThreadState.cpp
    private static long packBinderIdentityToken(
            boolean hasExplicitIdentity, int callingUid, int callingPid) {
        long res = ((long) callingUid << 32) | callingPid;
        if (hasExplicitIdentity) {
            res |= (0x1 << 30);
        } else {
            res &= ~(0x1 << 30);
        }
        return res;
    }

    private static RavenwoodConfig sConfig;
    private static RavenwoodSystemProperties sProps;
    // TODO: use the real UiAutomation class instead of a mock
    private static UiAutomation sMockUiAutomation;
    private static Set<String> sAdoptedPermissions = Collections.emptySet();
    private static boolean sInitialized = false;

    /**
     * Initialize the global environment.
     */
    public static void globalInitOnce() {
        if (sInitialized) {
            return;
        }
        sInitialized = true;

        // We haven't initialized liblog yet, so directly write to System.out here.
        RavenwoodCommonUtils.log(TAG, "globalInit()");

        // Load libravenwood_sysprop first
        var libProp = RavenwoodCommonUtils.getJniLibraryPath(RAVENWOOD_NATIVE_SYSPROP_NAME);
        System.load(libProp);
        RavenwoodRuntimeNative.reloadNativeLibrary(libProp);

        // Make sure libravenwood_runtime is loaded.
        System.load(RavenwoodCommonUtils.getJniLibraryPath(RAVENWOOD_NATIVE_RUNTIME_NAME));

        // Do the basic set up for the android sysprops.
        RavenwoodSystemProperties.initialize(RAVENWOOD_BUILD_PROP);
        setSystemProperties(null);

        // Make sure libandroid_runtime is loaded.
        RavenwoodNativeLoader.loadFrameworkNativeCode();

        // Redirect stdout/stdin to liblog.
        RuntimeInit.redirectLogStreams();

        if (RAVENWOOD_VERBOSE_LOGGING) {
            RavenwoodCommonUtils.log(TAG, "Force enabling verbose logging");
            try {
                Os.setenv("ANDROID_LOG_TAGS", "*:v", true);
            } catch (ErrnoException e) {
                // Shouldn't happen.
            }
        }

        System.setProperty(RAVENWOOD_VERSION_JAVA_SYSPROP, "1");
        // This will let AndroidJUnit4 use the original runner.
        System.setProperty("android.junit.runner",
                "androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner");

        assertMockitoVersion();
        sMockUiAutomation = createMockUiAutomation();
    }

    /**
     * Initialize the environment.
     */
    public static void init(RavenwoodConfig config) throws IOException {
        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.i(TAG, "init() called here", new RuntimeException("STACKTRACE"));
        }
        try {
            initInner(config);
        } catch (Exception th) {
            Log.e(TAG, "init() failed", th);
            reset();
            SneakyThrow.sneakyThrow(th);
        }
    }

    private static void initInner(RavenwoodConfig config) throws IOException {
        if (sConfig != null) {
            throw new RavenwoodRuntimeException("Internal error: init() called without reset()");
        }
        sConfig = config;
        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            maybeThrowPendingUncaughtException(false);
            Thread.setDefaultUncaughtExceptionHandler(sUncaughtExceptionHandler);
        }

        android.os.Process.init$ravenwood(config.mUid, config.mPid);
        sOriginalIdentityToken = Binder.clearCallingIdentity();
        Binder.restoreCallingIdentity(packBinderIdentityToken(false, config.mUid, config.mPid));
        setSystemProperties(config.mSystemProperties);

        ServiceManager.init$ravenwood();
        LocalServices.removeAllServicesForTest();

        ActivityManager.init$ravenwood(config.mCurrentUser);

        final var main = new HandlerThread(MAIN_THREAD_NAME);
        main.start();
        Looper.setMainLooperForTest(main.getLooper());

        final boolean isSelfInstrumenting =
                Objects.equals(config.mTestPackageName, config.mTargetPackageName);

        // This will load the resources from the apk set to `resource_apk` in the build file.
        // This is supposed to be the "target app"'s resources.
        final Supplier<Resources> targetResourcesLoader = () -> {
            var file = new File(RAVENWOOD_RESOURCE_APK);
            return config.mState.loadResources(file.exists() ? file : null);
        };

        // Set up test context's (== instrumentation context's) resources.
        // If the target package name == test package name, then we use the main resources.
        final Supplier<Resources> instResourcesLoader;
        if (isSelfInstrumenting) {
            instResourcesLoader = targetResourcesLoader;
        } else {
            instResourcesLoader = () -> {
                var file = new File(RAVENWOOD_INST_RESOURCE_APK);
                return config.mState.loadResources(file.exists() ? file : null);
            };
        }

        var instContext = new RavenwoodContext(
                config.mTestPackageName, main, instResourcesLoader);
        var targetContext = new RavenwoodContext(
                config.mTargetPackageName, main, targetResourcesLoader);

        // Set up app context.
        var appContext = new RavenwoodContext(
                config.mTargetPackageName, main, targetResourcesLoader);
        appContext.setApplicationContext(appContext);
        if (isSelfInstrumenting) {
            instContext.setApplicationContext(appContext);
            targetContext.setApplicationContext(appContext);
        } else {
            // When instrumenting into another APK, the test context doesn't have an app context.
            targetContext.setApplicationContext(appContext);
        }
        config.mInstContext = instContext;
        config.mTargetContext = targetContext;

        // Prepare other fields.
        config.mInstrumentation = new Instrumentation();
        config.mInstrumentation.basicInit(instContext, targetContext, sMockUiAutomation);
        InstrumentationRegistry.registerInstance(config.mInstrumentation, Bundle.EMPTY);

        RavenwoodSystemServer.init(config);

        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout = sTimeoutExecutor.schedule(
                    RavenwoodRuntimeEnvironmentController::dumpStacks,
                    TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        // Touch some references early to ensure they're <clinit>'ed
        Objects.requireNonNull(Build.TYPE);
        Objects.requireNonNull(Build.VERSION.SDK);
    }

    /**
     * De-initialize.
     */
    public static void reset() {
        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.i(TAG, "reset() called here", new RuntimeException("STACKTRACE"));
        }
        if (sConfig == null) {
            throw new RavenwoodRuntimeException("Internal error: reset() already called");
        }
        var config = sConfig;
        sConfig = null;

        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout.cancel(false);
        }

        RavenwoodSystemServer.reset(config);

        InstrumentationRegistry.registerInstance(null, Bundle.EMPTY);
        config.mInstrumentation = null;
        if (config.mInstContext != null) {
            ((RavenwoodContext) config.mInstContext).cleanUp();
            config.mInstContext = null;
        }
        if (config.mTargetContext != null) {
            ((RavenwoodContext) config.mTargetContext).cleanUp();
            config.mTargetContext = null;
        }
        sMockUiAutomation.dropShellPermissionIdentity();

        Looper.getMainLooper().quit();
        Looper.clearMainLooperForTest();

        ActivityManager.reset$ravenwood();

        LocalServices.removeAllServicesForTest();
        ServiceManager.reset$ravenwood();

        setSystemProperties(null);
        if (sOriginalIdentityToken != -1) {
            Binder.restoreCallingIdentity(sOriginalIdentityToken);
        }
        android.os.Process.reset$ravenwood();

        try {
            ResourcesManager.setInstance(null); // Better structure needed.
        } catch (Exception e) {
            // AOSP-CHANGE: AOSP doesn't support resources yet.
        }

        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            maybeThrowPendingUncaughtException(true);
        }
    }

    public static void logTestRunner(String label, Description description) {
        // This message string carefully matches the exact format emitted by on-device tests, to
        // aid developers in debugging raw text logs
        Log.e("TestRunner", label + ": " + description.getMethodName()
                + "(" + description.getTestClass().getName() + ")");
    }

    private static void dumpStacks() {
        final PrintStream out = System.err;
        out.println("-----BEGIN ALL THREAD STACKS-----");
        final Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> stack : stacks.entrySet()) {
            out.println();
            Thread t = stack.getKey();
            out.println(t.toString() + " ID=" + t.getId());
            for (StackTraceElement e : stack.getValue()) {
                out.println("\tat " + e);
            }
        }
        out.println("-----END ALL THREAD STACKS-----");
    }

    /**
     * If there's a pending uncaught exception, consume and throw it now. Typically used to
     * report an exception on a background thread as a failure for the currently running test.
     */
    private static void maybeThrowPendingUncaughtException(boolean duringReset) {
        final Throwable pending = sPendingUncaughtException.getAndSet(null);
        if (pending != null) {
            if (duringReset) {
                throw new IllegalStateException(
                        "Found an uncaught exception during this test", pending);
            } else {
                throw new IllegalStateException(
                        "Found an uncaught exception before this test started", pending);
            }
        }
    }

    /**
     * Set the current configuration to the actual SystemProperties.
     */
    private static void setSystemProperties(@Nullable RavenwoodSystemProperties systemProperties) {
        SystemProperties.clearChangeCallbacksForTest();
        RavenwoodRuntimeNative.clearSystemProperties();
        if (systemProperties == null) systemProperties = new RavenwoodSystemProperties();
        sProps = new RavenwoodSystemProperties(systemProperties, true);
        for (var entry : systemProperties.getValues().entrySet()) {
            RavenwoodRuntimeNative.setSystemProperty(entry.getKey(), entry.getValue());
        }
    }

    private static final String MOCKITO_ERROR = "FATAL: Unsupported Mockito detected!"
            + " Your test or its dependencies use one of the \"mockito-target-*\""
            + " modules as static library, which is unusable on host side."
            + " Please switch over to use \"mockito-ravenwood-prebuilt\" as shared library, or"
            + " as a last resort, set `ravenizer: { strip_mockito: true }` in your test module.";

    /**
     * Assert the Mockito version at runtime to ensure no incorrect Mockito classes are loaded.
     */
    private static void assertMockitoVersion() {
        // DexMaker should not exist
        assertThrows(
                MOCKITO_ERROR,
                ClassNotFoundException.class,
                () -> Class.forName("com.android.dx.DexMaker"));
        // Mockito 2 should not exist
        assertThrows(
                MOCKITO_ERROR,
                ClassNotFoundException.class,
                () -> Class.forName("org.mockito.Matchers"));
    }

    private static UiAutomation createMockUiAutomation() {
        var mock = mock(UiAutomation.class, inv -> {
            HostTestUtils.onThrowMethodCalled();
            return null;
        });
        doAnswer(inv -> {
            sAdoptedPermissions = UiAutomation.ALL_PERMISSIONS;
            return null;
        }).when(mock).adoptShellPermissionIdentity();
        doAnswer(inv -> {
            if (inv.getArgument(0) == null) {
                sAdoptedPermissions = UiAutomation.ALL_PERMISSIONS;
            } else {
                sAdoptedPermissions = (Set) Set.of(inv.getArguments());
            }
            return null;
        }).when(mock).adoptShellPermissionIdentity(any());
        doAnswer(inv -> {
            sAdoptedPermissions = Collections.emptySet();
            return null;
        }).when(mock).dropShellPermissionIdentity();
        doAnswer(inv -> sAdoptedPermissions).when(mock).getAdoptedShellPermissions();
        return mock;
    }

    @SuppressWarnings("unused")  // Called from native code (ravenwood_sysprop.cpp)
    private static void checkSystemPropertyAccess(String key, boolean write) {
        boolean result = write ? sProps.isKeyWritable(key) : sProps.isKeyReadable(key);
        if (!result) {
            throw new IllegalArgumentException((write ? "Write" : "Read")
                    + " access to system property '" + key + "' denied via RavenwoodConfig");
        }
    }
}
