/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.app.contentsuggestions;

import android.app.contentsuggestions.IClassificationsCallback;
import android.app.contentsuggestions.ISelectionsCallback;
import android.app.contentsuggestions.ClassificationsRequest;
import android.app.contentsuggestions.SelectionsRequest;
import android.os.Bundle;

/** @hide */
oneway interface IContentSuggestionsManager {
    void provideContextImage(
            int taskId,
            in Bundle imageContextRequestExtras);
    void suggestContentSelections(
            in SelectionsRequest request,
            in ISelectionsCallback callback);
    void classifyContentSelections(
            in ClassificationsRequest request,
            in IClassificationsCallback callback);
    void notifyInteraction(in String requestId, in Bundle interaction);
}
