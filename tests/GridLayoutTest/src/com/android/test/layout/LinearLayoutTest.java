/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.test.layout;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import static android.widget.LinearLayout.*;
import static android.widget.LinearLayout.LayoutParams.*;

public class LinearLayoutTest extends AbstractLayoutTest {
    public ViewGroup create(Context context) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        for (int i = 0; i < VERTICAL_ALIGNMENTS.length; i++) {
            int va = VERTICAL_ALIGNMENTS[i];
            for (int j = 0; j < HORIZONTAL_ALIGNMENTS.length; j++) {
                int ha = HORIZONTAL_ALIGNMENTS[j];
                LayoutParams lp = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
                lp.gravity = va | ha;
                View v = create(context, VERTICAL_NAMES[i] + "-" + HORIZONTAL_NAMES[j], 20);
                container.addView(v, lp);
            }
        }

        return container;
    }

    public String tag() {
        return LinearLayoutTest.class.getName();
    }
}
