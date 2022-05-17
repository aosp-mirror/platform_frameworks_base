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
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.app.chooser.DisplayResolveInfo;
import com.android.internal.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shows a dialog with actions to take on a chooser target.
 */
public class ChooserTargetActionsDialogFragment extends DialogFragment
        implements DialogInterface.OnClickListener {

    protected ArrayList<DisplayResolveInfo> mTargetInfos = new ArrayList<>();
    protected UserHandle mUserHandle;
    protected String mShortcutId;
    protected String mShortcutTitle;
    protected boolean mIsShortcutPinned;
    protected IntentFilter mIntentFilter;

    public static final String USER_HANDLE_KEY = "user_handle";
    public static final String TARGET_INFOS_KEY = "target_infos";
    public static final String SHORTCUT_ID_KEY = "shortcut_id";
    public static final String SHORTCUT_TITLE_KEY = "shortcut_title";
    public static final String IS_SHORTCUT_PINNED_KEY = "is_shortcut_pinned";
    public static final String INTENT_FILTER_KEY = "intent_filter";

    public ChooserTargetActionsDialogFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            setStateFromBundle(savedInstanceState);
        } else {
            setStateFromBundle(getArguments());
        }
    }

    void setStateFromBundle(Bundle b) {
        mTargetInfos = (ArrayList<DisplayResolveInfo>) b.get(TARGET_INFOS_KEY);
        mUserHandle = (UserHandle) b.get(USER_HANDLE_KEY);
        mShortcutId = b.getString(SHORTCUT_ID_KEY);
        mShortcutTitle = b.getString(SHORTCUT_TITLE_KEY);
        mIsShortcutPinned = b.getBoolean(IS_SHORTCUT_PINNED_KEY);
        mIntentFilter = (IntentFilter) b.get(INTENT_FILTER_KEY);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(ChooserTargetActionsDialogFragment.USER_HANDLE_KEY,
                mUserHandle);
        outState.putParcelableArrayList(ChooserTargetActionsDialogFragment.TARGET_INFOS_KEY,
                mTargetInfos);
        outState.putString(ChooserTargetActionsDialogFragment.SHORTCUT_ID_KEY, mShortcutId);
        outState.putBoolean(ChooserTargetActionsDialogFragment.IS_SHORTCUT_PINNED_KEY,
                mIsShortcutPinned);
        outState.putString(ChooserTargetActionsDialogFragment.SHORTCUT_TITLE_KEY, mShortcutTitle);
        outState.putParcelable(ChooserTargetActionsDialogFragment.INTENT_FILTER_KEY, mIntentFilter);
    }

    /**
     * Recreate the layout from scratch to match new Sharesheet redlines
     */
    @Override
    public View onCreateView(LayoutInflater inflater,
            @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            setStateFromBundle(savedInstanceState);
        } else {
            setStateFromBundle(getArguments());
        }
        // Make the background transparent to show dialog rounding
        Optional.of(getDialog()).map(Dialog::getWindow)
                .ifPresent(window -> {
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                });

        // Fetch UI details from target info
        List<Pair<Drawable, CharSequence>> items = mTargetInfos.stream().map(dri -> {
            return new Pair<>(getItemIcon(dri), getItemLabel(dri));
        }).collect(toList());

        View v = inflater.inflate(R.layout.chooser_dialog, container, false);

        TextView title = v.findViewById(R.id.title);
        ImageView icon = v.findViewById(R.id.icon);
        RecyclerView rv = v.findViewById(R.id.listContainer);

        final ResolveInfoPresentationGetter pg = getProvidingAppPresentationGetter();
        title.setText(isShortcutTarget() ? mShortcutTitle : pg.getLabel());
        icon.setImageDrawable(pg.getIcon(mUserHandle));
        rv.setAdapter(new VHAdapter(items));

        return v;
    }

    class VHAdapter extends RecyclerView.Adapter<VH> {

        List<Pair<Drawable, CharSequence>> mItems;

        VHAdapter(List<Pair<Drawable, CharSequence>> items) {
            mItems = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.chooser_dialog_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.bind(mItems.get(position), position);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }
    }

    class VH extends RecyclerView.ViewHolder {
        TextView mLabel;
        ImageView mIcon;

        VH(@NonNull View itemView) {
            super(itemView);
            mLabel = itemView.findViewById(R.id.text);
            mIcon = itemView.findViewById(R.id.icon);
        }

        public void bind(Pair<Drawable, CharSequence> item, int position) {
            mLabel.setText(item.second);

            if (item.first == null) {
                mIcon.setVisibility(View.GONE);
            } else {
                mIcon.setVisibility(View.VISIBLE);
                mIcon.setImageDrawable(item.first);
            }

            itemView.setOnClickListener(v -> onClick(getDialog(), position));
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (isShortcutTarget()) {
            toggleShortcutPinned(mTargetInfos.get(which).getResolvedComponentName());
        } else {
            pinComponent(mTargetInfos.get(which).getResolvedComponentName());
        }
        ((ChooserActivity) getActivity()).handlePackagesChanged();
        dismiss();
    }

    private void toggleShortcutPinned(ComponentName name) {
        if (mIntentFilter == null) {
            return;
        }
        // Fetch existing pinned shortcuts of the given package.
        List<String> pinnedShortcuts = getPinnedShortcutsFromPackageAsUser(getContext(),
                mUserHandle, mIntentFilter, name.getPackageName());
        // If the shortcut has already been pinned, unpin it; otherwise, pin it.
        if (mIsShortcutPinned) {
            pinnedShortcuts.remove(mShortcutId);
        } else {
            pinnedShortcuts.add(mShortcutId);
        }
        // Update pinned shortcut list in ShortcutService via LauncherApps
        getContext().getSystemService(LauncherApps.class).pinShortcuts(
                name.getPackageName(), pinnedShortcuts, mUserHandle);
    }

    private static List<String> getPinnedShortcutsFromPackageAsUser(Context context,
            UserHandle user, IntentFilter filter, String packageName) {
        Context contextAsUser = context.createContextAsUser(user, 0 /* flags */);
        List<ShortcutManager.ShareShortcutInfo> targets = contextAsUser.getSystemService(
                ShortcutManager.class).getShareTargets(filter);
        return targets.stream()
                .map(ShortcutManager.ShareShortcutInfo::getShortcutInfo)
                .filter(s -> s.isPinned() && s.getPackage().equals(packageName))
                .map(ShortcutInfo::getId)
                .collect(Collectors.toList());
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
        return getPinLabel(isPinned(dri),
                isShortcutTarget() ? mShortcutTitle : dri.getResolveInfo().loadLabel(pm));
    }

    @Nullable
    protected Drawable getItemIcon(DisplayResolveInfo dri) {
        return getPinIcon(isPinned(dri));
    }

    private ResolveInfoPresentationGetter getProvidingAppPresentationGetter() {
        final ActivityManager am = (ActivityManager) getContext()
                .getSystemService(ACTIVITY_SERVICE);
        final int iconDpi = am.getLauncherLargeIconDensity();

        // Use the matching application icon and label for the title, any TargetInfo will do
        return new ResolveInfoPresentationGetter(getContext(), iconDpi,
                mTargetInfos.get(0).getResolveInfo());
    }

    private boolean isPinned(DisplayResolveInfo dri) {
        return isShortcutTarget() ? mIsShortcutPinned : dri.isPinned();
    }

    private boolean isShortcutTarget() {
        return mShortcutId != null;
    }
}
