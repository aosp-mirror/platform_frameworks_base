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

package android.app.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class ClearTop extends Activity {
    public static final String WAIT_CLEAR_TASK = "waitClearTask";
    
    public ClearTop() {
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        //Log.i("foo", "Creating: " + this);
        Intent intent = new Intent(getIntent()).setAction(LocalScreen.CLEAR_TASK)
                .setClass(this, LocalScreen.class);
        startActivity(intent);
    }
    
    @Override
    public void onNewIntent(Intent intent) {
        //Log.i("foo", "New intent in " + this + ": " + intent);
        if (LocalScreen.CLEAR_TASK.equals(intent.getAction())) {
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED, new Intent().setAction(
                    "New intent received " + intent + ", expecting action "
                    + TestedScreen.CLEAR_TASK));
        }
        finish();
    }
}
