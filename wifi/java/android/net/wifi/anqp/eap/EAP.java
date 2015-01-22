package android.net.wifi.anqp.eap;

import java.util.HashMap;
import java.util.Map;

/**
 * EAP Related constants for the ANQP NAIRealm element, IEEE802.11-2012 section 8.4.4.10
 */
public abstract class EAP {

    private static final Map<Integer, EAPMethodID> s_eapIds = new HashMap<Integer, EAPMethodID>();

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
    public static final int EAP_AKAPrim = 50;
    public static final int EAP_GPSK = 51;
    public static final int EAP_PWD = 52;
    public static final int EAP_EKE = 53;
    public static final int EAP_TEAP = 55;

    public enum EAPMethodID {
        EAP_MD5,
        EAP_OTP,
        EAP_RSA,
        EAP_KEA,
        EAP_KEA_VALIDATE,
        EAP_TLS,
        EAP_LEAP,
        EAP_SIM,
        EAP_TTLS,
        EAP_AKA,
        EAP_3Com,
        EAP_MSCHAPv2,
        EAP_PEAP,
        EAP_POTP,
        EAP_ActiontecWireless,
        EAP_HTTPDigest,
        EAP_SPEKE,
        EAP_MOBAC,
        EAP_FAST,
        EAP_ZLXEAP,
        EAP_Link,
        EAP_PAX,
        EAP_PSK,
        EAP_SAKE,
        EAP_IKEv2,
        EAP_AKAPrim,
        EAP_GPSK,
        EAP_PWD,
        EAP_EKE,
        EAP_TEAP
    }

    public static final int ExpandedEAPMethod = 1;
    public static final int NonEAPInnerAuthType = 2;
    public static final int InnerAuthEAPMethodType = 3;
    public static final int ExpandedInnerEAPMethod = 4;
    public static final int CredentialType = 5;
    public static final int TunneledEAPMethodCredType = 6;
    public static final int VendorSpecific = 221;

    public enum AuthInfoID {
        Undefined,
        ExpandedEAPMethod,
        NonEAPInnerAuthType,
        InnerAuthEAPMethodType,
        ExpandedInnerEAPMethod,
        CredentialType,
        TunneledEAPMethodCredType,
        VendorSpecific
    }

    static {
        s_eapIds.put(EAP_MD5, EAPMethodID.EAP_MD5);
        s_eapIds.put(EAP_OTP, EAPMethodID.EAP_OTP);
        s_eapIds.put(EAP_RSA, EAPMethodID.EAP_RSA);
        s_eapIds.put(EAP_KEA, EAPMethodID.EAP_KEA);
        s_eapIds.put(EAP_KEA_VALIDATE, EAPMethodID.EAP_KEA_VALIDATE);
        s_eapIds.put(EAP_TLS, EAPMethodID.EAP_TLS);
        s_eapIds.put(EAP_LEAP, EAPMethodID.EAP_LEAP);
        s_eapIds.put(EAP_SIM, EAPMethodID.EAP_SIM);
        s_eapIds.put(EAP_TTLS, EAPMethodID.EAP_TTLS);
        s_eapIds.put(EAP_AKA, EAPMethodID.EAP_AKA);
        s_eapIds.put(EAP_3Com, EAPMethodID.EAP_3Com);
        s_eapIds.put(EAP_MSCHAPv2, EAPMethodID.EAP_MSCHAPv2);
        s_eapIds.put(EAP_PEAP, EAPMethodID.EAP_PEAP);
        s_eapIds.put(EAP_POTP, EAPMethodID.EAP_POTP);
        s_eapIds.put(EAP_ActiontecWireless, EAPMethodID.EAP_ActiontecWireless);
        s_eapIds.put(EAP_HTTPDigest, EAPMethodID.EAP_HTTPDigest);
        s_eapIds.put(EAP_SPEKE, EAPMethodID.EAP_SPEKE);
        s_eapIds.put(EAP_MOBAC, EAPMethodID.EAP_MOBAC);
        s_eapIds.put(EAP_FAST, EAPMethodID.EAP_FAST);
        s_eapIds.put(EAP_ZLXEAP, EAPMethodID.EAP_ZLXEAP);
        s_eapIds.put(EAP_Link, EAPMethodID.EAP_Link);
        s_eapIds.put(EAP_PAX, EAPMethodID.EAP_PAX);
        s_eapIds.put(EAP_PSK, EAPMethodID.EAP_PSK);
        s_eapIds.put(EAP_SAKE, EAPMethodID.EAP_SAKE);
        s_eapIds.put(EAP_IKEv2, EAPMethodID.EAP_IKEv2);
        s_eapIds.put(EAP_AKAPrim, EAPMethodID.EAP_AKAPrim);
        s_eapIds.put(EAP_GPSK, EAPMethodID.EAP_GPSK);
        s_eapIds.put(EAP_PWD, EAPMethodID.EAP_PWD);
        s_eapIds.put(EAP_EKE, EAPMethodID.EAP_EKE);
        s_eapIds.put(EAP_TEAP, EAPMethodID.EAP_TEAP);
    }

    public static EAPMethodID mapEAPMethod(int methodID) {
        return s_eapIds.get(methodID);
    }
}
