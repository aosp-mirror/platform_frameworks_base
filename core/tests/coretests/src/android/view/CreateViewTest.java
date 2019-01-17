/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.PerformanceTestCase;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

public class CreateViewTest extends AndroidTestCase implements PerformanceTestCase {

    public boolean isPerformanceOnly() {
        return false;
    }

    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        return 0;
    }

    @SmallTest
    public void testLayout1() throws Exception {
        new CreateViewTest.ViewOne(mContext);
    }

    @SmallTest
    public void testLayout2() throws Exception {
        LinearLayout vert = new LinearLayout(mContext);
        vert.addView(new CreateViewTest.ViewOne(mContext),
                new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 0));
    }

    @SmallTest
    public void testLayout3() throws Exception {
        LinearLayout vert = new LinearLayout(mContext);

        ViewOne one = new ViewOne(mContext);
        vert.addView(one, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 0));

        ViewOne two = new ViewOne(mContext);
        vert.addView(two, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 0));

        ViewOne three = new ViewOne(mContext);
        vert.addView(three, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 0));

        ViewOne four = new ViewOne(mContext);
        vert.addView(four, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 0));

        ViewOne five = new ViewOne(mContext);
        vert.addView(five, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 0));

        ViewOne six = new ViewOne(mContext);
        vert.addView(six, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, 0));
    }

    @SmallTest
    public void testLayout4() throws Exception {
        TextView text = new TextView(mContext);
        text.setText("S");
    }

    @SmallTest
    public void testLayout5() throws Exception {
        TextView text = new TextView(mContext);
        text.setText("S");

        LinearLayout vert = new LinearLayout(mContext);
        vert.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 0));
    }

    @SmallTest
    public void testLayout6() throws Exception {
        LinearLayout vert = new LinearLayout(mContext);

        TextView one = new TextView(mContext);
        one.setText("S");
        vert.addView(one, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 0));

        TextView two = new TextView(mContext);
        two.setText("M");
        vert.addView(two, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 0));

        TextView three = new TextView(mContext);
        three.setText("T");
        vert.addView(three, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 0));

        TextView four = new TextView(mContext);
        four.setText("W");
        vert.addView(four, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 0));

        TextView five = new TextView(mContext);
        five.setText("H");
        vert.addView(five, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 0));

        TextView six = new TextView(mContext);
        six.setText("F");
        vert.addView(six, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 0));
    }

    public static class ViewOne extends View {
        public ViewOne(Context context) {
            super(context);
        }
    }
}
