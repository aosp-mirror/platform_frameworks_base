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
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.TextView;
import android.os.Bundle;
import android.view.ViewGroup;
import android.content.Context;

public class HorizontalFocusSearch extends Activity {

    private LinearLayout mLayout;

    private Button mLeftTall;
    private Button mMidShort1Top;
    private Button mMidShort2Bottom;
    private Button mRightTall;


    public LinearLayout getLayout() {
        return mLayout;
    }

    public Button getLeftTall() {
        return mLeftTall;
    }

    public Button getMidShort1Top() {
        return mMidShort1Top;
    }

    public Button getMidShort2Bottom() {
        return mMidShort2Bottom;
    }

    public Button getRightTall() {
        return mRightTall;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.HORIZONTAL);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        mLeftTall = makeTall("left tall");
        mLayout.addView(mLeftTall);

        mMidShort1Top = addShort(mLayout, "mid(1) top", false);
        mMidShort2Bottom = addShort(mLayout, "mid(2) bottom", true);

        mRightTall = makeTall("right tall");
        mLayout.addView(mRightTall);

        setContentView(mLayout);
    }

    // just to get toString non-sucky
    private static class MyButton extends Button {

        public MyButton(Context context) {
            super(context);
        }


        @Override
        public String toString() {
            return getText().toString();
        }
    }

    private Button makeTall(String label) {
        Button button = new MyButton(this);
        button.setText(label);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return button;
    }

    private Button addShort(LinearLayout root, String label, boolean atBottom) {
        Button button = new MyButton(this);
        button.setText(label);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, // height
                490));

        TextView filler = new TextView(this);
        filler.setText("filler");
        filler.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                0, // height
                510));

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        if (atBottom) {
            ll.addView(filler);
            ll.addView(button);
            root.addView(ll);
        } else {
            ll.addView(button);
            ll.addView(filler);
            root.addView(ll);
        }
        return button;
    }
}
