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

package android.view.inputmethod;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.view.View;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Interface to receive the result of starting a connectionless stylus handwriting session using
 * one of {@link InputMethodManager#startConnectionlessStylusHandwriting(View, CursorAnchorInfo,
 * Executor,ConnectionlessHandwritingCallback)}, {@link
 * InputMethodManager#startConnectionlessStylusHandwritingForDelegation(View, CursorAnchorInfo,
 * Executor, ConnectionlessHandwritingCallback)}, or {@link
 * InputMethodManager#startConnectionlessStylusHandwritingForDelegation(View, CursorAnchorInfo,
 * String, Executor, ConnectionlessHandwritingCallback)}.
 */
@FlaggedApi(Flags.FLAG_CONNECTIONLESS_HANDWRITING)
public interface ConnectionlessHandwritingCallback {

    /** @hide */
    @IntDef(prefix = {"CONNECTIONLESS_HANDWRITING_ERROR_"}, value = {
            CONNECTIONLESS_HANDWRITING_ERROR_NO_TEXT_RECOGNIZED,
            CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED,
            CONNECTIONLESS_HANDWRITING_ERROR_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ConnectionlessHandwritingError {
    }

    /**
     * Error code indicating that the connectionless handwriting session started and completed
     * but no text was recognized.
     */
    int CONNECTIONLESS_HANDWRITING_ERROR_NO_TEXT_RECOGNIZED = 0;

    /**
     * Error code indicating that the connectionless handwriting session was not started as the
     * current IME does not support it.
     */
    int CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED = 1;

    /**
     * Error code for any other reason that the connectionless handwriting session did not complete
     * successfully. Either the session could not start, or the session started but did not complete
     * successfully.
     */
    int CONNECTIONLESS_HANDWRITING_ERROR_OTHER = 2;

    /**
     * Callback when the connectionless handwriting session completed successfully and
     * recognized text.
     */
    void onResult(@NonNull CharSequence text);

    /** Callback when the connectionless handwriting session did not complete successfully. */
    void onError(@ConnectionlessHandwritingError int errorCode);
}
