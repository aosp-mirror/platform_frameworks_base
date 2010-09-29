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
 * File            : NfcManager.java
 * Original-Author : Trusted Logic S.A. (Jeremie Corbier)
 * Created         : 26-08-2009
 */

package com.trustedlogic.trustednfc.android;

import java.io.IOException;

import com.trustedlogic.trustednfc.android.internal.ErrorCodes;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.media.MiniThumbFile;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

//import android.util.Log;

/**
 * This class provides the primary API for managing all aspects of NFC. Get an
 * instance of this class by calling
 * Context.getSystemService(Context.NFC_SERVICE).
 * @hide
 */
public final class NfcManager {
    /**
     * Tag Reader Discovery mode
     */
    private static final int DISCOVERY_MODE_TAG_READER = 0;

    /**
     * NFC-IP1 Peer-to-Peer mode Enables the manager to act as a peer in an
     * NFC-IP1 communication. Implementations should not assume that the
     * controller will end up behaving as an NFC-IP1 target or initiator and
     * should handle both cases, depending on the type of the remote peer type.
     */
    private static final int DISCOVERY_MODE_NFCIP1 = 1;

    /**
     * Card Emulation mode Enables the manager to act as an NFC tag. Provided
     * that a Secure Element (an UICC for instance) is connected to the NFC
     * controller through its SWP interface, it can be exposed to the outside
     * NFC world and be addressed by external readers the same way they would
     * with a tag.
     * <p>
     * Which Secure Element is exposed is implementation-dependent.
     * 
     * @since AA01.04
     */
    private static final int DISCOVERY_MODE_CARD_EMULATION = 2;

    /**
     * Used as Parcelable extra field in
     * {@link com.trustedlogic.trustednfc.android.NfcManager#NDEF_TAG_DISCOVERED_ACTION}
     * . It contains the NDEF message read from the NDEF tag discovered.
     */
    public static final String NDEF_MESSAGE_EXTRA = "com.trustedlogic.trustednfc.android.extra.NDEF_MESSAGE";

    /**
     * Broadcast Action: a NDEF tag has been discovered.
     * <p>
     * Always contains the extra field
     * {@link com.trustedlogic.trustednfc.android.NfcManager#NDEF_MESSAGE_EXTRA}.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_NOTIFY permission.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String NDEF_TAG_DISCOVERED_ACTION = "com.trustedlogic.trustednfc.android.action.NDEF_TAG_DISCOVERED";

    /**
     * Used as byte array extra field in
     * {@link com.trustedlogic.trustednfc.android.NfcManager#TRANSACTION_DETECTED_ACTION}
     * . It contains the AID of the applet concerned by the transaction.
     */
    public static final String AID_EXTRA = "com.trustedlogic.trustednfc.android.extra.AID";

    /**
     * Broadcast Action: a transaction with a secure element has been detected.
     * <p>
     * Always contains the extra field
     * {@link com.trustedlogic.trustednfc.android.NfcManager#AID_EXTRA}
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_NOTIFY permission
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String TRANSACTION_DETECTED_ACTION = "com.trustedlogic.trustednfc.android.action.TRANSACTION_DETECTED";

    /**
     * LLCP link status: The LLCP link is activated.
     * 
     * @since AA02.01
     */
    public static final int LLCP_LINK_STATE_ACTIVATED = 0;

    /**
     * LLCP link status: The LLCP link is deactivated.
     * 
     * @since AA02.01
     */
    public static final int LLCP_LINK_STATE_DEACTIVATED = 1;

    /**
     * Used as int extra field in
     * {@link com.trustedlogic.trustednfc.android.NfcManager#LLCP_LINK_STATE_CHANGED_ACTION}
     * . It contains the new state of the LLCP link.
     */
    public static final String LLCP_LINK_STATE_CHANGED_EXTRA = "com.trustedlogic.trustednfc.android.extra.LLCP_LINK_STATE";

