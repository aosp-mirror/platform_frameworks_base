/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.text.TextUtils;
import android.util.ArrayMap;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.Trace;
import android.os.UserHandle;
import android.util.AndroidRuntimeException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayAdjustments;
import android.view.Display;
import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;

final class IntentReceiverLeaked extends AndroidRuntimeException {
    public IntentReceiverLeaked(String msg) {
        super(msg);
    }
}

final class ServiceConnectionLeaked extends AndroidRuntimeException {
    public ServiceConnectionLeaked(String msg) {
        super(msg);
    }
}

/**
 * Local state maintained about a currently loaded .apk.
 * @hide
 */
public final class LoadedApk {

    private static final String TAG = "LoadedApk";

    private final ActivityThread mActivityThread;
    private ApplicationInfo mApplicationInfo;
    final String mPackageName;
    private final String mAppDir;
    private final String mResDir;
    private final String[] mSplitAppDirs;
    private final String[] mSplitResDirs;
    private final String[] mOverlayDirs;
    private final String[] mSharedLibraries;
    private final String mDataDir;
    private final String mLibDir;
    private final File mDataDirFile;
    private final ClassLoader mBaseClassLoader;
    private final boolean mSecurityViolation;
    private final boolean mIncludeCode;
    private final boolean mRegisterPackage;
    private final DisplayAdjustments mDisplayAdjustments = new DisplayAdjustments();
    Resources mResources;
    private ClassLoader mClassLoader;
    private Application mApplication;

    private final ArrayMap<Context, ArrayMap<BroadcastReceiver, ReceiverDispatcher>> mReceivers
        = new ArrayMap<Context, ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>>();
    private final ArrayMap<Context, ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>> mUnregisteredReceivers
        = new ArrayMap<Context, ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>>();
    private final ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mServices
        = new ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>>();
    private final ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>> mUnboundServices
        = new ArrayMap<Context, ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>>();

    int mClientCount = 0;

    Application getApplication() {
        return mApplication;
    }

    /**
     * Create information about a new .apk
     *
     * NOTE: This constructor is called with ActivityThread's lock held,
     * so MUST NOT call back out to the activity manager.
     */
    public LoadedApk(ActivityThread activityThread, ApplicationInfo aInfo,
            CompatibilityInfo compatInfo, ClassLoader baseLoader,
            boolean securityViolation, boolean includeCode, boolean registerPackage) {
        final int myUid = Process.myUid();
        aInfo = adjustNativeLibraryPaths(aInfo);

        mActivityThread = activityThread;
        mApplicationInfo = aInfo;
        mPackageName = aInfo.packageName;
        mAppDir = aInfo.sourceDir;
        mResDir = aInfo.uid == myUid ? aInfo.sourceDir : aInfo.publicSourceDir;
        mSplitAppDirs = aInfo.splitSourceDirs;
        mSplitResDirs = aInfo.uid == myUid ? aInfo.splitSourceDirs : aInfo.splitPublicSourceDirs;
        mOverlayDirs = aInfo.resourceDirs;
        if (!UserHandle.isSameUser(aInfo.uid, myUid) && !Process.isIsolated()) {
            aInfo.dataDir = PackageManager.getDataDirForUser(UserHandle.getUserId(myUid),
                    mPackageName);
        }
        mSharedLibraries = aInfo.sharedLibraryFiles;
        mDataDir = aInfo.dataDir;
        mDataDirFile = mDataDir != null ? new File(mDataDir) : null;
        mLibDir = aInfo.nativeLibraryDir;
        mBaseClassLoader = baseLoader;
        mSecurityViolation = securityViolation;
        mIncludeCode = includeCode;
        mRegisterPackage = registerPackage;
        mDisplayAdjustments.setCompatibilityInfo(compatInfo);
    }

    private static ApplicationInfo adjustNativeLibraryPaths(ApplicationInfo info) {
        // If we're dealing with a multi-arch application that has both
        // 32 and 64 bit shared libraries, we might need to choose the secondary
        // depending on what the current runtime's instruction set is.
        if (info.primaryCpuAbi != null && info.secondaryCpuAbi != null) {
            final String runtimeIsa = VMRuntime.getRuntime().vmInstructionSet();
            final String secondaryIsa = VMRuntime.getInstructionSet(info.secondaryCpuAbi);

            // If the runtimeIsa is the same as the primary isa, then we do nothing.
            // Everything will be set up correctly because info.nativeLibraryDir will
            // correspond to the right ISA.
            if (runtimeIsa.equals(secondaryIsa)) {
                final ApplicationInfo modified = new ApplicationInfo(info);
                modified.nativeLibraryDir = modified.secondaryNativeLibraryDir;
                return modified;
            }
        }

        return info;
    }

