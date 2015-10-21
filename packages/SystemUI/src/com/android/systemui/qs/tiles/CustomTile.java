/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.qs.QSTile;

public class CustomTile extends QSTile<QSTile.State> {
    public static final String PREFIX = "custom(";

    private final ComponentName mComponent;

    private CustomTile(Host host, String action) {
        super(host);
        mComponent = ComponentName.unflattenFromString(action);
    }

    public static QSTile<?> create(Host host, String spec) {
        if (spec == null || !spec.startsWith(PREFIX) || !spec.endsWith(")")) {
            throw new IllegalArgumentException("Bad intent tile spec: " + spec);
        }
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty intent tile spec action");
        }
        return new CustomTile(host, action);
    }

    @Override
    public void setListening(boolean listening) {
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
    }

    @Override
    protected void handleClick() {
        MetricsLogger.action(mContext, getMetricsCategory(), mComponent.getPackageName());
    }

    @Override
    protected void handleLongClick() {
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        // TODO: Actual things.
        try {
            PackageManager pm = mContext.getPackageManager();
            ServiceInfo info = pm.getServiceInfo(mComponent, 0);
            state.visible = true;
            state.icon = new DrawableIcon(info.loadIcon(pm));
            state.label = info.loadLabel(pm).toString();
            state.contentDescription = state.label;
        } catch (Exception e) {
            state.visible = false;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_INTENT;
    }
}
