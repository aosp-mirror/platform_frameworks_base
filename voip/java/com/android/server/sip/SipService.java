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

import android.app.AlarmManager;
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
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;
import javax.sip.SipException;

/**
 * @hide
 */
public final class SipService extends ISipService.Stub {
    static final String TAG = "SipService";
    static final boolean DEBUGV = false;
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_TIMER = DEBUG && false;
    private static final int EXPIRY_TIME = 3600;
    private static final int SHORT_EXPIRY_TIME = 10;
    private static final int MIN_EXPIRY_TIME = 60;

    private Context mContext;
    private String mLocalIp;
    private String mNetworkType;
    private boolean mConnected;
    private WakeupTimer mTimer;
    private WifiScanProcess mWifiScanProcess;
    private WifiManager.WifiLock mWifiLock;
    private boolean mWifiOnly;

    private MyExecutor mExecutor;

    // SipProfile URI --> group
    private Map<String, SipSessionGroupExt> mSipGroups =
            new HashMap<String, SipSessionGroupExt>();

    // session ID --> session
    private Map<String, ISipSession> mPendingSessions =
            new HashMap<String, ISipSession>();

    private ConnectivityReceiver mConnectivityReceiver;
    private boolean mWifiEnabled;
    private SipWakeLock mMyWakeLock;

    /**
     * Starts the SIP service. Do nothing if the SIP API is not supported on the
     * device.
     */
    public static void start(Context context) {
        if (SipManager.isApiSupported(context)) {
            ServiceManager.addService("sip", new SipService(context));
            context.sendBroadcast(new Intent(SipManager.ACTION_SIP_SERVICE_UP));
            if (DEBUG) Log.d(TAG, "SIP service started");
        }
    }

    private SipService(Context context) {
        if (DEBUG) Log.d(TAG, " service started!");
        mContext = context;
        mConnectivityReceiver = new ConnectivityReceiver();
        mMyWakeLock = new SipWakeLock((PowerManager)
                context.getSystemService(Context.POWER_SERVICE));

        mTimer = new WakeupTimer(context);
        mWifiOnly = SipManager.isSipWifiOnly(context);
    }

