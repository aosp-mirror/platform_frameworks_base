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

package com.android.server.biometrics.sensors.face.aidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricFaceConstants;
import android.hardware.biometrics.common.ICancellationSignal;
import android.hardware.biometrics.face.EnrollmentType;
import android.hardware.biometrics.face.FaceEnrollOptions;
import android.hardware.biometrics.face.Feature;
import android.hardware.biometrics.face.IFace;
import android.hardware.common.NativeHandle;
import android.hardware.face.Face;
import android.hardware.face.FaceEnrollFrame;
import android.hardware.face.FaceManager;
import android.hardware.keymaster.HardwareAuthToken;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.R;
import com.android.server.biometrics.HardwareAuthTokenUtils;
import com.android.server.biometrics.Utils;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.log.OperationContextExt;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricNotificationUtils;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.ClientMonitorCompositeCallback;
import com.android.server.biometrics.sensors.EnrollClient;
import com.android.server.biometrics.sensors.face.FaceService;
import com.android.server.biometrics.sensors.face.FaceUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Face-specific enroll client for the {@link IFace} AIDL HAL interface.
 */
public class FaceEnrollClient extends EnrollClient<AidlSession> {

    private static final String TAG = "FaceEnrollClient";

    @NonNull private final int[] mEnrollIgnoreList;
    @NonNull private final int[] mEnrollIgnoreListVendor;
    @NonNull private final int[] mDisabledFeatures;
    @Nullable private final Surface mPreviewSurface;
    @Nullable private android.os.NativeHandle mOsPreviewHandle;
    @Nullable private NativeHandle mHwPreviewHandle;
    @Nullable private ICancellationSignal mCancellationSignal;
    private final int mMaxTemplatesPerUser;
    private final boolean mDebugConsent;

    private final ClientMonitorCallback mPreviewHandleDeleterCallback =
            new ClientMonitorCallback() {
                @Override
                public void onClientStarted(@NonNull BaseClientMonitor clientMonitor) {
                }

                @Override
                public void onClientFinished(@NonNull BaseClientMonitor clientMonitor,
                        boolean success) {
                    releaseSurfaceHandlesIfNeeded();
                }
            };

