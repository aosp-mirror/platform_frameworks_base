/*
 * Copyright (C) 2019 The Android Open Source Project
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


package com.android.internal.app;

import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import com.android.internal.app.chooser.DisplayResolveInfo;
import com.android.internal.app.chooser.MultiDisplayResolveInfo;

/**
 * Shows individual actions for a "stacked" app target - such as an app with multiple posting
 * streams represented in the Sharesheet.
 */
public class ChooserStackedAppDialogFragment extends ChooserTargetActionsDialogFragment
        implements DialogInterface.OnClickListener {

    private MultiDisplayResolveInfo mMultiDisplayResolveInfo;
    private int mParentWhich;

    public ChooserStackedAppDialogFragment() {
    }

    public ChooserStackedAppDialogFragment(MultiDisplayResolveInfo targets,
            int parentWhich, UserHandle userHandle) {
        super(targets.getTargets(), userHandle);
        mMultiDisplayResolveInfo = targets;
        mParentWhich = parentWhich;
    }

    @Override
    protected CharSequence getItemLabel(DisplayResolveInfo dri) {
        final PackageManager pm = getContext().getPackageManager();
        return dri.getResolveInfo().loadLabel(pm);
    }

    @Override
    protected Drawable getItemIcon(DisplayResolveInfo dri) {
        // Show no icon for the group disambig dialog, null hides the imageview
        return null;
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        mMultiDisplayResolveInfo.setSelected(which);
        ((ChooserActivity) getActivity()).startSelected(mParentWhich, false, true);
        dismiss();
    }
}
