/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import static android.graphics.Color.WHITE;
import static android.graphics.Color.alpha;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND;
import static android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
import static android.view.WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES;
import static android.view.WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import static com.android.internal.policy.DecorView.NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.STATUS_BAR_COLOR_VIEW_ATTRIBUTES;
import static com.android.internal.policy.DecorView.getNavigationBarRect;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.util.Log;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.DecorView;

/**
 * Utils class to help draw a snapshot on a surface.
 * @hide
 */
public class SnapshotDrawerUtils {
    private static final String TAG = "SnapshotDrawerUtils";

    /**
     * When creating the starting window, we use the exact same layout flags such that we end up
     * with a window with the exact same dimensions etc. However, these flags are not used in layout
     * and might cause other side effects so we exclude them.
     */
    static final int FLAG_INHERIT_EXCLUDES = FLAG_NOT_FOCUSABLE
            | FLAG_NOT_TOUCHABLE
            | FLAG_NOT_TOUCH_MODAL
            | FLAG_ALT_FOCUSABLE_IM
            | FLAG_NOT_FOCUSABLE
            | FLAG_HARDWARE_ACCELERATED
            | FLAG_IGNORE_CHEEK_PRESSES
            | FLAG_LOCAL_FOCUS_MODE
            | FLAG_SLIPPERY
            | FLAG_WATCH_OUTSIDE_TOUCH
            | FLAG_SPLIT_TOUCH
            | FLAG_SCALED
            | FLAG_SECURE
            | FLAG_DIM_BEHIND;

    private static final RectF sTmpSnapshotSize = new RectF();
    private static final RectF sTmpDstFrame = new RectF();

    private static final Matrix sSnapshotMatrix = new Matrix();
    private static final float[] sTmpFloat9 = new float[9];
    private static final Paint sBackgroundPaint = new Paint();

    /**
     * The internal object to hold the surface and drawing on it.
     */
    @VisibleForTesting
    public static class SnapshotSurface {
        private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
        private final SurfaceControl mRootSurface;
        private final TaskSnapshot mSnapshot;
        private final CharSequence mTitle;

        private SystemBarBackgroundPainter mSystemBarBackgroundPainter;
        private final Rect mTaskBounds;
        private final Rect mFrame = new Rect();
        private final Rect mSystemBarInsets = new Rect();
        private boolean mSizeMismatch;

        public SnapshotSurface(SurfaceControl rootSurface, TaskSnapshot snapshot,
                CharSequence title,
                Rect taskBounds) {
            mRootSurface = rootSurface;
            mSnapshot = snapshot;
            mTitle = title;
            mTaskBounds = taskBounds;
        }

        /**
         * Initiate system bar painter to draw the system bar background.
         */
        void initiateSystemBarPainter(int windowFlags, int windowPrivateFlags,
                int appearance, ActivityManager.TaskDescription taskDescription,
                @WindowInsets.Type.InsetsType int requestedVisibleTypes) {
            mSystemBarBackgroundPainter = new SystemBarBackgroundPainter(windowFlags,
                    windowPrivateFlags, appearance, taskDescription, 1f, requestedVisibleTypes);
            int backgroundColor = taskDescription.getBackgroundColor();
            sBackgroundPaint.setColor(backgroundColor != 0 ? backgroundColor : WHITE);
        }

        /**
         * Set frame size.
         */
        void setFrames(Rect frame, Rect systemBarInsets) {
            mFrame.set(frame);
            mSystemBarInsets.set(systemBarInsets);
            final HardwareBuffer snapshot = mSnapshot.getHardwareBuffer();
            mSizeMismatch = (mFrame.width() != snapshot.getWidth()
                    || mFrame.height() != snapshot.getHeight());
            mSystemBarBackgroundPainter.setInsets(systemBarInsets);
        }

        private void drawSnapshot(boolean releaseAfterDraw) {
            Log.v(TAG, "Drawing snapshot surface sizeMismatch=" + mSizeMismatch);
            if (mSizeMismatch) {
                // The dimensions of the buffer and the window don't match, so attaching the buffer
                // will fail. Better create a child window with the exact dimensions and fill the
                // parent window with the background color!
                drawSizeMismatchSnapshot();
            } else {
                drawSizeMatchSnapshot();
            }

            // In case window manager leaks us, make sure we don't retain the snapshot.
            if (mSnapshot.getHardwareBuffer() != null) {
                mSnapshot.getHardwareBuffer().close();
            }
            if (releaseAfterDraw) {
                mRootSurface.release();
            }
        }

