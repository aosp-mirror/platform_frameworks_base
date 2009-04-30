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
 * A cache of services that export the {@link android.content.ISyncAdapter} interface.
 * @hide
 */
/* package private */ class SyncAdaptersCache extends RegisteredServicesCache<SyncAdapterType> {
    private static final String TAG = "Account";

    private static final String SERVICE_INTERFACE = "android.content.SyncAdapter";
    private static final String SERVICE_META_DATA = "android.content.SyncAdapter";
    private static final String ATTRIBUTES_NAME = "sync-adapter";

    SyncAdaptersCache(Context context) {
        super(context, SERVICE_INTERFACE, SERVICE_META_DATA, ATTRIBUTES_NAME);
    }

    public SyncAdapterType parseServiceAttributes(AttributeSet attrs) {
        TypedArray sa = mContext.getResources().obtainAttributes(attrs,
                com.android.internal.R.styleable.SyncAdapter);
        try {
            final String authority =
                    sa.getString(com.android.internal.R.styleable.SyncAdapter_contentAuthority);
            final String accountType =
                    sa.getString(com.android.internal.R.styleable.SyncAdapter_accountType);
            if (authority == null || accountType == null) {
                return null;
            }
            return new SyncAdapterType(authority, accountType);
        } finally {
            sa.recycle();
        }
    }
}