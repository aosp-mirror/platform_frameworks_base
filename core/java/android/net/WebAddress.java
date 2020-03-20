/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import static android.util.Patterns.GOOD_IRI_CHAR;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@hide}
 *
 * Web Address Parser
 *
 * This is called WebAddress, rather than URL or URI, because it
 * attempts to parse the stuff that a user will actually type into a
 * browser address widget.
 *
 * Unlike java.net.uri, this parser will not choke on URIs missing
 * schemes.  It will only throw a ParseException if the input is
 * really hosed.
 *
 * If given an https scheme but no port, fills in port
 *
 */
// TODO(igsolla): remove WebAddress from the system SDK once the WebView apk does not
// longer need to be binary compatible with the API 21 version of the framework.
@SystemApi
public class WebAddress {

    @UnsupportedAppUsage
    private String mScheme;
    @UnsupportedAppUsage
    private String mHost;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mPort;
    @UnsupportedAppUsage
    private String mPath;
    private String mAuthInfo;

    static final int MATCH_GROUP_SCHEME = 1;
    static final int MATCH_GROUP_AUTHORITY = 2;
    static final int MATCH_GROUP_HOST = 3;
    static final int MATCH_GROUP_PORT = 4;
    static final int MATCH_GROUP_PATH = 5;

    static Pattern sAddressPattern = Pattern.compile(
            /* scheme    */ "(?:(http|https|file)\\:\\/\\/)?" +
            /* authority */ "(?:([-A-Za-z0-9$_.+!*'(),;?&=]+(?:\\:[-A-Za-z0-9$_.+!*'(),;?&=]+)?)@)?" +
            /* host      */ "([" + GOOD_IRI_CHAR + "%_-][" + GOOD_IRI_CHAR + "%_\\.-]*|\\[[0-9a-fA-F:\\.]+\\])?" +
            /* port      */ "(?:\\:([0-9]*))?" +
            /* path      */ "(\\/?[^#]*)?" +
            /* anchor    */ ".*", Pattern.CASE_INSENSITIVE);

    /** parses given uriString. */
    public WebAddress(String address) throws ParseException {
        if (address == null) {
            throw new NullPointerException();
        }

        // android.util.Log.d(LOGTAG, "WebAddress: " + address);

        mScheme = "";
        mHost = "";
        mPort = -1;
        mPath = "/";
        mAuthInfo = "";

        Matcher m = sAddressPattern.matcher(address);
        String t;
        if (m.matches()) {
            t = m.group(MATCH_GROUP_SCHEME);
            if (t != null) mScheme = t.toLowerCase(Locale.ROOT);
            t = m.group(MATCH_GROUP_AUTHORITY);
            if (t != null) mAuthInfo = t;
            t = m.group(MATCH_GROUP_HOST);
            if (t != null) mHost = t;
            t = m.group(MATCH_GROUP_PORT);
            if (t != null && t.length() > 0) {
                // The ':' character is not returned by the regex.
                try {
                    mPort = Integer.parseInt(t);
                } catch (NumberFormatException ex) {
                    throw new ParseException("Bad port");
                }
            }
            t = m.group(MATCH_GROUP_PATH);
            if (t != null && t.length() > 0) {
                /* handle busted myspace frontpage redirect with
                   missing initial "/" */
                if (t.charAt(0) == '/') {
                    mPath = t;
                } else {
                    mPath = "/" + t;
                }
            }

        } else {
            // nothing found... outa here
            throw new ParseException("Bad address");
        }

        /* Get port from scheme or scheme from port, if necessary and
           possible */
        if (mPort == 443 && mScheme.equals("")) {
            mScheme = "https";
        } else if (mPort == -1) {
            if (mScheme.equals("https"))
                mPort = 443;
            else
                mPort = 80; // default
        }
        if (mScheme.equals("")) mScheme = "http";
    }

    @NonNull
    @Override
    public String toString() {
        String port = "";
        if ((mPort != 443 && mScheme.equals("https")) ||
            (mPort != 80 && mScheme.equals("http"))) {
            port = ":" + Integer.toString(mPort);
        }
        String authInfo = "";
        if (mAuthInfo.length() > 0) {
            authInfo = mAuthInfo + "@";
        }

        return mScheme + "://" + authInfo + mHost + port + mPath;
    }

    /** {@hide} */
    public void setScheme(String scheme) {
      mScheme = scheme;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public String getScheme() {
      return mScheme;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public void setHost(String host) {
      mHost = host;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public String getHost() {
      return mHost;
    }

    /** {@hide} */
    public void setPort(int port) {
      mPort = port;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public int getPort() {
      return mPort;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public void setPath(String path) {
      mPath = path;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public String getPath() {
      return mPath;
    }

    /** {@hide} */
    public void setAuthInfo(String authInfo) {
      mAuthInfo = authInfo;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public String getAuthInfo() {
      return mAuthInfo;
    }
}
