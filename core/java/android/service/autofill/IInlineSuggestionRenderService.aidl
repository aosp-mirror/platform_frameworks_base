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

package android.service.autofill;

import android.os.IBinder;
import android.os.RemoteCallback;
import android.service.autofill.IInlineSuggestionUiCallback;
import android.service.autofill.InlinePresentation;

/**
 * Interface from system to the inline suggestion render service.
 *
 * @hide
 */
oneway interface IInlineSuggestionRenderService {
    void renderSuggestion(in IInlineSuggestionUiCallback callback,
                          in InlinePresentation presentation, int width, int height,
                          in IBinder hostInputToken, int displayId, int userId, int sessionId);
    void getInlineSuggestionsRendererInfo(in RemoteCallback callback);

    /**
     * Releases the inline suggestion SurfaceControlViewHosts hosted in the service, for the
     * provided userId and sessionId.
     */
    void destroySuggestionViews(int userId, int sessionId);
}
