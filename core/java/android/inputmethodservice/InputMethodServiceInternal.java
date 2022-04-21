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

package android.inputmethodservice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A set of internal methods exposed by {@link InputMethodService} to be called only from other
 * framework classes for internal use.
 *
 * <p>CAVEATS: {@link AbstractInputMethodService} does not support all the methods here.</p>
 */
interface InputMethodServiceInternal {
    /**
     * @return {@link Context} associated with the service.
     */
    @NonNull
    Context getContext();

    /**
     * Allow the receiver of {@link InputContentInfo} to obtain a temporary read-only access
     * permission to the content.
     *
     * @param inputContentInfo Content to be temporarily exposed from the input method to the
     *                         application. This cannot be {@code null}.
     * @param inputConnection {@link InputConnection} with which
     *                        {@link InputConnection#commitContent(InputContentInfo, int, Bundle)}
     *                        will be called.
     */
    default void exposeContent(@NonNull InputContentInfo inputContentInfo,
            @NonNull InputConnection inputConnection) {
    }

    /**
     * Called when the user took some actions that should be taken into consideration to update the
     * MRU list for input method rotation.
     */
    default void notifyUserActionIfNecessary() {
    }

    /**
     * Called when the system is asking the IME to dump its information for debugging.
     *
     * <p>The caller is responsible for checking {@link android.Manifest.permission.DUMP}.</p>
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param fout The file to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    default void dump(@SuppressLint("UseParcelFileDescriptor") @NonNull FileDescriptor fd,
            @NonNull PrintWriter fout, @NonNull String[] args) {
    }

    /**
     * Called with {@link com.android.internal.inputmethod.ImeTracing#triggerServiceDump(String,
     * com.android.internal.inputmethod.ImeTracing.ServiceDumper, byte[])} needs to be triggered
     * with the given parameters.
     *
     * @param where {@code where} parameter to be passed.
     * @param icProto {@code icProto} parameter to be passed.
     */
    default void triggerServiceDump(@NonNull String where, @Nullable byte[] icProto) {
    }
}
