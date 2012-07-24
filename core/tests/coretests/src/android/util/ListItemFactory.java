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

package android.util;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Reusable methods for creating more complex list items.
 */
public class ListItemFactory {

    /**
     * Create a view with a button at the top and bottom, with filler in between.
     * The filler is sized to take up any space left over within desiredHeight.
     *
     * @param position      The position within the list.
     * @param context       The context.
     * @param desiredHeight The desired height of the entire view.
     * @return The created view.
     */
    public static View twoButtonsSeparatedByFiller(int position, Context context, int desiredHeight) {
        if (desiredHeight < 90) {
            throw new IllegalArgumentException("need at least 90 pixels of height " +
                    "to create the two buttons and leave 10 pixels for the filler");
        }

        final LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout.LayoutParams buttonLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        50);

        final Button topButton = new Button(context);
        topButton.setLayoutParams(
                buttonLp);
        topButton.setText("top (position " + position + ")");
        ll.addView(topButton);

        final TextView middleFiller = new TextView(context);
        middleFiller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                desiredHeight - 100));
        middleFiller.setText("filler");
        ll.addView(middleFiller);

        final Button bottomButton = new Button(context);
        bottomButton.setLayoutParams(buttonLp);
        bottomButton.setText("bottom (position " + position + ")");
        ll.addView(bottomButton);
        ll.setTag("twoButtons");
        return ll;
    }

    public enum Slot {
        Left,
        Middle,
        Right
    }

    /**
     * Create a horizontal linear layout divided into thirds (with some margins
     * separating the thirds), filled with buttons into some slots.
     * @param context The context.
     * @param desiredHeight The height of the LL.
     * @param slots Which slots to fill with buttons.
     * @return The linear layout.
     */
    public static View horizontalButtonSlots(Context context, int desiredHeight, Slot... slots) {

        final LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.HORIZONTAL);

        final LinearLayout.LayoutParams lp
                = new LinearLayout.LayoutParams(0, desiredHeight);
        lp.setMargins(10, 0, 10, 0);
        lp.weight = 0.33f;

        boolean left = false;
        boolean middle = false;
        boolean right = false;
        for (Slot slot : slots) {
            switch (slot) {
                case Left:
                    left = true;
                    break;
                case Middle:
                    middle = true;
                    break;
                case Right:
                    right = true;
                    break;
            }
        }

        if (left) {
            final Button button = new Button(context);
            button.setText("left");
            ll.addView(button, lp);
        } else {
           ll.addView(new View(context), lp);
        }

        if (middle) {
            final Button button = new Button(context);
            button.setText("center");
            ll.addView(button, lp);
        } else {
           ll.addView(new View(context), lp);
        }

        if (right) {
            final Button button = new Button(context);
            button.setText("right");
            ll.addView(button, lp);
        } else {
           ll.addView(new View(context), lp);
        }

        return ll;
    }


    /**
     * Create a button ready to be a list item.
     *
     * @param position      The position within the list.
     * @param context       The context.
     * @param text          The text of the button
     * @param desiredHeight The desired height of the button
     * @return The created view.
     */
    public static View button(int position, Context context, String text, int desiredHeight) {
        TextView result = new Button(context);
        result.setHeight(desiredHeight);
        result.setText(text);
        final ViewGroup.LayoutParams lp = new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        result.setLayoutParams(lp);
        result.setId(position);
        result.setTag("button");
        return result;
    }
    
    /**
     * Convert an existing button view to display the data at a new position.
     * 
     * @param convertView Non-null Button created by {@link #button}
     * @param text The text of the button
     * @param position The position withion the list
     * @return The converted view
     */
    public static View convertButton(View convertView, String text, int position) {
        if (((String) convertView.getTag()).equals("button")) {
            ((Button) convertView).setText(text);
            convertView.setId(position);
            return convertView;
        } else {
            return null;
        }
    }
    
    /**
     * Create a text view ready to be a list item.
     *
     * @param position      The position within the list.
     * @param context       The context.
     * @param text          The text to display
     * @param desiredHeight The desired height of the text view
     * @return The created view.
     */
    public static View text(int position, Context context, String text, int desiredHeight) {
        TextView result = new TextView(context);
        result.setHeight(desiredHeight);
        result.setText(text);
        final ViewGroup.LayoutParams lp = new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        result.setLayoutParams(lp);
        result.setId(position);
        result.setTag("text");
        return result;
    }
    
    /**
     * Convert an existing text view to display the data at a new position.
     * 
     * @param convertView Non-null TextView created by {@link #text}
     * @param text The text to display
     * @param position The position withion the list
     * @return The converted view
     */
    public static View convertText(View convertView, String text, int position) {
        if(convertView.getTag() != null && ((String) convertView.getTag()).equals("text")) {
            ((TextView) convertView).setText(text);
            convertView.setId(position);
            return convertView;

        } else {
            return null;
        }
    }
    
    /**
     * Create a text view ready to be a list item.
     *
     * @param position      The position within the list.
     * @param context       The context.
     * @param text          The text of the button
     * @param desiredHeight The desired height of the button
     * @return The created view.
     */
    public static View doubleText(int position, Context context, String text, int desiredHeight) {
        final LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.HORIZONTAL);

        final AbsListView.LayoutParams lp =
                new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        desiredHeight);
        ll.setLayoutParams(lp);
        ll.setId(position);
        
        TextView t1 = new TextView(context);
        t1.setHeight(desiredHeight);
        t1.setText(text);
        t1.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        final ViewGroup.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        ll.addView(t1, lp1);
        
        TextView t2 = new TextView(context);
        t2.setHeight(desiredHeight);
        t2.setText(text);
        t2.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        final ViewGroup.LayoutParams lp2 = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);

        ll.addView(t2, lp2);
        ll.setTag("double");
        return ll;
    }

    
    /**
     * Convert an existing button view to display the data at a new position.
     * 
     * @param convertView Non-null view created by {@link #doubleText}
     * @param text The text of the button
     * @param position The position withion the list
     * @return The converted view
     */
    public static View convertDoubleText(View convertView, String text, int position) {
        if (((String) convertView.getTag()).equals("double")) {
            TextView t1 = (TextView) ((LinearLayout) convertView).getChildAt(0);
            TextView t2 = (TextView) ((LinearLayout) convertView).getChildAt(1);
            t1.setText(text);
            t2.setText(text);
            convertView.setId(position);
            return convertView;
        } else {
            return null;
        }
    }
}
