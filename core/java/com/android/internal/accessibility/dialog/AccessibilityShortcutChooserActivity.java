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

import static com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.createEnableDialogContentView;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getInstalledTargets;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;
import static com.android.internal.accessibility.util.AccessibilityUtils.isUserSetupCompleted;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.Flags;
import android.widget.AdapterView;

import com.android.internal.R;
import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity used to display various targets related to accessibility service, accessibility
 * activity or allowlisting feature for volume key shortcut.
 */
public class AccessibilityShortcutChooserActivity extends Activity {
    @ShortcutConstants.UserShortcutType
    private final int mShortcutType = ShortcutConstants.UserShortcutType.HARDWARE;
    private static final String KEY_ACCESSIBILITY_SHORTCUT_MENU_MODE =
            "accessibility_shortcut_menu_mode";
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();
    private AlertDialog mMenuDialog;
    private Dialog mPermissionDialog;
    private ShortcutTargetAdapter mTargetAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final TypedArray theme = getTheme().obtainStyledAttributes(android.R.styleable.Theme);
        if (!theme.getBoolean(android.R.styleable.Theme_windowNoTitle, /* defValue= */ false)) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        mTargets.addAll(getTargets(this, mShortcutType));
        mTargetAdapter = new ShortcutTargetAdapter(mTargets);
        mMenuDialog = createMenuDialog();
        mMenuDialog.setOnShowListener(dialog -> updateDialogListeners());
        mMenuDialog.show();

