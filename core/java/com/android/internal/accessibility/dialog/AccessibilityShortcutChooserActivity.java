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

import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;
import static android.view.accessibility.AccessibilityManager.ShortcutType;

import static com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.createEnableDialogContentView;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getInstalledTargets;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;
import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity used to display various targets related to accessibility service, accessibility
 * activity or white listing feature for volume key shortcut.
 */
public class AccessibilityShortcutChooserActivity extends Activity {
    @ShortcutType
    private int mShortcutType;
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();
    private AlertDialog mMenuDialog;
    private AlertDialog mPermissionDialog;
    private ShortcutTargetAdapter mTargetAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final TypedArray theme = getTheme().obtainStyledAttributes(android.R.styleable.Theme);
        if (!theme.getBoolean(android.R.styleable.Theme_windowNoTitle, /* defValue= */ false)) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        mShortcutType = getIntent().getIntExtra(AccessibilityManager.EXTRA_SHORTCUT_TYPE,
                /* unexpectedShortcutType */ -1);
        final boolean existInShortcutType = (mShortcutType == ACCESSIBILITY_BUTTON)
                || (mShortcutType == ACCESSIBILITY_SHORTCUT_KEY);
        checkArgument(existInShortcutType, "Unexpected shortcut type: " + mShortcutType);

        mTargets.addAll(getTargets(this, mShortcutType));

        final String selectDialogTitle =
                getString(R.string.accessibility_select_shortcut_menu_title);
        mTargetAdapter = new ShortcutTargetAdapter(mTargets);
        mMenuDialog = new AlertDialog.Builder(this)
                .setTitle(selectDialogTitle)
                .setAdapter(mTargetAdapter, /* listener= */ null)
                .setPositiveButton(
                        getString(R.string.edit_accessibility_shortcut_menu_button),
                        /* listener= */ null)
                .setOnDismissListener(dialog -> finish())
                .create();
        mMenuDialog.setOnShowListener(dialog -> updateDialogListeners());
        mMenuDialog.show();
    }

    @Override
    protected void onDestroy() {
        mMenuDialog.dismiss();
        super.onDestroy();
    }

    private void onTargetSelected(AdapterView<?> parent, View view, int position, long id) {
        final AccessibilityTarget target = mTargets.get(position);
        target.onSelected();
        mMenuDialog.dismiss();
    }

    private void onTargetChecked(AdapterView<?> parent, View view, int position, long id) {
        final AccessibilityTarget target = mTargets.get(position);

        if ((target instanceof AccessibilityServiceTarget) && !target.isShortcutEnabled()) {
            mPermissionDialog = new AlertDialog.Builder(this)
                    .setView(createEnableDialogContentView(this,
                            (AccessibilityServiceTarget) target,
                            v -> {
                                mPermissionDialog.dismiss();
                                mTargetAdapter.notifyDataSetChanged();
                            },
                            v -> mPermissionDialog.dismiss()))
                    .create();
            mPermissionDialog.show();
            return;
        }

        target.onCheckedChanged(!target.isShortcutEnabled());
        mTargetAdapter.notifyDataSetChanged();
    }

    private void onDoneButtonClicked() {
        mTargets.clear();
        mTargets.addAll(getTargets(this, mShortcutType));
        if (mTargets.isEmpty()) {
            mMenuDialog.dismiss();
            return;
        }

        mTargetAdapter.setShortcutMenuMode(ShortcutMenuMode.LAUNCH);
        mTargetAdapter.notifyDataSetChanged();

        mMenuDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(
                getString(R.string.edit_accessibility_shortcut_menu_button));

        updateDialogListeners();
    }

    private void onEditButtonClicked() {
        mTargets.clear();
        mTargets.addAll(getInstalledTargets(this, mShortcutType));
        mTargetAdapter.setShortcutMenuMode(ShortcutMenuMode.EDIT);
        mTargetAdapter.notifyDataSetChanged();

        mMenuDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(
                getString(R.string.done_accessibility_shortcut_menu_button));

        updateDialogListeners();
    }

    private void updateDialogListeners() {
        final boolean isEditMenuMode =
                (mTargetAdapter.getShortcutMenuMode() == ShortcutMenuMode.EDIT);
        final int selectDialogTitleId = R.string.accessibility_select_shortcut_menu_title;
        final int editDialogTitleId =
                (mShortcutType == ACCESSIBILITY_BUTTON)
                        ? R.string.accessibility_edit_shortcut_menu_button_title
                        : R.string.accessibility_edit_shortcut_menu_volume_title;

        mMenuDialog.setTitle(getString(isEditMenuMode ? editDialogTitleId : selectDialogTitleId));
        mMenuDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                isEditMenuMode ? view -> onDoneButtonClicked() : view -> onEditButtonClicked());
        mMenuDialog.getListView().setOnItemClickListener(
                isEditMenuMode ? this::onTargetChecked : this::onTargetSelected);
    }
}
