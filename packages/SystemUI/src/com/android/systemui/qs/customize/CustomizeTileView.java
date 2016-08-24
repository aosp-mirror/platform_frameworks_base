/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.customize;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import com.android.systemui.R;
import com.android.systemui.qs.QSIconView;
import com.android.systemui.qs.QSTileView;
import libcore.util.Objects;

public class CustomizeTileView extends QSTileView {

    private TextView mAppLabel;
    private int mLabelMinLines;
    public CustomizeTileView(Context context, QSIconView icon) {
        super(context, icon);
    }

    @Override
    protected void createLabel() {
        super.createLabel();
        mLabelMinLines = mLabel.getMinLines();
        View view = LayoutInflater.from(mContext).inflate(R.layout.qs_tile_label, null);
        mAppLabel = (TextView) view.findViewById(R.id.tile_label);
        mAppLabel.setAlpha(.6f);
        mAppLabel.setSingleLine(true);
        addView(view);
    }

    public void setShowAppLabel(boolean showAppLabel) {
        mAppLabel.setVisibility(showAppLabel ? View.VISIBLE : View.GONE);
        mLabel.setSingleLine(showAppLabel);
        if (!showAppLabel) {
            mLabel.setMinLines(mLabelMinLines);
        }
    }

    public void setAppLabel(CharSequence label) {
        if (!Objects.equal(label, mAppLabel.getText())) {
            mAppLabel.setText(label);
        }
    }

    public TextView getAppLabel() {
        return mAppLabel;
    }
}
