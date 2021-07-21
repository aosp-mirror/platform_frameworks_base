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

package android.service.translation;

import android.os.IBinder;
import android.os.ResultReceiver;
import android.view.translation.TranslationContext;
import com.android.internal.os.IResultReceiver;

/**
 * System-wide on-device translation service.
 *
 * <p>Services requests to translate text between different languages. The primary use case for this
 * service is automatic translation of text and web views, when the auto Translate feature is
 * enabled.
 *
 * @hide
 */
oneway interface ITranslationService {
    void onConnected(in IBinder callback);
    void onDisconnected();
    void onCreateTranslationSession(in TranslationContext translationContext, int sessionId,
         in IResultReceiver receiver);

    void onTranslationCapabilitiesRequest(int sourceFormat, int targetFormat,
         in ResultReceiver receiver);
}
