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

package android.widget.layout.linear;

import android.app.Activity;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import com.android.frameworks.coretests.R;

/**
 * One of two simple vertical linear layouts of buttons used to test out
 * the transistion between touch and focus mode.
 */
public class LLOfButtons1 extends Activity {

    private boolean mButtonPressed = false;
    private Button mFirstButton;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.linear_layout_buttons);
        mFirstButton = (Button) findViewById(R.id.button1);

        mFirstButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mButtonPressed = true;
            }
        });

    }

    public LinearLayout getLayout() {
        return (LinearLayout) findViewById(R.id.layout);
    }

    public Button getFirstButton() {
        return mFirstButton;
    }

    public boolean buttonClickListenerFired() {
        return mButtonPressed;
    }

    public boolean isInTouchMode() {
        return mFirstButton.isInTouchMode();
    }

}
