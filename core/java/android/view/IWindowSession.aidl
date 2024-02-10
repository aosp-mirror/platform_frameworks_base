/* //device/java/android/android/view/IWindowSession.aidl
**
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

import android.content.ClipData;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.util.MergedConfiguration;
import android.view.DisplayCutout;
import android.view.InputChannel;
import android.view.IWindow;
import android.view.IWindowId;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.window.ClientWindowFrames;
import android.window.InputTransferToken;
import android.window.OnBackInvokedCallbackInfo;

import java.util.List;

/**
 * System private per-application interface to the window manager.
 *
 * {@hide}
 */
interface IWindowSession {
    int addToDisplay(IWindow window, in WindowManager.LayoutParams attrs,
            in int viewVisibility, in int layerStackId, int requestedVisibleTypes,
            out InputChannel outInputChannel, out InsetsState insetsState,
            out InsetsSourceControl.Array activeControls, out Rect attachedFrame,
            out float[] sizeCompatScale);
    int addToDisplayAsUser(IWindow window, in WindowManager.LayoutParams attrs,
            in int viewVisibility, in int layerStackId, in int userId, int requestedVisibleTypes,
            out InputChannel outInputChannel, out InsetsState insetsState,
            out InsetsSourceControl.Array activeControls, out Rect attachedFrame,
            out float[] sizeCompatScale);
    int addToDisplayWithoutInputChannel(IWindow window, in WindowManager.LayoutParams attrs,
            in int viewVisibility, in int layerStackId, out InsetsState insetsState,
            out Rect attachedFrame, out float[] sizeCompatScale);

    /**
     * Removes a clientToken from WMS, which includes unlinking the input channel.
     *
     * @param clientToken The token that should be removed. This will normally be the IWindow token
     * for a standard window. It can also be the generic clientToken that was used when calling
     * grantInputChannel
     */
    void remove(IBinder clientToken);

    /**
     * Change the parameters of a window.  You supply the
     * new parameters, it returns the new frame of the window on screen (the
     * position should be ignored) and surface of the window.  The surface
     * will be invalid if the window is currently hidden, else you can use it
     * to draw the window's contents.
     *
     * @param window The window being modified.
     * @param attrs If non-null, new attributes to apply to the window.
     * @param requestedWidth The width the window wants to be.
     * @param requestedHeight The height the window wants to be.
     * @param viewVisibility Window root view's visibility.
     * @param flags Request flags: {@link WindowManagerGlobal#RELAYOUT_INSETS_PENDING}.
     * @param seq The calling sequence of {@link #relayout} and {@link #relayoutAsync}.
     * @param lastSyncSeqId The last SyncSeqId that the client applied.
     * @param outFrames The window frames used by the client side for layout.
     * @param outMergedConfiguration New config container that holds global, override and merged
     *                               config for window, if it is now becoming visible and the merged
     *                               config has changed since it was last displayed.
     * @param outSurfaceControl Object in which is placed the new display surface.
     * @param insetsState The current insets state in the system.
     * @param activeControls Objects which allow controlling {@link InsetsSource}s.
     * @param bundle A temporary object to obtain the latest SyncSeqId.
     * @return int Result flags, defined in {@link WindowManagerGlobal}.
     */
    int relayout(IWindow window, in WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility,
            int flags, int seq, int lastSyncSeqId, out ClientWindowFrames outFrames,
            out MergedConfiguration outMergedConfiguration, out SurfaceControl outSurfaceControl,
            out InsetsState insetsState, out InsetsSourceControl.Array activeControls,
            out Bundle bundle);

    /**
     * Similar to {@link #relayout} but this is an oneway method which doesn't return anything.
     *
     * @param window The window being modified.
     * @param attrs If non-null, new attributes to apply to the window.
     * @param requestedWidth The width the window wants to be.
     * @param requestedHeight The height the window wants to be.
     * @param viewVisibility Window root view's visibility.
     * @param flags Request flags: {@link WindowManagerGlobal#RELAYOUT_INSETS_PENDING}.
     * @param seq The calling sequence of {@link #relayout} and {@link #relayoutAsync}.
     * @param lastSyncSeqId The last SyncSeqId that the client applied.
     */
    oneway void relayoutAsync(IWindow window, in WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility, int flags, int seq,
            int lastSyncSeqId);

    /**
     * Called by a client to report that it ran out of graphics memory.
     */
    boolean outOfMemory(IWindow window);

    /**
     * Tell the window manager about the content and visible insets of the
     * given window, which can be used to adjust the <var>outContentInsets</var>
     * and <var>outVisibleInsets</var> values returned by
     * {@link #relayout relayout()} for windows behind this one.
     *
     * @param touchableInsets Controls which part of the window inside of its
     * frame can receive pointer events, as defined by
     * {@link android.view.ViewTreeObserver.InternalInsetsInfo}.
     */
    oneway void setInsets(IWindow window, int touchableInsets, in Rect contentInsets,
            in Rect visibleInsets, in Region touchableRegion);

