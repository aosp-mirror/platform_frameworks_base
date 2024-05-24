/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.telephony.ims;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.telephony.BinderCacheManager;
import android.telephony.ims.aidl.IImsRcsController;
import android.telephony.ims.aidl.SipDelegateConnectionAidlWrapper;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.stub.DelegateConnectionMessageCallback;
import android.telephony.ims.stub.DelegateConnectionStateCallback;
import android.telephony.ims.stub.SipDelegate;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.ITelephony;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Manages the creation and destruction of SipDelegates for the {@link ImsService} managing IMS
 * for the subscription ID that this SipDelegateManager has been created for.
 *
 * This allows multiple IMS applications to forward SIP messages to/from their application for the
 * purposes of providing a single IMS registration to the carrier's IMS network from potentially
 * many IMS stacks implementing a subset of the supported MMTEL/RCS features.
 * <p>
 * This API is only supported if the device supports the
 * {@link PackageManager#FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION} feature.
 * @hide
 */
@SystemApi
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION)
public class SipDelegateManager {

    /**
     * The SIP message has failed being sent or received for an unknown reason.
     * <p>
     * The caller should retry a message that failed with this response.
     */
    public static final int MESSAGE_FAILURE_REASON_UNKNOWN = 0;

    /**
     * The remote service associated with this connection has died and the message was not
     * properly sent/received.
     * <p>
     * This is considered a permanent error and the system will automatically begin the teardown and
     * destruction of the SipDelegate. No further messages should be sent on this transport.
     */
    public static final int MESSAGE_FAILURE_REASON_DELEGATE_DEAD = 1;

    /**
     * The message has not been sent/received because the delegate is in the process of closing and
     * has become unavailable. No further messages should be sent/received on this delegate.
     */
    public static final int MESSAGE_FAILURE_REASON_DELEGATE_CLOSED = 2;

    /**
     * The SIP message has an invalid start line and the message can not be sent or the start line
     * failed validation due to the request containing a restricted SIP request method.
     * {@link SipDelegateConnection}s can not send SIP requests for the methods: REGISTER, PUBLISH,
     * or OPTIONS.
     */
    public static final int MESSAGE_FAILURE_REASON_INVALID_START_LINE = 3;

    /**
     * One or more of the header fields in the header section of the outgoing SIP message is invalid
     * or contains a restricted header value and the SIP message can not be sent.
     * {@link SipDelegateConnection}s can not send SIP SUBSCRIBE requests for the "Event" header
     * value of "presence".
     */
    public static final int MESSAGE_FAILURE_REASON_INVALID_HEADER_FIELDS = 4;

    /**
     * The body content of the SIP message is invalid and the message can not be sent.
     */
    public static final int MESSAGE_FAILURE_REASON_INVALID_BODY_CONTENT = 5;

    /**
     * The feature tag associated with the outgoing message does not match any known feature tags
     * or it matches a denied tag and this message can not be sent.
     */
    public static final int MESSAGE_FAILURE_REASON_INVALID_FEATURE_TAG = 6;

    /**
     * The feature tag associated with the outgoing message is not enabled for the associated
     * SipDelegateConnection and can not be sent.
     */
    public static final int MESSAGE_FAILURE_REASON_TAG_NOT_ENABLED_FOR_DELEGATE = 7;

    /**
     * The link to the network has been lost and the outgoing message has failed to send.
     * <p>
     * This message should be retried when connectivity to the network is re-established. See
     * {@link android.net.ConnectivityManager.NetworkCallback} for how this can be determined.
     */
    public static final int MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE = 8;

    /**
     * The outgoing SIP message has not been sent due to the SipDelegate not being registered for
     * IMS at this time.
     * <p>
     * This is considered a temporary failure, the message should not be retried until an IMS
     * registration change callback is received via
     * {@link DelegateConnectionStateCallback#onFeatureTagStatusChanged}
     */
    public static final int MESSAGE_FAILURE_REASON_NOT_REGISTERED = 9;

    /**
     * The outgoing SIP message has not been sent because the {@link SipDelegateConfiguration}
     * version associated with the outgoing {@link SipMessage} is now stale and has failed
     * validation checks.
     * <p>
     * The @link SipMessage} should be recreated using the newest
     * {@link SipDelegateConfiguration} and sent again.
     */
    public static final int MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION = 10;