    /**
     * Create information about the system package.
     * Must call {@link #installSystemApplicationInfo} later.
     */
    LoadedApk(ActivityThread activityThread) {
        mActivityThread = activityThread;
        mApplicationInfo = new ApplicationInfo();
        mApplicationInfo.packageName = "android";
        mPackageName = "android";
        mAppDir = null;
        mResDir = null;
        mSplitAppDirs = null;
        mSplitResDirs = null;
        mOverlayDirs = null;
        mSharedLibraries = null;
        mDataDir = null;
        mDataDirFile = null;
        mLibDir = null;
        mBaseClassLoader = null;
        mSecurityViolation = false;
        mIncludeCode = true;
        mRegisterPackage = false;
        mClassLoader = ClassLoader.getSystemClassLoader();
        mResources = Resources.getSystem();
    }

    /**
     * Sets application info about the system package.
     */
    void installSystemApplicationInfo(ApplicationInfo info, ClassLoader classLoader) {
        assert info.packageName.equals("android");
        mApplicationInfo = info;
        mClassLoader = classLoader;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public ApplicationInfo getApplicationInfo() {
        return mApplicationInfo;
    }

    public boolean isSecurityViolation() {
        return mSecurityViolation;
    }

    public CompatibilityInfo getCompatibilityInfo() {
        return mDisplayAdjustments.getCompatibilityInfo();
    }

    public void setCompatibilityInfo(CompatibilityInfo compatInfo) {
        mDisplayAdjustments.setCompatibilityInfo(compatInfo);
    }

    /**
     * Gets the array of shared libraries that are listed as
     * used by the given package.
     *
     * @param packageName the name of the package (note: not its
     * file name)
     * @return null-ok; the array of shared libraries, each one
     * a fully-qualified path
     */
    private static String[] getLibrariesFor(String packageName) {
        ApplicationInfo ai = null;
        try {
            ai = ActivityThread.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_SHARED_LIBRARY_FILES, UserHandle.myUserId());
        } catch (RemoteException e) {
            throw new AssertionError(e);
        }

        if (ai == null) {
            return null;
        }

        return ai.sharedLibraryFiles;
    }

    public ClassLoader getClassLoader() {
        synchronized (this) {
            if (mClassLoader != null) {
                return mClassLoader;
            }

            if (mIncludeCode && !mPackageName.equals("android")) {
                // Avoid the binder call when the package is the current application package.
                // The activity manager will perform ensure that dexopt is performed before
                // spinning up the process.
                if (!Objects.equals(mPackageName, ActivityThread.currentPackageName())) {
                    final String isa = VMRuntime.getRuntime().vmInstructionSet();
                    try {
                        ActivityThread.getPackageManager().performDexOptIfNeeded(mPackageName, isa);
                    } catch (RemoteException re) {
                        // Ignored.
                    }
                }

                final ArrayList<String> zipPaths = new ArrayList<>();
                final ArrayList<String> libPaths = new ArrayList<>();

                if (mRegisterPackage) {
                    try {
                        ActivityManagerNative.getDefault().addPackageDependency(mPackageName);
                    } catch (RemoteException e) {
                    }
                }

                zipPaths.add(mAppDir);
                if (mSplitAppDirs != null) {
                    Collections.addAll(zipPaths, mSplitAppDirs);
                }

                libPaths.add(mLibDir);

                /*
                 * The following is a bit of a hack to inject
                 * instrumentation into the system: If the app
                 * being started matches one of the instrumentation names,
                 * then we combine both the "instrumentation" and
                 * "instrumented" app into the path, along with the
                 * concatenation of both apps' shared library lists.
                 */

                String instrumentationPackageName = mActivityThread.mInstrumentationPackageName;
                String instrumentationAppDir = mActivityThread.mInstrumentationAppDir;
                String[] instrumentationSplitAppDirs = mActivityThread.mInstrumentationSplitAppDirs;
                String instrumentationLibDir = mActivityThread.mInstrumentationLibDir;

                String instrumentedAppDir = mActivityThread.mInstrumentedAppDir;
                String[] instrumentedSplitAppDirs = mActivityThread.mInstrumentedSplitAppDirs;
                String instrumentedLibDir = mActivityThread.mInstrumentedLibDir;
                String[] instrumentationLibs = null;

                if (mAppDir.equals(instrumentationAppDir)
                        || mAppDir.equals(instrumentedAppDir)) {
                    zipPaths.clear();
                    zipPaths.add(instrumentationAppDir);
                    if (instrumentationSplitAppDirs != null) {
                        Collections.addAll(zipPaths, instrumentationSplitAppDirs);
                    }
                    zipPaths.add(instrumentedAppDir);
                    if (instrumentedSplitAppDirs != null) {
                        Collections.addAll(zipPaths, instrumentedSplitAppDirs);
                    }

                    libPaths.clear();
                    libPaths.add(instrumentationLibDir);
                    libPaths.add(instrumentedLibDir);

                    if (!instrumentedAppDir.equals(instrumentationAppDir)) {
                        instrumentationLibs = getLibrariesFor(instrumentationPackageName);
                    }
                }

                if (mSharedLibraries != null) {
                    for (String lib : mSharedLibraries) {
                        if (!zipPaths.contains(lib)) {
                            zipPaths.add(0, lib);
                        }
                    }
                }

                if (instrumentationLibs != null) {
                    for (String lib : instrumentationLibs) {
                        if (!zipPaths.contains(lib)) {
                            zipPaths.add(0, lib);
                        }
                    }
                }

                final String zip = TextUtils.join(File.pathSeparator, zipPaths);
                final String lib = TextUtils.join(File.pathSeparator, libPaths);

                /*
                 * With all the combination done (if necessary, actually
                 * create the class loader.
                 */

                if (ActivityThread.localLOGV)
                    Slog.v(ActivityThread.TAG, "Class path: " + zip + ", JNI path: " + lib);

                // Temporarily disable logging of disk reads on the Looper thread
                // as this is early and necessary.
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();

                mClassLoader = ApplicationLoaders.getDefault().getClassLoader(zip, lib,
                        mBaseClassLoader);
                initializeJavaContextClassLoader();

                StrictMode.setThreadPolicy(oldPolicy);
            } else {
                if (mBaseClassLoader == null) {
                    mClassLoader = ClassLoader.getSystemClassLoader();
                } else {
                    mClassLoader = mBaseClassLoader;
                }
            }
            return mClassLoader;
        }
    }

