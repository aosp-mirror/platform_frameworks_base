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

package android.app;

import android.app.backup.BackupAgent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.IIntentReceiver;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDebug;
import android.database.sqlite.SQLiteDebug.DbStats;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.AndroidRuntimeException;
import android.util.Config;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.view.Display;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewManager;
import android.view.ViewRoot;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import com.android.internal.os.BinderInternal;
import com.android.internal.os.RuntimeInit;
import com.android.internal.os.SamplingProfilerIntegration;
import com.android.internal.util.ArrayUtils;

import org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import dalvik.system.SamplingProfiler;

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

final class SuperNotCalledException extends AndroidRuntimeException {
    public SuperNotCalledException(String msg) {
        super(msg);
    }
}

/**
 * This manages the execution of the main thread in an
 * application process, scheduling and executing activities,
 * broadcasts, and other operations on it as the activity
 * manager requests.
 *
 * {@hide}
 */
public final class ActivityThread {
    private static final String TAG = "ActivityThread";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    private static final boolean DEBUG_BROADCAST = false;
    private static final boolean DEBUG_RESULTS = false;
    private static final boolean DEBUG_BACKUP = false;
    private static final boolean DEBUG_CONFIGURATION = false;
    private static final long MIN_TIME_BETWEEN_GCS = 5*1000;
    private static final Pattern PATTERN_SEMICOLON = Pattern.compile(";");
    private static final int SQLITE_MEM_RELEASED_EVENT_LOG_TAG = 75003;
    private static final int LOG_ON_PAUSE_CALLED = 30021;
    private static final int LOG_ON_RESUME_CALLED = 30022;


    public static final ActivityThread currentActivityThread() {
        return (ActivityThread)sThreadLocal.get();
    }

    public static final String currentPackageName()
    {
        ActivityThread am = currentActivityThread();
        return (am != null && am.mBoundApplication != null)
            ? am.mBoundApplication.processName : null;
    }

    public static IPackageManager getPackageManager() {
        if (sPackageManager != null) {
            //Slog.v("PackageManager", "returning cur default = " + sPackageManager);
            return sPackageManager;
        }
        IBinder b = ServiceManager.getService("package");
        //Slog.v("PackageManager", "default service binder = " + b);
        sPackageManager = IPackageManager.Stub.asInterface(b);
        //Slog.v("PackageManager", "default service = " + sPackageManager);
        return sPackageManager;
    }

    DisplayMetrics getDisplayMetricsLocked(boolean forceUpdate) {
        if (mDisplayMetrics != null && !forceUpdate) {
            return mDisplayMetrics;
        }
        if (mDisplay == null) {
            WindowManager wm = WindowManagerImpl.getDefault();
            mDisplay = wm.getDefaultDisplay();
        }
        DisplayMetrics metrics = mDisplayMetrics = new DisplayMetrics();
        mDisplay.getMetrics(metrics);
        //Slog.i("foo", "New metrics: w=" + metrics.widthPixels + " h="
        //        + metrics.heightPixels + " den=" + metrics.density
        //        + " xdpi=" + metrics.xdpi + " ydpi=" + metrics.ydpi);
        return metrics;
    }

    /**
     * Creates the top level Resources for applications with the given compatibility info.
     *
     * @param resDir the resource directory.
     * @param compInfo the compability info. It will use the default compatibility info when it's
     * null.
     */
    Resources getTopLevelResources(String resDir, CompatibilityInfo compInfo) {
        ResourcesKey key = new ResourcesKey(resDir, compInfo.applicationScale);
        Resources r;
        synchronized (mPackages) {
            // Resources is app scale dependent.
            if (false) {
                Slog.w(TAG, "getTopLevelResources: " + resDir + " / "
                        + compInfo.applicationScale);
            }
            WeakReference<Resources> wr = mActiveResources.get(key);
            r = wr != null ? wr.get() : null;
            //if (r != null) Slog.i(TAG, "isUpToDate " + resDir + ": " + r.getAssets().isUpToDate());
            if (r != null && r.getAssets().isUpToDate()) {
                if (false) {
                    Slog.w(TAG, "Returning cached resources " + r + " " + resDir
                            + ": appScale=" + r.getCompatibilityInfo().applicationScale);
                }
                return r;
            }
        }

        //if (r != null) {
        //    Slog.w(TAG, "Throwing away out-of-date resources!!!! "
        //            + r + " " + resDir);
        //}

        AssetManager assets = new AssetManager();
        if (assets.addAssetPath(resDir) == 0) {
            return null;
        }

        //Slog.i(TAG, "Resource: key=" + key + ", display metrics=" + metrics);
        DisplayMetrics metrics = getDisplayMetricsLocked(false);
        r = new Resources(assets, metrics, getConfiguration(), compInfo);
        if (false) {
            Slog.i(TAG, "Created app resources " + resDir + " " + r + ": "
                    + r.getConfiguration() + " appScale="
                    + r.getCompatibilityInfo().applicationScale);
        }
        
        synchronized (mPackages) {
            WeakReference<Resources> wr = mActiveResources.get(key);
            Resources existing = wr != null ? wr.get() : null;
            if (existing != null && existing.getAssets().isUpToDate()) {
                // Someone else already created the resources while we were
                // unlocked; go ahead and use theirs.
                r.getAssets().close();
                return existing;
            }
            
            // XXX need to remove entries when weak references go away
            mActiveResources.put(key, new WeakReference<Resources>(r));
            return r;
        }
    }

    /**
     * Creates the top level resources for the given package.
     */
    Resources getTopLevelResources(String resDir, PackageInfo pkgInfo) {
        return getTopLevelResources(resDir, pkgInfo.mCompatibilityInfo);
    }

    final Handler getHandler() {
        return mH;
    }

    public final static class PackageInfo {

        private final ActivityThread mActivityThread;
        private final ApplicationInfo mApplicationInfo;
        private final String mPackageName;
        private final String mAppDir;
        private final String mResDir;
        private final String[] mSharedLibraries;
        private final String mDataDir;
        private final File mDataDirFile;
        private final ClassLoader mBaseClassLoader;
        private final boolean mSecurityViolation;
        private final boolean mIncludeCode;
        private Resources mResources;
        private ClassLoader mClassLoader;
        private Application mApplication;
        private CompatibilityInfo mCompatibilityInfo;

        private final HashMap<Context, HashMap<BroadcastReceiver, ReceiverDispatcher>> mReceivers
            = new HashMap<Context, HashMap<BroadcastReceiver, ReceiverDispatcher>>();
        private final HashMap<Context, HashMap<BroadcastReceiver, ReceiverDispatcher>> mUnregisteredReceivers
        = new HashMap<Context, HashMap<BroadcastReceiver, ReceiverDispatcher>>();
        private final HashMap<Context, HashMap<ServiceConnection, ServiceDispatcher>> mServices
            = new HashMap<Context, HashMap<ServiceConnection, ServiceDispatcher>>();
        private final HashMap<Context, HashMap<ServiceConnection, ServiceDispatcher>> mUnboundServices
            = new HashMap<Context, HashMap<ServiceConnection, ServiceDispatcher>>();

        int mClientCount = 0;

        Application getApplication() {
            return mApplication;
        }

        public PackageInfo(ActivityThread activityThread, ApplicationInfo aInfo,
                ActivityThread mainThread, ClassLoader baseLoader,
                boolean securityViolation, boolean includeCode) {
            mActivityThread = activityThread;
            mApplicationInfo = aInfo;
            mPackageName = aInfo.packageName;
            mAppDir = aInfo.sourceDir;
            mResDir = aInfo.uid == Process.myUid() ? aInfo.sourceDir
                    : aInfo.publicSourceDir;
            mSharedLibraries = aInfo.sharedLibraryFiles;
            mDataDir = aInfo.dataDir;
            mDataDirFile = mDataDir != null ? new File(mDataDir) : null;
            mBaseClassLoader = baseLoader;
            mSecurityViolation = securityViolation;
            mIncludeCode = includeCode;
            mCompatibilityInfo = new CompatibilityInfo(aInfo);

            if (mAppDir == null) {
                if (mSystemContext == null) {
                    mSystemContext =
                        ContextImpl.createSystemContext(mainThread);
                    mSystemContext.getResources().updateConfiguration(
                             mainThread.getConfiguration(),
                             mainThread.getDisplayMetricsLocked(false));
                    //Slog.i(TAG, "Created system resources "
                    //        + mSystemContext.getResources() + ": "
                    //        + mSystemContext.getResources().getConfiguration());
                }
                mClassLoader = mSystemContext.getClassLoader();
                mResources = mSystemContext.getResources();
            }
        }

        public PackageInfo(ActivityThread activityThread, String name,
                Context systemContext, ApplicationInfo info) {
            mActivityThread = activityThread;
            mApplicationInfo = info != null ? info : new ApplicationInfo();
            mApplicationInfo.packageName = name;
            mPackageName = name;
            mAppDir = null;
            mResDir = null;
            mSharedLibraries = null;
            mDataDir = null;
            mDataDirFile = null;
            mBaseClassLoader = null;
            mSecurityViolation = false;
            mIncludeCode = true;
            mClassLoader = systemContext.getClassLoader();
            mResources = systemContext.getResources();
            mCompatibilityInfo = new CompatibilityInfo(mApplicationInfo);
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
                ai = getPackageManager().getApplicationInfo(packageName,
                        PackageManager.GET_SHARED_LIBRARY_FILES);
            } catch (RemoteException e) {
                throw new AssertionError(e);
            }

            if (ai == null) {
                return null;
            }

            return ai.sharedLibraryFiles;
        }

        /**
         * Combines two arrays (of library names) such that they are
         * concatenated in order but are devoid of duplicates. The
         * result is a single string with the names of the libraries
         * separated by colons, or <code>null</code> if both lists
         * were <code>null</code> or empty.
         *
         * @param list1 null-ok; the first list
         * @param list2 null-ok; the second list
         * @return null-ok; the combination
         */
        private static String combineLibs(String[] list1, String[] list2) {
            StringBuilder result = new StringBuilder(300);
            boolean first = true;

            if (list1 != null) {
                for (String s : list1) {
                    if (first) {
                        first = false;
                    } else {
                        result.append(':');
                    }
                    result.append(s);
                }
            }

            // Only need to check for duplicates if list1 was non-empty.
            boolean dupCheck = !first;

            if (list2 != null) {
                for (String s : list2) {
                    if (dupCheck && ArrayUtils.contains(list1, s)) {
                        continue;
                    }

                    if (first) {
                        first = false;
                    } else {
                        result.append(':');
                    }
                    result.append(s);
                }
            }

            return result.toString();
        }

