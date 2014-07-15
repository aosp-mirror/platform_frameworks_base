/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.net.wifi.passpoint;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.ParcelableString;
import com.android.internal.util.Protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/**
 * Provides APIs for managing Wifi Passpoint credentials.
 * @hide
 */
public class WifiPasspointManager {

    private static final String TAG = "PasspointManager";

    private static final boolean DBG = true;

    /* Passpoint states values */

    /** Passpoint is in an unknown state. This should only occur in boot time */
    public static final int PASSPOINT_STATE_UNKNOWN = 0;

    /** Passpoint is disabled. This occurs when wifi is disabled */
    public static final int PASSPOINT_STATE_DISABLED = 1;

    /** Passpoint is enabled and in discovery state */
    public static final int PASSPOINT_STATE_DISCOVERY = 2;

    /** Passpoint is enabled and in access state */
    public static final int PASSPOINT_STATE_ACCESS = 3;

    /** Passpoint is enabled and in provisioning state */
    public static final int PASSPOINT_STATE_PROVISION = 4;

    /* Passpoint callback error codes */

    /** Indicates that the operation failed due to an internal error */
    public static final int REASON_ERROR = 0;

    /** Indicates that the operation failed because wifi is disabled */
    public static final int REASON_WIFI_DISABLED = 1;

    /** Indicates that the operation failed because the framework is busy */
    public static final int REASON_BUSY = 2;

    /** Indicates that the operation failed because parameter is invalid */
    public static final int REASON_INVALID_PARAMETER = 3;

    /** Indicates that the operation failed because the server is not trusted */
    public static final int REASON_NOT_TRUSTED = 4;

    /**
     * protocol supported for Passpoint
     */
    public static final String PROTOCOL_DM = "OMA-DM-ClientInitiated";

    /**
     * protocol supported for Passpoint
     */
    public static final String PROTOCOL_SOAP = "SPP-ClientInitiated";

    /* Passpoint broadcasts */

    /**
     * Broadcast intent action indicating that the state of Passpoint
     * connectivity has changed
     */
    public static final String PASSPOINT_STATE_CHANGED_ACTION =
            "android.net.wifi.passpoint.STATE_CHANGE";

    /**
     * Broadcast intent action indicating that the saved Passpoint credential
     * list has changed
     */
    public static final String PASSPOINT_CRED_CHANGED_ACTION =
            "android.net.wifi.passpoint.CRED_CHANGE";

    /**
     * Broadcast intent action indicating that Passpoint online sign up is
     * avaiable.
     */
    public static final String PASSPOINT_OSU_AVAILABLE_ACTION =
            "android.net.wifi.passpoint.OSU_AVAILABLE";

    /**
     * Broadcast intent action indicating that user remediation is required
     */
    public static final String PASSPOINT_USER_REM_REQ_ACTION =
            "android.net.wifi.passpoint.USER_REM_REQ";

    /**
     * Interface for callback invocation when framework channel is lost
     */
    public interface ChannelListener {
        /**
         * The channel to the framework has been disconnected. Application could
         * try re-initializing using {@link #initialize}
         */
        public void onChannelDisconnected();
    }

    /**
     * Interface for callback invocation on an application action
     */
    public interface ActionListener {
        /** The operation succeeded */
        public void onSuccess();

        /**
         * The operation failed
         *
         * @param reason The reason for failure could be one of
         *            {@link #WIFI_DISABLED}, {@link #ERROR} or {@link #BUSY}
         */
        public void onFailure(int reason);
    }

    /**
     * Interface for callback invocation when doing OSU or user remediation
     */
    public interface OsuRemListener {
        /** The operation succeeded */
        public void onSuccess();

        /**
         * The operation failed
         *
         * @param reason The reason for failure could be one of
         *            {@link #WIFI_DISABLED}, {@link #ERROR} or {@link #BUSY}
         */
        public void onFailure(int reason);

        /**
         * Browser launch is requried for user interaction. When this callback
         * is called, app should launch browser / webview to the given URI.
         *
         * @param uri URI for browser launch
         */
        public void onBrowserLaunch(String uri);

        /**
         * When this is called, app should dismiss the previously lanched browser.
         */
        public void onBrowserDismiss();
    }

    /**
     * A channel that connects the application to the wifi passpoint framework.
     * Most passpoint operations require a Channel as an argument.
     * An instance of Channel is obtained by doing a call on {@link #initialize}
     */
    public static class Channel {
        private final static int INVALID_LISTENER_KEY = 0;

        private ChannelListener mChannelListener;

        private HashMap<Integer, Object> mListenerMap = new HashMap<Integer, Object>();
        private HashMap<Integer, Integer> mListenerMapCount = new HashMap<Integer, Integer>();
        private Object mListenerMapLock = new Object();
        private int mListenerKey = 0;

        private List<ScanResult> mAnqpRequest = new LinkedList<ScanResult>();
        private Object mAnqpRequestLock = new Object();

