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

package com.android.systemui.statusbar.car;

import static android.service.voice.VoiceInteractionSession.SHOW_SOURCE_ASSIST_GESTURE;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;

import com.android.internal.app.AssistUtils;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;

/**
 * AssitantButton is a ui component that will trigger the Voice Interaction Service.
 */
public class AssitantButton extends CarFacetButton {

    private static final String TAG = "CarFacetButton";
    private IVoiceInteractionSessionShowCallback mShowCallback =
            new IVoiceInteractionSessionShowCallback.Stub() {
                @Override
                public void onFailed() {
                    Log.w(TAG, "Failed to show VoiceInteractionSession");
                }

                @Override
                public void onShown() {
                    Log.d(TAG, "IVoiceInteractionSessionShowCallback onShown()");
                }
            };
    
    private final AssistUtils mAssistUtils;

    public AssitantButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAssistUtils = new AssistUtils(context);
        setOnClickListener(v -> {
            showAssistant();
        });
    }

    private void showAssistant() {
        final Bundle args = new Bundle();
        mAssistUtils.showSessionForActiveService(args,
                SHOW_SOURCE_ASSIST_GESTURE, mShowCallback, /*activityToken=*/ null);
    }

    @Override
    protected void setupIntents(TypedArray typedArray){
        // left blank because for the assistant button Intent will not be passed from the layout.
    }
}
