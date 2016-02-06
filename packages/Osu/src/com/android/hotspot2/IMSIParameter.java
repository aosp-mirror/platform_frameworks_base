package com.android.hotspot2;

import java.io.IOException;

public class IMSIParameter {
    private final String mImsi;
    private final boolean mPrefix;

    public IMSIParameter(String imsi, boolean prefix) {
        mImsi = imsi;
        mPrefix = prefix;
    }

    public IMSIParameter(String imsi) throws IOException {
        if (imsi == null || imsi.length() == 0) {
            throw new IOException("Bad IMSI: '" + imsi + "'");
        }

        int nonDigit;
        char stopChar = '\0';
        for (nonDigit = 0; nonDigit < imsi.length(); nonDigit++) {
            stopChar = imsi.charAt(nonDigit);
            if (stopChar < '0' || stopChar > '9') {
                break;
            }
        }

        if (nonDigit == imsi.length()) {
            mImsi = imsi;
            mPrefix = false;
        } else if (nonDigit == imsi.length() - 1 && stopChar == '*') {
            mImsi = imsi.substring(0, nonDigit);
            mPrefix = true;
        } else {
            throw new IOException("Bad IMSI: '" + imsi + "'");
        }
    }

    public boolean matches(String fullIMSI) {
        if (mPrefix) {
            return mImsi.regionMatches(false, 0, fullIMSI, 0, mImsi.length());
        } else {
            return mImsi.equals(fullIMSI);
        }
    }

    public boolean matchesMccMnc(String mccMnc) {
        if (mPrefix) {
            // For a prefix match, the entire prefix must match the mcc+mnc
            return mImsi.regionMatches(false, 0, mccMnc, 0, mImsi.length());
        } else {
            // For regular match, the entire length of mcc+mnc must match this IMSI
            return mImsi.regionMatches(false, 0, mccMnc, 0, mccMnc.length());
        }
    }

    public boolean isPrefix() {
        return mPrefix;
    }

    public String getImsi() {
        return mImsi;
    }

    public int prefixLength() {
        return mImsi.length();
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        } else if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        IMSIParameter that = (IMSIParameter) thatObject;
        return mPrefix == that.mPrefix && mImsi.equals(that.mImsi);
    }

    @Override
    public int hashCode() {
        int result = mImsi != null ? mImsi.hashCode() : 0;
        result = 31 * result + (mPrefix ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        if (mPrefix) {
            return mImsi + '*';
        } else {
            return mImsi;
        }
    }
}
