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

import android.content.Context;
import android.widget.ListScenario;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.TextView;

/**
 * A list where each item expands by 1.5 when selected.
 */
public class ListItemsExpandOnSelection extends ListScenario {


    @Override
    protected void init(Params params) {
        params.setNumItems(10)
                .setItemScreenSizeFactor(1.0/5);
    }


    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        TextView result = new ExpandWhenSelectedView(parent.getContext(), desiredHeight);
        result.setHeight(desiredHeight);
        result.setFocusable(mItemsFocusable);
        result.setText(getValueAtPosition(position));
        final AbsListView.LayoutParams lp = new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        result.setLayoutParams(lp);
        return result;
    }

    
    @Override
    public View convertView(int position, View convertView, ViewGroup parent) {
        ((ExpandWhenSelectedView)convertView).setText(getValueAtPosition(position));
        return convertView;
    }


    static private class ExpandWhenSelectedView extends TextView {

        private final int mDesiredHeight;

        public ExpandWhenSelectedView(Context context, int desiredHeight) {
            super(context);
            mDesiredHeight = desiredHeight;
        }

        @Override
        public void setSelected(boolean selected) {
            super.setSelected(selected);
            if (selected) {
                setHeight((int) (mDesiredHeight * 1.5));
            } else {
                setHeight(mDesiredHeight);
            }
        }
    }
}
