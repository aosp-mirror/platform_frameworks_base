/* //device/java/android/android/view/IWindow.aidl
**
** Copyright 2007, The Android Open Source Project
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

import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.DisplayCutout;

import com.android.internal.os.IResultReceiver;
import android.util.MergedConfiguration;

/**
 * API back to a client window that the Window Manager uses to inform it of
 * interesting things happening.
 *
 * {@hide}
 */
oneway interface IWindow {
    /**
     * ===== NOTICE =====
     * The first method must remain the first method. Scripts
     * and tools rely on their transaction number to work properly.
     */

    /**
     * Invoked by the view server to tell a window to execute the specified
     * command. Any response from the receiver must be sent through the
     * specified file descriptor.
     */
    void executeCommand(String command, String parameters, in ParcelFileDescriptor descriptor);

    void resized(in Rect frame, in Rect overscanInsets, in Rect contentInsets,
            in Rect visibleInsets, in Rect stableInsets, in Rect outsets, boolean reportDraw,
            in MergedConfiguration newMergedConfiguration, in Rect backDropFrame,
            boolean forceLayout, boolean alwaysConsumeNavBar, int displayId,
            in DisplayCutout.ParcelableWrapper displayCutout);
    void moved(int newX, int newY);
    void dispatchAppVisibility(boolean visible);
    void dispatchGetNewSurface();

    /**
     * Tell the window that it is either gaining or losing focus.
     *
     * @param hasFocus       {@code true} if window has focus, {@code false} otherwise.
     * @param inTouchMode    {@code true} if screen is in touch mode, {@code false} otherwise.
     * @param reportToClient {@code true} when need to report to child view with
     *                       {@link View#onWindowFocusChanged(boolean)}, {@code false} otherwise.
     * <p>
     * Note: In the previous design, there is only one window focus state tracked by
     * WindowManagerService.
     * For multi-display, the window focus state is tracked by each display independently.
     * <p>
     * It will introduce a problem if the window was already focused on one display and then
     * switched to another display, since the window focus state on each display is independent,
     * there is no global window focus state in WindowManagerService, so the window focus state of
     * the former display remains unchanged.
     * <p>
     * When switched back to former display, some flows that rely on the global window focus state
     * in view root will be missed due to the window focus state remaining unchanged.
     * (i.e: Showing single IME window when switching between displays.)
     * <p>
     * To solve the problem, WindowManagerService tracks the top focused display change and then
     * callbacks to the client via this method to make sure that the client side will request the
     * IME on the top focused display, and then set {@param reportToClient} as {@code false} to
     * ignore reporting to the application, since its focus remains unchanged on its display.
     *
     */
    void windowFocusChanged(boolean hasFocus, boolean inTouchMode, boolean reportToClient);
    
    void closeSystemDialogs(String reason);
    
    /**
     * Called for wallpaper windows when their offsets change.
     */
    void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, boolean sync);
    
    void dispatchWallpaperCommand(String action, int x, int y,
            int z, in Bundle extras, boolean sync);

    /**
     * Drag/drop events
     */
    void dispatchDragEvent(in DragEvent event);

    /**
     * Pointer icon events
     */
    void updatePointerIcon(float x, float y);

    /**
     * System chrome visibility changes
     */
    void dispatchSystemUiVisibilityChanged(int seq, int globalVisibility,
            int localValue, int localChanges);

    /**
     * Called for non-application windows when the enter animation has completed.
     */
    void dispatchWindowShown();

    /**
     * Called when Keyboard Shortcuts are requested for the window.
     */
    void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId);

    /**
     * Tell the window that it is either gaining or losing pointer capture.
     */
    void dispatchPointerCaptureChanged(boolean hasCapture);
}
