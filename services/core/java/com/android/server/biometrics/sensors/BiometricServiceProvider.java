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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.biometrics.SensorPropertiesInternal;
import android.util.proto.ProtoOutputStream;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Common attributes for all biometric service providers.
 *
 * @param <T> Internal settings type.
 */
public interface BiometricServiceProvider<T extends SensorPropertiesInternal> {

    /** Checks if the specified sensor is owned by this provider. */
    boolean containsSensor(int sensorId);

    /** All sensor properties. */
    @NonNull
    List<T> getSensorProperties();

    /** Properties for the given sensor id. */
    @Nullable
    T getSensorProperties(int sensorId);

    boolean isHardwareDetected(int sensorId);

    /** If the user has any enrollments for the given sensor. */
    boolean hasEnrollments(int sensorId, int userId);

    long getAuthenticatorId(int sensorId, int userId);

    @LockoutTracker.LockoutMode
    int getLockoutModeForUser(int sensorId, int userId);

    void dumpProtoState(int sensorId, @NonNull ProtoOutputStream proto,
            boolean clearSchedulerBuffer);

    void dumpProtoMetrics(int sensorId, @NonNull FileDescriptor fd);

    void dumpInternal(int sensorId, @NonNull PrintWriter pw);
}
