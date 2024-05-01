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
import com.android.internal.policy.IKeyguardLockedStateListener;
import com.android.internal.policy.IShortcutService;

import android.app.IApplicationThread;
import android.app.IAssistDataReceiver;
import android.content.ComponentName;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.ICrossWindowBlurEnabledListener;
import android.view.IDisplayWindowInsetsController;
import android.view.IDisplayWindowListener;
import android.view.IDisplayFoldListener;
import android.view.IDisplayChangeWindowController;
import android.view.IOnKeyguardExitResult;
import android.view.IPinnedTaskListener;
import android.view.IScrollCaptureResponseListener;
import android.view.RemoteAnimationAdapter;
import android.view.IRotationWatcher;
import android.view.ISystemGestureExclusionListener;
import android.view.IDecorViewGestureListener;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.IWindowSessionCallback;
import android.view.KeyEvent;
import android.view.InputEvent;
import android.view.InsetsState;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.IInputFilter;
import android.view.AppTransitionAnimationSpec;
import android.view.TaskTransitionSpec;
import android.view.WindowContentFrameStats;
import android.view.WindowManager;
import android.view.SurfaceControl;
import android.view.displayhash.DisplayHash;
import android.view.displayhash.VerifiedDisplayHash;
import android.window.AddToSurfaceSyncGroupResult;
import android.window.IGlobalDragListener;
import android.window.IScreenRecordingCallback;
import android.window.ISurfaceSyncGroupCompletedListener;
import android.window.ITaskFpsCallback;
import android.window.ITrustedPresentationListener;
import android.window.InputTransferToken;
import android.window.ScreenCapture;
import android.window.TrustedPresentationThresholds;
import android.window.WindowContextInfo;

/**
 * System private interface to the window manager.
 *
 * {@hide}
 */
interface IWindowManager
{
    /**
     * No overridden behavior is provided in terms of fixing rotation to user rotation. Use
     * other flags to derive the default behavior, such as {@link WindowManagerService#mIsPc}
     * and {@link WindowManagerService#mForceDesktopModeOnExternalDisplays}.
     */
    const int FIXED_TO_USER_ROTATION_DEFAULT = 0;
    /**
     * Don't fix display rotation to {@link DisplayRotation#mUserRotation} only. Always allow
     * other factors to play a role in deciding display rotation.
     */
    const int FIXED_TO_USER_ROTATION_DISABLED = 1;
    /**
     * Only use {@link DisplayRotation#mUserRotation} as the display rotation.
     */
    const int FIXED_TO_USER_ROTATION_ENABLED = 2;
    /**
     * If auto-rotation is not supported, {@link DisplayRotation#mUserRotation} will be used.
     * Otherwise the behavior is same as {link #FIXED_TO_USER_ROTATION_DISABLED}.
     */
    const int FIXED_TO_USER_ROTATION_IF_NO_AUTO_ROTATION = 3;

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
    @EnforcePermission("WRITE_SECURE_SETTINGS")
    void setForcedDisplaySize(int displayId, int width, int height);
    @EnforcePermission("WRITE_SECURE_SETTINGS")
    void clearForcedDisplaySize(int displayId);
    @UnsupportedAppUsage
    int getInitialDisplayDensity(int displayId);
    int getBaseDisplayDensity(int displayId);
    int getDisplayIdByUniqueId(String uniqueId);
    @EnforcePermission("WRITE_SECURE_SETTINGS")
    void setForcedDisplayDensityForUser(int displayId, int density, int userId);
    @EnforcePermission("WRITE_SECURE_SETTINGS")
    void clearForcedDisplayDensityForUser(int displayId, int userId);
    @EnforcePermission("WRITE_SECURE_SETTINGS")
    void setForcedDisplayScalingMode(int displayId, int mode); // 0 = auto, 1 = disable

    // These can only be called when holding the MANAGE_APP_TOKENS permission.
    void setEventDispatching(boolean enabled);

