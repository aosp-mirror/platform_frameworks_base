/*
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.view;

import com.android.internal.app.IAssistScreenshotReceiver;
import com.android.internal.os.IResultReceiver;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.policy.IShortcutService;

import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.view.IApplicationToken;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IDockedStackListener;
import android.view.IOnKeyguardExitResult;
import android.view.IRotationWatcher;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.KeyEvent;
import android.view.InputEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.IInputFilter;
import android.view.AppTransitionAnimationSpec;
import android.view.WindowContentFrameStats;

/**
 * System private interface to the window manager.
 *
 * {@hide}
 */
interface IWindowManager
{
    /**
     * ===== NOTICE =====
     * The first three methods must remain the first three methods. Scripts
     * and tools rely on their transaction number to work properly.
     */
    // This is used for debugging
    boolean startViewServer(int port);   // Transaction #1
    boolean stopViewServer();            // Transaction #2
    boolean isViewServerRunning();       // Transaction #3

    IWindowSession openSession(in IWindowSessionCallback callback, in IInputMethodClient client,
            in IInputContext inputContext);
    boolean inputMethodClientHasFocus(IInputMethodClient client);

    void getInitialDisplaySize(int displayId, out Point size);
    void getBaseDisplaySize(int displayId, out Point size);
    void setForcedDisplaySize(int displayId, int width, int height);
    void clearForcedDisplaySize(int displayId);
    int getInitialDisplayDensity(int displayId);
    int getBaseDisplayDensity(int displayId);
    void setForcedDisplayDensityForUser(int displayId, int density, int userId);
    void clearForcedDisplayDensityForUser(int displayId, int userId);
    void setForcedDisplayScalingMode(int displayId, int mode); // 0 = auto, 1 = disable

    void setOverscan(int displayId, int left, int top, int right, int bottom);

    // These can only be called when holding the MANAGE_APP_TOKENS permission.
    void pauseKeyDispatching(IBinder token);
    void resumeKeyDispatching(IBinder token);
    void setEventDispatching(boolean enabled);
    void addWindowToken(IBinder token, int type);
    void removeWindowToken(IBinder token);
    /**
     * Adds an application token to the specified task Id.
     * @param addPos The position to add the token to in the task.
     * @param token The token to add.
     * @param taskId The Id of the task we are adding the token to.
     * @param stackId Stack Id to create a new Task with the input task Id on
     *                if the task doesn't exist yet.
     * @param requestedOrientation Orientation to use.
     * @param fullscreen True if the application token is fullscreen.
     * @param showWhenLocked True if the application token should be shown when locked.
     * @param userId Id of user to associate the token with.
     * @param configChanges Input configuration changes.
     * @param voiceInteraction True if the token is in voice interaction mode.
     * @param launchTaskBehind True if the token is been launched from behind.
     * @param taskBounds Bounds to use when creating a new Task with the input task Id if
     *                   the task doesn't exist yet.
     * @param configuration Configuration that is being used with this task.
     * @param taskResizeMode The resize mode of the task.
     * @param alwaysFocusable True if the app windows are always focusable regardless of the stack
     *                        they are in.
     * @param homeTask True if this is the task.
     * @param targetSdkVersion The application's target SDK version
     */
    void addAppToken(int addPos, IApplicationToken token, int taskId, int stackId,
            int requestedOrientation, boolean fullscreen, boolean showWhenLocked, int userId,
            int configChanges, boolean voiceInteraction, boolean launchTaskBehind,
            in Rect taskBounds, in Configuration configuration, int taskResizeMode,
            boolean alwaysFocusable, boolean homeTask, int targetSdkVersion, int rotationAnimationHint);
    /**
     *
     * @param token The token we are adding to the input task Id.
     * @param taskId The Id of the task we are adding the token to.
     * @param stackId Stack Id to create a new Task with the input task Id on
     *                if the task doesn't exist yet.
     * @param taskBounds Bounds to use when creating a new Task with the input task Id if
     *                   the task doesn't exist yet.
     * @param config Configuration that is being used with this task.
     * @param taskResizeMode The resize mode of the task.
     * @param homeTask True if this is the task.
     */
    void setAppTask(IBinder token, int taskId, int stackId, in Rect taskBounds,
            in Configuration config, int taskResizeMode, boolean homeTask);
    void setAppOrientation(IApplicationToken token, int requestedOrientation);
    int getAppOrientation(IApplicationToken token);
    void setFocusedApp(IBinder token, boolean moveFocusNow);
    void prepareAppTransition(int transit, boolean alwaysKeepCurrent);
    int getPendingAppTransition();
    void overridePendingAppTransition(String packageName, int enterAnim, int exitAnim,
            IRemoteCallback startedCallback);
    void overridePendingAppTransitionScaleUp(int startX, int startY, int startWidth,
            int startHeight);
    void overridePendingAppTransitionClipReveal(int startX, int startY,
            int startWidth, int startHeight);
    void overridePendingAppTransitionThumb(in Bitmap srcThumb, int startX, int startY,
            IRemoteCallback startedCallback, boolean scaleUp);
    void overridePendingAppTransitionAspectScaledThumb(in Bitmap srcThumb, int startX,
            int startY, int targetWidth, int targetHeight, IRemoteCallback startedCallback,
            boolean scaleUp);
    /**
     * Overrides animation for app transition that exits from an application to a multi-window
     * environment and allows specifying transition animation parameters for each window.
     *
     * @param specs Array of transition animation descriptions for entering windows.
     *
     * @hide
     */
    void overridePendingAppTransitionMultiThumb(in AppTransitionAnimationSpec[] specs,
            IRemoteCallback startedCallback, IRemoteCallback finishedCallback, boolean scaleUp);
    void overridePendingAppTransitionInPlace(String packageName, int anim);