    /**
     * Setup value for Thread.getContextClassLoader(). If the
     * package will not run in in a VM with other packages, we set
     * the Java context ClassLoader to the
     * PackageInfo.getClassLoader value. However, if this VM can
     * contain multiple packages, we intead set the Java context
     * ClassLoader to a proxy that will warn about the use of Java
     * context ClassLoaders and then fall through to use the
     * system ClassLoader.
     *
     * <p> Note that this is similar to but not the same as the
     * android.content.Context.getClassLoader(). While both
     * context class loaders are typically set to the
     * PathClassLoader used to load the package archive in the
     * single application per VM case, a single Android process
     * may contain several Contexts executing on one thread with
     * their own logical ClassLoaders while the Java context
     * ClassLoader is a thread local. This is why in the case when
     * we have multiple packages per VM we do not set the Java
     * context ClassLoader to an arbitrary but instead warn the
     * user to set their own if we detect that they are using a
     * Java library that expects it to be set.
     */
    private void initializeJavaContextClassLoader() {
        IPackageManager pm = ActivityThread.getPackageManager();
        android.content.pm.PackageInfo pi;
        try {
            pi = pm.getPackageInfo(mPackageName, 0, UserHandle.myUserId());
        } catch (RemoteException e) {
            throw new IllegalStateException("Unable to get package info for "
                    + mPackageName + "; is system dying?", e);
        }
        if (pi == null) {
            throw new IllegalStateException("Unable to get package info for "
                    + mPackageName + "; is package not installed?");
        }
        /*
         * Two possible indications that this package could be
         * sharing its virtual machine with other packages:
         *
         * 1.) the sharedUserId attribute is set in the manifest,
         *     indicating a request to share a VM with other
         *     packages with the same sharedUserId.
         *
         * 2.) the application element of the manifest has an
         *     attribute specifying a non-default process name,
         *     indicating the desire to run in another packages VM.
         */
        boolean sharedUserIdSet = (pi.sharedUserId != null);
        boolean processNameNotDefault =
            (pi.applicationInfo != null &&
             !mPackageName.equals(pi.applicationInfo.processName));
        boolean sharable = (sharedUserIdSet || processNameNotDefault);
        ClassLoader contextClassLoader =
            (sharable)
            ? new WarningContextClassLoader()
            : mClassLoader;
        Thread.currentThread().setContextClassLoader(contextClassLoader);
    }

    private static class WarningContextClassLoader extends ClassLoader {

        private static boolean warned = false;

        private void warn(String methodName) {
            if (warned) {
                return;
            }
            warned = true;
            Thread.currentThread().setContextClassLoader(getParent());
            Slog.w(ActivityThread.TAG, "ClassLoader." + methodName + ": " +
                  "The class loader returned by " +
                  "Thread.getContextClassLoader() may fail for processes " +
                  "that host multiple applications. You should explicitly " +
                  "specify a context class loader. For example: " +
                  "Thread.setContextClassLoader(getClass().getClassLoader());");
        }

        @Override public URL getResource(String resName) {
            warn("getResource");
            return getParent().getResource(resName);
        }

        @Override public Enumeration<URL> getResources(String resName) throws IOException {
            warn("getResources");
            return getParent().getResources(resName);
        }

        @Override public InputStream getResourceAsStream(String resName) {
            warn("getResourceAsStream");
            return getParent().getResourceAsStream(resName);
        }

        @Override public Class<?> loadClass(String className) throws ClassNotFoundException {
            warn("loadClass");
            return getParent().loadClass(className);
        }

        @Override public void setClassAssertionStatus(String cname, boolean enable) {
            warn("setClassAssertionStatus");
            getParent().setClassAssertionStatus(cname, enable);
        }

