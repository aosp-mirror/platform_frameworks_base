/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.security.keystore2;

import android.security.keymaster.KeymasterDefs;

import java.security.AlgorithmParameters;
import java.security.NoSuchAlgorithmException;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.InvalidParameterSpecException;

/**
 * @hide
 */
public final class KeymasterUtils {

    private KeymasterUtils() {}

    /** @hide */
    static int getDigestOutputSizeBits(int keymasterDigest) {
        switch (keymasterDigest) {
            case KeymasterDefs.KM_DIGEST_NONE:
                return -1;
            case KeymasterDefs.KM_DIGEST_MD5:
                return 128;
            case KeymasterDefs.KM_DIGEST_SHA1:
                return 160;
            case KeymasterDefs.KM_DIGEST_SHA_2_224:
                return 224;
            case KeymasterDefs.KM_DIGEST_SHA_2_256:
                return 256;
            case KeymasterDefs.KM_DIGEST_SHA_2_384:
                return 384;
            case KeymasterDefs.KM_DIGEST_SHA_2_512:
                return 512;
            default:
                throw new IllegalArgumentException("Unknown digest: " + keymasterDigest);
        }
    }

    /** @hide */
    static boolean isKeymasterBlockModeIndCpaCompatibleWithSymmetricCrypto(
            int keymasterBlockMode) {
        switch (keymasterBlockMode) {
            case KeymasterDefs.KM_MODE_ECB:
                return false;
            case KeymasterDefs.KM_MODE_CBC:
            case KeymasterDefs.KM_MODE_CTR:
            case KeymasterDefs.KM_MODE_GCM:
                return true;
            default:
                throw new IllegalArgumentException("Unsupported block mode: " + keymasterBlockMode);
        }
    }

    /** @hide */
    static boolean isKeymasterPaddingSchemeIndCpaCompatibleWithAsymmetricCrypto(
            int keymasterPadding) {
        switch (keymasterPadding) {
            case KeymasterDefs.KM_PAD_NONE:
                return false;
            case KeymasterDefs.KM_PAD_RSA_OAEP:
            case KeymasterDefs.KM_PAD_RSA_PKCS1_1_5_ENCRYPT:
                return true;
            default:
                throw new IllegalArgumentException(
                        "Unsupported asymmetric encryption padding scheme: " + keymasterPadding);
        }
    }

    static String getEcCurveFromKeymaster(int ecCurve) {
        switch (ecCurve) {
            case android.hardware.security.keymint.EcCurve.P_224:
                return "secp224r1";
            case android.hardware.security.keymint.EcCurve.P_256:
                return "secp256r1";
            case android.hardware.security.keymint.EcCurve.P_384:
                return "secp384r1";
            case android.hardware.security.keymint.EcCurve.P_521:
                return "secp521r1";
        }
        return "";
    }

    static int getKeymasterEcCurve(String ecCurveName) {
        if (ecCurveName.equals("secp224r1")) {
            return android.hardware.security.keymint.EcCurve.P_224;
        } else if (ecCurveName.equals("secp256r1")) {
            return android.hardware.security.keymint.EcCurve.P_256;
        } else if (ecCurveName.equals("secp384r1")) {
            return android.hardware.security.keymint.EcCurve.P_384;
        } else if (ecCurveName.equals("secp521r1")) {
            return android.hardware.security.keymint.EcCurve.P_521;
        }
        return -1;
    }

    static ECParameterSpec getCurveSpec(String name)
            throws NoSuchAlgorithmException, InvalidParameterSpecException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(name));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    static String getCurveName(ECParameterSpec spec) {
        if (KeymasterUtils.isECParameterSpecOfCurve(spec, "secp224r1")) {
            return "secp224r1";
        } else if (KeymasterUtils.isECParameterSpecOfCurve(spec, "secp256r1")) {
            return "secp256r1";
        } else if (KeymasterUtils.isECParameterSpecOfCurve(spec, "secp384r1")) {
            return "secp384r1";
        } else if (KeymasterUtils.isECParameterSpecOfCurve(spec, "secp521r1")) {
            return "secp521r1";
        }
        return null;
    }

    private static boolean isECParameterSpecOfCurve(ECParameterSpec spec, String curveName) {
        try {
            ECParameterSpec curveSpec = KeymasterUtils.getCurveSpec(curveName);
            if (curveSpec.getCurve().equals(spec.getCurve())
                    && curveSpec.getOrder().equals(spec.getOrder())
                    && curveSpec.getGenerator().equals(spec.getGenerator())) {
                return true;
            }
        } catch (NoSuchAlgorithmException | InvalidParameterSpecException e) {
            return false;
        }
        return false;
    }
}
