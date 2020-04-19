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

import java.util.List;

/**
 * System private per-application interface to the window manager.
 *
 * {@hide}
 */
interface IWindowSession {
    int addToDisplay(IWindow window, int seq, in WindowManager.LayoutParams attrs,
            in int viewVisibility, in int layerStackId, out Rect outFrame,
            out Rect outContentInsets, out Rect outStableInsets,
            out DisplayCutout.ParcelableWrapper displayCutout, out InputChannel outInputChannel,
            out InsetsState insetsState, out InsetsSourceControl[] activeControls);
    int addToDisplayAsUser(IWindow window, int seq, in WindowManager.LayoutParams attrs,
                in int viewVisibility, in int layerStackId, in int userId,
                out Rect outFrame, out Rect outContentInsets, out Rect outStableInsets,
                out DisplayCutout.ParcelableWrapper displayCutout, out InputChannel outInputChannel,
                out InsetsState insetsState, out InsetsSourceControl[] activeControls);
    int addToDisplayWithoutInputChannel(IWindow window, int seq, in WindowManager.LayoutParams attrs,
            in int viewVisibility, in int layerStackId, out Rect outContentInsets,
            out Rect outStableInsets, out InsetsState insetsState);
    @UnsupportedAppUsage
    void remove(IWindow window);

    /**
     * Change the parameters of a window.  You supply the
     * new parameters, it returns the new frame of the window on screen (the
     * position should be ignored) and surface of the window.  The surface
     * will be invalid if the window is currently hidden, else you can use it
     * to draw the window's contents.
     *
     * @param window The window being modified.
     * @param seq Ordering sequence number.
     * @param attrs If non-null, new attributes to apply to the window.
     * @param requestedWidth The width the window wants to be.
     * @param requestedHeight The height the window wants to be.
     * @param viewVisibility Window root view's visibility.
     * @param flags Request flags: {@link WindowManagerGlobal#RELAYOUT_INSETS_PENDING},
     * {@link WindowManagerGlobal#RELAYOUT_DEFER_SURFACE_DESTROY}.
     * @param frameNumber A frame number in which changes requested in this layout will be rendered.
     * @param outFrame Rect in which is placed the new position/size on
     * screen.
     * @param outContentInsets Rect in which is placed the offsets from
     * <var>outFrame</var> in which the content of the window should be
     * placed.  This can be used to modify the window layout to ensure its
     * contents are visible to the user, taking into account system windows
     * like the status bar or a soft keyboard.
     * @param outVisibleInsets Rect in which is placed the offsets from
     * <var>outFrame</var> in which the window is actually completely visible
     * to the user.  This can be used to temporarily scroll the window's
     * contents to make sure the user can see it.  This is different than
     * <var>outContentInsets</var> in that these insets change transiently,
     * so complex relayout of the window should not happen based on them.
     * @param outOutsets Rect in which is placed the dead area of the screen that we would like to
     * treat as real display. Example of such area is a chin in some models of wearable devices.
     * @param outBackdropFrame Rect which is used draw the resizing background during a resize
     * operation.
     * @param outMergedConfiguration New config container that holds global, override and merged
     * config for window, if it is now becoming visible and the merged configuration has changed
     * since it was last displayed.
     * @param outSurface Object in which is placed the new display surface.
     * @param insetsState The current insets state in the system.
     * @param outSurfaceSize The width and height of the surface control
     * @param outBlastSurfaceControl A BLAST SurfaceControl allocated by the WindowManager
     * the SurfaceControl willl be managed by the client side, but the WindowManager
     * may use it as a deferTransaction barrier.
     *
     * @return int Result flags: {@link WindowManagerGlobal#RELAYOUT_SHOW_FOCUS},
     * {@link WindowManagerGlobal#RELAYOUT_FIRST_TIME}.
     */
    int relayout(IWindow window, int seq, in WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewVisibility,
            int flags, long frameNumber, out Rect outFrame,
            out Rect outContentInsets, out Rect outVisibleInsets, out Rect outStableInsets,
            out Rect outBackdropFrame,
            out DisplayCutout.ParcelableWrapper displayCutout,
            out MergedConfiguration outMergedConfiguration, out SurfaceControl outSurfaceControl,
            out InsetsState insetsState, out InsetsSourceControl[] activeControls,
            out Point outSurfaceSize, out SurfaceControl outBlastSurfaceControl);

    /*
     * Notify the window manager that an application is relaunching and
     * windows should be prepared for replacement.
     *
     * @param appToken The application
     * @param childrenOnly Whether to only prepare child windows for replacement
     * (for example when main windows are being reused via preservation).
     */
    void prepareToReplaceWindows(IBinder appToken, boolean childrenOnly);

    /**
     * Called by a client to report that it ran out of graphics memory.
     */
    boolean outOfMemory(IWindow window);

    /**
     * Give the window manager a hint of the part of the window that is
     * completely transparent, allowing it to work with the surface flinger
     * to optimize compositing of this part of the window.
     */
    @UnsupportedAppUsage
    void setTransparentRegion(IWindow window, in Region region);

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
    void setInsets(IWindow window, int touchableInsets, in Rect contentInsets,
            in Rect visibleInsets, in Region touchableRegion);

    /**
     * Return the current display size in which the window is being laid out,
     * accounting for screen decorations around it.
     */
    void getDisplayFrame(IWindow window, out Rect outDisplayFrame);

