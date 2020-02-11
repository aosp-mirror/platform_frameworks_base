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
import static com.android.internal.app.AccessibilityButtonChooserActivity.WhiteListingFeatureElementIndex.COMPONENT_ID;
import static com.android.internal.app.AccessibilityButtonChooserActivity.WhiteListingFeatureElementIndex.FRAGMENT_TYPE;
import static com.android.internal.app.AccessibilityButtonChooserActivity.WhiteListingFeatureElementIndex.ICON_ID;
import static com.android.internal.app.AccessibilityButtonChooserActivity.WhiteListingFeatureElementIndex.LABEL_ID;
import static com.android.internal.app.AccessibilityButtonChooserActivity.WhiteListingFeatureElementIndex.SETTINGS_KEY;
import static com.android.internal.util.Preconditions.checkArgument;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityShortcutInfo;
import android.annotation.IntDef;
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
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Activity used to display and persist a service or feature target for the Accessibility button.
 */
public class AccessibilityButtonChooserActivity extends Activity {
    private static final char SERVICES_SEPARATOR = ':';
    private static final float DISABLED_ALPHA = 0.5f;
    private static final float ENABLED_ALPHA = 1.0f;
    private static final TextUtils.SimpleStringSplitter sStringColonSplitter =
            new TextUtils.SimpleStringSplitter(SERVICES_SEPARATOR);
    @ShortcutType
    private int mShortcutType;
    @UserShortcutType
    private int mShortcutUserType;
    private final List<AccessibilityButtonTarget> mTargets = new ArrayList<>();
    private AlertDialog mAlertDialog;
    private TargetAdapter mTargetAdapter;

    /**
     * Annotation for different user shortcut type UI type.
     *
     * {@code DEFAULT} for displaying default value.
     * {@code SOFTWARE} for displaying specifying the accessibility services or features which
     * choose accessibility button in the navigation bar as preferred shortcut.
     * {@code HARDWARE} for displaying specifying the accessibility services or features which
     * choose accessibility shortcut as preferred shortcut.
     * {@code TRIPLETAP} for displaying specifying magnification to be toggled via quickly
     * tapping screen 3 times as preferred shortcut.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            UserShortcutType.DEFAULT,
            UserShortcutType.SOFTWARE,
            UserShortcutType.HARDWARE,
            UserShortcutType.TRIPLETAP,
    })
    /** Denotes the user shortcut type. */
    private @interface UserShortcutType {
        int DEFAULT = 0;
        int SOFTWARE = 1; // 1 << 0
        int HARDWARE = 2; // 1 << 1
        int TRIPLETAP = 4; // 1 << 2
    }

