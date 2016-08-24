package com.android.anqp.eap;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static com.android.anqp.Constants.BYTE_MASK;

/**
 * An EAP authentication parameter, IEEE802.11-2012, table 8-188
 */
public class NonEAPInnerAuth implements AuthParam {

    public enum NonEAPType {Reserved, PAP, CHAP, MSCHAP, MSCHAPv2}
    private static final Map<NonEAPType, String> sOmaMap = new EnumMap<>(NonEAPType.class);
    private static final Map<String, NonEAPType> sRevOmaMap = new HashMap<>();

    private final NonEAPType mType;

    static {
        sOmaMap.put(NonEAPType.PAP, "PAP");
        sOmaMap.put(NonEAPType.CHAP, "CHAP");
        sOmaMap.put(NonEAPType.MSCHAP, "MS-CHAP");
        sOmaMap.put(NonEAPType.MSCHAPv2, "MS-CHAP-V2");

        for (Map.Entry<NonEAPType, String> entry : sOmaMap.entrySet()) {
            sRevOmaMap.put(entry.getValue(), entry.getKey());
        }
    }

    public NonEAPInnerAuth(int length, ByteBuffer payload) throws ProtocolException {
        if (length != 1) {
            throw new ProtocolException("Bad length: " + payload.remaining());
        }

        int typeID = payload.get() & BYTE_MASK;
        mType = typeID < NonEAPType.values().length ?
                NonEAPType.values()[typeID] :
                NonEAPType.Reserved;
    }

    public NonEAPInnerAuth(NonEAPType type) {
        mType = type;
    }

    /**
     * Construct from the OMA-DM PPS data
     * @param eapType as defined in the HS2.0 spec.
     */
    public NonEAPInnerAuth(String eapType) {
        mType = sRevOmaMap.get(eapType);
    }

    @Override
    public EAP.AuthInfoID getAuthInfoID() {
        return EAP.AuthInfoID.NonEAPInnerAuthType;
    }

    public NonEAPType getType() {
        return mType;
    }

    public String getOMAtype() {
        return sOmaMap.get(mType);
    }

    public static String mapInnerType(NonEAPType type) {
        return sOmaMap.get(type);
    }

    @Override
    public int hashCode() {
        return mType.hashCode();
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        } else if (thatObject == null || thatObject.getClass() != NonEAPInnerAuth.class) {
            return false;
        } else {
            return ((NonEAPInnerAuth) thatObject).getType() == getType();
        }
    }

    @Override
    public String toString() {
        return "Auth method NonEAPInnerAuthEAP, inner = " + mType + '\n';
    }
}
