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

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.UserHandle.SYSTEM;
import static android.platform.test.ravenwood.RavenwoodSystemServer.ANDROID_PACKAGE_NAME;

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_EMPTY_RESOURCES_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_INST_RESOURCE_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_RESOURCE_APK;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERBOSE_LOGGING;
import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERSION_JAVA_SYSPROP;
import static com.android.ravenwood.common.RavenwoodCommonUtils.parseNullableInt;
import static com.android.ravenwood.common.RavenwoodCommonUtils.withDefault;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppCompatCallbacks;
import android.app.Instrumentation;
import android.app.ResourcesManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process_ravenwood;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.SystemProperties;
import android.provider.DeviceConfig_host;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.util.Log_ravenwood;
import android.view.DisplayAdjustments;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.hoststubgen.hosthelper.HostTestUtils;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.RuntimeInit;
import com.android.ravenwood.RavenwoodRuntimeNative;
import com.android.ravenwood.RavenwoodRuntimeState;
import com.android.ravenwood.common.RavenwoodCommonUtils;
import com.android.ravenwood.common.SneakyThrow;
import com.android.server.LocalServices;
import com.android.server.compat.PlatformCompat;

import org.junit.internal.management.ManagementFactory;
import org.junit.runner.Description;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Responsible for initializing and the environment.
 */
public class RavenwoodRuntimeEnvironmentController {
    private static final String TAG = "RavenwoodRuntimeEnvironmentController";

    private RavenwoodRuntimeEnvironmentController() {
    }

    private static final String MAIN_THREAD_NAME = "RavenwoodMain";
    private static final String LIBRAVENWOOD_INITIALIZER_NAME = "ravenwood_initializer";
    private static final String RAVENWOOD_NATIVE_RUNTIME_NAME = "ravenwood_runtime";

    private static final String ANDROID_LOG_TAGS = "ANDROID_LOG_TAGS";
    private static final String RAVENWOOD_ANDROID_LOG_TAGS = "RAVENWOOD_" + ANDROID_LOG_TAGS;

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

    /** Map from path -> resources. */
    private static final HashMap<File, Resources> sCachedResources = new HashMap<>();
    private static Set<String> sAdoptedPermissions = Collections.emptySet();

    private static final Object sInitializationLock = new Object();

    @GuardedBy("sInitializationLock")
    private static boolean sInitialized = false;

    @GuardedBy("sInitializationLock")
    private static Throwable sExceptionFromGlobalInit;

    private static final int DEFAULT_TARGET_SDK_LEVEL = VERSION_CODES.CUR_DEVELOPMENT;
    private static final String DEFAULT_PACKAGE_NAME = "com.android.ravenwoodtests.defaultname";

    private static final int sMyPid = new Random().nextInt(100, 32768);
    private static int sTargetSdkLevel;
    private static String sTestPackageName;
    private static String sTargetPackageName;
    private static Instrumentation sInstrumentation;
    private static final long sCallingIdentity =
            packBinderIdentityToken(false, FIRST_APPLICATION_UID, sMyPid);

    /**
     * Initialize the global environment.
     */
    public static void globalInitOnce() {
        synchronized (sInitializationLock) {
            if (!sInitialized) {
                // globalInitOnce() is called from class initializer, which cause
                // this method to be called recursively,
                sInitialized = true;

                // This is the first call.
                try {
                    globalInitInner();
                } catch (Throwable th) {
                    Log.e(TAG, "globalInit() failed", th);

                    sExceptionFromGlobalInit = th;
                    SneakyThrow.sneakyThrow(th);
                }
            } else {
                // Subsequent calls. If the first call threw, just throw the same error, to prevent
                // the test from running.
                if (sExceptionFromGlobalInit != null) {
                    Log.e(TAG, "globalInit() failed re-throwing the same exception",
                            sExceptionFromGlobalInit);

                    SneakyThrow.sneakyThrow(sExceptionFromGlobalInit);
                }
            }
        }
    }

    private static void globalInitInner() throws IOException {
        if (RAVENWOOD_VERBOSE_LOGGING) {
            Log.v(TAG, "globalInit() called here...", new RuntimeException("NOT A CRASH"));
        }
        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            Thread.setDefaultUncaughtExceptionHandler(sUncaughtExceptionHandler);
        }

        // Some process-wide initialization:
        // - maybe redirect stdout/stderr
        // - override native system property functions
        var lib = RavenwoodCommonUtils.getJniLibraryPath(LIBRAVENWOOD_INITIALIZER_NAME);
        System.load(lib);
        RavenwoodRuntimeNative.reloadNativeLibrary(lib);

        // Redirect stdout/stdin to the Log API.
        RuntimeInit.redirectLogStreams();

