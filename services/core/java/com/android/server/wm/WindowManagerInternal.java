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

package com.android.server.wm;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.display.VirtualDisplayConfig;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.util.Pair;
import android.view.ContentRecordingSession;
import android.view.Display;
import android.view.IInputFilter;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IWindow;
import android.view.InputChannel;
import android.view.MagnificationSpec;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowInfo;
import android.view.WindowManager.DisplayImePolicy;
import android.view.inputmethod.ImeTracker;
import android.window.ScreenCapture;

import com.android.internal.policy.KeyInterceptionInfo;
import com.android.server.input.InputManagerService;
import com.android.server.policy.WindowManagerPolicy;

import java.lang.annotation.Retention;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Window manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class WindowManagerInternal {

    /**
     * Interface for accessibility features implemented by AccessibilityController inside
     * WindowManager.
     */
    public interface AccessibilityControllerInternal {
        /**
         * Start tracing for the given logging types.
         * @param loggingTypeFlags flags of the logging types enabled.
         */
        void startTrace(long loggingTypeFlags);

        /**
         * Disable accessibility tracing for all logging types.
         */
        void stopTrace();

        /**
         * Is tracing enabled for any logging type.
         */
        boolean isAccessibilityTracingEnabled();

        /**
         * Add an accessibility trace entry.
         *
         * @param where A string to identify this log entry, which can be used to filter/search
         *        through the tracing file.
         * @param loggingTypeFlags The flags for the logging types this log entry belongs to.
         * @param callingParams The parameters for the method to be logged.
         * @param a11yDump The proto byte array for a11y state when the entry is generated
         * @param callingUid The calling uid.
         * @param stackTrace The stack trace, null if not needed.
         * @param ignoreStackEntries The stack entries can be removed
         */
        void logTrace(
                String where, long loggingTypeFlags, String callingParams, byte[] a11yDump,
                int callingUid, StackTraceElement[] stackTrace, Set<String> ignoreStackEntries);

        /**
         * Add an accessibility trace entry.
         *
         * @param where A string to identify this log entry, which can be used to filter/search
         *        through the tracing file.
         * @param loggingTypeFlags The flags for the logging types this log entry belongs to.
         * @param callingParams The parameters for the method to be logged.
         * @param a11yDump The proto byte array for a11y state when the entry is generated.
         * @param callingUid The calling uid.
         * @param callStack The call stack of the method to be logged.
         * @param timeStamp The time when the method to be logged is called.
         * @param processId The calling process Id.
         * @param threadId The calling thread Id.
         * @param ignoreStackEntries The stack entries can be removed
         */
        void logTrace(String where, long loggingTypeFlags, String callingParams,
                byte[] a11yDump, int callingUid, StackTraceElement[] callStack, long timeStamp,
                int processId, long threadId, Set<String> ignoreStackEntries);

        /**
         * Set by the accessibility related modules which want to listen the event dispatched from
         * window manager. Accessibility modules can use these callbacks to handle some display
         * manipulations.
         * @param callbacks The callbacks to invoke.
         */
        void setUiChangesForAccessibilityCallbacks(UiChangesForAccessibilityCallbacks callbacks);

        /**
         * This interface is used by window manager to dispatch some ui change events which may
         * affect the screen accessibility features.
         */
        interface UiChangesForAccessibilityCallbacks {
            /**
             * Called when an application requests a rectangle focus on the screen.
             *
             * @param displayId The logical display id
             * @param left The rectangle left.
             * @param top The rectangle top.
             * @param right The rectangle right.
             * @param bottom The rectangle bottom.
             */
            void onRectangleOnScreenRequested(int displayId, int left, int top, int right,
                    int bottom);
        }
    }

    /**
     * Interface to receive a callback when the windows reported for
     * accessibility changed.
     */
    public interface WindowsForAccessibilityCallback {

        /**
         * Called when the windows for accessibility changed.
         *
         * @param forceSend Send the windows for accessibility even if they haven't changed.
         * @param topFocusedDisplayId The display Id which has the top focused window.
         * @param topFocusedWindowToken The window token of top focused window.
         * @param windows The windows for accessibility.
         */
        void onWindowsForAccessibilityChanged(boolean forceSend, int topFocusedDisplayId,
                IBinder topFocusedWindowToken, @NonNull List<WindowInfo> windows);
    }

    /**
     * Callbacks for contextual changes that affect the screen magnification
     * feature.
     */
    public interface MagnificationCallbacks {

        /**
         * Called when the region where magnification operates changes. Note that this isn't the
         * entire screen. For example, IMEs are not magnified.
         *
         * @param magnificationRegion the current magnification region
         */
        void onMagnificationRegionChanged(Region magnificationRegion);

        /**
         * Called when an application requests a rectangle on the screen to allow
         * the client to apply the appropriate pan and scale.
         *
         * @param left The rectangle left.
         * @param top The rectangle top.
         * @param right The rectangle right.
         * @param bottom The rectangle bottom.
         */
        void onRectangleOnScreenRequested(int left, int top, int right, int bottom);

        /**
         * Notifies that the display size is changed when rotation or the
         * logical display is changed.
         *
         */
        void onDisplaySizeChanged();

        /**
         * Notifies that the context of the user changed. For example, an application
         * was started.
         */
        void onUserContextChanged();

        /**
         * Notifies that the IME window visibility changed.
         * @param shown {@code true} means the IME window shows on the screen. Otherwise it's
         *                           hidden.
         */
        void onImeWindowVisibilityChanged(boolean shown);
    }

    /**
     * Abstract class to be notified about {@link com.android.server.wm.AppTransition} events. Held
     * as an abstract class so a listener only needs to implement the methods of its interest.
     */
    public static abstract class AppTransitionListener {

        /**
         * Called when an app transition is being setup and about to be executed.
         */
        public void onAppTransitionPendingLocked() {}

        /**
         * Called when a pending app transition gets cancelled.
         *
         * @param keyguardGoingAwayCancelled {@code true} if keyguard going away transition was
         *        cancelled.
         */
        public void onAppTransitionCancelledLocked(boolean keyguardGoingAwayCancelled) {}

        /**
         * Called when an app transition is timed out.
         */
        public void onAppTransitionTimeoutLocked() {}

        /**
         * Called when an app transition gets started
         *
         * @param statusBarAnimationStartTime the desired start time for all visual animations in
         *        the status bar caused by this app transition in uptime millis
         * @param statusBarAnimationDuration the duration for all visual animations in the status
         *        bar caused by this app transition in millis
         *
         * @return Return any bit set of {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_LAYOUT},
         * {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_CONFIG},
         * {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_WALLPAPER},
         * or {@link WindowManagerPolicy#FINISH_LAYOUT_REDO_ANIM}.
         */
        public int onAppTransitionStartingLocked(long statusBarAnimationStartTime,
                long statusBarAnimationDuration) {
            return 0;
        }

        /**
         * Called when an app transition is finished running.
         *
         * @param token the token for app whose transition has finished
         */
        public void onAppTransitionFinishedLocked(IBinder token) {}
    }

    /**
     * An interface to be notified when the system bars for a task change.
     */
    public interface TaskSystemBarsListener {

        /**
         * Called when the visibility of the system bars of a task change.
         *
         * @param taskId the identifier of the task.
         * @param visible if the transient system bars are visible.
         * @param wereRevealedFromSwipeOnSystemBar if the transient bars were revealed due to a
         *                                         swipe gesture on a system bar.
         */
        void onTransientSystemBarsVisibilityChanged(
                int taskId,
                boolean visible,
                boolean wereRevealedFromSwipeOnSystemBar);
    }

    /**
     * An interface to be notified when keyguard exit animation should start.
     */
    public interface KeyguardExitAnimationStartListener {
        /**
         * Called when keyguard exit animation should start.
         * @param apps The list of apps to animate.
         * @param wallpapers The list of wallpapers to animate.
         * @param finishedCallback The callback to invoke when the animation is finished.
         */
        void onAnimationStart(RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                IRemoteAnimationFinishedCallback finishedCallback);
    }

    /**
      * An interface to be notified about hardware keyboard status.
      */
    public interface OnHardKeyboardStatusChangeListener {
        public void onHardKeyboardStatusChange(boolean available);
    }

    /**
     * An interface to customize drag and drop behaviors.
     */
    public interface IDragDropCallback {
        default CompletableFuture<Boolean> registerInputChannel(
                DragState state, Display display, InputManagerService service,
                InputChannel source) {
            return state.register(display)
                .thenApply(unused ->
                    service.transferTouchFocus(source, state.getInputChannel(),
                            true /* isDragDrop */));
        }

        /**
         * Called when drag operation is starting.
         */
        default boolean prePerformDrag(IWindow window, IBinder dragToken,
                int touchSource, float touchX, float touchY, float thumbCenterX, float thumbCenterY,
                ClipData data) {
            return true;
        }

        /**
         * Called when drag operation is started.
         */
        default void postPerformDrag() {}

        /**
         * Called when drop result is being reported.
         */
        default void preReportDropResult(IWindow window, boolean consumed) {}

        /**
         * Called when drop result was reported.
         */
        default void postReportDropResult() {}

        /**
         * Called when drag operation is being cancelled.
         */
        default void preCancelDragAndDrop(IBinder dragToken) {}

        /**
         * Called when drag operation was cancelled.
         */
        default void postCancelDragAndDrop() {}

        /**
         * Called when it has entered a View that is willing to accept the drop.
         */
        default void dragRecipientEntered(IWindow window) {}

        /**
         * Called when it has exited a View that is willing to accept the drop.
         */
        default void dragRecipientExited(IWindow window) {}
    }

    /**
     * Request the interface to access features implemented by AccessibilityController.
     */
    public abstract AccessibilityControllerInternal getAccessibilityController();

    /**
     * Request that the window manager call
     * {@link DisplayManagerInternal#performTraversalInTransactionFromWindowManager}
     * within a surface transaction at a later time.
     */
    public abstract void requestTraversalFromDisplayManager();

    /**
     * Called just before display manager has applied the device state to the displays
     * @param deviceState device state as defined by
     *        {@link android.hardware.devicestate.DeviceStateManager}
     */
    public abstract void onDisplayManagerReceivedDeviceState(int deviceState);

    /**
     * Set by the accessibility layer to observe changes in the magnified region,
     * rotation, and other window transformations related to display magnification
     * as the window manager is responsible for doing the actual magnification
     * and has access to the raw window data while the accessibility layer serves
     * as a controller.
     *
     * @param displayId The logical display id.
     * @param callbacks The callbacks to invoke.
     * @return {@code false} if display id is not valid or an embedded display.
     */
    public abstract boolean setMagnificationCallbacks(int displayId,
            @Nullable MagnificationCallbacks callbacks);

    /**
     * Set by the accessibility layer to specify the magnification and panning to
     * be applied to all windows that should be magnified.
     *
     * @param displayId The logical display id.
     * @param spec The MagnficationSpec to set.
     *
     * @see #setMagnificationCallbacks(int, MagnificationCallbacks)
     */
    public abstract void setMagnificationSpec(int displayId, MagnificationSpec spec);

    /**
     * Set by the accessibility framework to indicate whether the magnifiable regions of the display
     * should be shown.
     *
     * @param displayId The logical display id.
     * @param show {@code true} to show magnifiable region bounds, {@code false} to hide
     */
    public abstract void setForceShowMagnifiableBounds(int displayId, boolean show);

    /**
     * Obtains the magnification regions.
     *
     * @param displayId The logical display id.
     * @param magnificationRegion the current magnification region
     */
    public abstract void getMagnificationRegion(int displayId, @NonNull Region magnificationRegion);

    /**
     * Sets a callback for observing which windows are touchable for the purposes
     * of accessibility on specified display.
     *
     * @param displayId The logical display id.
     * @param callback The callback.
     */
    public abstract void setWindowsForAccessibilityCallback(int displayId,
            WindowsForAccessibilityCallback callback);

    /**
     * Sets a filter for manipulating the input event stream.
     *
     * @param filter The filter implementation.
     */
    public abstract void setInputFilter(IInputFilter filter);

    /**
     * Gets the token of the window that has input focus.
     *
     * @return The token.
     */
    public abstract IBinder getFocusedWindowToken();

    /**
     * Gets the token of the window that has input focus. It is from the focused
     * {@link WindowState}.
     *
     * @return The token.
     */
    public abstract IBinder getFocusedWindowTokenFromWindowStates();

    /**
     * Moves the given display to the top.
     */
    public abstract void moveDisplayToTopIfAllowed(int displayId);

    /**
     * Request to move window input focus to the window with the provided window token.
     *
     * <p>
     * It is necessary to move window input focus before certain actions on views in a window can
     * be performed, such as opening an IME. Input normally requests to move focus on window touch
     * so this method should not be necessary in most cases; only features that bypass normal touch
     * behavior (like Accessibility actions) require this method.
     * </p>
     * @param windowToken The window token.
     */
    public abstract void requestWindowFocus(IBinder windowToken);

    /**
     * @return Whether the keyguard is engaged.
     */
    public abstract boolean isKeyguardLocked();

    /**
    * @return Whether the keyguard is showing and not occluded.
    */
    public abstract boolean isKeyguardShowingAndNotOccluded();

    /**
     * Return whether the keyguard is secured by a PIN, pattern or password or a SIM card is
     * currently locked.
     *
     * @param userId User ID to be queried about.
     * @return {@code true} if a PIN, pattern or password is set or a SIM card is locked.
     */
    public abstract boolean isKeyguardSecure(@UserIdInt int userId);

    /**
     * Gets the frame of a window given its token.
     *
     * @param token The token.
     * @param outBounds The frame to populate.
     */
    public abstract void getWindowFrame(IBinder token, Rect outBounds);

    /**
     * Get the transformation matrix and MagnificationSpec given its token.
     *
     * @param token The token.
     * @return The pair of the transformation matrix and magnification spec.
     */
    // TODO (b/231663133): Long term solution for tracking window when the
    //                     FLAG_RETRIEVE_INTERACTIVE_WINDOWS is unset.
    public abstract Pair<Matrix, MagnificationSpec>
            getWindowTransformationMatrixAndMagnificationSpec(IBinder token);

    /**
     * Opens the global actions dialog.
     */
    public abstract void showGlobalActions();

    /**
     * Invalidate all visible windows on a given display, and report back on the callback when all
     * windows have redrawn.
     *
     * @param message The message will be sent when all windows have redrawn. Note that the message
     *                must be obtained from handler, otherwise it will throw NPE.
     * @param timeout calls the callback anyway after the timeout.
     * @param displayId waits for the windows on the given display, INVALID_DISPLAY to wait for all
     *                  windows on all displays.
     */
    public abstract void waitForAllWindowsDrawn(Message message, long timeout, int displayId);

    /**
     * Overrides the display size.
     *
     * @param displayId The display to override the display size.
     * @param width The width to override.
     * @param height The height to override.
     */
    public abstract void setForcedDisplaySize(int displayId, int width, int height);

    /**
     * Recover the display size to real display size.
     *
     * @param displayId The display to recover the display size.
     */
    public abstract void clearForcedDisplaySize(int displayId);

    /**
     * Adds a window token for a given window type.
     *
     * @param token The token to add.
     * @param type The window type.
     * @param displayId The display to add the token to.
     * @param options A bundle used to pass window-related options.
     */
    public abstract void addWindowToken(@NonNull android.os.IBinder token, int type, int displayId,
            @Nullable Bundle options);

    /**
     * Removes a window token.
     *
     * @param token The toke to remove.
     * @param removeWindows Whether to also remove the windows associated with the token.
     * @param displayId The display to remove the token from.
     */
    public final void removeWindowToken(android.os.IBinder token, boolean removeWindows,
            int displayId) {
        removeWindowToken(token, removeWindows, true /* animateExit */, displayId);
    }

    /**
     * Removes a window token.
     *
     * @param token The toke to remove.
     * @param removeWindows Whether to also remove the windows associated with the token.
     * @param animateExit Whether to play the windows exit animation after the token removal.
     * @param displayId The display to remove the token from.
     */
    public abstract void removeWindowToken(android.os.IBinder token, boolean removeWindows,
            boolean animateExit, int displayId);

    /**
     * Registers a listener to be notified about app transition events.
     *
     * @param listener The listener to register.
     */
    public abstract void registerAppTransitionListener(AppTransitionListener listener);

    /**
     * Registers a listener to be notified to when the system bars of a task changes.
     *
     * @param listener The listener to register.
     */
    public abstract void registerTaskSystemBarsListener(TaskSystemBarsListener listener);

    /**
     * Registers a listener to be notified to when the system bars of a task changes.
     *
     * @param listener The listener to unregister.
     */
    public abstract void unregisterTaskSystemBarsListener(TaskSystemBarsListener listener);

    /**
     * Registers a listener to be notified to start the keyguard exit animation.
     *
     * @param listener The listener to register.
     */
    public abstract void registerKeyguardExitAnimationStartListener(
            KeyguardExitAnimationStartListener listener);

    /**
     * Reports that the password for the given user has changed.
     */
    public abstract void reportPasswordChanged(int userId);

    /**
     * Retrieves a height of input method window for given display.
     */
    public abstract int getInputMethodWindowVisibleHeight(int displayId);

    /**
     * Notifies WindowManagerService that the expected back-button behavior might have changed.
     *
     * <p>Only {@link com.android.server.inputmethod.InputMethodManagerService} is the expected and
     * tested caller of this method.</p>
     *
     * @param dismissImeOnBackKeyPressed {@code true} if the software keyboard is shown and the back
     *                                   key is expected to dismiss the software keyboard.
     */
    public abstract void setDismissImeOnBackKeyPressed(boolean dismissImeOnBackKeyPressed);

    /**
     * Notifies WindowManagerService that the current IME window status is being changed.
     *
     * <p>Only {@link com.android.server.inputmethod.InputMethodManagerService} is the expected and
     * tested caller of this method.</p>
     *
     * @param imeToken token to track the active input method. Corresponding IME windows can be
     *                 identified by checking {@link android.view.WindowManager.LayoutParams#token}.
     *                 Note that there is no guarantee that the corresponding window is already
     *                 created
     * @param imeTargetWindowToken token to identify the target window that the IME is associated
     *                             with
     */
    public abstract void updateInputMethodTargetWindow(@NonNull IBinder imeToken,
            @NonNull IBinder imeTargetWindowToken);

    /**
      * Returns true when the hardware keyboard is available.
      */
    public abstract boolean isHardKeyboardAvailable();

    /**
      * Sets the callback listener for hardware keyboard status changes.
      *
      * @param listener The listener to set.
      */
    public abstract void setOnHardKeyboardStatusChangeListener(
        OnHardKeyboardStatusChangeListener listener);

    /**
     * Requests the window manager to resend the windows for accessibility on specified display.
     *
     * @param displayId Display ID to be computed its windows for accessibility
     */
    public abstract void computeWindowsForAccessibility(int displayId);

    /**
     * Called after virtual display Id is updated by
     * {@link com.android.server.vr.Vr2dDisplay} with a specific
     * {@param vr2dDisplayId}.
     */
    public abstract void setVr2dDisplayId(int vr2dDisplayId);

    /**
     * Sets callback to DragDropController.
     */
    public abstract void registerDragDropControllerCallback(IDragDropCallback callback);

    /**
     * @see android.view.IWindowManager#lockNow
     */
    public abstract void lockNow();

    /**
     * Return the user that owns the given window, {@link android.os.UserHandle#USER_NULL} if
     * the window token is not found.
     */
    public abstract int getWindowOwnerUserId(IBinder windowToken);

    /**
     * Control visilibility of a {@link WallpaperWindowToken} {@code} binder on the lock screen.
     *
     * <p>This will also affect its Z-ordering as {@code showWhenLocked} wallpaper tokens are
     * arranged underneath non-{@code showWhenLocked} wallpaper tokens.
     *
     * @param windowToken wallpaper token previously added via {@link #addWindowToken}
     * @param showWhenLocked whether {@param token} can continue to be shown on the lock screen.
     */
    public abstract void setWallpaperShowWhenLocked(IBinder windowToken, boolean showWhenLocked);

    /**
     * Returns {@code true} if a Window owned by {@code uid} has focus.
     */
    public abstract boolean isUidFocused(int uid);

    /**
     * Checks whether the specified IME client has IME focus or not.
     *
     * @param windowToken The window token of the input method client
     * @param uid UID of the process to be queried
     * @param pid PID of the process to be queried
     * @param displayId Display ID reported from the client. Note that this method also verifies
     *                  whether the specified process is allowed to access to this display or not
     * @return {@code true} if the IME client specified with {@code uid}, {@code pid}, and
     *         {@code displayId} has IME focus
     */
    public abstract @ImeClientFocusResult int hasInputMethodClientFocus(IBinder windowToken,
            int uid, int pid, int displayId);

    @Retention(SOURCE)
    @IntDef({
            ImeClientFocusResult.HAS_IME_FOCUS,
            ImeClientFocusResult.NOT_IME_TARGET_WINDOW,
            ImeClientFocusResult.DISPLAY_ID_MISMATCH,
            ImeClientFocusResult.INVALID_DISPLAY_ID
    })
    public @interface ImeClientFocusResult {
        int HAS_IME_FOCUS = 0;
        int NOT_IME_TARGET_WINDOW = -1;
        int DISPLAY_ID_MISMATCH = -2;
        int INVALID_DISPLAY_ID = -3;
    }

    /**
     * Checks whether the given {@code uid} is allowed to use the given {@code displayId} or not.
     *
     * @param displayId Display ID to be checked
     * @param uid UID to be checked.
     * @return {@code true} if the given {@code uid} is allowed to use the given {@code displayId}
     */
    public abstract boolean isUidAllowedOnDisplay(int displayId, int uid);

    /**
     * Return the display Id for given window.
     */
    public abstract int getDisplayIdForWindow(IBinder windowToken);

    /**
     * @return The top focused display ID.
     */
    public abstract int getTopFocusedDisplayId();

    /**
     * @return The UI context of top focused display.
     */
    public abstract Context getTopFocusedDisplayUiContext();

    /**
     * Sets whether the relevant display content can host the relevant home activity and wallpaper.
     *
     * @param displayUniqueId The unique ID of the display. Note that the display may not yet be
     *   created, but whenever it is, this property will be applied.
     * @param displayType The type of the display, e.g. {@link Display#TYPE_VIRTUAL}.
     * @param supported Whether home and wallpaper are supported on this display.
     */
    public abstract void setHomeSupportedOnDisplay(
            @NonNull String displayUniqueId, int displayType, boolean supported);

    /**
     * Checks if this display is configured and allowed to show home activity and wallpaper.
     *
     * <p>This is implied for displays that have {@link Display#FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS}
     * and can also be set via {@link VirtualDisplayConfig.Builder#setHomeSupported}.</p>
     */
    public abstract boolean isHomeSupportedOnDisplay(int displayId);

    /**
     * Removes any settings relevant to the given display.
     *
     * <p>This may be used when a property is set for a display unique ID before the display
     * creation but the actual display creation failed for some reason.</p>
     */
    public abstract void clearDisplaySettings(@NonNull String displayUniqueId, int displayType);

    /**
     * Indicates the policy for how the display should show IME.
     *
     * @param displayId The id of the display.
     * @return The policy for how the display should show IME.
     */
    public abstract @DisplayImePolicy int getDisplayImePolicy(int displayId);

    /**
     * Show IME on imeTargetWindow once IME has finished layout.
     *
     * @param imeTargetWindowToken token of the (IME target) window which IME should be shown.
     * @param statsToken the token tracking the current IME show request or {@code null} otherwise.
     */
    public abstract void showImePostLayout(IBinder imeTargetWindowToken,
            @Nullable ImeTracker.Token statsToken);

    /**
     * Hide IME using imeTargetWindow when requested.
     *
     * @param imeTargetWindowToken token of the (IME target) window on which requests hiding IME.
     * @param displayId the id of the display the IME is on.
     * @param statsToken the token tracking the current IME hide request or {@code null} otherwise.
     */
    public abstract void hideIme(IBinder imeTargetWindowToken, int displayId,
            @Nullable ImeTracker.Token statsToken);

    /**
     * Tell window manager about a package that should be running with a restricted range of
     * refresh rate setting until removeRefreshRateRangeForPackage is called for the same package.
     *
     * This must not be called again for the same package.
     */
    public abstract void addRefreshRateRangeForPackage(@NonNull String packageName,
            float minRefreshRate, float maxRefreshRate);

    /**
     * Tell window manager to stop constraining refresh rate for the given package.
     */
    public abstract void removeRefreshRateRangeForPackage(@NonNull String packageName);

    /**
     * Checks if the device supports touch or faketouch.
     */
    public abstract boolean isTouchOrFaketouchDevice();

    /**
     * Returns the info associated with the input token used to determine if a key should be
     * intercepted. This info can be accessed without holding the global wm lock.
     */
    public abstract @Nullable KeyInterceptionInfo
            getKeyInterceptionInfoFromToken(IBinder inputToken);

    /**
     * Clears the snapshot cache of running activities so they show the splash-screen
     * the next time the activities are opened.
     */
    public abstract void clearSnapshotCache();

    /**
     * Assigns accessibility ID a window surface as a layer metadata.
     */
    public abstract void setAccessibilityIdToSurfaceMetadata(
            IBinder windowToken, int accessibilityWindowId);

    /**
     *
     * Returns the window name associated to the given binder.
     *
     * @param binder The {@link IBinder} object
     * @return The corresponding {@link WindowState#getName()}
     */
    public abstract String getWindowName(@NonNull IBinder binder);

    /**
     * The callback after the request of show/hide input method is sent.
     *
     * @param show Whether to show or hide input method.
     * @param focusedToken The token of focused window.
     * @param requestToken The token of window who requests the change.
     * @param displayId The ID of the display which input method is currently focused.
     * @return The information of the input method target.
     */
    public abstract ImeTargetInfo onToggleImeRequested(boolean show,
            @NonNull IBinder focusedToken, @NonNull IBinder requestToken, int displayId);

    /** The information of input method target when IME is requested to show or hide. */
    public static class ImeTargetInfo {
        public final String focusedWindowName;
        public final String requestWindowName;

        /** The window name of IME Insets control target. */
        public final String imeControlTargetName;

        /**
         * The current window name of the input method is on top of.
         * <p>
         * Note that the concept of this window is only used to reparent the target window behind
         * the input method window, it may be different from the window reported by
         * {@link com.android.server.inputmethod.InputMethodManagerService#reportStartInput} which
         * has input connection.
         */
        public final String imeLayerTargetName;

        /** The surface parent of the IME container. */
        public final String imeSurfaceParentName;

        public ImeTargetInfo(String focusedWindowName, String requestWindowName,
                String imeControlTargetName, String imeLayerTargetName,
                String imeSurfaceParentName) {
            this.focusedWindowName = focusedWindowName;
            this.requestWindowName = requestWindowName;
            this.imeControlTargetName = imeControlTargetName;
            this.imeLayerTargetName = imeLayerTargetName;
            this.imeSurfaceParentName = imeSurfaceParentName;
        }
    }

    /**
     * Sets by the {@link com.android.server.inputmethod.InputMethodManagerService} to monitor
     * the visibility change of the IME targeted windows.
     *
     * @see ImeTargetChangeListener#onImeTargetOverlayVisibilityChanged
     * @see ImeTargetChangeListener#onImeInputTargetVisibilityChanged
     */
    public abstract void setInputMethodTargetChangeListener(
            @NonNull ImeTargetChangeListener listener);

    /**
     * Moves the {@link WindowToken} {@code binder} to the display specified by {@code displayId}.
     */
    public abstract void moveWindowTokenToDisplay(IBinder binder, int displayId);

    /**
     * Checks whether the given window should restore the last IME visibility.
     *
     * @param imeTargetWindowToken The token of the (IME target) window
     * @return {@code true} when the system allows to restore the IME visibility,
     *         {@code false} otherwise.
     */
    public abstract boolean shouldRestoreImeVisibility(IBinder imeTargetWindowToken);

    /**
     * Internal methods for other parts of SystemServer to manage
     * SurfacePackage based overlays on tasks.
     *
     * Since these overlays will overlay application content, they exist
     * in a container with setTrustedOverlay(true). This means its imperative
     * that this overlay feature only be used with UI completely under the control
     * of the system, without 3rd party content.
     *
     * Callers prepare a view hierarchy with SurfaceControlViewHost
     * and send the package to WM here. The remote view hierarchy will receive
     * configuration change, lifecycle events, etc, forwarded over the
     * ISurfaceControlViewHost interface inside the SurfacePackage. Embedded
     * hierarchies will receive inset changes, including transient inset changes
     * (to avoid the status bar in immersive mode).
     *
     * The embedded hierarchy exists in a coordinate space relative to the task
     * bounds.
     */
    public abstract void addTrustedTaskOverlay(int taskId,
            SurfaceControlViewHost.SurfacePackage overlay);
    public abstract void removeTrustedTaskOverlay(int taskId,
            SurfaceControlViewHost.SurfacePackage overlay);

    /**
     * Get a SurfaceControl that is the container layer that should be used to receive input to
     * support handwriting (Scribe) by the IME.
     */
    public abstract SurfaceControl getHandwritingSurfaceForDisplay(int displayId);

    /**
     * Returns {@code true} if the given point is within the window bounds of the given window.
     *
     * @param windowToken the window whose bounds should be used for the hit test.
     * @param displayX the x coordinate of the test point in the display's coordinate space.
     * @param displayY the y coordinate of the test point in the display's coordinate space.
     */
    public abstract boolean isPointInsideWindow(
            @NonNull IBinder windowToken, int displayId, float displayX, float displayY);

    /**
     * Updates the content recording session. If a different session is already in progress, then
     * the pre-existing session is stopped, and the new incoming session takes over.
     *
     * The DisplayContent for the new session will begin recording when
     * {@link RootWindowContainer#onDisplayChanged} is invoked for the new {@link VirtualDisplay}.
     * Must be invoked for a valid MediaProjection session.
     *
     * @param incomingSession the nullable incoming content recording session
     * @return {@code true} if successfully set the session, or {@code false} if the session
     * could not be prepared and the session needs to be torn down.
     */
    public abstract boolean setContentRecordingSession(ContentRecordingSession incomingSession);

    /** Returns the SurfaceControl accessibility services should use for accessibility overlays. */
    public abstract SurfaceControl getA11yOverlayLayer(int displayId);

    /**
     * Captures the entire display specified by the displayId using the args provided. If the args
     * are null or if the sourceCrop is invalid or null, the entire display bounds will be captured.
     */
    public abstract void captureDisplay(int displayId,
                                        @Nullable ScreenCapture.CaptureArgs captureArgs,
                                        ScreenCapture.ScreenCaptureListener listener);

    /**
     * Device has a software navigation bar (separate from the status bar) on specific display.
     *
     * @param displayId the id of display to check if there is a software navigation bar.
     */
    public abstract boolean hasNavigationBar(int displayId);
}
