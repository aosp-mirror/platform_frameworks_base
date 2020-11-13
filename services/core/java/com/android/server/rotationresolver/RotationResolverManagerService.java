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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.rotationresolver.RotationResolverInternal;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.DumpUtils;
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
    private static final boolean DEFAULT_SERVICE_ENABLED = false;

    private final Context mContext;
    boolean mIsServiceEnabled;

    public RotationResolverManagerService(Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(
                        context,
                        R.string.config_defaultRotationResolverService), /*disallowProperty=*/null,
                PACKAGE_UPDATE_POLICY_REFRESH_EAGER
                        | /*To avoid high rotation latency*/ PACKAGE_RESTART_POLICY_REFRESH_EAGER);
        mContext = context;
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
                @NonNull RotationResolverCallbackInternal callbackInternal, int proposedRotation,
                int currentRotation, String packageName, long timeout,
                @NonNull CancellationSignal cancellationSignalInternal) {
            Objects.requireNonNull(callbackInternal);
            Objects.requireNonNull(cancellationSignalInternal);
            synchronized (mLock) {
                if (mIsServiceEnabled) {
                    final RotationResolverManagerPerUserService service = getServiceForUserLocked(
                            UserHandle.getCallingUserId());
                    service.resolveRotationLocked(callbackInternal, proposedRotation,
                            currentRotation, packageName, timeout, cancellationSignalInternal);
                } else {
                    Slog.w(TAG, "Rotation Resolver service is disabled.");
                    callbackInternal.onFailure(ROTATION_RESULT_FAILURE_CANCELLED);
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
                final RotationResolverManagerPerUserService service = getServiceForUserLocked(
                        UserHandle.getCallingUserId());
                service.dumpInternal(new IndentingPrintWriter(pw, "  "));
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                FileDescriptor err,
                String[] args, ShellCallback callback,
                ResultReceiver resultReceiver) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.MANAGE_ROTATION_RESOLVER,
                    TAG);
            final RotationResolverManagerPerUserService service = getServiceForUserLocked(
                    UserHandle.getCallingUserId());
            new RotationResolverShellCommend(service).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }
    }
}
