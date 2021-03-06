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

package com.android.systemui.shared.recents;

import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.MotionEvent;

import com.android.systemui.shared.recents.IPinnedStackAnimationListener;
import com.android.systemui.shared.recents.ISplitScreenListener;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.system.RemoteTransitionCompat;

/**
 * Temporary callbacks into SystemUI.
 * Next id = 43
 */
interface ISystemUiProxy {

    /**
     * Proxies SurfaceControl.screenshotToBuffer().
     * @Removed
     * GraphicBufferCompat screenshot(in Rect sourceCrop, int width, int height, int minLayer,
     *             int maxLayer, boolean useIdentityTransform, int rotation) = 0;
     */

    /**
     * Begins screen pinning on the provided {@param taskId}.
     */
    void startScreenPinning(int taskId) = 1;

    /**
     * Notifies SystemUI that Overview is shown.
     */
    void onOverviewShown(boolean fromHome) = 6;

    /**
     * Get the secondary split screen app's rectangle when not minimized.
     */
    Rect getNonMinimizedSplitScreenSecondaryBounds() = 7;

    /**
     * Control the {@param alpha} of the back button in the navigation bar and {@param animate} if
     * needed from current value
     * @deprecated
     */
    void setBackButtonAlpha(float alpha, boolean animate) = 8;

    /**
     * Control the {@param alpha} of the option nav bar button (back-button in 2 button mode
     * and home bar in no-button mode) and {@param animate} if needed from current value
     */
    void setNavBarButtonAlpha(float alpha, boolean animate) = 19;

    /**
     * Proxies motion events from the homescreen UI to the status bar. Only called when
     * swipe down is detected on WORKSPACE. The sender guarantees the following order of events on
     * the tracking pointer.
     *
     * Normal gesture: DOWN, MOVE/POINTER_DOWN/POINTER_UP)*, UP or CANCLE
     */
    void onStatusBarMotionEvent(in MotionEvent event) = 9;

    /**
     * Proxies the assistant gesture's progress started from navigation bar.
     */
    void onAssistantProgress(float progress) = 12;

    /**
    * Proxies the assistant gesture fling velocity (in pixels per millisecond) upon completion.
    * Velocity is 0 for drag gestures.
    */
    void onAssistantGestureCompletion(float velocity) = 18;

    /**
     * Start the assistant.
     */
    void startAssistant(in Bundle bundle) = 13;

    /**
     * Creates a new gesture monitor
     */
    Bundle monitorGestureInput(String name, int displayId) = 14;

    /**
     * Notifies that the accessibility button in the system's navigation area has been clicked
     */
    void notifyAccessibilityButtonClicked(int displayId) = 15;

    /**
     * Notifies that the accessibility button in the system's navigation area has been long clicked
     */
    void notifyAccessibilityButtonLongClicked() = 16;

    /**
     * Ends the system screen pinning.
     */
    void stopScreenPinning() = 17;

    /**
     * Sets the shelf height and visibility.
     */
    void setShelfHeight(boolean visible, int shelfHeight) = 20;

    /**
     * Handle the provided image as if it was a screenshot.
     *
     * Deprecated, use handleImageBundleAsScreenshot with image bundle and UserTask
     * @deprecated
     */
    void handleImageAsScreenshot(in Bitmap screenImage, in Rect locationInScreen,
              in Insets visibleInsets, int taskId) = 21;

    /**
     * Sets the split-screen divider minimized state
     */
    void setSplitScreenMinimized(boolean minimized) = 22;

    /*
     * Notifies that the swipe-to-home (recents animation) is finished.
     */
    void notifySwipeToHomeFinished() = 23;

    /**
     * Sets listener to get pinned stack animation callbacks.
     */
    void setPinnedStackAnimationListener(IPinnedStackAnimationListener listener) = 24;

    /**
     * Notifies that quickstep will switch to a new task
     * @param rotation indicates which Surface.Rotation the gesture was started in
     */
    void onQuickSwitchToNewTask(int rotation) = 25;

