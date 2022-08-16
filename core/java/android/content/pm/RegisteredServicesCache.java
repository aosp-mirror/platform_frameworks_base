/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content.pm;

import android.Manifest;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Environment;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Cache of registered services. This cache is lazily built by interrogating
 * {@link PackageManager} on a per-user basis. It's updated as packages are
 * added, removed and changed. Users are responsible for calling
 * {@link #invalidateCache(int)} when a user is started, since
 * {@link PackageManager} broadcasts aren't sent for stopped users.
 * <p>
 * The services are referred to by type V and are made available via the
 * {@link #getServiceInfo} method.
 *
 * @hide
 */
public abstract class RegisteredServicesCache<V> {
    private static final String TAG = "PackageManager";
    private static final boolean DEBUG = false;
    protected static final String REGISTERED_SERVICES_DIR = "registered_services";

    public final Context mContext;
    private final String mInterfaceName;
    private final String mMetaDataName;
    private final String mAttributesName;
    private final XmlSerializerAndParser<V> mSerializerAndParser;

    protected final Object mServicesLock = new Object();

    @GuardedBy("mServicesLock")
    private final SparseArray<UserServices<V>> mUserServices = new SparseArray<UserServices<V>>(2);

    private static class UserServices<V> {
        @GuardedBy("mServicesLock")
        final Map<V, Integer> persistentServices = Maps.newHashMap();
        @GuardedBy("mServicesLock")
        Map<V, ServiceInfo<V>> services = null;
        @GuardedBy("mServicesLock")
        boolean mPersistentServicesFileDidNotExist = true;
        @GuardedBy("mServicesLock")
        boolean mBindInstantServiceAllowed = false;
    }

    @GuardedBy("mServicesLock")
    private UserServices<V> findOrCreateUserLocked(int userId) {
        return findOrCreateUserLocked(userId, true);
    }

    @GuardedBy("mServicesLock")
    private UserServices<V> findOrCreateUserLocked(int userId, boolean loadFromFileIfNew) {
        UserServices<V> services = mUserServices.get(userId);
        if (services == null) {
            services = new UserServices<V>();
            mUserServices.put(userId, services);
            if (loadFromFileIfNew && mSerializerAndParser != null) {
                // Check if user exists and try loading data from file
                // clear existing data if there was an error during migration
                UserInfo user = getUser(userId);
                if (user != null) {
                    AtomicFile file = createFileForUser(user.id);
                    if (file.getBaseFile().exists()) {
                        if (DEBUG) {
                            Slog.i(TAG, String.format("Loading u%s data from %s", user.id, file));
                        }
                        InputStream is = null;
                        try {
                            is = file.openRead();
                            readPersistentServicesLocked(is);
                        } catch (Exception e) {
                            Log.w(TAG, "Error reading persistent services for user " + user.id, e);
                        } finally {
                            IoUtils.closeQuietly(is);
                        }
                    }
                }
            }
        }
        return services;
    }

    // the listener and handler are synchronized on "this" and must be updated together
    private RegisteredServicesCacheListener<V> mListener;
    private Handler mHandler;

    @UnsupportedAppUsage
    public RegisteredServicesCache(Context context, String interfaceName, String metaDataName,
            String attributeName, XmlSerializerAndParser<V> serializerAndParser) {
        mContext = context;
        mInterfaceName = interfaceName;
        mMetaDataName = metaDataName;
        mAttributesName = attributeName;
        mSerializerAndParser = serializerAndParser;

        migrateIfNecessaryLocked();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        Handler handler = BackgroundThread.getHandler();
        mContext.registerReceiverAsUser(
                mPackageReceiver, UserHandle.ALL, intentFilter, null, handler);

        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mExternalReceiver, sdFilter, null, handler);

        // Register for user-related events
        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(mUserRemovedReceiver, userFilter, null, handler);
    }

    private void handlePackageEvent(Intent intent, int userId) {
        // Don't regenerate the services map when the package is removed or its
        // ASEC container unmounted as a step in replacement.  The subsequent
        // _ADDED / _AVAILABLE call will regenerate the map in the final state.
        final String action = intent.getAction();
        // it's a new-component action if it isn't some sort of removal
        final boolean isRemoval = Intent.ACTION_PACKAGE_REMOVED.equals(action)
                || Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action);
        // if it's a removal, is it part of an update-in-place step?
        final boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);

        if (isRemoval && replacing) {
            // package is going away, but it's the middle of an upgrade: keep the current
            // state and do nothing here.  This clause is intentionally empty.
        } else {
            int[] uids = null;
            // either we're adding/changing, or it's a removal without replacement, so
            // we need to update the set of available services
            if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)
                    || Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                uids = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
            } else {
                int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                if (uid > 0) {
                    uids = new int[] { uid };
                }
            }
            generateServicesMap(uids, userId);
        }
    }

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            if (uid != -1) {
                handlePackageEvent(intent, UserHandle.getUserId(uid));
            }
        }
    };

    private final BroadcastReceiver mExternalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // External apps can't coexist with multi-user, so scan owner
            handlePackageEvent(intent, UserHandle.USER_SYSTEM);
        }
    };

    private final BroadcastReceiver mUserRemovedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
            if (DEBUG) {
                Slog.d(TAG, "u" + userId + " removed - cleaning up");
            }
            onUserRemoved(userId);
        }
    };

    public void invalidateCache(int userId) {
        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            user.services = null;
            onServicesChangedLocked(userId);
        }
    }

    public void dump(FileDescriptor fd, PrintWriter fout, String[] args, int userId) {
        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            if (user.services != null) {
                fout.println("RegisteredServicesCache: " + user.services.size() + " services");
                for (ServiceInfo<?> info : user.services.values()) {
                    fout.println("  " + info);
                }
            } else {
                fout.println("RegisteredServicesCache: services not loaded");
            }
        }
    }

    public RegisteredServicesCacheListener<V> getListener() {
        synchronized (this) {
            return mListener;
        }
    }

    public void setListener(RegisteredServicesCacheListener<V> listener, Handler handler) {
        if (handler == null) {
            handler = BackgroundThread.getHandler();
        }
        synchronized (this) {
            mHandler = handler;
            mListener = listener;
        }
    }

    private void notifyListener(final V type, final int userId, final boolean removed) {
        if (DEBUG) {
            Log.d(TAG, "notifyListener: " + type + " is " + (removed ? "removed" : "added"));
        }
        RegisteredServicesCacheListener<V> listener;
        Handler handler;
        synchronized (this) {
            listener = mListener;
            handler = mHandler;
        }
        if (listener == null) {
            return;
        }

        final RegisteredServicesCacheListener<V> listener2 = listener;
        handler.post(() -> {
            try {
                listener2.onServiceChanged(type, userId, removed);
            } catch (Throwable th) {
                Slog.wtf(TAG, "Exception from onServiceChanged", th);
            }
        });
    }

    /**
     * Value type that describes a Service. The information within can be used
     * to bind to the service.
     */
    public static class ServiceInfo<V> {
        @UnsupportedAppUsage
        public final V type;
        public final ComponentInfo componentInfo;
        @UnsupportedAppUsage
        public final ComponentName componentName;
        @UnsupportedAppUsage
        public final int uid;

        /** @hide */
        public ServiceInfo(V type, ComponentInfo componentInfo, ComponentName componentName) {
            this.type = type;
            this.componentInfo = componentInfo;
            this.componentName = componentName;
            this.uid = (componentInfo != null) ? componentInfo.applicationInfo.uid : -1;
        }

        @Override
        public String toString() {
            return "ServiceInfo: " + type + ", " + componentName + ", uid " + uid;
        }
    }

    /**
     * Accessor for the registered authenticators.
     * @param type the account type of the authenticator
     * @return the AuthenticatorInfo that matches the account type or null if none is present
     */
    public ServiceInfo<V> getServiceInfo(V type, int userId) {
        synchronized (mServicesLock) {
            // Find user and lazily populate cache
            final UserServices<V> user = findOrCreateUserLocked(userId);
            if (user.services == null) {
                generateServicesMap(null, userId);
            }
            return user.services.get(type);
        }
    }

    /**
     * @return a collection of {@link RegisteredServicesCache.ServiceInfo} objects for all
     * registered authenticators.
     */
    public Collection<ServiceInfo<V>> getAllServices(int userId) {
        synchronized (mServicesLock) {
            // Find user and lazily populate cache
            final UserServices<V> user = findOrCreateUserLocked(userId);
            if (user.services == null) {
                generateServicesMap(null, userId);
            }
            return Collections.unmodifiableCollection(
                    new ArrayList<ServiceInfo<V>>(user.services.values()));
        }
    }

    public void updateServices(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "updateServices u" + userId);
        }
        List<ServiceInfo<V>> allServices;
        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            // If services haven't been initialized yet - no updates required
            if (user.services == null) {
                return;
            }
            allServices = new ArrayList<>(user.services.values());
        }
        IntArray updatedUids = null;
        for (ServiceInfo<V> service : allServices) {
            long versionCode = service.componentInfo.applicationInfo.versionCode;
            String pkg = service.componentInfo.packageName;
            ApplicationInfo newAppInfo = null;
            try {
                newAppInfo = mContext.getPackageManager().getApplicationInfoAsUser(pkg, 0, userId);
            } catch (NameNotFoundException e) {
                // Package uninstalled - treat as null app info
            }
            // If package updated or removed
            if ((newAppInfo == null) || (newAppInfo.versionCode != versionCode)) {
                if (DEBUG) {
                    Slog.d(TAG, "Package " + pkg + " uid=" + service.uid
                            + " updated. New appInfo: " + newAppInfo);
                }
                if (updatedUids == null) {
                    updatedUids = new IntArray();
                }
                updatedUids.add(service.uid);
            }
        }
        if (updatedUids != null && updatedUids.size() > 0) {
            int[] updatedUidsArray = updatedUids.toArray();
            generateServicesMap(updatedUidsArray, userId);
        }
    }

    /**
     * @return whether the binding to service is allowed for instant apps.
     */
    public boolean getBindInstantServiceAllowed(int userId) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_BIND_INSTANT_SERVICE,
                "getBindInstantServiceAllowed");

        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            return user.mBindInstantServiceAllowed;
        }
    }

    /**
     * Set whether the binding to service is allowed or not for instant apps.
     */
    public void setBindInstantServiceAllowed(int userId, boolean allowed) {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_BIND_INSTANT_SERVICE,
                "setBindInstantServiceAllowed");

        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            user.mBindInstantServiceAllowed = allowed;
        }
    }

    @VisibleForTesting
    protected boolean inSystemImage(int callerUid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(callerUid);
        if (packages != null) {
            for (String name : packages) {
                try {
                    PackageInfo packageInfo =
                            mContext.getPackageManager().getPackageInfo(name, 0 /* flags */);
                    if ((packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    return false;
                }
            }
        }
        return false;
    }

    @VisibleForTesting
    protected List<ResolveInfo> queryIntentServices(int userId) {
        final PackageManager pm = mContext.getPackageManager();
        int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            if (user.mBindInstantServiceAllowed) {
                flags |= PackageManager.MATCH_INSTANT;
            }
        }
        return pm.queryIntentServicesAsUser(new Intent(mInterfaceName), flags, userId);
    }

    /**
     * Populate {@link UserServices#services} by scanning installed packages for
     * given {@link UserHandle}.
     * @param changedUids the array of uids that have been affected, as mentioned in the broadcast
     *                    or null to assume that everything is affected.
     * @param userId the user for whom to update the services map.
     */
    private void generateServicesMap(int[] changedUids, int userId) {
        if (DEBUG) {
            Slog.d(TAG, "generateServicesMap() for " + userId + ", changed UIDs = "
                    + Arrays.toString(changedUids));
        }

        final ArrayList<ServiceInfo<V>> serviceInfos = new ArrayList<>();
        final List<ResolveInfo> resolveInfos = queryIntentServices(userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            try {
                ServiceInfo<V> info = parseServiceInfo(resolveInfo);
                if (info == null) {
                    Log.w(TAG, "Unable to load service info " + resolveInfo.toString());
                    continue;
                }
                serviceInfos.add(info);
            } catch (XmlPullParserException | IOException e) {
                Log.w(TAG, "Unable to load service info " + resolveInfo.toString(), e);
            }
        }

        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            final boolean firstScan = user.services == null;
            if (firstScan) {
                user.services = Maps.newHashMap();
            }

            StringBuilder changes = new StringBuilder();
            boolean changed = false;
            for (ServiceInfo<V> info : serviceInfos) {
                // four cases:
                // - doesn't exist yet
                //   - add, notify user that it was added
                // - exists and the UID is the same
                //   - replace, don't notify user
                // - exists, the UID is different, and the new one is not a system package
                //   - ignore
                // - exists, the UID is different, and the new one is a system package
                //   - add, notify user that it was added
                Integer previousUid = user.persistentServices.get(info.type);
                if (previousUid == null) {
                    if (DEBUG) {
                        changes.append("  New service added: ").append(info).append("\n");
                    }
                    changed = true;
                    user.services.put(info.type, info);
                    user.persistentServices.put(info.type, info.uid);
                    if (!(user.mPersistentServicesFileDidNotExist && firstScan)) {
                        notifyListener(info.type, userId, false /* removed */);
                    }
                } else if (previousUid == info.uid) {
                    if (DEBUG) {
                        changes.append("  Existing service (nop): ").append(info).append("\n");
                    }
                    user.services.put(info.type, info);
                } else if (inSystemImage(info.uid)
                        || !containsTypeAndUid(serviceInfos, info.type, previousUid)) {
                    if (DEBUG) {
                        if (inSystemImage(info.uid)) {
                            changes.append("  System service replacing existing: ").append(info)
                                    .append("\n");
                        } else {
                            changes.append("  Existing service replacing a removed service: ")
                                    .append(info).append("\n");
                        }
                    }
                    changed = true;
                    user.services.put(info.type, info);
                    user.persistentServices.put(info.type, info.uid);
                    notifyListener(info.type, userId, false /* removed */);
                } else {
                    // ignore
                    if (DEBUG) {
                        changes.append("  Existing service with new uid ignored: ").append(info)
                                .append("\n");
                    }
                }
            }

            ArrayList<V> toBeRemoved = Lists.newArrayList();
            for (V v1 : user.persistentServices.keySet()) {
                // Remove a persisted service that's not in the currently available services list.
                // And only if it is in the list of changedUids.
                if (!containsType(serviceInfos, v1)
                        && containsUid(changedUids, user.persistentServices.get(v1))) {
                    toBeRemoved.add(v1);
                }
            }
            for (V v1 : toBeRemoved) {
                if (DEBUG) {
                    changes.append("  Service removed: ").append(v1).append("\n");
                }
                changed = true;
                user.persistentServices.remove(v1);
                user.services.remove(v1);
                notifyListener(v1, userId, true /* removed */);
            }
            if (DEBUG) {
                Log.d(TAG, "user.services=");
                for (V v : user.services.keySet()) {
                    Log.d(TAG, "  " + v + " " + user.services.get(v));
                }
                Log.d(TAG, "user.persistentServices=");
                for (V v : user.persistentServices.keySet()) {
                    Log.d(TAG, "  " + v + " " + user.persistentServices.get(v));
                }
            }
            if (DEBUG) {
                if (changes.length() > 0) {
                    Log.d(TAG, "generateServicesMap(" + mInterfaceName + "): " +
                            serviceInfos.size() + " services:\n" + changes);
                } else {
                    Log.d(TAG, "generateServicesMap(" + mInterfaceName + "): " +
                            serviceInfos.size() + " services unchanged");
                }
            }
            if (changed) {
                onServicesChangedLocked(userId);
                writePersistentServicesLocked(user, userId);
            }
        }
    }

    protected void onServicesChangedLocked(int userId) {
        // Feel free to override
    }

    /**
     * Returns true if the list of changed uids is null (wildcard) or the specified uid
     * is contained in the list of changed uids.
     */
    private boolean containsUid(int[] changedUids, int uid) {
        return changedUids == null || ArrayUtils.contains(changedUids, uid);
    }

    private boolean containsType(ArrayList<ServiceInfo<V>> serviceInfos, V type) {
        for (int i = 0, N = serviceInfos.size(); i < N; i++) {
            if (serviceInfos.get(i).type.equals(type)) {
                return true;
            }
        }

        return false;
    }

    private boolean containsTypeAndUid(ArrayList<ServiceInfo<V>> serviceInfos, V type, int uid) {
        for (int i = 0, N = serviceInfos.size(); i < N; i++) {
            final ServiceInfo<V> serviceInfo = serviceInfos.get(i);
            if (serviceInfo.type.equals(type) && serviceInfo.uid == uid) {
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    protected ServiceInfo<V> parseServiceInfo(ResolveInfo service)
            throws XmlPullParserException, IOException {
        android.content.pm.ServiceInfo si = service.serviceInfo;
        ComponentName componentName = new ComponentName(si.packageName, si.name);

        PackageManager pm = mContext.getPackageManager();

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, mMetaDataName);
            if (parser == null) {
                throw new XmlPullParserException("No " + mMetaDataName + " meta-data");
            }

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!mAttributesName.equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with " + mAttributesName +  " tag");
            }

            V v = parseServiceAttributes(pm.getResourcesForApplication(si.applicationInfo),
                    si.packageName, attrs);
            if (v == null) {
                return null;
            }
            final android.content.pm.ServiceInfo serviceInfo = service.serviceInfo;
            return new ServiceInfo<V>(v, serviceInfo, componentName);
        } catch (NameNotFoundException e) {
            throw new XmlPullParserException(
                    "Unable to load resources for pacakge " + si.packageName);
        } finally {
            if (parser != null) parser.close();
        }
    }

    /**
     * Read all sync status back in to the initial engine state.
     */
    private void readPersistentServicesLocked(InputStream is)
            throws XmlPullParserException, IOException {
        TypedXmlPullParser parser = Xml.resolvePullParser(is);
        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.START_TAG
                && eventType != XmlPullParser.END_DOCUMENT) {
            eventType = parser.next();
        }
        String tagName = parser.getName();
        if ("services".equals(tagName)) {
            eventType = parser.next();
            do {
                if (eventType == XmlPullParser.START_TAG && parser.getDepth() == 2) {
                    tagName = parser.getName();
                    if ("service".equals(tagName)) {
                        V service = mSerializerAndParser.createFromXml(parser);
                        if (service == null) {
                            break;
                        }
                        final int uid = parser.getAttributeInt(null, "uid");
                        final int userId = UserHandle.getUserId(uid);
                        final UserServices<V> user = findOrCreateUserLocked(userId,
                                false /*loadFromFileIfNew*/) ;
                        user.persistentServices.put(service, uid);
                    }
                }
                eventType = parser.next();
            } while (eventType != XmlPullParser.END_DOCUMENT);
        }
    }

    private void migrateIfNecessaryLocked() {
        if (mSerializerAndParser == null) {
            return;
        }
        File systemDir = new File(getDataDirectory(), "system");
        File syncDir = new File(systemDir, REGISTERED_SERVICES_DIR);
        AtomicFile oldFile = new AtomicFile(new File(syncDir, mInterfaceName + ".xml"));
        boolean oldFileExists = oldFile.getBaseFile().exists();

        if (oldFileExists) {
            File marker = new File(syncDir, mInterfaceName + ".xml.migrated");
            // if not migrated, perform the migration and add a marker
            if (!marker.exists()) {
                if (DEBUG) {
                    Slog.i(TAG, "Marker file " + marker + " does not exist - running migration");
                }
                InputStream is = null;
                try {
                    is = oldFile.openRead();
                    mUserServices.clear();
                    readPersistentServicesLocked(is);
                } catch (Exception e) {
                    Log.w(TAG, "Error reading persistent services, starting from scratch", e);
                } finally {
                    IoUtils.closeQuietly(is);
                }
                try {
                    for (UserInfo user : getUsers()) {
                        UserServices<V> userServices = mUserServices.get(user.id);
                        if (userServices != null) {
                            if (DEBUG) {
                                Slog.i(TAG, "Migrating u" + user.id + " services "
                                        + userServices.persistentServices);
                            }
                            writePersistentServicesLocked(userServices, user.id);
                        }
                    }
                    marker.createNewFile();
                } catch (Exception e) {
                    Log.w(TAG, "Migration failed", e);
                }
                // Migration is complete and we don't need to keep data for all users anymore,
                // It will be loaded from a new location when requested
                mUserServices.clear();
            }
        }
    }

    /**
     * Writes services of a specified user to the file.
     */
    private void writePersistentServicesLocked(UserServices<V> user, int userId) {
        if (mSerializerAndParser == null) {
            return;
        }
        AtomicFile atomicFile = createFileForUser(userId);
        FileOutputStream fos = null;
        try {
            fos = atomicFile.startWrite();
            TypedXmlSerializer out = Xml.resolveSerializer(fos);
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "services");
            for (Map.Entry<V, Integer> service : user.persistentServices.entrySet()) {
                out.startTag(null, "service");
                out.attributeInt(null, "uid", service.getValue());
                mSerializerAndParser.writeAsXml(service.getKey(), out);
                out.endTag(null, "service");
            }
            out.endTag(null, "services");
            out.endDocument();
            atomicFile.finishWrite(fos);
        } catch (IOException e1) {
            Log.w(TAG, "Error writing accounts", e1);
            if (fos != null) {
                atomicFile.failWrite(fos);
            }
        }
    }

    @VisibleForTesting
    protected void onUserRemoved(int userId) {
        synchronized (mServicesLock) {
            mUserServices.remove(userId);
        }
    }

    @VisibleForTesting
    protected List<UserInfo> getUsers() {
        return UserManager.get(mContext).getAliveUsers();
    }

    @VisibleForTesting
    protected UserInfo getUser(int userId) {
        return UserManager.get(mContext).getUserInfo(userId);
    }

    private AtomicFile createFileForUser(int userId) {
        File userDir = getUserSystemDirectory(userId);
        File userFile = new File(userDir, REGISTERED_SERVICES_DIR + "/" + mInterfaceName + ".xml");
        return new AtomicFile(userFile);
    }

    @VisibleForTesting
    protected File getUserSystemDirectory(int userId) {
        return Environment.getUserSystemDirectory(userId);
    }

    @VisibleForTesting
    protected File getDataDirectory() {
        return Environment.getDataDirectory();
    }

    @VisibleForTesting
    protected Map<V, Integer> getPersistentServices(int userId) {
        return findOrCreateUserLocked(userId).persistentServices;
    }

    public abstract V parseServiceAttributes(Resources res,
            String packageName, AttributeSet attrs);
}
