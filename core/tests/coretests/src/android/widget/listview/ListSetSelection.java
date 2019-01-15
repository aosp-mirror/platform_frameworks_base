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

package android.widget.listview;

import android.os.Bundle;
import android.util.ListScenario;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * List of 1,000 items used to test calls to setSelection() in touch mode.
 * Pressing the S key will call setSelection(0) on the list.
 */
public class ListSetSelection extends ListScenario {
    private Button mButton;

    @Override
    protected void init(Params params) {
        params.setStackFromBottom(false)
                .setStartingSelectionPosition(-1)
                .setNumItems(1000)
                .setItemScreenSizeFactor(0.22);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mButton = new Button(this);
        mButton.setText("setSelection(0)");
        mButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                getListView().setSelection(0);
            }
        });

        getListViewContainer().addView(mButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
    }

    public Button getButton() {
        return mButton;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_S) {
            getListView().setSelection(0);
            return true;
        }

        return super.dispatchKeyEvent(event);
    }
}
