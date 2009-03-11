/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.tests.appwidgethost;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;

public class TestAppWidgetConfigure extends Activity {
    static final String TAG = "TestAppWidgetConfigure";

    public TestAppWidgetConfigure() {
        super();
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.test_appwidget_configure);

        findViewById(R.id.save_button).setOnClickListener(mOnClickListener);
    }

    View.OnClickListener mOnClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            String text = ((EditText)findViewById(R.id.edit_text)).getText().toString();
            Log.d(TAG, "text is '" + text + '\'');
            SharedPreferences.Editor prefs = getSharedPreferences(TestAppWidgetProvider.PREFS_NAME, 0)
                    .edit();
            prefs.putString(TestAppWidgetProvider.PREF_PREFIX_KEY, text);
            prefs.commit();
            setResult(RESULT_OK);
            finish();
        }
    };

}