        @Override public void setPackageAssertionStatus(String pname, boolean enable) {
            warn("setPackageAssertionStatus");
            getParent().setPackageAssertionStatus(pname, enable);
        }

        @Override public void setDefaultAssertionStatus(boolean enable) {
            warn("setDefaultAssertionStatus");
            getParent().setDefaultAssertionStatus(enable);
        }

        @Override public void clearAssertionStatus() {
            warn("clearAssertionStatus");
            getParent().clearAssertionStatus();
        }
    }

    public String getAppDir() {
        return mAppDir;
    }

    public String getLibDir() {
        return mLibDir;
    }

    public String getResDir() {
        return mResDir;
    }

    public String[] getSplitAppDirs() {
        return mSplitAppDirs;
    }

    public String[] getSplitResDirs() {
        return mSplitResDirs;
    }

    public String[] getOverlayDirs() {
        return mOverlayDirs;
    }

    public String getDataDir() {
        return mDataDir;
    }

    public File getDataDirFile() {
        return mDataDirFile;
    }

    public AssetManager getAssets(ActivityThread mainThread) {
        return getResources(mainThread).getAssets();
    }

    public Resources getResources(ActivityThread mainThread) {
        if (mResources == null) {
            mResources = mainThread.getTopLevelResources(mResDir, mSplitResDirs, mOverlayDirs,
                    mApplicationInfo.sharedLibraryFiles, Display.DEFAULT_DISPLAY, null, this);
        }
        return mResources;
    }

    public Application makeApplication(boolean forceDefaultAppClass,
            Instrumentation instrumentation) {
        if (mApplication != null) {
            return mApplication;
        }

        Application app = null;

        String appClass = mApplicationInfo.className;
        if (forceDefaultAppClass || (appClass == null)) {
            appClass = "android.app.Application";
        }

        try {
            java.lang.ClassLoader cl = getClassLoader();
            ContextImpl appContext = ContextImpl.createAppContext(mActivityThread, this);
            app = mActivityThread.mInstrumentation.newApplication(
                    cl, appClass, appContext);
            appContext.setOuterContext(app);
        } catch (Exception e) {
            if (!mActivityThread.mInstrumentation.onException(app, e)) {
                throw new RuntimeException(
                    "Unable to instantiate application " + appClass
                    + ": " + e.toString(), e);
            }
        }
        mActivityThread.mAllApplications.add(app);
        mApplication = app;

        if (instrumentation != null) {
            try {
                instrumentation.callApplicationOnCreate(app);
            } catch (Exception e) {
                if (!instrumentation.onException(app, e)) {
                    throw new RuntimeException(
                        "Unable to create application " + app.getClass().getName()
                        + ": " + e.toString(), e);
                }
            }
        }

        // Rewrite the R 'constants' for all library apks.
        SparseArray<String> packageIdentifiers = getAssets(mActivityThread)
                .getAssignedPackageIdentifiers();
        final int N = packageIdentifiers.size();
        for (int i = 0; i < N; i++) {
            final int id = packageIdentifiers.keyAt(i);
            if (id == 0x01 || id == 0x7f) {
                continue;
            }

            rewriteRValues(getClassLoader(), packageIdentifiers.valueAt(i), id);
        }

        return app;
    }

    private void rewriteIntField(Field field, int packageId) throws IllegalAccessException {
        int requiredModifiers = Modifier.STATIC | Modifier.PUBLIC;
        int bannedModifiers = Modifier.FINAL;

        int mod = field.getModifiers();
        if ((mod & requiredModifiers) != requiredModifiers ||
                (mod & bannedModifiers) != 0) {
            throw new IllegalArgumentException("Field " + field.getName() +
                    " is not rewritable");
        }

        if (field.getType() != int.class && field.getType() != Integer.class) {
            throw new IllegalArgumentException("Field " + field.getName() +
                    " is not an integer");
        }

        try {
            int resId = field.getInt(null);
            field.setInt(null, (resId & 0x00ffffff) | (packageId << 24));
        } catch (IllegalAccessException e) {
            // This should not occur (we check above if we can write to it)
            throw new IllegalArgumentException(e);
        }
    }

    private void rewriteIntArrayField(Field field, int packageId) {
        int requiredModifiers = Modifier.STATIC | Modifier.PUBLIC;

        if ((field.getModifiers() & requiredModifiers) != requiredModifiers) {
            throw new IllegalArgumentException("Field " + field.getName() +
                    " is not rewritable");
        }

        if (field.getType() != int[].class) {
            throw new IllegalArgumentException("Field " + field.getName() +
                    " is not an integer array");
        }

        try {
            int[] array = (int[]) field.get(null);
            for (int i = 0; i < array.length; i++) {
                array[i] = (array[i] & 0x00ffffff) | (packageId << 24);
            }
        } catch (IllegalAccessException e) {
            // This should not occur (we check above if we can write to it)
            throw new IllegalArgumentException(e);
        }
    }

