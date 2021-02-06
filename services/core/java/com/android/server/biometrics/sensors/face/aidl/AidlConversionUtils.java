/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.hardware.biometrics.face.AuthenticationFrame;
import android.hardware.biometrics.face.BaseFrame;
import android.hardware.biometrics.face.Cell;
import android.hardware.biometrics.face.EnrollmentFrame;
import android.hardware.face.FaceAuthenticationFrame;
import android.hardware.face.FaceDataFrame;
import android.hardware.face.FaceEnrollCell;
import android.hardware.face.FaceEnrollFrame;

/**
 * Utilities for converting between hardware and framework-defined AIDL models.
 */
final class AidlConversionUtils {
    // Prevent instantiation.
    private AidlConversionUtils() {}

    @NonNull
    public static FaceAuthenticationFrame convert(@NonNull AuthenticationFrame frame) {
        return new FaceAuthenticationFrame(convert(frame.data));
    }

    @NonNull
    public static AuthenticationFrame convert(@NonNull FaceAuthenticationFrame frame) {
        final AuthenticationFrame convertedFrame = new AuthenticationFrame();
        convertedFrame.data = convert(frame.getData());
        return convertedFrame;
    }

    @NonNull
    public static FaceEnrollFrame convert(@NonNull EnrollmentFrame frame) {
        return new FaceEnrollFrame(convert(frame.cell), frame.stage, convert(frame.data));
    }

    @NonNull
    public static EnrollmentFrame convert(@NonNull FaceEnrollFrame frame) {
        final EnrollmentFrame convertedFrame = new EnrollmentFrame();
        convertedFrame.cell = convert(frame.getCell());
        convertedFrame.stage = (byte) frame.getStage();
        convertedFrame.data = convert(frame.getData());
        return convertedFrame;
    }

    @NonNull
    public static FaceDataFrame convert(@NonNull BaseFrame frame) {
        return new FaceDataFrame(
                frame.acquiredInfo,
                frame.vendorCode,
                frame.pan,
                frame.tilt,
                frame.distance,
                frame.isCancellable);
    }

    @NonNull
    public static BaseFrame convert(@NonNull FaceDataFrame frame) {
        final BaseFrame convertedFrame = new BaseFrame();
        convertedFrame.acquiredInfo = (byte) frame.getAcquiredInfo();
        convertedFrame.vendorCode = frame.getVendorCode();
        convertedFrame.pan = frame.getPan();
        convertedFrame.tilt = frame.getTilt();
        convertedFrame.distance = frame.getDistance();
        convertedFrame.isCancellable = frame.isCancellable();
        return convertedFrame;
    }

    @Nullable
    public static FaceEnrollCell convert(@Nullable Cell cell) {
        return cell == null ? null : new FaceEnrollCell(cell.x, cell.y, cell.z);
    }

    @Nullable
    public static Cell convert(@Nullable FaceEnrollCell cell) {
        if (cell == null) {
            return null;
        }

        final Cell convertedCell = new Cell();
        convertedCell.x = cell.getX();
        convertedCell.y = cell.getY();
        convertedCell.z = cell.getZ();
        return convertedCell;
    }
}
