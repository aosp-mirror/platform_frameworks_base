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
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSessionAdapter;
import android.net.sip.SipSessionState;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
 */
public final class SipService extends ISipService.Stub {
    private static final String TAG = "SipService";
    private static final int EXPIRY_TIME = 3600;
    private static final int SHORT_EXPIRY_TIME = 10;
    private static final int MIN_EXPIRY_TIME = 60;

    private Context mContext;
    private String mLocalIp;
    private String mNetworkType;
    private boolean mConnected;
    private WakeupTimer mTimer;
    private WifiManager.WifiLock mWifiLock;

    // SipProfile URI --> group
    private Map<String, SipSessionGroupExt> mSipGroups =
            new HashMap<String, SipSessionGroupExt>();

    // session ID --> session
    private Map<String, ISipSession> mPendingSessions =
            new HashMap<String, ISipSession>();

    private ConnectivityReceiver mConnectivityReceiver;

    public SipService(Context context) {
        Log.v(TAG, " service started!");
        mContext = context;
        mConnectivityReceiver = new ConnectivityReceiver();
        context.registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        mTimer = new WakeupTimer(context);
    }

    public synchronized SipProfile[] getListOfProfiles() {
        SipProfile[] profiles = new SipProfile[mSipGroups.size()];
        int i = 0;
        for (SipSessionGroupExt group : mSipGroups.values()) {
            profiles[i++] = group.getLocalProfile();
        }
        return profiles;
    }

    public void open(SipProfile localProfile) {
        if (localProfile.getAutoRegistration()) {
            openToReceiveCalls(localProfile);
        } else {
            openToMakeCalls(localProfile);
        }
    }

    private void openToMakeCalls(SipProfile localProfile) {
        try {
            createGroup(localProfile);
        } catch (SipException e) {
            Log.e(TAG, "openToMakeCalls()", e);
            // TODO: how to send the exception back
        }
    }

    private void openToReceiveCalls(SipProfile localProfile) {
        open3(localProfile, SipManager.SIP_INCOMING_CALL_ACTION, null);
    }

    public synchronized void open3(SipProfile localProfile,
            String incomingCallBroadcastAction, ISipSessionListener listener) {
        if (TextUtils.isEmpty(incomingCallBroadcastAction)) {
            throw new RuntimeException(
                    "empty broadcast action for incoming call");
        }
        Log.v(TAG, "open3: " + localProfile.getUriString() + ": "
                + incomingCallBroadcastAction + ": " + listener);
        try {
            SipSessionGroupExt group = createGroup(localProfile,
                    incomingCallBroadcastAction, listener);
            if (localProfile.getAutoRegistration()) {
                group.openToReceiveCalls();
                if (isWifiOn()) grabWifiLock();
            }
        } catch (SipException e) {
            Log.e(TAG, "openToReceiveCalls()", e);
            // TODO: how to send the exception back
        }
    }

    public synchronized void close(String localProfileUri) {
        SipSessionGroupExt group = mSipGroups.remove(localProfileUri);
        if (group != null) {
            notifyProfileRemoved(group.getLocalProfile());
            group.closeToNotReceiveCalls();
            if (isWifiOn() && !anyOpened()) releaseWifiLock();
        }
    }

    public synchronized boolean isOpened(String localProfileUri) {
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        return ((group != null) ? group.isOpened() : false);
    }

    public synchronized boolean isRegistered(String localProfileUri) {
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        return ((group != null) ? group.isRegistered() : false);
    }

