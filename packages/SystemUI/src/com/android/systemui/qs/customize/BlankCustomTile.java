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

package com.android.systemui.qs.customize;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;

public class BlankCustomTile extends QSTile<QSTile.State> {
    public static final String PREFIX = "custom(";

    private final ComponentName mComponent;

    private BlankCustomTile(Host host, String action) {
        super(host);
        mComponent = ComponentName.unflattenFromString(action);
    }

    public static QSTile<?> create(Host host, String spec) {
        if (spec == null || !spec.startsWith(PREFIX) || !spec.endsWith(")")) {
            throw new IllegalArgumentException("Bad custom tile spec: " + spec);
        }
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty custom tile spec action");
        }
        return new BlankCustomTile(host, action);
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
        try {
            PackageManager pm = mContext.getPackageManager();
            ServiceInfo info = pm.getServiceInfo(mComponent, 0);
            Drawable drawable = info.loadIcon(pm);
            drawable.setTint(mContext.getColor(R.color.qs_tile_tint_active));
            state.icon = new DrawableIcon(drawable);
            state.label = info.loadLabel(pm).toString();
            state.contentDescription = state.label;
        } catch (Exception e) {
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_INTENT;
    }
}
