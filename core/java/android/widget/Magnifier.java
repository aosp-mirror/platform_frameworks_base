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

    // The view to which this magnifier is attached.
    private final View mView;
    // The coordinates of the view in the surface.
    private final int[] mViewCoordinatesInSurface;
    // The window containing the magnifier.
    private InternalPopupWindow mWindow;
    // The width of the window containing the magnifier.
    private final int mWindowWidth;
    // The height of the window containing the magnifier.
    private final int mWindowHeight;
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
        mSourceWidth = Math.round(mWindowWidth / mZoom);
        mSourceHeight = Math.round(mWindowHeight / mZoom);
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
        obtainWindowCoordinates(magnifierCenterX, magnifierCenterY);

        final int startX = mClampedCenterZoomCoords.x - mSourceWidth / 2;
        final int startY = mClampedCenterZoomCoords.y - mSourceHeight / 2;
        if (sourceCenterX != mPrevShowSourceCoords.x || sourceCenterY != mPrevShowSourceCoords.y
                || mDirtyState) {
            if (mWindow == null) {
                synchronized (mLock) {
                    mWindow = new InternalPopupWindow(mView.getContext(), mView.getDisplay(),
                            mParentSurface.mSurfaceControl, mWindowWidth, mWindowHeight,
                            mWindowElevation, mWindowCornerRadius,
                            mOverlay != null ? mOverlay : new ColorDrawable(Color.TRANSPARENT),
                            Handler.getMain() /* draw the magnifier on the UI thread */, mLock,
                            mCallback);
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
        mSourceWidth = Math.round(mWindowWidth / mZoom);
        mSourceHeight = Math.round(mWindowHeight / mZoom);
        mDirtyState = true;
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
     * Returns the top left coordinates of the magnifier, relative to the surface of the
     * main application window. They will be determined by the coordinates of the last
     * {@link #show(float, float)} or {@link #show(float, float, float, float)} call, adjusted
     * to take into account any potential clamping behavior. The method can be used immediately
     * after a #show call to find out where the magnifier will be positioned. However, the
     * position of the magnifier will not be updated in the same frame due to the async
     * copying of the content copying and of the magnifier rendering.
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
        return new Point(getCurrentClampedWindowCoordinates());
    }

    /**
     * Returns the top left coordinates of the magnifier source (i.e. the view region going to
     * be magnified and copied to the magnifier), relative to the surface the content is copied
     * from. The content will be copied:
     * - if the magnified view is a {@link SurfaceView}, from the surface backing it
     * - otherwise, from the surface of the main application window
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
        return new Point(mPixelCopyRequestRect.left, mPixelCopyRequestRect.top);
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
                                surfaceWidth, surfaceHeight, true);
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
                        surfaceFrame.right, surfaceFrame.bottom, false);
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
                    + "the content requested size. This will probably lead to distorted content.");
        }
        resolvedRight = Math.max(resolvedRight, resolvedLeft + mSourceWidth);
        resolvedBottom = Math.max(resolvedBottom, resolvedTop + mSourceHeight);

        // Finally compute the coordinates of the source center.
        mClampedCenterZoomCoords.x = Math.max(resolvedLeft + mSourceWidth / 2, Math.min(
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

    private void performPixelCopy(final int startXInSurface, final int startYInSurface,
            final boolean updateWindowPosition) {
        if (mContentCopySurface.mSurface == null || !mContentCopySurface.mSurface.isValid()) {
            return;
        }

        // Clamp window coordinates inside the parent surface, to avoid displaying
        // the magnifier out of screen or overlapping with system insets.
        final Point windowCoords = getCurrentClampedWindowCoordinates();

        // Perform the pixel copy.
        mPixelCopyRequestRect.set(startXInSurface,
                startYInSurface,
                startXInSurface + mSourceWidth,
                startYInSurface + mSourceHeight);
        final InternalPopupWindow currentWindowInstance = mWindow;
        final Bitmap bitmap =
                Bitmap.createBitmap(mSourceWidth, mSourceHeight, Bitmap.Config.ARGB_8888);
        PixelCopy.request(mContentCopySurface.mSurface, mPixelCopyRequestRect, bitmap,
                result -> {
                    synchronized (mLock) {
                        if (mWindow != currentWindowInstance) {
                            // The magnifier was dismissed (and maybe shown again) in the meantime.
                            return;
                        }
                        if (updateWindowPosition) {
                            // TODO: pull the position update outside #performPixelCopy
                            mWindow.setContentPositionForNextDraw(windowCoords.x, windowCoords.y);
                        }
                        mWindow.updateContent(bitmap);
                    }
                },
                sPixelCopyHandlerThread.getThreadHandler());
        mPrevStartCoordsInSurface.x = startXInSurface;
        mPrevStartCoordsInSurface.y = startYInSurface;
        mDirtyState = false;
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
            windowBounds = new Rect(systemInsets.left, systemInsets.top,
                    mParentSurface.mWidth - systemInsets.right,
                    mParentSurface.mHeight - systemInsets.bottom);
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
        public static final SurfaceInfo NULL = new SurfaceInfo(null, null, 0, 0, false);

        private Surface mSurface;
        private SurfaceControl mSurfaceControl;
        private int mWidth;
        private int mHeight;
        private boolean mIsMainWindowSurface;

        SurfaceInfo(final SurfaceControl surfaceControl, final Surface surface,
                final int width, final int height,
                final boolean isMainWindowSurface) {
            mSurfaceControl = surfaceControl;
            mSurface = surface;
            mWidth = width;
            mHeight = height;
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
        private final int mContentHeight;
        // The size of the allocated surface.
        private final int mSurfaceWidth;
        private final int mSurfaceHeight;
        // The insets of the content inside the allocated surface.
        private final int mOffsetX;
        private final int mOffsetY;
        // The overlay to be drawn on the top of the content.
        private final Drawable mOverlay;
        // The surface we allocate for the magnifier content + shadow.
        private final SurfaceSession mSurfaceSession;
        private final SurfaceControl mSurfaceControl;
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
        // The position of the magnifier content when the last draw was requested.
        private int mLastDrawContentPositionX;
        private int mLastDrawContentPositionY;

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

        // The lock used to synchronize the UI and render threads when a #destroy
        // is performed on the UI thread and a frame callback on the render thread.
        // When both mLock and mDestroyLock need to be held at the same time,
        // mDestroyLock should be acquired before mLock in order to avoid deadlocks.
        private final Object mDestroyLock = new Object();

        // The current content of the magnifier. It is mBitmap + mOverlay, only used for testing.
        private Bitmap mCurrentContent;

        InternalPopupWindow(final Context context, final Display display,
                final SurfaceControl parentSurfaceControl, final int width, final int height,
                final float elevation, final float cornerRadius, final Drawable overlay,
                final Handler handler, final Object lock, final Callback callback) {
            mDisplay = display;
            mOverlay = overlay;
            mLock = lock;
            mCallback = callback;

            mContentWidth = width;
            mContentHeight = height;
            mOffsetX = (int) (1.05f * elevation);
            mOffsetY = (int) (1.05f * elevation);
            // Setup the surface we will use for drawing the content and shadow.
            mSurfaceWidth = mContentWidth + 2 * mOffsetX;
            mSurfaceHeight = mContentHeight + 2 * mOffsetY;
            mSurfaceSession = new SurfaceSession();
            mSurfaceControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setBufferSize(mSurfaceWidth, mSurfaceHeight)
                    .setName("magnifier surface")
                    .setFlags(SurfaceControl.HIDDEN)
                    .setParent(parentSurfaceControl)
                    .build();
            mSurface = new Surface();
            mSurface.copyFrom(mSurfaceControl);

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

            final RecordingCanvas canvas = mRenderer.getRootNode().start(width, height);
            try {
                canvas.insertReorderBarrier();
                canvas.drawRenderNode(mBitmapRenderNode);
                canvas.insertInorderBarrier();
                canvas.drawRenderNode(mOverlayRenderNode);
                canvas.insertInorderBarrier();
            } finally {
                mRenderer.getRootNode().end(canvas);
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

            // Create a dummy draw, which will be replaced later with real drawing.
            final RecordingCanvas canvas = bitmapRenderNode.start(mContentWidth, mContentHeight);
            try {
                canvas.drawColor(0xFF00FF00);
            } finally {
                bitmapRenderNode.end(canvas);
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
                    mOverlayRenderNode.startRecording(mContentWidth, mContentHeight);
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
         * Destroys this instance.
         */
        public void destroy() {
            synchronized (mDestroyLock) {
                mSurface.destroy();
            }
            synchronized (mLock) {
                mRenderer.destroy();
                mSurfaceControl.remove();
                mSurfaceSession.kill();
                mHandler.removeCallbacks(mMagnifierUpdater);
                if (mBitmap != null) {
                    mBitmap.recycle();
                }
            }
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
                        mBitmapRenderNode.start(mContentWidth, mContentHeight);
                try {
                    final Rect srcRect = new Rect(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
                    final Rect dstRect = new Rect(0, 0, mContentWidth, mContentHeight);
                    final Paint paint = new Paint();
                    paint.setFilterBitmap(true);
                    canvas.drawBitmap(mBitmap, srcRect, dstRect, paint);
                } finally {
                    mBitmapRenderNode.end(canvas);
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
                        synchronized (mDestroyLock) {
                            if (!mSurface.isValid()) {
                                return;
                            }
                            synchronized (mLock) {
                                // Show or move the window at the content draw frame.
                                SurfaceControl.openTransaction();
                                mSurfaceControl.deferTransactionUntil(mSurface, frame);
                                if (updateWindowPosition) {
                                    mSurfaceControl.setPosition(pendingX, pendingY);
                                }
                                if (firstDraw) {
                                    mSurfaceControl.setLayer(SURFACE_Z);
                                    mSurfaceControl.show();
                                }
                                SurfaceControl.closeTransaction();
                            }
                        }
                    };
                    mRenderer.setLightCenter(mDisplay, pendingX, pendingY);
                } else {
                    callback = null;
                }

                mLastDrawContentPositionX = mWindowPositionX + mOffsetX;
                mLastDrawContentPositionY = mWindowPositionY + mOffsetY;
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
    public static class Builder {
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

        /**
         * Construct a new builder for {@link Magnifier} objects.
         * @param view the view this magnifier is attached to
         */
        public Builder(@NonNull View view) {
            mView = Preconditions.checkNotNull(view);
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
