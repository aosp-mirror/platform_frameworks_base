/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.telephony.euicc;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.service.euicc.EuiccProfileInfo;
import android.telephony.TelephonyFrameworkInitializer;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.euicc.IAuthenticateServerCallback;
import com.android.internal.telephony.euicc.ICancelSessionCallback;
import com.android.internal.telephony.euicc.IDeleteProfileCallback;
import com.android.internal.telephony.euicc.IDisableProfileCallback;
import com.android.internal.telephony.euicc.IEuiccCardController;
import com.android.internal.telephony.euicc.IGetAllProfilesCallback;
import com.android.internal.telephony.euicc.IGetDefaultSmdpAddressCallback;
import com.android.internal.telephony.euicc.IGetEuiccChallengeCallback;
import com.android.internal.telephony.euicc.IGetEuiccInfo1Callback;
import com.android.internal.telephony.euicc.IGetEuiccInfo2Callback;
import com.android.internal.telephony.euicc.IGetProfileCallback;
import com.android.internal.telephony.euicc.IGetRulesAuthTableCallback;
import com.android.internal.telephony.euicc.IGetSmdsAddressCallback;
import com.android.internal.telephony.euicc.IListNotificationsCallback;
import com.android.internal.telephony.euicc.ILoadBoundProfilePackageCallback;
import com.android.internal.telephony.euicc.IPrepareDownloadCallback;
import com.android.internal.telephony.euicc.IRemoveNotificationFromListCallback;
import com.android.internal.telephony.euicc.IResetMemoryCallback;
import com.android.internal.telephony.euicc.IRetrieveNotificationCallback;
import com.android.internal.telephony.euicc.IRetrieveNotificationListCallback;
import com.android.internal.telephony.euicc.ISetDefaultSmdpAddressCallback;
import com.android.internal.telephony.euicc.ISetNicknameCallback;
import com.android.internal.telephony.euicc.ISwitchToProfileCallback;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * EuiccCardManager is the application interface to an eSIM card.
 * @hide
 */
@SystemApi
@RequiresFeature(PackageManager.FEATURE_TELEPHONY_EUICC)
public class EuiccCardManager {
    private static final String TAG = "EuiccCardManager";

    /**
     * Reason for canceling a profile download session
     *
     * @removed mistakenly exposed previously
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CANCEL_REASON_"}, value = {
            CANCEL_REASON_END_USER_REJECTED,
            CANCEL_REASON_POSTPONED,
            CANCEL_REASON_TIMEOUT,
            CANCEL_REASON_PPR_NOT_ALLOWED
    })
    public @interface CancelReason {
    }

    /**
     * The end user has rejected the download. The profile will be put into the error state and
     * cannot be downloaded again without the operator's change.
     */
    public static final int CANCEL_REASON_END_USER_REJECTED = 0;

    /** The download has been postponed and can be restarted later. */
    public static final int CANCEL_REASON_POSTPONED = 1;

    /** The download has been timed out and can be restarted later. */
    public static final int CANCEL_REASON_TIMEOUT = 2;

    /**
     * The profile to be downloaded cannot be installed due to its policy rule is not allowed by
     * the RAT (Rules Authorisation Table) on the eUICC or by other installed profiles. The
     * download can be restarted later.
     */
    public static final int CANCEL_REASON_PPR_NOT_ALLOWED = 3;

    /**
     * Options for resetting eUICC memory
     *
     * @removed mistakenly exposed previously
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = {"RESET_OPTION_"}, value = {
            RESET_OPTION_DELETE_OPERATIONAL_PROFILES,
            RESET_OPTION_DELETE_FIELD_LOADED_TEST_PROFILES,
            RESET_OPTION_RESET_DEFAULT_SMDP_ADDRESS
    })
    public @interface ResetOption {
    }

    /** Deletes all operational profiles. */
    public static final int RESET_OPTION_DELETE_OPERATIONAL_PROFILES = 1;

    /** Deletes all field-loaded testing profiles. */
    public static final int RESET_OPTION_DELETE_FIELD_LOADED_TEST_PROFILES = 1 << 1;

    /** Resets the default SM-DP+ address. */
    public static final int RESET_OPTION_RESET_DEFAULT_SMDP_ADDRESS = 1 << 2;

    /** Result code when the requested profile is not found.
     * {@link #RESULT_PROFILE_NOT_FOUND} is not used in Android U+,
     * use {@link #RESULT_PROFILE_DOES_NOT_EXIST} instead.
     **/
    public static final int RESULT_PROFILE_NOT_FOUND = 1;