    /**
     * Like overridePendingAppTransitionMultiThumb, but uses a future to supply the specs. This is
     * used for recents, where generating the thumbnails of the specs takes a non-trivial amount of
     * time, so we want to move that off the critical path for starting the new activity.
     */
    void overridePendingAppTransitionMultiThumbFuture(
            IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback startedCallback,
            boolean scaleUp);
    void executeAppTransition();

    /**
     * Called to set the starting window for the input token and returns true if the starting
     * window was set for the token.
     */
    boolean setAppStartingWindow(IBinder token, String pkg, int theme,
            in CompatibilityInfo compatInfo, CharSequence nonLocalizedLabel, int labelRes,
            int icon, int logo, int windowFlags, IBinder transferFrom, boolean createIfNeeded);
    void setAppVisibility(IBinder token, boolean visible);
    void notifyAppResumed(IBinder token, boolean wasStopped, boolean allowSavedSurface);
    void notifyAppStopped(IBinder token);
    void startAppFreezingScreen(IBinder token, int configChanges);
    void stopAppFreezingScreen(IBinder token, boolean force);
    void removeAppToken(IBinder token);

    /** Used by system ui to report that recents has shown itself. */
    void endProlongedAnimations();

    // Re-evaluate the current orientation from the caller's state.
    // If there is a change, the new Configuration is returned and the
    // caller must call setNewConfiguration() sometime later.
    Configuration updateOrientationFromAppTokens(in Configuration currentConfig,
            IBinder freezeThisOneIfNeeded);
    // Notify window manager of the new configuration. Returns an array of stack ids that's
    // affected by the update, ActivityManager should resize these stacks.
    int[] setNewConfiguration(in Configuration config);

    // Retrieves the new bounds after the configuration update evaluated by window manager.
    Rect getBoundsForNewConfiguration(int stackId);

    void startFreezingScreen(int exitAnim, int enterAnim);
    void stopFreezingScreen();

    // these require DISABLE_KEYGUARD permission
    void disableKeyguard(IBinder token, String tag);
    void reenableKeyguard(IBinder token);
    void exitKeyguardSecurely(IOnKeyguardExitResult callback);
    boolean isKeyguardLocked();
    boolean isKeyguardSecure();
    boolean inKeyguardRestrictedInputMode();
    void dismissKeyguard();
    void keyguardGoingAway(int flags);

    void closeSystemDialogs(String reason);

    // These can only be called with the SET_ANIMATON_SCALE permission.
    float getAnimationScale(int which);
    float[] getAnimationScales();
    void setAnimationScale(int which, float scale);
    void setAnimationScales(in float[] scales);

    float getCurrentAnimatorScale();

    // For testing
    void setInTouchMode(boolean showFocus);

    // For StrictMode flashing a red border on violations from the UI
    // thread.  The uid/pid is implicit from the Binder call, and the Window
    // Manager uses that to determine whether or not the red border should
    // actually be shown.  (it will be ignored that pid doesn't have windows
    // on screen)
    void showStrictModeViolation(boolean on);

    // Proxy to set the system property for whether the flashing
    // should be enabled.  The 'enabled' value is null or blank for
    // the system default (differs per build variant) or any valid
    // boolean string as parsed by SystemProperties.getBoolean().
    void setStrictModeVisualIndicatorPreference(String enabled);

    /**
     * Set whether screen capture is disabled for all windows of a specific user
     */
    void setScreenCaptureDisabled(int userId, boolean disabled);

    /**
     * Cancels the window transitions for the given task.
     */
    void cancelTaskWindowTransition(int taskId);

    /**
     * Cancels the thumbnail transitions for the given task.
     */
    void cancelTaskThumbnailTransition(int taskId);

    // These can only be called with the SET_ORIENTATION permission.
    /**
     * Update the current screen rotation based on the current state of
     * the world.
     * @param alwaysSendConfiguration Flag to force a new configuration to
     * be evaluated.  This can be used when there are other parameters in
     * configuration that are changing.
     * @param forceRelayout If true, the window manager will always do a relayout
     * of its windows even if the rotation hasn't changed.
     */
    void updateRotation(boolean alwaysSendConfiguration, boolean forceRelayout);

