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
import static com.android.internal.accessibility.common.ShortcutConstants.ShortcutMenuMode;
import static com.android.internal.accessibility.common.ShortcutConstants.TargetType;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.HARDWARE;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.COMPONENT_ID;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.FRAGMENT_TYPE;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.ICON_ID;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.LABEL_ID;
import static com.android.internal.accessibility.common.ShortcutConstants.WhiteListingFeatureElementIndex.SETTINGS_KEY;
import static com.android.internal.accessibility.util.AccessibilityUtils.getAccessibilityServiceFragmentType;
import static com.android.internal.accessibility.util.AccessibilityUtils.setAccessibilityServiceState;
import static com.android.internal.accessibility.util.ShortcutUtils.convertToUserType;
import static com.android.internal.accessibility.util.ShortcutUtils.hasValuesInSettings;
import static com.android.internal.accessibility.util.ShortcutUtils.optInValueToSettings;
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
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Activity used to display and persist a service or feature target for the Accessibility button.
 */
public class AccessibilityButtonChooserActivity extends Activity {
    @ShortcutType
    private static int sShortcutType;
    @UserShortcutType
    private int mShortcutUserType;
    private final List<AccessibilityButtonTarget> mTargets = new ArrayList<>();
    private AlertDialog mAlertDialog;
    private AlertDialog mEnableDialog;
    private TargetAdapter mTargetAdapter;
    private AccessibilityButtonTarget mCurrentCheckedTarget;

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

        sShortcutType = getIntent().getIntExtra(AccessibilityManager.EXTRA_SHORTCUT_TYPE,
                /* unexpectedShortcutType */ -1);
        final boolean existInShortcutType = (sShortcutType == ACCESSIBILITY_BUTTON)
                || (sShortcutType == ACCESSIBILITY_SHORTCUT_KEY);
        checkArgument(existInShortcutType, "Unexpected shortcut type: " + sShortcutType);

        mShortcutUserType = convertToUserType(sShortcutType);

        mTargets.addAll(getServiceTargets(this, sShortcutType));

        final String selectDialogTitle =
                getString(R.string.accessibility_select_shortcut_menu_title);
        mTargetAdapter = new TargetAdapter(mTargets);
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
        final List<AccessibilityButtonTarget> targets = getInstalledServiceTargets(context);
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<String> requiredTargets = ams.getAccessibilityShortcutTargets(shortcutType);
        targets.removeIf(target -> !requiredTargets.contains(target.getId()));

