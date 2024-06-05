/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.shade.carrier;

import android.annotation.StyleRes;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.FontSizeUtils;
import com.android.systemui.res.R;

/**
 * Displays Carrier name and network status in the shade header
 */
public class ShadeCarrierGroup extends LinearLayout {
    public ShadeCarrierGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    TextView getNoSimTextView() {
        return findViewById(R.id.no_carrier_text);
    }

    ShadeCarrier getCarrier1View() {
        return findViewById(R.id.carrier1);
    }

    ShadeCarrier getCarrier2View() {
        return findViewById(R.id.carrier2);
    }

    ShadeCarrier getCarrier3View() {
        return findViewById(R.id.carrier3);
    }

    View getCarrierDivider1() {
        return findViewById(R.id.shade_carrier_divider1);
    }

    View getCarrierDivider2() {
        return findViewById(R.id.shade_carrier_divider2);
    }

    public void updateTextAppearance(@StyleRes int resId) {
        FontSizeUtils.updateFontSizeFromStyle(getNoSimTextView(), resId);
        getCarrier1View().updateTextAppearance(resId);
        getCarrier2View().updateTextAppearance(resId);
        getCarrier3View().updateTextAppearance(resId);
    }
}
