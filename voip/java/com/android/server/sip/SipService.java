/*
 * Copyright (C) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.sip;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.sip.ISipService;
import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipErrorCode;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.net.sip.SipSessionAdapter;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.telephony.Rlog;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.sip.SipException;

/**
 * @hide
 */
public final class SipService extends ISipService.Stub {
    static final String TAG = "SipService";
    static final boolean DBG = true;
    private static final int EXPIRY_TIME = 3600;
    private static final int SHORT_EXPIRY_TIME = 10;
    private static final int MIN_EXPIRY_TIME = 60;
    private static final int DEFAULT_KEEPALIVE_INTERVAL = 10; // in seconds
    private static final int DEFAULT_MAX_KEEPALIVE_INTERVAL = 120; // in seconds

    private Context mContext;
    private String mLocalIp;
    private int mNetworkType = -1;
    private SipWakeupTimer mTimer;
    private WifiManager.WifiLock mWifiLock;
    private boolean mSipOnWifiOnly;

    private SipKeepAliveProcessCallback mSipKeepAliveProcessCallback;

    private MyExecutor mExecutor = new MyExecutor();

    // SipProfile URI --> group
    private Map<String, SipSessionGroupExt> mSipGroups =
            new HashMap<String, SipSessionGroupExt>();

    // session ID --> session
    private Map<String, ISipSession> mPendingSessions =
            new HashMap<String, ISipSession>();

    private ConnectivityReceiver mConnectivityReceiver;
    private SipWakeLock mMyWakeLock;
    private int mKeepAliveInterval;
    private int mLastGoodKeepAliveInterval = DEFAULT_KEEPALIVE_INTERVAL;

    /**
     * Starts the SIP service. Do nothing if the SIP API is not supported on the
     * device.
     */
    public static void start(Context context) {
        if (SipManager.isApiSupported(context)) {
            ServiceManager.addService("sip", new SipService(context));
            context.sendBroadcast(new Intent(SipManager.ACTION_SIP_SERVICE_UP));
            if (DBG) slog("start:");
        }
    }

    private SipService(Context context) {
        if (DBG) log("SipService: started!");
        mContext = context;
        mConnectivityReceiver = new ConnectivityReceiver();

        mWifiLock = ((WifiManager)
                context.getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);
        mWifiLock.setReferenceCounted(false);
        mSipOnWifiOnly = SipManager.isSipWifiOnly(context);

        mMyWakeLock = new SipWakeLock((PowerManager)
                context.getSystemService(Context.POWER_SERVICE));

        mTimer = new SipWakeupTimer(context, mExecutor);
    }

