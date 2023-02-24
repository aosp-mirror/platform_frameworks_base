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

package com.android.server.texttospeech;

import static com.android.server.texttospeech.TextToSpeechManagerPerUserService.runSessionCallbackMethod;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.UserHandle;
import android.speech.tts.ITextToSpeechManager;
import android.speech.tts.ITextToSpeechSessionCallback;

import com.android.server.infra.AbstractMasterSystemService;


/**
 * A service that  allows secured synthesizing of text to speech audio. Upon request creates a
 * session
 * that is managed by {@link TextToSpeechManagerPerUserService}.
 *
 * @see ITextToSpeechManager
 */
public final class TextToSpeechManagerService extends
        AbstractMasterSystemService<TextToSpeechManagerService,
                TextToSpeechManagerPerUserService> {

    private static final String TAG = TextToSpeechManagerService.class.getSimpleName();

    public TextToSpeechManagerService(@NonNull Context context) {
        super(context, /* serviceNameResolver= */ null,
                /* disallowProperty = */null);
    }

    @Override // from SystemService
    public void onStart() {
        publishBinderService(Context.TEXT_TO_SPEECH_MANAGER_SERVICE,
                new TextToSpeechManagerServiceStub());
    }

    @Override
    protected TextToSpeechManagerPerUserService newServiceLocked(
            @UserIdInt int resolvedUserId, boolean disabled) {
        return new TextToSpeechManagerPerUserService(this, mLock, resolvedUserId);
    }

    private final class TextToSpeechManagerServiceStub extends ITextToSpeechManager.Stub {
        @Override
        public void createSession(String engine,
                ITextToSpeechSessionCallback sessionCallback) {
            synchronized (mLock) {
                TextToSpeechManagerPerUserService perUserService = getServiceForUserLocked(
                        UserHandle.getCallingUserId());
                if (perUserService != null) {
                    perUserService.createSessionLocked(engine, sessionCallback);
                } else {
                    runSessionCallbackMethod(
                            () -> sessionCallback.onError("Service is not available for user"));
                }
            }
        }
    }
}
