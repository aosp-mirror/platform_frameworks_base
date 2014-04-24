/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.INotificationListener;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.server.notification.NotificationManagerService.UserProfiles;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class NotificationListeners {
    private static final String TAG = "NotificationListeners";
    private static final boolean DBG = NotificationManagerService.DBG;

    private static final String ENABLED_NOTIFICATION_LISTENERS_SEPARATOR = ":";

    private final Context mContext;
    private final Handler mHandler;
    private final Object mMutex;
    private final UserProfiles mUserProfiles;
    private final SettingsObserver mSettingsObserver;

    // contains connections to all connected listeners, including app services
    // and system listeners
    private final ArrayList<NotificationListenerInfo> mListeners
            = new ArrayList<NotificationListenerInfo>();
    // things that will be put into mListeners as soon as they're ready
    private final ArrayList<String> mServicesBinding = new ArrayList<String>();
    // lists the component names of all enabled (and therefore connected) listener
    // app services for current profiles.
    private ArraySet<ComponentName> mEnabledListenersForCurrentProfiles
            = new ArraySet<ComponentName>();
    // Just the packages from mEnabledListenersForCurrentProfiles
    private ArraySet<String> mEnabledListenerPackageNames = new ArraySet<String>();

    public NotificationListeners(Context context, Handler handler, Object mutex,
            UserProfiles userProfiles) {
        mContext = context;
        mHandler = handler;
        mMutex = mutex;
        mUserProfiles = userProfiles;
        mSettingsObserver = new SettingsObserver(mHandler);
    }

    public void onBootPhaseAppsCanStart() {
        mSettingsObserver.observe();
    }

    protected void onServiceAdded(INotificationListener mListener) {
        // for subclasses
    }

    public void dump(PrintWriter pw) {
        pw.println("  Listeners (" + mEnabledListenersForCurrentProfiles.size()
                + ") enabled for current profiles:");
        for (ComponentName cmpt : mEnabledListenersForCurrentProfiles) {
            pw.println("    " + cmpt);
        }

        pw.println("  Live listeners (" + mListeners.size() + "):");
        for (NotificationListenerInfo info : mListeners) {
            pw.println("    " + info.component
                    + " (user " + info.userid + "): " + info.listener
                    + (info.isSystem?" SYSTEM":""));
        }
    }

    public void onPackagesChanged(boolean queryReplace, String[] pkgList) {
        boolean anyListenersInvolved = false;
        if (pkgList != null && (pkgList.length > 0)) {
            for (String pkgName : pkgList) {
                if (mEnabledListenerPackageNames.contains(pkgName)) {
                    anyListenersInvolved = true;
                }
            }
        }

        if (anyListenersInvolved) {
            // if we're not replacing a package, clean up orphaned bits
            if (!queryReplace) {
                disableNonexistentListeners();
            }
            // make sure we're still bound to any of our
            // listeners who may have just upgraded
            rebindListenerServices();
        }
    }

    /**
     * asynchronously notify all listeners about a new notification
     */
    public void notifyPostedLocked(StatusBarNotification sbn) {
        // make a copy in case changes are made to the underlying Notification object
        final StatusBarNotification sbnClone = sbn.clone();
        for (final NotificationListenerInfo info : mListeners) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    info.notifyPostedIfUserMatch(sbnClone);
                }
            });
        }
    }

    /**
     * asynchronously notify all listeners about a removed notification
     */
    public void notifyRemovedLocked(StatusBarNotification sbn) {
        // make a copy in case changes are made to the underlying Notification object
        // NOTE: this copy is lightweight: it doesn't include heavyweight parts of the notification
        final StatusBarNotification sbnLight = sbn.cloneLight();

        for (final NotificationListenerInfo info : mListeners) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    info.notifyRemovedIfUserMatch(sbnLight);
                }
            });
        }
    }

    public NotificationListenerInfo checkListenerTokenLocked(INotificationListener listener) {
        checkNullListener(listener);
        final IBinder token = listener.asBinder();
        final int N = mListeners.size();
        for (int i=0; i<N; i++) {
            final NotificationListenerInfo info = mListeners.get(i);
            if (info.listener.asBinder() == token) return info;
        }
        throw new SecurityException("Disallowed call from unknown listener: " + listener);
    }

    public void unregisterListener(INotificationListener listener, int userid) {
        checkNullListener(listener);
        // no need to check permissions; if your listener binder is in the list,
        // that's proof that you had permission to add it in the first place
        unregisterListenerImpl(listener, userid);
    }

    public void registerListener(INotificationListener listener,
            ComponentName component, int userid) {
        checkNullListener(listener);
        registerListenerImpl(listener, component, userid);
    }

    /**
     * Remove notification access for any services that no longer exist.
     */
    private void disableNonexistentListeners() {
        int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int N = userIds.length;
        for (int i = 0 ; i < N; ++i) {
            disableNonexistentListeners(userIds[i]);
        }
    }

    private void disableNonexistentListeners(int userId) {
        String flatIn = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                userId);
        if (!TextUtils.isEmpty(flatIn)) {
            if (DBG) Slog.v(TAG, "flat before: " + flatIn);
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> installedServices = pm.queryIntentServicesAsUser(
                    new Intent(NotificationListenerService.SERVICE_INTERFACE),
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                    userId);

            Set<ComponentName> installed = new ArraySet<ComponentName>();
            for (int i = 0, count = installedServices.size(); i < count; i++) {
                ResolveInfo resolveInfo = installedServices.get(i);
                ServiceInfo info = resolveInfo.serviceInfo;

                if (!android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE.equals(
                                info.permission)) {
                    Slog.w(TAG, "Skipping notification listener service "
                            + info.packageName + "/" + info.name
                            + ": it does not require the permission "
                            + android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE);
                    continue;
                }
                installed.add(new ComponentName(info.packageName, info.name));
            }

            String flatOut = "";
            if (!installed.isEmpty()) {
                String[] enabled = flatIn.split(ENABLED_NOTIFICATION_LISTENERS_SEPARATOR);
                ArrayList<String> remaining = new ArrayList<String>(enabled.length);
                for (int i = 0; i < enabled.length; i++) {
                    ComponentName enabledComponent = ComponentName.unflattenFromString(enabled[i]);
                    if (installed.contains(enabledComponent)) {
                        remaining.add(enabled[i]);
                    }
                }
                flatOut = TextUtils.join(ENABLED_NOTIFICATION_LISTENERS_SEPARATOR, remaining);
            }
            if (DBG) Slog.v(TAG, "flat after: " + flatOut);
            if (!flatIn.equals(flatOut)) {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                        flatOut, userId);
            }
        }
    }

    /**
     * Called whenever packages change, the user switches, or ENABLED_NOTIFICATION_LISTENERS
     * is altered. (For example in response to USER_SWITCHED in our broadcast receiver)
     */
    private void rebindListenerServices() {
        final int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int nUserIds = userIds.length;

        final SparseArray<String> flat = new SparseArray<String>();

        for (int i = 0; i < nUserIds; ++i) {
            flat.put(userIds[i], Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                    userIds[i]));
        }

        NotificationListenerInfo[] toRemove = new NotificationListenerInfo[mListeners.size()];
        final SparseArray<ArrayList<ComponentName>> toAdd
                = new SparseArray<ArrayList<ComponentName>>();

        synchronized (mMutex) {
            // unbind and remove all existing listeners
            toRemove = mListeners.toArray(toRemove);

            final ArraySet<ComponentName> newEnabled = new ArraySet<ComponentName>();
            final ArraySet<String> newPackages = new ArraySet<String>();

            for (int i = 0; i < nUserIds; ++i) {
                final ArrayList<ComponentName> add = new ArrayList<ComponentName>();
                toAdd.put(userIds[i], add);

                // decode the list of components
                String toDecode = flat.get(userIds[i]);
                if (toDecode != null) {
                    String[] components = toDecode.split(ENABLED_NOTIFICATION_LISTENERS_SEPARATOR);
                    for (int j = 0; j < components.length; j++) {
                        final ComponentName component
                                = ComponentName.unflattenFromString(components[j]);
                        if (component != null) {
                            newEnabled.add(component);
                            add.add(component);
                            newPackages.add(component.getPackageName());
                        }
                    }

                }
            }
            mEnabledListenersForCurrentProfiles = newEnabled;
            mEnabledListenerPackageNames = newPackages;
        }

        for (NotificationListenerInfo info : toRemove) {
            final ComponentName component = info.component;
            final int oldUser = info.userid;
            Slog.v(TAG, "disabling notification listener for user "
                    + oldUser + ": " + component);
            unregisterListenerService(component, info.userid);
        }

        for (int i = 0; i < nUserIds; ++i) {
            final ArrayList<ComponentName> add = toAdd.get(userIds[i]);
            final int N = add.size();
            for (int j = 0; j < N; j++) {
                final ComponentName component = add.get(j);
                Slog.v(TAG, "enabling notification listener for user " + userIds[i] + ": "
                        + component);
                registerListenerService(component, userIds[i]);
            }
        }
    }

    /**
     * Version of registerListener that takes the name of a
     * {@link android.service.notification.NotificationListenerService} to bind to.
     *
     * This is the mechanism by which third parties may subscribe to notifications.
     */
    private void registerListenerService(final ComponentName name, final int userid) {
        NotificationUtil.checkCallerIsSystem();

        if (DBG) Slog.v(TAG, "registerListenerService: " + name + " u=" + userid);

        synchronized (mMutex) {
            final String servicesBindingTag = name.toString() + "/" + userid;
            if (mServicesBinding.contains(servicesBindingTag)) {
                // stop registering this thing already! we're working on it
                return;
            }
            mServicesBinding.add(servicesBindingTag);

            final int N = mListeners.size();
            for (int i=N-1; i>=0; i--) {
                final NotificationListenerInfo info = mListeners.get(i);
                if (name.equals(info.component)
                        && info.userid == userid) {
                    // cut old connections
                    if (DBG) Slog.v(TAG, "    disconnecting old listener: " + info.listener);
                    mListeners.remove(i);
                    if (info.connection != null) {
                        mContext.unbindService(info.connection);
                    }
                }
            }

            Intent intent = new Intent(NotificationListenerService.SERVICE_INTERFACE);
            intent.setComponent(name);

            intent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                    R.string.notification_listener_binding_label);

            final PendingIntent pendingIntent = PendingIntent.getActivity(
                    mContext, 0, new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), 0);
            intent.putExtra(Intent.EXTRA_CLIENT_INTENT, pendingIntent);

            ApplicationInfo appInfo = null;
            try {
                appInfo = mContext.getPackageManager().getApplicationInfo(
                        name.getPackageName(), 0);
            } catch (NameNotFoundException e) {
                // Ignore if the package doesn't exist we won't be able to bind to the service.
            }
            final int targetSdkVersion =
                    appInfo != null ? appInfo.targetSdkVersion : Build.VERSION_CODES.BASE;

            try {
                if (DBG) Slog.v(TAG, "binding: " + intent);
                if (!mContext.bindServiceAsUser(intent,
                        new ServiceConnection() {
                            INotificationListener mListener;

                            @Override
                            public void onServiceConnected(ComponentName name, IBinder service) {
                                boolean added = false;
                                synchronized (mMutex) {
                                    mServicesBinding.remove(servicesBindingTag);
                                    try {
                                        mListener = INotificationListener.Stub.asInterface(service);
                                        NotificationListenerInfo info
                                                = new NotificationListenerInfo(
                                                        mListener, name, userid, this,
                                                        targetSdkVersion);
                                        service.linkToDeath(info, 0);
                                        added = mListeners.add(info);
                                    } catch (RemoteException e) {
                                        // already dead
                                    }
                                }
                                if (added) {
                                    onServiceAdded(mListener);
                                }
                            }

                            @Override
                            public void onServiceDisconnected(ComponentName name) {
                                Slog.v(TAG, "notification listener connection lost: " + name);
                            }
                        },
                        Context.BIND_AUTO_CREATE,
                        new UserHandle(userid)))
                {
                    mServicesBinding.remove(servicesBindingTag);
                    Slog.w(TAG, "Unable to bind listener service: " + intent);
                    return;
                }
            } catch (SecurityException ex) {
                Slog.e(TAG, "Unable to bind listener service: " + intent, ex);
                return;
            }
        }
    }

    /**
     * Remove a listener service for the given user by ComponentName
     */
    private void unregisterListenerService(ComponentName name, int userid) {
        NotificationUtil.checkCallerIsSystem();

        synchronized (mMutex) {
            final int N = mListeners.size();
            for (int i=N-1; i>=0; i--) {
                final NotificationListenerInfo info = mListeners.get(i);
                if (name.equals(info.component)
                        && info.userid == userid) {
                    mListeners.remove(i);
                    if (info.connection != null) {
                        try {
                            mContext.unbindService(info.connection);
                        } catch (IllegalArgumentException ex) {
                            // something happened to the service: we think we have a connection
                            // but it's bogus.
                            Slog.e(TAG, "Listener " + name + " could not be unbound: " + ex);
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes a listener from the list but does not unbind from the listener's service.
     *
     * @return the removed listener.
     */
    private NotificationListenerInfo removeListenerImpl(
            final INotificationListener listener, final int userid) {
        NotificationListenerInfo listenerInfo = null;
        synchronized (mMutex) {
            final int N = mListeners.size();
            for (int i=N-1; i>=0; i--) {
                final NotificationListenerInfo info = mListeners.get(i);
                if (info.listener.asBinder() == listener.asBinder()
                        && info.userid == userid) {
                    listenerInfo = mListeners.remove(i);
                }
            }
        }
        return listenerInfo;
    }

    private void checkNullListener(INotificationListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
    }

    private void registerListenerImpl(final INotificationListener listener,
            final ComponentName component, final int userid) {
        synchronized (mMutex) {
            try {
                NotificationListenerInfo info
                        = new NotificationListenerInfo(listener, component, userid,
                        /*isSystem*/ true, Build.VERSION_CODES.L);
                listener.asBinder().linkToDeath(info, 0);
                mListeners.add(info);
            } catch (RemoteException e) {
                // already dead
            }
        }
    }

    /**
     * Removes a listener from the list and unbinds from its service.
     */
    private void unregisterListenerImpl(final INotificationListener listener, final int userid) {
        NotificationListenerInfo info = removeListenerImpl(listener, userid);
        if (info != null && info.connection != null) {
            mContext.unbindService(info.connection);
        }
    }

    private class SettingsObserver extends ContentObserver {
        private final Uri ENABLED_NOTIFICATION_LISTENERS_URI
                = Settings.Secure.getUriFor(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);

        private SettingsObserver(Handler handler) {
            super(handler);
        }

        private void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(ENABLED_NOTIFICATION_LISTENERS_URI,
                    false, this, UserHandle.USER_ALL);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        private void update(Uri uri) {
            if (uri == null || ENABLED_NOTIFICATION_LISTENERS_URI.equals(uri)) {
                rebindListenerServices();
            }
        }
    }

    public class NotificationListenerInfo implements IBinder.DeathRecipient {
        public INotificationListener listener;
        public ComponentName component;
        public int userid;
        public boolean isSystem;
        public ServiceConnection connection;
        public int targetSdkVersion;

        public NotificationListenerInfo(INotificationListener listener, ComponentName component,
                int userid, boolean isSystem, int targetSdkVersion) {
            this.listener = listener;
            this.component = component;
            this.userid = userid;
            this.isSystem = isSystem;
            this.connection = null;
            this.targetSdkVersion = targetSdkVersion;
        }

        public NotificationListenerInfo(INotificationListener listener, ComponentName component,
                int userid, ServiceConnection connection, int targetSdkVersion) {
            this.listener = listener;
            this.component = component;
            this.userid = userid;
            this.isSystem = false;
            this.connection = connection;
            this.targetSdkVersion = targetSdkVersion;
        }

        public boolean enabledAndUserMatches(StatusBarNotification sbn) {
            final int nid = sbn.getUserId();
            if (!isEnabledForCurrentProfiles()) {
                return false;
            }
            if (this.userid == UserHandle.USER_ALL) return true;
            if (nid == UserHandle.USER_ALL || nid == this.userid) return true;
            return supportsProfiles() && mUserProfiles.isCurrentProfile(nid);
        }

        public boolean supportsProfiles() {
            return targetSdkVersion >= Build.VERSION_CODES.L;
        }

        public void notifyPostedIfUserMatch(StatusBarNotification sbn) {
            if (!enabledAndUserMatches(sbn)) {
                return;
            }
            try {
                listener.onNotificationPosted(sbn);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (posted): " + listener, ex);
            }
        }

        public void notifyRemovedIfUserMatch(StatusBarNotification sbn) {
            if (!enabledAndUserMatches(sbn)) return;
            try {
                listener.onNotificationRemoved(sbn);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (removed): " + listener, ex);
            }
        }

        @Override
        public void binderDied() {
            // Remove the listener, but don't unbind from the service. The system will bring the
            // service back up, and the onServiceConnected handler will readd the listener with the
            // new binding. If this isn't a bound service, and is just a registered
            // INotificationListener, just removing it from the list is all we need to do anyway.
            removeListenerImpl(this.listener, this.userid);
        }

        /** convenience method for looking in mEnabledListenersForCurrentProfiles */
        public boolean isEnabledForCurrentProfiles() {
            if (this.isSystem) return true;
            if (this.connection == null) return false;
            return mEnabledListenersForCurrentProfiles.contains(this.component);
        }
    }
}