    /**
     * Called when the client has finished drawing the surface, if needed.
     *
     * @param postDrawTransaction transaction filled by the client that can be
     * used to synchronize any post draw transactions with the server. Transaction
     * is null if there is no sync required.
     */
    @UnsupportedAppUsage
    void finishDrawing(IWindow window, in SurfaceControl.Transaction postDrawTransaction);

    @UnsupportedAppUsage
    void setInTouchMode(boolean showFocus);
    @UnsupportedAppUsage
    boolean getInTouchMode();

    @UnsupportedAppUsage
    boolean performHapticFeedback(int effectId, boolean always);

    /**
     * Initiate the drag operation itself
     *
     * @param window Window which initiates drag operation.
     * @param flags See {@code View#startDragAndDrop}
     * @param surface Surface containing drag shadow image
     * @param touchSource See {@code InputDevice#getSource()}
     * @param touchX X coordinate of last touch point
     * @param touchY Y coordinate of last touch point
     * @param thumbCenterX X coordinate for the position within the shadow image that should be
     *         underneath the touch point during the drag and drop operation.
     * @param thumbCenterY Y coordinate for the position within the shadow image that should be
     *         underneath the touch point during the drag and drop operation.
     * @param data Data transferred by drag and drop
     * @return Token of drag operation which will be passed to cancelDragAndDrop.
     */
    @UnsupportedAppUsage
    IBinder performDrag(IWindow window, int flags, in SurfaceControl surface, int touchSource,
            float touchX, float touchY, float thumbCenterX, float thumbCenterY, in ClipData data);

    /**
     * Report the result of a drop action targeted to the given window.
     * consumed is 'true' when the drop was accepted by a valid recipient,
     * 'false' otherwise.
     */
    void reportDropResult(IWindow window, boolean consumed);

    /**
     * Cancel the current drag operation.
     * skipAnimation is 'true' when it should skip the drag cancel animation which brings the drag
     * shadow image back to the drag start position.
     */
    void cancelDragAndDrop(IBinder dragToken, boolean skipAnimation);

    /**
     * Tell the OS that we've just dragged into a View that is willing to accept the drop
     */
    void dragRecipientEntered(IWindow window);

    /**
     * Tell the OS that we've just dragged *off* of a View that was willing to accept the drop
     */
    void dragRecipientExited(IWindow window);

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
    void setShouldZoomOutWallpaper(IBinder windowToken, boolean shouldZoom);

    @UnsupportedAppUsage
    void wallpaperOffsetsComplete(IBinder window);

    /**
     * Apply a raw offset to the wallpaper service when shown behind this window.
     */
    void setWallpaperDisplayOffset(IBinder windowToken, int x, int y);

    Bundle sendWallpaperCommand(IBinder window, String action, int x, int y,
            int z, in Bundle extras, boolean sync);

    @UnsupportedAppUsage
    void wallpaperCommandComplete(IBinder window, in Bundle result);

    /**
     * Notifies that a rectangle on the screen has been requested.
     */
    void onRectangleOnScreenRequested(IBinder token, in Rect rectangle);

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

    void finishMovingTask(IWindow window);

    void updatePointerIcon(IWindow window);

    /**
     * Reparent the top layers for a display to the requested SurfaceControl. The display that is
     * going to be re-parented (the displayId passed in) needs to have been created by the same
     * process that is requesting the re-parent. This is to ensure clients can't just re-parent
     * display content info to any SurfaceControl, as this would be a security issue.
     *
     * @param window The window which owns the SurfaceControl. This indicates the z-order of the
     *               windows of this display against the windows on the parent display.
     * @param sc The SurfaceControl that the top level layers for the display should be re-parented
     *           to.
     * @param displayId The id of the display to be re-parented.
     */
    void reparentDisplayContent(IWindow window, in SurfaceControl sc, int displayId);

    /**
     * Update the location of a child display in its parent window. This enables windows in the
     * child display to compute the global transformation matrix.
     *
     * @param window The parent window of the display.
     * @param x The x coordinate in the parent window.
     * @param y The y coordinate in the parent window.
     * @param displayId The id of the display to be notified.
     */
    void updateDisplayContentLocation(IWindow window, int x, int y, int displayId);

    /**
     * Update a tap exclude region identified by provided id in the window. Touches on this region
     * will neither be dispatched to this window nor change the focus to this window. Passing an
     * invalid region will remove the area from the exclude region of this window.
     */
    void updateTapExcludeRegion(IWindow window, in Region region);

    /**
     * Called when the client has changed the local insets state, and now the server should reflect
     * that new state.
     */
    oneway void insetsModified(IWindow window, in InsetsState state);

    /**
     * Called when the system gesture exclusion has changed.
     */
    oneway void reportSystemGestureExclusionChanged(IWindow window, in List<Rect> exclusionRects);

    /**
    * Request the server to call setInputWindowInfo on a given Surface, and return
    * an input channel where the client can receive input.
    */
    void grantInputChannel(int displayId, in SurfaceControl surface, in IWindow window,
            in IBinder hostInputToken, int flags, out InputChannel outInputChannel);

    /**
     * Update the flags on an input channel associated with a particular surface.
     */
    void updateInputChannel(in IBinder channelToken, int displayId, in SurfaceControl surface,
            int flags);
}