    public synchronized void setRegistrationListener(String localProfileUri,
            ISipSessionListener listener) {
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group != null) group.setListener(listener);
    }

    public synchronized ISipSession createSession(SipProfile localProfile,
            ISipSessionListener listener) {
        if (!mConnected) return null;
        try {
            SipSessionGroupExt group = createGroup(localProfile);
            return group.createSession(listener);
        } catch (SipException e) {
            Log.w(TAG, "createSession()", e);
            return null;
        }
    }

    public synchronized ISipSession getPendingSession(String callId) {
        if (callId == null) return null;
        return mPendingSessions.get(callId);
    }

    private String determineLocalIp() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("192.168.1.1"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            Log.w(TAG, "determineLocalIp()", e);
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
        }
        return group;
    }

    private SipSessionGroupExt createGroup(SipProfile localProfile,
            String incomingCallBroadcastAction, ISipSessionListener listener)
            throws SipException {
        String key = localProfile.getUriString();
        SipSessionGroupExt group = mSipGroups.get(key);
        if (group != null) {
            group.setIncomingCallBroadcastAction(
                    incomingCallBroadcastAction);
            group.setListener(listener);
        } else {
            group = new SipSessionGroupExt(localProfile,
                    incomingCallBroadcastAction, listener);
            mSipGroups.put(key, group);
            notifyProfileAdded(localProfile);
        }
        return group;
    }

    private void notifyProfileAdded(SipProfile localProfile) {
        Log.d(TAG, "notify: profile added: " + localProfile);
        Intent intent = new Intent(SipManager.SIP_ADD_PHONE_ACTION);
        intent.putExtra(SipManager.LOCAL_URI_KEY, localProfile.getUriString());
        mContext.sendBroadcast(intent);
    }

    private void notifyProfileRemoved(SipProfile localProfile) {
        Log.d(TAG, "notify: profile removed: " + localProfile);
        Intent intent = new Intent(SipManager.SIP_REMOVE_PHONE_ACTION);
        intent.putExtra(SipManager.LOCAL_URI_KEY, localProfile.getUriString());
        mContext.sendBroadcast(intent);
    }

    private boolean anyOpened() {
        for (SipSessionGroupExt group : mSipGroups.values()) {
            if (group.isOpened()) return true;
        }
        return false;
    }

    private void grabWifiLock() {
        if (mWifiLock == null) {
            Log.v(TAG, "acquire wifi lock");
            mWifiLock = ((WifiManager)
                    mContext.getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);
            mWifiLock.acquire();
        }
    }

    private void releaseWifiLock() {
        if (mWifiLock != null) {
            Log.v(TAG, "release wifi lock");
            mWifiLock.release();
            mWifiLock = null;
        }
    }

    private boolean isWifiOn() {
        return "WIFI".equalsIgnoreCase(mNetworkType);
        //return (mConnected && "WIFI".equalsIgnoreCase(mNetworkType));
    }

    private synchronized void onConnectivityChanged(
            String type, boolean connected) {
        Log.v(TAG, "onConnectivityChanged(): "
                + mNetworkType + (mConnected? " CONNECTED" : " DISCONNECTED")
                + " --> " + type + (connected? " CONNECTED" : " DISCONNECTED"));

        boolean sameType = type.equals(mNetworkType);
        if (!sameType && !connected) return;

        boolean wasWifi = "WIFI".equalsIgnoreCase(mNetworkType);
        boolean isWifi = "WIFI".equalsIgnoreCase(type);
        boolean wifiOff = (isWifi && !connected) || (wasWifi && !sameType);
        boolean wifiOn = isWifi && connected;
        if (wifiOff) {
            releaseWifiLock();
        } else if (wifiOn) {
            if (anyOpened()) grabWifiLock();
        }

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
            }

        } catch (SipException e) {
            Log.e(TAG, "onConnectivityChanged()", e);
        }
    }

    private synchronized void addPendingSession(ISipSession session) {
        try {
            mPendingSessions.put(session.getCallId(), session);
        } catch (RemoteException e) {
            // should not happen with a local call
            Log.e(TAG, "addPendingSession()", e);
        }
    }

    private class SipSessionGroupExt extends SipSessionAdapter {
        private SipSessionGroup mSipGroup;
        private String mIncomingCallBroadcastAction;
        private boolean mOpened;

        private AutoRegistrationProcess mAutoRegistration =
                new AutoRegistrationProcess();

        public SipSessionGroupExt(SipProfile localProfile,
                String incomingCallBroadcastAction,
                ISipSessionListener listener) throws SipException {
            String password = localProfile.getPassword();
            SipProfile p = duplicate(localProfile);
            mSipGroup = createSipSessionGroup(mLocalIp, p, password);
            mIncomingCallBroadcastAction = incomingCallBroadcastAction;
            mAutoRegistration.setListener(listener);
        }

        public SipProfile getLocalProfile() {
            return mSipGroup.getLocalProfile();
        }

        // network connectivity is tricky because network can be disconnected
        // at any instant so need to deal with exceptions carefully even when
        // you think you are connected
        private SipSessionGroup createSipSessionGroup(String localIp,
                SipProfile localProfile, String password) throws SipException {
            try {
                return new SipSessionGroup(localIp, localProfile, password);
            } catch (IOException e) {
                // network disconnected
                Log.w(TAG, "createSipSessionGroup(): network disconnected?");
                if (localIp != null) {
                    return createSipSessionGroup(null, localProfile, password);
                } else {
                    // recursive
                    Log.wtf(TAG, "impossible!");
                    throw new RuntimeException("createSipSessionGroup");
                }
            }
        }

        private SipProfile duplicate(SipProfile p) {
            try {
                return new SipProfile.Builder(p.getUserName(), p.getSipDomain())
                        .setProfileName(p.getProfileName())
                        .setPassword("*")
                        .setPort(p.getPort())
                        .setProtocol(p.getProtocol())
                        .setOutboundProxy(p.getProxyAddress())
                        .setSendKeepAlive(p.getSendKeepAlive())
                        .setAutoRegistration(p.getAutoRegistration())
                        .setDisplayName(p.getDisplayName())
                        .build();
            } catch (Exception e) {
                Log.wtf(TAG, "duplicate()", e);
                throw new RuntimeException("duplicate profile", e);
            }
        }

        public void setListener(ISipSessionListener listener) {
            mAutoRegistration.setListener(listener);
        }

        public void setIncomingCallBroadcastAction(String action) {
            mIncomingCallBroadcastAction = action;
        }

        public void openToReceiveCalls() throws SipException {
            mOpened = true;
            if (mConnected) {
                mSipGroup.openToReceiveCalls(this);
                mAutoRegistration.start(mSipGroup);
            }
            Log.v(TAG, "  openToReceiveCalls: " + getUri() + ": "
                    + mIncomingCallBroadcastAction);
        }

        public void onConnectivityChanged(boolean connected)
                throws SipException {
            if (connected) {
                resetGroup(mLocalIp);
                if (mOpened) openToReceiveCalls();
            } else {
                // close mSipGroup but remember mOpened
                Log.v(TAG, "  close auto reg temporarily: " + getUri() + ": "
                        + mIncomingCallBroadcastAction);
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

        public void closeToNotReceiveCalls() {
            mOpened = false;
            mSipGroup.closeToNotReceiveCalls();
            mAutoRegistration.stop();
            Log.v(TAG, "   close: " + getUri() + ": "
                    + mIncomingCallBroadcastAction);
        }

        public ISipSession createSession(ISipSessionListener listener) {
            return mSipGroup.createSession(listener);
        }

        @Override
        public void onRinging(ISipSession session, SipProfile caller,
                byte[] sessionDescription) {
            synchronized (SipService.this) {
                try {
                    if (!isRegistered()) {
                        session.endCall();
                        return;
                    }

                    // send out incoming call broadcast
                    Log.d(TAG, " ringing~~ " + getUri() + ": " + caller.getUri()
                            + ": " + session.getCallId());
                    addPendingSession(session);
                    Intent intent = SipManager.createIncomingCallBroadcast(
                            mIncomingCallBroadcastAction, session.getCallId(),
                            sessionDescription);
                    Log.d(TAG, "   send out intent: " + intent);
                    mContext.sendBroadcast(intent);
                } catch (RemoteException e) {
                    // should never happen with a local call
                    Log.e(TAG, "processCall()", e);
                }
            }
        }

        @Override
        public void onError(ISipSession session, String errorClass,
                String message) {
            Log.v(TAG, "sip session error: " + errorClass + ": " + message);
        }

        public boolean isOpened() {
            return mOpened;
        }

        public boolean isRegistered() {
            return mAutoRegistration.isRegistered();
        }

        private String getUri() {
            return mSipGroup.getLocalProfileUri();
        }
    }

    private class KeepAliveProcess implements Runnable {
        private static final String TAG = "\\KEEPALIVE/";
        private static final int INTERVAL = 15;
        private SipSessionGroup.SipSessionImpl mSession;

        public KeepAliveProcess(SipSessionGroup.SipSessionImpl session) {
            mSession = session;
        }

        public void start() {
            mTimer.set(INTERVAL * 1000, this);
        }

        public void run() {
            synchronized (SipService.this) {
                SipSessionGroup.SipSessionImpl session = mSession.duplicate();
                Log.d(TAG, "  ~~~ keepalive");
                mTimer.cancel(this);
                session.sendKeepAlive();
                if (session.isReRegisterRequired()) {
                    mSession.register(EXPIRY_TIME);
                } else {
                    mTimer.set(INTERVAL * 1000, this);
                }
            }
        }

        public void stop() {
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

        private String getAction() {
            return toString();
        }

        public void start(SipSessionGroup group) {
            if (mSession == null) {
                mBackoff = 1;
                mSession = (SipSessionGroup.SipSessionImpl)
                        group.createSession(this);
                // return right away if no active network connection.
                if (mSession == null) return;

                // start unregistration to clear up old registration at server
                // TODO: when rfc5626 is deployed, use reg-id and sip.instance
                // in registration to avoid adding duplicate entries to server
                mSession.unregister();
                Log.v(TAG, "start AutoRegistrationProcess for "
                        + mSession.getLocalProfile().getUriString());
            }
        }

        public void stop() {
            if (mSession == null) return;
            if (mConnected) mSession.unregister();
            mTimer.cancel(this);
            if (mKeepAliveProcess != null) {
                mKeepAliveProcess.stop();
                mKeepAliveProcess = null;
            }
            mSession = null;
            mRegistered = false;
        }

        private boolean isStopped() {
            return (mSession == null);
        }

        public void setListener(ISipSessionListener listener) {
            Log.v(TAG, "setListener(): " + listener);
            mProxy.setListener(listener);
            if (mSession == null) return;

            try {
                if ((mSession != null) && SipSessionState.REGISTERING.equals(
                        mSession.getState())) {
                    mProxy.onRegistering(mSession);
                } else if (mRegistered) {
                    int duration = (int)
                            (mExpiryTime - SystemClock.elapsedRealtime());
                    mProxy.onRegistrationDone(mSession, duration);
                }
            } catch (Throwable t) {
                Log.w(TAG, "setListener(): " + t);
            }
        }

        public boolean isRegistered() {
            return mRegistered;
        }

        public void run() {
            Log.v(TAG, "  ~~~ registering");
            synchronized (SipService.this) {
                if (mConnected && !isStopped()) mSession.register(EXPIRY_TIME);
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
            Log.v(TAG, "Refresh registration " + duration + "s later.");
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
            Log.v(TAG, "onRegistering(): " + session + ": " + mSession);
            synchronized (SipService.this) {
                if (!isStopped() && (session != mSession)) return;
                mRegistered = false;
                try {
                    mProxy.onRegistering(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistering()", t);
                }
            }
        }

        @Override
        public void onRegistrationDone(ISipSession session, int duration) {
            Log.v(TAG, "onRegistrationDone(): " + session + ": " + mSession);
            synchronized (SipService.this) {
                if (!isStopped() && (session != mSession)) return;
                try {
                    mProxy.onRegistrationDone(session, duration);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationDone()", t);
                }
                if (isStopped()) return;

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
                } else {
                    mRegistered = false;
                    mExpiryTime = -1L;
                    Log.v(TAG, "Refresh registration immediately");
                    run();
                }
            }
        }

        @Override
        public void onRegistrationFailed(ISipSession session, String className,
                String message) {
            Log.v(TAG, "onRegistrationFailed(): " + session + ": " + mSession
                    + ": " + className + ": " + message);
            synchronized (SipService.this) {
                if (!isStopped() && (session != mSession)) return;
                try {
                    mProxy.onRegistrationFailed(session, className, message);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationFailed(): " + t);
                }

                if (!isStopped()) onError();
            }
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            Log.v(TAG, "onRegistrationTimeout(): " + session + ": " + mSession);
            synchronized (SipService.this) {
                if (!isStopped() && (session != mSession)) return;
                try {
                    mProxy.onRegistrationTimeout(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationTimeout(): " + t);
                }

                if (!isStopped()) {
                    mRegistered = false;
                    onError();
                }
            }
        }

        private void onError() {
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
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Bundle b = intent.getExtras();
                if (b != null) {
                    NetworkInfo netInfo = (NetworkInfo)
                            b.get(ConnectivityManager.EXTRA_NETWORK_INFO);
                    String type = netInfo.getTypeName();
                    NetworkInfo.State state = netInfo.getState();
                    if (state == NetworkInfo.State.CONNECTED) {
                        Log.v(TAG, "Connectivity alert: CONNECTED " + type);
                        onChanged(type, true);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        Log.v(TAG, "Connectivity alert: DISCONNECTED " + type);
                        onChanged(type, false);
                    } else {
                        Log.d(TAG, "Connectivity alert not processed: " + state
                                + " " + type);
                    }
                }
            }
        }

        private void onChanged(String type, boolean connected) {
            synchronized (SipService.this) {
                // When turning on WIFI, it needs some time for network
                // connectivity to get stabile so we defer good news (because
                // we want to skip the interim ones) but deliver bad news
                // immediately
                if (connected) {
                    if (mTask != null) mTask.cancel();
                    mTask = new MyTimerTask(type, connected);
                    mTimer.schedule(mTask, 3 * 1000L);
                    // TODO: hold wakup lock so that we can finish change before
                    // the device goes to sleep
                } else {
                    if ((mTask != null) && mTask.mNetworkType.equals(type)) {
                        mTask.cancel();
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

            @Override
            public void run() {
                synchronized (SipService.this) {
                    if (mTask != this) {
                        Log.w(TAG, "  unexpected task: " + mNetworkType
                                + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                        return;
                    }
                    mTask = null;
                    Log.v(TAG, " deliver change for " + mNetworkType
                            + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                    onConnectivityChanged(mNetworkType, mConnected);
                }
            }
        }
    }

    // TODO: clean up pending SipSession(s) periodically


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
            Log.v(TAG, "queue re-calculated");
            printQueue();
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
            Log.v(TAG, " add event " + event + " scheduled at "
                    + showTime(triggerTime) + " at " + showTime(now)
                    + ", #events=" + mEventQueue.size());
            printQueue();
        }

        /**
         * Cancels all the timer events with the specified callback.
         *
         * @param callback the callback
         */
        public synchronized void cancel(Runnable callback) {
            if (stopped() || mEventQueue.isEmpty()) return;
            Log.d(TAG, "cancel:" + callback);

            MyEvent firstEvent = mEventQueue.first();
            for (Iterator<MyEvent> iter = mEventQueue.iterator();
                    iter.hasNext();) {
                MyEvent event = iter.next();
                if (event.mCallback == callback) {
                    iter.remove();
                    Log.d(TAG, "    cancel found:" + event);
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
            Log.d(TAG, "after cancel:");
            printQueue();
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
        public synchronized void onReceive(Context context, Intent intent) {
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

        private void execute(long triggerTime) {
            Log.d(TAG, "time's up, triggerTime = " + showTime(triggerTime) + ": "
                    + mEventQueue.size());
            if (stopped() || mEventQueue.isEmpty()) return;

            for (MyEvent event : mEventQueue) {
                if (event.mTriggerTime != triggerTime) break;
                Log.d(TAG, "execute " + event);

                event.mLastTriggerTime = event.mTriggerTime;
                event.mTriggerTime += event.mPeriod;

                // run the callback in a new thread to prevent deadlock
                new Thread(event.mCallback).start();
            }
            Log.d(TAG, "after timeout execution");
            printQueue();
            scheduleNext();
        }

        private String getAction() {
            return toString();
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

        private static String showTime(long time) {
            int ms = (int) (time % 1000);
            int s = (int) (time / 1000);
            int m = s / 60;
            s %= 60;
            return String.format("%d.%d.%d", m, s, ms);
        }
    }
}
