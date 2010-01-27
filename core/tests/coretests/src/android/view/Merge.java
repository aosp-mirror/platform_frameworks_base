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
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.view.LayoutInflater;

/**
 * Exercise <merge /> tag in XML files.
 */
public class Merge extends Activity {
    private LinearLayout mLayout;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.VERTICAL);
        LayoutInflater.from(this).inflate(R.layout.merge_tag, mLayout);

        setContentView(mLayout);
    }

    public ViewGroup getLayout() {
        return mLayout;
    }
}
