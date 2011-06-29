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
import com.android.org.bouncycastle.openssl.PEMReader;
import com.android.org.bouncycastle.openssl.PEMWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charsets;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * {@hide}
 */
public class Credentials {
    private static final String LOGTAG = "Credentials";

    public static final String INSTALL_ACTION = "android.credentials.INSTALL";

    public static final String UNLOCK_ACTION = "com.android.credentials.UNLOCK";

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
    public static final String EXTRA_PUBLIC_KEY = "KEY";

    /** Data type for private keys. */
    public static final String EXTRA_PRIVATE_KEY = "PKEY";

    // historically used by Android
    public static final String EXTENSION_CRT = ".crt";
    public static final String EXTENSION_P12 = ".p12";
    // commonly used on Windows
    public static final String EXTENSION_CER = ".cer";
    public static final String EXTENSION_PFX = ".pfx";

    /**
     * Convert objects to a PEM format, which is used for
     * CA_CERTIFICATE, USER_CERTIFICATE, and USER_PRIVATE_KEY
     * entries.
     */
    public static byte[] convertToPem(Object... objects) throws IOException {
        ByteArrayOutputStream bao = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(bao, Charsets.US_ASCII);
        PEMWriter pw = new PEMWriter(writer);
        for (Object o : objects) {
            pw.writeObject(o);
        }
        pw.close();
        return bao.toByteArray();
    }
    /**
     * Convert objects from PEM format, which is used for
     * CA_CERTIFICATE, USER_CERTIFICATE, and USER_PRIVATE_KEY
     * entries.
     */
    public static List<Object> convertFromPem(byte[] bytes) throws IOException {
        ByteArrayInputStream bai = new ByteArrayInputStream(bytes);
        Reader reader = new InputStreamReader(bai, Charsets.US_ASCII);
        PEMReader pr = new PEMReader(reader);

        List<Object> result = new ArrayList<Object>();
        Object o;
        while ((o = pr.readObject()) != null) {
            result.add(o);
        }
        pr.close();
        return result;
    }

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

    public void install(Context context) {
        try {
            Intent intent = KeyChain.createInstallIntent();
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(LOGTAG, e.toString());
        }
    }

    public void install(Context context, KeyPair pair) {
        try {
            Intent intent = KeyChain.createInstallIntent();
            intent.putExtra(EXTRA_PRIVATE_KEY, pair.getPrivate().getEncoded());
            intent.putExtra(EXTRA_PUBLIC_KEY, pair.getPublic().getEncoded());
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(LOGTAG, e.toString());
        }
    }

    public void install(Context context, String type, byte[] value) {
        try {
            Intent intent = KeyChain.createInstallIntent();
            intent.putExtra(type, value);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(LOGTAG, e.toString());
        }
    }
}
