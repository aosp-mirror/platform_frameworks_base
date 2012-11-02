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

package com.android.server;

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
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
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
import java.util.Set;

class AppWidgetServiceImpl {

    private static final String TAG = "AppWidgetServiceImpl";
    private static final String SETTINGS_FILENAME = "appwidgets.xml";
    private static final int MIN_UPDATE_PERIOD = 30 * 60 * 1000; // 30 minutes

    private static boolean DBG = false;

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
    }

    static class Host {
        int uid;
        int hostId;
        String packageName;
        ArrayList<AppWidgetId> instances = new ArrayList<AppWidgetId>();
        IAppWidgetHost callbacks;
        boolean zombie; // if we're in safe mode, don't prune this just because nobody references it

        int tag; // for use while saving state (the index)
    }

    static class AppWidgetId {
        int appWidgetId;
        Provider provider;
        RemoteViews views;
        Bundle options;
        Host host;
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

    Context mContext;
    Locale mLocale;
    IPackageManager mPm;
    AlarmManager mAlarmManager;
    ArrayList<Provider> mInstalledProviders = new ArrayList<Provider>();
    int mNextAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID + 1;
    final ArrayList<AppWidgetId> mAppWidgetIds = new ArrayList<AppWidgetId>();
    ArrayList<Host> mHosts = new ArrayList<Host>();
    // set of package names
    HashSet<String> mPackagesWithBindWidgetPermission = new HashSet<String>();
    boolean mSafeMode;
    int mUserId;
    boolean mStateLoaded;
    int mMaxWidgetBitmapMemory;

    // These are for debugging only -- widgets are going missing in some rare instances
    ArrayList<Provider> mDeletedProviders = new ArrayList<Provider>();
    ArrayList<Host> mDeletedHosts = new ArrayList<Host>();

    AppWidgetServiceImpl(Context context, int userId) {
        mContext = context;
        mPm = AppGlobals.getPackageManager();
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mUserId = userId;
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
                saveStateLocked();
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
                    // The package was just added
                    for (String pkgName : pkgList) {
                        providersModified |= addProvidersForPackageLocked(pkgName);
                    }
                }
                saveStateLocked();
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
                        saveStateLocked();
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
            loadAppWidgetList();
            loadStateLocked();
            mStateLoaded = true;
        }
    }

    public int allocateAppWidgetId(String packageName, int hostId) {
        int callingUid = enforceSystemOrCallingUid(packageName);
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            int appWidgetId = mNextAppWidgetId++;

            Host host = lookupOrAddHostLocked(callingUid, packageName, hostId);

            AppWidgetId id = new AppWidgetId();
            id.appWidgetId = appWidgetId;
            id.host = host;

            host.instances.add(id);
            mAppWidgetIds.add(id);

            saveStateLocked();
            if (DBG) log("Allocating AppWidgetId for " + packageName + " host=" + hostId
                    + " id=" + appWidgetId);
            return appWidgetId;
        }
    }

    public void deleteAppWidgetId(int appWidgetId) {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null) {
                deleteAppWidgetLocked(id);
                saveStateLocked();
            }
        }
    }

    public void deleteHost(int hostId) {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            int callingUid = Binder.getCallingUid();
            Host host = lookupHostLocked(callingUid, hostId);
            if (host != null) {
                deleteHostLocked(host);
                saveStateLocked();
            }
        }
    }

    public void deleteAllHosts() {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            int callingUid = Binder.getCallingUid();
            final int N = mHosts.size();
            boolean changed = false;
            for (int i = N - 1; i >= 0; i--) {
                Host host = mHosts.get(i);
                if (host.uid == callingUid) {
                    deleteHostLocked(host);
                    changed = true;
                }
            }
            if (changed) {
                saveStateLocked();
            }
        }
    }

    void deleteHostLocked(Host host) {
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
                saveStateLocked();
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
        mContext.enforceCallingPermission(
                android.Manifest.permission.MODIFY_APPWIDGET_BIND_PERMISSIONS,
                "hasBindAppWidgetPermission packageName=" + packageName);

        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            return mPackagesWithBindWidgetPermission.contains(packageName);
        }
    }

    public void setBindAppWidgetPermission(String packageName, boolean permission) {
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
        }
        saveStateLocked();
    }

    // Binds to a specific RemoteViewsService
    public void bindRemoteViewsService(int appWidgetId, Intent intent, IBinder connection) {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id == null) {
                throw new IllegalArgumentException("bad appWidgetId");
            }
            final ComponentName componentName = intent.getComponent();
            try {
                final ServiceInfo si = AppGlobals.getPackageManager().getServiceInfo(componentName,
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
            // Bind to the RemoteViewsService (which will trigger a callback to the
            // RemoteViewsAdapter.onServiceConnected())
            final long token = Binder.clearCallingIdentity();
            try {
                conn = new ServiceConnectionProxy(key, connection);
                mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE, userId);
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
            } else {
                Log.e("AppWidgetService", "Error (unbindRemoteViewsService): Connection not bound");
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
            mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE, userId);
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
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null) {
                return cloneIfLocalBinder(id.views);
            }
            if (DBG) log("   couldn't find appwidgetid");
            return null;
        }
    }

    public List<AppWidgetProviderInfo> getInstalledProviders() {
        synchronized (mAppWidgetIds) {
            ensureStateLoadedLocked();
            final int N = mInstalledProviders.size();
            ArrayList<AppWidgetProviderInfo> result = new ArrayList<AppWidgetProviderInfo>(N);
            for (int i = 0; i < N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (!p.zombie) {
                    result.add(cloneIfLocalBinder(p.info));
                }
            }
            return result;
        }
    }

    public void updateAppWidgetIds(int[] appWidgetIds, RemoteViews views) {
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

    public void updateAppWidgetOptions(int appWidgetId, Bundle options) {
        synchronized (mAppWidgetIds) {
            options = cloneIfLocalBinder(options);
            ensureStateLoadedLocked();
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);

            if (id == null) {
                return;
            }

            Provider p = id.provider;
            // Merge the options
            id.options.putAll(options);

            // send the broacast saying that this appWidgetId has been deleted
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED);
            intent.setComponent(p.info.provider);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id.appWidgetId);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, id.options);
            mContext.sendBroadcastAsUser(intent, new UserHandle(mUserId));
            saveStateLocked();
        }
    }

    public Bundle getAppWidgetOptions(int appWidgetId) {
        synchronized (mAppWidgetIds) {
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
                if (id.views != null) {
                    // Only trigger a partial update for a widget if it has received a full update
                    updateAppWidgetInstanceLocked(id, views, true);
                }
            }
        }
    }

    public void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId) {
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
                    id.host.callbacks.updateAppWidget(id.appWidgetId, views);
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
                    id.host.callbacks.viewDataChanged(id.appWidgetId, viewId);
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
                            mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE, userId);
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
            ensureStateLoadedLocked();
            Host host = lookupHostLocked(Binder.getCallingUid(), hostId);
            if (host != null) {
                host.callbacks = null;
                pruneHostLocked(host);
            }
        }
    }

    boolean canAccessAppWidgetId(AppWidgetId id, int callingUid) {
        if (id.host.uid == callingUid) {
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
        final int N = mInstalledProviders.size();
        for (int i = 0; i < N; i++) {
            Provider p = mInstalledProviders.get(i);
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
            if (h.uid == uid && h.hostId == hostId) {
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
            mHosts.remove(host);
        }
    }

    void loadAppWidgetList() {
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
            mInstalledProviders.add(p);
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

            int providerIndex = 0;
            N = mInstalledProviders.size();
            for (int i = 0; i < N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (p.instances.size() > 0) {
                    out.startTag(null, "p");
                    out.attribute(null, "pkg", p.info.provider.getPackageName());
                    out.attribute(null, "cl", p.info.provider.getClassName());
                    out.endTag(null, "p");
                    p.tag = providerIndex;
                    providerIndex++;
                }
            }

            N = mHosts.size();
            for (int i = 0; i < N; i++) {
                Host host = mHosts.get(i);
                out.startTag(null, "h");
                out.attribute(null, "pkg", host.packageName);
                out.attribute(null, "id", Integer.toHexString(host.hostId));
                out.endTag(null, "h");
                host.tag = i;
            }

            N = mAppWidgetIds.size();
            for (int i = 0; i < N; i++) {
                AppWidgetId id = mAppWidgetIds.get(i);
                out.startTag(null, "g");
                out.attribute(null, "id", Integer.toHexString(id.appWidgetId));
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
                    if ("p".equals(tag)) {
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
                addProviderLocked(ri);
                providersAdded = true;
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
                                        id.host.callbacks.providerChanged(id.appWidgetId, p.info);
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
                    host.callbacks.providersChanged();
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
