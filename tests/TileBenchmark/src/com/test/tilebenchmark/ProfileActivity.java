/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.test.tilebenchmark;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;
import android.widget.ToggleButton;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * Interface for profiling the webview's scrolling, with simple controls on how
 * to scroll, and what content to load.
 */
public class ProfileActivity extends Activity {

    public interface ProfileCallback {
        public void profileCallback(RunData data);
    }

    public static final String TEMP_FILENAME = "profile.tiles";

    Button mInspectButton;
    ToggleButton mCaptureButton;
    Spinner mVelocitySpinner;
    Spinner mMovementSpinner;
    EditText mUrl;
    ProfiledWebView mWeb;
    ProfileCallback mCallback;

    LoggingWebViewClient mLoggingWebViewClient = new LoggingWebViewClient();
    AutoLoggingWebViewClient mAutoLoggingWebViewClient = new AutoLoggingWebViewClient();

    private enum TestingState {
        NOT_TESTING,
        PRE_TESTING,
        START_TESTING,
        STOP_TESTING,
        SAVED_TESTING
    };

    private class VelocitySelectedListener implements OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view,
                int position, long id) {
            String speedStr = parent.getItemAtPosition(position).toString();
            int speedInt = Integer.parseInt(speedStr);
            mWeb.setAutoScrollSpeed(speedInt);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private class MovementSelectedListener implements OnItemSelectedListener {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view,
                int position, long id) {
            String movementStr = parent.getItemAtPosition(position).toString();
            if (movementStr == getResources().getString(
                    R.string.movement_auto_scroll)
                    || movementStr == getResources().getString(
                            R.string.movement_auto_fling)) {
                mWeb.setWebViewClient(mAutoLoggingWebViewClient);
                mCaptureButton.setEnabled(false);
                mVelocitySpinner.setEnabled(true);
            } else if (movementStr == getResources().getString(
                    R.string.movement_manual)) {
                mWeb.setWebViewClient(mLoggingWebViewClient);
                mCaptureButton.setEnabled(true);
                mVelocitySpinner.setEnabled(false);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private class LoggingWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            mUrl.setText(url);
        }
    }

    private class AutoLoggingWebViewClient extends LoggingWebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.requestFocus();

            startViewProfiling(true);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            setTestingState(TestingState.PRE_TESTING);
        }
    }

    private class StoreFileTask extends
            AsyncTask<Pair<String, RunData>, Void, Void> {

        @Override
        protected Void doInBackground(Pair<String, RunData>... params) {
            try {
                FileOutputStream fos = openFileOutput(params[0].first,
                        Context.MODE_PRIVATE);
                ObjectOutputStream out = new ObjectOutputStream(fos);
                out.writeObject(params[0].second);
                out.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            setTestingState(TestingState.SAVED_TESTING);
        }
    }

    public void setTestingState(TestingState state) {
        switch (state) {
            case NOT_TESTING:
                mUrl.setBackgroundResource(R.color.background_not_testing);
                mInspectButton.setEnabled(true);
                mMovementSpinner.setEnabled(true);
                break;
            case PRE_TESTING:
                mInspectButton.setEnabled(false);
                mMovementSpinner.setEnabled(false);
                break;
            case START_TESTING:
                mUrl.setBackgroundResource(R.color.background_start_testing);
                mInspectButton.setEnabled(false);
                mMovementSpinner.setEnabled(false);
                break;
            case STOP_TESTING:
                mUrl.setBackgroundResource(R.color.background_stop_testing);
                break;
            case SAVED_TESTING:
                mInspectButton.setEnabled(true);
                mMovementSpinner.setEnabled(true);
                break;
        }
    }

    /** auto - automatically scroll. */
    private void startViewProfiling(boolean auto) {
        // toggle capture button to indicate capture state to user
        mCaptureButton.setChecked(true);
        mWeb.startScrollTest(mCallback, auto);
        setTestingState(TestingState.START_TESTING);
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mInspectButton = (Button) findViewById(R.id.inspect);
        mCaptureButton = (ToggleButton) findViewById(R.id.capture);
        mVelocitySpinner = (Spinner) findViewById(R.id.velocity);
        mMovementSpinner = (Spinner) findViewById(R.id.movement);
        mUrl = (EditText) findViewById(R.id.url);
        mWeb = (ProfiledWebView) findViewById(R.id.web);
        setCallback(new ProfileCallback() {
            @SuppressWarnings("unchecked")
            @Override
            public void profileCallback(RunData data) {
                new StoreFileTask().execute(new Pair<String, RunData>(
                        TEMP_FILENAME, data));
                mCaptureButton.setChecked(false);
                setTestingState(TestingState.STOP_TESTING);
            }
        });

        // Inspect button (opens PlaybackActivity)
        mInspectButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ProfileActivity.this,
                        PlaybackActivity.class));
            }
        });

        // Velocity spinner
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.velocity_array,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mVelocitySpinner.setAdapter(adapter);
        mVelocitySpinner.setOnItemSelectedListener(
                new VelocitySelectedListener());
        mVelocitySpinner.setSelection(3);

        // Movement spinner
        String content[] = {
                getResources().getString(R.string.movement_auto_scroll),
                getResources().getString(R.string.movement_auto_fling),
                getResources().getString(R.string.movement_manual)
        };
        adapter = new ArrayAdapter<CharSequence>(this,
                android.R.layout.simple_spinner_item, content);
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mMovementSpinner.setAdapter(adapter);
        mMovementSpinner.setOnItemSelectedListener(
                new MovementSelectedListener());
        mMovementSpinner.setSelection(0);

        // Capture toggle button
        mCaptureButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCaptureButton.isChecked()) {
                    startViewProfiling(false);
                } else {
                    mWeb.stopScrollTest();
                }
            }
        });

        // Custom profiling WebView
        WebSettings settings = mWeb.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setSupportZoom(true);
        settings.setEnableSmoothTransition(true);
        settings.setBuiltInZoomControls(true);
        settings.setLoadWithOverviewMode(true);
        mWeb.setWebViewClient(new LoggingWebViewClient());

        // URL text entry
        mUrl.setOnEditorActionListener(new OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId,
                    KeyEvent event) {
                String url = mUrl.getText().toString();
                mWeb.loadUrl(url);
                mWeb.requestFocus();
                return true;
            }
        });

        setTestingState(TestingState.NOT_TESTING);
    }

    public void setCallback(ProfileCallback callback) {
        mCallback = callback;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK) && mWeb.canGoBack()) {
            mWeb.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
