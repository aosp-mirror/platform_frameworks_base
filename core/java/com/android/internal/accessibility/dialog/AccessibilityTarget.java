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

package com.android.internal.accessibility.dialog;

import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.HARDWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.util.ShortcutUtils.optInValueToSettings;
import static com.android.internal.accessibility.util.ShortcutUtils.optOutValueFromSettings;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.Flags;

import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.accessibility.common.ShortcutConstants.AccessibilityFragmentType;
import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import com.android.internal.accessibility.dialog.TargetAdapter.ViewHolder;
import com.android.internal.accessibility.util.ShortcutUtils;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Abstract base class for creating various target related to accessibility service, accessibility
 * activity, and allowlisting features.
 *
 * <p> Disables accessibility features that are not permitted in adding a restricted padlock icon
 * and showing admin support message dialog.
 */
public abstract class AccessibilityTarget implements TargetOperations, OnTargetSelectedListener,
        OnTargetCheckedChangeListener {
    private Context mContext;
    @UserShortcutType
    private int mShortcutType;
    @AccessibilityFragmentType
    private int mFragmentType;
    private boolean mShortcutEnabled;
    private String mId;
    private int mUid;
    private ComponentName mComponentName;
    private CharSequence mLabel;
    private Drawable mIcon;
    private String mKey;
    private CharSequence mStateDescription;

    @VisibleForTesting
    public AccessibilityTarget(Context context, @UserShortcutType int shortcutType,
            @AccessibilityFragmentType int fragmentType, boolean isShortcutSwitched, String id,
            int uid, CharSequence label, Drawable icon, String key) {
        if (!isRecognizedShortcutType(shortcutType)) {
            throw new IllegalArgumentException(
                    "Unexpected shortcut type " + ShortcutUtils.convertToKey(shortcutType));
        }
        mContext = context;
        mShortcutType = shortcutType;
        mFragmentType = fragmentType;
        mShortcutEnabled = isShortcutSwitched;
        mId = id;
        mUid = uid;
        mComponentName = ComponentName.unflattenFromString(id);
        mLabel = label;
        mIcon = icon;
        mKey = key;
    }

    @Override
    public void updateActionItem(@NonNull ViewHolder holder,
            @ShortcutConstants.ShortcutMenuMode int shortcutMenuMode) {
        // Resetting the enable state of the item to avoid the previous wrong state of RecyclerView.
        holder.mCheckBoxView.setEnabled(true);
        holder.mIconView.setEnabled(true);
        holder.mLabelView.setEnabled(true);
        holder.mStatusView.setEnabled(true);

        final boolean isEditMenuMode =
                shortcutMenuMode == ShortcutConstants.ShortcutMenuMode.EDIT;
        holder.mCheckBoxView.setChecked(isEditMenuMode && isShortcutEnabled());
        holder.mCheckBoxView.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
        holder.mIconView.setImageDrawable(getIcon());
        holder.mLabelView.setText(getLabel());
        holder.mStatusView.setVisibility(View.GONE);
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onSelected() {
        final AccessibilityManager am =
                getContext().getSystemService(AccessibilityManager.class);
        if (am == null) {
            return;
        }
        am.performAccessibilityShortcut(getContext().getDisplayId(), mShortcutType, getId());
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onCheckedChanged(boolean isChecked) {
        setShortcutEnabled(isChecked);
        if (Flags.migrateEnableShortcuts()) {
            final AccessibilityManager am =
                    getContext().getSystemService(AccessibilityManager.class);
            am.enableShortcutsForTargets(
                    isChecked, getShortcutType(), Set.of(mId), UserHandle.myUserId());
        } else {
            if (isChecked) {
                optInValueToSettings(getContext(), getShortcutType(), getId());
            } else {
                optOutValueFromSettings(getContext(), getShortcutType(), getId());
            }
        }
    }

    public void setStateDescription(CharSequence stateDescription) {
        mStateDescription = stateDescription;
    }

    /**
     * Gets the state description of this feature target.
     *
     * @return the state description
     */
    @Nullable
    public CharSequence getStateDescription() {
        return mStateDescription;
    }

    public void setShortcutEnabled(boolean enabled) {
        mShortcutEnabled = enabled;
    }

    public Context getContext() {
        return mContext;
    }

    public @UserShortcutType int getShortcutType() {
        return mShortcutType;
    }

    public @AccessibilityFragmentType int getFragmentType() {
        return mFragmentType;
    }

    public boolean isShortcutEnabled() {
        return mShortcutEnabled;
    }

    public String getId() {
        return mId;
    }

    public int getUid() {
        return mUid;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public CharSequence getLabel() {
        return mLabel;
    }

    public Drawable getIcon() {
        return mIcon;
    }

    public String getKey() {
        return mKey;
    }

    /**
     * Determines if the provided shortcut type is valid for use with AccessibilityTargets.
     * @param shortcutType shortcut type to check.
     * @return {@code true} if the shortcut type can be used, {@code false} otherwise.
     */
    @VisibleForTesting
    public static boolean isRecognizedShortcutType(@UserShortcutType int shortcutType) {
        int mask = SOFTWARE | HARDWARE;
        return (shortcutType != 0 && (shortcutType & mask) == shortcutType);
    }
}
