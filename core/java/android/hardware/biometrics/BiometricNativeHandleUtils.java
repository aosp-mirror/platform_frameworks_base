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

package android.hardware.biometrics;

import android.os.NativeHandle;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

/**
 * A class that contains utilities for IBiometricNativeHandle.
 *
 * @hide
 */
public final class BiometricNativeHandleUtils {

    private BiometricNativeHandleUtils() {
    }

    /**
     * Converts a {@link NativeHandle} into an {@link IBiometricNativeHandle} by duplicating the
     * underlying file descriptors.
     *
     * Both the original and new handle must be closed after use.
     *
     * @param h {@link NativeHandle}. Usually used to identify a WindowManager window. Can be null.
     * @return A {@link IBiometricNativeHandle} representation of {@code h}. Will be null if
     * {@code h} or its raw file descriptors are null.
     */
    public static IBiometricNativeHandle dup(NativeHandle h) {
        IBiometricNativeHandle handle = null;
        if (h != null && h.getFileDescriptors() != null && h.getInts() != null) {
            handle = new IBiometricNativeHandle();
            handle.ints = h.getInts().clone();
            handle.fds = new ParcelFileDescriptor[h.getFileDescriptors().length];
            for (int i = 0; i < h.getFileDescriptors().length; ++i) {
                try {
                    handle.fds[i] = ParcelFileDescriptor.dup(h.getFileDescriptors()[i]);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return handle;
    }

    /**
     * Closes the handle's file descriptors.
     *
     * @param h {@link IBiometricNativeHandle} handle.
     */
    public static void close(IBiometricNativeHandle h) {
        if (h != null) {
            for (ParcelFileDescriptor fd : h.fds) {
                if (fd != null) {
                    try {
                        fd.close();
                    } catch (IOException e) {
                        // do nothing.
                    }
                }
            }
        }
    }
}
