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

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.ResolverDrawerLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Activity used to display and persist a service or feature target for the Accessibility button.
 */
public class AccessibilityButtonChooserActivity extends Activity {

    private static final String MAGNIFICATION_COMPONENT_ID =
            "com.android.server.accessibility.MagnificationController";

    private AccessibilityButtonTarget mMagnificationTarget = null;

    private List<AccessibilityButtonTarget> mTargets = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.accessibility_button_chooser);

        final ResolverDrawerLayout rdl = findViewById(R.id.contentPanel);
        if (rdl != null) {
            rdl.setOnDismissedListener(this::finish);
        }

        String component = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT);

        if (isGestureNavigateEnabled()) {
            TextView promptPrologue = findViewById(R.id.accessibility_button_prompt_prologue);
            promptPrologue.setText(isTouchExploreOn()
                    ? R.string.accessibility_gesture_3finger_prompt_text
                    : R.string.accessibility_gesture_prompt_text);
        }

        if (TextUtils.isEmpty(component)) {
            TextView prompt = findViewById(R.id.accessibility_button_prompt);
            if (isGestureNavigateEnabled()) {
                prompt.setText(isTouchExploreOn()
                        ? R.string.accessibility_gesture_3finger_instructional_text
                        : R.string.accessibility_gesture_instructional_text);
            }
            prompt.setVisibility(View.VISIBLE);
        }

        mMagnificationTarget = new AccessibilityButtonTarget(this, MAGNIFICATION_COMPONENT_ID,
                R.string.accessibility_magnification_chooser_text,
                R.drawable.ic_accessibility_magnification);

        mTargets = getServiceAccessibilityButtonTargets(this);
        if (Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED, 0) == 1) {
            mTargets.add(mMagnificationTarget);
        }

        if (mTargets.size() < 2) {
            // Why are we here?
            finish();
        }

        GridView gridview = findViewById(R.id.accessibility_button_chooser_grid);
        gridview.setAdapter(new TargetAdapter());
        gridview.setOnItemClickListener((parent, view, position, id) -> {
            onTargetSelected(mTargets.get(position));
        });
    }

    private boolean isGestureNavigateEnabled() {
        return NAV_BAR_MODE_GESTURAL == getResources().getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode);
    }

    private boolean isTouchExploreOn() {
        return ((AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE))
                .isTouchExplorationEnabled();
    }

    private static List<AccessibilityButtonTarget> getServiceAccessibilityButtonTargets(
            @NonNull Context context) {
        AccessibilityManager ams = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = ams.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (services == null) {
            return Collections.emptyList();
        }

        ArrayList<AccessibilityButtonTarget> targets = new ArrayList<>(services.size());
        for (AccessibilityServiceInfo info : services) {
            if ((info.flags & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0) {
                targets.add(new AccessibilityButtonTarget(context, info));
            }
        }

        return targets;
    }

    private void onTargetSelected(AccessibilityButtonTarget target) {
        Settings.Secure.putString(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT, target.getId());
        finish();
    }

    private class TargetAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mTargets.size();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = AccessibilityButtonChooserActivity.this.getLayoutInflater();
            View root = inflater.inflate(R.layout.accessibility_button_chooser_item, parent, false);
            final AccessibilityButtonTarget target = mTargets.get(position);
            ImageView iconView = root.findViewById(R.id.accessibility_button_target_icon);
            TextView labelView = root.findViewById(R.id.accessibility_button_target_label);
            iconView.setImageDrawable(target.getDrawable());
            labelView.setText(target.getLabel());
            return root;
        }
    }

    private static class AccessibilityButtonTarget {
        public String mId;
        public CharSequence mLabel;
        public Drawable mDrawable;

        public AccessibilityButtonTarget(@NonNull Context context,
                @NonNull AccessibilityServiceInfo serviceInfo) {
            this.mId = serviceInfo.getComponentName().flattenToString();
            this.mLabel = serviceInfo.getResolveInfo().loadLabel(context.getPackageManager());
            this.mDrawable = serviceInfo.getResolveInfo().loadIcon(context.getPackageManager());
        }

        public AccessibilityButtonTarget(Context context, @NonNull String id, int labelResId,
                int iconRes) {
            this.mId = id;
            this.mLabel = context.getText(labelResId);
            this.mDrawable = context.getDrawable(iconRes);
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
    }
}