    @Override
    public synchronized SipProfile[] getListOfProfiles() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        boolean isCallerRadio = isCallerRadio();
        ArrayList<SipProfile> profiles = new ArrayList<SipProfile>();
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if (isCallerRadio || isCallerCreator(group)) {
                profiles.add(group.getLocalProfile());
            }
        }
        return profiles.toArray(new SipProfile[profiles.size()]);
    }

    @Override
    public synchronized void open(SipProfile localProfile) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        try {
            createGroup(localProfile);
        } catch (SipException e) {
            loge("openToMakeCalls()", e);
            // TODO: how to send the exception back
        }
    }

    @Override
    public synchronized void open3(SipProfile localProfile,
            PendingIntent incomingCallPendingIntent,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        if (incomingCallPendingIntent == null) {
            if (DBG) log("open3: incomingCallPendingIntent cannot be null; "
                    + "the profile is not opened");
            return;
        }
        if (DBG) log("open3: " + localProfile.getUriString() + ": "
                + incomingCallPendingIntent + ": " + listener);
        try {
            SipSessionGroupExt group = createGroup(localProfile,
                    incomingCallPendingIntent, listener);
            if (localProfile.getAutoRegistration()) {
                group.openToReceiveCalls();
                updateWakeLocks();
            }
        } catch (SipException e) {
            loge("open3:", e);
            // TODO: how to send the exception back
        }
    }

    private boolean isCallerCreator(SipSessionGroupExt group) {
        SipProfile profile = group.getLocalProfile();
        return (profile.getCallingUid() == Binder.getCallingUid());
    }

    private boolean isCallerCreatorOrRadio(SipSessionGroupExt group) {
        return (isCallerRadio() || isCallerCreator(group));
    }

    private boolean isCallerRadio() {
        return (Binder.getCallingUid() == Process.PHONE_UID);
    }

    @Override
    public synchronized void close(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return;
        if (!isCallerCreatorOrRadio(group)) {
            if (DBG) log("only creator or radio can close this profile");
            return;
        }

        group = mSipGroups.remove(localProfileUri);
        notifyProfileRemoved(group.getLocalProfile());
        group.close();

        updateWakeLocks();
    }

    @Override
    public synchronized boolean isOpened(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return false;
        if (isCallerCreatorOrRadio(group)) {
            return true;
        } else {
            if (DBG) log("only creator or radio can query on the profile");
            return false;
        }
    }

    @Override
    public synchronized boolean isRegistered(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return false;
        if (isCallerCreatorOrRadio(group)) {
            return group.isRegistered();
        } else {
            if (DBG) log("only creator or radio can query on the profile");
            return false;
        }
    }

    @Override
    public synchronized void setRegistrationListener(String localProfileUri,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return;
        if (isCallerCreator(group)) {
            group.setListener(listener);
        } else {
            if (DBG) log("only creator can set listener on the profile");
        }
    }

    @Override
    public synchronized ISipSession createSession(SipProfile localProfile,
            ISipSessionListener listener) {
        if (DBG) log("createSession: profile" + localProfile);
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        if (mNetworkType == -1) {
            if (DBG) log("createSession: mNetworkType==-1 ret=null");
            return null;
        }
        try {
            SipSessionGroupExt group = createGroup(localProfile);
            return group.createSession(listener);
        } catch (SipException e) {
            if (DBG) loge("createSession;", e);
            return null;
        }
    }

    @Override
    public synchronized ISipSession getPendingSession(String callId) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        if (callId == null) return null;
        return mPendingSessions.get(callId);
    }

    private String determineLocalIp() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("192.168.1.1"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            if (DBG) loge("determineLocalIp()", e);
            // dont do anything; there should be a connectivity change going
            return null;
        }
    }

    private SipSessionGroupExt createGroup(SipProfile localProfile)
            throws SipException {
        String key = localProfile.getUriString();
        SipSessionGroupExt group = mSipGroups.get(key);
        if (group == null) {
            group = new SipSessionGroupExt(localProfile, null, null);
            mSipGroups.put(key, group);
            notifyProfileAdded(localProfile);
        } else if (!isCallerCreator(group)) {
            throw new SipException("only creator can access the profile");
        }
        return group;
    }

    private SipSessionGroupExt createGroup(SipProfile localProfile,
            PendingIntent incomingCallPendingIntent,
            ISipSessionListener listener) throws SipException {
        String key = localProfile.getUriString();
        SipSessionGroupExt group = mSipGroups.get(key);
        if (group != null) {
            if (!isCallerCreator(group)) {
                throw new SipException("only creator can access the profile");
            }
            group.setIncomingCallPendingIntent(incomingCallPendingIntent);
            group.setListener(listener);
        } else {
            group = new SipSessionGroupExt(localProfile,
                    incomingCallPendingIntent, listener);
            mSipGroups.put(key, group);
            notifyProfileAdded(localProfile);
        }
        return group;
    }

    private void notifyProfileAdded(SipProfile localProfile) {
        if (DBG) log("notify: profile added: " + localProfile);
        Intent intent = new Intent(SipManager.ACTION_SIP_ADD_PHONE);
        intent.putExtra(SipManager.EXTRA_LOCAL_URI, localProfile.getUriString());
        mContext.sendBroadcast(intent);
        if (mSipGroups.size() == 1) {
            registerReceivers();
        }
    }

    private void notifyProfileRemoved(SipProfile localProfile) {
        if (DBG) log("notify: profile removed: " + localProfile);
        Intent intent = new Intent(SipManager.ACTION_SIP_REMOVE_PHONE);
        intent.putExtra(SipManager.EXTRA_LOCAL_URI, localProfile.getUriString());
        mContext.sendBroadcast(intent);
        if (mSipGroups.size() == 0) {
            unregisterReceivers();
        }
    }

    private void stopPortMappingMeasurement() {
        if (mSipKeepAliveProcessCallback != null) {
            mSipKeepAliveProcessCallback.stop();
            mSipKeepAliveProcessCallback = null;
        }
    }

    private void startPortMappingLifetimeMeasurement(
            SipProfile localProfile) {
        startPortMappingLifetimeMeasurement(localProfile,
                DEFAULT_MAX_KEEPALIVE_INTERVAL);
    }

    private void startPortMappingLifetimeMeasurement(
            SipProfile localProfile, int maxInterval) {
        if ((mSipKeepAliveProcessCallback == null)
                && (mKeepAliveInterval == -1)
                && isBehindNAT(mLocalIp)) {
            if (DBG) log("startPortMappingLifetimeMeasurement: profile="
                    + localProfile.getUriString());

            int minInterval = mLastGoodKeepAliveInterval;
            if (minInterval >= maxInterval) {
                // If mLastGoodKeepAliveInterval also does not work, reset it
                // to the default min
                minInterval = mLastGoodKeepAliveInterval
                        = DEFAULT_KEEPALIVE_INTERVAL;
                log("  reset min interval to " + minInterval);
            }
            mSipKeepAliveProcessCallback = new SipKeepAliveProcessCallback(
                    localProfile, minInterval, maxInterval);
            mSipKeepAliveProcessCallback.start();
        }
    }

    private void restartPortMappingLifetimeMeasurement(
            SipProfile localProfile, int maxInterval) {
        stopPortMappingMeasurement();
        mKeepAliveInterval = -1;
        startPortMappingLifetimeMeasurement(localProfile, maxInterval);
    }

    private synchronized void addPendingSession(ISipSession session) {
        try {
            cleanUpPendingSessions();
            mPendingSessions.put(session.getCallId(), session);
            if (DBG) log("#pending sess=" + mPendingSessions.size());
        } catch (RemoteException e) {
            // should not happen with a local call
            loge("addPendingSession()", e);
        }
    }

    private void cleanUpPendingSessions() throws RemoteException {
        Map.Entry<String, ISipSession>[] entries =
                mPendingSessions.entrySet().toArray(
                new Map.Entry[mPendingSessions.size()]);
        for (Map.Entry<String, ISipSession> entry : entries) {
            if (entry.getValue().getState() != SipSession.State.INCOMING_CALL) {
                mPendingSessions.remove(entry.getKey());
            }
        }
    }

    private synchronized boolean callingSelf(SipSessionGroupExt ringingGroup,
            SipSessionGroup.SipSessionImpl ringingSession) {
        String callId = ringingSession.getCallId();
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if ((group != ringingGroup) && group.containsSession(callId)) {
                if (DBG) log("call self: "
                        + ringingSession.getLocalProfile().getUriString()
                        + " -> " + group.getLocalProfile().getUriString());
                return true;
            }
        }
        return false;
    }

    private synchronized void onKeepAliveIntervalChanged() {
        for (SipSessionGroupExt group : mSipGroups.values()) {
            group.onKeepAliveIntervalChanged();
        }
    }

    private int getKeepAliveInterval() {
        return (mKeepAliveInterval < 0)
                ? mLastGoodKeepAliveInterval
                : mKeepAliveInterval;
    }

    private boolean isBehindNAT(String address) {
        try {
            // TODO: How is isBehindNAT used and why these constanst address:
            //       10.x.x.x | 192.168.x.x | 172.16.x.x .. 172.19.x.x
            byte[] d = InetAddress.getByName(address).getAddress();
            if ((d[0] == 10) ||
                    (((0x000000FF & d[0]) == 172) &&
                    ((0x000000F0 & d[1]) == 16)) ||
                    (((0x000000FF & d[0]) == 192) &&
                    ((0x000000FF & d[1]) == 168))) {
                return true;
            }
        } catch (UnknownHostException e) {
            loge("isBehindAT()" + address, e);
        }
        return false;
    }

    private class SipSessionGroupExt extends SipSessionAdapter {
        private static final String SSGE_TAG = "SipSessionGroupExt";
        private static final boolean SSGE_DBG = true;
        private SipSessionGroup mSipGroup;
        private PendingIntent mIncomingCallPendingIntent;
        private boolean mOpenedToReceiveCalls;

        private SipAutoReg mAutoRegistration =
                new SipAutoReg();

        public SipSessionGroupExt(SipProfile localProfile,
                PendingIntent incomingCallPendingIntent,
                ISipSessionListener listener) throws SipException {
            if (SSGE_DBG) log("SipSessionGroupExt: profile=" + localProfile);
            mSipGroup = new SipSessionGroup(duplicate(localProfile),
                    localProfile.getPassword(), mTimer, mMyWakeLock);
            mIncomingCallPendingIntent = incomingCallPendingIntent;
            mAutoRegistration.setListener(listener);
        }

        public SipProfile getLocalProfile() {
            return mSipGroup.getLocalProfile();
        }

        public boolean containsSession(String callId) {
            return mSipGroup.containsSession(callId);
        }

        public void onKeepAliveIntervalChanged() {
            mAutoRegistration.onKeepAliveIntervalChanged();
        }

        // TODO: remove this method once SipWakeupTimer can better handle variety
        // of timeout values
        void setWakeupTimer(SipWakeupTimer timer) {
            mSipGroup.setWakeupTimer(timer);
        }

        private SipProfile duplicate(SipProfile p) {
            try {
                return new SipProfile.Builder(p).setPassword("*").build();
            } catch (Exception e) {
                loge("duplicate()", e);
                throw new RuntimeException("duplicate profile", e);
            }
        }

        public void setListener(ISipSessionListener listener) {
            mAutoRegistration.setListener(listener);
        }

        public void setIncomingCallPendingIntent(PendingIntent pIntent) {
            mIncomingCallPendingIntent = pIntent;
        }

        public void openToReceiveCalls() throws SipException {
            mOpenedToReceiveCalls = true;
            if (mNetworkType != -1) {
                mSipGroup.openToReceiveCalls(this);
                mAutoRegistration.start(mSipGroup);
            }
            if (SSGE_DBG) log("openToReceiveCalls: " + getUri() + ": "
                    + mIncomingCallPendingIntent);
        }

        public void onConnectivityChanged(boolean connected)
                throws SipException {
            if (SSGE_DBG) {
                log("onConnectivityChanged: connected=" + connected + " uri="
                    + getUri() + ": " + mIncomingCallPendingIntent);
            }
            mSipGroup.onConnectivityChanged();
            if (connected) {
                mSipGroup.reset();
                if (mOpenedToReceiveCalls) openToReceiveCalls();
            } else {
                mSipGroup.close();
                mAutoRegistration.stop();
            }
        }

        public void close() {
            mOpenedToReceiveCalls = false;
            mSipGroup.close();
            mAutoRegistration.stop();
            if (SSGE_DBG) log("close: " + getUri() + ": " + mIncomingCallPendingIntent);
        }

        public ISipSession createSession(ISipSessionListener listener) {
            if (SSGE_DBG) log("createSession");
            return mSipGroup.createSession(listener);
        }

        @Override
        public void onRinging(ISipSession s, SipProfile caller,
                String sessionDescription) {
            SipSessionGroup.SipSessionImpl session =
                    (SipSessionGroup.SipSessionImpl) s;
            synchronized (SipService.this) {
                try {
                    if (!isRegistered() || callingSelf(this, session)) {
                        if (SSGE_DBG) log("onRinging: end notReg or self");
                        session.endCall();
                        return;
                    }

                    // send out incoming call broadcast
                    addPendingSession(session);
                    Intent intent = SipManager.createIncomingCallBroadcast(
                            session.getCallId(), sessionDescription);
                    if (SSGE_DBG) log("onRinging: uri=" + getUri() + ": "
                            + caller.getUri() + ": " + session.getCallId()
                            + " " + mIncomingCallPendingIntent);
                    mIncomingCallPendingIntent.send(mContext,
                            SipManager.INCOMING_CALL_RESULT_CODE, intent);
                } catch (PendingIntent.CanceledException e) {
                    loge("onRinging: pendingIntent is canceled, drop incoming call", e);
                    session.endCall();
                }
            }
        }

        @Override
        public void onError(ISipSession session, int errorCode,
                String message) {
            if (SSGE_DBG) log("onError: errorCode=" + errorCode + " desc="
                    + SipErrorCode.toString(errorCode) + ": " + message);
        }

        public boolean isOpenedToReceiveCalls() {
            return mOpenedToReceiveCalls;
        }

        public boolean isRegistered() {
            return mAutoRegistration.isRegistered();
        }

        private String getUri() {
            return mSipGroup.getLocalProfileUri();
        }

        private void log(String s) {
            Rlog.d(SSGE_TAG, s);
        }

        private void loge(String s, Throwable t) {
            Rlog.e(SSGE_TAG, s, t);
        }

    }

    private class SipKeepAliveProcessCallback implements Runnable,
            SipSessionGroup.KeepAliveProcessCallback {
        private static final String SKAI_TAG = "SipKeepAliveProcessCallback";
        private static final boolean SKAI_DBG = true;
        private static final int MIN_INTERVAL = 5; // in seconds
        private static final int PASS_THRESHOLD = 10;
        private static final int NAT_MEASUREMENT_RETRY_INTERVAL = 120; // in seconds
        private SipProfile mLocalProfile;
        private SipSessionGroupExt mGroup;
        private SipSessionGroup.SipSessionImpl mSession;
        private int mMinInterval;
        private int mMaxInterval;
        private int mInterval;
        private int mPassCount;

        public SipKeepAliveProcessCallback(SipProfile localProfile,
                int minInterval, int maxInterval) {
            mMaxInterval = maxInterval;
            mMinInterval = minInterval;
            mLocalProfile = localProfile;
        }

        public void start() {
            synchronized (SipService.this) {
                if (mSession != null) {
                    return;
                }

                mInterval = (mMaxInterval + mMinInterval) / 2;
                mPassCount = 0;

                // Don't start measurement if the interval is too small
                if (mInterval < DEFAULT_KEEPALIVE_INTERVAL || checkTermination()) {
                    if (SKAI_DBG) log("start: measurement aborted; interval=[" +
                            mMinInterval + "," + mMaxInterval + "]");
                    return;
                }

                try {
                    if (SKAI_DBG) log("start: interval=" + mInterval);

                    mGroup = new SipSessionGroupExt(mLocalProfile, null, null);
                    // TODO: remove this line once SipWakeupTimer can better handle
                    // variety of timeout values
                    mGroup.setWakeupTimer(new SipWakeupTimer(mContext, mExecutor));

                    mSession = (SipSessionGroup.SipSessionImpl)
                            mGroup.createSession(null);
                    mSession.startKeepAliveProcess(mInterval, this);
                } catch (Throwable t) {
                    onError(SipErrorCode.CLIENT_ERROR, t.toString());
                }
            }
        }

        public void stop() {
            synchronized (SipService.this) {
                if (mSession != null) {
                    mSession.stopKeepAliveProcess();
                    mSession = null;
                }
                if (mGroup != null) {
                    mGroup.close();
                    mGroup = null;
                }
                mTimer.cancel(this);
                if (SKAI_DBG) log("stop");
            }
        }

        private void restart() {
            synchronized (SipService.this) {
                // Return immediately if the measurement process is stopped
                if (mSession == null) return;

                if (SKAI_DBG) log("restart: interval=" + mInterval);
                try {
                    mSession.stopKeepAliveProcess();
                    mPassCount = 0;
                    mSession.startKeepAliveProcess(mInterval, this);
                } catch (SipException e) {
                    loge("restart", e);
                }
            }
        }

        private boolean checkTermination() {
            return ((mMaxInterval - mMinInterval) < MIN_INTERVAL);
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onResponse(boolean portChanged) {
            synchronized (SipService.this) {
                if (!portChanged) {
                    if (++mPassCount != PASS_THRESHOLD) return;
                    // update the interval, since the current interval is good to
                    // keep the port mapping.
                    if (mKeepAliveInterval > 0) {
                        mLastGoodKeepAliveInterval = mKeepAliveInterval;
                    }
                    mKeepAliveInterval = mMinInterval = mInterval;
                    if (SKAI_DBG) {
                        log("onResponse: portChanged=" + portChanged + " mKeepAliveInterval="
                                + mKeepAliveInterval);
                    }
                    onKeepAliveIntervalChanged();
                } else {
                    // Since the rport is changed, shorten the interval.
                    mMaxInterval = mInterval;
                }
                if (checkTermination()) {
                    // update mKeepAliveInterval and stop measurement.
                    stop();
                    // If all the measurements failed, we still set it to
                    // mMinInterval; If mMinInterval still doesn't work, a new
                    // measurement with min interval=DEFAULT_KEEPALIVE_INTERVAL
                    // will be conducted.
                    mKeepAliveInterval = mMinInterval;
                    if (SKAI_DBG) {
                        log("onResponse: checkTermination mKeepAliveInterval="
                                + mKeepAliveInterval);
                    }
                } else {
                    // calculate the new interval and continue.
                    mInterval = (mMaxInterval + mMinInterval) / 2;
                    if (SKAI_DBG) {
                        log("onResponse: mKeepAliveInterval=" + mKeepAliveInterval
                                + ", new mInterval=" + mInterval);
                    }
                    restart();
                }
            }
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onError(int errorCode, String description) {
            if (SKAI_DBG) loge("onError: errorCode=" + errorCode + " desc=" + description);
            restartLater();
        }

        // timeout handler
        @Override
        public void run() {
            mTimer.cancel(this);
            restart();
        }

        private void restartLater() {
            synchronized (SipService.this) {
                int interval = NAT_MEASUREMENT_RETRY_INTERVAL;
                mTimer.cancel(this);
                mTimer.set(interval * 1000, this);
            }
        }

        private void log(String s) {
            Rlog.d(SKAI_TAG, s);
        }

        private void loge(String s) {
            Rlog.d(SKAI_TAG, s);
        }

        private void loge(String s, Throwable t) {
            Rlog.d(SKAI_TAG, s, t);
        }
    }

    private class SipAutoReg extends SipSessionAdapter
            implements Runnable, SipSessionGroup.KeepAliveProcessCallback {
        private String SAR_TAG;
        private static final boolean SAR_DBG = true;
        private static final int MIN_KEEPALIVE_SUCCESS_COUNT = 10;

        private SipSessionGroup.SipSessionImpl mSession;
        private SipSessionGroup.SipSessionImpl mKeepAliveSession;
        private SipSessionListenerProxy mProxy = new SipSessionListenerProxy();
        private int mBackoff = 1;
        private boolean mRegistered;
        private long mExpiryTime;
        private int mErrorCode;
        private String mErrorMessage;
        private boolean mRunning = false;

        private int mKeepAliveSuccessCount = 0;

        public void start(SipSessionGroup group) {
            if (!mRunning) {
                mRunning = true;
                mBackoff = 1;
                mSession = (SipSessionGroup.SipSessionImpl)
                        group.createSession(this);
                // return right away if no active network connection.
                if (mSession == null) return;

                // start unregistration to clear up old registration at server
                // TODO: when rfc5626 is deployed, use reg-id and sip.instance
                // in registration to avoid adding duplicate entries to server
                mMyWakeLock.acquire(mSession);
                mSession.unregister();
                SAR_TAG = "SipAutoReg:" + mSession.getLocalProfile().getUriString();
                if (SAR_DBG) log("start: group=" + group);
            }
        }

        private void startKeepAliveProcess(int interval) {
            if (SAR_DBG) log("startKeepAliveProcess: interval=" + interval);
            if (mKeepAliveSession == null) {
                mKeepAliveSession = mSession.duplicate();
            } else {
                mKeepAliveSession.stopKeepAliveProcess();
            }
            try {
                mKeepAliveSession.startKeepAliveProcess(interval, this);
            } catch (SipException e) {
                loge("startKeepAliveProcess: interval=" + interval, e);
            }
        }

        private void stopKeepAliveProcess() {
            if (mKeepAliveSession != null) {
                mKeepAliveSession.stopKeepAliveProcess();
                mKeepAliveSession = null;
            }
            mKeepAliveSuccessCount = 0;
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onResponse(boolean portChanged) {
            synchronized (SipService.this) {
                if (portChanged) {
                    int interval = getKeepAliveInterval();
                    if (mKeepAliveSuccessCount < MIN_KEEPALIVE_SUCCESS_COUNT) {
                        if (SAR_DBG) {
                            log("onResponse: keepalive doesn't work with interval "
                                    + interval + ", past success count="
                                    + mKeepAliveSuccessCount);
                        }
                        if (interval > DEFAULT_KEEPALIVE_INTERVAL) {
                            restartPortMappingLifetimeMeasurement(
                                    mSession.getLocalProfile(), interval);
                            mKeepAliveSuccessCount = 0;
                        }
                    } else {
                        if (SAR_DBG) {
                            log("keep keepalive going with interval "
                                    + interval + ", past success count="
                                    + mKeepAliveSuccessCount);
                        }
                        mKeepAliveSuccessCount /= 2;
                    }
                } else {
                    // Start keep-alive interval measurement on the first
                    // successfully kept-alive SipSessionGroup
                    startPortMappingLifetimeMeasurement(
                            mSession.getLocalProfile());
                    mKeepAliveSuccessCount++;
                }

                if (!mRunning || !portChanged) return;

                // The keep alive process is stopped when port is changed;
                // Nullify the session so that the process can be restarted
                // again when the re-registration is done
                mKeepAliveSession = null;

                // Acquire wake lock for the registration process. The
                // lock will be released when registration is complete.
                mMyWakeLock.acquire(mSession);
                mSession.register(EXPIRY_TIME);
            }
        }

        // SipSessionGroup.KeepAliveProcessCallback
        @Override
        public void onError(int errorCode, String description) {
            if (SAR_DBG) {
                loge("onError: errorCode=" + errorCode + " desc=" + description);
            }
            onResponse(true); // re-register immediately
        }

        public void stop() {
            if (!mRunning) return;
            mRunning = false;
            mMyWakeLock.release(mSession);
            if (mSession != null) {
                mSession.setListener(null);
                if (mNetworkType != -1 && mRegistered) mSession.unregister();
            }

            mTimer.cancel(this);
            stopKeepAliveProcess();

            mRegistered = false;
            setListener(mProxy.getListener());
        }

        public void onKeepAliveIntervalChanged() {
            if (mKeepAliveSession != null) {
                int newInterval = getKeepAliveInterval();
                if (SAR_DBG) {
                    log("onKeepAliveIntervalChanged: interval=" + newInterval);
                }
                mKeepAliveSuccessCount = 0;
                startKeepAliveProcess(newInterval);
            }
        }

        public void setListener(ISipSessionListener listener) {
            synchronized (SipService.this) {
                mProxy.setListener(listener);

                try {
                    int state = (mSession == null)
                            ? SipSession.State.READY_TO_CALL
                            : mSession.getState();
                    if ((state == SipSession.State.REGISTERING)
                            || (state == SipSession.State.DEREGISTERING)) {
                        mProxy.onRegistering(mSession);
                    } else if (mRegistered) {
                        int duration = (int)
                                (mExpiryTime - SystemClock.elapsedRealtime());
                        mProxy.onRegistrationDone(mSession, duration);
                    } else if (mErrorCode != SipErrorCode.NO_ERROR) {
                        if (mErrorCode == SipErrorCode.TIME_OUT) {
                            mProxy.onRegistrationTimeout(mSession);
                        } else {
                            mProxy.onRegistrationFailed(mSession, mErrorCode,
                                    mErrorMessage);
                        }
                    } else if (mNetworkType == -1) {
                        mProxy.onRegistrationFailed(mSession,
                                SipErrorCode.DATA_CONNECTION_LOST,
                                "no data connection");
                    } else if (!mRunning) {
                        mProxy.onRegistrationFailed(mSession,
                                SipErrorCode.CLIENT_ERROR,
                                "registration not running");
                    } else {
                        mProxy.onRegistrationFailed(mSession,
                                SipErrorCode.IN_PROGRESS,
                                String.valueOf(state));
                    }
                } catch (Throwable t) {
                    loge("setListener: ", t);
                }
            }
        }

        public boolean isRegistered() {
            return mRegistered;
        }

        // timeout handler: re-register
        @Override
        public void run() {
            synchronized (SipService.this) {
                if (!mRunning) return;

                mErrorCode = SipErrorCode.NO_ERROR;
                mErrorMessage = null;
                if (SAR_DBG) log("run: registering");
                if (mNetworkType != -1) {
                    mMyWakeLock.acquire(mSession);
                    mSession.register(EXPIRY_TIME);
                }
            }
        }

        private void restart(int duration) {
            if (SAR_DBG) log("restart: duration=" + duration + "s later.");
            mTimer.cancel(this);
            mTimer.set(duration * 1000, this);
        }

        private int backoffDuration() {
            int duration = SHORT_EXPIRY_TIME * mBackoff;
            if (duration > 3600) {
                duration = 3600;
            } else {
                mBackoff *= 2;
            }
            return duration;
        }

        @Override
        public void onRegistering(ISipSession session) {
            if (SAR_DBG) log("onRegistering: " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mRegistered = false;
                mProxy.onRegistering(session);
            }
        }

        private boolean notCurrentSession(ISipSession session) {
            if (session != mSession) {
                ((SipSessionGroup.SipSessionImpl) session).setListener(null);
                mMyWakeLock.release(session);
                return true;
            }
            return !mRunning;
        }

        @Override
        public void onRegistrationDone(ISipSession session, int duration) {
            if (SAR_DBG) log("onRegistrationDone: " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mProxy.onRegistrationDone(session, duration);

                if (duration > 0) {
                    mExpiryTime = SystemClock.elapsedRealtime()
                            + (duration * 1000);

                    if (!mRegistered) {
                        mRegistered = true;
                        // allow some overlap to avoid call drop during renew
                        duration -= MIN_EXPIRY_TIME;
                        if (duration < MIN_EXPIRY_TIME) {
                            duration = MIN_EXPIRY_TIME;
                        }
                        restart(duration);

                        SipProfile localProfile = mSession.getLocalProfile();
                        if ((mKeepAliveSession == null) && (isBehindNAT(mLocalIp)
                                || localProfile.getSendKeepAlive())) {
                            startKeepAliveProcess(getKeepAliveInterval());
                        }
                    }
                    mMyWakeLock.release(session);
                } else {
                    mRegistered = false;
                    mExpiryTime = -1L;
                    if (SAR_DBG) log("Refresh registration immediately");
                    run();
                }
            }
        }

        @Override
        public void onRegistrationFailed(ISipSession session, int errorCode,
                String message) {
            if (SAR_DBG) log("onRegistrationFailed: " + session + ": "
                    + SipErrorCode.toString(errorCode) + ": " + message);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                switch (errorCode) {
                    case SipErrorCode.INVALID_CREDENTIALS:
                    case SipErrorCode.SERVER_UNREACHABLE:
                        if (SAR_DBG) log("   pause auto-registration");
                        stop();
                        break;
                    default:
                        restartLater();
                }

                mErrorCode = errorCode;
                mErrorMessage = message;
                mProxy.onRegistrationFailed(session, errorCode, message);
                mMyWakeLock.release(session);
            }
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            if (SAR_DBG) log("onRegistrationTimeout: " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mErrorCode = SipErrorCode.TIME_OUT;
                mProxy.onRegistrationTimeout(session);
                restartLater();
                mMyWakeLock.release(session);
            }
        }

        private void restartLater() {
            if (SAR_DBG) loge("restartLater");
            mRegistered = false;
            restart(backoffDuration());
        }

        private void log(String s) {
            Rlog.d(SAR_TAG, s);
        }

        private void loge(String s) {
            Rlog.e(SAR_TAG, s);
        }

        private void loge(String s, Throwable e) {
            Rlog.e(SAR_TAG, s, e);
        }
    }

    private class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle bundle = intent.getExtras();
            if (bundle != null) {
                final NetworkInfo info = (NetworkInfo)
                        bundle.get(ConnectivityManager.EXTRA_NETWORK_INFO);

                // Run the handler in MyExecutor to be protected by wake lock
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        onConnectivityChanged(info);
                    }
                });
            }
        }
    }

    private void registerReceivers() {
        mContext.registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        if (DBG) log("registerReceivers:");
    }

    private void unregisterReceivers() {
        mContext.unregisterReceiver(mConnectivityReceiver);
        if (DBG) log("unregisterReceivers:");

        // Reset variables maintained by ConnectivityReceiver.
        mWifiLock.release();
        mNetworkType = -1;
    }

    private void updateWakeLocks() {
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if (group.isOpenedToReceiveCalls()) {
                // Also grab the WifiLock when we are disconnected, so the
                // system will keep trying to reconnect. It will be released
                // when the system eventually connects to something else.
                if (mNetworkType == ConnectivityManager.TYPE_WIFI || mNetworkType == -1) {
                    mWifiLock.acquire();
                } else {
                    mWifiLock.release();
                }
                return;
            }
        }
        mWifiLock.release();
        mMyWakeLock.reset(); // in case there's a leak
    }

    private synchronized void onConnectivityChanged(NetworkInfo info) {
        // We only care about the default network, and getActiveNetworkInfo()
        // is the only way to distinguish them. However, as broadcasts are
        // delivered asynchronously, we might miss DISCONNECTED events from
        // getActiveNetworkInfo(), which is critical to our SIP stack. To
        // solve this, if it is a DISCONNECTED event to our current network,
        // respect it. Otherwise get a new one from getActiveNetworkInfo().
        if (info == null || info.isConnected() || info.getType() != mNetworkType) {
            ConnectivityManager cm = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            info = cm.getActiveNetworkInfo();
        }

        // Some devices limit SIP on Wi-Fi. In this case, if we are not on
        // Wi-Fi, treat it as a DISCONNECTED event.
        int networkType = (info != null && info.isConnected()) ? info.getType() : -1;
        if (mSipOnWifiOnly && networkType != ConnectivityManager.TYPE_WIFI) {
            networkType = -1;
        }

        // Ignore the event if the current active network is not changed.
        if (mNetworkType == networkType) {
            // TODO: Maybe we need to send seq/generation number
            return;
        }
        if (DBG) {
            log("onConnectivityChanged: " + mNetworkType +
                    " -> " + networkType);
        }

        try {
            if (mNetworkType != -1) {
                mLocalIp = null;
                stopPortMappingMeasurement();
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(false);
                }
            }
            mNetworkType = networkType;

            if (mNetworkType != -1) {
                mLocalIp = determineLocalIp();
                mKeepAliveInterval = -1;
                mLastGoodKeepAliveInterval = DEFAULT_KEEPALIVE_INTERVAL;
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(true);
                }
            }
            updateWakeLocks();
        } catch (SipException e) {
            loge("onConnectivityChanged()", e);
        }
    }

    private static Looper createLooper() {
        HandlerThread thread = new HandlerThread("SipService.Executor");
        thread.start();
        return thread.getLooper();
    }

    // Executes immediate tasks in a single thread.
    // Hold/release wake lock for running tasks
    private class MyExecutor extends Handler implements Executor {
        MyExecutor() {
            super(createLooper());
        }

        @Override
        public void execute(Runnable task) {
            mMyWakeLock.acquire(task);
            Message.obtain(this, 0/* don't care */, task).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof Runnable) {
                executeInternal((Runnable) msg.obj);
            } else {
                if (DBG) log("handleMessage: not Runnable ignore msg=" + msg);
            }
        }

        private void executeInternal(Runnable task) {
            try {
                task.run();
            } catch (Throwable t) {
                loge("run task: " + task, t);
            } finally {
                mMyWakeLock.release(task);
            }
        }
    }

    private void log(String s) {
        Rlog.d(TAG, s);
    }

    private static void slog(String s) {
        Rlog.d(TAG, s);
    }

    private void loge(String s, Throwable e) {
        Rlog.e(TAG, s, e);
    }
}
