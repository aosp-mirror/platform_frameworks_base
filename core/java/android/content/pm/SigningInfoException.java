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

package android.content.pm;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Indicates an error when verifying the
 * <a href="https://source.android.com/docs/security/features/apksigning">app signing</a>
 * information.
 */
@FlaggedApi(Flags.FLAG_CLOUD_COMPILATION_PM)
public class SigningInfoException extends Exception {
    private final int mCode;

    /** @hide */
    public SigningInfoException(int code, @NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
        mCode = code;
    }

    /**
     * Returns a code representing the cause, in one of the installation parse return codes in
     * {@link PackageManager}.
     */
    @FlaggedApi(Flags.FLAG_CLOUD_COMPILATION_PM)
    public int getCode() {
        return mCode;
    }
}
