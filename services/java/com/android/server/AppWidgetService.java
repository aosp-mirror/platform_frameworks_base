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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.widget.RemoteViews;

import com.android.internal.appwidget.IAppWidgetHost;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.internal.widget.IRemoteViewsAdapterConnection;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


/**
 * Redirects calls to this service to the instance of the service for the appropriate user.
 */
class AppWidgetService extends IAppWidgetService.Stub
{
    private static final String TAG = "AppWidgetService";

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
        private final IBinder mConnectionCb;

        ServiceConnectionProxy(Pair<Integer, Intent.FilterComparison> key, IBinder connectionCb) {
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

    Context mContext;
    Locale mLocale;
    PackageManager mPackageManager;
    AlarmManager mAlarmManager;
    ArrayList<Provider> mInstalledProviders = new ArrayList<Provider>();
    int mNextAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID + 1;
    final ArrayList<AppWidgetId> mAppWidgetIds = new ArrayList<AppWidgetId>();
    ArrayList<Host> mHosts = new ArrayList<Host>();
    boolean mSafeMode;


    private final SparseArray<AppWidgetServiceImpl> mAppWidgetServices;

    AppWidgetService(Context context) {
        mContext = context;
        mAppWidgetServices = new SparseArray<AppWidgetServiceImpl>(5);
        AppWidgetServiceImpl primary = new AppWidgetServiceImpl(context, 0);
        mAppWidgetServices.append(0, primary);
    }

    public void systemReady(boolean safeMode) {
        mSafeMode = safeMode;

        mAppWidgetServices.get(0).systemReady(safeMode);

        // Register for the boot completed broadcast, so we can send the
        // ENABLE broacasts. If we try to send them now, they time out,
        // because the system isn't ready to handle them yet.
        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);

        // Register for configuration changes so we can update the names
        // of the widgets when the locale changes.
        mContext.registerReceiver(mBroadcastReceiver, new IntentFilter(
                Intent.ACTION_CONFIGURATION_CHANGED), null, null);

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

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onUserRemoved(intent.getIntExtra(Intent.EXTRA_USERID, -1));
            }
        }, userFilter);
    }

    @Override
    public int allocateAppWidgetId(String packageName, int hostId) throws RemoteException {
        return getImplForUser().allocateAppWidgetId(packageName, hostId);
    }
    
    @Override
    public void deleteAppWidgetId(int appWidgetId) throws RemoteException {
        getImplForUser().deleteAppWidgetId(appWidgetId);
    }

    @Override
    public void deleteHost(int hostId) throws RemoteException {
        getImplForUser().deleteHost(hostId);
    }

    @Override
    public void deleteAllHosts() throws RemoteException {
        getImplForUser().deleteAllHosts();
    }

    @Override
    public void bindAppWidgetId(int appWidgetId, ComponentName provider) throws RemoteException {
        getImplForUser().bindAppWidgetId(appWidgetId, provider);
    }

    @Override
    public boolean bindAppWidgetIdIfAllowed(
            String packageName, int appWidgetId, ComponentName provider) throws RemoteException {
        return getImplForUser().bindAppWidgetIdIfAllowed(packageName, appWidgetId, provider);
    }

    @Override
    public boolean hasBindAppWidgetPermission(String packageName) throws RemoteException {
        return getImplForUser().hasBindAppWidgetPermission(packageName);
    }

    @Override
    public void setBindAppWidgetPermission(String packageName, boolean permission)
            throws RemoteException {
        getImplForUser().setBindAppWidgetPermission(packageName, permission);
    }

    @Override
    public void bindRemoteViewsService(int appWidgetId, Intent intent, IBinder connection)
            throws RemoteException {
        getImplForUser().bindRemoteViewsService(appWidgetId, intent, connection);
    }

    @Override
    public int[] startListening(IAppWidgetHost host, String packageName, int hostId,
            List<RemoteViews> updatedViews) throws RemoteException {
        return getImplForUser().startListening(host, packageName, hostId, updatedViews);
    }

    public void onUserRemoved(int userId) {
        AppWidgetServiceImpl impl = mAppWidgetServices.get(userId);
        if (userId < 1) return;

        if (impl == null) {
            AppWidgetServiceImpl.getSettingsFile(userId).delete();
        } else {
            impl.onUserRemoved();
        }
    }

    private AppWidgetServiceImpl getImplForUser() {
        final int userId = Binder.getOrigCallingUser();
        AppWidgetServiceImpl service = mAppWidgetServices.get(userId);
        if (service == null) {
            Slog.e(TAG, "Unable to find AppWidgetServiceImpl for the current user");
            // TODO: Verify that it's a valid user
            service = new AppWidgetServiceImpl(mContext, userId);
            service.systemReady(mSafeMode);
            // Assume that BOOT_COMPLETED was received, as this is a non-primary user.
            service.sendInitialBroadcasts();
            mAppWidgetServices.append(userId, service);
        }

        return service;
    }

    @Override
    public int[] getAppWidgetIds(ComponentName provider) throws RemoteException {
        return getImplForUser().getAppWidgetIds(provider);
    }

    @Override
    public AppWidgetProviderInfo getAppWidgetInfo(int appWidgetId) throws RemoteException {
        return getImplForUser().getAppWidgetInfo(appWidgetId);
    }

    @Override
    public RemoteViews getAppWidgetViews(int appWidgetId) throws RemoteException {
        return getImplForUser().getAppWidgetViews(appWidgetId);
    }

    @Override
    public void updateAppWidgetOptions(int appWidgetId, Bundle options) {
        getImplForUser().updateAppWidgetOptions(appWidgetId, options);
    }

    @Override
    public Bundle getAppWidgetOptions(int appWidgetId) {
        return getImplForUser().getAppWidgetOptions(appWidgetId);
    }

    static int[] getAppWidgetIds(Provider p) {
        int instancesSize = p.instances.size();
        int appWidgetIds[] = new int[instancesSize];
        for (int i=0; i<instancesSize; i++) {
            appWidgetIds[i] = p.instances.get(i).appWidgetId;
        }
        return appWidgetIds;
    }

    @Override
    public List<AppWidgetProviderInfo> getInstalledProviders() throws RemoteException {
        return getImplForUser().getInstalledProviders();
    }

    @Override
    public void notifyAppWidgetViewDataChanged(int[] appWidgetIds, int viewId)
            throws RemoteException {
        getImplForUser().notifyAppWidgetViewDataChanged(appWidgetIds, viewId);
    }

    @Override
    public void partiallyUpdateAppWidgetIds(int[] appWidgetIds, RemoteViews views)
            throws RemoteException {
        getImplForUser().partiallyUpdateAppWidgetIds(appWidgetIds, views);
    }

    @Override
    public void stopListening(int hostId) throws RemoteException {
        getImplForUser().stopListening(hostId);
    }

    @Override
    public void unbindRemoteViewsService(int appWidgetId, Intent intent) throws RemoteException {
        getImplForUser().unbindRemoteViewsService(appWidgetId, intent);
    }

    @Override
    public void updateAppWidgetIds(int[] appWidgetIds, RemoteViews views) throws RemoteException {
        getImplForUser().updateAppWidgetIds(appWidgetIds, views);
    }

    @Override
    public void updateAppWidgetProvider(ComponentName provider, RemoteViews views)
            throws RemoteException {
        getImplForUser().updateAppWidgetProvider(provider, views);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // Dump the state of all the app widget providers
        for (int i = 0; i < mAppWidgetServices.size(); i++) {
            AppWidgetServiceImpl service = mAppWidgetServices.valueAt(i);
            service.dump(fd, pw, args);
        }
    }

    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // Slog.d(TAG, "received " + action);
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                getImplForUser().sendInitialBroadcasts();
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                for (int i = 0; i < mAppWidgetServices.size(); i++) {
                    AppWidgetServiceImpl service = mAppWidgetServices.valueAt(i);
                    service.onConfigurationChanged();
                }
            } else {
                for (int i = 0; i < mAppWidgetServices.size(); i++) {
                    AppWidgetServiceImpl service = mAppWidgetServices.valueAt(i);
                    service.onBroadcastReceived(intent);
                }
            }
        }
    };
}
