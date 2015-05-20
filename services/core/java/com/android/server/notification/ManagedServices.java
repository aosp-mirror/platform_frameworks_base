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

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages the lifecycle of application-provided services bound by system server.
 *
 * Services managed by this helper must have:
 *  - An associated system settings value with a list of enabled component names.
 *  - A well-known action for services to use in their intent-filter.
 *  - A system permission for services to require in order to ensure system has exclusive binding.
 *  - A settings page for user configuration of enabled services, and associated intent action.
 *  - A remote interface definition (aidl) provided by the service used for communication.
 */
abstract public class ManagedServices {
    protected final String TAG = getClass().getSimpleName();
    protected final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final String ENABLED_SERVICES_SEPARATOR = ":";

    protected final Context mContext;
    protected final Object mMutex;
    private final UserProfiles mUserProfiles;
    private final SettingsObserver mSettingsObserver;
    private final Config mConfig;
    private ArraySet<String> mRestored;

    // contains connections to all connected services, including app services
    // and system services
    protected final ArrayList<ManagedServiceInfo> mServices = new ArrayList<ManagedServiceInfo>();
    // things that will be put into mServices as soon as they're ready
    private final ArrayList<String> mServicesBinding = new ArrayList<String>();
    // lists the component names of all enabled (and therefore connected)
    // app services for current profiles.
    private ArraySet<ComponentName> mEnabledServicesForCurrentProfiles
            = new ArraySet<ComponentName>();
    // Just the packages from mEnabledServicesForCurrentProfiles
    private ArraySet<String> mEnabledServicesPackageNames = new ArraySet<String>();

    // Kept to de-dupe user change events (experienced after boot, when we receive a settings and a
    // user change).
    private int[] mLastSeenProfileIds;

    private final BroadcastReceiver mRestoreReceiver;

