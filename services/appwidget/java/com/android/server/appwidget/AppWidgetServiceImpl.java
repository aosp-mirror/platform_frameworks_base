/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.appwidget;

import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.Intent.FilterComparison;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Point;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.util.Xml;
import android.view.Display;
import android.view.WindowManager;
import android.widget.RemoteViews;

import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.widget.IRemoteViewsAdapterConnection;
import com.android.internal.widget.IRemoteViewsFactory;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;

import libcore.util.MutableInt;

class AppWidgetServiceImpl {

    private static final String KEYGUARD_HOST_PACKAGE = "com.android.keyguard";
    private static final int KEYGUARD_HOST_ID = 0x4b455947;
    private static final String TAG = "AppWidgetServiceImpl";
    private static final String SETTINGS_FILENAME = "appwidgets.xml";
    private static final int MIN_UPDATE_PERIOD = 30 * 60 * 1000; // 30 minutes
    private static final int CURRENT_VERSION = 1; // Bump if the stored widgets need to be upgraded.
    private static final int WIDGET_STATE_VERSION = 1;  // version of backed-up widget state

    private static boolean DBG = true;
    private static boolean DEBUG_BACKUP = DBG || true;

    /*
     * When identifying a Host or Provider based on the calling process, use the uid field. When
     * identifying a Host or Provider based on a package manager broadcast, use the package given.
     */

    static class Provider {
        int uid;
        AppWidgetProviderInfo info;
        ArrayList<AppWidgetId> instances = new ArrayList<AppWidgetId>();
        PendingIntent broadcast;
        boolean zombie; // if we're in safe mode, don't prune this just because nobody references it

        int tag; // for use while saving state (the index)

