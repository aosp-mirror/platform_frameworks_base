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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
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

    private static final String MAGNIFICATION_COMPONENT_ID =
            "com.android.server.accessibility.MagnificationController";

    private static final char SERVICES_SEPARATOR = ':';
    private static final TextUtils.SimpleStringSplitter sStringColonSplitter =
            new TextUtils.SimpleStringSplitter(SERVICES_SEPARATOR);
    private static final int ACCESSIBILITY_BUTTON_USER_TYPE = convertToUserType(
            ACCESSIBILITY_BUTTON);
    private static final int ACCESSIBILITY_SHORTCUT_KEY_USER_TYPE = convertToUserType(
            ACCESSIBILITY_SHORTCUT_KEY);

    private int mShortcutType;
    private List<AccessibilityButtonTarget> mTargets = new ArrayList<>();
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
    public @interface UserShortcutType {
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
    public @interface AccessibilityServiceFragmentType {
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
    public @interface ShortcutMenuMode {
        int LAUNCH = 0;
        int EDIT = 1;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final TypedArray theme = getTheme().obtainStyledAttributes(android.R.styleable.Theme);
        if (!theme.getBoolean(android.R.styleable.Theme_windowNoTitle, /* defValue= */ false)) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        mShortcutType = getIntent().getIntExtra(AccessibilityManager.EXTRA_SHORTCUT_TYPE,
                ACCESSIBILITY_BUTTON);
        mTargets.addAll(getServiceTargets(this, mShortcutType));

        mTargetAdapter = new TargetAdapter(mTargets);
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
        final AccessibilityManager ams = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        final List<AccessibilityServiceInfo> installedServices =
                ams.getInstalledAccessibilityServiceList();
        if (installedServices == null) {
            return Collections.emptyList();
        }

        final List<AccessibilityButtonTarget> targets = new ArrayList<>(installedServices.size());
        for (AccessibilityServiceInfo info : installedServices) {
            if ((info.flags & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0) {
                targets.add(new AccessibilityButtonTarget(context, info));
            }
        }

        final List<String> requiredTargets = ams.getAccessibilityShortcutTargets(shortcutType);
        targets.removeIf(target -> !requiredTargets.contains(target.getId()));

        // TODO(b/146815874): Will replace it with white list services.
        if (Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED, 0) == 1) {
            final AccessibilityButtonTarget magnificationTarget = new AccessibilityButtonTarget(
                    context,
                    MAGNIFICATION_COMPONENT_ID,
                    R.string.accessibility_magnification_chooser_text,
                    R.drawable.ic_accessibility_magnification,
                    AccessibilityServiceFragmentType.INTUITIVE);
            targets.add(magnificationTarget);
        }

        return targets;
    }

    private static class ViewHolder {
        ImageView mIconView;
        TextView mLabelView;
        FrameLayout mItemContainer;
        ImageView mViewItem;
        Switch mSwitchItem;
    }

    private static class TargetAdapter extends BaseAdapter {
        @ShortcutMenuMode
        private int mShortcutMenuMode = ShortcutMenuMode.LAUNCH;
        private List<AccessibilityButtonTarget> mButtonTargets;

        TargetAdapter(List<AccessibilityButtonTarget> targets) {
            this.mButtonTargets = targets;
        }

        void setShortcutMenuMode(int shortcutMenuMode) {
            mShortcutMenuMode = shortcutMenuMode;
        }

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
                holder.mIconView = convertView.findViewById(R.id.accessibility_button_target_icon);
                holder.mLabelView = convertView.findViewById(
                        R.id.accessibility_button_target_label);
                holder.mItemContainer = convertView.findViewById(
                        R.id.accessibility_button_target_item_container);
                holder.mViewItem = convertView.findViewById(
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
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);

            holder.mLabelView.setEnabled(!isEditMenuMode);
            holder.mViewItem.setEnabled(!isEditMenuMode);
            holder.mViewItem.setImageDrawable(context.getDrawable(R.drawable.ic_delete_item));
            holder.mViewItem.setVisibility(View.VISIBLE);
            holder.mSwitchItem.setVisibility(View.GONE);
            holder.mItemContainer.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
        }

        private void updateInvisibleActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder) {
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);

            holder.mViewItem.setImageDrawable(context.getDrawable(R.drawable.ic_delete_item));
            holder.mViewItem.setVisibility(View.VISIBLE);
            holder.mSwitchItem.setVisibility(View.GONE);
            holder.mItemContainer.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
        }

        private void updateIntuitiveActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder, AccessibilityButtonTarget target) {
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);

            holder.mViewItem.setImageDrawable(context.getDrawable(R.drawable.ic_delete_item));
            holder.mViewItem.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
            holder.mSwitchItem.setVisibility(isEditMenuMode ? View.GONE : View.VISIBLE);
            holder.mSwitchItem.setChecked(!isEditMenuMode && isServiceEnabled(context, target));
            holder.mItemContainer.setVisibility(View.VISIBLE);
        }

        private void updateBounceActionItemVisibility(@NonNull Context context,
                @NonNull ViewHolder holder) {
            final boolean isEditMenuMode = (mShortcutMenuMode == ShortcutMenuMode.EDIT);

            holder.mViewItem.setImageDrawable(
                    isEditMenuMode ? context.getDrawable(R.drawable.ic_delete_item)
                            : context.getDrawable(R.drawable.ic_open_in_new));
            holder.mViewItem.setVisibility(isEditMenuMode ? View.VISIBLE : View.GONE);
            holder.mSwitchItem.setVisibility(View.GONE);
            holder.mItemContainer.setVisibility(View.VISIBLE);
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

    private static boolean isServiceEnabled(@NonNull Context context,
            AccessibilityButtonTarget target) {
        final AccessibilityManager ams = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
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
        Settings.Secure.putString(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT,
                mTargets.get(position).getId());
        // TODO(b/146969684): notify accessibility button clicked.
        mAlertDialog.dismiss();
    }

    private void onTargetDeleted(AdapterView<?> parent, View view, int position, long id) {
        final AccessibilityButtonTarget target = mTargets.get(position);
        final ComponentName targetComponentName =
                ComponentName.unflattenFromString(target.getId());

        switch (target.getFragmentType()) {
            case AccessibilityServiceFragmentType.INVISIBLE:
                onInvisibleTargetDeleted(targetComponentName);
                break;
            case AccessibilityServiceFragmentType.INTUITIVE:
                onIntuitiveTargetDeleted(targetComponentName);
                break;
            case AccessibilityServiceFragmentType.LEGACY:
            case AccessibilityServiceFragmentType.BOUNCE:
                // Do nothing
                break;
            default:
                throw new IllegalStateException("Unexpected fragment type");
        }

        mTargets.remove(position);
        mTargetAdapter.notifyDataSetChanged();

        if (mTargets.isEmpty()) {
            mAlertDialog.dismiss();
        }
    }

    private void onInvisibleTargetDeleted(ComponentName componentName) {
        if (mShortcutType == ACCESSIBILITY_BUTTON) {
            optOutValueFromSettings(this, ACCESSIBILITY_BUTTON_USER_TYPE, componentName);

            if (!hasValueInSettings(this,
                    ACCESSIBILITY_SHORTCUT_KEY_USER_TYPE, componentName)) {
                setAccessibilityServiceState(this, componentName, /* enabled= */ false);
            }
        } else if (mShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            optOutValueFromSettings(this, ACCESSIBILITY_SHORTCUT_KEY_USER_TYPE, componentName);

            if (!hasValueInSettings(this,
                    ACCESSIBILITY_BUTTON_USER_TYPE, componentName)) {
                setAccessibilityServiceState(this, componentName, /* enabled= */ false);
            }
        } else {
            throw new IllegalArgumentException("Unsupported shortcut type:" + mShortcutType);
        }
    }

    private void onIntuitiveTargetDeleted(ComponentName componentName) {
        if (mShortcutType == ACCESSIBILITY_BUTTON) {
            optOutValueFromSettings(this, ACCESSIBILITY_BUTTON_USER_TYPE, componentName);
        } else if (mShortcutType == ACCESSIBILITY_SHORTCUT_KEY) {
            optOutValueFromSettings(this, ACCESSIBILITY_SHORTCUT_KEY_USER_TYPE, componentName);
        } else {
            throw new IllegalArgumentException("Unsupported shortcut type:" + mShortcutType);
        }
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
     * @param componentName The component name that need to be opted out from Settings.
     */
    private void optOutValueFromSettings(
            Context context, int shortcutType, ComponentName componentName) {
        final StringJoiner joiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        final String targetsKey = convertToKey(shortcutType);
        final String targetsValue = Settings.Secure.getString(context.getContentResolver(),
                targetsKey);

        if (TextUtils.isEmpty(targetsValue)) {
            return;
        }

        sStringColonSplitter.setString(targetsValue);
        while (sStringColonSplitter.hasNext()) {
            final String name = sStringColonSplitter.next();
            if (TextUtils.isEmpty(name) || (componentName.flattenToString()).equals(name)) {
                continue;
            }
            joiner.add(name);
        }

        Settings.Secure.putString(context.getContentResolver(), targetsKey, joiner.toString());
    }

    /**
     * Returns if component name existed in Settings.
     *
     * @param context The current context.
     * @param shortcutType The preferred shortcut type user selected.
     * @param componentName The component name that need to be checked existed in Settings.
     * @return {@code true} if componentName existed in Settings.
     */
    private boolean hasValueInSettings(Context context, @UserShortcutType int shortcutType,
            @NonNull ComponentName componentName) {
        final String targetKey = convertToKey(shortcutType);
        final String targetString = Settings.Secure.getString(context.getContentResolver(),
                targetKey);

        if (TextUtils.isEmpty(targetString)) {
            return false;
        }

        sStringColonSplitter.setString(targetString);
        while (sStringColonSplitter.hasNext()) {
            final String name = sStringColonSplitter.next();
            if ((componentName.flattenToString()).equals(name)) {
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