    /** Returns {@code true} if this binder is a registered window token. */
    boolean isWindowToken(in IBinder binder);
    /**
     * Adds window token for a given type.
     *
     * @param token Token to be registered.
     * @param type Window type to be used with this token.
     * @param displayId The ID of the display where this token should be added.
     * @param options A bundle used to pass window-related options.
     */
    void addWindowToken(IBinder token, int type, int displayId, in Bundle options);
    /**
     * Remove window token on a specific display.
     *
     * @param token Token to be removed
     * @displayId The ID of the display where this token should be removed.
     */
    void removeWindowToken(IBinder token, int displayId);

    /**
     * Sets a singular remote controller of display rotations. There can only be one. The
     * controller is called after the display has "frozen" for a rotation and display rotation will
     * only continue once the controller has finished calculating associated configurations.
     */
    void setDisplayChangeWindowController(IDisplayChangeWindowController controller);

    /**
     * Adds a root container that a client shell can populate with its own windows (usually via
     * WindowlessWindowManager).
     *
     * @param client an IWindow used for window-level communication (ime, finish draw, etc.).
     * @param shellRootLayer The container's layer. See WindowManager#ShellRootLayer.
     * @return a SurfaceControl to add things to.
     */
    @EnforcePermission("MANAGE_APP_TOKENS")
    SurfaceControl addShellRoot(int displayId, IWindow client, int shellRootLayer);

    /**
     * Sets the window token sent to accessibility for a particular shell root. The
     * displayId and windowType identify which shell-root to update.
     *
     * @param target The IWindow that accessibility service interfaces with.
     */
    @EnforcePermission("MANAGE_APP_TOKENS")
    void setShellRootAccessibilityWindow(int displayId, int shellRootLayer, IWindow target);

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

    /**
      * Used by system ui to report that recents has shown itself.
      * @deprecated to be removed once prebuilts are updated
      */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void endProlongedAnimations();

    void startFreezingScreen(int exitAnim, int enterAnim);
    void stopFreezingScreen();