        dumpCommandLineArgs();

        // We haven't initialized liblog yet, so directly write to System.out here.
        RavenwoodCommonUtils.log(TAG, "globalInitInner()");

        // Make sure libravenwood_runtime is loaded.
        System.load(RavenwoodCommonUtils.getJniLibraryPath(RAVENWOOD_NATIVE_RUNTIME_NAME));

        Log_ravenwood.setLogLevels(getLogTags());
        Log_ravenwood.onRavenwoodRuntimeNativeReady();

        // Do the basic set up for the android sysprops.
        RavenwoodSystemProperties.initialize();

        // Enable all log levels for native logging, until we'll have a way to change the native
        // side log level at runtime.
        // Do this after loading RAVENWOOD_NATIVE_RUNTIME_NAME (which backs Os.setenv()),
        // before loadFrameworkNativeCode() (which uses $ANDROID_LOG_TAGS).
        // This would also prevent libbase from crashing the process (b/381112373) because
        // the string format it accepts is very limited.
        try {
            Os.setenv("ANDROID_LOG_TAGS", "*:v", true);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }

        // Make sure libandroid_runtime is loaded.
        RavenwoodNativeLoader.loadFrameworkNativeCode();

        // Touch some references early to ensure they're <clinit>'ed
        Objects.requireNonNull(Build.TYPE);
        Objects.requireNonNull(Build.VERSION.SDK);

        System.setProperty(RAVENWOOD_VERSION_JAVA_SYSPROP, "1");
        // This will let AndroidJUnit4 use the original runner.
        System.setProperty("android.junit.runner",
                "androidx.test.internal.runner.junit4.AndroidJUnit4ClassRunner");

        loadRavenwoodProperties();

        assertMockitoVersion();

        Log.i(TAG, "TargetPackageName=" + sTargetPackageName);
        Log.i(TAG, "TestPackageName=" + sTestPackageName);
        Log.i(TAG, "TargetSdkLevel=" + sTargetSdkLevel);

        RavenwoodRuntimeState.sUid = FIRST_APPLICATION_UID;
        RavenwoodRuntimeState.sPid = sMyPid;
        RavenwoodRuntimeState.sTargetSdkLevel = sTargetSdkLevel;

        ServiceManager.init$ravenwood();
        LocalServices.removeAllServicesForTest();

        ActivityManager.init$ravenwood(SYSTEM.getIdentifier());

        final var main = new HandlerThread(MAIN_THREAD_NAME);
        main.start();
        Looper.setMainLooperForTest(main.getLooper());

        final boolean isSelfInstrumenting =
                Objects.equals(sTestPackageName, sTargetPackageName);

        // This will load the resources from the apk set to `resource_apk` in the build file.
        // This is supposed to be the "target app"'s resources.
        final Supplier<Resources> targetResourcesLoader = () -> {
            var file = new File(RAVENWOOD_RESOURCE_APK);
            return loadResources(file.exists() ? file : null);
        };

        // Set up test context's (== instrumentation context's) resources.
        // If the target package name == test package name, then we use the main resources.
        final Supplier<Resources> instResourcesLoader;
        if (isSelfInstrumenting) {
            instResourcesLoader = targetResourcesLoader;
        } else {
            instResourcesLoader = () -> {
                var file = new File(RAVENWOOD_INST_RESOURCE_APK);
                return loadResources(file.exists() ? file : null);
            };
        }

        var instContext = new RavenwoodContext(
                sTestPackageName, main, instResourcesLoader);
        var targetContext = new RavenwoodContext(
                sTargetPackageName, main, targetResourcesLoader);

        // Set up app context.
        var appContext = new RavenwoodContext(sTargetPackageName, main, targetResourcesLoader);
        appContext.setApplicationContext(appContext);
        if (isSelfInstrumenting) {
            instContext.setApplicationContext(appContext);
            targetContext.setApplicationContext(appContext);
        } else {
            // When instrumenting into another APK, the test context doesn't have an app context.
            targetContext.setApplicationContext(appContext);
        }

        final Supplier<Resources> systemResourcesLoader = () -> loadResources(null);

        var systemServerContext =
                new RavenwoodContext(ANDROID_PACKAGE_NAME, main, systemResourcesLoader);

        sInstrumentation = new Instrumentation();
        sInstrumentation.basicInit(instContext, targetContext, null);
        InstrumentationRegistry.registerInstance(sInstrumentation, Bundle.EMPTY);

        RavenwoodSystemServer.init(systemServerContext);

