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

package android.widget;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.GetChars;
import android.view.View;

import com.android.frameworks.coretests.R;

/**
 * TextViewTest tests {@link TextView}.
 */
public class TextViewTest extends ActivityInstrumentationTestCase2<TextViewTestActivity> {

    public TextViewTest() {
        super(TextViewTestActivity.class);
    }

    @SmallTest
    public void testArray() throws Exception {
        TextView tv = new TextView(getActivity());

        char[] c = new char[] { 'H', 'e', 'l', 'l', 'o', ' ',
                                'W', 'o', 'r', 'l', 'd', '!' };

        tv.setText(c, 1, 4);
        CharSequence oldText = tv.getText();

        tv.setText(c, 4, 5);
        CharSequence newText = tv.getText();

        assertTrue(newText == oldText);

        assertEquals(5, newText.length());
        assertEquals('o', newText.charAt(0));
        assertEquals("o Wor", newText.toString());

        assertEquals(" Wo", newText.subSequence(1, 4));

        char[] c2 = new char[7];
        ((GetChars) newText).getChars(1, 4, c2, 2);
        assertEquals('\0', c2[1]);
        assertEquals(' ', c2[2]);
        assertEquals('W', c2[3]);
        assertEquals('o', c2[4]);
        assertEquals('\0', c2[5]);
    }

    @SmallTest
    public void testTextDirectionDefault() {
        TextView tv = new TextView(getActivity());
        assertEquals(View.TEXT_DIRECTION_INHERIT, tv.getTextDirection());
    }

    @SmallTest
    public void testSetGetTextDirection() {
        TextView tv = new TextView(getActivity());

        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        assertEquals(View.TEXT_DIRECTION_ANY_RTL, tv.getTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_INHERIT);
        assertEquals(View.TEXT_DIRECTION_INHERIT, tv.getTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
        assertEquals(View.TEXT_DIRECTION_LTR, tv.getTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        assertEquals(View.TEXT_DIRECTION_RTL, tv.getTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        assertEquals(View.TEXT_DIRECTION_LOCALE, tv.getTextDirection());
    }

    @SmallTest
    public void testGetResolvedTextDirectionLtr() {
        TextView tv = new TextView(getActivity());
        tv.setText("this is a test");

        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        assertEquals(View.TEXT_DIRECTION_ANY_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_INHERIT);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
        assertEquals(View.TEXT_DIRECTION_LTR, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        assertEquals(View.TEXT_DIRECTION_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        assertEquals(View.TEXT_DIRECTION_LOCALE, tv.getResolvedTextDirection());
    }

    @SmallTest
    public void testGetResolvedTextDirectionLtrWithInheritance() {
        LinearLayout ll = new LinearLayout(getActivity());
        ll.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);

        TextView tv = new TextView(getActivity());
        tv.setText("this is a test");
        ll.addView(tv);

        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        assertEquals(View.TEXT_DIRECTION_ANY_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_INHERIT);
        assertEquals(View.TEXT_DIRECTION_ANY_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
        assertEquals(View.TEXT_DIRECTION_LTR, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        assertEquals(View.TEXT_DIRECTION_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        assertEquals(View.TEXT_DIRECTION_LOCALE, tv.getResolvedTextDirection());
    }

    @SmallTest
    public void testGetResolvedTextDirectionRtl() {
        TextView tv = new TextView(getActivity());
        tv.setText("\u05DD\u05DE"); // hebrew

        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        assertEquals(View.TEXT_DIRECTION_ANY_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_INHERIT);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
        assertEquals(View.TEXT_DIRECTION_LTR, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        assertEquals(View.TEXT_DIRECTION_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        assertEquals(View.TEXT_DIRECTION_LOCALE, tv.getResolvedTextDirection());
    }

    @SmallTest
    public void testGetResolvedTextDirectionRtlWithInheritance() {
        LinearLayout ll = new LinearLayout(getActivity());
        ll.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);

        TextView tv = new TextView(getActivity());
        tv.setText("\u05DD\u05DE"); // hebrew
        ll.addView(tv);

        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        assertEquals(View.TEXT_DIRECTION_ANY_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_INHERIT);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
        assertEquals(View.TEXT_DIRECTION_LTR, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        assertEquals(View.TEXT_DIRECTION_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        assertEquals(View.TEXT_DIRECTION_LOCALE, tv.getResolvedTextDirection());

        // Force to RTL text direction on the layout
        ll.setTextDirection(View.TEXT_DIRECTION_RTL);

        tv.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        assertEquals(View.TEXT_DIRECTION_ANY_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_INHERIT);
        assertEquals(View.TEXT_DIRECTION_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
        assertEquals(View.TEXT_DIRECTION_LTR, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        assertEquals(View.TEXT_DIRECTION_RTL, tv.getResolvedTextDirection());

        tv.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        assertEquals(View.TEXT_DIRECTION_LOCALE, tv.getResolvedTextDirection());
    }

    @SmallTest
    public void testResetTextDirection() {
        final TextViewTestActivity activity = getActivity();

        final LinearLayout ll = (LinearLayout) activity.findViewById(R.id.textviewtest_layout);
        final TextView tv = (TextView) activity.findViewById(R.id.textviewtest_textview);

        getActivity().runOnUiThread(new Runnable() {
            public void run() {
                ll.setTextDirection(View.TEXT_DIRECTION_RTL);
                tv.setTextDirection(View.TEXT_DIRECTION_INHERIT);
                assertEquals(View.TEXT_DIRECTION_RTL, tv.getResolvedTextDirection());

                ll.removeView(tv);
                assertEquals(View.TEXT_DIRECTION_FIRST_STRONG, tv.getResolvedTextDirection());
            }
        });
    }
}