        public ClassLoader getClassLoader() {
            synchronized (this) {
                if (mClassLoader != null) {
                    return mClassLoader;
                }

                if (mIncludeCode && !mPackageName.equals("android")) {
                    String zip = mAppDir;

                    /*
                     * The following is a bit of a hack to inject
                     * instrumentation into the system: If the app
                     * being started matches one of the instrumentation names,
                     * then we combine both the "instrumentation" and
                     * "instrumented" app into the path, along with the
                     * concatenation of both apps' shared library lists.
                     */

                    String instrumentationAppDir =
                            mActivityThread.mInstrumentationAppDir;
                    String instrumentationAppPackage =
                            mActivityThread.mInstrumentationAppPackage;
                    String instrumentedAppDir =
                            mActivityThread.mInstrumentedAppDir;
                    String[] instrumentationLibs = null;

                    if (mAppDir.equals(instrumentationAppDir)
                            || mAppDir.equals(instrumentedAppDir)) {
                        zip = instrumentationAppDir + ":" + instrumentedAppDir;
                        if (! instrumentedAppDir.equals(instrumentationAppDir)) {
                            instrumentationLibs =
                                getLibrariesFor(instrumentationAppPackage);
                        }
                    }

                    if ((mSharedLibraries != null) ||
                            (instrumentationLibs != null)) {
                        zip =
                            combineLibs(mSharedLibraries, instrumentationLibs)
                            + ':' + zip;
                    }

                    /*
                     * With all the combination done (if necessary, actually
                     * create the class loader.
                     */

                    if (localLOGV) Slog.v(TAG, "Class path: " + zip);

                    mClassLoader =
                        ApplicationLoaders.getDefault().getClassLoader(
                            zip, mDataDir, mBaseClassLoader);
                    initializeJavaContextClassLoader();
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
            IPackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi;
            try {
                pi = pm.getPackageInfo(mPackageName, 0);
            } catch (RemoteException e) {
                throw new AssertionError(e);
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
                Slog.w(TAG, "ClassLoader." + methodName + ": " +
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

        public String getResDir() {
            return mResDir;
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
                mResources = mainThread.getTopLevelResources(mResDir, this);
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
                ContextImpl appContext = new ContextImpl();
                appContext.init(this, null, mActivityThread);
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
            
            return app;
        }

        public void removeContextRegistrations(Context context,
                String who, String what) {
            HashMap<BroadcastReceiver, ReceiverDispatcher> rmap =
                mReceivers.remove(context);
            if (rmap != null) {
                Iterator<ReceiverDispatcher> it = rmap.values().iterator();
                while (it.hasNext()) {
                    ReceiverDispatcher rd = it.next();
                    IntentReceiverLeaked leak = new IntentReceiverLeaked(
                            what + " " + who + " has leaked IntentReceiver "
                            + rd.getIntentReceiver() + " that was " +
                            "originally registered here. Are you missing a " +
                            "call to unregisterReceiver()?");
                    leak.setStackTrace(rd.getLocation().getStackTrace());
                    Slog.e(TAG, leak.getMessage(), leak);
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
            HashMap<ServiceConnection, ServiceDispatcher> smap =
                mServices.remove(context);
            if (smap != null) {
                Iterator<ServiceDispatcher> it = smap.values().iterator();
                while (it.hasNext()) {
                    ServiceDispatcher sd = it.next();
                    ServiceConnectionLeaked leak = new ServiceConnectionLeaked(
                            what + " " + who + " has leaked ServiceConnection "
                            + sd.getServiceConnection() + " that was originally bound here");
                    leak.setStackTrace(sd.getLocation().getStackTrace());
                    Slog.e(TAG, leak.getMessage(), leak);
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
                ReceiverDispatcher rd = null;
                HashMap<BroadcastReceiver, ReceiverDispatcher> map = null;
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
                            map = new HashMap<BroadcastReceiver, ReceiverDispatcher>();
                            mReceivers.put(context, map);
                        }
                        map.put(r, rd);
                    }
                } else {
                    rd.validate(context, handler);
                }
                return rd.getIIntentReceiver();
            }
        }

        public IIntentReceiver forgetReceiverDispatcher(Context context,
                BroadcastReceiver r) {
            synchronized (mReceivers) {
                HashMap<BroadcastReceiver, ReceiverDispatcher> map = mReceivers.get(context);
                ReceiverDispatcher rd = null;
                if (map != null) {
                    rd = map.get(r);
                    if (rd != null) {
                        map.remove(r);
                        if (map.size() == 0) {
                            mReceivers.remove(context);
                        }
                        if (r.getDebugUnregister()) {
                            HashMap<BroadcastReceiver, ReceiverDispatcher> holder
                                    = mUnregisteredReceivers.get(context);
                            if (holder == null) {
                                holder = new HashMap<BroadcastReceiver, ReceiverDispatcher>();
                                mUnregisteredReceivers.put(context, holder);
                            }
                            RuntimeException ex = new IllegalArgumentException(
                                    "Originally unregistered here:");
                            ex.fillInStackTrace();
                            rd.setUnregisterLocation(ex);
                            holder.put(r, rd);
                        }
                        return rd.getIIntentReceiver();
                    }
                }
                HashMap<BroadcastReceiver, ReceiverDispatcher> holder
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
                final WeakReference<ReceiverDispatcher> mDispatcher;
                final ReceiverDispatcher mStrongRef;

                InnerReceiver(ReceiverDispatcher rd, boolean strong) {
                    mDispatcher = new WeakReference<ReceiverDispatcher>(rd);
                    mStrongRef = strong ? rd : null;
                }
                public void performReceive(Intent intent, int resultCode,
                        String data, Bundle extras, boolean ordered, boolean sticky) {
                    ReceiverDispatcher rd = mDispatcher.get();
                    if (DEBUG_BROADCAST) {
                        int seq = intent.getIntExtra("seq", -1);
                        Slog.i(TAG, "Receiving broadcast " + intent.getAction() + " seq=" + seq
                                + " to " + (rd != null ? rd.mReceiver : null));
                    }
                    if (rd != null) {
                        rd.performReceive(intent, resultCode, data, extras,
                                ordered, sticky);
                    } else {
                        // The activity manager dispatched a broadcast to a registered
                        // receiver in this process, but before it could be delivered the
                        // receiver was unregistered.  Acknowledge the broadcast on its
                        // behalf so that the system's broadcast sequence can continue.
                        if (DEBUG_BROADCAST) Slog.i(TAG,
                                "Finishing broadcast to unregistered receiver");
                        IActivityManager mgr = ActivityManagerNative.getDefault();
                        try {
                            mgr.finishReceiver(this, resultCode, data, extras, false);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Couldn't finish broadcast to unregistered receiver");
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

            final class Args implements Runnable {
                private Intent mCurIntent;
                private int mCurCode;
                private String mCurData;
                private Bundle mCurMap;
                private boolean mCurOrdered;
                private boolean mCurSticky;

                public void run() {
                    BroadcastReceiver receiver = mReceiver;
                    if (DEBUG_BROADCAST) {
                        int seq = mCurIntent.getIntExtra("seq", -1);
                        Slog.i(TAG, "Dispatching broadcast " + mCurIntent.getAction()
                                + " seq=" + seq + " to " + mReceiver);
                        Slog.i(TAG, "  mRegistered=" + mRegistered
                                + " mCurOrdered=" + mCurOrdered);
                    }
                    
                    IActivityManager mgr = ActivityManagerNative.getDefault();
                    Intent intent = mCurIntent;
                    mCurIntent = null;
                    
                    if (receiver == null) {
                        if (mRegistered && mCurOrdered) {
                            try {
                                if (DEBUG_BROADCAST) Slog.i(TAG,
                                        "Finishing null broadcast to " + mReceiver);
                                mgr.finishReceiver(mIIntentReceiver,
                                        mCurCode, mCurData, mCurMap, false);
                            } catch (RemoteException ex) {
                            }
                        }
                        return;
                    }

                    try {
                        ClassLoader cl =  mReceiver.getClass().getClassLoader();
                        intent.setExtrasClassLoader(cl);
                        if (mCurMap != null) {
                            mCurMap.setClassLoader(cl);
                        }
                        receiver.setOrderedHint(true);
                        receiver.setResult(mCurCode, mCurData, mCurMap);
                        receiver.clearAbortBroadcast();
                        receiver.setOrderedHint(mCurOrdered);
                        receiver.setInitialStickyHint(mCurSticky);
                        receiver.onReceive(mContext, intent);
                    } catch (Exception e) {
                        if (mRegistered && mCurOrdered) {
                            try {
                                if (DEBUG_BROADCAST) Slog.i(TAG,
                                        "Finishing failed broadcast to " + mReceiver);
                                mgr.finishReceiver(mIIntentReceiver,
                                        mCurCode, mCurData, mCurMap, false);
                            } catch (RemoteException ex) {
                            }
                        }
                        if (mInstrumentation == null ||
                                !mInstrumentation.onException(mReceiver, e)) {
                            throw new RuntimeException(
                                "Error receiving broadcast " + intent
                                + " in " + mReceiver, e);
                        }
                    }
                    if (mRegistered && mCurOrdered) {
                        try {
                            if (DEBUG_BROADCAST) Slog.i(TAG,
                                    "Finishing broadcast to " + mReceiver);
                            mgr.finishReceiver(mIIntentReceiver,
                                    receiver.getResultCode(),
                                    receiver.getResultData(),
                                    receiver.getResultExtras(false),
                                    receiver.getAbortBroadcast());
                        } catch (RemoteException ex) {
                        }
                    }
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

            public void performReceive(Intent intent, int resultCode,
                    String data, Bundle extras, boolean ordered, boolean sticky) {
                if (DEBUG_BROADCAST) {
                    int seq = intent.getIntExtra("seq", -1);
                    Slog.i(TAG, "Enqueueing broadcast " + intent.getAction() + " seq=" + seq
                            + " to " + mReceiver);
                }
                Args args = new Args();
                args.mCurIntent = intent;
                args.mCurCode = resultCode;
                args.mCurData = data;
                args.mCurMap = extras;
                args.mCurOrdered = ordered;
                args.mCurSticky = sticky;
                if (!mActivityThread.post(args)) {
                    if (mRegistered && ordered) {
                        IActivityManager mgr = ActivityManagerNative.getDefault();
                        try {
                            if (DEBUG_BROADCAST) Slog.i(TAG,
                                    "Finishing sync broadcast to " + mReceiver);
                            mgr.finishReceiver(mIIntentReceiver, args.mCurCode,
                                    args.mCurData, args.mCurMap, false);
                        } catch (RemoteException ex) {
                        }
                    }
                }
            }

        }

        public final IServiceConnection getServiceDispatcher(ServiceConnection c,
                Context context, Handler handler, int flags) {
            synchronized (mServices) {
                ServiceDispatcher sd = null;
                HashMap<ServiceConnection, ServiceDispatcher> map = mServices.get(context);
                if (map != null) {
                    sd = map.get(c);
                }
                if (sd == null) {
                    sd = new ServiceDispatcher(c, context, handler, flags);
                    if (map == null) {
                        map = new HashMap<ServiceConnection, ServiceDispatcher>();
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
                HashMap<ServiceConnection, ServiceDispatcher> map
                        = mServices.get(context);
                ServiceDispatcher sd = null;
                if (map != null) {
                    sd = map.get(c);
                    if (sd != null) {
                        map.remove(c);
                        sd.doForget();
                        if (map.size() == 0) {
                            mServices.remove(context);
                        }
                        if ((sd.getFlags()&Context.BIND_DEBUG_UNBIND) != 0) {
                            HashMap<ServiceConnection, ServiceDispatcher> holder
                                    = mUnboundServices.get(context);
                            if (holder == null) {
                                holder = new HashMap<ServiceConnection, ServiceDispatcher>();
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
                HashMap<ServiceConnection, ServiceDispatcher> holder
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
            private final InnerConnection mIServiceConnection;
            private final ServiceConnection mConnection;
            private final Context mContext;
            private final Handler mActivityThread;
            private final ServiceConnectionLeaked mLocation;
            private final int mFlags;

            private RuntimeException mUnbindLocation;

            private boolean mDied;

            private static class ConnectionInfo {
                IBinder binder;
                IBinder.DeathRecipient deathMonitor;
            }

            private static class InnerConnection extends IServiceConnection.Stub {
                final WeakReference<ServiceDispatcher> mDispatcher;

                InnerConnection(ServiceDispatcher sd) {
                    mDispatcher = new WeakReference<ServiceDispatcher>(sd);
                }

                public void connected(ComponentName name, IBinder service) throws RemoteException {
                    ServiceDispatcher sd = mDispatcher.get();
                    if (sd != null) {
                        sd.connected(name, service);
                    }
                }
            }

            private final HashMap<ComponentName, ConnectionInfo> mActiveConnections
                = new HashMap<ComponentName, ConnectionInfo>();

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
                    Iterator<ConnectionInfo> it = mActiveConnections.values().iterator();
                    while (it.hasNext()) {
                        ConnectionInfo ci = it.next();
                        ci.binder.unlinkToDeath(ci.deathMonitor, 0);
                    }
                    mActiveConnections.clear();
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
                ConnectionInfo old;

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
                ConnectionInfo old;
                ConnectionInfo info;

                synchronized (this) {
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

    private static ContextImpl mSystemContext = null;

    private static final class ActivityRecord {
        IBinder token;
        int ident;
        Intent intent;
        Bundle state;
        Activity activity;
        Window window;
        Activity parent;
        String embeddedID;
        Object lastNonConfigurationInstance;
        HashMap<String,Object> lastNonConfigurationChildInstances;
        boolean paused;
        boolean stopped;
        boolean hideForNow;
        Configuration newConfig;
        Configuration createdConfig;
        ActivityRecord nextIdle;

        ActivityInfo activityInfo;
        PackageInfo packageInfo;

        List<ResultInfo> pendingResults;
        List<Intent> pendingIntents;

        boolean startsNotResumed;
        boolean isForward;

        ActivityRecord() {
            parent = null;
            embeddedID = null;
            paused = false;
            stopped = false;
            hideForNow = false;
            nextIdle = null;
        }

        public String toString() {
            ComponentName componentName = intent.getComponent();
            return "ActivityRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " token=" + token + " " + (componentName == null
                        ? "no component name" : componentName.toShortString())
                + "}";
        }
    }

    private final class ProviderRecord implements IBinder.DeathRecipient {
        final String mName;
        final IContentProvider mProvider;
        final ContentProvider mLocalProvider;

        ProviderRecord(String name, IContentProvider provider,
                ContentProvider localProvider) {
            mName = name;
            mProvider = provider;
            mLocalProvider = localProvider;
        }

        public void binderDied() {
            removeDeadProvider(mName, mProvider);
        }
    }

    private static final class NewIntentData {
        List<Intent> intents;
        IBinder token;
        public String toString() {
            return "NewIntentData{intents=" + intents + " token=" + token + "}";
        }
    }

    private static final class ReceiverData {
        Intent intent;
        ActivityInfo info;
        int resultCode;
        String resultData;
        Bundle resultExtras;
        boolean sync;
        boolean resultAbort;
        public String toString() {
            return "ReceiverData{intent=" + intent + " packageName=" +
            info.packageName + " resultCode=" + resultCode
            + " resultData=" + resultData + " resultExtras=" + resultExtras + "}";
        }
    }

    private static final class CreateBackupAgentData {
        ApplicationInfo appInfo;
        int backupMode;
        public String toString() {
            return "CreateBackupAgentData{appInfo=" + appInfo
                    + " backupAgent=" + appInfo.backupAgentName
                    + " mode=" + backupMode + "}";
        }
    }

    private static final class CreateServiceData {
        IBinder token;
        ServiceInfo info;
        Intent intent;
        public String toString() {
            return "CreateServiceData{token=" + token + " className="
            + info.name + " packageName=" + info.packageName
            + " intent=" + intent + "}";
        }
    }

    private static final class BindServiceData {
        IBinder token;
        Intent intent;
        boolean rebind;
        public String toString() {
            return "BindServiceData{token=" + token + " intent=" + intent + "}";
        }
    }

    private static final class ServiceArgsData {
        IBinder token;
        int startId;
        int flags;
        Intent args;
        public String toString() {
            return "ServiceArgsData{token=" + token + " startId=" + startId
            + " args=" + args + "}";
        }
    }

    private static final class AppBindData {
        PackageInfo info;
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        ComponentName instrumentationName;
        String profileFile;
        Bundle instrumentationArgs;
        IInstrumentationWatcher instrumentationWatcher;
        int debugMode;
        boolean restrictedBackupMode;
        Configuration config;
        boolean handlingProfiling;
        public String toString() {
            return "AppBindData{appInfo=" + appInfo + "}";
        }
    }

    private static final class DumpServiceInfo {
        FileDescriptor fd;
        IBinder service;
        String[] args;
        boolean dumped;
    }

    private static final class ResultData {
        IBinder token;
        List<ResultInfo> results;
        public String toString() {
            return "ResultData{token=" + token + " results" + results + "}";
        }
    }

    private static final class ContextCleanupInfo {
        ContextImpl context;
        String what;
        String who;
    }

    private static final class ProfilerControlData {
        String path;
        ParcelFileDescriptor fd;
    }

    private final class ApplicationThread extends ApplicationThreadNative {
        private static final String HEAP_COLUMN = "%17s %8s %8s %8s %8s";
        private static final String ONE_COUNT_COLUMN = "%17s %8d";
        private static final String TWO_COUNT_COLUMNS = "%17s %8d %17s %8d";
        private static final String DB_INFO_FORMAT = "  %8d %8d %10d  %s";

        // Formatting for checkin service - update version if row format changes
        private static final int ACTIVITY_THREAD_CHECKIN_VERSION = 1;

        public final void schedulePauseActivity(IBinder token, boolean finished,
                boolean userLeaving, int configChanges) {
            queueOrSendMessage(
                    finished ? H.PAUSE_ACTIVITY_FINISHING : H.PAUSE_ACTIVITY,
                    token,
                    (userLeaving ? 1 : 0),
                    configChanges);
        }

        public final void scheduleStopActivity(IBinder token, boolean showWindow,
                int configChanges) {
           queueOrSendMessage(
                showWindow ? H.STOP_ACTIVITY_SHOW : H.STOP_ACTIVITY_HIDE,
                token, 0, configChanges);
        }

        public final void scheduleWindowVisibility(IBinder token, boolean showWindow) {
            queueOrSendMessage(
                showWindow ? H.SHOW_WINDOW : H.HIDE_WINDOW,
                token);
        }

        public final void scheduleResumeActivity(IBinder token, boolean isForward) {
            queueOrSendMessage(H.RESUME_ACTIVITY, token, isForward ? 1 : 0);
        }

        public final void scheduleSendResult(IBinder token, List<ResultInfo> results) {
            ResultData res = new ResultData();
            res.token = token;
            res.results = results;
            queueOrSendMessage(H.SEND_RESULT, res);
        }

        // we use token to identify this activity without having to send the
        // activity itself back to the activity manager. (matters more with ipc)
        public final void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
                ActivityInfo info, Bundle state, List<ResultInfo> pendingResults,
                List<Intent> pendingNewIntents, boolean notResumed, boolean isForward) {
            ActivityRecord r = new ActivityRecord();

            r.token = token;
            r.ident = ident;
            r.intent = intent;
            r.activityInfo = info;
            r.state = state;

            r.pendingResults = pendingResults;
            r.pendingIntents = pendingNewIntents;

            r.startsNotResumed = notResumed;
            r.isForward = isForward;

            queueOrSendMessage(H.LAUNCH_ACTIVITY, r);
        }

        public final void scheduleRelaunchActivity(IBinder token,
                List<ResultInfo> pendingResults, List<Intent> pendingNewIntents,
                int configChanges, boolean notResumed, Configuration config) {
            ActivityRecord r = new ActivityRecord();

            r.token = token;
            r.pendingResults = pendingResults;
            r.pendingIntents = pendingNewIntents;
            r.startsNotResumed = notResumed;
            r.createdConfig = config;

            synchronized (mPackages) {
                mRelaunchingActivities.add(r);
            }

            queueOrSendMessage(H.RELAUNCH_ACTIVITY, r, configChanges);
        }

        public final void scheduleNewIntent(List<Intent> intents, IBinder token) {
            NewIntentData data = new NewIntentData();
            data.intents = intents;
            data.token = token;

            queueOrSendMessage(H.NEW_INTENT, data);
        }

        public final void scheduleDestroyActivity(IBinder token, boolean finishing,
                int configChanges) {
            queueOrSendMessage(H.DESTROY_ACTIVITY, token, finishing ? 1 : 0,
                    configChanges);
        }

        public final void scheduleReceiver(Intent intent, ActivityInfo info,
                int resultCode, String data, Bundle extras, boolean sync) {
            ReceiverData r = new ReceiverData();

            r.intent = intent;
            r.info = info;
            r.resultCode = resultCode;
            r.resultData = data;
            r.resultExtras = extras;
            r.sync = sync;

            queueOrSendMessage(H.RECEIVER, r);
        }

        public final void scheduleCreateBackupAgent(ApplicationInfo app, int backupMode) {
            CreateBackupAgentData d = new CreateBackupAgentData();
            d.appInfo = app;
            d.backupMode = backupMode;

            queueOrSendMessage(H.CREATE_BACKUP_AGENT, d);
        }

        public final void scheduleDestroyBackupAgent(ApplicationInfo app) {
            CreateBackupAgentData d = new CreateBackupAgentData();
            d.appInfo = app;

            queueOrSendMessage(H.DESTROY_BACKUP_AGENT, d);
        }

        public final void scheduleCreateService(IBinder token,
                ServiceInfo info) {
            CreateServiceData s = new CreateServiceData();
            s.token = token;
            s.info = info;

            queueOrSendMessage(H.CREATE_SERVICE, s);
        }

        public final void scheduleBindService(IBinder token, Intent intent,
                boolean rebind) {
            BindServiceData s = new BindServiceData();
            s.token = token;
            s.intent = intent;
            s.rebind = rebind;

            queueOrSendMessage(H.BIND_SERVICE, s);
        }

        public final void scheduleUnbindService(IBinder token, Intent intent) {
            BindServiceData s = new BindServiceData();
            s.token = token;
            s.intent = intent;

            queueOrSendMessage(H.UNBIND_SERVICE, s);
        }

        public final void scheduleServiceArgs(IBinder token, int startId,
            int flags ,Intent args) {
            ServiceArgsData s = new ServiceArgsData();
            s.token = token;
            s.startId = startId;
            s.flags = flags;
            s.args = args;

            queueOrSendMessage(H.SERVICE_ARGS, s);
        }

        public final void scheduleStopService(IBinder token) {
            queueOrSendMessage(H.STOP_SERVICE, token);
        }

        public final void bindApplication(String processName,
                ApplicationInfo appInfo, List<ProviderInfo> providers,
                ComponentName instrumentationName, String profileFile,
                Bundle instrumentationArgs, IInstrumentationWatcher instrumentationWatcher,
                int debugMode, boolean isRestrictedBackupMode, Configuration config,
                Map<String, IBinder> services) {

            if (services != null) {
                // Setup the service cache in the ServiceManager
                ServiceManager.initServiceCache(services);
            }

            AppBindData data = new AppBindData();
            data.processName = processName;
            data.appInfo = appInfo;
            data.providers = providers;
            data.instrumentationName = instrumentationName;
            data.profileFile = profileFile;
            data.instrumentationArgs = instrumentationArgs;
            data.instrumentationWatcher = instrumentationWatcher;
            data.debugMode = debugMode;
            data.restrictedBackupMode = isRestrictedBackupMode;
            data.config = config;
            queueOrSendMessage(H.BIND_APPLICATION, data);
        }

        public final void scheduleExit() {
            queueOrSendMessage(H.EXIT_APPLICATION, null);
        }

        public final void scheduleSuicide() {
            queueOrSendMessage(H.SUICIDE, null);
        }

        public void requestThumbnail(IBinder token) {
            queueOrSendMessage(H.REQUEST_THUMBNAIL, token);
        }

        public void scheduleConfigurationChanged(Configuration config) {
            synchronized (mPackages) {
                if (mPendingConfiguration == null ||
                        mPendingConfiguration.isOtherSeqNewer(config)) {
                    mPendingConfiguration = config;
                }
            }
            queueOrSendMessage(H.CONFIGURATION_CHANGED, config);
        }

        public void updateTimeZone() {
            TimeZone.setDefault(null);
        }

        public void processInBackground() {
            mH.removeMessages(H.GC_WHEN_IDLE);
            mH.sendMessage(mH.obtainMessage(H.GC_WHEN_IDLE));
        }

        public void dumpService(FileDescriptor fd, IBinder servicetoken, String[] args) {
            DumpServiceInfo data = new DumpServiceInfo();
            data.fd = fd;
            data.service = servicetoken;
            data.args = args;
            data.dumped = false;
            queueOrSendMessage(H.DUMP_SERVICE, data);
            synchronized (data) {
                while (!data.dumped) {
                    try {
                        data.wait();
                    } catch (InterruptedException e) {
                        // no need to do anything here, we will keep waiting until
                        // dumped is set
                    }
                }
            }
        }

        // This function exists to make sure all receiver dispatching is
        // correctly ordered, since these are one-way calls and the binder driver
        // applies transaction ordering per object for such calls.
        public void scheduleRegisteredReceiver(IIntentReceiver receiver, Intent intent,
                int resultCode, String dataStr, Bundle extras, boolean ordered,
                boolean sticky) throws RemoteException {
            receiver.performReceive(intent, resultCode, dataStr, extras, ordered, sticky);
        }

        public void scheduleLowMemory() {
            queueOrSendMessage(H.LOW_MEMORY, null);
        }

        public void scheduleActivityConfigurationChanged(IBinder token) {
            queueOrSendMessage(H.ACTIVITY_CONFIGURATION_CHANGED, token);
        }

        public void requestPss() {
            try {
                ActivityManagerNative.getDefault().reportPss(this,
                        (int)Process.getPss(Process.myPid()));
            } catch (RemoteException e) {
            }
        }

        public void profilerControl(boolean start, String path, ParcelFileDescriptor fd) {
            ProfilerControlData pcd = new ProfilerControlData();
            pcd.path = path;
            pcd.fd = fd;
            queueOrSendMessage(H.PROFILER_CONTROL, pcd, start ? 1 : 0);
        }

        public void setSchedulingGroup(int group) {
            // Note: do this immediately, since going into the foreground
            // should happen regardless of what pending work we have to do
            // and the activity manager will wait for us to report back that
            // we are done before sending us to the background.
            try {
                Process.setProcessGroup(Process.myPid(), group);
            } catch (Exception e) {
                Slog.w(TAG, "Failed setting process group to " + group, e);
            }
        }

        public void getMemoryInfo(Debug.MemoryInfo outInfo) {
            Debug.getMemoryInfo(outInfo);
        }

        public void dispatchPackageBroadcast(int cmd, String[] packages) {
            queueOrSendMessage(H.DISPATCH_PACKAGE_BROADCAST, packages, cmd);
        }
        
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            long nativeMax = Debug.getNativeHeapSize() / 1024;
            long nativeAllocated = Debug.getNativeHeapAllocatedSize() / 1024;
            long nativeFree = Debug.getNativeHeapFreeSize() / 1024;

            Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memInfo);

            final int nativeShared = memInfo.nativeSharedDirty;
            final int dalvikShared = memInfo.dalvikSharedDirty;
            final int otherShared = memInfo.otherSharedDirty;

            final int nativePrivate = memInfo.nativePrivateDirty;
            final int dalvikPrivate = memInfo.dalvikPrivateDirty;
            final int otherPrivate = memInfo.otherPrivateDirty;

            Runtime runtime = Runtime.getRuntime();

            long dalvikMax = runtime.totalMemory() / 1024;
            long dalvikFree = runtime.freeMemory() / 1024;
            long dalvikAllocated = dalvikMax - dalvikFree;
            long viewInstanceCount = ViewDebug.getViewInstanceCount();
            long viewRootInstanceCount = ViewDebug.getViewRootInstanceCount();
            long appContextInstanceCount = ContextImpl.getInstanceCount();
            long activityInstanceCount = Activity.getInstanceCount();
            int globalAssetCount = AssetManager.getGlobalAssetCount();
            int globalAssetManagerCount = AssetManager.getGlobalAssetManagerCount();
            int binderLocalObjectCount = Debug.getBinderLocalObjectCount();
            int binderProxyObjectCount = Debug.getBinderProxyObjectCount();
            int binderDeathObjectCount = Debug.getBinderDeathObjectCount();
            int openSslSocketCount = OpenSSLSocketImpl.getInstanceCount();
            long sqliteAllocated = SQLiteDebug.getHeapAllocatedSize() / 1024;
            SQLiteDebug.PagerStats stats = SQLiteDebug.getDatabaseInfo();

            // Check to see if we were called by checkin server. If so, print terse format.
            boolean doCheckinFormat = false;
            if (args != null) {
                for (String arg : args) {
                    if ("-c".equals(arg)) doCheckinFormat = true;
                }
            }

            // For checkin, we print one long comma-separated list of values
            if (doCheckinFormat) {
                // NOTE: if you change anything significant below, also consider changing
                // ACTIVITY_THREAD_CHECKIN_VERSION.
                String processName = (mBoundApplication != null)
                        ? mBoundApplication.processName : "unknown";

                // Header
                pw.print(ACTIVITY_THREAD_CHECKIN_VERSION); pw.print(',');
                pw.print(Process.myPid()); pw.print(',');
                pw.print(processName); pw.print(',');

                // Heap info - max
                pw.print(nativeMax); pw.print(',');
                pw.print(dalvikMax); pw.print(',');
                pw.print("N/A,");
                pw.print(nativeMax + dalvikMax); pw.print(',');

                // Heap info - allocated
                pw.print(nativeAllocated); pw.print(',');
                pw.print(dalvikAllocated); pw.print(',');
                pw.print("N/A,");
                pw.print(nativeAllocated + dalvikAllocated); pw.print(',');

                // Heap info - free
                pw.print(nativeFree); pw.print(',');
                pw.print(dalvikFree); pw.print(',');
                pw.print("N/A,");
                pw.print(nativeFree + dalvikFree); pw.print(',');

                // Heap info - proportional set size
                pw.print(memInfo.nativePss); pw.print(',');
                pw.print(memInfo.dalvikPss); pw.print(',');
                pw.print(memInfo.otherPss); pw.print(',');
                pw.print(memInfo.nativePss + memInfo.dalvikPss + memInfo.otherPss); pw.print(',');

                // Heap info - shared
                pw.print(nativeShared); pw.print(',');
                pw.print(dalvikShared); pw.print(',');
                pw.print(otherShared); pw.print(',');
                pw.print(nativeShared + dalvikShared + otherShared); pw.print(',');

                // Heap info - private
                pw.print(nativePrivate); pw.print(',');
                pw.print(dalvikPrivate); pw.print(',');
                pw.print(otherPrivate); pw.print(',');
                pw.print(nativePrivate + dalvikPrivate + otherPrivate); pw.print(',');

                // Object counts
                pw.print(viewInstanceCount); pw.print(',');
                pw.print(viewRootInstanceCount); pw.print(',');
                pw.print(appContextInstanceCount); pw.print(',');
                pw.print(activityInstanceCount); pw.print(',');

                pw.print(globalAssetCount); pw.print(',');
                pw.print(globalAssetManagerCount); pw.print(',');
                pw.print(binderLocalObjectCount); pw.print(',');
                pw.print(binderProxyObjectCount); pw.print(',');

                pw.print(binderDeathObjectCount); pw.print(',');
                pw.print(openSslSocketCount); pw.print(',');

                // SQL
                pw.print(sqliteAllocated); pw.print(',');
                pw.print(stats.memoryUsed / 1024); pw.print(',');
                pw.print(stats.pageCacheOverflo / 1024); pw.print(',');
                pw.print(stats.largestMemAlloc / 1024); pw.print(',');
                for (int i = 0; i < stats.dbStats.size(); i++) {
                    DbStats dbStats = stats.dbStats.get(i);
                    printRow(pw, DB_INFO_FORMAT, dbStats.pageSize, dbStats.dbSize,
                            dbStats.lookaside, dbStats.dbName);
                    pw.print(',');
                }

                return;
            }

            // otherwise, show human-readable format
            printRow(pw, HEAP_COLUMN, "", "native", "dalvik", "other", "total");
            printRow(pw, HEAP_COLUMN, "size:", nativeMax, dalvikMax, "N/A", nativeMax + dalvikMax);
            printRow(pw, HEAP_COLUMN, "allocated:", nativeAllocated, dalvikAllocated, "N/A",
                    nativeAllocated + dalvikAllocated);
            printRow(pw, HEAP_COLUMN, "free:", nativeFree, dalvikFree, "N/A",
                    nativeFree + dalvikFree);

            printRow(pw, HEAP_COLUMN, "(Pss):", memInfo.nativePss, memInfo.dalvikPss,
                    memInfo.otherPss, memInfo.nativePss + memInfo.dalvikPss + memInfo.otherPss);

            printRow(pw, HEAP_COLUMN, "(shared dirty):", nativeShared, dalvikShared, otherShared,
                    nativeShared + dalvikShared + otherShared);
            printRow(pw, HEAP_COLUMN, "(priv dirty):", nativePrivate, dalvikPrivate, otherPrivate,
                    nativePrivate + dalvikPrivate + otherPrivate);

            pw.println(" ");
            pw.println(" Objects");
            printRow(pw, TWO_COUNT_COLUMNS, "Views:", viewInstanceCount, "ViewRoots:",
                    viewRootInstanceCount);

            printRow(pw, TWO_COUNT_COLUMNS, "AppContexts:", appContextInstanceCount,
                    "Activities:", activityInstanceCount);

            printRow(pw, TWO_COUNT_COLUMNS, "Assets:", globalAssetCount,
                    "AssetManagers:", globalAssetManagerCount);

            printRow(pw, TWO_COUNT_COLUMNS, "Local Binders:", binderLocalObjectCount,
                    "Proxy Binders:", binderProxyObjectCount);
            printRow(pw, ONE_COUNT_COLUMN, "Death Recipients:", binderDeathObjectCount);

            printRow(pw, ONE_COUNT_COLUMN, "OpenSSL Sockets:", openSslSocketCount);

            // SQLite mem info
            pw.println(" ");
            pw.println(" SQL");
            printRow(pw, TWO_COUNT_COLUMNS, "heap:", sqliteAllocated, "memoryUsed:",
                    stats.memoryUsed / 1024);
            printRow(pw, TWO_COUNT_COLUMNS, "pageCacheOverflo:", stats.pageCacheOverflo / 1024,
                    "largestMemAlloc:", stats.largestMemAlloc / 1024);
            pw.println(" ");
            int N = stats.dbStats.size();
            if (N > 0) {
                pw.println(" DATABASES");
                printRow(pw, "  %8s %8s %10s  %s", "Pagesize", "Dbsize", "Lookaside", "Dbname");
                for (int i = 0; i < N; i++) {
                    DbStats dbStats = stats.dbStats.get(i);
                    printRow(pw, DB_INFO_FORMAT, dbStats.pageSize, dbStats.dbSize,
                            dbStats.lookaside, dbStats.dbName);
                }
            }

            // Asset details.
            String assetAlloc = AssetManager.getAssetAllocations();
            if (assetAlloc != null) {
                pw.println(" ");
                pw.println(" Asset Allocations");
                pw.print(assetAlloc);
            }
        }

        private void printRow(PrintWriter pw, String format, Object...objs) {
            pw.println(String.format(format, objs));
        }
    }

    private final class H extends Handler {
        private H() {
            SamplingProfiler.getInstance().setEventThread(mLooper.getThread());
        }

        public static final int LAUNCH_ACTIVITY         = 100;
        public static final int PAUSE_ACTIVITY          = 101;
        public static final int PAUSE_ACTIVITY_FINISHING= 102;
        public static final int STOP_ACTIVITY_SHOW      = 103;
        public static final int STOP_ACTIVITY_HIDE      = 104;
        public static final int SHOW_WINDOW             = 105;
        public static final int HIDE_WINDOW             = 106;
        public static final int RESUME_ACTIVITY         = 107;
        public static final int SEND_RESULT             = 108;
        public static final int DESTROY_ACTIVITY         = 109;
        public static final int BIND_APPLICATION        = 110;
        public static final int EXIT_APPLICATION        = 111;
        public static final int NEW_INTENT              = 112;
        public static final int RECEIVER                = 113;
        public static final int CREATE_SERVICE          = 114;
        public static final int SERVICE_ARGS            = 115;
        public static final int STOP_SERVICE            = 116;
        public static final int REQUEST_THUMBNAIL       = 117;
        public static final int CONFIGURATION_CHANGED   = 118;
        public static final int CLEAN_UP_CONTEXT        = 119;
        public static final int GC_WHEN_IDLE            = 120;
        public static final int BIND_SERVICE            = 121;
        public static final int UNBIND_SERVICE          = 122;
        public static final int DUMP_SERVICE            = 123;
        public static final int LOW_MEMORY              = 124;
        public static final int ACTIVITY_CONFIGURATION_CHANGED = 125;
        public static final int RELAUNCH_ACTIVITY       = 126;
        public static final int PROFILER_CONTROL        = 127;
        public static final int CREATE_BACKUP_AGENT     = 128;
        public static final int DESTROY_BACKUP_AGENT    = 129;
        public static final int SUICIDE                 = 130;
        public static final int REMOVE_PROVIDER         = 131;
        public static final int ENABLE_JIT              = 132;
        public static final int DISPATCH_PACKAGE_BROADCAST = 133;
        String codeToString(int code) {
            if (localLOGV) {
                switch (code) {
                    case LAUNCH_ACTIVITY: return "LAUNCH_ACTIVITY";
                    case PAUSE_ACTIVITY: return "PAUSE_ACTIVITY";
                    case PAUSE_ACTIVITY_FINISHING: return "PAUSE_ACTIVITY_FINISHING";
                    case STOP_ACTIVITY_SHOW: return "STOP_ACTIVITY_SHOW";
                    case STOP_ACTIVITY_HIDE: return "STOP_ACTIVITY_HIDE";
                    case SHOW_WINDOW: return "SHOW_WINDOW";
                    case HIDE_WINDOW: return "HIDE_WINDOW";
                    case RESUME_ACTIVITY: return "RESUME_ACTIVITY";
                    case SEND_RESULT: return "SEND_RESULT";
                    case DESTROY_ACTIVITY: return "DESTROY_ACTIVITY";
                    case BIND_APPLICATION: return "BIND_APPLICATION";
                    case EXIT_APPLICATION: return "EXIT_APPLICATION";
                    case NEW_INTENT: return "NEW_INTENT";
                    case RECEIVER: return "RECEIVER";
                    case CREATE_SERVICE: return "CREATE_SERVICE";
                    case SERVICE_ARGS: return "SERVICE_ARGS";
                    case STOP_SERVICE: return "STOP_SERVICE";
                    case REQUEST_THUMBNAIL: return "REQUEST_THUMBNAIL";
                    case CONFIGURATION_CHANGED: return "CONFIGURATION_CHANGED";
                    case CLEAN_UP_CONTEXT: return "CLEAN_UP_CONTEXT";
                    case GC_WHEN_IDLE: return "GC_WHEN_IDLE";
                    case BIND_SERVICE: return "BIND_SERVICE";
                    case UNBIND_SERVICE: return "UNBIND_SERVICE";
                    case DUMP_SERVICE: return "DUMP_SERVICE";
                    case LOW_MEMORY: return "LOW_MEMORY";
                    case ACTIVITY_CONFIGURATION_CHANGED: return "ACTIVITY_CONFIGURATION_CHANGED";
                    case RELAUNCH_ACTIVITY: return "RELAUNCH_ACTIVITY";
                    case PROFILER_CONTROL: return "PROFILER_CONTROL";
                    case CREATE_BACKUP_AGENT: return "CREATE_BACKUP_AGENT";
                    case DESTROY_BACKUP_AGENT: return "DESTROY_BACKUP_AGENT";
                    case SUICIDE: return "SUICIDE";
                    case REMOVE_PROVIDER: return "REMOVE_PROVIDER";
                    case ENABLE_JIT: return "ENABLE_JIT";
                    case DISPATCH_PACKAGE_BROADCAST: return "DISPATCH_PACKAGE_BROADCAST";
                }
            }
            return "(unknown)";
        }
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LAUNCH_ACTIVITY: {
                    ActivityRecord r = (ActivityRecord)msg.obj;

                    r.packageInfo = getPackageInfoNoCheck(
                            r.activityInfo.applicationInfo);
                    handleLaunchActivity(r, null);
                } break;
                case RELAUNCH_ACTIVITY: {
                    ActivityRecord r = (ActivityRecord)msg.obj;
                    handleRelaunchActivity(r, msg.arg1);
                } break;
                case PAUSE_ACTIVITY:
                    handlePauseActivity((IBinder)msg.obj, false, msg.arg1 != 0, msg.arg2);
                    maybeSnapshot();
                    break;
                case PAUSE_ACTIVITY_FINISHING:
                    handlePauseActivity((IBinder)msg.obj, true, msg.arg1 != 0, msg.arg2);
                    break;
                case STOP_ACTIVITY_SHOW:
                    handleStopActivity((IBinder)msg.obj, true, msg.arg2);
                    break;
                case STOP_ACTIVITY_HIDE:
                    handleStopActivity((IBinder)msg.obj, false, msg.arg2);
                    break;
                case SHOW_WINDOW:
                    handleWindowVisibility((IBinder)msg.obj, true);
                    break;
                case HIDE_WINDOW:
                    handleWindowVisibility((IBinder)msg.obj, false);
                    break;
                case RESUME_ACTIVITY:
                    handleResumeActivity((IBinder)msg.obj, true,
                            msg.arg1 != 0);
                    break;
                case SEND_RESULT:
                    handleSendResult((ResultData)msg.obj);
                    break;
                case DESTROY_ACTIVITY:
                    handleDestroyActivity((IBinder)msg.obj, msg.arg1 != 0,
                            msg.arg2, false);
                    break;
                case BIND_APPLICATION:
                    AppBindData data = (AppBindData)msg.obj;
                    handleBindApplication(data);
                    break;
                case EXIT_APPLICATION:
                    if (mInitialApplication != null) {
                        mInitialApplication.onTerminate();
                    }
                    Looper.myLooper().quit();
                    break;
                case NEW_INTENT:
                    handleNewIntent((NewIntentData)msg.obj);
                    break;
                case RECEIVER:
                    handleReceiver((ReceiverData)msg.obj);
                    maybeSnapshot();
                    break;
                case CREATE_SERVICE:
                    handleCreateService((CreateServiceData)msg.obj);
                    break;
                case BIND_SERVICE:
                    handleBindService((BindServiceData)msg.obj);
                    break;
                case UNBIND_SERVICE:
                    handleUnbindService((BindServiceData)msg.obj);
                    break;
                case SERVICE_ARGS:
                    handleServiceArgs((ServiceArgsData)msg.obj);
                    break;
                case STOP_SERVICE:
                    handleStopService((IBinder)msg.obj);
                    maybeSnapshot();
                    break;
                case REQUEST_THUMBNAIL:
                    handleRequestThumbnail((IBinder)msg.obj);
                    break;
                case CONFIGURATION_CHANGED:
                    handleConfigurationChanged((Configuration)msg.obj);
                    break;
                case CLEAN_UP_CONTEXT:
                    ContextCleanupInfo cci = (ContextCleanupInfo)msg.obj;
                    cci.context.performFinalCleanup(cci.who, cci.what);
                    break;
                case GC_WHEN_IDLE:
                    scheduleGcIdler();
                    break;
                case DUMP_SERVICE:
                    handleDumpService((DumpServiceInfo)msg.obj);
                    break;
                case LOW_MEMORY:
                    handleLowMemory();
                    break;
                case ACTIVITY_CONFIGURATION_CHANGED:
                    handleActivityConfigurationChanged((IBinder)msg.obj);
                    break;
                case PROFILER_CONTROL:
                    handleProfilerControl(msg.arg1 != 0, (ProfilerControlData)msg.obj);
                    break;
                case CREATE_BACKUP_AGENT:
                    handleCreateBackupAgent((CreateBackupAgentData)msg.obj);
                    break;
                case DESTROY_BACKUP_AGENT:
                    handleDestroyBackupAgent((CreateBackupAgentData)msg.obj);
                    break;
                case SUICIDE:
                    Process.killProcess(Process.myPid());
                    break;
                case REMOVE_PROVIDER:
                    completeRemoveProvider((IContentProvider)msg.obj);
                    break;
                case ENABLE_JIT:
                    ensureJitEnabled();
                    break;
                case DISPATCH_PACKAGE_BROADCAST:
                    handleDispatchPackageBroadcast(msg.arg1, (String[])msg.obj);
                    break;
            }
        }

        void maybeSnapshot() {
            if (mBoundApplication != null) {
                SamplingProfilerIntegration.writeSnapshot(
                        mBoundApplication.processName);
            }
        }
    }

    private final class Idler implements MessageQueue.IdleHandler {
        public final boolean queueIdle() {
            ActivityRecord a = mNewActivities;
            if (a != null) {
                mNewActivities = null;
                IActivityManager am = ActivityManagerNative.getDefault();
                ActivityRecord prev;
                do {
                    if (localLOGV) Slog.v(
                        TAG, "Reporting idle of " + a +
                        " finished=" +
                        (a.activity != null ? a.activity.mFinished : false));
                    if (a.activity != null && !a.activity.mFinished) {
                        try {
                            am.activityIdle(a.token, a.createdConfig);
                            a.createdConfig = null;
                        } catch (RemoteException ex) {
                        }
                    }
                    prev = a;
                    a = a.nextIdle;
                    prev.nextIdle = null;
                } while (a != null);
            }
            ensureJitEnabled();
            return false;
        }
    }

    final class GcIdler implements MessageQueue.IdleHandler {
        public final boolean queueIdle() {
            doGcIfNeeded();
            return false;
        }
    }

    private final static class ResourcesKey {
        final private String mResDir;
        final private float mScale;
        final private int mHash;

        ResourcesKey(String resDir, float scale) {
            mResDir = resDir;
            mScale = scale;
            mHash = mResDir.hashCode() << 2 + (int) (mScale * 2);
        }

        @Override
        public int hashCode() {
            return mHash;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ResourcesKey)) {
                return false;
            }
            ResourcesKey peer = (ResourcesKey) obj;
            return mResDir.equals(peer.mResDir) && mScale == peer.mScale;
        }
    }

    static IPackageManager sPackageManager;

    final ApplicationThread mAppThread = new ApplicationThread();
    final Looper mLooper = Looper.myLooper();
    final H mH = new H();
    final HashMap<IBinder, ActivityRecord> mActivities
            = new HashMap<IBinder, ActivityRecord>();
    // List of new activities (via ActivityRecord.nextIdle) that should
    // be reported when next we idle.
    ActivityRecord mNewActivities = null;
    // Number of activities that are currently visible on-screen.
    int mNumVisibleActivities = 0;
    final HashMap<IBinder, Service> mServices
            = new HashMap<IBinder, Service>();
    AppBindData mBoundApplication;
    Configuration mConfiguration;
    Configuration mResConfiguration;
    Application mInitialApplication;
    final ArrayList<Application> mAllApplications
            = new ArrayList<Application>();
    // set of instantiated backup agents, keyed by package name
    final HashMap<String, BackupAgent> mBackupAgents = new HashMap<String, BackupAgent>();
    static final ThreadLocal sThreadLocal = new ThreadLocal();
    Instrumentation mInstrumentation;
    String mInstrumentationAppDir = null;
    String mInstrumentationAppPackage = null;
    String mInstrumentedAppDir = null;
    boolean mSystemThread = false;
    boolean mJitEnabled = false;

    // These can be accessed by multiple threads; mPackages is the lock.
    // XXX For now we keep around information about all packages we have
    // seen, not removing entries from this map.
    final HashMap<String, WeakReference<PackageInfo>> mPackages
            = new HashMap<String, WeakReference<PackageInfo>>();
    final HashMap<String, WeakReference<PackageInfo>> mResourcePackages
            = new HashMap<String, WeakReference<PackageInfo>>();
    Display mDisplay = null;
    DisplayMetrics mDisplayMetrics = null;
    final HashMap<ResourcesKey, WeakReference<Resources> > mActiveResources
            = new HashMap<ResourcesKey, WeakReference<Resources> >();
    final ArrayList<ActivityRecord> mRelaunchingActivities
            = new ArrayList<ActivityRecord>();
    Configuration mPendingConfiguration = null;

    // The lock of mProviderMap protects the following variables.
    final HashMap<String, ProviderRecord> mProviderMap
        = new HashMap<String, ProviderRecord>();
    final HashMap<IBinder, ProviderRefCount> mProviderRefCountMap
        = new HashMap<IBinder, ProviderRefCount>();
    final HashMap<IBinder, ProviderRecord> mLocalProviders
        = new HashMap<IBinder, ProviderRecord>();

    final GcIdler mGcIdler = new GcIdler();
    boolean mGcIdlerScheduled = false;

    public final PackageInfo getPackageInfo(String packageName, int flags) {
        synchronized (mPackages) {
            WeakReference<PackageInfo> ref;
            if ((flags&Context.CONTEXT_INCLUDE_CODE) != 0) {
                ref = mPackages.get(packageName);
            } else {
                ref = mResourcePackages.get(packageName);
            }
            PackageInfo packageInfo = ref != null ? ref.get() : null;
            //Slog.i(TAG, "getPackageInfo " + packageName + ": " + packageInfo);
            //if (packageInfo != null) Slog.i(TAG, "isUptoDate " + packageInfo.mResDir
            //        + ": " + packageInfo.mResources.getAssets().isUpToDate());
            if (packageInfo != null && (packageInfo.mResources == null
                    || packageInfo.mResources.getAssets().isUpToDate())) {
                if (packageInfo.isSecurityViolation()
                        && (flags&Context.CONTEXT_IGNORE_SECURITY) == 0) {
                    throw new SecurityException(
                            "Requesting code from " + packageName
                            + " to be run in process "
                            + mBoundApplication.processName
                            + "/" + mBoundApplication.appInfo.uid);
                }
                return packageInfo;
            }
        }

        ApplicationInfo ai = null;
        try {
            ai = getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_SHARED_LIBRARY_FILES);
        } catch (RemoteException e) {
        }

        if (ai != null) {
            return getPackageInfo(ai, flags);
        }

        return null;
    }

    public final PackageInfo getPackageInfo(ApplicationInfo ai, int flags) {
        boolean includeCode = (flags&Context.CONTEXT_INCLUDE_CODE) != 0;
        boolean securityViolation = includeCode && ai.uid != 0
                && ai.uid != Process.SYSTEM_UID && (mBoundApplication != null
                        ? ai.uid != mBoundApplication.appInfo.uid : true);
        if ((flags&(Context.CONTEXT_INCLUDE_CODE
                |Context.CONTEXT_IGNORE_SECURITY))
                == Context.CONTEXT_INCLUDE_CODE) {
            if (securityViolation) {
                String msg = "Requesting code from " + ai.packageName
                        + " (with uid " + ai.uid + ")";
                if (mBoundApplication != null) {
                    msg = msg + " to be run in process "
                        + mBoundApplication.processName + " (with uid "
                        + mBoundApplication.appInfo.uid + ")";
                }
                throw new SecurityException(msg);
            }
        }
        return getPackageInfo(ai, null, securityViolation, includeCode);
    }

    public final PackageInfo getPackageInfoNoCheck(ApplicationInfo ai) {
        return getPackageInfo(ai, null, false, true);
    }

    private final PackageInfo getPackageInfo(ApplicationInfo aInfo,
            ClassLoader baseLoader, boolean securityViolation, boolean includeCode) {
        synchronized (mPackages) {
            WeakReference<PackageInfo> ref;
            if (includeCode) {
                ref = mPackages.get(aInfo.packageName);
            } else {
                ref = mResourcePackages.get(aInfo.packageName);
            }
            PackageInfo packageInfo = ref != null ? ref.get() : null;
            if (packageInfo == null || (packageInfo.mResources != null
                    && !packageInfo.mResources.getAssets().isUpToDate())) {
                if (localLOGV) Slog.v(TAG, (includeCode ? "Loading code package "
                        : "Loading resource-only package ") + aInfo.packageName
                        + " (in " + (mBoundApplication != null
                                ? mBoundApplication.processName : null)
                        + ")");
                packageInfo =
                    new PackageInfo(this, aInfo, this, baseLoader,
                            securityViolation, includeCode &&
                            (aInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0);
                if (includeCode) {
                    mPackages.put(aInfo.packageName,
                            new WeakReference<PackageInfo>(packageInfo));
                } else {
                    mResourcePackages.put(aInfo.packageName,
                            new WeakReference<PackageInfo>(packageInfo));
                }
            }
            return packageInfo;
        }
    }

    ActivityThread() {
    }

    public ApplicationThread getApplicationThread()
    {
        return mAppThread;
    }

    public Instrumentation getInstrumentation()
    {
        return mInstrumentation;
    }

    public Configuration getConfiguration() {
        return mConfiguration;
    }

    public boolean isProfiling() {
        return mBoundApplication != null && mBoundApplication.profileFile != null;
    }

    public String getProfileFilePath() {
        return mBoundApplication.profileFile;
    }

    public Looper getLooper() {
        return mLooper;
    }

    public Application getApplication() {
        return mInitialApplication;
    }

    public String getProcessName() {
        return mBoundApplication.processName;
    }

    public ContextImpl getSystemContext() {
        synchronized (this) {
            if (mSystemContext == null) {
                ContextImpl context =
                    ContextImpl.createSystemContext(this);
                PackageInfo info = new PackageInfo(this, "android", context, null);
                context.init(info, null, this);
                context.getResources().updateConfiguration(
                        getConfiguration(), getDisplayMetricsLocked(false));
                mSystemContext = context;
                //Slog.i(TAG, "Created system resources " + context.getResources()
                //        + ": " + context.getResources().getConfiguration());
            }
        }
        return mSystemContext;
    }

    public void installSystemApplicationInfo(ApplicationInfo info) {
        synchronized (this) {
            ContextImpl context = getSystemContext();
            context.init(new PackageInfo(this, "android", context, info), null, this);
        }
    }

    void ensureJitEnabled() {
        if (!mJitEnabled) {
            mJitEnabled = true;
            dalvik.system.VMRuntime.getRuntime().startJitCompilation();
        }
    }
    
    void scheduleGcIdler() {
        if (!mGcIdlerScheduled) {
            mGcIdlerScheduled = true;
            Looper.myQueue().addIdleHandler(mGcIdler);
        }
        mH.removeMessages(H.GC_WHEN_IDLE);
    }

    void unscheduleGcIdler() {
        if (mGcIdlerScheduled) {
            mGcIdlerScheduled = false;
            Looper.myQueue().removeIdleHandler(mGcIdler);
        }
        mH.removeMessages(H.GC_WHEN_IDLE);
    }

    void doGcIfNeeded() {
        mGcIdlerScheduled = false;
        final long now = SystemClock.uptimeMillis();
        //Slog.i(TAG, "**** WE MIGHT WANT TO GC: then=" + Binder.getLastGcTime()
        //        + "m now=" + now);
        if ((BinderInternal.getLastGcTime()+MIN_TIME_BETWEEN_GCS) < now) {
            //Slog.i(TAG, "**** WE DO, WE DO WANT TO GC!");
            BinderInternal.forceGc("bg");
        }
    }

    public final ActivityInfo resolveActivityInfo(Intent intent) {
        ActivityInfo aInfo = intent.resolveActivityInfo(
                mInitialApplication.getPackageManager(), PackageManager.GET_SHARED_LIBRARY_FILES);
        if (aInfo == null) {
            // Throw an exception.
            Instrumentation.checkStartActivityResult(
                    IActivityManager.START_CLASS_NOT_FOUND, intent);
        }
        return aInfo;
    }

    public final Activity startActivityNow(Activity parent, String id,
        Intent intent, ActivityInfo activityInfo, IBinder token, Bundle state,
        Object lastNonConfigurationInstance) {
        ActivityRecord r = new ActivityRecord();
            r.token = token;
            r.ident = 0;
            r.intent = intent;
            r.state = state;
            r.parent = parent;
            r.embeddedID = id;
            r.activityInfo = activityInfo;
            r.lastNonConfigurationInstance = lastNonConfigurationInstance;
        if (localLOGV) {
            ComponentName compname = intent.getComponent();
            String name;
            if (compname != null) {
                name = compname.toShortString();
            } else {
                name = "(Intent " + intent + ").getComponent() returned null";
            }
            Slog.v(TAG, "Performing launch: action=" + intent.getAction()
                    + ", comp=" + name
                    + ", token=" + token);
        }
        return performLaunchActivity(r, null);
    }

    public final Activity getActivity(IBinder token) {
        return mActivities.get(token).activity;
    }

    public final void sendActivityResult(
            IBinder token, String id, int requestCode,
            int resultCode, Intent data) {
        if (DEBUG_RESULTS) Slog.v(TAG, "sendActivityResult: id=" + id
                + " req=" + requestCode + " res=" + resultCode + " data=" + data);
        ArrayList<ResultInfo> list = new ArrayList<ResultInfo>();
        list.add(new ResultInfo(id, requestCode, resultCode, data));
        mAppThread.scheduleSendResult(token, list);
    }

    // if the thread hasn't started yet, we don't have the handler, so just
    // save the messages until we're ready.
    private final void queueOrSendMessage(int what, Object obj) {
        queueOrSendMessage(what, obj, 0, 0);
    }

    private final void queueOrSendMessage(int what, Object obj, int arg1) {
        queueOrSendMessage(what, obj, arg1, 0);
    }

    private final void queueOrSendMessage(int what, Object obj, int arg1, int arg2) {
        synchronized (this) {
            if (localLOGV) Slog.v(
                TAG, "SCHEDULE " + what + " " + mH.codeToString(what)
                + ": " + arg1 + " / " + obj);
            Message msg = Message.obtain();
            msg.what = what;
            msg.obj = obj;
            msg.arg1 = arg1;
            msg.arg2 = arg2;
            mH.sendMessage(msg);
        }
    }

    final void scheduleContextCleanup(ContextImpl context, String who,
            String what) {
        ContextCleanupInfo cci = new ContextCleanupInfo();
        cci.context = context;
        cci.who = who;
        cci.what = what;
        queueOrSendMessage(H.CLEAN_UP_CONTEXT, cci);
    }

    private final Activity performLaunchActivity(ActivityRecord r, Intent customIntent) {
        // System.out.println("##### [" + System.currentTimeMillis() + "] ActivityThread.performLaunchActivity(" + r + ")");

        ActivityInfo aInfo = r.activityInfo;
        if (r.packageInfo == null) {
            r.packageInfo = getPackageInfo(aInfo.applicationInfo,
                    Context.CONTEXT_INCLUDE_CODE);
        }

        ComponentName component = r.intent.getComponent();
        if (component == null) {
            component = r.intent.resolveActivity(
                mInitialApplication.getPackageManager());
            r.intent.setComponent(component);
        }

        if (r.activityInfo.targetActivity != null) {
            component = new ComponentName(r.activityInfo.packageName,
                    r.activityInfo.targetActivity);
        }

        Activity activity = null;
        try {
            java.lang.ClassLoader cl = r.packageInfo.getClassLoader();
            activity = mInstrumentation.newActivity(
                    cl, component.getClassName(), r.intent);
            r.intent.setExtrasClassLoader(cl);
            if (r.state != null) {
                r.state.setClassLoader(cl);
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(activity, e)) {
                throw new RuntimeException(
                    "Unable to instantiate activity " + component
                    + ": " + e.toString(), e);
            }
        }

        try {
            Application app = r.packageInfo.makeApplication(false, mInstrumentation);

            if (localLOGV) Slog.v(TAG, "Performing launch of " + r);
            if (localLOGV) Slog.v(
                    TAG, r + ": app=" + app
                    + ", appName=" + app.getPackageName()
                    + ", pkg=" + r.packageInfo.getPackageName()
                    + ", comp=" + r.intent.getComponent().toShortString()
                    + ", dir=" + r.packageInfo.getAppDir());

            if (activity != null) {
                ContextImpl appContext = new ContextImpl();
                appContext.init(r.packageInfo, r.token, this);
                appContext.setOuterContext(activity);
                CharSequence title = r.activityInfo.loadLabel(appContext.getPackageManager());
                Configuration config = new Configuration(mConfiguration);
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Launching activity "
                        + r.activityInfo.name + " with config " + config);
                activity.attach(appContext, this, getInstrumentation(), r.token,
                        r.ident, app, r.intent, r.activityInfo, title, r.parent,
                        r.embeddedID, r.lastNonConfigurationInstance,
                        r.lastNonConfigurationChildInstances, config);

                if (customIntent != null) {
                    activity.mIntent = customIntent;
                }
                r.lastNonConfigurationInstance = null;
                r.lastNonConfigurationChildInstances = null;
                activity.mStartedActivity = false;
                int theme = r.activityInfo.getThemeResource();
                if (theme != 0) {
                    activity.setTheme(theme);
                }

                activity.mCalled = false;
                mInstrumentation.callActivityOnCreate(activity, r.state);
                if (!activity.mCalled) {
                    throw new SuperNotCalledException(
                        "Activity " + r.intent.getComponent().toShortString() +
                        " did not call through to super.onCreate()");
                }
                r.activity = activity;
                r.stopped = true;
                if (!r.activity.mFinished) {
                    activity.performStart();
                    r.stopped = false;
                }
                if (!r.activity.mFinished) {
                    if (r.state != null) {
                        mInstrumentation.callActivityOnRestoreInstanceState(activity, r.state);
                    }
                }
                if (!r.activity.mFinished) {
                    activity.mCalled = false;
                    mInstrumentation.callActivityOnPostCreate(activity, r.state);
                    if (!activity.mCalled) {
                        throw new SuperNotCalledException(
                            "Activity " + r.intent.getComponent().toShortString() +
                            " did not call through to super.onPostCreate()");
                    }
                }
            }
            r.paused = true;

            mActivities.put(r.token, r);

        } catch (SuperNotCalledException e) {
            throw e;

        } catch (Exception e) {
            if (!mInstrumentation.onException(activity, e)) {
                throw new RuntimeException(
                    "Unable to start activity " + component
                    + ": " + e.toString(), e);
            }
        }

        return activity;
    }

    private final void handleLaunchActivity(ActivityRecord r, Intent customIntent) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        if (localLOGV) Slog.v(
            TAG, "Handling launch of " + r);
        Activity a = performLaunchActivity(r, customIntent);

        if (a != null) {
            r.createdConfig = new Configuration(mConfiguration);
            Bundle oldState = r.state;
            handleResumeActivity(r.token, false, r.isForward);

            if (!r.activity.mFinished && r.startsNotResumed) {
                // The activity manager actually wants this one to start out
                // paused, because it needs to be visible but isn't in the
                // foreground.  We accomplish this by going through the
                // normal startup (because activities expect to go through
                // onResume() the first time they run, before their window
                // is displayed), and then pausing it.  However, in this case
                // we do -not- need to do the full pause cycle (of freezing
                // and such) because the activity manager assumes it can just
                // retain the current state it has.
                try {
                    r.activity.mCalled = false;
                    mInstrumentation.callActivityOnPause(r.activity);
                    // We need to keep around the original state, in case
                    // we need to be created again.
                    r.state = oldState;
                    if (!r.activity.mCalled) {
                        throw new SuperNotCalledException(
                            "Activity " + r.intent.getComponent().toShortString() +
                            " did not call through to super.onPause()");
                    }

                } catch (SuperNotCalledException e) {
                    throw e;

                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to pause activity "
                                + r.intent.getComponent().toShortString()
                                + ": " + e.toString(), e);
                    }
                }
                r.paused = true;
            }
        } else {
            // If there was an error, for any reason, tell the activity
            // manager to stop us.
            try {
                ActivityManagerNative.getDefault()
                    .finishActivity(r.token, Activity.RESULT_CANCELED, null);
            } catch (RemoteException ex) {
            }
        }
    }

    private final void deliverNewIntents(ActivityRecord r,
            List<Intent> intents) {
        final int N = intents.size();
        for (int i=0; i<N; i++) {
            Intent intent = intents.get(i);
            intent.setExtrasClassLoader(r.activity.getClassLoader());
            mInstrumentation.callActivityOnNewIntent(r.activity, intent);
        }
    }

    public final void performNewIntents(IBinder token,
            List<Intent> intents) {
        ActivityRecord r = mActivities.get(token);
        if (r != null) {
            final boolean resumed = !r.paused;
            if (resumed) {
                mInstrumentation.callActivityOnPause(r.activity);
            }
            deliverNewIntents(r, intents);
            if (resumed) {
                mInstrumentation.callActivityOnResume(r.activity);
            }
        }
    }

    private final void handleNewIntent(NewIntentData data) {
        performNewIntents(data.token, data.intents);
    }

    private final void handleReceiver(ReceiverData data) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        String component = data.intent.getComponent().getClassName();

        PackageInfo packageInfo = getPackageInfoNoCheck(
                data.info.applicationInfo);

        IActivityManager mgr = ActivityManagerNative.getDefault();

        BroadcastReceiver receiver = null;
        try {
            java.lang.ClassLoader cl = packageInfo.getClassLoader();
            data.intent.setExtrasClassLoader(cl);
            if (data.resultExtras != null) {
                data.resultExtras.setClassLoader(cl);
            }
            receiver = (BroadcastReceiver)cl.loadClass(component).newInstance();
        } catch (Exception e) {
            try {
                if (DEBUG_BROADCAST) Slog.i(TAG,
                        "Finishing failed broadcast to " + data.intent.getComponent());
                mgr.finishReceiver(mAppThread.asBinder(), data.resultCode,
                                   data.resultData, data.resultExtras, data.resultAbort);
            } catch (RemoteException ex) {
            }
            throw new RuntimeException(
                "Unable to instantiate receiver " + component
                + ": " + e.toString(), e);
        }

        try {
            Application app = packageInfo.makeApplication(false, mInstrumentation);

            if (localLOGV) Slog.v(
                TAG, "Performing receive of " + data.intent
                + ": app=" + app
                + ", appName=" + app.getPackageName()
                + ", pkg=" + packageInfo.getPackageName()
                + ", comp=" + data.intent.getComponent().toShortString()
                + ", dir=" + packageInfo.getAppDir());

            ContextImpl context = (ContextImpl)app.getBaseContext();
            receiver.setOrderedHint(true);
            receiver.setResult(data.resultCode, data.resultData,
                data.resultExtras);
            receiver.setOrderedHint(data.sync);
            receiver.onReceive(context.getReceiverRestrictedContext(),
                    data.intent);
        } catch (Exception e) {
            try {
                if (DEBUG_BROADCAST) Slog.i(TAG,
                        "Finishing failed broadcast to " + data.intent.getComponent());
                mgr.finishReceiver(mAppThread.asBinder(), data.resultCode,
                    data.resultData, data.resultExtras, data.resultAbort);
            } catch (RemoteException ex) {
            }
            if (!mInstrumentation.onException(receiver, e)) {
                throw new RuntimeException(
                    "Unable to start receiver " + component
                    + ": " + e.toString(), e);
            }
        }

        try {
            if (data.sync) {
                if (DEBUG_BROADCAST) Slog.i(TAG,
                        "Finishing ordered broadcast to " + data.intent.getComponent());
                mgr.finishReceiver(
                    mAppThread.asBinder(), receiver.getResultCode(),
                    receiver.getResultData(), receiver.getResultExtras(false),
                        receiver.getAbortBroadcast());
            } else {
                if (DEBUG_BROADCAST) Slog.i(TAG,
                        "Finishing broadcast to " + data.intent.getComponent());
                mgr.finishReceiver(mAppThread.asBinder(), 0, null, null, false);
            }
        } catch (RemoteException ex) {
        }
    }

    // Instantiate a BackupAgent and tell it that it's alive
    private final void handleCreateBackupAgent(CreateBackupAgentData data) {
        if (DEBUG_BACKUP) Slog.v(TAG, "handleCreateBackupAgent: " + data);

        // no longer idle; we have backup work to do
        unscheduleGcIdler();

        // instantiate the BackupAgent class named in the manifest
        PackageInfo packageInfo = getPackageInfoNoCheck(data.appInfo);
        String packageName = packageInfo.mPackageName;
        if (mBackupAgents.get(packageName) != null) {
            Slog.d(TAG, "BackupAgent " + "  for " + packageName
                    + " already exists");
            return;
        }

        BackupAgent agent = null;
        String classname = data.appInfo.backupAgentName;
        if (classname == null) {
            if (data.backupMode == IApplicationThread.BACKUP_MODE_INCREMENTAL) {
                Slog.e(TAG, "Attempted incremental backup but no defined agent for "
                        + packageName);
                return;
            }
            classname = "android.app.FullBackupAgent";
        }
        try {
            IBinder binder = null;
            try {
                java.lang.ClassLoader cl = packageInfo.getClassLoader();
                agent = (BackupAgent) cl.loadClass(data.appInfo.backupAgentName).newInstance();

                // set up the agent's context
                if (DEBUG_BACKUP) Slog.v(TAG, "Initializing BackupAgent "
                        + data.appInfo.backupAgentName);

                ContextImpl context = new ContextImpl();
                context.init(packageInfo, null, this);
                context.setOuterContext(agent);
                agent.attach(context);

                agent.onCreate();
                binder = agent.onBind();
                mBackupAgents.put(packageName, agent);
            } catch (Exception e) {
                // If this is during restore, fail silently; otherwise go
                // ahead and let the user see the crash.
                Slog.e(TAG, "Agent threw during creation: " + e);
                if (data.backupMode != IApplicationThread.BACKUP_MODE_RESTORE) {
                    throw e;
                }
                // falling through with 'binder' still null
            }

            // tell the OS that we're live now
            try {
                ActivityManagerNative.getDefault().backupAgentCreated(packageName, binder);
            } catch (RemoteException e) {
                // nothing to do.
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to create BackupAgent "
                    + data.appInfo.backupAgentName + ": " + e.toString(), e);
        }
    }

    // Tear down a BackupAgent
    private final void handleDestroyBackupAgent(CreateBackupAgentData data) {
        if (DEBUG_BACKUP) Slog.v(TAG, "handleDestroyBackupAgent: " + data);

        PackageInfo packageInfo = getPackageInfoNoCheck(data.appInfo);
        String packageName = packageInfo.mPackageName;
        BackupAgent agent = mBackupAgents.get(packageName);
        if (agent != null) {
            try {
                agent.onDestroy();
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown in onDestroy by backup agent of " + data.appInfo);
                e.printStackTrace();
            }
            mBackupAgents.remove(packageName);
        } else {
            Slog.w(TAG, "Attempt to destroy unknown backup agent " + data);
        }
    }

    private final void handleCreateService(CreateServiceData data) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        PackageInfo packageInfo = getPackageInfoNoCheck(
                data.info.applicationInfo);
        Service service = null;
        try {
            java.lang.ClassLoader cl = packageInfo.getClassLoader();
            service = (Service) cl.loadClass(data.info.name).newInstance();
        } catch (Exception e) {
            if (!mInstrumentation.onException(service, e)) {
                throw new RuntimeException(
                    "Unable to instantiate service " + data.info.name
                    + ": " + e.toString(), e);
            }
        }

        try {
            if (localLOGV) Slog.v(TAG, "Creating service " + data.info.name);

            ContextImpl context = new ContextImpl();
            context.init(packageInfo, null, this);

            Application app = packageInfo.makeApplication(false, mInstrumentation);
            context.setOuterContext(service);
            service.attach(context, this, data.info.name, data.token, app,
                    ActivityManagerNative.getDefault());
            service.onCreate();
            mServices.put(data.token, service);
            try {
                ActivityManagerNative.getDefault().serviceDoneExecuting(
                        data.token, 0, 0, 0);
            } catch (RemoteException e) {
                // nothing to do.
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(service, e)) {
                throw new RuntimeException(
                    "Unable to create service " + data.info.name
                    + ": " + e.toString(), e);
            }
        }
    }

    private final void handleBindService(BindServiceData data) {
        Service s = mServices.get(data.token);
        if (s != null) {
            try {
                data.intent.setExtrasClassLoader(s.getClassLoader());
                try {
                    if (!data.rebind) {
                        IBinder binder = s.onBind(data.intent);
                        ActivityManagerNative.getDefault().publishService(
                                data.token, data.intent, binder);
                    } else {
                        s.onRebind(data.intent);
                        ActivityManagerNative.getDefault().serviceDoneExecuting(
                                data.token, 0, 0, 0);
                    }
                    ensureJitEnabled();
                } catch (RemoteException ex) {
                }
            } catch (Exception e) {
                if (!mInstrumentation.onException(s, e)) {
                    throw new RuntimeException(
                            "Unable to bind to service " + s
                            + " with " + data.intent + ": " + e.toString(), e);
                }
            }
        }
    }

    private final void handleUnbindService(BindServiceData data) {
        Service s = mServices.get(data.token);
        if (s != null) {
            try {
                data.intent.setExtrasClassLoader(s.getClassLoader());
                boolean doRebind = s.onUnbind(data.intent);
                try {
                    if (doRebind) {
                        ActivityManagerNative.getDefault().unbindFinished(
                                data.token, data.intent, doRebind);
                    } else {
                        ActivityManagerNative.getDefault().serviceDoneExecuting(
                                data.token, 0, 0, 0);
                    }
                } catch (RemoteException ex) {
                }
            } catch (Exception e) {
                if (!mInstrumentation.onException(s, e)) {
                    throw new RuntimeException(
                            "Unable to unbind to service " + s
                            + " with " + data.intent + ": " + e.toString(), e);
                }
            }
        }
    }

    private void handleDumpService(DumpServiceInfo info) {
        try {
            Service s = mServices.get(info.service);
            if (s != null) {
                PrintWriter pw = new PrintWriter(new FileOutputStream(info.fd));
                s.dump(info.fd, pw, info.args);
                pw.close();
            }
        } finally {
            synchronized (info) {
                info.dumped = true;
                info.notifyAll();
            }
        }
    }

    private final void handleServiceArgs(ServiceArgsData data) {
        Service s = mServices.get(data.token);
        if (s != null) {
            try {
                if (data.args != null) {
                    data.args.setExtrasClassLoader(s.getClassLoader());
                }
                int res = s.onStartCommand(data.args, data.flags, data.startId);
                try {
                    ActivityManagerNative.getDefault().serviceDoneExecuting(
                            data.token, 1, data.startId, res);
                } catch (RemoteException e) {
                    // nothing to do.
                }
                ensureJitEnabled();
            } catch (Exception e) {
                if (!mInstrumentation.onException(s, e)) {
                    throw new RuntimeException(
                            "Unable to start service " + s
                            + " with " + data.args + ": " + e.toString(), e);
                }
            }
        }
    }

    private final void handleStopService(IBinder token) {
        Service s = mServices.remove(token);
        if (s != null) {
            try {
                if (localLOGV) Slog.v(TAG, "Destroying service " + s);
                s.onDestroy();
                Context context = s.getBaseContext();
                if (context instanceof ContextImpl) {
                    final String who = s.getClassName();
                    ((ContextImpl) context).scheduleFinalCleanup(who, "Service");
                }
                try {
                    ActivityManagerNative.getDefault().serviceDoneExecuting(
                            token, 0, 0, 0);
                } catch (RemoteException e) {
                    // nothing to do.
                }
            } catch (Exception e) {
                if (!mInstrumentation.onException(s, e)) {
                    throw new RuntimeException(
                            "Unable to stop service " + s
                            + ": " + e.toString(), e);
                }
            }
        }
        //Slog.i(TAG, "Running services: " + mServices);
    }

    public final ActivityRecord performResumeActivity(IBinder token,
            boolean clearHide) {
        ActivityRecord r = mActivities.get(token);
        if (localLOGV) Slog.v(TAG, "Performing resume of " + r
                + " finished=" + r.activity.mFinished);
        if (r != null && !r.activity.mFinished) {
            if (clearHide) {
                r.hideForNow = false;
                r.activity.mStartedActivity = false;
            }
            try {
                if (r.pendingIntents != null) {
                    deliverNewIntents(r, r.pendingIntents);
                    r.pendingIntents = null;
                }
                if (r.pendingResults != null) {
                    deliverResults(r, r.pendingResults);
                    r.pendingResults = null;
                }
                r.activity.performResume();

                EventLog.writeEvent(LOG_ON_RESUME_CALLED,
                        r.activity.getComponentName().getClassName());

                r.paused = false;
                r.stopped = false;
                r.state = null;
            } catch (Exception e) {
                if (!mInstrumentation.onException(r.activity, e)) {
                    throw new RuntimeException(
                        "Unable to resume activity "
                        + r.intent.getComponent().toShortString()
                        + ": " + e.toString(), e);
                }
            }
        }
        return r;
    }

    final void handleResumeActivity(IBinder token, boolean clearHide, boolean isForward) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        ActivityRecord r = performResumeActivity(token, clearHide);

        if (r != null) {
            final Activity a = r.activity;

            if (localLOGV) Slog.v(
                TAG, "Resume " + r + " started activity: " +
                a.mStartedActivity + ", hideForNow: " + r.hideForNow
                + ", finished: " + a.mFinished);

            final int forwardBit = isForward ?
                    WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION : 0;

            // If the window hasn't yet been added to the window manager,
            // and this guy didn't finish itself or start another activity,
            // then go ahead and add the window.
            boolean willBeVisible = !a.mStartedActivity;
            if (!willBeVisible) {
                try {
                    willBeVisible = ActivityManagerNative.getDefault().willActivityBeVisible(
                            a.getActivityToken());
                } catch (RemoteException e) {
                }
            }
            if (r.window == null && !a.mFinished && willBeVisible) {
                r.window = r.activity.getWindow();
                View decor = r.window.getDecorView();
                decor.setVisibility(View.INVISIBLE);
                ViewManager wm = a.getWindowManager();
                WindowManager.LayoutParams l = r.window.getAttributes();
                a.mDecor = decor;
                l.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
                l.softInputMode |= forwardBit;
                if (a.mVisibleFromClient) {
                    a.mWindowAdded = true;
                    wm.addView(decor, l);
                }

            // If the window has already been added, but during resume
            // we started another activity, then don't yet make the
            // window visible.
            } else if (!willBeVisible) {
                if (localLOGV) Slog.v(
                    TAG, "Launch " + r + " mStartedActivity set");
                r.hideForNow = true;
            }

            // The window is now visible if it has been added, we are not
            // simply finishing, and we are not starting another activity.
            if (!r.activity.mFinished && willBeVisible
                    && r.activity.mDecor != null && !r.hideForNow) {
                if (r.newConfig != null) {
                    if (DEBUG_CONFIGURATION) Slog.v(TAG, "Resuming activity "
                            + r.activityInfo.name + " with newConfig " + r.newConfig);
                    performConfigurationChanged(r.activity, r.newConfig);
                    r.newConfig = null;
                }
                if (localLOGV) Slog.v(TAG, "Resuming " + r + " with isForward="
                        + isForward);
                WindowManager.LayoutParams l = r.window.getAttributes();
                if ((l.softInputMode
                        & WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION)
                        != forwardBit) {
                    l.softInputMode = (l.softInputMode
                            & (~WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION))
                            | forwardBit;
                    if (r.activity.mVisibleFromClient) {
                        ViewManager wm = a.getWindowManager();
                        View decor = r.window.getDecorView();
                        wm.updateViewLayout(decor, l);
                    }
                }
                r.activity.mVisibleFromServer = true;
                mNumVisibleActivities++;
                if (r.activity.mVisibleFromClient) {
                    r.activity.makeVisible();
                }
            }

            r.nextIdle = mNewActivities;
            mNewActivities = r;
            if (localLOGV) Slog.v(
                TAG, "Scheduling idle handler for " + r);
            Looper.myQueue().addIdleHandler(new Idler());

        } else {
            // If an exception was thrown when trying to resume, then
            // just end this activity.
            try {
                ActivityManagerNative.getDefault()
                    .finishActivity(token, Activity.RESULT_CANCELED, null);
            } catch (RemoteException ex) {
            }
        }
    }

    private int mThumbnailWidth = -1;
    private int mThumbnailHeight = -1;

    private final Bitmap createThumbnailBitmap(ActivityRecord r) {
        Bitmap thumbnail = null;
        try {
            int w = mThumbnailWidth;
            int h;
            if (w < 0) {
                Resources res = r.activity.getResources();
                mThumbnailHeight = h =
                    res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_height);

                mThumbnailWidth = w =
                    res.getDimensionPixelSize(com.android.internal.R.dimen.thumbnail_width);
            } else {
                h = mThumbnailHeight;
            }

            // XXX Only set hasAlpha if needed?
            thumbnail = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            thumbnail.eraseColor(0);
            Canvas cv = new Canvas(thumbnail);
            if (!r.activity.onCreateThumbnail(thumbnail, cv)) {
                thumbnail = null;
            }
        } catch (Exception e) {
            if (!mInstrumentation.onException(r.activity, e)) {
                throw new RuntimeException(
                        "Unable to create thumbnail of "
                        + r.intent.getComponent().toShortString()
                        + ": " + e.toString(), e);
            }
            thumbnail = null;
        }

        return thumbnail;
    }

    private final void handlePauseActivity(IBinder token, boolean finished,
            boolean userLeaving, int configChanges) {
        ActivityRecord r = mActivities.get(token);
        if (r != null) {
            //Slog.v(TAG, "userLeaving=" + userLeaving + " handling pause of " + r);
            if (userLeaving) {
                performUserLeavingActivity(r);
            }

            r.activity.mConfigChangeFlags |= configChanges;
            Bundle state = performPauseActivity(token, finished, true);

            // Tell the activity manager we have paused.
            try {
                ActivityManagerNative.getDefault().activityPaused(token, state);
            } catch (RemoteException ex) {
            }
        }
    }

    final void performUserLeavingActivity(ActivityRecord r) {
        mInstrumentation.callActivityOnUserLeaving(r.activity);
    }

    final Bundle performPauseActivity(IBinder token, boolean finished,
            boolean saveState) {
        ActivityRecord r = mActivities.get(token);
        return r != null ? performPauseActivity(r, finished, saveState) : null;
    }

    final Bundle performPauseActivity(ActivityRecord r, boolean finished,
            boolean saveState) {
        if (r.paused) {
            if (r.activity.mFinished) {
                // If we are finishing, we won't call onResume() in certain cases.
                // So here we likewise don't want to call onPause() if the activity
                // isn't resumed.
                return null;
            }
            RuntimeException e = new RuntimeException(
                    "Performing pause of activity that is not resumed: "
                    + r.intent.getComponent().toShortString());
            Slog.e(TAG, e.getMessage(), e);
        }
        Bundle state = null;
        if (finished) {
            r.activity.mFinished = true;
        }
        try {
            // Next have the activity save its current state and managed dialogs...
            if (!r.activity.mFinished && saveState) {
                state = new Bundle();
                mInstrumentation.callActivityOnSaveInstanceState(r.activity, state);
                r.state = state;
            }
            // Now we are idle.
            r.activity.mCalled = false;
            mInstrumentation.callActivityOnPause(r.activity);
            EventLog.writeEvent(LOG_ON_PAUSE_CALLED, r.activity.getComponentName().getClassName());
            if (!r.activity.mCalled) {
                throw new SuperNotCalledException(
                    "Activity " + r.intent.getComponent().toShortString() +
                    " did not call through to super.onPause()");
            }

        } catch (SuperNotCalledException e) {
            throw e;

        } catch (Exception e) {
            if (!mInstrumentation.onException(r.activity, e)) {
                throw new RuntimeException(
                        "Unable to pause activity "
                        + r.intent.getComponent().toShortString()
                        + ": " + e.toString(), e);
            }
        }
        r.paused = true;
        return state;
    }

    final void performStopActivity(IBinder token) {
        ActivityRecord r = mActivities.get(token);
        performStopActivityInner(r, null, false);
    }

    private static class StopInfo {
        Bitmap thumbnail;
        CharSequence description;
    }

    private final class ProviderRefCount {
        public int count;
        ProviderRefCount(int pCount) {
            count = pCount;
        }
    }

    private final void performStopActivityInner(ActivityRecord r,
            StopInfo info, boolean keepShown) {
        if (localLOGV) Slog.v(TAG, "Performing stop of " + r);
        if (r != null) {
            if (!keepShown && r.stopped) {
                if (r.activity.mFinished) {
                    // If we are finishing, we won't call onResume() in certain
                    // cases.  So here we likewise don't want to call onStop()
                    // if the activity isn't resumed.
                    return;
                }
                RuntimeException e = new RuntimeException(
                        "Performing stop of activity that is not resumed: "
                        + r.intent.getComponent().toShortString());
                Slog.e(TAG, e.getMessage(), e);
            }

            if (info != null) {
                try {
                    // First create a thumbnail for the activity...
                    //info.thumbnail = createThumbnailBitmap(r);
                    info.description = r.activity.onCreateDescription();
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to save state of activity "
                                + r.intent.getComponent().toShortString()
                                + ": " + e.toString(), e);
                    }
                }
            }

            if (!keepShown) {
                try {
                    // Now we are idle.
                    r.activity.performStop();
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to stop activity "
                                + r.intent.getComponent().toShortString()
                                + ": " + e.toString(), e);
                    }
                }
                r.stopped = true;
            }

            r.paused = true;
        }
    }

