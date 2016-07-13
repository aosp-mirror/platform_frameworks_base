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
import android.net.LinkProperties;
import android.net.NetworkUtils;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

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
public class TetherInterfaceStateMachine extends StateMachine {
    private static final String USB_NEAR_IFACE_ADDR = "192.168.42.129";
    private static final int USB_PREFIX_LENGTH = 24;
    private static final String WIFI_HOST_IFACE_ADDR = "192.168.43.1";
    private static final int WIFI_HOST_IFACE_PREFIX_LENGTH = 24;

    private final static String TAG = "TetherInterfaceSM";
    private final static boolean DBG = false;
    private final static boolean VDBG = false;
    private static final Class[] messageClasses = {
            TetherInterfaceStateMachine.class
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
    // new IPv6 tethering parameters need to be processed
    public static final int CMD_IPV6_TETHER_UPDATE          = BASE_IFACE + 13;

    private final State mInitialState;
    private final State mTetheredState;
    private final State mUnavailableState;

    private final INetworkManagementService mNMService;
    private final INetworkStatsService mStatsService;
    private final IControlsTethering mTetherController;

    private final String mIfaceName;
    private final int mInterfaceType;
    private final IPv6TetheringInterfaceServices mIPv6TetherSvc;

    private int mLastError;
    private String mMyUpstreamIfaceName;  // may change over time

    public TetherInterfaceStateMachine(String ifaceName, Looper looper, int interfaceType,
                    INetworkManagementService nMService, INetworkStatsService statsService,
                    IControlsTethering tetherController) {
        super(ifaceName, looper);
        mNMService = nMService;
        mStatsService = statsService;
        mTetherController = tetherController;
        mIfaceName = ifaceName;
        mInterfaceType = interfaceType;
        mIPv6TetherSvc = new IPv6TetheringInterfaceServices(mIfaceName, mNMService);
        mLastError = ConnectivityManager.TETHER_ERROR_NO_ERROR;

        mInitialState = new InitialState();
        addState(mInitialState);
        mTetheredState = new TetheredState();
        addState(mTetheredState);
        mUnavailableState = new UnavailableState();
        addState(mUnavailableState);

        setInitialState(mInitialState);
    }

    public int interfaceType() {
        return mInterfaceType;
    }

    // configured when we start tethering and unconfig'd on error or conclusion
    private boolean configureIfaceIp(boolean enabled) {
        if (VDBG) Log.d(TAG, "configureIfaceIp(" + enabled + ")");

        String ipAsString = null;
        int prefixLen = 0;
        if (mInterfaceType == ConnectivityManager.TETHERING_USB) {
            ipAsString = USB_NEAR_IFACE_ADDR;
            prefixLen = USB_PREFIX_LENGTH;
        } else if (mInterfaceType == ConnectivityManager.TETHERING_WIFI) {
            ipAsString = WIFI_HOST_IFACE_ADDR;
            prefixLen = WIFI_HOST_IFACE_PREFIX_LENGTH;
        } else {
            // Nothing to do, BT does this elsewhere.
            return true;
        }

        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNMService.getInterfaceConfig(mIfaceName);
            if (ifcg != null) {
                InetAddress addr = NetworkUtils.numericToInetAddress(ipAsString);
                ifcg.setLinkAddress(new LinkAddress(addr, prefixLen));
                if (enabled) {
                    ifcg.setInterfaceUp();
                } else {
                    ifcg.setInterfaceDown();
                }
                ifcg.clearFlag("running");
                mNMService.setInterfaceConfig(mIfaceName, ifcg);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error configuring interface " + mIfaceName, e);
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
            mTetherController.notifyInterfaceStateChange(
                    mIfaceName, TetherInterfaceStateMachine.this,
                    IControlsTethering.STATE_AVAILABLE, mLastError);
        }

        @Override
        public boolean processMessage(Message message) {
            maybeLogMessage(this, message.what);
            boolean retValue = true;
            switch (message.what) {
                case CMD_TETHER_REQUESTED:
                    mLastError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
                    transitionTo(mTetheredState);
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    break;
                case CMD_IPV6_TETHER_UPDATE:
                    mIPv6TetherSvc.updateUpstreamIPv6LinkProperties(
                            (LinkProperties) message.obj);
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
            if (!configureIfaceIp(true)) {
                mLastError = ConnectivityManager.TETHER_ERROR_IFACE_CFG_ERROR;
                transitionTo(mInitialState);
                return;
            }

            try {
                mNMService.tetherInterface(mIfaceName);
            } catch (Exception e) {
                Log.e(TAG, "Error Tethering: " + e.toString());
                mLastError = ConnectivityManager.TETHER_ERROR_TETHER_IFACE_ERROR;
                transitionTo(mInitialState);
                return;
            }

            if (!mIPv6TetherSvc.start()) {
                Log.e(TAG, "Failed to start IPv6TetheringInterfaceServices");
            }

            if (DBG) Log.d(TAG, "Tethered " + mIfaceName);
            mTetherController.notifyInterfaceStateChange(
                    mIfaceName, TetherInterfaceStateMachine.this,
                    IControlsTethering.STATE_TETHERED, mLastError);
        }

        @Override
        public void exit() {
            // Note that at this point, we're leaving the tethered state.  We can fail any
            // of these operations, but it doesn't really change that we have to try them
            // all in sequence.
            mIPv6TetherSvc.stop();
            cleanupUpstream();

            try {
                mNMService.untetherInterface(mIfaceName);
            } catch (Exception ee) {
                mLastError = ConnectivityManager.TETHER_ERROR_UNTETHER_IFACE_ERROR;
                Log.e(TAG, "Failed to untether interface: " + ee.toString());
            }

            configureIfaceIp(false);
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
                    transitionTo(mInitialState);
                    if (DBG) Log.d(TAG, "Untethered (unrequested)" + mIfaceName);
                    break;
                case CMD_INTERFACE_DOWN:
                    transitionTo(mUnavailableState);
                    if (DBG) Log.d(TAG, "Untethered (ifdown)" + mIfaceName);
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
                            mLastError = ConnectivityManager.TETHER_ERROR_ENABLE_NAT_ERROR;
                            transitionTo(mInitialState);
                            return true;
                        }
                    }
                    mMyUpstreamIfaceName = newUpstreamIfaceName;
                    break;
                case CMD_IPV6_TETHER_UPDATE:
                    mIPv6TetherSvc.updateUpstreamIPv6LinkProperties(
                            (LinkProperties) message.obj);
                    break;
                case CMD_IP_FORWARDING_ENABLE_ERROR:
                case CMD_IP_FORWARDING_DISABLE_ERROR:
                case CMD_START_TETHERING_ERROR:
                case CMD_STOP_TETHERING_ERROR:
                case CMD_SET_DNS_FORWARDERS_ERROR:
                    mLastError = ConnectivityManager.TETHER_ERROR_MASTER_ERROR;
                    transitionTo(mInitialState);
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
            mLastError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
            mTetherController.notifyInterfaceStateChange(
                    mIfaceName, TetherInterfaceStateMachine.this,
                    IControlsTethering.STATE_UNAVAILABLE, mLastError);
        }
    }
}