    /**
     * Retrieve the current screen orientation, constants as per
     * {@link android.view.Surface}.
     */
    int getRotation();

    /**
     * Watch the rotation of the screen.  Returns the current rotation,
     * calls back when it changes.
     */
    int watchRotation(IRotationWatcher watcher);

    /**
     * Remove a rotation watcher set using watchRotation.
     * @hide
     */
    void removeRotationWatcher(IRotationWatcher watcher);

    /**
     * Determine the preferred edge of the screen to pin the compact options menu against.
     * @return a Gravity value for the options menu panel
     * @hide
     */
    int getPreferredOptionsPanelGravity();

    /**
     * Lock the device orientation to the specified rotation, or to the
     * current rotation if -1.  Sensor input will be ignored until
     * thawRotation() is called.
     * @hide
     */
    void freezeRotation(int rotation);

    /**
     * Release the orientation lock imposed by freezeRotation().
     * @hide
     */
    void thawRotation();

    /**
     * Gets whether the rotation is frozen.
     *
     * @return Whether the rotation is frozen.
     */
    boolean isRotationFrozen();

    /**
     * Screenshot the current wallpaper layer, including the whole screen.
     */
    Bitmap screenshotWallpaper();

    /**
     * Used only for assist -- request a screenshot of the current application.
     */
    boolean requestAssistScreenshot(IAssistScreenshotReceiver receiver);

    /**
     * Create a screenshot of the applications currently displayed.
     *
     * @param frameScale the scale to apply to the frame, only used when width = -1 and
     *                   height = -1
     */
    Bitmap screenshotApplications(IBinder appToken, int displayId, int maxWidth, int maxHeight,
            float frameScale);

    /**
     * Called by the status bar to notify Views of changes to System UI visiblity.
     */
    oneway void statusBarVisibilityChanged(int visibility);

    /**
     * Called by System UI to notify of changes to the visibility of Recents.
     */
    oneway void setRecentsVisibility(boolean visible);

    /**
     * Called by System UI to notify of changes to the visibility of PIP.
     */
    oneway void setTvPipVisibility(boolean visible);

    /**
     * Device requires a software navigation bar.
     */
    boolean needsNavigationBar();

    /**
     * Device has a software navigation bar (separate from the status bar).
     */
    boolean hasNavigationBar();

    /**
     * Lock the device immediately with the specified options (can be null).
     */
    void lockNow(in Bundle options);

    /**
     * Device is in safe mode.
     */
    boolean isSafeModeEnabled();

    /**
     * Enables the screen if all conditions are met.
     */
    void enableScreenIfNeeded();

    /**
     * Clears the frame statistics for a given window.
     *
     * @param token The window token.
     * @return Whether the frame statistics were cleared.
     */
    boolean clearWindowContentFrameStats(IBinder token);

    /**
     * Gets the content frame statistics for a given window.
     *
     * @param token The window token.
     * @return The frame statistics or null if the window does not exist.
     */
    WindowContentFrameStats getWindowContentFrameStats(IBinder token);

    /**
     * @return the dock side the current docked stack is at; must be one of the
     *         WindowManagerGlobal.DOCKED_* values
     */
    int getDockedStackSide();

    /**
     * Sets whether we are currently in a drag resize operation where we are changing the docked
     * stack size.
     */
    void setDockedStackResizing(boolean resizing);

    /**
     * Sets the region the user can touch the divider. This region will be excluded from the region
     * which is used to cause a focus switch when dispatching touch.
     */
    void setDockedStackDividerTouchRegion(in Rect touchableRegion);

    /**
     * Registers a listener that will be called when the dock divider changes its visibility or when
     * the docked stack gets added/removed.
     */
    void registerDockedStackListener(IDockedStackListener listener);

    /**
     * Updates the dim layer used while resizing.
     *
     * @param visible Whether the dim layer should be visible.
     * @param targetStackId The id of the task stack the dim layer should be placed on.
     * @param alpha The translucency of the dim layer, between 0 and 1.
     */
    void setResizeDimLayer(boolean visible, int targetStackId, float alpha);

    /**
     * Requests Keyboard Shortcuts from the displayed window.
     *
     * @param receiver The receiver to deliver the results to.
     */
    void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId);

    /**
     * Retrieves the current stable insets from the primary display.
     */
    void getStableInsets(out Rect outInsets);

    /**
     * Register shortcut key. Shortcut code is packed as:
     * (MetaState << Integer.SIZE) | KeyCode
     * @hide
     */
    void registerShortcutKey(in long shortcutCode, IShortcutService keySubscriber);

    /**
     * Create the input consumer for wallpaper events.
     */
    void createWallpaperInputConsumer(out InputChannel inputChannel);

    /**
     * Remove the input consumer for wallpaper events.
     */
    void removeWallpaperInputConsumer();
}
