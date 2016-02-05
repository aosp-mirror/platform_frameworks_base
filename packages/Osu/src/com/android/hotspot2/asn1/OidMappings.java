package com.android.hotspot2.asn1;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class OidMappings {
    public static class SigEntry {
        private final String mSigAlgo;
        private final Asn1Oid mKeyAlgo;

        private SigEntry(String sigAlgo, Asn1Oid keyAlgo) {
            mSigAlgo = sigAlgo;
            mKeyAlgo = keyAlgo;
        }

        public String getSigAlgo() {
            return mSigAlgo;
        }

        public Asn1Oid getKeyAlgo() {
            return mKeyAlgo;
        }
    }

    public static final String IdPeLogotype = "1.3.6.1.5.5.7.1.12";
    public static final String IdCeSubjectAltName = "2.5.29.17";

    private static final Map<Asn1Oid, String> sCryptoMapping = new HashMap<>();
    private static final Map<Asn1Oid, String> sNameMapping = new HashMap<>();
    private static final Set<Asn1Oid> sIDMapping = new HashSet<>();
    private static final Map<Asn1Oid, SigEntry> sSigAlgos = new HashMap<>();

    // DSA
    private static final Asn1Oid sAlgo_DSA = new Asn1Oid(1L, 2L, 840L, 10040L, 4L, 1L);
    private static final Asn1Oid sAlgo_SHA1withDSA = new Asn1Oid(1L, 2L, 840L, 10040L, 4L, 3L);

    // RSA
    public static final Asn1Oid sAlgo_RSA = new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 1L);
    private static final Asn1Oid sAlgo_MD2withRSA = new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 2L);
    private static final Asn1Oid sAlgo_MD5withRSA = new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 4L);
    private static final Asn1Oid sAlgo_SHA1withRSA = new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 5L);
    private static final Asn1Oid sAlgo_SHA224withRSA =
            new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 14L);   // n/a
    private static final Asn1Oid sAlgo_SHA256withRSA =
            new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 11L);
    private static final Asn1Oid sAlgo_SHA384withRSA =
            new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 12L);
    private static final Asn1Oid sAlgo_SHA512withRSA =
            new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 13L);

    // ECC
    public static final Asn1Oid sAlgo_EC = new Asn1Oid(1L, 2L, 840L, 10045L, 2L, 1L);
    private static final Asn1Oid sAlgo_SHA1withECDSA = new Asn1Oid(1L, 2L, 840L, 10045L, 4L, 1L);
    private static final Asn1Oid sAlgo_SHA224withECDSA =
            new Asn1Oid(1L, 2L, 840L, 10045L, 4L, 3L, 1L);     // n/a
    private static final Asn1Oid sAlgo_SHA256withECDSA =
            new Asn1Oid(1L, 2L, 840L, 10045L, 4L, 3L, 2L);
    private static final Asn1Oid sAlgo_SHA384withECDSA =
            new Asn1Oid(1L, 2L, 840L, 10045L, 4L, 3L, 3L);
    private static final Asn1Oid sAlgo_SHA512withECDSA =
            new Asn1Oid(1L, 2L, 840L, 10045L, 4L, 3L, 4L);

    private static final Asn1Oid sAlgo_MD2 = new Asn1Oid(1L, 2L, 840L, 113549L, 2L, 2L);
    private static final Asn1Oid sAlgo_MD5 = new Asn1Oid(1L, 2L, 840L, 113549L, 2L, 5L);
    private static final Asn1Oid sAlgo_SHA1 = new Asn1Oid(1L, 3L, 14L, 3L, 2L, 26L);
    private static final Asn1Oid sAlgo_SHA256 =
            new Asn1Oid(2L, 16L, 840L, 1L, 101L, 3L, 4L, 2L, 1L);
    private static final Asn1Oid sAlgo_SHA384 =
            new Asn1Oid(2L, 16L, 840L, 1L, 101L, 3L, 4L, 2L, 2L);
    private static final Asn1Oid sAlgo_SHA512 =
            new Asn1Oid(2L, 16L, 840L, 1L, 101L, 3L, 4L, 2L, 3L);

    // HS2.0 stuff:
    public static final Asn1Oid sPkcs9AtChallengePassword =
            new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 9L, 7L);
    public static final Asn1Oid sExtensionRequest = new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 9L, 14L);

    public static final Asn1Oid sMAC = new Asn1Oid(1L, 3L, 6L, 1L, 1L, 1L, 1L, 22L);
    public static final Asn1Oid sIMEI = new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 3L);
    public static final Asn1Oid sMEID = new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 4L);
    public static final Asn1Oid sDevID = new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 5L);

    public static final Asn1Oid sIdWfaHotspotFriendlyName =
            new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 1L);

    static {
        sCryptoMapping.put(sAlgo_DSA, "DSA");
        sCryptoMapping.put(sAlgo_RSA, "RSA");
        sCryptoMapping.put(sAlgo_EC, "EC");

        sSigAlgos.put(sAlgo_SHA1withDSA, new SigEntry("SHA1withDSA", sAlgo_DSA));

        sSigAlgos.put(sAlgo_MD2withRSA, new SigEntry("MD2withRSA", sAlgo_RSA));
        sSigAlgos.put(sAlgo_MD5withRSA, new SigEntry("MD5withRSA", sAlgo_RSA));
        sSigAlgos.put(sAlgo_SHA1withRSA, new SigEntry("SHA1withRSA", sAlgo_RSA));
        sSigAlgos.put(sAlgo_SHA224withRSA, new SigEntry(null, sAlgo_RSA));
        sSigAlgos.put(sAlgo_SHA256withRSA, new SigEntry("SHA256withRSA", sAlgo_RSA));
        sSigAlgos.put(sAlgo_SHA384withRSA, new SigEntry("SHA384withRSA", sAlgo_RSA));
        sSigAlgos.put(sAlgo_SHA512withRSA, new SigEntry("SHA512withRSA", sAlgo_RSA));

        sSigAlgos.put(sAlgo_SHA1withECDSA, new SigEntry("SHA1withECDSA", sAlgo_EC));
        sSigAlgos.put(sAlgo_SHA224withECDSA, new SigEntry(null, sAlgo_EC));
        sSigAlgos.put(sAlgo_SHA256withECDSA, new SigEntry("SHA256withECDSA", sAlgo_EC));
        sSigAlgos.put(sAlgo_SHA384withECDSA, new SigEntry("SHA384withECDSA", sAlgo_EC));
        sSigAlgos.put(sAlgo_SHA512withECDSA, new SigEntry("SHA512withECDSA", sAlgo_EC));

        sIDMapping.add(sMAC);
        sIDMapping.add(sIMEI);
        sIDMapping.add(sMEID);
        sIDMapping.add(sDevID);

        for (Map.Entry<Asn1Oid, String> entry : sCryptoMapping.entrySet()) {
            sNameMapping.put(entry.getKey(), entry.getValue());
        }
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 1L), "sect163k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 2L), "sect163r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 3L), "sect239k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 4L), "sect113r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 5L), "sect113r2");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 6L), "secp112r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 7L), "secp112r2");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 8L), "secp160r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 9L), "secp160k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 10L), "secp256k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 15L), "sect163r2");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 16L), "sect283k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 17L), "sect283r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 22L), "sect131r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 23L), "sect131r2");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 24L), "sect193r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 25L), "sect193r2");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 26L), "sect233k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 27L), "sect233r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 28L), "secp128r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 29L), "secp128r2");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 30L), "secp160r2");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 31L), "secp192k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 32L), "secp224k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 33L), "secp224r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 34L), "secp384r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 35L), "secp521r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 36L), "sect409k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 37L), "sect409r1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 38L), "sect571k1");
        sNameMapping.put(new Asn1Oid(1L, 3L, 132L, 0L, 39L), "sect571r1");
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 1L, 1L), "secp192r1");
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 1L, 7L), "secp256r1");
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 1L, 2L), "prime192v2");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 1L, 3L), "prime192v3");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 1L, 4L), "prime239v1");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 1L, 5L), "prime239v2");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 1L, 6L), "prime239v3");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 5L), "c2tnb191v1");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 6L), "c2tnb191v2");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 7L), "c2tnb191v3");    // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 11L), "c2tnb239v1");   // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 12L), "c2tnb239v2");   // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 13L), "c2tnb239v3");   // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 18L), "c2tnb359v1");   // X9.62
        sNameMapping.put(new Asn1Oid(1L, 2L, 840L, 10045L, 3L, 0L, 20L), "c2tnb431r1");   // X9.62

        sNameMapping.put(sAlgo_MD2, "MD2");
        sNameMapping.put(sAlgo_MD5, "MD5");
        sNameMapping.put(sAlgo_SHA1, "SHA-1");
        sNameMapping.put(sAlgo_SHA256, "SHA-256");
        sNameMapping.put(sAlgo_SHA384, "SHA-384");
        sNameMapping.put(sAlgo_SHA512, "SHA-512");
    }

    public static SigEntry getSigEntry(Asn1Oid oid) {
        return sSigAlgos.get(oid);
    }

    public static String getCryptoID(Asn1Oid oid) {
        return sCryptoMapping.get(oid);
    }

    public static String getJCEName(Asn1Oid oid) {
        return sNameMapping.get(oid);
    }

    public static String getSigAlgoName(Asn1Oid oid) {
        SigEntry sigEntry = sSigAlgos.get(oid);
        return sigEntry != null ? sigEntry.getSigAlgo() : null;
    }

    public static String getKeyAlgoName(Asn1Oid oid) {
        SigEntry sigEntry = sSigAlgos.get(oid);
        return sigEntry != null ? sNameMapping.get(sigEntry.getKeyAlgo()) : null;
    }

    public static boolean isIDAttribute(Asn1Oid oid) {
        return sIDMapping.contains(oid);
    }
}
