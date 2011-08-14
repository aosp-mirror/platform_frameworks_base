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

package android.net.sip;

import android.os.RemoteException;
import android.util.Log;

/**
 * Represents a SIP session that is associated with a SIP dialog or a standalone
 * transaction not within a dialog.
 * <p>You can get a {@link SipSession} from {@link SipManager} with {@link
 * SipManager#createSipSession createSipSession()} (when initiating calls) or {@link
 * SipManager#getSessionFor getSessionFor()} (when receiving calls).</p>
 */
public final class SipSession {
    private static final String TAG = "SipSession";

    /**
     * Defines SIP session states, such as "registering", "outgoing call", and "in call".
     */
    public static class State {
        /** When session is ready to initiate a call or transaction. */
        public static final int READY_TO_CALL = 0;

        /** When the registration request is sent out. */
        public static final int REGISTERING = 1;

        /** When the unregistration request is sent out. */
        public static final int DEREGISTERING = 2;

        /** When an INVITE request is received. */
        public static final int INCOMING_CALL = 3;

        /** When an OK response is sent for the INVITE request received. */
        public static final int INCOMING_CALL_ANSWERING = 4;

        /** When an INVITE request is sent. */
        public static final int OUTGOING_CALL = 5;

        /** When a RINGING response is received for the INVITE request sent. */
        public static final int OUTGOING_CALL_RING_BACK = 6;

        /** When a CANCEL request is sent for the INVITE request sent. */
        public static final int OUTGOING_CALL_CANCELING = 7;

        /** When a call is established. */
        public static final int IN_CALL = 8;

        /** When an OPTIONS request is sent. */
        public static final int PINGING = 9;

        /** When ending a call. @hide */
        public static final int ENDING_CALL = 10;

        /** Not defined. */
        public static final int NOT_DEFINED = 101;

        /**
         * Converts the state to string.
         */
        public static String toString(int state) {
            switch (state) {
                case READY_TO_CALL:
                    return "READY_TO_CALL";
                case REGISTERING:
                    return "REGISTERING";
                case DEREGISTERING:
                    return "DEREGISTERING";
                case INCOMING_CALL:
                    return "INCOMING_CALL";
                case INCOMING_CALL_ANSWERING:
                    return "INCOMING_CALL_ANSWERING";
                case OUTGOING_CALL:
                    return "OUTGOING_CALL";
                case OUTGOING_CALL_RING_BACK:
                    return "OUTGOING_CALL_RING_BACK";
                case OUTGOING_CALL_CANCELING:
                    return "OUTGOING_CALL_CANCELING";
                case IN_CALL:
                    return "IN_CALL";
                case PINGING:
                    return "PINGING";
                default:
                    return "NOT_DEFINED";
            }
        }

        private State() {
        }
    }

    /**
     * Listener for events relating to a SIP session, such as when a session is being registered
     * ("on registering") or a call is outgoing ("on calling").
     * <p>Many of these events are also received by {@link SipAudioCall.Listener}.</p>
     */
    public static class Listener {
        /**
         * Called when an INVITE request is sent to initiate a new call.
         *
         * @param session the session object that carries out the transaction
         */
        public void onCalling(SipSession session) {
        }

        /**
         * Called when an INVITE request is received.
         *
         * @param session the session object that carries out the transaction
         * @param caller the SIP profile of the caller
         * @param sessionDescription the caller's session description
         */
        public void onRinging(SipSession session, SipProfile caller,
                String sessionDescription) {
        }

        /**
         * Called when a RINGING response is received for the INVITE request sent
         *
         * @param session the session object that carries out the transaction
         */
        public void onRingingBack(SipSession session) {
        }

        /**
         * Called when the session is established.
         *
         * @param session the session object that is associated with the dialog
         * @param sessionDescription the peer's session description
         */
        public void onCallEstablished(SipSession session,
                String sessionDescription) {
        }

        /**
         * Called when the session is terminated.
         *
         * @param session the session object that is associated with the dialog
         */
        public void onCallEnded(SipSession session) {
        }

        /**
         * Called when the peer is busy during session initialization.
         *
         * @param session the session object that carries out the transaction
         */
        public void onCallBusy(SipSession session) {
        }

        /**
         * Called when the call is being transferred to a new one.
         *
         * @hide
         * @param newSession the new session that the call will be transferred to
         * @param sessionDescription the new peer's session description
         */
        public void onCallTransferring(SipSession newSession,
                String sessionDescription) {
        }

        /**
         * Called when an error occurs during session initialization and
         * termination.
         *
         * @param session the session object that carries out the transaction
         * @param errorCode error code defined in {@link SipErrorCode}
         * @param errorMessage error message
         */
        public void onError(SipSession session, int errorCode,
                String errorMessage) {
        }