        return targets;
    }

    private static List<AccessibilityButtonTarget> getInstalledServiceTargets(
            @NonNull Context context) {
        final List<AccessibilityButtonTarget> targets = new ArrayList<>();
        targets.addAll(getAccessibilityFilteredTargets(context));
        targets.addAll(getWhiteListingServiceTargets(context));

        return targets;
    }

    private static List<AccessibilityButtonTarget> getAccessibilityFilteredTargets(
            @NonNull Context context) {
        final List<AccessibilityButtonTarget> serviceTargets =
                getAccessibilityServiceTargets(context);
        final List<AccessibilityButtonTarget> activityTargets =
                getAccessibilityActivityTargets(context);

        for (AccessibilityButtonTarget activityTarget : activityTargets) {
            serviceTargets.removeIf(serviceTarget -> {
                final ComponentName serviceComponentName =
                        ComponentName.unflattenFromString(serviceTarget.getId());
                final ComponentName activityComponentName =
                        ComponentName.unflattenFromString(activityTarget.getId());
                final boolean isSamePackageName = activityComponentName.getPackageName().equals(
                        serviceComponentName.getPackageName());
                final boolean isSameLabel = activityTarget.getLabel().equals(
                        serviceTarget.getLabel());

                return isSamePackageName && isSameLabel;
            });
        }

        final List<AccessibilityButtonTarget> targets = new ArrayList<>();
        targets.addAll(serviceTargets);
        targets.addAll(activityTargets);

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
            final int targetSdk =
                    info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion;
            final boolean hasRequestAccessibilityButtonFlag =
                    (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
            if ((targetSdk < Build.VERSION_CODES.R) && !hasRequestAccessibilityButtonFlag
                    && (sShortcutType == ACCESSIBILITY_BUTTON)) {
                continue;
            }
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

    private void setServiceEnabled(String componentId, boolean enabled) {
        if (isWhiteListingService(componentId)) {
            setWhiteListingServiceEnabled(componentId,
                    enabled ? /* settingsValueOn */ 1 : /* settingsValueOff */ 0);
        } else {
            final ComponentName componentName = ComponentName.unflattenFromString(componentId);
            setAccessibilityServiceState(this, componentName, enabled);
        }
    }

    private static class ViewHolder {
        View mItemView;
        CheckBox mCheckBox;
        ImageView mIconView;
        TextView mLabelView;
        Switch mSwitchItem;
    }

    private static class TargetAdapter extends BaseAdapter {
        @ShortcutMenuMode
        private int mShortcutMenuMode = ShortcutMenuMode.LAUNCH;
        private List<AccessibilityButtonTarget> mButtonTargets;

        TargetAdapter(List<AccessibilityButtonTarget> targets) {
            this.mButtonTargets = targets;
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
                holder.mCheckBox = convertView.findViewById(
                        R.id.accessibility_button_target_checkbox);
                holder.mIconView = convertView.findViewById(R.id.accessibility_button_target_icon);
                holder.mLabelView = convertView.findViewById(
                        R.id.accessibility_button_target_label);
                holder.mSwitchItem = convertView.findViewById(
                        R.id.accessibility_button_target_switch_item);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final AccessibilityButtonTarget target = mButtonTargets.get(position);
            updateActionItem(context, holder, target);

            return convertView;
        }

        private void updateActionItem(@NonNull Context context,
                @NonNull ViewHolder holder, AccessibilityButtonTarget target) {

            switch (target.getFragmentType()) {
                case AccessibilityServiceFragmentType.LEGACY:
                    updateLegacyActionItemVisibility(holder, target);
                    break;
                case AccessibilityServiceFragmentType.INVISIBLE:
                    updateInvisibleActionItemVisibility(holder, target);
                    break;
                case AccessibilityServiceFragmentType.INTUITIVE:
                    updateIntuitiveActionItemVisibility(context, holder, target);
                    break;
                case AccessibilityServiceFragmentType.BOUNCE:
                    updateBounceActionItemVisibility(holder, target);
                    break;
                default:
                    throw new IllegalStateException("Unexpected fragment type");
            }
        }

        private void updateLegacyActionItemVisibility(@NonNull ViewHolder holder,
                AccessibilityButtonTarget target) {
            final boolean isLaunchMenuMode = (mShortcutMenuMode == ShortcutMenuMode.LAUNCH);

            holder.mCheckBox.setChecked(!isLaunchMenuMode && target.isChecked());
            holder.mCheckBox.setVisibility(isLaunchMenuMode ? View.GONE : View.VISIBLE);
            holder.mIconView.setImageDrawable(target.getDrawable());
            holder.mLabelView.setText(target.getLabel());
            holder.mSwitchItem.setVisibility(View.GONE);
        }

        private void updateInvisibleActionItemVisibility(@NonNull ViewHolder holder,
                AccessibilityButtonTarget target) {
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);

            holder.mCheckBox.setChecked(isEditMenuMode && target.isChecked());
            holder.mCheckBox.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
            holder.mIconView.setImageDrawable(target.getDrawable());
            holder.mLabelView.setText(target.getLabel());
            holder.mSwitchItem.setVisibility(View.GONE);
        }

        private void updateIntuitiveActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder, AccessibilityButtonTarget target) {
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);
            final boolean isServiceEnabled = isWhiteListingService(target.getId())
                    ? isWhiteListingServiceEnabled(context, target)
                    : isAccessibilityServiceEnabled(context, target);

            holder.mCheckBox.setChecked(isEditMenuMode && target.isChecked());
            holder.mCheckBox.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
            holder.mIconView.setImageDrawable(target.getDrawable());
            holder.mLabelView.setText(target.getLabel());
            holder.mSwitchItem.setVisibility(isEditMenuMode ? View.GONE : View.VISIBLE);
            holder.mSwitchItem.setChecked(!isEditMenuMode && isServiceEnabled);
        }

        private void updateBounceActionItemVisibility(@NonNull ViewHolder holder,
                AccessibilityButtonTarget target) {
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);

            holder.mCheckBox.setChecked(isEditMenuMode && target.isChecked());
            holder.mCheckBox.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
            holder.mIconView.setImageDrawable(target.getDrawable());
            holder.mLabelView.setText(target.getLabel());
            holder.mSwitchItem.setVisibility(View.GONE);
        }
    }

    private static class AccessibilityButtonTarget {
        private String mId;
        @TargetType
        private int mType;
        private boolean mChecked;
        private CharSequence mLabel;
        private Drawable mDrawable;
        @AccessibilityServiceFragmentType
        private int mFragmentType;

        AccessibilityButtonTarget(@NonNull Context context,
                @NonNull AccessibilityServiceInfo serviceInfo) {
            this.mId = serviceInfo.getComponentName().flattenToString();
            this.mType = TargetType.ACCESSIBILITY_SERVICE;
            this.mChecked = isTargetShortcutUsed(context, mId);
            this.mLabel = serviceInfo.getResolveInfo().loadLabel(context.getPackageManager());
            this.mDrawable = serviceInfo.getResolveInfo().loadIcon(context.getPackageManager());
            this.mFragmentType = getAccessibilityServiceFragmentType(serviceInfo);
        }

        AccessibilityButtonTarget(@NonNull Context context,
                @NonNull AccessibilityShortcutInfo shortcutInfo) {
            this.mId = shortcutInfo.getComponentName().flattenToString();
            this.mType = TargetType.ACCESSIBILITY_ACTIVITY;
            this.mChecked = isTargetShortcutUsed(context, mId);
            this.mLabel = shortcutInfo.getActivityInfo().loadLabel(context.getPackageManager());
            this.mDrawable = shortcutInfo.getActivityInfo().loadIcon(context.getPackageManager());
            this.mFragmentType = AccessibilityServiceFragmentType.BOUNCE;
        }

        AccessibilityButtonTarget(Context context, @NonNull String id, int labelResId,
                int iconRes, @AccessibilityServiceFragmentType int fragmentType) {
            this.mId = id;
            this.mType = TargetType.WHITE_LISTING;
            this.mChecked = isTargetShortcutUsed(context, mId);
            this.mLabel = context.getText(labelResId);
            this.mDrawable = context.getDrawable(iconRes);
            this.mFragmentType = fragmentType;
        }

        public void setChecked(boolean checked) {
            mChecked = checked;
        }

        public String getId() {
            return mId;
        }

        public int getType() {
            return mType;
        }

        public boolean isChecked() {
            return mChecked;
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
        if (sShortcutType == ACCESSIBILITY_BUTTON) {
            final AccessibilityManager ams = getSystemService(AccessibilityManager.class);
            ams.notifyAccessibilityButtonClicked(getDisplayId(), target.getId());
        } else if (sShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            switchServiceState(target);
        }
    }

    private void onInvisibleTargetSelected(AccessibilityButtonTarget target) {
        final AccessibilityManager ams = getSystemService(AccessibilityManager.class);
        if (sShortcutType == ACCESSIBILITY_BUTTON) {
            ams.notifyAccessibilityButtonClicked(getDisplayId(), target.getId());
        } else if (sShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            ams.performAccessibilityShortcut(target.getId());
        }
    }

    private void onIntuitiveTargetSelected(AccessibilityButtonTarget target) {
        switchServiceState(target);
    }

    private void onBounceTargetSelected(AccessibilityButtonTarget target) {
        final AccessibilityManager ams = getSystemService(AccessibilityManager.class);
        if (sShortcutType == ACCESSIBILITY_BUTTON) {
            ams.notifyAccessibilityButtonClicked(getDisplayId(), target.getId());
        } else if (sShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
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

    private void onTargetChecked(AdapterView<?> parent, View view, int position, long id) {
        mCurrentCheckedTarget = mTargets.get(position);

        if ((mCurrentCheckedTarget.getType() == TargetType.ACCESSIBILITY_SERVICE)
                && !mCurrentCheckedTarget.isChecked()) {
            mEnableDialog = new AlertDialog.Builder(this)
                    .setView(createEnableDialogContentView(this, mCurrentCheckedTarget,
                            this::onPermissionAllowButtonClicked,
                            this::onPermissionDenyButtonClicked))
                    .create();
            mEnableDialog.show();
            return;
        }

        onTargetChecked(mCurrentCheckedTarget, !mCurrentCheckedTarget.isChecked());
    }

    private void onTargetChecked(AccessibilityButtonTarget target, boolean checked) {
        switch (target.getFragmentType()) {
            case AccessibilityServiceFragmentType.LEGACY:
                onLegacyTargetChecked(checked);
                break;
            case AccessibilityServiceFragmentType.INVISIBLE:
                onInvisibleTargetChecked(checked);
                break;
            case AccessibilityServiceFragmentType.INTUITIVE:
                onIntuitiveTargetChecked(checked);
                break;
            case AccessibilityServiceFragmentType.BOUNCE:
                onBounceTargetChecked(checked);
                break;
            default:
                throw new IllegalStateException("Unexpected fragment type");
        }
    }

    private void onLegacyTargetChecked(boolean checked) {
        if (sShortcutType == ACCESSIBILITY_BUTTON) {
            setServiceEnabled(mCurrentCheckedTarget.getId(), checked);
            if (!checked) {
                optOutValueFromSettings(this, HARDWARE, mCurrentCheckedTarget.getId());
                final String warningText =
                        getString(R.string.accessibility_uncheck_legacy_item_warning,
                                mCurrentCheckedTarget.getLabel());
                Toast.makeText(this, warningText, Toast.LENGTH_SHORT).show();
            }
        } else if (sShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            updateValueToSettings(mCurrentCheckedTarget.getId(), checked);
        } else {
            throw new IllegalStateException("Unexpected shortcut type");
        }

        mCurrentCheckedTarget.setChecked(checked);
        mTargetAdapter.notifyDataSetChanged();
    }

    private void onInvisibleTargetChecked(boolean checked) {
        final int shortcutTypes = UserShortcutType.SOFTWARE | HARDWARE;
        if (!hasValuesInSettings(this, shortcutTypes, mCurrentCheckedTarget.getId())) {
            setServiceEnabled(mCurrentCheckedTarget.getId(), checked);
        }

        updateValueToSettings(mCurrentCheckedTarget.getId(), checked);
        mCurrentCheckedTarget.setChecked(checked);
        mTargetAdapter.notifyDataSetChanged();
    }

    private void onIntuitiveTargetChecked(boolean checked) {
        updateValueToSettings(mCurrentCheckedTarget.getId(), checked);
        mCurrentCheckedTarget.setChecked(checked);
        mTargetAdapter.notifyDataSetChanged();
    }

    private void onBounceTargetChecked(boolean checked) {
        updateValueToSettings(mCurrentCheckedTarget.getId(), checked);
        mCurrentCheckedTarget.setChecked(checked);
        mTargetAdapter.notifyDataSetChanged();
    }

    private void updateValueToSettings(String componentId, boolean checked) {
        if (checked) {
            optInValueToSettings(this, mShortcutUserType, componentId);
        } else  {
            optOutValueFromSettings(this, mShortcutUserType, componentId);
        }
    }

    private void onDoneButtonClicked() {
        mTargets.clear();
        mTargets.addAll(getServiceTargets(this, sShortcutType));
        if (mTargets.isEmpty()) {
            mAlertDialog.dismiss();
            return;
        }

        mTargetAdapter.setShortcutMenuMode(ShortcutMenuMode.LAUNCH);
        mTargetAdapter.notifyDataSetChanged();

        mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(
                getString(R.string.edit_accessibility_shortcut_menu_button));

        updateDialogListeners();
    }

    private void onEditButtonClicked() {
        mTargets.clear();
        mTargets.addAll(getInstalledServiceTargets(this));
        mTargetAdapter.setShortcutMenuMode(ShortcutMenuMode.EDIT);
        mTargetAdapter.notifyDataSetChanged();

        mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(
                getString(R.string.done_accessibility_shortcut_menu_button));

        updateDialogListeners();
    }

    private void updateDialogListeners() {
        final boolean isEditMenuMode =
                (mTargetAdapter.getShortcutMenuMode() == ShortcutMenuMode.EDIT);
        final int selectDialogTitleId = R.string.accessibility_select_shortcut_menu_title;
        final int editDialogTitleId =
                (sShortcutType == ACCESSIBILITY_BUTTON)
                        ? R.string.accessibility_edit_shortcut_menu_button_title
                        : R.string.accessibility_edit_shortcut_menu_volume_title;

        mAlertDialog.setTitle(getString(isEditMenuMode ? editDialogTitleId : selectDialogTitleId));
        mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                isEditMenuMode ? view -> onDoneButtonClicked() : view -> onEditButtonClicked());
        mAlertDialog.getListView().setOnItemClickListener(
                isEditMenuMode ? this::onTargetChecked : this::onTargetSelected);
    }

    private static boolean isTargetShortcutUsed(@NonNull Context context, String id) {
        final AccessibilityManager ams = context.getSystemService(AccessibilityManager.class);
        final List<String> requiredTargets = ams.getAccessibilityShortcutTargets(sShortcutType);
        return requiredTargets.contains(id);
    }

    private void onPermissionAllowButtonClicked(View view) {
        if (mCurrentCheckedTarget.getFragmentType() != AccessibilityServiceFragmentType.LEGACY) {
            updateValueToSettings(mCurrentCheckedTarget.getId(), /* checked= */ true);
        }
        onTargetChecked(mCurrentCheckedTarget, /* checked= */ true);
        mEnableDialog.dismiss();
    }

    private void onPermissionDenyButtonClicked(View view) {
        mEnableDialog.dismiss();
    }

    private static View createEnableDialogContentView(Context context,
            AccessibilityButtonTarget target, View.OnClickListener allowListener,
            View.OnClickListener denyListener) {
        final LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        final View content = inflater.inflate(
                R.layout.accessibility_enable_service_encryption_warning, /* root= */ null);

        final TextView encryptionWarningView = (TextView) content.findViewById(
                R.id.accessibility_encryption_warning);
        if (StorageManager.isNonDefaultBlockEncrypted()) {
            final String text = context.getString(
                    R.string.accessibility_enable_service_encryption_warning,
                    getServiceName(context, target.getLabel()));
            encryptionWarningView.setText(text);
            encryptionWarningView.setVisibility(View.VISIBLE);
        } else {
            encryptionWarningView.setVisibility(View.GONE);
        }

        final ImageView permissionDialogIcon = content.findViewById(
                R.id.accessibility_permissionDialog_icon);
        permissionDialogIcon.setImageDrawable(target.getDrawable());

        final TextView permissionDialogTitle = content.findViewById(
                R.id.accessibility_permissionDialog_title);
        permissionDialogTitle.setText(context.getString(R.string.accessibility_enable_service_title,
                getServiceName(context, target.getLabel())));

        final Button permissionAllowButton = content.findViewById(
                R.id.accessibility_permission_enable_allow_button);
        final Button permissionDenyButton = content.findViewById(
                R.id.accessibility_permission_enable_deny_button);
        permissionAllowButton.setOnClickListener(allowListener);
        permissionDenyButton.setOnClickListener(denyListener);

        return content;
    }

    // Gets the service name and bidi wrap it to protect from bidi side effects.
    private static CharSequence getServiceName(Context context, CharSequence label) {
        final Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return BidiFormatter.getInstance(locale).unicodeWrap(label);
    }
}