    private void rewriteRValues(ClassLoader cl, String packageName, int id) {
        final Class<?> rClazz;
        try {
            rClazz = cl.loadClass(packageName + ".R");
        } catch (ClassNotFoundException e) {
            // This is not necessarily an error, as some packages do not ship with resources
            // (or they do not need rewriting).
            Log.i(TAG, "Could not find R class for package '" + packageName + "'");
            return;
        }

        try {
            Class<?>[] declaredClasses = rClazz.getDeclaredClasses();
            for (Class<?> clazz : declaredClasses) {
                try {
                    if (clazz.getSimpleName().equals("styleable")) {
                        for (Field field : clazz.getDeclaredFields()) {
                            if (field.getType() == int[].class) {
                                rewriteIntArrayField(field, id);
                            }
                        }

                    } else {
                        for (Field field : clazz.getDeclaredFields()) {
                            rewriteIntField(field, id);
                        }
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException("Failed to rewrite R values for " +
                            clazz.getName(), e);
                }
            }

        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to rewrite R values", e);
        }
    }

    public void removeContextRegistrations(Context context,
            String who, String what) {
        final boolean reportRegistrationLeaks = StrictMode.vmRegistrationLeaksEnabled();
        ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> rmap =
                mReceivers.remove(context);
        if (rmap != null) {
            for (int i=0; i<rmap.size(); i++) {
                LoadedApk.ReceiverDispatcher rd = rmap.valueAt(i);
                IntentReceiverLeaked leak = new IntentReceiverLeaked(
                        what + " " + who + " has leaked IntentReceiver "
                        + rd.getIntentReceiver() + " that was " +
                        "originally registered here. Are you missing a " +
                        "call to unregisterReceiver()?");
                leak.setStackTrace(rd.getLocation().getStackTrace());
                Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                if (reportRegistrationLeaks) {
                    StrictMode.onIntentReceiverLeaked(leak);
                }
                try {
                    ActivityManagerNative.getDefault().unregisterReceiver(
                            rd.getIIntentReceiver());
                } catch (RemoteException e) {
                    // system crashed, nothing we can do
                }
            }
        }
        mUnregisteredReceivers.remove(context);
        //Slog.i(TAG, "Receiver registrations: " + mReceivers);
        ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> smap =
            mServices.remove(context);
        if (smap != null) {
            for (int i=0; i<smap.size(); i++) {
                LoadedApk.ServiceDispatcher sd = smap.valueAt(i);
                ServiceConnectionLeaked leak = new ServiceConnectionLeaked(
                        what + " " + who + " has leaked ServiceConnection "
                        + sd.getServiceConnection() + " that was originally bound here");
                leak.setStackTrace(sd.getLocation().getStackTrace());
                Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                if (reportRegistrationLeaks) {
                    StrictMode.onServiceConnectionLeaked(leak);
                }
                try {
                    ActivityManagerNative.getDefault().unbindService(
                            sd.getIServiceConnection());
                } catch (RemoteException e) {
                    // system crashed, nothing we can do
                }
                sd.doForget();
            }
        }
        mUnboundServices.remove(context);
        //Slog.i(TAG, "Service registrations: " + mServices);
    }

    public IIntentReceiver getReceiverDispatcher(BroadcastReceiver r,
            Context context, Handler handler,
            Instrumentation instrumentation, boolean registered) {
        synchronized (mReceivers) {
            LoadedApk.ReceiverDispatcher rd = null;
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = null;
            if (registered) {
                map = mReceivers.get(context);
                if (map != null) {
                    rd = map.get(r);
                }
            }
            if (rd == null) {
                rd = new ReceiverDispatcher(r, context, handler,
                        instrumentation, registered);
                if (registered) {
                    if (map == null) {
                        map = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                        mReceivers.put(context, map);
                    }
                    map.put(r, rd);
                }
            } else {
                rd.validate(context, handler);
            }
            rd.mForgotten = false;
            return rd.getIIntentReceiver();
        }
    }

    public IIntentReceiver forgetReceiverDispatcher(Context context,
            BroadcastReceiver r) {
        synchronized (mReceivers) {
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> map = mReceivers.get(context);
            LoadedApk.ReceiverDispatcher rd = null;
            if (map != null) {
                rd = map.get(r);
                if (rd != null) {
                    map.remove(r);
                    if (map.size() == 0) {
                        mReceivers.remove(context);
                    }
                    if (r.getDebugUnregister()) {
                        ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                                = mUnregisteredReceivers.get(context);
                        if (holder == null) {
                            holder = new ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher>();
                            mUnregisteredReceivers.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unregistered here:");
                        ex.fillInStackTrace();
                        rd.setUnregisterLocation(ex);
                        holder.put(r, rd);
                    }
                    rd.mForgotten = true;
                    return rd.getIIntentReceiver();
                }
            }
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> holder
                    = mUnregisteredReceivers.get(context);
            if (holder != null) {
                rd = holder.get(r);
                if (rd != null) {
                    RuntimeException ex = rd.getUnregisterLocation();
                    throw new IllegalArgumentException(
                            "Unregistering Receiver " + r
                            + " that was already unregistered", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Receiver " + r
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Receiver not registered: " + r);
            }

        }
    }

    static final class ReceiverDispatcher {

        final static class InnerReceiver extends IIntentReceiver.Stub {
            final WeakReference<LoadedApk.ReceiverDispatcher> mDispatcher;
            final LoadedApk.ReceiverDispatcher mStrongRef;

            InnerReceiver(LoadedApk.ReceiverDispatcher rd, boolean strong) {
                mDispatcher = new WeakReference<LoadedApk.ReceiverDispatcher>(rd);
                mStrongRef = strong ? rd : null;
            }
            public void performReceive(Intent intent, int resultCode, String data,
                    Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                LoadedApk.ReceiverDispatcher rd = mDispatcher.get();
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = intent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Receiving broadcast " + intent.getAction() + " seq=" + seq
                            + " to " + (rd != null ? rd.mReceiver : null));
                }
                if (rd != null) {
                    rd.performReceive(intent, resultCode, data, extras,
                            ordered, sticky, sendingUser);
                } else {
                    // The activity manager dispatched a broadcast to a registered
                    // receiver in this process, but before it could be delivered the
                    // receiver was unregistered.  Acknowledge the broadcast on its
                    // behalf so that the system's broadcast sequence can continue.
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing broadcast to unregistered receiver");
                    IActivityManager mgr = ActivityManagerNative.getDefault();
                    try {
                        if (extras != null) {
                            extras.setAllowFds(false);
                        }
                        mgr.finishReceiver(this, resultCode, data, extras, false);
                    } catch (RemoteException e) {
                        Slog.w(ActivityThread.TAG, "Couldn't finish broadcast to unregistered receiver");
                    }
                }
            }
        }

        final IIntentReceiver.Stub mIIntentReceiver;
        final BroadcastReceiver mReceiver;
        final Context mContext;
        final Handler mActivityThread;
        final Instrumentation mInstrumentation;
        final boolean mRegistered;
        final IntentReceiverLeaked mLocation;
        RuntimeException mUnregisterLocation;
        boolean mForgotten;

        final class Args extends BroadcastReceiver.PendingResult implements Runnable {
            private Intent mCurIntent;
            private final boolean mOrdered;

            public Args(Intent intent, int resultCode, String resultData, Bundle resultExtras,
                    boolean ordered, boolean sticky, int sendingUser) {
                super(resultCode, resultData, resultExtras,
                        mRegistered ? TYPE_REGISTERED : TYPE_UNREGISTERED,
                        ordered, sticky, mIIntentReceiver.asBinder(), sendingUser);
                mCurIntent = intent;
                mOrdered = ordered;
            }
            
            public void run() {
                final BroadcastReceiver receiver = mReceiver;
                final boolean ordered = mOrdered;
                
                if (ActivityThread.DEBUG_BROADCAST) {
                    int seq = mCurIntent.getIntExtra("seq", -1);
                    Slog.i(ActivityThread.TAG, "Dispatching broadcast " + mCurIntent.getAction()
                            + " seq=" + seq + " to " + mReceiver);
                    Slog.i(ActivityThread.TAG, "  mRegistered=" + mRegistered
                            + " mOrderedHint=" + ordered);
                }
                
                final IActivityManager mgr = ActivityManagerNative.getDefault();
                final Intent intent = mCurIntent;
                mCurIntent = null;
                
                if (receiver == null || mForgotten) {
                    if (mRegistered && ordered) {
                        if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                "Finishing null broadcast to " + mReceiver);
                        sendFinished(mgr);
                    }
                    return;
                }

                Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "broadcastReceiveReg");
                try {
                    ClassLoader cl =  mReceiver.getClass().getClassLoader();
                    intent.setExtrasClassLoader(cl);
                    setExtrasClassLoader(cl);
                    receiver.setPendingResult(this);
                    receiver.onReceive(mContext, intent);
                } catch (Exception e) {
                    if (mRegistered && ordered) {
                        if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                                "Finishing failed broadcast to " + mReceiver);
                        sendFinished(mgr);
                    }
                    if (mInstrumentation == null ||
                            !mInstrumentation.onException(mReceiver, e)) {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                        throw new RuntimeException(
                            "Error receiving broadcast " + intent
                            + " in " + mReceiver, e);
                    }
                }
                
                if (receiver.getPendingResult() != null) {
                    finish();
                }
                Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
            }
        }

