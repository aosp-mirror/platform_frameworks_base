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
import android.os.CountDownTimer;
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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * Interface for profiling the webview's scrolling, with simple controls on how
 * to scroll, and what content to load.
 */
public class ProfileActivity extends Activity {

    public interface ProfileCallback {
        public void profileCallback(TileData data[][]);
    }

    public static final String TEMP_FILENAME = "profile.tiles";
    private static final int LOAD_TEST_DELAY = 2000; // nr of millis after load,
                                                     // before test

    Button mInspectButton;
    Spinner mVelocitySpinner;
    EditText mUrl;
    ProfiledWebView mWeb;
    ProfileCallback mCallback;

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

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.requestFocus();
            new CountDownTimer(LOAD_TEST_DELAY, LOAD_TEST_DELAY) {
                @Override
                public void onTick(long millisUntilFinished) {
                }

                @Override
                public void onFinish() {
                    mWeb.startScrollTest(mCallback);
                }
            }.start();
        }
    }

    private class StoreFileTask extends
            AsyncTask<Pair<String, TileData[][]>, Void, Void> {

        @Override
        protected Void doInBackground(Pair<String, TileData[][]>... params) {
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
            mUrl.setBackgroundResource(R.color.finished_url);
        }
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mInspectButton = (Button) findViewById(R.id.inspect);
        mVelocitySpinner = (Spinner) findViewById(R.id.velocity);
        mUrl = (EditText) findViewById(R.id.url);
        mWeb = (ProfiledWebView) findViewById(R.id.web);
        mCallback = new ProfileCallback() {
            @SuppressWarnings("unchecked")
            @Override
            public void profileCallback(TileData[][] data) {
                new StoreFileTask().execute(new Pair<String, TileData[][]>(TEMP_FILENAME, data));
            }
        };

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
                mUrl.setBackgroundResource(R.color.unfinished_url);
                mWeb.loadUrl(url);
                mWeb.requestFocus();
                return true;
            }
        });
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
