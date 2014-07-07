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
    Button mConfirmButton;
    Button mCompleteButton;
    Button mAbortButton;

    static final int STATE_IDLE = 0;
    static final int STATE_LAUNCHING = 1;
    static final int STATE_CONFIRM = 2;
    static final int STATE_COMMAND = 3;
    static final int STATE_ABORT_VOICE = 4;
    static final int STATE_COMPLETE_VOICE = 5;

    int mState = STATE_IDLE;
    Request mPendingRequest;

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
        mConfirmButton = (Button)mContentView.findViewById(R.id.confirm);
        mConfirmButton.setOnClickListener(this);
        mCompleteButton = (Button)mContentView.findViewById(R.id.complete);
        mCompleteButton.setOnClickListener(this);
        mAbortButton = (Button)mContentView.findViewById(R.id.abort);
        mAbortButton.setOnClickListener(this);
        updateState();
        return mContentView;
    }

    void updateState() {
        mStartButton.setEnabled(mState == STATE_IDLE);
        mConfirmButton.setEnabled(mState == STATE_CONFIRM || mState == STATE_COMMAND);
        mAbortButton.setEnabled(mState == STATE_ABORT_VOICE);
        mCompleteButton.setEnabled(mState == STATE_COMPLETE_VOICE);
    }

    public void onClick(View v) {
        if (v == mStartButton) {
            mState = STATE_LAUNCHING;
            updateState();
            startVoiceActivity(mStartIntent);
        } else if (v == mConfirmButton) {
            if (mState == STATE_CONFIRM) {
                mPendingRequest.sendConfirmResult(true, null);
            } else {
                mPendingRequest.sendCommandResult(true, null);
            }
            mPendingRequest = null;
            mState = STATE_IDLE;
            updateState();
        } else if (v == mAbortButton) {
            mPendingRequest.sendAbortVoiceResult(null);
            mPendingRequest = null;
            mState = STATE_IDLE;
            updateState();
        } else if (v== mCompleteButton) {
            mPendingRequest.sendCompleteVoiceResult(null);
            mPendingRequest = null;
            mState = STATE_IDLE;
            updateState();
        }
    }

    @Override
    public boolean[] onGetSupportedCommands(Caller caller, String[] commands) {
        return new boolean[commands.length];
    }

    @Override
    public void onConfirm(Caller caller, Request request, CharSequence prompt, Bundle extras) {
        Log.i(TAG, "onConfirm: prompt=" + prompt + " extras=" + extras);
        mText.setText(prompt);
        mStartButton.setText("Confirm");
        mPendingRequest = request;
        mState = STATE_CONFIRM;
        updateState();
    }

    @Override
    public void onCompleteVoice(Caller caller, Request request, CharSequence message, Bundle extras) {
        Log.i(TAG, "onCompleteVoice: message=" + message + " extras=" + extras);
        mText.setText(message);
        mPendingRequest = request;
        mState = STATE_COMPLETE_VOICE;
        updateState();
    }

    @Override
    public void onAbortVoice(Caller caller, Request request, CharSequence message, Bundle extras) {
        Log.i(TAG, "onAbortVoice: message=" + message + " extras=" + extras);
        mText.setText(message);
        mPendingRequest = request;
        mState = STATE_ABORT_VOICE;
        updateState();
    }

    @Override
    public void onCommand(Caller caller, Request request, String command, Bundle extras) {
        Log.i(TAG, "onCommand: command=" + command + " extras=" + extras);
        mText.setText("Command: " + command);
        mStartButton.setText("Finish Command");
        mPendingRequest = request;
        mState = STATE_COMMAND;
        updateState();
    }

    @Override
    public void onCancel(Request request) {
        Log.i(TAG, "onCancel");
        request.sendCancelResult();
    }
}
