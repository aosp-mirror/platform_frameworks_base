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

import android.annotation.Nullable;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * A utility class for the AIDL implementation of NativeHandle - {@link
 * android.hardware.common.NativeHandle}.
 */
public final class AidlNativeHandleUtils {

    /**
     * Converts a {@link android.os.NativeHandle} to a {@link android.hardware.common.NativeHandle}
     * by duplicating the underlying file descriptors.
     *
     * Both the original and new handle must be closed after use.
     *
     * @param handle {@link android.os.NativeHandle}. Can be null.
     * @return a {@link android.hardware.common.NativeHandle} representation of {@code handle}.
     * Returns null if {@code handle} is null.
     * @throws IOException if any of the underlying calls to {@code dup} fail.
     */
    public static @Nullable android.hardware.common.NativeHandle dup(
            @Nullable android.os.NativeHandle handle) throws IOException {
        if (handle == null) {
            return null;
        }
        android.hardware.common.NativeHandle res = new android.hardware.common.NativeHandle();
        final FileDescriptor[] fds = handle.getFileDescriptors();
        res.ints = handle.getInts().clone();
        res.fds = new ParcelFileDescriptor[fds.length];
        for (int i = 0; i < fds.length; ++i) {
            res.fds[i] = ParcelFileDescriptor.dup(fds[i]);
        }
        return res;
    }

    /**
     * Closes the file descriptors contained within a {@link android.hardware.common.NativeHandle}.
     * This is a no-op if the handle is null.
     *
     * This should only be used for handles that own their file descriptors, for example handles
     * obtained using {@link #dup(android.os.NativeHandle)}.
     *
     * @param handle {@link android.hardware.common.NativeHandle}. Can be null.
     * @throws IOException if any of the underlying calls to {@code close} fail.
     */
    public static void close(@Nullable android.hardware.common.NativeHandle handle)
            throws IOException {
        if (handle != null) {
            for (ParcelFileDescriptor fd : handle.fds) {
                if (fd != null) {
                    fd.close();
                }
            }
        }
    }
}
