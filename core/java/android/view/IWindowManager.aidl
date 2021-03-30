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
import android.view.DisplayCutout;
import android.view.IApplicationToken;
import android.view.IAppTransitionAnimationSpecsFuture;
import android.view.ICrossWindowBlurEnabledListener;
import android.view.IDisplayWindowInsetsController;
import android.view.IDisplayWindowListener;
import android.view.IDisplayFoldListener;
import android.view.IDisplayWindowRotationController;
import android.view.IOnKeyguardExitResult;
import android.view.IPinnedTaskListener;
import android.view.IScrollCaptureResponseListener;
import android.view.RemoteAnimationAdapter;
import android.view.IRotationWatcher;
import android.view.ISystemGestureExclusionListener;
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
import android.view.WindowContentFrameStats;
import android.view.WindowManager;
import android.view.SurfaceControl;
import android.view.displayhash.DisplayHash;
import android.view.displayhash.VerifiedDisplayHash;

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
     * ===== NOTICE =====
     * The first three methods must remain the first three methods. Scripts
     * and tools rely on their transaction number to work properly.
     */
    // This is used for debugging
    boolean startViewServer(int port);   // Transaction #1
    boolean stopViewServer();            // Transaction #2
    boolean isViewServerRunning();       // Transaction #3

    IWindowSession openSession(in IWindowSessionCallback callback);

    boolean useBLAST();

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
    void setDisplayWindowRotationController(IDisplayWindowRotationController controller);

    /**
     * Adds a root container that a client shell can populate with its own windows (usually via
     * WindowlessWindowManager).
     *
     * @param client an IWindow used for window-level communication (ime, finish draw, etc.).
     * @param shellRootLayer The container's layer. See WindowManager#ShellRootLayer.
     * @return a SurfaceControl to add things to.
     */
    SurfaceControl addShellRoot(int displayId, IWindow client, int shellRootLayer);

    /**
     * Sets the window token sent to accessibility for a particular shell root. The
     * displayId and windowType identify which shell-root to update.
     *
     * @param target The IWindow that accessibility service interfaces with.
     */
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
    void exitKeyguardSecurely(IOnKeyguardExitResult callback);
    @UnsupportedAppUsage
    boolean isKeyguardLocked();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean isKeyguardSecure(int userId);
    void dismissKeyguard(IKeyguardDismissCallback callback, CharSequence message);

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

    // For testing
    @UnsupportedAppUsage(maxTargetSdk = 28)
    void setInTouchMode(boolean showFocus);

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
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
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
     * This is a no-op.
     */
    @UnsupportedAppUsage
    int getDockedStackSide();

    /**
     * Sets the region the user can touch the divider. This region will be excluded from the region
     * which is used to cause a focus switch when dispatching touch.
     */
    void setDockedTaskDividerTouchRegion(in Rect touchableRegion);

    /**
     * Registers a listener that will be called when the pinned task state changes.
     */
    void registerPinnedTaskListener(int displayId, IPinnedTaskListener listener);

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
     * Registers an IDisplayContainerListener
     */
    void registerDisplayWindowListener(IDisplayWindowListener listener);

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
     * Returns true if window trace is enabled.
     */
    boolean isWindowTraceEnabled();

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
     * Waits for transactions to get applied before injecting input, optionally waiting for
     * animations to complete. This includes waiting for the input windows to get sent to
     * InputManager.
     *
     * This is needed for testing since the system add windows and injects input
     * quick enough that the windows don't have time to get sent to InputManager.
     */
    boolean injectInputAfterTransactionsApplied(in InputEvent ev, int mode,
            boolean waitForAnimations);

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
    void setDisplayWindowInsetsController(
            int displayId, in IDisplayWindowInsetsController displayWindowInsetsController);

    /**
     * Called when a remote process modifies insets on a display window container.
     */
    void modifyDisplayWindowInsets(int displayId, in InsetsState state);

    /**
     * Called to get the expected window insets.
     *
     * @return {@code true} if system bars are always comsumed.
     */
    boolean getWindowInsets(in WindowManager.LayoutParams attrs, int displayId,
            out InsetsState outInsetsState);

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
     * Registers a listener for a {@link android.window.WindowContext} to handle configuration
     * changes from the server side.
     * <p>
     * Note that this API should be invoked after calling
     * {@link android.window.WindowTokenClient#attachContext(Context)}
     * </p>
     *
     * @param clientToken the window context's token
     * @param type Window type of the window context
     * @param displayId The display associated with the window context
     * @param options A bundle used to pass window-related options and choose the right DisplayArea
     *
     * @return {@code true} if the listener was registered successfully.
     */
    boolean registerWindowContextListener(IBinder clientToken, int type, int displayId,
            in Bundle options);

    /**
     * Unregisters a listener which registered with {@link #registerWindowContextListener()}.
     *
     * @param clientToken the window context's token
     */
    void unregisterWindowContextListener(IBinder clientToken);

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

    void setForceCrossWindowBlurDisabled(boolean disable);
}
