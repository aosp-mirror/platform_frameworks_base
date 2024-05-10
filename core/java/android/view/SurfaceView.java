/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import static android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
import static android.view.WindowManagerPolicyConstants.APPLICATION_MEDIA_OVERLAY_SUBLAYER;
import static android.view.WindowManagerPolicyConstants.APPLICATION_MEDIA_SUBLAYER;
import static android.view.WindowManagerPolicyConstants.APPLICATION_PANEL_SUBLAYER;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.CompatibilityInfo.Translator;
import android.graphics.BLASTBufferQueue;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RenderNode;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceControl.Transaction;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityEmbeddedConnection;
import android.window.SurfaceSyncGroup;

import com.android.graphics.hwui.flags.Flags;
import com.android.internal.view.SurfaceCallbackHelper;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Provides a dedicated drawing surface embedded inside of a view hierarchy.
 * You can control the format of this surface and, if you like, its size; the
 * SurfaceView takes care of placing the surface at the correct location on the
 * screen
 *
 * <p>The surface is Z ordered so that it is behind the window holding its
 * SurfaceView; the SurfaceView punches a hole in its window to allow its
 * surface to be displayed. The view hierarchy will take care of correctly
 * compositing with the Surface any siblings of the SurfaceView that would
 * normally appear on top of it. This can be used to place overlays such as
 * buttons on top of the Surface, though note however that it can have an
 * impact on performance since a full alpha-blended composite will be performed
 * each time the Surface changes.
 *
 * <p> The transparent region that makes the surface visible is based on the
 * layout positions in the view hierarchy. If the post-layout transform
 * properties are used to draw a sibling view on top of the SurfaceView, the
 * view may not be properly composited with the surface.
 *
 * <p>Access to the underlying surface is provided via the SurfaceHolder interface,
 * which can be retrieved by calling {@link #getHolder}.
 *
 * <p>The Surface will be created for you while the SurfaceView's window is
 * visible; you should implement {@link SurfaceHolder.Callback#surfaceCreated}
 * and {@link SurfaceHolder.Callback#surfaceDestroyed} to discover when the
 * Surface is created and destroyed as the window is shown and hidden.
 *
 * <p>One of the purposes of this class is to provide a surface in which a
 * secondary thread can render into the screen. If you are going to use it
 * this way, you need to be aware of some threading semantics:
 *
 * <ul>
 * <li> All SurfaceView and
 * {@link SurfaceHolder.Callback SurfaceHolder.Callback} methods will be called
 * from the thread running the SurfaceView's window (typically the main thread
 * of the application). They thus need to correctly synchronize with any
 * state that is also touched by the drawing thread.
 * <li> You must ensure that the drawing thread only touches the underlying
 * Surface while it is valid -- between
 * {@link SurfaceHolder.Callback#surfaceCreated SurfaceHolder.Callback.surfaceCreated()}
 * and
 * {@link SurfaceHolder.Callback#surfaceDestroyed SurfaceHolder.Callback.surfaceDestroyed()}.
 * </ul>
 *
 * <p class="note"><strong>Note:</strong> Starting in platform version
 * {@link android.os.Build.VERSION_CODES#N}, SurfaceView's window position is
 * updated synchronously with other View rendering. This means that translating
 * and scaling a SurfaceView on screen will not cause rendering artifacts. Such
 * artifacts may occur on previous versions of the platform when its window is
 * positioned asynchronously.</p>
 *
 * <p class="note"><strong>Note:</strong> Starting in platform version
 * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, SurfaceView will support arbitrary
 * alpha blending. Prior platform versions ignored alpha values on the SurfaceView if they were
 * between 0 and 1. If the SurfaceView is configured with Z-above, then the alpha is applied
 * directly to the Surface. If the SurfaceView is configured with Z-below, then the alpha is
 * applied to the hole punch directly. Note that when using Z-below, overlapping SurfaceViews
 * may not blend properly as a consequence of not applying alpha to the surface content directly.
 */
public class SurfaceView extends View implements ViewRootImpl.SurfaceChangedCallback {
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SURFACE_LIFECYCLE_"},
            value = {SURFACE_LIFECYCLE_DEFAULT,
                     SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY,
                     SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT})
    public @interface SurfaceLifecycleStrategy {}

    /**
     * Default lifecycle of the Surface owned by this SurfaceView.
     *
     * The default lifecycle matches {@link #SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY}.
     */
    public static final int SURFACE_LIFECYCLE_DEFAULT = 0;

    /**
     * The Surface lifecycle is tied to SurfaceView visibility.
     *
     * The Surface is created when the SurfaceView becomes visible, and is destroyed when the
     * SurfaceView is no longer visible.
     */
    public static final int SURFACE_LIFECYCLE_FOLLOWS_VISIBILITY = 1;

    /**
     * The Surface lifecycle is tied to SurfaceView attachment.
     * The Surface is created when the SurfaceView first becomes attached, but is not destroyed
     * until this SurfaceView has been detached from the current window.
     */
    public static final int SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT = 2;

    private static final String TAG = "SurfaceView";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_POSITION = false;

    private static final long FORWARD_BACK_KEY_TOLERANCE_MS = 100;

    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Track {@link SurfaceHolder#addCallback} instead")
    final ArrayList<SurfaceHolder.Callback> mCallbacks = new ArrayList<>();

    final int[] mLocation = new int[2];

    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@link SurfaceHolder#lockCanvas} instead")
    final ReentrantLock mSurfaceLock = new ReentrantLock();
    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@link SurfaceHolder#getSurface} instead")
    final Surface mSurface = new Surface();       // Current surface in use
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023,
                         publicAlternatives = "Use {@link View#getVisibility} instead")
    boolean mDrawingStopped = true;
    // We use this to track if the application has produced a frame
    // in to the Surface. Up until that point, we should be careful not to punch
    // holes.
    boolean mDrawFinished = false;

    final Rect mScreenRect = new Rect();
    private final SurfaceSession mSurfaceSession = new SurfaceSession();

    SurfaceControl mSurfaceControl;
    SurfaceControl mBackgroundControl;
    private boolean mDisableBackgroundLayer = false;

    @SurfaceLifecycleStrategy
    private int mRequestedSurfaceLifecycleStrategy = SURFACE_LIFECYCLE_DEFAULT;
    @SurfaceLifecycleStrategy
    private int mSurfaceLifecycleStrategy = SURFACE_LIFECYCLE_DEFAULT;

    /**
     * We use this lock to protect access to mSurfaceControl. Both are accessed on the UI
     * thread and the render thread via RenderNode.PositionUpdateListener#positionLost.
     */
    final Object mSurfaceControlLock = new Object();
    final Rect mTmpRect = new Rect();

    Paint mRoundedViewportPaint;

    int mSubLayer = APPLICATION_MEDIA_SUBLAYER;
    int mRequestedSubLayer = APPLICATION_MEDIA_SUBLAYER;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023,
                         publicAlternatives = "Use {@link SurfaceHolder#isCreating} instead")
    boolean mIsCreating = false;

    private final ViewTreeObserver.OnScrollChangedListener mScrollChangedListener =
            this::updateSurface;

    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Rely on {@link ViewTreeObserver#dispatchOnPreDraw} instead")
    private final ViewTreeObserver.OnPreDrawListener mDrawListener = () -> {
        // reposition ourselves where the surface is
        mHaveFrame = getWidth() > 0 && getHeight() > 0;
        updateSurface();
        return true;
    };

    boolean mRequestedVisible = false;
    boolean mWindowVisibility = false;
    boolean mLastWindowVisibility = false;
    boolean mViewVisibility = false;
    boolean mWindowStopped = false;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023,
                         publicAlternatives = "Use {@link View#getWidth} instead")
    int mRequestedWidth = -1;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023,
                         publicAlternatives = "Use {@link View#getHeight} instead")
    int mRequestedHeight = -1;
    /* Set SurfaceView's format to 565 by default to maintain backward
     * compatibility with applications assuming this format.
     */
    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@code SurfaceHolder.Callback#surfaceChanged} instead")
    int mRequestedFormat = PixelFormat.RGB_565;

    float mAlpha = 1f;
    boolean mClipSurfaceToBounds;
    int mBackgroundColor = Color.BLACK;

    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@link View#getWidth} and {@link View#getHeight} to "
                    + "determine if the SurfaceView is onscreen and has a frame")
    boolean mHaveFrame = false;
    boolean mSurfaceCreated = false;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023,
                         publicAlternatives = "Time {@link SurfaceHolder#lockCanvas} instead")
    long mLastLockTime = 0;

    boolean mVisible = false;
    int mWindowSpaceLeft = -1;
    int mWindowSpaceTop = -1;
    int mSurfaceWidth = -1;
    int mSurfaceHeight = -1;
    float mCornerRadius;
    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@code SurfaceHolder.Callback#surfaceChanged} "
            + "instead")
    int mFormat = -1;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023,
                         publicAlternatives = "Use {@link SurfaceHolder#getSurfaceFrame} instead")
    final Rect mSurfaceFrame = new Rect();
    int mLastSurfaceWidth = -1, mLastSurfaceHeight = -1;
    @SurfaceControl.BufferTransform int mTransformHint = 0;

    private boolean mGlobalListenersAdded;
    private boolean mAttachedToWindow;

    private int mSurfaceFlags = SurfaceControl.HIDDEN;

    private final ArraySet<SurfaceSyncGroup> mSyncGroups = new ArraySet<>();

    /**
     * Transaction that should be used from the render thread. This transaction is only thread safe
     * with other calls directly from the render thread.
     */
    private final SurfaceControl.Transaction mRtTransaction = new SurfaceControl.Transaction();

    /**
     * Transaction that should be used whe
     * {@link HardwareRenderer.FrameDrawingCallback#onFrameDraw} is invoked. All
     * frame callbacks can use the same transaction since they will be thread safe
     */
    private final SurfaceControl.Transaction mFrameCallbackTransaction =
            new SurfaceControl.Transaction();

    private int mParentSurfaceSequenceId;

    private RemoteAccessibilityController mRemoteAccessibilityController =
        new RemoteAccessibilityController(this);

    private final Matrix mTmpMatrix = new Matrix();

    SurfaceControlViewHost.SurfacePackage mSurfacePackage;


    private SurfaceControl mBlastSurfaceControl;
    private BLASTBufferQueue mBlastBufferQueue;

    private final ConcurrentLinkedQueue<WindowManager.LayoutParams> mEmbeddedWindowParams =
            new ConcurrentLinkedQueue<>();

    private final ISurfaceControlViewHostParent mSurfaceControlViewHostParent =
            new ISurfaceControlViewHostParent.Stub() {
        @Override
        public void updateParams(WindowManager.LayoutParams[] childAttrs) {
            mEmbeddedWindowParams.clear();
            mEmbeddedWindowParams.addAll(Arrays.asList(childAttrs));

            if (isAttachedToWindow()) {
                runOnUiThread(() -> {
                    if (mParent != null) {
                        mParent.recomputeViewAttributes(SurfaceView.this);
                    }
                });
            }
        }

        @Override
        public void forwardBackKeyToParent(@NonNull KeyEvent keyEvent) {
                runOnUiThread(() -> {
                    if (!isAttachedToWindow() || keyEvent.getKeyCode() != KeyEvent.KEYCODE_BACK) {
                        return;
                    }
                    final ViewRootImpl vri = getViewRootImpl();
                    if (vri == null) {
                        return;
                    }
                    final InputManager inputManager = mContext.getSystemService(InputManager.class);
                    if (inputManager == null) {
                        return;
                    }
                    // Check that the event was created recently.
                    final long timeDiff = SystemClock.uptimeMillis() - keyEvent.getEventTime();
                    if (timeDiff > FORWARD_BACK_KEY_TOLERANCE_MS) {
                        Log.e(TAG, "Ignore the input event that exceed the tolerance time, "
                                + "exceed " + timeDiff + "ms");
                        return;
                    }
                    if (inputManager.verifyInputEvent(keyEvent) == null) {
                        Log.e(TAG, "Received invalid input event");
                        return;
                    }
                    try {
                        vri.processingBackKey(true);
                        vri.enqueueInputEvent(keyEvent, null /* receiver */, 0 /* flags */,
                                true /* processImmediately */);
                    } finally {
                        vri.processingBackKey(false);
                    }
                });
        }
    };

    private final boolean mRtDrivenClipping = Flags.clipSurfaceviews();

    public SurfaceView(Context context) {
        this(context, null);
    }

    public SurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public SurfaceView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        this(context, attrs, defStyleAttr, defStyleRes, false);
    }

    /** @hide */
    public SurfaceView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes, boolean disableBackgroundLayer) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWillNotDraw(true);
        mDisableBackgroundLayer = disableBackgroundLayer;
    }

    /**
     * Return the SurfaceHolder providing access and control over this
     * SurfaceView's underlying surface.
     *
     * @return SurfaceHolder The holder of the surface.
     */
    public SurfaceHolder getHolder() {
        return mSurfaceHolder;
    }

    private void updateRequestedVisibility() {
        mRequestedVisible = mViewVisibility && mWindowVisibility && !mWindowStopped;
    }

    private void setWindowStopped(boolean stopped) {
        mWindowStopped = stopped;
        updateRequestedVisibility();
        updateSurface();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        getViewRootImpl().addSurfaceChangedCallback(this);
        mWindowStopped = false;

        mViewVisibility = getVisibility() == VISIBLE;
        updateRequestedVisibility();

        mAttachedToWindow = true;
        mParent.requestTransparentRegion(SurfaceView.this);
        if (!mGlobalListenersAdded) {
            ViewTreeObserver observer = getViewTreeObserver();
            observer.addOnScrollChangedListener(mScrollChangedListener);
            observer.addOnPreDrawListener(mDrawListener);
            mGlobalListenersAdded = true;
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mWindowVisibility = visibility == VISIBLE;
        updateRequestedVisibility();
        updateSurface();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        mViewVisibility = visibility == VISIBLE;
        boolean newRequestedVisible = mWindowVisibility && mViewVisibility && !mWindowStopped;
        if (newRequestedVisible != mRequestedVisible) {
            // our base class (View) invalidates the layout only when
            // we go from/to the GONE state. However, SurfaceView needs
            // to request a re-layout when the visibility changes at all.
            // This is needed because the transparent region is computed
            // as part of the layout phase, and it changes (obviously) when
            // the visibility changes.
            requestLayout();
        }
        mRequestedVisible = newRequestedVisible;
        updateSurface();
    }

    /**
     * Make alpha value of this view reflect onto the surface. This can only be called from at most
     * one SurfaceView within a view tree.
     *
     * <p class="note"><strong>Note:</strong> Alpha value of the view is ignored and the underlying
     * surface is rendered opaque by default.</p>
     *
     * @hide
     */
    public void setUseAlpha() {
        // TODO(b/241474646): Remove me
        return;
    }

    @Override
    public void setAlpha(float alpha) {
        if (DEBUG) {
            Log.d(TAG, System.identityHashCode(this)
                    + " setAlpha: alpha=" + alpha);
        }
        super.setAlpha(alpha);
    }

    @Override
    protected boolean onSetAlpha(int alpha) {
        if (Math.round(mAlpha * 255) != alpha) {
            updateSurface();
        }
        return true;
    }

    private void performDrawFinished() {
        mDrawFinished = true;
        if (mAttachedToWindow) {
            mParent.requestTransparentRegion(SurfaceView.this);
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        ViewRootImpl viewRoot = getViewRootImpl();
        // It's possible to create a SurfaceView using the default constructor and never
        // attach it to a view hierarchy, this is a common use case when dealing with
        // OpenGL. A developer will probably create a new GLSurfaceView, and let it manage
        // the lifecycle. Instead of attaching it to a view, they can just pass
        // the SurfaceHolder forward, most live wallpapers do it.
        if (viewRoot != null) {
            viewRoot.removeSurfaceChangedCallback(this);
        }

        mAttachedToWindow = false;
        if (mGlobalListenersAdded) {
            ViewTreeObserver observer = getViewTreeObserver();
            observer.removeOnScrollChangedListener(mScrollChangedListener);
            observer.removeOnPreDrawListener(mDrawListener);
            mGlobalListenersAdded = false;
        }
        if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " Detaching SV");

        mRequestedVisible = false;

        updateSurface();
        releaseSurfaces(true /* releaseSurfacePackage*/);

        mHaveFrame = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = mRequestedWidth >= 0
                ? resolveSizeAndState(mRequestedWidth, widthMeasureSpec, 0)
                : getDefaultSize(0, widthMeasureSpec);
        int height = mRequestedHeight >= 0
                ? resolveSizeAndState(mRequestedHeight, heightMeasureSpec, 0)
                : getDefaultSize(0, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    /** @hide */
    @Override
    @UnsupportedAppUsage
    protected boolean setFrame(int left, int top, int right, int bottom) {
        boolean result = super.setFrame(left, top, right, bottom);
        updateSurface();
        return result;
    }

    @Override
    public boolean gatherTransparentRegion(Region region) {
        if (isAboveParent() || !mDrawFinished) {
            return super.gatherTransparentRegion(region);
        }

        boolean opaque = true;
        if ((mPrivateFlags & PFLAG_SKIP_DRAW) == 0) {
            // this view draws, remove it from the transparent region
            opaque = super.gatherTransparentRegion(region);
        } else if (region != null) {
            int w = getWidth();
            int h = getHeight();
            if (w>0 && h>0) {
                getLocationInWindow(mLocation);
                // otherwise, punch a hole in the whole hierarchy
                int l = mLocation[0];
                int t = mLocation[1];
                region.op(l, t, l+w, t+h, Region.Op.UNION);
            }
        }
        if (PixelFormat.formatHasAlpha(mRequestedFormat)) {
            opaque = false;
        }
        return opaque;
    }

    @Override
    public void draw(Canvas canvas) {
        if (mDrawFinished && !isAboveParent()) {
            // draw() is not called when SKIP_DRAW is set
            if ((mPrivateFlags & PFLAG_SKIP_DRAW) == 0) {
                // punch a whole in the view-hierarchy below us
                clearSurfaceViewPort(canvas);
            }
        }
        super.draw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (mDrawFinished && !isAboveParent()) {
            // draw() is not called when SKIP_DRAW is set
            if ((mPrivateFlags & PFLAG_SKIP_DRAW) == PFLAG_SKIP_DRAW) {
                // punch a whole in the view-hierarchy below us
                clearSurfaceViewPort(canvas);
            }
        }
        super.dispatchDraw(canvas);
    }

    /**
     * Control whether the surface is clipped to the same bounds as the View. If true, then
     * the bounds set by {@link #setClipBounds(Rect)} are applied to the surface as window-crop.
     *
     * @param enabled whether to enable surface clipping
     * @hide
     */
    public void setEnableSurfaceClipping(boolean enabled) {
        mClipSurfaceToBounds = enabled;
        invalidate();
    }

    @Override
    public void setClipBounds(Rect clipBounds) {
        super.setClipBounds(clipBounds);

        if (mRtDrivenClipping && isHardwareAccelerated()) {
            return;
        }

        if (!mClipSurfaceToBounds || mSurfaceControl == null) {
            return;
        }

        // When cornerRadius is non-zero, a draw() is required to update
        // the viewport (rounding the corners of the clipBounds).
        if (mCornerRadius > 0f && !isAboveParent()) {
            invalidate();
        }

        if (mClipBounds != null) {
            mTmpRect.set(mClipBounds);
        } else {
            mTmpRect.set(0, 0, mSurfaceWidth, mSurfaceHeight);
        }
        final Transaction transaction = new Transaction();
        transaction.setWindowCrop(mSurfaceControl, mTmpRect);
        applyTransactionOnVriDraw(transaction);
        invalidate();
    }

    @Override
    public boolean hasOverlappingRendering() {
        // SurfaceViews only alpha composite by modulating the Surface alpha for Z-above, or
        // applying alpha on the hole punch for Z-below - no deferral to a layer is necessary.
        return false;
    }

    private void clearSurfaceViewPort(Canvas canvas) {
        final float alpha = getAlpha();
        if (mCornerRadius > 0f) {
            canvas.getClipBounds(mTmpRect);
            if (mClipSurfaceToBounds && mClipBounds != null) {
                mTmpRect.intersect(mClipBounds);
            }
            canvas.punchHole(
                    mTmpRect.left,
                    mTmpRect.top,
                    mTmpRect.right,
                    mTmpRect.bottom,
                    mCornerRadius,
                    mCornerRadius,
                    alpha
            );
        } else {
            canvas.punchHole(0f, 0f, getWidth(), getHeight(), 0f, 0f, alpha);
        }
    }

    /**
     * Sets the corner radius for the SurfaceView. This will round both the corners of the
     * underlying surface, as well as the corners of the hole created to expose the surface.
     *
     * @param cornerRadius the new radius of the corners in pixels
     * @hide
     */
    public void setCornerRadius(float cornerRadius) {
        mCornerRadius = cornerRadius;
        if (mCornerRadius > 0f && mRoundedViewportPaint == null) {
            mRoundedViewportPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mRoundedViewportPaint.setBlendMode(BlendMode.CLEAR);
            mRoundedViewportPaint.setColor(0);
        }
        invalidate();
    }

    /**
     * Returns the corner radius for the SurfaceView.

     * @return the radius of the corners in pixels
     * @hide
     */
    public float getCornerRadius() {
        return mCornerRadius;
    }

    /**
     * Control whether the surface view's surface is placed on top of another
     * regular surface view in the window (but still behind the window itself).
     * This is typically used to place overlays on top of an underlying media
     * surface view.
     *
     * <p>Note that this must be set before the surface view's containing
     * window is attached to the window manager.
     *
     * <p>Calling this overrides any previous call to {@link #setZOrderOnTop}.
     */
    public void setZOrderMediaOverlay(boolean isMediaOverlay) {
        mRequestedSubLayer = isMediaOverlay
            ? APPLICATION_MEDIA_OVERLAY_SUBLAYER : APPLICATION_MEDIA_SUBLAYER;
    }

    /**
     * Control whether the surface view's surface is placed on top of its
     * window.  Normally it is placed behind the window, to allow it to
     * (for the most part) appear to composite with the views in the
     * hierarchy.  By setting this, you cause it to be placed above the
     * window.  This means that none of the contents of the window this
     * SurfaceView is in will be visible on top of its surface.
     *
     * <p>Note that this must be set before the surface view's containing
     * window is attached to the window manager. If you target {@link Build.VERSION_CODES#R}
     * the Z ordering can be changed dynamically if the backing surface is
     * created, otherwise it would be applied at surface construction time.
     *
     * <p>Calling this overrides any previous call to {@link #setZOrderMediaOverlay}.
     *
     * @param onTop Whether to show the surface on top of this view's window.
     */
    public void setZOrderOnTop(boolean onTop) {
        // In R and above we allow dynamic layer changes.
        final boolean allowDynamicChange = getContext().getApplicationInfo().targetSdkVersion
                > Build.VERSION_CODES.Q;
        setZOrderedOnTop(onTop, allowDynamicChange);
    }

    /**
     * @return Whether the surface backing this view appears on top of its parent.
     *
     * @hide
     */
    public boolean isZOrderedOnTop() {
        return mRequestedSubLayer > 0;
    }

    /**
     * Controls whether the surface view's surface is placed on top of its
     * window. Normally it is placed behind the window, to allow it to
     * (for the most part) appear to composite with the views in the
     * hierarchy. By setting this, you cause it to be placed above the
     * window. This means that none of the contents of the window this
     * SurfaceView is in will be visible on top of its surface.
     *
     * <p>Calling this overrides any previous call to {@link #setZOrderMediaOverlay}.
     *
     * @param onTop Whether to show the surface on top of this view's window.
     * @param allowDynamicChange Whether this can happen after the surface is created.
     * @return Whether the Z ordering changed.
     *
     * @hide
     */
    public boolean setZOrderedOnTop(boolean onTop, boolean allowDynamicChange) {
        final int subLayer;
        if (onTop) {
            subLayer = APPLICATION_PANEL_SUBLAYER;
        } else {
            subLayer = APPLICATION_MEDIA_SUBLAYER;
        }
        if (mRequestedSubLayer == subLayer) {
            return false;
        }
        mRequestedSubLayer = subLayer;

        if (!allowDynamicChange) {
            return false;
        }
        if (mSurfaceControl == null) {
            return true;
        }
        final ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot == null) {
            return true;
        }

        updateSurface();
        invalidate();
        return true;
    }

    /**
     * Control whether the surface view's content should be treated as secure,
     * preventing it from appearing in screenshots or from being viewed on
     * non-secure displays.
     *
     * <p>Note that this must be set before the surface view's containing
     * window is attached to the window manager.
     *
     * <p>See {@link android.view.Display#FLAG_SECURE} for details.
     *
     * @param isSecure True if the surface view is secure.
     */
    public void setSecure(boolean isSecure) {
        if (isSecure) {
            mSurfaceFlags |= SurfaceControl.SECURE;
        } else {
            mSurfaceFlags &= ~SurfaceControl.SECURE;
        }
    }

    /**
     * Controls the lifecycle of the Surface owned by this SurfaceView.
     *
     * <p>By default, the lifecycycle strategy employed by the SurfaceView is
     * {@link #SURFACE_LIFECYCLE_DEFAULT}.
     *
     * @param lifecycleStrategy The strategy for the lifecycle of the Surface owned by this
     * SurfaceView.
     */
    public void setSurfaceLifecycle(@SurfaceLifecycleStrategy int lifecycleStrategy) {
        mRequestedSurfaceLifecycleStrategy = lifecycleStrategy;
        updateSurface();
    }

    private void updateOpaqueFlag() {
        if (!PixelFormat.formatHasAlpha(mRequestedFormat)) {
            mSurfaceFlags |= SurfaceControl.OPAQUE;
        } else {
            mSurfaceFlags &= ~SurfaceControl.OPAQUE;
        }
    }

    private void updateBackgroundVisibility(Transaction t) {
        if (mBackgroundControl == null) {
            return;
        }
        if ((mSubLayer < 0) && ((mSurfaceFlags & SurfaceControl.OPAQUE) != 0)
                && !mDisableBackgroundLayer) {
            t.show(mBackgroundControl);
        } else {
            t.hide(mBackgroundControl);
        }
    }

    private Transaction updateBackgroundColor(Transaction t) {
        final float[] colorComponents = new float[] { Color.red(mBackgroundColor) / 255.f,
                Color.green(mBackgroundColor) / 255.f, Color.blue(mBackgroundColor) / 255.f };
        t.setColor(mBackgroundControl, colorComponents);
        return t;
    }

    private void releaseSurfaces(boolean releaseSurfacePackage) {
        mAlpha = 1f;
        mSurface.destroy();
        synchronized (mSurfaceControlLock) {
            if (mBlastBufferQueue != null) {
                mBlastBufferQueue.destroy();
                mBlastBufferQueue = null;
            }

            final Transaction transaction = new Transaction();
            if (mSurfaceControl != null) {
                transaction.remove(mSurfaceControl);
                mSurfaceControl = null;
            }
            if (mBackgroundControl != null) {
                transaction.remove(mBackgroundControl);
                mBackgroundControl = null;
            }
            if (mBlastSurfaceControl != null) {
                transaction.remove(mBlastSurfaceControl);
                mBlastSurfaceControl = null;
            }

            if (mSurfacePackage != null) {
                try {
                    mSurfacePackage.getRemoteInterface().attachParentInterface(null);
                    mEmbeddedWindowParams.clear();
                } catch (RemoteException e) {
                    Log.d(TAG, "Failed to remove parent interface from SCVH. Likely SCVH is "
                            + "already dead");
                }
                if (releaseSurfacePackage) {
                    mSurfacePackage.release();
                    mSurfacePackage = null;
                }
            }

            applyTransactionOnVriDraw(transaction);
        }
    }

    // The position update listener is used to safely share the surface size between render thread
    // workers and the UI thread. Both threads need to know the surface size to determine the scale.
    // The parent layer scales the surface size to view size. The child (BBQ) layer scales
    // the buffer to the surface size. Both scales along with the window crop must be applied
    // synchronously otherwise we may see flickers.
    // When the listener is updated, we will get at least a single position update call so we can
    // guarantee any changes we post will be applied.
    private void replacePositionUpdateListener(int surfaceWidth, int surfaceHeight) {
        if (mPositionListener != null) {
            mRenderNode.removePositionUpdateListener(mPositionListener);
        }
        mPositionListener = new SurfaceViewPositionUpdateListener(surfaceWidth, surfaceHeight);
        mRenderNode.addPositionUpdateListener(mPositionListener);
    }

    private boolean performSurfaceTransaction(ViewRootImpl viewRoot, Translator translator,
            boolean creating, boolean sizeChanged, boolean hintChanged, boolean relativeZChanged,
            Transaction surfaceUpdateTransaction) {
        boolean realSizeChanged = false;

        mSurfaceLock.lock();
        try {
            mDrawingStopped = !surfaceShouldExist();

            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                    + "Cur surface: " + mSurface);

            // If we are creating the surface control or the parent surface has not
            // changed, then set relative z. Otherwise allow the parent
            // SurfaceChangedCallback to update the relative z. This is needed so that
            // we do not change the relative z before the server is ready to swap the
            // parent surface.
            if (creating) {
                updateRelativeZ(surfaceUpdateTransaction);
                if (mSurfacePackage != null) {
                    reparentSurfacePackage(surfaceUpdateTransaction, mSurfacePackage);
                }
            }
            mParentSurfaceSequenceId = viewRoot.getSurfaceSequenceId();

            // Only control visibility if we're not hardware-accelerated. Otherwise we'll
            // let renderthread drive since offscreen SurfaceControls should not be visible.
            if (!isHardwareAccelerated()) {
                if (mViewVisibility) {
                    surfaceUpdateTransaction.show(mSurfaceControl);
                } else {
                    surfaceUpdateTransaction.hide(mSurfaceControl);
                }
            }

            updateBackgroundVisibility(surfaceUpdateTransaction);
            updateBackgroundColor(surfaceUpdateTransaction);
            if (isAboveParent()) {
                float alpha = getAlpha();
                surfaceUpdateTransaction.setAlpha(mSurfaceControl, alpha);
            }

            if (relativeZChanged) {
                if (!isAboveParent()) {
                    // If we're moving from z-above to z-below, then restore the surface alpha back to 1
                    // and let the holepunch drive visibility and blending.
                    surfaceUpdateTransaction.setAlpha(mSurfaceControl, 1.f);
                }
                updateRelativeZ(surfaceUpdateTransaction);
            }

            surfaceUpdateTransaction.setCornerRadius(mSurfaceControl, mCornerRadius);
            if ((sizeChanged || hintChanged) && !creating) {
                setBufferSize(surfaceUpdateTransaction);
            }
            if (sizeChanged || creating || !isHardwareAccelerated()) {

                if (!mRtDrivenClipping || !isHardwareAccelerated()) {
                    // Set a window crop when creating the surface or changing its size to
                    // crop the buffer to the surface size since the buffer producer may
                    // use SCALING_MODE_SCALE and submit a larger size than the surface
                    // size.
                    if (mClipSurfaceToBounds && mClipBounds != null) {
                        surfaceUpdateTransaction.setWindowCrop(mSurfaceControl, mClipBounds);
                    } else {
                        surfaceUpdateTransaction.setWindowCrop(mSurfaceControl, mSurfaceWidth,
                                mSurfaceHeight);
                    }
                }

                surfaceUpdateTransaction.setDesintationFrame(mBlastSurfaceControl, mSurfaceWidth,
                            mSurfaceHeight);

                if (isHardwareAccelerated()) {
                    // This will consume the passed in transaction and the transaction will be
                    // applied on a render worker thread.
                    replacePositionUpdateListener(mSurfaceWidth, mSurfaceHeight);
                } else {
                    onSetSurfacePositionAndScale(surfaceUpdateTransaction, mSurfaceControl,
                            mScreenRect.left /*positionLeft*/,
                            mScreenRect.top /*positionTop*/,
                            mScreenRect.width() / (float) mSurfaceWidth /*postScaleX*/,
                            mScreenRect.height() / (float) mSurfaceHeight /*postScaleY*/);
                }
                if (DEBUG_POSITION) {
                    Log.d(TAG, TextUtils.formatSimple(
                            "%d performSurfaceTransaction %s "
                                + "position = [%d, %d, %d, %d] surfaceSize = %dx%d",
                            System.identityHashCode(this),
                            isHardwareAccelerated() ? "RenderWorker" : "UI Thread",
                            mScreenRect.left, mScreenRect.top, mScreenRect.right,
                            mScreenRect.bottom, mSurfaceWidth, mSurfaceHeight));
                }
            }
            applyTransactionOnVriDraw(surfaceUpdateTransaction);
            updateEmbeddedAccessibilityMatrix(false);

            mSurfaceFrame.left = 0;
            mSurfaceFrame.top = 0;
            if (translator == null) {
                mSurfaceFrame.right = mSurfaceWidth;
                mSurfaceFrame.bottom = mSurfaceHeight;
            } else {
                float appInvertedScale = translator.applicationInvertedScale;
                mSurfaceFrame.right = (int) (mSurfaceWidth * appInvertedScale + 0.5f);
                mSurfaceFrame.bottom = (int) (mSurfaceHeight * appInvertedScale + 0.5f);
            }
            final int surfaceWidth = mSurfaceFrame.right;
            final int surfaceHeight = mSurfaceFrame.bottom;
            realSizeChanged = mLastSurfaceWidth != surfaceWidth
                    || mLastSurfaceHeight != surfaceHeight;
            mLastSurfaceWidth = surfaceWidth;
            mLastSurfaceHeight = surfaceHeight;
        } finally {
            mSurfaceLock.unlock();
        }

        return realSizeChanged;
    }

    private boolean requiresSurfaceControlCreation(boolean formatChanged, boolean visibleChanged) {
        if (mSurfaceLifecycleStrategy == SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT) {
            return (mSurfaceControl == null || formatChanged) && mAttachedToWindow;
        }

        return (mSurfaceControl == null || formatChanged || visibleChanged) && mRequestedVisible;
    }

    private boolean surfaceShouldExist() {
        final boolean respectVisibility =
                mSurfaceLifecycleStrategy != SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT;
        return mVisible || (!respectVisibility && mAttachedToWindow);
    }

    /** @hide */
    protected void updateSurface() {
        if (!mHaveFrame) {
            if (DEBUG) {
                Log.d(TAG, System.identityHashCode(this) + " updateSurface: has no frame");
            }
            return;
        }
        final ViewRootImpl viewRoot = getViewRootImpl();

        if (viewRoot == null) {
            return;
        }

        if (viewRoot.mSurface == null || !viewRoot.mSurface.isValid()) {
            notifySurfaceDestroyed();
            releaseSurfaces(false /* releaseSurfacePackage*/);
            return;
        }

        final Translator translator = viewRoot.mTranslator;
        if (translator != null) {
            mSurface.setCompatibilityTranslator(translator);
        }

        int myWidth = mRequestedWidth;
        if (myWidth <= 0) myWidth = getWidth();
        int myHeight = mRequestedHeight;
        if (myHeight <= 0) myHeight = getHeight();

        final float alpha = getAlpha();
        final boolean formatChanged = mFormat != mRequestedFormat;
        final boolean visibleChanged = mVisible != mRequestedVisible;
        final boolean alphaChanged = mAlpha != alpha;
        final boolean creating = requiresSurfaceControlCreation(formatChanged, visibleChanged);
        final boolean sizeChanged = mSurfaceWidth != myWidth || mSurfaceHeight != myHeight;
        final boolean windowVisibleChanged = mWindowVisibility != mLastWindowVisibility;
        getLocationInWindow(mLocation);
        final boolean positionChanged = mWindowSpaceLeft != mLocation[0]
            || mWindowSpaceTop != mLocation[1];
        final boolean layoutSizeChanged = getWidth() != mScreenRect.width()
            || getHeight() != mScreenRect.height();
        final boolean hintChanged = (viewRoot.getBufferTransformHint() != mTransformHint)
                && mRequestedVisible;
        final boolean relativeZChanged = mSubLayer != mRequestedSubLayer;
        final boolean surfaceLifecycleStrategyChanged =
                mSurfaceLifecycleStrategy != mRequestedSurfaceLifecycleStrategy;

        if (creating || formatChanged || sizeChanged || visibleChanged
                || alphaChanged || windowVisibleChanged || positionChanged
                || layoutSizeChanged || hintChanged || relativeZChanged || !mAttachedToWindow
                || surfaceLifecycleStrategyChanged) {

            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                    + "Changes: creating=" + creating
                    + " format=" + formatChanged + " size=" + sizeChanged
                    + " visible=" + visibleChanged + " alpha=" + alphaChanged
                    + " hint=" + hintChanged
                    + " visible=" + visibleChanged
                    + " left=" + (mWindowSpaceLeft != mLocation[0])
                    + " top=" + (mWindowSpaceTop != mLocation[1])
                    + " z=" + relativeZChanged
                    + " attached=" + mAttachedToWindow
                    + " lifecycleStrategy=" + surfaceLifecycleStrategyChanged);

            try {
                mVisible = mRequestedVisible;
                mWindowSpaceLeft = mLocation[0];
                mWindowSpaceTop = mLocation[1];
                mSurfaceWidth = myWidth;
                mSurfaceHeight = myHeight;
                mFormat = mRequestedFormat;
                mAlpha = alpha;
                mLastWindowVisibility = mWindowVisibility;
                mTransformHint = viewRoot.getBufferTransformHint();
                mSubLayer = mRequestedSubLayer;

                final int previousSurfaceLifecycleStrategy = mSurfaceLifecycleStrategy;
                mSurfaceLifecycleStrategy = mRequestedSurfaceLifecycleStrategy;

                mScreenRect.left = mWindowSpaceLeft;
                mScreenRect.top = mWindowSpaceTop;
                mScreenRect.right = mWindowSpaceLeft + getWidth();
                mScreenRect.bottom = mWindowSpaceTop + getHeight();
                if (translator != null) {
                    translator.translateRectInAppWindowToScreen(mScreenRect);
                }

                final Rect surfaceInsets = viewRoot.mWindowAttributes.surfaceInsets;
                mScreenRect.offset(surfaceInsets.left, surfaceInsets.top);
                // Collect all geometry changes and apply these changes on the RenderThread worker
                // via the RenderNode.PositionUpdateListener.
                final Transaction surfaceUpdateTransaction = new Transaction();
                if (creating) {
                    updateOpaqueFlag();
                    final String name = "SurfaceView[" + viewRoot.getTitle().toString() + "]";
                    createBlastSurfaceControls(viewRoot, name, surfaceUpdateTransaction);
                } else if (mSurfaceControl == null) {
                    return;
                }

                final boolean redrawNeeded = sizeChanged || creating || hintChanged
                        || (mVisible && !mDrawFinished) || alphaChanged || relativeZChanged;
                boolean shouldSyncBuffer = redrawNeeded && viewRoot.wasRelayoutRequested()
                        && viewRoot.isInWMSRequestedSync();
                SyncBufferTransactionCallback syncBufferTransactionCallback = null;
                if (shouldSyncBuffer) {
                    syncBufferTransactionCallback = new SyncBufferTransactionCallback();
                    mBlastBufferQueue.syncNextTransaction(
                            false /* acquireSingleBuffer */,
                            syncBufferTransactionCallback::onTransactionReady);
                }

                final boolean realSizeChanged = performSurfaceTransaction(viewRoot, translator,
                        creating, sizeChanged, hintChanged, relativeZChanged,
                        surfaceUpdateTransaction);

                try {
                    SurfaceHolder.Callback[] callbacks = null;

                    final boolean surfaceChanged = creating;
                    final boolean respectVisibility =
                            mSurfaceLifecycleStrategy != SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT;
                    final boolean previouslyDidNotRespectVisibility =
                            previousSurfaceLifecycleStrategy
                                    == SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT;
                    final boolean lifecycleNewlyRespectsVisibility = respectVisibility
                            && previouslyDidNotRespectVisibility;
                    if (mSurfaceCreated) {
                        if (surfaceChanged || (!respectVisibility && !mAttachedToWindow)
                                || (respectVisibility && !mVisible
                                        && (visibleChanged || lifecycleNewlyRespectsVisibility))) {
                            mSurfaceCreated = false;
                            notifySurfaceDestroyed();
                        }
                    }

                    copySurface(creating /* surfaceControlCreated */, sizeChanged);

                    if (surfaceShouldExist() && mSurface.isValid()) {
                        if (!mSurfaceCreated
                                && (surfaceChanged || (respectVisibility && visibleChanged))) {
                            mSurfaceCreated = true;
                            mIsCreating = true;
                            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                                    + "visibleChanged -- surfaceCreated");
                            callbacks = getSurfaceCallbacks();
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceCreated(mSurfaceHolder);
                            }
                        }
                        if (creating || formatChanged || sizeChanged || hintChanged
                                || (respectVisibility && visibleChanged) || realSizeChanged) {
                            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                                    + "surfaceChanged -- format=" + mFormat
                                    + " w=" + myWidth + " h=" + myHeight);
                            if (callbacks == null) {
                                callbacks = getSurfaceCallbacks();
                            }
                            for (SurfaceHolder.Callback c : callbacks) {
                                c.surfaceChanged(mSurfaceHolder, mFormat, myWidth, myHeight);
                            }
                        }
                        if (redrawNeeded) {
                            if (DEBUG) {
                                Log.i(TAG, System.identityHashCode(this) + " surfaceRedrawNeeded");
                            }
                            if (callbacks == null) {
                                callbacks = getSurfaceCallbacks();
                            }

                            if (shouldSyncBuffer) {
                                handleSyncBufferCallback(callbacks, syncBufferTransactionCallback);
                            } else {
                                handleSyncNoBuffer(callbacks);
                            }
                        }
                    }
                } finally {
                    mIsCreating = false;
                    if (mSurfaceControl != null && !mSurfaceCreated) {
                        releaseSurfaces(false /* releaseSurfacePackage*/);
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, "Exception configuring surface", ex);
            }
            if (DEBUG) Log.v(
                TAG, "Layout: x=" + mScreenRect.left + " y=" + mScreenRect.top
                + " w=" + mScreenRect.width() + " h=" + mScreenRect.height()
                + ", frame=" + mSurfaceFrame);
        }
    }

    /**
     * @hide
     */
    public String getName() {
        ViewRootImpl viewRoot = getViewRootImpl();
        String viewRootName = viewRoot == null ? "detached" : viewRoot.getTitle().toString();
        return "SurfaceView[" + viewRootName + "]";
    }

    /**
     * If SV is trying to be part of the VRI sync, we need to add SV to the VRI sync before
     * invoking the redrawNeeded call to the owner. This is to ensure we can set up the SV in
     * the sync before the SV owner knows it needs to draw a new frame.
     * Once the redrawNeeded callback is invoked, we can stop the continuous sync transaction
     * call which will invoke the syncTransaction callback that contains the buffer. The
     * code waits until we can retrieve the transaction that contains the buffer before
     * notifying the syncer that the buffer is ready.
     */
    private void handleSyncBufferCallback(SurfaceHolder.Callback[] callbacks,
            SyncBufferTransactionCallback syncBufferTransactionCallback) {

        final SurfaceSyncGroup surfaceSyncGroup = new SurfaceSyncGroup(getName());
        getViewRootImpl().addToSync(surfaceSyncGroup);
        redrawNeededAsync(callbacks, () -> {
            Transaction t = null;
            if (mBlastBufferQueue != null) {
                mBlastBufferQueue.stopContinuousSyncTransaction();
                t = syncBufferTransactionCallback.waitForTransaction();
            }

            surfaceSyncGroup.addTransaction(t);
            surfaceSyncGroup.markSyncReady();
            onDrawFinished();
        });
    }

    private void handleSyncNoBuffer(SurfaceHolder.Callback[] callbacks) {
        final SurfaceSyncGroup surfaceSyncGroup = new SurfaceSyncGroup(getName());
        synchronized (mSyncGroups) {
            mSyncGroups.add(surfaceSyncGroup);
        }

        redrawNeededAsync(callbacks, () -> {
            synchronized (mSyncGroups) {
                mSyncGroups.remove(surfaceSyncGroup);
            }
            surfaceSyncGroup.markSyncReady();
            onDrawFinished();
        });

    }

    private void redrawNeededAsync(SurfaceHolder.Callback[] callbacks,
            Runnable callbacksCollected) {
        SurfaceCallbackHelper sch = new SurfaceCallbackHelper(callbacksCollected);
        sch.dispatchSurfaceRedrawNeededAsync(mSurfaceHolder, callbacks);
    }

    /**
     * @hide
     */
    @Override
    public void vriDrawStarted(boolean isWmSync) {
        ViewRootImpl viewRoot = getViewRootImpl();
        synchronized (mSyncGroups) {
            if (isWmSync && viewRoot != null) {
                for (SurfaceSyncGroup syncGroup : mSyncGroups) {
                    viewRoot.addToSync(syncGroup);
                }
            }
            mSyncGroups.clear();
        }
    }

    private static class SyncBufferTransactionCallback {
        private final CountDownLatch mCountDownLatch = new CountDownLatch(1);
        private Transaction mTransaction;

        Transaction waitForTransaction() {
            try {
                mCountDownLatch.await();
            } catch (InterruptedException e) {
            }
            return mTransaction;
        }

        void onTransactionReady(Transaction t) {
            mTransaction = t;
            mCountDownLatch.countDown();
        }
    }

    /**
     * Copy the Surface from the SurfaceControl or the blast adapter.
     *
     * @param surfaceControlCreated true if we created the SurfaceControl and need to update our
     *                              Surface if needed.
     * @param bufferSizeChanged true if the BufferSize has changed and we need to recreate the
     *                          Surface for compatibility reasons.
     */
    private void copySurface(boolean surfaceControlCreated, boolean bufferSizeChanged) {
        if (surfaceControlCreated) {
            mSurface.copyFrom(mBlastBufferQueue);
        }

        if (bufferSizeChanged && getContext().getApplicationInfo().targetSdkVersion
                < Build.VERSION_CODES.O) {
            // Some legacy applications use the underlying native {@link Surface} object
            // as a key to whether anything has changed. In these cases, updates to the
            // existing {@link Surface} will be ignored when the size changes.
            // Therefore, we must explicitly recreate the {@link Surface} in these
            // cases.
            if (mBlastBufferQueue != null) {
                mSurface.transferFrom(mBlastBufferQueue.createSurfaceWithHandle());
            }
        }
    }

    private void setBufferSize(Transaction transaction) {
        mBlastSurfaceControl.setTransformHint(mTransformHint);
        if (mBlastBufferQueue != null) {
            mBlastBufferQueue.update(mBlastSurfaceControl, mSurfaceWidth, mSurfaceHeight,
                        mFormat);
        }
    }


    /**
     * Creates the surface control hierarchy as follows
     *   ViewRootImpl surface
     *     bounds layer (crops all child surfaces to parent surface insets)
     *       * SurfaceView surface (drawn relative to ViewRootImpl surface)
     *           * Blast surface (if enabled)
     *       * Background color layer (drawn behind all SurfaceView surfaces)
     *
     *  The bounds layer is used to crop the surface view so it does not draw into the parent
     *  surface inset region. Since there can be multiple surface views below or above the parent
     *  surface, one option is to create multiple bounds layer for each z order. The other option,
     *  the one implement is to create a single bounds layer and set z order for each child surface
     *  relative to the parent surface.
     *  When creating the surface view, we parent it to the bounds layer and then set the relative z
     *  order. When the parent surface changes, we have to make sure to update the relative z via
     *  ViewRootImpl.SurfaceChangedCallback.
     *
     *  We don't recreate the surface controls but only recreate the adapter. Since the blast layer
     *  is still alive, the old buffers will continue to be presented until replaced by buffers from
     *  the new adapter. This means we do not need to track the old surface control and destroy it
     *  after the client has drawn to avoid any flickers.
     *
     */
    private void createBlastSurfaceControls(ViewRootImpl viewRoot, String name,
            Transaction surfaceUpdateTransaction) {
        if (mSurfaceControl == null) {
            mSurfaceControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setName(name)
                    .setLocalOwnerView(this)
                    .setParent(viewRoot.updateAndGetBoundsLayer(surfaceUpdateTransaction))
                    .setCallsite("SurfaceView.updateSurface")
                    .setContainerLayer()
                    .build();
        }

        if (mBlastSurfaceControl == null) {
            mBlastSurfaceControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setName(name + "(BLAST)")
                    .setLocalOwnerView(this)
                    .setParent(mSurfaceControl)
                    .setFlags(mSurfaceFlags)
                    .setHidden(false)
                    .setBLASTLayer()
                    .setCallsite("SurfaceView.updateSurface")
                    .build();
        } else {
            // update blast layer
            surfaceUpdateTransaction
                    .setOpaque(mBlastSurfaceControl, (mSurfaceFlags & SurfaceControl.OPAQUE) != 0)
                    .setSecure(mBlastSurfaceControl, (mSurfaceFlags & SurfaceControl.SECURE) != 0)
                    .show(mBlastSurfaceControl);
        }

        if (mBackgroundControl == null) {
            mBackgroundControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setName("Background for " + name)
                    .setLocalOwnerView(this)
                    .setOpaque(true)
                    .setColorLayer()
                    .setParent(mSurfaceControl)
                    .setCallsite("SurfaceView.updateSurface")
                    .build();
        }

        // Always recreate the IGBP for compatibility. This can be optimized in the future but
        // the behavior change will need to be gated by SDK version.
        if (mBlastBufferQueue != null) {
            mBlastBufferQueue.destroy();
        }
        mTransformHint = viewRoot.getBufferTransformHint();
        mBlastSurfaceControl.setTransformHint(mTransformHint);

        mBlastBufferQueue = new BLASTBufferQueue(name, false /* updateDestinationFrame */);
        mBlastBufferQueue.update(mBlastSurfaceControl, mSurfaceWidth, mSurfaceHeight, mFormat);
        mBlastBufferQueue.setTransactionHangCallback(ViewRootImpl.sTransactionHangCallback);
    }

    private void onDrawFinished() {
        if (DEBUG) {
            Log.i(TAG, System.identityHashCode(this) + " "
                    + "finishedDrawing");
        }

        runOnUiThread(this::performDrawFinished);
    }

    /**
     * Sets the surface position and scale. Can be called on
     * the UI thread as well as on the renderer thread.
     *
     * @param transaction Transaction in which to execute.
     * @param surface Surface whose location to set.
     * @param positionLeft The left position to set.
     * @param positionTop The top position to set.
     * @param postScaleX The X axis post scale
     * @param postScaleY The Y axis post scale
     *
     * @hide
     */
    protected void onSetSurfacePositionAndScale(@NonNull Transaction transaction,
            @NonNull SurfaceControl surface, int positionLeft, int positionTop,
            float postScaleX, float postScaleY) {
        transaction.setPosition(surface, positionLeft, positionTop);
        transaction.setMatrix(surface, postScaleX /*dsdx*/, 0f /*dtdx*/,
                0f /*dtdy*/, postScaleY /*dsdy*/);
    }

    /** @hide */
    public void requestUpdateSurfacePositionAndScale() {
        if (mSurfaceControl == null) {
            return;
        }
        final Transaction transaction = new Transaction();
        onSetSurfacePositionAndScale(transaction, mSurfaceControl,
                mScreenRect.left, /*positionLeft*/
                mScreenRect.top/*positionTop*/ ,
                mScreenRect.width() / (float) mSurfaceWidth /*postScaleX*/,
                mScreenRect.height() / (float) mSurfaceHeight /*postScaleY*/);
        applyTransactionOnVriDraw(transaction);
        invalidate();
    }

    /**
     * @return The last render position of the backing surface or an empty rect.
     *
     * @hide
     */
    public @NonNull Rect getSurfaceRenderPosition() {
        return mRTLastReportedPosition;
    }

    private void applyOrMergeTransaction(Transaction t, long frameNumber) {
        final ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot != null) {
            // If we are using BLAST, merge the transaction with the viewroot buffer transaction.
            viewRoot.mergeWithNextTransaction(t, frameNumber);
        } else {
            t.apply();
        }
    }

    private final Rect mRTLastReportedPosition = new Rect();
    private final Rect mRTLastSetCrop = new Rect();

    private class SurfaceViewPositionUpdateListener implements RenderNode.PositionUpdateListener {
        private final int mRtSurfaceWidth;
        private final int mRtSurfaceHeight;
        private final SurfaceControl.Transaction mPositionChangedTransaction =
                new SurfaceControl.Transaction();

        SurfaceViewPositionUpdateListener(int surfaceWidth, int surfaceHeight) {
            mRtSurfaceWidth = surfaceWidth;
            mRtSurfaceHeight = surfaceHeight;
        }

        @Override
        public void positionChanged(long frameNumber, int left, int top, int right, int bottom) {
            try {
                if (DEBUG_POSITION) {
                    Log.d(TAG, String.format(
                            "%d updateSurfacePosition RenderWorker, frameNr = %d, "
                                    + "position = [%d, %d, %d, %d] surfaceSize = %dx%d",
                            System.identityHashCode(SurfaceView.this), frameNumber,
                            left, top, right, bottom, mRtSurfaceWidth, mRtSurfaceHeight));
                }
                synchronized (mSurfaceControlLock) {
                    if (mSurfaceControl == null) return;

                    mRTLastReportedPosition.set(left, top, right, bottom);
                    onSetSurfacePositionAndScale(mPositionChangedTransaction, mSurfaceControl,
                            mRTLastReportedPosition.left /*positionLeft*/,
                            mRTLastReportedPosition.top /*positionTop*/,
                            mRTLastReportedPosition.width()
                                    / (float) mRtSurfaceWidth /*postScaleX*/,
                            mRTLastReportedPosition.height()
                                    / (float) mRtSurfaceHeight /*postScaleY*/);

                    mPositionChangedTransaction.show(mSurfaceControl);
                }
                applyOrMergeTransaction(mPositionChangedTransaction, frameNumber);
            } catch (Exception ex) {
                Log.e(TAG, "Exception from repositionChild", ex);
            }
        }

        @Override
        public void positionChanged(long frameNumber, int left, int top, int right, int bottom,
                int clipLeft, int clipTop, int clipRight, int clipBottom) {
            try {
                if (DEBUG_POSITION) {
                    Log.d(TAG, String.format(
                            "%d updateSurfacePosition RenderWorker, frameNr = %d, "
                                    + "position = [%d, %d, %d, %d] clip = [%d, %d, %d, %d] "
                                    + "surfaceSize = %dx%d",
                            System.identityHashCode(SurfaceView.this), frameNumber,
                            left, top, right, bottom, clipLeft, clipTop, clipRight, clipBottom,
                            mRtSurfaceWidth, mRtSurfaceHeight));
                }
                synchronized (mSurfaceControlLock) {
                    if (mSurfaceControl == null) return;

                    mRTLastReportedPosition.set(left, top, right, bottom);
                    final float postScaleX = mRTLastReportedPosition.width()
                            / (float) mRtSurfaceWidth;
                    final float postScaleY = mRTLastReportedPosition.height()
                            / (float) mRtSurfaceHeight;
                    onSetSurfacePositionAndScale(mPositionChangedTransaction, mSurfaceControl,
                            mRTLastReportedPosition.left /*positionLeft*/,
                            mRTLastReportedPosition.top /*positionTop*/,
                            postScaleX, postScaleY);

                    mRTLastSetCrop.set((int) (clipLeft / postScaleX), (int) (clipTop / postScaleY),
                            (int) Math.ceil(clipRight / postScaleX),
                            (int) Math.ceil(clipBottom / postScaleY));
                    if (DEBUG_POSITION) {
                        Log.d(TAG, String.format("Setting layer crop = [%d, %d, %d, %d] "
                                        + "from scale %f, %f", mRTLastSetCrop.left,
                                mRTLastSetCrop.top, mRTLastSetCrop.right, mRTLastSetCrop.bottom,
                                postScaleX, postScaleY));
                    }
                    mPositionChangedTransaction.setCrop(mSurfaceControl, mRTLastSetCrop);
                    if (mRTLastSetCrop.isEmpty()) {
                        mPositionChangedTransaction.hide(mSurfaceControl);
                    } else {
                        mPositionChangedTransaction.show(mSurfaceControl);
                    }
                }
                applyOrMergeTransaction(mPositionChangedTransaction, frameNumber);
            } catch (Exception ex) {
                Log.e(TAG, "Exception from repositionChild", ex);
            }
        }

        @Override
        public void applyStretch(long frameNumber, float width, float height,
                float vecX, float vecY, float maxStretchX, float maxStretchY,
                float childRelativeLeft, float childRelativeTop, float childRelativeRight,
                float childRelativeBottom) {
            mRtTransaction.setStretchEffect(mSurfaceControl, width, height, vecX, vecY,
                    maxStretchX, maxStretchY, childRelativeLeft, childRelativeTop,
                    childRelativeRight, childRelativeBottom);
            applyOrMergeTransaction(mRtTransaction, frameNumber);
        }

        @Override
        public void positionLost(long frameNumber) {
            if (DEBUG_POSITION) {
                Log.d(TAG, String.format("%d windowPositionLost, frameNr = %d",
                        System.identityHashCode(this), frameNumber));
            }
            mRTLastReportedPosition.setEmpty();

            // positionLost can be called while UI thread is un-paused.
            synchronized (mSurfaceControlLock) {
                if (mSurfaceControl == null) return;
                // b/131239825
                mRtTransaction.hide(mSurfaceControl);
                applyOrMergeTransaction(mRtTransaction, frameNumber);
            }
        }
    }

    private SurfaceViewPositionUpdateListener mPositionListener = null;

    private SurfaceHolder.Callback[] getSurfaceCallbacks() {
        SurfaceHolder.Callback[] callbacks;
        synchronized (mCallbacks) {
            callbacks = new SurfaceHolder.Callback[mCallbacks.size()];
            mCallbacks.toArray(callbacks);
        }
        return callbacks;
    }

    private void runOnUiThread(Runnable runnable) {
        Handler handler = getHandler();
        if (handler != null && handler.getLooper() != Looper.myLooper()) {
            handler.post(runnable);
        } else {
            runnable.run();
        }
    }

    /**
     * Check to see if the surface has fixed size dimensions or if the surface's
     * dimensions are dimensions are dependent on its current layout.
     *
     * @return true if the surface has dimensions that are fixed in size
     * @hide
     */
    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Track {@link SurfaceHolder#setFixedSize} instead")
    public boolean isFixedSize() {
        return (mRequestedWidth != -1 || mRequestedHeight != -1);
    }

    private boolean isAboveParent() {
        return mSubLayer >= 0;
    }

    /**
     * Set an opaque background color to use with this {@link SurfaceView} when it's being resized
     * and size of the content hasn't updated yet. This color will fill the expanded area when the
     * view becomes larger.
     * @param bgColor An opaque color to fill the background. Alpha component will be ignored.
     * @hide
     */
    public void setResizeBackgroundColor(int bgColor) {
        final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        setResizeBackgroundColor(transaction, bgColor);
        applyTransactionOnVriDraw(transaction);
        invalidate();
    }

    /**
     * Version of {@link #setResizeBackgroundColor(int)} that allows you to provide
     * {@link SurfaceControl.Transaction}.
     * @hide
     */
    public void setResizeBackgroundColor(@NonNull SurfaceControl.Transaction t, int bgColor) {
        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundColor = bgColor;
        updateBackgroundColor(t);
    }

    @UnsupportedAppUsage(
            maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@link SurfaceView#getHolder} instead")
    private final SurfaceHolder mSurfaceHolder = new SurfaceHolder() {
        private static final String LOG_TAG = "SurfaceHolder";

        @Override
        public boolean isCreating() {
            return mIsCreating;
        }

        @Override
        public void addCallback(Callback callback) {
            synchronized (mCallbacks) {
                // This is a linear search, but in practice we'll
                // have only a couple callbacks, so it doesn't matter.
                if (!mCallbacks.contains(callback)) {
                    mCallbacks.add(callback);
                }
            }
        }

        @Override
        public void removeCallback(Callback callback) {
            synchronized (mCallbacks) {
                mCallbacks.remove(callback);
            }
        }

        @Override
        public void setFixedSize(int width, int height) {
            if (mRequestedWidth != width || mRequestedHeight != height) {
                if (DEBUG_POSITION) {
                    Log.d(TAG, String.format("%d setFixedSize %dx%d -> %dx%d",
                            System.identityHashCode(this), mRequestedWidth, mRequestedHeight, width,
                                    height));
                }
                mRequestedWidth = width;
                mRequestedHeight = height;
                requestLayout();
            }
        }

        @Override
        public void setSizeFromLayout() {
            if (mRequestedWidth != -1 || mRequestedHeight != -1) {
                if (DEBUG_POSITION) {
                    Log.d(TAG, String.format("%d setSizeFromLayout was %dx%d",
                            System.identityHashCode(this), mRequestedWidth, mRequestedHeight));
                }
                mRequestedWidth = mRequestedHeight = -1;
                requestLayout();
            }
        }

        @Override
        public void setFormat(int format) {
            // for backward compatibility reason, OPAQUE always
            // means 565 for SurfaceView
            if (format == PixelFormat.OPAQUE)
                format = PixelFormat.RGB_565;

            mRequestedFormat = format;
            if (mSurfaceControl != null) {
                updateSurface();
            }
        }

        /**
         * @deprecated setType is now ignored.
         */
        @Override
        @Deprecated
        public void setType(int type) { }

        @Override
        public void setKeepScreenOn(boolean screenOn) {
            runOnUiThread(() -> SurfaceView.this.setKeepScreenOn(screenOn));
        }

        /**
         * Gets a {@link Canvas} for drawing into the SurfaceView's Surface
         *
         * After drawing into the provided {@link Canvas}, the caller must
         * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
         *
         * The caller must redraw the entire surface.
         * @return A canvas for drawing into the surface.
         */
        @Override
        public Canvas lockCanvas() {
            return internalLockCanvas(null, false);
        }

        /**
         * Gets a {@link Canvas} for drawing into the SurfaceView's Surface
         *
         * After drawing into the provided {@link Canvas}, the caller must
         * invoke {@link #unlockCanvasAndPost} to post the new contents to the surface.
         *
         * @param inOutDirty A rectangle that represents the dirty region that the caller wants
         * to redraw.  This function may choose to expand the dirty rectangle if for example
         * the surface has been resized or if the previous contents of the surface were
         * not available.  The caller must redraw the entire dirty region as represented
         * by the contents of the inOutDirty rectangle upon return from this function.
         * The caller may also pass <code>null</code> instead, in the case where the
         * entire surface should be redrawn.
         * @return A canvas for drawing into the surface.
         */
        @Override
        public Canvas lockCanvas(Rect inOutDirty) {
            return internalLockCanvas(inOutDirty, false);
        }

        @Override
        public Canvas lockHardwareCanvas() {
            return internalLockCanvas(null, true);
        }

        private Canvas internalLockCanvas(Rect dirty, boolean hardware) {
            mSurfaceLock.lock();

            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " " + "Locking canvas... stopped="
                    + mDrawingStopped + ", surfaceControl=" + mSurfaceControl);

            Canvas c = null;
            if (!mDrawingStopped && mSurfaceControl != null) {
                try {
                    if (hardware) {
                        c = mSurface.lockHardwareCanvas();
                    } else {
                        c = mSurface.lockCanvas(dirty);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Exception locking surface", e);
                }
            }

            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " " + "Returned canvas: " + c);
            if (c != null) {
                mLastLockTime = SystemClock.uptimeMillis();
                return c;
            }

            // If the Surface is not ready to be drawn, then return null,
            // but throttle calls to this function so it isn't called more
            // than every 100ms.
            long now = SystemClock.uptimeMillis();
            long nextTime = mLastLockTime + 100;
            if (nextTime > now) {
                try {
                    Thread.sleep(nextTime-now);
                } catch (InterruptedException e) {
                }
                now = SystemClock.uptimeMillis();
            }
            mLastLockTime = now;
            mSurfaceLock.unlock();

            return null;
        }

        /**
         * Posts the new contents of the {@link Canvas} to the surface and
         * releases the {@link Canvas}.
         *
         * @param canvas The canvas previously obtained from {@link #lockCanvas}.
         */
        @Override
        public void unlockCanvasAndPost(Canvas canvas) {
            try {
                mSurface.unlockCanvasAndPost(canvas);
            } finally {
                mSurfaceLock.unlock();
            }
        }

        @Override
        public Surface getSurface() {
            return mSurface;
        }

        @Override
        public Rect getSurfaceFrame() {
            return mSurfaceFrame;
        }
    };

    /**
     * Return a SurfaceControl which can be used for parenting Surfaces to this SurfaceView.
     *
     * Note that this SurfaceControl is effectively read-only. Its only well-defined usage is in
     * using the SurfaceControl as a parent for an application's hierarchy of SurfaceControls. All
     * other properties of the SurfaceControl, such as its position, may be mutated by the
     * SurfaceView at any time which will override what the application is requesting. Do not apply
     * any {@link SurfaceControl.Transaction} to this SurfaceControl except for reparenting
     * child SurfaceControls. See: {@link SurfaceControl.Transaction#reparent}.
     *
     * @return The SurfaceControl for this SurfaceView.
     */
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    /**
     * A token used for constructing {@link SurfaceControlViewHost}. This token should
     * be passed from the host process to the client process.
     *
     * @return The token
     */
    public @Nullable IBinder getHostToken() {
        final ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot == null) {
            return null;
        }
        return viewRoot.getInputToken();
    }

    /**
     * Set window stopped to false and update surface visibility when ViewRootImpl surface is
     * created.
     * @hide
     */
    @Override
    public void surfaceCreated(SurfaceControl.Transaction t) {
        setWindowStopped(false);
    }

    /**
     * Set window stopped to true and update surface visibility when ViewRootImpl surface is
     * destroyed.
     * @hide
     */
    @Override
    public void surfaceDestroyed() {
        setWindowStopped(true);
        mRemoteAccessibilityController.disassosciateHierarchy();
    }

    /**
     * Called when a valid ViewRootImpl surface is replaced by another valid surface. In this
     * case update relative z to the new parent surface.
     * @hide
     */
    @Override
    public void surfaceReplaced(Transaction t) {
        if (mSurfaceControl != null && mBackgroundControl != null) {
            updateRelativeZ(t);
        }
    }

    private void updateRelativeZ(Transaction t) {
        final ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot == null) {
            // We were just detached.
            return;
        }
        final SurfaceControl viewRootControl = viewRoot.getSurfaceControl();
        t.setRelativeLayer(mBackgroundControl, viewRootControl, Integer.MIN_VALUE);
        t.setRelativeLayer(mSurfaceControl, viewRootControl, mSubLayer);
    }

    /**
     * Display the view-hierarchy embedded within a {@link SurfaceControlViewHost.SurfacePackage}
     * within this SurfaceView.
     *
     * This can be called independently of the SurfaceView lifetime callbacks. SurfaceView
     * will internally manage reparenting the package to our Surface as it is created
     * and destroyed.
     *
     * If this SurfaceView is above its host Surface (see
     * {@link #setZOrderOnTop} then the embedded Surface hierarchy will be able to receive
     * input.
     *
     * This will take ownership of the SurfaceControl contained inside the SurfacePackage
     * and free the caller of the obligation to call
     * {@link SurfaceControlViewHost.SurfacePackage#release}. However, note that
     * {@link SurfaceControlViewHost.SurfacePackage#release} and
     * {@link SurfaceControlViewHost#release} are not the same. While the ownership
     * of this particular {@link SurfaceControlViewHost.SurfacePackage} will be taken by the
     * SurfaceView the underlying {@link SurfaceControlViewHost} remains managed by it's original
     * remote-owner.
     *
     * @param p The SurfacePackage to embed.
     */
    public void setChildSurfacePackage(@NonNull SurfaceControlViewHost.SurfacePackage p) {
        final SurfaceControl lastSc = mSurfacePackage != null ?
                mSurfacePackage.getSurfaceControl() : null;
        final SurfaceControl.Transaction transaction = new Transaction();
        if (mSurfaceControl != null) {
            if (lastSc != null) {
                transaction.reparent(lastSc, null);
                mSurfacePackage.release();
            }
            reparentSurfacePackage(transaction, p);
            applyTransactionOnVriDraw(transaction);
        }
        mSurfacePackage = p;
        try {
            mSurfacePackage.getRemoteInterface().attachParentInterface(
                    mSurfaceControlViewHostParent);
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to attach parent interface to SCVH. Likely SCVH is already dead.");
        }

        if (isFocused()) {
            requestEmbeddedFocus(true);
        }
        invalidate();
    }

    private void reparentSurfacePackage(SurfaceControl.Transaction t,
            SurfaceControlViewHost.SurfacePackage p) {
        final SurfaceControl sc = p.getSurfaceControl();
        if (sc == null || !sc.isValid()) {
            return;
        }
        initEmbeddedHierarchyForAccessibility(p);
        t.reparent(sc, mBlastSurfaceControl).show(sc);
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        if (!mRemoteAccessibilityController.connected()) {
            return;
        }
        // Add a leashed child when this SurfaceView embeds another view hierarchy. Getting this
        // leashed child would return the root node in the embedded hierarchy
        info.addChild(mRemoteAccessibilityController.getLeashToken());
    }

    @Override
    public int getImportantForAccessibility() {
        final int mode = super.getImportantForAccessibility();
        // If developers explicitly set the important mode for it, don't change the mode.
        // Only change the mode to important when this SurfaceView isn't explicitly set and has
        // an embedded hierarchy.
        if ((mRemoteAccessibilityController!= null && !mRemoteAccessibilityController.connected())
                || mode != IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            return mode;
        }
        return IMPORTANT_FOR_ACCESSIBILITY_YES;
    }

    private void initEmbeddedHierarchyForAccessibility(SurfaceControlViewHost.SurfacePackage p) {
        final IAccessibilityEmbeddedConnection connection = p.getAccessibilityEmbeddedConnection();
        if (mRemoteAccessibilityController.alreadyAssociated(connection)) {
            return;
        }
        mRemoteAccessibilityController.assosciateHierarchy(connection,
            getViewRootImpl().mLeashToken, getAccessibilityViewId());

        updateEmbeddedAccessibilityMatrix(true);
    }

    private void notifySurfaceDestroyed() {
        if (mSurface.isValid()) {
            if (DEBUG) Log.i(TAG, System.identityHashCode(this) + " "
                    + "surfaceDestroyed");
            SurfaceHolder.Callback[] callbacks = getSurfaceCallbacks();
            for (SurfaceHolder.Callback c : callbacks) {
                c.surfaceDestroyed(mSurfaceHolder);
            }
            // Since Android N the same surface may be reused and given to us
            // again by the system server at a later point. However
            // as we didn't do this in previous releases, clients weren't
            // necessarily required to clean up properly in
            // surfaceDestroyed. This leads to problems for example when
            // clients don't destroy their EGL context, and try
            // and create a new one on the same surface following reuse.
            // Since there is no valid use of the surface in-between
            // surfaceDestroyed and surfaceCreated, we force a disconnect,
            // so the next connect will always work if we end up reusing
            // the surface.
            if (mSurface.isValid()) {
                mSurface.forceScopedDisconnect();
            }
        }
    }

    void updateEmbeddedAccessibilityMatrix(boolean force) {
        if (!mRemoteAccessibilityController.connected()) {
            return;
        }
        getBoundsOnScreen(mTmpRect);

        // To compute the node bounds of the node on the embedded window,
        // apply this matrix to get the bounds in host window-relative coordinates,
        // then using the global transform to get the actual bounds on screen.
        mTmpRect.offset(-mAttachInfo.mWindowLeft, -mAttachInfo.mWindowTop);
        mTmpMatrix.reset();
        mTmpMatrix.setTranslate(mTmpRect.left, mTmpRect.top);
        mTmpMatrix.postScale(mScreenRect.width() / (float) mSurfaceWidth,
                mScreenRect.height() / (float) mSurfaceHeight);
        mRemoteAccessibilityController.setWindowMatrix(mTmpMatrix, force);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, @FocusDirection int direction,
            @Nullable Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        requestEmbeddedFocus(gainFocus);
    }

    private void requestEmbeddedFocus(boolean gainFocus) {
        final ViewRootImpl viewRoot = getViewRootImpl();
        if (mSurfacePackage == null || viewRoot == null) {
            return;
        }
        try {
            viewRoot.mWindowSession.grantEmbeddedWindowFocus(viewRoot.mWindow,
                    mSurfacePackage.getInputToken(), gainFocus);
        } catch (Exception e) {
            Log.e(TAG, System.identityHashCode(this)
                    + "Exception requesting focus on embedded window", e);
        }
    }

    private void applyTransactionOnVriDraw(Transaction t) {
        final ViewRootImpl viewRoot = getViewRootImpl();
        if (viewRoot != null) {
            // If we are using BLAST, merge the transaction with the viewroot buffer transaction.
            viewRoot.applyTransactionOnDraw(t);
        } else {
            t.apply();
        }
    }

    /**
     * @hide
     */
    public void syncNextFrame(Consumer<Transaction> t) {
        mBlastBufferQueue.syncNextTransaction(t);
    }

    /**
     * Adds a transaction that would be applied synchronously with displaying the SurfaceView's next
     * frame.
     *
     * Note that the exact frame that the transaction is applied with is only well-defined when
     * SurfaceView rendering is paused prior to calling applyTransactionToFrame(), so that the
     * transaction is applied with the next frame rendered after applyTransactionToFrame() is
     * called. If frames are continuously rendering to the SurfaceView when
     * applyTransactionToFrame() is called, then it is undefined which frame the transaction is
     * applied with. It is also possible for the transaction to not be applied if no new frames are
     * rendered to the SurfaceView after this is called.
     *
     * @param transaction The transaction to apply. The system takes ownership of the transaction
     *                    and promises to eventually apply the transaction.
     * @throws IllegalStateException if the underlying Surface does not exist (and therefore
     *         there is no next frame).
     */
    public void applyTransactionToFrame(@NonNull SurfaceControl.Transaction transaction) {
        synchronized (mSurfaceControlLock) {
            if (mBlastBufferQueue == null) {
                throw new IllegalStateException("Surface does not exist!");
            }

            long frameNumber = mBlastBufferQueue.getLastAcquiredFrameNum() + 1;
            mBlastBufferQueue.mergeWithNextTransaction(transaction, frameNumber);
        }
    }

    @Override
    void performCollectViewAttributes(AttachInfo attachInfo, int visibility) {
        super.performCollectViewAttributes(attachInfo, visibility);
        if (mEmbeddedWindowParams.isEmpty()) {
            return;
        }

        for (WindowManager.LayoutParams embeddedWindowAttr : mEmbeddedWindowParams) {
            if ((embeddedWindowAttr.flags & FLAG_KEEP_SCREEN_ON) == FLAG_KEEP_SCREEN_ON) {
                attachInfo.mKeepScreenOn = true;
                break;
            }
        }
    }
}
