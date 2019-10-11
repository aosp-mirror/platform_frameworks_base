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
import static com.android.server.wm.WindowFramesProto.CONTENT_INSETS;
import static com.android.server.wm.WindowFramesProto.CUTOUT;
import static com.android.server.wm.WindowFramesProto.DECOR_FRAME;
import static com.android.server.wm.WindowFramesProto.DISPLAY_FRAME;
import static com.android.server.wm.WindowFramesProto.FRAME;
import static com.android.server.wm.WindowFramesProto.OUTSETS;
import static com.android.server.wm.WindowFramesProto.OUTSET_FRAME;
import static com.android.server.wm.WindowFramesProto.OVERSCAN_FRAME;
import static com.android.server.wm.WindowFramesProto.OVERSCAN_INSETS;
import static com.android.server.wm.WindowFramesProto.PARENT_FRAME;
import static com.android.server.wm.WindowFramesProto.STABLE_INSETS;
import static com.android.server.wm.WindowFramesProto.VISIBLE_FRAME;
import static com.android.server.wm.WindowFramesProto.VISIBLE_INSETS;

import android.annotation.NonNull;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayCutout;

import com.android.server.wm.utils.InsetUtils;
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

    private boolean mFrameSizeChanged = false;

    // Frame that is scaled to the application's coordinate space when in
    // screen size compatibility mode.
    final Rect mCompatFrame = new Rect();

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
    private WmDisplayCutout mLastDisplayCutout = WmDisplayCutout.NO_CUTOUT;

    private boolean mDisplayCutoutChanged;

    /**
     * Insets that determine the area covered by the display overscan region.  These are in the
     * application's coordinate space (without compatibility scale applied).
     */
    final Rect mOverscanInsets = new Rect();
    final Rect mLastOverscanInsets = new Rect();
    private boolean mOverscanInsetsChanged;

    /**
     * Insets that determine the area covered by the stable system windows.  These are in the
     * application's coordinate space (without compatibility scale applied).
     */
    final Rect mStableInsets = new Rect();
    final Rect mLastStableInsets = new Rect();
    private boolean mStableInsetsChanged;

    /**
     * Outsets determine the area outside of the surface where we want to pretend that it's possible
     * to draw anyway.
     */
    final Rect mOutsets = new Rect();
    final Rect mLastOutsets = new Rect();
    private boolean mOutsetsChanged = false;

    /**
     * Insets that determine the actually visible area.  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mVisibleInsets = new Rect();
    final Rect mLastVisibleInsets = new Rect();
    private boolean mVisibleInsetsChanged;

    /**
     * Insets that are covered by system windows (such as the status bar) and
     * transient docking windows (such as the IME).  These are in the application's
     * coordinate space (without compatibility scale applied).
     */
    final Rect mContentInsets = new Rect();
    final Rect mLastContentInsets = new Rect();
    private boolean mContentInsetsChanged;

    private final Rect mTmpRect = new Rect();

    private boolean mHasOutsets;

    private boolean mContentChanged;

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
    private boolean didFrameSizeChange() {
        return (mLastFrame.width() != mFrame.width()) || (mLastFrame.height() != mFrame.height());
    }

    /**
     * Calculates the outsets for this windowFrame. The outsets are calculated by the area between
     * the {@link #mOutsetFrame} and the {@link #mContentFrame}. If there are no outsets, then
     * {@link #mOutsets} is set to empty.
     */
    void calculateOutsets() {
        if (mHasOutsets) {
            InsetUtils.insetsBetweenFrames(mOutsetFrame, mContentFrame, mOutsets);
        }
    }

    /**
     * Calculate the insets for the type
     * {@link android.view.WindowManager.LayoutParams#TYPE_DOCK_DIVIDER}
     *
     * @param cutoutInsets The insets for the cutout.
     */
    void calculateDockedDividerInsets(Rect cutoutInsets) {
        // For the docked divider, we calculate the stable insets like a full-screen window
        // so it can use it to calculate the snap positions.
        mTmpRect.set(mDisplayFrame);
        mTmpRect.inset(cutoutInsets);
        mTmpRect.intersectUnchecked(mStableFrame);
        InsetUtils.insetsBetweenFrames(mDisplayFrame, mTmpRect, mStableInsets);

        // The divider doesn't care about insets in any case, so set it to empty so we don't
        // trigger a relayout when moving it.
        mContentInsets.setEmpty();
        mVisibleInsets.setEmpty();
        mDisplayCutout = WmDisplayCutout.NO_CUTOUT;
    }

    /**
     * Calculate the insets for a window.
     *
     * @param windowsAreFloating    Whether the window is in a floating task such as pinned or
     *                              freeform
     * @param inFullscreenContainer Whether the window is in a container that takes up the screen's
     *                              entire space
     * @param windowBounds          The bounds for the window
     */
    void calculateInsets(boolean windowsAreFloating, boolean inFullscreenContainer,
            Rect windowBounds) {
        // Override right and/or bottom insets in case if the frame doesn't fit the screen in
        // non-fullscreen mode.
        boolean overrideRightInset = !windowsAreFloating && !inFullscreenContainer
                && mFrame.right > windowBounds.right;
        boolean overrideBottomInset = !windowsAreFloating && !inFullscreenContainer
                && mFrame.bottom > windowBounds.bottom;

        mTmpRect.set(mFrame.left, mFrame.top,
                overrideRightInset ? windowBounds.right : mFrame.right,
                overrideBottomInset ? windowBounds.bottom : mFrame.bottom);

        InsetUtils.insetsBetweenFrames(mTmpRect, mContentFrame, mContentInsets);
        InsetUtils.insetsBetweenFrames(mTmpRect, mVisibleFrame, mVisibleInsets);
        InsetUtils.insetsBetweenFrames(mTmpRect, mStableFrame, mStableInsets);
    }

    /**
     * Scales all the insets by a specific amount.
     *
     * @param scale The amount to scale the insets by.
     */
    void scaleInsets(float scale) {
        mOverscanInsets.scale(scale);
        mContentInsets.scale(scale);
        mVisibleInsets.scale(scale);
        mStableInsets.scale(scale);
        mOutsets.scale(scale);
    }

    void offsetFrames(int layoutXDiff, int layoutYDiff) {
        mFrame.offset(layoutXDiff, layoutYDiff);
        mContentFrame.offset(layoutXDiff, layoutYDiff);
        mVisibleFrame.offset(layoutXDiff, layoutYDiff);
        mStableFrame.offset(layoutXDiff, layoutYDiff);
    }

    /**
     * Updates info about whether the size of the window has changed since last reported.
     *
     * @return true if info about size has changed since last reported.
     */
    boolean setReportResizeHints() {
        mOverscanInsetsChanged |= !mLastOverscanInsets.equals(mOverscanInsets);
        mContentInsetsChanged |= !mLastContentInsets.equals(mContentInsets);
        mVisibleInsetsChanged |= !mLastVisibleInsets.equals(mVisibleInsets);
        mStableInsetsChanged |= !mLastStableInsets.equals(mStableInsets);
        mOutsetsChanged |= !mLastOutsets.equals(mOutsets);
        mFrameSizeChanged |= didFrameSizeChange();
        mDisplayCutoutChanged |= !mLastDisplayCutout.equals(mDisplayCutout);
        return mOverscanInsetsChanged || mContentInsetsChanged || mVisibleInsetsChanged
                || mStableInsetsChanged || mOutsetsChanged || mFrameSizeChanged
                || mDisplayCutoutChanged;
    }

    /**
     * Resets the insets changed flags so they're all set to false again. This should be called
     * after the insets are reported to client.
     */
    void resetInsetsChanged() {
        mOverscanInsetsChanged = false;
        mContentInsetsChanged = false;
        mVisibleInsetsChanged = false;
        mStableInsetsChanged = false;
        mOutsetsChanged = false;
        mFrameSizeChanged = false;
        mDisplayCutoutChanged = false;
    }

    /**
     * Copy over inset values as the last insets that were sent to the client.
     */
    void updateLastInsetValues() {
        mLastOverscanInsets.set(mOverscanInsets);
        mLastContentInsets.set(mContentInsets);
        mLastVisibleInsets.set(mVisibleInsets);
        mLastStableInsets.set(mStableInsets);
        mLastOutsets.set(mOutsets);
        mLastDisplayCutout = mDisplayCutout;
    }

    /**
     * Sets the last content insets as (-1, -1, -1, -1) to force the next layout pass to update
     * the client.
     */
    void resetLastContentInsets() {
        mLastContentInsets.set(-1, -1, -1, -1);
    }

    /**
     * Sets whether the frame has outsets.
     */
    public void setHasOutsets(boolean hasOutsets) {
        if (mHasOutsets == hasOutsets) {
            return;
        }
        mHasOutsets = hasOutsets;
        if (!hasOutsets) {
            mOutsets.setEmpty();
        }
    }

    /**
     * Sets whether the content has changed. This means that either the size or parent frame has
     * changed.
     */
    public void setContentChanged(boolean contentChanged) {
        mContentChanged = contentChanged;
    }

    /**
     * @see #setContentChanged(boolean)
     */
    boolean hasContentChanged() {
        return mContentChanged;
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
        mContentInsets.writeToProto(proto, CONTENT_INSETS);
        mOverscanInsets.writeToProto(proto, OVERSCAN_INSETS);
        mVisibleInsets.writeToProto(proto, VISIBLE_INSETS);
        mStableInsets.writeToProto(proto, STABLE_INSETS);
        mOutsets.writeToProto(proto, OUTSETS);

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
        pw.print(prefix + "Cur insets: overscan=" + mOverscanInsets.toShortString(sTmpSB)
                + " content=" + mContentInsets.toShortString(sTmpSB)
                + " visible=" + mVisibleInsets.toShortString(sTmpSB)
                + " stable=" + mStableInsets.toShortString(sTmpSB)
                + " outsets=" + mOutsets.toShortString(sTmpSB));
        pw.println(prefix + "Lst insets: overscan=" + mLastOverscanInsets.toShortString(sTmpSB)
                + " content=" + mLastContentInsets.toShortString(sTmpSB)
                + " visible=" + mLastVisibleInsets.toShortString(sTmpSB)
                + " stable=" + mLastStableInsets.toShortString(sTmpSB)
                + " outset=" + mLastOutsets.toShortString(sTmpSB));
    }

    String getInsetsInfo() {
        return "ci=" + mContentInsets.toShortString()
                + " vi=" + mVisibleInsets.toShortString()
                + " si=" + mStableInsets.toShortString()
                + " of=" + mOutsets.toShortString();
    }

    String getInsetsChangedInfo() {
        return "contentInsetsChanged=" + mContentInsetsChanged
                + " " + mContentInsets.toShortString()
                + " visibleInsetsChanged=" + mVisibleInsetsChanged
                + " " + mVisibleInsets.toShortString()
                + " stableInsetsChanged=" + mStableInsetsChanged
                + " " + mStableInsets.toShortString()
                + " outsetsChanged=" + mOutsetsChanged
                + " " + mOutsets.toShortString()
                + " displayCutoutChanged=" + mDisplayCutoutChanged;
    }
}