        ReceiverDispatcher(BroadcastReceiver receiver, Context context,
                Handler activityThread, Instrumentation instrumentation,
                boolean registered) {
            if (activityThread == null) {
                throw new NullPointerException("Handler must not be null");
            }

            mIIntentReceiver = new InnerReceiver(this, !registered);
            mReceiver = receiver;
            mContext = context;
            mActivityThread = activityThread;
            mInstrumentation = instrumentation;
            mRegistered = registered;
            mLocation = new IntentReceiverLeaked(null);
            mLocation.fillInStackTrace();
        }

        void validate(Context context, Handler activityThread) {
            if (mContext != context) {
                throw new IllegalStateException(
                    "Receiver " + mReceiver +
                    " registered with differing Context (was " +
                    mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new IllegalStateException(
                    "Receiver " + mReceiver +
                    " registered with differing handler (was " +
                    mActivityThread + " now " + activityThread + ")");
            }
        }

        IntentReceiverLeaked getLocation() {
            return mLocation;
        }

        BroadcastReceiver getIntentReceiver() {
            return mReceiver;
        }

        IIntentReceiver getIIntentReceiver() {
            return mIIntentReceiver;
        }

        void setUnregisterLocation(RuntimeException ex) {
            mUnregisterLocation = ex;
        }

        RuntimeException getUnregisterLocation() {
            return mUnregisterLocation;
        }

        public void performReceive(Intent intent, int resultCode, String data,
                Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
            if (ActivityThread.DEBUG_BROADCAST) {
                int seq = intent.getIntExtra("seq", -1);
                Slog.i(ActivityThread.TAG, "Enqueueing broadcast " + intent.getAction() + " seq=" + seq
                        + " to " + mReceiver);
            }
            Args args = new Args(intent, resultCode, data, extras, ordered,
                    sticky, sendingUser);
            if (!mActivityThread.post(args)) {
                if (mRegistered && ordered) {
                    IActivityManager mgr = ActivityManagerNative.getDefault();
                    if (ActivityThread.DEBUG_BROADCAST) Slog.i(ActivityThread.TAG,
                            "Finishing sync broadcast to " + mReceiver);
                    args.sendFinished(mgr);
                }
            }
        }

    }

