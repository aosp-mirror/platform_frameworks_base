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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.InternalSelectionView;
import android.util.ListScenario;

/**
 * Each item is an internal selection view, a button, and some filler
 */
public class ListItemISVAndButton extends ListScenario {


    @Override
    protected void init(Params params) {
        params.setItemScreenSizeFactor(2.0)
                .setNumItems(3)
                .setItemsFocusable(true);
    }

    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        Context context = parent.getContext();

        final LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);

        final InternalSelectionView isv = new InternalSelectionView(context, 8, "ISV postion " + position);
        isv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                desiredHeight - 240));
        ll.addView(isv);

        final LinearLayout.LayoutParams buttonLp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        40);
        final Button topButton = new Button(context);
        topButton.setLayoutParams(
                buttonLp);
        topButton.setText("button " + position + ")");
        ll.addView(topButton);

        final TextView filler = new TextView(context);
        filler.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                200));
        filler.setText("filler");
        ll.addView(filler);


        return ll;
    }

}