    /**
     * Broadcast Action: the LLCP link state changed.
     * <p>
     * Always contains the extra field
     * {@link com.trustedlogic.trustednfc.android.NfcManager#LLCP_LINK_STATE_CHANGED_EXTRA}.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_LLCP permission.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String LLCP_LINK_STATE_CHANGED_ACTION = "com.trustedlogic.trustednfc.android.action.LLCP_LINK_STATE_CHANGED";

    private static final String TAG = "NfcManager";

    private Handler mHandler;

    private INfcManager mService;

    private INfcTag mNfcTagService;

    private IP2pTarget mP2pTargetService;

    private IP2pInitiator mP2pInitiatorService;

    private ILlcpSocket mLlcpSocketService;

    private ILlcpConnectionlessSocket mLlcpConnectionlessSocketService;

    private ILlcpServiceSocket mLlcpServiceSocketService;

    static NfcException convertErrorToNfcException(int errorCode) {
        return convertErrorToNfcException(errorCode, null);
    }

    static NfcException convertErrorToNfcException(int errorCode, String message) {
        if (message == null) {
            message = "";
        } else {
            message = " (" + message + ")";
        }

        switch (errorCode) {
            case ErrorCodes.ERROR_BUSY:
                return new NfcException("Another operation is already pending" + message);
            case ErrorCodes.ERROR_CANCELLED:
                return new NfcException("Operation cancelled" + message);
            case ErrorCodes.ERROR_TIMEOUT:
                return new NfcException("Operation timed out" + message);
            case ErrorCodes.ERROR_SOCKET_CREATION:
                return new NfcException("Error during the creation of an Llcp socket:" + message);
            case ErrorCodes.ERROR_SAP_USED:
                return new NfcException("Error SAP already used:" + message);
            case ErrorCodes.ERROR_SERVICE_NAME_USED:
                return new NfcException("Error Service Name already used:" + message);
            case ErrorCodes.ERROR_SOCKET_OPTIONS:
                return new NfcException("Error Socket options:" + message);
            case ErrorCodes.ERROR_INVALID_PARAM:
                return new NfcException("Error Set Properties: invalid param" + message);
            case ErrorCodes.ERROR_NFC_ON:
                return new NfcException("Error Set Properties : NFC is ON" + message);
            case ErrorCodes.ERROR_NOT_INITIALIZED:
                return new NfcException("NFC is not running " + message);
            case ErrorCodes.ERROR_SE_ALREADY_SELECTED:
                return new NfcException("Secure Element already connected" + message);
            case ErrorCodes.ERROR_NO_SE_CONNECTED:
                return new NfcException("No Secure Element connected" + message);
            case ErrorCodes.ERROR_SE_CONNECTED:
                return new NfcException("A secure Element is already connected" + message);
            default:
                return new NfcException("Unkown error code " + errorCode + message);
        }
    }

    /**
     * @hide
     */
    public NfcManager(INfcManager service, Handler handler) {
        mService = service;
        mHandler = handler;
        try {
            mNfcTagService = mService.getNfcTagInterface();
            mP2pInitiatorService = mService.getP2pInitiatorInterface();
            mP2pTargetService = mService.getP2pTargetInterface();
            mLlcpServiceSocketService = mService.getLlcpServiceInterface();
            mLlcpConnectionlessSocketService = mService.getLlcpConnectionlessInterface();
            mLlcpSocketService = mService.getLlcpInterface();
        } catch (RemoteException e) {
            mLlcpSocketService = null;
            mNfcTagService = null;
            mP2pInitiatorService = null;
            mP2pTargetService = null;
            mLlcpConnectionlessSocketService = null;
            mLlcpServiceSocketService = null;
        }
    }

    /**
     * Return the status of the NFC feature
     * 
     * @return mIsNfcEnabled
     * @since AA02.01
     */
    public boolean isEnabled() {
        try {
            return mService.isEnabled();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in isEnabled(): ", e);
            return false;
        }
    }

