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


package com.android.internal.app;

import static android.content.Context.ACTIVITY_SERVICE;

import static com.android.internal.app.ResolverListAdapter.ResolveInfoPresentationGetter;

import android.app.ActivityManager;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.R;
import com.android.internal.app.chooser.DisplayResolveInfo;
import com.android.internal.app.chooser.TargetInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a dialog with actions to take on a chooser target.
 */
public class ResolverTargetActionsDialogFragment extends DialogFragment
        implements DialogInterface.OnClickListener {

    private List<DisplayResolveInfo> mTargetInfos = new ArrayList<>();
    private UserHandle mUserHandle;

    public ResolverTargetActionsDialogFragment() {
    }

    public ResolverTargetActionsDialogFragment(List<DisplayResolveInfo> targets,
            UserHandle userHandle) {
        mUserHandle = userHandle;
        mTargetInfos = targets;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Bundle args = getArguments();
        final PackageManager pm = getContext().getPackageManager();

        // Pin item for each sub-item
        CharSequence[] items = new CharSequence[mTargetInfos.size()];
        for (int i = 0; i < mTargetInfos.size(); i++) {
            final TargetInfo ti = mTargetInfos.get(i);
            final CharSequence label = ti.getResolveInfo().loadLabel(pm);
            items[i] = ti.isPinned()
                     ? getResources().getString(R.string.unpin_specific_target, label)
                     : getResources().getString(R.string.pin_specific_target, label);
        }

        // Use the matching application icon and label for the title, any TargetInfo will do
        final ActivityManager am = (ActivityManager) getContext()
                .getSystemService(ACTIVITY_SERVICE);
        final int iconDpi = am.getLauncherLargeIconDensity();
        final ResolveInfoPresentationGetter pg = new ResolveInfoPresentationGetter(getContext(),
                iconDpi, mTargetInfos.get(0).getResolveInfo());

        return new Builder(getContext())
                .setTitle(pg.getLabel())
                .setIcon(pg.getIcon(mUserHandle))
                .setCancelable(true)
                .setItems(items, this)
                .create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        pinComponent(mTargetInfos.get(which).getResolvedComponentName());
        ((ChooserActivity) getActivity()).handlePackagesChanged();
        dismiss();
    }

    private void pinComponent(ComponentName name) {
        SharedPreferences sp = ChooserActivity.getPinnedSharedPrefs(getContext());
        final String key = name.flattenToString();
        boolean currentVal = sp.getBoolean(name.flattenToString(), false);
        if (currentVal) {
            sp.edit().remove(key).apply();
        } else {
            sp.edit().putBoolean(key, true).apply();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Dismiss on config changed (eg: rotation)
        // TODO: Maintain state on config change
        super.onConfigurationChanged(newConfig);
        dismiss();
    }

}
