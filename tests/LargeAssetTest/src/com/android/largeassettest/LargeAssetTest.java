/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.largeassettest;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.InputStream;
import java.io.IOException;

/**
 * Skeleton to test large-asset handling.  The asset in question is one million
 * four-byte integers, in ascending numeric order.
 */
public class LargeAssetTest extends Activity {
    Button mValidateButton;
    TextView mResultText;
    Validator mValidateThread;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.lat);

        mResultText = findViewById(R.id.result);
        mValidateButton = findViewById(R.id.validate);

        mValidateButton.setOnClickListener(mClickListener);
    }

    View.OnClickListener mClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            mValidateButton.setEnabled(false);
            mValidateThread = new Validator();
            mValidateThread.execute(LargeAssetTest.this.getAssets());
        }
    };

    /**
     * Validation happens in a separate thread
     */
    class Validator extends AsyncTask<AssetManager, Integer, Boolean> {
        static final String TAG = "Validator";

        @Override
        protected Boolean doInBackground(AssetManager... params) {
            AssetManager am = params[0];
            try {
                InputStream is = am.open("million-ints", AssetManager.ACCESS_STREAMING);
                byte[] buf = new byte[4];

                for (int i = 0; i < 1000000; i++) {
                    int num = is.read(buf, 0, 4);
                    if (num != 4) {
                        Log.e(TAG, "Wanted 4 bytes but read " + num);
                        return false;
                    }
                    // the byte array is stored in the asset in little-endian order
                    int value = (buf[3] << 24) + ((buf[2] & 0xFF) << 16)
                            + ((buf[1] & 0xFF) << 8) + (buf[0] & 0xFF);
                    if (value != i) {
                        Log.e(TAG, "Mismatch: index " + i + " : value " + value);
                        return false;
                    }
                }

                is.close();
            } catch (IOException e) {
                Log.w(TAG, "Couldn't open asset", e);
                return false;
            }
            Log.i(TAG, "Finished, reporting valid");
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            CharSequence text = (result) ? "Valid!" : "NOT VALID";
            mResultText.setText(text);
            mValidateButton.setEnabled(true);
        }
    }
}
