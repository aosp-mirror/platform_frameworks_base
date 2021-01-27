/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.speech;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.os.UserHandle;
import android.speech.IRecognitionServiceManager;
import android.speech.IRecognitionServiceManagerCallback;

import com.android.internal.R;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

/**
 * System service implementation for Speech Recognizer.
 *
 * <p>This service uses RemoteSpeechRecognitionService to bind to a default implementation of
 * ISpeechRecognition. It relays all the requests from the client to the default system impl of
 * ISpeechRecognition service (denoted by {@code
 * R.string.config_defaultOnDeviceSpeechRecognitionService}).
 */
public final class SpeechRecognitionManagerService extends
        AbstractMasterSystemService<SpeechRecognitionManagerService,
                SpeechRecognitionManagerServiceImpl> {
    private static final String TAG = SpeechRecognitionManagerService.class.getSimpleName();

    public SpeechRecognitionManagerService(@NonNull Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(
                        context,
                        R.string.config_defaultOnDeviceSpeechRecognitionService),
                /*disallowProperty=*/ null);
    }

    @Override // from SystemService
    public void onStart() {
        SpeechRecognitionManagerServiceStub serviceStub = new SpeechRecognitionManagerServiceStub();
        publishBinderService(Context.SPEECH_RECOGNITION_SERVICE, serviceStub);
    }

    @Override
    protected SpeechRecognitionManagerServiceImpl newServiceLocked(
            @UserIdInt int resolvedUserId, boolean disabled) {
        return new SpeechRecognitionManagerServiceImpl(this, mLock, resolvedUserId, disabled);
    }

    final class SpeechRecognitionManagerServiceStub extends IRecognitionServiceManager.Stub {

        @Override
        public void createSession(IRecognitionServiceManagerCallback callback) {
            int userId = UserHandle.getCallingUserId();
            synchronized (mLock) {
                SpeechRecognitionManagerServiceImpl service = getServiceForUserLocked(userId);
                service.createSessionLocked(callback);
            }
        }
    }
}
