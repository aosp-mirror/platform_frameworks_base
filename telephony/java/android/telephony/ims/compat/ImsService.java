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
 * limitations under the License
 */

package android.telephony.ims.compat;

import android.annotation.Nullable;
import android.app.Service;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.telephony.CarrierConfigManager;
import android.telephony.ims.compat.feature.ImsFeature;
import android.telephony.ims.compat.feature.MMTelFeature;
import android.telephony.ims.compat.feature.RcsFeature;
import android.util.Log;
import android.util.SparseArray;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsMMTelFeature;
import com.android.ims.internal.IImsRcsFeature;
import com.android.ims.internal.IImsServiceController;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Main ImsService implementation, which binds via the Telephony ImsResolver. Services that extend
 * ImsService must register the service in their AndroidManifest to be detected by the framework.
 * First, the application must declare that they use the "android.permission.BIND_IMS_SERVICE"
 * permission. Then, the ImsService definition in the manifest must follow the following format:
 *
 * ...
 * <service android:name=".EgImsService"
 *     android:permission="android.permission.BIND_IMS_SERVICE" >
 *     <!-- Apps must declare which features they support as metadata. The different categories are
 *     defined below. In this example, the RCS_FEATURE feature is supported. -->
 *     <meta-data android:name="android.telephony.ims.RCS_FEATURE" android:value="true" />
 *     <intent-filter>
 *         <action android:name="android.telephony.ims.compat.ImsService" />
 *     </intent-filter>
 * </service>
 * ...
 *
 * The telephony framework will then bind to the ImsService you have defined in your manifest
 * if you are either:
 * 1) Defined as the default ImsService for the device in the device overlay using
 *    "config_ims_package".
 * 2) Defined as a Carrier Provided ImsService in the Carrier Configuration using
 *    {@link CarrierConfigManager#KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING}.
 *
 * The features that are currently supported in an ImsService are:
 * - RCS_FEATURE: This ImsService implements the RcsFeature class.
 * - MMTEL_FEATURE: This ImsService implements the MMTelFeature class.
 * - EMERGENCY_MMTEL_FEATURE: This ImsService implements the MMTelFeature class and will be
 *   available to place emergency calls at all times. This MUST be implemented by the default
 *   ImsService provided in the device overlay.
 *   @hide
 */
public class ImsService extends Service {

    private static final String LOG_TAG = "ImsService(Compat)";

    /**
     * The intent that must be defined as an intent-filter in the AndroidManifest of the ImsService.
     * @hide
     */
    public static final String SERVICE_INTERFACE = "android.telephony.ims.compat.ImsService";

    // A map of slot Id -> map of features (indexed by ImsFeature feature id) corresponding to that
    // slot.
    // We keep track of this to facilitate cleanup of the IImsFeatureStatusCallback and
    // call ImsFeature#onFeatureRemoved.
    private final SparseArray<SparseArray<ImsFeature>> mFeaturesBySlot = new SparseArray<>();

    /**
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    protected final IBinder mImsServiceController = new IImsServiceController.Stub() {

        @Override
        public IImsMMTelFeature createEmergencyMMTelFeature(int slotId) {
            return createEmergencyMMTelFeatureInternal(slotId);
        }

        @Override
        public IImsMMTelFeature createMMTelFeature(int slotId) {
            return createMMTelFeatureInternal(slotId);
        }

        @Override
        public IImsRcsFeature createRcsFeature(int slotId) {
            return createRcsFeatureInternal(slotId);
        }

        @Override
        public void removeImsFeature(int slotId, int featureType) {
            ImsService.this.removeImsFeature(slotId, featureType);
        }

        @Override
        public void addFeatureStatusCallback(int slotId, int featureType,
                IImsFeatureStatusCallback c) {
            addImsFeatureStatusCallback(slotId, featureType, c);
        }

        @Override
        public void removeFeatureStatusCallback(int slotId, int featureType,
                IImsFeatureStatusCallback c) {
            removeImsFeatureStatusCallback(slotId, featureType, c);
        }
    };

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ImsService() {
    }

    /**
     * @hide
     */
    @Override
    public IBinder onBind(Intent intent) {
        if(SERVICE_INTERFACE.equals(intent.getAction())) {
            Log.i(LOG_TAG, "ImsService(Compat) Bound.");
            return mImsServiceController;
        }
        return null;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public SparseArray<ImsFeature> getFeatures(int slotId) {
        return mFeaturesBySlot.get(slotId);
    }

    private IImsMMTelFeature createEmergencyMMTelFeatureInternal(int slotId) {
        MMTelFeature f = onCreateEmergencyMMTelImsFeature(slotId);
        if (f != null) {
            setupFeature(f, slotId, ImsFeature.EMERGENCY_MMTEL);
            return f.getBinder();
        } else {
            return null;
        }
    }

    private IImsMMTelFeature createMMTelFeatureInternal(int slotId) {
        MMTelFeature f = onCreateMMTelImsFeature(slotId);
        if (f != null) {
            setupFeature(f, slotId, ImsFeature.MMTEL);
            return f.getBinder();
        } else {
            return null;
        }
    }

    private IImsRcsFeature createRcsFeatureInternal(int slotId) {
        RcsFeature f = onCreateRcsFeature(slotId);
        if (f != null) {
            setupFeature(f, slotId, ImsFeature.RCS);
            return f.getBinder();
        } else {
            return null;
        }
    }

    private void setupFeature(ImsFeature f, int slotId, int featureType) {
        f.setContext(this);
        f.setSlotId(slotId);
        addImsFeature(slotId, featureType, f);
        f.onFeatureReady();
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

    private void addImsFeatureStatusCallback(int slotId, int featureType,
            IImsFeatureStatusCallback c) {
        synchronized (mFeaturesBySlot) {
            // get ImsFeature associated with the slot/feature
            SparseArray<ImsFeature> features = mFeaturesBySlot.get(slotId);
            if (features == null) {
                Log.w(LOG_TAG, "Can not add ImsFeatureStatusCallback. No ImsFeatures exist on"
                        + " slot " + slotId);
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
                Log.w(LOG_TAG, "Can not remove ImsFeatureStatusCallback. No ImsFeatures exist on"
                        + " slot " + slotId);
                return;
            }
            ImsFeature f = features.get(featureType);
            if (f != null) {
                f.removeImsFeatureStatusCallback(c);
            }
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
     * @return An implementation of MMTelFeature that will be used by the system for MMTel
     * functionality. Must be able to handle emergency calls at any time as well.
     * @hide
     */
    public @Nullable MMTelFeature onCreateEmergencyMMTelImsFeature(int slotId) {
        return null;
    }

    /**
     * @return An implementation of MMTelFeature that will be used by the system for MMTel
     * functionality.
     * @hide
     */
    public @Nullable MMTelFeature onCreateMMTelImsFeature(int slotId) {
        return null;
    }

    /**
     * @return An implementation of RcsFeature that will be used by the system for RCS.
     * @hide
     */
    public @Nullable RcsFeature onCreateRcsFeature(int slotId) {
        return null;
    }
}
