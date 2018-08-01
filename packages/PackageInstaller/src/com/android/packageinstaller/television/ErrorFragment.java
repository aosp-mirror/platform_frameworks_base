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
 * limitations under the License
 */

package com.android.packageinstaller.television;

import android.app.Activity;
import android.os.Bundle;
import androidx.leanback.app.GuidedStepFragment;
import androidx.leanback.widget.GuidanceStylist;
import androidx.leanback.widget.GuidedAction;

import com.android.packageinstaller.R;
import com.android.packageinstaller.UninstallerActivity;

import java.util.List;

public class ErrorFragment extends GuidedStepFragment {
    public static final String TITLE = "com.android.packageinstaller.arg.title";
    public static final String TEXT = "com.android.packageinstaller.arg.text";

    @Override
    public int onProvideTheme() {
        return R.style.Theme_Leanback_GuidedStep;
    }

    @Override
    public GuidanceStylist.Guidance onCreateGuidance(Bundle savedInstanceState) {
        return new GuidanceStylist.Guidance(
                getString(getArguments().getInt(TITLE)),
                getString(getArguments().getInt(TEXT)),
                null,
                null);
    }

    @Override
    public void onCreateActions(List<GuidedAction> actions, Bundle savedInstanceState) {
        actions.add(new GuidedAction.Builder(getContext())
                .clickAction(GuidedAction.ACTION_ID_OK)
                .build());
    }

    @Override
    public void onGuidedActionClicked(GuidedAction action) {
        if (isAdded()) {
            if (getActivity() instanceof UninstallerActivity) {
                ((UninstallerActivity) getActivity()).dispatchAborted();
            }

            getActivity().setResult(Activity.RESULT_FIRST_USER);
            getActivity().finish();
        }
    }
}
