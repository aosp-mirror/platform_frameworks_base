package com.android.hotspot2.asn1;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Asn1Oid extends Asn1Object {
    public static final int OidMaxOctet1 = 2;
    public static final int OidOctet1Modulus = 40;

    private final List<Long> mArcs;
    private final int mHashcode;

    private static final Map<Asn1Oid, String> sOidMap = new HashMap<>();

    public Asn1Oid(int tag, Asn1Class asn1Class, int length, ByteBuffer data)
            throws DecodeException {
        super(tag, asn1Class, false, length);

        if (length == 0)
            throw new DecodeException("oid-encoding length is zero", data.position());

        mArcs = new ArrayList<>();

        ByteBuffer payload = data.duplicate();
        payload.limit(payload.position() + length);
        data.position(data.position() + length);

        byte current = payload.get();
        long seg01 = current & Asn1Decoder.ByteMask;
        long segValue = seg01 / OidOctet1Modulus;
        int hashcode = (int) segValue;
        mArcs.add(segValue);
        segValue = seg01 - segValue * OidOctet1Modulus;
        hashcode = hashcode * 31 + (int) segValue;
        mArcs.add(segValue);

        current = 0;
        segValue = 0L;

        while (payload.hasRemaining()) {
            current = payload.get();
            segValue |= current & Asn1Decoder.MoreData;
            if ((current & Asn1Decoder.MoreBit) == 0) {
                hashcode = hashcode * 31 + (int) segValue;
                mArcs.add(segValue);
                segValue = 0L;
            } else
                segValue <<= Asn1Decoder.MoreWidth;
        }
        if ((current & Asn1Decoder.MoreBit) != 0)
            throw new DecodeException("Illegal (end of) oid-encoding", payload.position());
        mHashcode = hashcode;
    }

    public Asn1Oid(Long... arcs) {
        super(Asn1Decoder.TAG_OID, Asn1Class.Universal, false, -1);
        mArcs = Arrays.asList(arcs);
        int hashcode = 0;
        for (long arc : arcs) {
            hashcode = hashcode * 31 + (int) arc;
        }
        mHashcode = hashcode;
    }

    @Override
    public int hashCode() {
        return mHashcode;
    }

    @Override
    public boolean equals(Object thatObject) {
        return !(thatObject == null || thatObject.getClass() != Asn1Oid.class) &&
                mArcs.equals(((Asn1Oid) thatObject).mArcs);
    }

    public String toOIDString() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (long arc : mArcs) {
            if (first) {
                first = false;
            } else {
                sb.append('.');
            }
            sb.append(arc);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(toOIDString());
        String name = sOidMap.get(this);
        if (name != null) {
            sb.append(" (").append(name).append(')');
        }
        return super.toString() + '=' + sb.toString();
    }

    @Override
    public Collection<Asn1Object> getChildren() {
        throw new UnsupportedOperationException();
    }

    public static final Asn1Oid PKCS7Data = new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 7L, 1L);
    public static final Asn1Oid PKCS7SignedData = new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 7L, 2L);
    // encoded as an IA5STRING type
    public static final Asn1Oid OidMacAddress = new Asn1Oid(1L, 3L, 6L, 1L, 1L, 1L, 1L, 22L);
    // encoded as an IA5STRING type
    public static final Asn1Oid OidImei = new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 3L);
    // encoded as a BITSTRING type
    public static final Asn1Oid OidMeid = new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 4L);
    // encoded as a PRINTABLESTRING type
    public static final Asn1Oid OidDevId = new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 5L);

    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10040L, 4L, 1L), "algo_id_dsa");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10040L, 4L, 3L), "algo_id_dsawithsha1");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10045L, 2L, 1L), "algo_id_ecPublicKey");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10045L, 4L, 3L, 3L), "eccdaWithSHA384");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 1L), "algo_id_rsaEncryption");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 2L), "algo_id_md2WithRSAEncryption");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 4L), "algo_id_md5WithRSAEncryption");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 5L), "algo_id_sha1WithRSAEncryption");
    //sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 11L),
    // "algo_id_sha256WithRSAEncryption");

    static {
        sOidMap.put(new Asn1Oid(0L, 0L), "NullOid");
        sOidMap.put(new Asn1Oid(0L, 9L, 2342L, 19200300L, 100L, 1L, 25L), "domComp");

        sOidMap.put(OidMacAddress, "mac-address");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10040L, 4L, 1L), "algo_id_dsa");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10040L, 4L, 3L), "algo_id_dsawithsha1");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10045L, 2L, 1L), "algo_id_ecPublicKey");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10045L, 4L, 3L, 3L), "eccdaWithSHA384");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 10046L, 2L, 1L), "algo_id_dhpublicnumber");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 1L), "algo_id_rsaEncryption");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 2L), "algo_id_md2WithRSAEncryption");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 4L), "algo_id_md5WithRSAEncryption");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 5L),
                "algo_id_sha1WithRSAEncryption");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 1L, 11L),
                "algo_id_sha256WithRSAEncryption");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 7L), "pkcs7");
        sOidMap.put(PKCS7Data, "pkcs7-data");
        sOidMap.put(PKCS7SignedData, "pkcs7-signedData");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 9L, 1L), "emailAddress");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 9L, 7L), "challengePassword");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 1L, 9L, 14L), "extensionRequest");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 3L, 2L), "algo_id_RC2_CBC");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 3L, 4L), "algo_id_RC4_ENC");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 3L, 7L), "algo_id_DES_EDE3_CBC");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 3L, 9L), "algo_id_RC5_CBC_PAD");
        sOidMap.put(new Asn1Oid(1L, 2L, 840L, 113549L, 3L, 10L), "algo_id_desCDMF");
        sOidMap.put(new Asn1Oid(1L, 3L, 6L, 1L, 4L, 1L, 40808L, 1L, 1L, 2L), "id-kp-HS2.0Auth");
        sOidMap.put(OidImei, "imei");
        sOidMap.put(OidMeid, "meid");
        sOidMap.put(OidDevId, "DevId");
        sOidMap.put(new Asn1Oid(1L, 3L, 6L, 1L, 5L, 5L, 7L, 1L, 1L),
                "certAuthorityInfoAccessSyntax");
        sOidMap.put(new Asn1Oid(1L, 3L, 6L, 1L, 5L, 5L, 7L, 1L, 11L),
                "certSubjectInfoAccessSyntax");
        sOidMap.put(new Asn1Oid(1L, 3L, 14L, 3L, 2L, 26L), "algo_id_SHA1");
        sOidMap.put(new Asn1Oid(1L, 3L, 132L, 0L, 34L), "secp384r1");

        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 3L), "x500_CN");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 4L), "x500_SN");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 5L), "x500_serialNum");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 6L), "x500_C");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 7L), "x500_L");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 8L), "x500_ST");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 9L), "x500_STREET");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 10L), "x500_O");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 11L), "x500_OU");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 12L), "x500_title");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 13L), "x500_description");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 17L), "x500_postalCode");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 18L), "x500_poBox");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 20L), "x500_phone");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 41L), "x500_name");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 42L), "x500_givenName");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 44L), "x500_genQual");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 43L), "x500_initials");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 46L), "x500_dnQualifier");
        sOidMap.put(new Asn1Oid(2L, 5L, 4L, 65L), "x500_pseudonym");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 9L), "certSubjectDirectoryAttributes");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 14L), "certSubjectKeyIdentifier ");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 15L), "certKeyUsage");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 16L), "certPrivateKeyUsagePeriod");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 17L), "certSubjectAltName");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 18L), "certIssuerAltName");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 19L), "certBasicConstraints");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 30L), "certNameConstraints");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 31L), "certCRLDistributionPoints");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 32L), "certificatePolicies");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 33L), "certPolicyMappings");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 35L), "certAuthorityKeyIdentifier ");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 36L), "certPolicyConstraints");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 37L), "certExtKeyUsageSyntax");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 46L), "certFreshestCRL");
        sOidMap.put(new Asn1Oid(2L, 5L, 29L, 54L), "certInhibitAnyPolicy");
        sOidMap.put(new Asn1Oid(2L, 16L, 840L, 1L, 101L, 3L, 4L, 1L, 2L), "algo_id_aes128");
        sOidMap.put(new Asn1Oid(2L, 16L, 840L, 1L, 101L, 3L, 4L, 1L, 22L), "algo_id_aes192");
        sOidMap.put(new Asn1Oid(2L, 16L, 840L, 1L, 101L, 3L, 4L, 1L, 42L), "algo_id_aes256");
    }
}