        private AsyncChannel mAsyncChannel;
        private PasspointHandler mHandler;
        Context mContext;

        Channel(Context context, Looper looper, ChannelListener l) {
            mAsyncChannel = new AsyncChannel();
            mHandler = new PasspointHandler(looper);
            mChannelListener = l;
            mContext = context;
        }

        private int putListener(Object listener) {
            return putListener(listener, 1);
        }

        private int putListener(Object listener, int count) {
            if (listener == null || count <= 0)
                return INVALID_LISTENER_KEY;
            int key;
            synchronized (mListenerMapLock) {
                do {
                    key = mListenerKey++;
                } while (key == INVALID_LISTENER_KEY);
                mListenerMap.put(key, listener);
                mListenerMapCount.put(key, count);
            }
            return key;
        }

        private Object peekListener(int key) {
            Log.d(TAG, "peekListener() key=" + key);
            if (key == INVALID_LISTENER_KEY)
                return null;
            synchronized (mListenerMapLock) {
                return mListenerMap.get(key);
            }
        }


        private Object getListener(int key, boolean forceRemove) {
            Log.d(TAG, "getListener() key=" + key + " force=" + forceRemove);
            if (key == INVALID_LISTENER_KEY)
                return null;
            synchronized (mListenerMapLock) {
                if (!forceRemove) {
                    int count = mListenerMapCount.get(key);
                    Log.d(TAG, "count=" + count);
                    mListenerMapCount.put(key, --count);
                    if (count > 0)
                        return null;
                }
                Log.d(TAG, "remove key");
                mListenerMapCount.remove(key);
                return mListenerMap.remove(key);
            }
        }

        private void anqpRequestStart(ScanResult sr) {
            Log.d(TAG, "anqpRequestStart sr.bssid=" + sr.BSSID);
            synchronized (mAnqpRequestLock) {
                mAnqpRequest.add(sr);
            }
        }

        private void anqpRequestFinish(WifiPasspointInfo result) {
            Log.d(TAG, "anqpRequestFinish pi.bssid=" + result.bssid);
            synchronized (mAnqpRequestLock) {
                for (ScanResult sr : mAnqpRequest)
                    if (sr.BSSID.equals(result.bssid)) {
                        Log.d(TAG, "find hit " + result.bssid);
                        sr.passpoint = result;
                        mAnqpRequest.remove(sr);
                        Log.d(TAG, "mAnqpRequest.len=" + mAnqpRequest.size());
                        break;
                    }
            }
        }

        private void anqpRequestFinish(ScanResult sr) {
            Log.d(TAG, "anqpRequestFinish sr.bssid=" + sr.BSSID);
            synchronized (mAnqpRequestLock) {
                for (ScanResult sr1 : mAnqpRequest)
                    if (sr1.BSSID.equals(sr.BSSID)) {
                        mAnqpRequest.remove(sr1);
                        break;
                    }
            }
        }

