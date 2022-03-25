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

package com.android.server.rotationresolver;

import static android.provider.DeviceConfig.NAMESPACE_ROTATION_RESOLVER;
import static android.service.rotationresolver.RotationResolverService.ROTATION_RESULT_FAILURE_CANCELLED;
import static android.service.rotationresolver.RotationResolverService.ROTATION_RESULT_FAILURE_NOT_SUPPORTED;
import static android.service.rotationresolver.RotationResolverService.ROTATION_RESULT_FAILURE_PREEMPTED;
import static android.service.rotationresolver.RotationResolverService.ROTATION_RESULT_FAILURE_TIMED_OUT;

import static com.android.internal.util.FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_0;
import static com.android.internal.util.FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_180;
import static com.android.internal.util.FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_270;
import static com.android.internal.util.FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_90;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.SensorPrivacyManager;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.rotationresolver.RotationResolverInternal;
import android.service.rotationresolver.RotationResolutionRequest;
import android.service.rotationresolver.RotationResolverService;
import android.text.TextUtils;
import android.util.Slog;
import android.view.Surface;

import com.android.internal.R;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.SystemService;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;
import java.util.Set;

/**
 * A manager service for rotation resolver service that runs in System Server process.
 * This service publishes a LocalService and reroutes calls to a {@link
 * android.service.rotationresolver.RotationResolverService} that it manages.
 */
