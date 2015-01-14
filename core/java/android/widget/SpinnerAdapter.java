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

package android.widget;

import android.view.View;
import android.view.ViewGroup;

/**
 * Extended {@link Adapter} that is the bridge between a
 * {@link android.widget.Spinner} and its data. A spinner adapter allows to
 * define two different views: one that shows the data in the spinner itself
 * and one that shows the data in the drop down list when the spinner is
 * pressed.
 */
public interface SpinnerAdapter extends Adapter {
    /**
     * Gets a {@link android.view.View} that displays in the drop down popup
     * the data at the specified position in the data set.
     *
     * @param position index of the item whose view we want.
     * @param convertView the old view to reuse, if possible. Note: You should
     *        check that this view is non-null and of an appropriate type before
     *        using. If it is not possible to convert this view to display the
     *        correct data, this method can create a new view.
     * @param parent the parent that this view will eventually be attached to
     * @return a {@link android.view.View} corresponding to the data at the
     *         specified position.
     */
    public View getDropDownView(int position, View convertView, ViewGroup parent);
}
