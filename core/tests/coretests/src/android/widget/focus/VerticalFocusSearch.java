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
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Holds a few buttons of various sizes and horizontal placements in a
 * vertical layout to excercise some core focus searching.
 */
public class VerticalFocusSearch extends Activity {

    private LinearLayout mLayout;

    private Button mTopWide;
    private Button mMidSkinny1Left;
    private Button mBottomWide;

    private Button mMidSkinny2Right;


    public LinearLayout getLayout() {
        return mLayout;
    }

    public Button getTopWide() {
        return mTopWide;
    }

    public Button getMidSkinny1Left() {
        return mMidSkinny1Left;
    }

    public Button getMidSkinny2Right() {
        return mMidSkinny2Right;
    }

    public Button getBottomWide() {
        return mBottomWide;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.VERTICAL);
        mLayout.setHorizontalGravity(Gravity.START);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        mTopWide = makeWide("top wide");
        mLayout.addView(mTopWide);

        mMidSkinny1Left = addSkinny(mLayout, "mid skinny 1(L)", false);

        mMidSkinny2Right = addSkinny(mLayout, "mid skinny 2(R)", true);

        mBottomWide = makeWide("bottom wide");
        mLayout.addView(mBottomWide);

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

    private Button makeWide(String label) {
        Button button = new MyButton(this);
        button.setText(label);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }

    /**
     * Add a skinny button that takes up just less than half of the screen
     * horizontally.
     * @param root The layout to add the button to.
     * @param label The label of the button.
     * @param atRight Which side to put the button on.
     * @return The newly created button.
     */
    private Button addSkinny(LinearLayout root, String label, boolean atRight) {
        Button button = new MyButton(this);
        button.setText(label);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                0, // width
                ViewGroup.LayoutParams.WRAP_CONTENT,
                480));

        TextView filler = new TextView(this);
        filler.setText("filler");
        filler.setLayoutParams(new LinearLayout.LayoutParams(
                0, // width
                ViewGroup.LayoutParams.WRAP_CONTENT,
                520));

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        if (atRight) {
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
