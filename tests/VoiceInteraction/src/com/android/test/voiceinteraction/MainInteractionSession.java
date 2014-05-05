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
import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainInteractionSession extends VoiceInteractionSession
        implements View.OnClickListener {
    static final String TAG = "MainInteractionSession";

    Intent mStartIntent;
    View mContentView;
    TextView mText;
    Button mStartButton;

    Request mPendingRequest;
    boolean mPendingConfirm;

    MainInteractionSession(Context context) {
        super(context);
    }

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        showWindow();
        mStartIntent = args.getParcelable("intent");
    }

    @Override
    public View onCreateContentView() {
        mContentView = getLayoutInflater().inflate(R.layout.voice_interaction_session, null);
        mText = (TextView)mContentView.findViewById(R.id.text);
        mStartButton = (Button)mContentView.findViewById(R.id.start);
        mStartButton.setOnClickListener(this);
        return mContentView;
    }

    public void onClick(View v) {
        if (mPendingRequest == null) {
            mStartButton.setEnabled(false);
            startVoiceActivity(mStartIntent);
        } else {
            if (mPendingConfirm) {
                mPendingRequest.sendConfirmResult(true, null);
            } else {
                mPendingRequest.sendCommandResult(true, null);
            }
            mPendingRequest = null;
            mStartButton.setText("Start");
        }
    }

    @Override
    public boolean[] onGetSupportedCommands(Caller caller, String[] commands) {
        return new boolean[commands.length];
    }

    @Override
    public void onConfirm(Caller caller, Request request, String prompt, Bundle extras) {
        Log.i(TAG, "onConfirm: prompt=" + prompt + " extras=" + extras);
        mText.setText(prompt);
        mStartButton.setEnabled(true);
        mStartButton.setText("Confirm");
        mPendingRequest = request;
        mPendingConfirm = true;
    }

    @Override
    public void onCommand(Caller caller, Request request, String command, Bundle extras) {
        Log.i(TAG, "onCommand: command=" + command + " extras=" + extras);
        mText.setText("Command: " + command);
        mStartButton.setEnabled(true);
        mStartButton.setText("Finish Command");
        mPendingRequest = request;
        mPendingConfirm = false;
    }

    @Override
    public void onCancel(Request request) {
        Log.i(TAG, "onCancel");
        request.sendCancelResult();
    }
}