        /**
         * Called when an error occurs during session modification negotiation.
         *
         * @param session the session object that carries out the transaction
         * @param errorCode error code defined in {@link SipErrorCode}
         * @param errorMessage error message
         */
        public void onCallChangeFailed(SipSession session, int errorCode,
                String errorMessage) {
        }

        /**
         * Called when a registration request is sent.
         *
         * @param session the session object that carries out the transaction
         */
        public void onRegistering(SipSession session) {
        }

        /**
         * Called when registration is successfully done.
         *
         * @param session the session object that carries out the transaction
         * @param duration duration in second before the registration expires
         */
        public void onRegistrationDone(SipSession session, int duration) {
        }

        /**
         * Called when the registration fails.
         *
         * @param session the session object that carries out the transaction
         * @param errorCode error code defined in {@link SipErrorCode}
         * @param errorMessage error message
         */
        public void onRegistrationFailed(SipSession session, int errorCode,
                String errorMessage) {
        }

        /**
         * Called when the registration gets timed out.
         *
         * @param session the session object that carries out the transaction
         */
        public void onRegistrationTimeout(SipSession session) {
        }
    }

    private final ISipSession mSession;
    private Listener mListener;

    SipSession(ISipSession realSession) {
        mSession = realSession;
        if (realSession != null) {
            try {
                realSession.setListener(createListener());
            } catch (RemoteException e) {
                Log.e(TAG, "SipSession.setListener(): " + e);
            }
        }
    }

    SipSession(ISipSession realSession, Listener listener) {
        this(realSession);
        setListener(listener);
    }

    /**
     * Gets the IP address of the local host on which this SIP session runs.
     *
     * @return the IP address of the local host
     */
    public String getLocalIp() {
        try {
            return mSession.getLocalIp();
        } catch (RemoteException e) {
            Log.e(TAG, "getLocalIp(): " + e);
            return "127.0.0.1";
        }
    }

    /**
     * Gets the SIP profile that this session is associated with.
     *
     * @return the SIP profile that this session is associated with
     */
    public SipProfile getLocalProfile() {
        try {
            return mSession.getLocalProfile();
        } catch (RemoteException e) {
            Log.e(TAG, "getLocalProfile(): " + e);
            return null;
        }
    }

    /**
     * Gets the SIP profile that this session is connected to. Only available
     * when the session is associated with a SIP dialog.
     *
     * @return the SIP profile that this session is connected to
     */
    public SipProfile getPeerProfile() {
        try {
            return mSession.getPeerProfile();
        } catch (RemoteException e) {
            Log.e(TAG, "getPeerProfile(): " + e);
            return null;
        }
    }

    /**
     * Gets the session state. The value returned must be one of the states in
     * {@link State}.
     *
     * @return the session state
     */
    public int getState() {
        try {
            return mSession.getState();
        } catch (RemoteException e) {
            Log.e(TAG, "getState(): " + e);
            return State.NOT_DEFINED;
        }
    }

    /**
     * Checks if the session is in a call.
     *
     * @return true if the session is in a call
     */
    public boolean isInCall() {
        try {
            return mSession.isInCall();
        } catch (RemoteException e) {
            Log.e(TAG, "isInCall(): " + e);
            return false;
        }
    }

    /**
     * Gets the call ID of the session.
     *
     * @return the call ID
     */
    public String getCallId() {
        try {
            return mSession.getCallId();
        } catch (RemoteException e) {
            Log.e(TAG, "getCallId(): " + e);
            return null;
        }
    }


    /**
     * Sets the listener to listen to the session events. A {@code SipSession}
     * can only hold one listener at a time. Subsequent calls to this method
     * override the previous listener.
     *
     * @param listener to listen to the session events of this object
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }


    /**
     * Performs registration to the server specified by the associated local
     * profile. The session listener is called back upon success or failure of
     * registration. The method is only valid to call when the session state is
     * in {@link State#READY_TO_CALL}.
     *
     * @param duration duration in second before the registration expires
     * @see Listener
     */
    public void register(int duration) {
        try {
            mSession.register(duration);
        } catch (RemoteException e) {
            Log.e(TAG, "register(): " + e);
        }
    }

    /**
     * Performs unregistration to the server specified by the associated local
     * profile. Unregistration is technically the same as registration with zero
     * expiration duration. The session listener is called back upon success or
     * failure of unregistration. The method is only valid to call when the
     * session state is in {@link State#READY_TO_CALL}.
     *
     * @see Listener
     */
    public void unregister() {
        try {
            mSession.unregister();
        } catch (RemoteException e) {
            Log.e(TAG, "unregister(): " + e);
        }
    }

