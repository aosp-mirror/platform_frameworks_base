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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArraySet;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.IconLogger;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Receives the callbacks from CommandQueue related to icons and tracks the state of
 * all the icons. Dispatches this state to any IconManagers that are currently
 * registered with it.
 */
public class StatusBarIconControllerImpl extends StatusBarIconList implements Tunable,
        ConfigurationListener, Dumpable, CommandQueue.Callbacks, StatusBarIconController {

    private final DarkIconDispatcher mDarkIconDispatcher;

    private Context mContext;
    private DemoStatusIcons mDemoStatusIcons;

    private final ArrayList<IconManager> mIconGroups = new ArrayList<>();

    private final ArraySet<String> mIconBlacklist = new ArraySet<>();
    private final IconLogger mIconLogger = Dependency.get(IconLogger.class);

    public StatusBarIconControllerImpl(Context context) {
        super(context.getResources().getStringArray(
                com.android.internal.R.array.config_statusBarIcons));
        Dependency.get(ConfigurationController.class).addCallback(this);
        mDarkIconDispatcher = Dependency.get(DarkIconDispatcher.class);
        mContext = context;

        loadDimens();

        SysUiServiceProvider.getComponent(context, CommandQueue.class)
                .addCallbacks(this);
        Dependency.get(TunerService.class).addTunable(this, ICON_BLACKLIST);
    }

    @Override
    public void addIconGroup(IconManager group) {
        mIconGroups.add(group);
        for (int i = 0; i < mIcons.size(); i++) {
            StatusBarIcon icon = mIcons.get(i);
            if (icon != null) {
                String slot = mSlots.get(i);
                boolean blocked = mIconBlacklist.contains(slot);
                group.onIconAdded(getViewIndex(getSlotIndex(slot)), slot, blocked, icon);
            }
        }
    }

    @Override
    public void removeIconGroup(IconManager group) {
        group.destroy();
        mIconGroups.remove(group);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!ICON_BLACKLIST.equals(key)) {
            return;
        }
        mIconBlacklist.clear();
        mIconBlacklist.addAll(StatusBarIconController.getIconBlacklist(newValue));
        ArrayList<StatusBarIcon> current = new ArrayList<>(mIcons);
        ArrayList<String> currentSlots = new ArrayList<>(mSlots);
        // Remove all the icons.
        for (int i = current.size() - 1; i >= 0; i--) {
            removeIcon(currentSlots.get(i));
        }
        // Add them all back
        for (int i = 0; i < current.size(); i++) {
            setIcon(currentSlots.get(i), current.get(i));
        }
    }

    private void loadDimens() {
    }

    private void addSystemIcon(int index, StatusBarIcon icon) {
        String slot = getSlot(index);
        int viewIndex = getViewIndex(index);
        boolean blocked = mIconBlacklist.contains(slot);

        mIconLogger.onIconVisibility(getSlot(index), icon.visible);
        mIconGroups.forEach(l -> l.onIconAdded(viewIndex, slot, blocked, icon));
    }

    @Override
    public void setIcon(String slot, int resourceId, CharSequence contentDescription) {
        int index = getSlotIndex(slot);
        StatusBarIcon icon = getIcon(index);
        if (icon == null) {
            icon = new StatusBarIcon(UserHandle.SYSTEM, mContext.getPackageName(),
                    Icon.createWithResource(mContext, resourceId), 0, 0, contentDescription);
            setIcon(slot, icon);
        } else {
            icon.icon = Icon.createWithResource(mContext, resourceId);
            icon.contentDescription = contentDescription;
            handleSet(index, icon);
        }
    }

    @Override
    public void setExternalIcon(String slot) {
        int viewIndex = getViewIndex(getSlotIndex(slot));
        int height = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_icon_drawing_size);
        mIconGroups.forEach(l -> l.onIconExternal(viewIndex, height));
    }

    @Override
    public void setIcon(String slot, StatusBarIcon icon) {
        setIcon(getSlotIndex(slot), icon);
    }

    @Override
    public void removeIcon(String slot) {
        int index = getSlotIndex(slot);
        removeIcon(index);
    }

    public void setIconVisibility(String slot, boolean visibility) {
        int index = getSlotIndex(slot);
        StatusBarIcon icon = getIcon(index);
        if (icon == null || icon.visible == visibility) {
            return;
        }
        icon.visible = visibility;
        handleSet(index, icon);
    }

    @Override
    public void removeIcon(int index) {
        if (getIcon(index) == null) {
            return;
        }
        mIconLogger.onIconHidden(getSlot(index));
        super.removeIcon(index);
        int viewIndex = getViewIndex(index);
        mIconGroups.forEach(l -> l.onRemoveIcon(viewIndex));
    }

    @Override
    public void setIcon(int index, StatusBarIcon icon) {
        if (icon == null) {
            removeIcon(index);
            return;
        }
        boolean isNew = getIcon(index) == null;
        super.setIcon(index, icon);
        if (isNew) {
            addSystemIcon(index, icon);
        } else {
            handleSet(index, icon);
        }
    }

    private void handleSet(int index, StatusBarIcon icon) {
        int viewIndex = getViewIndex(index);
        mIconLogger.onIconVisibility(getSlot(index), icon.visible);
        mIconGroups.forEach(l -> l.onSetIcon(viewIndex, icon));
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // TODO: Dump info about all icon groups?
        ViewGroup statusIcons = mIconGroups.get(0).mGroup;
        int N = statusIcons.getChildCount();
        pw.println("  icon views: " + N);
        for (int i = 0; i < N; i++) {
            StatusBarIconView ic = (StatusBarIconView) statusIcons.getChildAt(i);
            pw.println("    [" + i + "] icon=" + ic);
        }
        super.dump(pw);
    }

    public void dispatchDemoCommand(String command, Bundle args) {
        if (mDemoStatusIcons == null) {
            // TODO: Rework how we handle demo mode.
            int iconSize = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_icon_size);
            mDemoStatusIcons = new DemoStatusIcons((LinearLayout) mIconGroups.get(0).mGroup,
                    iconSize);
        }
        mDemoStatusIcons.dispatchDemoCommand(command, args);
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        loadDimens();
    }
}
