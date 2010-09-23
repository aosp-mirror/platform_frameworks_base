/*
 * Copyright (C) 2010 The Android Open Source Project
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

/**
 * File            : NativeNfcManager.java
 * Original-Author : Trusted Logic S.A. (Sylvain Fonteneau)
 * Created         : 18-02-2010
 */

package com.trustedlogic.trustednfc.android.internal;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.trustedlogic.trustednfc.android.NfcManager;
import com.trustedlogic.trustednfc.android.NdefMessage;
import com.trustedlogic.trustednfc.android.NfcTag;

/**
 * Native interface to the NFC Manager functions {@hide}
 */
public class NativeNfcManager {
    
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_LLCP_LINK_STATE_CHANGED_EXTRA = "com.trustedlogic.trustednfc.android.extra.INTERNAL_LLCP_LINK_STATE";

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION = "com.trustedlogic.trustednfc.android.action.INTERNAL_LLCP_LINK_STATE_CHANGED";
    
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_TARGET_DESELECTED_ACTION = "com.trustedlogic.trustednfc.android.action.INTERNAL_TARGET_DESELECTED";

    /* Native structure */
    private int mNative;

    private Context mContext;

    private Handler mNfcHandler;

    private static final String TAG = "NativeNfcManager";

    private static final int MSG_NDEF_TAG = 0;

    private static final int MSG_CARD_EMULATION = 1;

    private static final int MSG_LLCP_LINK_ACTIVATION = 2;

    private static final int MSG_LLCP_LINK_DEACTIVATED = 3;

    private static final int MSG_TARGET_DESELECTED = 4;

    public NativeNfcManager(Context context) {
        mNfcHandler = new NfcHandler();
        mContext = context;
    }

    /**
     * Initializes Native structure
     */
    public native boolean initializeNativeStructure();

    /**
     * Initializes NFC stack.
     */
    public native boolean initialize();

    /**
     * Deinitializes NFC stack.
     */
    public native boolean deinitialize();

    /**
     * Enable discory for the NdefMessage and Transaction notification
     */
    public native void enableDiscovery(int mode);

    /**
     * Disables an NFCManager mode of operation. Allows to disable tag reader,
     * peer to peer initiator or target modes.
     * 
     * @param mode discovery mode to enable. Must be one of the provided
     *            NFCManager.DISCOVERY_MODE_* constants.
     */
    public native void disableDiscoveryMode(int mode);

    public native int[] doGetSecureElementList();

    public native void doSelectSecureElement(int seID);

    public native void doDeselectSecureElement(int seID);

    public native NativeP2pDevice doOpenP2pConnection(int timeout);

    public native NativeNfcTag doOpenTagConnection(int timeout);

    public native int doGetLastError();

    public native void doSetProperties(int param, int value);

    public native void doCancel();

    public native NativeLlcpConnectionlessSocket doCreateLlcpConnectionlessSocket(int nSap);

    public native NativeLlcpServiceSocket doCreateLlcpServiceSocket(int nSap, String sn, int miu,
            int rw, int linearBufferLength);

    public native NativeLlcpSocket doCreateLlcpSocket(int sap, int miu, int rw,
            int linearBufferLength);

    public native boolean doCheckLlcp();

    public native boolean doActivateLlcp();

    private class NfcHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {

