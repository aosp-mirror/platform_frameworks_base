/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.autofill.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.android.internal.R;
import com.android.internal.widget.ButtonBarLayout;

/** An extension of {@link ButtonBarLayout} for use in Autofill bottom sheets. */
public class BottomSheetButtonBarLayout extends ButtonBarLayout {

    public BottomSheetButtonBarLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final View spacer = findViewById(R.id.autofill_button_bar_spacer);
        if (spacer == null) {
            return;
        }
        if (isStacked()) {
            spacer.getLayoutParams().width = 0;
            spacer.getLayoutParams().height =
                    getResources().getDimensionPixelSize(R.dimen.autofill_button_bar_spacer_height);
            setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        } else {
            spacer.getLayoutParams().width =
                    getResources().getDimensionPixelSize(R.dimen.autofill_button_bar_spacer_width);
            spacer.getLayoutParams().height = 0;
            setGravity(Gravity.CENTER_VERTICAL);
        }
    }

    private boolean isStacked() {
        return getOrientation() == LinearLayout.VERTICAL;
    }
}
