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

package android.view.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.CancellationSignal;
import android.service.autofill.FillCallback;
import android.view.inputmethod.InlineSuggestionsRequest;

/**
 * <p>This class is used to provide some input suggestions to the Autofill framework.
 *
 * <P>When the user is requested to input something, Autofill will try to query input suggestions
 * for the user choosing. If the application want to provide some internal input suggestions,
 * implements this callback and register via
 * {@link AutofillManager#setAutofillRequestCallback(java.util.concurrent.Executor,
 * AutofillRequestCallback)}. Autofill will callback the
 * {@link #onFillRequest(InlineSuggestionsRequest, CancellationSignal, FillCallback)} to request
 * input suggestions.
 *
 * <P>To make sure the callback to take effect, must register before the autofill session starts.
 * If the autofill session is started, calls {@link AutofillManager#cancel()} to finish current
 * session, and then the callback will be used at the next restarted session.
 *
 * <P>To create a {@link android.service.autofill.FillResponse}, application should fetch
 * {@link AutofillId}s from its view structure. Below is an example:
 * <pre class="prettyprint">
 * AutofillId usernameId = findViewById(R.id.username).getAutofillId();
 * AutofillId passwordId = findViewById(R.id.password).getAutofillId();
 * </pre>
 * To learn more about creating a {@link android.service.autofill.FillResponse}, read
 * <a href="/guide/topics/text/autofill-services#fill">Fill out client views</a>.
 *
 * <P>To fallback to the default {@link android.service.autofill.AutofillService}, just respond
 * a null of the {@link android.service.autofill.FillResponse}. And then Autofill will do a fill
 * request with the default {@link android.service.autofill.AutofillService}. Or clear the callback
 * from {@link AutofillManager} via {@link AutofillManager#clearAutofillRequestCallback()}. If the
 * client would like to keep no suggestions for the field, respond with an empty
 * {@link android.service.autofill.FillResponse} which has no dataset.
 *
 * <P>IMPORTANT: This should not be used for displaying anything other than input suggestions, or
 * the keyboard may choose to block your app from the inline strip.
 */
public interface AutofillRequestCallback {
    /**
     * Called by the Android system to decide if a screen can be autofilled by the callback.
     *
     * @param inlineSuggestionsRequest the {@link InlineSuggestionsRequest request} to handle if
     *     currently inline suggestions are supported and can be displayed.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     *     this to notify you that the fill result is no longer needed and you should stop
     *     handling this fill request in order to save resources.
     * @param callback object used to notify the result of the request.
     */
    void onFillRequest(@Nullable InlineSuggestionsRequest inlineSuggestionsRequest,
            @NonNull CancellationSignal cancellationSignal, @NonNull FillCallback callback);
}
