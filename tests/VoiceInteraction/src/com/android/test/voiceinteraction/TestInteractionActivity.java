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

import android.app.Activity;
import android.app.VoiceInteractor;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

public class TestInteractionActivity extends Activity implements View.OnClickListener {
    static final String TAG = "TestInteractionActivity";

    VoiceInteractor mInteractor;
    Button mAbortButton;
    Button mCompleteButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!isVoiceInteraction()) {
            Log.w(TAG, "Not running as a voice interaction!");
            finish();
            return;
        }

        setContentView(R.layout.test_interaction);
        mAbortButton = (Button)findViewById(R.id.abort);
        mAbortButton.setOnClickListener(this);
        mCompleteButton = (Button)findViewById(R.id.complete);
        mCompleteButton.setOnClickListener(this);

        // Framework should take care of these.
        getWindow().setGravity(Gravity.TOP);
        getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);

        mInteractor = getVoiceInteractor();
        VoiceInteractor.ConfirmationRequest req = new VoiceInteractor.ConfirmationRequest(
                "This is a confirmation", null) {
            @Override
            public void onCancel() {
                Log.i(TAG, "Canceled!");
                getActivity().finish();
            }

            @Override
            public void onConfirmationResult(boolean confirmed, Bundle result) {
                Log.i(TAG, "Confirmation result: confirmed=" + confirmed + " result=" + result);
                getActivity().finish();
            }
        };
        mInteractor.submitRequest(req);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onClick(View v) {
        if (v == mAbortButton) {
            VoiceInteractor.AbortVoiceRequest req = new VoiceInteractor.AbortVoiceRequest(
                    "Dammit, we suck :(", null) {
                @Override
                public void onCancel() {
                    Log.i(TAG, "Canceled!");
                }

                @Override
                public void onAbortResult(Bundle result) {
                    Log.i(TAG, "Abort result: result=" + result);
                    getActivity().finish();
                }
            };
            mInteractor.submitRequest(req);
        } else if (v == mCompleteButton) {
            VoiceInteractor.CompleteVoiceRequest req = new VoiceInteractor.CompleteVoiceRequest(
                    "Woohoo, completed!", null) {
                @Override
                public void onCancel() {
                    Log.i(TAG, "Canceled!");
                }

                @Override
                public void onCompleteResult(Bundle result) {
                    Log.i(TAG, "Complete result: result=" + result);
                    getActivity().finish();
                }
            };
            mInteractor.submitRequest(req);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
}
