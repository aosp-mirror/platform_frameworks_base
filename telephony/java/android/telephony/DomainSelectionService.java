/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.telephony.Annotation.DisconnectCauses;
import android.telephony.Annotation.PreciseDisconnectCauses;
import android.telephony.ims.ImsReasonInfo;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.IDomainSelectionServiceController;
import com.android.internal.telephony.IDomainSelector;
import com.android.internal.telephony.ITransportSelectorCallback;
import com.android.internal.telephony.ITransportSelectorResultCallback;
import com.android.internal.telephony.IWwanSelectorCallback;
import com.android.internal.telephony.IWwanSelectorResultCallback;
import com.android.internal.telephony.util.TelephonyUtils;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Main domain selection implementation for various telephony features.
 *
 * The telephony framework will bind to the {@link DomainSelectionService}.
 *
 * @hide
 */
public class DomainSelectionService extends Service {

    private static final String LOG_TAG = "DomainSelectionService";

    /**
     * The intent that must be defined as an intent-filter in the AndroidManifest of the
     * {@link DomainSelectionService}.
     *
     * @hide
     */
    public static final String SERVICE_INTERFACE = "android.telephony.DomainSelectionService";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SELECTOR_TYPE_",
            value = {
                    SELECTOR_TYPE_CALLING,
                    SELECTOR_TYPE_SMS,
                    SELECTOR_TYPE_UT})
    public @interface SelectorType {}

    /** Indicates the domain selector type for calling. */
    public static final int SELECTOR_TYPE_CALLING = 1;
    /** Indicates the domain selector type for sms. */
    public static final int SELECTOR_TYPE_SMS = 2;
    /** Indicates the domain selector type for supplementary services. */
    public static final int SELECTOR_TYPE_UT = 3;

    /** Indicates that the modem can scan for emergency service as per modem’s implementation. */
    public static final int SCAN_TYPE_NO_PREFERENCE = 0;

    /** Indicates that the modem will scan for emergency service in limited service mode. */
    public static final int SCAN_TYPE_LIMITED_SERVICE = 1;

    /** Indicates that the modem will scan for emergency service in full service mode. */
    public static final int SCAN_TYPE_FULL_SERVICE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SCAN_TYPE_",
            value = {
                    SCAN_TYPE_NO_PREFERENCE,
                    SCAN_TYPE_LIMITED_SERVICE,
                    SCAN_TYPE_FULL_SERVICE})
    public @interface EmergencyScanType {}

    /**
     * Contains attributes required to determine the domain for a telephony service.
     */
    public static final class SelectionAttributes implements Parcelable {

        private static final String TAG = "SelectionAttributes";

        private int mSlotId;
        private int mSubId;
        private @Nullable String mCallId;
        private @Nullable String mNumber;
        private @SelectorType int mSelectorType;
        private boolean mIsVideoCall;
        private boolean mIsEmergency;
        private boolean mIsExitedFromAirplaneMode;
        //private @Nullable UtAttributes mUtAttributes;
        private @Nullable ImsReasonInfo mImsReasonInfo;
        private @PreciseDisconnectCauses int mCause;
        private @Nullable EmergencyRegResult mEmergencyRegResult;

        /**
         * @param slotId The slot identifier.
         * @param subId The subscription identifier.
         * @param callId The call identifier.
         * @param number The dialed number.
         * @param selectorType Indicates the requested domain selector type.
         * @param video Indicates it's a video call.
         * @param emergency Indicates it's emergency service.
         * @param exited {@code true} if the request caused the device to move out of airplane mode.
         * @param imsReasonInfo The reason why the last PS attempt failed.
         * @param cause The reason why the last CS attempt failed.
         * @param regResult The current registration result for emergency services.
         */
        private SelectionAttributes(int slotId, int subId, @Nullable String callId,
                @Nullable String number, @SelectorType int selectorType,
                boolean video, boolean emergency, boolean exited,
                /*UtAttributes attr,*/
                @Nullable ImsReasonInfo imsReasonInfo, @PreciseDisconnectCauses int cause,
                @Nullable EmergencyRegResult regResult) {
            mSlotId = slotId;
            mSubId = subId;
            mCallId = callId;
            mNumber = number;
            mSelectorType = selectorType;
            mIsVideoCall = video;
            mIsEmergency = emergency;
            mIsExitedFromAirplaneMode = exited;
            //mUtAttributes = attr;
            mImsReasonInfo = imsReasonInfo;
            mCause = cause;
            mEmergencyRegResult = regResult;
        }

        /**
         * Copy constructor.
         *
         * @param s Source selection attributes.
         * @hide
         */
        public SelectionAttributes(@NonNull SelectionAttributes s) {
            mSlotId = s.mSlotId;
            mSubId = s.mSubId;
            mCallId = s.mCallId;
            mNumber = s.mNumber;
            mSelectorType = s.mSelectorType;
            mIsEmergency = s.mIsEmergency;
            mIsExitedFromAirplaneMode = s.mIsExitedFromAirplaneMode;
            //mUtAttributes = s.mUtAttributes;
            mImsReasonInfo = s.mImsReasonInfo;
            mCause = s.mCause;
            mEmergencyRegResult = s.mEmergencyRegResult;
        }

        /**
         * Constructs a SelectionAttributes object from the given parcel.
         */
        private SelectionAttributes(@NonNull Parcel in) {
            readFromParcel(in);
        }

        /**
         * @return The slot identifier.
         */
        public int getSlotId() {
            return mSlotId;
        }

        /**
         * @return The subscription identifier.
         */
        public int getSubId() {
            return mSubId;
        }

        /**
         * @return The call identifier.
         */
        public @Nullable String getCallId() {
            return mCallId;
        }

        /**
         * @return The dialed number.
         */
        public @Nullable String getNumber() {
            return mNumber;
        }

        /**
         * @return The domain selector type.
         */
        public @SelectorType int getSelectorType() {
            return mSelectorType;
        }

        /**
         * @return {@code true} if the request is for a video call.
         */
        public boolean isVideoCall() {
            return mIsVideoCall;
        }

        /**
         * @return {@code true} if the request is for emergency services.
         */
        public boolean isEmergency() {
            return mIsEmergency;
        }

        /**
         * @return {@code true} if the request caused the device to move out of airplane mode.
         */
        public boolean isExitedFromAirplaneMode() {
            return mIsExitedFromAirplaneMode;
        }

        /*
        public @Nullable UtAttributes getUtAttributes();
            return mUtAttributes;
        }
        */

        /**
         * @return The PS disconnect cause if trying over PS resulted in a failure and
         *         reselection is required.
         */
        public @Nullable ImsReasonInfo getPsDisconnectCause() {
            return mImsReasonInfo;
        }

        /**
         * @return The CS disconnect cause if trying over CS resulted in a failure and
         *         reselection is required.
         */
        public @PreciseDisconnectCauses int getCsDisconnectCause() {
            return mCause;
        }

        /**
         * @return The current registration state of cellular network.
         */
        public @Nullable EmergencyRegResult getEmergencyRegResult() {
            return mEmergencyRegResult;
        }

        @Override
        public @NonNull String toString() {
            return "{ slotId=" + mSlotId
                    + ", subId=" + mSubId
                    + ", callId=" + mCallId
                    + ", number=" + (Build.IS_DEBUGGABLE ? mNumber : "***")
                    + ", type=" + mSelectorType
                    + ", videoCall=" + mIsVideoCall
                    + ", emergency=" + mIsEmergency
                    + ", airplaneMode=" + mIsExitedFromAirplaneMode
                    + ", reasonInfo=" + mImsReasonInfo
                    + ", cause=" + mCause
                    + ", regResult=" + mEmergencyRegResult
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SelectionAttributes that = (SelectionAttributes) o;
            return mSlotId == that.mSlotId && mSubId == that.mSubId
                    && TextUtils.equals(mCallId, that.mCallId)
                    && TextUtils.equals(mNumber, that.mNumber)
                    && mSelectorType == that.mSelectorType && mIsVideoCall == that.mIsVideoCall
                    && mIsEmergency == that.mIsEmergency
                    && mIsExitedFromAirplaneMode == that.mIsExitedFromAirplaneMode
                    //&& equalsHandlesNulls(mUtAttributes, that.mUtAttributes)
                    && equalsHandlesNulls(mImsReasonInfo, that.mImsReasonInfo)
                    && mCause == that.mCause
                    && equalsHandlesNulls(mEmergencyRegResult, that.mEmergencyRegResult);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mCallId, mNumber, mImsReasonInfo,
                    mIsVideoCall, mIsEmergency, mIsExitedFromAirplaneMode, mEmergencyRegResult,
                    mSlotId, mSubId, mSelectorType, mCause);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel out, int flags) {
            out.writeInt(mSlotId);
            out.writeInt(mSubId);
            out.writeString8(mCallId);
            out.writeString8(mNumber);
            out.writeInt(mSelectorType);
            out.writeBoolean(mIsVideoCall);
            out.writeBoolean(mIsEmergency);
            out.writeBoolean(mIsExitedFromAirplaneMode);
            //out.writeParcelable(mUtAttributes, 0);
            out.writeParcelable(mImsReasonInfo, 0);
            out.writeInt(mCause);
            out.writeParcelable(mEmergencyRegResult, 0);
        }

        private void readFromParcel(@NonNull Parcel in) {
            mSlotId = in.readInt();
            mSubId = in.readInt();
            mCallId = in.readString8();
            mNumber = in.readString8();
            mSelectorType = in.readInt();
            mIsVideoCall = in.readBoolean();
            mIsEmergency = in.readBoolean();
            mIsExitedFromAirplaneMode = in.readBoolean();
            //mUtAttributes = s.mUtAttributes;
            mImsReasonInfo = in.readParcelable(ImsReasonInfo.class.getClassLoader(),
                    android.telephony.ims.ImsReasonInfo.class);
            mCause = in.readInt();
            mEmergencyRegResult = in.readParcelable(EmergencyRegResult.class.getClassLoader(),
                    EmergencyRegResult.class);
        }

        public static final @NonNull Creator<SelectionAttributes> CREATOR =
                new Creator<SelectionAttributes>() {
            @Override
            public SelectionAttributes createFromParcel(@NonNull Parcel in) {
                return new SelectionAttributes(in);
            }

            @Override
            public SelectionAttributes[] newArray(int size) {
                return new SelectionAttributes[size];
            }
        };

        private static boolean equalsHandlesNulls(Object a, Object b) {
            return (a == null) ? (b == null) : a.equals(b);
        }

        /**
         * Builder class creating a new instance.
         */
        public static final class Builder {
            private final int mSlotId;
            private final int mSubId;
            private @Nullable String mCallId;
            private @Nullable String mNumber;
            private final @SelectorType int mSelectorType;
            private boolean mIsVideoCall;
            private boolean mIsEmergency;
            private boolean mIsExitedFromAirplaneMode;
            //private @Nullable UtAttributes mUtAttributes;
            private @Nullable ImsReasonInfo mImsReasonInfo;
            private @PreciseDisconnectCauses int mCause;
            private @Nullable EmergencyRegResult mEmergencyRegResult;

            /**
             * Default constructor for Builder.
             */
            public Builder(int slotId, int subId, @SelectorType int selectorType) {
                mSlotId = slotId;
                mSubId = subId;
                mSelectorType = selectorType;
            }

            /**
             * Sets the call identifier.
             *
             * @param callId The call identifier.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setCallId(@NonNull String callId) {
                mCallId = callId;
                return this;
            }

            /**
             * Sets the dialed number.
             *
             * @param number The dialed number.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setNumber(@NonNull String number) {
                mNumber = number;
                return this;
            }

            /**
             * Sets whether it's a video call or not.
             *
             * @param video Indicates it's a video call.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setVideoCall(boolean video) {
                mIsVideoCall = video;
                return this;
            }

            /**
             * Sets whether it's an emergency service or not.
             *
             * @param emergency Indicates it's emergency service.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setEmergency(boolean emergency) {
                mIsEmergency = emergency;
                return this;
            }

            /**
             * Sets whether the request caused the device to move out of airplane mode.
             *
             * @param exited {@code true} if the request caused the device to move out of
             *        airplane mode.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setExitedFromAirplaneMode(boolean exited) {
                mIsExitedFromAirplaneMode = exited;
                return this;
            }

            /**
             * Sets the Ut service attributes.
             * Only applicable for SELECTOR_TYPE_UT
             *
             * @param attr Ut services attributes.
             * @return The same instance of the builder.
             */
            /*
            public @NonNull Builder setUtAttributes(@NonNull UtAttributes attr);
                mUtAttributes = attr;
                return this;
            }
            */

            /**
             * Sets an optional reason why the last PS attempt failed.
             *
             * @param info The reason why the last PS attempt failed.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setPsDisconnectCause(@NonNull ImsReasonInfo info) {
                mImsReasonInfo = info;
                return this;
            }

            /**
             * Sets an optional reason why the last CS attempt failed.
             *
             * @param cause The reason why the last CS attempt failed.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setCsDisconnectCause(@PreciseDisconnectCauses int cause) {
                mCause = cause;
                return this;
            }

            /**
             * Sets the current registration result for emergency services.
             *
             * @param regResult The current registration result for emergency services.
             * @return The same instance of the builder.
             */
            public @NonNull Builder setEmergencyRegResult(@NonNull EmergencyRegResult regResult) {
                mEmergencyRegResult = regResult;
                return this;
            }

            /**
             * Build the SelectionAttributes.
             * @return The SelectionAttributes object.
             */
            public @NonNull SelectionAttributes build() {
                return new SelectionAttributes(mSlotId, mSubId, mCallId, mNumber, mSelectorType,
                        mIsVideoCall, mIsEmergency, mIsExitedFromAirplaneMode, /*mUtAttributes,*/
                        mImsReasonInfo, mCause, mEmergencyRegResult);
            }
        }
    }

    /**
     * A wrapper class for ITransportSelectorCallback interface.
     */
    private final class TransportSelectorCallbackWrapper implements TransportSelectorCallback {
        private static final String TAG = "TransportSelectorCallbackWrapper";

        private final @NonNull ITransportSelectorCallback mCallback;
        private final @NonNull Executor mExecutor;

        private @Nullable ITransportSelectorResultCallbackAdapter mResultCallback;
        private @Nullable DomainSelectorWrapper mSelectorWrapper;

        TransportSelectorCallbackWrapper(@NonNull ITransportSelectorCallback cb,
                @NonNull Executor executor) {
            mCallback = cb;
            mExecutor = executor;
        }

        @Override
        public void onCreated(@NonNull DomainSelector selector) {
            try {
                mSelectorWrapper = new DomainSelectorWrapper(selector, mExecutor);
                mCallback.onCreated(mSelectorWrapper.getCallbackBinder());
            } catch (Exception e) {
                Rlog.e(TAG, "onCreated e=" + e);
            }
        }

        @Override
        public void onWlanSelected(boolean useEmergencyPdn) {
            try {
                mCallback.onWlanSelected(useEmergencyPdn);
            } catch (Exception e) {
                Rlog.e(TAG, "onWlanSelected e=" + e);
            }
        }

        @Override
        public @NonNull WwanSelectorCallback onWwanSelected() {
            WwanSelectorCallback callback = null;
            try {
                IWwanSelectorCallback cb = mCallback.onWwanSelected();
                callback = new WwanSelectorCallbackWrapper(cb, mExecutor);
            } catch (Exception e) {
                Rlog.e(TAG, "onWwanSelected e=" + e);
            }

            return callback;
        }

        @Override
        public void onWwanSelected(Consumer<WwanSelectorCallback> consumer) {
            try {
                mResultCallback = new ITransportSelectorResultCallbackAdapter(consumer, mExecutor);
                mCallback.onWwanSelectedAsync(mResultCallback);
            } catch (Exception e) {
                Rlog.e(TAG, "onWwanSelected e=" + e);
                executeMethodAsyncNoException(mExecutor,
                        () -> consumer.accept(null), TAG, "onWwanSelectedAsync-Exception");
            }
        }

        @Override
        public void onSelectionTerminated(@DisconnectCauses int cause) {
            try {
                mCallback.onSelectionTerminated(cause);
                mSelectorWrapper = null;
            } catch (Exception e) {
                Rlog.e(TAG, "onSelectionTerminated e=" + e);
            }
        }

        private class ITransportSelectorResultCallbackAdapter
                extends ITransportSelectorResultCallback.Stub {
            private final @NonNull Consumer<WwanSelectorCallback> mConsumer;
            private final @NonNull Executor mExecutor;

            ITransportSelectorResultCallbackAdapter(
                    @NonNull Consumer<WwanSelectorCallback> consumer,
                    @NonNull Executor executor) {
                mConsumer = consumer;
                mExecutor = executor;
            }

            @Override
            public void onCompleted(@NonNull IWwanSelectorCallback cb) {
                if (mConsumer == null) return;

                WwanSelectorCallback callback = new WwanSelectorCallbackWrapper(cb, mExecutor);
                executeMethodAsyncNoException(mExecutor,
                        () -> mConsumer.accept(callback), TAG, "onWwanSelectedAsync-Completed");
            }
        }
    }

    /**
     * A wrapper class for IDomainSelector interface.
     */
    private final class DomainSelectorWrapper {
        private static final String TAG = "DomainSelectorWrapper";

        private @NonNull IDomainSelector mCallbackBinder;

        DomainSelectorWrapper(@NonNull DomainSelector cb, @NonNull Executor executor) {
            mCallbackBinder = new IDomainSelectorAdapter(cb, executor);
        }

        private class IDomainSelectorAdapter extends IDomainSelector.Stub {
            private final @NonNull WeakReference<DomainSelector> mDomainSelectorWeakRef;
            private final @NonNull Executor mExecutor;

            IDomainSelectorAdapter(@NonNull DomainSelector domainSelector,
                    @NonNull Executor executor) {
                mDomainSelectorWeakRef =
                        new WeakReference<DomainSelector>(domainSelector);
                mExecutor = executor;
            }

            @Override
            public void cancelSelection() {
                final DomainSelector domainSelector = mDomainSelectorWeakRef.get();
                if (domainSelector == null) return;

                executeMethodAsyncNoException(mExecutor,
                        () -> domainSelector.cancelSelection(), TAG, "cancelSelection");
            }

            @Override
            public void reselectDomain(@NonNull SelectionAttributes attr) {
                final DomainSelector domainSelector = mDomainSelectorWeakRef.get();
                if (domainSelector == null) return;

                executeMethodAsyncNoException(mExecutor,
                        () -> domainSelector.reselectDomain(attr), TAG, "reselectDomain");
            }

            @Override
            public void finishSelection() {
                final DomainSelector domainSelector = mDomainSelectorWeakRef.get();
                if (domainSelector == null) return;

                executeMethodAsyncNoException(mExecutor,
                        () -> domainSelector.finishSelection(), TAG, "finishSelection");
            }
        }

        public @NonNull IDomainSelector getCallbackBinder() {
            return mCallbackBinder;
        }
    }

    /**
     * A wrapper class for IWwanSelectorCallback and IWwanSelectorResultCallback.
     */
    private final class WwanSelectorCallbackWrapper
            implements WwanSelectorCallback, CancellationSignal.OnCancelListener {
        private static final String TAG = "WwanSelectorCallbackWrapper";

        private final @NonNull IWwanSelectorCallback mCallback;
        private final @NonNull Executor mExecutor;

        private @Nullable IWwanSelectorResultCallbackAdapter mResultCallback;

        WwanSelectorCallbackWrapper(@NonNull IWwanSelectorCallback cb,
                @NonNull Executor executor) {
            mCallback = cb;
            mExecutor = executor;
        }

        @Override
        public void onCancel() {
            try {
                mCallback.onCancel();
            } catch (Exception e) {
                Rlog.e(TAG, "onCancel e=" + e);
            }
        }

        @Override
        public void onRequestEmergencyNetworkScan(@NonNull List<Integer> preferredNetworks,
                @EmergencyScanType int scanType, @NonNull CancellationSignal signal,
                @NonNull Consumer<EmergencyRegResult> consumer) {
            try {
                if (signal != null) signal.setOnCancelListener(this);
                mResultCallback = new IWwanSelectorResultCallbackAdapter(consumer, mExecutor);
                mCallback.onRequestEmergencyNetworkScan(
                        preferredNetworks.stream().mapToInt(Integer::intValue).toArray(),
                        scanType, mResultCallback);
            } catch (Exception e) {
                Rlog.e(TAG, "onRequestEmergencyNetworkScan e=" + e);
            }
        }

        @Override
        public void onDomainSelected(@NetworkRegistrationInfo.Domain int domain,
                boolean useEmergencyPdn) {
            try {
                mCallback.onDomainSelected(domain, useEmergencyPdn);
            } catch (Exception e) {
                Rlog.e(TAG, "onDomainSelected e=" + e);
            }
        }

        private class IWwanSelectorResultCallbackAdapter
                extends IWwanSelectorResultCallback.Stub {
            private final @NonNull Consumer<EmergencyRegResult> mConsumer;
            private final @NonNull Executor mExecutor;

            IWwanSelectorResultCallbackAdapter(@NonNull Consumer<EmergencyRegResult> consumer,
                    @NonNull Executor executor) {
                mConsumer = consumer;
                mExecutor = executor;
            }

            @Override
            public void onComplete(@NonNull EmergencyRegResult result) {
                if (mConsumer == null) return;

                executeMethodAsyncNoException(mExecutor,
                        () -> mConsumer.accept(result), TAG, "onScanComplete");
            }
        }
    }

    private final Object mExecutorLock = new Object();

    /** Executor used to execute methods called remotely by the framework. */
    private @NonNull Executor mExecutor;

    /**
     * Selects a domain for the given operation.
     *
     * @param attr Required to determine the domain.
     * @param callback The callback instance being registered.
     */
    public void onDomainSelection(@NonNull SelectionAttributes attr,
            @NonNull TransportSelectorCallback callback) {
    }

    /**
     * Notifies the change in {@link ServiceState} for a specific slot.
     *
     * @param slotId For which the state changed.
     * @param subId For which the state changed.
     * @param serviceState Updated {@link ServiceState}.
     */
    public void onServiceStateUpdated(int slotId, int subId, @NonNull ServiceState serviceState) {
    }

    /**
     * Notifies the change in {@link BarringInfo} for a specific slot.
     *
     * @param slotId For which the state changed.
     * @param subId For which the state changed.
     * @param info Updated {@link BarringInfo}.
     */
    public void onBarringInfoUpdated(int slotId, int subId, @NonNull BarringInfo info) {
    }

    private final IBinder mDomainSelectionServiceController =
            new IDomainSelectionServiceController.Stub() {
        @Override
        public void selectDomain(@NonNull SelectionAttributes attr,
                @NonNull ITransportSelectorCallback callback)  throws RemoteException {
            executeMethodAsync(getCachedExecutor(),
                    () -> DomainSelectionService.this.onDomainSelection(attr,
                            new TransportSelectorCallbackWrapper(callback, getCachedExecutor())),
                    LOG_TAG, "onDomainSelection");
        }

        @Override
        public void updateServiceState(int slotId, int subId, @NonNull ServiceState serviceState) {
            executeMethodAsyncNoException(getCachedExecutor(),
                    () -> DomainSelectionService.this.onServiceStateUpdated(slotId,
                            subId, serviceState), LOG_TAG, "onServiceStateUpdated");
        }

        @Override
        public void updateBarringInfo(int slotId, int subId, @NonNull BarringInfo info) {
            executeMethodAsyncNoException(getCachedExecutor(),
                    () -> DomainSelectionService.this.onBarringInfoUpdated(slotId, subId, info),
                    LOG_TAG, "onBarringInfoUpdated");
        }
    };

    private static void executeMethodAsync(@NonNull Executor executor, @NonNull Runnable r,
            @NonNull String tag, @NonNull String errorLogName) throws RemoteException {
        try {
            CompletableFuture.runAsync(
                    () -> TelephonyUtils.runWithCleanCallingIdentity(r), executor).join();
        } catch (CancellationException | CompletionException e) {
            Rlog.w(tag, "Binder - " + errorLogName + " exception: " + e.getMessage());
            throw new RemoteException(e.getMessage());
        }
    }

    private void executeMethodAsyncNoException(@NonNull Executor executor, @NonNull Runnable r,
            @NonNull String tag, @NonNull String errorLogName) {
        try {
            CompletableFuture.runAsync(
                    () -> TelephonyUtils.runWithCleanCallingIdentity(r), executor).join();
        } catch (CancellationException | CompletionException e) {
            Rlog.w(tag, "Binder - " + errorLogName + " exception: " + e.getMessage());
        }
    }

    /** @hide */
    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.i(LOG_TAG, "DomainSelectionService Bound.");
            return mDomainSelectionServiceController;
        }
        return null;
    }

    /**
     * The DomainSelectionService will be able to define an {@link Executor} that the service
     * can use to execute the methods. It has set the default executor as Runnable::run,
     *
     * @return An {@link Executor} to be used.
     */
    @SuppressLint("OnNameExpected")
    public @NonNull Executor getExecutor() {
        return Runnable::run;
    }

    /**
     * Gets the {@link Executor} which executes methods of this service.
     * This method should be private when this service is implemented in a separated process
     * other than telephony framework.
     * @return {@link Executor} instance.
     * @hide
     */
    public @NonNull Executor getCachedExecutor() {
        synchronized (mExecutorLock) {
            if (mExecutor == null) {
                Executor e = getExecutor();
                mExecutor = (e != null) ? e : Runnable::run;
            }
            return mExecutor;
        }
    }

    /**
     * Returns a string representation of the domain.
     * @param domain The domain.
     * @return The name of the domain.
     * @hide
     */
    public static @NonNull String getDomainName(@NetworkRegistrationInfo.Domain int domain) {
        return NetworkRegistrationInfo.domainToString(domain);
    }
}