    private BroadcastReceiver mWifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                synchronized (SipService.this) {
                    switch (state) {
                        case WifiManager.WIFI_STATE_ENABLED:
                            mWifiEnabled = true;
                            if (anyOpenedToReceiveCalls()) grabWifiLock();
                            break;
                        case WifiManager.WIFI_STATE_DISABLED:
                            mWifiEnabled = false;
                            releaseWifiLock();
                            break;
                    }
                }
            }
        }
    };

    private void registerReceivers() {
        mContext.registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        mContext.registerReceiver(mWifiStateReceiver,
                new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        if (DEBUG) Log.d(TAG, " +++ register receivers");
    }

    private void unregisterReceivers() {
        mContext.unregisterReceiver(mConnectivityReceiver);
        mContext.unregisterReceiver(mWifiStateReceiver);
        if (DEBUG) Log.d(TAG, " --- unregister receivers");
    }

    private MyExecutor getExecutor() {
        // create mExecutor lazily
        if (mExecutor == null) mExecutor = new MyExecutor();
        return mExecutor;
    }

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

    public synchronized void open(SipProfile localProfile) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        try {
            boolean addingFirstProfile = mSipGroups.isEmpty();
            createGroup(localProfile);
            if (addingFirstProfile && !mSipGroups.isEmpty()) registerReceivers();
        } catch (SipException e) {
            Log.e(TAG, "openToMakeCalls()", e);
            // TODO: how to send the exception back
        }
    }

    public synchronized void open3(SipProfile localProfile,
            PendingIntent incomingCallPendingIntent,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        if (incomingCallPendingIntent == null) {
            Log.w(TAG, "incomingCallPendingIntent cannot be null; "
                    + "the profile is not opened");
            return;
        }
        if (DEBUG) Log.d(TAG, "open3: " + localProfile.getUriString() + ": "
                + incomingCallPendingIntent + ": " + listener);
        try {
            boolean addingFirstProfile = mSipGroups.isEmpty();
            SipSessionGroupExt group = createGroup(localProfile,
                    incomingCallPendingIntent, listener);
            if (addingFirstProfile && !mSipGroups.isEmpty()) registerReceivers();
            if (localProfile.getAutoRegistration()) {
                group.openToReceiveCalls();
                if (mWifiEnabled) grabWifiLock();
            }
        } catch (SipException e) {
            Log.e(TAG, "openToReceiveCalls()", e);
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

    public synchronized void close(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return;
        if (!isCallerCreatorOrRadio(group)) {
            Log.w(TAG, "only creator or radio can close this profile");
            return;
        }

        group = mSipGroups.remove(localProfileUri);
        notifyProfileRemoved(group.getLocalProfile());
        group.close();

        if (!anyOpenedToReceiveCalls()) {
            releaseWifiLock();
            mMyWakeLock.reset(); // in case there's leak
        }
        if (mSipGroups.isEmpty()) unregisterReceivers();
    }

    public synchronized boolean isOpened(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return false;
        if (isCallerCreatorOrRadio(group)) {
            return true;
        } else {
            Log.w(TAG, "only creator or radio can query on the profile");
            return false;
        }
    }

    public synchronized boolean isRegistered(String localProfileUri) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return false;
        if (isCallerCreatorOrRadio(group)) {
            return group.isRegistered();
        } else {
            Log.w(TAG, "only creator or radio can query on the profile");
            return false;
        }
    }

    public synchronized void setRegistrationListener(String localProfileUri,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group == null) return;
        if (isCallerCreator(group)) {
            group.setListener(listener);
        } else {
            Log.w(TAG, "only creator can set listener on the profile");
        }
    }

    public synchronized ISipSession createSession(SipProfile localProfile,
            ISipSessionListener listener) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.USE_SIP, null);
        localProfile.setCallingUid(Binder.getCallingUid());
        if (!mConnected) return null;
        try {
            SipSessionGroupExt group = createGroup(localProfile);
            return group.createSession(listener);
        } catch (SipException e) {
            if (DEBUG) Log.d(TAG, "createSession()", e);
            return null;
        }
    }

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
            if (DEBUG) Log.d(TAG, "determineLocalIp()", e);
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
        if (DEBUG) Log.d(TAG, "notify: profile added: " + localProfile);
        Intent intent = new Intent(SipManager.ACTION_SIP_ADD_PHONE);
        intent.putExtra(SipManager.EXTRA_LOCAL_URI, localProfile.getUriString());
        mContext.sendBroadcast(intent);
    }

    private void notifyProfileRemoved(SipProfile localProfile) {
        if (DEBUG) Log.d(TAG, "notify: profile removed: " + localProfile);
        Intent intent = new Intent(SipManager.ACTION_SIP_REMOVE_PHONE);
        intent.putExtra(SipManager.EXTRA_LOCAL_URI, localProfile.getUriString());
        mContext.sendBroadcast(intent);
    }

    private boolean anyOpenedToReceiveCalls() {
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if (group.isOpenedToReceiveCalls()) return true;
        }
        return false;
    }

    private void grabWifiLock() {
        if (mWifiLock == null) {
            if (DEBUG) Log.d(TAG, "~~~~~~~~~~~~~~~~~~~~~ acquire wifi lock");
            mWifiLock = ((WifiManager)
                    mContext.getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);
            mWifiLock.acquire();
            if (!mConnected) startWifiScanner();
        }
    }

    private void releaseWifiLock() {
        if (mWifiLock != null) {
            if (DEBUG) Log.d(TAG, "~~~~~~~~~~~~~~~~~~~~~ release wifi lock");
            mWifiLock.release();
            mWifiLock = null;
            stopWifiScanner();
        }
    }

    private synchronized void startWifiScanner() {
        if (mWifiScanProcess == null) {
            mWifiScanProcess = new WifiScanProcess();
        }
        mWifiScanProcess.start();
    }

    private synchronized void stopWifiScanner() {
        if (mWifiScanProcess != null) {
            mWifiScanProcess.stop();
        }
    }

    private synchronized void onConnectivityChanged(
            String type, boolean connected) {
        if (DEBUG) Log.d(TAG, "onConnectivityChanged(): "
                + mNetworkType + (mConnected? " CONNECTED" : " DISCONNECTED")
                + " --> " + type + (connected? " CONNECTED" : " DISCONNECTED"));

        boolean sameType = type.equals(mNetworkType);
        if (!sameType && !connected) return;

        boolean wasWifi = "WIFI".equalsIgnoreCase(mNetworkType);
        boolean isWifi = "WIFI".equalsIgnoreCase(type);
        boolean wifiOff = (isWifi && !connected) || (wasWifi && !sameType);
        boolean wifiOn = isWifi && connected;

        try {
            boolean wasConnected = mConnected;
            mNetworkType = type;
            mConnected = connected;

            if (wasConnected) {
                mLocalIp = null;
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(false);
                }
            }

            if (connected) {
                mLocalIp = determineLocalIp();
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(true);
                }
                if (isWifi && (mWifiLock != null)) stopWifiScanner();
            } else {
                mMyWakeLock.reset(); // in case there's a leak
                if (isWifi && (mWifiLock != null)) startWifiScanner();
            }
        } catch (SipException e) {
            Log.e(TAG, "onConnectivityChanged()", e);
        }
    }

    private synchronized void addPendingSession(ISipSession session) {
        try {
            cleanUpPendingSessions();
            mPendingSessions.put(session.getCallId(), session);
            if (DEBUG) Log.d(TAG, "#pending sess=" + mPendingSessions.size());
        } catch (RemoteException e) {
            // should not happen with a local call
            Log.e(TAG, "addPendingSession()", e);
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
                if (DEBUG) Log.d(TAG, "call self: "
                        + ringingSession.getLocalProfile().getUriString()
                        + " -> " + group.getLocalProfile().getUriString());
                return true;
            }
        }
        return false;
    }


    private class SipSessionGroupExt extends SipSessionAdapter {
        private SipSessionGroup mSipGroup;
        private PendingIntent mIncomingCallPendingIntent;
        private boolean mOpenedToReceiveCalls;

        private AutoRegistrationProcess mAutoRegistration =
                new AutoRegistrationProcess();

        public SipSessionGroupExt(SipProfile localProfile,
                PendingIntent incomingCallPendingIntent,
                ISipSessionListener listener) throws SipException {
            String password = localProfile.getPassword();
            SipProfile p = duplicate(localProfile);
            mSipGroup = createSipSessionGroup(mLocalIp, p, password);
            mIncomingCallPendingIntent = incomingCallPendingIntent;
            mAutoRegistration.setListener(listener);
        }

        public SipProfile getLocalProfile() {
            return mSipGroup.getLocalProfile();
        }

        public boolean containsSession(String callId) {
            return mSipGroup.containsSession(callId);
        }

        // network connectivity is tricky because network can be disconnected
        // at any instant so need to deal with exceptions carefully even when
        // you think you are connected
        private SipSessionGroup createSipSessionGroup(String localIp,
                SipProfile localProfile, String password) throws SipException {
            try {
                return new SipSessionGroup(localIp, localProfile, password,
                        mMyWakeLock);
            } catch (IOException e) {
                // network disconnected
                Log.w(TAG, "createSipSessionGroup(): network disconnected?");
                if (localIp != null) {
                    return createSipSessionGroup(null, localProfile, password);
                } else {
                    // recursive
                    Log.wtf(TAG, "impossible! recursive!");
                    throw new RuntimeException("createSipSessionGroup");
                }
            }
        }

        private SipProfile duplicate(SipProfile p) {
            try {
                return new SipProfile.Builder(p).setPassword("*").build();
            } catch (Exception e) {
                Log.wtf(TAG, "duplicate()", e);
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
            if (mConnected) {
                mSipGroup.openToReceiveCalls(this);
                mAutoRegistration.start(mSipGroup);
            }
            if (DEBUG) Log.d(TAG, "  openToReceiveCalls: " + getUri() + ": "
                    + mIncomingCallPendingIntent);
        }

        public void onConnectivityChanged(boolean connected)
                throws SipException {
            mSipGroup.onConnectivityChanged();
            if (connected) {
                resetGroup(mLocalIp);
                if (mOpenedToReceiveCalls) openToReceiveCalls();
            } else {
                // close mSipGroup but remember mOpenedToReceiveCalls
                if (DEBUG) Log.d(TAG, "  close auto reg temporarily: "
                        + getUri() + ": " + mIncomingCallPendingIntent);
                mSipGroup.close();
                mAutoRegistration.stop();
            }
        }

        private void resetGroup(String localIp) throws SipException {
            try {
                mSipGroup.reset(localIp);
            } catch (IOException e) {
                // network disconnected
                Log.w(TAG, "resetGroup(): network disconnected?");
                if (localIp != null) {
                    resetGroup(null); // reset w/o local IP
                } else {
                    // recursive
                    Log.wtf(TAG, "impossible!");
                    throw new RuntimeException("resetGroup");
                }
            }
        }

        public void close() {
            mOpenedToReceiveCalls = false;
            mSipGroup.close();
            mAutoRegistration.stop();
            if (DEBUG) Log.d(TAG, "   close: " + getUri() + ": "
                    + mIncomingCallPendingIntent);
        }

        public ISipSession createSession(ISipSessionListener listener) {
            return mSipGroup.createSession(listener);
        }

        @Override
        public void onRinging(ISipSession s, SipProfile caller,
                String sessionDescription) {
            if (DEBUGV) Log.d(TAG, "<<<<< onRinging()");
            SipSessionGroup.SipSessionImpl session =
                    (SipSessionGroup.SipSessionImpl) s;
            synchronized (SipService.this) {
                try {
                    if (!isRegistered() || callingSelf(this, session)) {
                        session.endCall();
                        return;
                    }

                    // send out incoming call broadcast
                    addPendingSession(session);
                    Intent intent = SipManager.createIncomingCallBroadcast(
                            session.getCallId(), sessionDescription);
                    if (DEBUG) Log.d(TAG, " ringing~~ " + getUri() + ": "
                            + caller.getUri() + ": " + session.getCallId()
                            + " " + mIncomingCallPendingIntent);
                    mIncomingCallPendingIntent.send(mContext,
                            SipManager.INCOMING_CALL_RESULT_CODE, intent);
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "pendingIntent is canceled, drop incoming call");
                    session.endCall();
                }
            }
        }

        @Override
        public void onError(ISipSession session, int errorCode,
                String message) {
            if (DEBUG) Log.d(TAG, "sip session error: "
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
    }

    private class WifiScanProcess implements Runnable {
        private static final String TAG = "\\WIFI_SCAN/";
        private static final int INTERVAL = 60;
        private boolean mRunning = false;

        private WifiManager mWifiManager;

        public void start() {
            if (mRunning) return;
            mRunning = true;
            mTimer.set(INTERVAL * 1000, this);
        }

        WifiScanProcess() {
            mWifiManager = (WifiManager)
                    mContext.getSystemService(Context.WIFI_SERVICE);
        }

        public void run() {
            // scan and associate now
            if (DEBUGV) Log.v(TAG, "just wake up here for wifi scanning...");
            mWifiManager.startScanActive();
        }

        public void stop() {
            mRunning = false;
            mTimer.cancel(this);
        }
    }

    // KeepAliveProcess is controlled by AutoRegistrationProcess.
    // All methods will be invoked in sync with SipService.this.
    private class KeepAliveProcess implements Runnable {
        private static final String TAG = "\\KEEPALIVE/";
        private static final int INTERVAL = 10;
        private SipSessionGroup.SipSessionImpl mSession;
        private boolean mRunning = false;

        public KeepAliveProcess(SipSessionGroup.SipSessionImpl session) {
            mSession = session;
        }

        public void start() {
            if (mRunning) return;
            mRunning = true;
            mTimer.set(INTERVAL * 1000, this);
        }

        // timeout handler
        public void run() {
            synchronized (SipService.this) {
                if (!mRunning) return;

                if (DEBUGV) Log.v(TAG, "~~~ keepalive: "
                        + mSession.getLocalProfile().getUriString());
                SipSessionGroup.SipSessionImpl session = mSession.duplicate();
                try {
                    session.sendKeepAlive();
                    if (session.isReRegisterRequired()) {
                        // Acquire wake lock for the registration process. The
                        // lock will be released when registration is complete.
                        mMyWakeLock.acquire(mSession);
                        mSession.register(EXPIRY_TIME);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "keepalive error: " + t);
                }
            }
        }

        public void stop() {
            if (DEBUGV && (mSession != null)) Log.v(TAG, "stop keepalive:"
                    + mSession.getLocalProfile().getUriString());
            mRunning = false;
            mSession = null;
            mTimer.cancel(this);
        }
    }

    private class AutoRegistrationProcess extends SipSessionAdapter
            implements Runnable {
        private SipSessionGroup.SipSessionImpl mSession;
        private SipSessionListenerProxy mProxy = new SipSessionListenerProxy();
        private KeepAliveProcess mKeepAliveProcess;
        private int mBackoff = 1;
        private boolean mRegistered;
        private long mExpiryTime;
        private int mErrorCode;
        private String mErrorMessage;
        private boolean mRunning = false;

        private String getAction() {
            return toString();
        }

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
                if (DEBUG) Log.d(TAG, "start AutoRegistrationProcess for "
                        + mSession.getLocalProfile().getUriString());
            }
        }

        public void stop() {
            if (!mRunning) return;
            mRunning = false;
            mMyWakeLock.release(mSession);
            if (mSession != null) {
                mSession.setListener(null);
                if (mConnected && mRegistered) mSession.unregister();
            }

            mTimer.cancel(this);
            if (mKeepAliveProcess != null) {
                mKeepAliveProcess.stop();
                mKeepAliveProcess = null;
            }

            mRegistered = false;
            setListener(mProxy.getListener());
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
                    } else if (!mConnected) {
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
                    Log.w(TAG, "setListener(): " + t);
                }
            }
        }

        public boolean isRegistered() {
            return mRegistered;
        }

        // timeout handler: re-register
        public void run() {
            synchronized (SipService.this) {
                if (!mRunning) return;

                mErrorCode = SipErrorCode.NO_ERROR;
                mErrorMessage = null;
                if (DEBUG) Log.d(TAG, "~~~ registering");
                if (mConnected) {
                    mMyWakeLock.acquire(mSession);
                    mSession.register(EXPIRY_TIME);
                }
            }
        }

        private boolean isBehindNAT(String address) {
            try {
                byte[] d = InetAddress.getByName(address).getAddress();
                if ((d[0] == 10) ||
                        (((0x000000FF & ((int)d[0])) == 172) &&
                        ((0x000000F0 & ((int)d[1])) == 16)) ||
                        (((0x000000FF & ((int)d[0])) == 192) &&
                        ((0x000000FF & ((int)d[1])) == 168))) {
                    return true;
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "isBehindAT()" + address, e);
            }
            return false;
        }

        private void restart(int duration) {
            if (DEBUG) Log.d(TAG, "Refresh registration " + duration + "s later.");
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
            if (DEBUG) Log.d(TAG, "onRegistering(): " + session);
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
            if (DEBUG) Log.d(TAG, "onRegistrationDone(): " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mProxy.onRegistrationDone(session, duration);

                if (duration > 0) {
                    mSession.clearReRegisterRequired();
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

                        if (isBehindNAT(mLocalIp) ||
                                mSession.getLocalProfile().getSendKeepAlive()) {
                            if (mKeepAliveProcess == null) {
                                mKeepAliveProcess =
                                        new KeepAliveProcess(mSession);
                            }
                            mKeepAliveProcess.start();
                        }
                    }
                    mMyWakeLock.release(session);
                } else {
                    mRegistered = false;
                    mExpiryTime = -1L;
                    if (DEBUG) Log.d(TAG, "Refresh registration immediately");
                    run();
                }
            }
        }

        @Override
        public void onRegistrationFailed(ISipSession session, int errorCode,
                String message) {
            if (DEBUG) Log.d(TAG, "onRegistrationFailed(): " + session + ": "
                    + SipErrorCode.toString(errorCode) + ": " + message);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                switch (errorCode) {
                    case SipErrorCode.INVALID_CREDENTIALS:
                    case SipErrorCode.SERVER_UNREACHABLE:
                        if (DEBUG) Log.d(TAG, "   pause auto-registration");
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
            if (DEBUG) Log.d(TAG, "onRegistrationTimeout(): " + session);
            synchronized (SipService.this) {
                if (notCurrentSession(session)) return;

                mErrorCode = SipErrorCode.TIME_OUT;
                mProxy.onRegistrationTimeout(session);
                restartLater();
                mMyWakeLock.release(session);
            }
        }

        private void restartLater() {
            mRegistered = false;
            restart(backoffDuration());
            if (mKeepAliveProcess != null) {
                mKeepAliveProcess.stop();
                mKeepAliveProcess = null;
            }
        }
    }

    private class ConnectivityReceiver extends BroadcastReceiver {
        private Timer mTimer = new Timer();
        private MyTimerTask mTask;

        @Override
        public void onReceive(final Context context, final Intent intent) {
            // Run the handler in MyExecutor to be protected by wake lock
            getExecutor().execute(new Runnable() {
                public void run() {
                    onReceiveInternal(context, intent);
                }
            });
        }

        private void onReceiveInternal(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Bundle b = intent.getExtras();
                if (b != null) {
                    NetworkInfo netInfo = (NetworkInfo)
                            b.get(ConnectivityManager.EXTRA_NETWORK_INFO);
                    String type = netInfo.getTypeName();
                    NetworkInfo.State state = netInfo.getState();

                    if (mWifiOnly && (netInfo.getType() !=
                            ConnectivityManager.TYPE_WIFI)) {
                        if (DEBUG) {
                            Log.d(TAG, "Wifi only, other connectivity ignored: "
                                    + type);
                        }
                        return;
                    }

                    NetworkInfo activeNetInfo = getActiveNetworkInfo();
                    if (DEBUG) {
                        if (activeNetInfo != null) {
                            Log.d(TAG, "active network: "
                                    + activeNetInfo.getTypeName()
                                    + ((activeNetInfo.getState() == NetworkInfo.State.CONNECTED)
                                            ? " CONNECTED" : " DISCONNECTED"));
                        } else {
                            Log.d(TAG, "active network: null");
                        }
                    }
                    if ((state == NetworkInfo.State.CONNECTED)
                            && (activeNetInfo != null)
                            && (activeNetInfo.getType() != netInfo.getType())) {
                        if (DEBUG) Log.d(TAG, "ignore connect event: " + type
                                + ", active: " + activeNetInfo.getTypeName());
                        return;
                    }

                    if (state == NetworkInfo.State.CONNECTED) {
                        if (DEBUG) Log.d(TAG, "Connectivity alert: CONNECTED " + type);
                        onChanged(type, true);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        if (DEBUG) Log.d(TAG, "Connectivity alert: DISCONNECTED " + type);
                        onChanged(type, false);
                    } else {
                        if (DEBUG) Log.d(TAG, "Connectivity alert not processed: "
                                + state + " " + type);
                    }
                }
            }
        }

        private NetworkInfo getActiveNetworkInfo() {
            ConnectivityManager cm = (ConnectivityManager)
                    mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.getActiveNetworkInfo();
        }

        private void onChanged(String type, boolean connected) {
            synchronized (SipService.this) {
                // When turning on WIFI, it needs some time for network
                // connectivity to get stabile so we defer good news (because
                // we want to skip the interim ones) but deliver bad news
                // immediately
                if (connected) {
                    if (mTask != null) {
                        mTask.cancel();
                        mMyWakeLock.release(mTask);
                    }
                    mTask = new MyTimerTask(type, connected);
                    mTimer.schedule(mTask, 2 * 1000L);
                    // hold wakup lock so that we can finish changes before the
                    // device goes to sleep
                    mMyWakeLock.acquire(mTask);
                } else {
                    if ((mTask != null) && mTask.mNetworkType.equals(type)) {
                        mTask.cancel();
                        mMyWakeLock.release(mTask);
                    }
                    onConnectivityChanged(type, false);
                }
            }
        }

        private class MyTimerTask extends TimerTask {
            private boolean mConnected;
            private String mNetworkType;

            public MyTimerTask(String type, boolean connected) {
                mNetworkType = type;
                mConnected = connected;
            }

            // timeout handler
            @Override
            public void run() {
                // delegate to mExecutor
                getExecutor().execute(new Runnable() {
                    public void run() {
                        realRun();
                    }
                });
            }

            private void realRun() {
                synchronized (SipService.this) {
                    if (mTask != this) {
                        Log.w(TAG, "  unexpected task: " + mNetworkType
                                + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                        mMyWakeLock.release(this);
                        return;
                    }
                    mTask = null;
                    if (DEBUG) Log.d(TAG, " deliver change for " + mNetworkType
                            + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                    onConnectivityChanged(mNetworkType, mConnected);
                    mMyWakeLock.release(this);
                }
            }
        }
    }

    /**
     * Timer that can schedule events to occur even when the device is in sleep.
     * Only used internally in this package.
     */
    class WakeupTimer extends BroadcastReceiver {
        private static final String TAG = "_SIP.WkTimer_";
        private static final String TRIGGER_TIME = "TriggerTime";

        private Context mContext;
        private AlarmManager mAlarmManager;

        // runnable --> time to execute in SystemClock
        private TreeSet<MyEvent> mEventQueue =
                new TreeSet<MyEvent>(new MyEventComparator());

        private PendingIntent mPendingIntent;

        public WakeupTimer(Context context) {
            mContext = context;
            mAlarmManager = (AlarmManager)
                    context.getSystemService(Context.ALARM_SERVICE);

            IntentFilter filter = new IntentFilter(getAction());
            context.registerReceiver(this, filter);
        }

        /**
         * Stops the timer. No event can be scheduled after this method is called.
         */
        public synchronized void stop() {
            mContext.unregisterReceiver(this);
            if (mPendingIntent != null) {
                mAlarmManager.cancel(mPendingIntent);
                mPendingIntent = null;
            }
            mEventQueue.clear();
            mEventQueue = null;
        }

        private synchronized boolean stopped() {
            if (mEventQueue == null) {
                Log.w(TAG, "Timer stopped");
                return true;
            } else {
                return false;
            }
        }

        private void cancelAlarm() {
            mAlarmManager.cancel(mPendingIntent);
            mPendingIntent = null;
        }

        private void recalculatePeriods() {
            if (mEventQueue.isEmpty()) return;

            MyEvent firstEvent = mEventQueue.first();
            int minPeriod = firstEvent.mMaxPeriod;
            long minTriggerTime = firstEvent.mTriggerTime;
            for (MyEvent e : mEventQueue) {
                e.mPeriod = e.mMaxPeriod / minPeriod * minPeriod;
                int interval = (int) (e.mLastTriggerTime + e.mMaxPeriod
                        - minTriggerTime);
                interval = interval / minPeriod * minPeriod;
                e.mTriggerTime = minTriggerTime + interval;
            }
            TreeSet<MyEvent> newQueue = new TreeSet<MyEvent>(
                    mEventQueue.comparator());
            newQueue.addAll((Collection<MyEvent>) mEventQueue);
            mEventQueue.clear();
            mEventQueue = newQueue;
            if (DEBUG_TIMER) {
                Log.d(TAG, "queue re-calculated");
                printQueue();
            }
        }

        // Determines the period and the trigger time of the new event and insert it
        // to the queue.
        private void insertEvent(MyEvent event) {
            long now = SystemClock.elapsedRealtime();
            if (mEventQueue.isEmpty()) {
                event.mTriggerTime = now + event.mPeriod;
                mEventQueue.add(event);
                return;
            }
            MyEvent firstEvent = mEventQueue.first();
            int minPeriod = firstEvent.mPeriod;
            if (minPeriod <= event.mMaxPeriod) {
                event.mPeriod = event.mMaxPeriod / minPeriod * minPeriod;
                int interval = event.mMaxPeriod;
                interval -= (int) (firstEvent.mTriggerTime - now);
                interval = interval / minPeriod * minPeriod;
                event.mTriggerTime = firstEvent.mTriggerTime + interval;
                mEventQueue.add(event);
            } else {
                long triggerTime = now + event.mPeriod;
                if (firstEvent.mTriggerTime < triggerTime) {
                    event.mTriggerTime = firstEvent.mTriggerTime;
                    event.mLastTriggerTime -= event.mPeriod;
                } else {
                    event.mTriggerTime = triggerTime;
                }
                mEventQueue.add(event);
                recalculatePeriods();
            }
        }

        /**
         * Sets a periodic timer.
         *
         * @param period the timer period; in milli-second
         * @param callback is called back when the timer goes off; the same callback
         *      can be specified in multiple timer events
         */
        public synchronized void set(int period, Runnable callback) {
            if (stopped()) return;

            long now = SystemClock.elapsedRealtime();
            MyEvent event = new MyEvent(period, callback, now);
            insertEvent(event);

            if (mEventQueue.first() == event) {
                if (mEventQueue.size() > 1) cancelAlarm();
                scheduleNext();
            }

            long triggerTime = event.mTriggerTime;
            if (DEBUG_TIMER) {
                Log.d(TAG, " add event " + event + " scheduled at "
                        + showTime(triggerTime) + " at " + showTime(now)
                        + ", #events=" + mEventQueue.size());
                printQueue();
            }
        }

        /**
         * Cancels all the timer events with the specified callback.
         *
         * @param callback the callback
         */
        public synchronized void cancel(Runnable callback) {
            if (stopped() || mEventQueue.isEmpty()) return;
            if (DEBUG_TIMER) Log.d(TAG, "cancel:" + callback);

            MyEvent firstEvent = mEventQueue.first();
            for (Iterator<MyEvent> iter = mEventQueue.iterator();
                    iter.hasNext();) {
                MyEvent event = iter.next();
                if (event.mCallback == callback) {
                    iter.remove();
                    if (DEBUG_TIMER) Log.d(TAG, "    cancel found:" + event);
                }
            }
            if (mEventQueue.isEmpty()) {
                cancelAlarm();
            } else if (mEventQueue.first() != firstEvent) {
                cancelAlarm();
                firstEvent = mEventQueue.first();
                firstEvent.mPeriod = firstEvent.mMaxPeriod;
                firstEvent.mTriggerTime = firstEvent.mLastTriggerTime
                        + firstEvent.mPeriod;
                recalculatePeriods();
                scheduleNext();
            }
            if (DEBUG_TIMER) {
                Log.d(TAG, "after cancel:");
                printQueue();
            }
        }

        private void scheduleNext() {
            if (stopped() || mEventQueue.isEmpty()) return;

            if (mPendingIntent != null) {
                throw new RuntimeException("pendingIntent is not null!");
            }

            MyEvent event = mEventQueue.first();
            Intent intent = new Intent(getAction());
            intent.putExtra(TRIGGER_TIME, event.mTriggerTime);
            PendingIntent pendingIntent = mPendingIntent =
                    PendingIntent.getBroadcast(mContext, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT);
            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    event.mTriggerTime, pendingIntent);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            // This callback is already protected by AlarmManager's wake lock.
            String action = intent.getAction();
            if (getAction().equals(action)
                    && intent.getExtras().containsKey(TRIGGER_TIME)) {
                mPendingIntent = null;
                long triggerTime = intent.getLongExtra(TRIGGER_TIME, -1L);
                execute(triggerTime);
            } else {
                Log.d(TAG, "unrecognized intent: " + intent);
            }
        }

        private void printQueue() {
            int count = 0;
            for (MyEvent event : mEventQueue) {
                Log.d(TAG, "     " + event + ": scheduled at "
                        + showTime(event.mTriggerTime) + ": last at "
                        + showTime(event.mLastTriggerTime));
                if (++count >= 5) break;
            }
            if (mEventQueue.size() > count) {
                Log.d(TAG, "     .....");
            } else if (count == 0) {
                Log.d(TAG, "     <empty>");
            }
        }

        private synchronized void execute(long triggerTime) {
            if (DEBUG_TIMER) Log.d(TAG, "time's up, triggerTime = "
                    + showTime(triggerTime) + ": " + mEventQueue.size());
            if (stopped() || mEventQueue.isEmpty()) return;

            for (MyEvent event : mEventQueue) {
                if (event.mTriggerTime != triggerTime) break;
                if (DEBUG_TIMER) Log.d(TAG, "execute " + event);

                event.mLastTriggerTime = event.mTriggerTime;
                event.mTriggerTime += event.mPeriod;

                // run the callback in the handler thread to prevent deadlock
                getExecutor().execute(event.mCallback);
            }
            if (DEBUG_TIMER) {
                Log.d(TAG, "after timeout execution");
                printQueue();
            }
            scheduleNext();
        }

        private String getAction() {
            return toString();
        }

        private String showTime(long time) {
            int ms = (int) (time % 1000);
            int s = (int) (time / 1000);
            int m = s / 60;
            s %= 60;
            return String.format("%d.%d.%d", m, s, ms);
        }
    }

    private static class MyEvent {
        int mPeriod;
        int mMaxPeriod;
        long mTriggerTime;
        long mLastTriggerTime;
        Runnable mCallback;

        MyEvent(int period, Runnable callback, long now) {
            mPeriod = mMaxPeriod = period;
            mCallback = callback;
            mLastTriggerTime = now;
        }

        @Override
        public String toString() {
            String s = super.toString();
            s = s.substring(s.indexOf("@"));
            return s + ":" + (mPeriod / 1000) + ":" + (mMaxPeriod / 1000) + ":"
                    + toString(mCallback);
        }

        private String toString(Object o) {
            String s = o.toString();
            int index = s.indexOf("$");
            if (index > 0) s = s.substring(index + 1);
            return s;
        }
    }

    private static class MyEventComparator implements Comparator<MyEvent> {
        public int compare(MyEvent e1, MyEvent e2) {
            if (e1 == e2) return 0;
            int diff = e1.mMaxPeriod - e2.mMaxPeriod;
            if (diff == 0) diff = -1;
            return diff;
        }

        public boolean equals(Object that) {
            return (this == that);
        }
    }

    private static Looper createLooper() {
        HandlerThread thread = new HandlerThread("SipService.Executor");
        thread.start();
        return thread.getLooper();
    }

    // Executes immediate tasks in a single thread.
    // Hold/release wake lock for running tasks
    private class MyExecutor extends Handler {
        MyExecutor() {
            super(createLooper());
        }

        void execute(Runnable task) {
            mMyWakeLock.acquire(task);
            Message.obtain(this, 0/* don't care */, task).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof Runnable) {
                executeInternal((Runnable) msg.obj);
            } else {
                Log.w(TAG, "can't handle msg: " + msg);
            }
        }

        private void executeInternal(Runnable task) {
            try {
                task.run();
            } catch (Throwable t) {
                Log.e(TAG, "run task: " + task, t);
            } finally {
                mMyWakeLock.release(task);
            }
        }
    }
}
