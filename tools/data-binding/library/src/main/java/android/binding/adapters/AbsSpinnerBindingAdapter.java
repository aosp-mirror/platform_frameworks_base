/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.binding.adapters;

import android.binding.BindingAdapter;
import android.widget.AbsSpinner;
import android.widget.ArrayAdapter;
import android.widget.SpinnerAdapter;

public class AbsSpinnerBindingAdapter {

    @BindingAdapter("android:entries")
    public static void setEntries(AbsSpinner view, CharSequence[] entries) {
        if (entries != null) {
            SpinnerAdapter oldAdapter = view.getAdapter();
            boolean changed = true;
            if (oldAdapter.getCount() == entries.length) {
                changed = false;
                for (int i = 0; i < entries.length; i++) {
                    if (!entries[i].toString().equals(oldAdapter.getItem(i))) {
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) {
                ArrayAdapter<CharSequence> adapter =
                        new ArrayAdapter<CharSequence>(view.getContext(),
                                android.R.layout.simple_spinner_item, entries);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                view.setAdapter(adapter);
            }
        } else {
            view.setAdapter(null);
        }
    }
}
