/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget.focus;

import com.android.frameworks.coretests.R;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;

/**
 * Exercises cases where elements of the UI are requestFocus()ed.
 */
public class RequestFocus extends Activity {
    protected final Handler mHandler = new Handler();

    @Override protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.focus_after_removal);

        // bottom right button starts with the focus.
        final Button bottomRightButton = (Button) findViewById(R.id.bottomRightButton);
        bottomRightButton.requestFocus();
        bottomRightButton.setText("I should have focus");
    }

    public Handler getHandler() {
        return mHandler;
    }
}
