/*
 * Copyright (C) 2019 The Android Open Source Project
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

package perftests.multiuser.apps.dummyapp;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;

/** An activity. */
public class DummyForegroundActivity extends Activity {
    private static final String TAG = DummyForegroundActivity.class.getSimpleName();

    public static final int TOP_SLEEP_TIME_MS = 2_000;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        doSleepWhileTop(TOP_SLEEP_TIME_MS);
    }

    /** Does nothing, but asynchronously. */
    private void doSleepWhileTop(int sleepTime) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                SystemClock.sleep(sleepTime);
                return null;
            }

            @Override
            protected void onPostExecute(Void nothing) {
                finish();
            }
        }.execute();
    }
}