        private void drawSizeMatchSnapshot() {
            mTransaction.setBuffer(mRootSurface, mSnapshot.getHardwareBuffer())
                    .setColorSpace(mRootSurface, mSnapshot.getColorSpace())
                    .apply();
        }

        private void drawSizeMismatchSnapshot() {
            final HardwareBuffer buffer = mSnapshot.getHardwareBuffer();
            final SurfaceSession session = new SurfaceSession();

            // We consider nearly matched dimensions as there can be rounding errors and the user
            // won't notice very minute differences from scaling one dimension more than the other
            boolean aspectRatioMismatch = !isAspectRatioMatch(mFrame, mSnapshot);

            // Keep a reference to it such that it doesn't get destroyed when finalized.
            SurfaceControl childSurfaceControl = new SurfaceControl.Builder(session)
                    .setName(mTitle + " - task-snapshot-surface")
                    .setBLASTLayer()
                    .setFormat(buffer.getFormat())
                    .setParent(mRootSurface)
                    .setCallsite("TaskSnapshotWindow.drawSizeMismatchSnapshot")
                    .build();

            final Rect frame;
            // We can just show the surface here as it will still be hidden as the parent is
            // still hidden.
            mTransaction.show(childSurfaceControl);
            if (aspectRatioMismatch) {
                Rect crop = null;
                final Rect letterboxInsets = mSnapshot.getLetterboxInsets();
                if (letterboxInsets.left != 0 || letterboxInsets.top != 0
                        || letterboxInsets.right != 0 || letterboxInsets.bottom != 0) {
                    // Clip off letterbox.
                    crop = calculateSnapshotCrop(letterboxInsets);
                    // If the snapshot can cover the frame, then no need to draw background.
                    aspectRatioMismatch = !isAspectRatioMatch(mFrame, crop);
                }
                // if letterbox doesn't match window frame, try crop by content insets
                if (aspectRatioMismatch) {
                    // Clip off ugly navigation bar.
                    crop = calculateSnapshotCrop(mSnapshot.getContentInsets());
                }
                frame = calculateSnapshotFrame(crop);
                mTransaction.setWindowCrop(childSurfaceControl, crop);
                mTransaction.setPosition(childSurfaceControl, frame.left, frame.top);
                sTmpSnapshotSize.set(crop);
                sTmpDstFrame.set(frame);
            } else {
                frame = null;
                sTmpSnapshotSize.set(0, 0, buffer.getWidth(), buffer.getHeight());
                sTmpDstFrame.set(mFrame);
                sTmpDstFrame.offsetTo(0, 0);
            }

            // Scale the mismatch dimensions to fill the task bounds
            sSnapshotMatrix.setRectToRect(sTmpSnapshotSize, sTmpDstFrame, Matrix.ScaleToFit.FILL);
            mTransaction.setMatrix(childSurfaceControl, sSnapshotMatrix, sTmpFloat9);
            mTransaction.setColorSpace(childSurfaceControl, mSnapshot.getColorSpace());
            mTransaction.setBuffer(childSurfaceControl, mSnapshot.getHardwareBuffer());

            if (aspectRatioMismatch) {
                GraphicBuffer background = GraphicBuffer.create(mFrame.width(), mFrame.height(),
                        PixelFormat.RGBA_8888,
                        GraphicBuffer.USAGE_HW_TEXTURE | GraphicBuffer.USAGE_HW_COMPOSER
                                | GraphicBuffer.USAGE_SW_WRITE_RARELY);
                final Canvas c = background != null ? background.lockCanvas() : null;
                if (c == null) {
                    Log.e(TAG, "Unable to draw snapshot: failed to allocate graphic buffer for "
                            + mTitle);
                    mTransaction.clear();
                    childSurfaceControl.release();
                    return;
                }
                drawBackgroundAndBars(c, frame);
                background.unlockCanvasAndPost(c);
                mTransaction.setBuffer(mRootSurface,
                        HardwareBuffer.createFromGraphicBuffer(background));
            }
            mTransaction.apply();
            childSurfaceControl.release();
        }

