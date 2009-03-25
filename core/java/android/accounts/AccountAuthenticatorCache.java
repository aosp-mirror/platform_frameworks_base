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

package android.accounts;

import android.content.*;
import android.content.res.XmlResourceParser;
import android.content.res.TypedArray;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;
import android.util.AttributeSet;
import android.util.Xml;

import java.util.*;
import java.io.IOException;

import com.google.android.collect.Maps;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

/**
 * A cache of services that export the {@link IAccountAuthenticator} interface. This cache
 * is built by interrogating the {@link PackageManager} and is updated as packages are added,
 * removed and changed. The authenticators are referred to by their account type and
 * are made available via the {@link #getAuthenticatorInfo(String type)} method.
 */
public class AccountAuthenticatorCache {
    private static final String TAG = "Account";

    private static final String SERVICE_INTERFACE = "android.accounts.AccountAuthenticator";
    private static final String SERVICE_META_DATA = "android.accounts.AccountAuthenticator";

    private volatile Map<String, AuthenticatorInfo> mAuthenticators;

    private final Context mContext;
    private BroadcastReceiver mReceiver;

    public AccountAuthenticatorCache(Context context) {
        mContext = context;
        mReceiver = new BroadcastReceiver() {
            public void onReceive(Context context, Intent intent) {
                buildAuthenticatorList();
            }
        };
    }

    private void monitorPackageChanges() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    /**
     * Value type that describes an AccountAuthenticator. The information within can be used
     * to bind to its {@link IAccountAuthenticator} interface.
     */
    public class AuthenticatorInfo {
        public final String mType;
        public final String mComponentShortName;
        public final ComponentName mComponentName;

        private AuthenticatorInfo(String type, ComponentName componentName) {
            mType = type;
            mComponentName = componentName;
            mComponentShortName = componentName.flattenToShortString();
        }
    }

    /**
     * Accessor for the registered authenticators.
     * @param type the account type of the authenticator
     * @return the AuthenticatorInfo that matches the account type or null if none is present
     */
    public AuthenticatorInfo getAuthenticatorInfo(String type) {
        if (mAuthenticators == null) {
            monitorPackageChanges();
            buildAuthenticatorList();
        }
        return mAuthenticators.get(type);
    }

    /**
     * @return a collection of {@link AuthenticatorInfo} objects for all
     * registered authenticators.
     */
    public Collection<AuthenticatorInfo> getAllAuthenticators() {
        if (mAuthenticators == null) {
            monitorPackageChanges();
            buildAuthenticatorList();
        }
        return Collections.unmodifiableCollection(mAuthenticators.values());
    }

    /**
     * Stops the monitoring of package additions, removals and changes.
     */
    public void close() {
        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    protected void finalize() throws Throwable {
        if (mReceiver != null) {
            Log.e(TAG, "AccountAuthenticatorCache finalized without being closed");
        }
        close();
        super.finalize();
    }

    private void buildAuthenticatorList() {
        Map<String, AuthenticatorInfo> authenticators = Maps.newHashMap();
        PackageManager pm = mContext.getPackageManager();

        List<ResolveInfo> services =
                pm.queryIntentServices(new Intent(SERVICE_INTERFACE), PackageManager.GET_META_DATA);

        for (ResolveInfo resolveInfo : services) {
            try {
                AuthenticatorInfo info = parseAuthenticatorInfo(resolveInfo);
                if (info != null) {
                    authenticators.put(info.mType, info);
                } else {
                    Log.w(TAG, "Unable to load input method " + resolveInfo.toString());
                }
            } catch (XmlPullParserException e) {
                Log.w(TAG, "Unable to load input method " + resolveInfo.toString(), e);
            } catch (IOException e) {
                Log.w(TAG, "Unable to load input method " + resolveInfo.toString(), e);
            }
        }

        mAuthenticators = authenticators;
    }

    public AuthenticatorInfo parseAuthenticatorInfo(ResolveInfo service)
            throws XmlPullParserException, IOException {
        ServiceInfo si = service.serviceInfo;
        ComponentName componentName = new ComponentName(si.packageName, si.name);

        PackageManager pm = mContext.getPackageManager();
        String authenticatorType = null;

        XmlResourceParser parser = null;
        try {
            parser = si.loadXmlMetaData(pm, SERVICE_META_DATA);
            if (parser == null) {
                throw new XmlPullParserException("No " + SERVICE_META_DATA + " meta-data");
            }

            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!"account-authenticator".equals(nodeName)) {
                throw new XmlPullParserException(
                        "Meta-data does not start with account-authenticator tag");
            }

            TypedArray sa = mContext.getResources().obtainAttributes(attrs,
                    com.android.internal.R.styleable.AccountAuthenticator);
            authenticatorType = sa.getString(
                    com.android.internal.R.styleable.AccountAuthenticator_accountType);
            sa.recycle();
        } finally {
            if (parser != null) parser.close();
        }

        if (authenticatorType == null) {
            return null;
        }

        return new AuthenticatorInfo(authenticatorType, componentName);
    }
}
