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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.Bundle;

public class TestedActivity extends Activity
{
    public TestedActivity()
    {
    }

    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
    }

    protected void onRestoreInstanceState(Bundle state)
    {
        super.onRestoreInstanceState(state);
    }

    protected void onResume()
    {
        super.onResume();
        Looper.myLooper().myQueue().addIdleHandler(new Idler());
    }

    protected void onSaveInstanceState(Bundle outState)
    {
        super.onSaveInstanceState(outState);
    }

    protected void onStop()
    {
        super.onStop();
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            setResult(RESULT_OK);
            finish();
        }
    };

    private class Idler implements MessageQueue.IdleHandler
    {
        public final boolean queueIdle()
        {
            //Message m = Message.obtain();
            //mHandler.sendMessageAtTime(m, SystemClock.uptimeMillis()+1000);
            setResult(RESULT_OK);
            finish();
            return false;
        }
    }
}

