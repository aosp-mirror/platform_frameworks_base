/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.server.wm.WindowFramesProto.CONTAINING_FRAME;
import static com.android.server.wm.WindowFramesProto.CONTENT_FRAME;
import static com.android.server.wm.WindowFramesProto.CUTOUT;
import static com.android.server.wm.WindowFramesProto.DECOR_FRAME;
import static com.android.server.wm.WindowFramesProto.DISPLAY_FRAME;
import static com.android.server.wm.WindowFramesProto.FRAME;
import static com.android.server.wm.WindowFramesProto.OUTSET_FRAME;
import static com.android.server.wm.WindowFramesProto.OVERSCAN_FRAME;
import static com.android.server.wm.WindowFramesProto.PARENT_FRAME;
import static com.android.server.wm.WindowFramesProto.VISIBLE_FRAME;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;

import com.android.server.wm.utils.WmDisplayCutout;

import java.io.PrintWriter;

/**
 * Container class for all the window frames that affect how windows are laid out.
 *
 * TODO(b/111611553): Investigate which frames are still needed and which are duplicates
 */
public class WindowFrames {
    private static final StringBuilder sTmpSB = new StringBuilder();

    /**
     * In most cases, this is the area of the entire screen.
     *
     * TODO(b/111611553): The name is unclear and most likely should be swapped with
     * {@link #mDisplayFrame}
     * TODO(b/111611553): In some cases, it also includes top insets, like for IME. Determine
     * whether this is still necessary to do.
     */
    public final Rect mParentFrame = new Rect();

    /**
     * The entire screen area of the {@link TaskStack} this window is in. Usually equal to the
     * screen area of the device.
     *
     * TODO(b/111611553): The name is unclear and most likely should be swapped with
     * {@link #mParentFrame}
    */
    public final Rect mDisplayFrame = new Rect();

    /**
     * The region of the display frame that the display type supports displaying content on. This
     * is mostly a special case for TV where some displays don’t have the entire display usable.
     * {@link android.view.WindowManager.LayoutParams#FLAG_LAYOUT_IN_OVERSCAN} flag can be used to
     * allow window display contents to extend into the overscan region.
     */
    public final Rect mOverscanFrame = new Rect();

    /**
     * Legacy stuff. Generally equal to the content frame expect when the IME for older apps
     * displays hint text.
     */
    public final Rect mVisibleFrame = new Rect();

    /**
     * The area not occupied by the status and navigation bars. So, if both status and navigation
     * bars are visible, the decor frame is equal to the stable frame.
     */
    public final Rect mDecorFrame = new Rect();

    /**
     * Equal to the decor frame if the IME (e.g. keyboard) is not present. Equal to the decor frame
     * minus the area occupied by the IME if the IME is present.
     */
    public final Rect mContentFrame = new Rect();

    /**
     * The display frame minus the stable insets. This value is always constant regardless of if
     * the status bar or navigation bar is visible.
     */
    public final Rect mStableFrame = new Rect();

    /**
     * Frame that includes dead area outside of the surface but where we want to pretend that it's
     * possible to draw.
     */
    final public Rect mOutsetFrame = new Rect();

    /**
     * Similar to {@link #mDisplayFrame}
     *
     * TODO: Why is this different than mDisplayFrame
     */
    final Rect mContainingFrame = new Rect();

    /**
     * "Real" frame that the application sees, in display coordinate space.
     */
    final Rect mFrame = new Rect();

    /**
     * The last real frame that was reported to the client.
     */
    final Rect mLastFrame = new Rect();

    /**
     * Whether the parent frame would have been different if there was no display cutout.
     */
    private boolean mParentFrameWasClippedByDisplayCutout;

    /**
     * Part of the display that has been cut away. See {@link DisplayCutout}.
     */
    WmDisplayCutout mDisplayCutout = WmDisplayCutout.NO_CUTOUT;

