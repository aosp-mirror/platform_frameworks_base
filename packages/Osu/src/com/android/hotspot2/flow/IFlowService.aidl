package com.android.hotspot2.flow;

import com.android.hotspot2.flow.OSUInfo;
import android.net.Network;

interface IFlowService {
    void provision(in OSUInfo osuInfo);
    void remediate(String spFqdn, String url, boolean policy, in Network network);
    void spDeleted(String fqdn);
}