    private final void updateVisibility(ActivityRecord r, boolean show) {
        View v = r.activity.mDecor;
        if (v != null) {
            if (show) {
                if (!r.activity.mVisibleFromServer) {
                    r.activity.mVisibleFromServer = true;
                    mNumVisibleActivities++;
                    if (r.activity.mVisibleFromClient) {
                        r.activity.makeVisible();
                    }
                }
                if (r.newConfig != null) {
                    if (DEBUG_CONFIGURATION) Slog.v(TAG, "Updating activity vis "
                            + r.activityInfo.name + " with new config " + r.newConfig);
                    performConfigurationChanged(r.activity, r.newConfig);
                    r.newConfig = null;
                }
            } else {
                if (r.activity.mVisibleFromServer) {
                    r.activity.mVisibleFromServer = false;
                    mNumVisibleActivities--;
                    v.setVisibility(View.INVISIBLE);
                }
            }
        }
    }

    private final void handleStopActivity(IBinder token, boolean show, int configChanges) {
        ActivityRecord r = mActivities.get(token);
        r.activity.mConfigChangeFlags |= configChanges;

        StopInfo info = new StopInfo();
        performStopActivityInner(r, info, show);

        if (localLOGV) Slog.v(
            TAG, "Finishing stop of " + r + ": show=" + show
            + " win=" + r.window);

        updateVisibility(r, show);

        // Tell activity manager we have been stopped.
        try {
            ActivityManagerNative.getDefault().activityStopped(
                r.token, info.thumbnail, info.description);
        } catch (RemoteException ex) {
        }
    }

