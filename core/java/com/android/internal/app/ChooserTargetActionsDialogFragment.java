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

import static java.util.stream.Collectors.toList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.app.chooser.DisplayResolveInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a dialog with actions to take on a chooser target.
 */
public class ChooserTargetActionsDialogFragment extends DialogFragment
        implements DialogInterface.OnClickListener {

    protected List<DisplayResolveInfo> mTargetInfos = new ArrayList<>();
    protected UserHandle mUserHandle;

    public ChooserTargetActionsDialogFragment() {
    }

    public ChooserTargetActionsDialogFragment(List<DisplayResolveInfo> targets,
            UserHandle userHandle) {
        mUserHandle = userHandle;
        mTargetInfos = targets;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {

        // Fetch UI details from target info
        List<Pair<CharSequence, Drawable>> items = mTargetInfos.stream().map(dri -> {
            return new Pair<>(getItemLabel(dri), getItemIcon(dri));
        }).collect(toList());

        final ResolveInfoPresentationGetter pg = getProvidingAppPresentationGetter();
        return new Builder(getContext())
                .setTitle(pg.getLabel())
                .setIcon(pg.getIcon(mUserHandle))
                .setCancelable(true)
                .setAdapter(getAdapterForContent(items), this)
                .create();
    }

    protected ArrayAdapter<Pair<CharSequence, Drawable>> getAdapterForContent(
            List<Pair<CharSequence, Drawable>> items) {
        return new ArrayAdapter<Pair<CharSequence, Drawable>>(getContext(),
                R.layout.chooser_dialog_item, R.id.text, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v =  super.getView(position, convertView, parent); // super recycles views
                TextView label = v.findViewById(R.id.text);
                ImageView icon = v.findViewById(R.id.icon);

                Pair<CharSequence, Drawable> pair = getItem(position);
                label.setText(pair.first);

                // Hide icon view if one isn't available
                if (pair.second == null) {
                    icon.setVisibility(View.GONE);
                } else {
                    icon.setImageDrawable(pair.second);
                    icon.setVisibility(View.VISIBLE);
                }

                return v;
            }
        };
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

    private Drawable getPinIcon(boolean isPinned) {
        return isPinned
                ? getContext().getDrawable(R.drawable.ic_close)
                : getContext().getDrawable(R.drawable.ic_chooser_pin_dialog);
    }

    private CharSequence getPinLabel(boolean isPinned, CharSequence targetLabel) {
        return isPinned
                ? getResources().getString(R.string.unpin_specific_target, targetLabel)
                : getResources().getString(R.string.pin_specific_target, targetLabel);
    }

    @NonNull
    protected CharSequence getItemLabel(DisplayResolveInfo dri) {
        final PackageManager pm = getContext().getPackageManager();
        return getPinLabel(dri.isPinned(), dri.getResolveInfo().loadLabel(pm));
    }

    @Nullable
    protected Drawable getItemIcon(DisplayResolveInfo dri) {
        return getPinIcon(dri.isPinned());
    }

    private ResolveInfoPresentationGetter getProvidingAppPresentationGetter() {
        final ActivityManager am = (ActivityManager) getContext()
                .getSystemService(ACTIVITY_SERVICE);
        final int iconDpi = am.getLauncherLargeIconDensity();

        // Use the matching application icon and label for the title, any TargetInfo will do
        return new ResolveInfoPresentationGetter(getContext(), iconDpi,
                mTargetInfos.get(0).getResolveInfo());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Dismiss on config changed (eg: rotation)
        // TODO: Maintain state on config change
        super.onConfigurationChanged(newConfig);
        dismiss();
    }

}