    /**
     * Called when the client has finished drawing the surface, if needed.
     *
     * @param postDrawTransaction transaction filled by the client that can be
     * used to synchronize any post draw transactions with the server. Transaction
     * is null if there is no sync required.
     */
    @UnsupportedAppUsage
    oneway void finishDrawing(IWindow window, in SurfaceControl.Transaction postDrawTransaction,
            int seqId);

    @UnsupportedAppUsage
    boolean performHapticFeedback(int effectId, boolean always);

    /**
     * Called by attached views to perform predefined haptic feedback without requiring VIBRATE
     * permission.
     */
    oneway void performHapticFeedbackAsync(int effectId, boolean always);

    /**
     * Initiate the drag operation itself
     *
     * @param window Window which initiates drag operation.
     * @param flags See {@code View#startDragAndDrop}
     * @param surface Surface containing drag shadow image
     * @param touchSource See {@code InputDevice#getSource()}
     * @param touchDeviceId device ID of last touch event
     * @param pointerId pointer ID of last touch event
     * @param touchX X coordinate of last touch point
     * @param touchY Y coordinate of last touch point
     * @param thumbCenterX X coordinate for the position within the shadow image that should be
     *         underneath the touch point during the drag and drop operation.
     * @param thumbCenterY Y coordinate for the position within the shadow image that should be
     *         underneath the touch point during the drag and drop operation.
     * @param data Data transferred by drag and drop
     * @return Token of drag operation which will be passed to cancelDragAndDrop.
     */
    IBinder performDrag(IWindow window, int flags, in SurfaceControl surface, int touchSource,
            int touchDeviceId, int touchPointerId, float touchX, float touchY, float thumbCenterX,
            float thumbCenterY, in ClipData data);

    /**
     * Drops the content of the current drag operation for accessibility
     */
    boolean dropForAccessibility(IWindow window, int x, int y);

    /**
     * Report the result of a drop action targeted to the given window.
     * consumed is 'true' when the drop was accepted by a valid recipient,
     * 'false' otherwise.
     */
    oneway void reportDropResult(IWindow window, boolean consumed);

    /**
     * Cancel the current drag operation.
     * skipAnimation is 'true' when it should skip the drag cancel animation which brings the drag
     * shadow image back to the drag start position.
     */
    oneway void cancelDragAndDrop(IBinder dragToken, boolean skipAnimation);

    /**
     * Tell the OS that we've just dragged into a View that is willing to accept the drop
     */
    oneway void dragRecipientEntered(IWindow window);

    /**
     * Tell the OS that we've just dragged *off* of a View that was willing to accept the drop
     */
    oneway void dragRecipientExited(IWindow window);

    /**
     * For windows with the wallpaper behind them, and the wallpaper is
     * larger than the screen, set the offset within the screen.
     * For multi screen launcher type applications, xstep and ystep indicate
     * how big the increment is from one screen to another.
     */
    oneway void setWallpaperPosition(IBinder windowToken, float x, float y, float xstep, float ystep);

    /**
     * For wallpaper windows, sets the scale of the wallpaper based on
     * SystemUI behavior.
     */
    oneway void setWallpaperZoomOut(IBinder windowToken, float scale);

    /**
     * For wallpaper windows, sets whether the wallpaper should actually be
     * scaled when setWallpaperZoomOut is called. If set to false, the WallpaperService will
     * receive the zoom out value but the surface won't be scaled.
     */
    oneway void setShouldZoomOutWallpaper(IBinder windowToken, boolean shouldZoom);

    @UnsupportedAppUsage
    oneway void wallpaperOffsetsComplete(IBinder window);

    /**
     * Apply a raw offset to the wallpaper service when shown behind this window.
     */
    oneway void setWallpaperDisplayOffset(IBinder windowToken, int x, int y);

