/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.telephony;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.carrier.CarrierService;
import android.telephony.Annotation.CallState;
import android.telephony.Annotation.DataActivityType;
import android.telephony.Annotation.DisconnectCauses;
import android.telephony.Annotation.NetworkType;
import android.telephony.Annotation.PreciseCallStates;
import android.telephony.Annotation.PreciseDisconnectCauses;
import android.telephony.Annotation.RadioPowerState;
import android.telephony.Annotation.SimActivationState;
import android.telephony.Annotation.SrvccState;
import android.telephony.TelephonyManager.CarrierPrivilegesCallback;
import android.telephony.emergency.EmergencyNumber;
import android.telephony.ims.ImsReasonInfo;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.listeners.ListenerExecutor;
import com.android.internal.telephony.ICarrierPrivilegesCallback;
import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.ITelephonyRegistry;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * A centralized place to notify telephony related status changes, e.g, {@link ServiceState} update
 * or {@link PhoneCapability} changed. This might trigger callback from applications side through
 * {@link android.telephony.PhoneStateListener}
 *
 * TODO: limit API access to only carrier apps with certain permissions or apps running on
 * privileged UID.
 *
 * @hide
 */
public class TelephonyRegistryManager {

    private static final String TAG = "TelephonyRegistryManager";
    private static ITelephonyRegistry sRegistry;
    private final Context mContext;

    /**
     * A mapping between {@link SubscriptionManager.OnSubscriptionsChangedListener} and
     * its callback IOnSubscriptionsChangedListener.
     */
    private final Map<SubscriptionManager.OnSubscriptionsChangedListener,
                IOnSubscriptionsChangedListener> mSubscriptionChangedListenerMap = new HashMap<>();
    /**
     * A mapping between {@link SubscriptionManager.OnOpportunisticSubscriptionsChangedListener} and
     * its callback IOnSubscriptionsChangedListener.
     */
    private final Map<SubscriptionManager.OnOpportunisticSubscriptionsChangedListener,
            IOnSubscriptionsChangedListener> mOpportunisticSubscriptionChangedListenerMap
            = new HashMap<>();


    /** @hide **/
    public TelephonyRegistryManager(@NonNull Context context) {
        mContext = context;
        if (sRegistry == null) {
            sRegistry = ITelephonyRegistry.Stub.asInterface(
                ServiceManager.getService("telephony.registry"));
        }
    }

