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

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.MergedConfiguration;
import android.view.DisplayCutout;
import android.view.DragEvent;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.IScrollCaptureResponseListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.ImeTracker;
import android.window.ActivityWindowInfo;
import android.window.ClientWindowFrames;

import com.android.internal.os.IResultReceiver;

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

    /**
     * Please dispatch through WindowStateResizeItem instead of directly calling this method from
     * the system server.
     */
    void resized(in ClientWindowFrames frames, boolean reportDraw,
            in MergedConfiguration newMergedConfiguration, in InsetsState insetsState,
            boolean forceLayout, boolean alwaysConsumeSystemBars, int displayId,
            int syncSeqId, boolean dragResizing,
            in @nullable ActivityWindowInfo activityWindowInfo);

    /**
     * Called when this window retrieved control over a specified set of insets sources.
     */
    void insetsControlChanged(in InsetsState insetsState, in InsetsSourceControl[] activeControls);

    /**
     * Called when a set of insets source window should be shown by policy.
     *
     * @param types internal insets types (WindowInsets.Type.InsetsType) to show
     * @param fromIme true if this request originated from IME (InputMethodService).
     * @param statsToken the token tracking the current IME request or {@code null} otherwise.
     */
    void showInsets(int types, boolean fromIme, in @nullable ImeTracker.Token statsToken);

    /**
     * Called when a set of insets source window should be hidden by policy.
     *
     * @param types internal insets types (WindowInsets.Type.InsetsType) to hide
     * @param fromIme true if this request originated from IME (InputMethodService).
     * @param statsToken the token tracking the current IME request or {@code null} otherwise.
     */
    void hideInsets(int types, boolean fromIme, in @nullable ImeTracker.Token statsToken);

    void moved(int newX, int newY);
    void dispatchAppVisibility(boolean visible);
    void dispatchGetNewSurface();

    void closeSystemDialogs(String reason);

    /**
     * Called for wallpaper windows when their offsets or zoom level change.
     */
    void dispatchWallpaperOffsets(float x, float y, float xStep, float yStep, float zoom, boolean sync);

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
     * Called for non-application windows when the enter animation has completed.
     */
    void dispatchWindowShown();

    /**
     * Called when Keyboard Shortcuts are requested for the window.
     */
    void requestAppKeyboardShortcuts(IResultReceiver receiver, int deviceId);

    /**
     * Called when Scroll Capture support is requested for a window.
     *
     * @param callbacks to receive responses
     */
    void requestScrollCapture(in IScrollCaptureResponseListener callbacks);

    /**
     * Dump the details of a window.
     */
    void dumpWindow(in ParcelFileDescriptor pfd);
}
