/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.CancellationSignal;
import android.os.CancellationSignalBeamer;
import android.os.IBinder;

/**
 * A {@link HandwritingGesture} that can be {@link CancellationSignal#cancel() cancelled}.
 * @hide
 */
@TestApi
public abstract class CancellableHandwritingGesture extends HandwritingGesture {
    @NonNull
    CancellationSignal mCancellationSignal;

    @Nullable
    IBinder mCancellationSignalToken;


    /**
     * Set {@link CancellationSignal} for testing only.
     * @hide
     */
    @TestApi
    public void setCancellationSignal(@NonNull CancellationSignal cancellationSignal) {
        mCancellationSignal = cancellationSignal;
    }

    @NonNull
    CancellationSignal getCancellationSignal() {
        return mCancellationSignal;
    }

    /**
     * Unbeam cancellation token.
     * @hide
     */
    public void unbeamCancellationSignal(@NonNull CancellationSignalBeamer.Receiver receiver) {
        if (mCancellationSignalToken != null) {
            mCancellationSignal = receiver.unbeam(mCancellationSignalToken);
            mCancellationSignalToken = null;
        }
    }

}
