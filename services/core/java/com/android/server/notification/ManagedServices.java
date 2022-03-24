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
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_SYSTEM;

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
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.TriPredicate;
import com.android.server.notification.NotificationManagerService.DumpFilter;
import com.android.server.utils.TimingsTraceAndSlog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
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

    private static final int ON_BINDING_DIED_REBIND_DELAY_MS = 10000;
    protected static final String ENABLED_SERVICES_SEPARATOR = ":";
    private static final String DB_VERSION_1 = "1";
    private static final String DB_VERSION_2 = "2";
    private static final String DB_VERSION_3 = "3";


    /**
     * List of components and apps that can have running {@link ManagedServices}.
     */
    static final String TAG_MANAGED_SERVICES = "service_listing";
    static final String ATT_APPROVED_LIST = "approved";
    static final String ATT_USER_ID = "user";
    static final String ATT_IS_PRIMARY = "primary";
    static final String ATT_VERSION = "version";
    static final String ATT_DEFAULTS = "defaults";
    static final String ATT_USER_SET = "user_set_services";
    static final String ATT_USER_SET_OLD = "user_set";
    static final String ATT_USER_CHANGED = "user_changed";

    static final String DB_VERSION = "4";

    static final int APPROVAL_BY_PACKAGE = 0;
    static final int APPROVAL_BY_COMPONENT = 1;

    protected final Context mContext;
    protected final Object mMutex;
    private final UserProfiles mUserProfiles;
    protected final IPackageManager mPm;
    protected final UserManager mUm;
    private final Config mConfig;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    // contains connections to all connected services, including app services
    // and system services
    private final ArrayList<ManagedServiceInfo> mServices = new ArrayList<>();
    /**
     * The services that have been bound by us. If the service is also connected, it will also
     * be in {@link #mServices}.
     */
    private final ArrayList<Pair<ComponentName, Integer>> mServicesBound = new ArrayList<>();
    private final ArraySet<Pair<ComponentName, Integer>> mServicesRebinding = new ArraySet<>();
    // we need these packages to be protected because classes that inherit from it need to see it
    protected final Object mDefaultsLock = new Object();
    protected final ArraySet<ComponentName> mDefaultComponents = new ArraySet<>();
    protected final ArraySet<String> mDefaultPackages = new ArraySet<>();

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
    protected ArrayMap<Integer, ArrayMap<Boolean, ArraySet<String>>> mApproved = new ArrayMap<>();

    // List of packages or components (by user) that are configured to be enabled/disabled
    // explicitly by the user
    @GuardedBy("mApproved")
    protected ArrayMap<Integer, ArraySet<String>> mUserSetServices = new ArrayMap<>();

    protected ArrayMap<Integer, Boolean> mIsUserChanged = new ArrayMap<>();

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

    abstract protected void ensureFilters(ServiceInfo si, int userId);

    protected List<ManagedServiceInfo> getServices() {
        synchronized (mMutex) {
            List<ManagedServiceInfo> services = new ArrayList<>(mServices);
            return services;
        }
    }

    protected void addDefaultComponentOrPackage(String packageOrComponent) {
        if (!TextUtils.isEmpty(packageOrComponent)) {
            synchronized (mDefaultsLock) {
                if (mApprovalLevel == APPROVAL_BY_PACKAGE) {
                    mDefaultPackages.add(packageOrComponent);
                    return;
                }
                ComponentName cn = ComponentName.unflattenFromString(packageOrComponent);
                if (cn != null  && mApprovalLevel == APPROVAL_BY_COMPONENT) {
                    mDefaultPackages.add(cn.getPackageName());
                    mDefaultComponents.add(cn);
                    return;
                }
            }
        }
    }

    protected abstract void loadDefaultsFromConfig();

    boolean isDefaultComponentOrPackage(String packageOrComponent) {
        synchronized (mDefaultsLock) {
            ComponentName cn = ComponentName.unflattenFromString(packageOrComponent);
            if (cn == null) {
                return mDefaultPackages.contains(packageOrComponent);
            } else {
                return mDefaultComponents.contains(cn);
            }
        }
    }

    ArraySet<ComponentName> getDefaultComponents() {
        synchronized (mDefaultsLock) {
            return new ArraySet<>(mDefaultComponents);
        }
    }

    ArraySet<String> getDefaultPackages() {
        synchronized (mDefaultsLock) {
            return new ArraySet<>(mDefaultPackages);
        }
    }

    /**
     * When resetting a package, we need to enable default components that belong to that packages
     * we also need to disable components that are not default to return the managed service state
     * to when a new android device is first turned on for that package.
     *
     * @param packageName package to reset.
     * @param userId the android user id
     * @return a list of components that were permitted
     */
    @NonNull
    ArrayMap<Boolean, ArrayList<ComponentName>> resetComponents(String packageName, int userId) {
        // components that we want to enable
        ArrayList<ComponentName> componentsToEnable =
                new ArrayList<>(mDefaultComponents.size());

        // components that were removed
        ArrayList<ComponentName> disabledComponents =
                new ArrayList<>(mDefaultComponents.size());

        // all components that are enabled now
        ArraySet<ComponentName> enabledComponents =
                new ArraySet<>(getAllowedComponents(userId));

        boolean changed = false;

        synchronized (mDefaultsLock) {
            // record all components that are enabled but should not be by default
            for (int i = 0; i < mDefaultComponents.size() && enabledComponents.size() > 0; i++) {
                ComponentName currentDefault = mDefaultComponents.valueAt(i);
                if (packageName.equals(currentDefault.getPackageName())
                        && !enabledComponents.contains(currentDefault)) {
                    componentsToEnable.add(currentDefault);
                }
            }
            synchronized (mApproved) {
                final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.get(
                        userId);
                if (approvedByType != null) {
                    final int M = approvedByType.size();
                    for (int j = 0; j < M; j++) {
                        final ArraySet<String> approved = approvedByType.valueAt(j);
                        for (int i = 0; i < enabledComponents.size(); i++) {
                            ComponentName currentComponent = enabledComponents.valueAt(i);
                            if (packageName.equals(currentComponent.getPackageName())
                                    && !mDefaultComponents.contains(currentComponent)) {
                                if (approved.remove(currentComponent.flattenToString())) {
                                    disabledComponents.add(currentComponent);
                                    clearUserSetFlagLocked(currentComponent, userId);
                                    changed = true;
                                }
                            }
                        }
                        for (int i = 0; i < componentsToEnable.size(); i++) {
                            ComponentName candidate = componentsToEnable.get(i);
                            changed |= approved.add(candidate.flattenToString());
                        }
                    }

                }
            }
        }
        if (changed) rebindServices(false, USER_ALL);

        ArrayMap<Boolean, ArrayList<ComponentName>> changes = new ArrayMap<>();
        changes.put(true, componentsToEnable);
        changes.put(false, disabledComponents);

        return changes;
    }

    private boolean clearUserSetFlagLocked(ComponentName component, int userId) {
        String approvedValue = getApprovedValue(component.flattenToString());
        ArraySet<String> userSet = mUserSetServices.get(userId);
        return userSet != null && userSet.remove(approvedValue);
    }

    protected int getBindFlags() {
        return BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE | BIND_ALLOW_WHITELIST_MANAGEMENT;
    }

    protected void onServiceRemovedLocked(ManagedServiceInfo removed) { }

    private ManagedServiceInfo newServiceInfo(IInterface service,
            ComponentName component, int userId, boolean isSystem, ServiceConnection connection,
            int targetSdkVersion, int uid) {
        return new ManagedServiceInfo(service, component, userId, isSystem, connection,
                targetSdkVersion, uid);
    }

    public void onBootPhaseAppsCanStart() {}

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    Allowed " + getCaption() + "s:");
        synchronized (mApproved) {
            final int N = mApproved.size();
            for (int i = 0; i < N; i++) {
                final int userId = mApproved.keyAt(i);
                final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.valueAt(i);
                final Boolean userChanged = mIsUserChanged.get(userId);
                if (approvedByType != null) {
                    final int M = approvedByType.size();
                    for (int j = 0; j < M; j++) {
                        final boolean isPrimary = approvedByType.keyAt(j);
                        final ArraySet<String> approved = approvedByType.valueAt(j);
                        if (approvedByType != null && approvedByType.size() > 0) {
                            pw.println("      " + String.join(ENABLED_SERVICES_SEPARATOR, approved)
                                    + " (user: " + userId + " isPrimary: " + isPrimary
                                    + (userChanged == null ? "" : " isUserChanged: "
                                    + userChanged) + ")");
                        }
                    }
                }
            }
            pw.println("    Has user set:");
            Set<Integer> userIds = mUserSetServices.keySet();
            for (int userId : userIds) {
                if (mIsUserChanged.get(userId) == null) {
                    pw.println("      userId=" + userId + " value="
                            + (mUserSetServices.get(userId)));
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
        synchronized (mMutex) {
            for (ManagedServiceInfo info : mServices) {
                if (filter != null && !filter.matches(info.component)) continue;
                pw.println("      " + info.component
                        + " (user " + info.userid + "): " + info.service
                        + (info.isSystem ? " SYSTEM" : "")
                        + (info.isGuest(this) ? " GUEST" : ""));
            }
        }

        pw.println("    Snoozed " + getCaption() + "s (" +
                mSnoozingForCurrentProfiles.size() + "):");
        for (ComponentName name : mSnoozingForCurrentProfiles) {
            pw.println("      " + name.flattenToShortString());
        }
    }

    public void dump(ProtoOutputStream proto, DumpFilter filter) {
        proto.write(ManagedServicesProto.CAPTION, getCaption());
        synchronized (mApproved) {
            final int N = mApproved.size();
            for (int i = 0; i < N; i++) {
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
        }

        for (ComponentName cmpt : mEnabledServicesForCurrentProfiles) {
            if (filter != null && !filter.matches(cmpt)) continue;
            cmpt.dumpDebug(proto, ManagedServicesProto.ENABLED);
        }

        synchronized (mMutex) {
            for (ManagedServiceInfo info : mServices) {
                if (filter != null && !filter.matches(info.component)) continue;
                info.dumpDebug(proto, ManagedServicesProto.LIVE_SERVICES, this);
            }
        }

        for (ComponentName name : mSnoozingForCurrentProfiles) {
            name.dumpDebug(proto, ManagedServicesProto.SNOOZED);
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
                if (shouldReflectToSettings()) {
                    Settings.Secure.putStringForUser(
                            mContext.getContentResolver(), element, value, userId);
                }

                for (UserInfo user : mUm.getUsers()) {
                    addApprovedList(value, user.id, mConfig.secureSettingName.equals(element));
                }
                Slog.d(TAG, "Done loading approved values from settings");
                rebindServices(false, userId);
            }
        }
    }

    void writeDefaults(TypedXmlSerializer out) throws IOException {
        synchronized (mDefaultsLock) {
            List<String> componentStrings = new ArrayList<>(mDefaultComponents.size());
            for (int i = 0; i < mDefaultComponents.size(); i++) {
                componentStrings.add(mDefaultComponents.valueAt(i).flattenToString());
            }
            String defaults = String.join(ENABLED_SERVICES_SEPARATOR, componentStrings);
            out.attribute(null, ATT_DEFAULTS, defaults);
        }
    }

    public void writeXml(TypedXmlSerializer out, boolean forBackup, int userId) throws IOException {
        out.startTag(null, getConfig().xmlTag);

        out.attributeInt(null, ATT_VERSION, Integer.parseInt(DB_VERSION));

        writeDefaults(out);

        if (forBackup) {
            trimApprovedListsAccordingToInstalledServices(userId);
        }

        synchronized (mApproved) {
            final int N = mApproved.size();
            for (int i = 0; i < N; i++) {
                final int approvedUserId = mApproved.keyAt(i);
                if (forBackup && approvedUserId != userId) {
                    continue;
                }
                final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.valueAt(i);
                final Boolean isUserChanged = mIsUserChanged.get(approvedUserId);
                if (approvedByType != null) {
                    final int M = approvedByType.size();
                    for (int j = 0; j < M; j++) {
                        final boolean isPrimary = approvedByType.keyAt(j);
                        final Set<String> approved = approvedByType.valueAt(j);
                        final Set<String> userSet = mUserSetServices.get(approvedUserId);
                        if (approved != null || userSet != null || isUserChanged != null) {
                            String allowedItems = approved == null
                                    ? ""
                                    : String.join(ENABLED_SERVICES_SEPARATOR, approved);
                            out.startTag(null, TAG_MANAGED_SERVICES);
                            out.attribute(null, ATT_APPROVED_LIST, allowedItems);
                            out.attributeInt(null, ATT_USER_ID, approvedUserId);
                            out.attributeBoolean(null, ATT_IS_PRIMARY, isPrimary);
                            if (isUserChanged != null) {
                                out.attributeBoolean(null, ATT_USER_CHANGED, isUserChanged);
                            } else if (userSet != null) {
                                String userSetItems =
                                        String.join(ENABLED_SERVICES_SEPARATOR, userSet);
                                out.attribute(null, ATT_USER_SET, userSetItems);
                            }
                            writeExtraAttributes(out, approvedUserId);
                            out.endTag(null, TAG_MANAGED_SERVICES);

                            if (!forBackup && isPrimary) {
                                if (shouldReflectToSettings()) {
                                    // Also write values to settings, for observers who haven't
                                    // migrated yet
                                    Settings.Secure.putStringForUser(mContext.getContentResolver(),
                                            getConfig().secureSettingName, allowedItems,
                                            approvedUserId);
                                }
                            }

                        }
                    }
                }
            }
        }

        writeExtraXmlTags(out);

        out.endTag(null, getConfig().xmlTag);
    }

    /**
     * Returns whether the approved list of services should also be written to the Settings db
     */
    protected boolean shouldReflectToSettings() {
        return false;
    }

    /**
     * Writes extra xml attributes to {@link #TAG_MANAGED_SERVICES} tag.
     */
    protected void writeExtraAttributes(TypedXmlSerializer out, int userId) throws IOException {}

    /**
     * Writes extra xml tags within the parent tag specified in {@link Config#xmlTag}.
     */
    protected void writeExtraXmlTags(TypedXmlSerializer out) throws IOException {}

    /**
     * This is called to process tags other than {@link #TAG_MANAGED_SERVICES}.
     */
    protected void readExtraTag(String tag, TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {}

    protected final void migrateToXml() {
        for (UserInfo user : mUm.getUsers()) {
            final ContentResolver cr = mContext.getContentResolver();
            if (!TextUtils.isEmpty(getConfig().secureSettingName)) {
                addApprovedList(Settings.Secure.getStringForUser(
                        cr,
                        getConfig().secureSettingName,
                        user.id), user.id, true);
            }
            if (!TextUtils.isEmpty(getConfig().secondarySettingName)) {
                addApprovedList(Settings.Secure.getStringForUser(
                        cr,
                        getConfig().secondarySettingName,
                        user.id), user.id, false);
            }
        }
    }

    void readDefaults(TypedXmlPullParser parser) {
        String defaultComponents = XmlUtils.readStringAttribute(parser, ATT_DEFAULTS);

        if (!TextUtils.isEmpty(defaultComponents)) {
            String[] components = defaultComponents.split(ENABLED_SERVICES_SEPARATOR);
            synchronized (mDefaultsLock) {
                for (int i = 0; i < components.length; i++) {
                    if (!TextUtils.isEmpty(components[i])) {
                        ComponentName cn = ComponentName.unflattenFromString(components[i]);
                        if (cn != null) {
                            mDefaultPackages.add(cn.getPackageName());
                            mDefaultComponents.add(cn);
                        } else {
                            mDefaultPackages.add(components[i]);
                        }
                    }
                }
            }
        }
    }

    public void readXml(
            TypedXmlPullParser parser,
            TriPredicate<String, Integer, String> allowedManagedServicePackages,
            boolean forRestore,
            int userId)
            throws XmlPullParserException, IOException {
        // read grants
        int type;
        String version = XmlUtils.readStringAttribute(parser, ATT_VERSION);
        boolean needUpgradeUserset = false;
        readDefaults(parser);
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
                    // Ignore parser's user id for restore.
                    final int resolvedUserId = forRestore
                            ? userId : parser.getAttributeInt(null, ATT_USER_ID, 0);
                    final boolean isPrimary =
                            parser.getAttributeBoolean(null, ATT_IS_PRIMARY, true);

                    // Load three different userSet attributes from xml
                    // user_changed, not null if version == 4 and is NAS setting
                    final String isUserChanged = XmlUtils.readStringAttribute(parser,
                            ATT_USER_CHANGED);
                    // user_set, not null if version <= 3
                    final String isUserChanged_Old = XmlUtils.readStringAttribute(parser,
                            ATT_USER_SET_OLD);
                    // user_set_services, not null if version >= 3 and is non-NAS setting
                    String userSetComponent = XmlUtils.readStringAttribute(parser, ATT_USER_SET);

                    // since the same xml version may have different userSet attributes,
                    // we need to check both xml version and userSet values to know how to set
                    // the userSetComponent/mIsUserChanged to the correct value
                    if (DB_VERSION.equals(version)) {
                        // version 4, NAS contains user_changed and
                        // NLS/others contain user_set_services
                        if (isUserChanged == null) { //NLS
                            userSetComponent = TextUtils.emptyIfNull(userSetComponent);
                        } else { //NAS
                            mIsUserChanged.put(resolvedUserId, Boolean.valueOf(isUserChanged));
                            userSetComponent = Boolean.valueOf(isUserChanged) ? approved : "";
                        }
                    } else {
                        // version 3 may contain user_set (R) or user_set_services (S)
                        // version 2 or older contain user_set or nothing
                        needUpgradeUserset = true;
                        if (userSetComponent == null) { //contains user_set
                            if (isUserChanged_Old != null && Boolean.valueOf(isUserChanged_Old)) {
                                //user_set = true
                                userSetComponent = approved;
                                mIsUserChanged.put(resolvedUserId, true);
                                needUpgradeUserset = false;
                            } else {
                                userSetComponent = "";
                            }
                        }
                    }
                    readExtraAttributes(tag, parser, resolvedUserId);
                    if (allowedManagedServicePackages == null || allowedManagedServicePackages.test(
                            getPackageName(approved), resolvedUserId, getRequiredPermission())
                            || approved.isEmpty()) {
                        if (mUm.getUserInfo(resolvedUserId) != null) {
                            addApprovedList(approved, resolvedUserId, isPrimary, userSetComponent);
                        }
                        mUseXml = true;
                    }
                } else {
                    readExtraTag(tag, parser);
                }
            }
        }
        boolean isOldVersion = TextUtils.isEmpty(version)
                || DB_VERSION_1.equals(version)
                || DB_VERSION_2.equals(version)
                || DB_VERSION_3.equals(version);
        if (isOldVersion) {
            upgradeDefaultsXmlVersion();
        }
        if (needUpgradeUserset) {
            upgradeUserSet();
        }

        rebindServices(false, USER_ALL);
    }

    void upgradeDefaultsXmlVersion() {
        // check if any defaults are loaded
        int defaultsSize = mDefaultComponents.size() + mDefaultPackages.size();
        if (defaultsSize == 0) {
            // load defaults from current allowed
            if (this.mApprovalLevel == APPROVAL_BY_COMPONENT) {
                List<ComponentName> approvedComponents = getAllowedComponents(USER_SYSTEM);
                for (int i = 0; i < approvedComponents.size(); i++) {
                    addDefaultComponentOrPackage(approvedComponents.get(i).flattenToString());
                }
            }
            if (this.mApprovalLevel == APPROVAL_BY_PACKAGE) {
                List<String> approvedPkgs = getAllowedPackages(USER_SYSTEM);
                for (int i = 0; i < approvedPkgs.size(); i++) {
                    addDefaultComponentOrPackage(approvedPkgs.get(i));
                }
            }
        }
        // if no defaults are loaded, then load from config
        defaultsSize = mDefaultComponents.size() + mDefaultPackages.size();
        if (defaultsSize == 0) {
            loadDefaultsFromConfig();
        }
    }

    protected void upgradeUserSet() {};

    /**
     * Read extra attributes in the {@link #TAG_MANAGED_SERVICES} tag.
     */
    protected void readExtraAttributes(String tag, TypedXmlPullParser parser, int userId)
            throws IOException {}

    protected abstract String getRequiredPermission();

    protected void addApprovedList(String approved, int userId, boolean isPrimary) {
        addApprovedList(approved, userId, isPrimary, approved);
    }

    protected void addApprovedList(String approved, int userId, boolean isPrimary, String userSet) {
        if (TextUtils.isEmpty(approved)) {
            approved = "";
        }
        if (userSet == null) {
            userSet = approved;
        }
        synchronized (mApproved) {
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

            ArraySet<String> userSetList = mUserSetServices.get(userId);
            if (userSetList == null) {
                userSetList = new ArraySet<>();
                mUserSetServices.put(userId, userSetList);
            }
            String[] userSetArray = userSet.split(ENABLED_SERVICES_SEPARATOR);
            for (String pkgOrComponent : userSetArray) {
                String approvedItem = getApprovedValue(pkgOrComponent);
                if (approvedItem != null) {
                    userSetList.add(approvedItem);
                }
            }
        }
    }

    protected boolean isComponentEnabledForPackage(String pkg) {
        return mEnabledServicesPackageNames.contains(pkg);
    }

    protected void setPackageOrComponentEnabled(String pkgOrComponent, int userId,
            boolean isPrimary, boolean enabled) {
        setPackageOrComponentEnabled(pkgOrComponent, userId, isPrimary, enabled, true);
    }

    protected void setPackageOrComponentEnabled(String pkgOrComponent, int userId,
            boolean isPrimary, boolean enabled, boolean userSet) {
        Slog.i(TAG,
                (enabled ? " Allowing " : "Disallowing ") + mConfig.caption + " "
                        + pkgOrComponent + " (userSet: " + userSet + ")");
        synchronized (mApproved) {
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
            ArraySet<String> userSetServices = mUserSetServices.get(userId);
            if (userSetServices == null) {
                userSetServices = new ArraySet<>();
                mUserSetServices.put(userId, userSetServices);
            }
            if (userSet) {
                userSetServices.add(pkgOrComponent);
            } else {
                userSetServices.remove(pkgOrComponent);
            }
        }

        rebindServices(false, userId);
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
        synchronized (mApproved) {
            final ArrayMap<Boolean, ArraySet<String>> allowedByType =
                    mApproved.getOrDefault(userId, new ArrayMap<>());
            ArraySet<String> approved = allowedByType.getOrDefault(primary, new ArraySet<>());
            return String.join(ENABLED_SERVICES_SEPARATOR, approved);
        }
    }

    protected List<ComponentName> getAllowedComponents(int userId) {
        final List<ComponentName> allowedComponents = new ArrayList<>();
        synchronized (mApproved) {
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
        }
        return allowedComponents;
    }

    protected List<String> getAllowedPackages(int userId) {
        final List<String> allowedPackages = new ArrayList<>();
        synchronized (mApproved) {
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
        }
        return allowedPackages;
    }

    protected boolean isPackageOrComponentAllowed(String pkgOrComponent, int userId) {
        synchronized (mApproved) {
            ArrayMap<Boolean, ArraySet<String>> allowedByType =
                    mApproved.getOrDefault(userId, new ArrayMap<>());
            for (int i = 0; i < allowedByType.size(); i++) {
                ArraySet<String> allowed = allowedByType.valueAt(i);
                if (allowed.contains(pkgOrComponent)) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean isPackageOrComponentUserSet(String pkgOrComponent, int userId) {
        synchronized (mApproved) {
            ArraySet<String> services = mUserSetServices.get(userId);
            return services != null && services.contains(pkgOrComponent);
        }
    }

    protected boolean isPackageAllowed(String pkg, int userId) {
        if (pkg == null) {
            return false;
        }
        synchronized (mApproved) {
            ArrayMap<Boolean, ArraySet<String>> allowedByType =
                    mApproved.getOrDefault(userId, new ArrayMap<>());
            for (int i = 0; i < allowedByType.size(); i++) {
                ArraySet<String> allowed = allowedByType.valueAt(i);
                for (String allowedEntry : allowed) {
                    ComponentName component = ComponentName.unflattenFromString(allowedEntry);
                    if (component != null) {
                        if (pkg.equals(component.getPackageName())) {
                            return true;
                        }
                    } else {
                        if (pkg.equals(allowedEntry)) {
                            return true;
                        }
                    }
                }
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
            if (removingPackage && uidList != null) {
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
                if (uidList != null && uidList.length > 0) {
                    for (int uid : uidList) {
                        if (isPackageAllowed(pkgName, UserHandle.getUserId(uid))) {
                            anyServicesInvolved = true;
                        }
                    }
                }
            }

            if (anyServicesInvolved) {
                // make sure we're still bound to any of our services who may have just upgraded
                rebindServices(false, USER_ALL);
            }
        }
    }

    public void onUserRemoved(int user) {
        Slog.i(TAG, "Removing approved services for removed user " + user);
        synchronized (mApproved) {
            mApproved.remove(user);
        }
        rebindServices(true, user);
    }

    public void onUserSwitched(int user) {
        if (DEBUG) Slog.d(TAG, "onUserSwitched u=" + user);
        unbindOtherUserServices(user);
        rebindServices(true, user);
    }

    public void onUserUnlocked(int user) {
        if (DEBUG) Slog.d(TAG, "onUserUnlocked u=" + user);
        rebindServices(false, user);
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

    public boolean isSameUser(IInterface service, int userId) {
        checkNotNull(service);
        synchronized (mMutex) {
            ManagedServiceInfo info = getServiceFromTokenLocked(service);
            if (info != null) {
                return info.isSameUser(userId);
            }
            return false;
        }
    }

    public void unregisterService(IInterface service, int userid) {
        checkNotNull(service);
        // no need to check permissions; if your service binder is in the list,
        // that's proof that you had permission to add it in the first place
        unregisterServiceImpl(service, userid);
    }

    public void registerSystemService(IInterface service, ComponentName component, int userid,
            int uid) {
        checkNotNull(service);
        ManagedServiceInfo info = registerServiceImpl(
                service, component, userid, Build.VERSION_CODES.CUR_DEVELOPMENT, uid);
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

    protected void setComponentState(ComponentName component, int userId, boolean enabled) {
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
            if (enabled) {
                if (isPackageOrComponentAllowed(component.flattenToString(), userId)
                        || isPackageOrComponentAllowed(component.getPackageName(), userId)) {
                    registerServiceLocked(component, userId);
                } else {
                    Slog.d(TAG, component + " no longer has permission to be bound");
                }
            } else {
                unregisterServiceLocked(component, userId);
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

    protected ArraySet<ComponentName> queryPackageForServices(String packageName, int extraFlags,
            int userId) {
        ArraySet<ComponentName> installed = new ArraySet<>();
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

    protected Set<String> getAllowedPackages() {
        final Set<String> allowedPackages = new ArraySet<>();
        synchronized (mApproved) {
            for (int k = 0; k < mApproved.size(); k++) {
                ArrayMap<Boolean, ArraySet<String>> allowedByType = mApproved.valueAt(k);
                for (int i = 0; i < allowedByType.size(); i++) {
                    final ArraySet<String> allowed = allowedByType.valueAt(i);
                    for (int j = 0; j < allowed.size(); j++) {
                        String pkgName = getPackageName(allowed.valueAt(j));
                        if (!TextUtils.isEmpty(pkgName)) {
                            allowedPackages.add(pkgName);
                        }
                    }
                }
            }
        }
        return allowedPackages;
    }

    private void trimApprovedListsAccordingToInstalledServices(int userId) {
        synchronized (mApproved) {
            final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.get(userId);
            if (approvedByType == null) {
                return;
            }
            for (int i = 0; i < approvedByType.size(); i++) {
                final ArraySet<String> approved = approvedByType.valueAt(i);
                for (int j = approved.size() - 1; j >= 0; j--) {
                    final String approvedPackageOrComponent = approved.valueAt(j);
                    if (!isValidEntry(approvedPackageOrComponent, userId)) {
                        approved.removeAt(j);
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
        synchronized (mApproved) {
            final ArrayMap<Boolean, ArraySet<String>> approvedByType = mApproved.get(
                    uninstalledUserId);
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

    @VisibleForTesting
    protected SparseArray<ArraySet<ComponentName>> getAllowedComponents(IntArray userIds) {
        final int nUserIds = userIds.size();
        final SparseArray<ArraySet<ComponentName>> componentsByUser = new SparseArray<>();

        for (int i = 0; i < nUserIds; ++i) {
            final int userId = userIds.get(i);
            synchronized (mApproved) {
                final ArrayMap<Boolean, ArraySet<String>> approvedLists = mApproved.get(userId);
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
        }
        return componentsByUser;
    }

    @GuardedBy("mMutex")
    protected void populateComponentsToBind(SparseArray<Set<ComponentName>> componentsToBind,
            final IntArray activeUsers,
            SparseArray<ArraySet<ComponentName>> approvedComponentsByUser) {
        mEnabledServicesForCurrentProfiles.clear();
        mEnabledServicesPackageNames.clear();
        final int nUserIds = activeUsers.size();

        for (int i = 0; i < nUserIds; ++i) {
            // decode the list of components
            final int userId = activeUsers.get(i);
            final ArraySet<ComponentName> userComponents = approvedComponentsByUser.get(userId);
            if (null == userComponents) {
                componentsToBind.put(userId, new ArraySet<>());
                continue;
            }

            final Set<ComponentName> add = new HashSet<>(userComponents);
            add.removeAll(mSnoozingForCurrentProfiles);

            componentsToBind.put(userId, add);

            mEnabledServicesForCurrentProfiles.addAll(userComponents);

            for (int j = 0; j < userComponents.size(); j++) {
                final ComponentName component = userComponents.valueAt(j);
                mEnabledServicesPackageNames.add(component.getPackageName());
            }
        }
    }

    @GuardedBy("mMutex")
    protected Set<ManagedServiceInfo> getRemovableConnectedServices() {
        final Set<ManagedServiceInfo> removableBoundServices = new ArraySet<>();
        for (ManagedServiceInfo service : mServices) {
            if (!service.isSystem && !service.isGuest(this)) {
                removableBoundServices.add(service);
            }
        }
        return removableBoundServices;
    }

    protected void populateComponentsToUnbind(
            boolean forceRebind,
            Set<ManagedServiceInfo> removableBoundServices,
            SparseArray<Set<ComponentName>> allowedComponentsToBind,
            SparseArray<Set<ComponentName>> componentsToUnbind) {
        for (ManagedServiceInfo info : removableBoundServices) {
            final Set<ComponentName> allowedComponents = allowedComponentsToBind.get(info.userid);
            if (allowedComponents != null) {
                if (forceRebind || !allowedComponents.contains(info.component)) {
                    Set<ComponentName> toUnbind =
                            componentsToUnbind.get(info.userid, new ArraySet<>());
                    toUnbind.add(info.component);
                    componentsToUnbind.put(info.userid, toUnbind);
                }
            }
        }
    }

    /**
     * Called whenever packages change, the user switches, or the secure setting
     * is altered. (For example in response to USER_SWITCHED in our broadcast receiver)
     */
    protected void rebindServices(boolean forceRebind, int userToRebind) {
        if (DEBUG) Slog.d(TAG, "rebindServices " + forceRebind + " " + userToRebind);
        IntArray userIds = mUserProfiles.getCurrentProfileIds();
        if (userToRebind != USER_ALL) {
            userIds = new IntArray(1);
            userIds.add(userToRebind);
        }

        final SparseArray<Set<ComponentName>> componentsToBind = new SparseArray<>();
        final SparseArray<Set<ComponentName>> componentsToUnbind = new SparseArray<>();

        synchronized (mMutex) {
            final SparseArray<ArraySet<ComponentName>> approvedComponentsByUser =
                    getAllowedComponents(userIds);
            final Set<ManagedServiceInfo> removableBoundServices = getRemovableConnectedServices();

            // Filter approvedComponentsByUser to collect all of the components that are allowed
            // for the currently active user(s).
            populateComponentsToBind(componentsToBind, userIds, approvedComponentsByUser);

            // For every current non-system connection, disconnect services that are no longer
            // approved, or ALL services if we are force rebinding
            populateComponentsToUnbind(
                    forceRebind, removableBoundServices, componentsToBind, componentsToUnbind);
        }

        unbindFromServices(componentsToUnbind);
        bindToServices(componentsToBind);
    }

    /**
     * Called when user switched to unbind all services from other users.
     */
    @VisibleForTesting
    void unbindOtherUserServices(int currentUser) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("ManagedServices.unbindOtherUserServices_current" + currentUser);
        final SparseArray<Set<ComponentName>> componentsToUnbind = new SparseArray<>();

        synchronized (mMutex) {
            final Set<ManagedServiceInfo> removableBoundServices = getRemovableConnectedServices();
            for (ManagedServiceInfo info : removableBoundServices) {
                if (info.userid != currentUser) {
                    Set<ComponentName> toUnbind =
                            componentsToUnbind.get(info.userid, new ArraySet<>());
                    toUnbind.add(info.component);
                    componentsToUnbind.put(info.userid, toUnbind);
                }
            }
        }
        unbindFromServices(componentsToUnbind);
        t.traceEnd();
    }

    protected void unbindFromServices(SparseArray<Set<ComponentName>> componentsToUnbind) {
        for (int i = 0; i < componentsToUnbind.size(); i++) {
            final int userId = componentsToUnbind.keyAt(i);
            final Set<ComponentName> removableComponents = componentsToUnbind.get(userId);
            for (ComponentName cn : removableComponents) {
                // No longer allowed to be bound, or must rebind.
                Slog.v(TAG, "disabling " + getCaption() + " for user " + userId + ": " + cn);
                unregisterService(cn, userId);
            }
        }
    }

    // Attempt to bind to services, skipping those that cannot be found or lack the permission.
    private void bindToServices(SparseArray<Set<ComponentName>> componentsToBind) {
        for (int i = 0; i < componentsToBind.size(); i++) {
            final int userId = componentsToBind.keyAt(i);
            final Set<ComponentName> add = componentsToBind.get(userId);
            for (ComponentName component : add) {
                try {
                    ServiceInfo info = mPm.getServiceInfo(component,
                            PackageManager.GET_META_DATA
                                    | PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                            userId);
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
                            "enabling " + getCaption() + " for " + userId + ": " + component);
                    registerService(info, userId);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Version of registerService that takes the name of a service component to bind to.
     */
    @VisibleForTesting
    void registerService(final ServiceInfo si, final int userId) {
        ensureFilters(si, userId);
        registerService(si.getComponentName(), userId);
    }

    @VisibleForTesting
    void registerService(final ComponentName cn, final int userId) {
        synchronized (mMutex) {
            registerServiceLocked(cn, userId);
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

        final Pair<ComponentName, Integer> servicesBindingTag = Pair.create(name, userid);
        if (mServicesBound.contains(servicesBindingTag)) {
            Slog.v(TAG, "Not registering " + name + " is already bound");
            // stop registering this thing already! we're working on it
            return;
        }
        mServicesBound.add(servicesBindingTag);

        final int N = mServices.size();
        for (int i = N - 1; i >= 0; i--) {
            final ManagedServiceInfo info = mServices.get(i);
            if (name.equals(info.component)
                && info.userid == userid) {
                // cut old connections
                Slog.v(TAG, "    disconnecting old " + getCaption() + ": " + info.service);
                removeServiceLocked(i);
                if (info.connection != null) {
                    unbindService(info.connection, info.component, info.userid);
                }
            }
        }

        Intent intent = new Intent(mConfig.serviceInterface);
        intent.setComponent(name);

        intent.putExtra(Intent.EXTRA_CLIENT_LABEL, mConfig.clientLabel);

        final PendingIntent pendingIntent = PendingIntent.getActivity(
            mContext, 0, new Intent(mConfig.settingsAction), PendingIntent.FLAG_IMMUTABLE);
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
        final int uid = appInfo != null ? appInfo.uid : -1;

        try {
            Slog.v(TAG, "binding: " + intent);
            ServiceConnection serviceConnection = new ServiceConnection() {
                IInterface mService;

                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    Slog.v(TAG,  userid + " " + getCaption() + " service connected: " + name);
                    boolean added = false;
                    ManagedServiceInfo info = null;
                    synchronized (mMutex) {
                        mServicesRebinding.remove(servicesBindingTag);
                        try {
                            mService = asInterface(binder);
                            info = newServiceInfo(mService, name,
                                userid, isSystem, this, targetSdkVersion, uid);
                            binder.linkToDeath(info, 0);
                            added = mServices.add(info);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Failed to linkToDeath, already dead", e);
                        }
                    }
                    if (added) {
                        onServiceAdded(info);
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Slog.v(TAG, userid + " " + getCaption() + " connection lost: " + name);
                }

                @Override
                public void onBindingDied(ComponentName name) {
                    Slog.w(TAG,  userid + " " + getCaption() + " binding died: " + name);
                    synchronized (mMutex) {
                        unbindService(this, name, userid);
                        if (!mServicesRebinding.contains(servicesBindingTag)) {
                            mServicesRebinding.add(servicesBindingTag);
                            mHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        registerService(name, userid);
                                    }
                               }, ON_BINDING_DIED_REBIND_DELAY_MS);
                        } else {
                            Slog.v(TAG, getCaption() + " not rebinding in user " + userid
                                    + " as a previous rebind attempt was made: " + name);
                        }
                    }
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    Slog.v(TAG, "onNullBinding() called with: name = [" + name + "]");
                    mContext.unbindService(this);
                }
            };
            if (!mContext.bindServiceAsUser(intent,
                    serviceConnection,
                    getBindFlags(),
                    new UserHandle(userid))) {
                mServicesBound.remove(servicesBindingTag);
                Slog.w(TAG, "Unable to bind " + getCaption() + " service: " + intent
                        + " in user " + userid);
                return;
            }
        } catch (SecurityException ex) {
            mServicesBound.remove(servicesBindingTag);
            Slog.e(TAG, "Unable to bind " + getCaption() + " service: " + intent, ex);
        }
    }

    boolean isBound(ComponentName cn, int userId) {
        final Pair<ComponentName, Integer> servicesBindingTag = Pair.create(cn, userId);
        return mServicesBound.contains(servicesBindingTag);
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
                    unbindService(info.connection, info.component, info.userid);
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
            final ComponentName component, final int userid, int targetSdk, int uid) {
        ManagedServiceInfo info = newServiceInfo(service, component, userid,
                true /*isSystem*/, null /*connection*/, targetSdk, uid);
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
            unbindService(info.connection, info.component, info.userid);
        }
    }

    private void unbindService(ServiceConnection connection, ComponentName component, int userId) {
        try {
            mContext.unbindService(connection);
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, getCaption() + " " + component + " could not be unbound", e);
        }
        synchronized (mMutex) {
            mServicesBound.remove(Pair.create(component, userId));
        }
    }

    public class ManagedServiceInfo implements IBinder.DeathRecipient {
        public IInterface service;
        public ComponentName component;
        public int userid;
        public boolean isSystem;
        public ServiceConnection connection;
        public int targetSdkVersion;
        public Pair<ComponentName, Integer> mKey;
        public int uid;

        public ManagedServiceInfo(IInterface service, ComponentName component,
                int userid, boolean isSystem, ServiceConnection connection, int targetSdkVersion,
                int uid) {
            this.service = service;
            this.component = component;
            this.userid = userid;
            this.isSystem = isSystem;
            this.connection = connection;
            this.targetSdkVersion = targetSdkVersion;
            this.uid = uid;
            mKey = Pair.create(component, userid);
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

        public void dumpDebug(ProtoOutputStream proto, long fieldId, ManagedServices host) {
            final long token = proto.start(fieldId);
            component.dumpDebug(proto, ManagedServiceInfoProto.COMPONENT);
            proto.write(ManagedServiceInfoProto.USER_ID, userid);
            proto.write(ManagedServiceInfoProto.SERVICE, service.getClass().getName());
            proto.write(ManagedServiceInfoProto.IS_SYSTEM, isSystem);
            proto.write(ManagedServiceInfoProto.IS_GUEST, isGuest(host));
            proto.end(token);
        }

        public boolean isSameUser(int userId) {
            if (!isEnabledForCurrentProfiles()) {
                return false;
            }
            return userId == USER_ALL || userId == this.userid;
        }

        public boolean enabledAndUserMatches(int nid) {
            if (!isEnabledForCurrentProfiles()) {
                return false;
            }
            if (this.userid == USER_ALL) return true;
            if (this.isSystem) return true;
            if (nid == USER_ALL || nid == this.userid) return true;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ManagedServiceInfo that = (ManagedServiceInfo) o;
            return userid == that.userid
                    && isSystem == that.isSystem
                    && targetSdkVersion == that.targetSdkVersion
                    && Objects.equals(service, that.service)
                    && Objects.equals(component, that.component)
                    && Objects.equals(connection, that.connection);
        }

        @Override
        public int hashCode() {
            return Objects.hash(service, component, userid, isSystem, connection, targetSdkVersion);
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

        /**
         * Returns the currently active users (generally one user and its work profile).
         */
        public IntArray getCurrentProfileIds() {
            synchronized (mCurrentProfiles) {
                IntArray users = new IntArray(mCurrentProfiles.size());
                final int N = mCurrentProfiles.size();
                for (int i = 0; i < N; ++i) {
                    users.add(mCurrentProfiles.keyAt(i));
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
