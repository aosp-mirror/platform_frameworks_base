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

package android.security;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.security.KeyPair;

/**
 * {@hide}
 */
public class Credentials {
    private static final String LOGTAG = "Credentials";

    public static final String UNLOCK_ACTION = "android.credentials.UNLOCK";

    public static final String INSTALL_ACTION = "android.credentials.INSTALL";

    public static final String SYSTEM_INSTALL_ACTION = "android.credentials.SYSTEM_INSTALL";

    /** Key prefix for CA certificates. */
    public static final String CA_CERTIFICATE = "CACERT_";

    /** Key prefix for user certificates. */
    public static final String USER_CERTIFICATE = "USRCERT_";

    /** Key prefix for user private keys. */
    public static final String USER_PRIVATE_KEY = "USRPKEY_";

    /** Key prefix for VPN. */
    public static final String VPN = "VPN_";

    /** Key prefix for WIFI. */
    public static final String WIFI = "WIFI_";

    /** Data type for public keys. */
    public static final String PUBLIC_KEY = "KEY";

    /** Data type for private keys. */
    public static final String PRIVATE_KEY = "PKEY";

    /** Data type for certificates. */
    public static final String CERTIFICATE = "CERT";

    /** Data type for PKCS12. */
    public static final String PKCS12 = "PKCS12";

    private static Credentials singleton;

    public static Credentials getInstance() {
        if (singleton == null) {
            singleton = new Credentials();
        }
        return singleton;
    }

    public void unlock(Context context) {
        try {
            Intent intent = new Intent(UNLOCK_ACTION);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(LOGTAG, e.toString());
        }
    }

    private Intent createInstallIntent() {
        Intent intent = new Intent(INSTALL_ACTION);
        intent.setClassName("com.android.certinstaller",
                "com.android.certinstaller.CertInstallerMain");
        return intent;
    }

    public void install(Context context, KeyPair pair) {
        try {
            Intent intent = createInstallIntent();
            intent.putExtra(PRIVATE_KEY, pair.getPrivate().getEncoded());
            intent.putExtra(PUBLIC_KEY, pair.getPublic().getEncoded());
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(LOGTAG, e.toString());
        }
    }

    public void install(Context context, String type, byte[] value) {
        try {
            Intent intent = createInstallIntent();
            intent.putExtra(type, value);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(LOGTAG, e.toString());
        }
    }

    public void installFromSdCard(Context context) {
        try {
            context.startActivity(createInstallIntent());
        } catch (ActivityNotFoundException e) {
            Log.w(LOGTAG, e.toString());
        }
    }
}
