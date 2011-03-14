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

package com.android.internal.telephony.gsm;

import android.os.Message;
import android.util.Log;
import android.util.Patterns;
import android.text.TextUtils;

import com.android.internal.telephony.ApnSetting;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RetryManager;

/**
 * {@hide}
 */
public class GsmDataConnection extends DataConnection {

    private static final String LOG_TAG = "GSM";

    //***** Instance Variables
    private ApnSetting apn;

    protected int mProfileId = RILConstants.DATA_PROFILE_DEFAULT;
    protected String mActiveApnType = Phone.APN_TYPE_DEFAULT;
    //***** Constructor
    private GsmDataConnection(PhoneBase phone, String name, RetryManager rm) {
        super(phone, name, rm);
    }

    /**
     * Create the connection object
     *
     * @param phone the Phone
     * @param id the connection id
     * @param rm the RetryManager
     * @return GsmDataConnection that was created.
     */
    static GsmDataConnection makeDataConnection(PhoneBase phone, int id, RetryManager rm) {
        synchronized (mCountLock) {
            mCount += 1;
        }
        GsmDataConnection gsmDc = new GsmDataConnection(phone, "GsmDataConnection-" + mCount, rm);
        gsmDc.start();
        if (DBG) gsmDc.log("Made " + gsmDc.getName());
        gsmDc.mId = id;
        gsmDc.mRetryMgr = rm;
        return gsmDc;
    }

    /**
     * Begin setting up a data connection, calls setupDataCall
     * and the ConnectionParams will be returned with the
     * EVENT_SETUP_DATA_CONNECTION_DONE AsyncResul.userObj.
     *
     * @param cp is the connection parameters
     */
    @Override
    protected
    void onConnect(ConnectionParams cp) {
        apn = cp.apn;

        if (DBG) log("Connecting to carrier: '" + apn.carrier
                + "' APN: '" + apn.apn
                + "' proxy: '" + apn.proxy + "' port: '" + apn.port);

        setHttpProxy (apn.proxy, apn.port);

        createTime = -1;
        lastFailTime = -1;
        lastFailCause = FailCause.NONE;

        // msg.obj will be returned in AsyncResult.userObj;
        Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
        msg.obj = cp;

        int authType = apn.authType;
        if (authType == -1) {
            authType = (apn.user != null) ? RILConstants.SETUP_DATA_AUTH_PAP_CHAP :
                RILConstants.SETUP_DATA_AUTH_NONE;
        }

        String protocol;
        if (phone.getServiceState().getRoaming()) {
            protocol = apn.roamingProtocol;
        } else {
            protocol = apn.protocol;
        }

        phone.mCM.setupDataCall(
                Integer.toString(RILConstants.SETUP_DATA_TECH_GSM),
                Integer.toString(mProfileId),
                apn.apn, apn.user, apn.password,
                Integer.toString(authType),
                protocol, msg);
    }

    public void setProfileId(int profileId) {
        mProfileId = profileId;
    }

    public int getProfileId() {
        return mProfileId;
    }

    public int getCid() {
        // 'cid' has been defined in parent class
        return cid;
    }

    public void setActiveApnType(String apnType) {
        mActiveApnType = apnType;
    }

    @Override
    protected void clearSettings() {
        super.clearSettings();
        apn = null;
    }

    @Override
    public String toString() {
        return "State=" + getCurrentState().getName() + " Apn=" + apn +
               " create=" + createTime + " lastFail=" + lastFailTime +
               " lastFailCause=" + lastFailCause;
    }

    @Override
    protected boolean isDnsOk(String[] domainNameServers) {
        if (NULL_IP.equals(domainNameServers[0]) && NULL_IP.equals(domainNameServers[1])
                && !((GSMPhone) phone).isDnsCheckDisabled()) {
            // Work around a race condition where QMI does not fill in DNS:
            // Deactivate PDP and let DataConnectionTracker retry.
            // Do not apply the race condition workaround for MMS APN
            // if Proxy is an IP-address.
            // Otherwise, the default APN will not be restored anymore.
            if (!apn.types[0].equals(Phone.APN_TYPE_MMS)
                || !isIpAddress(apn.mmsProxy)) {
                log(String.format(
                        "isDnsOk: return false apn.types[0]=%s APN_TYPE_MMS=%s isIpAddress(%s)=%s",
                        apn.types[0], Phone.APN_TYPE_MMS, apn.mmsProxy, isIpAddress(apn.mmsProxy)));
                return false;
            }
        }
        return true;
    }

    @Override
    protected void log(String s) {
        Log.d(LOG_TAG, "[" + getName() + "] " + s);
    }

    public ApnSetting getApn() {
        return this.apn;
    }

    private void setHttpProxy(String httpProxy, String httpPort) {

        if (DBG) log("set http proxy for"
                + "' APN: '" + mActiveApnType
                + "' proxy: '" + apn.proxy + "' port: '" + apn.port);
        if(TextUtils.equals(mActiveApnType, Phone.APN_TYPE_DEFAULT)) {
            if (httpProxy == null || httpProxy.length() == 0) {
                phone.setSystemProperty("net.gprs.http-proxy", null);
                return;
            }

            if (httpPort == null || httpPort.length() == 0) {
                httpPort = "8080";     // Default to port 8080
            }

            phone.setSystemProperty("net.gprs.http-proxy",
                    "http://" + httpProxy + ":" + httpPort + "/");
        } else {
            if (httpProxy == null || httpProxy.length() == 0) {
                phone.setSystemProperty("net.gprs.http-proxy." + mActiveApnType, null);
                return;
            }

            if (httpPort == null || httpPort.length() == 0) {
                httpPort = "8080";  // Default to port 8080
            }

            phone.setSystemProperty("net.gprs.http-proxy." + mActiveApnType,
                    "http://" + httpProxy + ":" + httpPort + "/");
        }
    }

    private boolean isIpAddress(String address) {
        if (address == null) return false;

        return Patterns.IP_ADDRESS.matcher(apn.mmsProxy).matches();
    }
}
