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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.R;

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

    private AlertDialog mAlertDialog;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final TypedArray theme = getTheme().obtainStyledAttributes(android.R.styleable.Theme);
        if (!theme.getBoolean(android.R.styleable.Theme_windowNoTitle, /* defValue= */false)) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        // TODO(b/146815874): Will replace it with white list services
        mMagnificationTarget = new AccessibilityButtonTarget(this, MAGNIFICATION_COMPONENT_ID,
                R.string.accessibility_magnification_chooser_text,
                R.drawable.ic_accessibility_magnification);

        // TODO(b/146815544): Will use shortcut type or button type to get the corresponding
        //  services
        mTargets = getServiceAccessibilityButtonTargets(this);
        if (Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_NAVBAR_ENABLED, 0) == 1) {
            mTargets.add(mMagnificationTarget);
        }

        // TODO(b/146815548): Will add title to separate which one type
        mAlertDialog = new AlertDialog.Builder(this)
                .setAdapter(new TargetAdapter(),
                        (dialog, position) -> onTargetSelected(mTargets.get(position)))
                .setOnDismissListener(dialog -> finish())
                .create();
        mAlertDialog.show();
    }

    @Override
    protected void onDestroy() {
        mAlertDialog.dismiss();
        super.onDestroy();
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

            // TODO(b/146815874): Need to get every service status to update UI
            return root;
        }
    }

    private static class AccessibilityButtonTarget {
        public String mId;
        public CharSequence mLabel;
        public Drawable mDrawable;
        // TODO(b/146815874): Will add fragment type and related functions
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