    /**
     * Start the one-handed mode.
     */
    void startOneHandedMode() = 26;

    /**
     * Stop the one-handed mode.
     */
    void stopOneHandedMode() = 27;

    /**
     * Handle the provided image as if it was a screenshot.
     */
    void handleImageBundleAsScreenshot(in Bundle screenImageBundle, in Rect locationInScreen,
              in Insets visibleInsets, in Task.TaskKey task) = 28;

    /**
     * Notifies to expand notification panel.
     */
    void expandNotificationPanel() = 29;

    /**
     * Notifies that Activity is about to be swiped to home with entering PiP transition and
     * queries the destination bounds for PiP depends on Launcher's rotation and shelf height.
     *
     * @param componentName ComponentName represents the Activity
     * @param activityInfo ActivityInfo tied to the Activity
     * @param pictureInPictureParams PictureInPictureParams tied to the Activity
     * @param launcherRotation Launcher rotation to calculate the PiP destination bounds
     * @param shelfHeight Shelf height of launcher to calculate the PiP destination bounds
     * @return destination bounds the PiP window should land into
     */
    Rect startSwipePipToHome(in ComponentName componentName, in ActivityInfo activityInfo,
                in PictureInPictureParams pictureInPictureParams,
                int launcherRotation, int shelfHeight) = 30;

    /**
     * Notifies the swiping Activity to PiP onto home transition is finished
     *
     * @param componentName ComponentName represents the Activity
     * @param destinationBounds the destination bounds the PiP window lands into
     */
    void stopSwipePipToHome(in ComponentName componentName, in Rect destinationBounds) = 31;

    /**
     * Registers a RemoteTransitionCompat that will handle transitions. This parameter bundles an
     * IRemoteTransition and a filter that must pass for it.
     */
    void registerRemoteTransition(in RemoteTransitionCompat remoteTransition) = 32;

    /** Unegisters a RemoteTransitionCompat that will handle transitions. */
    void unregisterRemoteTransition(in RemoteTransitionCompat remoteTransition) = 33;

// SplitScreen APIs...copied from SplitScreen.java
    /**
     * Stage position isn't specified normally meaning to use what ever it is currently set to.
     */
    //int STAGE_POSITION_UNDEFINED = -1;
    /**
     * Specifies that a stage is positioned at the top half of the screen if
     * in portrait mode or at the left half of the screen if in landscape mode.
     */
    //int STAGE_POSITION_TOP_OR_LEFT = 0;
    /**
     * Specifies that a stage is positioned at the bottom half of the screen if
     * in portrait mode or at the right half of the screen if in landscape mode.
     */
    //int STAGE_POSITION_BOTTOM_OR_RIGHT = 1;

    /**
     * Stage type isn't specified normally meaning to use what ever the default is.
     * E.g. exit split-screen and launch the app in fullscreen.
     */
    //int STAGE_TYPE_UNDEFINED = -1;
    /**
     * The main stage type.
     * @see MainStage
     */
    //int STAGE_TYPE_MAIN = 0;
    /**
     * The side stage type.
     * @see SideStage
     */
    //int STAGE_TYPE_SIDE = 1;

    void registerSplitScreenListener(in ISplitScreenListener listener) = 34;
    void unregisterSplitScreenListener(in ISplitScreenListener listener) = 35;

    /** Hides the side-stage if it is currently visible. */
    void setSideStageVisibility(in boolean visible) = 36;
    /** Removes the split-screen stages. */
    void exitSplitScreen() = 37;
    /** @param exitSplitScreenOnHide if to exit split-screen if both stages are not visible. */
    void exitSplitScreenOnHide(boolean exitSplitScreenOnHide) = 38;
    void startTask(in int taskId, in int stage, in int position, in Bundle options) = 39;
    void startShortcut(in String packageName, in String shortcutId, in int stage, in int position,
            in Bundle options, in UserHandle user) = 40;
    void startIntent(
            in PendingIntent intent, in Intent fillInIntent, in int stage, in int position,
            in Bundle options) = 41;
    void removeFromSideStage(in int taskId) = 42;
}
