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

package android.view;

import com.android.frameworks.coretests.R;

import android.os.Bundle;
import android.widget.Button;
import android.view.View;
import android.view.View.OnClickListener;
import android.app.Activity;

/**
 * Exercise View's disabled state.
 */
public class Disabled extends Activity implements OnClickListener {
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.disabled);

        // Find our buttons
        Button disabledButton = (Button) findViewById(R.id.disabledButton);
        disabledButton.setEnabled(false);
        
        // Find our buttons
        Button disabledButtonA = (Button) findViewById(R.id.disabledButtonA);
        disabledButtonA.setOnClickListener(this);
    }

    public void onClick(View v) {
        Button disabledButtonB = (Button) findViewById(R.id.disabledButtonB);
        disabledButtonB.setEnabled(!disabledButtonB.isEnabled());
    }
}