        class PasspointHandler extends Handler {
            PasspointHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message message) {
                Object listener = null;

                switch (message.what) {
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (mChannelListener != null) {
                            mChannelListener.onChannelDisconnected();
                            mChannelListener = null;
                        }
                        break;

                    case REQUEST_ANQP_INFO_SUCCEEDED:
                        WifiPasspointInfo result = (WifiPasspointInfo) message.obj;
                        anqpRequestFinish(result);
                        listener = getListener(message.arg2, false);
                        if (listener != null) {
                            ((ActionListener) listener).onSuccess();
                        }
                        break;

                    case REQUEST_ANQP_INFO_FAILED:
                        anqpRequestFinish((ScanResult) message.obj);
                        listener = getListener(message.arg2, false);
                        if (listener == null)
                            getListener(message.arg2, true);
                        if (listener != null) {
                            ((ActionListener) listener).onFailure(message.arg1);
                        }
                        break;

                    case START_OSU_SUCCEEDED:
                        listener = getListener(message.arg2, true);
                        if (listener != null) {
                            ((OsuRemListener) listener).onSuccess();
                        }
                        break;

                    case START_OSU_FAILED:
                        listener = getListener(message.arg2, true);
                        if (listener != null) {
                            ((OsuRemListener) listener).onFailure(message.arg1);
                        }
                        break;

                    case START_OSU_BROWSER:
                        listener = peekListener(message.arg2);
                        if (listener != null) {
                            ParcelableString str = (ParcelableString) message.obj;
                            if (str == null || str.string == null)
                                ((OsuRemListener) listener).onBrowserDismiss();
                            else
                                ((OsuRemListener) listener).onBrowserLaunch(str.string);
                        }
                        break;

                    default:
                        Log.d(TAG, "Ignored " + message);
                        break;
                }
            }
        }

    }

    private static final int BASE = Protocol.BASE_WIFI_PASSPOINT_MANAGER;

    public static final int REQUEST_ANQP_INFO                   = BASE + 1;
    public static final int REQUEST_ANQP_INFO_FAILED            = BASE + 2;
    public static final int REQUEST_ANQP_INFO_SUCCEEDED         = BASE + 3;
    public static final int REQUEST_OSU_ICON                    = BASE + 4;
    public static final int REQUEST_OSU_ICON_FAILED             = BASE + 5;
    public static final int REQUEST_OSU_ICON_SUCCEEDED          = BASE + 6;
    public static final int START_OSU                           = BASE + 7;
    public static final int START_OSU_BROWSER                   = BASE + 8;
    public static final int START_OSU_FAILED                    = BASE + 9;
    public static final int START_OSU_SUCCEEDED                 = BASE + 10;

    private Context mContext;
    IWifiPasspointManager mService;

    /**
     * TODO: doc
     * @param context
     * @param service
     */
    public WifiPasspointManager(Context context, IWifiPasspointManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Registers the application with the framework. This function must be the
     * first to be called before any async passpoint operations are performed.
     *
     * @param srcContext is the context of the source
     * @param srcLooper is the Looper on which the callbacks are receivied
     * @param listener for callback at loss of framework communication. Can be
     *            null.
     * @return Channel instance that is necessary for performing any further
     *         passpoint operations
     *
     */
    public Channel initialize(Context srcContext, Looper srcLooper, ChannelListener listener) {
        Messenger messenger = getMessenger();
        if (messenger == null)
            return null;

        Channel c = new Channel(srcContext, srcLooper, listener);
        if (c.mAsyncChannel.connectSync(srcContext, c.mHandler, messenger)
                == AsyncChannel.STATUS_SUCCESSFUL) {
            return c;
        } else {
            return null;
        }
    }

    /**
     * STOPSHIP: temp solution, should use supplicant manager instead, check
     * with b/13931972
     */
    public Messenger getMessenger() {
        try {
            return mService.getMessenger();
        } catch (RemoteException e) {
            return null;
        }
    }

    public int getPasspointState() {
        try {
            return mService.getPasspointState();
        } catch (RemoteException e) {
            return PASSPOINT_STATE_UNKNOWN;
        }
    }

    public void requestAnqpInfo(Channel c, List<ScanResult> requested, int mask,
            ActionListener listener) {
        Log.d(TAG, "requestAnqpInfo start");
        Log.d(TAG, "requested.size=" + requested.size());
        checkChannel(c);
        List<ScanResult> list = new ArrayList<ScanResult>();
        for (ScanResult sr : requested)
            if (sr.capabilities.contains("[HS20]")) {
                list.add(sr);
                c.anqpRequestStart(sr);
                Log.d(TAG, "adding " + sr.BSSID);
            }
        int count = list.size();
        Log.d(TAG, "after filter, count=" + count);
        if (count == 0) {
            if (DBG)
                Log.d(TAG, "ANQP info request contains no HS20 APs, skipped");
            listener.onSuccess();
            return;
        }
        int key = c.putListener(listener, count);
        for (ScanResult sr : list)
            c.mAsyncChannel.sendMessage(REQUEST_ANQP_INFO, mask, key, sr);
        Log.d(TAG, "requestAnqpInfo end");
    }

    public void requestOsuIcons(Channel c, List<WifiPasspointOsuProvider> requested,
            int resolution, ActionListener listener) {
    }

    public List<WifiPasspointPolicy> requestCredentialMatch(List<ScanResult> requested) {
        try {
            return mService.requestCredentialMatch(requested);
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Get a list of saved Passpoint credentials. Only those credentials owned
     * by the caller will be returned.
     *
     * @return The list of credentials
     */
    public List<WifiPasspointCredential> getCredentials() {
        try {
            return mService.getCredentials();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Add a new Passpoint credential.
     *
     * @param cred The credential to be added
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean addCredential(WifiPasspointCredential cred) {
        try {
            return mService.addCredential(cred);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Update an existing Passpoint credential. Only system or the owner of this
     * credential has the permission to do this.
     *
     * @param cred The credential to be updated
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean updateCredential(WifiPasspointCredential cred) {
        try {
            return mService.updateCredential(cred);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Remove an existing Passpoint credential. Only system or the owner of this
     * credential has the permission to do this.
     *
     * @param cred The credential to be removed
     * @return {@code true} if the operation succeeds, {@code false} otherwise
     */
    public boolean removeCredential(WifiPasspointCredential cred) {
        try {
            return mService.removeCredential(cred);
        } catch (RemoteException e) {
            return false;
        }
    }

    public void startOsu(Channel c, WifiPasspointOsuProvider osu, OsuRemListener listener) {
        Log.d(TAG, "startOsu start");
        checkChannel(c);
        int key = c.putListener(listener);
        c.mAsyncChannel.sendMessage(START_OSU, 0, key, osu);
        Log.d(TAG, "startOsu end");
    }

    public void startRemediation(Channel c, OsuRemListener listener) {
    }

    public void connect(WifiPasspointPolicy policy) {
    }

    private static void checkChannel(Channel c) {
        if (c == null) throw new IllegalArgumentException("Channel needs to be initialized");
    }
}
