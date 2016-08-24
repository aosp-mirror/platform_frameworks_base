/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.widget;

import com.android.frameworks.coretests.R;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

/**
 * An activity for testing the TextView widget.
 *
 * This class is copied from {@link android.text.method.cts.KeyListenerCtsActivity} in
 * CtsTextTestCase.  The original class is located at
 * cts/tests/tests/text/src/android/text/method/cts/KeyListenerCtsActivity.java
 */
public class TextViewActivity extends Activity {
    private boolean mHasWindowFocus = false;
    private Object mHasWindowFocusLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_view);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            Log.w("TextViewActivity", "TextViewActivity lost window focus");
        }
        synchronized(mHasWindowFocusLock) {
            mHasWindowFocus = hasFocus;
            mHasWindowFocusLock.notify();
        }
    }

    /**
     * Blocks the calling thread until the {@link KeyListenerCtsActivity} has window focus or the
     * specified duration (in milliseconds) has passed.
     */
    public boolean waitForWindowFocus(long durationMillis) {
        long elapsedMillis = SystemClock.elapsedRealtime();
        synchronized(mHasWindowFocusLock) {
            mHasWindowFocus = hasWindowFocus();
            while (!mHasWindowFocus && durationMillis > 0) {
                long newElapsedMillis = SystemClock.elapsedRealtime();
                durationMillis -= (newElapsedMillis - elapsedMillis);
                elapsedMillis = newElapsedMillis;
                if (durationMillis > 0) {
                    try {
                        mHasWindowFocusLock.wait(durationMillis);
                    } catch (InterruptedException e) {
                    }
                }
            }
            return mHasWindowFocus;
        }
    }
}