        /**
         * Calculates the snapshot crop in snapshot coordinate space.
         * @param insets Content insets or Letterbox insets
         * @return crop rect in snapshot coordinate space.
         */
        Rect calculateSnapshotCrop(@NonNull Rect insets) {
            final Rect rect = new Rect();
            final HardwareBuffer snapshot = mSnapshot.getHardwareBuffer();
            rect.set(0, 0, snapshot.getWidth(), snapshot.getHeight());

            final float scaleX = (float) snapshot.getWidth() / mSnapshot.getTaskSize().x;
            final float scaleY = (float) snapshot.getHeight() / mSnapshot.getTaskSize().y;

            // Let's remove all system decorations except the status bar, but only if the task is at
            // the very top of the screen.
            final boolean isTop = mTaskBounds.top == 0 && mFrame.top == 0;
            rect.inset((int) (insets.left * scaleX),
                    isTop ? 0 : (int) (insets.top * scaleY),
                    (int) (insets.right * scaleX),
                    (int) (insets.bottom * scaleY));
            return rect;
        }

        /**
         * Calculates the snapshot frame in window coordinate space from crop.
         *
         * @param crop rect that is in snapshot coordinate space.
         */
        Rect calculateSnapshotFrame(Rect crop) {
            final HardwareBuffer snapshot = mSnapshot.getHardwareBuffer();
            final float scaleX = (float) snapshot.getWidth() / mSnapshot.getTaskSize().x;
            final float scaleY = (float) snapshot.getHeight() / mSnapshot.getTaskSize().y;

            // Rescale the frame from snapshot to window coordinate space
            final Rect frame = new Rect(0, 0,
                    (int) (crop.width() / scaleX + 0.5f),
                    (int) (crop.height() / scaleY + 0.5f)
            );

            // However, we also need to make space for the navigation bar on the left side.
            frame.offset(mSystemBarInsets.left, 0);
            return frame;
        }

        /**
         * Draw status bar and navigation bar background.
         */
        void drawBackgroundAndBars(Canvas c, Rect frame) {
            final int statusBarHeight = mSystemBarBackgroundPainter.getStatusBarColorViewHeight();
            final boolean fillHorizontally = c.getWidth() > frame.right;
            final boolean fillVertically = c.getHeight() > frame.bottom;
            if (fillHorizontally) {
                c.drawRect(frame.right, alpha(mSystemBarBackgroundPainter.mStatusBarColor) == 0xFF
                        ? statusBarHeight : 0, c.getWidth(), fillVertically
                        ? frame.bottom : c.getHeight(), sBackgroundPaint);
            }
            if (fillVertically) {
                c.drawRect(0, frame.bottom, c.getWidth(), c.getHeight(), sBackgroundPaint);
            }
            mSystemBarBackgroundPainter.drawDecors(c, frame);
        }

        /**
         * Ask system bar background painter to draw status bar background.
         *
         */
        void drawStatusBarBackground(Canvas c, @Nullable Rect alreadyDrawnFrame) {
            mSystemBarBackgroundPainter.drawStatusBarBackground(c, alreadyDrawnFrame,
                    mSystemBarBackgroundPainter.getStatusBarColorViewHeight());
        }

        /**
         * Ask system bar background painter to draw navigation bar background.
         *
         */
        void drawNavigationBarBackground(Canvas c) {
            mSystemBarBackgroundPainter.drawNavigationBarBackground(c);
        }
    }

    /**
     * @return true if the aspect ratio match between a frame and a snapshot buffer.
     */
    public static boolean isAspectRatioMatch(Rect frame, TaskSnapshot snapshot) {
        if (frame.isEmpty()) {
            return false;
        }
        final HardwareBuffer buffer = snapshot.getHardwareBuffer();
        return Math.abs(
                ((float) buffer.getWidth() / buffer.getHeight())
                        - ((float) frame.width() / frame.height())) <= 0.01f;
    }

    private static boolean isAspectRatioMatch(Rect frame1, Rect frame2) {
        if (frame1.isEmpty() || frame2.isEmpty()) {
            return false;
        }
        return Math.abs(
                ((float) frame2.width() / frame2.height())
                        - ((float) frame1.width() / frame1.height())) <= 0.01f;
    }

