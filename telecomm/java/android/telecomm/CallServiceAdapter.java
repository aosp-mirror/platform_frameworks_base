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

package android.telecomm;

import android.content.ComponentName;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;

import com.android.internal.telecomm.ICallService;
import com.android.internal.telecomm.ICallServiceAdapter;
import com.android.internal.telecomm.ICallVideoProvider;
import com.android.internal.telecomm.RemoteServiceCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Provides methods for ICallService implementations to interact with the system phone app.
 * TODO(santoscordon): Rename this to CallServiceAdapterDemultiplexer (or something).
 *
 * @hide
 */
public final class CallServiceAdapter implements DeathRecipient {
    private final Set<ICallServiceAdapter> mAdapters = new HashSet<>();

    /**
     * @hide
     */
    public CallServiceAdapter() {
    }

    /**
     * @hide
     */
    public void addAdapter(ICallServiceAdapter adapter) {
        if (mAdapters.add(adapter)) {
            try {
                adapter.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                mAdapters.remove(adapter);
            }
        }
    }

    /**
     * @hide
     */
    public void removeAdapter(ICallServiceAdapter adapter) {
        if (mAdapters.remove(adapter)) {
            adapter.asBinder().unlinkToDeath(this, 0);
        }
    }

    /** ${inheritDoc} */
    @Override
    public void binderDied() {
        ICallServiceAdapter adapterToRemove = null;
        for (ICallServiceAdapter adapter : mAdapters) {
            if (!adapter.asBinder().isBinderAlive()) {
                adapterToRemove = adapter;
                break;
            }
        }

        if (adapterToRemove != null) {
            removeAdapter(adapterToRemove);
        }
    }

    /**
     * Provides Telecomm with the details of an incoming call. An invocation of this method must
     * follow {@link CallService#setIncomingCallId} and use the call ID specified therein. Upon the
     * invocation of this method, Telecomm will bring up the incoming-call interface where the user
     * can elect to answer or reject a call.
     *
     * @param callInfo The details of the relevant call.
     */
    public void notifyIncomingCall(CallInfo callInfo) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.notifyIncomingCall(callInfo);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Tells Telecomm that an attempt to place the specified outgoing call succeeded.
     * TODO(santoscordon): Consider adding a CallState parameter in case this outgoing call is
     * somehow no longer in the DIALING state.
     *
     * @param callId The ID of the outgoing call.
     */
    public void handleSuccessfulOutgoingCall(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.handleSuccessfulOutgoingCall(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Tells Telecomm that an attempt to place the specified outgoing call failed.
     *
     * @param request The originating request for a connection.
     * @param errorCode The error code associated with the failed call attempt.
     * @param errorMsg The error message associated with the failed call attempt.
     */
    public void handleFailedOutgoingCall(
            ConnectionRequest request,
            int errorCode,
            String errorMsg) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.handleFailedOutgoingCall(request, errorCode, errorMsg);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Tells Telecomm to cancel the call.
     *
     * @param callId The ID of the outgoing call.
     */
    public void cancelOutgoingCall(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.cancelOutgoingCall(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to active (e.g., an ongoing call where two parties can actively
     * communicate).
     *
     * @param callId The unique ID of the call whose state is changing to active.
     */
    public void setActive(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setActive(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to ringing (e.g., an inbound ringing call).
     *
     * @param callId The unique ID of the call whose state is changing to ringing.
     */
    public void setRinging(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setRinging(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to dialing (e.g., dialing an outbound call).
     *
     * @param callId The unique ID of the call whose state is changing to dialing.
     */
    public void setDialing(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setDialing(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to disconnected.
     *
     * @param callId The unique ID of the call whose state is changing to disconnected.
     * @param disconnectCause The reason for the disconnection, any of
     *            {@link android.telephony.DisconnectCause}.
     * @param disconnectMessage Optional call-service-provided message about the disconnect.
     */
    public void setDisconnected(String callId, int disconnectCause, String disconnectMessage) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setDisconnected(callId, disconnectCause, disconnectMessage);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Sets a call's state to be on hold.
     *
     * @param callId - The unique ID of the call whose state is changing to be on hold.
     */
    public void setOnHold(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setOnHold(callId);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Asks Telecomm to start or stop a ringback tone for a call.
     *
     * @param callId The unique ID of the call whose ringback is being changed.
     * @param ringback Whether Telecomm should start playing a ringback tone.
     */
    public void setRequestingRingback(String callId, boolean ringback) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setRequestingRingback(callId, ringback);
            } catch (RemoteException e) {
            }
        }
    }

    /**
     * Indicates that the specified call can conference with any of the specified list of calls.
     *
     * @param callId The unique ID of the call.
     * @param canConference Specified whether or not the call can be conferenced.
     * @hide
     */
    public void setCanConference(String callId, boolean canConference) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setCanConference(callId, canConference);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Indicates whether or not the specified call is currently conferenced into the specified
     * conference call.
     *
     * @param callId The unique ID of the call being conferenced.
     * @param conferenceCallId The unique ID of the conference call. Null if call is not
     *            conferenced.
     * @hide
     */
    public void setIsConferenced(String callId, String conferenceCallId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setIsConferenced(callId, conferenceCallId);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Indicates that the call no longer exists. Can be used with either a call or a conference
     * call.
     *
     * @param callId The unique ID of the call.
     * @hide
     */
    public void removeCall(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.removeCall(callId);
            } catch (RemoteException ignored) {
            }
        }
    }

    public void onPostDialWait(String callId, String remaining) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.onPostDialWait(callId, remaining);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Indicates that a new conference call has been created.
     *
     * @param callId The unique ID of the conference call.
     */
    public void addConferenceCall(String callId) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.addConferenceCall(callId, null);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Retrieves a list of remote connection services usable to place calls.
     * @hide
     */
    public void queryRemoteConnectionServices(RemoteServiceCallback callback) {
        // Only supported when there is only one adapter.
        if (mAdapters.size() == 1) {
            try {
                mAdapters.iterator().next().queryRemoteConnectionServices(callback);
            } catch (RemoteException e) {
                Log.e(this, e, "Exception trying to query for remote CSs");
            }
        }
    }

    /**
     * Sets the call video provider for a call.
     *
     * @param callId The unique ID of the call to set with the given call video provider.
     * @param callVideoProvider The call video provider instance to set on the call.
     */
    public void setCallVideoProvider(String callId, CallVideoProvider callVideoProvider) {
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setCallVideoProvider(callId, callVideoProvider.getInterface());
            } catch (RemoteException e) {
            }
        }
    }

    /**
    * Set the features associated with the given call.
    * Features are defined in {@link android.telecomm.CallFeatures} and are passed in as a
    * bit-mask.
    *
    * @param callId The unique ID of the call to set features for.
    * @param features The features.
    */
    public void setFeatures(String callId, int features) {
        Log.v(this, "setFeatures: %d", features);
        for (ICallServiceAdapter adapter : mAdapters) {
            try {
                adapter.setFeatures(callId, features);
            } catch (RemoteException ignored) {
            }
        }
    }
}
