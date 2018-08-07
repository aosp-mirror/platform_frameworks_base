/*
 * Copyright 2018 The Android Open Source Project
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

package android.security;

import android.annotation.NonNull;

/**
 * Callback class used when signaling that a prompt is no longer being presented.
 */
public abstract class ConfirmationCallback {
    /**
     * Called when the requested prompt was accepted by the user.
     *
     * The format of 'dataThatWasConfirmed' parameter is a <a href="http://cbor.io/">CBOR</a>
     * encoded map (type 5) with (at least) the keys <strong>prompt</strong> and
     * <strong>extra</strong>. The keys are encoded as CBOR text string (type 3). The value of
     * promptText is encoded as CBOR text string (type 3), and the value of extraData is encoded as
     * CBOR byte string (type 2). Other keys may be added in the future.
     *
     * @param dataThatWasConfirmed the data that was confirmed, see above for the format.
     */
    public void onConfirmed(@NonNull byte[] dataThatWasConfirmed) {}

    /**
     * Called when the requested prompt was dismissed (not accepted) by the user.
     */
    public void onDismissed() {}

    /**
     * Called when the requested prompt was dismissed by the application.
     */
    public void onCanceled() {}

    /**
     * Called when the requested prompt was dismissed because of a low-level error.
     *
     * @param e a throwable representing the error.
     */
    public void onError(Throwable e) {}
}
