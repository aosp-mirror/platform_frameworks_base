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

import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IShortcutService;

import android.app.IAssistDataReceiver;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.view.IApplicationToken;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.IDockedStackListener;
import android.view.IDisplayFoldListener;
import android.view.IOnKeyguardExitResult;
import android.view.IPinnedStackListener;
import android.view.RemoteAnimationAdapter;
import android.view.IRotationWatcher;
import android.view.ISystemGestureExclusionListener;
import android.view.IWallpaperVisibilityListener;
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
import android.view.WindowManager;
import android.view.SurfaceControl;

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

    IWindowSession openSession(in IWindowSessionCallback callback);

    @UnsupportedAppUsage
    void getInitialDisplaySize(int displayId, out Point size);
    @UnsupportedAppUsage
    void getBaseDisplaySize(int displayId, out Point size);
    void setForcedDisplaySize(int displayId, int width, int height);
    void clearForcedDisplaySize(int displayId);
    @UnsupportedAppUsage
    int getInitialDisplayDensity(int displayId);
    int getBaseDisplayDensity(int displayId);
    void setForcedDisplayDensityForUser(int displayId, int density, int userId);
    void clearForcedDisplayDensityForUser(int displayId, int userId);
    void setForcedDisplayScalingMode(int displayId, int mode); // 0 = auto, 1 = disable

    void setOverscan(int displayId, int left, int top, int right, int bottom);

    // These can only be called when holding the MANAGE_APP_TOKENS permission.
    void setEventDispatching(boolean enabled);
    void addWindowToken(IBinder token, int type, int displayId);
    void removeWindowToken(IBinder token, int displayId);
    void prepareAppTransition(int transit, boolean alwaysKeepCurrent);

    /**
     * Like overridePendingAppTransitionMultiThumb, but uses a future to supply the specs. This is
     * used for recents, where generating the thumbnails of the specs takes a non-trivial amount of
     * time, so we want to move that off the critical path for starting the new activity.
     */
    @UnsupportedAppUsage
    void overridePendingAppTransitionMultiThumbFuture(
            IAppTransitionAnimationSpecsFuture specsFuture, IRemoteCallback startedCallback,
            boolean scaleUp, int displayId);
    @UnsupportedAppUsage
    void overridePendingAppTransitionRemote(in RemoteAnimationAdapter remoteAnimationAdapter,
            int displayId);
    @UnsupportedAppUsage
    void executeAppTransition();

    /**
      * Used by system ui to report that recents has shown itself.
      * @deprecated to be removed once prebuilts are updated
      */
    @UnsupportedAppUsage
    void endProlongedAnimations();

    void startFreezingScreen(int exitAnim, int enterAnim);
    void stopFreezingScreen();

    // these require DISABLE_KEYGUARD permission
    /** @deprecated use Activity.setShowWhenLocked instead. */
    void disableKeyguard(IBinder token, String tag, int userId);
    /** @deprecated use Activity.setShowWhenLocked instead. */
    void reenableKeyguard(IBinder token, int userId);
    void exitKeyguardSecurely(IOnKeyguardExitResult callback);
    @UnsupportedAppUsage
    boolean isKeyguardLocked();
    @UnsupportedAppUsage
    boolean isKeyguardSecure(int userId);
    void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message);

    // Requires INTERACT_ACROSS_USERS_FULL permission
    void setSwitchingUser(boolean switching);

    void closeSystemDialogs(String reason);

    // These can only be called with the SET_ANIMATON_SCALE permission.
    @UnsupportedAppUsage
    float getAnimationScale(int which);
    @UnsupportedAppUsage
    float[] getAnimationScales();
    @UnsupportedAppUsage
    void setAnimationScale(int which, float scale);
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    void setStrictModeVisualIndicatorPreference(String enabled);

    /**
     * Set whether screen capture is disabled for all windows of a specific user from
     * the device policy cache.
     */
    void refreshScreenCaptureDisabled(int userId);

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
     * Retrieve the current orientation of the primary screen.
     * @return Constant as per {@link android.view.Surface.Rotation}.
     *
     * @see android.view.Display#DEFAULT_DISPLAY
     */
    int getDefaultDisplayRotation();

    /**
     * Watch the rotation of the specified screen.  Returns the current rotation,
     * calls back when it changes.
     */
    int watchRotation(IRotationWatcher watcher, int displayId);

    /**
     * Remove a rotation watcher set using watchRotation.
     * @hide
     */
    @UnsupportedAppUsage
    void removeRotationWatcher(IRotationWatcher watcher);

    /**
     * Determine the preferred edge of the screen to pin the compact options menu against.
     *
     * @param displayId Id of the display where the menu window currently resides.
     * @return a Gravity value for the options menu panel.
     * @hide
     */
    int getPreferredOptionsPanelGravity(int displayId);

    /**
     * Equivalent to calling {@link #freezeDisplayRotation(int, int)} with {@link
     * android.view.Display#DEFAULT_DISPLAY} and given rotation.
     */
    @UnsupportedAppUsage
    void freezeRotation(int rotation);

    /**
     * Equivalent to calling {@link #thawDisplayRotation(int)} with {@link
     * android.view.Display#DEFAULT_DISPLAY}.
     */
    @UnsupportedAppUsage
    void thawRotation();

    /**
     * Equivelant to call {@link #isDisplayRotationFrozen(int)} with {@link
     * android.view.Display#DEFAULT_DISPLAY}.
     */
    boolean isRotationFrozen();

    /**
     * Lock the display orientation to the specified rotation, or to the current
     * rotation if -1. Sensor input will be ignored until thawRotation() is called.
     *
     * @param displayId the ID of display which rotation should be frozen.
     * @param rotation one of {@link android.view.Surface#ROTATION_0},
     *        {@link android.view.Surface#ROTATION_90}, {@link android.view.Surface#ROTATION_180},
     *        {@link android.view.Surface#ROTATION_270} or -1 to freeze it to current rotation.
     * @hide
     */
    void freezeDisplayRotation(int displayId, int rotation);

    /**
     * Release the orientation lock imposed by freezeRotation() on the display.
     *
     * @param displayId the ID of display which rotation should be thawed.
     * @hide
     */
    void thawDisplayRotation(int displayId);

    /**
     * Gets whether the rotation is frozen on the display.
     *
     * @param displayId the ID of display which frozen is needed.
     * @return Whether the rotation is frozen.
     */
    boolean isDisplayRotationFrozen(int displayId);

    /**
     * Screenshot the current wallpaper layer, including the whole screen.
     */
    Bitmap screenshotWallpaper();

    /**
     * Registers a wallpaper visibility listener.
     * @return Current visibility.
     */
    boolean registerWallpaperVisibilityListener(IWallpaperVisibilityListener listener,
        int displayId);

    /**
     * Remove a visibility watcher that was added using registerWallpaperVisibilityListener.
     */
    void unregisterWallpaperVisibilityListener(IWallpaperVisibilityListener listener,
        int displayId);

    /**
     * Registers a system gesture exclusion listener for a given display.
     */
    void registerSystemGestureExclusionListener(ISystemGestureExclusionListener listener,
        int displayId);

    /**
     * Unregisters a system gesture exclusion listener for a given display.
     */
    void unregisterSystemGestureExclusionListener(ISystemGestureExclusionListener listener,
        int displayId);

    /**
     * Used only for assist -- request a screenshot of the current application.
     */
    boolean requestAssistScreenshot(IAssistDataReceiver receiver);

    /**
     * Called by the status bar to notify Views of changes to System UI visiblity.
     */
    oneway void statusBarVisibilityChanged(int displayId, int visibility);

    /**
    * When set to {@code true} the system bars will always be shown. This is true even if an app
    * requests to be fullscreen by setting the system ui visibility flags. The
    * functionality was added for the automotive case as a way to guarantee required content stays
    * on screen at all times.
    *
    * @hide
    */
    oneway void setForceShowSystemBars(boolean show);

    /**
     * Called by System UI to notify of changes to the visibility of Recents.
     */
    oneway void setRecentsVisibility(boolean visible);

    /**
     * Called by System UI to notify of changes to the visibility of PIP.
     */
    oneway void setPipVisibility(boolean visible);

    /**
     * Called by System UI to notify of changes to the visibility and height of the shelf.
     */
    @UnsupportedAppUsage
    void setShelfHeight(boolean visible, int shelfHeight);

    /**
     * Called by System UI to enable or disable haptic feedback on the navigation bar buttons.
     */
    @UnsupportedAppUsage
    void setNavBarVirtualKeyHapticFeedbackEnabled(boolean enabled);

    /**
     * Device has a software navigation bar (separate from the status bar) on specific display.
     *
     * @param displayId the id of display to check if there is a software navigation bar.
     */
    @UnsupportedAppUsage
    boolean hasNavigationBar(int displayId);

    /**
     * Get the position of the nav bar
     */
    int getNavBarPosition(int displayId);

    /**
     * Lock the device immediately with the specified options (can be null).
     */
    @UnsupportedAppUsage
    void lockNow(in Bundle options);

    /**
     * Device is in safe mode.
     */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    int getDockedStackSide();

    /**
     * Sets the region the user can touch the divider. This region will be excluded from the region
     * which is used to cause a focus switch when dispatching touch.
     */
    void setDockedStackDividerTouchRegion(in Rect touchableRegion);

    /**
     * Registers a listener that will be called when the dock divider changes its visibility or when
     * the docked stack gets added/removed.
     */
    @UnsupportedAppUsage
    void registerDockedStackListener(IDockedStackListener listener);

    /**
     * Registers a listener that will be called when the pinned stack state changes.
     */
    void registerPinnedStackListener(int displayId, IPinnedStackListener listener);

    /**
     * Updates the dim layer used while resizing.
     *
     * @param visible Whether the dim layer should be visible.
     * @param targetWindowingMode The windowing mode of the stack the dim layer should be placed on.
     * @param alpha The translucency of the dim layer, between 0 and 1.
     */
    void setResizeDimLayer(boolean visible, int targetWindowingMode, float alpha);

    /**
     * Requests Keyboard Shortcuts from the displayed window.
     *
     * @param receiver The receiver to deliver the results to.
     */
    void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId);

    /**
     * Retrieves the current stable insets from the primary display.
     */
    @UnsupportedAppUsage
    void getStableInsets(int displayId, out Rect outInsets);

    /**
     * Set the forwarded insets on the display.
     * <p>
     * This is only used in case a virtual display is displayed on another display that has insets,
     * and the bounds of the virtual display is overlapping with the insets from the host display.
     * In that case, the contents on the virtual display won't be placed over the forwarded insets.
     * Only the owner of the display is permitted to set the forwarded insets on it.
     */
    void setForwardedInsets(int displayId, in Insets insets);

    /**
     * Register shortcut key. Shortcut code is packed as:
     * (MetaState << Integer.SIZE) | KeyCode
     * @hide
     */
    void registerShortcutKey(in long shortcutCode, IShortcutService keySubscriber);

    /**
     * Create an input consumer by name and display id.
     */
    @UnsupportedAppUsage
    void createInputConsumer(IBinder token, String name, int displayId,
        out InputChannel inputChannel);

    /**
     * Destroy an input consumer by name and display id.
     * This method will also dispose the input channels associated with that InputConsumer.
     */
    @UnsupportedAppUsage
    boolean destroyInputConsumer(String name, int displayId);

    /**
     * Return the touch region for the current IME window, or an empty region if there is none.
     */
    Region getCurrentImeTouchRegion();

    /**
     * Registers an IDisplayFoldListener.
     */
    void registerDisplayFoldListener(IDisplayFoldListener listener);

    /**
     * Unregisters an IDisplayFoldListener.
     */
    void unregisterDisplayFoldListener(IDisplayFoldListener listener);

    /**
     * Starts a window trace.
     */
    void startWindowTrace();

    /**
     * Stops a window trace.
     */
    void stopWindowTrace();

    /**
     * Returns true if window trace is enabled.
     */
    boolean isWindowTraceEnabled();

    /**
     * Requests that the WindowManager sends
     * WindowManagerPolicyConstants#ACTION_USER_ACTIVITY_NOTIFICATION on the next user activity.
     */
    void requestUserActivityNotification();

    /**
     * Notify WindowManager that it should not override the info in DisplayManager for the specified
     * display. This can disable letter- or pillar-boxing applied in DisplayManager when the metrics
     * of the logical display reported from WindowManager do not correspond to the metrics of the
     * physical display it is based on.
     *
     * @param displayId The id of the display.
     */
    void dontOverrideDisplayInfo(int displayId);

    /**
     * Gets the windowing mode of the display.
     *
     * @param displayId The id of the display.
     * @return {@link WindowConfiguration.WindowingMode}
     */
    int getWindowingMode(int displayId);

    /**
     * Sets the windowing mode of the display.
     *
     * @param displayId The id of the display.
     * @param mode {@link WindowConfiguration.WindowingMode}
     */
    void setWindowingMode(int displayId, int mode);

    /**
     * Gets current remove content mode of the display.
     * <p>
     * What actions should be performed with the display's content when it is removed. Default
     * behavior for public displays in this case is to move all activities to the primary display
     * and make it focused. For private display is to destroy all activities.
     * </p>
     *
     * @param displayId The id of the display.
     * @return The remove content mode of the display.
     * @see WindowManager#REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY
     * @see WindowManager#REMOVE_CONTENT_MODE_DESTROY
     */
    int getRemoveContentMode(int displayId);

    /**
     * Sets the remove content mode of the display.
     * <p>
     * This mode indicates what actions should be performed with the display's content when it is
     * removed.
     * </p>
     *
     * @param displayId The id of the display.
     * @param mode Remove content mode.
     * @see WindowManager#REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY
     * @see WindowManager#REMOVE_CONTENT_MODE_DESTROY
     */
    void setRemoveContentMode(int displayId, int mode);

    /**
     * Indicates that the display should show its content when non-secure keyguard is shown.
     * <p>
     * This flag identifies secondary displays that will continue showing content if keyguard can be
     * dismissed without entering credentials.
     * </p><p>
     * An example of usage is a virtual display which content is displayed on external hardware
     * display that is not visible to the system directly.
     * </p>
     *
     * @param displayId The id of the display.
     * @return {@code true} if the display should show its content when non-secure keyguard is
     *         shown.
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     */
    boolean shouldShowWithInsecureKeyguard(int displayId);

    /**
     * Sets that the display should show its content when non-secure keyguard is shown.
     *
     * @param displayId The id of the display.
     * @param shouldShow Indicates that the display should show its content when non-secure keyguard
     *                  is shown.
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     */
    void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow);

    /**
     * Indicates the display should show system decors.
     * <p>
     * System decors include status bar, navigation bar, launcher.
     * </p>
     *
     * @param displayId The id of the display.
     * @return {@code true} if the display should show system decors.
     */
    boolean shouldShowSystemDecors(int displayId);

    /**
     * Sets that the display should show system decors.
     * <p>
     * System decors include status bar, navigation bar, launcher.
     * </p>
     *
     * @param displayId The id of the display.
     * @param shouldShow Indicates that the display should show system decors.
     */
    void setShouldShowSystemDecors(int displayId, boolean shouldShow);

    /**
     * Indicates that the display should show IME.
     *
     * @param displayId The id of the display.
     * @return {@code true} if the display should show IME.
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     */
    boolean shouldShowIme(int displayId);

    /**
     * Sets that the display should show IME.
     *
     * @param displayId The id of the display.
     * @param shouldShow Indicates that the display should show IME.
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     */
    void setShouldShowIme(int displayId, boolean shouldShow);

    /**
     * Waits for transactions to get applied before injecting input.
     * This includes waiting for the input windows to get sent to InputManager.
     *
     * This is needed for testing since the system add windows and injects input
     * quick enough that the windows don't have time to get sent to InputManager.
     */
    boolean injectInputAfterTransactionsApplied(in InputEvent ev, int mode);

    /**
     * Waits until all animations have completed and input information has been sent from
     * WindowManager to native InputManager.
     *
     * This is needed for testing since we need to ensure input information has been propagated to
     * native InputManager before proceeding with tests.
     */
    void syncInputTransactions();

    /**
     * Long screenshot
     * @hide
     */
    void takeOPScreenshot(int type);
    void stopLongshotConnection();
}