            try {
                switch (msg.what) {
                    case MSG_NDEF_TAG:
                        Log.d(TAG, "Checking for NDEF tag message");
                        NativeNfcTag tag = (NativeNfcTag) msg.obj;
                        if (tag.doConnect()) {
                            if (tag.checkNDEF()) {
                                byte[] buff = tag.doRead();
                                if (buff != null) {
                                    NdefMessage msgNdef = new NdefMessage(buff);
                                    if (msgNdef != null) {
                                        /* Send broadcast ordered */
                                        Intent NdefMessageIntent = new Intent();
                                        NdefMessageIntent
                                                .setAction(NfcManager.NDEF_TAG_DISCOVERED_ACTION);
                                        NdefMessageIntent.putExtra(NfcManager.NDEF_MESSAGE_EXTRA,
                                                msgNdef);
                                        Log.d(TAG, "NDEF message found, broadcasting to applications");
                                        mContext.sendOrderedBroadcast(NdefMessageIntent,
                                                android.Manifest.permission.NFC_NOTIFY);
                                        /* Disconnect tag */
                                        tag.doAsyncDisconnect();
                                    }
                                } else {
                                   Log.w(TAG, "Unable to read NDEF message (tag empty or not well formated)");
                                    /* Disconnect tag */
                                    tag.doAsyncDisconnect();
                                }
                            } else {
                                Log.d(TAG, "Tag is *not* NDEF compliant");
                                /* Disconnect tag */
                                tag.doAsyncDisconnect();
                            }
                        } else {
                            /* Disconnect tag */
                            tag.doAsyncDisconnect();
                        }
                        break;
                    case MSG_CARD_EMULATION:
                        Log.d(TAG, "Card Emulation message");
                        byte[] aid = (byte[]) msg.obj;
                        /* Send broadcast ordered */
                        Intent TransactionIntent = new Intent();
                        TransactionIntent.setAction(NfcManager.TRANSACTION_DETECTED_ACTION);
                        TransactionIntent.putExtra(NfcManager.AID_EXTRA, aid);
                        Log.d(TAG, "Broadcasting Card Emulation event");
                        mContext.sendOrderedBroadcast(TransactionIntent,
                                android.Manifest.permission.NFC_NOTIFY);
                        break;

                    case MSG_LLCP_LINK_ACTIVATION:
                        NativeP2pDevice device = (NativeP2pDevice) msg.obj;

                        Log.d(TAG, "LLCP Activation message");

                        if (device.getMode() == NativeP2pDevice.MODE_P2P_TARGET) {
                            if (device.doConnect()) {
                                /* Check Llcp compliancy */
                                if (doCheckLlcp()) {
                                    /* Activate Llcp Link */
                                    if (doActivateLlcp()) {
                                        Log.d(TAG, "Initiator Activate LLCP OK");
                                        /* Broadcast Intent Link LLCP activated */
                                        Intent LlcpLinkIntent = new Intent();
                                        LlcpLinkIntent
                                                .setAction(INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION);
                                        LlcpLinkIntent.putExtra(
                                                INTERNAL_LLCP_LINK_STATE_CHANGED_EXTRA,
                                                NfcManager.LLCP_LINK_STATE_ACTIVATED);
                                        Log.d(TAG, "Broadcasting internal LLCP activation");
                                        mContext.sendBroadcast(LlcpLinkIntent);
                                    }

                                } else {
                                    device.doDisconnect();
                                }

                            }

                        } else if (device.getMode() == NativeP2pDevice.MODE_P2P_INITIATOR) {
                            /* Check Llcp compliancy */
                            if (doCheckLlcp()) {
                                /* Activate Llcp Link */
                                if (doActivateLlcp()) {
                                    Log.d(TAG, "Target Activate LLCP OK");
                                    /* Broadcast Intent Link LLCP activated */
                                    Intent LlcpLinkIntent = new Intent();
                                    LlcpLinkIntent
                                            .setAction(INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION);
                                    LlcpLinkIntent.putExtra(INTERNAL_LLCP_LINK_STATE_CHANGED_EXTRA,
                                            NfcManager.LLCP_LINK_STATE_ACTIVATED);
                                    Log.d(TAG, "Broadcasting internal LLCP activation");
                                    mContext.sendBroadcast(LlcpLinkIntent);
                                }
                            }
                        }
                        break;

                    case MSG_LLCP_LINK_DEACTIVATED:
                        /* Broadcast Intent Link LLCP activated */
                        Log.d(TAG, "LLCP Link Deactivated message");
                        Intent LlcpLinkIntent = new Intent();
                        LlcpLinkIntent.setAction(NfcManager.LLCP_LINK_STATE_CHANGED_ACTION);
                        LlcpLinkIntent.putExtra(NfcManager.LLCP_LINK_STATE_CHANGED_EXTRA,
                                NfcManager.LLCP_LINK_STATE_DEACTIVATED);
                        Log.d(TAG, "Broadcasting LLCP deactivation");
                        mContext.sendOrderedBroadcast(LlcpLinkIntent,
                                android.Manifest.permission.NFC_LLCP);
                        break;

                    case MSG_TARGET_DESELECTED:
                        /* Broadcast Intent Target Deselected */
                        Log.d(TAG, "Target Deselected");
                        Intent TargetDeselectedIntent = new Intent();
                        TargetDeselectedIntent.setAction(INTERNAL_TARGET_DESELECTED_ACTION);
                        Log.d(TAG, "Broadcasting Intent");
                        mContext.sendOrderedBroadcast(TargetDeselectedIntent,
                                android.Manifest.permission.NFC_LLCP);
                        break;

                    default:
                        Log.e(TAG, "Unknown message received");
                        break;
                }
            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in NfcHandler.handleMessage:", e);
            }
        }
    };

    /**
     * Notifies Ndef Message
     */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        Message msg = mNfcHandler.obtainMessage();

        msg.what = MSG_NDEF_TAG;
        msg.obj = tag;

        mNfcHandler.sendMessage(msg);
    }

    /**
     * Notifies transaction
     */
    private void notifyTargetDeselected() {
        Message msg = mNfcHandler.obtainMessage();

        msg.what = MSG_TARGET_DESELECTED;

        mNfcHandler.sendMessage(msg);
    }

    /**
     * Notifies transaction
     */
    private void notifyTransactionListeners(byte[] aid) {
        Message msg = mNfcHandler.obtainMessage();

        msg.what = MSG_CARD_EMULATION;
        msg.obj = aid;

        mNfcHandler.sendMessage(msg);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkActivation(NativeP2pDevice device) {
        Message msg = mNfcHandler.obtainMessage();

        msg.what = MSG_LLCP_LINK_ACTIVATION;
        msg.obj = device;

        mNfcHandler.sendMessage(msg);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkDeactivated() {
        Message msg = mNfcHandler.obtainMessage();

        msg.what = MSG_LLCP_LINK_DEACTIVATED;

        mNfcHandler.sendMessage(msg);
    }

}
