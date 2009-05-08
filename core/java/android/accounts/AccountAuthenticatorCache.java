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

import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.RegisteredServicesCache;
import android.content.res.XmlResourceParser;
import android.content.res.TypedArray;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import android.util.AttributeSet;
import android.util.Xml;

import java.io.IOException;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.android.collect.Maps;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParser;

/**
 * A cache of services that export the {@link IAccountAuthenticator} interface. This cache
 * is built by interrogating the {@link PackageManager} and is updated as packages are added,
 * removed and changed. The authenticators are referred to by their account type and
 * are made available via the {@link RegisteredServicesCache#getServiceInfo} method.
 * @hide
 */
/* package private */ class AccountAuthenticatorCache extends RegisteredServicesCache<String> {
    private static final String TAG = "Account";

    private static final String SERVICE_INTERFACE = "android.accounts.AccountAuthenticator";
    private static final String SERVICE_META_DATA = "android.accounts.AccountAuthenticator";
    private static final String ATTRIBUTES_NAME = "account-authenticator";

    public AccountAuthenticatorCache(Context context) {
        super(context, SERVICE_INTERFACE, SERVICE_META_DATA, ATTRIBUTES_NAME);
    }

    public String parseServiceAttributes(AttributeSet attrs) {
        TypedArray sa = mContext.getResources().obtainAttributes(attrs,
                com.android.internal.R.styleable.AccountAuthenticator);
        try {
            return sa.getString(com.android.internal.R.styleable.AccountAuthenticator_accountType);
        } finally {
            sa.recycle();
        }
    }
}
