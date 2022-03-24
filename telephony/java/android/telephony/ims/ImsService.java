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

package android.telephony.ims;

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.aidl.IImsConfig;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsRcsFeature;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsServiceController;
import android.telephony.ims.aidl.IImsServiceControllerListener;
import android.telephony.ims.aidl.ISipTransport;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.feature.RcsFeature;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsFeatureConfiguration;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.SipTransportImplBase;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.TelephonyUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Main ImsService implementation, which binds via the Telephony ImsResolver. Services that extend
 * ImsService must register the service in their AndroidManifest to be detected by the framework.
 * First, the application must declare that they use the "android.permission.BIND_IMS_SERVICE"
 * permission. Then, the ImsService definition in the manifest must follow the following format:
 *
 * ...
 * <service android:name=".EgImsService"
 *     android:permission="android.permission.BIND_IMS_SERVICE" >
 *     ...
 *     <intent-filter>
 *         <action android:name="android.telephony.ims.ImsService" />
 *     </intent-filter>
 * </service>
 * ...
 *
 * The telephony framework will then bind to the ImsService you have defined in your manifest
 * if you are either:
 * 1) Defined as the default ImsService for the device in the device overlay using
 *    "config_ims_mmtel_package" or "config_ims_rcs_package".
 * 2) Defined as a Carrier Provided ImsService in the Carrier Configuration using
 *    {@link CarrierConfigManager#KEY_CONFIG_IMS_MMTEL_PACKAGE_OVERRIDE_STRING} or
 *    {@link CarrierConfigManager#KEY_CONFIG_IMS_RCS_PACKAGE_OVERRIDE_STRING}.
 *
 * There are two ways to define to the platform which {@link ImsFeature}s this {@link ImsService}
 * supports, dynamic or static definitions.
 *
 * In the static definition, the {@link ImsFeature}s that are supported are defined in the service
 * definition of the AndroidManifest.xml file as metadata:
 * <!-- Apps must declare which features they support as metadata. The different categories are
 *      defined below. In this example, the MMTEL_FEATURE feature is supported. -->
 * <meta-data android:name="android.telephony.ims.MMTEL_FEATURE" android:value="true" />
 *
 * The features that are currently supported in an ImsService are:
 * - RCS_FEATURE: This ImsService implements the RcsFeature class.
 * - MMTEL_FEATURE: This ImsService implements the MmTelFeature class.
 * - EMERGENCY_MMTEL_FEATURE: This ImsService supports Emergency Calling for MMTEL, must be
 *   declared along with the MMTEL_FEATURE. If this is not specified, the framework will use
 *   circuit switch for emergency calling.
 *
 * In the dynamic definition, the supported features are not specified in the service definition
 * of the AndroidManifest. Instead, the framework binds to this service and calls
 * {@link #querySupportedImsFeatures()}. The {@link ImsService} then returns an
 * {@link ImsFeatureConfiguration}, which the framework uses to initialize the supported
 * {@link ImsFeature}s. If at any time, the list of supported {@link ImsFeature}s changes,
 * {@link #onUpdateSupportedImsFeatures(ImsFeatureConfiguration)} can be called to tell the
 * framework of the changes.
 *
 * @hide
 */
@SystemApi
public class ImsService extends Service {

    private static final String LOG_TAG = "ImsService";

    /**
     * This ImsService supports the capability to place emergency calls over MMTEL.
     * <p>
     * Note: This should never be set by {@link #getImsServiceCapabilities()}, as whether it is
     * there or not depends on whether or not {@link ImsFeature#FEATURE_EMERGENCY_MMTEL} is defined
     * for this ImsService. If it is set, it will be removed during sanitization before the final
     * capabilities bitfield is sent back to the framework.
     * @hide This is encoded into the {@link ImsFeature#FEATURE_EMERGENCY_MMTEL}, but we will be
     * adding other capabilities in a central location, so track this capability here as well.
     */
    public static final long CAPABILITY_EMERGENCY_OVER_MMTEL = 1 << 0;

    /**
     * This ImsService supports the capability to create SIP delegates for other IMS applications
     * to use to proxy SIP messaging traffic through it.
     * <p>
     * In order for the framework to report SipDelegate creation as being available for this
     * ImsService implementation, this ImsService must report this capability flag in
     * {@link #getImsServiceCapabilities()}, {@link #getSipTransport(int)} must not return null, and
     * this ImsService MUST report the ability to create both {@link ImsFeature#FEATURE_MMTEL} and
     * {@link ImsFeature#FEATURE_RCS} features.
     */
    public static final long CAPABILITY_SIP_DELEGATE_CREATION = 1 << 1;

    /**
     * Used for internal correctness checks of capabilities set by the ImsService implementation and
     * tracks the index of the largest defined flag in the capabilities long.
     * @hide
     */
    public static final long CAPABILITY_MAX_INDEX =
            Long.numberOfTrailingZeros(CAPABILITY_SIP_DELEGATE_CREATION);

    /**
     * @hide
     */
    @LongDef(flag = true,
            prefix = "CAPABILITY_",
            value = {
                    // CAPABILITY_EMERGENCY_OVER_MMTEL is not included here because it is managed by
                    // whether or not ImsFeature.FEATURE_EMERGENCY_MMTEL feature is set and should
                    // not be set by users of ImsService.
                    CAPABILITY_SIP_DELEGATE_CREATION
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsServiceCapability {}

    /**
     * Used for logging purposes, see {@link #getCapabilitiesString(long)}
     * @hide
     */
    private static final Map<Long, String> CAPABILITIES_LOG_MAP = new HashMap<Long, String>() {{
            put(CAPABILITY_EMERGENCY_OVER_MMTEL, "EMERGENCY_OVER_MMTEL");
            put(CAPABILITY_SIP_DELEGATE_CREATION, "SIP_DELEGATE_CREATION");
        }};

    /**
     * The intent that must be defined as an intent-filter in the AndroidManifest of the ImsService.
     * @hide
     */
    public static final String SERVICE_INTERFACE = "android.telephony.ims.ImsService";

    // A map of slot Id -> map of features (indexed by ImsFeature feature id) corresponding to that
    // slot.
    // We keep track of this to facilitate cleanup of the IImsFeatureStatusCallback and
    // call ImsFeature#onFeatureRemoved.
    private final SparseArray<SparseArray<ImsFeature>> mFeaturesBySlot = new SparseArray<>();

    // A map of slot id -> boolean array, where each entry in the boolean array corresponds to an
    // ImsFeature that was created for a slot id and not a sub id for backwards compatibility
    // purposes.
    private final SparseArray<SparseBooleanArray> mCreateImsFeatureWithSlotIdFlagMap =
            new SparseArray<>();

    private IImsServiceControllerListener mListener;
    private final Object mListenerLock = new Object();
    private Executor mExecutor;

    /**
     * Create a new ImsService.
     * <p>
     * Method stubs called from the framework will be called asynchronously. Vendor specifies the
     * {@link Executor} that the methods stubs will be called. If mExecutor is set to null by
     * vendor use Runnable::run.
     */
    public ImsService() {
        mExecutor = ImsService.this.getExecutor();
        if (mExecutor == null) {
            mExecutor = Runnable::run;
        }
    }

    /**
     * Listener that notifies the framework of ImsService changes.
     * @hide
     */
    public static class Listener extends IImsServiceControllerListener.Stub {
        /**
         * The IMS features that this ImsService supports has changed.
         * @param c a new {@link ImsFeatureConfiguration} containing {@link ImsFeature.FeatureType}s
         *   that this ImsService supports. This may trigger the addition/removal of feature
         *   in this service.
         */
        public void onUpdateSupportedImsFeatures(ImsFeatureConfiguration c) {
        }
    }

    /**
     * @hide
     */
    protected final IBinder mImsServiceController = new IImsServiceController.Stub() {
        @Override
        public void setListener(IImsServiceControllerListener l) {
            synchronized (mListenerLock) {
                if (mListener != null && mListener.asBinder().isBinderAlive()) {
                    try {
                        mListener.asBinder().unlinkToDeath(mDeathRecipient, 0);
                    } catch (NoSuchElementException e) {
                        Log.w(LOG_TAG, "IImsServiceControllerListener does not exist");
                    }
                }

                mListener = l;
                if (mListener == null) {
                    executeMethodAsync(() -> releaseResource(), "releaseResource");
                    return;
                }

                try {
                    mListener.asBinder().linkToDeath(mDeathRecipient, 0);
                    Log.i(LOG_TAG, "setListener: register linkToDeath");
                } catch (RemoteException e) {
                    // RemoteException means target binder process was crashed
                    // release resource
                    executeMethodAsync(() -> releaseResource(), "releaseResource");
                }
            }
        }

        @Override
        public IImsMmTelFeature createMmTelFeature(int slotId, int subId) {
            MmTelFeature f  = (MmTelFeature) getImsFeature(slotId, ImsFeature.FEATURE_MMTEL);
            if (f == null) {
                return executeMethodAsyncForResult(() -> createMmTelFeatureInternal(slotId, subId),
                        "createMmTelFeature");
            } else {
                return f.getBinder();
            }
        }

        @Override
        public IImsMmTelFeature createEmergencyOnlyMmTelFeature(int slotId) {
            MmTelFeature f  = (MmTelFeature) getImsFeature(slotId, ImsFeature.FEATURE_MMTEL);
            if (f == null) {
                return executeMethodAsyncForResult(() -> createEmergencyOnlyMmTelFeatureInternal(
                        slotId), "createEmergencyOnlyMmTelFeature");
            } else {
                return f.getBinder();
            }
        }

        @Override
        public IImsRcsFeature createRcsFeature(int slotId, int subId) {
            RcsFeature f  = (RcsFeature) getImsFeature(slotId, ImsFeature.FEATURE_RCS);
            if (f == null) {
                return executeMethodAsyncForResult(() ->
                        createRcsFeatureInternal(slotId, subId), "createRcsFeature");
            } else {
                return f.getBinder();
            }
        }

        @Override
        public void addFeatureStatusCallback(int slotId, int featureType,
                IImsFeatureStatusCallback c) {
            executeMethodAsync(() -> ImsService.this.addImsFeatureStatusCallback(
                    slotId, featureType, c), "addFeatureStatusCallback");
        }

        @Override
        public void removeFeatureStatusCallback(int slotId, int featureType,
                IImsFeatureStatusCallback c) {
            executeMethodAsync(() -> ImsService.this.removeImsFeatureStatusCallback(
                    slotId, featureType, c), "removeFeatureStatusCallback");
        }

        @Override
        public void removeImsFeature(int slotId, int featureType, boolean changeSubId) {
            if (changeSubId && isImsFeatureCreatedForSlot(slotId, featureType)) {
                Log.w(LOG_TAG, "Do not remove Ims feature for compatibility");
                return;
            }
            executeMethodAsync(() -> ImsService.this.removeImsFeature(slotId, featureType),
                    "removeImsFeature");
            setImsFeatureCreatedForSlot(slotId, featureType, false);
        }

        @Override
        public ImsFeatureConfiguration querySupportedImsFeatures() {
            return executeMethodAsyncForResult(() -> ImsService.this.querySupportedImsFeatures(),
                    "ImsFeatureConfiguration");
        }

        @Override
        public long getImsServiceCapabilities() {
            return executeMethodAsyncForResult(() -> {
                long caps = ImsService.this.getImsServiceCapabilities();
                long sanitizedCaps = sanitizeCapabilities(caps);
                if (caps != sanitizedCaps) {
                    Log.w(LOG_TAG, "removing invalid bits from field: 0x"
                            + Long.toHexString(caps ^ sanitizedCaps));
                }
                return sanitizedCaps;
            }, "getImsServiceCapabilities");
        }

        @Override
        public void notifyImsServiceReadyForFeatureCreation() {
            executeMethodAsync(() -> ImsService.this.readyForFeatureCreation(),
                    "notifyImsServiceReadyForFeatureCreation");
        }

        @Override
        public IImsConfig getConfig(int slotId, int subId) {
            return executeMethodAsyncForResult(() -> {
                ImsConfigImplBase c =
                        ImsService.this.getConfigForSubscription(slotId, subId);
                if (c != null) {
                    c.setDefaultExecutor(mExecutor);
                    return c.getIImsConfig();
                } else {
                    return null;
                }
            }, "getConfig");
        }

        @Override
        public IImsRegistration getRegistration(int slotId, int subId) {
            return executeMethodAsyncForResult(() -> {
                ImsRegistrationImplBase r =
                        ImsService.this.getRegistrationForSubscription(slotId, subId);
                if (r != null) {
                    r.setDefaultExecutor(mExecutor);
                    return r.getBinder();
                } else {
                    return null;
                }
            }, "getRegistration");
        }

        @Override
        public ISipTransport getSipTransport(int slotId) {
            return executeMethodAsyncForResult(() -> {
                SipTransportImplBase s =  ImsService.this.getSipTransport(slotId);
                if (s != null) {
                    s.setDefaultExecutor(mExecutor);
                    return s.getBinder();
                } else {
                    return null;
                }
            }, "getSipTransport");
        }

        @Override
        public void enableIms(int slotId, int subId) {
            executeMethodAsync(() ->
                    ImsService.this.enableImsForSubscription(slotId, subId), "enableIms");
        }

        @Override
        public void disableIms(int slotId, int subId) {
            executeMethodAsync(() ->
                    ImsService.this.disableImsForSubscription(slotId, subId), "disableIms");
        }


    };

    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(LOG_TAG,
                    "IImsServiceControllerListener binder to framework has died. Cleaning up");
            executeMethodAsync(() -> releaseResource(), "releaseResource");
        }
    };

    /**
     * @hide
     */
    @Override
    public IBinder onBind(Intent intent) {
        if(SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.i(LOG_TAG, "ImsService Bound.");
            return mImsServiceController;
        }
        return null;
    }

    private IImsMmTelFeature createMmTelFeatureInternal(int slotId, int subscriptionId) {
        MmTelFeature f = createMmTelFeatureForSubscription(slotId, subscriptionId);
        if (f != null) {
            setupFeature(f, slotId, ImsFeature.FEATURE_MMTEL);
            f.setDefaultExecutor(mExecutor);
            return f.getBinder();
        } else {
            Log.e(LOG_TAG, "createMmTelFeatureInternal: null feature returned.");
            return null;
        }
    }

    private IImsMmTelFeature createEmergencyOnlyMmTelFeatureInternal(int slotId) {
        MmTelFeature f = createEmergencyOnlyMmTelFeature(slotId);
        if (f != null) {
            setupFeature(f, slotId, ImsFeature.FEATURE_MMTEL);
            f.setDefaultExecutor(mExecutor);
            return f.getBinder();
        } else {
            Log.e(LOG_TAG, "createEmergencyOnlyMmTelFeatureInternal: null feature returned.");
            return null;
        }
    }

    private IImsRcsFeature createRcsFeatureInternal(int slotId, int subI) {
        RcsFeature f = createRcsFeatureForSubscription(slotId, subI);
        if (f != null) {
            f.setDefaultExecutor(mExecutor);
            setupFeature(f, slotId, ImsFeature.FEATURE_RCS);
            return f.getBinder();
        } else {
            Log.e(LOG_TAG, "createRcsFeatureInternal: null feature returned.");
            return null;
        }
    }

    private void setupFeature(ImsFeature f, int slotId, int featureType) {
        f.initialize(this, slotId);
        addImsFeature(slotId, featureType, f);
    }

    private void addImsFeatureStatusCallback(int slotId, int featureType,
            IImsFeatureStatusCallback c) {
        synchronized (mFeaturesBySlot) {
            // get ImsFeature associated with the slot/feature
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                Log.w(LOG_TAG, "Can not add ImsFeatureStatusCallback - no features on slot "
                        + slotId);
                return;
            }
            ImsFeature f = features.get(featureType);
            if (f != null) {
                f.addImsFeatureStatusCallback(c);
            }
        }
    }

    private void removeImsFeatureStatusCallback(int slotId, int featureType,
            IImsFeatureStatusCallback c) {
        synchronized (mFeaturesBySlot) {
            // get ImsFeature associated with the slot/feature
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                Log.w(LOG_TAG, "Can not remove ImsFeatureStatusCallback - no features on slot "
                        + slotId);
                return;
            }
            ImsFeature f = features.get(featureType);
            if (f != null) {
                f.removeImsFeatureStatusCallback(c);
            }
        }
    }

    private void addImsFeature(int slotId, int featureType, ImsFeature f) {
        synchronized (mFeaturesBySlot) {
            // Get SparseArray for Features, by querying slot Id
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                // Populate new SparseArray of features if it doesn't exist for this slot yet.
                features = new SparseArray<>();
                mFeaturesBySlot.put(slotId, features);
            }
            features.put(featureType, f);
        }
    }

    private void removeImsFeature(int slotId, int featureType) {
        synchronized (mFeaturesBySlot) {
            // get ImsFeature associated with the slot/feature
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                Log.w(LOG_TAG, "Can not remove ImsFeature. No ImsFeatures exist on slot "
                        + slotId);
                return;
            }
            ImsFeature f = features.get(featureType);
            if (f == null) {
                Log.w(LOG_TAG, "Can not remove ImsFeature. No feature with type "
                        + featureType + " exists on slot " + slotId);
                return;
            }
            f.onFeatureRemoved();
            features.remove(featureType);
        }

    }

    /**
     * @hide
     */
    @VisibleForTesting
    public ImsFeature getImsFeature(int slotId, int featureType) {
        synchronized (mFeaturesBySlot) {
            // Get SparseArray for Features, by querying slot Id
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                return null;
            }
            return features.get(featureType);
        }
    }

    private void setImsFeatureCreatedForSlot(int slotId,
            @ImsFeature.FeatureType int featureType, boolean createdForSlot) {
        synchronized (mCreateImsFeatureWithSlotIdFlagMap) {
            getImsFeatureCreatedForSlot(slotId).put(featureType, createdForSlot);
        }
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public boolean isImsFeatureCreatedForSlot(int slotId,
            @ImsFeature.FeatureType int featureType) {
        synchronized (mCreateImsFeatureWithSlotIdFlagMap) {
            return getImsFeatureCreatedForSlot(slotId).get(featureType);
        }
    }

    private SparseBooleanArray getImsFeatureCreatedForSlot(int slotId) {
        SparseBooleanArray createFlag = mCreateImsFeatureWithSlotIdFlagMap.get(slotId);
        if (createFlag == null) {
            createFlag = new SparseBooleanArray();
            mCreateImsFeatureWithSlotIdFlagMap.put(slotId, createFlag);
        }
        return createFlag;
    }

    private void releaseResource() {
        Log.w(LOG_TAG, "cleaning up features");
        synchronized (mFeaturesBySlot) {
            SparseArray<ImsFeature> features;
            ImsFeature imsFeature;

            for (int i = 0; i < mFeaturesBySlot.size(); i++) {
                features = mFeaturesBySlot.valueAt(i);
                if (features == null) {
                    continue;
                }

                for (int index = 0; index < features.size(); index++) {
                    imsFeature = features.valueAt(index);
                    if (imsFeature != null) {
                        imsFeature.onFeatureRemoved();
                    }
                }
                features.clear();
            }
            mFeaturesBySlot.clear();
        }
    }

    // Call the methods with a clean calling identity on the executor and wait indefinitely for
    // the future to return.
    private void executeMethodAsync(Runnable r, String errorLogName) {
        try {
            CompletableFuture.runAsync(
                    () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor).join();
        } catch (CancellationException | CompletionException e) {
            Log.w(LOG_TAG, "ImsService Binder - " + errorLogName + " exception: "
                    + e.getMessage());
        }
    }

    private <T> T executeMethodAsyncForResult(Supplier<T> r, String errorLogName) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(
                () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor);
        try {
            return future.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.w(LOG_TAG, "ImsService Binder - " + errorLogName + " exception: "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * When called, provide the {@link ImsFeatureConfiguration} that this {@link ImsService}
     * currently supports. This will trigger the framework to set up the {@link ImsFeature}s that
     * correspond to the {@link ImsFeature}s configured here.
     *
     * Use {@link #onUpdateSupportedImsFeatures(ImsFeatureConfiguration)} to change the supported
     * {@link ImsFeature}s.
     *
     * @return an {@link ImsFeatureConfiguration} containing Features this ImsService supports.
     */
    public ImsFeatureConfiguration querySupportedImsFeatures() {
        // Return empty for base implementation
        return new ImsFeatureConfiguration();
    }

    /**
     * Updates the framework with a new {@link ImsFeatureConfiguration} containing the updated
     * features, that this {@link ImsService} supports. This may trigger the framework to add/remove
     * new ImsFeatures, depending on the configuration.
     */
    public final void onUpdateSupportedImsFeatures(ImsFeatureConfiguration c)
            throws RemoteException {
        IImsServiceControllerListener l;
        synchronized (mListenerLock) {
            if (mListener == null) {
                throw new IllegalStateException("Framework is not ready");
            }
            l = mListener;
        }
        l.onUpdateSupportedImsFeatures(c);
    }

    /**
     * The optional capabilities that this ImsService supports.
     * <p>
     * This should be a static configuration and should not change at runtime.
     * @return The optional static capabilities of this ImsService implementation.
     */
    // ImsService follows a different convention, since it is a stub class. The on* methods are
    // final and call back into the framework with a state update.
    @SuppressLint("OnNameExpected")
    public @ImsServiceCapability long getImsServiceCapabilities() {
        // Stub implementation to be implemented by ImsService.
        return 0L;
    }

    /**
     * The ImsService has been bound and is ready for ImsFeature creation based on the Features that
     * the ImsService has registered for with the framework, either in the manifest or via
     * {@link #querySupportedImsFeatures()}.
     *
     * The ImsService should use this signal instead of onCreate/onBind or similar to perform
     * feature initialization because the framework may bind to this service multiple times to
     * query the ImsService's {@link ImsFeatureConfiguration} via
     * {@link #querySupportedImsFeatures()}before creating features.
     */
    public void readyForFeatureCreation() {
    }

    /**
     * The framework has enabled IMS for the subscription specified, the ImsService should register
     * for IMS and perform all appropriate initialization to bring up all ImsFeatures.
     *
     * @param slotId The slot ID that IMS will be enabled for.
     * @param subscriptionId The subscription ID that IMS will be enabled for.
     */
    public void enableImsForSubscription(int slotId, int subscriptionId) {
        enableIms(slotId);
    }

    /**
     * The framework has disabled IMS for the subscription specified. The ImsService must deregister
     * for IMS and set capability status to false for all ImsFeatures.
     * @param slotId The slot ID that IMS will be disabled for.
     * @param subscriptionId The subscription ID that IMS will be disabled for.
     */
    public void disableImsForSubscription(int slotId, int subscriptionId) {
        disableIms(slotId);
    }

    /**
     * The framework has enabled IMS for the slot specified, the ImsService should register for IMS
     * and perform all appropriate initialization to bring up all ImsFeatures.
     * @deprecated Use {@link #enableImsForSubscription} instead.
     */
    @Deprecated
    public void enableIms(int slotId) {
    }

    /**
     * The framework has disabled IMS for the slot specified. The ImsService must deregister for IMS
     * and set capability status to false for all ImsFeatures.
     * @deprecated Use {@link #disableImsForSubscription} instead.
     */
    @Deprecated
    public void disableIms(int slotId) {
    }

    /**
     * When called, the framework is requesting that a new {@link MmTelFeature} is created for the
     * specified subscription.
     *
     * @param subscriptionId The subscription ID that the MMTEL Feature is being created for.
     * @return The newly created {@link MmTelFeature} associated with the subscription or null if
     * the feature is not supported.
     */
    public @Nullable MmTelFeature createMmTelFeatureForSubscription(int slotId,
            int subscriptionId) {
        setImsFeatureCreatedForSlot(slotId, ImsFeature.FEATURE_MMTEL, true);
        return createMmTelFeature(slotId);
    }

    /**
     * When called, the framework is requesting that a new {@link RcsFeature} is created for the
     * specified subscription.
     *
     * @param subscriptionId The subscription ID that the RCS Feature is being created for.
     * @return The newly created {@link RcsFeature} associated with the subscription or null if the
     * feature is not supported.
     */
    public @Nullable RcsFeature createRcsFeatureForSubscription(int slotId, int subscriptionId) {
        setImsFeatureCreatedForSlot(slotId, ImsFeature.FEATURE_RCS, true);
        return createRcsFeature(slotId);
    }

    /**
     * When called, the framework is requesting that a new emergency-only {@link MmTelFeature} is
     * created for the specified slot. For emergency calls, there is no known Subscription Id.
     *
     * @param slotId The slot ID that the MMTEL Feature is being created for.
     * @return An MmTelFeature instance to be used for the slot ID when there is not
     * subscription inserted. Only requested when there is no subscription active on
     * the specified slot.
     */
    public @Nullable MmTelFeature createEmergencyOnlyMmTelFeature(int slotId) {
        setImsFeatureCreatedForSlot(slotId, ImsFeature.FEATURE_MMTEL, true);
        return createMmTelFeature(slotId);
    }

    /**
     * When called, the framework is requesting that a new {@link MmTelFeature} is created for the
     * specified slot.
     * @deprecated Use {@link #createMmTelFeatureForSubscription} instead
     *
     * @param slotId The slot ID that the MMTEL Feature is being created for.
     * @return The newly created {@link MmTelFeature} associated with the slot or null if the
     * feature is not supported.
     */
    @Deprecated
    public MmTelFeature createMmTelFeature(int slotId) {
        return null;
    }

    /**
     * When called, the framework is requesting that a new {@link RcsFeature} is created for the
     * specified slot.
     * @deprecated Use {@link #createRcsFeatureForSubscription} instead
     *
     * @param slotId The slot ID that the RCS Feature is being created for.
     * @return The newly created {@link RcsFeature} associated with the slot or null if the feature
     * is not supported.
     */
    @Deprecated
    public RcsFeature createRcsFeature(int slotId) {
        return null;
    }

    /**
     * Return the {@link ImsConfigImplBase} implementation associated with the provided
     * subscription. This will be used by the platform to get/set specific IMS related
     * configurations.
     *
     * @param subscriptionId The subscription ID that the IMS configuration is associated with.
     * @return ImsConfig implementation that is associated with the specified subscription.
     */
    public @NonNull ImsConfigImplBase getConfigForSubscription(int slotId, int subscriptionId) {
        return getConfig(slotId);
    }

    /**
     * Return the {@link ImsRegistrationImplBase} implementation associated with the provided
     * subscription.
     *
     * @param subscriptionId The subscription ID that is associated with the IMS Registration.
     * @return the ImsRegistration implementation associated with the subscription.
     */
    public @NonNull ImsRegistrationImplBase getRegistrationForSubscription(int slotId,
            int subscriptionId) {
        return getRegistration(slotId);
    }

    /**
     * Return the {@link ImsConfigImplBase} implementation associated with the provided slot. This
     * will be used by the platform to get/set specific IMS related configurations.
     * @deprecated use {@link #getConfigForSubscription} instead.
     *
     * @param slotId The slot that the IMS configuration is associated with.
     * @return ImsConfig implementation that is associated with the specified slot.
     */
    @Deprecated
    public ImsConfigImplBase getConfig(int slotId) {
        return new ImsConfigImplBase();
    }

    /**
     * Return the {@link ImsRegistrationImplBase} implementation associated with the provided slot.
     * @deprecated use  {@link #getRegistrationForSubscription} instead.
     *
     * @param slotId The slot that is associated with the IMS Registration.
     * @return the ImsRegistration implementation associated with the slot.
     */
    @Deprecated
    public ImsRegistrationImplBase getRegistration(int slotId) {
        return new ImsRegistrationImplBase();
    }

    /**
     * Return the {@link SipTransportImplBase} implementation associated with the provided slot.
     * <p>
     * This is an optional interface used for devices that must support IMS single registration and
     * proxy SIP traffic to remote IMS applications. If this is not supported for this IMS service,
     * this method should return {@code null}. If this feature is supported, then this method must
     * never be {@code null} and the optional ImsService capability flag
     * {@link #CAPABILITY_SIP_DELEGATE_CREATION} must be set in
     * {@link #getImsServiceCapabilities()}. Otherwise the framework will assume this feature is not
     * supported for this ImsService.
     * @param slotId The slot that is associated with the SipTransport implementation.
     * @return the SipTransport implementation for the specified slot.
     */
    // ImsService follows a different convention, since it is a stub class. The on* methods are
    // final and call back into the framework with a state update.
    @SuppressLint("OnNameExpected")
    public @Nullable SipTransportImplBase getSipTransport(int slotId) {
        // Stub implementation for ImsServices that do not support SipTransport.
        return null;
    }

    private static long sanitizeCapabilities(@ImsServiceCapability long caps) {
        long filter = 0xFFFFFFFFFFFFFFFFL;
        // pad the "allowed" set with zeros
        filter <<= CAPABILITY_MAX_INDEX + 1;
        // remove values above the allowed set.
        caps &= ~filter;
        // CAPABILITY_EMERGENCY_OVER_MMTEL should also not be set here, will be set by telephony
        // internally.
        caps &= ~CAPABILITY_EMERGENCY_OVER_MMTEL;
        return caps;
    }

    /**
     * @return A string representation of the ImsService capabilities for logging.
     * @hide
     */
    public static String getCapabilitiesString(@ImsServiceCapability long caps) {
        StringBuffer result = new StringBuffer();
        result.append("capabilities={ ");
        // filter incrementally fills 0s from  left to right. This is used to keep filtering out
        // more bits in the long until the remaining leftmost bits are all zero.
        long filter = 0xFFFFFFFFFFFFFFFFL;
        // position of iterator to potentially print capability.
        long i = 0;
        while ((caps & filter) != 0 && i <= 63) {
            long bitToCheck = (1L << i);
            if ((caps & bitToCheck) != 0) {
                result.append(CAPABILITIES_LOG_MAP.getOrDefault(bitToCheck, bitToCheck + "?"));
                result.append(" ");
            }
            // shift left by one and fill in another 1 on the leftmost bit.
            filter <<= 1;
            i++;
        }
        result.append("}");
        return result.toString();
    }

    /**
     * The ImsService will now be able to define an Executor that the ImsService can be used to
     * execute the methods. By default all ImsService level method calls will use this Executor.
     * The ImsService has set the default executor as Runnable::run,
     * Should be override or default executor will be used.
     *  @return an Executor used to execute methods called remotely by the framework.
     */
    public @NonNull Executor getExecutor() {
        return Runnable::run;
    }
}
