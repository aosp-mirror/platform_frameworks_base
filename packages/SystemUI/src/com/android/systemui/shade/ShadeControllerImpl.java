/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade;

import android.util.Log;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.android.systemui.assist.AssistManager;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

import java.util.ArrayList;
import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;

/** An implementation of {@link ShadeController}. */
@SysUISingleton
public class ShadeControllerImpl implements ShadeController {

    private static final String TAG = "ShadeControllerImpl";
    private static final boolean SPEW = false;

    private final CommandQueue mCommandQueue;
    private final StatusBarStateController mStatusBarStateController;
    protected final NotificationShadeWindowController mNotificationShadeWindowController;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final int mDisplayId;
    protected final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;
    private final Lazy<AssistManager> mAssistManagerLazy;

    private final ArrayList<Runnable> mPostCollapseRunnables = new ArrayList<>();

    @Inject
    public ShadeControllerImpl(
            CommandQueue commandQueue,
            StatusBarStateController statusBarStateController,
            NotificationShadeWindowController notificationShadeWindowController,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            WindowManager windowManager,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            Lazy<AssistManager> assistManagerLazy
    ) {
        mCommandQueue = commandQueue;
        mStatusBarStateController = statusBarStateController;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mDisplayId = windowManager.getDefaultDisplay().getDisplayId();
        // TODO: Remove circular reference to CentralSurfaces when possible.
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
        mAssistManagerLazy = assistManagerLazy;
    }

    @Override
    public void instantExpandNotificationsPanel() {
        // Make our window larger and the panel expanded.
        getCentralSurfaces().makeExpandedVisible(true /* force */);
        getNotificationPanelViewController().expand(false /* animate */);
        mCommandQueue.recomputeDisableFlags(mDisplayId, false /* animate */);
    }

    @Override
    public void animateCollapsePanels() {
        animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_NONE);
    }

    @Override
    public void animateCollapsePanels(int flags) {
        animateCollapsePanels(flags, false /* force */, false /* delayed */,
                1.0f /* speedUpFactor */);
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force) {
        animateCollapsePanels(flags, force, false /* delayed */, 1.0f /* speedUpFactor */);
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force, boolean delayed) {
        animateCollapsePanels(flags, force, delayed, 1.0f /* speedUpFactor */);
    }

    @Override
    public void animateCollapsePanels(int flags, boolean force, boolean delayed,
            float speedUpFactor) {
        if (!force && mStatusBarStateController.getState() != StatusBarState.SHADE) {
            runPostCollapseRunnables();
            return;
        }
        if (SPEW) {
            Log.d(TAG, "animateCollapse():"
                    + " mExpandedVisible=" + getCentralSurfaces().isExpandedVisible()
                    + " flags=" + flags);
        }

        // TODO(b/62444020): remove when this bug is fixed
        Log.v(TAG, "NotificationShadeWindow: " + getNotificationShadeWindowView()
                + " canPanelBeCollapsed(): "
                + getNotificationPanelViewController().canPanelBeCollapsed());
        if (getNotificationShadeWindowView() != null
                && getNotificationPanelViewController().canPanelBeCollapsed()
                && (flags & CommandQueue.FLAG_EXCLUDE_NOTIFICATION_PANEL) == 0) {
            // release focus immediately to kick off focus change transition
            mNotificationShadeWindowController.setNotificationShadeFocusable(false);

            getCentralSurfaces().getNotificationShadeWindowViewController().cancelExpandHelper();
            getNotificationPanelViewController()
                    .collapsePanel(true /* animate */, delayed, speedUpFactor);
        }
    }


    @Override
    public boolean closeShadeIfOpen() {
        if (!getNotificationPanelViewController().isFullyCollapsed()) {
            mCommandQueue.animateCollapsePanels(
                    CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL, true /* force */);
            getCentralSurfaces().visibilityChanged(false);
            mAssistManagerLazy.get().hideAssist();
        }
        return false;
    }

    @Override
    public boolean isShadeOpen() {
        NotificationPanelViewController controller =
                getNotificationPanelViewController();
        return controller.isExpanding() || controller.isFullyExpanded();
    }

    @Override
    public void postOnShadeExpanded(Runnable executable) {
        getNotificationPanelViewController().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (getCentralSurfaces().getNotificationShadeWindowView()
                                .isVisibleToUser()) {
                            getNotificationPanelViewController().removeOnGlobalLayoutListener(this);
                            getNotificationPanelViewController().getView().post(executable);
                        }
                    }
                });
    }

    @Override
    public void addPostCollapseAction(Runnable action) {
        mPostCollapseRunnables.add(action);
    }

    @Override
    public void runPostCollapseRunnables() {
        ArrayList<Runnable> clonedList = new ArrayList<>(mPostCollapseRunnables);
        mPostCollapseRunnables.clear();
        int size = clonedList.size();
        for (int i = 0; i < size; i++) {
            clonedList.get(i).run();
        }
        mStatusBarKeyguardViewManager.readyForKeyguardDone();
    }

    @Override
    public boolean collapsePanel() {
        if (!getNotificationPanelViewController().isFullyCollapsed()) {
            // close the shade if it was open
            animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                    true /* force */, true /* delayed */);
            getCentralSurfaces().visibilityChanged(false);

            return true;
        } else {
            return false;
        }
    }

    @Override
    public void collapsePanel(boolean animate) {
        if (animate) {
            boolean willCollapse = collapsePanel();
            if (!willCollapse) {
                runPostCollapseRunnables();
            }
        } else if (!getPresenter().isPresenterFullyCollapsed()) {
            getCentralSurfaces().instantCollapseNotificationPanel();
            getCentralSurfaces().visibilityChanged(false);
        } else {
            runPostCollapseRunnables();
        }
    }

    private CentralSurfaces getCentralSurfaces() {
        return mCentralSurfacesOptionalLazy.get().get();
    }

    private NotificationPresenter getPresenter() {
        return getCentralSurfaces().getPresenter();
    }

    protected NotificationShadeWindowView getNotificationShadeWindowView() {
        return getCentralSurfaces().getNotificationShadeWindowView();
    }

    private NotificationPanelViewController getNotificationPanelViewController() {
        return getCentralSurfaces().getPanelController();
    }
}
