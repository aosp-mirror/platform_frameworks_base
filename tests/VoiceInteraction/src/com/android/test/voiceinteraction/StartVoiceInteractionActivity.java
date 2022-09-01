/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.Arrays;

public class StartVoiceInteractionActivity extends Activity implements View.OnClickListener {
    static final String TAG = "LocalVoiceInteractionActivity";

    static final String REQUEST_ABORT = "abort";
    static final String REQUEST_COMPLETE = "complete";
    static final String REQUEST_COMMAND = "command";
    static final String REQUEST_PICK = "pick";
    static final String REQUEST_CONFIRM = "confirm";

    VoiceInteractor mInteractor;
    VoiceInteractor.Request mCurrentRequest = null;
    TextView mLog;
    Button mCommandButton;
    Button mPickButton;
    Button mCancelButton;
    Button mStartButton;
    Button mStopButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.local_interaction_app);

        mLog = (TextView)findViewById(R.id.log);
        mCommandButton = (Button)findViewById(R.id.command);
        mCommandButton.setOnClickListener(this);
        mPickButton = (Button)findViewById(R.id.pick);
        mPickButton.setOnClickListener(this);
        mCancelButton = (Button)findViewById(R.id.cancel);
        mCancelButton.setOnClickListener(this);
        mStartButton = findViewById(R.id.start);
        mStartButton.setOnClickListener(this);
        mStopButton = findViewById(R.id.stop);
        mStopButton.setOnClickListener(this);

        mLog.append("Local Voice Interaction Supported = " + isLocalVoiceInteractionSupported());
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onClick(View v) {
        if (v == mCommandButton) {
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
        } else if (v == mCancelButton && mCurrentRequest != null) {
            Log.i(TAG, "Cancel request");
            mCurrentRequest.cancel();
        } else if (v == mStartButton) {
            Bundle args = new Bundle();
            args.putString("Foo", "Bar");
            startLocalVoiceInteraction(args);
        } else if (v == mStopButton) {
            stopLocalVoiceInteraction();
        }
    }

    @Override
    public void onLocalVoiceInteractionStarted() {
        mInteractor = getVoiceInteractor();
        mLog.append("\nLocalVoiceInteraction started!");
        mStopButton.setEnabled(true);
    }

    @Override
    public void onLocalVoiceInteractionStopped() {
        mInteractor = getVoiceInteractor();
        mLog.append("\nLocalVoiceInteraction stopped!");
        mStopButton.setEnabled(false);
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
            ((StartVoiceInteractionActivity)getActivity()).mLog.append("Canceled abort\n");
        }
        @Override public void onAbortResult(Bundle result) {
            Log.i(TAG, "Abort result: result=" + result);
            ((StartVoiceInteractionActivity)getActivity()).mLog.append(
                    "Abort: result=" + result + "\n");
            getActivity().finish();
        }
    }

    static class TestCompleteVoice extends VoiceInteractor.CompleteVoiceRequest {
        public TestCompleteVoice() {
            super(new VoiceInteractor.Prompt("Woohoo, completed!"), null);
        }
        @Override public void onCancel() {
            Log.i(TAG, "Canceled!");
            ((StartVoiceInteractionActivity)getActivity()).mLog.append("Canceled complete\n");
        }
        @Override public void onCompleteResult(Bundle result) {
            Log.i(TAG, "Complete result: result=" + result);
            ((StartVoiceInteractionActivity)getActivity()).mLog.append("Complete: result="
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
            ((StartVoiceInteractionActivity)getActivity()).mLog.append("Canceled command\n");
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
            ((StartVoiceInteractionActivity)getActivity()).mLog.append(sb.toString());
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
            ((StartVoiceInteractionActivity)getActivity()).mLog.append("Canceled pick\n");
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
            ((StartVoiceInteractionActivity)getActivity()).mLog.append(sb.toString());
        }
    }
}
