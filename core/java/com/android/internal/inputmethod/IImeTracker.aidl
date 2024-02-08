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

package com.android.internal.inputmethod;

import android.view.inputmethod.ImeTracker;

/**
 * Interface to the global IME tracker service, used by all client applications.
 * {@hide}
 */
interface IImeTracker {

    /**
     * Called when an IME show request is created.
     *
     * @param tag the logging tag.
     * @param uid the uid of the client that requested the IME.
     * @param origin the origin of the IME show request.
     * @param reason the reason why the IME show request was created.
     * @param fromUser whether this request was created directly from user interaction.
     * @return A new IME tracking token.
     */
    ImeTracker.Token onRequestShow(String tag, int uid, int origin, int reason, boolean fromUser);

    /**
     * Called when an IME hide request is created.
     *
     * @param tag the logging tag.
     * @param uid the uid of the client that requested the IME.
     * @param origin the origin of the IME hide request.
     * @param reason the reason why the IME hide request was created.
     * @param fromUser whether this request was created directly from user interaction.
     * @return A new IME tracking token.
     */
    ImeTracker.Token onRequestHide(String tag, int uid, int origin, int reason, boolean fromUser);

    /**
     * Called when the IME request progresses to a further phase.
     *
     * @param binder the binder of token tracking the current IME request.
     * @param phase the new phase the IME request reached.
     */
    oneway void onProgress(in IBinder binder, int phase);

    /**
     * Called when the IME request fails.
     *
     * @param statsToken the token tracking the current IME request.
     * @param phase the phase the IME request failed at.
     */
    oneway void onFailed(in ImeTracker.Token statsToken, int phase);

    /**
     * Called when the IME request is cancelled.
     *
     * @param statsToken the token tracking the current IME request.
     * @param phase the phase the IME request was cancelled at.
     */
    oneway void onCancelled(in ImeTracker.Token statsToken, int phase);

    /**
     * Called when the IME show request is successful.
     *
     * @param statsToken the token tracking the current IME request.
     */
    oneway void onShown(in ImeTracker.Token statsToken);

    /**
     * Called when the IME hide request is successful.
     *
     * @param statsToken the token tracking the current IME request.
     */
    oneway void onHidden(in ImeTracker.Token statsToken);

    /**
     * Checks whether there are any pending IME visibility requests.
     *
     * @return {@code true} iff there are pending IME visibility requests.
     */
    @EnforcePermission("TEST_INPUT_METHOD")
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(value = "
            + "android.Manifest.permission.TEST_INPUT_METHOD)")
    boolean hasPendingImeVisibilityRequests();
}
