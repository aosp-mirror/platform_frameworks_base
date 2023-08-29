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

import static com.android.systemui.statusbar.phone.StatusBarIconList.Slot;

import android.annotation.NonNull;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.ViewGroup;

import androidx.annotation.VisibleForTesting;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.Dumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusIconDisplayable;
import com.android.systemui.statusbar.phone.StatusBarSignalPolicy.CallIndicatorIconState;
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

/**
 * Receives the callbacks from CommandQueue related to icons and tracks the state of
 * all the icons. Dispatches this state to any IconManagers that are currently
 * registered with it.
 */
@SysUISingleton
public class StatusBarIconControllerImpl implements Tunable,
        ConfigurationListener, Dumpable, StatusBarIconController, DemoMode {

    private static final String TAG = "StatusBarIconController";
    // Use this suffix to prevent external icon slot names from unintentionally overriding our
    // internal, system-level slot names. See b/255428281.
    @VisibleForTesting
    protected static final String EXTERNAL_SLOT_SUFFIX = "__external";

    private final StatusBarIconList mStatusBarIconList;
    private final ArrayList<IconManager> mIconGroups = new ArrayList<>();
    private final ArraySet<String> mIconHideList = new ArraySet<>();
    private final StatusBarPipelineFlags mStatusBarPipelineFlags;
    private final Context mContext;

    /** */
    @Inject
    public StatusBarIconControllerImpl(
            Context context,
            CommandQueue commandQueue,
            DemoModeController demoModeController,
            ConfigurationController configurationController,
            TunerService tunerService,
            DumpManager dumpManager,
            StatusBarIconList statusBarIconList,
            StatusBarPipelineFlags statusBarPipelineFlags
    ) {
        mStatusBarIconList = statusBarIconList;
        mContext = context;
        mStatusBarPipelineFlags = statusBarPipelineFlags;

        configurationController.addCallback(this);
        commandQueue.addCallback(mCommandQueueCallbacks);
        tunerService.addTunable(this, ICON_HIDE_LIST);
        demoModeController.addCallback(this);
        dumpManager.registerDumpable(getClass().getSimpleName(), this);
    }

    /** */
    @Override
    public void addIconGroup(IconManager group) {
        for (IconManager existingIconManager : mIconGroups) {
            if (existingIconManager.mGroup == group.mGroup) {
                Log.e(TAG, "Adding new IconManager for the same ViewGroup. This could cause "
                        + "unexpected results.");
            }
        }

        group.setController(this);
        group.reloadDimens();
        mIconGroups.add(group);
        List<Slot> allSlots = mStatusBarIconList.getSlots();
        for (int i = 0; i < allSlots.size(); i++) {
            Slot slot = allSlots.get(i);
            List<StatusBarIconHolder> holders = slot.getHolderListInViewOrder();
            boolean hidden = mIconHideList.contains(slot.getName());

            for (StatusBarIconHolder holder : holders) {
                int viewIndex = mStatusBarIconList.getViewIndex(slot.getName(), holder.getTag());
                group.onIconAdded(viewIndex, slot.getName(), hidden, holder);
            }
        }
    }

    @Override
    public void refreshIconGroup(IconManager iconManager) {
        removeIconGroup(iconManager);
        addIconGroup(iconManager);
    }

    private void refreshIconGroups() {
        for (int i = mIconGroups.size() - 1; i >= 0; --i) {
            IconManager group = mIconGroups.get(i);
            removeIconGroup(group);
            addIconGroup(group);
        }
    }

    /** */
    @Override
    public void removeIconGroup(IconManager group) {
        group.destroy();
        mIconGroups.remove(group);
    }

    /** */
    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!ICON_HIDE_LIST.equals(key)) {
            return;
        }
        mIconHideList.clear();
        mIconHideList.addAll(StatusBarIconController.getIconHideList(mContext, newValue));
        List<Slot> currentSlots = mStatusBarIconList.getSlots();
        ArrayMap<Slot, List<StatusBarIconHolder>> slotsToReAdd = new ArrayMap<>();

        // This is a little hacky... Peel off all of the holders on all of the slots
        // but keep them around so they can be re-added

        // Remove all the icons.
        for (int i = currentSlots.size() - 1; i >= 0; i--) {
            Slot s = currentSlots.get(i);
            slotsToReAdd.put(s, s.getHolderList());
            removeAllIconsForSlot(s.getName(), /* fromNewPipeline */ false);
        }

        // Add them all back
        for (int i = 0; i < currentSlots.size(); i++) {
            Slot item = currentSlots.get(i);
            List<StatusBarIconHolder> iconsForSlot = slotsToReAdd.get(item);
            if (iconsForSlot == null) continue;
            for (StatusBarIconHolder holder : iconsForSlot) {
                setIcon(item.getName(), holder);
            }
        }
    }

    private void addSystemIcon(String slot, StatusBarIconHolder holder) {
        int viewIndex = mStatusBarIconList.getViewIndex(slot, holder.getTag());
        boolean hidden = mIconHideList.contains(slot);

        mIconGroups.forEach(l -> l.onIconAdded(viewIndex, slot, hidden, holder));
    }

    /** */
    @Override
    public void setIcon(String slot, int resourceId, CharSequence contentDescription) {
        StatusBarIconHolder holder = mStatusBarIconList.getIconHolder(slot, 0);
        if (holder == null) {
            StatusBarIcon icon = new StatusBarIcon(UserHandle.SYSTEM, mContext.getPackageName(),
                    Icon.createWithResource(
                            mContext, resourceId), 0, 0, contentDescription);
            holder = StatusBarIconHolder.fromIcon(icon);
            setIcon(slot, holder);
        } else {
            holder.getIcon().icon = Icon.createWithResource(mContext, resourceId);
            holder.getIcon().contentDescription = contentDescription;
            handleSet(slot, holder);
        }
    }

    @Override
    public void setNewWifiIcon() {
        String slot = mContext.getString(com.android.internal.R.string.status_bar_wifi);
        StatusBarIconHolder holder = mStatusBarIconList.getIconHolder(slot, /* tag= */ 0);
        if (holder == null) {
            holder = StatusBarIconHolder.forNewWifiIcon();
            setIcon(slot, holder);
        } else {
            // Don't have to do anything in the new world
        }
    }

    /**
     * Accept a list of MobileIconStates, which all live in the same slot(?!), and then are sorted
     * by subId. Don't worry this definitely makes sense and works.
     * @param subIds list of subscription ID integers that provide the key to the icon to display.
     */
    @Override
    public void setNewMobileIconSubIds(List<Integer> subIds) {
        String slotName = mContext.getString(com.android.internal.R.string.status_bar_mobile);
        Slot mobileSlot = mStatusBarIconList.getSlot(slotName);

        // Because of the way we cache the icon holders, we need to remove everything any time
        // we get a new set of subscriptions. This might change in the future, but is required
        // to support demo mode for now
        removeAllIconsForSlot(slotName, /* fromNewPipeline */ true);

        Collections.reverse(subIds);

        for (Integer subId : subIds) {
            StatusBarIconHolder holder = mobileSlot.getHolderForTag(subId);
            if (holder == null) {
                holder = StatusBarIconHolder.fromSubIdForModernMobileIcon(subId);
                setIcon(slotName, holder);
            } else {
                // Don't have to do anything in the new world
            }
        }
    }

    /**
     * Accept a list of CallIndicatorIconStates, and show the call strength icons.
     * @param slot statusbar slot for the call strength icons
     * @param states All of the no Calling & SMS icon states
     */
    @Override
    public void setCallStrengthIcons(String slot, List<CallIndicatorIconState> states) {
        Slot callStrengthSlot = mStatusBarIconList.getSlot(slot);
        Collections.reverse(states);
        for (CallIndicatorIconState state : states) {
            if (!state.isNoCalling) {
                StatusBarIconHolder holder = callStrengthSlot.getHolderForTag(state.subId);
                if (holder == null) {
                    holder = StatusBarIconHolder.fromCallIndicatorState(mContext, state);
                } else {
                    holder.setIcon(new StatusBarIcon(UserHandle.SYSTEM, mContext.getPackageName(),
                            Icon.createWithResource(mContext, state.callStrengthResId), 0, 0,
                            state.callStrengthDescription));
                }
                setIcon(slot, holder);
            }
            setIconVisibility(slot, !state.isNoCalling, state.subId);
        }
    }

    /**
     * Accept a list of CallIndicatorIconStates, and show the no calling icons.
     * @param slot statusbar slot for the no calling icons
     * @param states All of the no Calling & SMS icon states
     */
    @Override
    public void setNoCallingIcons(String slot, List<CallIndicatorIconState> states) {
        Slot noCallingSlot = mStatusBarIconList.getSlot(slot);
        Collections.reverse(states);
        for (CallIndicatorIconState state : states) {
            if (state.isNoCalling) {
                StatusBarIconHolder holder = noCallingSlot.getHolderForTag(state.subId);
                if (holder == null) {
                    holder = StatusBarIconHolder.fromCallIndicatorState(mContext, state);
                } else {
                    holder.setIcon(new StatusBarIcon(UserHandle.SYSTEM, mContext.getPackageName(),
                            Icon.createWithResource(mContext, state.noCallingResId), 0, 0,
                            state.noCallingDescription));
                }
                setIcon(slot, holder);
            }
            setIconVisibility(slot, state.isNoCalling, state.subId);
        }
    }

    private final CommandQueue.Callbacks mCommandQueueCallbacks = new CommandQueue.Callbacks() {
        @Override
        public void setIcon(String slot, StatusBarIcon icon) {
            // Icons that come from CommandQueue are from external services.
            setExternalIcon(slot, icon);
        }

        @Override
        public void removeIcon(String slot) {
            removeAllIconsForExternalSlot(slot);
        }
    };

    @Override
    public void setIconFromTile(String slot, StatusBarIcon icon) {
        setExternalIcon(slot, icon);
    }

    @Override
    public void removeIconForTile(String slot) {
        removeAllIconsForExternalSlot(slot);
    }

    private void setExternalIcon(String slot, StatusBarIcon icon) {
        if (icon == null) {
            removeAllIconsForExternalSlot(slot);
            return;
        }
        String slotName = createExternalSlotName(slot);
        StatusBarIconHolder holder = StatusBarIconHolder.fromIcon(icon);
        setIcon(slotName, holder);
    }

    private void setIcon(String slot, @NonNull StatusBarIconHolder holder) {
        boolean isNew = mStatusBarIconList.getIconHolder(slot, holder.getTag()) == null;
        mStatusBarIconList.setIcon(slot, holder);

        if (isNew) {
            addSystemIcon(slot, holder);
        } else {
            handleSet(slot, holder);
        }
    }

    /** */
    public void setIconVisibility(String slot, boolean visibility) {
        setIconVisibility(slot, visibility, 0);
    }

    /** */
    public void setIconVisibility(String slot, boolean visibility, int tag) {
        StatusBarIconHolder holder = mStatusBarIconList.getIconHolder(slot, tag);
        if (holder == null || holder.isVisible() == visibility) {
            return;
        }

        holder.setVisible(visibility);
        handleSet(slot, holder);
    }

    /** */
    @Override
    public void setIconAccessibilityLiveRegion(String slotName, int accessibilityLiveRegion) {
        Slot slot = mStatusBarIconList.getSlot(slotName);
        if (!slot.hasIconsInSlot()) {
            return;
        }

        List<StatusBarIconHolder> iconsToUpdate = slot.getHolderListInViewOrder();
        for (StatusBarIconHolder holder : iconsToUpdate) {
            int viewIndex = mStatusBarIconList.getViewIndex(slotName, holder.getTag());
            mIconGroups.forEach(l -> l.mGroup.getChildAt(viewIndex)
                    .setAccessibilityLiveRegion(accessibilityLiveRegion));
        }
    }

    /** */
    @Override
    public void removeIcon(String slot, int tag) {
        // If the new pipeline is on for this icon, don't allow removal, since the new pipeline
        // will never call this method
        if (mStatusBarPipelineFlags.isIconControlledByFlags(slot)) {
            Log.i(TAG, "Ignoring removal of (" + slot + "). "
                    + "It should be controlled elsewhere");
            return;
        }

        if (mStatusBarIconList.getIconHolder(slot, tag) == null) {
            return;
        }
        int viewIndex = mStatusBarIconList.getViewIndex(slot, tag);
        mStatusBarIconList.removeIcon(slot, tag);
        mIconGroups.forEach(l -> l.onRemoveIcon(viewIndex));
    }

    private void removeAllIconsForExternalSlot(String slotName) {
        removeAllIconsForSlot(createExternalSlotName(slotName), /* fromNewPipeline= */ false);
    }

    private void removeAllIconsForSlot(String slotName, boolean fromNewPipeline) {
        // If the new pipeline is on for this icon, don't allow removal, since the new pipeline
        // will never call this method
        if (!fromNewPipeline && mStatusBarPipelineFlags.isIconControlledByFlags(slotName)) {
            Log.i(TAG, "Ignoring removal of (" + slotName + "). "
                    + "It should be controlled elsewhere");
            return;
        }

        Slot slot = mStatusBarIconList.getSlot(slotName);
        if (!slot.hasIconsInSlot()) {
            return;
        }

        List<StatusBarIconHolder> iconsToRemove = slot.getHolderListInViewOrder();
        for (StatusBarIconHolder holder : iconsToRemove) {
            int viewIndex = mStatusBarIconList.getViewIndex(slotName, holder.getTag());
            slot.removeForTag(holder.getTag());
            mIconGroups.forEach(l -> l.onRemoveIcon(viewIndex));
        }
    }

    private void handleSet(String slotName, StatusBarIconHolder holder) {
        int viewIndex = mStatusBarIconList.getViewIndex(slotName, holder.getTag());
        mIconGroups.forEach(l -> l.onSetIconHolder(viewIndex, holder));
    }

    /** */
    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(TAG + " state:");
        for (IconManager manager : mIconGroups) {
            if (manager.shouldLog()) {
                ViewGroup group = manager.mGroup;
                int N = group.getChildCount();
                pw.println("  icon views: " + N);
                for (int i = 0; i < N; i++) {
                    StatusIconDisplayable ic = (StatusIconDisplayable) group.getChildAt(i);
                    pw.println("    [" + i + "] icon=" + ic);
                }
            }
        }

        mStatusBarIconList.dump(pw);
    }

    /** */
    @Override
    public void onDemoModeStarted() {
        for (IconManager manager : mIconGroups) {
            if (manager.isDemoable()) {
                manager.onDemoModeStarted();
            }
        }
    }

    /** */
    @Override
    public void onDemoModeFinished() {
        for (IconManager manager : mIconGroups) {
            if (manager.isDemoable()) {
                manager.onDemoModeFinished();
            }
        }
    }

    /** */
    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        for (IconManager manager : mIconGroups) {
            if (manager.isDemoable()) {
                manager.dispatchDemoCommand(command, args);
            }
        }
    }

    /** */
    @Override
    public List<String> demoCommands() {
        List<String> s = new ArrayList<>();
        s.add(DemoMode.COMMAND_STATUS);
        return s;
    }

    /** */
    @Override
    public void onDensityOrFontScaleChanged() {
        refreshIconGroups();
    }

    private String createExternalSlotName(String slot) {
        if (slot.endsWith(EXTERNAL_SLOT_SUFFIX)) {
            return slot;
        } else {
            return slot + EXTERNAL_SLOT_SUFFIX;
        }
    }
}
