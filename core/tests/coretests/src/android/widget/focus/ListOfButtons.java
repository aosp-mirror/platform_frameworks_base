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

import com.android.frameworks.coretests.R;

import android.app.ListActivity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;

/**
 * A layout with a ListView containing buttons.
 */
public class ListOfButtons extends ListActivity {


    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.list_with_button_above);
        getListView().setItemsCanFocus(true);
        setListAdapter(new MyAdapter(this, mLabels));
    }

    String[] mLabels = {
            "Alabama", "Alaska", "Arizona", "apple sauce!",
            "California", "Colorado", "Connecticut", "Delaware"
    };


    public String[] getLabels() {
        return mLabels;
    }

    public static class MyAdapter extends ArrayAdapter<String> {


        public MyAdapter(Context context, String[] labels) {
            super(context, 0, labels);
        }


        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            String label = getItem(position);

            Button button = new Button(parent.getContext());
            button.setText(label);
            return button;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }
    }
}
