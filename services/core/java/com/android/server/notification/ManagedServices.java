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

import static android.content.Context.BIND_ALLOW_WHITELIST_MANAGEMENT;
import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.Context.BIND_FOREGROUND_SERVICE;

import android.annotation.NonNull;
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
import java.util.HashSet;
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

    protected static final String ENABLED_SERVICES_SEPARATOR = ":";

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
    // lists the component names of all enabled (and therefore potentially connected)
    // app services for current profiles.
    private ArraySet<ComponentName> mEnabledServicesForCurrentProfiles
            = new ArraySet<ComponentName>();
    // Just the packages from mEnabledServicesForCurrentProfiles
    private ArraySet<String> mEnabledServicesPackageNames = new ArraySet<String>();
    // List of packages in restored setting across all mUserProfiles, for quick
    // filtering upon package updates.
    private ArraySet<String> mRestoredPackages = new ArraySet<>();
    // List of enabled packages that have nevertheless asked not to be run
    private ArraySet<ComponentName> mSnoozingForCurrentProfiles = new ArraySet<>();


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
        rebuildRestoredPackages();
    }

    class SettingRestoredReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SETTING_RESTORED.equals(intent.getAction())) {
                String element = intent.getStringExtra(Intent.EXTRA_SETTING_NAME);
                if (Objects.equals(element, mConfig.secureSettingName)
                        || Objects.equals(element, mConfig.secondarySettingName)) {
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

    abstract protected boolean checkType(IInterface service);

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
                    + (info.isSystem?" SYSTEM":"")
                    + (info.isGuest(this)?" GUEST":""));
        }

        pw.println("    Snoozed " + getCaption() + "s (" +
                mSnoozingForCurrentProfiles.size() + "):");
        for (ComponentName name : mSnoozingForCurrentProfiles) {
            pw.println("      " + name.flattenToShortString());
        }
    }

    // By convention, restored settings are replicated to another settings
    // entry, named similarly but with a disambiguation suffix.
    public static String restoredSettingName(String setting) {
        return setting + ":restored";
    }

    // The OS has done a restore of this service's saved state.  We clone it to the
    // 'restored' reserve, and then once we return and the actual write to settings is
    // performed, our observer will do the work of maintaining the restored vs live
    // settings data.
    public void settingRestored(String element, String oldValue, String newValue, int userid) {
        if (DEBUG) Slog.d(TAG, "Restored managed service setting: " + element
                + " ovalue=" + oldValue + " nvalue=" + newValue);
        if (mConfig.secureSettingName.equals(element) ||
                mConfig.secondarySettingName.equals(element)) {
            if (element != null) {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        restoredSettingName(element),
                        newValue,
                        userid);
                updateSettingsAccordingToInstalledServices(element, userid);
                rebuildRestoredPackages();
            }
        }
    }

    public boolean isComponentEnabledForPackage(String pkg) {
        return mEnabledServicesPackageNames.contains(pkg);
    }

    public void onPackagesChanged(boolean removingPackage, String[] pkgList) {
        if (DEBUG) Slog.d(TAG, "onPackagesChanged removingPackage=" + removingPackage
                + " pkgList=" + (pkgList == null ? null : Arrays.asList(pkgList))
                + " mEnabledServicesPackageNames=" + mEnabledServicesPackageNames);
        boolean anyServicesInvolved = false;

        if (pkgList != null && (pkgList.length > 0)) {
            for (String pkgName : pkgList) {
                if (mEnabledServicesPackageNames.contains(pkgName) ||
                        mRestoredPackages.contains(pkgName)) {
                    anyServicesInvolved = true;
                }
            }
        }

        if (anyServicesInvolved) {
            // if we're not replacing a package, clean up orphaned bits
            if (removingPackage) {
                updateSettingsAccordingToInstalledServices();
                rebuildRestoredPackages();
            }
            // make sure we're still bound to any of our services who may have just upgraded
            rebindServices(false);
        }
    }

    public void onUserSwitched(int user) {
        if (DEBUG) Slog.d(TAG, "onUserSwitched u=" + user);
        rebuildRestoredPackages();
        if (Arrays.equals(mLastSeenProfileIds, mUserProfiles.getCurrentProfileIds())) {
            if (DEBUG) Slog.d(TAG, "Current profile IDs didn't change, skipping rebindServices().");
            return;
        }
        rebindServices(true);
    }

    public void onUserUnlocked(int user) {
        if (DEBUG) Slog.d(TAG, "onUserUnlocked u=" + user);
        rebuildRestoredPackages();
        rebindServices(false);
    }

    public ManagedServiceInfo getServiceFromTokenLocked(IInterface service) {
        if (service == null) {
            return null;
        }
        final IBinder token = service.asBinder();
        final int N = mServices.size();
        for (int i = 0; i < N; i++) {
            final ManagedServiceInfo info = mServices.get(i);
            if (info.service.asBinder() == token) return info;
        }
        return null;
    }

    public ManagedServiceInfo checkServiceTokenLocked(IInterface service) {
        checkNotNull(service);
        ManagedServiceInfo info = getServiceFromTokenLocked(service);
        if (info != null) {
            return info;
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
     * Add a service to our callbacks. The lifecycle of this service is managed externally,
     * but unlike a system service, it should not be considered privledged.
     * */
    public void registerGuestService(ManagedServiceInfo guest) {
        checkNotNull(guest.service);
        if (!checkType(guest.service)) {
            throw new IllegalArgumentException();
        }
        if (registerServiceImpl(guest) != null) {
            onServiceAdded(guest);
        }
    }

    public void setComponentState(ComponentName component, boolean enabled) {
        boolean previous = !mSnoozingForCurrentProfiles.contains(component);
        if (previous == enabled) {
            return;
        }

        if (enabled) {
            mSnoozingForCurrentProfiles.remove(component);
        } else {
            mSnoozingForCurrentProfiles.add(component);
        }

        // State changed
        if (DEBUG) {
            Slog.d(TAG, ((enabled) ? "Enabling " : "Disabling ") + "component " +
                    component.flattenToShortString());
        }


        synchronized (mMutex) {
            final int[] userIds = mUserProfiles.getCurrentProfileIds();

            for (int userId : userIds) {
                if (enabled) {
                    registerServiceLocked(component, userId);
                } else {
                    unregisterServiceLocked(component, userId);
                }
            }
        }
    }

    private void rebuildRestoredPackages() {
        mRestoredPackages.clear();
        mSnoozingForCurrentProfiles.clear();
        String secureSettingName = restoredSettingName(mConfig.secureSettingName);
        String secondarySettingName = mConfig.secondarySettingName == null
                ? null : restoredSettingName(mConfig.secondarySettingName);
        int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int N = userIds.length;
        for (int i = 0; i < N; ++i) {
            ArraySet<ComponentName> names =
                    loadComponentNamesFromSetting(secureSettingName, userIds[i]);
            if (secondarySettingName != null) {
                names.addAll(loadComponentNamesFromSetting(secondarySettingName, userIds[i]));
            }
            for (ComponentName name : names) {
                mRestoredPackages.add(name.getPackageName());
            }
        }
    }


    protected @NonNull ArraySet<ComponentName> loadComponentNamesFromSetting(String settingName,
            int userId) {
        final ContentResolver cr = mContext.getContentResolver();
        String settingValue = Settings.Secure.getStringForUser(
            cr,
            settingName,
            userId);
        if (TextUtils.isEmpty(settingValue))
            return new ArraySet<>();
        String[] restored = settingValue.split(ENABLED_SERVICES_SEPARATOR);
        ArraySet<ComponentName> result = new ArraySet<>(restored.length);
        for (int i = 0; i < restored.length; i++) {
            ComponentName value = ComponentName.unflattenFromString(restored[i]);
            if (null != value) {
                result.add(value);
            }
        }
        return result;
    }

    private void storeComponentsToSetting(Set<ComponentName> components,
                                          String settingName,
                                          int userId) {
        String[] componentNames = null;
        if (null != components) {
            componentNames = new String[components.size()];
            int index = 0;
            for (ComponentName c: components) {
                componentNames[index++] = c.flattenToString();
            }
        }
        final String value = (componentNames == null) ? "" :
                TextUtils.join(ENABLED_SERVICES_SEPARATOR, componentNames);
        final ContentResolver cr = mContext.getContentResolver();
        Settings.Secure.putStringForUser(
            cr,
            settingName,
            value,
            userId);
    }

    /**
     * Remove access for any services that no longer exist.
     */
    private void updateSettingsAccordingToInstalledServices() {
        int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int N = userIds.length;
        for (int i = 0; i < N; ++i) {
            updateSettingsAccordingToInstalledServices(mConfig.secureSettingName, userIds[i]);
            if (mConfig.secondarySettingName != null) {
                updateSettingsAccordingToInstalledServices(
                        mConfig.secondarySettingName, userIds[i]);
            }
        }
        rebuildRestoredPackages();
    }

    protected Set<ComponentName> queryPackageForServices(String packageName, int userId) {
        Set<ComponentName> installed = new ArraySet<>();
        final PackageManager pm = mContext.getPackageManager();
        Intent queryIntent = new Intent(mConfig.serviceInterface);
        if (!TextUtils.isEmpty(packageName)) {
            queryIntent.setPackage(packageName);
        }
        List<ResolveInfo> installedServices = pm.queryIntentServicesAsUser(
                queryIntent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                userId);
        if (DEBUG)
            Slog.v(TAG, mConfig.serviceInterface + " services: " + installedServices);
        if (installedServices != null) {
            for (int i = 0, count = installedServices.size(); i < count; i++) {
                ResolveInfo resolveInfo = installedServices.get(i);
                ServiceInfo info = resolveInfo.serviceInfo;

                ComponentName component = new ComponentName(info.packageName, info.name);
                if (!mConfig.bindPermission.equals(info.permission)) {
                    Slog.w(TAG, "Skipping " + getCaption() + " service "
                        + info.packageName + "/" + info.name
                        + ": it does not require the permission "
                        + mConfig.bindPermission);
                    continue;
                }
                installed.add(component);
            }
        }
        return installed;
    }

    private void updateSettingsAccordingToInstalledServices(String setting, int userId) {
        boolean restoredChanged = false;
        boolean currentChanged = false;
        Set<ComponentName> restored =
                loadComponentNamesFromSetting(restoredSettingName(setting), userId);
        Set<ComponentName> current =
                loadComponentNamesFromSetting(setting, userId);
        // Load all services for all packages.
        Set<ComponentName> installed = queryPackageForServices(null, userId);

        ArraySet<ComponentName> retained = new ArraySet<>();

        for (ComponentName component : installed) {
            if (null != restored) {
                boolean wasRestored = restored.remove(component);
                if (wasRestored) {
                    // Freshly installed package has service that was mentioned in restored setting.
                    if (DEBUG)
                        Slog.v(TAG, "Restoring " + component + " for user " + userId);
                    restoredChanged = true;
                    currentChanged = true;
                    retained.add(component);
                    continue;
                }
            }

            if (null != current) {
                if (current.contains(component))
                    retained.add(component);
            }
        }

        currentChanged |= ((current == null ? 0 : current.size()) != retained.size());

        if (currentChanged) {
            if (DEBUG) Slog.v(TAG, "List of  " + getCaption() + " services was updated " + current);
            storeComponentsToSetting(retained, setting, userId);
        }

        if (restoredChanged) {
            if (DEBUG) Slog.v(TAG,
                    "List of  " + getCaption() + " restored services was updated " + restored);
            storeComponentsToSetting(restored, restoredSettingName(setting), userId);
        }
    }

    /**
     * Called whenever packages change, the user switches, or the secure setting
     * is altered. (For example in response to USER_SWITCHED in our broadcast receiver)
     */
    private void rebindServices(boolean forceRebind) {
        if (DEBUG) Slog.d(TAG, "rebindServices");
        final int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int nUserIds = userIds.length;

        final SparseArray<ArraySet<ComponentName>> componentsByUser = new SparseArray<>();

        for (int i = 0; i < nUserIds; ++i) {
            componentsByUser.put(userIds[i],
                    loadComponentNamesFromSetting(mConfig.secureSettingName, userIds[i]));
            if (mConfig.secondarySettingName != null) {
                componentsByUser.get(userIds[i]).addAll(
                        loadComponentNamesFromSetting(mConfig.secondarySettingName, userIds[i]));
            }
        }

        final ArrayList<ManagedServiceInfo> removableBoundServices = new ArrayList<>();
        final SparseArray<Set<ComponentName>> toAdd = new SparseArray<>();

        synchronized (mMutex) {
            // Rebind to non-system services if user switched
            for (ManagedServiceInfo service : mServices) {
                if (!service.isSystem && !service.isGuest(this)) {
                    removableBoundServices.add(service);
                }
            }

            mEnabledServicesForCurrentProfiles.clear();
            mEnabledServicesPackageNames.clear();

            for (int i = 0; i < nUserIds; ++i) {
                // decode the list of components
                final ArraySet<ComponentName> userComponents = componentsByUser.get(userIds[i]);
                if (null == userComponents) {
                    toAdd.put(userIds[i], new ArraySet<ComponentName>());
                    continue;
                }

                final Set<ComponentName> add = new HashSet<>(userComponents);
                add.removeAll(mSnoozingForCurrentProfiles);

                toAdd.put(userIds[i], add);

                mEnabledServicesForCurrentProfiles.addAll(userComponents);

                for (int j = 0; j < userComponents.size(); j++) {
                    final ComponentName component = userComponents.valueAt(j);
                    mEnabledServicesPackageNames.add(component.getPackageName());
                }
            }
        }

        for (ManagedServiceInfo info : removableBoundServices) {
            final ComponentName component = info.component;
            final int oldUser = info.userid;
            final Set<ComponentName> allowedComponents = toAdd.get(info.userid);
            if (allowedComponents != null) {
                if (allowedComponents.contains(component) && !forceRebind) {
                    // Already bound, don't need to bind again.
                    allowedComponents.remove(component);
                } else {
                    // No longer allowed to be bound, or must rebind.
                    Slog.v(TAG, "disabling " + getCaption() + " for user "
                            + oldUser + ": " + component);
                    unregisterService(component, oldUser);
                }
            }
        }

        for (int i = 0; i < nUserIds; ++i) {
            final Set<ComponentName> add = toAdd.get(userIds[i]);
            for (ComponentName component : add) {
                Slog.v(TAG, "enabling " + getCaption() + " for " + userIds[i] + ": " + component);
                registerService(component, userIds[i]);
            }
        }

        mLastSeenProfileIds = userIds;
    }

    /**
     * Version of registerService that takes the name of a service component to bind to.
     */
    private void registerService(final ComponentName name, final int userid) {
        synchronized (mMutex) {
            registerServiceLocked(name, userid);
        }
    }

    /**
     * Inject a system service into the management list.
     */
    public void registerSystemService(final ComponentName name, final int userid) {
        synchronized (mMutex) {
            registerServiceLocked(name, userid, true /* isSystem */);
        }
    }

    private void registerServiceLocked(final ComponentName name, final int userid) {
        registerServiceLocked(name, userid, false /* isSystem */);
    }

    private void registerServiceLocked(final ComponentName name, final int userid,
            final boolean isSystem) {
        if (DEBUG) Slog.v(TAG, "registerService: " + name + " u=" + userid);

        final String servicesBindingTag = name.toString() + "/" + userid;
        if (mServicesBinding.contains(servicesBindingTag)) {
            // stop registering this thing already! we're working on it
            return;
        }
        mServicesBinding.add(servicesBindingTag);

        final int N = mServices.size();
        for (int i = N - 1; i >= 0; i--) {
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
            ServiceConnection serviceConnection = new ServiceConnection() {
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
                                userid, isSystem, this, targetSdkVersion);
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
            };
            if (!mContext.bindServiceAsUser(intent,
                serviceConnection,
                BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE | BIND_ALLOW_WHITELIST_MANAGEMENT,
                new UserHandle(userid))) {
                mServicesBinding.remove(servicesBindingTag);
                Slog.w(TAG, "Unable to bind " + getCaption() + " service: " + intent);
                return;
            }
        } catch (SecurityException ex) {
            Slog.e(TAG, "Unable to bind " + getCaption() + " service: " + intent, ex);
            return;
        }
    }

    /**
     * Remove a service for the given user by ComponentName
     */
    private void unregisterService(ComponentName name, int userid) {
        synchronized (mMutex) {
            unregisterServiceLocked(name, userid);
        }
    }

    private void unregisterServiceLocked(ComponentName name, int userid) {
        final int N = mServices.size();
        for (int i = N - 1; i >= 0; i--) {
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
            for (int i = N - 1; i >= 0; i--) {
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
        ManagedServiceInfo info = newServiceInfo(service, component, userid,
                true /*isSystem*/, null /*connection*/, Build.VERSION_CODES.LOLLIPOP);
        return registerServiceImpl(info);
    }

    private ManagedServiceInfo registerServiceImpl(ManagedServiceInfo info) {
        synchronized (mMutex) {
            try {
                info.service.asBinder().linkToDeath(info, 0);
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
        if (info != null && info.connection != null && !info.isGuest(this)) {
            mContext.unbindService(info.connection);
        }
    }

    private class SettingsObserver extends ContentObserver {
        private final Uri mSecureSettingsUri = Settings.Secure.getUriFor(mConfig.secureSettingName);
        private final Uri mSecondarySettingsUri;

        private SettingsObserver(Handler handler) {
            super(handler);
            if (mConfig.secondarySettingName != null) {
                mSecondarySettingsUri = Settings.Secure.getUriFor(mConfig.secondarySettingName);
            } else {
                mSecondarySettingsUri = null;
            }
        }

        private void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mSecureSettingsUri,
                    false, this, UserHandle.USER_ALL);
            if (mSecondarySettingsUri != null) {
                resolver.registerContentObserver(mSecondarySettingsUri,
                        false, this, UserHandle.USER_ALL);
            }
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        private void update(Uri uri) {
            if (uri == null || mSecureSettingsUri.equals(uri)
                    || uri.equals(mSecondarySettingsUri)) {
                if (DEBUG) Slog.d(TAG, "Setting changed: uri=" + uri);
                rebindServices(false);
                rebuildRestoredPackages();
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

        public boolean isGuest(ManagedServices host) {
            return ManagedServices.this != host;
        }

        public ManagedServices getOwner() {
            return ManagedServices.this;
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
            if (this.isSystem) return true;
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

    /** convenience method for looking in mEnabledServicesForCurrentProfiles */
    public boolean isComponentEnabledForCurrentProfiles(ComponentName component) {
        return mEnabledServicesForCurrentProfiles.contains(component);
    }

    public static class UserProfiles {
        // Profiles of the current user.
        private final SparseArray<UserInfo> mCurrentProfiles = new SparseArray<>();

        public void updateCache(@NonNull Context context) {
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

    public static class Config {
        public String caption;
        public String serviceInterface;
        public String secureSettingName;
        public String secondarySettingName;
        public String bindPermission;
        public String settingsAction;
        public int clientLabel;
    }
}
