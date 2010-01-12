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
import android.app.Activity;
import android.view.View;

/**
 * Exercise <ViewStub /> tag in XML files.
 */
public class StubbedView extends Activity {
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.viewstub);

        findViewById(R.id.vis).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final View view = findViewById(R.id.viewStub);
                if (view != null) {
                    view.setVisibility(View.VISIBLE);
                }
            }
        });
    }
}
