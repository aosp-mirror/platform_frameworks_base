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

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * An activity that helps test the scenario where a parent is
 * GONE and one of its children has focus; the activity should get
 * the key event.  see bug 945150. 
 */
public class GoneParentFocusedChild extends Activity {
    private LinearLayout mGoneGroup;
    private Button mButton;

    private boolean mUnhandledKeyEvent = false;
    private LinearLayout mLayout;

    public boolean isUnhandledKeyEvent() {
        return mUnhandledKeyEvent;
    }

    public LinearLayout getLayout() {
        return mLayout;
    }

    public LinearLayout getGoneGroup() {
        return mGoneGroup;
    }

    public Button getButton() {
        return mButton;
    }

    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.HORIZONTAL);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));


        mGoneGroup = new LinearLayout(this);
        mGoneGroup.setOrientation(LinearLayout.HORIZONTAL);
        mGoneGroup.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        mButton = new Button(this);
        mButton.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));


        mGoneGroup.addView(mButton);
        setContentView(mLayout);

        mGoneGroup.setVisibility(View.GONE);
        mButton.requestFocus();
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        mUnhandledKeyEvent = true;
        return true;
    }
}