    public final IServiceConnection getServiceDispatcher(ServiceConnection c,
            Context context, Handler handler, int flags) {
        synchronized (mServices) {
            LoadedApk.ServiceDispatcher sd = null;
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map = mServices.get(context);
            if (map != null) {
                sd = map.get(c);
            }
            if (sd == null) {
                sd = new ServiceDispatcher(c, context, handler, flags);
                if (map == null) {
                    map = new ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                    mServices.put(context, map);
                }
                map.put(c, sd);
            } else {
                sd.validate(context, handler);
            }
            return sd.getIServiceConnection();
        }
    }

    public final IServiceConnection forgetServiceDispatcher(Context context,
            ServiceConnection c) {
        synchronized (mServices) {
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map
                    = mServices.get(context);
            LoadedApk.ServiceDispatcher sd = null;
            if (map != null) {
                sd = map.get(c);
                if (sd != null) {
                    map.remove(c);
                    sd.doForget();
                    if (map.size() == 0) {
                        mServices.remove(context);
                    }
                    if ((sd.getFlags()&Context.BIND_DEBUG_UNBIND) != 0) {
                        ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                                = mUnboundServices.get(context);
                        if (holder == null) {
                            holder = new ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                            mUnboundServices.put(context, holder);
                        }
                        RuntimeException ex = new IllegalArgumentException(
                                "Originally unbound here:");
                        ex.fillInStackTrace();
                        sd.setUnbindLocation(ex);
                        holder.put(c, sd);
                    }
                    return sd.getIServiceConnection();
                }
            }
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> holder
                    = mUnboundServices.get(context);
            if (holder != null) {
                sd = holder.get(c);
                if (sd != null) {
                    RuntimeException ex = sd.getUnbindLocation();
                    throw new IllegalArgumentException(
                            "Unbinding Service " + c
                            + " that was already unbound", ex);
                }
            }
            if (context == null) {
                throw new IllegalStateException("Unbinding Service " + c
                        + " from Context that is no longer in use: " + context);
            } else {
                throw new IllegalArgumentException("Service not registered: " + c);
            }
        }
    }

    static final class ServiceDispatcher {
        private final ServiceDispatcher.InnerConnection mIServiceConnection;
        private final ServiceConnection mConnection;
        private final Context mContext;
        private final Handler mActivityThread;
        private final ServiceConnectionLeaked mLocation;
        private final int mFlags;

        private RuntimeException mUnbindLocation;

        private boolean mDied;
        private boolean mForgotten;

        private static class ConnectionInfo {
            IBinder binder;
            IBinder.DeathRecipient deathMonitor;
        }

        private static class InnerConnection extends IServiceConnection.Stub {
            final WeakReference<LoadedApk.ServiceDispatcher> mDispatcher;

            InnerConnection(LoadedApk.ServiceDispatcher sd) {
                mDispatcher = new WeakReference<LoadedApk.ServiceDispatcher>(sd);
            }

