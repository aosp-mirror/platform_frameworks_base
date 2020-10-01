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
import android.view.View;
import android.widget.TextView;

import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.tileimpl.QSTileView;

public class CustomizeTileView extends QSTileView {
    private boolean mShowAppLabel;

    public CustomizeTileView(Context context, QSIconView icon) {
        super(context, icon);
    }

    public void setShowAppLabel(boolean showAppLabel) {
        mShowAppLabel = showAppLabel;
        mSecondLine.setVisibility(showAppLabel ? View.VISIBLE : View.GONE);
        mLabel.setSingleLine(showAppLabel);
    }

    @Override
    protected void handleStateChanged(QSTile.State state) {
        super.handleStateChanged(state);
        mSecondLine.setVisibility(mShowAppLabel ? View.VISIBLE : View.GONE);
    }

    public TextView getAppLabel() {
        return mSecondLine;
    }

    @Override
    protected boolean animationsEnabled() {
        return false;
    }

    @Override
    public boolean isLongClickable() {
        return false;
    }
}
