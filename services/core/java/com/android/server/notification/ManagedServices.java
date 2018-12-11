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
import static android.content.Context.DEVICE_POLICY_SERVICE;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.ManagedServiceInfoProto;
import android.service.notification.ManagedServicesProto;
import android.service.notification.ManagedServicesProto.ServiceProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.XmlUtils;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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

    private static final int ON_BINDING_DIED_REBIND_DELAY_MS = 10000;
    protected static final String ENABLED_SERVICES_SEPARATOR = ":";

    /**
     * List of components and apps that can have running {@link ManagedServices}.
     */
    static final String TAG_MANAGED_SERVICES = "service_listing";
    static final String ATT_APPROVED_LIST = "approved";
    static final String ATT_USER_ID = "user";
    static final String ATT_IS_PRIMARY = "primary";
    static final String ATT_VERSION = "version";

    static final int DB_VERSION = 1;

    static final int APPROVAL_BY_PACKAGE = 0;
    static final int APPROVAL_BY_COMPONENT = 1;

    protected final Context mContext;
    protected final Object mMutex;
    private final UserProfiles mUserProfiles;
    private final IPackageManager mPm;
    protected final UserManager mUm;
    private final Config mConfig;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // contains connections to all connected services, including app services
    // and system services
    private final ArrayList<ManagedServiceInfo> mServices = new ArrayList<>();
    // things that will be put into mServices as soon as they're ready
    private final ArrayList<String> mServicesBinding = new ArrayList<>();
    private final ArraySet<String> mServicesRebinding = new ArraySet<>();

    // lists the component names of all enabled (and therefore potentially connected)
    // app services for current profiles.
    private ArraySet<ComponentName> mEnabledServicesForCurrentProfiles
            = new ArraySet<>();
    // Just the packages from mEnabledServicesForCurrentProfiles
    private ArraySet<String> mEnabledServicesPackageNames = new ArraySet<>();
    // List of enabled packages that have nevertheless asked not to be run
    private ArraySet<ComponentName> mSnoozingForCurrentProfiles = new ArraySet<>();

    // List of approved packages or components (by user, then by primary/secondary) that are
    // allowed to be bound as managed services. A package or component appearing in this list does
    // not mean that we are currently bound to said package/component.
    private ArrayMap<Integer, ArrayMap<Boolean, ArraySet<String>>> mApproved = new ArrayMap<>();

    // Kept to de-dupe user change events (experienced after boot, when we receive a settings and a
    // user change).
    private int[] mLastSeenProfileIds;

    // True if approved services are stored in xml, not settings.
    private boolean mUseXml;

    // Whether managed services are approved individually or package wide
    protected int mApprovalLevel;

    public ManagedServices(Context context, Object mutex, UserProfiles userProfiles,
            IPackageManager pm) {
        mContext = context;
        mMutex = mutex;
        mUserProfiles = userProfiles;
        mPm = pm;
        mConfig = getConfig();
        mApprovalLevel = APPROVAL_BY_COMPONENT;
        mUm = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
    }

    abstract protected Config getConfig();

    private String getCaption() {
        return mConfig.caption;
    }

    abstract protected IInterface asInterface(IBinder binder);

    abstract protected boolean checkType(IInterface service);

    abstract protected void onServiceAdded(ManagedServiceInfo info);

    protected List<ManagedServiceInfo> getServices() {
        synchronized (mMutex) {
            List<ManagedServiceInfo> services = new ArrayList<>(mServices);
            return services;
        }
    }

    protected int getBindFlags() {
        return BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE | BIND_ALLOW_WHITELIST_MANAGEMENT;
    }

    protected void onServiceRemovedLocked(ManagedServiceInfo removed) { }

    private ManagedServiceInfo newServiceInfo(IInterface service,
            ComponentName component, int userId, boolean isSystem, ServiceConnection connection,
            int targetSdkVersion) {
        return new ManagedServiceInfo(service, component, userId, isSystem, connection,
                targetSdkVersion);
    }

    public void onBootPhaseAppsCanStart() {}

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    Allowed " + getCaption() + "s:");
        final int N = mApproved.size();
        for (int i = 0 ; i < N; i++) {
            final int userId = mApproved.keyAt(i);
            final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.valueAt(i);
            if (approvedByType != null) {
                final int M = approvedByType.size();
                for (int j = 0; j < M; j++) {
                    final boolean isPrimary = approvedByType.keyAt(j);
                    final ArraySet<String> approved = approvedByType.valueAt(j);
                    if (approvedByType != null && approvedByType.size() > 0) {
                        pw.println("      " + String.join(ENABLED_SERVICES_SEPARATOR, approved)
                                + " (user: " + userId + " isPrimary: " + isPrimary + ")");
                    }
                }
            }
        }

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

    public void dump(ProtoOutputStream proto, DumpFilter filter) {
        proto.write(ManagedServicesProto.CAPTION, getCaption());
        final int N = mApproved.size();
        for (int i = 0 ; i < N; i++) {
            final int userId = mApproved.keyAt(i);
            final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.valueAt(i);
            if (approvedByType != null) {
                final int M = approvedByType.size();
                for (int j = 0; j < M; j++) {
                    final boolean isPrimary = approvedByType.keyAt(j);
                    final ArraySet<String> approved = approvedByType.valueAt(j);
                    if (approvedByType != null && approvedByType.size() > 0) {
                        final long sToken = proto.start(ManagedServicesProto.APPROVED);
                        for (String s : approved) {
                            proto.write(ServiceProto.NAME, s);
                        }
                        proto.write(ServiceProto.USER_ID, userId);
                        proto.write(ServiceProto.IS_PRIMARY, isPrimary);
                        proto.end(sToken);
                    }
                }
            }
        }

        for (ComponentName cmpt : mEnabledServicesForCurrentProfiles) {
            if (filter != null && !filter.matches(cmpt)) continue;
            cmpt.writeToProto(proto, ManagedServicesProto.ENABLED);
        }

        for (ManagedServiceInfo info : mServices) {
            if (filter != null && !filter.matches(info.component)) continue;
            info.writeToProto(proto, ManagedServicesProto.LIVE_SERVICES, this);
        }

        for (ComponentName name : mSnoozingForCurrentProfiles) {
            name.writeToProto(proto, ManagedServicesProto.SNOOZED);
        }
    }

    protected void onSettingRestored(String element, String value, int backupSdkInt, int userId) {
        if (!mUseXml) {
            Slog.d(TAG, "Restored managed service setting: " + element);
            if (mConfig.secureSettingName.equals(element) ||
                    (mConfig.secondarySettingName != null
                            && mConfig.secondarySettingName.equals(element))) {
                if (backupSdkInt < Build.VERSION_CODES.O) {
                    // automatic system grants were added in O, so append the approved apps
                    // rather than wiping out the setting
                    String currentSetting =
                            getApproved(userId, mConfig.secureSettingName.equals(element));
                    if (!TextUtils.isEmpty(currentSetting)) {
                        if (!TextUtils.isEmpty(value)) {
                            value = value + ENABLED_SERVICES_SEPARATOR + currentSetting;
                        } else {
                            value = currentSetting;
                        }
                    }
                }
                Settings.Secure.putStringForUser(
                        mContext.getContentResolver(), element, value, userId);
                loadAllowedComponentsFromSettings();
                rebindServices(false);
            }
        }
    }

    public void writeXml(XmlSerializer out, boolean forBackup) throws IOException {
        out.startTag(null, getConfig().xmlTag);

        out.attribute(null, ATT_VERSION, String.valueOf(DB_VERSION));

        if (forBackup) {
            trimApprovedListsAccordingToInstalledServices();
        }

        final int N = mApproved.size();
        for (int i = 0 ; i < N; i++) {
            final int userId = mApproved.keyAt(i);
            final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.valueAt(i);
            if (approvedByType != null) {
                final int M = approvedByType.size();
                for (int j = 0; j < M; j++) {
                    final boolean isPrimary = approvedByType.keyAt(j);
                    final Set<String> approved = approvedByType.valueAt(j);
                    if (approved != null) {
                        String allowedItems = String.join(ENABLED_SERVICES_SEPARATOR, approved);
                        out.startTag(null, TAG_MANAGED_SERVICES);
                        out.attribute(null, ATT_APPROVED_LIST, allowedItems);
                        out.attribute(null, ATT_USER_ID, Integer.toString(userId));
                        out.attribute(null, ATT_IS_PRIMARY, Boolean.toString(isPrimary));
                        out.endTag(null, TAG_MANAGED_SERVICES);

                        if (!forBackup && isPrimary) {
                            // Also write values to settings, for observers who haven't migrated yet
                            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                                    getConfig().secureSettingName, allowedItems, userId);
                        }

                    }
                }
            }
        }

        out.endTag(null, getConfig().xmlTag);
    }

    protected void migrateToXml() {
        loadAllowedComponentsFromSettings();
    }

    public void readXml(XmlPullParser parser, Predicate<String> allowedManagedServicePackages)
            throws XmlPullParserException, IOException {
        // upgrade xml
        int xmlVersion = XmlUtils.readIntAttribute(parser, ATT_VERSION, 0);
        final List<UserInfo> activeUsers = mUm.getUsers(true);
        for (UserInfo userInfo : activeUsers) {
            upgradeXml(xmlVersion, userInfo.getUserHandle().getIdentifier());
        }

        // read grants
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (type == XmlPullParser.END_TAG
                    && getConfig().xmlTag.equals(tag)) {
                break;
            }
            if (type == XmlPullParser.START_TAG) {
                if (TAG_MANAGED_SERVICES.equals(tag)) {
                    Slog.i(TAG, "Read " + mConfig.caption + " permissions from xml");

                    final String approved = XmlUtils.readStringAttribute(parser, ATT_APPROVED_LIST);
                    final int userId = XmlUtils.readIntAttribute(parser, ATT_USER_ID, 0);
                    final boolean isPrimary =
                            XmlUtils.readBooleanAttribute(parser, ATT_IS_PRIMARY, true);

                    if (allowedManagedServicePackages == null ||
                            allowedManagedServicePackages.test(getPackageName(approved))) {
                        if (mUm.getUserInfo(userId) != null) {
                            addApprovedList(approved, userId, isPrimary);
                        }
                        mUseXml = true;
                    }
                }
            }
        }
        rebindServices(false);
    }

    protected void upgradeXml(final int xmlVersion, final int userId) {}

    private void loadAllowedComponentsFromSettings() {
        for (UserInfo user : mUm.getUsers()) {
            final ContentResolver cr = mContext.getContentResolver();
            addApprovedList(Settings.Secure.getStringForUser(
                    cr,
                    getConfig().secureSettingName,
                    user.id), user.id, true);
            if (!TextUtils.isEmpty(getConfig().secondarySettingName)) {
                addApprovedList(Settings.Secure.getStringForUser(
                        cr,
                        getConfig().secondarySettingName,
                        user.id), user.id, false);
            }
        }
        Slog.d(TAG, "Done loading approved values from settings");
    }

    protected void addApprovedList(String approved, int userId, boolean isPrimary) {
        if (TextUtils.isEmpty(approved)) {
            approved = "";
        }
        ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.get(userId);
        if (approvedByType == null) {
            approvedByType = new ArrayMap<>();
            mApproved.put(userId, approvedByType);
        }

        ArraySet<String> approvedList = approvedByType.get(isPrimary);
        if (approvedList == null) {
            approvedList = new ArraySet<>();
            approvedByType.put(isPrimary, approvedList);
        }

        String[] approvedArray = approved.split(ENABLED_SERVICES_SEPARATOR);
        for (String pkgOrComponent : approvedArray) {
            String approvedItem = getApprovedValue(pkgOrComponent);
            if (approvedItem != null) {
                approvedList.add(approvedItem);
            }
        }
    }

    protected boolean isComponentEnabledForPackage(String pkg) {
        return mEnabledServicesPackageNames.contains(pkg);
    }

    protected void setPackageOrComponentEnabled(String pkgOrComponent, int userId,
            boolean isPrimary, boolean enabled) {
        Slog.i(TAG,
                (enabled ? " Allowing " : "Disallowing ") + mConfig.caption + " " + pkgOrComponent);
        ArrayMap<Boolean, ArraySet<String>> allowedByType = mApproved.get(userId);
        if (allowedByType == null) {
            allowedByType = new ArrayMap<>();
            mApproved.put(userId, allowedByType);
        }
        ArraySet<String> approved = allowedByType.get(isPrimary);
        if (approved == null) {
            approved = new ArraySet<>();
            allowedByType.put(isPrimary, approved);
        }
        String approvedItem = getApprovedValue(pkgOrComponent);

        if (approvedItem != null) {
            if (enabled) {
                approved.add(approvedItem);
            } else {
                approved.remove(approvedItem);
            }
        }

        rebindServices(false);
    }

    private String getApprovedValue(String pkgOrComponent) {
        if (mApprovalLevel == APPROVAL_BY_COMPONENT) {
            if(ComponentName.unflattenFromString(pkgOrComponent) != null) {
                return pkgOrComponent;
            }
            return null;
        } else {
            return getPackageName(pkgOrComponent);
        }
    }

    protected String getApproved(int userId, boolean primary) {
        final ArrayMap<Boolean, ArraySet<String>> allowedByType =
                mApproved.getOrDefault(userId, new ArrayMap<>());
        ArraySet<String> approved = allowedByType.getOrDefault(primary, new ArraySet<>());
        return String.join(ENABLED_SERVICES_SEPARATOR, approved);
    }

    protected List<ComponentName> getAllowedComponents(int userId) {
        final List<ComponentName> allowedComponents = new ArrayList<>();
        final ArrayMap<Boolean, ArraySet<String>> allowedByType =
                mApproved.getOrDefault(userId, new ArrayMap<>());
        for (int i = 0; i < allowedByType.size(); i++) {
            final ArraySet<String> allowed = allowedByType.valueAt(i);
            for (int j = 0; j < allowed.size(); j++) {
                ComponentName cn = ComponentName.unflattenFromString(allowed.valueAt(j));
                if (cn != null) {
                    allowedComponents.add(cn);
                }
            }
        }
        return allowedComponents;
    }

    protected List<String> getAllowedPackages(int userId) {
        final List<String> allowedPackages = new ArrayList<>();
        final ArrayMap<Boolean, ArraySet<String>> allowedByType =
                mApproved.getOrDefault(userId, new ArrayMap<>());
        for (int i = 0; i < allowedByType.size(); i++) {
            final ArraySet<String> allowed = allowedByType.valueAt(i);
            for (int j = 0; j < allowed.size(); j++) {
                String pkgName = getPackageName(allowed.valueAt(j));
                if (!TextUtils.isEmpty(pkgName)) {
                    allowedPackages.add(pkgName);
                }
            }
        }
        return allowedPackages;
    }

    protected boolean isPackageOrComponentAllowed(String pkgOrComponent, int userId) {
        ArrayMap<Boolean, ArraySet<String>> allowedByType =
                mApproved.getOrDefault(userId, new ArrayMap<>());
        for (int i = 0; i < allowedByType.size(); i++) {
            ArraySet<String> allowed = allowedByType.valueAt(i);
            if (allowed.contains(pkgOrComponent)) {
                return true;
            }
        }
        return false;
    }

    public void onPackagesChanged(boolean removingPackage, String[] pkgList, int[] uidList) {
        if (DEBUG) Slog.d(TAG, "onPackagesChanged removingPackage=" + removingPackage
                + " pkgList=" + (pkgList == null ? null : Arrays.asList(pkgList))
                + " mEnabledServicesPackageNames=" + mEnabledServicesPackageNames);

        if (pkgList != null && (pkgList.length > 0)) {
            boolean anyServicesInvolved = false;
            // Remove notification settings for uninstalled package
            if (removingPackage) {
                int size = Math.min(pkgList.length, uidList.length);
                for (int i = 0; i < size; i++) {
                    final String pkg = pkgList[i];
                    final int userId = UserHandle.getUserId(uidList[i]);
                    anyServicesInvolved = removeUninstalledItemsFromApprovedLists(userId, pkg);
                }
            }
            for (String pkgName : pkgList) {
                if (mEnabledServicesPackageNames.contains(pkgName)) {
                    anyServicesInvolved = true;
                }
            }

            if (anyServicesInvolved) {
                // make sure we're still bound to any of our services who may have just upgraded
                rebindServices(false);
            }
        }
    }

    public void onUserRemoved(int user) {
        Slog.i(TAG, "Removing approved services for removed user " + user);
        mApproved.remove(user);
        rebindServices(true);
    }

    public void onUserSwitched(int user) {
        if (DEBUG) Slog.d(TAG, "onUserSwitched u=" + user);
        if (Arrays.equals(mLastSeenProfileIds, mUserProfiles.getCurrentProfileIds())) {
            if (DEBUG) Slog.d(TAG, "Current profile IDs didn't change, skipping rebindServices().");
            return;
        }
        rebindServices(true);
    }

    public void onUserUnlocked(int user) {
        if (DEBUG) Slog.d(TAG, "onUserUnlocked u=" + user);
        rebindServices(false);
    }

    private ManagedServiceInfo getServiceFromTokenLocked(IInterface service) {
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

    protected boolean isServiceTokenValidLocked(IInterface service) {
        if (service == null) {
            return false;
        }
        ManagedServiceInfo info = getServiceFromTokenLocked(service);
        if (info != null) {
            return true;
        }
        return false;
    }

    protected ManagedServiceInfo checkServiceTokenLocked(IInterface service) {
        checkNotNull(service);
        ManagedServiceInfo info = getServiceFromTokenLocked(service);
        if (info != null) {
            return info;
        }
        throw new SecurityException("Disallowed call from unknown " + getCaption() + ": "
                + service + " " + service.getClass());
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
     * but unlike a system service, it should not be considered privileged.
     * */
    protected void registerGuestService(ManagedServiceInfo guest) {
        checkNotNull(guest.service);
        if (!checkType(guest.service)) {
            throw new IllegalArgumentException();
        }
        if (registerServiceImpl(guest) != null) {
            onServiceAdded(guest);
        }
    }

    protected void setComponentState(ComponentName component, boolean enabled) {
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
        Slog.d(TAG, ((enabled) ? "Enabling " : "Disabling ") + "component " +
                component.flattenToShortString());

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

    private @NonNull ArraySet<ComponentName> loadComponentNamesFromValues(
            ArraySet<String> approved, int userId) {
        if (approved == null || approved.size() == 0)
            return new ArraySet<>();
        ArraySet<ComponentName> result = new ArraySet<>(approved.size());
        for (int i = 0; i < approved.size(); i++) {
            final String packageOrComponent = approved.valueAt(i);
            if (!TextUtils.isEmpty(packageOrComponent)) {
                ComponentName component = ComponentName.unflattenFromString(packageOrComponent);
                if (component != null) {
                    result.add(component);
                } else {
                    result.addAll(queryPackageForServices(packageOrComponent, userId));
                }
            }
        }
        return result;
    }

    protected Set<ComponentName> queryPackageForServices(String packageName, int userId) {
        return queryPackageForServices(packageName, 0, userId);
    }

    protected Set<ComponentName> queryPackageForServices(String packageName, int extraFlags,
            int userId) {
        Set<ComponentName> installed = new ArraySet<>();
        final PackageManager pm = mContext.getPackageManager();
        Intent queryIntent = new Intent(mConfig.serviceInterface);
        if (!TextUtils.isEmpty(packageName)) {
            queryIntent.setPackage(packageName);
        }
        List<ResolveInfo> installedServices = pm.queryIntentServicesAsUser(
                queryIntent,
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA | extraFlags,
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

    private void trimApprovedListsAccordingToInstalledServices() {
        int N = mApproved.size();
        for (int i = 0 ; i < N; i++) {
            final int userId = mApproved.keyAt(i);
            final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.valueAt(i);
            int M = approvedByType.size();
            for (int j = 0; j < M; j++) {
                final ArraySet<String> approved = approvedByType.valueAt(j);
                int P = approved.size();
                for (int k = P - 1; k >= 0; k--) {
                    final String approvedPackageOrComponent = approved.valueAt(k);
                    if (!isValidEntry(approvedPackageOrComponent, userId)){
                        approved.removeAt(k);
                        Slog.v(TAG, "Removing " + approvedPackageOrComponent
                                + " from approved list; no matching services found");
                    } else {
                        if (DEBUG) {
                            Slog.v(TAG, "Keeping " + approvedPackageOrComponent
                                    + " on approved list; matching services found");
                        }
                    }
                }
            }
        }
    }

    private boolean removeUninstalledItemsFromApprovedLists(int uninstalledUserId, String pkg) {
        boolean removed = false;
        final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.get(uninstalledUserId);
        if (approvedByType != null) {
            int M = approvedByType.size();
            for (int j = 0; j < M; j++) {
                final ArraySet<String> approved = approvedByType.valueAt(j);
                int O = approved.size();
                for (int k = O - 1; k >= 0; k--) {
                    final String packageOrComponent = approved.valueAt(k);
                    final String packageName = getPackageName(packageOrComponent);
                    if (TextUtils.equals(pkg, packageName)) {
                        approved.removeAt(k);
                        if (DEBUG) {
                            Slog.v(TAG, "Removing " + packageOrComponent
                                    + " from approved list; uninstalled");
                        }
                    }
                }
            }
        }
        return removed;
    }

    protected String getPackageName(String packageOrComponent) {
        final ComponentName component = ComponentName.unflattenFromString(packageOrComponent);
        if (component != null) {
            return component.getPackageName();
        } else {
            return packageOrComponent;
        }
    }

    protected boolean isValidEntry(String packageOrComponent, int userId) {
        return hasMatchingServices(packageOrComponent, userId);
    }

    private boolean hasMatchingServices(String packageOrComponent, int userId) {
        if (!TextUtils.isEmpty(packageOrComponent)) {
            final String packageName = getPackageName(packageOrComponent);
            return queryPackageForServices(packageName, userId).size() > 0;
        }
        return false;
    }

    /**
     * Called whenever packages change, the user switches, or the secure setting
     * is altered. (For example in response to USER_SWITCHED in our broadcast receiver)
     */
    protected void rebindServices(boolean forceRebind) {
        if (DEBUG) Slog.d(TAG, "rebindServices");
        final int[] userIds = mUserProfiles.getCurrentProfileIds();
        final int nUserIds = userIds.length;

        final SparseArray<ArraySet<ComponentName>> componentsByUser = new SparseArray<>();

        for (int i = 0; i < nUserIds; ++i) {
            final int userId = userIds[i];
            final ArrayMap<Boolean, ArraySet<String>> approvedLists = mApproved.get(userIds[i]);
            if (approvedLists != null) {
                final int N = approvedLists.size();
                for (int j = 0; j < N; j++) {
                    ArraySet<ComponentName> approvedByUser = componentsByUser.get(userId);
                    if (approvedByUser == null) {
                        approvedByUser = new ArraySet<>();
                        componentsByUser.put(userId, approvedByUser);
                    }
                    approvedByUser.addAll(
                            loadComponentNamesFromValues(approvedLists.valueAt(j), userId));
                }
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
                    toAdd.put(userIds[i], new ArraySet<>());
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
                try {
                    ServiceInfo info = mPm.getServiceInfo(component,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userIds[i]);
                    if (info == null) {
                        Slog.w(TAG, "Not binding " + getCaption() + " service " + component
                                + ": service not found");
                        continue;
                    }
                    if (!mConfig.bindPermission.equals(info.permission)) {
                        Slog.w(TAG, "Not binding " + getCaption() + " service " + component
                                + ": it does not require the permission " + mConfig.bindPermission);
                        continue;
                    }
                    Slog.v(TAG,
                            "enabling " + getCaption() + " for " + userIds[i] + ": " + component);
                    registerService(component, userIds[i]);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
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
            Slog.v(TAG, "Not registering " + name + " as bind is already in progress");
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
                Slog.v(TAG, "    disconnecting old " + getCaption() + ": " + info.service);
                removeServiceLocked(i);
                if (info.connection != null) {
                    try {
                        mContext.unbindService(info.connection);
                    } catch (IllegalArgumentException e) {
                        Slog.e(TAG, "failed to unbind " + name, e);
                    }
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
            Slog.v(TAG, "binding: " + intent);
            ServiceConnection serviceConnection = new ServiceConnection() {
                IInterface mService;

                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    boolean added = false;
                    ManagedServiceInfo info = null;
                    synchronized (mMutex) {
                        mServicesRebinding.remove(servicesBindingTag);
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
                    mServicesBinding.remove(servicesBindingTag);
                    Slog.v(TAG, getCaption() + " connection lost: " + name);
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    Slog.w(TAG, getCaption() + " binding died: " + name);
                    synchronized (mMutex) {
                        mServicesBinding.remove(servicesBindingTag);
                        try {
                            mContext.unbindService(this);
                        } catch (IllegalArgumentException e) {
                            Slog.e(TAG, "failed to unbind " + name, e);
                        }
                        if (!mServicesRebinding.contains(servicesBindingTag)) {
                            mServicesRebinding.add(servicesBindingTag);
                            mHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        registerService(name, userid);
                                    }
                               }, ON_BINDING_DIED_REBIND_DELAY_MS);
                        } else {
                            Slog.v(TAG, getCaption() + " not rebinding as "
                                    + "a previous rebind attempt was made: " + name);
                        }
                    }
                }
            };
            if (!mContext.bindServiceAsUser(intent,
                    serviceConnection,
                    getBindFlags(),
                    new UserHandle(userid))) {
                mServicesBinding.remove(servicesBindingTag);
                Slog.w(TAG, "Unable to bind " + getCaption() + " service: " + intent);
                return;
            }
        } catch (SecurityException ex) {
            mServicesBinding.remove(servicesBindingTag);
            Slog.e(TAG, "Unable to bind " + getCaption() + " service: " + intent, ex);
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
            if (name.equals(info.component) && info.userid == userid) {
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
                if (info.service.asBinder() == service.asBinder() && info.userid == userid) {
                    Slog.d(TAG, "Removing active service " + info.component);
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

        public void writeToProto(ProtoOutputStream proto, long fieldId, ManagedServices host) {
            final long token = proto.start(fieldId);
            component.writeToProto(proto, ManagedServiceInfoProto.COMPONENT);
            proto.write(ManagedServiceInfoProto.USER_ID, userid);
            proto.write(ManagedServiceInfoProto.SERVICE, service.getClass().getName());
            proto.write(ManagedServiceInfoProto.IS_SYSTEM, isSystem);
            proto.write(ManagedServiceInfoProto.IS_GUEST, isGuest(host));
            proto.end(token);
        }

        public boolean enabledAndUserMatches(int nid) {
            if (!isEnabledForCurrentProfiles()) {
                return false;
            }
            if (this.userid == UserHandle.USER_ALL) return true;
            if (this.isSystem) return true;
            if (nid == UserHandle.USER_ALL || nid == this.userid) return true;
            return supportsProfiles()
                    && mUserProfiles.isCurrentProfile(nid)
                    && isPermittedForProfile(nid);
        }

        public boolean supportsProfiles() {
            return targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP;
        }

        @Override
        public void binderDied() {
            if (DEBUG) Slog.d(TAG, "binderDied");
            // Remove the service, but don't unbind from the service. The system will bring the
            // service back up, and the onServiceConnected handler will read the service with the
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

        /**
         * Returns true if this service is allowed to receive events for the given userId. A
         * managed profile owner can disallow non-system services running outside of the profile
         * from receiving events from the profile.
         */
        public boolean isPermittedForProfile(int userId) {
            if (!mUserProfiles.isManagedProfile(userId)) {
                return true;
            }
            DevicePolicyManager dpm =
                    (DevicePolicyManager) mContext.getSystemService(DEVICE_POLICY_SERVICE);
            final long identity = Binder.clearCallingIdentity();
            try {
                return dpm.isNotificationListenerServicePermitted(
                        component.getPackageName(), userId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
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

        public boolean isManagedProfile(int userId) {
            synchronized (mCurrentProfiles) {
                UserInfo user = mCurrentProfiles.get(userId);
                return user != null && user.isManagedProfile();
            }
        }
    }

    public static class Config {
        public String caption;
        public String serviceInterface;
        public String secureSettingName;
        public String secondarySettingName;
        public String xmlTag;
        public String bindPermission;
        public String settingsAction;
        public int clientLabel;
    }
}
