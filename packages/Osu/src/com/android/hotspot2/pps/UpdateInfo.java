package com.android.hotspot2.pps;

import android.util.Base64;

import com.android.hotspot2.Utils;
import com.android.hotspot2.omadm.MOManager;
import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.OMANode;

import java.nio.charset.StandardCharsets;

import static com.android.hotspot2.omadm.MOManager.TAG_CertSHA256Fingerprint;
import static com.android.hotspot2.omadm.MOManager.TAG_CertURL;
import static com.android.hotspot2.omadm.MOManager.TAG_Password;
import static com.android.hotspot2.omadm.MOManager.TAG_Restriction;
import static com.android.hotspot2.omadm.MOManager.TAG_TrustRoot;
import static com.android.hotspot2.omadm.MOManager.TAG_URI;
import static com.android.hotspot2.omadm.MOManager.TAG_UpdateInterval;
import static com.android.hotspot2.omadm.MOManager.TAG_UpdateMethod;
import static com.android.hotspot2.omadm.MOManager.TAG_Username;
import static com.android.hotspot2.omadm.MOManager.TAG_UsernamePassword;

public class UpdateInfo {
    public enum UpdateRestriction {HomeSP, RoamingPartner, Unrestricted}

    public static final long NO_UPDATE = 0xffffffffL;

    private final long mInterval;
    private final boolean mSPPClientInitiated;
    private final UpdateRestriction mUpdateRestriction;
    private final String mURI;
    private final String mUsername;
    private final String mPassword;
    private final String mCertURL;
    private final String mCertFP;

    public UpdateInfo(OMANode policyUpdate) throws OMAException {
        long minutes = MOManager.getLong(policyUpdate, TAG_UpdateInterval, null);
        mInterval = minutes == NO_UPDATE ? -1 : minutes * MOManager.IntervalFactor;
        mSPPClientInitiated = MOManager.getSelection(policyUpdate, TAG_UpdateMethod);
        mUpdateRestriction = MOManager.getSelection(policyUpdate, TAG_Restriction);
        mURI = MOManager.getString(policyUpdate, TAG_URI);

        OMANode unp = policyUpdate.getChild(TAG_UsernamePassword);
        if (unp != null) {
            mUsername = MOManager.getString(unp.getChild(TAG_Username));
            String pw = MOManager.getString(unp.getChild(TAG_Password));
            mPassword = new String(Base64.decode(pw.getBytes(StandardCharsets.US_ASCII),
                    Base64.DEFAULT), StandardCharsets.UTF_8);
        } else {
            mUsername = null;
            mPassword = null;
        }

        OMANode trustRoot = MOManager.getChild(policyUpdate, TAG_TrustRoot);
        mCertURL = MOManager.getString(trustRoot, TAG_CertURL);
        mCertFP = MOManager.getString(trustRoot, TAG_CertSHA256Fingerprint);
    }

    public long getInterval() {
        return mInterval;
    }

    public boolean isSPPClientInitiated() {
        return mSPPClientInitiated;
    }

    public UpdateRestriction getUpdateRestriction() {
        return mUpdateRestriction;
    }

    public String getURI() {
        return mURI;
    }

    public String getUsername() {
        return mUsername;
    }

    public String getPassword() {
        return mPassword;
    }

    public String getCertURL() {
        return mCertURL;
    }

    public String getCertFP() {
        return mCertFP;
    }

    @Override
    public String toString() {
        return "UpdateInfo{" +
                "interval=" + Utils.toHMS(mInterval) +
                ", SPPClientInitiated=" + mSPPClientInitiated +
                ", updateRestriction=" + mUpdateRestriction +
                ", URI='" + mURI + '\'' +
                ", username='" + mUsername + '\'' +
                ", password=" + mPassword +
                ", certURL='" + mCertURL + '\'' +
                ", certFP='" + mCertFP + '\'' +
                '}';
    }
}
