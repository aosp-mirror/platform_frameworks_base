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

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.res.XmlResourceParser;
import android.util.Log;
import android.util.AttributeSet;
import android.util.Xml;

import java.util.Map;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.IOException;

import com.google.android.collect.Maps;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

/**
 * A cache of registered services. This cache
 * is built by interrogating the {@link PackageManager} and is updated as packages are added,
 * removed and changed. The services are referred to by type V and
 * are made available via the {@link #getServiceInfo} method.
 * @hide
 */
public abstract class RegisteredServicesCache<V> {
    private static final String TAG = "PackageManager";

    public final Context mContext;
    private final String mInterfaceName;
    private final String mMetaDataName;
    private final String mAttributesName;

    // no need to be synchronized since the map is never changed once mService is written
    volatile Map<V, ServiceInfo<V>> mServices;

    // synchronized on "this"
    private BroadcastReceiver mReceiver = null;

    public RegisteredServicesCache(Context context, String interfaceName, String metaDataName,
            String attributeName) {
        mContext = context;
        mInterfaceName = interfaceName;
        mMetaDataName = metaDataName;
        mAttributesName = attributeName;
    }

    public void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        getAllServices();
        Map<V, ServiceInfo<V>> services = mServices;
        fout.println("RegisteredServicesCache: " + services.size() + " services");
        for (ServiceInfo info : services.values()) {
            fout.println("  " + info);
        }
    }

    private boolean maybeRegisterForPackageChanges() {
        synchronized (this) {
            if (mReceiver == null) {
                synchronized (this) {
                    mReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            mServices = generateServicesMap();
                        }
                    };
                }

                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
                intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                intentFilter.addDataScheme("package");
                mContext.registerReceiver(mReceiver, intentFilter);
                return true;
            }
            return false;
        }
    }

    private void maybeUnregisterForPackageChanges() {
        synchronized (this) {
            if (mReceiver != null) {
                mContext.unregisterReceiver(mReceiver);
                mReceiver = null;
            }
        }
    }

    /**
     * Value type that describes a Service. The information within can be used
     * to bind to the service.
     */
    public static class ServiceInfo<V> {
        public final V type;
        public final ComponentName componentName;
        public final int uid;

        private ServiceInfo(V type, ComponentName componentName, int uid) {
            this.type = type;
            this.componentName = componentName;
            this.uid = uid;
        }

        @Override
        public String toString() {
            return "ServiceInfo: " + type + ", " + componentName;
        }
    }

    /**
     * Accessor for the registered authenticators.
     * @param type the account type of the authenticator
     * @return the AuthenticatorInfo that matches the account type or null if none is present
     */
    public ServiceInfo<V> getServiceInfo(V type) {
        if (mServices == null) {
            maybeRegisterForPackageChanges();
            mServices = generateServicesMap();
        }
        return mServices.get(type);
    }

    /**
     * @return a collection of {@link RegisteredServicesCache.ServiceInfo} objects for all
     * registered authenticators.
     */
    public Collection<ServiceInfo<V>> getAllServices() {
        if (mServices == null) {
            maybeRegisterForPackageChanges();
            mServices = generateServicesMap();
        }
        return Collections.unmodifiableCollection(mServices.values());
    }

    /**
     * Stops the monitoring of package additions, removals and changes.
     */
    public void close() {
        maybeUnregisterForPackageChanges();
    }

    @Override
    protected void finalize() throws Throwable {
        synchronized (this) {
            if (mReceiver != null) {
                Log.e(TAG, "RegisteredServicesCache finalized without being closed");
            }
        }
        close();
        super.finalize();
    }

    Map<V, ServiceInfo<V>> generateServicesMap() {
        Map<V, ServiceInfo<V>> services = Maps.newHashMap();
        PackageManager pm = mContext.getPackageManager();

        List<ResolveInfo> resolveInfos =
                pm.queryIntentServices(new Intent(mInterfaceName), PackageManager.GET_META_DATA);

        for (ResolveInfo resolveInfo : resolveInfos) {
            try {
                ServiceInfo<V> info = parseServiceInfo(resolveInfo);
                if (info != null) {
                    services.put(info.type, info);
                } else {
                    Log.w(TAG, "Unable to load input method " + resolveInfo.toString());
                }
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Unable to load input method " + resolveInfo.toString(), e);
            } catch (IOException e) {
                Log.w(TAG, "Unable to load input method " + resolveInfo.toString(), e);
            }
        }

        return services;
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

            V v = parseServiceAttributes(si.packageName, attrs);
            if (v == null) {
                return null;
            }
            final android.content.pm.ServiceInfo serviceInfo = service.serviceInfo;
            final ApplicationInfo applicationInfo = serviceInfo.applicationInfo;
            final int uid = applicationInfo.uid;
            return new ServiceInfo<V>(v, componentName, uid);
        } finally {
            if (parser != null) parser.close();
        }
    }

    public abstract V parseServiceAttributes(String packageName, AttributeSet attrs);
}
