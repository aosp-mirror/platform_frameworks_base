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
import android.content.pm.RegisteredServicesCache;
import android.content.res.TypedArray;
import android.content.Context;
import android.util.AttributeSet;

/**
 * A cache of services that export the {@link IAccountAuthenticator} interface. This cache
 * is built by interrogating the {@link PackageManager} and is updated as packages are added,
 * removed and changed. The authenticators are referred to by their account type and
 * are made available via the {@link RegisteredServicesCache#getServiceInfo} method.
 * @hide
 */
/* package private */ class AccountAuthenticatorCache
        extends RegisteredServicesCache<AuthenticatorDescription> {
    private static final String TAG = "Account";

    private static final String SERVICE_INTERFACE = "android.accounts.AccountAuthenticator";
    private static final String SERVICE_META_DATA = "android.accounts.AccountAuthenticator";
    private static final String ATTRIBUTES_NAME = "account-authenticator";

    public AccountAuthenticatorCache(Context context) {
        super(context, SERVICE_INTERFACE, SERVICE_META_DATA, ATTRIBUTES_NAME);
    }

    public AuthenticatorDescription parseServiceAttributes(String packageName, AttributeSet attrs) {
        TypedArray sa = mContext.getResources().obtainAttributes(attrs,
                com.android.internal.R.styleable.AccountAuthenticator);
        try {
            final String accountType =
                    sa.getString(com.android.internal.R.styleable.AccountAuthenticator_accountType);
            final int labelId = sa.getResourceId(
                    com.android.internal.R.styleable.AccountAuthenticator_label, 0);
            final int iconId = sa.getResourceId(
                    com.android.internal.R.styleable.AccountAuthenticator_icon, 0);
            return new AuthenticatorDescription(accountType, packageName, labelId, iconId);
        } finally {
            sa.recycle();
        }
    }
}
