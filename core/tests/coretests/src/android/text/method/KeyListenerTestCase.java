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

package android.text.method;

import android.app.Instrumentation;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.widget.EditText;

import com.android.frameworks.coretests.R;

public abstract class KeyListenerTestCase extends InstrumentationTestCase {

    protected Instrumentation mInstrumentation;
    protected EditText mTextView;

    public KeyListenerTestCase() {
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mInstrumentation = getInstrumentation();
        mTextView = new EditText(mInstrumentation.getContext());
    }

    protected static KeyEvent getKey(int keycode, int metaState) {
        long currentTime = System.currentTimeMillis();
        return new KeyEvent(currentTime, currentTime, KeyEvent.ACTION_DOWN, keycode,
                0 /* repeat */, metaState);
    }
}
