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

package android.widget.listview;

import android.util.ListItemFactory;
import static android.util.ListItemFactory.Slot;
import android.util.ListScenario;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

public class ListHorizontalFocusWithinItemWins extends ListScenario {

    @Override
    protected void init(Params params) {
        params.setItemsFocusable(true)
                .setNumItems(2)
                .setItemScreenSizeFactor(0.2)
                .setMustFillScreen(false);
    }

    public Button getTopLeftButton() {
        return (Button) ((ViewGroup) getListView().getChildAt(0)).getChildAt(0);
    }

    public Button getTopRightButton() {
        return (Button) ((ViewGroup) getListView().getChildAt(0)).getChildAt(2);
    }

    public Button getBottomMiddleButton() {
        return (Button) ((ViewGroup) getListView().getChildAt(1)).getChildAt(1);
    }

    @Override
    protected View createView(int position, ViewGroup parent,
            int desiredHeight) {
        final Context context = parent.getContext();
        if (position == 0) {
            return ListItemFactory.horizontalButtonSlots(
                    context, desiredHeight, Slot.Left, Slot.Right);
        } else if (position == 1) {
            return ListItemFactory.horizontalButtonSlots(
                    context, desiredHeight, Slot.Middle);
        } else {
            throw new IllegalArgumentException("expecting position 0 or 1");
        }
    }
}
