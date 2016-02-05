package com.android.hotspot2.osu;

import com.android.hotspot2.omadm.OMAException;
import com.android.hotspot2.omadm.XMLNode;

public interface ResponseFactory {
    public OSUResponse buildResponse(XMLNode root) throws OMAException;
}
