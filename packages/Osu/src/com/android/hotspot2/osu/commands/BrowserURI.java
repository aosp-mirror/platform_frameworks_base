package com.android.hotspot2.osu.commands;

import com.android.hotspot2.omadm.XMLNode;

/*
    <spp:sppPostDevDataResponse xmlns:spp="http://www.wi-fi.org/specifications/hotspot2dot0/v1.0/spp"
                                spp:sessionID="D74A7B03005645DAA516191DEE77B94F" spp:sppStatus="OK"
                                spp:sppVersion="1.0">
        <spp:exec>
            <spp:launchBrowserToURI>
                https://subscription-server.r2-testbed-rks.wi-fi.org:8443/web/ruckuswireles/home/-/onlinesignup/subscriberDetails?Credentials=USERNAME_PASSWORD&amp;SessionID=D74A7B03005645DAA516191DEE77B94F&amp;RedirectURI=http://127.0.0.1:12345/index.htm&amp;UpdateMethod=SPP-ClientInitiated
            </spp:launchBrowserToURI>
        </spp:exec>
    </spp:sppPostDevDataResponse>
 */

public class BrowserURI implements OSUCommandData {
    private final String mURI;

    public BrowserURI(XMLNode commandNode) {
        mURI = commandNode.getText();
    }

    public String getURI() {
        return mURI;
    }

    @Override
    public String toString() {
        return "URI: " + mURI;
    }
}
