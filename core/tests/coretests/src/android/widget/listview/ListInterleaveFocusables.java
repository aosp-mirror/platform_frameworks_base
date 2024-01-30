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

import android.util.ListItemFactory;
import android.widget.ListScenario;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.collect.Sets;

import java.util.Set;

/**
 * List that interleaves focusable items.
 */
public class ListInterleaveFocusables extends ListScenario {

    private Set<Integer> mFocusablePositions = Sets.newHashSet(1, 3, 6);

    @Override
    protected void init(Params params) {
        params.setNumItems(7)
                .setItemScreenSizeFactor(1.0 / 8)
                .setItemsFocusable(true)
                .setMustFillScreen(false);
    }

    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        if (mFocusablePositions.contains(position)) {
            return ListItemFactory.button(
                    position, parent.getContext(), getValueAtPosition(position), desiredHeight);
        } else {
            return super.createView(position, parent, desiredHeight);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return mFocusablePositions.contains(position) ? 0 : 1;
    }

    @Override
    public int getViewTypeCount() {
        return 2;
    }
    
    
}