public class RotationResolverManagerService extends
        AbstractMasterSystemService<RotationResolverManagerService,
                RotationResolverManagerPerUserService> {

    private static final String TAG = RotationResolverManagerService.class.getSimpleName();

    private static final String KEY_SERVICE_ENABLED = "service_enabled";

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;

    static final int ORIENTATION_UNKNOWN =
            FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__UNKNOWN;
    static final int RESOLUTION_DISABLED =
            FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__DISABLED;
    static final int RESOLUTION_UNAVAILABLE =
            FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__UNAVAILABLE;
    static final int RESOLUTION_FAILURE =
            FrameworkStatsLog.AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__FAILURE;

    private final Context mContext;
    private final SensorPrivacyManager mPrivacyManager;
    boolean mIsServiceEnabled;

    public RotationResolverManagerService(Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(
                        context,
                        R.string.config_defaultRotationResolverService), /*disallowProperty=*/null,
                PACKAGE_UPDATE_POLICY_REFRESH_EAGER
                        | /*To avoid high rotation latency*/ PACKAGE_RESTART_POLICY_REFRESH_EAGER);
        mContext = context;
        mPrivacyManager = SensorPrivacyManager.getInstance(context);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_ROTATION_RESOLVER,
                    getContext().getMainExecutor(),
                    (properties) -> onDeviceConfigChange(properties.getKeyset()));

            mIsServiceEnabled = DeviceConfig.getBoolean(NAMESPACE_ROTATION_RESOLVER,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        if (keys.contains(KEY_SERVICE_ENABLED)) {
            mIsServiceEnabled = DeviceConfig.getBoolean(NAMESPACE_ROTATION_RESOLVER,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.ROTATION_RESOLVER_SERVICE, new BinderService());
        publishLocalService(RotationResolverInternal.class, new LocalService());
    }

    @Override
    public RotationResolverManagerPerUserService newServiceLocked(
            @UserIdInt int resolvedUserId,
            @SuppressWarnings("unused") boolean disabled) {
        return new RotationResolverManagerPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    protected void onServiceRemoved(
            RotationResolverManagerPerUserService service, @UserIdInt int userId) {
        synchronized (mLock) {
            service.destroyLocked();
        }
    }

    /** Returns {@code true} if rotation resolver service is configured on this device. */
    public static boolean isServiceConfigured(Context context) {
        return !TextUtils.isEmpty(getServiceConfigPackage(context));
    }

    ComponentName getComponentNameShellCommand(@UserIdInt int userId) {
        synchronized (mLock) {
            final RotationResolverManagerPerUserService service = getServiceForUserLocked(userId);
            if (service != null) {
                return service.getComponentName();
            }
        }
        return null;
    }

    void resolveRotationShellCommand(@UserIdInt int userId,
            RotationResolverInternal.RotationResolverCallbackInternal callbackInternal,
            RotationResolutionRequest request) {
        synchronized (mLock) {
            final RotationResolverManagerPerUserService service = getServiceForUserLocked(userId);
            if (service != null) {
                service.resolveRotationLocked(callbackInternal, request, new CancellationSignal());
            } else {
                Slog.i(TAG, "service not available for user_id: " + userId);
            }
        }
    }


    static String getServiceConfigPackage(Context context) {
        return context.getPackageManager().getRotationResolverPackageName();
    }

    private final class LocalService extends RotationResolverInternal {
        @Override
        public boolean isRotationResolverSupported() {
            synchronized (mLock) {
                return mIsServiceEnabled;
            }
        }

        @Override
        public void resolveRotation(
                @NonNull RotationResolverCallbackInternal callbackInternal, String packageName,
                int proposedRotation, int currentRotation, long timeout,
                @NonNull CancellationSignal cancellationSignalInternal) {
            Objects.requireNonNull(callbackInternal);
            Objects.requireNonNull(cancellationSignalInternal);
            synchronized (mLock) {
                final boolean isCameraAvailable = !mPrivacyManager.isSensorPrivacyEnabled(
                        SensorPrivacyManager.Sensors.CAMERA);
                if (mIsServiceEnabled && isCameraAvailable) {
                    final RotationResolverManagerPerUserService service =
                            getServiceForUserLocked(
                                    UserHandle.getCallingUserId());
                    final RotationResolutionRequest request;
                    if (packageName == null) {
                        request = new RotationResolutionRequest(/* packageName */ "",
                                currentRotation, proposedRotation, /* shouldUseCamera */ true,
                                timeout);
                    } else {
                        request = new RotationResolutionRequest(packageName, currentRotation,
                                proposedRotation, /* shouldUseCamera */ true, timeout);
                    }

                    service.resolveRotationLocked(callbackInternal, request,
                            cancellationSignalInternal);
                } else {
                    if (isCameraAvailable) {
                        Slog.w(TAG, "Rotation Resolver service is disabled.");
                    } else {
                        Slog.w(TAG, "Camera is locked by a toggle.");
                    }
                    callbackInternal.onFailure(ROTATION_RESULT_FAILURE_CANCELLED);
                    logRotationStats(proposedRotation, currentRotation, RESOLUTION_DISABLED);
                }
            }
        }
    }

    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) {
                return;
            }
            synchronized (mLock) {
                dumpLocked("", pw);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err,
                String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROTATION_RESOLVER,
                    TAG);
            new RotationResolverShellCommand(RotationResolverManagerService.this).exec(this, in,
                    out, err, args, callback,
                    resultReceiver);
        }
    }

    static void logRotationStatsWithTimeToCalculate(int proposedRotation, int currentRotation,
            int result, long timeToCalculate) {
        FrameworkStatsLog.write(FrameworkStatsLog.AUTO_ROTATE_REPORTED,
                /* previous_orientation= */ surfaceRotationToProto(currentRotation),
                /* proposed_orientation= */ surfaceRotationToProto(proposedRotation),
                result,
                /* process_duration_millis= */ timeToCalculate);
    }

    static void logRotationStats(int proposedRotation, int currentRotation,
            int result) {
        FrameworkStatsLog.write(FrameworkStatsLog.AUTO_ROTATE_REPORTED,
                /* previous_orientation= */ surfaceRotationToProto(currentRotation),
                /* proposed_orientation= */ surfaceRotationToProto(proposedRotation),
                result);
    }

    static int errorCodeToProto(@RotationResolverService.FailureCodes int error) {
        switch (error) {
            case ROTATION_RESULT_FAILURE_NOT_SUPPORTED:
                return RESOLUTION_UNAVAILABLE;
            case ROTATION_RESULT_FAILURE_TIMED_OUT:
            case ROTATION_RESULT_FAILURE_PREEMPTED:
            case ROTATION_RESULT_FAILURE_CANCELLED:
                return ORIENTATION_UNKNOWN;
            default:
                return RESOLUTION_FAILURE;
        }
    }

    static int surfaceRotationToProto(@Surface.Rotation int rotationPoseResult) {
        switch (rotationPoseResult) {
            case Surface.ROTATION_0:
                return AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_0;
            case Surface.ROTATION_90:
                return AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_90;
            case Surface.ROTATION_180:
                return AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_180;
            case Surface.ROTATION_270:
                return AUTO_ROTATE_REPORTED__PROPOSED_ORIENTATION__ROTATION_270;
            default:
                // Should not reach here.
                return RESOLUTION_FAILURE;
        }
    }
}
