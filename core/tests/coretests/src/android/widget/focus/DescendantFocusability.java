/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.view.ViewGroup;
import android.widget.Button;

public class DescendantFocusability extends Activity {

    public ViewGroup beforeDescendants;
    public Button beforeDescendantsChild;

    public ViewGroup afterDescendants;
    public Button afterDescendantsChild;

    public ViewGroup blocksDescendants;
    public Button blocksDescendantsChild;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.descendant_focusability);

        beforeDescendants = findViewById(R.id.beforeDescendants);
        beforeDescendantsChild = (Button) beforeDescendants.getChildAt(0);

        afterDescendants = findViewById(R.id.afterDescendants);
        afterDescendantsChild = (Button) afterDescendants.getChildAt(0);

        blocksDescendants = findViewById(R.id.blocksDescendants);
        blocksDescendantsChild = (Button) blocksDescendants.getChildAt(0);
    }

}