    /**
     * Initiates a call to the specified profile. The session listener is called
     * back upon defined session events. The method is only valid to call when
     * the session state is in {@link State#READY_TO_CALL}.
     *
     * @param callee the SIP profile to make the call to
     * @param sessionDescription the session description of this call
     * @param timeout the session will be timed out if the call is not
     *        established within {@code timeout} seconds. Default value (defined
     *        by SIP protocol) is used if {@code timeout} is zero or negative.
     * @see Listener
     */
    public void makeCall(SipProfile callee, String sessionDescription,
            int timeout) {
        try {
            mSession.makeCall(callee, sessionDescription, timeout);
        } catch (RemoteException e) {
            Log.e(TAG, "makeCall(): " + e);
        }
    }

    /**
     * Answers an incoming call with the specified session description. The
     * method is only valid to call when the session state is in
     * {@link State#INCOMING_CALL}.
     *
     * @param sessionDescription the session description to answer this call
     * @param timeout the session will be timed out if the call is not
     *        established within {@code timeout} seconds. Default value (defined
     *        by SIP protocol) is used if {@code timeout} is zero or negative.
     */
    public void answerCall(String sessionDescription, int timeout) {
        try {
            mSession.answerCall(sessionDescription, timeout);
        } catch (RemoteException e) {
            Log.e(TAG, "answerCall(): " + e);
        }
    }

    /**
     * Ends an established call, terminates an outgoing call or rejects an
     * incoming call. The method is only valid to call when the session state is
     * in {@link State#IN_CALL},
     * {@link State#INCOMING_CALL},
     * {@link State#OUTGOING_CALL} or
     * {@link State#OUTGOING_CALL_RING_BACK}.
     */
    public void endCall() {
        try {
            mSession.endCall();
        } catch (RemoteException e) {
            Log.e(TAG, "endCall(): " + e);
        }
    }

    /**
     * Changes the session description during a call. The method is only valid
     * to call when the session state is in {@link State#IN_CALL}.
     *
     * @param sessionDescription the new session description
     * @param timeout the session will be timed out if the call is not
     *        established within {@code timeout} seconds. Default value (defined
     *        by SIP protocol) is used if {@code timeout} is zero or negative.
     */
    public void changeCall(String sessionDescription, int timeout) {
        try {
            mSession.changeCall(sessionDescription, timeout);
        } catch (RemoteException e) {
            Log.e(TAG, "changeCall(): " + e);
        }
    }

    ISipSession getRealSession() {
        return mSession;
    }

    private ISipSessionListener createListener() {
        return new ISipSessionListener.Stub() {
            public void onCalling(ISipSession session) {
                if (mListener != null) {
                    mListener.onCalling(SipSession.this);
                }
            }

            public void onRinging(ISipSession session, SipProfile caller,
                    String sessionDescription) {
                if (mListener != null) {
                    mListener.onRinging(SipSession.this, caller,
                            sessionDescription);
                }
            }

            public void onRingingBack(ISipSession session) {
                if (mListener != null) {
                    mListener.onRingingBack(SipSession.this);
                }
            }

            public void onCallEstablished(ISipSession session,
                    String sessionDescription) {
                if (mListener != null) {
                    mListener.onCallEstablished(SipSession.this,
                            sessionDescription);
                }
            }

            public void onCallEnded(ISipSession session) {
                if (mListener != null) {
                    mListener.onCallEnded(SipSession.this);
                }
            }

            public void onCallBusy(ISipSession session) {
                if (mListener != null) {
                    mListener.onCallBusy(SipSession.this);
                }
            }

            public void onCallTransferring(ISipSession session,
                    String sessionDescription) {
                if (mListener != null) {
                    mListener.onCallTransferring(
                            new SipSession(session, SipSession.this.mListener),
                            sessionDescription);

                }
            }

            public void onCallChangeFailed(ISipSession session, int errorCode,
                    String message) {
                if (mListener != null) {
                    mListener.onCallChangeFailed(SipSession.this, errorCode,
                            message);
                }
            }

            public void onError(ISipSession session, int errorCode, String message) {
                if (mListener != null) {
                    mListener.onError(SipSession.this, errorCode, message);
                }
            }

            public void onRegistering(ISipSession session) {
                if (mListener != null) {
                    mListener.onRegistering(SipSession.this);
                }
            }

            public void onRegistrationDone(ISipSession session, int duration) {
                if (mListener != null) {
                    mListener.onRegistrationDone(SipSession.this, duration);
                }
            }

            public void onRegistrationFailed(ISipSession session, int errorCode,
                    String message) {
                if (mListener != null) {
                    mListener.onRegistrationFailed(SipSession.this, errorCode,
                            message);
                }
            }

            public void onRegistrationTimeout(ISipSession session) {
                if (mListener != null) {
                    mListener.onRegistrationTimeout(SipSession.this);
                }
            }
        };
    }
}