    /**
     * Enable the NFC Feature
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_ADMIN permission
     * 
     * @throws NfcException if the enable failed
     * @since AA02.01
     */
    public void enable() throws NfcException {
        try {
            boolean isSuccess = mService.enable();
            if (isSuccess == false) {
                throw new NfcException("NFC Service failed to enable");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in enable(): ", e);
        }
    }

    /**
     * Disable the NFC feature
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_ADMIN permission
     * 
     * @throws NfcException if the disable failed
     * @since AA02.01
     */
    public void disable() throws NfcException {
        try {
            boolean isSuccess = mService.disable();
            if (isSuccess == false) {
                throw new NfcException("NFC Service failed to disable");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in disable(): ", e);
        }
    }

    /**
     * Get the list of the identifiers of the Secure Elements detected
     * by the NFC controller.
     * 
     * @return list a list of Secure Element identifiers.
     * @see #getSelectedSecureElement
     * @see #selectSecureElement(int)
     * @see #deselectSecureElement
     * @since AA02.01
     */
    public int[] getSecureElementList() {
        try {
            return mService.getSecureElementList();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getSecureElementList(): ", e);
            return null;
        }
    }

    /**
     * Get the identifier of the currently selected secure element.
     * 
     * @return id identifier of the currently selected Secure Element. 0 if none.
     * @see #getSecureElementList
     * @see #selectSecureElement(int)
     * @see #deselectSecureElement
     * @since AA02.01
     */
    public int getSelectedSecureElement() {
        try {
            return mService.getSelectedSecureElement();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getSelectedSecureElement(): ", e);
            return -1;
        }
    }

    /**
     * Select a specific Secure Element by its identifier.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_ADMIN permission
     * 
     * @throws NfcException if a or this secure element is already selected
     * @see #getSecureElementList
     * @see #getSelectedSecureElement
     * @see #deselectSecureElement
     * @since AA02.01
     */
    public void selectSecureElement(int seId) throws NfcException  {
        try {
           int status = mService.selectSecureElement(seId);
           if(status != ErrorCodes.SUCCESS){
               throw convertErrorToNfcException(status);
           }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in selectSecureElement(): ", e);
        }
    }

    /**
     * Deselect the currently selected Secure Element
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_ADMIN permission
     * 
     * @throws NfcException if no secure Element is selected
     * @see #getSecureElementList
     * @see #getSelectedSecureElement
     * @see #selectSecureElement(int)
     * @since AA02.01
     */
    public void deselectSecureElement() throws NfcException {
        try {
            int status = mService.deselectSecureElement();
            if(status != ErrorCodes.SUCCESS){
                throw convertErrorToNfcException(status);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in deselectSecureElement(): ", e);
        }
    }

    /**
     * Open a connection with a remote NFC peer
     * 
     * This method does not return while no remote NFC peer enters the field.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_RAW permission
     * 
     * @return P2pDevice object to be used to communicate with the detected
     *         peer.
     * @throws IOException if the target has been lost or the connection has
     *             been closed.
     * @throws NfcException if an open is already started
     * @see P2pDevice
     * @see #getOpenTimeout
     * @see #setOpenTimeout(int)
     * @see #cancel
     * @since AA02.01
     */
    public P2pDevice openP2pConnection() throws IOException, NfcException {
        try {
            int handle = mService.openP2pConnection();
            // Handle potential errors
            if (ErrorCodes.isError(handle)) {
                if (handle == ErrorCodes.ERROR_IO) {
                    throw new IOException();
                } else {
                    throw convertErrorToNfcException(handle);
                }
            }
            // Build the public NfcTag object, depending on its type
            if (mP2pTargetService.getMode(handle) == P2pDevice.MODE_P2P_TARGET) {
                return new P2pTarget(mP2pTargetService, handle);
            } else {
                return new P2pInitiator(mP2pInitiatorService, handle);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openTagConnection(): ", e);
            return null;
        }
    }

    /**
     * Open a connection with a tag
     *
     * This method does not return while no tag enters the field.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_RAW permission
     * 
     * @return tag object to be use to communicate with the detected NfcTag.
     * @throws IOException if the target has been lost or the connection has
     *             been closed.
     * @throws NfcException if an open is already started
     * @see NfcTag
     * @see #getOpenTimeout
     * @see #setOpenTimeout(int)
     * @see #cancel
     * @since AA02.01
     */
    public NfcTag openTagConnection() throws IOException, NfcException {
        try {
            int handle = mService.openTagConnection();
            // Handle potential errors
            if (ErrorCodes.isError(handle)) {
                if (handle == ErrorCodes.ERROR_IO) {
                    throw new IOException();
                } else {
                    throw convertErrorToNfcException(handle);
                }
            }
            // Build the public NfcTag object
            return new NfcTag(mNfcTagService, handle);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in openTagConnection(): ", e);
            return null;
        }
    }

    /**
     * Set the timeout for open requests
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_RAW permission
     * 
     * @param timeout value of the timeout for open request
     * @see #openP2pConnection
     * @see #openTagConnection
     * @see #getOpenTimeout
     * @since AA02.01
     */
    public void setOpenTimeout(int timeout) {
        try {
            mService.setOpenTimeout(timeout);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setOpenTimeout(): ", e);
        }
    }

    /**
     * Get the timeout value of open requests
     * 
     * @return mTimeout
     * @see #setOpenTimeout(int)
     * @since AA02.01
     */
    public int getOpenTimeout() {
        try {
            return mService.getOpenTimeout();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getOpenTimeout(): ", e);
            return 0;
        }
    }

    /**
     * Cancel an openTagConnection or an openP2pConnection started
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_RAW permission
     * 
     * @see #openP2pConnection
     * @see #openTagConnection
     * @since AA02.01
     */
    public void cancel() {
        try {
            mService.cancel();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in cancel(): ", e);
        }
    }

    /**
     * Creates a connectionless socket for a LLCP link and set its Service
     * Access Point number (SAP)
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_LLCP permission
     * 
     * @param sap Service Access Point number related to the created
     *            Connectionless socket.
     * @return LlcpConnectionlessSocket object to be used in a LLCP
     *         Connectionless communication.
     * @throws IOException if the socket creation failed
     * @throws NfcException if socket ressources are insufficicent
     * @see LlcpConnectionlessSocket
     * @since AA02.01
     */
    public LlcpConnectionlessSocket createLlcpConnectionlessSocket(int sap) throws IOException,
            NfcException {

        try {
            int handle = mService.createLlcpConnectionlessSocket(sap);
            // Handle potential errors
            if (ErrorCodes.isError(handle)) {
                if (handle == ErrorCodes.ERROR_IO) {
                    throw new IOException();
                } else {
                    throw convertErrorToNfcException(handle);
                }
            }

            // Build the public LlcpConnectionLess object
            return new LlcpConnectionlessSocket(mLlcpConnectionlessSocketService, handle);

        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in createLlcpConnectionlessSocket(): ", e);
            return null;
        }
    }

    /**
     * Creates a LlcpServiceSocket for a LLCP link, set its Service Access Point
     * number (SAP).
     * <p>
     * During a LLCP communication, the LlcpServiceSocket will create LlcpSocket
     * to communicate with incoming LLCP clients. For that, a server socket need
     * to have some informations as a working buffer length in order to handle
     * incoming data and some options to define the LLCP communication.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_LLCP permission
     * 
     * @param sap
     * @param sn Service Name of the LlcpServiceSocket
     * @param miu Maximum Information Unit (MIU) for a LlcpSocket created by the
     *            LlcpServiceSocket
     * @param rw Receive Window (RW) for a LlcpSocket created by the
     *            LlcpServiceSocket
     * @param linearBufferLength size of the memory space needed to handle
     *            incoming data for every LlcpSocket created.
     * @return LlcpServiceSocket object to be used as a LLCP Service in a
     *         connection oriented communication.
     * @throws IOException if the socket creation failed
     * @throws NfcException if socket ressources are insufficicent
     * @see LlcpServiceSocket
     * @since AA02.01
     */
    public LlcpServiceSocket createLlcpServiceSocket(int sap, String sn, int miu, int rw,
            int linearBufferLength) throws IOException, NfcException {
        try {
            int handle = mService.createLlcpServiceSocket(sap, sn, miu, rw, linearBufferLength);
            // Handle potential errors
            if (ErrorCodes.isError(handle)) {
                if (handle == ErrorCodes.ERROR_IO) {
                    throw new IOException();
                } else {
                    throw convertErrorToNfcException(handle);
                }
            }

            // Build the public LlcpServiceSocket object
            return new LlcpServiceSocket(mLlcpServiceSocketService, mLlcpSocketService, handle);

        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in createLlcpServiceSocket(): ", e);
            return null;
        }
    }

    /**
     * Creates a LlcpSocket for a LLCP link with a specific Service Access Point
     * number (SAP)
     * <p>
     * A LlcpSocket need to have a linear buffer in order to handle incoming
     * data. This linear buffer will be used to store incoming data as a stream.
     * Data will be readable later.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_LLCP permission
     * 
     * @param sap Service Access Point number for the created socket
     * @param miu Maximum Information Unit (MIU) of the communication socket
     * @param rw Receive Window (RW) of the communication socket
     * @param linearBufferLength size of the memory space needed to handle
     *            incoming data with this socket
     * @throws IOException if the socket creation failed
     * @throws NfcException if socket ressources are insufficicent
     * @see LlcpSocket
     * @since AA02.01
     */
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw, int linearBufferLength)
            throws IOException, NfcException {
        try {
            int handle = mService.createLlcpSocket(sap, miu, rw, linearBufferLength);
            // Handle potential errors
            if (ErrorCodes.isError(handle)) {
                if (handle == ErrorCodes.ERROR_IO) {
                    throw new IOException();
                } else {
                    throw convertErrorToNfcException(handle);
                }
            }
            // Build the public LlcpSocket object
            return new LlcpSocket(mLlcpSocketService, handle);

        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in createLlcpSocket(): ", e);
            return null;
        }
    }

    /**
     * Set different parameters like the NCIP General bytes, the LLCP link
     * parameters and all tag discovery parameters.
     * <p class="note">
     * <strong>Note:</strong> Requires the NFC_ADMIN permission
     * 
     * @param param parameter to be updated with a new value
     * @param value new value of the parameter
     * @throws NfcException if incorrect parameters of NFC is ON
     * @since AA02.01
     */
    public void setProperties(String param, String value) throws NfcException {
        try {
            int result = mService.setProperties(param, value);
            // Handle potential errors
            if (ErrorCodes.isError(result)) {
                throw convertErrorToNfcException(result);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in setProperties(): ", e);
        }
    }

    /**
     * Get the value of different parameters like the NCFIP General bytes, the
     * LLCP link parameters and all tag discovery parameters.
     * 
     * @param param parameter to be updated
     * @return String value of the requested parameter
     * @throws RemoteException
     * @since AA02.01
     */
    public String getProperties(String param) {
        String value;
        try {
            value = mService.getProperties(param);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in getProperties(): ", e);
            return null;
        }
        return value;
    }

}
