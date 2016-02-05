package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.OMAConstants;
import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;

import java.util.HashMap;
import java.util.Map;

public abstract class OSUResponse {
    private static final String SPPVersionAttribute = "sppVersion";
    private static final String SPPStatusAttribute = "sppStatus";
    private static final String SPPSessionIDAttribute = "sessionID";

    private final OSUMessageType mMessageType;
    private final String mVersion;
    private final String mSessionID;
    private final OSUStatus mStatus;
    private final OSUError mError;
    private final Map<String, String> mAttributes;

    protected OSUResponse(XMLNode root, OSUMessageType messageType, String... attributes)
            throws OMAException {
        mMessageType = messageType;
        String ns = root.getNameSpace() + ":";
        mVersion = root.getAttributeValue(ns + SPPVersionAttribute);
        mSessionID = root.getAttributeValue(ns + SPPSessionIDAttribute);

        String status = root.getAttributeValue(ns + SPPStatusAttribute);
        if (status == null) {
            throw new OMAException("Missing status");
        }
        mStatus = OMAConstants.mapStatus(status);

        if (mVersion == null || mSessionID == null || mStatus == null) {
            throw new OMAException("Incomplete request: " + root.getAttributes());
        }

        if (attributes != null) {
            mAttributes = new HashMap<>();
            for (String attribute : attributes) {
                String value = root.getAttributeValue(ns + attribute);
                if (value == null) {
                    throw new OMAException("Missing attribute: " + attribute);
                }
                mAttributes.put(attribute, value);
            }
        } else {
            mAttributes = null;
        }

        if (mStatus == OSUStatus.Error) {
            OSUError error = null;
            String errorTag = ns + "sppError";
            for (XMLNode child : root.getChildren()) {
                if (child.getTag().equals(errorTag)) {
                    error = OMAConstants.mapError(child.getAttributeValue("errorCode"));
                    break;
                }
            }
            mError = error;
        } else {
            mError = null;
        }
    }

    public OSUMessageType getMessageType() {
        return mMessageType;
    }

    public String getVersion() {
        return mVersion;
    }

    public String getSessionID() {
        return mSessionID;
    }

    public OSUStatus getStatus() {
        return mStatus;
    }

    public OSUError getError() {
        return mError;
    }

    protected Map<String, String> getAttributes() {
        return mAttributes;
    }

    @Override
    public String toString() {
        return String.format("%s version '%s', status %s, session-id '%s'%s",
                mMessageType, mVersion, mStatus, mSessionID, mError != null
                        ? (" (" + mError + ")") : "");
    }
}