    final void performRestartActivity(IBinder token) {
        ActivityRecord r = mActivities.get(token);
        if (r.stopped) {
            r.activity.performRestart();
            r.stopped = false;
        }
    }

    private final void handleWindowVisibility(IBinder token, boolean show) {
        ActivityRecord r = mActivities.get(token);
        if (!show && !r.stopped) {
            performStopActivityInner(r, null, show);
        } else if (show && r.stopped) {
            // If we are getting ready to gc after going to the background, well
            // we are back active so skip it.
            unscheduleGcIdler();

            r.activity.performRestart();
            r.stopped = false;
        }
        if (r.activity.mDecor != null) {
            if (Config.LOGV) Slog.v(
                TAG, "Handle window " + r + " visibility: " + show);
            updateVisibility(r, show);
        }
    }

    private final void deliverResults(ActivityRecord r, List<ResultInfo> results) {
        final int N = results.size();
        for (int i=0; i<N; i++) {
            ResultInfo ri = results.get(i);
            try {
                if (ri.mData != null) {
                    ri.mData.setExtrasClassLoader(r.activity.getClassLoader());
                }
                if (DEBUG_RESULTS) Slog.v(TAG,
                        "Delivering result to activity " + r + " : " + ri);
                r.activity.dispatchActivityResult(ri.mResultWho,
                        ri.mRequestCode, ri.mResultCode, ri.mData);
            } catch (Exception e) {
                if (!mInstrumentation.onException(r.activity, e)) {
                    throw new RuntimeException(
                            "Failure delivering result " + ri + " to activity "
                            + r.intent.getComponent().toShortString()
                            + ": " + e.toString(), e);
                }
            }
        }
    }

