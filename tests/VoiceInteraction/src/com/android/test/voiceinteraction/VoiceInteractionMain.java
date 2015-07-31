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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;

public class VoiceInteractionMain extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        findViewById(R.id.start).setOnClickListener(mStartListener);
        findViewById(R.id.secure).setOnClickListener(mSecureListener);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public Uri onProvideReferrer() {
        return Uri.parse("http://www.example.com/VoiceInteractionMain");
    }

    View.OnClickListener mStartListener = new View.OnClickListener() {
        public void onClick(View v) {
            showAssist(null);
        }
    };

    View.OnClickListener mSecureListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (((CheckBox)v).isChecked()) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        }
    };
}