    /**
     * Get or create a TaskDescription from a RunningTaskInfo.
     */
    public static ActivityManager.TaskDescription getOrCreateTaskDescription(
            ActivityManager.RunningTaskInfo runningTaskInfo) {
        final ActivityManager.TaskDescription taskDescription;
        if (runningTaskInfo.taskDescription != null) {
            taskDescription = runningTaskInfo.taskDescription;
        } else {
            taskDescription = new ActivityManager.TaskDescription();
            taskDescription.setBackgroundColor(WHITE);
        }
        return taskDescription;
    }

    /**
     * Help method to draw the snapshot on a surface.
     */
    public static void drawSnapshotOnSurface(StartingWindowInfo info, WindowManager.LayoutParams lp,
            SurfaceControl rootSurface, TaskSnapshot snapshot,
            Rect configBounds, Rect windowBounds, InsetsState topWindowInsetsState,
            boolean releaseAfterDraw) {
        if (windowBounds.isEmpty()) {
            Log.e(TAG, "Unable to draw snapshot on an empty windowBounds");
            return;
        }
        final SnapshotSurface drawSurface = new SnapshotSurface(
                rootSurface, snapshot, lp.getTitle(), configBounds);

        final WindowManager.LayoutParams attrs = info.topOpaqueWindowLayoutParams;
        final ActivityManager.RunningTaskInfo runningTaskInfo = info.taskInfo;
        final ActivityManager.TaskDescription taskDescription =
                getOrCreateTaskDescription(runningTaskInfo);
        drawSurface.initiateSystemBarPainter(lp.flags, lp.privateFlags,
                attrs.insetsFlags.appearance, taskDescription, info.requestedVisibleTypes);
        final Rect systemBarInsets = getSystemBarInsets(windowBounds, topWindowInsetsState);
        drawSurface.setFrames(windowBounds, systemBarInsets);
        drawSurface.drawSnapshot(releaseAfterDraw);
    }

    /**
     * Help method to create a layout parameters for a window.
     */
    public static WindowManager.LayoutParams createLayoutParameters(StartingWindowInfo info,
            CharSequence title, @WindowManager.LayoutParams.WindowType int windowType,
            int pixelFormat, IBinder token) {
        final WindowManager.LayoutParams attrs = info.topOpaqueWindowLayoutParams;
        final WindowManager.LayoutParams mainWindowParams = info.mainWindowLayoutParams;
        final InsetsState topWindowInsetsState = info.topOpaqueWindowInsetsState;
        if (attrs == null || mainWindowParams == null || topWindowInsetsState == null) {
            Log.w(TAG, "unable to create taskSnapshot surface ");
            return null;
        }
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();

        final int appearance = attrs.insetsFlags.appearance;
        final int windowFlags = attrs.flags;
        final int windowPrivateFlags = attrs.privateFlags;

        layoutParams.packageName = mainWindowParams.packageName;
        layoutParams.windowAnimations = mainWindowParams.windowAnimations;
        layoutParams.dimAmount = mainWindowParams.dimAmount;
        layoutParams.type = windowType;
        layoutParams.format = pixelFormat;
        layoutParams.flags = (windowFlags & ~FLAG_INHERIT_EXCLUDES)
                | FLAG_NOT_FOCUSABLE
                | FLAG_NOT_TOUCHABLE;
        // Setting as trusted overlay to let touches pass through. This is safe because this
        // window is controlled by the system.
        layoutParams.privateFlags = (windowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS)
                | PRIVATE_FLAG_TRUSTED_OVERLAY;
        layoutParams.token = token;
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.insetsFlags.appearance = appearance;
        layoutParams.insetsFlags.behavior = attrs.insetsFlags.behavior;
        layoutParams.layoutInDisplayCutoutMode = attrs.layoutInDisplayCutoutMode;
        layoutParams.setFitInsetsTypes(attrs.getFitInsetsTypes());
        layoutParams.setFitInsetsSides(attrs.getFitInsetsSides());
        layoutParams.setFitInsetsIgnoringVisibility(attrs.isFitInsetsIgnoringVisibility());

        layoutParams.setTitle(title);
        layoutParams.inputFeatures |= INPUT_FEATURE_NO_INPUT_CHANNEL;
        return layoutParams;
    }

    static Rect getSystemBarInsets(Rect frame, InsetsState state) {
        return state.calculateInsets(frame, WindowInsets.Type.systemBars(),
                false /* ignoreVisibility */).toRect();
    }

