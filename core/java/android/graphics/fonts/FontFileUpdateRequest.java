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

package android.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.ParcelFileDescriptor;

import java.util.Objects;

/**
 * Request for updating a font file on the system.
 *
 * @hide
 */
@SystemApi
public final class FontFileUpdateRequest {

    private final ParcelFileDescriptor mParcelFileDescriptor;
    private final byte[] mSignature;

    /**
     * Creates a FontFileUpdateRequest with the given file and signature.
     *
     * @param parcelFileDescriptor A file descriptor of the font file.
     * @param signature            A PKCS#7 detached signature for verifying the font file.
     */
    public FontFileUpdateRequest(@NonNull ParcelFileDescriptor parcelFileDescriptor,
            @NonNull byte[] signature) {
        Objects.requireNonNull(parcelFileDescriptor);
        Objects.requireNonNull(signature);
        mParcelFileDescriptor = parcelFileDescriptor;
        mSignature = signature;
    }

    /**
     * Returns the file descriptor of the font file.
     */
    @NonNull
    public ParcelFileDescriptor getParcelFileDescriptor() {
        return mParcelFileDescriptor;
    }

    /**
     * Returns the PKCS#7 detached signature for verifying the font file.
     */
    @NonNull
    public byte[] getSignature() {
        return mSignature;
    }
}