        initializeCompatIds();
    }

    /**
     * Get log tags from environmental variable.
     */
    @Nullable
    private static String getLogTags() {
        var logTags = System.getenv(RAVENWOOD_ANDROID_LOG_TAGS);
        if (logTags == null) {
            logTags = System.getenv(ANDROID_LOG_TAGS);
        }
        return logTags;
    }

    private static void loadRavenwoodProperties() {
        var props = RavenwoodSystemProperties.readProperties("ravenwood.properties");

        sTargetSdkLevel = withDefault(
                parseNullableInt(props.get("targetSdkVersionInt")), DEFAULT_TARGET_SDK_LEVEL);
        sTargetPackageName = withDefault(props.get("packageName"), DEFAULT_PACKAGE_NAME);
        sTestPackageName = withDefault(props.get("instPackageName"), sTargetPackageName);

        // TODO(b/377765941) Read them from the manifest too?
    }

    /**
     * Partially reset and initialize before each test class invocation
     */
    public static void initForRunner() {
        var targetContext = sInstrumentation.getTargetContext();
        var instContext = sInstrumentation.getContext();
        // We need to recreate the mock UiAutomation for each test class, because sometimes tests
        // will call Mockito.framework().clearInlineMocks() after execution.
        sInstrumentation.basicInit(instContext, targetContext, createMockUiAutomation());

        // Reset some global state
        Process_ravenwood.reset();
        DeviceConfig_host.reset();
        Binder.restoreCallingIdentity(sCallingIdentity);

        SystemProperties.clearChangeCallbacksForTest();

        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout = sTimeoutExecutor.schedule(
                    RavenwoodRuntimeEnvironmentController::dumpStacks,
                    TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        if (ENABLE_UNCAUGHT_EXCEPTION_DETECTION) {
            maybeThrowPendingUncaughtException(false);
        }
    }

    /**
     * Partially reset and initialize before each test method invocation
     */
    public static void initForMethod() {
        // TODO(b/375272444): this is a hacky workaround to ensure binder identity
        Binder.restoreCallingIdentity(sCallingIdentity);
    }

    private static void initializeCompatIds() {
        // Set up compat-IDs for the app side.
        // TODO: Inside the system server, all the compat-IDs should be enabled,
        // Due to the `AppCompatCallbacks.install(new long[0], new long[0])` call in
        // SystemServer.

        // Compat framework only uses the package name and the target SDK level.
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = sTargetPackageName;
        appInfo.targetSdkVersion = sTargetSdkLevel;

        PlatformCompat platformCompat = null;
        try {
            platformCompat = (PlatformCompat) ServiceManager.getServiceOrThrow(
                    Context.PLATFORM_COMPAT_SERVICE);
        } catch (ServiceNotFoundException e) {
            throw new RuntimeException(e);
        }

        var disabledChanges = platformCompat.getDisabledChanges(appInfo);
        var loggableChanges = platformCompat.getLoggableChanges(appInfo);

        AppCompatCallbacks.install(disabledChanges, loggableChanges);
    }

    /**
     * Load {@link Resources} from an APK, with cache.
     */
    private static Resources loadResources(@Nullable File apkPath) {
        var cached = sCachedResources.get(apkPath);
        if (cached != null) {
            return cached;
        }

        var fileToLoad = apkPath != null ? apkPath : new File(RAVENWOOD_EMPTY_RESOURCES_APK);

        assertTrue("File " + fileToLoad + " doesn't exist.", fileToLoad.isFile());

        final String path = fileToLoad.getAbsolutePath();
        final var emptyPaths = new String[0];

        ResourcesManager.getInstance().initializeApplicationPaths(path, emptyPaths);

        final var ret = ResourcesManager.getInstance().getResources(null, path,
                emptyPaths, emptyPaths, emptyPaths,
                emptyPaths, null, null,
                new DisplayAdjustments().getCompatibilityInfo(),
                RavenwoodRuntimeEnvironmentController.class.getClassLoader(), null);

        assertNotNull(ret);

        sCachedResources.put(apkPath, ret);
        return ret;
    }

    /**
     * A callback when a test class finishes its execution, mostly only for debugging.
     */
    public static void exitTestClass() {
        if (ENABLE_TIMEOUT_STACKS) {
            sPendingTimeout.cancel(false);
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

    // TODO: use the real UiAutomation class instead of a mock
    private static UiAutomation createMockUiAutomation() {
        sAdoptedPermissions = Collections.emptySet();
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

    private static void dumpCommandLineArgs() {
        Log.i(TAG, "JVM arguments:");

        // Note, we use the wrapper in JUnit4, not the actual class (
        // java.lang.management.ManagementFactory), because we can't see the later at the build
        // because this source file is compiled for the device target, where ManagementFactory
        // doesn't exist.
        var args = ManagementFactory.getRuntimeMXBean().getInputArguments();

        for (var arg : args) {
            Log.i(TAG, "  " + arg);
        }
    }
}