        // is there an instance of this provider hosted by the given app?
        public boolean isHostedBy(String packageName) {
            final int N = instances.size();
            for (int i = 0; i < N; i++) {
                AppWidgetId id = instances.get(i);
                if (packageName.equals(id.host.packageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "Provider{" + ((info == null) ? "null" : info.provider)
                    + (zombie ? " Z" : "")
                    + '}';
        }
    }

    static class Host {
        int uid;
        int hostId;
        String packageName;
        ArrayList<AppWidgetId> instances = new ArrayList<AppWidgetId>();
        IAppWidgetHost callbacks;
        boolean zombie; // if we're in safe mode, don't prune this just because nobody references it

        int tag; // for use while saving state (the index)

        boolean uidMatches(int callingUid) {
            if (UserHandle.getAppId(callingUid) == Process.myUid()) {
                // For a host that's in the system process, ignore the user id
                return UserHandle.isSameApp(this.uid, callingUid);
            } else {
                return this.uid == callingUid;
            }
        }

        boolean hostsPackage(String pkg) {
            final int N = instances.size();
            for (int i = 0; i < N; i++) {
                Provider p = instances.get(i).provider;
                if (p.info != null && pkg.equals(p.info.provider.getPackageName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "Host{" + packageName + ":" + hostId + '}';
        }
    }

    static class AppWidgetId {
        int appWidgetId;
        int restoredId;  // tracking & remapping any restored state
        Provider provider;
        RemoteViews views;
        Bundle options;
        Host host;

        @Override
        public String toString() {
            return "AppWidgetId{" + appWidgetId + ':' + host + ':' + provider + '}';
        }
    }

    AppWidgetId findRestoredWidgetLocked(int restoredId, Host host, Provider p) {
        if (DEBUG_BACKUP) {
            Slog.i(TAG, "Find restored widget: id=" + restoredId
                    + " host=" + host + " provider=" + p);
        }

        if (p == null || host == null) {
            return null;
        }

        final int N = mAppWidgetIds.size();
        for (int i = 0; i < N; i++) {
            AppWidgetId widget = mAppWidgetIds.get(i);
            if (widget.restoredId == restoredId
                    && widget.host.hostId == host.hostId
                    && widget.host.packageName.equals(host.packageName)
                    && widget.provider.info.provider.equals(p.info.provider)) {
                if (DEBUG_BACKUP) {
                    Slog.i(TAG, "   Found at " + i + " : " + widget);
                }
                return widget;
            }
        }
        return null;
    }

    /**
     * Acts as a proxy between the ServiceConnection and the RemoteViewsAdapterConnection. This
     * needs to be a static inner class since a reference to the ServiceConnection is held globally
     * and may lead us to leak AppWidgetService instances (if there were more than one).
     */
    static class ServiceConnectionProxy implements ServiceConnection {
        private final IBinder mConnectionCb;

        ServiceConnectionProxy(Pair<Integer, Intent.FilterComparison> key, IBinder connectionCb) {
            mConnectionCb = connectionCb;
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            final IRemoteViewsAdapterConnection cb = IRemoteViewsAdapterConnection.Stub
                    .asInterface(mConnectionCb);
            try {
                cb.onServiceConnected(service);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            disconnect();
        }

        public void disconnect() {
            final IRemoteViewsAdapterConnection cb = IRemoteViewsAdapterConnection.Stub
                    .asInterface(mConnectionCb);
            try {
                cb.onServiceDisconnected();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Manages active connections to RemoteViewsServices
    private final HashMap<Pair<Integer, FilterComparison>, ServiceConnection> mBoundRemoteViewsServices = new HashMap<Pair<Integer, FilterComparison>, ServiceConnection>();
    // Manages persistent references to RemoteViewsServices from different App Widgets
    private final HashMap<FilterComparison, HashSet<Integer>> mRemoteViewsServicesAppWidgets = new HashMap<FilterComparison, HashSet<Integer>>();

    final Context mContext;
    final IPackageManager mPm;
    final AlarmManager mAlarmManager;
    final ArrayList<Provider> mInstalledProviders = new ArrayList<Provider>();
    final int mUserId;
    final boolean mHasFeature;

    Locale mLocale;
    int mNextAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID + 1;
    final ArrayList<AppWidgetId> mAppWidgetIds = new ArrayList<AppWidgetId>();
    final ArrayList<Host> mHosts = new ArrayList<Host>();
    // set of package names
    final HashSet<String> mPackagesWithBindWidgetPermission = new HashSet<String>();
    boolean mSafeMode;
    boolean mStateLoaded;
    int mMaxWidgetBitmapMemory;

    // Map old (restored) widget IDs to new AppWidgetId instances.  This object is used
    // as the lock around manipulation of the overall restored-widget state, just as
    // mAppWidgetIds is used as the lock object around all "live" widget state
    // manipulations.  Methods that must be called with this lock held are decorated
    // with the suffix "Lr".
    //
    // In cases when both of those locks must be held concurrently, mRestoredWidgetIds
    // must be acquired *first.*
    private final SparseArray<AppWidgetId> mRestoredWidgetIds = new SparseArray<AppWidgetId>();

    // We need to make sure to wipe the pre-restore widget state only once for
    // a given package.  Keep track of what we've done so far here; the list is
    // cleared at the start of every system restore pass, but preserved through
    // any install-time restore operations.
    HashSet<String> mPrunedApps = new HashSet<String>();

    private final Handler mSaveStateHandler;

    // These are for debugging only -- widgets are going missing in some rare instances
    ArrayList<Provider> mDeletedProviders = new ArrayList<Provider>();
    ArrayList<Host> mDeletedHosts = new ArrayList<Host>();

    AppWidgetServiceImpl(Context context, int userId, Handler saveStateHandler) {
        mContext = context;
        mPm = AppGlobals.getPackageManager();
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mUserId = userId;
        mSaveStateHandler = saveStateHandler;
        mHasFeature = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_APP_WIDGETS);
        computeMaximumWidgetBitmapMemory();
    }

    void computeMaximumWidgetBitmapMemory() {
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        // Cap memory usage at 1.5 times the size of the display
        // 1.5 * 4 bytes/pixel * w * h ==> 6 * w * h
        mMaxWidgetBitmapMemory = 6 * size.x * size.y;
    }

    public void systemReady(boolean safeMode) {
        mSafeMode = safeMode;

        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
        }
    }

    private void log(String msg) {
        Slog.i(TAG, "u=" + mUserId + ": " + msg);
    }

    void onConfigurationChanged() {
        if (DBG) log("Got onConfigurationChanged()");
        Locale revised = Locale.getDefault();
        if (revised == null || mLocale == null || !(revised.equals(mLocale))) {
            mLocale = revised;

            synchronized (mAppWidgetIds) {
                ensureStateLoadedLocked();
                // Note: updateProvidersForPackageLocked() may remove providers, so we must copy the
                // list of installed providers and skip providers that we don't need to update.
                // Also note that remove the provider does not clear the Provider component data.
                ArrayList<Provider> installedProviders =
                        new ArrayList<Provider>(mInstalledProviders);
                HashSet<ComponentName> removedProviders = new HashSet<ComponentName>();
                int N = installedProviders.size();
                for (int i = N - 1; i >= 0; i--) {
                    Provider p = installedProviders.get(i);
                    ComponentName cn = p.info.provider;
                    if (!removedProviders.contains(cn)) {
                        updateProvidersForPackageLocked(cn.getPackageName(), removedProviders);
                    }
                }
                saveStateAsync();
            }
        }
    }

    void onBroadcastReceived(Intent intent) {
        if (DBG) log("onBroadcast " + intent);
        final String action = intent.getAction();
        boolean added = false;
        boolean changed = false;
        boolean providersModified = false;
        String pkgList[] = null;
        if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
            pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            added = true;
        } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
            pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            added = false;
        } else {
            Uri uri = intent.getData();
            if (uri == null) {
                return;
            }
            String pkgName = uri.getSchemeSpecificPart();
            if (pkgName == null) {
                return;
            }
            pkgList = new String[] { pkgName };
            added = Intent.ACTION_PACKAGE_ADDED.equals(action);
            changed = Intent.ACTION_PACKAGE_CHANGED.equals(action);
        }
        if (pkgList == null || pkgList.length == 0) {
            return;
        }
        if (added || changed) {
            synchronized (mAppWidgetIds) {
                ensureStateLoadedLocked();
                Bundle extras = intent.getExtras();
                if (changed
                        || (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false))) {
                    for (String pkgName : pkgList) {
                        // The package was just upgraded
                        providersModified |= updateProvidersForPackageLocked(pkgName, null);
                    }
                } else {
                    // The package was just added.  Fix up the providers...
                    for (String pkgName : pkgList) {
                        providersModified |= addProvidersForPackageLocked(pkgName);
                    }
                    // ...and see if these are hosts we've been awaiting
                    for (String pkg : pkgList) {
                        try {
                            int uid = getUidForPackage(pkg);
                            resolveHostUidLocked(pkg, uid);
                        } catch (NameNotFoundException e) {
                            // shouldn't happen; we just installed it!
                        }
                    }
                }
                saveStateAsync();
            }
        } else {
            Bundle extras = intent.getExtras();
            if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                // The package is being updated. We'll receive a PACKAGE_ADDED shortly.
            } else {
                synchronized (mAppWidgetIds) {
                    ensureStateLoadedLocked();
                    for (String pkgName : pkgList) {
                        providersModified |= removeProvidersForPackageLocked(pkgName);
                        saveStateAsync();
                    }
                }
            }
        }

        if (providersModified) {
            // If the set of providers has been modified, notify each active AppWidgetHost
            synchronized (mAppWidgetIds) {
                ensureStateLoadedLocked();
                notifyHostsForProvidersChangedLocked();
            }
        }
    }

    void resolveHostUidLocked(String pkg, int uid) {
        final int N = mHosts.size();
        for (int i = 0; i < N; i++) {
            Host h = mHosts.get(i);
            if (h.uid == -1 && pkg.equals(h.packageName)) {
                if (DEBUG_BACKUP) {
                    Slog.i(TAG, "host " + pkg + ":" + h.hostId + " resolved to uid " + uid);
                }
                h.uid = uid;
            }
        }
    }

    private void dumpProvider(Provider p, int index, PrintWriter pw) {
        AppWidgetProviderInfo info = p.info;
        pw.print("  ["); pw.print(index); pw.print("] provider ");
                pw.print(info.provider.flattenToShortString());
                pw.println(':');
        pw.print("    min=("); pw.print(info.minWidth);
                pw.print("x"); pw.print(info.minHeight);
        pw.print(")   minResize=("); pw.print(info.minResizeWidth);
                pw.print("x"); pw.print(info.minResizeHeight);
                pw.print(") updatePeriodMillis=");
                pw.print(info.updatePeriodMillis);
                pw.print(" resizeMode=");
                pw.print(info.resizeMode);
                pw.print(info.widgetCategory);
                pw.print(" autoAdvanceViewId=");
                pw.print(info.autoAdvanceViewId);
                pw.print(" initialLayout=#");
                pw.print(Integer.toHexString(info.initialLayout));
                pw.print(" uid="); pw.print(p.uid);
                pw.print(" zombie="); pw.println(p.zombie);
    }

    private void dumpHost(Host host, int index, PrintWriter pw) {
        pw.print("  ["); pw.print(index); pw.print("] hostId=");
                pw.print(host.hostId); pw.print(' ');
                pw.print(host.packageName); pw.print('/');
        pw.print(host.uid); pw.println(':');
        pw.print("    callbacks="); pw.println(host.callbacks);
        pw.print("    instances.size="); pw.print(host.instances.size());
                pw.print(" zombie="); pw.println(host.zombie);
    }

    private void dumpAppWidgetId(AppWidgetId id, int index, PrintWriter pw) {
        pw.print("  ["); pw.print(index); pw.print("] id=");
                pw.println(id.appWidgetId);
        pw.print("    hostId=");
                pw.print(id.host.hostId); pw.print(' ');
                pw.print(id.host.packageName); pw.print('/');
                pw.println(id.host.uid);
        if (id.provider != null) {
            pw.print("    provider=");
                    pw.println(id.provider.info.provider.flattenToShortString());
        }
        if (id.host != null) {
            pw.print("    host.callbacks="); pw.println(id.host.callbacks);
        }
        if (id.views != null) {
            pw.print("    views="); pw.println(id.views);
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (mAppWidgetIds) {
            int N = mInstalledProviders.size();
            pw.println("Providers:");
            for (int i=0; i<N; i++) {
                dumpProvider(mInstalledProviders.get(i), i, pw);
            }

            N = mAppWidgetIds.size();
            pw.println(" ");
            pw.println("AppWidgetIds:");
            for (int i=0; i<N; i++) {
                dumpAppWidgetId(mAppWidgetIds.get(i), i, pw);
            }

            N = mHosts.size();
            pw.println(" ");
            pw.println("Hosts:");
            for (int i=0; i<N; i++) {
                dumpHost(mHosts.get(i), i, pw);
            }

            N = mDeletedProviders.size();
            pw.println(" ");
            pw.println("Deleted Providers:");
            for (int i=0; i<N; i++) {
                dumpProvider(mDeletedProviders.get(i), i, pw);
            }

            N = mDeletedHosts.size();
            pw.println(" ");
            pw.println("Deleted Hosts:");
            for (int i=0; i<N; i++) {
                dumpHost(mDeletedHosts.get(i), i, pw);
            }
        }
    }

    private void ensureStateLoadedLocked() {
        if (!mStateLoaded) {
            if (!mHasFeature) {
                return;
            }
            loadWidgetProviderListLocked();
            loadStateLocked();
            mStateLoaded = true;
        }
    }

    public int allocateAppWidgetId(String packageName, int hostId) {
        int callingUid = enforceSystemOrCallingUid(packageName);
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return -1;
            }
            ensureStateLoadedLocked();
            int appWidgetId = mNextAppWidgetId++;

            Host host = lookupOrAddHostLocked(callingUid, packageName, hostId);

            AppWidgetId id = new AppWidgetId();
            id.appWidgetId = appWidgetId;
            id.host = host;

            host.instances.add(id);
            mAppWidgetIds.add(id);

            saveStateAsync();
            if (DBG) log("Allocating AppWidgetId for " + packageName + " host=" + hostId
                    + " id=" + appWidgetId);
            return appWidgetId;
        }
    }

    public void deleteAppWidgetId(int appWidgetId) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return;
            }
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null) {
                deleteAppWidgetLocked(id);
                saveStateAsync();
            }
        }
    }

    public void deleteHost(int hostId) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return;
            }
            ensureStateLoadedLocked();
            int callingUid = Binder.getCallingUid();
            Host host = lookupHostLocked(callingUid, hostId);
            if (host != null) {
                deleteHostLocked(host);
                saveStateAsync();
            }
        }
    }

    public void deleteAllHosts() {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return;
            }
            ensureStateLoadedLocked();
            int callingUid = Binder.getCallingUid();
            final int N = mHosts.size();
            boolean changed = false;
            for (int i = N - 1; i >= 0; i--) {
                Host host = mHosts.get(i);
                if (host.uidMatches(callingUid)) {
                    deleteHostLocked(host);
                    changed = true;
                }
            }
            if (changed) {
                saveStateAsync();
            }
        }
    }

    void deleteHostLocked(Host host) {
        if (DBG) log("Deleting host " + host);
        final int N = host.instances.size();
        for (int i = N - 1; i >= 0; i--) {
            AppWidgetId id = host.instances.get(i);
            deleteAppWidgetLocked(id);
        }
        host.instances.clear();
        mHosts.remove(host);
        mDeletedHosts.add(host);
        // it's gone or going away, abruptly drop the callback connection
        host.callbacks = null;
    }

    void deleteAppWidgetLocked(AppWidgetId id) {
        // We first unbind all services that are bound to this id
        unbindAppWidgetRemoteViewsServicesLocked(id);

        Host host = id.host;
        host.instances.remove(id);
        pruneHostLocked(host);

        mAppWidgetIds.remove(id);

        Provider p = id.provider;
        if (p != null) {
            p.instances.remove(id);
            if (!p.zombie) {
                // send the broacast saying that this appWidgetId has been deleted
                Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_DELETED);
                intent.setComponent(p.info.provider);
                intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id.appWidgetId);
                mContext.sendBroadcastAsUser(intent, new UserHandle(mUserId));
                if (p.instances.size() == 0) {
                    // cancel the future updates
                    cancelBroadcasts(p);

                    // send the broacast saying that the provider is not in use any more
                    intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_DISABLED);
                    intent.setComponent(p.info.provider);
                    mContext.sendBroadcastAsUser(intent, new UserHandle(mUserId));
                }
            }
        }
    }

    void cancelBroadcasts(Provider p) {
        if (DBG) log("cancelBroadcasts for " + p);
        if (p.broadcast != null) {
            mAlarmManager.cancel(p.broadcast);
            long token = Binder.clearCallingIdentity();
            try {
                p.broadcast.cancel();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            p.broadcast = null;
        }
    }

    private void bindAppWidgetIdImpl(int appWidgetId, ComponentName provider, Bundle options) {
        if (DBG) log("bindAppWidgetIdImpl appwid=" + appWidgetId
                + " provider=" + provider);
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mAppWidgetIds) {
                if (!mHasFeature) {
                    return;
                }
                options = cloneIfLocalBinder(options);
                ensureStateLoadedLocked();
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
                if (id == null) {
                    throw new IllegalArgumentException("bad appWidgetId");
                }
                if (id.provider != null) {
                    throw new IllegalArgumentException("appWidgetId " + appWidgetId
                            + " already bound to " + id.provider.info.provider);
                }
                Provider p = lookupProviderLocked(provider);
                if (p == null) {
                    throw new IllegalArgumentException("not a appwidget provider: " + provider);
                }
                if (p.zombie) {
                    throw new IllegalArgumentException("can't bind to a 3rd party provider in"
                            + " safe mode: " + provider);
                }

                id.provider = p;
                if (options == null) {
                    options = new Bundle();
                }
                id.options = options;

                // We need to provide a default value for the widget category if it is not specified
                if (!options.containsKey(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY)) {
                    options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
                }

                p.instances.add(id);
                int instancesSize = p.instances.size();
                if (instancesSize == 1) {
                    // tell the provider that it's ready
                    sendEnableIntentLocked(p);
                }

                // send an update now -- We need this update now, and just for this appWidgetId.
                // It's less critical when the next one happens, so when we schedule the next one,
                // we add updatePeriodMillis to its start time. That time will have some slop,
                // but that's okay.
                sendUpdateIntentLocked(p, new int[] { appWidgetId });

                // schedule the future updates
                registerForBroadcastsLocked(p, getAppWidgetIds(p));
                saveStateAsync();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void bindAppWidgetId(int appWidgetId, ComponentName provider, Bundle options) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BIND_APPWIDGET,
            "bindAppWidgetId appWidgetId=" + appWidgetId + " provider=" + provider);
        bindAppWidgetIdImpl(appWidgetId, provider, options);
    }

    public boolean bindAppWidgetIdIfAllowed(
            String packageName, int appWidgetId, ComponentName provider, Bundle options) {
        if (!mHasFeature) {
            return false;
        }
        try {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BIND_APPWIDGET, null);
        } catch (SecurityException se) {
            if (!callerHasBindAppWidgetPermission(packageName)) {
                return false;
            }
        }
        bindAppWidgetIdImpl(appWidgetId, provider, options);
        return true;
    }

    private boolean callerHasBindAppWidgetPermission(String packageName) {
        int callingUid = Binder.getCallingUid();
        try {
            if (!UserHandle.isSameApp(callingUid, getUidForPackage(packageName))) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            return mPackagesWithBindWidgetPermission.contains(packageName);
        }
    }

    public boolean hasBindAppWidgetPermission(String packageName) {
        if (!mHasFeature) {
            return false;
        }
        mContext.enforceCallingPermission(
                android.Manifest.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS,
                "hasBindAppWidgetPermission packageName=" + packageName);

        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            return mPackagesWithBindWidgetPermission.contains(packageName);
        }
    }

    public void setBindAppWidgetPermission(String packageName, boolean permission) {
        if (!mHasFeature) {
            return;
        }
        mContext.enforceCallingPermission(
                android.Manifest.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS,
                "setBindAppWidgetPermission packageName=" + packageName);

        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            if (permission) {
                mPackagesWithBindWidgetPermission.add(packageName);
            } else {
                mPackagesWithBindWidgetPermission.remove(packageName);
            }
            saveStateAsync();
        }
    }

    // Binds to a specific RemoteViewsService
    public void bindRemoteViewsService(int appWidgetId, Intent intent, IBinder connection) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return;
            }
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id == null) {
                throw new IllegalArgumentException("bad appWidgetId");
            }
            final ComponentName componentName = intent.getComponent();
            try {
                final ServiceInfo si = mPm.getServiceInfo(componentName,
                        PackageManager.GET_PERMISSIONS, mUserId);
                if (!android.Manifest.permission.BIND_REMOTEVIEWS.equals(si.permission)) {
                    throw new SecurityException("Selected service does not require "
                            + android.Manifest.permission.BIND_REMOTEVIEWS + ": " + componentName);
                }
            } catch (RemoteException e) {
                throw new IllegalArgumentException("Unknown component " + componentName);
            }

            // If there is already a connection made for this service intent, then disconnect from
            // that first. (This does not allow multiple connections to the same service under
            // the same key)
            ServiceConnectionProxy conn = null;
            FilterComparison fc = new FilterComparison(intent);
            Pair<Integer, FilterComparison> key = Pair.create(appWidgetId, fc);
            if (mBoundRemoteViewsServices.containsKey(key)) {
                conn = (ServiceConnectionProxy) mBoundRemoteViewsServices.get(key);
                conn.disconnect();
                mContext.unbindService(conn);
                mBoundRemoteViewsServices.remove(key);
            }

            int userId = UserHandle.getUserId(id.provider.uid);
            if (userId != mUserId) {
                Slog.w(TAG, "AppWidgetServiceImpl of user " + mUserId
                        + " binding to provider on user " + userId);
            }
            // Bind to the RemoteViewsService (which will trigger a callback to the
            // RemoteViewsAdapter.onServiceConnected())
            final long token = Binder.clearCallingIdentity();
            try {
                conn = new ServiceConnectionProxy(key, connection);
                mContext.bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE,
                        new UserHandle(userId));
                mBoundRemoteViewsServices.put(key, conn);
            } finally {
                Binder.restoreCallingIdentity(token);
            }

            // Add it to the mapping of RemoteViewsService to appWidgetIds so that we can determine
            // when we can call back to the RemoteViewsService later to destroy associated
            // factories.
            incrementAppWidgetServiceRefCount(appWidgetId, fc);
        }
    }

    // Unbinds from a specific RemoteViewsService
    public void unbindRemoteViewsService(int appWidgetId, Intent intent) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return;
            }
            ensureStateLoadedLocked();
            // Unbind from the RemoteViewsService (which will trigger a callback to the bound
            // RemoteViewsAdapter)
            Pair<Integer, FilterComparison> key = Pair.create(appWidgetId, new FilterComparison(
                    intent));
            if (mBoundRemoteViewsServices.containsKey(key)) {
                // We don't need to use the appWidgetId until after we are sure there is something
                // to unbind. Note that this may mask certain issues with apps calling unbind()
                // more than necessary.
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
                if (id == null) {
                    throw new IllegalArgumentException("bad appWidgetId");
                }

                ServiceConnectionProxy conn = (ServiceConnectionProxy) mBoundRemoteViewsServices
                        .get(key);
                conn.disconnect();
                mContext.unbindService(conn);
                mBoundRemoteViewsServices.remove(key);
            }
        }
    }

    // Unbinds from a RemoteViewsService when we delete an app widget
    private void unbindAppWidgetRemoteViewsServicesLocked(AppWidgetId id) {
        int appWidgetId = id.appWidgetId;
        // Unbind all connections to Services bound to this AppWidgetId
        Iterator<Pair<Integer, Intent.FilterComparison>> it = mBoundRemoteViewsServices.keySet()
                .iterator();
        while (it.hasNext()) {
            final Pair<Integer, Intent.FilterComparison> key = it.next();
            if (key.first.intValue() == appWidgetId) {
                final ServiceConnectionProxy conn = (ServiceConnectionProxy) mBoundRemoteViewsServices
                        .get(key);
                conn.disconnect();
                mContext.unbindService(conn);
                it.remove();
            }
        }

        // Check if we need to destroy any services (if no other app widgets are
        // referencing the same service)
        decrementAppWidgetServiceRefCount(id);
    }

    // Destroys the cached factory on the RemoteViewsService's side related to the specified intent
    private void destroyRemoteViewsService(final Intent intent, AppWidgetId id) {
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final IRemoteViewsFactory cb = IRemoteViewsFactory.Stub.asInterface(service);
                try {
                    cb.onDestroy(intent);
                } catch (RemoteException e) {
                    e.printStackTrace();
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
                mContext.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(android.content.ComponentName name) {
                // Do nothing
            }
        };

        int userId = UserHandle.getUserId(id.provider.uid);
        // Bind to the service and remove the static intent->factory mapping in the
        // RemoteViewsService.
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE,
                    new UserHandle(userId));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Adds to the ref-count for a given RemoteViewsService intent
    private void incrementAppWidgetServiceRefCount(int appWidgetId, FilterComparison fc) {
        HashSet<Integer> appWidgetIds = null;
        if (mRemoteViewsServicesAppWidgets.containsKey(fc)) {
            appWidgetIds = mRemoteViewsServicesAppWidgets.get(fc);
        } else {
            appWidgetIds = new HashSet<Integer>();
            mRemoteViewsServicesAppWidgets.put(fc, appWidgetIds);
        }
        appWidgetIds.add(appWidgetId);
    }

    // Subtracts from the ref-count for a given RemoteViewsService intent, prompting a delete if
    // the ref-count reaches zero.
    private void decrementAppWidgetServiceRefCount(AppWidgetId id) {
        Iterator<FilterComparison> it = mRemoteViewsServicesAppWidgets.keySet().iterator();
        while (it.hasNext()) {
            final FilterComparison key = it.next();
            final HashSet<Integer> ids = mRemoteViewsServicesAppWidgets.get(key);
            if (ids.remove(id.appWidgetId)) {
                // If we have removed the last app widget referencing this service, then we
                // should destroy it and remove it from this set
                if (ids.isEmpty()) {
                    destroyRemoteViewsService(key.getIntent(), id);
                    it.remove();
                }
            }
        }
    }

    public AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return null;
            }
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null && id.provider != null && !id.provider.zombie) {
                return cloneIfLocalBinder(id.provider.info);
            }
            return null;
        }
    }

    public RemoteViews getAppWidgetViews(int appWidgetId) {
        if (DBG) log("getAppWidgetViews id=" + appWidgetId);
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return null;
            }
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null) {
                return cloneIfLocalBinder(id.views);
            }
            if (DBG) log("   couldn't find appwidgetid");
            return null;
        }
    }

    public List<AppWidgetProviderInfo> getInstalledProviders(int categoryFilter) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return new ArrayList<AppWidgetProviderInfo>(0);
            }
            ensureStateLoadedLocked();
            final int N = mInstalledProviders.size();
            ArrayList<AppWidgetProviderInfo> result = new ArrayList<AppWidgetProviderInfo>(N);
            for (int i = 0; i < N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (!p.zombie && (p.info.widgetCategory & categoryFilter) != 0) {
                    result.add(cloneIfLocalBinder(p.info));
                }
            }
            return result;
        }
    }

    public void updateAppWidgetIds(int[] appWidgetIds, RemoteViews views) {
        if (!mHasFeature) {
            return;
        }
        if (appWidgetIds == null) {
            return;
        }
        if (DBG) log("updateAppWidgetIds views: " + views);
        int bitmapMemoryUsage = 0;
        if (views != null) {
            bitmapMemoryUsage = views.estimateMemoryUsage();
        }
        if (bitmapMemoryUsage > mMaxWidgetBitmapMemory) {
            throw new IllegalArgumentException("RemoteViews for widget update exceeds maximum" +
                    " bitmap memory usage (used: " + bitmapMemoryUsage + ", max: " +
                    mMaxWidgetBitmapMemory + ") The total memory cannot exceed that required to" +
                    " fill the device's screen once.");
        }

        if (appWidgetIds.length == 0) {
            return;
        }
        final int N = appWidgetIds.length;

        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            for (int i = 0; i < N; i++) {
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetIds[i]);
                updateAppWidgetInstanceLocked(id, views);
            }
        }
    }

    private void saveStateAsync() {
        mSaveStateHandler.post(mSaveStateRunnable);
    }

    private final Runnable mSaveStateRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mAppWidgetIds) {
                ensureStateLoadedLocked();
                saveStateLocked();
            }
        }
    };

    public void updateAppWidgetOptions(int appWidgetId, Bundle options) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return;
            }
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);

            if (id == null) {
                return;
            }

            Provider p = id.provider;
            // Merge the options
            id.options.putAll(cloneIfLocalBinder(options));

            // send the broacast saying that this appWidgetId has been deleted
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED);
            intent.setComponent(p.info.provider);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id.appWidgetId);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, id.options);
            mContext.sendBroadcastAsUser(intent, new UserHandle(mUserId));
            saveStateAsync();
        }
    }

    public Bundle getAppWidgetOptions(int appWidgetId) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return Bundle.EMPTY;
            }
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null && id.options != null) {
                return cloneIfLocalBinder(id.options);
            } else {
                return Bundle.EMPTY;
            }
        }
    }

    public void partiallyUpdateAppWidgetIds(int[] appWidgetIds, RemoteViews views) {
        if (!mHasFeature) {
            return;
        }
        if (appWidgetIds == null) {
            return;
        }
        if (appWidgetIds.length == 0) {
            return;
        }
        final int N = appWidgetIds.length;

        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            for (int i = 0; i < N; i++) {
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetIds[i]);
                if (id == null) {
                    Slog.w(TAG, "widget id " + appWidgetIds[i] + " not found!");
                } else if (id.views != null) {
                    // Only trigger a partial update for a widget if it has received a full update
                    updateAppWidgetInstanceLocked(id, views, true);
                }
            }
        }
    }

    public void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId) {
        if (!mHasFeature) {
            return;
        }
        if (appWidgetIds == null) {
            return;
        }
        if (appWidgetIds.length == 0) {
            return;
        }
        final int N = appWidgetIds.length;

        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            for (int i = 0; i < N; i++) {
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetIds[i]);
                notifyAppWidgetViewDataChangedInstanceLocked(id, viewId);
            }
        }
    }

    public void updateAppWidgetProvider(ComponentName provider, RemoteViews views) {
        if (!mHasFeature) {
            return;
        }
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            Provider p = lookupProviderLocked(provider);
            if (p == null) {
                Slog.w(TAG, "updateAppWidgetProvider: provider doesn't exist: " + provider);
                return;
            }
            ArrayList<AppWidgetId> instances = p.instances;
            final int callingUid = Binder.getCallingUid();
            final int N = instances.size();
            for (int i = 0; i < N; i++) {
                AppWidgetId id = instances.get(i);
                if (canAccessAppWidgetId(id, callingUid)) {
                    updateAppWidgetInstanceLocked(id, views);
                }
            }
        }
    }

    void updateAppWidgetInstanceLocked(AppWidgetId id, RemoteViews views) {
        updateAppWidgetInstanceLocked(id, views, false);
    }

    void updateAppWidgetInstanceLocked(AppWidgetId id, RemoteViews views, boolean isPartialUpdate) {
        // allow for stale appWidgetIds and other badness
        // lookup also checks that the calling process can access the appWidgetId
        // drop unbound appWidgetIds (shouldn't be possible under normal circumstances)
        if (id != null && id.provider != null && !id.provider.zombie && !id.host.zombie) {

            if (!isPartialUpdate) {
                // For a full update we replace the RemoteViews completely.
                id.views = views;
            } else {
                // For a partial update, we merge the new RemoteViews with the old.
                id.views.mergeRemoteViews(views);
            }

            // is anyone listening?
            if (id.host.callbacks != null) {
                try {
                    // the lock is held, but this is a oneway call
                    id.host.callbacks.updateAppWidget(id.appWidgetId, views, mUserId);
                } catch (RemoteException e) {
                    // It failed; remove the callback. No need to prune because
                    // we know that this host is still referenced by this instance.
                    id.host.callbacks = null;
                }
            }
        }
    }

    void notifyAppWidgetViewDataChangedInstanceLocked(AppWidgetId id, int viewId) {
        // allow for stale appWidgetIds and other badness
        // lookup also checks that the calling process can access the appWidgetId
        // drop unbound appWidgetIds (shouldn't be possible under normal circumstances)
        if (id != null && id.provider != null && !id.provider.zombie && !id.host.zombie) {
            // is anyone listening?
            if (id.host.callbacks != null) {
                try {
                    // the lock is held, but this is a oneway call
                    id.host.callbacks.viewDataChanged(id.appWidgetId, viewId, mUserId);
                } catch (RemoteException e) {
                    // It failed; remove the callback. No need to prune because
                    // we know that this host is still referenced by this instance.
                    id.host.callbacks = null;
                }
            }

            // If the host is unavailable, then we call the associated
            // RemoteViewsFactory.onDataSetChanged() directly
            if (id.host.callbacks == null) {
                Set<FilterComparison> keys = mRemoteViewsServicesAppWidgets.keySet();
                for (FilterComparison key : keys) {
                    if (mRemoteViewsServicesAppWidgets.get(key).contains(id.appWidgetId)) {
                        Intent intent = key.getIntent();

                        final ServiceConnection conn = new ServiceConnection() {
                            @Override
                            public void onServiceConnected(ComponentName name, IBinder service) {
                                IRemoteViewsFactory cb = IRemoteViewsFactory.Stub
                                        .asInterface(service);
                                try {
                                    cb.onDataSetChangedAsync();
                                } catch (RemoteException e) {
                                    e.printStackTrace();
                                } catch (RuntimeException e) {
                                    e.printStackTrace();
                                }
                                mContext.unbindService(this);
                            }

                            @Override
                            public void onServiceDisconnected(android.content.ComponentName name) {
                                // Do nothing
                            }
                        };

                        int userId = UserHandle.getUserId(id.provider.uid);
                        // Bind to the service and call onDataSetChanged()
                        final long token = Binder.clearCallingIdentity();
                        try {
                            mContext.bindServiceAsUser(intent, conn, Context.BIND_AUTO_CREATE,
                                    new UserHandle(userId));
                        } finally {
                            Binder.restoreCallingIdentity(token);
                        }
                    }
                }
            }
        }
    }

    private boolean isLocalBinder() {
        return Process.myPid() == Binder.getCallingPid();
    }

    private RemoteViews cloneIfLocalBinder(RemoteViews rv) {
        if (isLocalBinder() && rv != null) {
            return rv.clone();
        }
        return rv;
    }

    private AppWidgetProviderInfo cloneIfLocalBinder(AppWidgetProviderInfo info) {
        if (isLocalBinder() && info != null) {
            return info.clone();
        }
        return info;
    }

    private Bundle cloneIfLocalBinder(Bundle bundle) {
        // Note: this is only a shallow copy. For now this will be fine, but it could be problematic
        // if we start adding objects to the options. Further, it would only be an issue if keyguard
        // used such options.
        if (isLocalBinder() && bundle != null) {
            return (Bundle) bundle.clone();
        }
        return bundle;
    }

    public int[] startListening(IAppWidgetHost callbacks, String packageName, int hostId,
            List<RemoteViews> updatedViews) {
        if (!mHasFeature) {
            return new int[0];
        }
        int callingUid = enforceCallingUid(packageName);
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            Host host = lookupOrAddHostLocked(callingUid, packageName, hostId);
            host.callbacks = callbacks;

            updatedViews.clear();

            ArrayList<AppWidgetId> instances = host.instances;
            int N = instances.size();
            int[] updatedIds = new int[N];
            for (int i = 0; i < N; i++) {
                AppWidgetId id = instances.get(i);
                updatedIds[i] = id.appWidgetId;
                updatedViews.add(cloneIfLocalBinder(id.views));
            }
            return updatedIds;
        }
    }

    public void stopListening(int hostId) {
        synchronized (mAppWidgetIds) {
            if (!mHasFeature) {
                return;
            }
            ensureStateLoadedLocked();
            Host host = lookupHostLocked(Binder.getCallingUid(), hostId);
            if (host != null) {
                host.callbacks = null;
                pruneHostLocked(host);
            }
        }
    }

    boolean canAccessAppWidgetId(AppWidgetId id, int callingUid) {
        if (id.host.uidMatches(callingUid)) {
            // Apps hosting the AppWidget have access to it.
            return true;
        }
        if (id.provider != null && id.provider.uid == callingUid) {
            // Apps providing the AppWidget have access to it (if the appWidgetId has been bound)
            return true;
        }
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.BIND_APPWIDGET) == PackageManager.PERMISSION_GRANTED) {
            // Apps that can bind have access to all appWidgetIds.
            return true;
        }
        // Nobody else can access it.
        return false;
    }

    AppWidgetId lookupAppWidgetIdLocked(int appWidgetId) {
        int callingUid = Binder.getCallingUid();
        final int N = mAppWidgetIds.size();
        for (int i = 0; i < N; i++) {
            AppWidgetId id = mAppWidgetIds.get(i);
            if (id.appWidgetId == appWidgetId && canAccessAppWidgetId(id, callingUid)) {
                return id;
            }
        }
        return null;
    }

    Provider lookupProviderLocked(ComponentName provider) {
        return lookupProviderLocked(provider, mInstalledProviders);
    }

    Provider lookupProviderLocked(ComponentName provider, ArrayList<Provider> installedProviders) {
        final int N = installedProviders.size();
        for (int i = 0; i < N; i++) {
            Provider p = installedProviders.get(i);
            if (p.info.provider.equals(provider)) {
                return p;
            }
        }
        return null;
    }

    Host lookupHostLocked(int uid, int hostId) {
        final int N = mHosts.size();
        for (int i = 0; i < N; i++) {
            Host h = mHosts.get(i);
            if (h.uidMatches(uid) && h.hostId == hostId) {
                return h;
            }
        }
        return null;
    }

    Host lookupOrAddHostLocked(int uid, String packageName, int hostId) {
        final int N = mHosts.size();
        for (int i = 0; i < N; i++) {
            Host h = mHosts.get(i);
            if (h.hostId == hostId && h.packageName.equals(packageName)) {
                return h;
            }
        }
        Host host = new Host();
        host.packageName = packageName;
        host.uid = uid;
        host.hostId = hostId;
        mHosts.add(host);
        return host;
    }

    void pruneHostLocked(Host host) {
        if (host.instances.size() == 0 && host.callbacks == null) {
            if (DBG) log("Pruning host " + host);
            mHosts.remove(host);
        }
    }

    void loadWidgetProviderListLocked() {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        try {
            List<ResolveInfo> broadcastReceivers = mPm.queryIntentReceivers(intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    PackageManager.GET_META_DATA, mUserId);

            final int N = broadcastReceivers == null ? 0 : broadcastReceivers.size();
            for (int i = 0; i < N; i++) {
                ResolveInfo ri = broadcastReceivers.get(i);
                addProviderLocked(ri);
            }
        } catch (RemoteException re) {
            // Shouldn't happen, local call
        }
    }

    boolean addProviderLocked(ResolveInfo ri) {
        if ((ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
            return false;
        }
        if (!ri.activityInfo.isEnabled()) {
            return false;
        }
        Provider p = parseProviderInfoXml(new ComponentName(ri.activityInfo.packageName,
                ri.activityInfo.name), ri);
        if (p != null) {
            // we might have an inactive entry for this provider already due to
            // a preceding restore operation.  if so, fix it up in place; otherwise
            // just add this new one.
            Provider existing = lookupProviderLocked(p.info.provider);
            if (existing != null) {
                if (existing.zombie && !mSafeMode) {
                    // it's a placeholder that was set up during an app restore
                    existing.zombie = false;
                    existing.info = p.info; // the real one filled out from the ResolveInfo
                    existing.uid = p.uid;
                    if (DEBUG_BACKUP) {
                        Slog.i(TAG, "Provider placeholder now reified: " + existing);
                    }
                }
            } else {
                mInstalledProviders.add(p);
            }
            return true;
        } else {
            return false;
        }
    }

    void removeProviderLocked(int index, Provider p) {
        int N = p.instances.size();
        for (int i = 0; i < N; i++) {
            AppWidgetId id = p.instances.get(i);
            // Call back with empty RemoteViews
            updateAppWidgetInstanceLocked(id, null);
            // Stop telling the host about updates for this from now on
            cancelBroadcasts(p);
            // clear out references to this appWidgetId
            id.host.instances.remove(id);
            mAppWidgetIds.remove(id);
            id.provider = null;
            pruneHostLocked(id.host);
            id.host = null;
        }
        p.instances.clear();
        mInstalledProviders.remove(index);
        mDeletedProviders.add(p);
        // no need to send the DISABLE broadcast, since the receiver is gone anyway
        cancelBroadcasts(p);
    }

    void sendEnableIntentLocked(Provider p) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_ENABLED);
        intent.setComponent(p.info.provider);
        mContext.sendBroadcastAsUser(intent, new UserHandle(mUserId));
    }

    void sendUpdateIntentLocked(Provider p, int[] appWidgetIds) {
        if (appWidgetIds != null && appWidgetIds.length > 0) {
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
            intent.setComponent(p.info.provider);
            mContext.sendBroadcastAsUser(intent, new UserHandle(mUserId));
        }
    }

    void registerForBroadcastsLocked(Provider p, int[] appWidgetIds) {
        if (p.info.updatePeriodMillis > 0) {
            // if this is the first instance, set the alarm. otherwise,
            // rely on the fact that we've already set it and that
            // PendingIntent.getBroadcast will update the extras.
            boolean alreadyRegistered = p.broadcast != null;
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
            intent.setComponent(p.info.provider);
            long token = Binder.clearCallingIdentity();
            try {
                p.broadcast = PendingIntent.getBroadcastAsUser(mContext, 1, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT, new UserHandle(mUserId));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (!alreadyRegistered) {
                long period = p.info.updatePeriodMillis;
                if (period < MIN_UPDATE_PERIOD) {
                    period = MIN_UPDATE_PERIOD;
                }
                mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock
                        .elapsedRealtime()
                        + period, period, p.broadcast);
            }
        }
    }

    static int[] getAppWidgetIds(Provider p) {
        int instancesSize = p.instances.size();
        int appWidgetIds[] = new int[instancesSize];
        for (int i = 0; i < instancesSize; i++) {
            appWidgetIds[i] = p.instances.get(i).appWidgetId;
        }
        return appWidgetIds;
    }

    public int[] getAppWidgetIds(ComponentName provider) {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            Provider p = lookupProviderLocked(provider);
            if (p != null && Binder.getCallingUid() == p.uid) {
                return getAppWidgetIds(p);
            } else {
                return new int[0];
            }
        }
    }

    static int[] getAppWidgetIds(Host h) {
        int instancesSize = h.instances.size();
        int appWidgetIds[] = new int[instancesSize];
        for (int i = 0; i < instancesSize; i++) {
            appWidgetIds[i] = h.instances.get(i).appWidgetId;
        }
        return appWidgetIds;
    }

    public int[] getAppWidgetIdsForHost(int hostId) {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            int callingUid = Binder.getCallingUid();
            Host host = lookupHostLocked(callingUid, hostId);
            if (host != null) {
                return getAppWidgetIds(host);
            } else {
                return new int[0];
            }
        }
    }

    public List<String> getWidgetParticipants() {
        HashSet<String> packages = new HashSet<String>();
        synchronized (mAppWidgetIds) {
            final int N = mAppWidgetIds.size();
            for (int i = 0; i < N; i++) {
                final AppWidgetId id = mAppWidgetIds.get(i);
                packages.add(id.host.packageName);
                packages.add(id.provider.info.provider.getPackageName());
            }
        }
        return new ArrayList<String>(packages);
    }

    private void serializeProvider(XmlSerializer out, Provider p) throws IOException {
        out.startTag(null, "p");
        out.attribute(null, "pkg", p.info.provider.getPackageName());
        out.attribute(null, "cl", p.info.provider.getClassName());
        out.endTag(null, "p");
    }

    private void serializeHost(XmlSerializer out, Host host) throws IOException {
        out.startTag(null, "h");
        out.attribute(null, "pkg", host.packageName);
        out.attribute(null, "id", Integer.toHexString(host.hostId));
        out.endTag(null, "h");
    }

    private void serializeAppWidgetId(XmlSerializer out, AppWidgetId id) throws IOException {
        out.startTag(null, "g");
        out.attribute(null, "id", Integer.toHexString(id.appWidgetId));
        out.attribute(null, "rid", Integer.toHexString(id.restoredId));
        out.attribute(null, "h", Integer.toHexString(id.host.tag));
        if (id.provider != null) {
            out.attribute(null, "p", Integer.toHexString(id.provider.tag));
        }
        if (id.options != null) {
            out.attribute(null, "min_width", Integer.toHexString(id.options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)));
            out.attribute(null, "min_height", Integer.toHexString(id.options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)));
            out.attribute(null, "max_width", Integer.toHexString(id.options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)));
            out.attribute(null, "max_height", Integer.toHexString(id.options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)));
            out.attribute(null, "host_category", Integer.toHexString(id.options.getInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY)));
        }
        out.endTag(null, "g");
    }

    private Bundle parseWidgetIdOptions(XmlPullParser parser) {
        Bundle options = new Bundle();
        String minWidthString = parser.getAttributeValue(null, "min_width");
        if (minWidthString != null) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                    Integer.parseInt(minWidthString, 16));
        }
        String minHeightString = parser.getAttributeValue(null, "min_height");
        if (minHeightString != null) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                    Integer.parseInt(minHeightString, 16));
        }
        String maxWidthString = parser.getAttributeValue(null, "max_width");
        if (maxWidthString != null) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                    Integer.parseInt(maxWidthString, 16));
        }
        String maxHeightString = parser.getAttributeValue(null, "max_height");
        if (maxHeightString != null) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                    Integer.parseInt(maxHeightString, 16));
        }
        String categoryString = parser.getAttributeValue(null, "host_category");
        if (categoryString != null) {
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    Integer.parseInt(categoryString, 16));
        }
        return options;
    }

    // Does this package either host or provide any active widgets?
    private boolean packageNeedsWidgetBackupLocked(String packageName) {
        int N = mAppWidgetIds.size();
        for (int i = 0; i < N; i++) {
            AppWidgetId id = mAppWidgetIds.get(i);
            if (packageName.equals(id.host.packageName)) {
                // this package is hosting widgets, so it knows widget IDs
                return true;
            }
            Provider p = id.provider;
            if (p != null && packageName.equals(p.info.provider.getPackageName())) {
                // someone is hosting this app's widgets, so it knows widget IDs
                return true;
            }
        }
        return false;
    }

    // build the widget-state blob that we save for the app during backup.
    public byte[] getWidgetState(String backupTarget) {
        if (!mHasFeature) {
            return null;
        }

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        synchronized (mAppWidgetIds) {
            // Preflight: if this app neither hosts nor provides any live widgets
            // we have no work to do.
            if (!packageNeedsWidgetBackupLocked(backupTarget)) {
                return null;
            }

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, "utf-8");
                out.startDocument(null, true);
                out.startTag(null, "ws");      // widget state
                out.attribute(null, "version", String.valueOf(WIDGET_STATE_VERSION));
                out.attribute(null, "pkg", backupTarget);

                // Remember all the providers that are currently hosted or published
                // by this package: that is, all of the entities related to this app
                // which will need to be told about id remapping.
                int N = mInstalledProviders.size();
                int index = 0;
                for (int i = 0; i < N; i++) {
                    Provider p = mInstalledProviders.get(i);
                    if (p.instances.size() > 0) {
                        if (backupTarget.equals(p.info.provider.getPackageName())
                                || p.isHostedBy(backupTarget)) {
                            serializeProvider(out, p);
                            p.tag = index++;
                        }
                    }
                }

                N = mHosts.size();
                index = 0;
                for (int i = 0; i < N; i++) {
                    Host host = mHosts.get(i);
                    if (backupTarget.equals(host.packageName)
                            || host.hostsPackage(backupTarget)) {
                        serializeHost(out, host);
                        host.tag = index++;
                    }
                }

                // All widget instances involving this package,
                // either as host or as provider
                N = mAppWidgetIds.size();
                for (int i = 0; i < N; i++) {
                    AppWidgetId id = mAppWidgetIds.get(i);
                    if (backupTarget.equals(id.host.packageName)
                            || backupTarget.equals(id.provider.info.provider.getPackageName())) {
                        serializeAppWidgetId(out, id);
                    }
                }

                out.endTag(null, "ws");
                out.endDocument();
            } catch (IOException e) {
                Slog.w(TAG, "Unable to save widget state for " + backupTarget);
                return null;
            }

        }
        return stream.toByteArray();
    }

    public void restoreStarting() {
        if (DEBUG_BACKUP) {
            Slog.i(TAG, "restore starting for user " + mUserId);
        }
        synchronized (mRestoredWidgetIds) {
            // We're starting a new "system" restore operation, so any widget restore
            // state that we see from here on is intended to replace the current
            // widget configuration of any/all of the affected apps.
            mPrunedApps.clear();
            mUpdatesByProvider.clear();
            mUpdatesByHost.clear();
        }
    }

    // We're restoring widget state for 'pkg', so we start by wiping (a) all widget
    // instances that are hosted by that app, and (b) all instances in other hosts
    // for which 'pkg' is the provider.  We assume that we'll be restoring all of
    // these hosts & providers, so will be reconstructing a correct live state.
    private void pruneWidgetStateLr(String pkg) {
        if (!mPrunedApps.contains(pkg)) {
            if (DEBUG_BACKUP) {
                Slog.i(TAG, "pruning widget state for restoring package " + pkg);
            }
            for (int i = mAppWidgetIds.size() - 1; i >= 0; i--) {
                AppWidgetId id = mAppWidgetIds.get(i);
                Provider p = id.provider;
                if (id.host.packageName.equals(pkg)
                        || p.info.provider.getPackageName().equals(pkg)) {
                    // 'pkg' is either the host or the provider for this instances,
                    // so we tear it down in anticipation of it (possibly) being
                    // reconstructed due to the restore
                    p.instances.remove(id);

                    unbindAppWidgetRemoteViewsServicesLocked(id);
                    mAppWidgetIds.remove(i);
                }
            }
            mPrunedApps.add(pkg);
        } else {
            if (DEBUG_BACKUP) {
                Slog.i(TAG, "already pruned " + pkg + ", continuing normally");
            }
        }
    }

    // Accumulate a list of updates that affect the given provider for a final
    // coalesced notification broadcast once restore is over.
    class RestoreUpdateRecord {
        public int oldId;
        public int newId;
        public boolean notified;

        public RestoreUpdateRecord(int theOldId, int theNewId) {
            oldId = theOldId;
            newId = theNewId;
            notified = false;
        }
    }

    HashMap<Provider, ArrayList<RestoreUpdateRecord>> mUpdatesByProvider
            = new HashMap<Provider, ArrayList<RestoreUpdateRecord>>();
    HashMap<Host, ArrayList<RestoreUpdateRecord>> mUpdatesByHost
            = new HashMap<Host, ArrayList<RestoreUpdateRecord>>();

    private boolean alreadyStashed(ArrayList<RestoreUpdateRecord> stash,
            final int oldId, final int newId) {
        final int N = stash.size();
        for (int i = 0; i < N; i++) {
            RestoreUpdateRecord r = stash.get(i);
            if (r.oldId == oldId && r.newId == newId) {
                return true;
            }
        }
        return false;
    }

    private void stashProviderRestoreUpdateLr(Provider provider, int oldId, int newId) {
        ArrayList<RestoreUpdateRecord> r = mUpdatesByProvider.get(provider);
        if (r == null) {
            r = new ArrayList<RestoreUpdateRecord>();
            mUpdatesByProvider.put(provider, r);
        } else {
            // don't duplicate
            if (alreadyStashed(r, oldId, newId)) {
                if (DEBUG_BACKUP) {
                    Slog.i(TAG, "ID remap " + oldId + " -> " + newId
                            + " already stashed for " + provider);
                }
                return;
            }
        }
        r.add(new RestoreUpdateRecord(oldId, newId));
    }

    private void stashHostRestoreUpdateLr(Host host, int oldId, int newId) {
        ArrayList<RestoreUpdateRecord> r = mUpdatesByHost.get(host);
        if (r == null) {
            r = new ArrayList<RestoreUpdateRecord>();
            mUpdatesByHost.put(host, r);
        } else {
            if (alreadyStashed(r, oldId, newId)) {
                if (DEBUG_BACKUP) {
                    Slog.i(TAG, "ID remap " + oldId + " -> " + newId
                            + " already stashed for " + host);
                }
                return;
            }
        }
        r.add(new RestoreUpdateRecord(oldId, newId));
    }

    public void restoreWidgetState(String packageName, byte[] restoredState) {
        if (!mHasFeature) {
            return;
        }

        if (DEBUG_BACKUP) {
            Slog.i(TAG, "Restoring widget state for " + packageName);
        }

        ByteArrayInputStream stream = new ByteArrayInputStream(restoredState);
        try {
            // Providers mentioned in the widget dataset by ordinal
            ArrayList<Provider> restoredProviders = new ArrayList<Provider>();

            // Hosts mentioned in the widget dataset by ordinal
            ArrayList<Host> restoredHosts = new ArrayList<Host>();

            //HashSet<String> toNotify = new HashSet<String>();

            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            synchronized (mAppWidgetIds) {
                synchronized (mRestoredWidgetIds) {
                    int type;
                    do {
                        type = parser.next();
                        if (type == XmlPullParser.START_TAG) {
                            final String tag = parser.getName();
                            if ("ws".equals(tag)) {
                                String v = parser.getAttributeValue(null, "version");
                                String pkg = parser.getAttributeValue(null, "pkg");

                                // TODO: fix up w.r.t. canonical vs current package names
                                if (!packageName.equals(pkg)) {
                                    Slog.w(TAG, "Package mismatch in ws");
                                    return;
                                }

                                int version = Integer.parseInt(v);
                                if (version > WIDGET_STATE_VERSION) {
                                    Slog.w(TAG, "Unable to process state version " + version);
                                    return;
                                }
                            } else if ("p".equals(tag)) {
                                String pkg = parser.getAttributeValue(null, "pkg");
                                String cl = parser.getAttributeValue(null, "cl");

                                // hostedProviders index will match 'p' attribute in widget's
                                // entry in the xml file being restored
                                // If there's no live entry for this provider, add an inactive one
                                // so that widget IDs referring to them can be properly allocated
                                final ComponentName cn = new ComponentName(pkg, cl);
                                Provider p = lookupProviderLocked(cn, mInstalledProviders);
                                if (p == null) {
                                    p = new Provider();
                                    p.info = new AppWidgetProviderInfo();
                                    p.info.provider = cn;
                                    p.zombie = true;
                                    mInstalledProviders.add(p);
                                }
                                if (DEBUG_BACKUP) {
                                    Slog.i(TAG, "   provider " + cn);
                                }
                                restoredProviders.add(p);
                            } else if ("h".equals(tag)) {
                                // The host app may not yet exist on the device.  If it's here we
                                // just use the existing Host entry, otherwise we create a
                                // placeholder whose uid will be fixed up at PACKAGE_ADDED time.
                                String pkg = parser.getAttributeValue(null, "pkg");
                                int uid;
                                try {
                                    uid = getUidForPackage(pkg);
                                } catch (NameNotFoundException e) {
                                    uid = -1;
                                }
                                int hostId = Integer.parseInt(
                                        parser.getAttributeValue(null, "id"), 16);
                                Host h = lookupOrAddHostLocked(uid, pkg, hostId);
                                if (DEBUG_BACKUP) {
                                    Slog.i(TAG, "   host[" + restoredHosts.size()
                                            + "]: {" + h.packageName + ":" + h.hostId + "}");
                                }
                                restoredHosts.add(h);
                            } else if ("g".equals(tag)) {
                                int restoredId = Integer.parseInt(
                                        parser.getAttributeValue(null, "id"), 16);
                                int hostIndex = Integer.parseInt(
                                        parser.getAttributeValue(null, "h"), 16);
                                Host host = restoredHosts.get(hostIndex);
                                Provider p = null;
                                String prov = parser.getAttributeValue(null, "p");
                                if (prov != null) {
                                    // could have been null if the app had allocated an id
                                    // but not yet established a binding under that id
                                    int which = Integer.parseInt(prov, 16);
                                    p = restoredProviders.get(which);
                                }

                                // We'll be restoring widget state for both the host and
                                // provider sides of this widget ID, so make sure we are
                                // beginning from a clean slate on both fronts.
                                pruneWidgetStateLr(host.packageName);
                                if (p != null) {
                                    pruneWidgetStateLr(p.info.provider.getPackageName());
                                }

                                // Have we heard about this ancestral widget instance before?
                                AppWidgetId id = findRestoredWidgetLocked(restoredId, host, p);
                                if (id == null) {
                                    id = new AppWidgetId();
                                    id.appWidgetId = mNextAppWidgetId++;
                                    id.restoredId = restoredId;
                                    id.options = parseWidgetIdOptions(parser);
                                    id.host = host;
                                    id.host.instances.add(id);
                                    id.provider = p;
                                    if (id.provider != null) {
                                        id.provider.instances.add(id);
                                    }
                                    if (DEBUG_BACKUP) {
                                        Slog.i(TAG, "New restored id " + restoredId
                                                + " now " + id);
                                    }
                                    mAppWidgetIds.add(id);
                                }
                                if (id.provider.info != null) {
                                    stashProviderRestoreUpdateLr(id.provider,
                                            restoredId, id.appWidgetId);
                                } else {
                                    Slog.w(TAG, "Missing provider for restored widget " + id);
                                }
                                stashHostRestoreUpdateLr(id.host, restoredId, id.appWidgetId);

                                if (DEBUG_BACKUP) {
                                    Slog.i(TAG, "   instance: " + restoredId
                                            + " -> " + id.appWidgetId
                                            + " :: p=" + id.provider);
                                }
                            }
                        }
                    } while (type != XmlPullParser.END_DOCUMENT);

                    // We've updated our own bookkeeping.  We'll need to notify the hosts and
                    // providers about the changes, but we can't do that yet because the restore
                    // target is not necessarily fully live at this moment.  Set aside the
                    // information for now; the backup manager will call us once more at the
                    // end of the process when all of the targets are in a known state, and we
                    // will update at that point.
                }
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Unable to restore widget state for " + packageName);
        } catch (IOException e) {
            Slog.w(TAG, "Unable to restore widget state for " + packageName);
        } finally {
            saveStateAsync();
        }
    }

    // Called once following the conclusion of a restore operation.  This is when we
    // send out updates to apps involved in widget-state restore telling them about
    // the new widget ID space.
    public void restoreFinished() {
        if (DEBUG_BACKUP) {
            Slog.i(TAG, "restoreFinished for " + mUserId);
        }

        final UserHandle userHandle = new UserHandle(mUserId);
        synchronized (mRestoredWidgetIds) {
            // Build the providers' broadcasts and send them off
            Set<Entry<Provider, ArrayList<RestoreUpdateRecord>>> providerEntries
                    = mUpdatesByProvider.entrySet();
            for (Entry<Provider, ArrayList<RestoreUpdateRecord>> e : providerEntries) {
                // For each provider there's a list of affected IDs
                Provider provider = e.getKey();
                ArrayList<RestoreUpdateRecord> updates = e.getValue();
                final int pending = countPendingUpdates(updates);
                if (DEBUG_BACKUP) {
                    Slog.i(TAG, "Provider " + provider + " pending: " + pending);
                }
                if (pending > 0) {
                    int[] oldIds = new int[pending];
                    int[] newIds = new int[pending];
                    final int N = updates.size();
                    int nextPending = 0;
                    for (int i = 0; i < N; i++) {
                        RestoreUpdateRecord r = updates.get(i);
                        if (!r.notified) {
                            r.notified = true;
                            oldIds[nextPending] = r.oldId;
                            newIds[nextPending] = r.newId;
                            nextPending++;
                            if (DEBUG_BACKUP) {
                                Slog.i(TAG, "   " + r.oldId + " => " + r.newId);
                            }
                        }
                    }
                    sendWidgetRestoreBroadcast(AppWidgetManager.ACTION_APPWIDGET_RESTORED,
                            provider, null, oldIds, newIds, userHandle);
                }
            }

            // same thing per host
            Set<Entry<Host, ArrayList<RestoreUpdateRecord>>> hostEntries
                    = mUpdatesByHost.entrySet();
            for (Entry<Host, ArrayList<RestoreUpdateRecord>> e : hostEntries) {
                Host host = e.getKey();
                if (host.uid > 0) {
                    ArrayList<RestoreUpdateRecord> updates = e.getValue();
                    final int pending = countPendingUpdates(updates);
                    if (DEBUG_BACKUP) {
                        Slog.i(TAG, "Host " + host + " pending: " + pending);
                    }
                    if (pending > 0) {
                        int[] oldIds = new int[pending];
                        int[] newIds = new int[pending];
                        final int N = updates.size();
                        int nextPending = 0;
                        for (int i = 0; i < N; i++) {
                            RestoreUpdateRecord r = updates.get(i);
                            if (!r.notified) {
                                r.notified = true;
                                oldIds[nextPending] = r.oldId;
                                newIds[nextPending] = r.newId;
                                nextPending++;
                                if (DEBUG_BACKUP) {
                                    Slog.i(TAG, "   " + r.oldId + " => " + r.newId);
                                }
                            }
                        }
                        sendWidgetRestoreBroadcast(AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED,
                                null, host, oldIds, newIds, userHandle);
                    }
                }
            }
        }
    }

    private int countPendingUpdates(ArrayList<RestoreUpdateRecord> updates) {
        int pending = 0;
        final int N = updates.size();
        for (int i = 0; i < N; i++) {
            RestoreUpdateRecord r = updates.get(i);
            if (!r.notified) {
                pending++;
            }
        }
        return pending;
    }

    void sendWidgetRestoreBroadcast(String action, Provider provider, Host host,
            int[] oldIds, int[] newIds, UserHandle userHandle) {
        Intent intent = new Intent(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS, oldIds);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, newIds);
        if (provider != null) {
            intent.setComponent(provider.info.provider);
            mContext.sendBroadcastAsUser(intent, userHandle);
        }
        if (host != null) {
            intent.setComponent(null);
            intent.setPackage(host.packageName);
            intent.putExtra(AppWidgetManager.EXTRA_HOST_ID, host.hostId);
            mContext.sendBroadcastAsUser(intent, userHandle);
        }
    }

    private Provider parseProviderInfoXml(ComponentName component, ResolveInfo ri) {
        Provider p = null;

        ActivityInfo activityInfo = ri.activityInfo;
        XmlResourceParser parser = null;
        try {
            parser = activityInfo.loadXmlMetaData(mContext.getPackageManager(),
                    AppWidgetManager.META_DATA_APPWIDGET_PROVIDER);
            if (parser == null) {
                Slog.w(TAG, "No " + AppWidgetManager.META_DATA_APPWIDGET_PROVIDER
                        + " meta-data for " + "AppWidget provider '" + component + '\'');
                return null;
            }

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // drain whitespace, comments, etc.
            }

            String nodeName = parser.getName();
            if (!"appwidget-provider".equals(nodeName)) {
                Slog.w(TAG, "Meta-data does not start with appwidget-provider tag for"
                        + " AppWidget provider '" + component + '\'');
                return null;
            }

            p = new Provider();
            AppWidgetProviderInfo info = p.info = new AppWidgetProviderInfo();
            info.provider = component;
            p.uid = activityInfo.applicationInfo.uid;

            Resources res = mContext.getPackageManager()
                    .getResourcesForApplicationAsUser(activityInfo.packageName, mUserId);

            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.AppWidgetProviderInfo);

            // These dimensions has to be resolved in the application's context.
            // We simply send back the raw complex data, which will be
            // converted to dp in {@link AppWidgetManager#getAppWidgetInfo}.
            TypedValue value = sa
                    .peekValue(com.android.internal.R.styleable.AppWidgetProviderInfo_minWidth);
            info.minWidth = value != null ? value.data : 0;
            value = sa.peekValue(com.android.internal.R.styleable.AppWidgetProviderInfo_minHeight);
            info.minHeight = value != null ? value.data : 0;
            value = sa.peekValue(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_minResizeWidth);
            info.minResizeWidth = value != null ? value.data : info.minWidth;
            value = sa.peekValue(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_minResizeHeight);
            info.minResizeHeight = value != null ? value.data : info.minHeight;
            info.updatePeriodMillis = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_updatePeriodMillis, 0);
            info.initialLayout = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_initialLayout, 0);
            info.initialKeyguardLayout = sa.getResourceId(com.android.internal.R.styleable.
                    AppWidgetProviderInfo_initialKeyguardLayout, 0);
            String className = sa
                    .getString(com.android.internal.R.styleable.AppWidgetProviderInfo_configure);
            if (className != null) {
                info.configure = new ComponentName(component.getPackageName(), className);
            }
            info.label = activityInfo.loadLabel(mContext.getPackageManager()).toString();
            info.icon = ri.getIconResource();
            info.previewImage = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_previewImage, 0);
            info.autoAdvanceViewId = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_autoAdvanceViewId, -1);
            info.resizeMode = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_resizeMode,
                    AppWidgetProviderInfo.RESIZE_NONE);
            info.widgetCategory = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_widgetCategory,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);

            sa.recycle();
        } catch (Exception e) {
            // Ok to catch Exception here, because anything going wrong because
            // of what a client process passes to us should not be fatal for the
            // system process.
            Slog.w(TAG, "XML parsing failed for AppWidget provider '" + component + '\'', e);
            return null;
        } finally {
            if (parser != null)
                parser.close();
        }
        return p;
    }

    int getUidForPackage(String packageName) throws PackageManager.NameNotFoundException {
        PackageInfo pkgInfo = null;
        try {
            pkgInfo = mPm.getPackageInfo(packageName, 0, mUserId);
        } catch (RemoteException re) {
            // Shouldn't happen, local call
        }
        if (pkgInfo == null || pkgInfo.applicationInfo == null) {
            throw new PackageManager.NameNotFoundException();
        }
        return pkgInfo.applicationInfo.uid;
    }

    int enforceSystemOrCallingUid(String packageName) throws IllegalArgumentException {
        int callingUid = Binder.getCallingUid();
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID || callingUid == 0) {
            return callingUid;
        }
        return enforceCallingUid(packageName);
    }

    int enforceCallingUid(String packageName) throws IllegalArgumentException {
        int callingUid = Binder.getCallingUid();
        int packageUid;
        try {
            packageUid = getUidForPackage(packageName);
        } catch (PackageManager.NameNotFoundException ex) {
            throw new IllegalArgumentException("packageName and uid don't match packageName="
                    + packageName);
        }
        if (!UserHandle.isSameApp(callingUid, packageUid)) {
            throw new IllegalArgumentException("packageName and uid don't match packageName="
                    + packageName);
        }
        return callingUid;
    }

    void sendInitialBroadcasts() {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            final int N = mInstalledProviders.size();
            for (int i = 0; i < N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (p.instances.size() > 0) {
                    sendEnableIntentLocked(p);
                    int[] appWidgetIds = getAppWidgetIds(p);
                    sendUpdateIntentLocked(p, appWidgetIds);
                    registerForBroadcastsLocked(p, appWidgetIds);
                }
            }
        }
    }

    // only call from initialization -- it assumes that the data structures are all empty
    void loadStateLocked() {
        AtomicFile file = savedStateFile();
        try {
            FileInputStream stream = file.openRead();
            readStateFromFileLocked(stream);

            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    Slog.w(TAG, "Failed to close state FileInputStream " + e);
                }
            }
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Failed to read state: " + e);
        }
    }

    void saveStateLocked() {
        if (!mHasFeature) {
            return;
        }
        AtomicFile file = savedStateFile();
        FileOutputStream stream;
        try {
            stream = file.startWrite();
            if (writeStateToFileLocked(stream)) {
                file.finishWrite(stream);
            } else {
                file.failWrite(stream);
                Slog.w(TAG, "Failed to save state, restoring backup.");
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failed open state file for write: " + e);
        }
    }

    boolean writeStateToFileLocked(FileOutputStream stream) {
        int N;

        try {
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);
            out.startTag(null, "gs");
            out.attribute(null, "version", String.valueOf(CURRENT_VERSION));
            int providerIndex = 0;
            N = mInstalledProviders.size();
            for (int i = 0; i < N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (p.instances.size() > 0) {
                    serializeProvider(out, p);
                    p.tag = providerIndex;
                    providerIndex++;
                }
            }

            N = mHosts.size();
            for (int i = 0; i < N; i++) {
                Host host = mHosts.get(i);
                serializeHost(out, host);
                host.tag = i;
            }

            N = mAppWidgetIds.size();
            for (int i = 0; i < N; i++) {
                AppWidgetId id = mAppWidgetIds.get(i);
                serializeAppWidgetId(out, id);
            }

            Iterator<String> it = mPackagesWithBindWidgetPermission.iterator();
            while (it.hasNext()) {
                out.startTag(null, "b");
                out.attribute(null, "packageName", it.next());
                out.endTag(null, "b");
            }

            out.endTag(null, "gs");

            out.endDocument();
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write state: " + e);
            return false;
        }
    }

    @SuppressWarnings("unused")
    void readStateFromFileLocked(FileInputStream stream) {
        boolean success = false;
        int version = 0;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type;
            int providerIndex = 0;
            HashMap<Integer, Provider> loadedProviders = new HashMap<Integer, Provider>();
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("gs".equals(tag)) {
                        String attributeValue = parser.getAttributeValue(null, "version");
                        try {
                            version = Integer.parseInt(attributeValue);
                        } catch (NumberFormatException e) {
                            version = 0;
                        }
                    } else if ("p".equals(tag)) {
                        // TODO: do we need to check that this package has the same signature
                        // as before?
                        String pkg = parser.getAttributeValue(null, "pkg");
                        String cl = parser.getAttributeValue(null, "cl");

                        final IPackageManager packageManager = AppGlobals.getPackageManager();
                        try {
                            packageManager.getReceiverInfo(new ComponentName(pkg, cl), 0, mUserId);
                        } catch (RemoteException e) {
                            String[] pkgs = mContext.getPackageManager()
                                    .currentToCanonicalPackageNames(new String[] { pkg });
                            pkg = pkgs[0];
                        }

                        Provider p = lookupProviderLocked(new ComponentName(pkg, cl));
                        if (p == null && mSafeMode) {
                            // if we're in safe mode, make a temporary one
                            p = new Provider();
                            p.info = new AppWidgetProviderInfo();
                            p.info.provider = new ComponentName(pkg, cl);
                            p.zombie = true;
                            mInstalledProviders.add(p);
                        }
                        if (p != null) {
                            // if it wasn't uninstalled or something
                            loadedProviders.put(providerIndex, p);
                        }
                        providerIndex++;
                    } else if ("h".equals(tag)) {
                        Host host = new Host();

                        // TODO: do we need to check that this package has the same signature
                        // as before?
                        host.packageName = parser.getAttributeValue(null, "pkg");
                        try {
                            host.uid = getUidForPackage(host.packageName);
                        } catch (PackageManager.NameNotFoundException ex) {
                            host.zombie = true;
                        }
                        if (!host.zombie || mSafeMode) {
                            // In safe mode, we don't discard the hosts we don't recognize
                            // so that they're not pruned from our list. Otherwise, we do.
                            host.hostId = Integer
                                    .parseInt(parser.getAttributeValue(null, "id"), 16);
                            mHosts.add(host);
                        }
                    } else if ("b".equals(tag)) {
                        String packageName = parser.getAttributeValue(null, "packageName");
                        if (packageName != null) {
                            mPackagesWithBindWidgetPermission.add(packageName);
                        }
                    } else if ("g".equals(tag)) {
                        AppWidgetId id = new AppWidgetId();
                        id.appWidgetId = Integer.parseInt(parser.getAttributeValue(null, "id"), 16);
                        if (id.appWidgetId >= mNextAppWidgetId) {
                            mNextAppWidgetId = id.appWidgetId + 1;
                        }

                        // restored ID is allowed to be absent
                        String restoredIdString = parser.getAttributeValue(null, "rid");
                        id.restoredId = (restoredIdString == null) ? 0
                                : Integer.parseInt(restoredIdString, 16);

                        Bundle options = new Bundle();
                        String minWidthString = parser.getAttributeValue(null, "min_width");
                        if (minWidthString != null) {
                            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                                    Integer.parseInt(minWidthString, 16));
                        }
                        String minHeightString = parser.getAttributeValue(null, "min_height");
                        if (minHeightString != null) {
                            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
                                    Integer.parseInt(minHeightString, 16));
                        }
                        String maxWidthString = parser.getAttributeValue(null, "max_width");
                        if (maxWidthString != null) {
                            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
                                    Integer.parseInt(maxWidthString, 16));
                        }
                        String maxHeightString = parser.getAttributeValue(null, "max_height");
                        if (maxHeightString != null) {
                            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                                    Integer.parseInt(maxHeightString, 16));
                        }
                        String categoryString = parser.getAttributeValue(null, "host_category");
                        if (categoryString != null) {
                            options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                                    Integer.parseInt(categoryString, 16));
                        }
                        id.options = options;

                        String providerString = parser.getAttributeValue(null, "p");
                        if (providerString != null) {
                            // there's no provider if it hasn't been bound yet.
                            // maybe we don't have to save this, but it brings the system
                            // to the state it was in.
                            int pIndex = Integer.parseInt(providerString, 16);
                            id.provider = loadedProviders.get(pIndex);
                            if (false) {
                                Slog.d(TAG, "bound appWidgetId=" + id.appWidgetId + " to provider "
                                        + pIndex + " which is " + id.provider);
                            }
                            if (id.provider == null) {
                                // This provider is gone. We just let the host figure out
                                // that this happened when it fails to load it.
                                continue;
                            }
                        }

                        int hIndex = Integer.parseInt(parser.getAttributeValue(null, "h"), 16);
                        id.host = mHosts.get(hIndex);
                        if (id.host == null) {
                            // This host is gone.
                            continue;
                        }

                        if (id.provider != null) {
                            id.provider.instances.add(id);
                        }
                        id.host.instances.add(id);
                        mAppWidgetIds.add(id);
                    }
                }
            } while (type != XmlPullParser.END_DOCUMENT);
            success = true;
        } catch (NullPointerException e) {
            Slog.w(TAG, "failed parsing " + e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "failed parsing " + e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "failed parsing " + e);
        } catch (IOException e) {
            Slog.w(TAG, "failed parsing " + e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "failed parsing " + e);
        }

        if (success) {
            // delete any hosts that didn't manage to get connected (should happen)
            // if it matters, they'll be reconnected.
            for (int i = mHosts.size() - 1; i >= 0; i--) {
                pruneHostLocked(mHosts.get(i));
            }
            // upgrade the database if needed
            performUpgrade(version);
        } else {
            // failed reading, clean up
            Slog.w(TAG, "Failed to read state, clearing widgets and hosts.");

            mAppWidgetIds.clear();
            mHosts.clear();
            final int N = mInstalledProviders.size();
            for (int i = 0; i < N; i++) {
                mInstalledProviders.get(i).instances.clear();
            }
        }
    }

    private void performUpgrade(int fromVersion) {
        if (fromVersion < CURRENT_VERSION) {
            Slog.v(TAG, "Upgrading widget database from " + fromVersion + " to " + CURRENT_VERSION
                    + " for user " + mUserId);
        }

        int version = fromVersion;

        // Update 1: keyguard moved from package "android" to "com.android.keyguard"
        if (version == 0) {
            for (int i = 0; i < mHosts.size(); i++) {
                Host host = mHosts.get(i);
                if (host != null && "android".equals(host.packageName)
                        && host.hostId == KEYGUARD_HOST_ID) {
                    host.packageName = KEYGUARD_HOST_PACKAGE;
                }
            }
            version = 1;
        }

        if (version != CURRENT_VERSION) {
            throw new IllegalStateException("Failed to upgrade widget database");
        }
    }

    static File getSettingsFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), SETTINGS_FILENAME);
    }

    AtomicFile savedStateFile() {
        File dir = Environment.getUserSystemDirectory(mUserId);
        File settingsFile = getSettingsFile(mUserId);
        if (!settingsFile.exists() && mUserId == 0) {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            // Migrate old data
            File oldFile = new File("/data/system/" + SETTINGS_FILENAME);
            // Method doesn't throw an exception on failure. Ignore any errors
            // in moving the file (like non-existence)
            oldFile.renameTo(settingsFile);
        }
        return new AtomicFile(settingsFile);
    }

    void onUserStopping() {
        // prune the ones we don't want to keep
        int N = mInstalledProviders.size();
        for (int i = N - 1; i >= 0; i--) {
            Provider p = mInstalledProviders.get(i);
            cancelBroadcasts(p);
        }
    }

    void onUserRemoved() {
        getSettingsFile(mUserId).delete();
    }

    boolean addProvidersForPackageLocked(String pkgName) {
        boolean providersAdded = false;
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.setPackage(pkgName);
        List<ResolveInfo> broadcastReceivers;
        try {
            broadcastReceivers = mPm.queryIntentReceivers(intent,
                    intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                    PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException re) {
            // Shouldn't happen, local call
            return false;
        }
        final int N = broadcastReceivers == null ? 0 : broadcastReceivers.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            ActivityInfo ai = ri.activityInfo;
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                continue;
            }
            if (pkgName.equals(ai.packageName)) {
                providersAdded = addProviderLocked(ri);
            }
        }

        return providersAdded;
    }

    /**
     * Updates all providers with the specified package names, and records any providers that were
     * pruned.
     *
     * @return whether any providers were updated
     */
    boolean updateProvidersForPackageLocked(String pkgName, Set<ComponentName> removedProviders) {
        boolean providersUpdated = false;
        HashSet<String> keep = new HashSet<String>();
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.setPackage(pkgName);
        List<ResolveInfo> broadcastReceivers;
        try {
            broadcastReceivers = mPm.queryIntentReceivers(intent,
                intent.resolveTypeIfNeeded(mContext.getContentResolver()),
                PackageManager.GET_META_DATA, mUserId);
        } catch (RemoteException re) {
            // Shouldn't happen, local call
            return false;
        }

        // add the missing ones and collect which ones to keep
        int N = broadcastReceivers == null ? 0 : broadcastReceivers.size();
        for (int i = 0; i < N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            ActivityInfo ai = ri.activityInfo;
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                continue;
            }
            if (pkgName.equals(ai.packageName)) {
                ComponentName component = new ComponentName(ai.packageName, ai.name);
                Provider p = lookupProviderLocked(component);
                if (p == null) {
                    if (addProviderLocked(ri)) {
                        keep.add(ai.name);
                        providersUpdated = true;
                    }
                } else {
                    Provider parsed = parseProviderInfoXml(component, ri);
                    if (parsed != null) {
                        keep.add(ai.name);
                        // Use the new AppWidgetProviderInfo.
                        p.info = parsed.info;
                        // If it's enabled
                        final int M = p.instances.size();
                        if (M > 0) {
                            int[] appWidgetIds = getAppWidgetIds(p);
                            // Reschedule for the new updatePeriodMillis (don't worry about handling
                            // it specially if updatePeriodMillis didn't change because we just sent
                            // an update, and the next one will be updatePeriodMillis from now).
                            cancelBroadcasts(p);
                            registerForBroadcastsLocked(p, appWidgetIds);
                            // If it's currently showing, call back with the new
                            // AppWidgetProviderInfo.
                            for (int j = 0; j < M; j++) {
                                AppWidgetId id = p.instances.get(j);
                                id.views = null;
                                if (id.host != null && id.host.callbacks != null) {
                                    try {
                                        id.host.callbacks.providerChanged(id.appWidgetId, p.info,
                                                mUserId);
                                    } catch (RemoteException ex) {
                                        // It failed; remove the callback. No need to prune because
                                        // we know that this host is still referenced by this
                                        // instance.
                                        id.host.callbacks = null;
                                    }
                                }
                            }
                            // Now that we've told the host, push out an update.
                            sendUpdateIntentLocked(p, appWidgetIds);
                            providersUpdated = true;
                        }
                    }
                }
            }
        }

        // prune the ones we don't want to keep
        N = mInstalledProviders.size();
        for (int i = N - 1; i >= 0; i--) {
            Provider p = mInstalledProviders.get(i);
            if (pkgName.equals(p.info.provider.getPackageName())
                    && !keep.contains(p.info.provider.getClassName())) {
                if (removedProviders != null) {
                    removedProviders.add(p.info.provider);
                }
                removeProviderLocked(i, p);
                providersUpdated = true;
            }
        }

        return providersUpdated;
    }

    boolean removeProvidersForPackageLocked(String pkgName) {
        boolean providersRemoved = false;
        int N = mInstalledProviders.size();
        for (int i = N - 1; i >= 0; i--) {
            Provider p = mInstalledProviders.get(i);
            if (pkgName.equals(p.info.provider.getPackageName())) {
                removeProviderLocked(i, p);
                providersRemoved = true;
            }
        }

        // Delete the hosts for this package too
        //
        // By now, we have removed any AppWidgets that were in any hosts here,
        // so we don't need to worry about sending DISABLE broadcasts to them.
        N = mHosts.size();
        for (int i = N - 1; i >= 0; i--) {
            Host host = mHosts.get(i);
            if (pkgName.equals(host.packageName)) {
                deleteHostLocked(host);
            }
        }

        return providersRemoved;
    }

    void notifyHostsForProvidersChangedLocked() {
        final int N = mHosts.size();
        for (int i = N - 1; i >= 0; i--) {
            Host host = mHosts.get(i);
            try {
                if (host.callbacks != null) {
                    host.callbacks.providersChanged(mUserId);
                }
            } catch (RemoteException ex) {
                // It failed; remove the callback. No need to prune because
                // we know that this host is still referenced by this
                // instance.
                host.callbacks = null;
            }
        }
    }
}
