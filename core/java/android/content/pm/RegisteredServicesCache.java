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
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastXmlSerializer;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
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

    public final Context mContext;
    private final String mInterfaceName;
    private final String mMetaDataName;
    private final String mAttributesName;
    private final XmlSerializerAndParser<V> mSerializerAndParser;

    private final Object mServicesLock = new Object();

    @GuardedBy("mServicesLock")
    private boolean mPersistentServicesFileDidNotExist;
    @GuardedBy("mServicesLock")
    private final SparseArray<UserServices<V>> mUserServices = new SparseArray<UserServices<V>>(2);

    private static class UserServices<V> {
        @GuardedBy("mServicesLock")
        public final Map<V, Integer> persistentServices = Maps.newHashMap();
        @GuardedBy("mServicesLock")
        public Map<V, ServiceInfo<V>> services = null;
    }

    private UserServices<V> findOrCreateUserLocked(int userId) {
        UserServices<V> services = mUserServices.get(userId);
        if (services == null) {
            services = new UserServices<V>();
            mUserServices.put(userId, services);
        }
        return services;
    }

    /**
     * This file contains the list of known services. We would like to maintain this forever
     * so we store it as an XML file.
     */
    private final AtomicFile mPersistentServicesFile;

    // the listener and handler are synchronized on "this" and must be updated together
    private RegisteredServicesCacheListener<V> mListener;
    private Handler mHandler;

    public RegisteredServicesCache(Context context, String interfaceName, String metaDataName,
            String attributeName, XmlSerializerAndParser<V> serializerAndParser) {
        mContext = context;
        mInterfaceName = interfaceName;
        mMetaDataName = metaDataName;
        mAttributesName = attributeName;
        mSerializerAndParser = serializerAndParser;

        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File syncDir = new File(systemDir, "registered_services");
        mPersistentServicesFile = new AtomicFile(new File(syncDir, interfaceName + ".xml"));

        // Load persisted services from disk
        readPersistentServicesLocked();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(mPackageReceiver, UserHandle.ALL, intentFilter, null, null);

        // Register for events related to sdcard installation.
        IntentFilter sdFilter = new IntentFilter();
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        sdFilter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        mContext.registerReceiver(mExternalReceiver, sdFilter);
    }

    private final void handlePackageEvent(Intent intent, int userId) {
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
            // either we're adding/changing, or it's a removal without replacement, so
            // we need to recalculate the set of available services
            generateServicesMap(userId);
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
            handlePackageEvent(intent, UserHandle.USER_OWNER);
        }
    };

    public void invalidateCache(int userId) {
        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            user.services = null;
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
            handler = new Handler(mContext.getMainLooper());
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
        handler.post(new Runnable() {
            public void run() {
                listener2.onServiceChanged(type, userId, removed);
            }
        });
    }

    /**
     * Value type that describes a Service. The information within can be used
     * to bind to the service.
     */
    public static class ServiceInfo<V> {
        public final V type;
        public final ComponentName componentName;
        public final int uid;

        /** @hide */
        public ServiceInfo(V type, ComponentName componentName, int uid) {
            this.type = type;
            this.componentName = componentName;
            this.uid = uid;
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
                generateServicesMap(userId);
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
                generateServicesMap(userId);
            }
            return Collections.unmodifiableCollection(
                    new ArrayList<ServiceInfo<V>>(user.services.values()));
        }
    }

    private boolean inSystemImage(int callerUid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(callerUid);
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
        return false;
    }

    /**
     * Populate {@link UserServices#services} by scanning installed packages for
     * given {@link UserHandle}.
     */
    private void generateServicesMap(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "generateServicesMap() for " + userId);
        }

        final PackageManager pm = mContext.getPackageManager();
        final ArrayList<ServiceInfo<V>> serviceInfos = new ArrayList<ServiceInfo<V>>();
        final List<ResolveInfo> resolveInfos = pm.queryIntentServicesAsUser(
                new Intent(mInterfaceName), PackageManager.GET_META_DATA, userId);
        for (ResolveInfo resolveInfo : resolveInfos) {
            try {
                ServiceInfo<V> info = parseServiceInfo(resolveInfo);
                if (info == null) {
                    Log.w(TAG, "Unable to load service info " + resolveInfo.toString());
                    continue;
                }
                serviceInfos.add(info);
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Unable to load service info " + resolveInfo.toString(), e);
            } catch (IOException e) {
                Log.w(TAG, "Unable to load service info " + resolveInfo.toString(), e);
            }
        }

        synchronized (mServicesLock) {
            final UserServices<V> user = findOrCreateUserLocked(userId);
            final boolean firstScan = user.services == null;
            if (firstScan) {
                user.services = Maps.newHashMap();
            } else {
                user.services.clear();
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
                    if (!(mPersistentServicesFileDidNotExist && firstScan)) {
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
                if (!containsType(serviceInfos, v1)) {
                    toBeRemoved.add(v1);
                }
            }
            for (V v1 : toBeRemoved) {
                if (DEBUG) {
                    changes.append("  Service removed: ").append(v1).append("\n");
                }
                changed = true;
                user.persistentServices.remove(v1);
                notifyListener(v1, userId, true /* removed */);
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
                writePersistentServicesLocked();
            }
        }
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

    private ServiceInfo<V> parseServiceInfo(ResolveInfo service)
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
            final ApplicationInfo applicationInfo = serviceInfo.applicationInfo;
            final int uid = applicationInfo.uid;
            return new ServiceInfo<V>(v, componentName, uid);
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
    private void readPersistentServicesLocked() {
        mUserServices.clear();
        if (mSerializerAndParser == null) {
            return;
        }
        FileInputStream fis = null;
        try {
            mPersistentServicesFileDidNotExist = !mPersistentServicesFile.getBaseFile().exists();
            if (mPersistentServicesFileDidNotExist) {
                return;
            }
            fis = mPersistentServicesFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
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
                            String uidString = parser.getAttributeValue(null, "uid");
                            final int uid = Integer.parseInt(uidString);
                            final int userId = UserHandle.getUserId(uid);
                            final UserServices<V> user = findOrCreateUserLocked(userId);
                            user.persistentServices.put(service, uid);
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading persistent services, starting from scratch", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {
                }
            }
        }
    }

    /**
     * Write all sync status to the sync status file.
     */
    private void writePersistentServicesLocked() {
        if (mSerializerAndParser == null) {
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = mPersistentServicesFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            out.startTag(null, "services");
            for (int i = 0; i < mUserServices.size(); i++) {
                final UserServices<V> user = mUserServices.valueAt(i);
                for (Map.Entry<V, Integer> service : user.persistentServices.entrySet()) {
                    out.startTag(null, "service");
                    out.attribute(null, "uid", Integer.toString(service.getValue()));
                    mSerializerAndParser.writeAsXml(service.getKey(), out);
                    out.endTag(null, "service");
                }
            }
            out.endTag(null, "services");
            out.endDocument();
            mPersistentServicesFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing accounts", e1);
            if (fos != null) {
                mPersistentServicesFile.failWrite(fos);
            }
        }
    }

    public abstract V parseServiceAttributes(Resources res,
            String packageName, AttributeSet attrs);
}
