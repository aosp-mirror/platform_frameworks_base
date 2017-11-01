/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.telephony.ims.feature;

import android.app.PendingIntent;
import android.os.Message;
import android.os.RemoteException;

import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsCallSessionListener;
import com.android.ims.internal.IImsConfig;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsRegistrationListener;
import com.android.ims.internal.IImsUt;

/**
 * MMTel interface for an ImsService. When updating this interface, ensure that base implementations
 * of your changes are also present in MMTelFeature for compatibility with older versions of the
 * MMTel feature.
 * @hide
 */

public interface IMMTelFeature {

    /**
     * Notifies the MMTel feature that you would like to start a session. This should always be
     * done before making/receiving IMS calls. The IMS service will register the device to the
     * operator's network with the credentials (from ISIM) periodically in order to receive calls
     * from the operator's network. When the IMS service receives a new call, it will send out an
     * intent with the provided action string. The intent contains a call ID extra
     * {@link IImsCallSession#getCallId} and it can be used to take a call.
     *
     * @param incomingCallIntent When an incoming call is received, the IMS service will call
     * {@link PendingIntent#send} to send back the intent to the caller with
     * {@link #INCOMING_CALL_RESULT_CODE} as the result code and the intent to fill in the call ID;
     * It cannot be null.
     * @param listener To listen to IMS registration events; It cannot be null
     * @return an integer (greater than 0) representing the session id associated with the session
     * that has been started.
     */
    int startSession(PendingIntent incomingCallIntent, IImsRegistrationListener listener)
            throws RemoteException;

    /**
     * End a previously started session using the associated sessionId.
     * @param sessionId an integer (greater than 0) representing the ongoing session. See
     * {@link #startSession}.
     */
    void endSession(int sessionId) throws RemoteException;

    /**
     * Checks if the IMS service has successfully registered to the IMS network with the specified
     * service & call type.
     *
     * @param callServiceType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE_N_VIDEO}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     * @return true if the specified service id is connected to the IMS network; false otherwise
     * @throws RemoteException
     */
    boolean isConnected(int callServiceType, int callType) throws RemoteException;

    /**
     * Checks if the specified IMS service is opened.
     *
     * @return true if the specified service id is opened; false otherwise
     */
    boolean isOpened() throws RemoteException;

    /**
     * Add a new registration listener for the client associated with the session Id.
     * @param listener An implementation of IImsRegistrationListener.
     */
    void addRegistrationListener(IImsRegistrationListener listener)
            throws RemoteException;

    /**
     * Remove a previously registered listener using {@link #addRegistrationListener} for the client
     * associated with the session Id.
     * @param listener A previously registered IImsRegistrationListener
     */
    void removeRegistrationListener(IImsRegistrationListener listener)
            throws RemoteException;

    /**
     * Creates a {@link ImsCallProfile} from the service capabilities & IMS registration state.
     *
     * @param sessionId a session id which is obtained from {@link #startSession}
     * @param callServiceType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NONE}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VT_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_RX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_NODIR}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     *        {@link ImsCallProfile#CALL_TYPE_VS_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VS_RX}
     * @return a {@link ImsCallProfile} object
     */
    ImsCallProfile createCallProfile(int sessionId, int callServiceType, int callType)
            throws RemoteException;

    /**
     * Creates a {@link ImsCallSession} with the specified call profile.
     * Use other methods, if applicable, instead of interacting with
     * {@link ImsCallSession} directly.
     *
     * @param sessionId a session id which is obtained from {@link #startSession}
     * @param profile a call profile to make the call
     * @param listener An implementation of IImsCallSessionListener.
     */
    IImsCallSession createCallSession(int sessionId, ImsCallProfile profile,
            IImsCallSessionListener listener) throws RemoteException;

    /**
     * Retrieves the call session associated with a pending call.
     *
     * @param sessionId a session id which is obtained from {@link #startSession}
     * @param callId a call id to make the call
     */
    IImsCallSession getPendingCallSession(int sessionId, String callId) throws RemoteException;

    /**
     * @return The Ut interface for the supplementary service configuration.
     */
    IImsUt getUtInterface() throws RemoteException;

    /**
     * @return The config interface for IMS Configuration
     */
    IImsConfig getConfigInterface() throws RemoteException;

    /**
     * Signal the MMTelFeature to turn on IMS when it has been turned off using {@link #turnOffIms}
     * @param sessionId a session id which is obtained from {@link #startSession}
     */
    void turnOnIms() throws RemoteException;

    /**
     * Signal the MMTelFeature to turn off IMS when it has been turned on using {@link #turnOnIms}
     * @param sessionId a session id which is obtained from {@link #startSession}
     */
    void turnOffIms() throws RemoteException;

    /**
     * @return The Emergency call-back mode interface for emergency VoLTE calls that support it.
     */
    IImsEcbm getEcbmInterface() throws RemoteException;

    /**
     * Sets the current UI TTY mode for the MMTelFeature.
     * @param uiTtyMode An integer containing the new UI TTY Mode.
     * @param onComplete A {@link Message} to be used when the mode has been set.
     * @throws RemoteException
     */
    void setUiTTYMode(int uiTtyMode, Message onComplete) throws RemoteException;

    /**
     * @return MultiEndpoint interface for DEP notifications
     */
    IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException;
}