    /**
     * Helper class to draw the background of the system bars in regions the task snapshot isn't
     * filling the window.
     */
    public static class SystemBarBackgroundPainter {
        private final Paint mStatusBarPaint = new Paint();
        private final Paint mNavigationBarPaint = new Paint();
        private final int mStatusBarColor;
        private final int mNavigationBarColor;
        private final int mWindowFlags;
        private final int mWindowPrivateFlags;
        private final float mScale;
        private final @WindowInsets.Type.InsetsType int mRequestedVisibleTypes;
        private final Rect mSystemBarInsets = new Rect();

        public SystemBarBackgroundPainter(int windowFlags, int windowPrivateFlags, int appearance,
                ActivityManager.TaskDescription taskDescription, float scale,
                @WindowInsets.Type.InsetsType int requestedVisibleTypes) {
            mWindowFlags = windowFlags;
            mWindowPrivateFlags = windowPrivateFlags;
            mScale = scale;
            final Context context = ActivityThread.currentActivityThread().getSystemUiContext();
            final int semiTransparent = context.getColor(
                    R.color.system_bar_background_semi_transparent);
            mStatusBarColor = DecorView.calculateBarColor(windowFlags, FLAG_TRANSLUCENT_STATUS,
                    semiTransparent, taskDescription.getStatusBarColor(), appearance,
                    APPEARANCE_LIGHT_STATUS_BARS,
                    taskDescription.getEnsureStatusBarContrastWhenTransparent());
            mNavigationBarColor = DecorView.calculateBarColor(windowFlags,
                    FLAG_TRANSLUCENT_NAVIGATION, semiTransparent,
                    taskDescription.getNavigationBarColor(), appearance,
                    APPEARANCE_LIGHT_NAVIGATION_BARS,
                    taskDescription.getEnsureNavigationBarContrastWhenTransparent()
                            && context.getResources().getBoolean(
                            R.bool.config_navBarNeedsScrim));
            mStatusBarPaint.setColor(mStatusBarColor);
            mNavigationBarPaint.setColor(mNavigationBarColor);
            mRequestedVisibleTypes = requestedVisibleTypes;
        }

        /**
         * Set system bar insets.
         */
        public void setInsets(Rect systemBarInsets) {
            mSystemBarInsets.set(systemBarInsets);
        }

        int getStatusBarColorViewHeight() {
            final boolean forceBarBackground =
                    (mWindowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;
            if (STATUS_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                    mRequestedVisibleTypes, mStatusBarColor, mWindowFlags,
                    forceBarBackground)) {
                return (int) (mSystemBarInsets.top * mScale);
            } else {
                return 0;
            }
        }

        private boolean isNavigationBarColorViewVisible() {
            final boolean forceBarBackground =
                    (mWindowPrivateFlags & PRIVATE_FLAG_FORCE_DRAW_BAR_BACKGROUNDS) != 0;
            return NAVIGATION_BAR_COLOR_VIEW_ATTRIBUTES.isVisible(
                    mRequestedVisibleTypes, mNavigationBarColor, mWindowFlags,
                    forceBarBackground);
        }

        /**
         * Draw bar colors to a canvas.
         */
        public void drawDecors(Canvas c, @Nullable Rect alreadyDrawnFrame) {
            drawStatusBarBackground(c, alreadyDrawnFrame, getStatusBarColorViewHeight());
            drawNavigationBarBackground(c);
        }

        void drawStatusBarBackground(Canvas c, @Nullable Rect alreadyDrawnFrame,
                int statusBarHeight) {
            if (statusBarHeight > 0 && Color.alpha(mStatusBarColor) != 0
                    && (alreadyDrawnFrame == null || c.getWidth() > alreadyDrawnFrame.right)) {
                final int rightInset = (int) (mSystemBarInsets.right * mScale);
                final int left = alreadyDrawnFrame != null ? alreadyDrawnFrame.right : 0;
                c.drawRect(left, 0, c.getWidth() - rightInset, statusBarHeight,
                        mStatusBarPaint);
            }
        }

        void drawNavigationBarBackground(Canvas c) {
            final Rect navigationBarRect = new Rect();
            getNavigationBarRect(c.getWidth(), c.getHeight(), mSystemBarInsets, navigationBarRect,
                    mScale);
            final boolean visible = isNavigationBarColorViewVisible();
            if (visible && Color.alpha(mNavigationBarColor) != 0
                    && !navigationBarRect.isEmpty()) {
                c.drawRect(navigationBarRect, mNavigationBarPaint);
            }
        }
    }
}
