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

package com.android.test.voiceinteraction;

import android.content.Context;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

public class MainInteractionSession extends VoiceInteractionSession {
    static final String TAG = "MainInteractionSession";

    final Bundle mArgs;

    MainInteractionSession(Context context, Bundle args) {
        super(context);
        mArgs = args;
    }

    @Override
    public boolean[] onGetSupportedCommands(Caller caller, String[] commands) {
        return new boolean[commands.length];
    }

    @Override
    public void onConfirm(Caller caller, Request request, String prompt, Bundle extras) {
        Log.i(TAG, "onConform: prompt=" + prompt + " extras=" + extras);
        request.sendConfirmResult(true, null);
    }

    @Override
    public void onCommand(Caller caller, Request request, String command, Bundle extras) {
        Log.i(TAG, "onCommand: command=" + command + " extras=" + extras);
        request.sendCommandResult(true, null);
    }

    @Override
    public void onCancel(Request request) {
        Log.i(TAG, "onCancel");
        request.sendCancelResult();
    }
}