    /** Result code of execution with no error. */
    public static final int RESULT_OK = 0;

    /** Result code of an unknown error. */
    public static final int RESULT_UNKNOWN_ERROR = -1;

    /** Result code when the eUICC card with the given card Id is not found. */
    public static final int RESULT_EUICC_NOT_FOUND = -2;

    /** Result code indicating the caller is not the active LPA. */
    public static final int RESULT_CALLER_NOT_ALLOWED = -3;

    /** Result code when the requested profile does not exist */
    public static final int RESULT_PROFILE_DOES_NOT_EXIST = -4;

    /**
     * Callback to receive the result of an eUICC card API.
     *
     * @param <T> Type of the result.
     */
    public interface ResultCallback<T> {
        /**
         * This method will be called when an eUICC card API call is completed.
         *
         * @param resultCode This can be {@link #RESULT_OK} or other positive values returned by the
         *                   eUICC.
         * @param result     The result object. It can be null if the {@code resultCode} is not
         *                   {@link #RESULT_OK}.
         */
        void onComplete(int resultCode, T result);
    }

    private final Context mContext;

    /** @hide */
    public EuiccCardManager(Context context) {
        mContext = context;
    }

    private IEuiccCardController getIEuiccCardController() {
        return IEuiccCardController.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getEuiccCardControllerServiceRegisterer()
                        .get());
    }

    /**
     * Requests all the profiles on eUicc.
     *
     * @param cardId   The Id of the eUICC.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code and all the profiles.
     */
    public void requestAllProfiles(String cardId, @CallbackExecutor Executor executor,
            ResultCallback<EuiccProfileInfo[]> callback) {
        try {
            getIEuiccCardController().getAllProfiles(mContext.getOpPackageName(), cardId,
                    new IGetAllProfilesCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccProfileInfo[] profiles) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, profiles));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getAllProfiles", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the profile of the given iccid.
     *
     * @param cardId   The Id of the eUICC.
     * @param iccid    The iccid of the profile.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code and profile.
     */
    public void requestProfile(String cardId, String iccid, @CallbackExecutor Executor executor,
            ResultCallback<EuiccProfileInfo> callback) {
        try {
            getIEuiccCardController().getProfile(mContext.getOpPackageName(), cardId, iccid,
                    new IGetProfileCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccProfileInfo profile) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, profile));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getProfile", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the enabled profile for a given port on an eUicc. Callback with result code
     * {@link RESULT_PROFILE_DOES_NOT_EXIST} and {@code NULL} EuiccProfile if there is no enabled
     * profile on the target port.
     *
     * @param cardId    The Id of the eUICC.
     * @param portIndex The portIndex to use. The port may be active or inactive. As long as the
     *                  ICCID is known, an APDU will be sent through to read the enabled profile.
     * @param executor  The executor through which the callback should be invoked.
     * @param callback  The callback to get the result code and the profile.
     */
    public void requestEnabledProfileForPort(@NonNull String cardId, int portIndex,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ResultCallback<EuiccProfileInfo> callback) {
        try {
            getIEuiccCardController().getEnabledProfile(mContext.getOpPackageName(), cardId,
                    portIndex,
                    new IGetProfileCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccProfileInfo profile) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, profile));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling requestEnabledProfileForPort", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Disables the profile of the given iccid.
     *
     * @param cardId   The Id of the eUICC.
     * @param iccid    The iccid of the profile.
     * @param refresh  Whether sending the REFRESH command to modem.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code.
     */
    public void disableProfile(String cardId, String iccid, boolean refresh,
            @CallbackExecutor Executor executor, ResultCallback<Void> callback) {
        try {
            getIEuiccCardController().disableProfile(mContext.getOpPackageName(), cardId, iccid,
                    refresh, new IDisableProfileCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, null));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling disableProfile", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switches from the current profile to another profile. The current profile will be disabled
     * and the specified profile will be enabled.
     *
     * @param cardId   The Id of the eUICC.
     * @param iccid    The iccid of the profile to switch to.
     * @param refresh  Whether sending the REFRESH command to modem.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code and the EuiccProfileInfo enabled.
     * @deprecated instead use {@link #switchToProfile(String, String, int, boolean, Executor,
     * ResultCallback)}
     */
    @Deprecated
    public void switchToProfile(String cardId, String iccid, boolean refresh,
            @CallbackExecutor Executor executor, ResultCallback<EuiccProfileInfo> callback) {
        try {
            getIEuiccCardController().switchToProfile(mContext.getOpPackageName(), cardId, iccid,
                    TelephonyManager.DEFAULT_PORT_INDEX, refresh,
                    new ISwitchToProfileCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccProfileInfo profile) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, profile));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling switchToProfile", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switches from the current profile to another profile. The current profile will be disabled
     * and the specified profile will be enabled. Here portIndex specifies on which port the
     * profile is to be enabled.
     *
     * @param cardId    The Id of the eUICC.
     * @param iccid     The iccid of the profile to switch to.
     * @param portIndex The Port index is the unique index referring to a port.
     * @param refresh   Whether sending the REFRESH command to modem.
     * @param executor  The executor through which the callback should be invoked.
     * @param callback  The callback to get the result code and the EuiccProfileInfo enabled.
     */
    public void switchToProfile(@Nullable String cardId, @Nullable String iccid, int portIndex,
            boolean refresh, @NonNull @CallbackExecutor Executor executor,
            @NonNull ResultCallback<EuiccProfileInfo> callback) {
        try {
            getIEuiccCardController().switchToProfile(mContext.getOpPackageName(), cardId, iccid,
                    portIndex, refresh, new ISwitchToProfileCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccProfileInfo profile) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, profile));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling switchToProfile", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the nickname of the profile of the given iccid.
     *
     * @param cardId The Id of the eUICC.
     * @param iccid The iccid of the profile.
     * @param nickname The nickname of the profile.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code.
     */
    public void setNickname(String cardId, String iccid, String nickname,
            @CallbackExecutor Executor executor, ResultCallback<Void> callback) {
        try {
            getIEuiccCardController().setNickname(mContext.getOpPackageName(), cardId, iccid,
                    nickname, new ISetNicknameCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, null));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setNickname", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Deletes the profile of the given iccid from eUICC.
     *
     * @param cardId The Id of the eUICC.
     * @param iccid The iccid of the profile.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code.
     */
    public void deleteProfile(String cardId, String iccid, @CallbackExecutor Executor executor,
            ResultCallback<Void> callback) {
        try {
            getIEuiccCardController().deleteProfile(mContext.getOpPackageName(), cardId, iccid,
                    new IDeleteProfileCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, null));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling deleteProfile", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the eUICC memory.
     *
     * @param cardId The Id of the eUICC.
     * @param options Bits of the options of resetting which parts of the eUICC memory. See
     *     EuiccCard for details.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code.
     */
    public void resetMemory(String cardId, @ResetOption int options,
            @CallbackExecutor Executor executor, ResultCallback<Void> callback) {
        try {
            getIEuiccCardController().resetMemory(mContext.getOpPackageName(), cardId, options,
                    new IResetMemoryCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, null));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling resetMemory", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the default SM-DP+ address from eUICC.
     *
     * @param cardId The Id of the eUICC.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code and the default SM-DP+ address.
     */
    public void requestDefaultSmdpAddress(String cardId, @CallbackExecutor Executor executor,
            ResultCallback<String> callback) {
        try {
            getIEuiccCardController().getDefaultSmdpAddress(mContext.getOpPackageName(), cardId,
                    new IGetDefaultSmdpAddressCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, String address) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, address));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getDefaultSmdpAddress", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the SM-DS address from eUICC.
     *
     * @param cardId The Id of the eUICC.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code and the SM-DS address.
     */
    public void requestSmdsAddress(String cardId, @CallbackExecutor Executor executor,
            ResultCallback<String> callback) {
        try {
            getIEuiccCardController().getSmdsAddress(mContext.getOpPackageName(), cardId,
                    new IGetSmdsAddressCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, String address) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, address));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getSmdsAddress", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the default SM-DP+ address of eUICC.
     *
     * @param cardId The Id of the eUICC.
     * @param defaultSmdpAddress The default SM-DP+ address to set.
     * @param executor The executor through which the callback should be invoked.
     * @param callback The callback to get the result code.
     */
    public void setDefaultSmdpAddress(String cardId, String defaultSmdpAddress,
            @CallbackExecutor Executor executor, ResultCallback<Void> callback) {
        try {
            getIEuiccCardController().setDefaultSmdpAddress(mContext.getOpPackageName(), cardId,
                    defaultSmdpAddress,
                    new ISetDefaultSmdpAddressCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, null));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling setDefaultSmdpAddress", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests Rules Authorisation Table.
     *
     * @param cardId The Id of the eUICC.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and the rule authorisation table.
     */
    public void requestRulesAuthTable(String cardId, @CallbackExecutor Executor executor,
            ResultCallback<EuiccRulesAuthTable> callback) {
        try {
            getIEuiccCardController().getRulesAuthTable(mContext.getOpPackageName(), cardId,
                    new IGetRulesAuthTableCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccRulesAuthTable rat) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, rat));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getRulesAuthTable", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the eUICC challenge for new profile downloading.
     *
     * @param cardId The Id of the eUICC.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and the challenge.
     */
    public void requestEuiccChallenge(String cardId, @CallbackExecutor Executor executor,
            ResultCallback<byte[]> callback) {
        try {
            getIEuiccCardController().getEuiccChallenge(mContext.getOpPackageName(), cardId,
                    new IGetEuiccChallengeCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, byte[] challenge) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, challenge));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getEuiccChallenge", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests the eUICC info1 defined in GSMA RSP v2.0+ for new profile downloading.
     *
     * @param cardId The Id of the eUICC.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and the info1.
     */
    public void requestEuiccInfo1(String cardId, @CallbackExecutor Executor executor,
            ResultCallback<byte[]> callback) {
        try {
            getIEuiccCardController().getEuiccInfo1(mContext.getOpPackageName(), cardId,
                    new IGetEuiccInfo1Callback.Stub() {
                        @Override
                        public void onComplete(int resultCode, byte[] info) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, info));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getEuiccInfo1", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the eUICC info2 defined in GSMA RSP v2.0+ for new profile downloading.
     *
     * @param cardId The Id of the eUICC.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and the info2.
     */
    public void requestEuiccInfo2(String cardId, @CallbackExecutor Executor executor,
            ResultCallback<byte[]> callback) {
        try {
            getIEuiccCardController().getEuiccInfo2(mContext.getOpPackageName(), cardId,
                    new IGetEuiccInfo2Callback.Stub() {
                        @Override
                        public void onComplete(int resultCode, byte[] info) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, info));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling getEuiccInfo2", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Authenticates the SM-DP+ server by the eUICC.
     *
     * @param cardId The Id of the eUICC.
     * @param matchingId the activation code token defined in GSMA RSP v2.0+ or empty when it is not
     *     required.
     * @param serverSigned1 ASN.1 data in byte array signed and returned by the SM-DP+ server.
     * @param serverSignature1 ASN.1 data in byte array indicating a SM-DP+ signature which is
     *     returned by SM-DP+ server.
     * @param euiccCiPkIdToBeUsed ASN.1 data in byte array indicating CI Public Key Identifier to be
     *     used by the eUICC for signature which is returned by SM-DP+ server. This is defined in
     *     GSMA RSP v2.0+.
     * @param serverCertificate ASN.1 data in byte array indicating SM-DP+ Certificate returned by
     *     SM-DP+ server.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and a byte array which represents a
     *     {@code AuthenticateServerResponse} defined in GSMA RSP v2.0+.
     */
    public void authenticateServer(String cardId, String matchingId, byte[] serverSigned1,
            byte[] serverSignature1, byte[] euiccCiPkIdToBeUsed, byte[] serverCertificate,
            @CallbackExecutor Executor executor, ResultCallback<byte[]> callback) {
        try {
            getIEuiccCardController().authenticateServer(
                    mContext.getOpPackageName(),
                    cardId,
                    matchingId,
                    serverSigned1,
                    serverSignature1,
                    euiccCiPkIdToBeUsed,
                    serverCertificate,
                    new IAuthenticateServerCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, byte[] response) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, response));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling authenticateServer", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Prepares the profile download request sent to SM-DP+.
     *
     * @param cardId The Id of the eUICC.
     * @param hashCc the hash of confirmation code. It can be null if there is no confirmation code
     *     required.
     * @param smdpSigned2 ASN.1 data in byte array indicating the data to be signed by the SM-DP+
     *     returned by SM-DP+ server.
     * @param smdpSignature2 ASN.1 data in byte array indicating the SM-DP+ signature returned by
     *     SM-DP+ server.
     * @param smdpCertificate ASN.1 data in byte array indicating the SM-DP+ Certificate returned
     *     by SM-DP+ server.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and a byte array which represents a
     *     {@code PrepareDownloadResponse} defined in GSMA RSP v2.0+
     */
    public void prepareDownload(String cardId, @Nullable byte[] hashCc, byte[] smdpSigned2,
            byte[] smdpSignature2, byte[] smdpCertificate, @CallbackExecutor Executor executor,
            ResultCallback<byte[]> callback) {
        try {
            getIEuiccCardController().prepareDownload(
                    mContext.getOpPackageName(),
                    cardId,
                    hashCc,
                    smdpSigned2,
                    smdpSignature2,
                    smdpCertificate,
                    new IPrepareDownloadCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, byte[] response) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, response));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling prepareDownload", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Loads a downloaded bound profile package onto the eUICC.
     *
     * @param cardId The Id of the eUICC.
     * @param boundProfilePackage the Bound Profile Package data returned by SM-DP+ server.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and a byte array which represents a
     *     {@code LoadBoundProfilePackageResponse} defined in GSMA RSP v2.0+.
     */
    public void loadBoundProfilePackage(String cardId, byte[] boundProfilePackage,
            @CallbackExecutor Executor executor, ResultCallback<byte[]> callback) {
        try {
            getIEuiccCardController().loadBoundProfilePackage(
                    mContext.getOpPackageName(),
                    cardId,
                    boundProfilePackage,
                    new ILoadBoundProfilePackageCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, byte[] response) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, response));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling loadBoundProfilePackage", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancels the current profile download session.
     *
     * @param cardId The Id of the eUICC.
     * @param transactionId the transaction ID returned by SM-DP+ server.
     * @param reason the cancel reason.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and an byte[] which represents a
     *     {@code CancelSessionResponse} defined in GSMA RSP v2.0+.
     */
    public void cancelSession(String cardId, byte[] transactionId, @CancelReason int reason,
            @CallbackExecutor Executor executor, ResultCallback<byte[]> callback) {
        try {
            getIEuiccCardController().cancelSession(
                    mContext.getOpPackageName(),
                    cardId,
                    transactionId,
                    reason,
                    new ICancelSessionCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, byte[] response) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, response));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling cancelSession", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Lists all notifications of the given {@code events}.
     *
     * @param cardId The Id of the eUICC.
     * @param events bits of the event types ({@link EuiccNotification.Event}) to list.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and the list of notifications.
     */
    public void listNotifications(String cardId, @EuiccNotification.Event int events,
            @CallbackExecutor Executor executor, ResultCallback<EuiccNotification[]> callback) {
        try {
            getIEuiccCardController().listNotifications(mContext.getOpPackageName(), cardId, events,
                    new IListNotificationsCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccNotification[] notifications) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(
                                        resultCode, notifications));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling listNotifications", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves contents of all notification of the given {@code events}.
     *
     * @param cardId The Id of the eUICC.
     * @param events bits of the event types ({@link EuiccNotification.Event}) to list.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and the list of notifications.
     */
    public void retrieveNotificationList(String cardId, @EuiccNotification.Event int events,
            @CallbackExecutor Executor executor, ResultCallback<EuiccNotification[]> callback) {
        try {
            getIEuiccCardController().retrieveNotificationList(mContext.getOpPackageName(), cardId,
                    events, new IRetrieveNotificationListCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccNotification[] notifications) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(
                                        resultCode, notifications));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling retrieveNotificationList", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves the content of a notification of the given {@code seqNumber}.
     *
     * @param cardId The Id of the eUICC.
     * @param seqNumber the sequence number of the notification.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code and the notification.
     */
    public void retrieveNotification(String cardId, int seqNumber,
            @CallbackExecutor Executor executor, ResultCallback<EuiccNotification> callback) {
        try {
            getIEuiccCardController().retrieveNotification(mContext.getOpPackageName(), cardId,
                    seqNumber, new IRetrieveNotificationCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode, EuiccNotification notification) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(
                                        resultCode, notification));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling retrieveNotification", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a notification from eUICC.
     *
     * @param cardId The Id of the eUICC.
     * @param seqNumber the sequence number of the notification.
     * @param executor The executor through which the callback should be invoked.
     * @param callback the callback to get the result code.
     */
    public void removeNotificationFromList(String cardId, int seqNumber,
            @CallbackExecutor Executor executor, ResultCallback<Void> callback) {
        try {
            getIEuiccCardController().removeNotificationFromList(
                    mContext.getOpPackageName(),
                    cardId,
                    seqNumber,
                    new IRemoveNotificationFromListCallback.Stub() {
                        @Override
                        public void onComplete(int resultCode) {
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.onComplete(resultCode, null));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling removeNotificationFromList", e);
            throw e.rethrowFromSystemServer();
        }
    }
}
