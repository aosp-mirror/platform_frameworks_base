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

package com.trustedlogic.trustednfc.android.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Set;

import com.trustedlogic.trustednfc.android.ILlcpConnectionlessSocket;
import com.trustedlogic.trustednfc.android.ILlcpServiceSocket;
import com.trustedlogic.trustednfc.android.INfcManager;
import com.trustedlogic.trustednfc.android.ILlcpSocket;
import com.trustedlogic.trustednfc.android.INfcTag;
import com.trustedlogic.trustednfc.android.IP2pInitiator;
import com.trustedlogic.trustednfc.android.IP2pTarget;
import com.trustedlogic.trustednfc.android.LlcpPacket;
import com.trustedlogic.trustednfc.android.NdefMessage;
import com.trustedlogic.trustednfc.android.NfcException;
import com.trustedlogic.trustednfc.android.NfcManager;
import com.trustedlogic.trustednfc.android.internal.NativeLlcpConnectionlessSocket;
import com.trustedlogic.trustednfc.android.internal.NativeLlcpServiceSocket;
import com.trustedlogic.trustednfc.android.internal.NativeLlcpSocket;
import com.trustedlogic.trustednfc.android.internal.NativeNfcManager;
import com.trustedlogic.trustednfc.android.internal.NativeNfcTag;
import com.trustedlogic.trustednfc.android.internal.NativeP2pDevice;
import com.trustedlogic.trustednfc.android.internal.ErrorCodes;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class NfcService extends INfcManager.Stub implements Runnable {

    /**
     * NFC Service tag
     */
    private static final String TAG = "NfcService";

    /**
     * NFC features disabled state
     */
    private static final short NFC_STATE_DISABLED = 0x00;

    /**
     * NFC features enabled state
     */
    private static final short NFC_STATE_ENABLED = 0x01;

    /**
     * NFC Discovery for Reader mode
     */
    private static final int DISCOVERY_MODE_READER = 0;

    /**
     * NFC Discovery for Card Emulation Mode
     */
    private static final int DISCOVERY_MODE_CARD_EMULATION = 2;

    /**
     * LLCP Service Socket type
     */
    private static final int LLCP_SERVICE_SOCKET_TYPE = 0;

    /**
     * LLCP Socket type
     */
    private static final int LLCP_SOCKET_TYPE = 1;

    /**
     * LLCP Connectionless socket type
     */
    private static final int LLCP_CONNECTIONLESS_SOCKET_TYPE = 2;

    /**
     * Maximun number of sockets managed
     */
    private static final int LLCP_SOCKET_NB_MAX = 5;

    /**
     * Default value for the Maximum Information Unit parameter
     */
    private static final int LLCP_LTO_DEFAULT_VALUE = 150;

    /**
     * Default value for the Maximum Information Unit parameter
     */
    private static final int LLCP_LTO_MAX_VALUE = 255;

    /**
     * Maximun value for the Receive Window
     */
    private static final int LLCP_RW_MAX_VALUE = 15;

    /**
     * Default value for the Maximum Information Unit parameter
     */
    private static final int LLCP_MIU_DEFAULT_VALUE = 128;

    /**
     * Default value for the Maximum Information Unit parameter
     */
    private static final int LLCP_MIU_MAX_VALUE = 2176;

    /**
     * Default value for the Well Known Service List parameter
     */
    private static final int LLCP_WKS_DEFAULT_VALUE = 1;

    /**
     * Max value for the Well Known Service List parameter
     */
    private static final int LLCP_WKS_MAX_VALUE = 15;

    /**
     * Default value for the Option parameter
     */
    private static final int LLCP_OPT_DEFAULT_VALUE = 0;

    /**
     * Max value for the Option parameter
     */
    private static final int LLCP_OPT_MAX_VALUE = 3;

    /**
     * LLCP Properties
     */
    private static final int PROPERTY_LLCP_LTO = 0;

    private static final int PROPERTY_LLCP_MIU = 1;

    private static final int PROPERTY_LLCP_WKS = 2;

    private static final int PROPERTY_LLCP_OPT = 3;

    private static final String PROPERTY_LLCP_LTO_VALUE = "llcp.lto";

    private static final String PROPERTY_LLCP_MIU_VALUE = "llcp.miu";

    private static final String PROPERTY_LLCP_WKS_VALUE = "llcp.wks";

    private static final String PROPERTY_LLCP_OPT_VALUE = "llcp.opt";

    /**
     * NFC Reader Properties
     */
    private static final int PROPERTY_NFC_DISCOVERY_A = 4;

    private static final int PROPERTY_NFC_DISCOVERY_B = 5;

    private static final int PROPERTY_NFC_DISCOVERY_F = 6;

    private static final int PROPERTY_NFC_DISCOVERY_15693 = 7;

    private static final int PROPERTY_NFC_DISCOVERY_NFCIP = 8;

    private static final String PROPERTY_NFC_DISCOVERY_A_VALUE = "discovery.iso14443A";

    private static final String PROPERTY_NFC_DISCOVERY_B_VALUE = "discovery.iso14443B";

    private static final String PROPERTY_NFC_DISCOVERY_F_VALUE = "discovery.felica";

    private static final String PROPERTY_NFC_DISCOVERY_15693_VALUE = "discovery.iso15693";

    private static final String PROPERTY_NFC_DISCOVERY_NFCIP_VALUE = "discovery.nfcip";

    private Context mContext;

    private HashMap<Integer, Object> mObjectMap = new HashMap<Integer, Object>();

    private HashMap<Integer, Object> mSocketMap = new HashMap<Integer, Object>();

    private LinkedList<RegisteredSocket> mRegisteredSocketList = new LinkedList<RegisteredSocket>();

    private int mLlcpLinkState = NfcManager.LLCP_LINK_STATE_DEACTIVATED;

    private int mGeneratedSocketHandle = 0;

    private int mNbSocketCreated = 0;

    private boolean mIsNfcEnabled = false;

    private NfcHandler mNfcHandler;

    private int mSelectedSeId = 0;

    private int mTimeout = 0;

    private int mNfcState;

    private int mNfcSecureElementState;

    private boolean mOpenPending = false;

    private NativeNfcManager mManager;

    private ILlcpSocket mLlcpSocket = new ILlcpSocket.Stub() {

        public int close(int nativeHandle) throws RemoteException {
            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                        return ErrorCodes.SUCCESS;
                    } else {
                        return ErrorCodes.ERROR_IO;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;

                    return ErrorCodes.SUCCESS;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        public int connect(int nativeHandle, int sap) throws RemoteException {
            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnect(sap, socket.getConnectTimeout());
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        public int connectByName(int nativeHandle, String sn) throws RemoteException {
            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doConnectBy(sn, socket.getConnectTimeout());
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }

        }

        public int getConnectTimeout(int nativeHandle) throws RemoteException {
            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getConnectTimeout();
            } else {
                return 0;
            }
        }

        public int getLocalSap(int nativeHandle) throws RemoteException {
            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        public int getLocalSocketMiu(int nativeHandle) throws RemoteException {
            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getMiu();
            } else {
                return 0;
            }
        }

        public int getLocalSocketRw(int nativeHandle) throws RemoteException {
            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getRw();
            } else {
                return 0;
            }
        }

        public int getRemoteSocketMiu(int nativeHandle) throws RemoteException {
            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (socket.doGetRemoteSocketMiu() != 0) {
                    return socket.doGetRemoteSocketMiu();
                } else {
                    return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
                }
            } else {
                return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
            }
        }

        public int getRemoteSocketRw(int nativeHandle) throws RemoteException {
            NativeLlcpSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (socket.doGetRemoteSocketRw() != 0) {
                    return socket.doGetRemoteSocketRw();
                } else {
                    return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
                }
            } else {
                return ErrorCodes.ERROR_SOCKET_NOT_CONNECTED;
            }
        }

        public int receive(int nativeHandle, byte[] receiveBuffer) throws RemoteException {
            NativeLlcpSocket socket = null;
            int receiveLength = 0;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                receiveLength = socket.doReceive(receiveBuffer);
                if (receiveLength != 0) {
                    return receiveLength;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        public int send(int nativeHandle, byte[] data) throws RemoteException {
            NativeLlcpSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doSend(data);
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }

        public void setConnectTimeout(int nativeHandle, int timeout) throws RemoteException {
            NativeLlcpSocket socket = null;

            /* find the socket in the hmap */
            socket = (NativeLlcpSocket) findSocket(nativeHandle);
            if (socket != null) {
                socket.setConnectTimeout(timeout);
            }
        }

    };

    private ILlcpServiceSocket mLlcpServerSocketService = new ILlcpServiceSocket.Stub() {

        public int accept(int nativeHandle) throws RemoteException {
            NativeLlcpServiceSocket socket = null;
            NativeLlcpSocket clientSocket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
                /* find the socket in the hmap */
                socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
                if (socket != null) {
                    clientSocket = socket.doAccept(socket.getAcceptTimeout(), socket.getMiu(),
                            socket.getRw(), socket.getLinearBufferLength());
                    if (clientSocket != null) {
                        /* Add the socket into the socket map */
                        mSocketMap.put(clientSocket.getHandle(), clientSocket);
                        mNbSocketCreated++;
                        return clientSocket.getHandle();
                    } else {
                        return ErrorCodes.ERROR_IO;
                    }
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
            }

        }

        public void close(int nativeHandle) throws RemoteException {
            NativeLlcpServiceSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;
                }
            }
        }

        public int getAcceptTimeout(int nativeHandle) throws RemoteException {
            NativeLlcpServiceSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getAcceptTimeout();
            } else {
                return 0;
            }
        }

        public void setAcceptTimeout(int nativeHandle, int timeout) throws RemoteException {
            NativeLlcpServiceSocket socket = null;

            /* find the socket in the hmap */
            socket = (NativeLlcpServiceSocket) findSocket(nativeHandle);
            if (socket != null) {
                socket.setAcceptTimeout(timeout);
            }
        }
    };

    private ILlcpConnectionlessSocket mLlcpConnectionlessSocketService = new ILlcpConnectionlessSocket.Stub() {

        public void close(int nativeHandle) throws RemoteException {
            NativeLlcpConnectionlessSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_ACTIVATED) {
                    isSuccess = socket.doClose();
                    if (isSuccess) {
                        /* Remove the socket closed from the hmap */
                        RemoveSocket(nativeHandle);
                        /* Update mNbSocketCreated */
                        mNbSocketCreated--;
                    }
                } else {
                    /* Remove the socket closed from the hmap */
                    RemoveSocket(nativeHandle);

                    /* Remove registered socket from the list */
                    RemoveRegisteredSocket(nativeHandle);

                    /* Update mNbSocketCreated */
                    mNbSocketCreated--;
                }
            }
        }

        public int getSap(int nativeHandle) throws RemoteException {
            NativeLlcpConnectionlessSocket socket = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                return socket.getSap();
            } else {
                return 0;
            }
        }

        public LlcpPacket receiveFrom(int nativeHandle) throws RemoteException {
            NativeLlcpConnectionlessSocket socket = null;
            LlcpPacket packet;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                packet = socket.doReceiveFrom(socket.getLinkMiu());
                if (packet != null) {
                    return packet;
                }
                return null;
            } else {
                return null;
            }
        }

        public int sendTo(int nativeHandle, LlcpPacket packet) throws RemoteException {
            NativeLlcpConnectionlessSocket socket = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the socket in the hmap */
            socket = (NativeLlcpConnectionlessSocket) findSocket(nativeHandle);
            if (socket != null) {
                isSuccess = socket.doSendTo(packet.getRemoteSap(), packet.getDataBuffer());
                if (isSuccess) {
                    return ErrorCodes.SUCCESS;
                } else {
                    return ErrorCodes.ERROR_IO;
                }
            } else {
                return ErrorCodes.ERROR_IO;
            }
        }
    };

    private INfcTag mNfcTagService = new INfcTag.Stub() {

        public int close(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                if (tag.doDisconnect()) {
                    /* Remove the device from the hmap */
                    RemoveObject(nativeHandle);
                    /* Restart polling loop for notification */
                    mManager.enableDiscovery(DISCOVERY_MODE_READER);
                    mOpenPending = false;
                    return ErrorCodes.SUCCESS;
                }

            }
            /* Restart polling loop for notification */
            mManager.enableDiscovery(DISCOVERY_MODE_READER);
            mOpenPending = false;
            return ErrorCodes.ERROR_DISCONNECT;
        }

        public int connect(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                if (tag.doConnect())
                    return ErrorCodes.SUCCESS;
            }
            /* Restart polling loop for notification */
            mManager.enableDiscovery(DISCOVERY_MODE_READER);
            mOpenPending = false;
            return ErrorCodes.ERROR_CONNECT;
        }

        public String getType(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            String type;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                type = tag.getType();
                return type;
            }
            return null;
        }

        public byte[] getUid(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            byte[] uid;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                uid = tag.getUid();
                return uid;
            }
            return null;
        }

        public boolean isNdef(int nativeHandle) throws RemoteException {
            NativeNfcTag tag = null;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                isSuccess = tag.checkNDEF();
            }
            return isSuccess;
        }

        public byte[] transceive(int nativeHandle, byte[] data) throws RemoteException {
            NativeNfcTag tag = null;
            byte[] response;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                response = tag.doTransceive(data);
                return response;
            }
            return null;
        }

        public NdefMessage read(int nativeHandle) throws RemoteException {
            NativeNfcTag tag;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                byte[] buf = tag.doRead();
                if (buf == null)
                    return null;

                /* Create an NdefMessage */
                try {
                    return new NdefMessage(buf);
                } catch (NfcException e) {
                    return null;
                }
            }
            return null;
        }

        public boolean write(int nativeHandle, NdefMessage msg) throws RemoteException {
            NativeNfcTag tag;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the tag in the hmap */
            tag = (NativeNfcTag) findObject(nativeHandle);
            if (tag != null) {
                isSuccess = tag.doWrite(msg.toByteArray());
            }
            return isSuccess;

        }

    };

    private IP2pInitiator mP2pInitiatorService = new IP2pInitiator.Stub() {

        public byte[] getGeneralBytes(int nativeHandle) throws RemoteException {
            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.getGeneralBytes();
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }

        public int getMode(int nativeHandle) throws RemoteException {
            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        public byte[] receive(int nativeHandle) throws RemoteException {
            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.doReceive();
                if (buff == null)
                    return null;
                return buff;
            }
            /* Restart polling loop for notification */
            mManager.enableDiscovery(DISCOVERY_MODE_READER);
            mOpenPending = false;
            return null;
        }

        public boolean send(int nativeHandle, byte[] data) throws RemoteException {
            NativeP2pDevice device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                isSuccess = device.doSend(data);
            }
            return isSuccess;
        }
    };

    private IP2pTarget mP2pTargetService = new IP2pTarget.Stub() {

        public int connect(int nativeHandle) throws RemoteException {
            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                if (device.doConnect()) {
                    return ErrorCodes.SUCCESS;
                }
            }
            return ErrorCodes.ERROR_CONNECT;
        }

        public boolean disconnect(int nativeHandle) throws RemoteException {
            NativeP2pDevice device;
            boolean isSuccess = false;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return isSuccess;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                if (isSuccess = device.doDisconnect()) {
                    mOpenPending = false;
                    /* remove the device from the hmap */
                    RemoveObject(nativeHandle);
                    /* Restart polling loop for notification */
                    mManager.enableDiscovery(DISCOVERY_MODE_READER);
                }
            }
            return isSuccess;

        }

        public byte[] getGeneralBytes(int nativeHandle) throws RemoteException {
            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.getGeneralBytes();
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }

        public int getMode(int nativeHandle) throws RemoteException {
            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return ErrorCodes.ERROR_NOT_INITIALIZED;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                return device.getMode();
            }
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        public byte[] transceive(int nativeHandle, byte[] data) throws RemoteException {
            NativeP2pDevice device;

            // Check if NFC is enabled
            if (!mIsNfcEnabled) {
                return null;
            }

            /* find the device in the hmap */
            device = (NativeP2pDevice) findObject(nativeHandle);
            if (device != null) {
                byte[] buff = device.doTransceive(data);
                if (buff == null)
                    return null;
                return buff;
            }
            return null;
        }
    };

    private class NfcHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            try {

            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in NfcHandler.handleMessage:", e);
            }
        }

    };

    public NfcService(Context context) {
        super();
        mContext = context;
        mManager = new NativeNfcManager(mContext);

        mContext.registerReceiver(mNfcServiceReceiver, new IntentFilter(
                NativeNfcManager.INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION));

        mContext.registerReceiver(mNfcServiceReceiver, new IntentFilter(
                NfcManager.LLCP_LINK_STATE_CHANGED_ACTION));
        
        mContext.registerReceiver(mNfcServiceReceiver, new IntentFilter(
                NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION));

        Thread thread = new Thread(null, this, "NfcService");
        thread.start();

        mManager.initializeNativeStructure();

            int nfcState = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NFC_ON, 0);

            if (nfcState == NFC_STATE_ENABLED) {
                if (this._enable()) {
                }
            }

    }

    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Looper.prepare();
        mNfcHandler = new NfcHandler();
        Looper.loop();
    }

    public void cancel() throws RemoteException {
        mContext.enforceCallingPermission(android.Manifest.permission.NFC_RAW,
                "NFC_RAW permission required to cancel NFC opening");
        if (mOpenPending) {
            mOpenPending = false;
            mManager.doCancel();
            /* Restart polling loop for notification */
            mManager.enableDiscovery(DISCOVERY_MODE_READER);
        }
    }

    public int createLlcpConnectionlessSocket(int sap) throws RemoteException {

        // Check if NFC is enabled
        if (!mIsNfcEnabled) {
            return ErrorCodes.ERROR_NOT_INITIALIZED;
        }

        mContext.enforceCallingPermission(android.Manifest.permission.NFC_LLCP,
                "NFC_LLCP permission required for LLCP operations with NFC service");

        /* Check SAP is not already used */

        /* Check nb socket created */
        if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
            /* Store the socket handle */
            int sockeHandle = mGeneratedSocketHandle;

            if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_ACTIVATED) {
                NativeLlcpConnectionlessSocket socket;

                socket = mManager.doCreateLlcpConnectionlessSocket(sap);
                if (socket != null) {
                    /* Update the number of socket created */
                    mNbSocketCreated++;

                    /* Add the socket into the socket map */
                    mSocketMap.put(sockeHandle, socket);

                    return sockeHandle;
                } else {
                    /*
                     * socket creation error - update the socket handle
                     * generation
                     */
                    mGeneratedSocketHandle -= 1;

                    /* Get Error Status */
                    int errorStatus = mManager.doGetLastError();

                    switch (errorStatus) {
                        case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                            return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                        case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                            return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                        default:
                            return ErrorCodes.ERROR_SOCKET_CREATION;
                    }
                }
            } else {
                /* Check SAP is not already used */
                if (!CheckSocketSap(sap)) {
                    return ErrorCodes.ERROR_SAP_USED;
                }

                NativeLlcpConnectionlessSocket socket = new NativeLlcpConnectionlessSocket(sap);

                /* Add the socket into the socket map */
                mSocketMap.put(sockeHandle, socket);

                /* Update the number of socket created */
                mNbSocketCreated++;

                /* Create new registered socket */
                RegisteredSocket registeredSocket = new RegisteredSocket(
                        LLCP_CONNECTIONLESS_SOCKET_TYPE, sockeHandle, sap);

                /* Put this socket into a list of registered socket */
                mRegisteredSocketList.add(registeredSocket);
            }

            /* update socket handle generation */
            mGeneratedSocketHandle++;

            return sockeHandle;

        } else {
            /* No socket available */
            return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
        }

    }

    public int createLlcpServiceSocket(int sap, String sn, int miu, int rw, int linearBufferLength)
            throws RemoteException {

        // Check if NFC is enabled
        if (!mIsNfcEnabled) {
            return ErrorCodes.ERROR_NOT_INITIALIZED;
        }

        mContext.enforceCallingPermission(android.Manifest.permission.NFC_LLCP,
                "NFC_LLCP permission required for LLCP operations with NFC service");

        if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {
            int sockeHandle = mGeneratedSocketHandle;

            if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_ACTIVATED) {
                NativeLlcpServiceSocket socket;

                socket = mManager.doCreateLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
                if (socket != null) {
                    /* Update the number of socket created */
                    mNbSocketCreated++;
                    /* Add the socket into the socket map */
                    mSocketMap.put(sockeHandle, socket);
                } else {
                    /* socket creation error - update the socket handle counter */
                    mGeneratedSocketHandle -= 1;

                    /* Get Error Status */
                    int errorStatus = mManager.doGetLastError();

                    switch (errorStatus) {
                        case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                            return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                        case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                            return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                        default:
                            return ErrorCodes.ERROR_SOCKET_CREATION;
                    }
                }
            } else {

                /* Check SAP is not already used */
                if (!CheckSocketSap(sap)) {
                    return ErrorCodes.ERROR_SAP_USED;
                }

                /* Service Name */
                if (!CheckSocketServiceName(sn)) {
                    return ErrorCodes.ERROR_SERVICE_NAME_USED;
                }

                /* Check socket options */
                if (!CheckSocketOptions(miu, rw, linearBufferLength)) {
                    return ErrorCodes.ERROR_SOCKET_OPTIONS;
                }

                NativeLlcpServiceSocket socket = new NativeLlcpServiceSocket(sn);

                /* Add the socket into the socket map */
                mSocketMap.put(sockeHandle, socket);

                /* Update the number of socket created */
                mNbSocketCreated++;

                /* Create new registered socket */
                RegisteredSocket registeredSocket = new RegisteredSocket(LLCP_SERVICE_SOCKET_TYPE,
                        sockeHandle, sap, sn, miu, rw, linearBufferLength);

                /* Put this socket into a list of registered socket */
                mRegisteredSocketList.add(registeredSocket);
            }

            /* update socket handle generation */
            mGeneratedSocketHandle += 1;

            Log.d(TAG, "Llcp Service Socket Handle =" + sockeHandle);
            return sockeHandle;
        } else {
            /* No socket available */
            return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
        }
    }

    public int createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
            throws RemoteException {

        // Check if NFC is enabled
        if (!mIsNfcEnabled) {
            return ErrorCodes.ERROR_NOT_INITIALIZED;
        }

        mContext.enforceCallingPermission(android.Manifest.permission.NFC_LLCP,
                "NFC_LLCP permission required for LLCP operations with NFC service");

        if (mNbSocketCreated < LLCP_SOCKET_NB_MAX) {

            int sockeHandle = mGeneratedSocketHandle;

            if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_ACTIVATED) {
                NativeLlcpSocket socket;

                socket = mManager.doCreateLlcpSocket(sap, miu, rw, linearBufferLength);

                if (socket != null) {
                    /* Update the number of socket created */
                    mNbSocketCreated++;
                    /* Add the socket into the socket map */
                    mSocketMap.put(sockeHandle, socket);
                } else {
                    /*
                     * socket creation error - update the socket handle
                     * generation
                     */
                    mGeneratedSocketHandle -= 1;

                    /* Get Error Status */
                    int errorStatus = mManager.doGetLastError();

                    switch (errorStatus) {
                        case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                            return ErrorCodes.ERROR_BUFFER_TO_SMALL;
                        case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                            return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
                        default:
                            return ErrorCodes.ERROR_SOCKET_CREATION;
                    }
                }
            } else {

                /* Check SAP is not already used */
                if (!CheckSocketSap(sap)) {
                    return ErrorCodes.ERROR_SAP_USED;
                }

                /* Check Socket options */
                if (!CheckSocketOptions(miu, rw, linearBufferLength)) {
                    return ErrorCodes.ERROR_SOCKET_OPTIONS;
                }

                NativeLlcpSocket socket = new NativeLlcpSocket(sap, miu, rw);

                /* Add the socket into the socket map */
                mSocketMap.put(sockeHandle, socket);

                /* Update the number of socket created */
                mNbSocketCreated++;
                /* Create new registered socket */
                RegisteredSocket registeredSocket = new RegisteredSocket(LLCP_SOCKET_TYPE,
                        sockeHandle, sap, miu, rw, linearBufferLength);

                /* Put this socket into a list of registered socket */
                mRegisteredSocketList.add(registeredSocket);
            }

            /* update socket handle generation */
            mGeneratedSocketHandle++;

            return sockeHandle;
        } else {
            /* No socket available */
            return ErrorCodes.ERROR_INSUFFICIENT_RESOURCES;
        }
    }

    public int deselectSecureElement() throws RemoteException {
        // Check if NFC is enabled
        if (!mIsNfcEnabled) {
            return ErrorCodes.ERROR_NOT_INITIALIZED;
        }

        if (mSelectedSeId == 0) {
            return ErrorCodes.ERROR_NO_SE_CONNECTED;
        }

        mContext.enforceCallingPermission(android.Manifest.permission.NFC_ADMIN,
                "NFC_ADMIN permission required to deselect NFC Secure Element");

            mManager.doDeselectSecureElement(mSelectedSeId);
        mNfcSecureElementState = 0;
            mSelectedSeId = 0;
        
        /* Store that a secure element is deselected */
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.NFC_SECURE_ELEMENT_ON, 0);

        /* Reset Secure Element ID */
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.NFC_SECURE_ELEMENT_ID, 0);
        

        return ErrorCodes.SUCCESS; 
    }

    public boolean disable() throws RemoteException {
        boolean isSuccess = false;
        mContext.enforceCallingPermission(android.Manifest.permission.NFC_ADMIN,
                "NFC_ADMIN permission required to disable NFC service");
        if (isEnabled()) {
            isSuccess = mManager.deinitialize();
            if (isSuccess) {
                mIsNfcEnabled = false;
            }
        }

        updateNfcOnSetting();

        return isSuccess;
    }

    public boolean enable() throws RemoteException {
        boolean isSuccess = false;
        mContext.enforceCallingPermission(android.Manifest.permission.NFC_ADMIN,
                "NFC_ADMIN permission required to enable NFC service");
        if (!isEnabled()) {
            reset();
            isSuccess = _enable();
        }
        return isSuccess;
    }

    private boolean _enable() {
        boolean isSuccess = mManager.initialize();
        if (isSuccess) {
            /* Check persistent properties */
            checkProperties();

            /* Check Secure Element setting */
            mNfcSecureElementState = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_SECURE_ELEMENT_ON, 0);

            if (mNfcSecureElementState == 1) {

                int secureElementId = Settings.System.getInt(mContext.getContentResolver(),
                        Settings.System.NFC_SECURE_ELEMENT_ID, 0);
                int[] Se_list = mManager.doGetSecureElementList();
                if (Se_list != null) {
                    for (int i = 0; i < Se_list.length; i++) {
                        if (Se_list[i] == secureElementId) {
                            mManager.doSelectSecureElement(Se_list[i]);
                            mSelectedSeId = Se_list[i];
                            break;
                        }
                    }
                }
            }

            /* Start polling loop */
            mManager.enableDiscovery(DISCOVERY_MODE_READER);

            mIsNfcEnabled = true;
        } else {
            mIsNfcEnabled = false;
        }

        updateNfcOnSetting();

        return isSuccess;
    }

    private void updateNfcOnSetting() {
        int state;

        if (mIsNfcEnabled) {
            state = NFC_STATE_ENABLED;
        } else {
            state = NFC_STATE_DISABLED;
        }

        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_ON, state);
    }

    private void checkProperties() {
        int value;

        /* LLCP LTO */
        value = Settings.System.getInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_LTO,
                LLCP_LTO_DEFAULT_VALUE);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_LTO, value);
        mManager.doSetProperties(PROPERTY_LLCP_LTO, value);

        /* LLCP MIU */
        value = Settings.System.getInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_MIU,
                LLCP_MIU_DEFAULT_VALUE);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_MIU, value);
        mManager.doSetProperties(PROPERTY_LLCP_MIU, value);

        /* LLCP WKS */
        value = Settings.System.getInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_WKS,
                LLCP_WKS_DEFAULT_VALUE);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_WKS, value);
        mManager.doSetProperties(PROPERTY_LLCP_WKS, value);

        /* LLCP OPT */
        value = Settings.System.getInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_OPT,
                LLCP_OPT_DEFAULT_VALUE);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_OPT, value);
        mManager.doSetProperties(PROPERTY_LLCP_OPT, value);

        /* NFC READER A */
        value = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NFC_DISCOVERY_A, 1);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_A,
                value);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_A, value);

        /* NFC READER B */
        value = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NFC_DISCOVERY_B, 1);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_B,
                value);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_B, value);

        /* NFC READER F */
        value = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NFC_DISCOVERY_F, 1);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_F,
                value);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_F, value);

        /* NFC READER 15693 */
        value = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NFC_DISCOVERY_15693, 1);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_15693,
                value);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_15693, value);

        /* NFC NFCIP */
        value = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.NFC_DISCOVERY_NFCIP, 1);
        Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_NFCIP,
                value);
        mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_NFCIP, value);
    }

    public ILlcpConnectionlessSocket getLlcpConnectionlessInterface() throws RemoteException {
        return mLlcpConnectionlessSocketService;
    }

    public ILlcpSocket getLlcpInterface() throws RemoteException {
        return mLlcpSocket;
    }

    public ILlcpServiceSocket getLlcpServiceInterface() throws RemoteException {
        return mLlcpServerSocketService;
    }

    public INfcTag getNfcTagInterface() throws RemoteException {
        return mNfcTagService;
    }

    public int getOpenTimeout() throws RemoteException {
        return mTimeout;
    }

    public IP2pInitiator getP2pInitiatorInterface() throws RemoteException {
        return mP2pInitiatorService;
    }

    public IP2pTarget getP2pTargetInterface() throws RemoteException {
        return mP2pTargetService;
    }

    public String getProperties(String param) throws RemoteException {
        int value;

        if (param == null) {
            return "Wrong parameter";
        }

        if (param.equals(PROPERTY_LLCP_LTO_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_LLCP_LTO, 0);
        } else if (param.equals(PROPERTY_LLCP_MIU_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_LLCP_MIU, 0);
        } else if (param.equals(PROPERTY_LLCP_WKS_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_LLCP_WKS, 0);
        } else if (param.equals(PROPERTY_LLCP_OPT_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_LLCP_OPT, 0);
        } else if (param.equals(PROPERTY_NFC_DISCOVERY_A_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_DISCOVERY_A, 0);
        } else if (param.equals(PROPERTY_NFC_DISCOVERY_B_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_DISCOVERY_B, 0);
        } else if (param.equals(PROPERTY_NFC_DISCOVERY_F_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_DISCOVERY_F, 0);
        } else if (param.equals(PROPERTY_NFC_DISCOVERY_NFCIP_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_DISCOVERY_NFCIP, 0);
        } else if (param.equals(PROPERTY_NFC_DISCOVERY_15693_VALUE)) {
            value = Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.NFC_DISCOVERY_15693, 0);
        } else {
            return "Unknown property";
        }

        if (param.equals(PROPERTY_NFC_DISCOVERY_A_VALUE)
                || param.equals(PROPERTY_NFC_DISCOVERY_B_VALUE)
                || param.equals(PROPERTY_NFC_DISCOVERY_F_VALUE)
                || param.equals(PROPERTY_NFC_DISCOVERY_NFCIP_VALUE)
                || param.equals(PROPERTY_NFC_DISCOVERY_15693_VALUE)) {
            if (value == 0) {
                return "false";
            } else if (value == 1) {
                return "true";
            } else {
                return "Unknown Value";
            }
        }else{
            return "" + value;
        }

    }

    public int[] getSecureElementList() throws RemoteException {
        int[] list = null;
        if (mIsNfcEnabled == true) {
            list = mManager.doGetSecureElementList();
        }
        return list;
    }

    public int getSelectedSecureElement() throws RemoteException {
        return mSelectedSeId;
    }

    public boolean isEnabled() throws RemoteException {
        return mIsNfcEnabled;
    }

    public int openP2pConnection() throws RemoteException {
        // Check if NFC is enabled
        if (!mIsNfcEnabled) {
            return ErrorCodes.ERROR_NOT_INITIALIZED;
        }

        mContext.enforceCallingPermission(android.Manifest.permission.NFC_RAW,
                "NFC_RAW permission required to open NFC P2P connection");
        if (!mOpenPending) {
            NativeP2pDevice device;
            mOpenPending = true;
            device = mManager.doOpenP2pConnection(mTimeout);
            if (device != null) {
                /* add device to the Hmap */
                mObjectMap.put(device.getHandle(), device);
                return device.getHandle();
            } else {
                mOpenPending = false;
                /* Restart polling loop for notification */
                mManager.enableDiscovery(DISCOVERY_MODE_READER);
                return ErrorCodes.ERROR_IO;
            }
        } else {
            return ErrorCodes.ERROR_BUSY;
        }

    }

    public int openTagConnection() throws RemoteException {
        NativeNfcTag tag;
        // Check if NFC is enabled
        if (!mIsNfcEnabled) {
            return ErrorCodes.ERROR_NOT_INITIALIZED;
        }

        mContext.enforceCallingPermission(android.Manifest.permission.NFC_RAW,
                "NFC_RAW permission required to open NFC Tag connection");
        if (!mOpenPending) {
            mOpenPending = true;
            tag = mManager.doOpenTagConnection(mTimeout);
            if (tag != null) {
                mObjectMap.put(tag.getHandle(), tag);
                return tag.getHandle();
            } else {
                mOpenPending = false;
                /* Restart polling loop for notification */
                mManager.enableDiscovery(DISCOVERY_MODE_READER);
                return ErrorCodes.ERROR_IO;
            }
        } else {
            return ErrorCodes.ERROR_BUSY;
        }
    }

    public int selectSecureElement(int seId) throws RemoteException {
        // Check if NFC is enabled
        if (!mIsNfcEnabled) {
            return ErrorCodes.ERROR_NOT_INITIALIZED;
        }
        
        if (mSelectedSeId == seId) {
            return ErrorCodes.ERROR_SE_ALREADY_SELECTED;
        }

        if (mSelectedSeId != 0) {
            return ErrorCodes.ERROR_SE_CONNECTED;
        }

        mContext.enforceCallingPermission(android.Manifest.permission.NFC_ADMIN,
                "NFC_ADMIN permission required to select NFC Secure Element");

            mSelectedSeId = seId;
            mManager.doSelectSecureElement(mSelectedSeId);

        /* Store that a secure element is selected */
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.NFC_SECURE_ELEMENT_ON, 1);

        /* Store the ID of the Secure Element Selected */
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.NFC_SECURE_ELEMENT_ID, mSelectedSeId);
        
        mNfcSecureElementState = 1;

        return ErrorCodes.SUCCESS;

    }

    public void setOpenTimeout(int timeout) throws RemoteException {
        mContext.enforceCallingPermission(android.Manifest.permission.NFC_RAW,
                "NFC_RAW permission required to set NFC connection timeout");
        mTimeout = timeout;
    }

    public int setProperties(String param, String value) throws RemoteException {
        mContext.enforceCallingPermission(android.Manifest.permission.NFC_ADMIN,
                "NFC_ADMIN permission required to set NFC Properties");

        if (isEnabled()) {
            return ErrorCodes.ERROR_NFC_ON;
        }

        int val;

        /* Check params validity */
        if (param == null || value == null) {
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        if (param.equals(PROPERTY_LLCP_LTO_VALUE)) {
            val = Integer.parseInt(value);

            /* Check params */
            if (val > LLCP_LTO_MAX_VALUE)
                return ErrorCodes.ERROR_INVALID_PARAM;

            /* Store value */
            Settings.System
                    .putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_LTO, val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_LLCP_LTO, val);

        } else if (param.equals(PROPERTY_LLCP_MIU_VALUE)) {
            val = Integer.parseInt(value);

            /* Check params */
            if ((val < LLCP_MIU_DEFAULT_VALUE) || (val > LLCP_MIU_MAX_VALUE))
                return ErrorCodes.ERROR_INVALID_PARAM;

            /* Store value */
            Settings.System
                    .putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_MIU, val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_LLCP_MIU, val);

        } else if (param.equals(PROPERTY_LLCP_WKS_VALUE)) {
            val = Integer.parseInt(value);

            /* Check params */
            if (val > LLCP_WKS_MAX_VALUE)
                return ErrorCodes.ERROR_INVALID_PARAM;

            /* Store value */
            Settings.System
                    .putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_WKS, val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_LLCP_WKS, val);

        } else if (param.equals(PROPERTY_LLCP_OPT_VALUE)) {
            val = Integer.parseInt(value);

            /* Check params */
            if (val > LLCP_OPT_MAX_VALUE)
                return ErrorCodes.ERROR_INVALID_PARAM;

            /* Store value */
            Settings.System
                    .putInt(mContext.getContentResolver(), Settings.System.NFC_LLCP_OPT, val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_LLCP_OPT, val);

        } else if (param.equals(PROPERTY_NFC_DISCOVERY_A_VALUE)) {

            /* Check params */
            if (value.equals("true")) {
                val = 1;
            } else if (value.equals("false")) {
                val = 0;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }
            /* Store value */
            Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_A,
                    val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_A, val);

        } else if (param.equals(PROPERTY_NFC_DISCOVERY_B_VALUE)) {

            /* Check params */
            if (value.equals("true")) {
                val = 1;
            } else if (value.equals("false")) {
                val = 0;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            /* Store value */
            Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_B,
                    val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_B, val);

        } else if (param.equals(PROPERTY_NFC_DISCOVERY_F_VALUE)) {

            /* Check params */
            if (value.equals("true")) {
                val = 1;
            } else if (value.equals("false")) {
                val = 0;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            /* Store value */
            Settings.System.putInt(mContext.getContentResolver(), Settings.System.NFC_DISCOVERY_F,
                    val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_F, val);

        } else if (param.equals(PROPERTY_NFC_DISCOVERY_15693_VALUE)) {

            /* Check params */
            if (value.equals("true")) {
                val = 1;
            } else if (value.equals("false")) {
                val = 0;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            /* Store value */
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.NFC_DISCOVERY_15693, val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_15693, val);

        } else if (param.equals(PROPERTY_NFC_DISCOVERY_NFCIP_VALUE)) {

            /* Check params */
            if (value.equals("true")) {
                val = 1;
            } else if (value.equals("false")) {
                val = 0;
            } else {
                return ErrorCodes.ERROR_INVALID_PARAM;
            }

            /* Store value */
            Settings.System.putInt(mContext.getContentResolver(),
                    Settings.System.NFC_DISCOVERY_NFCIP, val);

            /* Update JNI */
            mManager.doSetProperties(PROPERTY_NFC_DISCOVERY_NFCIP, val);
        } else {
            return ErrorCodes.ERROR_INVALID_PARAM;
        }

        return ErrorCodes.SUCCESS;
    }

    // Reset all internals
    private void reset() {

        // Clear tables
        mObjectMap.clear();
        mSocketMap.clear();
        mRegisteredSocketList.clear();

        // Reset variables
        mLlcpLinkState = NfcManager.LLCP_LINK_STATE_DEACTIVATED;
        mNbSocketCreated = 0;
        mIsNfcEnabled = false;
        mSelectedSeId = 0;
        mTimeout = 0;
        mNfcState = NFC_STATE_DISABLED;
        mOpenPending = false;
    }

    private Object findObject(int key) {
        Object device = null;

        device = mObjectMap.get(key);

        return device;
    }

    private void RemoveObject(int key) {
        mObjectMap.remove(key);
    }

    private Object findSocket(int key) {
        Object socket = null;

        socket = mSocketMap.get(key);

        return socket;
    }

    private void RemoveSocket(int key) {
        mSocketMap.remove(key);
    }

    private boolean CheckSocketSap(int sap) {
        /* List of sockets registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            if (sap == registeredSocket.mSap) {
                /* SAP already used */
                return false;
            }
        }
        return true;
    }

    private boolean CheckSocketOptions(int miu, int rw, int linearBufferlength) {

        if (rw > LLCP_RW_MAX_VALUE || miu < LLCP_MIU_DEFAULT_VALUE || linearBufferlength < miu) {
            return false;
        }
        return true;
    }

    private boolean CheckSocketServiceName(String sn) {

        /* List of sockets registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();

            if (sn.equals(registeredSocket.mServiceName)) {
                /* Service Name already used */
                return false;
            }
        }
        return true;
    }

    private void RemoveRegisteredSocket(int nativeHandle) {
        /* check if sockets are registered */
        ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

        while (it.hasNext()) {
            RegisteredSocket registeredSocket = it.next();
            if (registeredSocket.mHandle == nativeHandle) {
                /* remove the registered socket from the list */
                it.remove();
                Log.d(TAG, "socket removed");
            }
        }
    }

    /*
     * RegisteredSocket class to store the creation request of socket until the
     * LLCP link in not activated
     */
    private class RegisteredSocket {
        private int mType;

        private int mHandle;

        private int mSap;

        private int mMiu;

        private int mRw;

        private String mServiceName;

        private int mlinearBufferLength;

        RegisteredSocket(int type, int handle, int sap, String sn, int miu, int rw,
                int linearBufferLength) {
            mType = type;
            mHandle = handle;
            mSap = sap;
            mServiceName = sn;
            mRw = rw;
            mMiu = miu;
            mlinearBufferLength = linearBufferLength;
        }

        RegisteredSocket(int type, int handle, int sap, int miu, int rw, int linearBufferLength) {
            mType = type;
            mHandle = handle;
            mSap = sap;
            mRw = rw;
            mMiu = miu;
            mlinearBufferLength = linearBufferLength;
        }

        RegisteredSocket(int type, int handle, int sap) {
            mType = type;
            mHandle = handle;
            mSap = sap;
        }
    }

    private BroadcastReceiver mNfcServiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Internal NFC Intent received");

            /* LLCP Link deactivation */
            if (intent.getAction().equals(NfcManager.LLCP_LINK_STATE_CHANGED_ACTION)) {
                mLlcpLinkState = intent.getIntExtra(NfcManager.LLCP_LINK_STATE_CHANGED_EXTRA,
                        NfcManager.LLCP_LINK_STATE_DEACTIVATED);

                if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_DEACTIVATED) {
                    /* restart polling loop */
                    mManager.enableDiscovery(DISCOVERY_MODE_READER);
                }

            }
            /* LLCP Link activation */
            else if (intent.getAction().equals(
                    NativeNfcManager.INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION)) {

                mLlcpLinkState = intent.getIntExtra(
                        NativeNfcManager.INTERNAL_LLCP_LINK_STATE_CHANGED_EXTRA,
                        NfcManager.LLCP_LINK_STATE_DEACTIVATED);

                if (mLlcpLinkState == NfcManager.LLCP_LINK_STATE_ACTIVATED) {
                    /* check if sockets are registered */
                    ListIterator<RegisteredSocket> it = mRegisteredSocketList.listIterator();

                    Log.d(TAG, "Nb socket resgistered = " + mRegisteredSocketList.size());

                    while (it.hasNext()) {
                        RegisteredSocket registeredSocket = it.next();

                        switch (registeredSocket.mType) {
                            case LLCP_SERVICE_SOCKET_TYPE:
                                Log.d(TAG, "Registered Llcp Service Socket");
                                NativeLlcpServiceSocket serviceSocket;

                                serviceSocket = mManager.doCreateLlcpServiceSocket(
                                        registeredSocket.mSap, registeredSocket.mServiceName,
                                        registeredSocket.mMiu, registeredSocket.mRw,
                                        registeredSocket.mlinearBufferLength);

                                if (serviceSocket != null) {
                                    /* Add the socket into the socket map */
                                    mSocketMap.put(registeredSocket.mHandle, serviceSocket);
                                } else {
                                    /*
                                     * socket creation error - update the socket
                                     * handle counter
                                     */
                                    mGeneratedSocketHandle -= 1;
                                }
                                break;

                            case LLCP_SOCKET_TYPE:
                                Log.d(TAG, "Registered Llcp Socket");
                                NativeLlcpSocket clientSocket;
                                clientSocket = mManager.doCreateLlcpSocket(registeredSocket.mSap,
                                        registeredSocket.mMiu, registeredSocket.mRw,
                                        registeredSocket.mlinearBufferLength);
                                if (clientSocket != null) {
                                    /* Add the socket into the socket map */
                                    mSocketMap.put(registeredSocket.mHandle, clientSocket);
                                } else {
                                    /*
                                     * socket creation error - update the socket
                                     * handle counter
                                     */
                                    mGeneratedSocketHandle -= 1;
                                }
                                break;

                            case LLCP_CONNECTIONLESS_SOCKET_TYPE:
                                Log.d(TAG, "Registered Llcp Connectionless Socket");
                                NativeLlcpConnectionlessSocket connectionlessSocket;
                                connectionlessSocket = mManager
                                        .doCreateLlcpConnectionlessSocket(registeredSocket.mSap);
                                if (connectionlessSocket != null) {
                                    /* Add the socket into the socket map */
                                    mSocketMap.put(registeredSocket.mHandle, connectionlessSocket);
                                } else {
                                    /*
                                     * socket creation error - update the socket
                                     * handle counter
                                     */
                                    mGeneratedSocketHandle -= 1;
                                }
                                break;

                        }
                    }

                    /* Remove all registered socket from the list */
                    mRegisteredSocketList.clear();

                    /* Broadcast Intent Link LLCP activated */
                    Intent LlcpLinkIntent = new Intent();
                    LlcpLinkIntent.setAction(NfcManager.LLCP_LINK_STATE_CHANGED_ACTION);

                    LlcpLinkIntent.putExtra(NfcManager.LLCP_LINK_STATE_CHANGED_EXTRA,
                            NfcManager.LLCP_LINK_STATE_ACTIVATED);

                    Log.d(TAG, "Broadcasting LLCP activation");
                    mContext.sendOrderedBroadcast(LlcpLinkIntent,
                            android.Manifest.permission.NFC_LLCP);
                }
            }            
            /* Target Deactivated */
            else if (intent.getAction().equals(
                    NativeNfcManager.INTERNAL_TARGET_DESELECTED_ACTION)) {
                if(mOpenPending != false){
                    mOpenPending = false;
                }
                /* Restart polling loop for notification */
                mManager.enableDiscovery(DISCOVERY_MODE_READER);
                
            }
        }
    };
}
