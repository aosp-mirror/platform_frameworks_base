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

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.android.frameworks.coretests.R;

/**
 * Tests views using post*() and getViewTreeObserver() before onAttachedToWindow().
 */
public class RunQueue extends Activity implements ViewTreeObserver.OnGlobalLayoutListener {
    public boolean runnableRan = false;
    public boolean runnableCancelled = true;
    public boolean globalLayout = false;
    public ViewTreeObserver viewTreeObserver;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        TextView textView = new TextView(this);
        textView.setText("RunQueue");
        textView.setId(R.id.simple_view);

        setContentView(textView);
        final View view = findViewById(R.id.simple_view);

        view.post(new Runnable() {
            public void run() {
                runnableRan = true;
            }
        });

        final Runnable runnable = new Runnable() {
            public void run() {
                runnableCancelled = false;
            }
        };
        view.post(runnable);
        view.post(runnable);
        view.post(runnable);
        view.post(runnable);
        view.removeCallbacks(runnable);

        viewTreeObserver = view.getViewTreeObserver();
        viewTreeObserver.addOnGlobalLayoutListener(this);
    }

    public void onGlobalLayout() {
        globalLayout = true;
    }
}