    /**
     * Annotation for different accessibilityService fragment UI type.
     *
     * {@code LEGACY} for displaying appearance aligned with sdk version Q accessibility service
     * page, but only hardware shortcut allowed and under service in version Q or early.
     * {@code INVISIBLE} for displaying appearance without switch bar.
     * {@code INTUITIVE} for displaying appearance with version R accessibility design.
     * {@code BOUNCE} for displaying appearance with pop-up action.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            AccessibilityServiceFragmentType.LEGACY,
            AccessibilityServiceFragmentType.INVISIBLE,
            AccessibilityServiceFragmentType.INTUITIVE,
            AccessibilityServiceFragmentType.BOUNCE,
    })
    private @interface AccessibilityServiceFragmentType {
        int LEGACY = 0;
        int INVISIBLE = 1;
        int INTUITIVE = 2;
        int BOUNCE = 3;
    }

    /**
     * Annotation for different shortcut menu mode.
     *
     * {@code LAUNCH} for clicking list item to trigger the service callback.
     * {@code EDIT} for clicking list item and save button to disable the service.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ShortcutMenuMode.LAUNCH,
            ShortcutMenuMode.EDIT,
    })
    private @interface ShortcutMenuMode {
        int LAUNCH = 0;
        int EDIT = 1;
    }

    /**
     * Annotation for align the element index of white listing feature
     * {@code WHITE_LISTING_FEATURES}.
     *
     * {@code COMPONENT_ID} is to get the service component name.
     * {@code LABEL_ID} is to get the service label text.
     * {@code ICON_ID} is to get the service icon.
     * {@code FRAGMENT_TYPE} is to get the service fragment type.
     * {@code SETTINGS_KEY} is to get the service settings key.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            WhiteListingFeatureElementIndex.COMPONENT_ID,
            WhiteListingFeatureElementIndex.LABEL_ID,
            WhiteListingFeatureElementIndex.ICON_ID,
            WhiteListingFeatureElementIndex.FRAGMENT_TYPE,
            WhiteListingFeatureElementIndex.SETTINGS_KEY,
    })
    @interface WhiteListingFeatureElementIndex {
        int COMPONENT_ID = 0;
        int LABEL_ID = 1;
        int ICON_ID = 2;
        int FRAGMENT_TYPE = 3;
        int SETTINGS_KEY = 4;
    }

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

        mTargetAdapter = new TargetAdapter(mTargets, mShortcutType);
        mAlertDialog = new AlertDialog.Builder(this)
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

    /**
     * Gets the corresponding fragment type of a given accessibility service.
     *
     * @param accessibilityServiceInfo The accessibilityService's info.
     * @return int from {@link AccessibilityServiceFragmentType}.
     */
    private static @AccessibilityServiceFragmentType int getAccessibilityServiceFragmentType(
            AccessibilityServiceInfo accessibilityServiceInfo) {
        final int targetSdk = accessibilityServiceInfo.getResolveInfo()
                .serviceInfo.applicationInfo.targetSdkVersion;
        final boolean requestA11yButton = (accessibilityServiceInfo.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;

        if (targetSdk <= Build.VERSION_CODES.Q) {
            return AccessibilityServiceFragmentType.LEGACY;
        }
        return requestA11yButton
                ? AccessibilityServiceFragmentType.INVISIBLE
                : AccessibilityServiceFragmentType.INTUITIVE;
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

        mAlertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(
                isEditMenuMode ? view -> onCancelButtonClicked() : view -> onEditButtonClicked());
        mAlertDialog.getListView().setOnItemClickListener(
                isEditMenuMode ? this::onTargetDeleted : this::onTargetSelected);
    }

    /**
     * @return the set of enabled accessibility services for {@param userId}. If there are no
     * services, it returns the unmodifiable {@link Collections#emptySet()}.
     */
    private Set<ComponentName> getEnabledServicesFromSettings(Context context, int userId) {
        final String enabledServicesSetting = Settings.Secure.getStringForUser(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userId);
        if (TextUtils.isEmpty(enabledServicesSetting)) {
            return Collections.emptySet();
        }

        final Set<ComponentName> enabledServices = new HashSet<>();
        final TextUtils.StringSplitter colonSplitter =
                new TextUtils.SimpleStringSplitter(SERVICES_SEPARATOR);
        colonSplitter.setString(enabledServicesSetting);

        for (String componentNameString : colonSplitter) {
            final ComponentName enabledService = ComponentName.unflattenFromString(
                    componentNameString);
            if (enabledService != null) {
                enabledServices.add(enabledService);
            }
        }

        return enabledServices;
    }

    /**
     * Changes an accessibility component's state.
     */
    private void setAccessibilityServiceState(Context context, ComponentName componentName,
            boolean enabled) {
        setAccessibilityServiceState(context, componentName, enabled, UserHandle.myUserId());
    }

    /**
     * Changes an accessibility component's state for {@param userId}.
     */
    private void setAccessibilityServiceState(Context context, ComponentName componentName,
            boolean enabled, int userId) {
        Set<ComponentName> enabledServices = getEnabledServicesFromSettings(
                context, userId);

        if (enabledServices.isEmpty()) {
            enabledServices = new ArraySet<>(/* capacity= */ 1);
        }

        if (enabled) {
            enabledServices.add(componentName);
        } else {
            enabledServices.remove(componentName);
        }

        final StringBuilder enabledServicesBuilder = new StringBuilder();
        for (ComponentName enabledService : enabledServices) {
            enabledServicesBuilder.append(enabledService.flattenToString());
            enabledServicesBuilder.append(
                    SERVICES_SEPARATOR);
        }

        final int enabledServicesBuilderLength = enabledServicesBuilder.length();
        if (enabledServicesBuilderLength > 0) {
            enabledServicesBuilder.deleteCharAt(enabledServicesBuilderLength - 1);
        }

        Settings.Secure.putStringForUser(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabledServicesBuilder.toString(), userId);
    }

    /**
     * Opts out component name into colon-separated {@code shortcutType} key's string in Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be opted out from Settings.
     */
    private void optOutValueFromSettings(
            Context context, @UserShortcutType int shortcutType, String componentId) {
        final StringJoiner joiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        final String targetsKey = convertToKey(shortcutType);
        final String targetsValue = Settings.Secure.getString(context.getContentResolver(),
                targetsKey);

        if (TextUtils.isEmpty(targetsValue)) {
            return;
        }

        sStringColonSplitter.setString(targetsValue);
        while (sStringColonSplitter.hasNext()) {
            final String id = sStringColonSplitter.next();
            if (TextUtils.isEmpty(id) || componentId.equals(id)) {
                continue;
            }
            joiner.add(id);
        }

        Settings.Secure.putString(context.getContentResolver(), targetsKey, joiner.toString());
    }

    /**
     * Returns if component name existed in one of {@code shortcutTypes} string in Settings.
     *
     * @param context The current context.
     * @param shortcutTypes A combination of {@link UserShortcutType}.
     * @param componentId The component name that need to be checked existed in Settings.
     * @return {@code true} if componentName existed in Settings.
     */
    private boolean hasValuesInSettings(Context context, int shortcutTypes,
            @NonNull String componentId) {
        boolean exist = false;
        if ((shortcutTypes & UserShortcutType.SOFTWARE) == UserShortcutType.SOFTWARE) {
            exist = hasValueInSettings(context, UserShortcutType.SOFTWARE, componentId);
        }
        if (((shortcutTypes & UserShortcutType.HARDWARE) == UserShortcutType.HARDWARE)) {
            exist |= hasValueInSettings(context, UserShortcutType.HARDWARE, componentId);
        }
        return exist;
    }


    /**
     * Returns if component name existed in Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentId The component id that need to be checked existed in Settings.
     * @return {@code true} if componentName existed in Settings.
     */
    private boolean hasValueInSettings(Context context, @UserShortcutType int shortcutType,
            @NonNull String componentId) {
        final String targetKey = convertToKey(shortcutType);
        final String targetString = Settings.Secure.getString(context.getContentResolver(),
                targetKey);

        if (TextUtils.isEmpty(targetString)) {
            return false;
        }

        sStringColonSplitter.setString(targetString);
        while (sStringColonSplitter.hasNext()) {
            final String id = sStringColonSplitter.next();
            if (componentId.equals(id)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Converts {@link UserShortcutType} to key in Settings.
     *
     * @param type The shortcut type.
     * @return Mapping key in Settings.
     */
    private String convertToKey(@UserShortcutType int type) {
        switch (type) {
            case UserShortcutType.SOFTWARE:
                return Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT;
            case UserShortcutType.HARDWARE:
                return Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE;
            case UserShortcutType.TRIPLETAP:
                return Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_ENABLED;
            default:
                throw new IllegalArgumentException(
                        "Unsupported user shortcut type: " + type);
        }
    }

    private static @UserShortcutType int convertToUserType(@ShortcutType int type) {
        switch (type) {
            case ACCESSIBILITY_BUTTON:
                return UserShortcutType.SOFTWARE;
            case ACCESSIBILITY_SHORTCUT_KEY:
                return UserShortcutType.HARDWARE;
            default:
                throw new IllegalArgumentException(
                        "Unsupported shortcut type:" + type);
        }
    }
}