    oneway void sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, in Bundle extras, boolean sync);

    @UnsupportedAppUsage
    oneway void wallpaperCommandComplete(IBinder window, in Bundle result);

    /**
     * Notifies that a rectangle on the screen has been requested.
     */
    oneway void onRectangleOnScreenRequested(IBinder token, in Rect rectangle);

    IWindowId getWindowId(IBinder window);

    /**
     * When the system is dozing in a low-power partially suspended state, pokes a short
     * lived wake lock and ensures that the display is ready to accept the next frame
     * of content drawn in the window.
     *
     * This mechanism is bound to the window rather than to the display manager or the
     * power manager so that the system can ensure that the window is actually visible
     * and prevent runaway applications from draining the battery.  This is similar to how
     * FLAG_KEEP_SCREEN_ON works.
     *
     * This method is synchronous because it may need to acquire a wake lock before returning.
     * The assumption is that this method will be called rather infrequently.
     */
    void pokeDrawLock(IBinder window);

    /**
     * Starts a task window move with {startX, startY} as starting point. The amount of move
     * will be the offset between {startX, startY} and the new cursor position.
     *
     * Returns true if the move started successfully; false otherwise.
     */
    boolean startMovingTask(IWindow window, float startX, float startY);

    oneway void finishMovingTask(IWindow window);

    oneway void updatePointerIcon(IWindow window);

    /**
     * Update a tap exclude region identified by provided id in the window. Touches on this region
     * will neither be dispatched to this window nor change the focus to this window. Passing an
     * invalid region will remove the area from the exclude region of this window.
     */
    oneway void updateTapExcludeRegion(IWindow window, in Region region);

    /**
     * Updates the requested visible types of insets.
     */
    oneway void updateRequestedVisibleTypes(IWindow window, int requestedVisibleTypes);

    /**
     * Called when the system gesture exclusion has changed.
     */
    oneway void reportSystemGestureExclusionChanged(IWindow window, in List<Rect> exclusionRects);

    /**
     * Called when the DecorView gesture interception state has changed.
     */
    oneway void reportDecorViewGestureInterceptionChanged(IWindow window, in boolean intercepted);

    /**
     * Called when the keep-clear areas for this window have changed.
     */
    oneway void reportKeepClearAreasChanged(IWindow window, in List<Rect> restricted,
           in List<Rect> unrestricted);

    /**
    * Request the server to call setInputWindowInfo on a given Surface, and return
    * an input channel where the client can receive input. For windows, the clientToken should be
    * the IWindow binder object. For other requests, the token can be any unique IBinder token to
    * be used as unique identifier.
    */
    void grantInputChannel(int displayId, in SurfaceControl surface, in IBinder clientToken,
            in @nullable InputTransferToken hostInputTransferToken, int flags, int privateFlags,
            int inputFeatures, int type, in IBinder windowToken,
            in InputTransferToken embeddedInputTransferToken, String inputHandleName,
            out InputChannel outInputChannel);

    /**
     * Update the flags on an input channel associated with a particular surface.
     */
    oneway void updateInputChannel(in IBinder channelToken, int displayId,
            in SurfaceControl surface, int flags, int privateFlags, int inputFeatures,
            in Region region);

    /**
     * Transfer window focus to an embedded window if the calling window has focus.
     *
     * @param window - calling window owned by the caller. Window can be null if there
     *                 is no host window but the caller must have permissions to create an embedded
     *                 window without a host window.
     * @param inputToken - token identifying the embedded window that should gain focus.
     * @param grantFocus - true if focus should be granted to the embedded window, false if focus
     *                     should be transferred back to the host window. If there is no host
     *                     window, the system will try to find a new focus target.
     */
    void grantEmbeddedWindowFocus(IWindow window, in InputTransferToken inputToken,
            boolean grantFocus);

    /**
     * Generates an DisplayHash that can be used to validate whether specific content was on
     * screen.
     *
     * @param window The token for the window to generate the hash of.
     * @param boundsInWindow The size and position in the window of where to generate the hash.
     * @param hashAlgorithm The String for the hash algorithm to use based on values returned
     *                      from {@link IWindowManager#getSupportedDisplayHashAlgorithms()}
     * @param callback The callback invoked to get the results of generateDisplayHash
     */
    oneway void generateDisplayHash(IWindow window, in Rect boundsInWindow,
            in String hashAlgorithm, in RemoteCallback callback);

    /**
     * Sets the {@link OnBackInvokedCallbackInfo} containing the callback to be invoked for
     * a window when back is triggered.
     *
     * @param window The token for the window to set the callback to.
     * @param callbackInfo The {@link OnBackInvokedCallbackInfo} to set.
     */
    oneway void setOnBackInvokedCallbackInfo(
            IWindow window, in OnBackInvokedCallbackInfo callbackInfo);

    /**
     * Clears a touchable region set by {@link #setInsets}.
     */
    void clearTouchableRegion(IWindow window);

    /**
     * Returns whether this window needs to cancel draw and retry later.
     */
    boolean cancelDraw(IWindow window);

    boolean transferEmbeddedTouchFocusToHost(IWindow embeddedWindow);

    boolean transferHostTouchGestureToEmbedded(IWindow hostWindow,
        in InputTransferToken transferTouchToken);

    /**
     * Moves the focus to the adjacent window if there is one in the given direction. This can only
     * move the focus to the window in the same leaf task.
     *
     * @param fromWindow The calling window that the focus is moved from.
     * @param direction The {@link android.view.View.FocusDirection} that the new focus should go.
     * @return {@code true} if the focus changes. Otherwise, {@code false}.
     */
    boolean moveFocusToAdjacentWindow(IWindow fromWindow, int direction);
}
