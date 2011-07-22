/*
 * Copyright (C) 2007 The Android Open Source Project
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

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.commons.logging.impl.SimpleLog;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.Intent.FilterComparison;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.TypedValue;
import android.util.Xml;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.widget.IRemoteViewsAdapterConnection;
import com.android.internal.widget.IRemoteViewsFactory;

class AppWidgetService extends IAppWidgetService.Stub
{
    private static final String TAG = "AppWidgetService";

    private static final String SETTINGS_FILENAME = "appwidgets.xml";
    private static final String SETTINGS_TMP_FILENAME = SETTINGS_FILENAME + ".tmp";
    private static final int MIN_UPDATE_PERIOD = 30 * 60 * 1000; // 30 minutes

    /*
     * When identifying a Host or Provider based on the calling process, use the uid field.
     * When identifying a Host or Provider based on a package manager broadcast, use the
     * package given.
     */

    static class Provider {
        int uid;
        AppWidgetProviderInfo info;
        ArrayList<AppWidgetId> instances = new ArrayList<AppWidgetId>();
        PendingIntent broadcast;
        boolean zombie; // if we're in safe mode, don't prune this just because nobody references it
        
        int tag;    // for use while saving state (the index)
    }

    static class Host {
        int uid;
        int hostId;
        String packageName;
        ArrayList<AppWidgetId> instances = new ArrayList<AppWidgetId>();
        IAppWidgetHost callbacks;
        boolean zombie; // if we're in safe mode, don't prune this just because nobody references it
        
        int tag;    // for use while saving state (the index)
    }

    static class AppWidgetId {
        int appWidgetId;
        Provider provider;
        RemoteViews views;
        Host host;
    }

    /**
     * Acts as a proxy between the ServiceConnection and the RemoteViewsAdapterConnection.
     * This needs to be a static inner class since a reference to the ServiceConnection is held
     * globally and may lead us to leak AppWidgetService instances (if there were more than one).
     */
    static class ServiceConnectionProxy implements ServiceConnection {
        private final Pair<Integer, Intent.FilterComparison> mKey;
        private final IBinder mConnectionCb;

        ServiceConnectionProxy(Pair<Integer, Intent.FilterComparison> key, IBinder connectionCb) {
            mKey = key;
            mConnectionCb = connectionCb;
        }
        public void onServiceConnected(ComponentName name, IBinder service) {
            final IRemoteViewsAdapterConnection cb =
                IRemoteViewsAdapterConnection.Stub.asInterface(mConnectionCb);
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
            final IRemoteViewsAdapterConnection cb =
                IRemoteViewsAdapterConnection.Stub.asInterface(mConnectionCb);
            try {
                cb.onServiceDisconnected();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // Manages active connections to RemoteViewsServices
    private final HashMap<Pair<Integer, FilterComparison>, ServiceConnection>
        mBoundRemoteViewsServices = new HashMap<Pair<Integer,FilterComparison>,ServiceConnection>();
    // Manages persistent references to RemoteViewsServices from different App Widgets
    private final HashMap<FilterComparison, HashSet<Integer>>
        mRemoteViewsServicesAppWidgets = new HashMap<FilterComparison, HashSet<Integer>>();

    Context mContext;
    Locale mLocale;
    PackageManager mPackageManager;
    AlarmManager mAlarmManager;
    ArrayList<Provider> mInstalledProviders = new ArrayList<Provider>();
    int mNextAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID + 1;
    final ArrayList<AppWidgetId> mAppWidgetIds = new ArrayList<AppWidgetId>();
    ArrayList<Host> mHosts = new ArrayList<Host>();
    boolean mSafeMode;

    AppWidgetService(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
    }

    public void systemReady(boolean safeMode) {
        mSafeMode = safeMode;

        loadAppWidgetList();
        loadStateLocked();

        // Register for the boot completed broadcast, so we can send the
        // ENABLE broacasts.  If we try to send them now, they time out,
        // because the system isn't ready to handle them yet.
        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);

        // Register for configuration changes so we can update the names
        // of the widgets when the locale changes.
        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), null, null);

        // Register for broadcasts about package install, etc., so we can
        // update the provider list.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(mBroadcastReceiver, filter);
        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mBroadcastReceiver, sdFilter);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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
                Provider p = mInstalledProviders.get(i);
                AppWidgetProviderInfo info = p.info;
                pw.print("  ["); pw.print(i); pw.print("] provider ");
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
                        pw.print(" autoAdvanceViewId=");
                        pw.print(info.autoAdvanceViewId);
                        pw.print(" initialLayout=#");
                        pw.print(Integer.toHexString(info.initialLayout));
                        pw.print(" zombie="); pw.println(p.zombie);
            }

            N = mAppWidgetIds.size();
            pw.println(" ");
            pw.println("AppWidgetIds:");
            for (int i=0; i<N; i++) {
                AppWidgetId id = mAppWidgetIds.get(i);
                pw.print("  ["); pw.print(i); pw.print("] id=");
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

            N = mHosts.size();
            pw.println(" ");
            pw.println("Hosts:");
            for (int i=0; i<N; i++) {
                Host host = mHosts.get(i);
                pw.print("  ["); pw.print(i); pw.print("] hostId=");
                        pw.print(host.hostId); pw.print(' ');
                        pw.print(host.packageName); pw.print('/');
                        pw.print(host.uid); pw.println(':');
                pw.print("    callbacks="); pw.println(host.callbacks);
                pw.print("    instances.size="); pw.print(host.instances.size());
                        pw.print(" zombie="); pw.println(host.zombie);
            }
        }
    }

    public int allocateAppWidgetId(String packageName, int hostId) {
        int callingUid = enforceCallingUid(packageName);
        synchronized (mAppWidgetIds) {
            int appWidgetId = mNextAppWidgetId++;

            Host host = lookupOrAddHostLocked(callingUid, packageName, hostId);

            AppWidgetId id = new AppWidgetId();
            id.appWidgetId = appWidgetId;
            id.host = host;

            host.instances.add(id);
            mAppWidgetIds.add(id);

            saveStateLocked();

            return appWidgetId;
        }
    }

    public void deleteAppWidgetId(int appWidgetId) {
        synchronized (mAppWidgetIds) {
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null) {
                deleteAppWidgetLocked(id);
                saveStateLocked();
            }
        }
    }

    public void deleteHost(int hostId) {
        synchronized (mAppWidgetIds) {
            int callingUid = getCallingUid();
            Host host = lookupHostLocked(callingUid, hostId);
            if (host != null) {
                deleteHostLocked(host);
                saveStateLocked();
            }
        }
    }

    public void deleteAllHosts() {
        synchronized (mAppWidgetIds) {
            int callingUid = getCallingUid();
            final int N = mHosts.size();
            boolean changed = false;
            for (int i=N-1; i>=0; i--) {
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
        for (int i=N-1; i>=0; i--) {
            AppWidgetId id = host.instances.get(i);
            deleteAppWidgetLocked(id);
        }
        host.instances.clear();
        mHosts.remove(host);
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
                mContext.sendBroadcast(intent);
                if (p.instances.size() == 0) {
                    // cancel the future updates
                    cancelBroadcasts(p);

                    // send the broacast saying that the provider is not in use any more
                    intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_DISABLED);
                    intent.setComponent(p.info.provider);
                    mContext.sendBroadcast(intent);
                }
            }
        }
    }

    void cancelBroadcasts(Provider p) {
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

    public void bindAppWidgetId(int appWidgetId, ComponentName provider) {
        mContext.enforceCallingPermission(android.Manifest.permission.BIND_APPWIDGET,
                "bindGagetId appWidgetId=" + appWidgetId + " provider=" + provider);
        
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mAppWidgetIds) {
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
                if (id == null) {
                    throw new IllegalArgumentException("bad appWidgetId");
                }
                if (id.provider != null) {
                    throw new IllegalArgumentException("appWidgetId " + appWidgetId + " already bound to "
                            + id.provider.info.provider);
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
                p.instances.add(id);
                int instancesSize = p.instances.size();
                if (instancesSize == 1) {
                    // tell the provider that it's ready
                    sendEnableIntentLocked(p);
                }
    
                // send an update now -- We need this update now, and just for this appWidgetId.
                // It's less critical when the next one happens, so when we schdule the next one,
                // we add updatePeriodMillis to its start time.  That time will have some slop,
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

    // Binds to a specific RemoteViewsService
    public void bindRemoteViewsService(int appWidgetId, Intent intent, IBinder connection) {
        synchronized (mAppWidgetIds) {
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id == null) {
                throw new IllegalArgumentException("bad appWidgetId");
            }
            final ComponentName componentName = intent.getComponent();
            try {
                final ServiceInfo si = mContext.getPackageManager().getServiceInfo(componentName,
                        PackageManager.GET_PERMISSIONS);
                if (!android.Manifest.permission.BIND_REMOTEVIEWS.equals(si.permission)) {
                    throw new SecurityException("Selected service does not require "
                            + android.Manifest.permission.BIND_REMOTEVIEWS
                            + ": " + componentName);
                }
            } catch (PackageManager.NameNotFoundException e) {
                throw new IllegalArgumentException("Unknown component " + componentName);
            }

            // If there is already a connection made for this service intent, then disconnect from
            // that first.  (This does not allow multiple connections to the same service under
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

            // Bind to the RemoteViewsService (which will trigger a callback to the
            // RemoteViewsAdapter.onServiceConnected())
            final long token = Binder.clearCallingIdentity();
            try {
                conn = new ServiceConnectionProxy(key, connection);
                mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
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
            // Unbind from the RemoteViewsService (which will trigger a callback to the bound
            // RemoteViewsAdapter)
            Pair<Integer, FilterComparison> key = Pair.create(appWidgetId,
                    new FilterComparison(intent));
            if (mBoundRemoteViewsServices.containsKey(key)) {
                // We don't need to use the appWidgetId until after we are sure there is something
                // to unbind.  Note that this may mask certain issues with apps calling unbind()
                // more than necessary.
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
                if (id == null) {
                    throw new IllegalArgumentException("bad appWidgetId");
                }

                ServiceConnectionProxy conn =
                    (ServiceConnectionProxy) mBoundRemoteViewsServices.get(key);
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
        Iterator<Pair<Integer, Intent.FilterComparison>> it =
            mBoundRemoteViewsServices.keySet().iterator();
        while (it.hasNext()) {
            final Pair<Integer, Intent.FilterComparison> key = it.next();
            if (key.first.intValue() == appWidgetId) {
                final ServiceConnectionProxy conn = (ServiceConnectionProxy)
                        mBoundRemoteViewsServices.get(key);
                conn.disconnect();
                mContext.unbindService(conn);
                it.remove();
            }
        }

        // Check if we need to destroy any services (if no other app widgets are
        // referencing the same service)
        decrementAppWidgetServiceRefCount(appWidgetId);
    }

    // Destroys the cached factory on the RemoteViewsService's side related to the specified intent
    private void destroyRemoteViewsService(final Intent intent) {
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final IRemoteViewsFactory cb =
                    IRemoteViewsFactory.Stub.asInterface(service);
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

        // Bind to the service and remove the static intent->factory mapping in the
        // RemoteViewsService.
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
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
    private void decrementAppWidgetServiceRefCount(int appWidgetId) {
        Iterator<FilterComparison> it =
            mRemoteViewsServicesAppWidgets.keySet().iterator();
        while (it.hasNext()) {
            final FilterComparison key = it.next();
            final HashSet<Integer> ids = mRemoteViewsServicesAppWidgets.get(key);
            if (ids.remove(appWidgetId)) {
                // If we have removed the last app widget referencing this service, then we
                // should destroy it and remove it from this set
                if (ids.isEmpty()) {
                    destroyRemoteViewsService(key.getIntent());
                    it.remove();
                }
            }
        }
    }

    public AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId) {
        synchronized (mAppWidgetIds) {
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null && id.provider != null && !id.provider.zombie) {
                return id.provider.info;
            }
            return null;
        }
    }

    public RemoteViews getAppWidgetViews(int appWidgetId) {
        synchronized (mAppWidgetIds) {
            AppWidgetId id = lookupAppWidgetIdLocked(appWidgetId);
            if (id != null) {
                return id.views;
            }
            return null;
        }
    }

    public List<AppWidgetProviderInfo> getInstalledProviders() {
        synchronized (mAppWidgetIds) {
            final int N = mInstalledProviders.size();
            ArrayList<AppWidgetProviderInfo> result = new ArrayList<AppWidgetProviderInfo>(N);
            for (int i=0; i<N; i++) {
                Provider p = mInstalledProviders.get(i);
                if (!p.zombie) {
                    result.add(p.info);
                }
            }
            return result;
        }
    }

    public void updateAppWidgetIds(int[] appWidgetIds, RemoteViews views) {
        if (appWidgetIds == null) {
            return;
        }
        if (appWidgetIds.length == 0) {
            return;
        }
        final int N = appWidgetIds.length;

        synchronized (mAppWidgetIds) {
            for (int i=0; i<N; i++) {
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetIds[i]);
                updateAppWidgetInstanceLocked(id, views);
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
            for (int i=0; i<N; i++) {
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetIds[i]);
                updateAppWidgetInstanceLocked(id, views, true);
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
            for (int i=0; i<N; i++) {
                AppWidgetId id = lookupAppWidgetIdLocked(appWidgetIds[i]);
                notifyAppWidgetViewDataChangedInstanceLocked(id, viewId);
            }
        }
    }

    public void updateAppWidgetProvider(ComponentName provider, RemoteViews views) {
        synchronized (mAppWidgetIds) {
            Provider p = lookupProviderLocked(provider);
            if (p == null) {
                Slog.w(TAG, "updateAppWidgetProvider: provider doesn't exist: " + provider);
                return;
            }
            ArrayList<AppWidgetId> instances = p.instances;
            final int N = instances.size();
            for (int i=0; i<N; i++) {
                AppWidgetId id = instances.get(i);
                updateAppWidgetInstanceLocked(id, views);
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

            // We do not want to save this RemoteViews
            if (!isPartialUpdate) id.views = views;

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
        }
    }

    public int[] startListening(IAppWidgetHost callbacks, String packageName, int hostId,
            List<RemoteViews> updatedViews) {
        int callingUid = enforceCallingUid(packageName);
        synchronized (mAppWidgetIds) {
            Host host = lookupOrAddHostLocked(callingUid, packageName, hostId);
            host.callbacks = callbacks;

            updatedViews.clear();

            ArrayList<AppWidgetId> instances = host.instances;
            int N = instances.size();
            int[] updatedIds = new int[N];
            for (int i=0; i<N; i++) {
                AppWidgetId id = instances.get(i);
                updatedIds[i] = id.appWidgetId;
                updatedViews.add(id.views);
            }
            return updatedIds;
        }
    }

    public void stopListening(int hostId) {
        synchronized (mAppWidgetIds) {
            Host host = lookupHostLocked(getCallingUid(), hostId);
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
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.BIND_APPWIDGET)
                == PackageManager.PERMISSION_GRANTED) {
            // Apps that can bind have access to all appWidgetIds.
            return true;
        }
        // Nobody else can access it.
        return false;
    }

   AppWidgetId lookupAppWidgetIdLocked(int appWidgetId) {
        int callingUid = getCallingUid();
        final int N = mAppWidgetIds.size();
        for (int i=0; i<N; i++) {
            AppWidgetId id = mAppWidgetIds.get(i);
            if (id.appWidgetId == appWidgetId && canAccessAppWidgetId(id, callingUid)) {
                return id;
            }
        }
        return null;
    }

    Provider lookupProviderLocked(ComponentName provider) {
        final String className = provider.getClassName();
        final int N = mInstalledProviders.size();
        for (int i=0; i<N; i++) {
            Provider p = mInstalledProviders.get(i);
            if (p.info.provider.equals(provider) || className.equals(p.info.oldName)) {
                return p;
            }
        }
        return null;
    }

    Host lookupHostLocked(int uid, int hostId) {
        final int N = mHosts.size();
        for (int i=0; i<N; i++) {
            Host h = mHosts.get(i);
            if (h.uid == uid && h.hostId == hostId) {
                return h;
            }
        }
        return null;
    }

    Host lookupOrAddHostLocked(int uid, String packageName, int hostId) {
        final int N = mHosts.size();
        for (int i=0; i<N; i++) {
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
        PackageManager pm = mPackageManager;

        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        List<ResolveInfo> broadcastReceivers = pm.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA);

        final int N = broadcastReceivers == null ? 0 : broadcastReceivers.size();
        for (int i=0; i<N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            addProviderLocked(ri);
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
        for (int i=0; i<N; i++) {
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
        // no need to send the DISABLE broadcast, since the receiver is gone anyway
        cancelBroadcasts(p);
    }

    void sendEnableIntentLocked(Provider p) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_ENABLED);
        intent.setComponent(p.info.provider);
        mContext.sendBroadcast(intent);
    }

    void sendUpdateIntentLocked(Provider p, int[] appWidgetIds) {
        if (appWidgetIds != null && appWidgetIds.length > 0) {
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
            intent.setComponent(p.info.provider);
            mContext.sendBroadcast(intent);
        }
    }

    void registerForBroadcastsLocked(Provider p, int[] appWidgetIds) {
        if (p.info.updatePeriodMillis > 0) {
            // if this is the first instance, set the alarm.  otherwise,
            // rely on the fact that we've already set it and that
            // PendingIntent.getBroadcast will update the extras.
            boolean alreadyRegistered = p.broadcast != null;
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
            intent.setComponent(p.info.provider);
            long token = Binder.clearCallingIdentity();
            try {
                p.broadcast = PendingIntent.getBroadcast(mContext, 1, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            if (!alreadyRegistered) {
                long period = p.info.updatePeriodMillis;
                if (period < MIN_UPDATE_PERIOD) {
                    period = MIN_UPDATE_PERIOD;
                }
                mAlarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + period, period, p.broadcast);
            }
        }
    }
    
    static int[] getAppWidgetIds(Provider p) {
        int instancesSize = p.instances.size();
        int appWidgetIds[] = new int[instancesSize];
        for (int i=0; i<instancesSize; i++) {
            appWidgetIds[i] = p.instances.get(i).appWidgetId;
        }
        return appWidgetIds;
    }
    
    public int[] getAppWidgetIds(ComponentName provider) {
        synchronized (mAppWidgetIds) {
            Provider p = lookupProviderLocked(provider);
            if (p != null && getCallingUid() == p.uid) {
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
            parser = activityInfo.loadXmlMetaData(mPackageManager,
                    AppWidgetManager.META_DATA_APPWIDGET_PROVIDER);
            if (parser == null) {
                Slog.w(TAG, "No " + AppWidgetManager.META_DATA_APPWIDGET_PROVIDER + " meta-data for "
                        + "AppWidget provider '" + component + '\'');
                return null;
            }

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
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
            // If metaData was null, we would have returned earlier when getting
            // the parser No need to do the check here
            info.oldName = activityInfo.metaData.getString(
                    AppWidgetManager.META_DATA_APPWIDGET_OLD_NAME);

            info.provider = component;
            p.uid = activityInfo.applicationInfo.uid;

            Resources res = mPackageManager.getResourcesForApplication(
                    activityInfo.applicationInfo);

            TypedArray sa = res.obtainAttributes(attrs,
                    com.android.internal.R.styleable.AppWidgetProviderInfo);

            // These dimensions has to be resolved in the application's context.
            // We simply send back the raw complex data, which will be
            // converted to dp in {@link AppWidgetManager#getAppWidgetInfo}.
            TypedValue value = sa.peekValue(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_minWidth);
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
            String className = sa.getString(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_configure);
            if (className != null) {
                info.configure = new ComponentName(component.getPackageName(), className);
            }
            info.label = activityInfo.loadLabel(mPackageManager).toString();
            info.icon = ri.getIconResource();
            info.previewImage = sa.getResourceId(
            		com.android.internal.R.styleable.AppWidgetProviderInfo_previewImage, 0);
            info.autoAdvanceViewId = sa.getResourceId(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_autoAdvanceViewId, -1);
            info.resizeMode = sa.getInt(
                    com.android.internal.R.styleable.AppWidgetProviderInfo_resizeMode,
                    AppWidgetProviderInfo.RESIZE_NONE);

            sa.recycle();
        } catch (Exception e) {
            // Ok to catch Exception here, because anything going wrong because
            // of what a client process passes to us should not be fatal for the
            // system process.
            Slog.w(TAG, "XML parsing failed for AppWidget provider '" + component + '\'', e);
            return null;
        } finally {
            if (parser != null) parser.close();
        }
        return p;
    }

    int getUidForPackage(String packageName) throws PackageManager.NameNotFoundException {
        PackageInfo pkgInfo = mPackageManager.getPackageInfo(packageName, 0);
        if (pkgInfo == null || pkgInfo.applicationInfo == null) {
            throw new PackageManager.NameNotFoundException();
        }
        return pkgInfo.applicationInfo.uid;
    }

    int enforceCallingUid(String packageName) throws IllegalArgumentException {
        int callingUid = getCallingUid();
        int packageUid;
        try {
            packageUid = getUidForPackage(packageName);
        } catch (PackageManager.NameNotFoundException ex) {
            throw new IllegalArgumentException("packageName and uid don't match packageName="
                    + packageName);
        }
        if (callingUid != packageUid) {
            throw new IllegalArgumentException("packageName and uid don't match packageName="
                    + packageName);
        }
        return callingUid;
    }

    void sendInitialBroadcasts() {
        synchronized (mAppWidgetIds) {
            final int N = mInstalledProviders.size();
            for (int i=0; i<N; i++) {
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
        File temp = savedStateTempFile();
        File real = savedStateRealFile();

        // prefer the real file.  If it doesn't exist, use the temp one, and then copy it to the
        // real one.  if there is both a real file and a temp one, assume that the temp one isn't
        // fully written and delete it.
        if (real.exists()) {
            readStateFromFileLocked(real);
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } else if (temp.exists()) {
            readStateFromFileLocked(temp);
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(real);
        }
    }
    
    void saveStateLocked() {
        File temp = savedStateTempFile();
        File real = savedStateRealFile();

        if (!real.exists()) {
            // If the real one doesn't exist, it's either because this is the first time
            // or because something went wrong while copying them.  In this case, we can't
            // trust anything that's in temp.  In order to have the loadState code not
            // use the temporary one until it's fully written, create an empty file
            // for real, which will we'll shortly delete.
            try {
                //noinspection ResultOfMethodCallIgnored
                real.createNewFile();
            } catch (IOException e) {
                // Ignore
            }
        }

        if (temp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }

        if (!writeStateToFileLocked(temp)) {
            Slog.w(TAG, "Failed to persist new settings");
            return;
        }

        //noinspection ResultOfMethodCallIgnored
        real.delete();
        //noinspection ResultOfMethodCallIgnored
        temp.renameTo(real);
    }

    boolean writeStateToFileLocked(File file) {
        FileOutputStream stream = null;
        int N;

        try {
            stream = new FileOutputStream(file, false);
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(stream, "utf-8");
            out.startDocument(null, true);

            
            out.startTag(null, "gs");

            int providerIndex = 0;
            N = mInstalledProviders.size();
            for (int i=0; i<N; i++) {
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
            for (int i=0; i<N; i++) {
                Host host = mHosts.get(i);
                out.startTag(null, "h");
                out.attribute(null, "pkg", host.packageName);
                out.attribute(null, "id", Integer.toHexString(host.hostId));
                out.endTag(null, "h");
                host.tag = i;
            }

            N = mAppWidgetIds.size();
            for (int i=0; i<N; i++) {
                AppWidgetId id = mAppWidgetIds.get(i);
                out.startTag(null, "g");
                out.attribute(null, "id", Integer.toHexString(id.appWidgetId));
                out.attribute(null, "h", Integer.toHexString(id.host.tag));
                if (id.provider != null) {
                    out.attribute(null, "p", Integer.toHexString(id.provider.tag));
                }
                out.endTag(null, "g");
            }

            out.endTag(null, "gs");

            out.endDocument();
            stream.close();
            return true;
        } catch (IOException e) {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (IOException ex) {
                // Ignore
            }
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            return false;
        }
    }

    void readStateFromFileLocked(File file) {
        FileInputStream stream = null;

        boolean success = false;

        try {
            stream = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            int type;
            int providerIndex = 0;
            HashMap<Integer,Provider> loadedProviders = new HashMap<Integer, Provider>();
            do {
                type = parser.next();
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    if ("p".equals(tag)) {
                        // TODO: do we need to check that this package has the same signature
                        // as before?
                        String pkg = parser.getAttributeValue(null, "pkg");
                        String cl = parser.getAttributeValue(null, "cl");

                        final PackageManager packageManager = mContext.getPackageManager();
                        try {
                            packageManager.getReceiverInfo(new ComponentName(pkg, cl), 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            String[] pkgs = packageManager.currentToCanonicalPackageNames(
                                    new String[] { pkg });
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
                    }
                    else if ("h".equals(tag)) {
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
                            // so that they're not pruned from our list.  Otherwise, we do.
                            host.hostId = Integer.parseInt(
                                    parser.getAttributeValue(null, "id"), 16);
                            mHosts.add(host);
                        }
                    }
                    else if ("g".equals(tag)) {
                        AppWidgetId id = new AppWidgetId();
                        id.appWidgetId = Integer.parseInt(parser.getAttributeValue(null, "id"), 16);
                        if (id.appWidgetId >= mNextAppWidgetId) {
                            mNextAppWidgetId = id.appWidgetId + 1;
                        }

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
                                // This provider is gone.  We just let the host figure out
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
            Slog.w(TAG, "failed parsing " + file, e);
        } catch (NumberFormatException e) {
            Slog.w(TAG, "failed parsing " + file, e);
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "failed parsing " + file, e);
        } catch (IOException e) {
            Slog.w(TAG, "failed parsing " + file, e);
        } catch (IndexOutOfBoundsException e) {
            Slog.w(TAG, "failed parsing " + file, e);
        }
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        if (success) {
            // delete any hosts that didn't manage to get connected (should happen)
            // if it matters, they'll be reconnected.
            for (int i=mHosts.size()-1; i>=0; i--) {
                pruneHostLocked(mHosts.get(i));
            }
        } else {
            // failed reading, clean up
            mAppWidgetIds.clear();
            mHosts.clear();
            final int N = mInstalledProviders.size();
            for (int i=0; i<N; i++) {
                mInstalledProviders.get(i).instances.clear();
            }
        }
    }

    File savedStateTempFile() {
        return new File("/data/system/" + SETTINGS_TMP_FILENAME);
        //return new File(mContext.getFilesDir(), SETTINGS_FILENAME);
    }

    File savedStateRealFile() {
        return new File("/data/system/" + SETTINGS_FILENAME);
        //return new File(mContext.getFilesDir(), SETTINGS_TMP_FILENAME);
    }

    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            //Slog.d(TAG, "received " + action);
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                sendInitialBroadcasts();
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                Locale revised = Locale.getDefault();
                if (revised == null || mLocale == null ||
                    !(revised.equals(mLocale))) {
                    mLocale = revised;

                    synchronized (mAppWidgetIds) {
                        int N = mInstalledProviders.size();
                        for (int i=N-1; i>=0; i--) {
                            Provider p = mInstalledProviders.get(i);
                            String pkgName = p.info.provider.getPackageName();
                            updateProvidersForPackageLocked(pkgName);
                        }
                        saveStateLocked();
                    }
                }
            } else {
                boolean added = false;
                boolean changed = false;
                String pkgList[] = null;
                if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    added = true;
                } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    added = false;
                } else  {
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
                        Bundle extras = intent.getExtras();
                        if (changed || (extras != null &&
                                    extras.getBoolean(Intent.EXTRA_REPLACING, false))) {
                            for (String pkgName : pkgList) {
                                // The package was just upgraded
                                updateProvidersForPackageLocked(pkgName);
                            }
                        } else {
                            // The package was just added
                            for (String pkgName : pkgList) {
                                addProvidersForPackageLocked(pkgName);
                            }
                        }
                        saveStateLocked();
                    }
                } else {
                    Bundle extras = intent.getExtras();
                    if (extras != null && extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                        // The package is being updated.  We'll receive a PACKAGE_ADDED shortly.
                    } else {
                        synchronized (mAppWidgetIds) {
                            for (String pkgName : pkgList) {
                                removeProvidersForPackageLocked(pkgName);
                                saveStateLocked();
                            }
                        }
                    }
                }
            }
        }
    };

    void addProvidersForPackageLocked(String pkgName) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.setPackage(pkgName);
        List<ResolveInfo> broadcastReceivers = mPackageManager.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA);

        final int N = broadcastReceivers == null ? 0 : broadcastReceivers.size();
        for (int i=0; i<N; i++) {
            ResolveInfo ri = broadcastReceivers.get(i);
            ActivityInfo ai = ri.activityInfo;
            if ((ai.applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                continue;
            }
            if (pkgName.equals(ai.packageName)) {
                addProviderLocked(ri);
            }
        }
    }

    void updateProvidersForPackageLocked(String pkgName) {
        HashSet<String> keep = new HashSet<String>();
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        intent.setPackage(pkgName);
        List<ResolveInfo> broadcastReceivers = mPackageManager.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA);

        // add the missing ones and collect which ones to keep
        int N = broadcastReceivers == null ? 0 : broadcastReceivers.size();
        for (int i=0; i<N; i++) {
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
                            // If it's currently showing, call back with the new AppWidgetProviderInfo.
                            for (int j=0; j<M; j++) {
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
                        }
                    }
                }
            }
        }

        // prune the ones we don't want to keep
        N = mInstalledProviders.size();
        for (int i=N-1; i>=0; i--) {
            Provider p = mInstalledProviders.get(i);
            if (pkgName.equals(p.info.provider.getPackageName())
                    && !keep.contains(p.info.provider.getClassName())) {
                removeProviderLocked(i, p);
            }
        }
    }

    void removeProvidersForPackageLocked(String pkgName) {
        int N = mInstalledProviders.size();
        for (int i=N-1; i>=0; i--) {
            Provider p = mInstalledProviders.get(i);
            if (pkgName.equals(p.info.provider.getPackageName())) {
                removeProviderLocked(i, p);
            }
        }

        // Delete the hosts for this package too
        //
        // By now, we have removed any AppWidgets that were in any hosts here,
        // so we don't need to worry about sending DISABLE broadcasts to them.
        N = mHosts.size();
        for (int i=N-1; i>=0; i--) {
            Host host = mHosts.get(i);
            if (pkgName.equals(host.packageName)) {
                deleteHostLocked(host);
            }
        }
    }
}