    /**
     * Register for changes to the list of active {@link SubscriptionInfo} records or to the
     * individual records themselves. When a change occurs the onSubscriptionsChanged method of
     * the listener will be invoked immediately if there has been a notification. The
     * onSubscriptionChanged method will also be triggered once initially when calling this
     * function.
     *
     * @param listener an instance of {@link SubscriptionManager.OnSubscriptionsChangedListener}
     *                 with onSubscriptionsChanged overridden.
     * @param executor the executor that will execute callbacks.
     */
    public void addOnSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnSubscriptionsChangedListener listener,
            @NonNull Executor executor) {
        if (mSubscriptionChangedListenerMap.get(listener) != null) {
            Log.d(TAG, "addOnSubscriptionsChangedListener listener already present");
            return;
        }
        IOnSubscriptionsChangedListener callback = new IOnSubscriptionsChangedListener.Stub() {
            @Override
            public void onSubscriptionsChanged () {
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onSubscriptionsChanged());
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };
        mSubscriptionChangedListenerMap.put(listener, callback);
        try {
            sRegistry.addOnSubscriptionsChangedListener(mContext.getOpPackageName(),
                    mContext.getAttributionTag(), callback);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister the {@link SubscriptionManager.OnSubscriptionsChangedListener}. This is not
     * strictly necessary as the listener will automatically be unregistered if an attempt to
     * invoke the listener fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnSubscriptionsChangedListener listener) {
        if (mSubscriptionChangedListenerMap.get(listener) == null) {
            return;
        }
        try {
            sRegistry.removeOnSubscriptionsChangedListener(mContext.getOpPackageName(),
                    mSubscriptionChangedListenerMap.get(listener));
            mSubscriptionChangedListenerMap.remove(listener);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Register for changes to the list of opportunistic subscription records or to the
     * individual records themselves. When a change occurs the onOpportunisticSubscriptionsChanged
     * method of the listener will be invoked immediately if there has been a notification.
     *
     * @param listener an instance of
     * {@link SubscriptionManager.OnOpportunisticSubscriptionsChangedListener} with
     *                 onOpportunisticSubscriptionsChanged overridden.
     * @param executor an Executor that will execute callbacks.
     */
    public void addOnOpportunisticSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnOpportunisticSubscriptionsChangedListener listener,
            @NonNull Executor executor) {
        if (mOpportunisticSubscriptionChangedListenerMap.get(listener) != null) {
            Log.d(TAG, "addOnOpportunisticSubscriptionsChangedListener listener already present");
            return;
        }
        /**
         * The callback methods need to be called on the executor thread where
         * this object was created.  If the binder did that for us it'd be nice.
         */
        IOnSubscriptionsChangedListener callback = new IOnSubscriptionsChangedListener.Stub() {
            @Override
            public void onSubscriptionsChanged() {
                final long identity = Binder.clearCallingIdentity();
                try {
                    Log.d(TAG, "onOpportunisticSubscriptionsChanged callback received.");
                    executor.execute(() -> listener.onOpportunisticSubscriptionsChanged());
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };
        mOpportunisticSubscriptionChangedListenerMap.put(listener, callback);
        try {
            sRegistry.addOnOpportunisticSubscriptionsChangedListener(mContext.getOpPackageName(),
                    mContext.getAttributionTag(), callback);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Unregister the {@link SubscriptionManager.OnOpportunisticSubscriptionsChangedListener}
     * that is currently listening opportunistic subscriptions change. This is not strictly
     * necessary as the listener will automatically be unregistered if an attempt to invoke the
     * listener fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnOpportunisticSubscriptionsChangedListener(
            @NonNull SubscriptionManager.OnOpportunisticSubscriptionsChangedListener listener) {
        if (mOpportunisticSubscriptionChangedListenerMap.get(listener) == null) {
            return;
        }
        try {
            sRegistry.removeOnSubscriptionsChangedListener(mContext.getOpPackageName(),
                    mOpportunisticSubscriptionChangedListenerMap.get(listener));
            mOpportunisticSubscriptionChangedListenerMap.remove(listener);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * To check the SDK version for {@link #listenFromListener}.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.P)
    private static final long LISTEN_CODE_CHANGE = 147600208L;

    /**
     * Listen for incoming subscriptions
     * @param subId Subscription ID
     * @param pkg Package name
     * @param featureId Feature ID
     * @param listener Listener providing callback
     * @param events Events
     * @param notifyNow Whether to notify instantly
     */
    public void listenFromListener(int subId, @NonNull String pkg, @NonNull String featureId,
            @NonNull PhoneStateListener listener, @NonNull int events, boolean notifyNow) {
        if (listener == null) {
            throw new IllegalStateException("telephony service is null.");
        }

        try {
            int[] eventsList = getEventsFromBitmask(events).stream().mapToInt(i -> i).toArray();
            // subId from PhoneStateListener is deprecated Q on forward, use the subId from
            // TelephonyManager instance. Keep using subId from PhoneStateListener for pre-Q.
            if (Compatibility.isChangeEnabled(LISTEN_CODE_CHANGE)) {
                // Since mSubId in PhoneStateListener is deprecated from Q on forward, this is
                // the only place to set mSubId and its for "informational" only.
                listener.mSubId = (eventsList.length == 0)
                        ? SubscriptionManager.INVALID_SUBSCRIPTION_ID : subId;
            } else if (listener.mSubId != null) {
                subId = listener.mSubId;
            }
            sRegistry.listenWithEventList(false, false, subId, pkg, featureId,
                    listener.callback, eventsList, notifyNow);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Listen for incoming subscriptions
     * @param subId Subscription ID
     * @param pkg Package name
     * @param featureId Feature ID
     * @param telephonyCallback Listener providing callback
     * @param events List events
     * @param notifyNow Whether to notify instantly
     */
    private void listenFromCallback(boolean renounceFineLocationAccess,
            boolean renounceCoarseLocationAccess, int subId,
            @NonNull String pkg, @NonNull String featureId,
            @NonNull TelephonyCallback telephonyCallback, @NonNull int[] events,
            boolean notifyNow) {
        try {
            sRegistry.listenWithEventList(renounceFineLocationAccess, renounceCoarseLocationAccess,
                    subId, pkg, featureId, telephonyCallback.callback, events, notifyNow);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Informs the system of an intentional upcoming carrier network change by a carrier app.
     * This call only used to allow the system to provide alternative UI while telephony is
     * performing an action that may result in intentional, temporary network lack of connectivity.
     * <p>
     * Based on the active parameter passed in, this method will either show or hide the alternative
     * UI. There is no timeout associated with showing this UX, so a carrier app must be sure to
     * call with active set to false sometime after calling with it set to {@code true}.
     * <p>
     * This will apply to all subscriptions the carrier app has carrier privileges on.
     * <p>
     * Requires Permission: calling app has carrier privileges.
     *
     * @param active Whether the carrier network change is or shortly will be
     * active. Set this value to true to begin showing alternative UI and false to stop.
     * @see TelephonyManager#hasCarrierPrivileges
     */
    public void notifyCarrierNetworkChange(boolean active) {
        try {
            sRegistry.notifyCarrierNetworkChange(active);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Informs the system of an intentional upcoming carrier network change by a carrier app on the
     * given {@code subscriptionId}. This call only used to allow the system to provide alternative
     * UI while telephony is performing an action that may result in intentional, temporary network
     * lack of connectivity.
     * <p>
     * Based on the active parameter passed in, this method will either show or hide the
     * alternative UI. There is no timeout associated with showing this UX, so a carrier app must be
     * sure to call with active set to false sometime after calling with it set to {@code true}.
     * <p>
     * Requires Permission: calling app has carrier privileges.
     *
     * @param subscriptionId the subscription of the carrier network.
     * @param active whether the carrier network change is or shortly will be active. Set this value
     *              to true to begin showing alternative UI and false to stop.
     * @see TelephonyManager#hasCarrierPrivileges
     */
    public void notifyCarrierNetworkChange(int subscriptionId, boolean active) {
        try {
            sRegistry.notifyCarrierNetworkChangeWithSubId(subscriptionId, active);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify call state changed on certain subscription.
     *
     * @param slotIndex for which call state changed. Can be derived from subId except when subId is
     * invalid.
     * @param subId for which call state changed.
     * @param state latest call state. e.g, offhook, ringing
     * @param incomingNumber incoming phone number.
     */
    public void notifyCallStateChanged(int slotIndex, int subId, @CallState int state,
            @Nullable String incomingNumber) {
        try {
            sRegistry.notifyCallState(slotIndex, subId, state, incomingNumber);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify call state changed on all subscriptions.
     *
     * @param state latest call state. e.g, offhook, ringing
     * @param incomingNumber incoming phone number.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void notifyCallStateChangedForAllSubscriptions(@CallState int state,
            @Nullable String incomingNumber) {
        try {
            sRegistry.notifyCallStateForAllSubs(state, incomingNumber);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link SubscriptionInfo} change.
     * @hide
     */
    public void notifySubscriptionInfoChanged() {
        try {
            sRegistry.notifySubscriptionInfoChanged();
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify opportunistic {@link SubscriptionInfo} change.
     * @hide
     */
    public void notifyOpportunisticSubscriptionInfoChanged() {
        try {
            sRegistry.notifyOpportunisticSubscriptionInfoChanged();
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link ServiceState} update on certain subscription.
     *
     * @param slotIndex for which the service state changed. Can be derived from subId except
     * subId is invalid.
     * @param subId for which the service state changed.
     * @param state service state e.g, in service, out of service or roaming status.
     */
    public void notifyServiceStateChanged(int slotIndex, int subId, @NonNull ServiceState state) {
        try {
            sRegistry.notifyServiceStateForPhoneId(slotIndex, subId, state);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link SignalStrength} update on certain subscription.
     *
     * @param slotIndex for which the signalstrength changed. Can be derived from subId except when
     * subId is invalid.
     * @param subId for which the signalstrength changed.
     * @param signalStrength e.g, signalstrength level {@see SignalStrength#getLevel()}
     */
    public void notifySignalStrengthChanged(int slotIndex, int subId,
            @NonNull SignalStrength signalStrength) {
        try {
            sRegistry.notifySignalStrengthForPhoneId(slotIndex, subId, signalStrength);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify changes to the message-waiting indicator on certain subscription. e.g, The status bar
     * uses message waiting indicator to determine when to display the voicemail icon.
     *
     * @param slotIndex for which message waiting indicator changed. Can be derived from subId
     * except when subId is invalid.
     * @param subId for which message waiting indicator changed.
     * @param msgWaitingInd {@code true} indicates there is message-waiting indicator, {@code false}
     * otherwise.
     */
    public void notifyMessageWaitingChanged(int slotIndex, int subId, boolean msgWaitingInd) {
        try {
            sRegistry.notifyMessageWaitingChangedForPhoneId(slotIndex, subId, msgWaitingInd);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify changes to the call-forwarding status on certain subscription.
     *
     * @param subId for which call forwarding status changed.
     * @param callForwardInd {@code true} indicates there is call forwarding, {@code false}
     * otherwise.
     */
    public void notifyCallForwardingChanged(int subId, boolean callForwardInd) {
        try {
            sRegistry.notifyCallForwardingChangedForSubscriber(subId, callForwardInd);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify changes to activity state changes on certain subscription.
     *
     * @param subId for which data activity state changed.
     * @param dataActivityType indicates the latest data activity type e.g, {@link
     * TelephonyManager#DATA_ACTIVITY_IN}
     */
    public void notifyDataActivityChanged(int subId, @DataActivityType int dataActivityType) {
        try {
            sRegistry.notifyDataActivityForSubscriber(subId, dataActivityType);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify changes to default (Internet) data connection state on certain subscription.
     *
     * @param slotIndex for which data connections state changed. Can be derived from subId except
     * when subId is invalid.
     * @param subId for which data connection state changed.
     * @param preciseState the PreciseDataConnectionState
     *
     * @see PreciseDataConnectionState
     * @see TelephonyManager#DATA_DISCONNECTED
     */
    public void notifyDataConnectionForSubscriber(int slotIndex, int subId,
            @NonNull PreciseDataConnectionState preciseState) {
        try {
            sRegistry.notifyDataConnectionForSubscriber(
                    slotIndex, subId, preciseState);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link CallQuality} change on certain subscription.
     *
     * @param slotIndex for which call quality state changed. Can be derived from subId except when
     * subId is invalid.
     * @param subId for which call quality state changed.
     * @param callQuality Information about call quality e.g, call quality level
     * @param networkType associated with this data connection. e.g, LTE
     */
    public void notifyCallQualityChanged(int slotIndex, int subId, @NonNull CallQuality callQuality,
        @NetworkType int networkType) {
        try {
            sRegistry.notifyCallQualityChanged(callQuality, slotIndex, subId, networkType);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify emergency number list changed on certain subscription.
     *
     * @param slotIndex for which emergency number list changed. Can be derived from subId except
     * when subId is invalid.
     * @param subId for which emergency number list changed.
     */
    public void notifyEmergencyNumberList( int slotIndex, int subId) {
        try {
            sRegistry.notifyEmergencyNumberList(slotIndex, subId);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify outgoing emergency call.
     * @param phoneId Sender phone ID.
     * @param subId Sender subscription ID.
     * @param emergencyNumber Emergency number.
     */
    public void notifyOutgoingEmergencyCall(int phoneId, int subId,
            @NonNull EmergencyNumber emergencyNumber) {
        try {
            sRegistry.notifyOutgoingEmergencyCall(phoneId, subId, emergencyNumber);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify outgoing emergency SMS.
     * @param phoneId Sender phone ID.
     * @param subId Sender subscription ID.
     * @param emergencyNumber Emergency number.
     */
    public void notifyOutgoingEmergencySms(int phoneId, int subId,
            @NonNull EmergencyNumber emergencyNumber) {
        try {
            sRegistry.notifyOutgoingEmergencySms(phoneId, subId, emergencyNumber);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify radio power state changed on certain subscription.
     *
     * @param slotIndex for which radio power state changed. Can be derived from subId except when
     * subId is invalid.
     * @param subId for which radio power state changed.
     * @param radioPowerState the current modem radio state.
     */
    public void notifyRadioPowerStateChanged(int slotIndex, int subId,
            @RadioPowerState int radioPowerState) {
        try {
            sRegistry.notifyRadioPowerStateChanged(slotIndex, subId, radioPowerState);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link PhoneCapability} changed.
     *
     * @param phoneCapability the capability of the modem group.
     */
    public void notifyPhoneCapabilityChanged(@NonNull PhoneCapability phoneCapability) {
        try {
            sRegistry.notifyPhoneCapabilityChanged(phoneCapability);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Sim activation type: voice
     * @see #notifyVoiceActivationStateChanged
     * @hide
     */
    public static final int SIM_ACTIVATION_TYPE_VOICE = 0;
    /**
     * Sim activation type: data
     * @see #notifyDataActivationStateChanged
     * @hide
     */
    public static final int SIM_ACTIVATION_TYPE_DATA = 1;

    /**
     * Notify data activation state changed on certain subscription.
     * @see TelephonyManager#getDataActivationState()
     *
     * @param slotIndex for which data activation state changed. Can be derived from subId except
     * when subId is invalid.
     * @param subId for which data activation state changed.
     * @param activationState sim activation state e.g, activated.
     */
    public void notifyDataActivationStateChanged(int slotIndex, int subId,
            @SimActivationState int activationState) {
        try {
            sRegistry.notifySimActivationStateChangedForPhoneId(slotIndex, subId,
                    SIM_ACTIVATION_TYPE_DATA, activationState);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify voice activation state changed on certain subscription.
     * @see TelephonyManager#getVoiceActivationState()
     *
     * @param slotIndex for which voice activation state changed. Can be derived from subId except
     * subId is invalid.
     * @param subId for which voice activation state changed.
     * @param activationState sim activation state e.g, activated.
     */
    public void notifyVoiceActivationStateChanged(int slotIndex, int subId,
            @SimActivationState int activationState) {
        try {
            sRegistry.notifySimActivationStateChangedForPhoneId(slotIndex, subId,
                    SIM_ACTIVATION_TYPE_VOICE, activationState);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify User mobile data state changed on certain subscription. e.g, mobile data is enabled
     * or disabled.
     *
     * @param slotIndex for which mobile data state has changed. Can be derived from subId except
     * when subId is invalid.
     * @param subId for which mobile data state has changed.
     * @param state {@code true} indicates mobile data is enabled/on. {@code false} otherwise.
     */
    public void notifyUserMobileDataStateChanged(int slotIndex, int subId, boolean state) {
        try {
            sRegistry.notifyUserMobileDataStateChangedForPhoneId(slotIndex, subId, state);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify display info changed.
     *
     * @param slotIndex The SIM slot index for which display info has changed. Can be
     * derived from {@code subscriptionId} except when {@code subscriptionId} is invalid, such as
     * when the device is in emergency-only mode.
     * @param subscriptionId Subscription id for which display network info has changed.
     * @param telephonyDisplayInfo The display info.
     */
    public void notifyDisplayInfoChanged(int slotIndex, int subscriptionId,
            @NonNull TelephonyDisplayInfo telephonyDisplayInfo) {
        try {
            sRegistry.notifyDisplayInfoChanged(slotIndex, subscriptionId, telephonyDisplayInfo);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify IMS call disconnect causes which contains {@link android.telephony.ims.ImsReasonInfo}.
     *
     * @param subId for which ims call disconnect.
     * @param imsReasonInfo the reason for ims call disconnect.
     */
    public void notifyImsDisconnectCause(int subId, @NonNull ImsReasonInfo imsReasonInfo) {
        try {
            sRegistry.notifyImsDisconnectCause(subId, imsReasonInfo);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify single Radio Voice Call Continuity (SRVCC) state change for the currently active call
     * on certain subscription.
     *
     * @param subId for which srvcc state changed.
     * @param state srvcc state
     */
    public void notifySrvccStateChanged(int subId, @SrvccState int state) {
        try {
            sRegistry.notifySrvccStateChanged(subId, state);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify precise call state changed on certain subscription, including foreground, background
     * and ringcall states.
     *
     * @param slotIndex for which precise call state changed. Can be derived from subId except when
     * subId is invalid.
     * @param subId for which precise call state changed.
     * @param ringCallPreciseState ringCall state.
     * @param foregroundCallPreciseState foreground call state.
     * @param backgroundCallPreciseState background call state.
     */
    public void notifyPreciseCallState(int slotIndex, int subId,
            @PreciseCallStates int ringCallPreciseState,
            @PreciseCallStates int foregroundCallPreciseState,
            @PreciseCallStates int backgroundCallPreciseState) {
        try {
            sRegistry.notifyPreciseCallState(slotIndex, subId, ringCallPreciseState,
                foregroundCallPreciseState, backgroundCallPreciseState);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify call disconnect causes which contains {@link DisconnectCause} and {@link
     * android.telephony.PreciseDisconnectCause}.
     *
     * @param slotIndex for which call disconnected. Can be derived from subId except when subId is
     * invalid.
     * @param subId for which call disconnected.
     * @param cause {@link DisconnectCause} for the disconnected call.
     * @param preciseCause {@link android.telephony.PreciseDisconnectCause} for the disconnected
     * call.
     */
    public void notifyDisconnectCause(int slotIndex, int subId, @DisconnectCauses int cause,
            @PreciseDisconnectCauses int preciseCause) {
        try {
            sRegistry.notifyDisconnectCause(slotIndex, subId, cause, preciseCause);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link android.telephony.CellLocation} changed.
     *
     * <p>To be compatible with {@link TelephonyRegistry}, use {@link CellIdentity} which is
     * parcelable, and convert to CellLocation in client code.
     */
    public void notifyCellLocation(int subId, @NonNull CellIdentity cellLocation) {
        try {
            sRegistry.notifyCellLocationForSubscriber(subId, cellLocation);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link CellInfo} changed on certain subscription. e.g, when an observed cell info has
     * changed or new cells have been added or removed on the given subscription.
     *
     * @param subId for which cellinfo changed.
     * @param cellInfo A list of cellInfo associated with the given subscription.
     */
    public void notifyCellInfoChanged(int subId, @NonNull List<CellInfo> cellInfo) {
        try {
            sRegistry.notifyCellInfoForSubscriber(subId, cellInfo);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify that the active data subscription ID has changed.
     * @param activeDataSubId The new subscription ID for active data
     */
    public void notifyActiveDataSubIdChanged(int activeDataSubId) {
        try {
            sRegistry.notifyActiveDataSubIdChanged(activeDataSubId);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Report that Registration or a Location/Routing/Tracking Area update has failed.
     *
     * @param slotIndex for which call disconnected. Can be derived from subId except when subId is
     * invalid.
     * @param subId for which cellinfo changed.
     * @param cellIdentity the CellIdentity, which must include the globally unique identifier
     *        for the cell (for example, all components of the CGI or ECGI).
     * @param chosenPlmn a 5 or 6 digit alphanumeric PLMN (MCC|MNC) among those broadcast by the
     *         cell that was chosen for the failed registration attempt.
     * @param domain DOMAIN_CS, DOMAIN_PS or both in case of a combined procedure.
     * @param causeCode the primary failure cause code of the procedure.
     *        For GSM/UMTS (MM), values are in TS 24.008 Sec 10.5.95
     *        For GSM/UMTS (GMM), values are in TS 24.008 Sec 10.5.147
     *        For LTE (EMM), cause codes are TS 24.301 Sec 9.9.3.9
     *        For NR (5GMM), cause codes are TS 24.501 Sec 9.11.3.2
     *        Integer.MAX_VALUE if this value is unused.
     * @param additionalCauseCode the cause code of any secondary/combined procedure if appropriate.
     *        For UMTS, if a combined attach succeeds for PS only, then the GMM cause code shall be
     *        included as an additionalCauseCode. For LTE (ESM), cause codes are in
     *        TS 24.301 9.9.4.4. Integer.MAX_VALUE if this value is unused.
     */
    public void notifyRegistrationFailed(int slotIndex, int subId,
            @NonNull CellIdentity cellIdentity, @NonNull String chosenPlmn,
            int domain, int causeCode, int additionalCauseCode) {
        try {
            sRegistry.notifyRegistrationFailed(slotIndex, subId, cellIdentity,
                    chosenPlmn, domain, causeCode, additionalCauseCode);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link BarringInfo} has changed for a specific subscription.
     *
     * @param slotIndex for the phone object that got updated barring info.
     * @param subId for which the BarringInfo changed.
     * @param barringInfo updated BarringInfo.
     */
    public void notifyBarringInfoChanged(
            int slotIndex, int subId, @NonNull BarringInfo barringInfo) {
        try {
            sRegistry.notifyBarringInfoChanged(slotIndex, subId, barringInfo);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify {@link PhysicalChannelConfig} has changed for a specific subscription.
     *
     * @param slotIndex for which physical channel configs changed.
     * @param subId the subId
     * @param configs a list of {@link PhysicalChannelConfig}, the configs of physical channel.
     */
    public void notifyPhysicalChannelConfigForSubscriber(int slotIndex, int subId,
            List<PhysicalChannelConfig> configs) {
        try {
            sRegistry.notifyPhysicalChannelConfigForSubscriber(slotIndex, subId, configs);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify that the data enabled has changed.
     *
     * @param enabled True if data is enabled, otherwise disabled.
     * @param reason Reason for data enabled/disabled. See {@code REASON_*} in
     * {@link TelephonyManager}.
     */
    public void notifyDataEnabled(int slotIndex, int subId, boolean enabled,
            @TelephonyManager.DataEnabledReason int reason) {
        try {
            sRegistry.notifyDataEnabled(slotIndex, subId, enabled, reason);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify the allowed network types has changed for a specific subscription and the specific
     * reason.
     * @param slotIndex for which allowed network types changed.
     * @param subId for which allowed network types changed.
     * @param reason an allowed network type reasons.
     * @param allowedNetworkType an allowed network type bitmask value.
     */
    public void notifyAllowedNetworkTypesChanged(int slotIndex, int subId,
            int reason, long allowedNetworkType) {
        try {
            sRegistry.notifyAllowedNetworkTypesChanged(slotIndex, subId, reason,
                    allowedNetworkType);
        } catch (RemoteException ex) {
            // system process is dead
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Notify that the link capacity estimate has changed.
     * @param slotIndex for the phone object that gets the updated link capacity estimate
     * @param subId for subscription that gets the updated link capacity estimate
     * @param linkCapacityEstimateList a list of {@link  LinkCapacityEstimate}
     */
    public void notifyLinkCapacityEstimateChanged(int slotIndex, int subId,
            List<LinkCapacityEstimate> linkCapacityEstimateList) {
        try {
            sRegistry.notifyLinkCapacityEstimateChanged(slotIndex, subId, linkCapacityEstimateList);
        } catch (RemoteException ex) {
            // system server crash
            throw ex.rethrowFromSystemServer();
        }
    }

    public @NonNull Set<Integer> getEventsFromCallback(
            @NonNull TelephonyCallback telephonyCallback) {
        Set<Integer> eventList = new ArraySet<>();

        if (telephonyCallback instanceof TelephonyCallback.ServiceStateListener) {
            eventList.add(TelephonyCallback.EVENT_SERVICE_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.MessageWaitingIndicatorListener) {
            eventList.add(TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.CallForwardingIndicatorListener) {
            eventList.add(TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.CellLocationListener) {
            eventList.add(TelephonyCallback.EVENT_CELL_LOCATION_CHANGED);
        }

        // Note: Legacy PhoneStateListeners use EVENT_LEGACY_CALL_STATE_CHANGED
        if (telephonyCallback instanceof TelephonyCallback.CallStateListener) {
            eventList.add(TelephonyCallback.EVENT_CALL_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.DataConnectionStateListener) {
            eventList.add(TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.DataActivityListener) {
            eventList.add(TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.SignalStrengthsListener) {
            eventList.add(TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.CellInfoListener) {
            eventList.add(TelephonyCallback.EVENT_CELL_INFO_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.PreciseCallStateListener) {
            eventList.add(TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.CallDisconnectCauseListener) {
            eventList.add(TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.ImsCallDisconnectCauseListener) {
            eventList.add(TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.PreciseDataConnectionStateListener) {
            eventList.add(TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.SrvccStateListener) {
            eventList.add(TelephonyCallback.EVENT_SRVCC_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.VoiceActivationStateListener) {
            eventList.add(TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.DataActivationStateListener) {
            eventList.add(TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.UserMobileDataStateListener) {
            eventList.add(TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.DisplayInfoListener) {
            eventList.add(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.EmergencyNumberListListener) {
            eventList.add(TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.OutgoingEmergencyCallListener) {
            eventList.add(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL);
        }

        if (telephonyCallback instanceof TelephonyCallback.OutgoingEmergencySmsListener) {
            eventList.add(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS);
        }

        if (telephonyCallback instanceof TelephonyCallback.PhoneCapabilityListener) {
            eventList.add(TelephonyCallback.EVENT_PHONE_CAPABILITY_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.ActiveDataSubscriptionIdListener) {
            eventList.add(TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.RadioPowerStateListener) {
            eventList.add(TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.CarrierNetworkListener) {
            eventList.add(TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.RegistrationFailedListener) {
            eventList.add(TelephonyCallback.EVENT_REGISTRATION_FAILURE);
        }

        if (telephonyCallback instanceof TelephonyCallback.CallAttributesListener) {
            eventList.add(TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.BarringInfoListener) {
            eventList.add(TelephonyCallback.EVENT_BARRING_INFO_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.PhysicalChannelConfigListener) {
            eventList.add(TelephonyCallback.EVENT_PHYSICAL_CHANNEL_CONFIG_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.DataEnabledListener) {
            eventList.add(TelephonyCallback.EVENT_DATA_ENABLED_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.AllowedNetworkTypesListener) {
            eventList.add(TelephonyCallback.EVENT_ALLOWED_NETWORK_TYPE_LIST_CHANGED);
        }

        if (telephonyCallback instanceof TelephonyCallback.LinkCapacityEstimateChangedListener) {
            eventList.add(TelephonyCallback.EVENT_LINK_CAPACITY_ESTIMATE_CHANGED);
        }

        return eventList;
    }

    private @NonNull Set<Integer> getEventsFromBitmask(int eventMask) {

        Set<Integer> eventList = new ArraySet<>();

        if ((eventMask & PhoneStateListener.LISTEN_SERVICE_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_SERVICE_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_SIGNAL_STRENGTH) != 0) {
            eventList.add(TelephonyCallback.EVENT_SIGNAL_STRENGTH_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_MESSAGE_WAITING_INDICATOR) != 0) {
            eventList.add(TelephonyCallback.EVENT_MESSAGE_WAITING_INDICATOR_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR) != 0) {
            eventList.add(TelephonyCallback.EVENT_CALL_FORWARDING_INDICATOR_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_CELL_LOCATION) != 0) {
            eventList.add(TelephonyCallback.EVENT_CELL_LOCATION_CHANGED);
        }

        // Note: Legacy call state listeners can get the phone number which is not provided in the
        // new version in TelephonyCallback.
        if ((eventMask & PhoneStateListener.LISTEN_CALL_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_LEGACY_CALL_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_DATA_CONNECTION_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_DATA_CONNECTION_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_DATA_ACTIVITY) != 0) {
            eventList.add(TelephonyCallback.EVENT_DATA_ACTIVITY_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) != 0) {
            eventList.add(TelephonyCallback.EVENT_SIGNAL_STRENGTHS_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_CELL_INFO) != 0) {
            eventList.add(TelephonyCallback.EVENT_CELL_INFO_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_PRECISE_CALL_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_PRECISE_CALL_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_PRECISE_DATA_CONNECTION_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_PRECISE_DATA_CONNECTION_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_DATA_CONNECTION_REAL_TIME_INFO) != 0) {
            eventList.add(TelephonyCallback.EVENT_DATA_CONNECTION_REAL_TIME_INFO_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_OEM_HOOK_RAW_EVENT) != 0) {
            eventList.add(TelephonyCallback.EVENT_OEM_HOOK_RAW);
        }

        if ((eventMask & PhoneStateListener.LISTEN_SRVCC_STATE_CHANGED) != 0) {
            eventList.add(TelephonyCallback.EVENT_SRVCC_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_CARRIER_NETWORK_CHANGE) != 0) {
            eventList.add(TelephonyCallback.EVENT_CARRIER_NETWORK_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_VOICE_ACTIVATION_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_VOICE_ACTIVATION_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_DATA_ACTIVATION_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_DATA_ACTIVATION_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_USER_MOBILE_DATA_STATE) != 0) {
            eventList.add(TelephonyCallback.EVENT_USER_MOBILE_DATA_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_DISPLAY_INFO_CHANGED) != 0) {
            eventList.add(TelephonyCallback.EVENT_DISPLAY_INFO_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_PHONE_CAPABILITY_CHANGE) != 0) {
            eventList.add(TelephonyCallback.EVENT_PHONE_CAPABILITY_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGE) != 0) {
            eventList.add(TelephonyCallback.EVENT_ACTIVE_DATA_SUBSCRIPTION_ID_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_RADIO_POWER_STATE_CHANGED) != 0) {
            eventList.add(TelephonyCallback.EVENT_RADIO_POWER_STATE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_EMERGENCY_NUMBER_LIST) != 0) {
            eventList.add(TelephonyCallback.EVENT_EMERGENCY_NUMBER_LIST_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_CALL_DISCONNECT_CAUSES) != 0) {
            eventList.add(TelephonyCallback.EVENT_CALL_DISCONNECT_CAUSE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_CALL_ATTRIBUTES_CHANGED) != 0) {
            eventList.add(TelephonyCallback.EVENT_CALL_ATTRIBUTES_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_IMS_CALL_DISCONNECT_CAUSES) != 0) {
            eventList.add(TelephonyCallback.EVENT_IMS_CALL_DISCONNECT_CAUSE_CHANGED);
        }

        if ((eventMask & PhoneStateListener.LISTEN_OUTGOING_EMERGENCY_CALL) != 0) {
            eventList.add(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_CALL);
        }

        if ((eventMask & PhoneStateListener.LISTEN_OUTGOING_EMERGENCY_SMS) != 0) {
            eventList.add(TelephonyCallback.EVENT_OUTGOING_EMERGENCY_SMS);
        }

        if ((eventMask & PhoneStateListener.LISTEN_REGISTRATION_FAILURE) != 0) {
            eventList.add(TelephonyCallback.EVENT_REGISTRATION_FAILURE);
        }

        if ((eventMask & PhoneStateListener.LISTEN_BARRING_INFO) != 0) {
            eventList.add(TelephonyCallback.EVENT_BARRING_INFO_CHANGED);
        }
        return eventList;

    }

    /**
     * Registers a callback object to receive notification of changes in specified telephony states.
     * <p>
     * To register a callback, pass a {@link TelephonyCallback} which implements
     * interfaces of events. For example,
     * FakeServiceStateCallback extends {@link TelephonyCallback} implements
     * {@link TelephonyCallback.ServiceStateListener}.
     *
     * At registration, and when a specified telephony state changes, the telephony manager invokes
     * the appropriate callback method on the callback object and passes the current (updated)
     * values.
     * <p>
     *
     * If this TelephonyManager object has been created with
     * {@link TelephonyManager#createForSubscriptionId}, applies to the given subId.
     * Otherwise, applies to {@link SubscriptionManager#getDefaultSubscriptionId()}.
     * To register events for multiple subIds, pass a separate callback object to
     * each TelephonyManager object created with {@link TelephonyManager#createForSubscriptionId}.
     *
     * Note: if you call this method while in the middle of a binder transaction, you <b>must</b>
     * call {@link android.os.Binder#clearCallingIdentity()} before calling this method. A
     * {@link SecurityException} will be thrown otherwise.
     *
     * This API should be used sparingly -- large numbers of callbacks will cause system
     * instability. If a process has registered too many callbacks without unregistering them, it
     * may encounter an {@link IllegalStateException} when trying to register more callbacks.
     *
     * @param callback The {@link TelephonyCallback} object to register.
     */
    public void registerTelephonyCallback(boolean renounceFineLocationAccess,
            boolean renounceCoarseLocationAccess,
            @NonNull @CallbackExecutor Executor executor,
            int subId, String pkgName, String attributionTag, @NonNull TelephonyCallback callback,
            boolean notifyNow) {
        if (callback == null) {
            throw new IllegalStateException("telephony service is null.");
        }
        callback.init(executor);
        listenFromCallback(renounceFineLocationAccess, renounceCoarseLocationAccess, subId,
                pkgName, attributionTag, callback,
                getEventsFromCallback(callback).stream().mapToInt(i -> i).toArray(), notifyNow);
    }

    /**
     * Unregister an existing {@link TelephonyCallback}.
     *
     * @param callback The {@link TelephonyCallback} object to unregister.
     */
    public void unregisterTelephonyCallback(int subId, String pkgName, String attributionTag,
            @NonNull TelephonyCallback callback, boolean notifyNow) {
        listenFromCallback(false, false, subId,
                pkgName, attributionTag, callback, new int[0], notifyNow);
    }

    private static class CarrierPrivilegesCallbackWrapper extends ICarrierPrivilegesCallback.Stub
            implements ListenerExecutor {
        @NonNull private final WeakReference<CarrierPrivilegesCallback> mCallback;
        @NonNull private final Executor mExecutor;

        CarrierPrivilegesCallbackWrapper(
                @NonNull CarrierPrivilegesCallback callback, @NonNull Executor executor) {
            mCallback = new WeakReference<>(callback);
            mExecutor = executor;
        }

        @Override
        public void onCarrierPrivilegesChanged(
                @NonNull List<String> privilegedPackageNames, @NonNull int[] privilegedUids) {
            // AIDL interface does not support Set, keep the List/Array and translate them here
            Set<String> privilegedPkgNamesSet = Set.copyOf(privilegedPackageNames);
            Set<Integer> privilegedUidsSet = Arrays.stream(privilegedUids).boxed().collect(
                    Collectors.toSet());
            Binder.withCleanCallingIdentity(
                    () ->
                            executeSafely(
                                    mExecutor,
                                    mCallback::get,
                                    cpc ->
                                            cpc.onCarrierPrivilegesChanged(
                                                    privilegedPkgNamesSet, privilegedUidsSet)));
        }

        @Override
        public void onCarrierServiceChanged(@Nullable String packageName, int uid) {
            Binder.withCleanCallingIdentity(
                    () ->
                            executeSafely(
                                    mExecutor,
                                    mCallback::get,
                                    cpc -> cpc.onCarrierServiceChanged(packageName, uid)));
        }
    }

    @NonNull
    @GuardedBy("sCarrierPrivilegeCallbacks")
    private static final WeakHashMap<CarrierPrivilegesCallback,
            WeakReference<CarrierPrivilegesCallbackWrapper>>
            sCarrierPrivilegeCallbacks = new WeakHashMap<>();

    /**
     * Registers a {@link CarrierPrivilegesCallback} on the given {@code logicalSlotIndex} to
     * receive callbacks when the set of packages with carrier privileges changes. The callback will
     * immediately be called with the latest state.
     *
     * @param logicalSlotIndex The SIM slot to listen on
     * @param executor The executor where {@code listener} will be invoked
     * @param callback The callback to register
     */
    public void addCarrierPrivilegesCallback(
            int logicalSlotIndex,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CarrierPrivilegesCallback callback) {
        if (callback == null || executor == null) {
            throw new IllegalArgumentException("callback and executor must be non-null");
        }
        synchronized (sCarrierPrivilegeCallbacks) {
            WeakReference<CarrierPrivilegesCallbackWrapper> existing =
                    sCarrierPrivilegeCallbacks.get(callback);
            if (existing != null && existing.get() != null) {
                Log.d(TAG, "addCarrierPrivilegesCallback: callback already registered");
                return;
            }
            CarrierPrivilegesCallbackWrapper wrapper =
                    new CarrierPrivilegesCallbackWrapper(callback, executor);
            sCarrierPrivilegeCallbacks.put(callback, new WeakReference<>(wrapper));
            try {
                sRegistry.addCarrierPrivilegesCallback(
                        logicalSlotIndex,
                        wrapper,
                        mContext.getOpPackageName(),
                        mContext.getAttributionTag());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregisters a {@link CarrierPrivilegesCallback}.
     *
     * @param callback The callback to unregister
     */
    public void removeCarrierPrivilegesCallback(@NonNull CarrierPrivilegesCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("listener must be non-null");
        }
        synchronized (sCarrierPrivilegeCallbacks) {
            WeakReference<CarrierPrivilegesCallbackWrapper> ref =
                    sCarrierPrivilegeCallbacks.remove(callback);
            if (ref == null) return;
            CarrierPrivilegesCallbackWrapper wrapper = ref.get();
            if (wrapper == null) return;
            try {
                sRegistry.removeCarrierPrivilegesCallback(wrapper, mContext.getOpPackageName());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Notify listeners that the set of packages with carrier privileges has changed.
     *
     * @param logicalSlotIndex The SIM slot the change occurred on
     * @param privilegedPackageNames The updated set of packages names with carrier privileges
     * @param privilegedUids The updated set of UIDs with carrier privileges
     */
    public void notifyCarrierPrivilegesChanged(
            int logicalSlotIndex,
            @NonNull Set<String> privilegedPackageNames,
            @NonNull Set<Integer> privilegedUids) {
        if (privilegedPackageNames == null || privilegedUids == null) {
            throw new IllegalArgumentException(
                    "privilegedPackageNames and privilegedUids must be non-null");
        }
        try {
            // AIDL doesn't support Set yet. Convert Set to List/Array
            List<String> pkgList = List.copyOf(privilegedPackageNames);
            int[] uids = privilegedUids.stream().mapToInt(Number::intValue).toArray();
            sRegistry.notifyCarrierPrivilegesChanged(logicalSlotIndex, pkgList, uids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify listeners that the {@link CarrierService} for current user has changed.
     *
     * @param logicalSlotIndex the SIM slot the change occurred on
     * @param packageName the package name of the changed {@link CarrierService}
     * @param uid the UID of the changed {@link CarrierService}
     */
    public void notifyCarrierServiceChanged(int logicalSlotIndex, @Nullable String packageName,
            int uid) {
        try {
            sRegistry.notifyCarrierServiceChanged(logicalSlotIndex, packageName, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