            public void connected(ComponentName name, IBinder service) throws RemoteException {
                LoadedApk.ServiceDispatcher sd = mDispatcher.get();
                if (sd != null) {
                    sd.connected(name, service);
                }
            }
        }

        private final ArrayMap<ComponentName, ServiceDispatcher.ConnectionInfo> mActiveConnections
            = new ArrayMap<ComponentName, ServiceDispatcher.ConnectionInfo>();

        ServiceDispatcher(ServiceConnection conn,
                Context context, Handler activityThread, int flags) {
            mIServiceConnection = new InnerConnection(this);
            mConnection = conn;
            mContext = context;
            mActivityThread = activityThread;
            mLocation = new ServiceConnectionLeaked(null);
            mLocation.fillInStackTrace();
            mFlags = flags;
        }

        void validate(Context context, Handler activityThread) {
            if (mContext != context) {
                throw new RuntimeException(
                    "ServiceConnection " + mConnection +
                    " registered with differing Context (was " +
                    mContext + " now " + context + ")");
            }
            if (mActivityThread != activityThread) {
                throw new RuntimeException(
                    "ServiceConnection " + mConnection +
                    " registered with differing handler (was " +
                    mActivityThread + " now " + activityThread + ")");
            }
        }

        void doForget() {
            synchronized(this) {
                for (int i=0; i<mActiveConnections.size(); i++) {
                    ServiceDispatcher.ConnectionInfo ci = mActiveConnections.valueAt(i);
                    ci.binder.unlinkToDeath(ci.deathMonitor, 0);
                }
                mActiveConnections.clear();
                mForgotten = true;
            }
        }

        ServiceConnectionLeaked getLocation() {
            return mLocation;
        }

        ServiceConnection getServiceConnection() {
            return mConnection;
        }

        IServiceConnection getIServiceConnection() {
            return mIServiceConnection;
        }

        int getFlags() {
            return mFlags;
        }

        void setUnbindLocation(RuntimeException ex) {
            mUnbindLocation = ex;
        }

        RuntimeException getUnbindLocation() {
            return mUnbindLocation;
        }

        public void connected(ComponentName name, IBinder service) {
            if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 0));
            } else {
                doConnected(name, service);
            }
        }

        public void death(ComponentName name, IBinder service) {
            ServiceDispatcher.ConnectionInfo old;

            synchronized (this) {
                mDied = true;
                old = mActiveConnections.remove(name);
                if (old == null || old.binder != service) {
                    // Death for someone different than who we last
                    // reported...  just ignore it.
                    return;
                }
                old.binder.unlinkToDeath(old.deathMonitor, 0);
            }

            if (mActivityThread != null) {
                mActivityThread.post(new RunConnection(name, service, 1));
            } else {
                doDeath(name, service);
            }
        }

        public void doConnected(ComponentName name, IBinder service) {
            ServiceDispatcher.ConnectionInfo old;
            ServiceDispatcher.ConnectionInfo info;

            synchronized (this) {
                if (mForgotten) {
                    // We unbound before receiving the connection; ignore
                    // any connection received.
                    return;
                }
                old = mActiveConnections.get(name);
                if (old != null && old.binder == service) {
                    // Huh, already have this one.  Oh well!
                    return;
                }

                if (service != null) {
                    // A new service is being connected... set it all up.
                    mDied = false;
                    info = new ConnectionInfo();
                    info.binder = service;
                    info.deathMonitor = new DeathMonitor(name, service);
                    try {
                        service.linkToDeath(info.deathMonitor, 0);
                        mActiveConnections.put(name, info);
                    } catch (RemoteException e) {
                        // This service was dead before we got it...  just
                        // don't do anything with it.
                        mActiveConnections.remove(name);
                        return;
                    }

                } else {
                    // The named service is being disconnected... clean up.
                    mActiveConnections.remove(name);
                }

                if (old != null) {
                    old.binder.unlinkToDeath(old.deathMonitor, 0);
                }
            }

            // If there was an old service, it is not disconnected.
            if (old != null) {
                mConnection.onServiceDisconnected(name);
            }
            // If there is a new service, it is now connected.
            if (service != null) {
                mConnection.onServiceConnected(name, service);
            }
        }

        public void doDeath(ComponentName name, IBinder service) {
            mConnection.onServiceDisconnected(name);
        }

        private final class RunConnection implements Runnable {
            RunConnection(ComponentName name, IBinder service, int command) {
                mName = name;
                mService = service;
                mCommand = command;
            }

            public void run() {
                if (mCommand == 0) {
                    doConnected(mName, mService);
                } else if (mCommand == 1) {
                    doDeath(mName, mService);
                }
            }

            final ComponentName mName;
            final IBinder mService;
            final int mCommand;
        }

        private final class DeathMonitor implements IBinder.DeathRecipient
        {
            DeathMonitor(ComponentName name, IBinder service) {
                mName = name;
                mService = service;
            }

            public void binderDied() {
                death(mName, mService);
            }

            final ComponentName mName;
            final IBinder mService;
        }
    }
}
