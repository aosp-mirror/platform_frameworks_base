/*
* Copyright (C) 2016 The Android Open Source Project
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

package com.android.packageinstaller.permission.ui.handheld;

import android.content.Context;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.Switch;

class MultiTargetSwitchPreference extends SwitchPreference {
    private View.OnClickListener mSwitchOnClickLister;

    public MultiTargetSwitchPreference(Context context) {
        super(context);
    }

    public void setCheckedOverride(boolean checked) {
        super.setChecked(checked);
    }

    @Override
    public void setChecked(boolean checked) {
        // If double target behavior is enabled do nothing
        if (mSwitchOnClickLister == null) {
            super.setChecked(checked);
        }
    }

    public void setSwitchOnClickListener(View.OnClickListener listener) {
        mSwitchOnClickLister = listener;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Switch switchView = (Switch) view.findViewById(
                com.android.internal.R.id.switch_widget);
        switchView.setOnClickListener(mSwitchOnClickLister);

        if (mSwitchOnClickLister != null) {
            final int padding = (int) ((view.getMeasuredHeight()
                    - switchView.getMeasuredHeight()) / 2 + 0.5f);
            switchView.setPadding(padding, padding, 0, padding);
        }
    }
}