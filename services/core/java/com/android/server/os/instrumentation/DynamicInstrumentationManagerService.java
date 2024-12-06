/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.os.instrumentation;

import static android.Manifest.permission.DYNAMIC_INSTRUMENTATION;
import static android.content.Context.DYNAMIC_INSTRUMENTATION_SERVICE;

import android.annotation.NonNull;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresPermission;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.os.RemoteException;
import android.os.instrumentation.ExecutableMethodFileOffsets;
import android.os.instrumentation.IDynamicInstrumentationManager;
import android.os.instrumentation.IOffsetCallback;
import android.os.instrumentation.MethodDescriptor;
import android.os.instrumentation.MethodDescriptorParser;
import android.os.instrumentation.TargetProcess;


import com.android.server.LocalServices;
import com.android.server.SystemService;

import dalvik.system.VMDebug;

import java.lang.reflect.Method;
import java.util.NoSuchElementException;
import java.util.Objects;


/**
 * System private implementation of the {@link IDynamicInstrumentationManager interface}.
 */
public class DynamicInstrumentationManagerService extends SystemService {

    private ActivityManagerInternal mAmInternal;

    public DynamicInstrumentationManagerService(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        publishBinderService(DYNAMIC_INSTRUMENTATION_SERVICE, new BinderService());
    }

    private final class BinderService extends IDynamicInstrumentationManager.Stub {
        @Override
        @PermissionManuallyEnforced
        @RequiresPermission(value = android.Manifest.permission.DYNAMIC_INSTRUMENTATION)
        public void getExecutableMethodFileOffsets(
                @NonNull TargetProcess targetProcess, @NonNull MethodDescriptor methodDescriptor,
                @NonNull IOffsetCallback callback) {
            if (!com.android.art.flags.Flags.executableMethodFileOffsets()) {
                throw new UnsupportedOperationException();
            }
            getContext().enforceCallingOrSelfPermission(
                    DYNAMIC_INSTRUMENTATION, "Caller must have DYNAMIC_INSTRUMENTATION permission");
            Objects.requireNonNull(targetProcess.processName);

            if (!targetProcess.processName.equals("system_server")) {
                try {
                    mAmInternal.getExecutableMethodFileOffsets(targetProcess.processName,
                            targetProcess.pid, targetProcess.uid, methodDescriptor,
                            new IOffsetCallback.Stub() {
                                @Override
                                public void onResult(ExecutableMethodFileOffsets result) {
                                    try {
                                        callback.onResult(result);
                                    } catch (RemoteException e) {
                                        /* ignore */
                                    }
                                }
                            });
                    return;
                } catch (NoSuchElementException e) {
                    throw new IllegalArgumentException(
                            "The specified app process cannot be found." , e);
                }
            }

            Method method = MethodDescriptorParser.parseMethodDescriptor(
                    getClass().getClassLoader(), methodDescriptor);
            VMDebug.ExecutableMethodFileOffsets location =
                    VMDebug.getExecutableMethodFileOffsets(method);

            try {
                if (location == null) {
                    callback.onResult(null);
                    return;
                }

                ExecutableMethodFileOffsets ret = new ExecutableMethodFileOffsets();
                ret.containerPath = location.getContainerPath();
                ret.containerOffset = location.getContainerOffset();
                ret.methodOffset = location.getMethodOffset();
                callback.onResult(ret);
            } catch (RemoteException e) {
                throw new RuntimeException("Failed to invoke result callback", e);
            }
        }
    }
}