    private final void handleSendResult(ResultData res) {
        ActivityRecord r = mActivities.get(res.token);
        if (DEBUG_RESULTS) Slog.v(TAG, "Handling send result to " + r);
        if (r != null) {
            final boolean resumed = !r.paused;
            if (!r.activity.mFinished && r.activity.mDecor != null
                    && r.hideForNow && resumed) {
                // We had hidden the activity because it started another
                // one...  we have gotten a result back and we are not
                // paused, so make sure our window is visible.
                updateVisibility(r, true);
            }
            if (resumed) {
                try {
                    // Now we are idle.
                    r.activity.mCalled = false;
                    mInstrumentation.callActivityOnPause(r.activity);
                    if (!r.activity.mCalled) {
                        throw new SuperNotCalledException(
                            "Activity " + r.intent.getComponent().toShortString()
                            + " did not call through to super.onPause()");
                    }
                } catch (SuperNotCalledException e) {
                    throw e;
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to pause activity "
                                + r.intent.getComponent().toShortString()
                                + ": " + e.toString(), e);
                    }
                }
            }
            deliverResults(r, res.results);
            if (resumed) {
                mInstrumentation.callActivityOnResume(r.activity);
            }
        }
    }

    public final ActivityRecord performDestroyActivity(IBinder token, boolean finishing) {
        return performDestroyActivity(token, finishing, 0, false);
    }

    private final ActivityRecord performDestroyActivity(IBinder token, boolean finishing,
            int configChanges, boolean getNonConfigInstance) {
        ActivityRecord r = mActivities.get(token);
        if (localLOGV) Slog.v(TAG, "Performing finish of " + r);
        if (r != null) {
            r.activity.mConfigChangeFlags |= configChanges;
            if (finishing) {
                r.activity.mFinished = true;
            }
            if (!r.paused) {
                try {
                    r.activity.mCalled = false;
                    mInstrumentation.callActivityOnPause(r.activity);
                    EventLog.writeEvent(LOG_ON_PAUSE_CALLED,
                            r.activity.getComponentName().getClassName());
                    if (!r.activity.mCalled) {
                        throw new SuperNotCalledException(
                            "Activity " + safeToComponentShortString(r.intent)
                            + " did not call through to super.onPause()");
                    }
                } catch (SuperNotCalledException e) {
                    throw e;
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to pause activity "
                                + safeToComponentShortString(r.intent)
                                + ": " + e.toString(), e);
                    }
                }
                r.paused = true;
            }
            if (!r.stopped) {
                try {
                    r.activity.performStop();
                } catch (SuperNotCalledException e) {
                    throw e;
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to stop activity "
                                + safeToComponentShortString(r.intent)
                                + ": " + e.toString(), e);
                    }
                }
                r.stopped = true;
            }
            if (getNonConfigInstance) {
                try {
                    r.lastNonConfigurationInstance
                            = r.activity.onRetainNonConfigurationInstance();
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to retain activity "
                                + r.intent.getComponent().toShortString()
                                + ": " + e.toString(), e);
                    }
                }
                try {
                    r.lastNonConfigurationChildInstances
                            = r.activity.onRetainNonConfigurationChildInstances();
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to retain child activities "
                                + safeToComponentShortString(r.intent)
                                + ": " + e.toString(), e);
                    }
                }

            }
            try {
                r.activity.mCalled = false;
                r.activity.onDestroy();
                if (!r.activity.mCalled) {
                    throw new SuperNotCalledException(
                        "Activity " + safeToComponentShortString(r.intent) +
                        " did not call through to super.onDestroy()");
                }
                if (r.window != null) {
                    r.window.closeAllPanels();
                }
            } catch (SuperNotCalledException e) {
                throw e;
            } catch (Exception e) {
                if (!mInstrumentation.onException(r.activity, e)) {
                    throw new RuntimeException(
                            "Unable to destroy activity " + safeToComponentShortString(r.intent)
                            + ": " + e.toString(), e);
                }
            }
        }
        mActivities.remove(token);

        return r;
    }

    private static String safeToComponentShortString(Intent intent) {
        ComponentName component = intent.getComponent();
        return component == null ? "[Unknown]" : component.toShortString();
    }

    private final void handleDestroyActivity(IBinder token, boolean finishing,
            int configChanges, boolean getNonConfigInstance) {
        ActivityRecord r = performDestroyActivity(token, finishing,
                configChanges, getNonConfigInstance);
        if (r != null) {
            WindowManager wm = r.activity.getWindowManager();
            View v = r.activity.mDecor;
            if (v != null) {
                if (r.activity.mVisibleFromServer) {
                    mNumVisibleActivities--;
                }
                IBinder wtoken = v.getWindowToken();
                if (r.activity.mWindowAdded) {
                    wm.removeViewImmediate(v);
                }
                if (wtoken != null) {
                    WindowManagerImpl.getDefault().closeAll(wtoken,
                            r.activity.getClass().getName(), "Activity");
                }
                r.activity.mDecor = null;
            }
            WindowManagerImpl.getDefault().closeAll(token,
                    r.activity.getClass().getName(), "Activity");

            // Mocked out contexts won't be participating in the normal
            // process lifecycle, but if we're running with a proper
            // ApplicationContext we need to have it tear down things
            // cleanly.
            Context c = r.activity.getBaseContext();
            if (c instanceof ContextImpl) {
                ((ContextImpl) c).scheduleFinalCleanup(
                        r.activity.getClass().getName(), "Activity");
            }
        }
        if (finishing) {
            try {
                ActivityManagerNative.getDefault().activityDestroyed(token);
            } catch (RemoteException ex) {
                // If the system process has died, it's game over for everyone.
            }
        }
    }

    private final void handleRelaunchActivity(ActivityRecord tmp, int configChanges) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        Configuration changedConfig = null;

        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Relaunching activity "
                + tmp.token + " with configChanges=0x"
                + Integer.toHexString(configChanges));
        
        // First: make sure we have the most recent configuration and most
        // recent version of the activity, or skip it if some previous call
        // had taken a more recent version.
        synchronized (mPackages) {
            int N = mRelaunchingActivities.size();
            IBinder token = tmp.token;
            tmp = null;
            for (int i=0; i<N; i++) {
                ActivityRecord r = mRelaunchingActivities.get(i);
                if (r.token == token) {
                    tmp = r;
                    mRelaunchingActivities.remove(i);
                    i--;
                    N--;
                }
            }

            if (tmp == null) {
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Abort, activity not relaunching!");
                return;
            }

            if (mPendingConfiguration != null) {
                changedConfig = mPendingConfiguration;
                mPendingConfiguration = null;
            }
        }

        if (tmp.createdConfig != null) {
            // If the activity manager is passing us its current config,
            // assume that is really what we want regardless of what we
            // may have pending.
            if (mConfiguration == null
                    || (tmp.createdConfig.isOtherSeqNewer(mConfiguration)
                            && mConfiguration.diff(tmp.createdConfig) != 0)) {
                if (changedConfig == null
                        || tmp.createdConfig.isOtherSeqNewer(changedConfig)) {
                    changedConfig = tmp.createdConfig;
                }
            }
        }
        
        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Relaunching activity "
                + tmp.token + ": changedConfig=" + changedConfig);
        
        // If there was a pending configuration change, execute it first.
        if (changedConfig != null) {
            handleConfigurationChanged(changedConfig);
        }

        ActivityRecord r = mActivities.get(tmp.token);
        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Handling relaunch of " + r);
        if (r == null) {
            return;
        }

        r.activity.mConfigChangeFlags |= configChanges;
        Intent currentIntent = r.activity.mIntent;

        Bundle savedState = null;
        if (!r.paused) {
            savedState = performPauseActivity(r.token, false, true);
        }

        handleDestroyActivity(r.token, false, configChanges, true);

        r.activity = null;
        r.window = null;
        r.hideForNow = false;
        r.nextIdle = null;
        // Merge any pending results and pending intents; don't just replace them
        if (tmp.pendingResults != null) {
            if (r.pendingResults == null) {
                r.pendingResults = tmp.pendingResults;
            } else {
                r.pendingResults.addAll(tmp.pendingResults);
            }
        }
        if (tmp.pendingIntents != null) {
            if (r.pendingIntents == null) {
                r.pendingIntents = tmp.pendingIntents;
            } else {
                r.pendingIntents.addAll(tmp.pendingIntents);
            }
        }
        r.startsNotResumed = tmp.startsNotResumed;
        if (savedState != null) {
            r.state = savedState;
        }

        handleLaunchActivity(r, currentIntent);
    }

    private final void handleRequestThumbnail(IBinder token) {
        ActivityRecord r = mActivities.get(token);
        Bitmap thumbnail = createThumbnailBitmap(r);
        CharSequence description = null;
        try {
            description = r.activity.onCreateDescription();
        } catch (Exception e) {
            if (!mInstrumentation.onException(r.activity, e)) {
                throw new RuntimeException(
                        "Unable to create description of activity "
                        + r.intent.getComponent().toShortString()
                        + ": " + e.toString(), e);
            }
        }
        //System.out.println("Reporting top thumbnail " + thumbnail);
        try {
            ActivityManagerNative.getDefault().reportThumbnail(
                token, thumbnail, description);
        } catch (RemoteException ex) {
        }
    }

    ArrayList<ComponentCallbacks> collectComponentCallbacksLocked(
            boolean allActivities, Configuration newConfig) {
        ArrayList<ComponentCallbacks> callbacks
                = new ArrayList<ComponentCallbacks>();

        if (mActivities.size() > 0) {
            Iterator<ActivityRecord> it = mActivities.values().iterator();
            while (it.hasNext()) {
                ActivityRecord ar = it.next();
                Activity a = ar.activity;
                if (a != null) {
                    if (!ar.activity.mFinished && (allActivities ||
                            (a != null && !ar.paused))) {
                        // If the activity is currently resumed, its configuration
                        // needs to change right now.
                        callbacks.add(a);
                    } else if (newConfig != null) {
                        // Otherwise, we will tell it about the change
                        // the next time it is resumed or shown.  Note that
                        // the activity manager may, before then, decide the
                        // activity needs to be destroyed to handle its new
                        // configuration.
                        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Setting activity "
                                + ar.activityInfo.name + " newConfig=" + newConfig);
                        ar.newConfig = newConfig;
                    }
                }
            }
        }
        if (mServices.size() > 0) {
            Iterator<Service> it = mServices.values().iterator();
            while (it.hasNext()) {
                callbacks.add(it.next());
            }
        }
        synchronized (mProviderMap) {
            if (mLocalProviders.size() > 0) {
                Iterator<ProviderRecord> it = mLocalProviders.values().iterator();
                while (it.hasNext()) {
                    callbacks.add(it.next().mLocalProvider);
                }
            }
        }
        final int N = mAllApplications.size();
        for (int i=0; i<N; i++) {
            callbacks.add(mAllApplications.get(i));
        }

        return callbacks;
    }

    private final void performConfigurationChanged(
            ComponentCallbacks cb, Configuration config) {
        // Only for Activity objects, check that they actually call up to their
        // superclass implementation.  ComponentCallbacks is an interface, so
        // we check the runtime type and act accordingly.
        Activity activity = (cb instanceof Activity) ? (Activity) cb : null;
        if (activity != null) {
            activity.mCalled = false;
        }

        boolean shouldChangeConfig = false;
        if ((activity == null) || (activity.mCurrentConfig == null)) {
            shouldChangeConfig = true;
        } else {

            // If the new config is the same as the config this Activity
            // is already running with then don't bother calling
            // onConfigurationChanged
            int diff = activity.mCurrentConfig.diff(config);
            if (diff != 0) {

                // If this activity doesn't handle any of the config changes
                // then don't bother calling onConfigurationChanged as we're
                // going to destroy it.
                if ((~activity.mActivityInfo.configChanges & diff) == 0) {
                    shouldChangeConfig = true;
                }
            }
        }

        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Config callback " + cb
                + ": shouldChangeConfig=" + shouldChangeConfig);
        if (shouldChangeConfig) {
            cb.onConfigurationChanged(config);

            if (activity != null) {
                if (!activity.mCalled) {
                    throw new SuperNotCalledException(
                            "Activity " + activity.getLocalClassName() +
                        " did not call through to super.onConfigurationChanged()");
                }
                activity.mConfigChangeFlags = 0;
                activity.mCurrentConfig = new Configuration(config);
            }
        }
    }

    final boolean applyConfigurationToResourcesLocked(Configuration config) {
        if (mResConfiguration == null) {
            mResConfiguration = new Configuration();
        }
        if (!mResConfiguration.isOtherSeqNewer(config)) {
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Skipping new config: curSeq="
                    + mResConfiguration.seq + ", newSeq=" + config.seq);
            return false;
        }
        int changes = mResConfiguration.updateFrom(config);
        DisplayMetrics dm = getDisplayMetricsLocked(true);

        // set it for java, this also affects newly created Resources
        if (config.locale != null) {
            Locale.setDefault(config.locale);
        }

        Resources.updateSystemConfiguration(config, dm);

        ContextImpl.ApplicationPackageManager.configurationChanged();
        //Slog.i(TAG, "Configuration changed in " + currentPackageName());
        
        Iterator<WeakReference<Resources>> it =
            mActiveResources.values().iterator();
        //Iterator<Map.Entry<String, WeakReference<Resources>>> it =
        //    mActiveResources.entrySet().iterator();
        while (it.hasNext()) {
            WeakReference<Resources> v = it.next();
            Resources r = v.get();
            if (r != null) {
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Changing resources "
                        + r + " config to: " + config);
                r.updateConfiguration(config, dm);
                //Slog.i(TAG, "Updated app resources " + v.getKey()
                //        + " " + r + ": " + r.getConfiguration());
            } else {
                //Slog.i(TAG, "Removing old resources " + v.getKey());
                it.remove();
            }
        }
        
        return changes != 0;
    }
    
    final void handleConfigurationChanged(Configuration config) {

        ArrayList<ComponentCallbacks> callbacks = null;

        synchronized (mPackages) {
            if (mPendingConfiguration != null) {
                if (!mPendingConfiguration.isOtherSeqNewer(config)) {
                    config = mPendingConfiguration;
                }
                mPendingConfiguration = null;
            }

            if (config == null) {
                return;
            }
            
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Handle configuration changed: "
                    + config);
        
            applyConfigurationToResourcesLocked(config);
            
            if (mConfiguration == null) {
                mConfiguration = new Configuration();
            }
            if (!mConfiguration.isOtherSeqNewer(config)) {
                return;
            }
            mConfiguration.updateFrom(config);

            callbacks = collectComponentCallbacksLocked(false, config);
        }

        if (callbacks != null) {
            final int N = callbacks.size();
            for (int i=0; i<N; i++) {
                performConfigurationChanged(callbacks.get(i), config);
            }
        }
    }

    final void handleActivityConfigurationChanged(IBinder token) {
        ActivityRecord r = mActivities.get(token);
        if (r == null || r.activity == null) {
            return;
        }

        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Handle activity config changed: "
                + r.activityInfo.name);
        
        performConfigurationChanged(r.activity, mConfiguration);
    }

    final void handleProfilerControl(boolean start, ProfilerControlData pcd) {
        if (start) {
            try {
                Debug.startMethodTracing(pcd.path, pcd.fd.getFileDescriptor(),
                        8 * 1024 * 1024, 0);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Profiling failed on path " + pcd.path
                        + " -- can the process access this path?");
            } finally {
                try {
                    pcd.fd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failure closing profile fd", e);
                }
            }
        } else {
            Debug.stopMethodTracing();
        }
    }

    final void handleDispatchPackageBroadcast(int cmd, String[] packages) {
        boolean hasPkgInfo = false;
        if (packages != null) {
            for (int i=packages.length-1; i>=0; i--) {
                //Slog.i(TAG, "Cleaning old package: " + packages[i]);
                if (!hasPkgInfo) {
                    WeakReference<PackageInfo> ref;
                    ref = mPackages.get(packages[i]);
                    if (ref != null && ref.get() != null) {
                        hasPkgInfo = true;
                    } else {
                        ref = mResourcePackages.get(packages[i]);
                        if (ref != null && ref.get() != null) {
                            hasPkgInfo = true;
                        }
                    }
                }
                mPackages.remove(packages[i]);
                mResourcePackages.remove(packages[i]);
            }
        }
        ContextImpl.ApplicationPackageManager.handlePackageBroadcast(cmd, packages,
                hasPkgInfo);
    }
        
    final void handleLowMemory() {
        ArrayList<ComponentCallbacks> callbacks
                = new ArrayList<ComponentCallbacks>();

        synchronized (mPackages) {
            callbacks = collectComponentCallbacksLocked(true, null);
        }

        final int N = callbacks.size();
        for (int i=0; i<N; i++) {
            callbacks.get(i).onLowMemory();
        }

        // Ask SQLite to free up as much memory as it can, mostly from its page caches.
        if (Process.myUid() != Process.SYSTEM_UID) {
            int sqliteReleased = SQLiteDatabase.releaseMemory();
            EventLog.writeEvent(SQLITE_MEM_RELEASED_EVENT_LOG_TAG, sqliteReleased);
        }

        // Ask graphics to free up as much as possible (font/image caches)
        Canvas.freeCaches();

        BinderInternal.forceGc("mem");
    }

    private final void handleBindApplication(AppBindData data) {
        mBoundApplication = data;
        mConfiguration = new Configuration(data.config);

        // send up app name; do this *before* waiting for debugger
        Process.setArgV0(data.processName);
        android.ddm.DdmHandleAppName.setAppName(data.processName);

        /*
         * Before spawning a new process, reset the time zone to be the system time zone.
         * This needs to be done because the system time zone could have changed after the
         * the spawning of this process. Without doing this this process would have the incorrect
         * system time zone.
         */
        TimeZone.setDefault(null);

        /*
         * Initialize the default locale in this process for the reasons we set the time zone.
         */
        Locale.setDefault(data.config.locale);

        /*
         * Update the system configuration since its preloaded and might not
         * reflect configuration changes. The configuration object passed
         * in AppBindData can be safely assumed to be up to date
         */
        Resources.getSystem().updateConfiguration(mConfiguration, null);

        data.info = getPackageInfoNoCheck(data.appInfo);

        /**
         * Switch this process to density compatibility mode if needed.
         */
        if ((data.appInfo.flags&ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES)
                == 0) {
            Bitmap.setDefaultDensity(DisplayMetrics.DENSITY_DEFAULT);
        }

        if (data.debugMode != IApplicationThread.DEBUG_OFF) {
            // XXX should have option to change the port.
            Debug.changeDebugPort(8100);
            if (data.debugMode == IApplicationThread.DEBUG_WAIT) {
                Slog.w(TAG, "Application " + data.info.getPackageName()
                      + " is waiting for the debugger on port 8100...");

                IActivityManager mgr = ActivityManagerNative.getDefault();
                try {
                    mgr.showWaitingForDebugger(mAppThread, true);
                } catch (RemoteException ex) {
                }

                Debug.waitForDebugger();

                try {
                    mgr.showWaitingForDebugger(mAppThread, false);
                } catch (RemoteException ex) {
                }

            } else {
                Slog.w(TAG, "Application " + data.info.getPackageName()
                      + " can be debugged on port 8100...");
            }
        }

        if (data.instrumentationName != null) {
            ContextImpl appContext = new ContextImpl();
            appContext.init(data.info, null, this);
            InstrumentationInfo ii = null;
            try {
                ii = appContext.getPackageManager().
                    getInstrumentationInfo(data.instrumentationName, 0);
            } catch (PackageManager.NameNotFoundException e) {
            }
            if (ii == null) {
                throw new RuntimeException(
                    "Unable to find instrumentation info for: "
                    + data.instrumentationName);
            }

            mInstrumentationAppDir = ii.sourceDir;
            mInstrumentationAppPackage = ii.packageName;
            mInstrumentedAppDir = data.info.getAppDir();

            ApplicationInfo instrApp = new ApplicationInfo();
            instrApp.packageName = ii.packageName;
            instrApp.sourceDir = ii.sourceDir;
            instrApp.publicSourceDir = ii.publicSourceDir;
            instrApp.dataDir = ii.dataDir;
            PackageInfo pi = getPackageInfo(instrApp,
                    appContext.getClassLoader(), false, true);
            ContextImpl instrContext = new ContextImpl();
            instrContext.init(pi, null, this);

            try {
                java.lang.ClassLoader cl = instrContext.getClassLoader();
                mInstrumentation = (Instrumentation)
                    cl.loadClass(data.instrumentationName.getClassName()).newInstance();
            } catch (Exception e) {
                throw new RuntimeException(
                    "Unable to instantiate instrumentation "
                    + data.instrumentationName + ": " + e.toString(), e);
            }

            mInstrumentation.init(this, instrContext, appContext,
                    new ComponentName(ii.packageName, ii.name), data.instrumentationWatcher);

            if (data.profileFile != null && !ii.handleProfiling) {
                data.handlingProfiling = true;
                File file = new File(data.profileFile);
                file.getParentFile().mkdirs();
                Debug.startMethodTracing(file.toString(), 8 * 1024 * 1024);
            }

            try {
                mInstrumentation.onCreate(data.instrumentationArgs);
            }
            catch (Exception e) {
                throw new RuntimeException(
                    "Exception thrown in onCreate() of "
                    + data.instrumentationName + ": " + e.toString(), e);
            }

        } else {
            mInstrumentation = new Instrumentation();
        }

        // If the app is being launched for full backup or restore, bring it up in
        // a restricted environment with the base application class.
        Application app = data.info.makeApplication(data.restrictedBackupMode, null);
        mInitialApplication = app;

        List<ProviderInfo> providers = data.providers;
        if (providers != null) {
            installContentProviders(app, providers);
            // For process that contain content providers, we want to
            // ensure that the JIT is enabled "at some point".
            mH.sendEmptyMessageDelayed(H.ENABLE_JIT, 10*1000);
        }

        try {
            mInstrumentation.callApplicationOnCreate(app);
        } catch (Exception e) {
            if (!mInstrumentation.onException(app, e)) {
                throw new RuntimeException(
                    "Unable to create application " + app.getClass().getName()
                    + ": " + e.toString(), e);
            }
        }
    }

    /*package*/ final void finishInstrumentation(int resultCode, Bundle results) {
        IActivityManager am = ActivityManagerNative.getDefault();
        if (mBoundApplication.profileFile != null && mBoundApplication.handlingProfiling) {
            Debug.stopMethodTracing();
        }
        //Slog.i(TAG, "am: " + ActivityManagerNative.getDefault()
        //      + ", app thr: " + mAppThread);
        try {
            am.finishInstrumentation(mAppThread, resultCode, results);
        } catch (RemoteException ex) {
        }
    }

    private final void installContentProviders(
            Context context, List<ProviderInfo> providers) {
        final ArrayList<IActivityManager.ContentProviderHolder> results =
            new ArrayList<IActivityManager.ContentProviderHolder>();

        Iterator<ProviderInfo> i = providers.iterator();
        while (i.hasNext()) {
            ProviderInfo cpi = i.next();
            StringBuilder buf = new StringBuilder(128);
            buf.append("Publishing provider ");
            buf.append(cpi.authority);
            buf.append(": ");
            buf.append(cpi.name);
            Log.i(TAG, buf.toString());
            IContentProvider cp = installProvider(context, null, cpi, false);
            if (cp != null) {
                IActivityManager.ContentProviderHolder cph =
                    new IActivityManager.ContentProviderHolder(cpi);
                cph.provider = cp;
                results.add(cph);
                // Don't ever unload this provider from the process.
                synchronized(mProviderMap) {
                    mProviderRefCountMap.put(cp.asBinder(), new ProviderRefCount(10000));
                }
            }
        }

        try {
            ActivityManagerNative.getDefault().publishContentProviders(
                getApplicationThread(), results);
        } catch (RemoteException ex) {
        }
    }

    private final IContentProvider getProvider(Context context, String name) {
        synchronized(mProviderMap) {
            final ProviderRecord pr = mProviderMap.get(name);
            if (pr != null) {
                return pr.mProvider;
            }
        }

        IActivityManager.ContentProviderHolder holder = null;
        try {
            holder = ActivityManagerNative.getDefault().getContentProvider(
                getApplicationThread(), name);
        } catch (RemoteException ex) {
        }
        if (holder == null) {
            Slog.e(TAG, "Failed to find provider info for " + name);
            return null;
        }
        if (holder.permissionFailure != null) {
            throw new SecurityException("Permission " + holder.permissionFailure
                    + " required for provider " + name);
        }

        IContentProvider prov = installProvider(context, holder.provider,
                holder.info, true);
        //Slog.i(TAG, "noReleaseNeeded=" + holder.noReleaseNeeded);
        if (holder.noReleaseNeeded || holder.provider == null) {
            // We are not going to release the provider if it is an external
            // provider that doesn't care about being released, or if it is
            // a local provider running in this process.
            //Slog.i(TAG, "*** NO RELEASE NEEDED");
            synchronized(mProviderMap) {
                mProviderRefCountMap.put(prov.asBinder(), new ProviderRefCount(10000));
            }
        }
        return prov;
    }

    public final IContentProvider acquireProvider(Context c, String name) {
        IContentProvider provider = getProvider(c, name);
        if(provider == null)
            return null;
        IBinder jBinder = provider.asBinder();
        synchronized(mProviderMap) {
            ProviderRefCount prc = mProviderRefCountMap.get(jBinder);
            if(prc == null) {
                mProviderRefCountMap.put(jBinder, new ProviderRefCount(1));
            } else {
                prc.count++;
            } //end else
        } //end synchronized
        return provider;
    }

    public final boolean releaseProvider(IContentProvider provider) {
        if(provider == null) {
            return false;
        }
        IBinder jBinder = provider.asBinder();
        synchronized(mProviderMap) {
            ProviderRefCount prc = mProviderRefCountMap.get(jBinder);
            if(prc == null) {
                if(localLOGV) Slog.v(TAG, "releaseProvider::Weird shouldnt be here");
                return false;
            } else {
                prc.count--;
                if(prc.count == 0) {
                    // Schedule the actual remove asynchronously, since we
                    // don't know the context this will be called in.
                    // TODO: it would be nice to post a delayed message, so
                    // if we come back and need the same provider quickly
                    // we will still have it available.
                    Message msg = mH.obtainMessage(H.REMOVE_PROVIDER, provider);
                    mH.sendMessage(msg);
                } //end if
            } //end else
        } //end synchronized
        return true;
    }

    final void completeRemoveProvider(IContentProvider provider) {
        IBinder jBinder = provider.asBinder();
        String name = null;
        synchronized(mProviderMap) {
            ProviderRefCount prc = mProviderRefCountMap.get(jBinder);
            if(prc != null && prc.count == 0) {
                mProviderRefCountMap.remove(jBinder);
                //invoke removeProvider to dereference provider
                name = removeProviderLocked(provider);
            }
        }
        
        if (name != null) {
            try {
                if(localLOGV) Slog.v(TAG, "removeProvider::Invoking " +
                        "ActivityManagerNative.removeContentProvider(" + name);
                ActivityManagerNative.getDefault().removeContentProvider(
                        getApplicationThread(), name);
            } catch (RemoteException e) {
                //do nothing content provider object is dead any way
            } //end catch
        }
    }
    
    public final String removeProviderLocked(IContentProvider provider) {
        if (provider == null) {
            return null;
        }
        IBinder providerBinder = provider.asBinder();

        String name = null;
        
        // remove the provider from mProviderMap
        Iterator<ProviderRecord> iter = mProviderMap.values().iterator();
        while (iter.hasNext()) {
            ProviderRecord pr = iter.next();
            IBinder myBinder = pr.mProvider.asBinder();
            if (myBinder == providerBinder) {
                //find if its published by this process itself
                if(pr.mLocalProvider != null) {
                    if(localLOGV) Slog.i(TAG, "removeProvider::found local provider returning");
                    return name;
                }
                if(localLOGV) Slog.v(TAG, "removeProvider::Not local provider Unlinking " +
                        "death recipient");
                //content provider is in another process
                myBinder.unlinkToDeath(pr, 0);
                iter.remove();
                //invoke remove only once for the very first name seen
                if(name == null) {
                    name = pr.mName;
                }
            } //end if myBinder
        }  //end while iter
        
        return name;
    }

    final void removeDeadProvider(String name, IContentProvider provider) {
        synchronized(mProviderMap) {
            ProviderRecord pr = mProviderMap.get(name);
            if (pr.mProvider.asBinder() == provider.asBinder()) {
                Slog.i(TAG, "Removing dead content provider: " + name);
                ProviderRecord removed = mProviderMap.remove(name);
                if (removed != null) {
                    removed.mProvider.asBinder().unlinkToDeath(removed, 0);
                }
            }
        }
    }

    final void removeDeadProviderLocked(String name, IContentProvider provider) {
        ProviderRecord pr = mProviderMap.get(name);
        if (pr.mProvider.asBinder() == provider.asBinder()) {
            Slog.i(TAG, "Removing dead content provider: " + name);
            ProviderRecord removed = mProviderMap.remove(name);
            if (removed != null) {
                removed.mProvider.asBinder().unlinkToDeath(removed, 0);
            }
        }
    }

    private final IContentProvider installProvider(Context context,
            IContentProvider provider, ProviderInfo info, boolean noisy) {
        ContentProvider localProvider = null;
        if (provider == null) {
            if (noisy) {
                Slog.d(TAG, "Loading provider " + info.authority + ": "
                        + info.name);
            }
            Context c = null;
            ApplicationInfo ai = info.applicationInfo;
            if (context.getPackageName().equals(ai.packageName)) {
                c = context;
            } else if (mInitialApplication != null &&
                    mInitialApplication.getPackageName().equals(ai.packageName)) {
                c = mInitialApplication;
            } else {
                try {
                    c = context.createPackageContext(ai.packageName,
                            Context.CONTEXT_INCLUDE_CODE);
                } catch (PackageManager.NameNotFoundException e) {
                }
            }
            if (c == null) {
                Slog.w(TAG, "Unable to get context for package " +
                      ai.packageName +
                      " while loading content provider " +
                      info.name);
                return null;
            }
            try {
                final java.lang.ClassLoader cl = c.getClassLoader();
                localProvider = (ContentProvider)cl.
                    loadClass(info.name).newInstance();
                provider = localProvider.getIContentProvider();
                if (provider == null) {
                    Slog.e(TAG, "Failed to instantiate class " +
                          info.name + " from sourceDir " +
                          info.applicationInfo.sourceDir);
                    return null;
                }
                if (Config.LOGV) Slog.v(
                    TAG, "Instantiating local provider " + info.name);
                // XXX Need to create the correct context for this provider.
                localProvider.attachInfo(c, info);
            } catch (java.lang.Exception e) {
                if (!mInstrumentation.onException(null, e)) {
                    throw new RuntimeException(
                            "Unable to get provider " + info.name
                            + ": " + e.toString(), e);
                }
                return null;
            }
        } else if (localLOGV) {
            Slog.v(TAG, "Installing external provider " + info.authority + ": "
                    + info.name);
        }

        synchronized (mProviderMap) {
            // Cache the pointer for the remote provider.
            String names[] = PATTERN_SEMICOLON.split(info.authority);
            for (int i=0; i<names.length; i++) {
                ProviderRecord pr = new ProviderRecord(names[i], provider,
                        localProvider);
                try {
                    provider.asBinder().linkToDeath(pr, 0);
                    mProviderMap.put(names[i], pr);
                } catch (RemoteException e) {
                    return null;
                }
            }
            if (localProvider != null) {
                mLocalProviders.put(provider.asBinder(),
                        new ProviderRecord(null, provider, localProvider));
            }
        }

        return provider;
    }

    private final void attach(boolean system) {
        sThreadLocal.set(this);
        mSystemThread = system;
        if (!system) {
            ViewRoot.addFirstDrawHandler(new Runnable() {
                public void run() {
                    ensureJitEnabled();
                }
            });
            android.ddm.DdmHandleAppName.setAppName("<pre-initialized>");
            RuntimeInit.setApplicationObject(mAppThread.asBinder());
            IActivityManager mgr = ActivityManagerNative.getDefault();
            try {
                mgr.attachApplication(mAppThread);
            } catch (RemoteException ex) {
            }
        } else {
            // Don't set application object here -- if the system crashes,
            // we can't display an alert, we just want to die die die.
            android.ddm.DdmHandleAppName.setAppName("system_process");
            try {
                mInstrumentation = new Instrumentation();
                ContextImpl context = new ContextImpl();
                context.init(getSystemContext().mPackageInfo, null, this);
                Application app = Instrumentation.newApplication(Application.class, context);
                mAllApplications.add(app);
                mInitialApplication = app;
                app.onCreate();
            } catch (Exception e) {
                throw new RuntimeException(
                        "Unable to instantiate Application():" + e.toString(), e);
            }
        }
        
        ViewRoot.addConfigCallback(new ComponentCallbacks() {
            public void onConfigurationChanged(Configuration newConfig) {
                synchronized (mPackages) {
                    // We need to apply this change to the resources
                    // immediately, because upon returning the view
                    // hierarchy will be informed about it.
                    if (applyConfigurationToResourcesLocked(newConfig)) {
                        // This actually changed the resources!  Tell
                        // everyone about it.
                        if (mPendingConfiguration == null ||
                                mPendingConfiguration.isOtherSeqNewer(newConfig)) {
                            mPendingConfiguration = newConfig;
                            
                            queueOrSendMessage(H.CONFIGURATION_CHANGED, newConfig);
                        }
                    }
                }
            }
            public void onLowMemory() {
            }
        });
    }

    private final void detach()
    {
        sThreadLocal.set(null);
    }

    public static final ActivityThread systemMain() {
        ActivityThread thread = new ActivityThread();
        thread.attach(true);
        return thread;
    }

    public final void installSystemProviders(List providers) {
        if (providers != null) {
            installContentProviders(mInitialApplication,
                                    (List<ProviderInfo>)providers);
        }
    }

    public static final void main(String[] args) {
        SamplingProfilerIntegration.start();

        Process.setArgV0("<pre-initialized>");

        Looper.prepareMainLooper();

        ActivityThread thread = new ActivityThread();
        thread.attach(false);

        Looper.loop();

        if (Process.supportsProcesses()) {
            throw new RuntimeException("Main thread loop unexpectedly exited");
        }

        thread.detach();
        String name = (thread.mInitialApplication != null)
            ? thread.mInitialApplication.getPackageName()
            : "<unknown>";
        Slog.i(TAG, "Main thread of " + name + " is now exiting");
    }
}
