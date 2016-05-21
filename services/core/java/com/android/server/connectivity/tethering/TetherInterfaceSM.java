/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.connectivity.tethering;

import android.net.ConnectivityManager;
import android.net.INetworkStatsService;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.IState;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.net.InetAddress;

/**
 * @hide
 *
 * Tracks the eligibility of a given network interface for tethering.
 */
public class TetherInterfaceSM extends StateMachine {
    private static final String USB_NEAR_IFACE_ADDR = "192.168.42.129";
    private static final int USB_PREFIX_LENGTH = 24;

    private final static String TAG = "TetherInterfaceSM";
    private final static boolean DBG = false;
    private final static boolean VDBG = false;
    private static final Class[] messageClasses = {
            TetherInterfaceSM.class
    };
    private static final SparseArray<String> sMagicDecoderRing =
            MessageUtils.findMessageNames(messageClasses);

    private static final int BASE_IFACE              = Protocol.BASE_TETHERING + 100;
    // request from the user that it wants to tether
    public static final int CMD_TETHER_REQUESTED            = BASE_IFACE + 2;
    // request from the user that it wants to untether
    public static final int CMD_TETHER_UNREQUESTED          = BASE_IFACE + 3;
    // notification that this interface is down
    public static final int CMD_INTERFACE_DOWN              = BASE_IFACE + 4;
    // notification from the master SM that it had trouble enabling IP Forwarding
    public static final int CMD_IP_FORWARDING_ENABLE_ERROR  = BASE_IFACE + 7;
    // notification from the master SM that it had trouble disabling IP Forwarding
    public static final int CMD_IP_FORWARDING_DISABLE_ERROR = BASE_IFACE + 8;
    // notification from the master SM that it had trouble starting tethering
    public static final int CMD_START_TETHERING_ERROR       = BASE_IFACE + 9;
    // notification from the master SM that it had trouble stopping tethering
    public static final int CMD_STOP_TETHERING_ERROR        = BASE_IFACE + 10;
    // notification from the master SM that it had trouble setting the DNS forwarders
    public static final int CMD_SET_DNS_FORWARDERS_ERROR    = BASE_IFACE + 11;
    // the upstream connection has changed
    public static final int CMD_TETHER_CONNECTION_CHANGED   = BASE_IFACE + 12;

    private final State mInitialState;
    private final State mTetheredState;
    private final State mUnavailableState;

    private final INetworkManagementService mNMService;
    private final INetworkStatsService mStatsService;
    private final IControlsTethering mTetherController;

    private final boolean mUsb;
    private final String mIfaceName;

    private final Object mMutex;  // Protects the fields below.
    private boolean mAvailable;
    private boolean mTethered;
    private int mLastError;
    private String mMyUpstreamIfaceName;  // may change over time

    public TetherInterfaceSM(String ifaceName, Looper looper, boolean usb, Object mutex,
                    INetworkManagementService nMService, INetworkStatsService statsService,
                    IControlsTethering tetherController) {
        super(ifaceName, looper);
        mNMService = nMService;
        mStatsService = statsService;
        mTetherController = tetherController;
        mIfaceName = ifaceName;
        mUsb = usb;
        mMutex = mutex;
        setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);

        mInitialState = new InitialState();
        addState(mInitialState);
        mTetheredState = new TetheredState();
        addState(mTetheredState);
        mUnavailableState = new UnavailableState();
        addState(mUnavailableState);

