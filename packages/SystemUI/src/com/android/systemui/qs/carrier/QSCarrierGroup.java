/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.carrier;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Displays Carrier name and network status in QS
 */
public class QSCarrierGroup extends LinearLayout {
    public QSCarrierGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    TextView getNoSimTextView() {
        return findViewById(R.id.no_carrier_text);
    }

    QSCarrier getCarrier1View() {
        return findViewById(R.id.carrier1);
    }

    QSCarrier getCarrier2View() {
        return findViewById(R.id.carrier2);
    }

    QSCarrier getCarrier3View() {
        return findViewById(R.id.carrier3);
    }

    View getCarrierDivider1() {
        return findViewById(R.id.qs_carrier_divider1);
    }

    View getCarrierDivider2() {
        return findViewById(R.id.qs_carrier_divider2);
    }
}