    /**
     * The last cutout that has been reported to the client.
     */
    WmDisplayCutout mLastDisplayCutout = WmDisplayCutout.NO_CUTOUT;

    public WindowFrames() {
    }

    public WindowFrames(Rect parentFrame, Rect displayFrame, Rect overscanFrame, Rect contentFrame,
            Rect visibleFrame, Rect decorFrame, Rect stableFrame, Rect outsetFrame) {
        setFrames(parentFrame, displayFrame, overscanFrame, contentFrame, visibleFrame, decorFrame,
                stableFrame, outsetFrame);
    }

    public void setFrames(Rect parentFrame, Rect displayFrame, Rect overscanFrame,
            Rect contentFrame, Rect visibleFrame, Rect decorFrame, Rect stableFrame,
            Rect outsetFrame) {
        mParentFrame.set(parentFrame);
        mDisplayFrame.set(displayFrame);
        mOverscanFrame.set(overscanFrame);
        mContentFrame.set(contentFrame);
        mVisibleFrame.set(visibleFrame);
        mDecorFrame.set(decorFrame);
        mStableFrame.set(stableFrame);
        mOutsetFrame.set(outsetFrame);
    }

    public void setParentFrameWasClippedByDisplayCutout(
            boolean parentFrameWasClippedByDisplayCutout) {
        mParentFrameWasClippedByDisplayCutout = parentFrameWasClippedByDisplayCutout;
    }

    boolean parentFrameWasClippedByDisplayCutout() {
        return mParentFrameWasClippedByDisplayCutout;
    }

    public void setDisplayCutout(WmDisplayCutout displayCutout) {
        mDisplayCutout = displayCutout;
    }

    /**
     * @return true if the width or height has changed since last reported to the client.
     */
    boolean didFrameSizeChange() {
        return (mLastFrame.width() != mFrame.width()) || (mLastFrame.height() != mFrame.height());
    }

    /**
     * @return true if the display cutout has changed since last reported to the client.
     */
    boolean didDisplayCutoutChange() {
        return !mLastDisplayCutout.equals(mDisplayCutout);
    }

    public void writeToProto(@NonNull ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mParentFrame.writeToProto(proto, PARENT_FRAME);
        mContentFrame.writeToProto(proto, CONTENT_FRAME);
        mDisplayFrame.writeToProto(proto, DISPLAY_FRAME);
        mOverscanFrame.writeToProto(proto, OVERSCAN_FRAME);
        mVisibleFrame.writeToProto(proto, VISIBLE_FRAME);
        mDecorFrame.writeToProto(proto, DECOR_FRAME);
        mOutsetFrame.writeToProto(proto, OUTSET_FRAME);
        mContainingFrame.writeToProto(proto, CONTAINING_FRAME);
        mFrame.writeToProto(proto, FRAME);
        mDisplayCutout.getDisplayCutout().writeToProto(proto, CUTOUT);
        proto.end(token);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Frames: containing="
                + mContainingFrame.toShortString(sTmpSB)
                + " parent=" + mParentFrame.toShortString(sTmpSB));
        pw.println(prefix + "    display=" + mDisplayFrame.toShortString(sTmpSB)
                + " overscan=" + mOverscanFrame.toShortString(sTmpSB));
        pw.println(prefix + "    content=" + mContentFrame.toShortString(sTmpSB)
                + " visible=" + mVisibleFrame.toShortString(sTmpSB));
        pw.println(prefix + "    decor=" + mDecorFrame.toShortString(sTmpSB));
        pw.println(prefix + "    outset=" + mOutsetFrame.toShortString(sTmpSB));
        pw.println(prefix + "mFrame=" + mFrame.toShortString(sTmpSB)
                + " last=" + mLastFrame.toShortString(sTmpSB));
        pw.println(prefix + " cutout=" + mDisplayCutout.getDisplayCutout()
                + " last=" + mLastDisplayCutout.getDisplayCutout());
    }
}