        setInitialState(mInitialState);
    }

    @Override
    public String toString() {
        String res = new String();
        res += mIfaceName + " - ";
        IState current = getCurrentState();
        if (current == mInitialState) res += "InitialState";
        if (current == mTetheredState) res += "TetheredState";
        if (current == mUnavailableState) res += "UnavailableState";
        if (isAvailable()) res += " - Available";
        if (isTethered()) res += " - Tethered";
        res += " - lastError =" + getLastError();
        return res;
    }

    public int getLastError() {
        synchronized (mMutex) {
            return mLastError;
        }
    }

    private void setLastError(int error) {
        synchronized (mMutex) {
            mLastError = error;

            if (isErrored()) {
                if (mUsb) {
                    // note everything's been unwound by this point so nothing to do on
                    // further error..
                    configureUsbIface(false, mIfaceName);
                }
            }
        }
    }

    public boolean isAvailable() {
        synchronized (mMutex) {
            return mAvailable;
        }
    }

    private void setAvailable(boolean available) {
        synchronized (mMutex) {
            mAvailable = available;
        }
    }

    public boolean isTethered() {
        synchronized (mMutex) {
            return mTethered;
        }
    }

    private void setTethered(boolean tethered) {
        synchronized (mMutex) {
            mTethered = tethered;
        }
    }

    public boolean isErrored() {
        synchronized (mMutex) {
            return (mLastError != ConnectivityManager.TETHER_ERROR_NO_ERROR);
        }
    }

    // configured when we start tethering and unconfig'd on error or conclusion
    private boolean configureUsbIface(boolean enabled, String iface) {
        if (VDBG) Log.d(TAG, "configureUsbIface(" + enabled + ")");

        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNMService.getInterfaceConfig(iface);
            if (ifcg != null) {
                InetAddress addr = NetworkUtils.numericToInetAddress(USB_NEAR_IFACE_ADDR);
                ifcg.setLinkAddress(new LinkAddress(addr, USB_PREFIX_LENGTH));
                if (enabled) {
                    ifcg.setInterfaceUp();
                } else {
                    ifcg.setInterfaceDown();
                }
                ifcg.clearFlag("running");
                mNMService.setInterfaceConfig(iface, ifcg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring interface " + iface, e);
            return false;
        }

        return true;
    }

    private void maybeLogMessage(State state, int what) {
        if (DBG) {
            Log.d(TAG, state.getName() + " got " +
                    sMagicDecoderRing.get(what, Integer.toString(what)));
        }
    }

    class InitialState extends State {
        @Override
        public void enter() {
            setAvailable(true);
            setTethered(false);
            mTetherController.sendTetherStateChangedBroadcast();
        }

        @Override
        public boolean processMessage(Message message) {
            maybeLogMessage(this, message.what);
            boolean retValue = true;
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);
                    mTetherController.notifyInterfaceTetheringReadiness(true, TetherInterfaceSM.this);
                    transitionTo(mTetheredState);
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    break;
                default:
                    retValue = false;
                    break;
            }
            return retValue;
        }
    }

    class TetheredState extends State {
        @Override
        public void enter() {
            setAvailable(false);
            if (mUsb) {
                if (!configureUsbIface(true, mIfaceName)) {
                    mTetherController.notifyInterfaceTetheringReadiness(false, TetherInterfaceSM.this);
                    setLastError(ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);

                    transitionTo(mInitialState);
                    return;
                }
            }

            try {
                mNMService.tetherInterface(mIfaceName);
            } catch (Exception e) {
                Log.e(TAG, "Error Tethering: " + e.toString());
                setLastError(ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR);

                try {
                    mNMService.untetherInterface(mIfaceName);
                } catch (Exception ee) {
                    Log.e(TAG, "Error untethering after failure!" + ee.toString());
                }
                transitionTo(mInitialState);
                return;
            }
            if (DBG) Log.d(TAG, "Tethered " + mIfaceName);
            setTethered(true);
            mTetherController.sendTetherStateChangedBroadcast();
        }

        private void cleanupUpstream() {
            if (mMyUpstreamIfaceName != null) {
                // note that we don't care about errors here.
                // sometimes interfaces are gone before we get
                // to remove their rules, which generates errors.
                // just do the best we can.
                try {
                    // about to tear down NAT; gather remaining statistics
                    mStatsService.forceUpdate();
                } catch (Exception e) {
                    if (VDBG) Log.e(TAG, "Exception in forceUpdate: " + e.toString());
                }
                try {
                    mNMService.stopInterfaceForwarding(mIfaceName, mMyUpstreamIfaceName);
                } catch (Exception e) {
                    if (VDBG) Log.e(
                            TAG, "Exception in removeInterfaceForward: " + e.toString());
                }
                try {
                    mNMService.disableNat(mIfaceName, mMyUpstreamIfaceName);
                } catch (Exception e) {
                    if (VDBG) Log.e(TAG, "Exception in disableNat: " + e.toString());
                }
                mMyUpstreamIfaceName = null;
            }
            return;
        }

        @Override
        public boolean processMessage(Message message) {
            maybeLogMessage(this, message.what);
            boolean retValue = true;
            switch (message.what) {
                case CMD_TETHER_UNREQUESTED:
                case CMD_INTERFACE_DOWN:
                    cleanupUpstream();
                    try {
                        mNMService.untetherInterface(mIfaceName);
                    } catch (Exception e) {
                        setLastErrorAndTransitionToInitialState(
                                ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR);
                        break;
                    }
                    mTetherController.notifyInterfaceTetheringReadiness(false, TetherInterfaceSM.this);
                    if (message.what == CMD_TETHER_UNREQUESTED) {
                        if (mUsb) {
                            if (!configureUsbIface(false, mIfaceName)) {
                                setLastError(
                                        ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR);
                            }
                        }
                        transitionTo(mInitialState);
                    } else if (message.what == CMD_INTERFACE_DOWN) {
                        transitionTo(mUnavailableState);
                    }
                    if (DBG) Log.d(TAG, "Untethered " + mIfaceName);
                    break;
                case CMD_TETHER_CONNECTION_CHANGED:
                    String newUpstreamIfaceName = (String)(message.obj);
                    if ((mMyUpstreamIfaceName == null && newUpstreamIfaceName == null) ||
                            (mMyUpstreamIfaceName != null &&
                            mMyUpstreamIfaceName.equals(newUpstreamIfaceName))) {
                        if (VDBG) Log.d(TAG, "Connection changed noop - dropping");
                        break;
                    }
                    cleanupUpstream();
                    if (newUpstreamIfaceName != null) {
                        try {
                            mNMService.enableNat(mIfaceName, newUpstreamIfaceName);
                            mNMService.startInterfaceForwarding(mIfaceName,
                                    newUpstreamIfaceName);
                        } catch (Exception e) {
                            Log.e(TAG, "Exception enabling Nat: " + e.toString());
                            try {
                                mNMService.disableNat(mIfaceName, newUpstreamIfaceName);
                            } catch (Exception ee) {}
                            try {
                                mNMService.untetherInterface(mIfaceName);
                            } catch (Exception ee) {}

                            setLastError(ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR);
                            transitionTo(mInitialState);
                            return true;
                        }
                    }
                    mMyUpstreamIfaceName = newUpstreamIfaceName;
                    break;
                case CMD_IP_FORWARDING_ENABLE_ERROR:
                case CMD_IP_FORWARDING_DISABLE_ERROR:
                case CMD_START_TETHERING_ERROR:
                case CMD_STOP_TETHERING_ERROR:
                case CMD_SET_DNS_FORWARDERS_ERROR:
                    cleanupUpstream();
                    try {
                        mNMService.untetherInterface(mIfaceName);
                    } catch (Exception e) {
                        setLastErrorAndTransitionToInitialState(
                                ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR);
                        break;
                    }
                    setLastErrorAndTransitionToInitialState(
                            ConnectivityManager.TETHER_ERROR_MASTER_ERROR);
                    break;
                default:
                    retValue = false;
                    break;
            }
            return retValue;
        }
    }

    /**
     * This state is terminal for the per interface state machine.  At this
     * point, the master state machine should have removed this interface
     * specific state machine from its list of possible recipients of
     * tethering requests.  The state machine itself will hang around until
     * the garbage collector finds it.
     */
    class UnavailableState extends State {
        @Override
        public void enter() {
            setAvailable(false);
            setLastError(ConnectivityManager.TETHER_ERROR_NO_ERROR);
            setTethered(false);
            mTetherController.sendTetherStateChangedBroadcast();
        }
    }

    void setLastErrorAndTransitionToInitialState(int error) {
        setLastError(error);
        transitionTo(mInitialState);
    }
}
