/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_BUTTON;
import static android.view.accessibility.AccessibilityManager.ACCESSIBILITY_SHORTCUT_KEY;
import static android.view.accessibility.AccessibilityManager.ShortcutType;

import static com.android.internal.accessibility.AccessibilityShortcutController.COLOR_INVERSION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.DALTONIZER_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.AccessibilityServiceFragmentType;
import static com.android.internal.accessibility.common.ShortcutConstants.DISABLED_ALPHA;
import static com.android.internal.accessibility.common.ShortcutConstants.ENABLED_ALPHA;
import static com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.COMPONENT_ID;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.FRAGMENT_TYPE;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.ICON_ID;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.LABEL_ID;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.SETTINGS_KEY;
import static com.android.internal.accessibility.util.AccessibilityUtils.getAccessibilityServiceFragmentType;
import static com.android.internal.accessibility.util.AccessibilityUtils.setAccessibilityServiceState;
import static com.android.internal.accessibility.util.ShortcutUtils.convertToUserType;
import static com.android.internal.accessibility.util.ShortcutUtils.hasValuesInSettings;
import static com.android.internal.accessibility.util.ShortcutUtils.optOutValueFromSettings;
import static com.android.internal.util.Preconditions.checkArgument;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Activity used to display and persist a service or feature target for the Accessibility button.
 */
public class AccessibilityButtonChooserActivity extends Activity {
    @ShortcutType
    private int mShortcutType;
    @UserShortcutType
    private int mShortcutUserType;
    private final List<AccessibilityButtonTarget> mTargets = new ArrayList<>();
    private AlertDialog mAlertDialog;
    private TargetAdapter mTargetAdapter;

    private static final String[][] WHITE_LISTING_FEATURES = {
            {
                    COLOR_INVERSION_COMPONENT_NAME.flattenToString(),
                    String.valueOf(R.string.color_inversion_feature_name),
                    String.valueOf(R.drawable.ic_accessibility_color_inversion),
                    String.valueOf(AccessibilityServiceFragmentType.INTUITIVE),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
            },
            {
                    DALTONIZER_COMPONENT_NAME.flattenToString(),
                    String.valueOf(R.string.color_correction_feature_name),
                    String.valueOf(R.drawable.ic_accessibility_color_correction),
                    String.valueOf(AccessibilityServiceFragmentType.INTUITIVE),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED,
            },
            {
                    MAGNIFICATION_CONTROLLER_NAME,
                    String.valueOf(R.string.accessibility_magnification_chooser_text),
                    String.valueOf(R.drawable.ic_accessibility_magnification),
                    String.valueOf(AccessibilityServiceFragmentType.INVISIBLE),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED,
            },
    };

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

        mShortcutUserType = convertToUserType(mShortcutType);

        mTargets.addAll(getServiceTargets(this, mShortcutType));