        if (savedInstanceState != null) {
            final int restoreShortcutMenuMode =
                    savedInstanceState.getInt(KEY_ACCESSIBILITY_SHORTCUT_MENU_MODE,
                            ShortcutMenuMode.LAUNCH);
            if (restoreShortcutMenuMode == ShortcutMenuMode.EDIT) {
                onEditButtonClicked();
            }
        }
    }

    @Override
    protected void onDestroy() {
        mMenuDialog.setOnDismissListener(null);
        mMenuDialog.dismiss();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_ACCESSIBILITY_SHORTCUT_MENU_MODE, mTargetAdapter.getShortcutMenuMode());
    }

    private void onTargetSelected(AdapterView<?> parent, View view, int position, long id) {
        final AccessibilityTarget target = mTargets.get(position);
        if (target instanceof AccessibilityServiceTarget
                || target instanceof AccessibilityActivityTarget) {
            if (sendRestrictedDialogIntentIfNeeded(target)) {
                return;
            }
        }

        target.onSelected();
        mMenuDialog.dismiss();
    }

    private void onTargetChecked(AdapterView<?> parent, View view, int position, long id) {
        final AccessibilityTarget target = mTargets.get(position);

        if (Flags.cleanupAccessibilityWarningDialog()) {
            if (target instanceof AccessibilityServiceTarget serviceTarget) {
                if (sendRestrictedDialogIntentIfNeeded(target)) {
                    return;
                }
                final AccessibilityManager am = getSystemService(AccessibilityManager.class);
                if (am.isAccessibilityServiceWarningRequired(
                        serviceTarget.getAccessibilityServiceInfo())) {
                    showPermissionDialogIfNeeded(this, (AccessibilityServiceTarget) target,
                            position, mTargetAdapter);
                    return;
                }
            }
            if (target instanceof AccessibilityActivityTarget activityTarget) {
                if (!activityTarget.isShortcutEnabled()
                        && sendRestrictedDialogIntentIfNeeded(activityTarget)) {
                    return;
                }
            }
        } else {
            if (!target.isShortcutEnabled()) {
                if (target instanceof AccessibilityServiceTarget
                        || target instanceof AccessibilityActivityTarget) {
                    if (sendRestrictedDialogIntentIfNeeded(target)) {
                        return;
                    }
                }

                if (target instanceof AccessibilityServiceTarget) {
                    showPermissionDialogIfNeeded(this, (AccessibilityServiceTarget) target,
                            position, mTargetAdapter);
                    return;
                }
            }
        }

        target.onCheckedChanged(!target.isShortcutEnabled());
        mTargetAdapter.notifyDataSetChanged();
    }

    /**
     * Sends restricted dialog intent if the accessibility target is disallowed.
     *
     * @return true if sends restricted dialog intent, otherwise false.
     */
    private boolean sendRestrictedDialogIntentIfNeeded(AccessibilityTarget target) {
        if (AccessibilityTargetHelper.isAccessibilityTargetAllowed(this,
                target.getComponentName().getPackageName(), target.getUid())) {
            return false;
        }

        AccessibilityTargetHelper.sendRestrictedDialogIntent(this,
                target.getComponentName().getPackageName(), target.getUid());
        return true;
    }

    private void showPermissionDialogIfNeeded(Context context,
            AccessibilityServiceTarget serviceTarget, int position,
            ShortcutTargetAdapter targetAdapter) {
        if (mPermissionDialog != null) {
            return;
        }

        if (Flags.cleanupAccessibilityWarningDialog()) {
            mPermissionDialog = AccessibilityServiceWarning
                    .createAccessibilityServiceWarningDialog(context,
                            serviceTarget.getAccessibilityServiceInfo(),
                            v -> {
                                serviceTarget.onCheckedChanged(true);
                                targetAdapter.notifyDataSetChanged();
                                mPermissionDialog.dismiss();
                            }, v -> {
                                serviceTarget.onCheckedChanged(false);
                                mPermissionDialog.dismiss();
                            },
                            v -> {
                                mTargets.remove(position);
                                context.getPackageManager().getPackageInstaller().uninstall(
                                        serviceTarget.getComponentName().getPackageName(), null);
                                targetAdapter.notifyDataSetChanged();
                                mPermissionDialog.dismiss();
                            });
            mPermissionDialog.setOnDismissListener(dialog -> mPermissionDialog = null);
        } else {
            mPermissionDialog = new AlertDialog.Builder(context)
                    .setView(createEnableDialogContentView(context, serviceTarget,
                            v -> {
                                mPermissionDialog.dismiss();
                                targetAdapter.notifyDataSetChanged();
                            },
                            v -> mPermissionDialog.dismiss()))
                    .setOnDismissListener(dialog -> mPermissionDialog = null)
                    .create();
        }
        mPermissionDialog.show();
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
                mTargetAdapter.getShortcutMenuMode() == ShortcutMenuMode.EDIT;
        final int selectDialogTitleId = R.string.accessibility_select_shortcut_menu_title;
        final int editDialogTitleId =
                mShortcutType == ShortcutConstants.UserShortcutType.SOFTWARE
                        ? R.string.accessibility_edit_shortcut_menu_button_title
                        : R.string.accessibility_edit_shortcut_menu_volume_title;

        mMenuDialog.setTitle(getString(isEditMenuMode ? editDialogTitleId : selectDialogTitleId));
        mMenuDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                isEditMenuMode ? view -> onDoneButtonClicked() : view -> onEditButtonClicked());
        mMenuDialog.getListView().setOnItemClickListener(
                isEditMenuMode ? this::onTargetChecked : this::onTargetSelected);
    }

    @VisibleForTesting
    public AlertDialog getMenuDialog() {
        return mMenuDialog;
    }

    @VisibleForTesting
    public Dialog getPermissionDialog() {
        return mPermissionDialog;
    }

    private AlertDialog createMenuDialog() {
        final String dialogTitle =
                getString(R.string.accessibility_select_shortcut_menu_title);

        final AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setAdapter(mTargetAdapter, /* listener= */ null)
                .setOnDismissListener(dialog -> finish());

        boolean allowEditing = isUserSetupCompleted(this);
        boolean showWhenLocked = false;
        if (Flags.allowShortcutChooserOnLockscreen()) {
            final KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
            if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
                allowEditing = false;
                showWhenLocked = true;
            }
        }
        if (allowEditing) {
            final String positiveButtonText =
                    getString(R.string.edit_accessibility_shortcut_menu_button);
            builder.setPositiveButton(positiveButtonText, /* listener= */ null);
        }

        final AlertDialog dialog = builder.create();
        if (showWhenLocked) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        return dialog;
    }
}