    public FaceEnrollClient(@NonNull Context context, @NonNull Supplier<AidlSession> lazyDaemon,
            @NonNull IBinder token, @NonNull ClientMonitorCallbackConverter listener, int userId,
            @NonNull byte[] hardwareAuthToken, @NonNull String opPackageName, long requestId,
            @NonNull BiometricUtils<Face> utils, @NonNull int[] disabledFeatures, int timeoutSec,
            @Nullable Surface previewSurface, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            int maxTemplatesPerUser, boolean debugConsent) {
        super(context, lazyDaemon, token, listener, userId, hardwareAuthToken, opPackageName, utils,
                timeoutSec, sensorId, false /* shouldVibrate */, logger, biometricContext);
        setRequestId(requestId);
        mEnrollIgnoreList = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_enroll_ignorelist);
        mEnrollIgnoreListVendor = getContext().getResources()
                .getIntArray(R.array.config_face_acquire_vendor_enroll_ignorelist);
        mMaxTemplatesPerUser = maxTemplatesPerUser;
        mDebugConsent = debugConsent;
        mDisabledFeatures = disabledFeatures;
        mPreviewSurface = previewSurface;
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        BiometricNotificationUtils.cancelFaceEnrollNotification(getContext());
        BiometricNotificationUtils.cancelFaceReEnrollNotification(getContext());
    }

    @NonNull
    @Override
    protected ClientMonitorCallback wrapCallbackForStart(@NonNull ClientMonitorCallback callback) {
        return new ClientMonitorCompositeCallback(mPreviewHandleDeleterCallback,
                getLogger().getAmbientLightProbe(true /* startWithClient */), callback);
    }

    @Override
    protected boolean hasReachedEnrollmentLimit() {
        return FaceUtils.getInstance(getSensorId()).getBiometricsForUser(getContext(),
                getTargetUserId()).size() >= mMaxTemplatesPerUser;
    }

    private boolean shouldSendAcquiredMessage(int acquireInfo, int vendorCode) {
        return acquireInfo == FaceManager.FACE_ACQUIRED_VENDOR
                ? !Utils.listContains(mEnrollIgnoreListVendor, vendorCode)
                : !Utils.listContains(mEnrollIgnoreList, acquireInfo);
    }

    @Override
    public void onAcquired(int acquireInfo, int vendorCode) {
        final boolean shouldSend = shouldSendAcquiredMessage(acquireInfo, vendorCode);
        onAcquiredInternal(acquireInfo, vendorCode, shouldSend);
    }

    /**
     * Called each time a new frame is received during face enrollment.
     *
     * @param frame Information about the current frame.
     */
    public void onEnrollmentFrame(@NonNull FaceEnrollFrame frame) {
        // Log acquisition but don't send it to the client yet, since that's handled below.
        final int acquireInfo = frame.getData().getAcquiredInfo();
        final int vendorCode = frame.getData().getVendorCode();
        onAcquiredInternal(acquireInfo, vendorCode, false /* shouldSend */);

        final boolean shouldSend = shouldSendAcquiredMessage(acquireInfo, vendorCode);
        if (shouldSend && getListener() != null) {
            try {
                getListener().onEnrollmentFrame(frame);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to send enrollment frame", e);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    @Override
    protected void startHalOperation() {
        obtainSurfaceHandlesIfNeeded();
        try {
            List<Byte> featureList = new ArrayList<Byte>();
            if (mDebugConsent) {
                featureList.add(Feature.DEBUG);
            }

            boolean shouldAddDiversePoses = true;
            for (int disabledFeature : mDisabledFeatures) {
                if (AidlConversionUtils.convertFrameworkToAidlFeature(disabledFeature)
                        == Feature.REQUIRE_DIVERSE_POSES) {
                    shouldAddDiversePoses = false;
                }
            }

            if (shouldAddDiversePoses) {
                featureList.add(Feature.REQUIRE_DIVERSE_POSES);
            }

            final byte[] features = new byte[featureList.size()];
            for (int i = 0; i < featureList.size(); i++) {
                features[i] = featureList.get(i);
            }

            mCancellationSignal = doEnroll(features);
        } catch (RemoteException | IllegalArgumentException e) {
            Slog.e(TAG, "Exception when requesting enroll", e);
            onError(BiometricFaceConstants.FACE_ERROR_UNABLE_TO_PROCESS, 0 /* vendorCode */);
            mCallback.onClientFinished(this, false /* success */);
        }
    }

    private ICancellationSignal doEnroll(byte[] features) throws RemoteException {
        final AidlSession session = getFreshDaemon();
        final HardwareAuthToken hat =
                HardwareAuthTokenUtils.toHardwareAuthToken(mHardwareAuthToken);

        if (session.hasContextMethods()) {
            final OperationContextExt opContext = getOperationContext();
            ICancellationSignal cancel;
            if (session.supportsFaceEnrollOptions()) {
                FaceEnrollOptions options = new FaceEnrollOptions();
                options.hardwareAuthToken = hat;
                options.enrollmentType = EnrollmentType.DEFAULT;
                options.features = features;
                options.nativeHandlePreview = null;
                options.context = opContext.toAidlContext();
                options.surfacePreview = mPreviewSurface;
                cancel = session.getSession().enrollWithOptions(options);
            } else {
                cancel = session.getSession().enrollWithContext(
                        hat, EnrollmentType.DEFAULT, features, mHwPreviewHandle,
                        opContext.toAidlContext());
            }
            getBiometricContext().subscribe(opContext, ctx -> {
                try {
                    session.getSession().onContextChanged(ctx);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Unable to notify context changed", e);
                }
            });
            return cancel;
        } else {
            return session.getSession().enroll(hat, EnrollmentType.DEFAULT, features,
                    mHwPreviewHandle);
        }
    }

    @Override
    protected void stopHalOperation() {
        unsubscribeBiometricContext();

        if (mCancellationSignal != null) {
            try {
                mCancellationSignal.cancel();
            } catch (RemoteException e) {
                Slog.e(TAG, "Remote exception when requesting cancel", e);
                onError(BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE, 0 /* vendorCode */);
                mCallback.onClientFinished(this, false /* success */);
            }
        }
    }

    private void obtainSurfaceHandlesIfNeeded() {
        if (mPreviewSurface != null) {
            // There is no direct way to convert Surface to android.hardware.common.NativeHandle. We
            // first convert Surface to android.os.NativeHandle, and then android.os.NativeHandle to
            // android.hardware.common.NativeHandle, which can be passed to the HAL.
            // The resources for both handles must be explicitly freed to avoid memory leaks.
            mOsPreviewHandle = FaceService.acquireSurfaceHandle(mPreviewSurface);
            try {
                // We must manually free up the resources for both handles after they are no longer
                // needed. mHwPreviewHandle must be closed, but mOsPreviewHandle must be released
                // through FaceService.
                mHwPreviewHandle = AidlNativeHandleUtils.dup(mOsPreviewHandle);
                Slog.v(TAG, "Obtained handles for the preview surface.");
            } catch (IOException e) {
                mHwPreviewHandle = null;
                Slog.e(TAG, "Failed to dup mOsPreviewHandle", e);
            }
        }
    }

    private void releaseSurfaceHandlesIfNeeded() {
        if (mPreviewSurface != null && mHwPreviewHandle == null) {
            Slog.w(TAG, "mHwPreviewHandle is null even though mPreviewSurface is not null.");
        }
        if (mHwPreviewHandle != null) {
            try {
                Slog.v(TAG, "Closing mHwPreviewHandle");
                AidlNativeHandleUtils.close(mHwPreviewHandle);
            } catch (IOException e) {
                Slog.e(TAG, "Failed to close mPreviewSurface", e);
            }
            mHwPreviewHandle = null;
        }
        if (mOsPreviewHandle != null) {
            Slog.v(TAG, "Releasing mOsPreviewHandle");
            FaceService.releaseSurfaceHandle(mOsPreviewHandle);
            mOsPreviewHandle = null;
        }
        if (mPreviewSurface != null) {
            Slog.v(TAG, "Releasing mPreviewSurface");
            // We need to manually release this surface because it's a copy of the original surface
            // that was sent to us by an app (e.g. Settings). The app cleans up its own surface (as
            // part of the SurfaceView lifecycle, for example), but there is no mechanism in place
            // that will clean up this copy.
            // If this copy isn't cleaned up, it will eventually be garbage collected. However, this
            // surface could be holding onto the native buffers that the GC is not aware of,
            // exhausting the native memory before the GC feels the need to garbage collect.
            mPreviewSurface.release();
        }
    }
}
