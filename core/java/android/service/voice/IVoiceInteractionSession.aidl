/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.voice;

import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;

import com.android.internal.app.IVoiceInteractionSessionShowCallback;

/**
 * @hide
 */
oneway interface IVoiceInteractionSession {
    void show(in Bundle sessionArgs, int flags, IVoiceInteractionSessionShowCallback showCallback);
    void hide();
    void handleAssist(in Bundle assistData, in AssistStructure structure, in AssistContent content,
                      int index, int count);
    void handleScreenshot(in Bitmap screenshot);
    void taskStarted(in Intent intent, int taskId);
    void taskFinished(in Intent intent, int taskId);
    void closeSystemDialogs();
    void onLockscreenShown();
    void destroy();
}