    // these require DISABLE_KEYGUARD permission
    /** @deprecated use Activity.setShowWhenLocked instead. */
    void disableKeyguard(IBinder token, String tag, int userId);
    /** @deprecated use Activity.setShowWhenLocked instead. */
    void reenableKeyguard(IBinder token, int userId);
    @EnforcePermission("DISABLE_KEYGUARD")
    void exitKeyguardSecurely(IOnKeyguardExitResult callback);
    @UnsupportedAppUsage
    boolean isKeyguardLocked();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isKeyguardSecure(int userId);
    void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message);

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)")
    void addKeyguardLockedStateListener(in IKeyguardLockedStateListener listener);

    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)")
    void removeKeyguardLockedStateListener(in IKeyguardLockedStateListener listener);

    // Requires INTERACT_ACROSS_USERS_FULL permission
    void setSwitchingUser(boolean switching);

    void closeSystemDialogs(String reason);

    // These can only be called with the SET_ANIMATON_SCALE permission.
    @UnsupportedAppUsage
    float getAnimationScale(int which);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    float[] getAnimationScales();
    @UnsupportedAppUsage
    void setAnimationScale(int which, float scale);
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setAnimationScales(in float[] scales);

    float getCurrentAnimatorScale();

    // Request to change the touch mode on the display represented by the displayId parameter.
    //
    // If com.android.internal.R.bool.config_perDisplayFocusEnabled is false, then it will request
    // to change the touch mode on all displays (disregarding displayId parameter).
    void setInTouchMode(boolean inTouch, int displayId);

    // Request to change the touch mode on all displays (disregarding the value
    // com.android.internal.R.bool.config_perDisplayFocusEnabled).
    void setInTouchModeOnAllDisplays(boolean inTouch);

    // Returns the touch mode state for the display represented by the displayId parameter.
    boolean isInTouchMode(int displayId);

    // For StrictMode flashing a red border on violations from the UI
    // thread.  The uid/pid is implicit from the Binder call, and the Window
    // Manager uses that to determine whether or not the red border should
    // actually be shown.  (it will be ignored that pid doesn't have windows
    // on screen)
    @UnsupportedAppUsage(maxTargetSdk = 28)
    void showStrictModeViolation(boolean on);

    // Proxy to set the system property for whether the flashing
    // should be enabled.  The 'enabled' value is null or blank for
    // the system default (differs per build variant) or any valid
    // boolean string as parsed by SystemProperties.getBoolean().
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void setStrictModeVisualIndicatorPreference(String enabled);

    /**
     * Set whether screen capture is disabled for all windows of a specific user from
     * the device policy cache.
     */
    void refreshScreenCaptureDisabled();

    /**
     * Retrieve the current orientation of the primary screen.
     * @return Constant as per {@link android.view.Surface.Rotation}.
     *
     * @see android.view.Display#DEFAULT_DISPLAY
     */
    int getDefaultDisplayRotation();

    /**
     * Retrieve the display user rotation.
     * @param displayId Id of the display
     * @return Rotation one of {@link android.view.Surface#ROTATION_0},
     *        {@link android.view.Surface#ROTATION_90}, {@link android.view.Surface#ROTATION_180},
     *        {@link android.view.Surface#ROTATION_270} or -1 if display is not found.
     */
    int getDisplayUserRotation(int displayId);

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

    /** Registers the listener to the context token and returns the current proposed rotation. */
    int registerProposedRotationListener(IBinder contextToken, IRotationWatcher listener);

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
    void freezeRotation(int rotation, String caller);

    /**
     * Equivalent to calling {@link #thawDisplayRotation(int)} with {@link
     * android.view.Display#DEFAULT_DISPLAY}.
     */
    @UnsupportedAppUsage
    void thawRotation(String caller);

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
    void freezeDisplayRotation(int displayId, int rotation, String caller);

    /**
     * Release the orientation lock imposed by freezeRotation() on the display.
     *
     * @param displayId the ID of display which rotation should be thawed.
     * @hide
     */
    void thawDisplayRotation(int displayId, String caller);

    /**
     * Gets whether the rotation is frozen on the display.
     *
     * @param displayId the ID of display which frozen is needed.
     * @return Whether the rotation is frozen.
     */
    boolean isDisplayRotationFrozen(int displayId);

   /**
    *  Sets if display rotation is fixed to user specified value for given displayId.
    */
    void setFixedToUserRotation(int displayId, int fixedToUserRotation);

   /**
    *  Sets if all requested fixed orientation should be ignored for given displayId.
    */
    void setIgnoreOrientationRequest(int displayId, boolean ignoreOrientationRequest);

    /**
     * Screenshot the current wallpaper layer, including the whole screen.
     */
    Bitmap screenshotWallpaper();

    /**
     * Mirrors the wallpaper for the given display.
     *
     * @param displayId ID of the display for the wallpaper.
     * @return A SurfaceControl for the parent of the mirrored wallpaper.
     */
    SurfaceControl mirrorWallpaperSurface(int displayId);

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
     * Called by System UI to notify Window Manager to hide transient bars.
     */
    oneway void hideTransientBars(int displayId);

    /**
     * Called by System UI to notify of changes to the visibility of Recents.
     */
    oneway void setRecentsVisibility(boolean visible);

    /**
    * Called by System UI to indicate the maximum bounds of the system Privacy Indicator, for the
    * current orientation, whether the indicator is showing or not. Should be an array of length
    * 4, with the bounds for ROTATION_0, 90, 180, and 270, in that order.
    */
     oneway void updateStaticPrivacyIndicatorBounds(int displayId, in Rect[] staticBounds);

    /**
     * Called by System UI to enable or disable haptic feedback on the navigation bar buttons.
     */
    @EnforcePermission("STATUS_BAR")
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
     * Lock the device immediately with the specified options (can be null).
     */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void lockNow(in Bundle options);

    /**
     * Device is in safe mode.
     */
    @UnsupportedAppUsage
    boolean isSafeModeEnabled();

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
     * This is a no-op.
     */
    @UnsupportedAppUsage
    int getDockedStackSide();

    /**
     * Registers a listener that will be called when the pinned task state changes.
     */
    void registerPinnedTaskListener(int displayId, IPinnedTaskListener listener);

    /**
     * Requests Keyboard Shortcuts from the displayed window.
     *
     * @param receiver The receiver to deliver the results to.
     * @param deviceId The deviceId of KeyEvent by which this request is triggered, or -1 if it's
     *                 not triggered by a KeyEvent.
     * @see #requestImeKeyboardShortcuts(IResultReceiver, int)
     */
    void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId);

    /**
     * Requests Keyboard Shortcuts from currently selected IME.
     *
     * @param receiver The receiver to deliver the results to.
     * @param deviceId The deviceId of KeyEvent by which this request is triggered, or -1 if it's
     *                 not triggered by a KeyEvent.
     * @see #requestAppKeyboardShortcuts(IResultReceiver, int)
     */
    void requestImeKeyboardShortcuts(IResultReceiver receiver, int deviceId);

    /**
     * Retrieves the current stable insets from the primary display.
     */
    @UnsupportedAppUsage
    void getStableInsets(int displayId, out Rect outInsets);

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
     * Destroy an input consumer by token and display id.
     * This method will also dispose the input channels associated with that InputConsumer.
     */
    @UnsupportedAppUsage
    boolean destroyInputConsumer(IBinder token, int displayId);

    /**
     * Return the touch region for the current IME window, or an empty region if there is none.
     */
    @EnforcePermission("RESTRICTED_VR_ACCESS")
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
     * Registers an IDisplayContainerListener, and returns the set of existing display ids. The
     * listener's onDisplayAdded() will not be called for the displays returned.
     */
    int[] registerDisplayWindowListener(IDisplayWindowListener listener);

    /**
     * Unregisters an IDisplayContainerListener.
     */
    void unregisterDisplayWindowListener(IDisplayWindowListener listener);

    /**
     * Starts a window trace.
     */
    void startWindowTrace();

    /**
     * Stops a window trace.
     */
    void stopWindowTrace();

    /**
    * If window tracing is active, saves the window trace to file, otherwise does nothing
    */
    void saveWindowTraceToFile();

    /**
     * Returns true if window trace is enabled.
     */
    boolean isWindowTraceEnabled();

    /**
     * Starts a transition trace.
     */
    void startTransitionTrace();

    /**
     * Stops a transition trace.
     */
    void stopTransitionTrace();

    /**
     * Returns true if transition trace is enabled.
     */
    boolean isTransitionTraceEnabled();

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
     * Indicates the policy for how the display should show IME.
     *
     * @param displayId The id of the display.
     * @return The policy for how the display should show IME.
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     */
    int getDisplayImePolicy(int displayId);

    /**
     * Sets the policy for how the display should show IME.
     *
     * @param displayId The id of the display.
     * @param imePolicy Indicates the policy for how the display should show IME.
     * @see KeyguardManager#isDeviceSecure()
     * @see KeyguardManager#isDeviceLocked()
     */
    void setDisplayImePolicy(int displayId, int imePolicy);

    /**
     * Waits until input information has been sent from WindowManager to native InputManager,
     * optionally waiting for animations to complete.
     *
     * This is needed for testing since we need to ensure input information has been propagated to
     * native InputManager before proceeding with tests.
     */
    void syncInputTransactions(boolean waitForAnimations);

    /**
     * Returns whether SurfaceFlinger layer tracing is enabled.
     */
    boolean isLayerTracing();

    /**
     * Enables/disables SurfaceFlinger layer tracing.
     */
    void setLayerTracing(boolean enabled);

    /**
     * Mirrors a specified display. The root of the mirrored hierarchy will be stored in
     * outSurfaceControl.
     * Requires the ACCESS_SURFACE_FLINGER permission.
     *
     * @param displayId The id of the display to mirror
     * @param outSurfaceControl The SurfaceControl for the root of the mirrored hierarchy.
     *
     * @return true if the display was successfully mirrored.
     */
    boolean mirrorDisplay(int displayId, out SurfaceControl outSurfaceControl);

    /**
     * When in multi-window mode, the provided displayWindowInsetsController will control insets
     * animations.
     */
    @EnforcePermission("MANAGE_APP_TOKENS")
    void setDisplayWindowInsetsController(
            int displayId, in IDisplayWindowInsetsController displayWindowInsetsController);

    /**
     * Called when a remote process updates the requested visibilities of insets on a display window
     * container.
     */
    @EnforcePermission("MANAGE_APP_TOKENS")
    void updateDisplayWindowRequestedVisibleTypes(int displayId, int requestedVisibleTypes);

    /**
     * Called to get the expected window insets.
     *
     * @return {@code true} if system bars are always consumed.
     */
    boolean getWindowInsets(int displayId, in IBinder token, out InsetsState outInsetsState);

    /**
     * Returns a list of {@link android.view.DisplayInfo} for the logical display. This is not
     * guaranteed to include all possible device states. The list items are unique.
     *
     * If invoked through a package other than a launcher app, returns an empty list.
     *
     * @param displayId the id of the logical display
     */
    List<DisplayInfo> getPossibleDisplayInfo(int displayId);

    /**
     * Called to show global actions.
     */
    void showGlobalActions();

    /**
     * Sets layer tracing flags for SurfaceFlingerTrace.
     *
     * @param flags see definition in SurfaceTracing.cpp
     */
    void setLayerTracingFlags(int flags);

    /**
     * Toggle active SurfaceFlinger transaction tracing.
     */
    void setActiveTransactionTracing(boolean active);

    /**
     * Forwards a scroll capture request to the appropriate window, if available.
     *
     * @param displayId the id of the display to target
     * @param behindClient token for a window, used to filter the search to windows behind it, or
     *                     {@code null} to accept a window at any zOrder
     * @param taskId specifies the id of a task the result must belong to, or -1 to ignore task ids
     * @param listener the object to receive the response
     */
    void requestScrollCapture(int displayId, IBinder behindClient, int taskId,
            IScrollCaptureResponseListener listener);

    /**
     * Holds the WM lock for the specified amount of milliseconds.
     * Intended for use by the tests that need to imitate lock contention.
     * The token should be obtained by
     * {@link android.content.pm.PackageManager#getHoldLockToken()}.
     */
    void holdLock(in IBinder token, in int durationMs);

    /**
     * Gets an array of support hash algorithms that can be used to generate a DisplayHash. The
     * String value of one algorithm should be used when requesting to generate
     * the DisplayHash.
     *
     * @return a String array of supported hash algorithms.
     */
    String[] getSupportedDisplayHashAlgorithms();

    /**
     * Validate the DisplayHash was generated by the system. The DisplayHash passed in should be
     * the object generated when calling {@link IWindowSession#generateDisplayHash}
     *
     * @param DisplayHash The hash to verify that it was generated by the system.
     * @return a {@link VerifiedDisplayHash} if the hash was generated by the system or null
     * if the token cannot be verified.
     */
    VerifiedDisplayHash verifyDisplayHash(in DisplayHash displayHash);

    /**
     * Call to enable or disable the throttling when generating a display hash. This should only be
     * used for testing. Throttling is enabled by default.
     *
     * Must be called from a process that has {@link android.Manifest.permission#READ_FRAME_BUFFER}
     * permission.
     */
     void setDisplayHashThrottlingEnabled(boolean enable);

    /**
     * Attaches a {@link android.window.WindowContext} to the DisplayArea specified by {@code type},
     * {@code displayId} and {@code options}.
     * <p>
     * Note that this API should be invoked after calling
     * {@link android.window.WindowTokenClient#attachContext(Context)}
     * </p><p>
     * Generally, this API is used for initializing a {@link android.window.WindowContext}
     * before obtaining a valid {@link com.android.server.wm.WindowToken}. A WindowToken is usually
     * generated when calling {@link android.view.WindowManager#addView(View, LayoutParams)}, or
     * obtained from {@link android.view.WindowManager.LayoutParams#token}.
     * </p><p>
     * In some cases, the WindowToken is passed from the server side because it is managed by the
     * system server. {@link #attachWindowContextToWindowToken(IBinder, IBinder)} could be used in
     * this case to attach the WindowContext to the WindowToken.</p>
     *
     * @param appThread the process that the window context is on.
     * @param clientToken {@link android.window.WindowContext#getWindowContextToken()
     * the WindowContext's token}
     * @param type Window type of the window context
     * @param displayId The display associated with the window context
     * @param options A bundle used to pass window-related options and choose the right DisplayArea
     *
     * @return the {@link WindowContextInfo} of the DisplayArea if the WindowContext is attached to
     * the DisplayArea successfully. {@code null}, otherwise.
     */
    @nullable WindowContextInfo attachWindowContextToDisplayArea(in IApplicationThread appThread,
            IBinder clientToken, int type, int displayId, in @nullable Bundle options);

    /**
     * Attaches a {@link android.window.WindowContext} to a {@code WindowToken}.
     * <p>
     * This API is used when we hold a valid WindowToken and want to associate with the token and
     * receive its configuration updates.
     * </p><p>
     * Note that this API should be invoked after calling
     * {@link android.window.WindowTokenClient#attachContext(Context)}
     * </p>
     *
     * @param appThread the process that the window context is on.
     * @param clientToken {@link android.window.WindowContext#getWindowContextToken()
     * the WindowContext's token}
     * @param token the WindowToken to attach
     *
     * @return the {@link WindowContextInfo} of the WindowToken if the WindowContext is attached to
     * the WindowToken successfully. {@code null}, otherwise.
     * @throws IllegalArgumentException if the {@code clientToken} have not been attached to
     * the server or the WindowContext's type doesn't match WindowToken {@code token}'s type.
     *
     * @see #attachWindowContextToDisplayArea(IBinder, int, int, Bundle)
     */
    @nullable WindowContextInfo  attachWindowContextToWindowToken(in IApplicationThread appThread,
            IBinder clientToken, IBinder token);

    /**
     * Attaches a {@code clientToken} to associate with DisplayContent.
     * <p>
     * Note that this API should be invoked after calling
     * {@link android.window.WindowTokenClient#attachContext(Context)}
     * </p>
     *
     * @param appThread the process that the window context is on.
     * @param clientToken {@link android.window.WindowContext#getWindowContextToken()
     * the WindowContext's token}
     * @param displayId The display associated with the window context
     *
     * @return the {@link WindowContextInfo} of the DisplayContent if the WindowContext is attached
     * to the DisplayContent successfully. {@code null}, otherwise.
     * @throws android.view.WindowManager.InvalidDisplayException if the display ID is invalid
     */
    @nullable WindowContextInfo attachWindowContextToDisplayContent(in IApplicationThread appThread,
            IBinder clientToken, int displayId);

    /**
     * Detaches {@link android.window.WindowContext} from the window manager node it's currently
     * attached to. It is no-op if the WindowContext is not attached to a window manager node.
     *
     * @param clientToken the window context's token
     */
    void detachWindowContext(IBinder clientToken);

    /**
     * Registers a listener, which is to be called whenever cross-window blur is enabled/disabled.
     *
     * @param listener the listener to be registered
     * @return true if cross-window blur is currently enabled; false otherwise
     */
    boolean registerCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener);

    /**
     * Unregisters a listener which was registered with
     * {@link #registerCrossWindowBlurEnabledListener()}.
     *
     * @param listener the listener to be unregistered
     */
    void unregisterCrossWindowBlurEnabledListener(ICrossWindowBlurEnabledListener listener);

    boolean isTaskSnapshotSupported();

    /**
     * Returns the preferred display ID to show software keyboard.
     *
     * @see android.window.WindowProviderService#getLaunchedDisplayId
     */
    int getImeDisplayId();

    /**
     * Control if we should enable task snapshot features on this device.
     * @hide
     */
    void setTaskSnapshotEnabled(boolean enabled);

    /**
     * Customized the task transition animation with a task transition spec.
     *
     * @param spec the spec that will be used to customize the task animations
     */
    void setTaskTransitionSpec(in TaskTransitionSpec spec);

    /**
     * Clears any task transition spec that has been previously set and
     * reverts to using the default task transition with no spec changes.
     */
    void clearTaskTransitionSpec();

    /**
     * Registers the frame rate per second count callback for one given task ID.
     * Each callback can only register for receiving FPS callback for one task id until unregister
     * is called. If there's no task associated with the given task id,
     * {@link IllegalArgumentException} will be thrown. If a task id destroyed after a callback is
     * registered, the registered callback will not be unregistered until
     * {@link unregisterTaskFpsCallback()} is called
     * @param taskId task id of the task.
     * @param callback callback to be registered.
     *
     * @hide
     */
    void registerTaskFpsCallback(in int taskId, in ITaskFpsCallback callback);

    /**
     * Unregisters the frame rate per second count callback which was registered with
     * {@link #registerTaskFpsCallback(int,TaskFpsCallback)}.
     *
     * @param callback callback to be unregistered.
     *
     * @hide
     */
    void unregisterTaskFpsCallback(in ITaskFpsCallback listener);

    /**
     * Take a snapshot using the same path that's used for Recents. This is used for Testing only.
     *
     * @param taskId to take the snapshot of
     *
     * Returns a bitmap of the screenshot or {@code null} if it was unable to screenshot.
     * @hide
     */
    Bitmap snapshotTaskForRecents(int taskId);

    /**
     * Informs the system whether the recents app is currently behind the system bars. If so,
     * means the recents app can control the SystemUI flags, and vice-versa.
     */
    void setRecentsAppBehindSystemBars(boolean behindSystemBars);

    /**
     * Gets the background color of the letterbox. Considered invalid if the background has
     * multiple colors {@link #isLetterboxBackgroundMultiColored}. Should be called by SystemUI when
     * computing the letterbox appearance for status bar treatment.
     */
    int getLetterboxBackgroundColorInArgb();

    /**
     * Whether the outer area of the letterbox has multiple colors (e.g. blurred background).
     * Should be called by SystemUI when computing the letterbox appearance for status bar
     * treatment.
     */
    boolean isLetterboxBackgroundMultiColored();

    /**
     * Captures the entire display specified by the displayId using the args provided. If the args
     * are null or if the sourceCrop is invalid or null, the entire display bounds will be captured.
     */
    oneway void captureDisplay(int displayId, in @nullable ScreenCapture.CaptureArgs captureArgs,
            in ScreenCapture.ScreenCaptureListener listener);

    /**
     * Returns {@code true} if the key will be handled globally and not forwarded to all apps.
     *
     * @param keyCode the key code to check
     * @return {@code true} if the key will be handled globally.
     */
    boolean isGlobalKey(int keyCode);

    /**
     * Create or add to a SurfaceSyncGroup in WindowManager. WindowManager maintains some
     * SurfaceSyncGroups to ensure multiple processes can sync with each other without sharing
     * SurfaceControls
     */
    boolean addToSurfaceSyncGroup(in IBinder syncGroupToken, boolean parentSyncGroupMerge,
                in @nullable ISurfaceSyncGroupCompletedListener completedListener,
                out AddToSurfaceSyncGroupResult addToSurfaceSyncGroupResult);

    /**
     * Mark a SurfaceSyncGroup stored in WindowManager as ready.
     */
    oneway void markSurfaceSyncGroupReady(in IBinder syncGroupToken);

    /**
     * Invoked when a screenshot is taken of the default display to notify registered listeners.
     *
     * Should be invoked only by SysUI.
     *
     * @param displayId id of the display screenshot.
     * @return List of ComponentNames corresponding to the activities that were notified.
    */
    List<ComponentName> notifyScreenshotListeners(int displayId);

    /**
     * Replace the content of the displayId with the SurfaceControl passed in. This can be used for
     * tests when creating a VirtualDisplay, but only want to capture specific content and not
     * mirror the entire display.
     */
     @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
             + ".permission.ACCESS_SURFACE_FLINGER)")
    boolean replaceContentOnDisplay(int displayId, in SurfaceControl sc);

    /**
     * Registers a DecorView gesture listener for a given display.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MONITOR_INPUT)")
    void registerDecorViewGestureListener(IDecorViewGestureListener listener, int displayId);

    /**
     * Unregisters a DecorView gesture listener for a given display.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.MONITOR_INPUT)")
    void unregisterDecorViewGestureListener(IDecorViewGestureListener listener, int displayId);

    void registerTrustedPresentationListener(in IBinder window, in ITrustedPresentationListener listener,
            in TrustedPresentationThresholds thresholds, int id);


    void unregisterTrustedPresentationListener(in ITrustedPresentationListener listener, int id);

    @EnforcePermission("DETECT_SCREEN_RECORDING")
    boolean registerScreenRecordingCallback(IScreenRecordingCallback callback);

    @EnforcePermission("DETECT_SCREEN_RECORDING")
    void unregisterScreenRecordingCallback(IScreenRecordingCallback callback);

    /**
     * Sets the listener to be called back when a cross-window drag and drop operation happens.
     */
    void setGlobalDragListener(IGlobalDragListener listener);

    boolean transferTouchGesture(in InputTransferToken transferFromToken,
            in InputTransferToken transferToToken);
}
