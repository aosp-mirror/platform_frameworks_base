/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.globalactions;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.R;

/** A single press action maintains no state, just responds to a press and takes an action. */
public abstract class SinglePressAction implements Action {
    private final int mIconResId;
    private final Drawable mIcon;
    private final int mMessageResId;
    private final CharSequence mMessage;

    protected SinglePressAction(int iconResId, int messageResId) {
        mIconResId = iconResId;
        mMessageResId = messageResId;
        mMessage = null;
        mIcon = null;
    }

    protected SinglePressAction(int iconResId, Drawable icon, CharSequence message) {
        mIconResId = iconResId;
        mMessageResId = 0;
        mMessage = message;
        mIcon = icon;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    public String getStatus() {
        return null;
    }

    @Override
    abstract public void onPress();

    @Override
    public CharSequence getLabelForAccessibility(Context context) {
        if (mMessage != null) {
            return mMessage;
        } else {
            return context.getString(mMessageResId);
        }
    }

    @Override
    public View create(
            Context context, View convertView, ViewGroup parent, LayoutInflater inflater) {
        View v = inflater.inflate(R.layout.global_actions_item, parent, false);

        ImageView icon = v.findViewById(R.id.icon);
        TextView messageView = v.findViewById(R.id.message);

        TextView statusView = v.findViewById(R.id.status);
        final String status = getStatus();
        if (statusView != null) {
            if (!TextUtils.isEmpty(status)) {
                statusView.setText(status);
            } else {
                statusView.setVisibility(View.GONE);
            }
        }
        if (icon != null) {
            if (mIcon != null) {
                icon.setImageDrawable(mIcon);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else if (mIconResId != 0) {
                icon.setImageDrawable(context.getDrawable(mIconResId));
            }
        }
        if (messageView != null) {
            if (mMessage != null) {
                messageView.setText(mMessage);
            } else {
                messageView.setText(mMessageResId);
            }
        }

        return v;
    }
}