    public ManagedServices(Context context, Handler handler, Object mutex,
            UserProfiles userProfiles) {
        mContext = context;
        mMutex = mutex;
        mUserProfiles = userProfiles;
        mConfig = getConfig();
        mSettingsObserver = new SettingsObserver(handler);

        mRestoreReceiver = new SettingRestoredReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SETTING_RESTORED);
        context.registerReceiver(mRestoreReceiver, filter);
    }

    class SettingRestoredReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SETTING_RESTORED.equals(intent.getAction())) {
                String element = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                if (Objects.equals(element, mConfig.secureSettingName)) {
                    String prevValue = intent.getStringExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE);
                    String newValue = intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE);
                    settingRestored(element, prevValue, newValue, getSendingUserId());
                }
            }
        }
    }

    abstract protected Config getConfig();

    private String getCaption() {
        return mConfig.caption;
    }

    abstract protected IInterface asInterface(IBinder binder);

    abstract protected void onServiceAdded(ManagedServiceInfo info);

    protected void onServiceRemovedLocked(ManagedServiceInfo removed) { }

    private ManagedServiceInfo newServiceInfo(IInterface service,
            ComponentName component, int userid, boolean isSystem, ServiceConnection connection,
            int targetSdkVersion) {
        return new ManagedServiceInfo(service, component, userid, isSystem, connection,
                targetSdkVersion);
    }

    public void onBootPhaseAppsCanStart() {
        mSettingsObserver.observe();
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    All " + getCaption() + "s (" + mEnabledServicesForCurrentProfiles.size()
                + ") enabled for current profiles:");
        for (ComponentName cmpt : mEnabledServicesForCurrentProfiles) {
            if (filter != null && !filter.matches(cmpt)) continue;
            pw.println("      " + cmpt);
        }

        pw.println("    Live " + getCaption() + "s (" + mServices.size() + "):");
        for (ManagedServiceInfo info : mServices) {
            if (filter != null && !filter.matches(info.component)) continue;
            pw.println("      " + info.component
                    + " (user " + info.userid + "): " + info.service
                    + (info.isSystem?" SYSTEM":""));
        }
    }

    // By convention, restored settings are replicated to another settings
    // entry, named similarly but with a disambiguation suffix.
    public static final String restoredSettingName(Config config) {
        return config.secureSettingName + ":restored";
    }

    // The OS has done a restore of this service's saved state.  We clone it to the
    // 'restored' reserve, and then once we return and the actual write to settings is
    // performed, our observer will do the work of maintaining the restored vs live
    // settings data.
    public void settingRestored(String element, String oldValue, String newValue, int userid) {
        if (DEBUG) Slog.d(TAG, "Restored managed service setting: " + element
                + " ovalue=" + oldValue + " nvalue=" + newValue);
        if (mConfig.secureSettingName.equals(element)) {
            if (element != null) {
                mRestored = null;
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        restoredSettingName(mConfig),
                        newValue,
                        userid);
                disableNonexistentServices(userid);
            }
        }
    }

    public boolean isComponentEnabledForPackage(String pkg) {
        return mEnabledServicesPackageNames.contains(pkg);
    }

    public void onPackagesChanged(boolean queryReplace, String[] pkgList) {
        if (DEBUG) Slog.d(TAG, "onPackagesChanged queryReplace=" + queryReplace
                + " pkgList=" + (pkgList == null ? null : Arrays.asList(pkgList))
                + " mEnabledServicesPackageNames=" + mEnabledServicesPackageNames);
        boolean anyServicesInvolved = false;
        if (pkgList != null && (pkgList.length > 0)) {
            for (String pkgName : pkgList) {
                if (mEnabledServicesPackageNames.contains(pkgName)) {
                    anyServicesInvolved = true;
                }
            }
        }

        if (anyServicesInvolved) {
            // if we're not replacing a package, clean up orphaned bits
            if (!queryReplace) {
                disableNonexistentServices();
            }
            // make sure we're still bound to any of our services who may have just upgraded
            rebindServices();
        }
    }

    public void onUserSwitched(int user) {
        if (DEBUG) Slog.d(TAG, "onUserSwitched u=" + user);
        if (Arrays.equals(mLastSeenProfileIds, mUserProfiles.getCurrentProfileIds())) {
            if (DEBUG) Slog.d(TAG, "Current profile IDs didn't change, skipping rebindServices().");
            return;
        }
        rebindServices();
    }

    public ManagedServiceInfo checkServiceTokenLocked(IInterface service) {
        checkNotNull(service);
        final IBinder token = service.asBinder();
        final int N = mServices.size();
        for (int i=0; i<N; i++) {
            final ManagedServiceInfo info = mServices.get(i);
            if (info.service.asBinder() == token) return info;
        }
        throw new SecurityException("Disallowed call from unknown " + getCaption() + ": "
                + service);
    }

    public void unregisterService(IInterface service, int userid) {
        checkNotNull(service);
        // no need to check permissions; if your service binder is in the list,
        // that's proof that you had permission to add it in the first place
        unregisterServiceImpl(service, userid);
    }

    public void registerService(IInterface service, ComponentName component, int userid) {
        checkNotNull(service);
        ManagedServiceInfo info = registerServiceImpl(service, component, userid);
        if (info != null) {
            onServiceAdded(info);
        }
    }

    /**
     * Remove access for any services that no longer exist.
     */
    private void disableNonexistentServices() {
        int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int N = userIds.length;
        for (int i = 0 ; i < N; ++i) {
            disableNonexistentServices(userIds[i]);
        }
    }

    private void disableNonexistentServices(int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        boolean restoredChanged = false;
        if (mRestored == null) {
            String restoredSetting = Settings.Secure.getStringForUser(
                    cr,
                    restoredSettingName(mConfig),
                    userId);
            if (!TextUtils.isEmpty(restoredSetting)) {
                if (DEBUG) Slog.d(TAG, "restored: " + restoredSetting);
                String[] restored = restoredSetting.split(ENABLED_SERVICES_SEPARATOR);
                mRestored = new ArraySet<String>(Arrays.asList(restored));
            } else {
                mRestored = new ArraySet<String>();
            }
        }
        String flatIn = Settings.Secure.getStringForUser(
                cr,
                mConfig.secureSettingName,
                userId);
        if (!TextUtils.isEmpty(flatIn)) {
            if (DEBUG) Slog.v(TAG, "flat before: " + flatIn);
            PackageManager pm = mContext.getPackageManager();
            List<ResolveInfo> installedServices = pm.queryIntentServicesAsUser(
                    new Intent(mConfig.serviceInterface),
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                    userId);
            if (DEBUG) Slog.v(TAG, mConfig.serviceInterface + " services: " + installedServices);
            Set<ComponentName> installed = new ArraySet<ComponentName>();
            for (int i = 0, count = installedServices.size(); i < count; i++) {
                ResolveInfo resolveInfo = installedServices.get(i);
                ServiceInfo info = resolveInfo.serviceInfo;

                ComponentName component = new ComponentName(info.packageName, info.name);
                if (!mConfig.bindPermission.equals(info.permission)) {
                    Slog.w(TAG, "Skipping " + getCaption() + " service "
                            + info.packageName + "/" + info.name
                            + ": it does not require the permission "
                            + mConfig.bindPermission);
                    restoredChanged |= mRestored.remove(component.flattenToString());
                    continue;
                }
                installed.add(component);
            }

            String flatOut = "";
            if (!installed.isEmpty()) {
                String[] enabled = flatIn.split(ENABLED_SERVICES_SEPARATOR);
                ArrayList<String> remaining = new ArrayList<String>(enabled.length);
                for (int i = 0; i < enabled.length; i++) {
                    ComponentName enabledComponent = ComponentName.unflattenFromString(enabled[i]);
                    if (installed.contains(enabledComponent)) {
                        remaining.add(enabled[i]);
                        restoredChanged |= mRestored.remove(enabled[i]);
                    }
                }
                remaining.addAll(mRestored);
                flatOut = TextUtils.join(ENABLED_SERVICES_SEPARATOR, remaining);
            }
            if (DEBUG) Slog.v(TAG, "flat after: " + flatOut);
            if (!flatIn.equals(flatOut)) {
                Settings.Secure.putStringForUser(cr,
                        mConfig.secureSettingName,
                        flatOut, userId);
            }
            if (restoredChanged) {
                if (DEBUG) Slog.d(TAG, "restored changed; rewriting");
                final String flatRestored = TextUtils.join(ENABLED_SERVICES_SEPARATOR,
                        mRestored.toArray());
                Settings.Secure.putStringForUser(cr,
                        restoredSettingName(mConfig),
                        flatRestored,
                        userId);
            }
        }
    }

    /**
     * Called whenever packages change, the user switches, or the secure setting
     * is altered. (For example in response to USER_SWITCHED in our broadcast receiver)
     */
    private void rebindServices() {
        if (DEBUG) Slog.d(TAG, "rebindServices");
        final int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int nUserIds = userIds.length;

        final SparseArray<String> flat = new SparseArray<String>();

        for (int i = 0; i < nUserIds; ++i) {
            flat.put(userIds[i], Settings.Secure.getStringForUser(
                    mContext.getContentResolver(),
                    mConfig.secureSettingName,
                    userIds[i]));
        }

        ArrayList<ManagedServiceInfo> toRemove = new ArrayList<ManagedServiceInfo>();
        final SparseArray<ArrayList<ComponentName>> toAdd
                = new SparseArray<ArrayList<ComponentName>>();

        synchronized (mMutex) {
            // Unbind automatically bound services, retain system services.
            for (ManagedServiceInfo service : mServices) {
                if (!service.isSystem) {
                    toRemove.add(service);
                }
            }

            final ArraySet<ComponentName> newEnabled = new ArraySet<ComponentName>();
            final ArraySet<String> newPackages = new ArraySet<String>();

            for (int i = 0; i < nUserIds; ++i) {
                final ArrayList<ComponentName> add = new ArrayList<ComponentName>();
                toAdd.put(userIds[i], add);

                // decode the list of components
                String toDecode = flat.get(userIds[i]);
                if (toDecode != null) {
                    String[] components = toDecode.split(ENABLED_SERVICES_SEPARATOR);
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
            mEnabledServicesForCurrentProfiles = newEnabled;
            mEnabledServicesPackageNames = newPackages;
        }

        for (ManagedServiceInfo info : toRemove) {
            final ComponentName component = info.component;
            final int oldUser = info.userid;
            Slog.v(TAG, "disabling " + getCaption() + " for user "
                    + oldUser + ": " + component);
            unregisterService(component, info.userid);
        }

        for (int i = 0; i < nUserIds; ++i) {
            final ArrayList<ComponentName> add = toAdd.get(userIds[i]);
            final int N = add.size();
            for (int j = 0; j < N; j++) {
                final ComponentName component = add.get(j);
                Slog.v(TAG, "enabling " + getCaption() + " for user " + userIds[i] + ": "
                        + component);
                registerService(component, userIds[i]);
            }
        }

        mLastSeenProfileIds = mUserProfiles.getCurrentProfileIds();
    }

    /**
     * Version of registerService that takes the name of a service component to bind to.
     */
    private void registerService(final ComponentName name, final int userid) {
        if (DEBUG) Slog.v(TAG, "registerService: " + name + " u=" + userid);

        synchronized (mMutex) {
            final String servicesBindingTag = name.toString() + "/" + userid;
            if (mServicesBinding.contains(servicesBindingTag)) {
                // stop registering this thing already! we're working on it
                return;
            }
            mServicesBinding.add(servicesBindingTag);

            final int N = mServices.size();
            for (int i=N-1; i>=0; i--) {
                final ManagedServiceInfo info = mServices.get(i);
                if (name.equals(info.component)
                        && info.userid == userid) {
                    // cut old connections
                    if (DEBUG) Slog.v(TAG, "    disconnecting old " + getCaption() + ": "
                            + info.service);
                    removeServiceLocked(i);
                    if (info.connection != null) {
                        mContext.unbindService(info.connection);
                    }
                }
            }

            Intent intent = new Intent(mConfig.serviceInterface);
            intent.setComponent(name);

            intent.putExtra(Intent.EXTRA_CLIENT_LABEL, mConfig.clientLabel);

            final PendingIntent pendingIntent = PendingIntent.getActivity(
                    mContext, 0, new Intent(mConfig.settingsAction), 0);
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
                if (DEBUG) Slog.v(TAG, "binding: " + intent);
                if (!mContext.bindServiceAsUser(intent,
                        new ServiceConnection() {
                            IInterface mService;

                            @Override
                            public void onServiceConnected(ComponentName name, IBinder binder) {
                                boolean added = false;
                                ManagedServiceInfo info = null;
                                synchronized (mMutex) {
                                    mServicesBinding.remove(servicesBindingTag);
                                    try {
                                        mService = asInterface(binder);
                                        info = newServiceInfo(mService, name,
                                                userid, false /*isSystem*/, this, targetSdkVersion);
                                        binder.linkToDeath(info, 0);
                                        added = mServices.add(info);
                                    } catch (RemoteException e) {
                                        // already dead
                                    }
                                }
                                if (added) {
                                    onServiceAdded(info);
                                }
                            }

                            @Override
                            public void onServiceDisconnected(ComponentName name) {
                                Slog.v(TAG, getCaption() + " connection lost: " + name);
                            }
                        },
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        new UserHandle(userid)))
                {
                    mServicesBinding.remove(servicesBindingTag);
                    Slog.w(TAG, "Unable to bind " + getCaption() + " service: " + intent);
                    return;
                }
            } catch (SecurityException ex) {
                Slog.e(TAG, "Unable to bind " + getCaption() + " service: " + intent, ex);
                return;
            }
        }
    }

    /**
     * Remove a service for the given user by ComponentName
     */
    private void unregisterService(ComponentName name, int userid) {
        synchronized (mMutex) {
            final int N = mServices.size();
            for (int i=N-1; i>=0; i--) {
                final ManagedServiceInfo info = mServices.get(i);
                if (name.equals(info.component)
                        && info.userid == userid) {
                    removeServiceLocked(i);
                    if (info.connection != null) {
                        try {
                            mContext.unbindService(info.connection);
                        } catch (IllegalArgumentException ex) {
                            // something happened to the service: we think we have a connection
                            // but it's bogus.
                            Slog.e(TAG, getCaption() + " " + name + " could not be unbound: " + ex);
                        }
                    }
                }
            }
        }
    }

    /**
     * Removes a service from the list but does not unbind
     *
     * @return the removed service.
     */
    private ManagedServiceInfo removeServiceImpl(IInterface service, final int userid) {
        if (DEBUG) Slog.d(TAG, "removeServiceImpl service=" + service + " u=" + userid);
        ManagedServiceInfo serviceInfo = null;
        synchronized (mMutex) {
            final int N = mServices.size();
            for (int i=N-1; i>=0; i--) {
                final ManagedServiceInfo info = mServices.get(i);
                if (info.service.asBinder() == service.asBinder()
                        && info.userid == userid) {
                    if (DEBUG) Slog.d(TAG, "Removing active service " + info.component);
                    serviceInfo = removeServiceLocked(i);
                }
            }
        }
        return serviceInfo;
    }

    private ManagedServiceInfo removeServiceLocked(int i) {
        final ManagedServiceInfo info = mServices.remove(i);
        onServiceRemovedLocked(info);
        return info;
    }

    private void checkNotNull(IInterface service) {
        if (service == null) {
            throw new IllegalArgumentException(getCaption() + " must not be null");
        }
    }

    private ManagedServiceInfo registerServiceImpl(final IInterface service,
            final ComponentName component, final int userid) {
        synchronized (mMutex) {
            try {
                ManagedServiceInfo info = newServiceInfo(service, component, userid,
                        true /*isSystem*/, null, Build.VERSION_CODES.LOLLIPOP);
                service.asBinder().linkToDeath(info, 0);
                mServices.add(info);
                return info;
            } catch (RemoteException e) {
                // already dead
            }
        }
        return null;
    }

    /**
     * Removes a service from the list and unbinds.
     */
    private void unregisterServiceImpl(IInterface service, int userid) {
        ManagedServiceInfo info = removeServiceImpl(service, userid);
        if (info != null && info.connection != null) {
            mContext.unbindService(info.connection);
        }
    }

    private class SettingsObserver extends ContentObserver {
        private final Uri mSecureSettingsUri = Settings.Secure.getUriFor(mConfig.secureSettingName);

        private SettingsObserver(Handler handler) {
            super(handler);
        }

        private void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mSecureSettingsUri,
                    false, this, UserHandle.USER_ALL);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        private void update(Uri uri) {
            if (uri == null || mSecureSettingsUri.equals(uri)) {
                if (DEBUG) Slog.d(TAG, "Setting changed: mSecureSettingsUri=" + mSecureSettingsUri +
                        " / uri=" + uri);
                rebindServices();
            }
        }
    }

    public class ManagedServiceInfo implements IBinder.DeathRecipient {
        public IInterface service;
        public ComponentName component;
        public int userid;
        public boolean isSystem;
        public ServiceConnection connection;
        public int targetSdkVersion;

        public ManagedServiceInfo(IInterface service, ComponentName component,
                int userid, boolean isSystem, ServiceConnection connection, int targetSdkVersion) {
            this.service = service;
            this.component = component;
            this.userid = userid;
            this.isSystem = isSystem;
            this.connection = connection;
            this.targetSdkVersion = targetSdkVersion;
        }

        @Override
        public String toString() {
            return new StringBuilder("ManagedServiceInfo[")
                    .append("component=").append(component)
                    .append(",userid=").append(userid)
                    .append(",isSystem=").append(isSystem)
                    .append(",targetSdkVersion=").append(targetSdkVersion)
                    .append(",connection=").append(connection == null ? null : "<connection>")
                    .append(",service=").append(service)
                    .append(']').toString();
        }

        public boolean enabledAndUserMatches(int nid) {
            if (!isEnabledForCurrentProfiles()) {
                return false;
            }
            if (this.userid == UserHandle.USER_ALL) return true;
            if (nid == UserHandle.USER_ALL || nid == this.userid) return true;
            return supportsProfiles() && mUserProfiles.isCurrentProfile(nid);
        }

        public boolean supportsProfiles() {
            return targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP;
        }

        @Override
        public void binderDied() {
            if (DEBUG) Slog.d(TAG, "binderDied");
            // Remove the service, but don't unbind from the service. The system will bring the
            // service back up, and the onServiceConnected handler will readd the service with the
            // new binding. If this isn't a bound service, and is just a registered
            // service, just removing it from the list is all we need to do anyway.
            removeServiceImpl(this.service, this.userid);
        }

        /** convenience method for looking in mEnabledServicesForCurrentProfiles */
        public boolean isEnabledForCurrentProfiles() {
            if (this.isSystem) return true;
            if (this.connection == null) return false;
            return mEnabledServicesForCurrentProfiles.contains(this.component);
        }
    }

    public static class UserProfiles {
        // Profiles of the current user.
        private final SparseArray<UserInfo> mCurrentProfiles = new SparseArray<UserInfo>();

        public void updateCache(Context context) {
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager != null) {
                int currentUserId = ActivityManager.getCurrentUser();
                List<UserInfo> profiles = userManager.getProfiles(currentUserId);
                synchronized (mCurrentProfiles) {
                    mCurrentProfiles.clear();
                    for (UserInfo user : profiles) {
                        mCurrentProfiles.put(user.id, user);
                    }
                }
            }
        }

        public int[] getCurrentProfileIds() {
            synchronized (mCurrentProfiles) {
                int[] users = new int[mCurrentProfiles.size()];
                final int N = mCurrentProfiles.size();
                for (int i = 0; i < N; ++i) {
                    users[i] = mCurrentProfiles.keyAt(i);
                }
                return users;
            }
        }

        public boolean isCurrentProfile(int userId) {
            synchronized (mCurrentProfiles) {
                return mCurrentProfiles.get(userId) != null;
            }
        }
    }

    protected static class Config {
        String caption;
        String serviceInterface;
        String secureSettingName;
        String bindPermission;
        String settingsAction;
        int clientLabel;
    }
}
