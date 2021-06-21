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

package android.widget;

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.BLASTBufferQueue;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceSession;
import android.view.SurfaceView;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewRootImpl;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Android magnifier widget. Can be used by any view which is attached to a window.
 */
@UiThread
public final class Magnifier {
    private static final String TAG = "Magnifier";
    // Use this to specify that a previous configuration value does not exist.
    private static final int NONEXISTENT_PREVIOUS_CONFIG_VALUE = -1;
    // The callbacks of the pixel copy requests will be invoked on
    // the Handler of this Thread when the copy is finished.
    private static final HandlerThread sPixelCopyHandlerThread =
            new HandlerThread("magnifier pixel copy result handler");
    // The width of the ramp region in DP on the left & right sides of the fish-eye effect.
    private static final float FISHEYE_RAMP_WIDTH = 12f;

    // The view to which this magnifier is attached.
    private final View mView;
    // The coordinates of the view in the surface.
    private final int[] mViewCoordinatesInSurface;
    // The window containing the magnifier.
    private InternalPopupWindow mWindow;
    // The width of the window containing the magnifier.
    private final int mWindowWidth;
    // The height of the window containing the magnifier.
    private int mWindowHeight;
    // The zoom applied to the view region copied to the magnifier view.
    private float mZoom;
    // The width of the content that will be copied to the magnifier.
    private int mSourceWidth;
    // The height of the content that will be copied to the magnifier.
    private int mSourceHeight;
    // Whether the zoom of the magnifier or the view position have changed since last content copy.
    private boolean mDirtyState;
    // The elevation of the window containing the magnifier.
    private final float mWindowElevation;
    // The corner radius of the window containing the magnifier.
    private final float mWindowCornerRadius;
    // The overlay to be drawn on the top of the magnifier content.
    private final Drawable mOverlay;
    // The horizontal offset between the source and window coords when #show(float, float) is used.
    private final int mDefaultHorizontalSourceToMagnifierOffset;
    // The vertical offset between the source and window coords when #show(float, float) is used.
    private final int mDefaultVerticalSourceToMagnifierOffset;
    // Whether the area where the magnifier can be positioned will be clipped to the main window
    // and within system insets.
    private final boolean mClippingEnabled;
    // The behavior of the left bound of the rectangle where the content can be copied from.
    private @SourceBound int mLeftContentBound;
    // The behavior of the top bound of the rectangle where the content can be copied from.
    private @SourceBound int mTopContentBound;
    // The behavior of the right bound of the rectangle where the content can be copied from.
    private @SourceBound int mRightContentBound;
    // The behavior of the bottom bound of the rectangle where the content can be copied from.
    private @SourceBound int mBottomContentBound;
    // The parent surface for the magnifier surface.
    private SurfaceInfo mParentSurface;
    // The surface where the content will be copied from.
    private SurfaceInfo mContentCopySurface;
    // The center coordinates of the window containing the magnifier.
    private final Point mWindowCoords = new Point();
    // The center coordinates of the content to be magnified,
    // clamped inside the visible region of the magnified view.
    private final Point mClampedCenterZoomCoords = new Point();
    // Variables holding previous states, used for detecting redundant calls and invalidation.
    private final Point mPrevStartCoordsInSurface = new Point(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    private final PointF mPrevShowSourceCoords = new PointF(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    private final PointF mPrevShowWindowCoords = new PointF(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    // Rectangle defining the view surface area we pixel copy content from.
    private final Rect mPixelCopyRequestRect = new Rect();
    // Lock to synchronize between the UI thread and the thread that handles pixel copy results.
    // Only sync mWindow writes from UI thread with mWindow reads from sPixelCopyHandlerThread.
    private final Object mLock = new Object();

    // Members for new styled magnifier (Eloquent style).

    // Whether the magnifier is in new style.
    private boolean mIsFishEyeStyle;
    // The width of the cut region on the left edge of the pixel copy source rect.
    private int mLeftCutWidth = 0;
    // The width of the cut region on the right edge of the pixel copy source rect.
    private int mRightCutWidth = 0;
    // The width of the ramp region in pixels on the left & right sides of the fish-eye effect.
    private final int mRamp;

    /**
     * Initializes a magnifier.
     *
     * @param view the view for which this magnifier is attached
     *
     * @deprecated Please use {@link Builder} instead
     */
    @Deprecated
    public Magnifier(@NonNull View view) {
        this(createBuilderWithOldMagnifierDefaults(view));
    }

    static Builder createBuilderWithOldMagnifierDefaults(final View view) {
        final Builder params = new Builder(view);
        final Context context = view.getContext();
        final TypedArray a = context.obtainStyledAttributes(null, R.styleable.Magnifier,
                R.attr.magnifierStyle, 0);
        params.mWidth = a.getDimensionPixelSize(R.styleable.Magnifier_magnifierWidth, 0);
        params.mHeight = a.getDimensionPixelSize(R.styleable.Magnifier_magnifierHeight, 0);
        params.mElevation = a.getDimension(R.styleable.Magnifier_magnifierElevation, 0);
        params.mCornerRadius = getDeviceDefaultDialogCornerRadius(context);
        params.mZoom = a.getFloat(R.styleable.Magnifier_magnifierZoom, 0);
        params.mHorizontalDefaultSourceToMagnifierOffset =
                a.getDimensionPixelSize(R.styleable.Magnifier_magnifierHorizontalOffset, 0);
        params.mVerticalDefaultSourceToMagnifierOffset =
                a.getDimensionPixelSize(R.styleable.Magnifier_magnifierVerticalOffset, 0);
        params.mOverlay = new ColorDrawable(a.getColor(
                R.styleable.Magnifier_magnifierColorOverlay, Color.TRANSPARENT));
        a.recycle();
        params.mClippingEnabled = true;
        params.mLeftContentBound = SOURCE_BOUND_MAX_VISIBLE;
        params.mTopContentBound = SOURCE_BOUND_MAX_IN_SURFACE;
        params.mRightContentBound = SOURCE_BOUND_MAX_VISIBLE;
        params.mBottomContentBound = SOURCE_BOUND_MAX_IN_SURFACE;
        return params;
    }

    /**
     * Returns the device default theme dialog corner radius attribute.
     * We retrieve this from the device default theme to avoid
     * using the values set in the custom application themes.
     */
    private static float getDeviceDefaultDialogCornerRadius(final Context context) {
        final Context deviceDefaultContext =
                new ContextThemeWrapper(context, R.style.Theme_DeviceDefault);
        final TypedArray ta = deviceDefaultContext.obtainStyledAttributes(
                new int[]{android.R.attr.dialogCornerRadius});
        final float dialogCornerRadius = ta.getDimension(0, 0);
        ta.recycle();
        return dialogCornerRadius;
    }

    private Magnifier(@NonNull Builder params) {
        // Copy params from builder.
        mView = params.mView;
        mWindowWidth = params.mWidth;
        mWindowHeight = params.mHeight;
        mZoom = params.mZoom;
        mIsFishEyeStyle = params.mIsFishEyeStyle;
        if (params.mSourceWidth > 0 && params.mSourceHeight > 0) {
            mSourceWidth = params.mSourceWidth;
            mSourceHeight = params.mSourceHeight;
        } else {
            mSourceWidth = Math.round(mWindowWidth / mZoom);
            mSourceHeight = Math.round(mWindowHeight / mZoom);
        }
        mWindowElevation = params.mElevation;
        mWindowCornerRadius = params.mCornerRadius;
        mOverlay = params.mOverlay;
        mDefaultHorizontalSourceToMagnifierOffset =
                params.mHorizontalDefaultSourceToMagnifierOffset;
        mDefaultVerticalSourceToMagnifierOffset =
                params.mVerticalDefaultSourceToMagnifierOffset;
        mClippingEnabled = params.mClippingEnabled;
        mLeftContentBound = params.mLeftContentBound;
        mTopContentBound = params.mTopContentBound;
        mRightContentBound = params.mRightContentBound;
        mBottomContentBound = params.mBottomContentBound;
        // The view's surface coordinates will not be updated until the magnifier is first shown.
        mViewCoordinatesInSurface = new int[2];
        mRamp = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, FISHEYE_RAMP_WIDTH,
                mView.getContext().getResources().getDisplayMetrics());
    }

    static {
        sPixelCopyHandlerThread.start();
    }

    /**
     * Shows the magnifier on the screen. The method takes the coordinates of the center
     * of the content source going to be magnified and copied to the magnifier. The coordinates
     * are relative to the top left corner of the magnified view. The magnifier will be
     * positioned such that its center will be at the default offset from the center of the source.
     * The default offset can be specified using the method
     * {@link Builder#setDefaultSourceToMagnifierOffset(int, int)}. If the offset should
     * be different across calls to this method, you should consider to use method
     * {@link #show(float, float, float, float)} instead.
     *
     * @param sourceCenterX horizontal coordinate of the source center, relative to the view
     * @param sourceCenterY vertical coordinate of the source center, relative to the view
     *
     * @see Builder#setDefaultSourceToMagnifierOffset(int, int)
     * @see Builder#getDefaultHorizontalSourceToMagnifierOffset()
     * @see Builder#getDefaultVerticalSourceToMagnifierOffset()
     * @see #show(float, float, float, float)
     */
    public void show(@FloatRange(from = 0) float sourceCenterX,
            @FloatRange(from = 0) float sourceCenterY) {
        show(sourceCenterX, sourceCenterY,
                sourceCenterX + mDefaultHorizontalSourceToMagnifierOffset,
                sourceCenterY + mDefaultVerticalSourceToMagnifierOffset);
    }

    private Drawable mCursorDrawable;
    private boolean mDrawCursorEnabled;

    void setDrawCursor(boolean enabled, Drawable cursorDrawable) {
        mDrawCursorEnabled = enabled;
        mCursorDrawable = cursorDrawable;
    }

    /**
     * Shows the magnifier on the screen at a position that is independent from its content
     * position. The first two arguments represent the coordinates of the center of the
     * content source going to be magnified and copied to the magnifier. The last two arguments
     * represent the coordinates of the center of the magnifier itself. All four coordinates
     * are relative to the top left corner of the magnified view. If you consider using this
     * method such that the offset between the source center and the magnifier center coordinates
     * remains constant, you should consider using method {@link #show(float, float)} instead.
     *
     * @param sourceCenterX horizontal coordinate of the source center relative to the view
     * @param sourceCenterY vertical coordinate of the source center, relative to the view
     * @param magnifierCenterX horizontal coordinate of the magnifier center, relative to the view
     * @param magnifierCenterY vertical coordinate of the magnifier center, relative to the view
     */
    public void show(@FloatRange(from = 0) float sourceCenterX,
            @FloatRange(from = 0) float sourceCenterY,
            float magnifierCenterX, float magnifierCenterY) {

        obtainSurfaces();
        obtainContentCoordinates(sourceCenterX, sourceCenterY);

        int startX = mClampedCenterZoomCoords.x - mSourceWidth / 2;
        final int startY = mClampedCenterZoomCoords.y - mSourceHeight / 2;

        if (mIsFishEyeStyle) {
            // The magnifier center is the same as source center in new style.
            magnifierCenterX = mClampedCenterZoomCoords.x - mViewCoordinatesInSurface[0];
            magnifierCenterY = mClampedCenterZoomCoords.y - mViewCoordinatesInSurface[1];

            // PixelCopy requires the pre-magnified bounds.
            // The below logic calculates the leftBound & rightBound for the pre-magnified bounds.
            final float rampPre =
                    (mSourceWidth - (mSourceWidth - 2 * mRamp) / mZoom) / 2;

            // Calculates the pre-zoomed left edge.
            // The leftEdge moves from the left of view towards to sourceCenterX, considering the
            // fisheye-like zooming.
            final float x0 = sourceCenterX - mSourceWidth / 2f;
            final float rampX0 = x0 + mRamp;
            float leftEdge = 0;
            if (leftEdge > rampX0) {
                // leftEdge is in the zoom range, the distance from leftEdge to sourceCenterX
                // should reduce per mZoom.
                leftEdge = sourceCenterX - (sourceCenterX - leftEdge) / mZoom;
            } else if (leftEdge > x0) {
                // leftEdge is in the ramp range, the distance from leftEdge to rampX0 should
                // increase per ramp zoom (ramp / rampPre).
                leftEdge = x0 + rampPre - (rampX0 - leftEdge) * rampPre / mRamp;
            }
            int leftBound = Math.min((int) leftEdge, mView.getWidth());

            // Calculates the pre-zoomed right edge.
            // The rightEdge moves from the right of view towards to sourceCenterX, considering the
            // fisheye-like zooming.
            final float x1 = sourceCenterX + mSourceWidth / 2f;
            final float rampX1 = x1 - mRamp;
            float rightEdge = mView.getWidth();
            if (rightEdge < rampX1) {
                // rightEdge is in the zoom range, the distance from rightEdge to sourceCenterX
                // should reduce per mZoom.
                rightEdge = sourceCenterX + (rightEdge - sourceCenterX) / mZoom;
            } else if (rightEdge < x1) {
                // rightEdge is in the ramp range, the distance from rightEdge to rampX1 should
                // increase per ramp zoom (ramp / rampPre).
                rightEdge = x1 - rampPre + (rightEdge - rampX1) * rampPre / mRamp;
            }
            int rightBound = Math.max(leftBound, (int) rightEdge);

            // Gets the startX for new style, which should be bounded by the horizontal bounds.
            // Also calculates the left/right cut width for pixel copy.
            leftBound = Math.max(leftBound + mViewCoordinatesInSurface[0], 0);
            rightBound = Math.min(
                rightBound + mViewCoordinatesInSurface[0], mContentCopySurface.mWidth);
            mLeftCutWidth = Math.max(0, leftBound - startX);
            mRightCutWidth = Math.max(0, startX + mSourceWidth - rightBound);
            startX = Math.max(startX, leftBound);
        }
        obtainWindowCoordinates(magnifierCenterX, magnifierCenterY);

        if (sourceCenterX != mPrevShowSourceCoords.x || sourceCenterY != mPrevShowSourceCoords.y
                || mDirtyState) {
            if (mWindow == null) {
                synchronized (mLock) {
                    mWindow = new InternalPopupWindow(mView.getContext(), mView.getDisplay(),
                            mParentSurface.mSurfaceControl, mWindowWidth, mWindowHeight, mZoom,
                            mRamp, mWindowElevation, mWindowCornerRadius,
                            mOverlay != null ? mOverlay : new ColorDrawable(Color.TRANSPARENT),
                            Handler.getMain() /* draw the magnifier on the UI thread */, mLock,
                            mCallback, mIsFishEyeStyle);
                }
            }
            performPixelCopy(startX, startY, true /* update window position */);
        } else if (magnifierCenterX != mPrevShowWindowCoords.x
                || magnifierCenterY != mPrevShowWindowCoords.y) {
            final Point windowCoords = getCurrentClampedWindowCoordinates();
            final InternalPopupWindow currentWindowInstance = mWindow;
            sPixelCopyHandlerThread.getThreadHandler().post(() -> {
                synchronized (mLock) {
                    if (mWindow != currentWindowInstance) {
                        // The magnifier was dismissed (and maybe shown again) in the meantime.
                        return;
                    }
                    mWindow.setContentPositionForNextDraw(windowCoords.x, windowCoords.y);
                }
            });
        }
        mPrevShowSourceCoords.x = sourceCenterX;
        mPrevShowSourceCoords.y = sourceCenterY;
        mPrevShowWindowCoords.x = magnifierCenterX;
        mPrevShowWindowCoords.y = magnifierCenterY;
    }

    /**
     * Dismisses the magnifier from the screen. Calling this on a dismissed magnifier is a no-op.
     */
    public void dismiss() {
        if (mWindow != null) {
            synchronized (mLock) {
                mWindow.destroy();
                mWindow = null;
            }
            mPrevShowSourceCoords.x = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevShowSourceCoords.y = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevShowWindowCoords.x = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevShowWindowCoords.y = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevStartCoordsInSurface.x = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            mPrevStartCoordsInSurface.y = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
        }
    }

    /**
     * Asks the magnifier to update its content. It uses the previous coordinates passed to
     * {@link #show(float, float)} or {@link #show(float, float, float, float)}. The
     * method only has effect if the magnifier is currently showing.
     */
    public void update() {
        if (mWindow != null) {
            obtainSurfaces();
            if (!mDirtyState) {
                // Update the content shown in the magnifier.
                performPixelCopy(mPrevStartCoordsInSurface.x, mPrevStartCoordsInSurface.y,
                        false /* update window position */);
            } else {
                // If for example the zoom has changed, we cannot use the same top left
                // coordinates as before, so just #show again to have them recomputed.
                show(mPrevShowSourceCoords.x, mPrevShowSourceCoords.y,
                        mPrevShowWindowCoords.x, mPrevShowWindowCoords.y);
            }
        }
    }

    /**
     * @return the width of the magnifier window, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     */
    @Px
    public int getWidth() {
        return mWindowWidth;
    }

    /**
     * @return the height of the magnifier window, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     */
    @Px
    public int getHeight() {
        return mWindowHeight;
    }

    /**
     * @return the initial width of the content magnified and copied to the magnifier, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     * @see Magnifier.Builder#setInitialZoom(float)
     */
    @Px
    public int getSourceWidth() {
        return mSourceWidth;
    }

    /**
     * @return the initial height of the content magnified and copied to the magnifier, in pixels
     * @see Magnifier.Builder#setSize(int, int)
     * @see Magnifier.Builder#setInitialZoom(float)
     */
    @Px
    public int getSourceHeight() {
        return mSourceHeight;
    }

    /**
     * Sets the zoom to be applied to the chosen content before being copied to the magnifier popup.
     * The change will become effective at the next #show or #update call.
     * @param zoom the zoom to be set
     */
    public void setZoom(@FloatRange(from = 0f) float zoom) {
        Preconditions.checkArgumentPositive(zoom, "Zoom should be positive");
        mZoom = zoom;
        mSourceWidth = mIsFishEyeStyle ? mWindowWidth : Math.round(mWindowWidth / mZoom);
        mSourceHeight = Math.round(mWindowHeight / mZoom);
        mDirtyState = true;
    }

    /**
     * Updates the factors of source which may impact the magnifier's size.
     * This can be called while the magnifier is showing and moving.
     * @param sourceHeight the new source height.
     * @param zoom the new zoom factor.
     */
    void updateSourceFactors(final int sourceHeight, final float zoom) {
        mZoom = zoom;
        mSourceHeight = sourceHeight;
        mWindowHeight = (int) (sourceHeight * zoom);
        if (mWindow != null) {
            mWindow.updateContentFactors(mWindowHeight, zoom);
        }
    }

    /**
     * Returns the zoom to be applied to the magnified view region copied to the magnifier.
     * If the zoom is x and the magnifier window size is (width, height), the original size
     * of the content being magnified will be (width / x, height / x).
     * @return the zoom applied to the content
     * @see Magnifier.Builder#setInitialZoom(float)
     */
    public float getZoom() {
        return mZoom;
    }

    /**
     * @return the elevation set for the magnifier window, in pixels
     * @see Magnifier.Builder#setElevation(float)
     */
    @Px
    public float getElevation() {
        return mWindowElevation;
    }

    /**
     * @return the corner radius of the magnifier window, in pixels
     * @see Magnifier.Builder#setCornerRadius(float)
     */
    @Px
    public float getCornerRadius() {
        return mWindowCornerRadius;
    }

    /**
     * Returns the horizontal offset, in pixels, to be applied to the source center position
     * to obtain the magnifier center position when {@link #show(float, float)} is called.
     * The value is ignored when {@link #show(float, float, float, float)} is used instead.
     *
     * @return the default horizontal offset between the source center and the magnifier
     * @see Magnifier.Builder#setDefaultSourceToMagnifierOffset(int, int)
     * @see Magnifier#show(float, float)
     */
    @Px
    public int getDefaultHorizontalSourceToMagnifierOffset() {
        return mDefaultHorizontalSourceToMagnifierOffset;
    }

    /**
     * Returns the vertical offset, in pixels, to be applied to the source center position
     * to obtain the magnifier center position when {@link #show(float, float)} is called.
     * The value is ignored when {@link #show(float, float, float, float)} is used instead.
     *
     * @return the default vertical offset between the source center and the magnifier
     * @see Magnifier.Builder#setDefaultSourceToMagnifierOffset(int, int)
     * @see Magnifier#show(float, float)
     */
    @Px
    public int getDefaultVerticalSourceToMagnifierOffset() {
        return mDefaultVerticalSourceToMagnifierOffset;
    }

    /**
     * Returns the overlay to be drawn on the top of the magnifier, or
     * {@code null} if no overlay should be drawn.
     * @return the overlay
     * @see Magnifier.Builder#setOverlay(Drawable)
     */
    @Nullable
    public Drawable getOverlay() {
        return mOverlay;
    }

    /**
     * Returns whether the magnifier position will be adjusted such that the magnifier will be
     * fully within the bounds of the main application window, by also avoiding any overlap
     * with system insets (such as the one corresponding to the status bar) i.e. whether the
     * area where the magnifier can be positioned will be clipped to the main application window
     * and the system insets.
     * @return whether the magnifier position will be adjusted
     * @see Magnifier.Builder#setClippingEnabled(boolean)
     */
    public boolean isClippingEnabled() {
        return mClippingEnabled;
    }

    /**
     * Returns the top left coordinates of the magnifier, relative to the main application
     * window. They will be determined by the coordinates of the last {@link #show(float, float)}
     * or {@link #show(float, float, float, float)} call, adjusted to take into account any
     * potential clamping behavior. The method can be used immediately after a #show
     * call to find out where the magnifier will be positioned. However, the position of the
     * magnifier will not be updated visually in the same frame, due to the async nature of
     * the content copying and of the magnifier rendering.
     * The method will return {@code null} if #show has not yet been called, or if the last
     * operation performed was a #dismiss.
     *
     * @return the top left coordinates of the magnifier
     */
    @Nullable
    public Point getPosition() {
        if (mWindow == null) {
            return null;
        }
        final Point position = getCurrentClampedWindowCoordinates();
        position.offset(-mParentSurface.mInsets.left, -mParentSurface.mInsets.top);
        return new Point(position);
    }

    /**
     * Returns the top left coordinates of the magnifier source (i.e. the view region going to
     * be magnified and copied to the magnifier), relative to the window or surface the content
     * is copied from. The content will be copied:
     * - if the magnified view is a {@link SurfaceView}, from the surface backing it
     * - otherwise, from the surface backing the main application window, and the coordinates
     *   returned will be relative to the main application window
     * The method will return {@code null} if #show has not yet been called, or if the last
     * operation performed was a #dismiss.
     *
     * @return the top left coordinates of the magnifier source
     */
    @Nullable
    public Point getSourcePosition() {
        if (mWindow == null) {
            return null;
        }
        final Point position = new Point(mPixelCopyRequestRect.left, mPixelCopyRequestRect.top);
        position.offset(-mContentCopySurface.mInsets.left, -mContentCopySurface.mInsets.top);
        return new Point(position);
    }

    /**
     * Retrieves the surfaces used by the magnifier:
     * - a parent surface for the magnifier surface. This will usually be the main app window.
     * - a surface where the magnified content will be copied from. This will be the main app
     *   window unless the magnified view is a SurfaceView, in which case its backing surface
     *   will be used.
     */
    private void obtainSurfaces() {
        // Get the main window surface.
        SurfaceInfo validMainWindowSurface = SurfaceInfo.NULL;
        if (mView.getViewRootImpl() != null) {
            final ViewRootImpl viewRootImpl = mView.getViewRootImpl();
            final Surface mainWindowSurface = viewRootImpl.mSurface;
            if (mainWindowSurface != null && mainWindowSurface.isValid()) {
                final Rect surfaceInsets = viewRootImpl.mWindowAttributes.surfaceInsets;
                final int surfaceWidth =
                        viewRootImpl.getWidth() + surfaceInsets.left + surfaceInsets.right;
                final int surfaceHeight =
                        viewRootImpl.getHeight() + surfaceInsets.top + surfaceInsets.bottom;
                validMainWindowSurface =
                        new SurfaceInfo(viewRootImpl.getSurfaceControl(), mainWindowSurface,
                                surfaceWidth, surfaceHeight, surfaceInsets, true);
            }
        }
        // Get the surface backing the magnified view, if it is a SurfaceView.
        SurfaceInfo validSurfaceViewSurface = SurfaceInfo.NULL;
        if (mView instanceof SurfaceView) {
            final SurfaceControl sc = ((SurfaceView) mView).getSurfaceControl();
            final SurfaceHolder surfaceHolder = ((SurfaceView) mView).getHolder();
            final Surface surfaceViewSurface = surfaceHolder.getSurface();

            if (sc != null && sc.isValid()) {
                final Rect surfaceFrame = surfaceHolder.getSurfaceFrame();
                validSurfaceViewSurface = new SurfaceInfo(sc, surfaceViewSurface,
                        surfaceFrame.right, surfaceFrame.bottom, new Rect(), false);
            }
        }

        // Choose the parent surface for the magnifier and the source surface for the content.
        mParentSurface = validMainWindowSurface != SurfaceInfo.NULL
                ? validMainWindowSurface : validSurfaceViewSurface;
        mContentCopySurface = mView instanceof SurfaceView
                ? validSurfaceViewSurface : validMainWindowSurface;
    }

    /**
     * Computes the coordinates of the center of the content going to be displayed in the
     * magnifier. These are relative to the surface the content is copied from.
     */
    private void obtainContentCoordinates(final float xPosInView, final float yPosInView) {
        final int prevViewXInSurface = mViewCoordinatesInSurface[0];
        final int prevViewYInSurface = mViewCoordinatesInSurface[1];
        mView.getLocationInSurface(mViewCoordinatesInSurface);
        if (mViewCoordinatesInSurface[0] != prevViewXInSurface
                || mViewCoordinatesInSurface[1] != prevViewYInSurface) {
            mDirtyState = true;
        }

        final int zoomCenterX;
        final int zoomCenterY;
        if (mView instanceof SurfaceView) {
            // No offset required if the backing Surface matches the size of the SurfaceView.
            zoomCenterX = Math.round(xPosInView);
            zoomCenterY = Math.round(yPosInView);
        } else {
            zoomCenterX = Math.round(xPosInView + mViewCoordinatesInSurface[0]);
            zoomCenterY = Math.round(yPosInView + mViewCoordinatesInSurface[1]);
        }

        final Rect[] bounds = new Rect[2]; // [MAX_IN_SURFACE, MAX_VISIBLE]
        // Obtain the surface bounds rectangle.
        final Rect surfaceBounds = new Rect(0, 0,
                mContentCopySurface.mWidth, mContentCopySurface.mHeight);
        bounds[0] = surfaceBounds;
        // Obtain the visible view region rectangle.
        final Rect viewVisibleRegion = new Rect();
        mView.getGlobalVisibleRect(viewVisibleRegion);
        if (mView.getViewRootImpl() != null) {
            // Clamping coordinates relative to the surface, not to the window.
            final Rect surfaceInsets = mView.getViewRootImpl().mWindowAttributes.surfaceInsets;
            viewVisibleRegion.offset(surfaceInsets.left, surfaceInsets.top);
        }
        if (mView instanceof SurfaceView) {
            // If we copy content from a SurfaceView, clamp coordinates relative to it.
            viewVisibleRegion.offset(-mViewCoordinatesInSurface[0], -mViewCoordinatesInSurface[1]);
        }
        bounds[1] = viewVisibleRegion;

        // Aggregate the above to obtain the bounds where the content copy will be restricted.
        int resolvedLeft = Integer.MIN_VALUE;
        for (int i = mLeftContentBound; i >= 0; --i) {
            resolvedLeft = Math.max(resolvedLeft, bounds[i].left);
        }
        int resolvedTop = Integer.MIN_VALUE;
        for (int i = mTopContentBound; i >= 0; --i) {
            resolvedTop = Math.max(resolvedTop, bounds[i].top);
        }
        int resolvedRight = Integer.MAX_VALUE;
        for (int i = mRightContentBound; i >= 0; --i) {
            resolvedRight = Math.min(resolvedRight, bounds[i].right);
        }
        int resolvedBottom = Integer.MAX_VALUE;
        for (int i = mBottomContentBound; i >= 0; --i) {
            resolvedBottom = Math.min(resolvedBottom, bounds[i].bottom);
        }
        // Adjust <left-right> and <top-bottom> pairs of bounds to make sense.
        resolvedLeft = Math.min(resolvedLeft, mContentCopySurface.mWidth - mSourceWidth);
        resolvedTop = Math.min(resolvedTop, mContentCopySurface.mHeight - mSourceHeight);
        if (resolvedLeft < 0 || resolvedTop < 0) {
            Log.e(TAG, "Magnifier's content is copied from a surface smaller than"
                    + "the content requested size. The magnifier will be dismissed.");
        }
        resolvedRight = Math.max(resolvedRight, resolvedLeft + mSourceWidth);
        resolvedBottom = Math.max(resolvedBottom, resolvedTop + mSourceHeight);

        // Finally compute the coordinates of the source center.
        mClampedCenterZoomCoords.x = mIsFishEyeStyle
                ? Math.max(resolvedLeft, Math.min(zoomCenterX, resolvedRight))
                : Math.max(resolvedLeft + mSourceWidth / 2, Math.min(
                        zoomCenterX, resolvedRight - mSourceWidth / 2));
        mClampedCenterZoomCoords.y = Math.max(resolvedTop + mSourceHeight / 2, Math.min(
                zoomCenterY, resolvedBottom - mSourceHeight / 2));
    }

    /**
     * Computes the coordinates of the top left corner of the magnifier window.
     * These are relative to the surface the magnifier window is attached to.
     */
    private void obtainWindowCoordinates(final float xWindowPos, final float yWindowPos) {
        final int windowCenterX;
        final int windowCenterY;
        if (mView instanceof SurfaceView) {
            // No offset required if the backing Surface matches the size of the SurfaceView.
            windowCenterX = Math.round(xWindowPos);
            windowCenterY = Math.round(yWindowPos);
        } else {
            windowCenterX = Math.round(xWindowPos + mViewCoordinatesInSurface[0]);
            windowCenterY = Math.round(yWindowPos + mViewCoordinatesInSurface[1]);
        }

        mWindowCoords.x = windowCenterX - mWindowWidth / 2;
        mWindowCoords.y = windowCenterY - mWindowHeight / 2;
        if (mParentSurface != mContentCopySurface) {
            mWindowCoords.x += mViewCoordinatesInSurface[0];
            mWindowCoords.y += mViewCoordinatesInSurface[1];
        }
    }

    private void maybeDrawCursor(Canvas canvas) {
        if (mDrawCursorEnabled) {
            if (mCursorDrawable != null) {
                mCursorDrawable.setBounds(
                        mSourceWidth / 2, 0,
                        mSourceWidth / 2 + mCursorDrawable.getIntrinsicWidth(), mSourceHeight);
                mCursorDrawable.draw(canvas);
            } else {
                Paint paint = new Paint();
                paint.setColor(Color.BLACK);  // The cursor on magnifier is by default in black.
                canvas.drawRect(
                        new Rect(mSourceWidth / 2 - 1, 0, mSourceWidth / 2 + 1, mSourceHeight),
                        paint);
            }
        }
    }

    private void performPixelCopy(final int startXInSurface, final int startYInSurface,
            final boolean updateWindowPosition) {
        if (mContentCopySurface.mSurface == null || !mContentCopySurface.mSurface.isValid()) {
            onPixelCopyFailed();
            return;
        }

        // Clamp window coordinates inside the parent surface, to avoid displaying
        // the magnifier out of screen or overlapping with system insets.
        final Point windowCoords = getCurrentClampedWindowCoordinates();

        // Perform the pixel copy.
        mPixelCopyRequestRect.set(startXInSurface,
                startYInSurface,
                startXInSurface + mSourceWidth - mLeftCutWidth - mRightCutWidth,
                startYInSurface + mSourceHeight);
        mPrevStartCoordsInSurface.x = startXInSurface;
        mPrevStartCoordsInSurface.y = startYInSurface;
        mDirtyState = false;

        final InternalPopupWindow currentWindowInstance = mWindow;
        if (mPixelCopyRequestRect.width() == 0) {
            // If the copy rect is empty, updates an empty bitmap to the window.
            mWindow.updateContent(
                    Bitmap.createBitmap(mSourceWidth, mSourceHeight, Bitmap.Config.ALPHA_8));
            return;
        }
        final Bitmap bitmap =
                Bitmap.createBitmap(mSourceWidth - mLeftCutWidth - mRightCutWidth,
                        mSourceHeight, Bitmap.Config.ARGB_8888);
        PixelCopy.request(mContentCopySurface.mSurface, mPixelCopyRequestRect, bitmap,
                result -> {
                    if (result != PixelCopy.SUCCESS) {
                        onPixelCopyFailed();
                        return;
                    }
                    synchronized (mLock) {
                        if (mWindow != currentWindowInstance) {
                            // The magnifier was dismissed (and maybe shown again) in the meantime.
                            return;
                        }
                        if (updateWindowPosition) {
                            // TODO: pull the position update outside #performPixelCopy
                            mWindow.setContentPositionForNextDraw(windowCoords.x,
                                    windowCoords.y);
                        }
                        if (bitmap.getWidth() < mSourceWidth) {
                            // When bitmap width has been cut, re-fills it with full width bitmap.
                            // This only happens in new styled magnifier.
                            final Bitmap newBitmap = Bitmap.createBitmap(
                                    mSourceWidth, bitmap.getHeight(), bitmap.getConfig());
                            final Canvas can = new Canvas(newBitmap);
                            final Rect dstRect = new Rect(mLeftCutWidth, 0,
                                    mSourceWidth - mRightCutWidth, bitmap.getHeight());
                            can.drawBitmap(bitmap, null, dstRect, null);
                            maybeDrawCursor(can);
                            mWindow.updateContent(newBitmap);
                        } else {
                            maybeDrawCursor(new Canvas(bitmap));
                            mWindow.updateContent(bitmap);
                        }
                    }
                },
                sPixelCopyHandlerThread.getThreadHandler());
    }

    private void onPixelCopyFailed() {
        Log.e(TAG, "Magnifier failed to copy content from the view Surface. It will be dismissed.");
        // Post to make sure #dismiss is done on the main thread.
        Handler.getMain().postAtFrontOfQueue(() -> {
            dismiss();
            if (mCallback != null) {
                mCallback.onOperationComplete();
            }
        });
    }

    /**
     * Clamp window coordinates inside the surface the magnifier is attached to, to avoid
     * displaying the magnifier out of screen or overlapping with system insets.
     * @return the current window coordinates, after they are clamped inside the parent surface
     */
    private Point getCurrentClampedWindowCoordinates() {
        if (!mClippingEnabled) {
            // No position adjustment should be done, so return the raw coordinates.
            return new Point(mWindowCoords);
        }

        final Rect windowBounds;
        if (mParentSurface.mIsMainWindowSurface) {
            final Insets systemInsets = mView.getRootWindowInsets().getSystemWindowInsets();
            windowBounds = new Rect(
                    systemInsets.left + mParentSurface.mInsets.left,
                    systemInsets.top + mParentSurface.mInsets.top,
                    mParentSurface.mWidth - systemInsets.right - mParentSurface.mInsets.right,
                    mParentSurface.mHeight - systemInsets.bottom
                            - mParentSurface.mInsets.bottom
            );
        } else {
            windowBounds = new Rect(0, 0, mParentSurface.mWidth, mParentSurface.mHeight);
        }
        final int windowCoordsX = Math.max(windowBounds.left,
                Math.min(windowBounds.right - mWindowWidth, mWindowCoords.x));
        final int windowCoordsY = Math.max(windowBounds.top,
                Math.min(windowBounds.bottom - mWindowHeight, mWindowCoords.y));
        return new Point(windowCoordsX, windowCoordsY);
    }

    /**
     * Contains a surface and metadata corresponding to it.
     */
    private static class SurfaceInfo {
        public static final SurfaceInfo NULL = new SurfaceInfo(null, null, 0, 0, null, false);

        private Surface mSurface;
        private SurfaceControl mSurfaceControl;
        private int mWidth;
        private int mHeight;
        private Rect mInsets;
        private boolean mIsMainWindowSurface;

        SurfaceInfo(final SurfaceControl surfaceControl, final Surface surface,
                final int width, final int height, final Rect insets,
                final boolean isMainWindowSurface) {
            mSurfaceControl = surfaceControl;
            mSurface = surface;
            mWidth = width;
            mHeight = height;
            mInsets = insets;
            mIsMainWindowSurface = isMainWindowSurface;
        }
    }

    /**
     * Magnifier's own implementation of PopupWindow-similar floating window.
     * This exists to ensure frame-synchronization between window position updates and window
     * content updates. By using a PopupWindow, these events would happen in different frames,
     * producing a shakiness effect for the magnifier content.
     */
    private static class InternalPopupWindow {
        // The z of the magnifier surface, defining its z order in the list of
        // siblings having the same parent surface (usually the main app surface).
        private static final int SURFACE_Z = 5;

        // Display associated to the view the magnifier is attached to.
        private final Display mDisplay;
        // The size of the content of the magnifier.
        private final int mContentWidth;
        private int mContentHeight;
        // The insets of the content inside the allocated surface.
        private final int mOffsetX;
        private final int mOffsetY;
        // The overlay to be drawn on the top of the content.
        private final Drawable mOverlay;
        // The surface we allocate for the magnifier content + shadow.
        private final SurfaceSession mSurfaceSession;
        private final SurfaceControl mSurfaceControl;
        private final SurfaceControl mBbqSurfaceControl;
        private final BLASTBufferQueue mBBQ;
        private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
        private final Surface mSurface;
        // The renderer used for the allocated surface.
        private final ThreadedRenderer.SimpleRenderer mRenderer;
        // The RenderNode used to draw the magnifier content in the surface.
        private final RenderNode mBitmapRenderNode;
        // The RenderNode used to draw the overlay over the magnifier content.
        private final RenderNode mOverlayRenderNode;
        // The job that will be post'd to apply the pending magnifier updates to the surface.
        private final Runnable mMagnifierUpdater;
        // The handler where the magnifier updater jobs will be post'd.
        private final Handler mHandler;
        // The callback to be run after the next draw.
        private Callback mCallback;

        // Members below describe the state of the magnifier. Reads/writes to them
        // have to be synchronized between the UI thread and the thread that handles
        // the pixel copy results. This is the purpose of mLock.
        private final Object mLock;
        // Whether a magnifier frame draw is currently pending in the UI thread queue.
        private boolean mFrameDrawScheduled;
        // The content bitmap, as returned by pixel copy.
        private Bitmap mBitmap;
        // Whether the next draw will be the first one for the current instance.
        private boolean mFirstDraw = true;
        // The window position in the parent surface. Might be applied during the next draw,
        // when mPendingWindowPositionUpdate is true.
        private int mWindowPositionX;
        private int mWindowPositionY;
        private boolean mPendingWindowPositionUpdate;

        // The current content of the magnifier. It is mBitmap + mOverlay, only used for testing.
        private Bitmap mCurrentContent;

        private float mZoom;
        // The width of the ramp region in pixels on the left & right sides of the fish-eye effect.
        private final int mRamp;
        // Whether is in the new magnifier style.
        private boolean mIsFishEyeStyle;
        // The mesh matrix for the fish-eye effect.
        private float[] mMeshLeft;
        private float[] mMeshRight;
        private int mMeshWidth;
        private int mMeshHeight;

        InternalPopupWindow(final Context context, final Display display,
                final SurfaceControl parentSurfaceControl, final int width, final int height,
                final float zoom, final int ramp, final float elevation, final float cornerRadius,
                final Drawable overlay, final Handler handler, final Object lock,
                final Callback callback, final boolean isFishEyeStyle) {
            mDisplay = display;
            mOverlay = overlay;
            mLock = lock;
            mCallback = callback;

            mContentWidth = width;
            mContentHeight = height;
            mZoom = zoom;
            mRamp = ramp;
            mOffsetX = (int) (1.05f * elevation);
            mOffsetY = (int) (1.05f * elevation);
            // Setup the surface we will use for drawing the content and shadow.
            final int surfaceWidth = mContentWidth + 2 * mOffsetX;
            final int surfaceHeight = mContentHeight + 2 * mOffsetY;
            mSurfaceSession = new SurfaceSession();
            mSurfaceControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setName("magnifier surface")
                    .setFlags(SurfaceControl.HIDDEN)
                    .setContainerLayer()
                    .setParent(parentSurfaceControl)
                    .setCallsite("InternalPopupWindow")
                    .build();
            mBbqSurfaceControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setName("magnifier surface bbq wrapper")
                    .setHidden(false)
                    .setBLASTLayer()
                    .setParent(mSurfaceControl)
                    .setCallsite("InternalPopupWindow")
                    .build();

            mBBQ = new BLASTBufferQueue("magnifier surface", mBbqSurfaceControl,
                surfaceWidth, surfaceHeight, PixelFormat.TRANSLUCENT);
            mSurface = mBBQ.createSurface();

            // Setup the RenderNode tree. The root has two children, one containing the bitmap
            // and one containing the overlay. We use a separate render node for the overlay
            // to avoid drawing this as the same rate we do for content.
            mRenderer = new ThreadedRenderer.SimpleRenderer(
                    context,
                    "magnifier renderer",
                    mSurface
            );
            mBitmapRenderNode = createRenderNodeForBitmap(
                    "magnifier content",
                    elevation,
                    cornerRadius
            );
            mOverlayRenderNode = createRenderNodeForOverlay(
                    "magnifier overlay",
                    cornerRadius
            );
            setupOverlay();

            final RecordingCanvas canvas = mRenderer.getRootNode().beginRecording(width, height);
            try {
                canvas.enableZ();
                canvas.drawRenderNode(mBitmapRenderNode);
                canvas.disableZ();
                canvas.drawRenderNode(mOverlayRenderNode);
                canvas.disableZ();
            } finally {
                mRenderer.getRootNode().endRecording();
            }
            if (mCallback != null) {
                mCurrentContent =
                        Bitmap.createBitmap(mContentWidth, mContentHeight, Bitmap.Config.ARGB_8888);
                updateCurrentContentForTesting();
            }

            // Initialize the update job and the handler where this will be post'd.
            mHandler = handler;
            mMagnifierUpdater = this::doDraw;
            mFrameDrawScheduled = false;
            mIsFishEyeStyle = isFishEyeStyle;

            if (mIsFishEyeStyle) {
                createMeshMatrixForFishEyeEffect();
            }
        }

        /**
         * Updates the factors of content which may resize the window.
         * @param contentHeight the new height of content.
         * @param zoom the new zoom factor.
         */
        private void updateContentFactors(final int contentHeight, final float zoom) {
            if (mContentHeight == contentHeight && mZoom == zoom) {
              return;
            }
            if (mContentHeight < contentHeight) {
                // Grows the surface height as necessary.
                mBBQ.update(mBbqSurfaceControl, mContentWidth, contentHeight,
                    PixelFormat.TRANSLUCENT);
                mRenderer.setSurface(mSurface);

                final Outline outline = new Outline();
                outline.setRoundRect(0, 0, mContentWidth, contentHeight, 0);
                outline.setAlpha(1.0f);

                mBitmapRenderNode.setLeftTopRightBottom(mOffsetX, mOffsetY,
                        mOffsetX + mContentWidth, mOffsetY + contentHeight);
                mBitmapRenderNode.setOutline(outline);

                mOverlayRenderNode.setLeftTopRightBottom(mOffsetX, mOffsetY,
                        mOffsetX + mContentWidth, mOffsetY + contentHeight);
                mOverlayRenderNode.setOutline(outline);

                final RecordingCanvas canvas =
                        mRenderer.getRootNode().beginRecording(mContentWidth, contentHeight);
                try {
                    canvas.enableZ();
                    canvas.drawRenderNode(mBitmapRenderNode);
                    canvas.disableZ();
                    canvas.drawRenderNode(mOverlayRenderNode);
                    canvas.disableZ();
                } finally {
                    mRenderer.getRootNode().endRecording();
                }
            }
            mContentHeight = contentHeight;
            mZoom = zoom;
            fillMeshMatrix();
        }

        private void createMeshMatrixForFishEyeEffect() {
            mMeshWidth = 1;
            mMeshHeight = 6;
            mMeshLeft = new float[2 * (mMeshWidth + 1) * (mMeshHeight + 1)];
            mMeshRight = new float[2 * (mMeshWidth + 1) * (mMeshHeight + 1)];
            fillMeshMatrix();
        }

        private void fillMeshMatrix() {
            mMeshWidth = 1;
            mMeshHeight = 6;
            final float w = mContentWidth;
            final float h = mContentHeight;
            final float h0 = h / mZoom;
            final float dh = h - h0;
            for (int i = 0; i < 2 * (mMeshWidth + 1) * (mMeshHeight + 1); i += 2) {
                // Calculates X value.
                final int colIndex = i % (2 * (mMeshWidth + 1)) / 2;
                mMeshLeft[i] = (float) colIndex * mRamp / mMeshWidth;
                mMeshRight[i] = w - mRamp + colIndex * mRamp / mMeshWidth;

                // Calculates Y value.
                final int rowIndex = i / 2 / (mMeshWidth + 1);
                final float hl = h0 + dh * colIndex / mMeshWidth;
                final float yl = (h - hl) / 2;
                mMeshLeft[i + 1] = yl + hl * rowIndex / mMeshHeight;
                final float hr = h - dh * colIndex / mMeshWidth;
                final float yr = (h - hr) / 2;
                mMeshRight[i + 1] = yr + hr * rowIndex / mMeshHeight;
            }
        }

        private RenderNode createRenderNodeForBitmap(final String name,
                final float elevation, final float cornerRadius) {
            final RenderNode bitmapRenderNode = RenderNode.create(name, null);

            // Define the position of the bitmap in the parent render node. The surface regions
            // outside the bitmap are used to draw elevation.
            bitmapRenderNode.setLeftTopRightBottom(mOffsetX, mOffsetY,
                    mOffsetX + mContentWidth, mOffsetY + mContentHeight);
            bitmapRenderNode.setElevation(elevation);

            final Outline outline = new Outline();
            outline.setRoundRect(0, 0, mContentWidth, mContentHeight, cornerRadius);
            outline.setAlpha(1.0f);
            bitmapRenderNode.setOutline(outline);
            bitmapRenderNode.setClipToOutline(true);

            // Create a placeholder draw, which will be replaced later with real drawing.
            final RecordingCanvas canvas = bitmapRenderNode.beginRecording(
                    mContentWidth, mContentHeight);
            try {
                canvas.drawColor(0xFF00FF00);
            } finally {
                bitmapRenderNode.endRecording();
            }

            return bitmapRenderNode;
        }

        private RenderNode createRenderNodeForOverlay(final String name, final float cornerRadius) {
            final RenderNode overlayRenderNode = RenderNode.create(name, null);

            // Define the position of the overlay in the parent render node.
            // This coincides with the position of the content.
            overlayRenderNode.setLeftTopRightBottom(mOffsetX, mOffsetY,
                    mOffsetX + mContentWidth, mOffsetY + mContentHeight);

            final Outline outline = new Outline();
            outline.setRoundRect(0, 0, mContentWidth, mContentHeight, cornerRadius);
            outline.setAlpha(1.0f);
            overlayRenderNode.setOutline(outline);
            overlayRenderNode.setClipToOutline(true);

            return overlayRenderNode;
        }

        private void setupOverlay() {
            drawOverlay();

            mOverlay.setCallback(new Drawable.Callback() {
                @Override
                public void invalidateDrawable(Drawable who) {
                    // When the overlay drawable is invalidated, redraw it to the render node.
                    drawOverlay();
                    if (mCallback != null) {
                        updateCurrentContentForTesting();
                    }
                }

                @Override
                public void scheduleDrawable(Drawable who, Runnable what, long when) {
                    Handler.getMain().postAtTime(what, who, when);
                }

                @Override
                public void unscheduleDrawable(Drawable who, Runnable what) {
                    Handler.getMain().removeCallbacks(what, who);
                }
            });
        }

        private void drawOverlay() {
            // Draw the drawable to the render node. This happens once during
            // initialization and whenever the overlay drawable is invalidated.
            final RecordingCanvas canvas =
                    mOverlayRenderNode.beginRecording(mContentWidth, mContentHeight);
            try {
                mOverlay.setBounds(0, 0, mContentWidth, mContentHeight);
                mOverlay.draw(canvas);
            } finally {
                mOverlayRenderNode.endRecording();
            }
        }

        /**
         * Sets the position of the magnifier content relative to the parent surface.
         * The position update will happen in the same frame with the next draw.
         * The method has to be called in a context that holds {@link #mLock}.
         *
         * @param contentX the x coordinate of the content
         * @param contentY the y coordinate of the content
         */
        public void setContentPositionForNextDraw(final int contentX, final int contentY) {
            mWindowPositionX = contentX - mOffsetX;
            mWindowPositionY = contentY - mOffsetY;
            mPendingWindowPositionUpdate = true;
            requestUpdate();
        }

        /**
         * Sets the content that should be displayed in the magnifier.
         * The update happens immediately, and possibly triggers a pending window movement set
         * by {@link #setContentPositionForNextDraw(int, int)}.
         * The method has to be called in a context that holds {@link #mLock}.
         *
         * @param bitmap the content bitmap
         */
        public void updateContent(final @NonNull Bitmap bitmap) {
            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mBitmap = bitmap;
            requestUpdate();
        }

        private void requestUpdate() {
            if (mFrameDrawScheduled) {
                return;
            }
            final Message request = Message.obtain(mHandler, mMagnifierUpdater);
            request.setAsynchronous(true);
            request.sendToTarget();
            mFrameDrawScheduled = true;
        }

        /**
         * Destroys this instance. The method has to be called in a context holding {@link #mLock}.
         */
        public void destroy() {
            // Destroy the renderer. This will not proceed until pending frame callbacks complete.
            mRenderer.destroy();
            mSurface.destroy();
            mBBQ.destroy();
            new SurfaceControl.Transaction()
                    .remove(mSurfaceControl)
                    .remove(mBbqSurfaceControl)
                    .apply();
            mSurfaceSession.kill();
            mHandler.removeCallbacks(mMagnifierUpdater);
            if (mBitmap != null) {
                mBitmap.recycle();
            }
            mOverlay.setCallback(null);
        }

        private void doDraw() {
            final ThreadedRenderer.FrameDrawingCallback callback;

            // Draw the current bitmap to the surface, and prepare the callback which updates the
            // surface position. These have to be in the same synchronized block, in order to
            // guarantee the consistency between the bitmap content and the surface position.
            synchronized (mLock) {
                if (!mSurface.isValid()) {
                    // Probably #destroy() was called for the current instance, so we skip the draw.
                    return;
                }

                final RecordingCanvas canvas =
                        mBitmapRenderNode.beginRecording(mContentWidth, mContentHeight);
                try {
                    final int w = mBitmap.getWidth();
                    final int h = mBitmap.getHeight();
                    final Paint paint = new Paint();
                    paint.setFilterBitmap(true);
                    if (mIsFishEyeStyle) {
                        final int margin =
                            (int)((mContentWidth - (mContentWidth - 2 * mRamp) / mZoom) / 2);

                        // Draws the middle part.
                        final Rect srcRect = new Rect(margin, 0, w - margin, h);
                        final Rect dstRect = new Rect(
                            mRamp, 0, mContentWidth - mRamp, mContentHeight);
                        canvas.drawBitmap(mBitmap, srcRect, dstRect, paint);

                        // Draws the left/right parts with mesh matrixes.
                        canvas.drawBitmapMesh(
                                Bitmap.createBitmap(mBitmap, 0, 0, margin, h),
                                mMeshWidth, mMeshHeight, mMeshLeft, 0, null, 0, paint);
                        canvas.drawBitmapMesh(
                                Bitmap.createBitmap(mBitmap, w - margin, 0, margin, h),
                                mMeshWidth, mMeshHeight, mMeshRight, 0, null, 0, paint);
                    } else {
                        final Rect srcRect = new Rect(0, 0, w, h);
                        final Rect dstRect = new Rect(0, 0, mContentWidth, mContentHeight);
                        canvas.drawBitmap(mBitmap, srcRect, dstRect, paint);
                    }
                } finally {
                    mBitmapRenderNode.endRecording();
                }
                if (mPendingWindowPositionUpdate || mFirstDraw) {
                    // If the window has to be shown or moved, defer this until the next draw.
                    final boolean firstDraw = mFirstDraw;
                    mFirstDraw = false;
                    final boolean updateWindowPosition = mPendingWindowPositionUpdate;
                    mPendingWindowPositionUpdate = false;
                    final int pendingX = mWindowPositionX;
                    final int pendingY = mWindowPositionY;

                    callback = frame -> {
                        if (!mSurface.isValid()) {
                            return;
                        }
                        if (updateWindowPosition) {
                            mTransaction.setPosition(mSurfaceControl, pendingX, pendingY);
                        }
                        if (firstDraw) {
                            mTransaction.setLayer(mSurfaceControl, SURFACE_Z)
                                .show(mSurfaceControl);

                        }
                        // Show or move the window at the content draw frame.
                        mBBQ.mergeWithNextTransaction(mTransaction, frame);
                    };
                    if (!mIsFishEyeStyle) {
                        // The new style magnifier doesn't need the light/shadow.
                        mRenderer.setLightCenter(mDisplay, pendingX, pendingY);
                    }
                } else {
                    callback = null;
                }

                mFrameDrawScheduled = false;
            }

            mRenderer.draw(callback);
            if (mCallback != null) {
                // The current content bitmap is only used in testing, so, for performance,
                // we only want to update it when running tests. For this, we check that
                // mCallback is not null, as it can only be set from a @TestApi.
                updateCurrentContentForTesting();
                mCallback.onOperationComplete();
            }
        }

        /**
         * Updates mCurrentContent, which reproduces what is currently supposed to be
         * drawn in the magnifier. mCurrentContent is only used for testing, so this method
         * should only be called otherwise.
         */
        private void updateCurrentContentForTesting() {
            final Canvas canvas = new Canvas(mCurrentContent);
            final Rect bounds = new Rect(0, 0, mContentWidth, mContentHeight);
            if (mBitmap != null && !mBitmap.isRecycled()) {
                final Rect originalBounds = new Rect(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
                canvas.drawBitmap(mBitmap, originalBounds, bounds, null);
            }
            mOverlay.setBounds(bounds);
            mOverlay.draw(canvas);
        }
    }

    /**
     * Builder class for {@link Magnifier} objects.
     */
    public static final class Builder {
        private @NonNull View mView;
        private @Px @IntRange(from = 0) int mWidth;
        private @Px @IntRange(from = 0) int mHeight;
        private float mZoom;
        private @FloatRange(from = 0f) float mElevation;
        private @FloatRange(from = 0f) float mCornerRadius;
        private @Nullable Drawable mOverlay;
        private int mHorizontalDefaultSourceToMagnifierOffset;
        private int mVerticalDefaultSourceToMagnifierOffset;
        private boolean mClippingEnabled;
        private @SourceBound int mLeftContentBound;
        private @SourceBound int mTopContentBound;
        private @SourceBound int mRightContentBound;
        private @SourceBound int  mBottomContentBound;
        private boolean mIsFishEyeStyle;
        private int mSourceWidth;
        private int mSourceHeight;

        /**
         * Construct a new builder for {@link Magnifier} objects.
         * @param view the view this magnifier is attached to
         */
        public Builder(@NonNull View view) {
            mView = Objects.requireNonNull(view);
            applyDefaults();
        }

        private void applyDefaults() {
            final Resources resources = mView.getContext().getResources();
            mWidth = resources.getDimensionPixelSize(R.dimen.default_magnifier_width);
            mHeight = resources.getDimensionPixelSize(R.dimen.default_magnifier_height);
            mElevation = resources.getDimension(R.dimen.default_magnifier_elevation);
            mCornerRadius = resources.getDimension(R.dimen.default_magnifier_corner_radius);
            mZoom = resources.getFloat(R.dimen.default_magnifier_zoom);
            mHorizontalDefaultSourceToMagnifierOffset =
                    resources.getDimensionPixelSize(R.dimen.default_magnifier_horizontal_offset);
            mVerticalDefaultSourceToMagnifierOffset =
                    resources.getDimensionPixelSize(R.dimen.default_magnifier_vertical_offset);
            mOverlay = new ColorDrawable(resources.getColor(
                    R.color.default_magnifier_color_overlay, null));
            mClippingEnabled = true;
            mLeftContentBound = SOURCE_BOUND_MAX_VISIBLE;
            mTopContentBound = SOURCE_BOUND_MAX_VISIBLE;
            mRightContentBound = SOURCE_BOUND_MAX_VISIBLE;
            mBottomContentBound = SOURCE_BOUND_MAX_VISIBLE;
            mIsFishEyeStyle = false;
        }

        /**
         * Sets the size of the magnifier window, in pixels. Defaults to (100dp, 48dp).
         * Note that the size of the content being magnified and copied to the magnifier
         * will be computed as (window width / zoom, window height / zoom).
         * @param width the window width to be set
         * @param height the window height to be set
         */
        @NonNull
        public Builder setSize(@Px @IntRange(from = 0) int width,
                @Px @IntRange(from = 0) int height) {
            Preconditions.checkArgumentPositive(width, "Width should be positive");
            Preconditions.checkArgumentPositive(height, "Height should be positive");
            mWidth = width;
            mHeight = height;
            return this;
        }

        /**
         * Sets the zoom to be applied to the chosen content before being copied to the magnifier.
         * A content of size (content_width, content_height) will be magnified to
         * (content_width * zoom, content_height * zoom), which will coincide with the size
         * of the magnifier. A zoom of 1 will translate to no magnification (the content will
         * be just copied to the magnifier with no scaling). The zoom defaults to 1.25.
         * Note that the zoom can also be changed after the instance is built, using the
         * {@link Magnifier#setZoom(float)} method.
         * @param zoom the zoom to be set
         */
        @NonNull
        public Builder setInitialZoom(@FloatRange(from = 0f) float zoom) {
            Preconditions.checkArgumentPositive(zoom, "Zoom should be positive");
            mZoom = zoom;
            return this;
        }

        /**
         * Sets the elevation of the magnifier window, in pixels. Defaults to 4dp.
         * @param elevation the elevation to be set
         */
        @NonNull
        public Builder setElevation(@Px @FloatRange(from = 0) float elevation) {
            Preconditions.checkArgumentNonNegative(elevation, "Elevation should be non-negative");
            mElevation = elevation;
            return this;
        }

        /**
         * Sets the corner radius of the magnifier window, in pixels. Defaults to 2dp.
         * @param cornerRadius the corner radius to be set
         */
        @NonNull
        public Builder setCornerRadius(@Px @FloatRange(from = 0) float cornerRadius) {
            Preconditions.checkArgumentNonNegative(cornerRadius,
                    "Corner radius should be non-negative");
            mCornerRadius = cornerRadius;
            return this;
        }

        /**
         * Sets an overlay that will be drawn on the top of the magnifier.
         * In general, the overlay should not be opaque, in order to let the magnified
         * content be partially visible in the magnifier. The default overlay is {@code null}
         * (no overlay). As an example, TextView applies a white {@link ColorDrawable}
         * overlay with 5% alpha, aiming to make the magnifier distinguishable when shown in dark
         * application regions. To disable the overlay, the parameter should be set
         * to {@code null}. If not null, the overlay will be automatically redrawn
         * when the drawable is invalidated. To achieve this, the magnifier will set a new
         * {@link android.graphics.drawable.Drawable.Callback} for the overlay drawable,
         * so keep in mind that any existing one set by the application will be lost.
         * @param overlay the overlay to be drawn on top
         */
        @NonNull
        public Builder setOverlay(@Nullable Drawable overlay) {
            mOverlay = overlay;
            return this;
        }

        /**
         * Sets an offset that should be added to the content source center to obtain
         * the position of the magnifier window, when the {@link #show(float, float)}
         * method is called. The offset is ignored when {@link #show(float, float, float, float)}
         * is used. The offset can be negative. It defaults to (0dp, 0dp).
         * @param horizontalOffset the horizontal component of the offset
         * @param verticalOffset the vertical component of the offset
         */
        @NonNull
        public Builder setDefaultSourceToMagnifierOffset(@Px int horizontalOffset,
                @Px int verticalOffset) {
            mHorizontalDefaultSourceToMagnifierOffset = horizontalOffset;
            mVerticalDefaultSourceToMagnifierOffset = verticalOffset;
            return this;
        }

        /**
         * Defines the behavior of the magnifier when it is requested to position outside the
         * surface of the main application window. The default value is {@code true}, which means
         * that the position will be adjusted such that the magnifier will be fully within the
         * bounds of the main application window, while also avoiding any overlap with system insets
         * (such as the one corresponding to the status bar). If this flag is set to {@code false},
         * the area where the magnifier can be positioned will no longer be clipped, so the
         * magnifier will be able to extend outside the main application window boundaries (and also
         * overlap the system insets). This can be useful if you require a custom behavior, but it
         * should be handled with care, when passing coordinates to {@link #show(float, float)};
         * note that:
         * <ul>
         *   <li>in a multiwindow context, if the magnifier crosses the boundary between the two
         *   windows, it will not be able to show over the window of the other application</li>
         *   <li>if the magnifier overlaps the status bar, there is no guarantee about which one
         *   will be displayed on top. This should be handled with care.</li>
         * </ul>
         * @param clip whether the magnifier position will be adjusted
         */
        @NonNull
        public Builder setClippingEnabled(boolean clip) {
            mClippingEnabled = clip;
            return this;
        }

        /**
         * Defines the bounds of the rectangle where the magnifier will be able to copy its content
         * from. The content will always be copied from the {@link Surface} of the main application
         * window unless the magnified view is a {@link SurfaceView}, in which case its backing
         * surface will be used. Each bound can have a different behavior, with the options being:
         * <ul>
         *   <li>{@link #SOURCE_BOUND_MAX_VISIBLE}, which extends the bound as much as possible
         *   while remaining in the visible region of the magnified view, as given by
         *   {@link android.view.View#getGlobalVisibleRect(Rect)}. For example, this will take into
         *   account the case when the view is contained in a scrollable container, and the
         *   magnifier will refuse to copy content outside of the visible view region</li>
         *   <li>{@link #SOURCE_BOUND_MAX_IN_SURFACE}, which extends the bound as much
         *   as possible while remaining inside the surface the content is copied from.</li>
         * </ul>
         * Note that if either of the first three options is used, the bound will be compared to
         * the bound of the surface (i.e. as if {@link #SOURCE_BOUND_MAX_IN_SURFACE} was used),
         * and the more restrictive one will be chosen. In other words, no attempt to copy content
         * from outside the surface will be permitted. If two opposite bounds are not well-behaved
         * (i.e. left + sourceWidth > right or top + sourceHeight > bottom), the left and top
         * bounds will have priority and the others will be extended accordingly. If the pairs
         * obtained this way still remain out of bounds, the smallest possible offset will be added
         * to the pairs to bring them inside the surface bounds. If this is impossible
         * (i.e. the surface is too small for the size of the content we try to copy on either
         * dimension), an error will be logged and the magnifier content will look distorted.
         * The default values assumed by the builder for the source bounds are
         * left: {@link #SOURCE_BOUND_MAX_VISIBLE}, top: {@link #SOURCE_BOUND_MAX_IN_SURFACE},
         * right: {@link #SOURCE_BOUND_MAX_VISIBLE}, bottom: {@link #SOURCE_BOUND_MAX_IN_SURFACE}.
         * @param left the left bound for content copy
         * @param top the top bound for content copy
         * @param right the right bound for content copy
         * @param bottom the bottom bound for content copy
         */
        @NonNull
        public Builder setSourceBounds(@SourceBound int left, @SourceBound int top,
                @SourceBound int right, @SourceBound int bottom) {
            mLeftContentBound = left;
            mTopContentBound = top;
            mRightContentBound = right;
            mBottomContentBound = bottom;
            return this;
        }

        /**
         * Sets the source width/height.
         */
        @NonNull
        Builder setSourceSize(int width, int height) {
            mSourceWidth = width;
            mSourceHeight = height;
            return this;
        }

        /**
         * Sets the magnifier as the new fish-eye style.
         */
        @NonNull
        Builder setFishEyeStyle() {
            mIsFishEyeStyle = true;
            return this;
        }

        /**
         * Builds a {@link Magnifier} instance based on the configuration of this {@link Builder}.
         */
        public @NonNull Magnifier build() {
            return new Magnifier(this);
        }
    }

    /**
     * A source bound that will extend as much as possible, while remaining within the surface
     * the content is copied from.
     */
    public static final int SOURCE_BOUND_MAX_IN_SURFACE = 0;

    /**
     * A source bound that will extend as much as possible, while remaining within the
     * visible region of the magnified view, as determined by
     * {@link View#getGlobalVisibleRect(Rect)}.
     */
    public static final int SOURCE_BOUND_MAX_VISIBLE = 1;


    /**
     * Used to describe the {@link Surface} rectangle where the magnifier's content is allowed
     * to be copied from. For more details, see method
     * {@link Magnifier.Builder#setSourceBounds(int, int, int, int)}
     *
     * @hide
     */
    @IntDef({SOURCE_BOUND_MAX_IN_SURFACE, SOURCE_BOUND_MAX_VISIBLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SourceBound {}

    // The rest of the file consists of test APIs and methods relevant for tests.

    /**
     * See {@link #setOnOperationCompleteCallback(Callback)}.
     */
    @TestApi
    private Callback mCallback;

    /**
     * Sets a callback which will be invoked at the end of the next
     * {@link #show(float, float)} or {@link #update()} operation.
     *
     * @hide
     */
    @TestApi
    public void setOnOperationCompleteCallback(final Callback callback) {
        mCallback = callback;
        if (mWindow != null) {
            mWindow.mCallback = callback;
        }
    }

    /**
     * @return the drawing being currently displayed in the magnifier, as bitmap
     *
     * @hide
     */
    @TestApi
    public @Nullable Bitmap getContent() {
        if (mWindow == null) {
            return null;
        }
        synchronized (mWindow.mLock) {
            return mWindow.mCurrentContent;
        }
    }

    /**
     * Returns a bitmap containing the content that was magnified and drew to the
     * magnifier, at its original size, without the overlay applied.
     * @return the content that is magnified, as bitmap
     *
     * @hide
     */
    @TestApi
    public @Nullable Bitmap getOriginalContent() {
        if (mWindow == null) {
            return null;
        }
        synchronized (mWindow.mLock) {
            return Bitmap.createBitmap(mWindow.mBitmap);
        }
    }

    /**
     * @return the size of the magnifier window in dp
     *
     * @hide
     */
    @TestApi
    public static PointF getMagnifierDefaultSize() {
        final Resources resources = Resources.getSystem();
        final float density = resources.getDisplayMetrics().density;
        final PointF size = new PointF();
        size.x = resources.getDimension(R.dimen.default_magnifier_width) / density;
        size.y = resources.getDimension(R.dimen.default_magnifier_height) / density;
        return size;
    }

    /**
     * @hide
     */
    @TestApi
    public interface Callback {
        /**
         * Callback called after the drawing for a magnifier update has happened.
         */
        void onOperationComplete();
    }
}