        final String selectDialogTitle =
                getString(R.string.accessibility_select_shortcut_menu_title);
        mTargetAdapter = new TargetAdapter(mTargets, mShortcutType);
        mAlertDialog = new AlertDialog.Builder(this)
                .setTitle(selectDialogTitle)
                .setAdapter(mTargetAdapter, /* listener= */ null)
                .setPositiveButton(
                        getString(R.string.edit_accessibility_shortcut_menu_button),
                        /* listener= */ null)
                .setOnDismissListener(dialog -> finish())
                .create();
        mAlertDialog.setOnShowListener(dialog -> updateDialogListeners());
        mAlertDialog.show();
    }

    @Override
    protected void onDestroy() {
        mAlertDialog.dismiss();
        super.onDestroy();
    }

    private static List<AccessibilityButtonTarget> getServiceTargets(@NonNull Context context,
            @ShortcutType int shortcutType) {
        final List<AccessibilityButtonTarget> targets = new ArrayList<>();
        targets.addAll(getAccessibilityServiceTargets(context));
        targets.addAll(getAccessibilityActivityTargets(context));
        targets.addAll(getWhiteListingServiceTargets(context));

        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<String> requiredTargets = ams.getAccessibilityShortcutTargets(shortcutType);
        targets.removeIf(target -> !requiredTargets.contains(target.getId()));

        return targets;
    }

    private static List<AccessibilityButtonTarget> getAccessibilityServiceTargets(
            @NonNull Context context) {
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<AccessibilityServiceInfo> installedServices =
                ams.getInstalledAccessibilityServiceList();
        if (installedServices == null) {
            return Collections.emptyList();
        }

        final List<AccessibilityButtonTarget> targets = new ArrayList<>(installedServices.size());
        for (AccessibilityServiceInfo info : installedServices) {
            targets.add(new AccessibilityButtonTarget(context, info));
        }

        return targets;
    }

    private static List<AccessibilityButtonTarget> getAccessibilityActivityTargets(
            @NonNull Context context) {
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<AccessibilityShortcutInfo> installedServices =
                ams.getInstalledAccessibilityShortcutListAsUser(context,
                        ActivityManager.getCurrentUser());
        if (installedServices == null) {
            return Collections.emptyList();
        }

        final List<AccessibilityButtonTarget> targets = new ArrayList<>(installedServices.size());
        for (AccessibilityShortcutInfo info : installedServices) {
            targets.add(new AccessibilityButtonTarget(context, info));
        }

        return targets;
    }

    private static List<AccessibilityButtonTarget> getWhiteListingServiceTargets(
            @NonNull Context context) {
        final List<AccessibilityButtonTarget> targets = new ArrayList<>();

        for (int i = 0; i < WHITE_LISTING_FEATURES.length; i++) {
            final AccessibilityButtonTarget target = new AccessibilityButtonTarget(
                    context,
                    WHITE_LISTING_FEATURES[i][COMPONENT_ID],
                    Integer.parseInt(WHITE_LISTING_FEATURES[i][LABEL_ID]),
                    Integer.parseInt(WHITE_LISTING_FEATURES[i][ICON_ID]),
                    Integer.parseInt(WHITE_LISTING_FEATURES[i][FRAGMENT_TYPE]));
            targets.add(target);
        }

        return targets;
    }

    private static boolean isWhiteListingServiceEnabled(@NonNull Context context,
            AccessibilityButtonTarget target) {

        for (int i = 0; i < WHITE_LISTING_FEATURES.length; i++) {
            if (WHITE_LISTING_FEATURES[i][COMPONENT_ID].equals(target.getId())) {
                return Settings.Secure.getInt(context.getContentResolver(),
                        WHITE_LISTING_FEATURES[i][SETTINGS_KEY],
                        /* settingsValueOff */ 0) == /* settingsValueOn */ 1;
            }
        }

        return false;
    }

    private static boolean isWhiteListingService(String componentId) {
        for (int i = 0; i < WHITE_LISTING_FEATURES.length; i++) {
            if (WHITE_LISTING_FEATURES[i][COMPONENT_ID].equals(componentId)) {
                return true;
            }
        }

        return false;
    }

    private void setWhiteListingServiceEnabled(String componentId, int settingsValue) {
        for (int i = 0; i < WHITE_LISTING_FEATURES.length; i++) {
            if (WHITE_LISTING_FEATURES[i][COMPONENT_ID].equals(componentId)) {
                Settings.Secure.putInt(getContentResolver(),
                        WHITE_LISTING_FEATURES[i][SETTINGS_KEY], settingsValue);
                return;
            }
        }
    }

    private void disableService(String componentId) {
        if (isWhiteListingService(componentId)) {
            setWhiteListingServiceEnabled(componentId, /* settingsValueOff */ 0);
        } else {
            final ComponentName componentName = ComponentName.unflattenFromString(componentId);
            setAccessibilityServiceState(this, componentName, /* enabled= */ false);
        }
    }

    private static class ViewHolder {
        View mItemView;
        ImageView mIconView;
        TextView mLabelView;
        FrameLayout mItemContainer;
        ImageView mActionViewItem;
        Switch mSwitchItem;
    }

    private static class TargetAdapter extends BaseAdapter {
        @ShortcutMenuMode
        private int mShortcutMenuMode = ShortcutMenuMode.LAUNCH;
        @ShortcutType
        private int mShortcutButtonType;
        private List<AccessibilityButtonTarget> mButtonTargets;

        TargetAdapter(List<AccessibilityButtonTarget> targets,
                @ShortcutType int shortcutButtonType) {
            this.mButtonTargets = targets;
            this.mShortcutButtonType = shortcutButtonType;
        }

        void setShortcutMenuMode(@ShortcutMenuMode int shortcutMenuMode) {
            mShortcutMenuMode = shortcutMenuMode;
        }

        @ShortcutMenuMode
        int getShortcutMenuMode() {
            return mShortcutMenuMode;
        }

        @Override
        public int getCount() {
            return mButtonTargets.size();
        }

        @Override
        public Object getItem(int position) {
            return mButtonTargets.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final Context context = parent.getContext();
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(
                        R.layout.accessibility_button_chooser_item, parent, /* attachToRoot= */
                        false);
                holder = new ViewHolder();
                holder.mItemView = convertView;
                holder.mIconView = convertView.findViewById(R.id.accessibility_button_target_icon);
                holder.mLabelView = convertView.findViewById(
                        R.id.accessibility_button_target_label);
                holder.mItemContainer = convertView.findViewById(
                        R.id.accessibility_button_target_item_container);
                holder.mActionViewItem = convertView.findViewById(
                        R.id.accessibility_button_target_view_item);
                holder.mSwitchItem = convertView.findViewById(
                        R.id.accessibility_button_target_switch_item);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final AccessibilityButtonTarget target = mButtonTargets.get(position);
            holder.mIconView.setImageDrawable(target.getDrawable());
            holder.mLabelView.setText(target.getLabel());

            updateActionItem(context, holder, target);

            return convertView;
        }

        private void updateActionItem(@NonNull Context context,
                @NonNull ViewHolder holder, AccessibilityButtonTarget target) {

            switch (target.getFragmentType()) {
                case AccessibilityServiceFragmentType.LEGACY:
                    updateLegacyActionItemVisibility(context, holder);
                    break;
                case AccessibilityServiceFragmentType.INVISIBLE:
                    updateInvisibleActionItemVisibility(context, holder);
                    break;
                case AccessibilityServiceFragmentType.INTUITIVE:
                    updateIntuitiveActionItemVisibility(context, holder, target);
                    break;
                case AccessibilityServiceFragmentType.BOUNCE:
                    updateBounceActionItemVisibility(context, holder);
                    break;
                default:
                    throw new IllegalStateException("Unexpected fragment type");
            }
        }

        private void updateLegacyActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder) {
            final boolean isLaunchMenuMode = (mShortcutMenuMode == ShortcutMenuMode.LAUNCH);
            final boolean isHardwareButtonTriggered =
                    (mShortcutButtonType == ACCESSIBILITY_SHORTCUT_KEY);
            final boolean enabledState = (isLaunchMenuMode || isHardwareButtonTriggered);
            final ColorMatrix grayScaleMatrix = new ColorMatrix();
            grayScaleMatrix.setSaturation(/* grayScale */0);

            holder.mIconView.setColorFilter(enabledState
                    ? null : new ColorMatrixColorFilter(grayScaleMatrix));
            holder.mIconView.setAlpha(enabledState
                    ? ENABLED_ALPHA : DISABLED_ALPHA);
            holder.mLabelView.setEnabled(enabledState);
            holder.mActionViewItem.setEnabled(enabledState);
            holder.mActionViewItem.setImageDrawable(context.getDrawable(R.drawable.ic_delete_item));
            holder.mActionViewItem.setVisibility(View.VISIBLE);
            holder.mSwitchItem.setVisibility(View.GONE);
            holder.mItemContainer.setVisibility(isLaunchMenuMode ? View.GONE : View.VISIBLE);
            holder.mItemView.setEnabled(enabledState);
            holder.mItemView.setClickable(!enabledState);
        }

        private void updateInvisibleActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder) {
            holder.mIconView.setColorFilter(null);
            holder.mIconView.setAlpha(ENABLED_ALPHA);
            holder.mLabelView.setEnabled(true);
            holder.mActionViewItem.setEnabled(true);
            holder.mActionViewItem.setImageDrawable(context.getDrawable(R.drawable.ic_delete_item));
            holder.mActionViewItem.setVisibility(View.VISIBLE);
            holder.mSwitchItem.setVisibility(View.GONE);
            holder.mItemContainer.setVisibility((mShortcutMenuMode == ShortcutMenuMode.EDIT)
                    ? View.VISIBLE : View.GONE);
            holder.mItemView.setEnabled(true);
            holder.mItemView.setClickable(false);
        }

        private void updateIntuitiveActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder, AccessibilityButtonTarget target) {
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);
            final boolean isServiceEnabled = isWhiteListingService(target.getId())
                    ? isWhiteListingServiceEnabled(context, target)
                    : isAccessibilityServiceEnabled(context, target);

            holder.mIconView.setColorFilter(null);
            holder.mIconView.setAlpha(ENABLED_ALPHA);
            holder.mLabelView.setEnabled(true);
            holder.mActionViewItem.setEnabled(true);
            holder.mActionViewItem.setImageDrawable(context.getDrawable(R.drawable.ic_delete_item));
            holder.mActionViewItem.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
            holder.mSwitchItem.setVisibility(isEditMenuMode ? View.GONE : View.VISIBLE);
            holder.mSwitchItem.setChecked(!isEditMenuMode && isServiceEnabled);
            holder.mItemContainer.setVisibility(View.VISIBLE);
            holder.mItemView.setEnabled(true);
            holder.mItemView.setClickable(false);
        }

        private void updateBounceActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder) {
            holder.mIconView.setColorFilter(null);
            holder.mIconView.setAlpha(ENABLED_ALPHA);
            holder.mLabelView.setEnabled(true);
            holder.mActionViewItem.setEnabled(true);
            holder.mActionViewItem.setImageDrawable(context.getDrawable(R.drawable.ic_delete_item));
            holder.mActionViewItem.setVisibility((mShortcutMenuMode == ShortcutMenuMode.EDIT)
                    ? View.VISIBLE : View.GONE);
            holder.mSwitchItem.setVisibility(View.GONE);
            holder.mItemContainer.setVisibility(View.VISIBLE);
            holder.mItemView.setEnabled(true);
            holder.mItemView.setClickable(false);
        }
    }

    private static class AccessibilityButtonTarget {
        private String mId;
        private CharSequence mLabel;
        private Drawable mDrawable;
        @AccessibilityServiceFragmentType
        private int mFragmentType;

        AccessibilityButtonTarget(@NonNull Context context,
                @NonNull AccessibilityServiceInfo serviceInfo) {
            this.mId = serviceInfo.getComponentName().flattenToString();
            this.mLabel = serviceInfo.getResolveInfo().loadLabel(context.getPackageManager());
            this.mDrawable = serviceInfo.getResolveInfo().loadIcon(context.getPackageManager());
            this.mFragmentType = getAccessibilityServiceFragmentType(serviceInfo);
        }

        AccessibilityButtonTarget(@NonNull Context context,
                @NonNull AccessibilityShortcutInfo shortcutInfo) {
            this.mId = shortcutInfo.getComponentName().flattenToString();
            this.mLabel = shortcutInfo.getActivityInfo().loadLabel(context.getPackageManager());
            this.mDrawable = shortcutInfo.getActivityInfo().loadIcon(context.getPackageManager());
            this.mFragmentType = AccessibilityServiceFragmentType.BOUNCE;
        }

        AccessibilityButtonTarget(Context context, @NonNull String id, int labelResId,
                int iconRes, @AccessibilityServiceFragmentType int fragmentType) {
            this.mId = id;
            this.mLabel = context.getText(labelResId);
            this.mDrawable = context.getDrawable(iconRes);
            this.mFragmentType = fragmentType;
        }

        public String getId() {
            return mId;
        }

        public CharSequence getLabel() {
            return mLabel;
        }

        public Drawable getDrawable() {
            return mDrawable;
        }

        public int getFragmentType() {
            return mFragmentType;
        }
    }

    private static boolean isAccessibilityServiceEnabled(@NonNull Context context,
            AccessibilityButtonTarget target) {
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<AccessibilityServiceInfo> enabledServices =
                ams.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);

        for (AccessibilityServiceInfo info : enabledServices) {
            final String id = info.getComponentName().flattenToString();
            if (id.equals(target.getId())) {
                return true;
            }
        }

        return false;
    }

    private void onTargetSelected(AdapterView<?> parent, View view, int position, long id) {
        final AccessibilityButtonTarget target = mTargets.get(position);
        switch (target.getFragmentType()) {
            case AccessibilityServiceFragmentType.LEGACY:
                onLegacyTargetSelected(target);
                break;
            case AccessibilityServiceFragmentType.INVISIBLE:
                onInvisibleTargetSelected(target);
                break;
            case AccessibilityServiceFragmentType.INTUITIVE:
                onIntuitiveTargetSelected(target);
                break;
            case AccessibilityServiceFragmentType.BOUNCE:
                onBounceTargetSelected(target);
                break;
            default:
                throw new IllegalStateException("Unexpected fragment type");
        }

        mAlertDialog.dismiss();
    }

    private void onLegacyTargetSelected(AccessibilityButtonTarget target) {
        if (mShortcutType == ACCESSIBILITY_BUTTON) {
            final AccessibilityManager ams = getSystemService(AccessibilityManager.class);
            ams.notifyAccessibilityButtonClicked(getDisplayId(), target.getId());
        } else if (mShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            switchServiceState(target);
        }
    }

    private void onInvisibleTargetSelected(AccessibilityButtonTarget target) {
        final AccessibilityManager ams = getSystemService(AccessibilityManager.class);
        if (mShortcutType == ACCESSIBILITY_BUTTON) {
            ams.notifyAccessibilityButtonClicked(getDisplayId(), target.getId());
        } else if (mShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            ams.performAccessibilityShortcut(target.getId());
        }
    }

    private void onIntuitiveTargetSelected(AccessibilityButtonTarget target) {
        switchServiceState(target);
    }

    private void onBounceTargetSelected(AccessibilityButtonTarget target) {
        final AccessibilityManager ams = getSystemService(AccessibilityManager.class);
        if (mShortcutType == ACCESSIBILITY_BUTTON) {
            ams.notifyAccessibilityButtonClicked(getDisplayId(), target.getId());
        } else if (mShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            ams.performAccessibilityShortcut(target.getId());
        }
    }

    private void switchServiceState(AccessibilityButtonTarget target) {
        final ComponentName componentName =
                ComponentName.unflattenFromString(target.getId());
        final String componentId = componentName.flattenToString();

        if (isWhiteListingService(componentId)) {
            setWhiteListingServiceEnabled(componentId,
                    isWhiteListingServiceEnabled(this, target)
                            ? /* settingsValueOff */ 0
                            : /* settingsValueOn */ 1);
        } else {
            setAccessibilityServiceState(this, componentName,
                    /* enabled= */!isAccessibilityServiceEnabled(this, target));
        }
    }

    private void onTargetDeleted(AdapterView<?> parent, View view, int position, long id) {
        final AccessibilityButtonTarget target = mTargets.get(position);
        final String componentId = target.getId();

        switch (target.getFragmentType()) {
            case AccessibilityServiceFragmentType.LEGACY:
                onLegacyTargetDeleted(position, componentId);
                break;
            case AccessibilityServiceFragmentType.INVISIBLE:
                onInvisibleTargetDeleted(position, componentId);
                break;
            case AccessibilityServiceFragmentType.INTUITIVE:
                onIntuitiveTargetDeleted(position, componentId);
                break;
            case AccessibilityServiceFragmentType.BOUNCE:
                onBounceTargetDeleted(position, componentId);
                break;
            default:
                throw new IllegalStateException("Unexpected fragment type");
        }

        if (mTargets.isEmpty()) {
            mAlertDialog.dismiss();
        }
    }

    private void onLegacyTargetDeleted(int position, String componentId) {
        if (mShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            optOutValueFromSettings(this, mShortcutUserType, componentId);

            mTargets.remove(position);
            mTargetAdapter.notifyDataSetChanged();
        }
    }

    private void onInvisibleTargetDeleted(int position, String componentId) {
        optOutValueFromSettings(this, mShortcutUserType, componentId);

        final int shortcutTypes = UserShortcutType.SOFTWARE | UserShortcutType.HARDWARE;
        if (!hasValuesInSettings(this, shortcutTypes, componentId)) {
            disableService(componentId);
        }

        mTargets.remove(position);
        mTargetAdapter.notifyDataSetChanged();
    }

    private void onIntuitiveTargetDeleted(int position, String componentId) {
        optOutValueFromSettings(this, mShortcutUserType, componentId);
        mTargets.remove(position);
        mTargetAdapter.notifyDataSetChanged();
    }

    private void onBounceTargetDeleted(int position, String componentId) {
        optOutValueFromSettings(this, mShortcutUserType, componentId);
        mTargets.remove(position);
        mTargetAdapter.notifyDataSetChanged();
    }

    private void onCancelButtonClicked() {
        mTargetAdapter.setShortcutMenuMode(ShortcutMenuMode.LAUNCH);
        mTargetAdapter.notifyDataSetChanged();

        mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(
                getString(R.string.edit_accessibility_shortcut_menu_button));

        updateDialogListeners();
    }

    private void onEditButtonClicked() {
        mTargetAdapter.setShortcutMenuMode(ShortcutMenuMode.EDIT);
        mTargetAdapter.notifyDataSetChanged();

        mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(
                getString(R.string.cancel_accessibility_shortcut_menu_button));

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

        mAlertDialog.setTitle(getString(isEditMenuMode ? editDialogTitleId : selectDialogTitleId));

        mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                isEditMenuMode ? view -> onCancelButtonClicked() : view -> onEditButtonClicked());
        mAlertDialog.getListView().setOnItemClickListener(
                isEditMenuMode ? this::onTargetDeleted : this::onTargetSelected);
    }
}
