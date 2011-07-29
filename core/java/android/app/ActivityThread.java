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
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.IContentProvider;
import android.content.Intent;
import android.content.IIntentReceiver;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
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
import android.net.IConnectivityManager;
import android.net.Proxy;
import android.net.ProxyProperties;
import android.os.AsyncTask;
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
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.AndroidRuntimeException;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Log;
import android.util.LogPrinter;
import android.util.Slog;
import android.view.Display;
import android.view.HardwareRenderer;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewManager;
import android.view.ViewRootImpl;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

import com.android.internal.os.BinderInternal;
import com.android.internal.os.RuntimeInit;
import com.android.internal.os.SamplingProfilerIntegration;

import org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

import dalvik.system.CloseGuard;

final class SuperNotCalledException extends AndroidRuntimeException {
    public SuperNotCalledException(String msg) {
        super(msg);
    }
}

final class RemoteServiceException extends AndroidRuntimeException {
    public RemoteServiceException(String msg) {
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
    /** @hide */
    public static final String TAG = "ActivityThread";
    private static final android.graphics.Bitmap.Config THUMBNAIL_FORMAT = Bitmap.Config.RGB_565;
    static final boolean localLOGV = false;
    static final boolean DEBUG_MESSAGES = false;
    /** @hide */
    public static final boolean DEBUG_BROADCAST = false;
    private static final boolean DEBUG_RESULTS = false;
    private static final boolean DEBUG_BACKUP = true;
    private static final boolean DEBUG_CONFIGURATION = false;
    private static final long MIN_TIME_BETWEEN_GCS = 5*1000;
    private static final Pattern PATTERN_SEMICOLON = Pattern.compile(";");
    private static final int SQLITE_MEM_RELEASED_EVENT_LOG_TAG = 75003;
    private static final int LOG_ON_PAUSE_CALLED = 30021;
    private static final int LOG_ON_RESUME_CALLED = 30022;

    static ContextImpl mSystemContext = null;

    static IPackageManager sPackageManager;

    final ApplicationThread mAppThread = new ApplicationThread();
    final Looper mLooper = Looper.myLooper();
    final H mH = new H();
    final HashMap<IBinder, ActivityClientRecord> mActivities
            = new HashMap<IBinder, ActivityClientRecord>();
    // List of new activities (via ActivityRecord.nextIdle) that should
    // be reported when next we idle.
    ActivityClientRecord mNewActivities = null;
    // Number of activities that are currently visible on-screen.
    int mNumVisibleActivities = 0;
    final HashMap<IBinder, Service> mServices
            = new HashMap<IBinder, Service>();
    AppBindData mBoundApplication;
    Configuration mConfiguration;
    Configuration mCompatConfiguration;
    Configuration mResConfiguration;
    CompatibilityInfo mResCompatibilityInfo;
    Application mInitialApplication;
    final ArrayList<Application> mAllApplications
            = new ArrayList<Application>();
    // set of instantiated backup agents, keyed by package name
    final HashMap<String, BackupAgent> mBackupAgents = new HashMap<String, BackupAgent>();
    static final ThreadLocal<ActivityThread> sThreadLocal = new ThreadLocal<ActivityThread>();
    Instrumentation mInstrumentation;
    String mInstrumentationAppDir = null;
    String mInstrumentationAppPackage = null;
    String mInstrumentedAppDir = null;
    boolean mSystemThread = false;
    boolean mJitEnabled = false;

    // These can be accessed by multiple threads; mPackages is the lock.
    // XXX For now we keep around information about all packages we have
    // seen, not removing entries from this map.
    // NOTE: The activity manager in its process needs to call in to
    // ActivityThread to do things like update resource configurations,
    // which means this lock gets held while the activity manager holds its
    // own lock.  Thus you MUST NEVER call back into the activity manager
    // or anything that depends on it while holding this lock.
    final HashMap<String, WeakReference<LoadedApk>> mPackages
            = new HashMap<String, WeakReference<LoadedApk>>();
    final HashMap<String, WeakReference<LoadedApk>> mResourcePackages
            = new HashMap<String, WeakReference<LoadedApk>>();
    final HashMap<CompatibilityInfo, DisplayMetrics> mDisplayMetrics
            = new HashMap<CompatibilityInfo, DisplayMetrics>();
    final HashMap<ResourcesKey, WeakReference<Resources> > mActiveResources
            = new HashMap<ResourcesKey, WeakReference<Resources> >();
    final ArrayList<ActivityClientRecord> mRelaunchingActivities
            = new ArrayList<ActivityClientRecord>();
    Configuration mPendingConfiguration = null;

    // The lock of mProviderMap protects the following variables.
    final HashMap<String, ProviderClientRecord> mProviderMap
        = new HashMap<String, ProviderClientRecord>();
    final HashMap<IBinder, ProviderRefCount> mProviderRefCountMap
        = new HashMap<IBinder, ProviderRefCount>();
    final HashMap<IBinder, ProviderClientRecord> mLocalProviders
        = new HashMap<IBinder, ProviderClientRecord>();

    final HashMap<Activity, ArrayList<OnActivityPausedListener>> mOnPauseListeners
        = new HashMap<Activity, ArrayList<OnActivityPausedListener>>();

    final GcIdler mGcIdler = new GcIdler();
    boolean mGcIdlerScheduled = false;

    static Handler sMainThreadHandler;  // set once in main()

    Bundle mCoreSettings = null;

    static final class ActivityClientRecord {
        IBinder token;
        int ident;
        Intent intent;
        Bundle state;
        Activity activity;
        Window window;
        Activity parent;
        String embeddedID;
        Activity.NonConfigurationInstances lastNonConfigurationInstances;
        boolean paused;
        boolean stopped;
        boolean hideForNow;
        Configuration newConfig;
        Configuration createdConfig;
        ActivityClientRecord nextIdle;

        ActivityInfo activityInfo;
        CompatibilityInfo compatInfo;
        LoadedApk packageInfo;

        List<ResultInfo> pendingResults;
        List<Intent> pendingIntents;

        boolean startsNotResumed;
        boolean isForward;
        int pendingConfigChanges;
        boolean onlyLocalRequest;

        View mPendingRemoveWindow;
        WindowManager mPendingRemoveWindowManager;

        ActivityClientRecord() {
            parent = null;
            embeddedID = null;
            paused = false;
            stopped = false;
            hideForNow = false;
            nextIdle = null;
        }

        public boolean isPreHoneycomb() {
            if (activity != null) {
                return activity.getApplicationInfo().targetSdkVersion
                        < android.os.Build.VERSION_CODES.HONEYCOMB;
            }
            return false;
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

    final class ProviderClientRecord implements IBinder.DeathRecipient {
        final String mName;
        final IContentProvider mProvider;
        final ContentProvider mLocalProvider;

        ProviderClientRecord(String name, IContentProvider provider,
                ContentProvider localProvider) {
            mName = name;
            mProvider = provider;
            mLocalProvider = localProvider;
        }

        public void binderDied() {
            removeDeadProvider(mName, mProvider);
        }
    }

    static final class NewIntentData {
        List<Intent> intents;
        IBinder token;
        public String toString() {
            return "NewIntentData{intents=" + intents + " token=" + token + "}";
        }
    }

    static final class ReceiverData extends BroadcastReceiver.PendingResult {
        public ReceiverData(Intent intent, int resultCode, String resultData, Bundle resultExtras,
                boolean ordered, boolean sticky, IBinder token) {
            super(resultCode, resultData, resultExtras, TYPE_COMPONENT, ordered, sticky, token);
            this.intent = intent;
        }

        Intent intent;
        ActivityInfo info;
        CompatibilityInfo compatInfo;
        public String toString() {
            return "ReceiverData{intent=" + intent + " packageName=" +
                    info.packageName + " resultCode=" + getResultCode()
                    + " resultData=" + getResultData() + " resultExtras="
                    + getResultExtras(false) + "}";
        }
    }

    static final class CreateBackupAgentData {
        ApplicationInfo appInfo;
        CompatibilityInfo compatInfo;
        int backupMode;
        public String toString() {
            return "CreateBackupAgentData{appInfo=" + appInfo
                    + " backupAgent=" + appInfo.backupAgentName
                    + " mode=" + backupMode + "}";
        }
    }

    static final class CreateServiceData {
        IBinder token;
        ServiceInfo info;
        CompatibilityInfo compatInfo;
        Intent intent;
        public String toString() {
            return "CreateServiceData{token=" + token + " className="
            + info.name + " packageName=" + info.packageName
            + " intent=" + intent + "}";
        }
    }

    static final class BindServiceData {
        IBinder token;
        Intent intent;
        boolean rebind;
        public String toString() {
            return "BindServiceData{token=" + token + " intent=" + intent + "}";
        }
    }

    static final class ServiceArgsData {
        IBinder token;
        boolean taskRemoved;
        int startId;
        int flags;
        Intent args;
        public String toString() {
            return "ServiceArgsData{token=" + token + " startId=" + startId
            + " args=" + args + "}";
        }
    }

    static final class AppBindData {
        LoadedApk info;
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
        CompatibilityInfo compatInfo;
        boolean handlingProfiling;
        public String toString() {
            return "AppBindData{appInfo=" + appInfo + "}";
        }
    }

    static final class DumpComponentInfo {
        ParcelFileDescriptor fd;
        IBinder token;
        String prefix;
        String[] args;
    }

    static final class ResultData {
        IBinder token;
        List<ResultInfo> results;
        public String toString() {
            return "ResultData{token=" + token + " results" + results + "}";
        }
    }

    static final class ContextCleanupInfo {
        ContextImpl context;
        String what;
        String who;
    }

    static final class ProfilerControlData {
        String path;
        ParcelFileDescriptor fd;
    }

    static final class DumpHeapData {
        String path;
        ParcelFileDescriptor fd;
    }

    static final class UpdateCompatibilityData {
        String pkg;
        CompatibilityInfo info;
    }
    
    private native void dumpGraphicsInfo(FileDescriptor fd);

    private class ApplicationThread extends ApplicationThreadNative {
        private static final String HEAP_COLUMN = "%13s %8s %8s %8s %8s %8s %8s";
        private static final String ONE_COUNT_COLUMN = "%21s %8d";
        private static final String TWO_COUNT_COLUMNS = "%21s %8d %21s %8d";
        private static final String TWO_COUNT_COLUMNS_DB = "%21s %8d %21s %8d";
        private static final String DB_INFO_FORMAT = "  %8s %8s %14s %14s  %s";

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

        public final void scheduleSleeping(IBinder token, boolean sleeping) {
            queueOrSendMessage(H.SLEEPING, token, sleeping ? 1 : 0);
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
                ActivityInfo info, CompatibilityInfo compatInfo, Bundle state,
                List<ResultInfo> pendingResults,
                List<Intent> pendingNewIntents, boolean notResumed, boolean isForward) {
            ActivityClientRecord r = new ActivityClientRecord();

            r.token = token;
            r.ident = ident;
            r.intent = intent;
            r.activityInfo = info;
            r.compatInfo = compatInfo;
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
            requestRelaunchActivity(token, pendingResults, pendingNewIntents,
                    configChanges, notResumed, config, true);
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
                CompatibilityInfo compatInfo, int resultCode, String data, Bundle extras,
                boolean sync) {
            ReceiverData r = new ReceiverData(intent, resultCode, data, extras,
                    sync, false, mAppThread.asBinder());
            r.info = info;
            r.compatInfo = compatInfo;
            queueOrSendMessage(H.RECEIVER, r);
        }

        public final void scheduleCreateBackupAgent(ApplicationInfo app,
                CompatibilityInfo compatInfo, int backupMode) {
            CreateBackupAgentData d = new CreateBackupAgentData();
            d.appInfo = app;
            d.compatInfo = compatInfo;
            d.backupMode = backupMode;

            queueOrSendMessage(H.CREATE_BACKUP_AGENT, d);
        }

        public final void scheduleDestroyBackupAgent(ApplicationInfo app,
                CompatibilityInfo compatInfo) {
            CreateBackupAgentData d = new CreateBackupAgentData();
            d.appInfo = app;
            d.compatInfo = compatInfo;

            queueOrSendMessage(H.DESTROY_BACKUP_AGENT, d);
        }

        public final void scheduleCreateService(IBinder token,
                ServiceInfo info, CompatibilityInfo compatInfo) {
            CreateServiceData s = new CreateServiceData();
            s.token = token;
            s.info = info;
            s.compatInfo = compatInfo;

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

        public final void scheduleServiceArgs(IBinder token, boolean taskRemoved, int startId,
            int flags ,Intent args) {
            ServiceArgsData s = new ServiceArgsData();
            s.token = token;
            s.taskRemoved = taskRemoved;
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
                CompatibilityInfo compatInfo, Map<String, IBinder> services,
                Bundle coreSettings) {

            if (services != null) {
                // Setup the service cache in the ServiceManager
                ServiceManager.initServiceCache(services);
            }

            setCoreSettings(coreSettings);

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
            data.compatInfo = compatInfo;
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

        public void clearDnsCache() {
            // a non-standard API to get this to libcore
            InetAddress.clearDnsCache();
        }

        public void setHttpProxy(String host, String port, String exclList) {
            Proxy.setHttpProxySystemProperty(host, port, exclList);
        }

        public void processInBackground() {
            mH.removeMessages(H.GC_WHEN_IDLE);
            mH.sendMessage(mH.obtainMessage(H.GC_WHEN_IDLE));
        }

        public void dumpService(FileDescriptor fd, IBinder servicetoken, String[] args) {
            DumpComponentInfo data = new DumpComponentInfo();
            try {
                data.fd = ParcelFileDescriptor.dup(fd);
                data.token = servicetoken;
                data.args = args;
                queueOrSendMessage(H.DUMP_SERVICE, data);
            } catch (IOException e) {
                Slog.w(TAG, "dumpService failed", e);
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

        public void profilerControl(boolean start, String path, ParcelFileDescriptor fd,
                int profileType) {
            ProfilerControlData pcd = new ProfilerControlData();
            pcd.path = path;
            pcd.fd = fd;
            queueOrSendMessage(H.PROFILER_CONTROL, pcd, start ? 1 : 0, profileType);
        }

        public void dumpHeap(boolean managed, String path, ParcelFileDescriptor fd) {
            DumpHeapData dhd = new DumpHeapData();
            dhd.path = path;
            dhd.fd = fd;
            queueOrSendMessage(H.DUMP_HEAP, dhd, managed ? 1 : 0);
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

        public void scheduleCrash(String msg) {
            queueOrSendMessage(H.SCHEDULE_CRASH, msg);
        }

        public void dumpActivity(FileDescriptor fd, IBinder activitytoken,
                String prefix, String[] args) {
            DumpComponentInfo data = new DumpComponentInfo();
            try {
                data.fd = ParcelFileDescriptor.dup(fd);
                data.token = activitytoken;
                data.prefix = prefix;
                data.args = args;
                queueOrSendMessage(H.DUMP_ACTIVITY, data);
            } catch (IOException e) {
                Slog.w(TAG, "dumpActivity failed", e);
            }
        }

        @Override
        public Debug.MemoryInfo dumpMemInfo(FileDescriptor fd, String[] args) {
            FileOutputStream fout = new FileOutputStream(fd);
            PrintWriter pw = new PrintWriter(fout);
            try {
                return dumpMemInfo(pw, args);
            } finally {
                pw.flush();
            }
        }

        private Debug.MemoryInfo dumpMemInfo(PrintWriter pw, String[] args) {
            long nativeMax = Debug.getNativeHeapSize() / 1024;
            long nativeAllocated = Debug.getNativeHeapAllocatedSize() / 1024;
            long nativeFree = Debug.getNativeHeapFreeSize() / 1024;

            Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memInfo);

            Runtime runtime = Runtime.getRuntime();

            long dalvikMax = runtime.totalMemory() / 1024;
            long dalvikFree = runtime.freeMemory() / 1024;
            long dalvikAllocated = dalvikMax - dalvikFree;
            long viewInstanceCount = ViewDebug.getViewInstanceCount();
            long viewRootInstanceCount = ViewDebug.getViewRootImplCount();
            long appContextInstanceCount = Debug.countInstancesOfClass(ContextImpl.class);
            long activityInstanceCount = Debug.countInstancesOfClass(Activity.class);
            int globalAssetCount = AssetManager.getGlobalAssetCount();
            int globalAssetManagerCount = AssetManager.getGlobalAssetManagerCount();
            int binderLocalObjectCount = Debug.getBinderLocalObjectCount();
            int binderProxyObjectCount = Debug.getBinderProxyObjectCount();
            int binderDeathObjectCount = Debug.getBinderDeathObjectCount();
            long openSslSocketCount = Debug.countInstancesOfClass(OpenSSLSocketImpl.class);
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
                pw.print(memInfo.nativeSharedDirty); pw.print(',');
                pw.print(memInfo.dalvikSharedDirty); pw.print(',');
                pw.print(memInfo.otherSharedDirty); pw.print(',');
                pw.print(memInfo.nativeSharedDirty + memInfo.dalvikSharedDirty
                        + memInfo.otherSharedDirty); pw.print(',');

                // Heap info - private
                pw.print(memInfo.nativePrivateDirty); pw.print(',');
                pw.print(memInfo.dalvikPrivateDirty); pw.print(',');
                pw.print(memInfo.otherPrivateDirty); pw.print(',');
                pw.print(memInfo.nativePrivateDirty + memInfo.dalvikPrivateDirty
                        + memInfo.otherPrivateDirty); pw.print(',');

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
                            dbStats.lookaside, dbStats.cache, dbStats.dbName);
                    pw.print(',');
                }

                return memInfo;
            }

            // otherwise, show human-readable format
            printRow(pw, HEAP_COLUMN, "", "", "Shared", "Private", "Heap", "Heap", "Heap");
            printRow(pw, HEAP_COLUMN, "", "Pss", "Dirty", "Dirty", "Size", "Alloc", "Free");
            printRow(pw, HEAP_COLUMN, "", "------", "------", "------", "------", "------",
                    "------");
            printRow(pw, HEAP_COLUMN, "Native", memInfo.nativePss, memInfo.nativeSharedDirty,
                    memInfo.nativePrivateDirty, nativeMax, nativeAllocated, nativeFree);
            printRow(pw, HEAP_COLUMN, "Dalvik", memInfo.dalvikPss, memInfo.dalvikSharedDirty,
                    memInfo.dalvikPrivateDirty, dalvikMax, dalvikAllocated, dalvikFree);

            int otherPss = memInfo.otherPss;
            int otherSharedDirty = memInfo.otherSharedDirty;
            int otherPrivateDirty = memInfo.otherPrivateDirty;

            for (int i=0; i<Debug.MemoryInfo.NUM_OTHER_STATS; i++) {
                printRow(pw, HEAP_COLUMN, Debug.MemoryInfo.getOtherLabel(i),
                        memInfo.getOtherPss(i), memInfo.getOtherSharedDirty(i),
                        memInfo.getOtherPrivateDirty(i), "", "", "");
                otherPss -= memInfo.getOtherPss(i);
                otherSharedDirty -= memInfo.getOtherSharedDirty(i);
                otherPrivateDirty -= memInfo.getOtherPrivateDirty(i);
            }

            printRow(pw, HEAP_COLUMN, "Unknown", otherPss, otherSharedDirty,
                    otherPrivateDirty, "", "", "");
            printRow(pw, HEAP_COLUMN, "TOTAL", memInfo.getTotalPss(),
                    memInfo.getTotalSharedDirty(), memInfo.getTotalPrivateDirty(),
                    nativeMax+dalvikMax, nativeAllocated+dalvikAllocated,
                    nativeFree+dalvikFree);

            pw.println(" ");
            pw.println(" Objects");
            printRow(pw, TWO_COUNT_COLUMNS, "Views:", viewInstanceCount, "ViewRootImpl:",
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
            printRow(pw, TWO_COUNT_COLUMNS_DB, "heap:", sqliteAllocated, "MEMORY_USED:",
                    stats.memoryUsed / 1024);
            printRow(pw, TWO_COUNT_COLUMNS_DB, "PAGECACHE_OVERFLOW:",
                    stats.pageCacheOverflo / 1024, "MALLOC_SIZE:", stats.largestMemAlloc / 1024);
            pw.println(" ");
            int N = stats.dbStats.size();
            if (N > 0) {
                pw.println(" DATABASES");
                printRow(pw, "  %8s %8s %14s %14s  %s", "pgsz", "dbsz", "Lookaside(b)", "cache",
                        "Dbname");
                for (int i = 0; i < N; i++) {
                    DbStats dbStats = stats.dbStats.get(i);
                    printRow(pw, DB_INFO_FORMAT,
                            (dbStats.pageSize > 0) ? String.valueOf(dbStats.pageSize) : " ",
                            (dbStats.dbSize > 0) ? String.valueOf(dbStats.dbSize) : " ",
                            (dbStats.lookaside > 0) ? String.valueOf(dbStats.lookaside) : " ",
                            dbStats.cache, dbStats.dbName);
                }
            }

            // Asset details.
            String assetAlloc = AssetManager.getAssetAllocations();
            if (assetAlloc != null) {
                pw.println(" ");
                pw.println(" Asset Allocations");
                pw.print(assetAlloc);
            }

            return memInfo;
        }

        @Override
        public void dumpGfxInfo(FileDescriptor fd, String[] args) {
            dumpGraphicsInfo(fd);
            WindowManagerImpl.getDefault().dumpGfxInfo(fd);
        }

        private void printRow(PrintWriter pw, String format, Object...objs) {
            pw.println(String.format(format, objs));
        }

        public void setCoreSettings(Bundle coreSettings) {
            queueOrSendMessage(H.SET_CORE_SETTINGS, coreSettings);
        }

        public void updatePackageCompatibilityInfo(String pkg, CompatibilityInfo info) {
            UpdateCompatibilityData ucd = new UpdateCompatibilityData();
            ucd.pkg = pkg;
            ucd.info = info;
            queueOrSendMessage(H.UPDATE_PACKAGE_COMPATIBILITY_INFO, ucd);
        }

        public void scheduleTrimMemory(int level) {
            queueOrSendMessage(H.TRIM_MEMORY, null, level);
        }
    }

    private class H extends Handler {
        public static final int LAUNCH_ACTIVITY         = 100;
        public static final int PAUSE_ACTIVITY          = 101;
        public static final int PAUSE_ACTIVITY_FINISHING= 102;
        public static final int STOP_ACTIVITY_SHOW      = 103;
        public static final int STOP_ACTIVITY_HIDE      = 104;
        public static final int SHOW_WINDOW             = 105;
        public static final int HIDE_WINDOW             = 106;
        public static final int RESUME_ACTIVITY         = 107;
        public static final int SEND_RESULT             = 108;
        public static final int DESTROY_ACTIVITY        = 109;
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
        public static final int SCHEDULE_CRASH          = 134;
        public static final int DUMP_HEAP               = 135;
        public static final int DUMP_ACTIVITY           = 136;
        public static final int SLEEPING                = 137;
        public static final int SET_CORE_SETTINGS       = 138;
        public static final int UPDATE_PACKAGE_COMPATIBILITY_INFO = 139;
        public static final int TRIM_MEMORY             = 140;
        String codeToString(int code) {
            if (DEBUG_MESSAGES) {
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
                    case SCHEDULE_CRASH: return "SCHEDULE_CRASH";
                    case DUMP_HEAP: return "DUMP_HEAP";
                    case DUMP_ACTIVITY: return "DUMP_ACTIVITY";
                    case SLEEPING: return "SLEEPING";
                    case SET_CORE_SETTINGS: return "SET_CORE_SETTINGS";
                    case UPDATE_PACKAGE_COMPATIBILITY_INFO: return "UPDATE_PACKAGE_COMPATIBILITY_INFO";
                    case TRIM_MEMORY: return "TRIM_MEMORY";
                }
            }
            return "(unknown)";
        }
        public void handleMessage(Message msg) {
            if (DEBUG_MESSAGES) Slog.v(TAG, ">>> handling: " + msg.what);
            switch (msg.what) {
                case LAUNCH_ACTIVITY: {
                    ActivityClientRecord r = (ActivityClientRecord)msg.obj;

                    r.packageInfo = getPackageInfoNoCheck(
                            r.activityInfo.applicationInfo, r.compatInfo);
                    handleLaunchActivity(r, null);
                } break;
                case RELAUNCH_ACTIVITY: {
                    ActivityClientRecord r = (ActivityClientRecord)msg.obj;
                    handleRelaunchActivity(r);
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
                    handleConfigurationChanged((Configuration)msg.obj, null);
                    break;
                case CLEAN_UP_CONTEXT:
                    ContextCleanupInfo cci = (ContextCleanupInfo)msg.obj;
                    cci.context.performFinalCleanup(cci.who, cci.what);
                    break;
                case GC_WHEN_IDLE:
                    scheduleGcIdler();
                    break;
                case DUMP_SERVICE:
                    handleDumpService((DumpComponentInfo)msg.obj);
                    break;
                case LOW_MEMORY:
                    handleLowMemory();
                    break;
                case ACTIVITY_CONFIGURATION_CHANGED:
                    handleActivityConfigurationChanged((IBinder)msg.obj);
                    break;
                case PROFILER_CONTROL:
                    handleProfilerControl(msg.arg1 != 0, (ProfilerControlData)msg.obj, msg.arg2);
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
                case SCHEDULE_CRASH:
                    throw new RemoteServiceException((String)msg.obj);
                case DUMP_HEAP:
                    handleDumpHeap(msg.arg1 != 0, (DumpHeapData)msg.obj);
                    break;
                case DUMP_ACTIVITY:
                    handleDumpActivity((DumpComponentInfo)msg.obj);
                    break;
                case SLEEPING:
                    handleSleeping((IBinder)msg.obj, msg.arg1 != 0);
                    break;
                case SET_CORE_SETTINGS:
                    handleSetCoreSettings((Bundle) msg.obj);
                    break;
                case UPDATE_PACKAGE_COMPATIBILITY_INFO:
                    handleUpdatePackageCompatibilityInfo((UpdateCompatibilityData)msg.obj);
                    break;
                case TRIM_MEMORY:
                    handleTrimMemory(msg.arg1);
                    break;
            }
            if (DEBUG_MESSAGES) Slog.v(TAG, "<<< done: " + msg.what);
        }

        private void maybeSnapshot() {
            if (mBoundApplication != null && SamplingProfilerIntegration.isEnabled()) {
                // convert the *private* ActivityThread.PackageInfo to *public* known
                // android.content.pm.PackageInfo
                String packageName = mBoundApplication.info.mPackageName;
                android.content.pm.PackageInfo packageInfo = null;
                try {
                    Context context = getSystemContext();
                    if(context == null) {
                        Log.e(TAG, "cannot get a valid context");
                        return;
                    }
                    PackageManager pm = context.getPackageManager();
                    if(pm == null) {
                        Log.e(TAG, "cannot get a valid PackageManager");
                        return;
                    }
                    packageInfo = pm.getPackageInfo(
                            packageName, PackageManager.GET_ACTIVITIES);
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "cannot get package info for " + packageName, e);
                }
                SamplingProfilerIntegration.writeSnapshot(mBoundApplication.processName, packageInfo);
            }
        }
    }

    private class Idler implements MessageQueue.IdleHandler {
        public final boolean queueIdle() {
            ActivityClientRecord a = mNewActivities;
            if (a != null) {
                mNewActivities = null;
                IActivityManager am = ActivityManagerNative.getDefault();
                ActivityClientRecord prev;
                do {
                    if (localLOGV) Slog.v(
                        TAG, "Reporting idle of " + a +
                        " finished=" +
                        (a.activity != null && a.activity.mFinished));
                    if (a.activity != null && !a.activity.mFinished) {
                        try {
                            am.activityIdle(a.token, a.createdConfig);
                            a.createdConfig = null;
                        } catch (RemoteException ex) {
                            // Ignore
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

    private static class ResourcesKey {
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

    public static ActivityThread currentActivityThread() {
        return sThreadLocal.get();
    }

    public static String currentPackageName() {
        ActivityThread am = currentActivityThread();
        return (am != null && am.mBoundApplication != null)
            ? am.mBoundApplication.processName : null;
    }

    public static Application currentApplication() {
        ActivityThread am = currentActivityThread();
        return am != null ? am.mInitialApplication : null;
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

    DisplayMetrics getDisplayMetricsLocked(CompatibilityInfo ci, boolean forceUpdate) {
        DisplayMetrics dm = mDisplayMetrics.get(ci);
        if (dm != null && !forceUpdate) {
            return dm;
        }
        if (dm == null) {
            dm = new DisplayMetrics();
            mDisplayMetrics.put(ci, dm);
        }
        Display d = WindowManagerImpl.getDefault(ci).getDefaultDisplay();
        d.getMetrics(dm);
        //Slog.i("foo", "New metrics: w=" + metrics.widthPixels + " h="
        //        + metrics.heightPixels + " den=" + metrics.density
        //        + " xdpi=" + metrics.xdpi + " ydpi=" + metrics.ydpi);
        return dm;
    }

    static Configuration applyConfigCompat(Configuration config, CompatibilityInfo compat) {
        if (config == null) {
            return null;
        }
        if (compat != null && !compat.supportsScreen()) {
            config = new Configuration(config);
            compat.applyToConfiguration(config);
        }
        return config;
    }

    private Configuration mMainThreadConfig = new Configuration();
    Configuration applyConfigCompatMainThread(Configuration config, CompatibilityInfo compat) {
        if (config == null) {
            return null;
        }
        if (compat != null && !compat.supportsScreen()) {
            mMainThreadConfig.setTo(config);
            config = mMainThreadConfig;
            compat.applyToConfiguration(config);
        }
        return config;
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
        DisplayMetrics metrics = getDisplayMetricsLocked(compInfo, false);
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
    Resources getTopLevelResources(String resDir, LoadedApk pkgInfo) {
        return getTopLevelResources(resDir, pkgInfo.mCompatibilityInfo.get());
    }

    final Handler getHandler() {
        return mH;
    }

    public final LoadedApk getPackageInfo(String packageName, CompatibilityInfo compatInfo,
            int flags) {
        synchronized (mPackages) {
            WeakReference<LoadedApk> ref;
            if ((flags&Context.CONTEXT_INCLUDE_CODE) != 0) {
                ref = mPackages.get(packageName);
            } else {
                ref = mResourcePackages.get(packageName);
            }
            LoadedApk packageInfo = ref != null ? ref.get() : null;
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
            // Ignore
        }

        if (ai != null) {
            return getPackageInfo(ai, compatInfo, flags);
        }

        return null;
    }

    public final LoadedApk getPackageInfo(ApplicationInfo ai, CompatibilityInfo compatInfo,
            int flags) {
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
        return getPackageInfo(ai, compatInfo, null, securityViolation, includeCode);
    }

    public final LoadedApk getPackageInfoNoCheck(ApplicationInfo ai,
            CompatibilityInfo compatInfo) {
        return getPackageInfo(ai, compatInfo, null, false, true);
    }

    public final LoadedApk peekPackageInfo(String packageName, boolean includeCode) {
        synchronized (mPackages) {
            WeakReference<LoadedApk> ref;
            if (includeCode) {
                ref = mPackages.get(packageName);
            } else {
                ref = mResourcePackages.get(packageName);
            }
            return ref != null ? ref.get() : null;
        }
    }

    private LoadedApk getPackageInfo(ApplicationInfo aInfo, CompatibilityInfo compatInfo,
            ClassLoader baseLoader, boolean securityViolation, boolean includeCode) {
        synchronized (mPackages) {
            WeakReference<LoadedApk> ref;
            if (includeCode) {
                ref = mPackages.get(aInfo.packageName);
            } else {
                ref = mResourcePackages.get(aInfo.packageName);
            }
            LoadedApk packageInfo = ref != null ? ref.get() : null;
            if (packageInfo == null || (packageInfo.mResources != null
                    && !packageInfo.mResources.getAssets().isUpToDate())) {
                if (localLOGV) Slog.v(TAG, (includeCode ? "Loading code package "
                        : "Loading resource-only package ") + aInfo.packageName
                        + " (in " + (mBoundApplication != null
                                ? mBoundApplication.processName : null)
                        + ")");
                packageInfo =
                    new LoadedApk(this, aInfo, compatInfo, this, baseLoader,
                            securityViolation, includeCode &&
                            (aInfo.flags&ApplicationInfo.FLAG_HAS_CODE) != 0);
                if (includeCode) {
                    mPackages.put(aInfo.packageName,
                            new WeakReference<LoadedApk>(packageInfo));
                } else {
                    mResourcePackages.put(aInfo.packageName,
                            new WeakReference<LoadedApk>(packageInfo));
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
        return mResConfiguration;
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
                LoadedApk info = new LoadedApk(this, "android", context, null,
                        CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO);
                context.init(info, null, this);
                context.getResources().updateConfiguration(
                        getConfiguration(), getDisplayMetricsLocked(
                                CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO, false));
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
            context.init(new LoadedApk(this, "android", context, info,
                    CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO), null, this);
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

    public void registerOnActivityPausedListener(Activity activity,
            OnActivityPausedListener listener) {
        synchronized (mOnPauseListeners) {
            ArrayList<OnActivityPausedListener> list = mOnPauseListeners.get(activity);
            if (list == null) {
                list = new ArrayList<OnActivityPausedListener>();
                mOnPauseListeners.put(activity, list);
            }
            list.add(listener);
        }
    }

    public void unregisterOnActivityPausedListener(Activity activity,
            OnActivityPausedListener listener) {
        synchronized (mOnPauseListeners) {
            ArrayList<OnActivityPausedListener> list = mOnPauseListeners.get(activity);
            if (list != null) {
                list.remove(listener);
            }
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
        Activity.NonConfigurationInstances lastNonConfigurationInstances) {
        ActivityClientRecord r = new ActivityClientRecord();
            r.token = token;
            r.ident = 0;
            r.intent = intent;
            r.state = state;
            r.parent = parent;
            r.embeddedID = id;
            r.activityInfo = activityInfo;
            r.lastNonConfigurationInstances = lastNonConfigurationInstances;
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
    private void queueOrSendMessage(int what, Object obj) {
        queueOrSendMessage(what, obj, 0, 0);
    }

    private void queueOrSendMessage(int what, Object obj, int arg1) {
        queueOrSendMessage(what, obj, arg1, 0);
    }

    private void queueOrSendMessage(int what, Object obj, int arg1, int arg2) {
        synchronized (this) {
            if (DEBUG_MESSAGES) Slog.v(
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

    private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
        // System.out.println("##### [" + System.currentTimeMillis() + "] ActivityThread.performLaunchActivity(" + r + ")");

        ActivityInfo aInfo = r.activityInfo;
        if (r.packageInfo == null) {
            r.packageInfo = getPackageInfo(aInfo.applicationInfo, r.compatInfo,
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
            StrictMode.incrementExpectedActivityCount(activity.getClass());
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
                Configuration config = new Configuration(mCompatConfiguration);
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Launching activity "
                        + r.activityInfo.name + " with config " + config);
                activity.attach(appContext, this, getInstrumentation(), r.token,
                        r.ident, app, r.intent, r.activityInfo, title, r.parent,
                        r.embeddedID, r.lastNonConfigurationInstances, config);

                if (customIntent != null) {
                    activity.mIntent = customIntent;
                }
                r.lastNonConfigurationInstances = null;
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

    private void handleLaunchActivity(ActivityClientRecord r, Intent customIntent) {
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
                // Ignore
            }
        }
    }

    private void deliverNewIntents(ActivityClientRecord r,
            List<Intent> intents) {
        final int N = intents.size();
        for (int i=0; i<N; i++) {
            Intent intent = intents.get(i);
            intent.setExtrasClassLoader(r.activity.getClassLoader());
            r.activity.mFragments.noteStateNotSaved();
            mInstrumentation.callActivityOnNewIntent(r.activity, intent);
        }
    }

    public final void performNewIntents(IBinder token,
            List<Intent> intents) {
        ActivityClientRecord r = mActivities.get(token);
        if (r != null) {
            final boolean resumed = !r.paused;
            if (resumed) {
                r.activity.mTemporaryPause = true;
                mInstrumentation.callActivityOnPause(r.activity);
            }
            deliverNewIntents(r, intents);
            if (resumed) {
                r.activity.performResume();
                r.activity.mTemporaryPause = false;
            }
        }
    }

    private void handleNewIntent(NewIntentData data) {
        performNewIntents(data.token, data.intents);
    }

    private static final ThreadLocal<Intent> sCurrentBroadcastIntent = new ThreadLocal<Intent>();

    /**
     * Return the Intent that's currently being handled by a
     * BroadcastReceiver on this thread, or null if none.
     * @hide
     */
    public static Intent getIntentBeingBroadcast() {
        return sCurrentBroadcastIntent.get();
    }

    private void handleReceiver(ReceiverData data) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        String component = data.intent.getComponent().getClassName();

        LoadedApk packageInfo = getPackageInfoNoCheck(
                data.info.applicationInfo, data.compatInfo);

        IActivityManager mgr = ActivityManagerNative.getDefault();

        BroadcastReceiver receiver;
        try {
            java.lang.ClassLoader cl = packageInfo.getClassLoader();
            data.intent.setExtrasClassLoader(cl);
            data.setExtrasClassLoader(cl);
            receiver = (BroadcastReceiver)cl.loadClass(component).newInstance();
        } catch (Exception e) {
            if (DEBUG_BROADCAST) Slog.i(TAG,
                    "Finishing failed broadcast to " + data.intent.getComponent());
            data.sendFinished(mgr);
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
            sCurrentBroadcastIntent.set(data.intent);
            receiver.setPendingResult(data);
            receiver.onReceive(context.getReceiverRestrictedContext(),
                    data.intent);
        } catch (Exception e) {
            if (DEBUG_BROADCAST) Slog.i(TAG,
                    "Finishing failed broadcast to " + data.intent.getComponent());
            data.sendFinished(mgr);
            if (!mInstrumentation.onException(receiver, e)) {
                throw new RuntimeException(
                    "Unable to start receiver " + component
                    + ": " + e.toString(), e);
            }
        } finally {
            sCurrentBroadcastIntent.set(null);
        }

        if (receiver.getPendingResult() != null) {
            data.finish();
        }
    }

    // Instantiate a BackupAgent and tell it that it's alive
    private void handleCreateBackupAgent(CreateBackupAgentData data) {
        if (DEBUG_BACKUP) Slog.v(TAG, "handleCreateBackupAgent: " + data);

        // no longer idle; we have backup work to do
        unscheduleGcIdler();

        // instantiate the BackupAgent class named in the manifest
        LoadedApk packageInfo = getPackageInfoNoCheck(data.appInfo, data.compatInfo);
        String packageName = packageInfo.mPackageName;
        if (mBackupAgents.get(packageName) != null) {
            Slog.d(TAG, "BackupAgent " + "  for " + packageName
                    + " already exists");
            return;
        }

        BackupAgent agent = null;
        String classname = data.appInfo.backupAgentName;

        // full backup operation but no app-supplied agent?  use the default implementation
        if (classname == null && (data.backupMode == IApplicationThread.BACKUP_MODE_FULL
                || data.backupMode == IApplicationThread.BACKUP_MODE_RESTORE_FULL)) {
            classname = "android.app.backup.FullBackupAgent";
        }

        try {
            IBinder binder = null;
            try {
                if (DEBUG_BACKUP) Slog.v(TAG, "Initializing agent class " + classname);

                java.lang.ClassLoader cl = packageInfo.getClassLoader();
                agent = (BackupAgent) cl.loadClass(classname).newInstance();

                // set up the agent's context
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
                if (data.backupMode != IApplicationThread.BACKUP_MODE_RESTORE
                        && data.backupMode != IApplicationThread.BACKUP_MODE_RESTORE_FULL) {
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
                    + classname + ": " + e.toString(), e);
        }
    }

    // Tear down a BackupAgent
    private void handleDestroyBackupAgent(CreateBackupAgentData data) {
        if (DEBUG_BACKUP) Slog.v(TAG, "handleDestroyBackupAgent: " + data);

        LoadedApk packageInfo = getPackageInfoNoCheck(data.appInfo, data.compatInfo);
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

    private void handleCreateService(CreateServiceData data) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        LoadedApk packageInfo = getPackageInfoNoCheck(
                data.info.applicationInfo, data.compatInfo);
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

    private void handleBindService(BindServiceData data) {
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

    private void handleUnbindService(BindServiceData data) {
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

    private void handleDumpService(DumpComponentInfo info) {
        Service s = mServices.get(info.token);
        if (s != null) {
            PrintWriter pw = new PrintWriter(new FileOutputStream(info.fd.getFileDescriptor()));
            s.dump(info.fd.getFileDescriptor(), pw, info.args);
            pw.flush();
            try {
                info.fd.close();
            } catch (IOException e) {
            }
        }
    }

    private void handleDumpActivity(DumpComponentInfo info) {
        ActivityClientRecord r = mActivities.get(info.token);
        if (r != null && r.activity != null) {
            PrintWriter pw = new PrintWriter(new FileOutputStream(info.fd.getFileDescriptor()));
            r.activity.dump(info.prefix, info.fd.getFileDescriptor(), pw, info.args);
            pw.flush();
            try {
                info.fd.close();
            } catch (IOException e) {
            }
        }
    }

    private void handleServiceArgs(ServiceArgsData data) {
        Service s = mServices.get(data.token);
        if (s != null) {
            try {
                if (data.args != null) {
                    data.args.setExtrasClassLoader(s.getClassLoader());
                }
                int res;
                if (!data.taskRemoved) {
                    res = s.onStartCommand(data.args, data.flags, data.startId);
                } else {
                    s.onTaskRemoved(data.args);
                    res = Service.START_TASK_REMOVED_COMPLETE;
                }

                QueuedWork.waitToFinish();

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

    private void handleStopService(IBinder token) {
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

                QueuedWork.waitToFinish();

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

    public final ActivityClientRecord performResumeActivity(IBinder token,
            boolean clearHide) {
        ActivityClientRecord r = mActivities.get(token);
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

    final void cleanUpPendingRemoveWindows(ActivityClientRecord r) {
        if (r.mPendingRemoveWindow != null) {
            r.mPendingRemoveWindowManager.removeViewImmediate(r.mPendingRemoveWindow);
            IBinder wtoken = r.mPendingRemoveWindow.getWindowToken();
            if (wtoken != null) {
                WindowManagerImpl.getDefault().closeAll(wtoken,
                        r.activity.getClass().getName(), "Activity");
            }
        }
        r.mPendingRemoveWindow = null;
        r.mPendingRemoveWindowManager = null;
    }

    final void handleResumeActivity(IBinder token, boolean clearHide, boolean isForward) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        ActivityClientRecord r = performResumeActivity(token, clearHide);

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

            // Get rid of anything left hanging around.
            cleanUpPendingRemoveWindows(r);

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

            if (!r.onlyLocalRequest) {
                r.nextIdle = mNewActivities;
                mNewActivities = r;
                if (localLOGV) Slog.v(
                    TAG, "Scheduling idle handler for " + r);
                Looper.myQueue().addIdleHandler(new Idler());
            }
            r.onlyLocalRequest = false;

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
    private Bitmap mAvailThumbnailBitmap = null;
    private Canvas mThumbnailCanvas = null;

    private Bitmap createThumbnailBitmap(ActivityClientRecord r) {
        Bitmap thumbnail = mAvailThumbnailBitmap;
        try {
            if (thumbnail == null) {
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

                // On platforms where we don't want thumbnails, set dims to (0,0)
                if ((w > 0) && (h > 0)) {
                    thumbnail = Bitmap.createBitmap(w, h, THUMBNAIL_FORMAT);
                    thumbnail.eraseColor(0);
                }
            }

            if (thumbnail != null) {
                Canvas cv = mThumbnailCanvas;
                if (cv == null) {
                    mThumbnailCanvas = cv = new Canvas();
                }
    
                cv.setBitmap(thumbnail);
                if (!r.activity.onCreateThumbnail(thumbnail, cv)) {
                    mAvailThumbnailBitmap = thumbnail;
                    thumbnail = null;
                }
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

    private void handlePauseActivity(IBinder token, boolean finished,
            boolean userLeaving, int configChanges) {
        ActivityClientRecord r = mActivities.get(token);
        if (r != null) {
            //Slog.v(TAG, "userLeaving=" + userLeaving + " handling pause of " + r);
            if (userLeaving) {
                performUserLeavingActivity(r);
            }

            r.activity.mConfigChangeFlags |= configChanges;
            performPauseActivity(token, finished, r.isPreHoneycomb());

            // Make sure any pending writes are now committed.
            if (r.isPreHoneycomb()) {
                QueuedWork.waitToFinish();
            }
            
            // Tell the activity manager we have paused.
            try {
                ActivityManagerNative.getDefault().activityPaused(token);
            } catch (RemoteException ex) {
            }
        }
    }

    final void performUserLeavingActivity(ActivityClientRecord r) {
        mInstrumentation.callActivityOnUserLeaving(r.activity);
    }

    final Bundle performPauseActivity(IBinder token, boolean finished,
            boolean saveState) {
        ActivityClientRecord r = mActivities.get(token);
        return r != null ? performPauseActivity(r, finished, saveState) : null;
    }

    final Bundle performPauseActivity(ActivityClientRecord r, boolean finished,
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

        // Notify any outstanding on paused listeners
        ArrayList<OnActivityPausedListener> listeners;
        synchronized (mOnPauseListeners) {
            listeners = mOnPauseListeners.remove(r.activity);
        }
        int size = (listeners != null ? listeners.size() : 0);
        for (int i = 0; i < size; i++) {
            listeners.get(i).onPaused(r.activity);
        }

        return state;
    }

    final void performStopActivity(IBinder token, boolean saveState) {
        ActivityClientRecord r = mActivities.get(token);
        performStopActivityInner(r, null, false, saveState);
    }

    private static class StopInfo {
        Bitmap thumbnail;
        CharSequence description;
    }

    private class ProviderRefCount {
        public int count;
        ProviderRefCount(int pCount) {
            count = pCount;
        }
    }

    /**
     * Core implementation of stopping an activity.  Note this is a little
     * tricky because the server's meaning of stop is slightly different
     * than our client -- for the server, stop means to save state and give
     * it the result when it is done, but the window may still be visible.
     * For the client, we want to call onStop()/onStart() to indicate when
     * the activity's UI visibillity changes.
     */
    private void performStopActivityInner(ActivityClientRecord r,
            StopInfo info, boolean keepShown, boolean saveState) {
        if (localLOGV) Slog.v(TAG, "Performing stop of " + r);
        Bundle state = null;
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
                    info.thumbnail = createThumbnailBitmap(r);
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

            // Next have the activity save its current state and managed dialogs...
            if (!r.activity.mFinished && saveState) {
                if (r.state == null) {
                    state = new Bundle();
                    mInstrumentation.callActivityOnSaveInstanceState(r.activity, state);
                    r.state = state;
                } else {
                    state = r.state;
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

    private void updateVisibility(ActivityClientRecord r, boolean show) {
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

    private void handleStopActivity(IBinder token, boolean show, int configChanges) {
        ActivityClientRecord r = mActivities.get(token);
        r.activity.mConfigChangeFlags |= configChanges;

        StopInfo info = new StopInfo();
        performStopActivityInner(r, info, show, true);

        if (localLOGV) Slog.v(
            TAG, "Finishing stop of " + r + ": show=" + show
            + " win=" + r.window);

        updateVisibility(r, show);

        // Make sure any pending writes are now committed.
        if (!r.isPreHoneycomb()) {
            QueuedWork.waitToFinish();
        }

        // Tell activity manager we have been stopped.
        try {
            ActivityManagerNative.getDefault().activityStopped(
                r.token, r.state, info.thumbnail, info.description);
        } catch (RemoteException ex) {
        }
    }

    final void performRestartActivity(IBinder token) {
        ActivityClientRecord r = mActivities.get(token);
        if (r.stopped) {
            r.activity.performRestart();
            r.stopped = false;
        }
    }

    private void handleWindowVisibility(IBinder token, boolean show) {
        ActivityClientRecord r = mActivities.get(token);
        
        if (r == null) {
            Log.w(TAG, "handleWindowVisibility: no activity for token " + token);
            return;
        }
        
        if (!show && !r.stopped) {
            performStopActivityInner(r, null, show, false);
        } else if (show && r.stopped) {
            // If we are getting ready to gc after going to the background, well
            // we are back active so skip it.
            unscheduleGcIdler();

            r.activity.performRestart();
            r.stopped = false;
        }
        if (r.activity.mDecor != null) {
            if (false) Slog.v(
                TAG, "Handle window " + r + " visibility: " + show);
            updateVisibility(r, show);
        }
    }

    private void handleSleeping(IBinder token, boolean sleeping) {
        ActivityClientRecord r = mActivities.get(token);

        if (r == null) {
            Log.w(TAG, "handleSleeping: no activity for token " + token);
            return;
        }

        if (sleeping) {
            if (!r.stopped && !r.isPreHoneycomb()) {
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

            // Make sure any pending writes are now committed.
            if (!r.isPreHoneycomb()) {
                QueuedWork.waitToFinish();
            }

            // Tell activity manager we slept.
            try {
                ActivityManagerNative.getDefault().activitySlept(r.token);
            } catch (RemoteException ex) {
            }
        } else {
            if (r.stopped && r.activity.mVisibleFromServer) {
                r.activity.performRestart();
                r.stopped = false;
            }
        }
    }

    private void handleSetCoreSettings(Bundle coreSettings) {
        synchronized (mPackages) {
            mCoreSettings = coreSettings;
        }
    }

    private void handleUpdatePackageCompatibilityInfo(UpdateCompatibilityData data) {
        LoadedApk apk = peekPackageInfo(data.pkg, false);
        if (apk != null) {
            apk.mCompatibilityInfo.set(data.info);
        }
        apk = peekPackageInfo(data.pkg, true);
        if (apk != null) {
            apk.mCompatibilityInfo.set(data.info);
        }
        handleConfigurationChanged(mConfiguration, data.info);
        WindowManagerImpl.getDefault().reportNewConfiguration(mConfiguration);
    }

    private void deliverResults(ActivityClientRecord r, List<ResultInfo> results) {
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

    private void handleSendResult(ResultData res) {
        ActivityClientRecord r = mActivities.get(res.token);
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
                    r.activity.mTemporaryPause = true;
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
                r.activity.performResume();
                r.activity.mTemporaryPause = false;
            }
        }
    }

    public final ActivityClientRecord performDestroyActivity(IBinder token, boolean finishing) {
        return performDestroyActivity(token, finishing, 0, false);
    }

    private ActivityClientRecord performDestroyActivity(IBinder token, boolean finishing,
            int configChanges, boolean getNonConfigInstance) {
        ActivityClientRecord r = mActivities.get(token);
        Class activityClass = null;
        if (localLOGV) Slog.v(TAG, "Performing finish of " + r);
        if (r != null) {
            activityClass = r.activity.getClass();
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
                    r.lastNonConfigurationInstances
                            = r.activity.retainNonConfigurationInstances();
                } catch (Exception e) {
                    if (!mInstrumentation.onException(r.activity, e)) {
                        throw new RuntimeException(
                                "Unable to retain activity "
                                + r.intent.getComponent().toShortString()
                                + ": " + e.toString(), e);
                    }
                }
            }
            try {
                r.activity.mCalled = false;
                mInstrumentation.callActivityOnDestroy(r.activity);
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
        StrictMode.decrementExpectedActivityCount(activityClass);
        return r;
    }

    private static String safeToComponentShortString(Intent intent) {
        ComponentName component = intent.getComponent();
        return component == null ? "[Unknown]" : component.toShortString();
    }

    private void handleDestroyActivity(IBinder token, boolean finishing,
            int configChanges, boolean getNonConfigInstance) {
        ActivityClientRecord r = performDestroyActivity(token, finishing,
                configChanges, getNonConfigInstance);
        if (r != null) {
            cleanUpPendingRemoveWindows(r);
            WindowManager wm = r.activity.getWindowManager();
            View v = r.activity.mDecor;
            if (v != null) {
                if (r.activity.mVisibleFromServer) {
                    mNumVisibleActivities--;
                }
                IBinder wtoken = v.getWindowToken();
                if (r.activity.mWindowAdded) {
                    if (r.onlyLocalRequest) {
                        // Hold off on removing this until the new activity's
                        // window is being added.
                        r.mPendingRemoveWindow = v;
                        r.mPendingRemoveWindowManager = wm;
                    } else {
                        wm.removeViewImmediate(v);
                    }
                }
                if (wtoken != null && r.mPendingRemoveWindow == null) {
                    WindowManagerImpl.getDefault().closeAll(wtoken,
                            r.activity.getClass().getName(), "Activity");
                }
                r.activity.mDecor = null;
            }
            if (r.mPendingRemoveWindow == null) {
                // If we are delaying the removal of the activity window, then
                // we can't clean up all windows here.  Note that we can't do
                // so later either, which means any windows that aren't closed
                // by the app will leak.  Well we try to warning them a lot
                // about leaking windows, because that is a bug, so if they are
                // using this recreate facility then they get to live with leaks.
                WindowManagerImpl.getDefault().closeAll(token,
                        r.activity.getClass().getName(), "Activity");
            }

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

    public final void requestRelaunchActivity(IBinder token,
            List<ResultInfo> pendingResults, List<Intent> pendingNewIntents,
            int configChanges, boolean notResumed, Configuration config,
            boolean fromServer) {
        ActivityClientRecord target = null;

        synchronized (mPackages) {
            for (int i=0; i<mRelaunchingActivities.size(); i++) {
                ActivityClientRecord r = mRelaunchingActivities.get(i);
                if (r.token == token) {
                    target = r;
                    if (pendingResults != null) {
                        if (r.pendingResults != null) {
                            r.pendingResults.addAll(pendingResults);
                        } else {
                            r.pendingResults = pendingResults;
                        }
                    }
                    if (pendingNewIntents != null) {
                        if (r.pendingIntents != null) {
                            r.pendingIntents.addAll(pendingNewIntents);
                        } else {
                            r.pendingIntents = pendingNewIntents;
                        }
                    }
                    break;
                }
            }

            if (target == null) {
                target = new ActivityClientRecord();
                target.token = token;
                target.pendingResults = pendingResults;
                target.pendingIntents = pendingNewIntents;
                if (!fromServer) {
                    ActivityClientRecord existing = mActivities.get(token);
                    if (existing != null) {
                        target.startsNotResumed = existing.paused;
                    }
                    target.onlyLocalRequest = true;
                }
                mRelaunchingActivities.add(target);
                queueOrSendMessage(H.RELAUNCH_ACTIVITY, target);
            }

            if (fromServer) {
                target.startsNotResumed = notResumed;
                target.onlyLocalRequest = false;
            }
            if (config != null) {
                target.createdConfig = config;
            }
            target.pendingConfigChanges |= configChanges;
        }
    }

    private void handleRelaunchActivity(ActivityClientRecord tmp) {
        // If we are getting ready to gc after going to the background, well
        // we are back active so skip it.
        unscheduleGcIdler();

        Configuration changedConfig = null;
        int configChanges = 0;

        // First: make sure we have the most recent configuration and most
        // recent version of the activity, or skip it if some previous call
        // had taken a more recent version.
        synchronized (mPackages) {
            int N = mRelaunchingActivities.size();
            IBinder token = tmp.token;
            tmp = null;
            for (int i=0; i<N; i++) {
                ActivityClientRecord r = mRelaunchingActivities.get(i);
                if (r.token == token) {
                    tmp = r;
                    configChanges |= tmp.pendingConfigChanges;
                    mRelaunchingActivities.remove(i);
                    i--;
                    N--;
                }
            }

            if (tmp == null) {
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Abort, activity not relaunching!");
                return;
            }

            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Relaunching activity "
                    + tmp.token + " with configChanges=0x"
                    + Integer.toHexString(configChanges));

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
            handleConfigurationChanged(changedConfig, null);
        }

        ActivityClientRecord r = mActivities.get(tmp.token);
        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Handling relaunch of " + r);
        if (r == null) {
            return;
        }

        r.activity.mConfigChangeFlags |= configChanges;
        r.onlyLocalRequest = tmp.onlyLocalRequest;
        Intent currentIntent = r.activity.mIntent;

        r.activity.mChangingConfigurations = true;

        // Need to ensure state is saved.
        if (!r.paused) {
            performPauseActivity(r.token, false, r.isPreHoneycomb());
        }
        if (r.state == null && !r.stopped && !r.isPreHoneycomb()) {
            r.state = new Bundle();
            mInstrumentation.callActivityOnSaveInstanceState(r.activity, r.state);
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

        handleLaunchActivity(r, currentIntent);
    }

    private void handleRequestThumbnail(IBinder token) {
        ActivityClientRecord r = mActivities.get(token);
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

    ArrayList<ComponentCallbacks2> collectComponentCallbacksLocked(
            boolean allActivities, Configuration newConfig) {
        ArrayList<ComponentCallbacks2> callbacks
                = new ArrayList<ComponentCallbacks2>();

        if (mActivities.size() > 0) {
            Iterator<ActivityClientRecord> it = mActivities.values().iterator();
            while (it.hasNext()) {
                ActivityClientRecord ar = it.next();
                Activity a = ar.activity;
                if (a != null) {
                    Configuration thisConfig = applyConfigCompatMainThread(newConfig,
                            ar.packageInfo.mCompatibilityInfo.getIfNeeded());
                    if (!ar.activity.mFinished && (allActivities ||
                            (a != null && !ar.paused))) {
                        // If the activity is currently resumed, its configuration
                        // needs to change right now.
                        callbacks.add(a);
                    } else if (thisConfig != null) {
                        // Otherwise, we will tell it about the change
                        // the next time it is resumed or shown.  Note that
                        // the activity manager may, before then, decide the
                        // activity needs to be destroyed to handle its new
                        // configuration.
                        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Setting activity "
                                + ar.activityInfo.name + " newConfig=" + thisConfig);
                        ar.newConfig = thisConfig;
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
                Iterator<ProviderClientRecord> it = mLocalProviders.values().iterator();
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
            ComponentCallbacks2 cb, Configuration config) {
        // Only for Activity objects, check that they actually call up to their
        // superclass implementation.  ComponentCallbacks2 is an interface, so
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
                if ((~activity.mActivityInfo.getRealConfigChanged() & diff) == 0) {
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

    public final void applyConfigurationToResources(Configuration config) {
        synchronized (mPackages) {
            applyConfigurationToResourcesLocked(config, null);
        }
    }

    final boolean applyConfigurationToResourcesLocked(Configuration config,
            CompatibilityInfo compat) {
        if (mResConfiguration == null) {
            mResConfiguration = new Configuration();
        }
        if (!mResConfiguration.isOtherSeqNewer(config) && compat == null) {
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Skipping new config: curSeq="
                    + mResConfiguration.seq + ", newSeq=" + config.seq);
            return false;
        }
        int changes = mResConfiguration.updateFrom(config);
        DisplayMetrics dm = getDisplayMetricsLocked(compat, true);

        if (compat != null && (mResCompatibilityInfo == null ||
                !mResCompatibilityInfo.equals(compat))) {
            mResCompatibilityInfo = compat;
            changes |= ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_SCREEN_SIZE
                    | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        }

        // set it for java, this also affects newly created Resources
        if (config.locale != null) {
            Locale.setDefault(config.locale);
        }

        Resources.updateSystemConfiguration(config, dm, compat);

        ApplicationPackageManager.configurationChanged();
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
                r.updateConfiguration(config, dm, compat);
                //Slog.i(TAG, "Updated app resources " + v.getKey()
                //        + " " + r + ": " + r.getConfiguration());
            } else {
                //Slog.i(TAG, "Removing old resources " + v.getKey());
                it.remove();
            }
        }
        
        return changes != 0;
    }
    
    final void handleConfigurationChanged(Configuration config, CompatibilityInfo compat) {

        ArrayList<ComponentCallbacks2> callbacks = null;

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
        
            applyConfigurationToResourcesLocked(config, compat);
            
            if (mConfiguration == null) {
                mConfiguration = new Configuration();
            }
            if (!mConfiguration.isOtherSeqNewer(config) && compat == null) {
                return;
            }
            mConfiguration.updateFrom(config);
            if (mCompatConfiguration == null) {
                mCompatConfiguration = new Configuration();
            }
            mCompatConfiguration.setTo(mConfiguration);
            if (mResCompatibilityInfo != null && !mResCompatibilityInfo.supportsScreen()) {
                mResCompatibilityInfo.applyToConfiguration(mCompatConfiguration);
                config = mCompatConfiguration;
            }
            callbacks = collectComponentCallbacksLocked(false, config);
        }
        
        // Cleanup hardware accelerated stuff
        WindowManagerImpl.getDefault().trimLocalMemory();

        if (callbacks != null) {
            final int N = callbacks.size();
            for (int i=0; i<N; i++) {
                performConfigurationChanged(callbacks.get(i), config);
            }
        }
    }

    final void handleActivityConfigurationChanged(IBinder token) {
        ActivityClientRecord r = mActivities.get(token);
        if (r == null || r.activity == null) {
            return;
        }

        if (DEBUG_CONFIGURATION) Slog.v(TAG, "Handle activity config changed: "
                + r.activityInfo.name);
        
        performConfigurationChanged(r.activity, mCompatConfiguration);
    }

    final void handleProfilerControl(boolean start, ProfilerControlData pcd, int profileType) {
        if (start) {
            try {
                switch (profileType) {
                    case 1:
                        ViewDebug.startLooperProfiling(pcd.path, pcd.fd.getFileDescriptor());
                        break;
                    default:
                        Debug.startMethodTracing(pcd.path, pcd.fd.getFileDescriptor(),
                                8 * 1024 * 1024, 0);
                        break;
                }
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
            switch (profileType) {
                case 1:
                    ViewDebug.stopLooperProfiling();
                    break;
                default:
                    Debug.stopMethodTracing();
                    break;
                    
            }
        }
    }

    final void handleDumpHeap(boolean managed, DumpHeapData dhd) {
        if (managed) {
            try {
                Debug.dumpHprofData(dhd.path, dhd.fd.getFileDescriptor());
            } catch (IOException e) {
                Slog.w(TAG, "Managed heap dump failed on path " + dhd.path
                        + " -- can the process access this path?");
            } finally {
                try {
                    dhd.fd.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failure closing profile fd", e);
                }
            }
        } else {
            Debug.dumpNativeHeap(dhd.fd.getFileDescriptor());
        }
    }

    final void handleDispatchPackageBroadcast(int cmd, String[] packages) {
        boolean hasPkgInfo = false;
        if (packages != null) {
            for (int i=packages.length-1; i>=0; i--) {
                //Slog.i(TAG, "Cleaning old package: " + packages[i]);
                if (!hasPkgInfo) {
                    WeakReference<LoadedApk> ref;
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
        ApplicationPackageManager.handlePackageBroadcast(cmd, packages,
                hasPkgInfo);
    }
        
    final void handleLowMemory() {
        ArrayList<ComponentCallbacks2> callbacks;

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

    final void handleTrimMemory(int level) {
        WindowManagerImpl.getDefault().trimMemory(level);
        ArrayList<ComponentCallbacks2> callbacks;

        synchronized (mPackages) {
            callbacks = collectComponentCallbacksLocked(true, null);
        }

        final int N = callbacks.size();
        for (int i=0; i<N; i++) {
            callbacks.get(i).onTrimMemory(level);
        }
    }

    private void handleBindApplication(AppBindData data) {
        mBoundApplication = data;
        mConfiguration = new Configuration(data.config);
        mCompatConfiguration = new Configuration(data.config);

        // send up app name; do this *before* waiting for debugger
        Process.setArgV0(data.processName);
        android.ddm.DdmHandleAppName.setAppName(data.processName);

        // If the app is Honeycomb MR1 or earlier, switch its AsyncTask
        // implementation to use the pool executor.  Normally, we use the
        // serialized executor as the default. This has to happen in the
        // main thread so the main looper is set right.
        if (data.appInfo.targetSdkVersion <= 12) {
            AsyncTask.setDefaultExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }

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
        applyConfigurationToResourcesLocked(data.config, data.compatInfo);

        data.info = getPackageInfoNoCheck(data.appInfo, data.compatInfo);

        /**
         * For system applications on userdebug/eng builds, log stack
         * traces of disk and network access to dropbox for analysis.
         */
        if ((data.appInfo.flags &
             (ApplicationInfo.FLAG_SYSTEM |
              ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            StrictMode.conditionallyEnableDebugLogging();
        }

        /**
         * For apps targetting SDK Honeycomb or later, we don't allow
         * network usage on the main event loop / UI thread.
         *
         * Note to those grepping:  this is what ultimately throws
         * NetworkOnMainThreadException ...
         */
        if (data.appInfo.targetSdkVersion > 9) {
            StrictMode.enableDeathOnNetwork();
        }

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

        /**
         * Initialize the default http proxy in this process for the reasons we set the time zone.
         */
        IBinder b = ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        IConnectivityManager service = IConnectivityManager.Stub.asInterface(b);
        try {
            ProxyProperties proxyProperties = service.getProxy();
            Proxy.setHttpProxySystemProperty(proxyProperties);
        } catch (RemoteException e) {}

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
            instrApp.nativeLibraryDir = ii.nativeLibraryDir;
            LoadedApk pi = getPackageInfo(instrApp, data.compatInfo,
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

        if ((data.appInfo.flags&ApplicationInfo.FLAG_LARGE_HEAP) != 0) {
            dalvik.system.VMRuntime.getRuntime().clearGrowthLimit();
        }

        // If the app is being launched for full backup or restore, bring it up in
        // a restricted environment with the base application class.
        Application app = data.info.makeApplication(data.restrictedBackupMode, null);
        mInitialApplication = app;

        // don't bring up providers in restricted mode; they may depend on the
        // app's custom Application class
        if (!data.restrictedBackupMode){ 
            List<ProviderInfo> providers = data.providers;
            if (providers != null) {
                installContentProviders(app, providers);
                // For process that contains content providers, we want to
                // ensure that the JIT is enabled "at some point".
                mH.sendEmptyMessageDelayed(H.ENABLE_JIT, 10*1000);
            }
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

    private void installContentProviders(
            Context context, List<ProviderInfo> providers) {
        final ArrayList<IActivityManager.ContentProviderHolder> results =
            new ArrayList<IActivityManager.ContentProviderHolder>();

        Iterator<ProviderInfo> i = providers.iterator();
        while (i.hasNext()) {
            ProviderInfo cpi = i.next();
            StringBuilder buf = new StringBuilder(128);
            buf.append("Pub ");
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

    private IContentProvider getExistingProvider(Context context, String name) {
        synchronized(mProviderMap) {
            final ProviderClientRecord pr = mProviderMap.get(name);
            if (pr != null) {
                return pr.mProvider;
            }
            return null;
        }
    }

    private IContentProvider getProvider(Context context, String name) {
        IContentProvider existing = getExistingProvider(context, name);
        if (existing != null) {
            return existing;
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

    public final IContentProvider acquireExistingProvider(Context c, String name) {
        IContentProvider provider = getExistingProvider(c, name);
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
                if(localLOGV) Slog.v(TAG, "releaseProvider::Weird shouldn't be here");
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
        Iterator<ProviderClientRecord> iter = mProviderMap.values().iterator();
        while (iter.hasNext()) {
            ProviderClientRecord pr = iter.next();
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
            ProviderClientRecord pr = mProviderMap.get(name);
            if (pr.mProvider.asBinder() == provider.asBinder()) {
                Slog.i(TAG, "Removing dead content provider: " + name);
                ProviderClientRecord removed = mProviderMap.remove(name);
                if (removed != null) {
                    removed.mProvider.asBinder().unlinkToDeath(removed, 0);
                }
            }
        }
    }

    final void removeDeadProviderLocked(String name, IContentProvider provider) {
        ProviderClientRecord pr = mProviderMap.get(name);
        if (pr.mProvider.asBinder() == provider.asBinder()) {
            Slog.i(TAG, "Removing dead content provider: " + name);
            ProviderClientRecord removed = mProviderMap.remove(name);
            if (removed != null) {
                removed.mProvider.asBinder().unlinkToDeath(removed, 0);
            }
        }
    }

    private IContentProvider installProvider(Context context,
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
                    // Ignore
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
                if (false) Slog.v(
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
                ProviderClientRecord pr = new ProviderClientRecord(names[i], provider,
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
                        new ProviderClientRecord(null, provider, localProvider));
            }
        }

        return provider;
    }

    private void attach(boolean system) {
        sThreadLocal.set(this);
        mSystemThread = system;
        if (!system) {
            ViewRootImpl.addFirstDrawHandler(new Runnable() {
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
                // Ignore
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
        
        ViewRootImpl.addConfigCallback(new ComponentCallbacks2() {
            public void onConfigurationChanged(Configuration newConfig) {
                synchronized (mPackages) {
                    // We need to apply this change to the resources
                    // immediately, because upon returning the view
                    // hierarchy will be informed about it.
                    if (applyConfigurationToResourcesLocked(newConfig, null)) {
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
            public void onTrimMemory(int level) {
            }
        });
    }

    public static final ActivityThread systemMain() {
        HardwareRenderer.disable();
        ActivityThread thread = new ActivityThread();
        thread.attach(true);
        return thread;
    }

    public final void installSystemProviders(List<ProviderInfo> providers) {
        if (providers != null) {
            installContentProviders(mInitialApplication, providers);
        }
    }

    public int getIntCoreSetting(String key, int defaultValue) {
        synchronized (mPackages) {
            if (mCoreSettings != null) {
                return mCoreSettings.getInt(key, defaultValue);
            } else {
                return defaultValue;
            }
        }
    }

    public static void main(String[] args) {
        SamplingProfilerIntegration.start();

        // CloseGuard defaults to true and can be quite spammy.  We
        // disable it here, but selectively enable it later (via
        // StrictMode) on debug builds, but using DropBox, not logs.
        CloseGuard.setEnabled(false);

        Process.setArgV0("<pre-initialized>");

        Looper.prepareMainLooper();
        if (sMainThreadHandler == null) {
            sMainThreadHandler = new Handler();
        }

        ActivityThread thread = new ActivityThread();
        thread.attach(false);

        if (false) {
            Looper.myLooper().setMessageLogging(new
                    LogPrinter(Log.DEBUG, "ActivityThread"));
        }

        Looper.loop();

        throw new RuntimeException("Main thread loop unexpectedly exited");
    }
}
