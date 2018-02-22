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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import java.util.function.BooleanSupplier;
import java.util.List;

/**
 * The adapter used for the list within the global actions dialog, taking into account whether the
 * keyguard is showing via {@link LegacyGlobalActions#mKeyguardShowing} and whether the device is
 * provisioned via {@link LegacyGlobalActions#mDeviceProvisioned}.
 */
public class ActionsAdapter extends BaseAdapter {
    private final Context mContext;
    private final List<Action> mItems;
    private final BooleanSupplier mDeviceProvisioned;
    private final BooleanSupplier mKeyguardShowing;

    public ActionsAdapter(Context context, List<Action> items,
            BooleanSupplier deviceProvisioned, BooleanSupplier keyguardShowing) {
        mContext = context;
        mItems = items;
        mDeviceProvisioned = deviceProvisioned;
        mKeyguardShowing = keyguardShowing;
    }

    @Override
    public int getCount() {
        final boolean keyguardShowing = mKeyguardShowing.getAsBoolean();
        final boolean deviceProvisioned = mDeviceProvisioned.getAsBoolean();
        int count = 0;

        for (int i = 0; i < mItems.size(); i++) {
            final Action action = mItems.get(i);

            if (keyguardShowing && !action.showDuringKeyguard()) {
                continue;
            }
            if (!deviceProvisioned && !action.showBeforeProvisioning()) {
                continue;
            }
            count++;
        }
        return count;
    }

    @Override
    public boolean isEnabled(int position) {
        return getItem(position).isEnabled();
    }

    @Override
    public boolean areAllItemsEnabled() {
        return false;
    }

    @Override
    public Action getItem(int position) {
        final boolean keyguardShowing = mKeyguardShowing.getAsBoolean();
        final boolean deviceProvisioned = mDeviceProvisioned.getAsBoolean();

        int filteredPos = 0;
        for (int i = 0; i < mItems.size(); i++) {
            final Action action = mItems.get(i);
            if (keyguardShowing && !action.showDuringKeyguard()) {
                continue;
            }
            if (!deviceProvisioned && !action.showBeforeProvisioning()) {
                continue;
            }
            if (filteredPos == position) {
                return action;
            }
            filteredPos++;
        }

        throw new IllegalArgumentException("position " + position
                + " out of range of showable actions"
                + ", filtered count=" + getCount()
                + ", keyguardshowing=" + keyguardShowing
                + ", provisioned=" + deviceProvisioned);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Action action = getItem(position);
        return action.create(mContext, convertView, parent, LayoutInflater.from(mContext));
    }
}
