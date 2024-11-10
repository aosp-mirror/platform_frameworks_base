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
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.content.Context;
import android.os.instrumentation.ExecutableMethodFileOffsets;
import android.os.instrumentation.IDynamicInstrumentationManager;
import android.os.instrumentation.MethodDescriptor;
import android.os.instrumentation.TargetProcess;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import dalvik.system.VMDebug;

import java.lang.reflect.Method;

/**
 * System private implementation of the {@link IDynamicInstrumentationManager interface}.
 */
public class DynamicInstrumentationManagerService extends SystemService {
    public DynamicInstrumentationManagerService(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(DYNAMIC_INSTRUMENTATION_SERVICE, new BinderService());
    }

    private final class BinderService extends IDynamicInstrumentationManager.Stub {
        @Override
        @PermissionManuallyEnforced
        public @Nullable ExecutableMethodFileOffsets getExecutableMethodFileOffsets(
                @NonNull TargetProcess targetProcess, @NonNull MethodDescriptor methodDescriptor) {
            if (!com.android.art.flags.Flags.executableMethodFileOffsets()) {
                throw new UnsupportedOperationException();
            }
            getContext().enforceCallingOrSelfPermission(
                    DYNAMIC_INSTRUMENTATION, "Caller must have DYNAMIC_INSTRUMENTATION permission");

            if (targetProcess.processName == null
                    || !targetProcess.processName.equals("system_server")) {
                throw new UnsupportedOperationException(
                        "system_server is the only supported target process");
            }

            Method method = parseMethodDescriptor(
                    getClass().getClassLoader(), methodDescriptor);
            VMDebug.ExecutableMethodFileOffsets location =
                    VMDebug.getExecutableMethodFileOffsets(method);

            if (location == null) {
                return null;
            }

            ExecutableMethodFileOffsets ret = new ExecutableMethodFileOffsets();
            ret.containerPath = location.getContainerPath();
            ret.containerOffset = location.getContainerOffset();
            ret.methodOffset = location.getMethodOffset();
            return ret;
        }
    }

    @VisibleForTesting
    static Method parseMethodDescriptor(ClassLoader classLoader,
            @NonNull MethodDescriptor descriptor) {
        try {
            Class<?> javaClass = classLoader.loadClass(descriptor.fullyQualifiedClassName);
            Class<?>[] parameters = new Class[descriptor.fullyQualifiedParameters.length];
            for (int i = 0; i < descriptor.fullyQualifiedParameters.length; i++) {
                String typeName = descriptor.fullyQualifiedParameters[i];
                boolean isArrayType = typeName.endsWith("[]");
                if (isArrayType) {
                    typeName = typeName.substring(0, typeName.length() - 2);
                }
                switch (typeName) {
                    case "boolean":
                        parameters[i] = isArrayType ? boolean.class.arrayType() : boolean.class;
                        break;
                    case "byte":
                        parameters[i] = isArrayType ? byte.class.arrayType() : byte.class;
                        break;
                    case "char":
                        parameters[i] = isArrayType ? char.class.arrayType() : char.class;
                        break;
                    case "short":
                        parameters[i] = isArrayType ? short.class.arrayType() : short.class;
                        break;
                    case "int":
                        parameters[i] = isArrayType ? int.class.arrayType() : int.class;
                        break;
                    case "long":
                        parameters[i] = isArrayType ? long.class.arrayType() : long.class;
                        break;
                    case "float":
                        parameters[i] = isArrayType ? float.class.arrayType() : float.class;
                        break;
                    case "double":
                        parameters[i] = isArrayType ? double.class.arrayType() : double.class;
                        break;
                    default:
                        parameters[i] = isArrayType ? classLoader.loadClass(typeName).arrayType()
                                : classLoader.loadClass(typeName);
                }
            }

            return javaClass.getDeclaredMethod(descriptor.methodName, parameters);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "The specified method cannot be found. Is this descriptor valid? "
                            + descriptor, e);
        }
    }
}
