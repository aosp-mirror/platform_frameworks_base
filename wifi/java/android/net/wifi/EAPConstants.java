/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi;

/**
 * Utility class containing EAP (Extensible Authentication Protocol) Related constants.
 *
 * @hide
 */
public final class EAPConstants {
    // Constant definition for EAP types. Refer to
    // http://www.iana.org/assignments/eap-numbers/eap-numbers.xhtml for more info.
    public static final int EAP_MD5 = 4;
    public static final int EAP_OTP = 5;
    public static final int EAP_RSA = 9;
    public static final int EAP_KEA = 11;
    public static final int EAP_KEA_VALIDATE = 12;
    public static final int EAP_TLS = 13;
    public static final int EAP_LEAP = 17;
    public static final int EAP_SIM = 18;
    public static final int EAP_TTLS = 21;
    public static final int EAP_AKA = 23;
    public static final int EAP_3Com = 24;
    public static final int EAP_MSCHAPv2 = 26;
    public static final int EAP_PEAP = 29;
    public static final int EAP_POTP = 32;
    public static final int EAP_ActiontecWireless = 35;
    public static final int EAP_HTTPDigest = 38;
    public static final int EAP_SPEKE = 41;
    public static final int EAP_MOBAC = 42;
    public static final int EAP_FAST = 43;
    public static final int EAP_ZLXEAP = 44;
    public static final int EAP_Link = 45;
    public static final int EAP_PAX = 46;
    public static final int EAP_PSK = 47;
    public static final int EAP_SAKE = 48;
    public static final int EAP_IKEv2 = 49;
    public static final int EAP_AKA_PRIME = 50;
    public static final int EAP_GPSK = 51;
    public static final int EAP_PWD = 52;
    public static final int EAP_EKE = 53;
    public static final int EAP_TEAP = 55;
}
