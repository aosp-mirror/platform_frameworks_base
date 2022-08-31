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
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.Arrays;

public class TestInteractionActivity extends Activity implements View.OnClickListener {
    static final String TAG = "TestInteractionActivity";

    static final String REQUEST_ABORT = "abort";
    static final String REQUEST_COMPLETE = "complete";
    static final String REQUEST_COMMAND = "command";
    static final String REQUEST_PICK = "pick";
    static final String REQUEST_CONFIRM = "confirm";

    VoiceInteractor mInteractor;
    VoiceInteractor.Request mCurrentRequest = null;
    TextView mLog;
    Button mAirplaneButton;
    Button mAbortButton;
    Button mCompleteButton;
    Button mCommandButton;
    Button mPickButton;
    Button mJumpOutButton;
    Button mCancelButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!isVoiceInteraction()) {
            Log.w(TAG, "Not running as a voice interaction!");
            finish();
            return;
        }

        if (!VoiceInteractionService.isActiveService(this,
                new ComponentName(this, MainInteractionService.class))) {
            Log.w(TAG, "Not current voice interactor!");
            finish();
            return;
        }

        setContentView(R.layout.test_interaction);
        mLog = (TextView)findViewById(R.id.log);
        mAirplaneButton = (Button)findViewById(R.id.airplane);
        mAirplaneButton.setOnClickListener(this);
        mAbortButton = (Button)findViewById(R.id.abort);
        mAbortButton.setOnClickListener(this);
        mCompleteButton = (Button)findViewById(R.id.complete);
        mCompleteButton.setOnClickListener(this);
        mCommandButton = (Button)findViewById(R.id.command);
        mCommandButton.setOnClickListener(this);
        mPickButton = (Button)findViewById(R.id.pick);
        mPickButton.setOnClickListener(this);
        mJumpOutButton = (Button)findViewById(R.id.jump);
        mJumpOutButton.setOnClickListener(this);
        mCancelButton = (Button)findViewById(R.id.cancel);
        mCancelButton.setOnClickListener(this);

        mInteractor = getVoiceInteractor();

        VoiceInteractor.Request[] active = mInteractor.getActiveRequests();
        for (int i=0; i<active.length; i++) {
            Log.i(TAG, "Active #" + i + " / " + active[i].getName() + ": " + active[i]);
        }

        mCurrentRequest = mInteractor.getActiveRequest(REQUEST_CONFIRM);
        if (mCurrentRequest == null) {
            mCurrentRequest = new VoiceInteractor.ConfirmationRequest(
                    new VoiceInteractor.Prompt("This is a confirmation"), null) {
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
            mInteractor.submitRequest(mCurrentRequest, REQUEST_CONFIRM);
            String[] cmds = new String[] {
                    "com.android.test.voiceinteraction.COMMAND",
                    "com.example.foo.bar"
            };
            boolean sup[] = mInteractor.supportsCommands(cmds);
            for (int i=0; i<cmds.length; i++) {
                mLog.append(cmds[i] + ": " + (sup[i] ? "SUPPORTED" : "NOT SUPPORTED") + "\n");
            }
        } else {
            Log.i(TAG, "Restarting with active confirmation: " + mCurrentRequest);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onClick(View v) {
        if (v == mAirplaneButton) {
            Intent intent = new Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE);
            intent.addCategory(Intent.CATEGORY_VOICE);
            intent.putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, true);
            startActivity(intent);
        } else if (v == mAbortButton) {
            VoiceInteractor.AbortVoiceRequest req = new TestAbortVoice();
            mInteractor.submitRequest(req, REQUEST_ABORT);
        } else if (v == mCompleteButton) {
            VoiceInteractor.CompleteVoiceRequest req = new TestCompleteVoice();
            mInteractor.submitRequest(req, REQUEST_COMPLETE);
        } else if (v == mCommandButton) {
            VoiceInteractor.CommandRequest req = new TestCommand("Some arg");
            mInteractor.submitRequest(req, REQUEST_COMMAND);
        } else if (v == mPickButton) {
            VoiceInteractor.PickOptionRequest.Option[] options =
                    new VoiceInteractor.PickOptionRequest.Option[5];
            options[0] = new VoiceInteractor.PickOptionRequest.Option("One");
            options[1] = new VoiceInteractor.PickOptionRequest.Option("Two");
            options[2] = new VoiceInteractor.PickOptionRequest.Option("Three");
            options[3] = new VoiceInteractor.PickOptionRequest.Option("Four");
            options[4] = new VoiceInteractor.PickOptionRequest.Option("Five");
            VoiceInteractor.PickOptionRequest req = new TestPickOption(options);
            mInteractor.submitRequest(req, REQUEST_PICK);
        } else if (v == mJumpOutButton) {
            Log.i(TAG, "Jump out");
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(this, VoiceInteractionMain.class));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else if (v == mCancelButton && mCurrentRequest != null) {
            Log.i(TAG, "Cancel request");
            mCurrentRequest.cancel();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    static class TestAbortVoice extends VoiceInteractor.AbortVoiceRequest {
        public TestAbortVoice() {
            super(new VoiceInteractor.Prompt("Dammit, we suck :("), null);
        }
        @Override public void onCancel() {
            Log.i(TAG, "Canceled!");
            ((TestInteractionActivity)getActivity()).mLog.append("Canceled abort\n");
        }
        @Override public void onAbortResult(Bundle result) {
            Log.i(TAG, "Abort result: result=" + result);
            ((TestInteractionActivity)getActivity()).mLog.append("Abort: result=" + result + "\n");
            getActivity().finish();
        }
    }

    static class TestCompleteVoice extends VoiceInteractor.CompleteVoiceRequest {
        public TestCompleteVoice() {
            super(new VoiceInteractor.Prompt("Woohoo, completed!"), null);
        }
        @Override public void onCancel() {
            Log.i(TAG, "Canceled!");
            ((TestInteractionActivity)getActivity()).mLog.append("Canceled complete\n");
        }
        @Override public void onCompleteResult(Bundle result) {
            Log.i(TAG, "Complete result: result=" + result);
            ((TestInteractionActivity)getActivity()).mLog.append("Complete: result="
                    + result + "\n");
            getActivity().finish();
        }
    }

    static class TestCommand extends VoiceInteractor.CommandRequest {
        public TestCommand(String arg) {
            super("com.android.test.voiceinteraction.COMMAND", makeBundle(arg));
        }
        @Override public void onCancel() {
            Log.i(TAG, "Canceled!");
            ((TestInteractionActivity)getActivity()).mLog.append("Canceled command\n");
        }
        @Override
        public void onCommandResult(boolean finished, Bundle result) {
            Log.i(TAG, "Command result: finished=" + finished + " result=" + result);
            StringBuilder sb = new StringBuilder();
            if (finished) {
                sb.append("Command final result: ");
            } else {
                sb.append("Command intermediate result: ");
            }
            if (result != null) {
                result.getString("key");
            }
            sb.append(result);
            sb.append("\n");
            ((TestInteractionActivity)getActivity()).mLog.append(sb.toString());
        }
        static Bundle makeBundle(String arg) {
            Bundle b = new Bundle();
            b.putString("key", arg);
            return b;
        }
    }

    static class TestPickOption extends VoiceInteractor.PickOptionRequest {
        public TestPickOption(Option[] options) {
            super(new VoiceInteractor.Prompt("Need to pick something"), options, null);
        }
        @Override public void onCancel() {
            Log.i(TAG, "Canceled!");
            ((TestInteractionActivity)getActivity()).mLog.append("Canceled pick\n");
        }
        @Override
        public void onPickOptionResult(boolean finished, Option[] selections, Bundle result) {
            Log.i(TAG, "Pick result: finished=" + finished
                    + " selections=" + Arrays.toString(selections)
                    + " result=" + result);
            StringBuilder sb = new StringBuilder();
            if (finished) {
                sb.append("Pick final result: ");
            } else {
                sb.append("Pick intermediate result: ");
            }
            for (int i=0; i<selections.length; i++) {
                if (i >= 1) {
                    sb.append(", ");
                }
                sb.append(selections[i].getLabel());
            }
            sb.append("\n");
            ((TestInteractionActivity)getActivity()).mLog.append(sb.toString());
        }
    }
}
