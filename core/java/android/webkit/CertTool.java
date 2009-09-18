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

package android.webkit;

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.jce.netscape.NetscapeCertRequest;
import org.bouncycastle.util.encoders.Base64;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.security.Credentials;
import android.util.Log;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

class CertTool {
    private static final String LOGTAG = "CertTool";

    private static final AlgorithmIdentifier MD5_WITH_RSA =
            new AlgorithmIdentifier(PKCSObjectIdentifiers.md5WithRSAEncryption);

    static final String CERT = Credentials.CERTIFICATE;
    static final String PKCS12 = Credentials.PKCS12;

    static String[] getKeyStrengthList() {
        return new String[] {"High Grade", "Medium Grade"};
    }

    static String getSignedPublicKey(Context context, int index, String challenge) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize((index == 0) ? 2048 : 1024);
            KeyPair pair = generator.genKeyPair();

            NetscapeCertRequest request = new NetscapeCertRequest(challenge,
                    MD5_WITH_RSA, pair.getPublic());
            request.sign(pair.getPrivate());
            byte[] signed = request.toASN1Object().getDEREncoded();

            Credentials.getInstance().install(context, pair);
            return new String(Base64.encode(signed));
        } catch (Exception e) {
            Log.w(LOGTAG, e);
        }
        return null;
    }

    static void addCertificate(Context context, String type, byte[] value) {
        Credentials.getInstance().install(context, type, value);
    }

    private CertTool() {}
}