    /**
     * The outgoing SIP message has not been sent because the internal state of the associated
     * {@link SipDelegate} is changing and has temporarily brought the transport down.
     * <p>
     * This is considered a temporary error and the {@link SipDelegateConnection} should resend the
     * message once {@link DelegateRegistrationState#DEREGISTERING_REASON_FEATURE_TAGS_CHANGING} is
     * no longer reported.
     */
    public static final int MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION = 11;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "MESSAGE_FAILURE_REASON_", value = {
            MESSAGE_FAILURE_REASON_UNKNOWN,
            MESSAGE_FAILURE_REASON_DELEGATE_DEAD,
            MESSAGE_FAILURE_REASON_DELEGATE_CLOSED,
            MESSAGE_FAILURE_REASON_INVALID_START_LINE,
            MESSAGE_FAILURE_REASON_INVALID_HEADER_FIELDS,
            MESSAGE_FAILURE_REASON_INVALID_BODY_CONTENT,
            MESSAGE_FAILURE_REASON_INVALID_FEATURE_TAG,
            MESSAGE_FAILURE_REASON_TAG_NOT_ENABLED_FOR_DELEGATE,
            MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE,
            MESSAGE_FAILURE_REASON_NOT_REGISTERED,
            MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION,
            MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION
    })
    public @interface MessageFailureReason {}

    /**@hide*/
    public static final ArrayMap<Integer, String> MESSAGE_FAILURE_REASON_STRING_MAP =
            new ArrayMap<>(11);
    static {
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_UNKNOWN,
                "MESSAGE_FAILURE_REASON_UNKNOWN");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_DELEGATE_DEAD,
                "MESSAGE_FAILURE_REASON_DELEGATE_DEAD");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_DELEGATE_CLOSED,
                "MESSAGE_FAILURE_REASON_DELEGATE_CLOSED");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_INVALID_HEADER_FIELDS,
                "MESSAGE_FAILURE_REASON_INVALID_HEADER_FIELDS");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_INVALID_BODY_CONTENT,
                "MESSAGE_FAILURE_REASON_INVALID_BODY_CONTENT");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_INVALID_FEATURE_TAG,
                "MESSAGE_FAILURE_REASON_INVALID_FEATURE_TAG");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(
                MESSAGE_FAILURE_REASON_TAG_NOT_ENABLED_FOR_DELEGATE,
                "MESSAGE_FAILURE_REASON_TAG_NOT_ENABLED_FOR_DELEGATE");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE,
                "MESSAGE_FAILURE_REASON_NETWORK_NOT_AVAILABLE");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_NOT_REGISTERED,
                "MESSAGE_FAILURE_REASON_NOT_REGISTERED");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION,
                "MESSAGE_FAILURE_REASON_STALE_IMS_CONFIGURATION");
        MESSAGE_FAILURE_REASON_STRING_MAP.append(
                MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION,
                "MESSAGE_FAILURE_REASON_INTERNAL_DELEGATE_STATE_TRANSITION");
    }

    /**
     * Access to use this feature tag has been denied for an unknown reason.
     */
    public static final int DENIED_REASON_UNKNOWN = 0;

    /**
     * This feature tag is allowed to be used by this SipDelegateConnection, but it is in use by
     * another SipDelegateConnection and can not be associated with this delegate. The feature tag
     * will stay in this state until the feature tag is release by the other application.
     */
    public static final int DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE = 1;

    /**
     * Access to use this feature tag has been denied because this application does not have the
     * permissions required to access this feature tag.
     */
    public static final int DENIED_REASON_NOT_ALLOWED = 2;

    /**
     * Access to use this feature tag has been denied because single registration is not allowed by
     * the carrier at this time. The application should fall back to dual registration if
     * applicable.
     */
    public static final int DENIED_REASON_SINGLE_REGISTRATION_NOT_ALLOWED = 3;

    /**
     * This feature tag is not recognized as a valid feature tag by the SipDelegate and has been
     * denied.
     */
    public static final int DENIED_REASON_INVALID = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "DENIED_REASON_", value = {
            DENIED_REASON_UNKNOWN,
            DENIED_REASON_IN_USE_BY_ANOTHER_DELEGATE,
            DENIED_REASON_NOT_ALLOWED,
            DENIED_REASON_SINGLE_REGISTRATION_NOT_ALLOWED,
            DENIED_REASON_INVALID
    })
    public @interface DeniedReason {}

    /**
     * The SipDelegate has closed due to an unknown reason.
     */
    public static final int SIP_DELEGATE_DESTROY_REASON_UNKNOWN = 0;

    /**
     * The SipDelegate has closed because the IMS service has died unexpectedly.
     */
    public static final int SIP_DELEGATE_DESTROY_REASON_SERVICE_DEAD = 1;

    /**
     * The SipDelegate has closed because the IMS application has requested that the connection be
     * destroyed.
     */
    public static final int SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP = 2;

    /**
     * The SipDelegate has been closed due to the user disabling RCS.
     */
    public static final int SIP_DELEGATE_DESTROY_REASON_USER_DISABLED_RCS = 3;

    /**
     * The SipDelegate has been closed due to the subscription associated with this delegate being
     * torn down.
     */
    public static final int SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN = 4;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SIP_DELEGATE_DESTROY_REASON", value = {
            SIP_DELEGATE_DESTROY_REASON_UNKNOWN,
            SIP_DELEGATE_DESTROY_REASON_SERVICE_DEAD,
            SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP,
            SIP_DELEGATE_DESTROY_REASON_USER_DISABLED_RCS,
            SIP_DELEGATE_DESTROY_REASON_SUBSCRIPTION_TORN_DOWN
    })
    public @interface SipDelegateDestroyReason {}

    private final Context mContext;
    private final int mSubId;
    private final BinderCacheManager<IImsRcsController> mBinderCache;
    private final BinderCacheManager<ITelephony> mTelephonyBinderCache;

    /**
     * Only visible for testing. To instantiate an instance of this class, please use
     * {@link ImsManager#getSipDelegateManager(int)}.
     * @hide
     */
    @VisibleForTesting
    public SipDelegateManager(Context context, int subId,
            BinderCacheManager<IImsRcsController> binderCache,
            BinderCacheManager<ITelephony> telephonyBinderCache) {
        mContext = context;
        mSubId = subId;
        mBinderCache = binderCache;
        mTelephonyBinderCache = telephonyBinderCache;
    }

    /**
     * Determines if creating SIP delegates are supported for the subscription specified.
     * <p>
     * If SIP delegates are not supported on this device or the carrier associated with this
     * subscription, creating a SIP delegate will always fail, as this feature is not supported.
     * @return true if this device supports creating a SIP delegate and the carrier associated with
     * this subscription supports single registration, false if creating SIP delegates is not
     * supported.
     * @throws ImsException If the remote ImsService is not available for any reason or the
     * subscription associated with this instance is no longer active. See
     * {@link ImsException#getCode()} for more information.
     *
     * @see CarrierConfigManager.Ims#KEY_IMS_SINGLE_REGISTRATION_REQUIRED_BOOL
     * @see PackageManager#FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION
     */
    @RequiresPermission(anyOf = {Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            Manifest.permission.PERFORM_IMS_SINGLE_REGISTRATION})
    public boolean isSupported() throws ImsException {
        try {
            IImsRcsController controller = mBinderCache.getBinder();
            if (controller == null) {
                throw new ImsException("Telephony server is down",
                        ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
            }
            return controller.isSipDelegateSupported(mSubId);
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(),
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Request that the ImsService implementation create a SipDelegate, which will configure the
     * ImsService to forward SIP traffic that matches the filtering criteria set in supplied
     * {@link DelegateRequest} to the application that the supplied callbacks are registered for.
     * <p>
     * This API requires that the caller is running as part of a long-running process and will
     * always be available to handle incoming messages. One mechanism that can be used for this is
     * the {@link android.service.carrier.CarrierMessagingClientService}, which the framework keeps
     * a persistent binding to when the app is the default SMS application.
     * <p>
     * Note: the ability to create SipDelegates is only available applications running as the
     * primary user.
     * @param request The parameters that are associated with the SipDelegate creation request that
     *                will be used to create the SipDelegate connection.
     * @param executor The executor that will be used to call the callbacks associated with this
     *          SipDelegate.
     * @param dc The callback that will be used to notify the listener of the creation/destruction
     *           of the remote SipDelegate as well as changes to the state of the remote SipDelegate
     *           connection.
     * @param mc The callback that will be used to notify the listener of new incoming SIP messages
     *           as well as the status of messages that were sent by the associated
     *           SipDelegateConnection.
     * @throws ImsException Thrown if there was a problem communicating with the ImsService
     * associated with this SipDelegateManager. See {@link ImsException#getCode()}.
     */
    @RequiresPermission(Manifest.permission.PERFORM_IMS_SINGLE_REGISTRATION)
    public void createSipDelegate(@NonNull DelegateRequest request, @NonNull Executor executor,
            @NonNull DelegateConnectionStateCallback dc,
            @NonNull DelegateConnectionMessageCallback mc) throws ImsException {
        Objects.requireNonNull(request, "The DelegateRequest must not be null.");
        Objects.requireNonNull(executor, "The Executor must not be null.");
        Objects.requireNonNull(dc, "The DelegateConnectionStateCallback must not be null.");
        Objects.requireNonNull(mc, "The DelegateConnectionMessageCallback must not be null.");
        try {
            SipDelegateConnectionAidlWrapper wrapper =
                    new SipDelegateConnectionAidlWrapper(executor, dc, mc);
            IImsRcsController controller = mBinderCache.listenOnBinder(wrapper,
                    wrapper::binderDied);
            if (controller == null) {
                throw new ImsException("Telephony server is down",
                        ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
            }
            controller.createSipDelegate(mSubId, request, mContext.getOpPackageName(),
                    wrapper.getStateCallbackBinder(), wrapper.getMessageCallbackBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(),
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Destroy a previously created {@link SipDelegateConnection} that was created using
     * {@link #createSipDelegate}.
     * <p>
     * This will also clean up all related callbacks in the associated ImsService.
     * @param delegateConnection The SipDelegateConnection to destroy.
     * @param reason The reason for why this SipDelegateConnection was destroyed.
     */
    @RequiresPermission(Manifest.permission.PERFORM_IMS_SINGLE_REGISTRATION)
    public void destroySipDelegate(@NonNull SipDelegateConnection delegateConnection,
            @SipDelegateDestroyReason int reason) {
        Objects.requireNonNull(delegateConnection, "SipDelegateConnection can not be null.");
        if (delegateConnection instanceof SipDelegateConnectionAidlWrapper) {
            SipDelegateConnectionAidlWrapper w =
                    (SipDelegateConnectionAidlWrapper) delegateConnection;
            try {
                IImsRcsController c = mBinderCache.removeRunnable(w);
                c.destroySipDelegate(mSubId, w.getSipDelegateBinder(), reason);
            } catch (RemoteException e) {
                // Connection to telephony died, but this will signal destruction of SipDelegate
                // eventually anyway, so return normally.
                try {
                    w.getStateCallbackBinder().onDestroyed(
                            SipDelegateManager.SIP_DELEGATE_DESTROY_REASON_REQUESTED_BY_APP);
                } catch (RemoteException ignore) {
                    // Local to process.
                }
            }
        } else {
            throw new IllegalArgumentException("Unknown SipDelegateConnection implementation passed"
                    + " into this method");
        }
    }

    /**
     * Trigger a full network registration as required by receiving a SIP message containing a
     * permanent error from the network or never receiving a response to a SIP transaction request.
     *
     * @param connection The {@link SipDelegateConnection} that was being used when this error was
     *         received.
     * @param sipCode The SIP code response associated with the SIP message request that
     *         triggered this condition.
     * @param sipReason The SIP reason code associated with the SIP message request that triggered
     *         this condition. May be {@code null} if there was no reason String provided from the
     *         network.
     */
    @RequiresPermission(Manifest.permission.PERFORM_IMS_SINGLE_REGISTRATION)
    public void triggerFullNetworkRegistration(@NonNull SipDelegateConnection connection,
            @IntRange(from = 100, to = 699) int sipCode, @Nullable String sipReason) {
        Objects.requireNonNull(connection, "SipDelegateConnection can not be null.");
        if (connection instanceof SipDelegateConnectionAidlWrapper) {
            SipDelegateConnectionAidlWrapper w = (SipDelegateConnectionAidlWrapper) connection;
            try {
                IImsRcsController controller = mBinderCache.getBinder();
                controller.triggerNetworkRegistration(mSubId, w.getSipDelegateBinder(), sipCode,
                        sipReason);
            } catch (RemoteException e) {
                // Connection to telephony died, but this will signal destruction of SipDelegate
                // eventually anyway, so return.
            }
        } else {
            throw new IllegalArgumentException("Unknown SipDelegateConnection implementation passed"
                    + " into this method");
        }
    }

    /**
     * Register a new callback, which is used to notify the registrant of changes to
     * the state of the underlying  IMS service that is attached to telephony to
     * implement IMS functionality. If the manager is created for
     * the {@link android.telephony.SubscriptionManager#DEFAULT_SUBSCRIPTION_ID},
     * this throws an {@link ImsException}.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRECISE_PHONE_STATE READ_PRECISE_PHONE_STATE}
     * or that the calling app has carrier privileges
     * (see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}).
     *
     * @param executor the Executor that will be used to call the {@link ImsStateCallback}.
     * @param callback The callback instance being registered.
     * @throws ImsException in the case that the callback can not be registered.
     * See {@link ImsException#getCode} for more information on when this is called.
     */
    @RequiresPermission(anyOf = {Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
            Manifest.permission.PERFORM_IMS_SINGLE_REGISTRATION})
    public void registerImsStateCallback(@NonNull Executor executor,
            @NonNull ImsStateCallback callback) throws ImsException {
        Objects.requireNonNull(callback, "Must include a non-null ImsStateCallback.");
        Objects.requireNonNull(executor, "Must include a non-null Executor.");

        callback.init(executor);
        ITelephony telephony = mTelephonyBinderCache.listenOnBinder(callback, callback::binderDied);
        if (telephony == null) {
            throw new ImsException("Telephony server is down",
                    ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }

        try {
            telephony.registerImsStateCallback(
                    mSubId, ImsFeature.FEATURE_RCS,
                    callback.getCallbackBinder(), mContext.getOpPackageName());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException | IllegalStateException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Unregisters a previously registered callback.
     *
     * @param callback The callback instance to be unregistered.
     */
    public void unregisterImsStateCallback(@NonNull ImsStateCallback callback) {
        Objects.requireNonNull(callback, "Must include a non-null ImsStateCallback.");

        ITelephony telephony = mTelephonyBinderCache.removeRunnable(callback);

        try {
            if (telephony != null) {
                telephony.unregisterImsStateCallback(callback.getCallbackBinder());
            }
        } catch (RemoteException ignore) {
            // ignore it
        }
    }

    /**
     * Register a new callback, which is used to notify the registrant of changes
     * to the state of the Sip Sessions managed remotely by the IMS stack.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
     * @param executor the Executor that will be used to call the {@link SipDialogStateCallback}.
     * @param callback The callback instance being registered.
     * @throws ImsException in the case that the callback can not be registered.
     * See {@link ImsException#getCode} for more information on when this is called.
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION}.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void registerSipDialogStateCallback(@NonNull Executor executor,
            @NonNull SipDialogStateCallback callback) throws ImsException {
        Objects.requireNonNull(callback, "Must include a non-null SipDialogStateCallback.");
        Objects.requireNonNull(executor, "Must include a non-null Executor.");

        callback.attachExecutor(executor);
        try {
            IImsRcsController controller = mBinderCache.listenOnBinder(
                    callback, callback::binderDied);
            if (controller == null) {
                throw new ImsException("Telephony server is down",
                        ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
            }
            controller.registerSipDialogStateCallback(mSubId, callback.getCallbackBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    /**
     * Unregisters a previously registered callback.
     *
     *  <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE}
     *
     * @param callback The callback instance to be unregistered.
     *
     * @throws UnsupportedOperationException If the device does not have
     *          {@link PackageManager#FEATURE_TELEPHONY_IMS_SINGLE_REGISTRATION}.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public void unregisterSipDialogStateCallback(@NonNull SipDialogStateCallback callback)
            throws ImsException {
        Objects.requireNonNull(callback, "Must include a non-null SipDialogStateCallback.");

        IImsRcsController controller = mBinderCache.removeRunnable(callback);
        try {
            if (controller == null) {
                throw new ImsException("Telephony server is down",
                        ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
            }
            controller.unregisterSipDialogStateCallback(mSubId, callback.getCallbackBinder());
        } catch (ServiceSpecificException e) {
            throw new ImsException(e.getMessage(), e.errorCode);
        } catch (RemoteException e) {
            throw new ImsException(e.getMessage(), ImsException.CODE_ERROR_SERVICE_UNAVAILABLE);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }
}
