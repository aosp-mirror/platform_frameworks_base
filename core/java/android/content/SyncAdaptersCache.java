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

package android.content;

import android.content.pm.RegisteredServicesCache;
import android.content.pm.XmlSerializerAndParser;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

/**
 * A cache of services that export the {@link android.content.ISyncAdapter} interface.
 * @hide
 */
public class SyncAdaptersCache extends RegisteredServicesCache<SyncAdapterType> {
    private static final String TAG = "Account";

    private static final String SERVICE_INTERFACE = "android.content.SyncAdapter";
    private static final String SERVICE_META_DATA = "android.content.SyncAdapter";
    private static final String ATTRIBUTES_NAME = "sync-adapter";
    private static final MySerializer sSerializer = new MySerializer();

    @GuardedBy("mServicesLock")
    private SparseArray<ArrayMap<String,String[]>> mAuthorityToSyncAdapters
            = new SparseArray<>();

    public SyncAdaptersCache(Context context) {
        super(context, SERVICE_INTERFACE, SERVICE_META_DATA, ATTRIBUTES_NAME, sSerializer);
    }

    public SyncAdapterType parseServiceAttributes(Resources res,
            String packageName, AttributeSet attrs) {
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.SyncAdapter);
        try {
            final String authority =
                    sa.getString(com.android.internal.R.styleable.SyncAdapter_contentAuthority);
            final String accountType =
                    sa.getString(com.android.internal.R.styleable.SyncAdapter_accountType);
            if (TextUtils.isEmpty(authority) || TextUtils.isEmpty(accountType)) {
                return null;
            }
            final boolean userVisible =
                    sa.getBoolean(com.android.internal.R.styleable.SyncAdapter_userVisible, true);
            final boolean supportsUploading =
                    sa.getBoolean(com.android.internal.R.styleable.SyncAdapter_supportsUploading,
                            true);
            final boolean isAlwaysSyncable =
                    sa.getBoolean(com.android.internal.R.styleable.SyncAdapter_isAlwaysSyncable,
                            false);
            final boolean allowParallelSyncs =
                    sa.getBoolean(com.android.internal.R.styleable.SyncAdapter_allowParallelSyncs,
                            false);
            final String settingsActivity =
                    sa.getString(com.android.internal.R.styleable
                            .SyncAdapter_settingsActivity);
            return new SyncAdapterType(authority, accountType, userVisible, supportsUploading,
                    isAlwaysSyncable, allowParallelSyncs, settingsActivity, packageName);
        } finally {
            sa.recycle();
        }
    }

    @Override
    protected void onServicesChangedLocked(int userId) {
        synchronized (mServicesLock) {
            ArrayMap<String,String[]> adapterMap = mAuthorityToSyncAdapters.get(userId);
            if (adapterMap != null) {
                adapterMap.clear();
            }
        }

        super.onServicesChangedLocked(userId);
    }

    public String[] getSyncAdapterPackagesForAuthority(String authority, int userId) {
        synchronized (mServicesLock) {
            ArrayMap<String,String[]> adapterMap = mAuthorityToSyncAdapters.get(userId);
            if (adapterMap == null) {
                adapterMap = new ArrayMap<>();
                mAuthorityToSyncAdapters.put(userId, adapterMap);
            }
            // If the mapping exists, return it
            if (adapterMap.containsKey(authority)) {
                return adapterMap.get(authority);
            }
            // Create the mapping and cache it
            String[] syncAdapterPackages;
            final Collection<RegisteredServicesCache.ServiceInfo<SyncAdapterType>> serviceInfos;
            serviceInfos = getAllServices(userId);
            ArrayList<String> packages = new ArrayList<>();
            for (RegisteredServicesCache.ServiceInfo<SyncAdapterType> serviceInfo : serviceInfos) {
                if (authority.equals(serviceInfo.type.authority)
                        && serviceInfo.componentName != null) {
                    packages.add(serviceInfo.componentName.getPackageName());
                }
            }
            syncAdapterPackages = new String[packages.size()];
            packages.toArray(syncAdapterPackages);
            adapterMap.put(authority, syncAdapterPackages);

            return syncAdapterPackages;
        }
    }

    @Override
    protected void onUserRemoved(int userId) {
        synchronized (mServicesLock) {
            mAuthorityToSyncAdapters.remove(userId);
        }

        super.onUserRemoved(userId);
    }

    static class MySerializer implements XmlSerializerAndParser<SyncAdapterType> {
        public void writeAsXml(SyncAdapterType item, XmlSerializer out) throws IOException {
            out.attribute(null, "authority", item.authority);
            out.attribute(null, "accountType", item.accountType);
        }

        public SyncAdapterType createFromXml(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            final String authority = parser.getAttributeValue(null, "authority");
            final String accountType = parser.getAttributeValue(null, "accountType");
            return SyncAdapterType.newKey(authority, accountType);
        }
    }
}
