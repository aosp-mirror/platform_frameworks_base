/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
import static android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.Trace;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.android.internal.R;
import com.android.internal.policy.DecorView;
import com.android.internal.util.ContrastColorUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * <p>The view which allows an activity to customize its splash screen exit animation.</p>
 *
 * <p>Activities will receive this view as a parameter of
 * {@link SplashScreen.OnExitAnimationListener#onSplashScreenExit} if
 * they set {@link SplashScreen#setOnExitAnimationListener}.
 * When this callback is called, this view will be on top of the activity.</p>
 *
 * <p>This view is composed of a view containing the splashscreen icon (see
 * windowSplashscreenAnimatedIcon) and a background.
 * Developers can use {@link #getIconView} to get this view and replace the drawable or
 * add animation to it. The background of this view is filled with a single color, which can be
 * edited during the animation by {@link View#setBackground} or {@link View#setBackgroundColor}.</p>
 *
 * @see SplashScreen
 */
public final class SplashScreenView extends FrameLayout {
    private static final String TAG = SplashScreenView.class.getSimpleName();
    private static final boolean DEBUG = Build.IS_DEBUGGABLE;

    private static final int LIGHT_BARS_MASK =
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
    private static final int WINDOW_FLAG_MASK = FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                    | FLAG_TRANSLUCENT_NAVIGATION | FLAG_TRANSLUCENT_STATUS;

    private boolean mNotCopyable;
    private boolean mIsCopied;
    private int mInitBackgroundColor;
    private View mIconView;
    private Bitmap mParceledIconBitmap;
    private View mBrandingImageView;
    private Bitmap mParceledBrandingBitmap;
    private Bitmap mParceledIconBackgroundBitmap;
    private Duration mIconAnimationDuration;
    private Instant mIconAnimationStart;

    // The host activity when transfer view to it.
    private Activity mHostActivity;

    @Nullable
    private SurfaceControlViewHost.SurfacePackage mSurfacePackageCopy;
    @Nullable
    private SurfaceControlViewHost.SurfacePackage mSurfacePackage;
    @Nullable
    private SurfaceView mSurfaceView;
    @Nullable
    private SurfaceControlViewHost mSurfaceHost;
    @Nullable
    private RemoteCallback mClientCallback;

    // cache original window and status
    private Window mWindow;
    private int mAppWindowFlags;
    private int mStatusBarColor;
    private int mNavigationBarColor;
    private int mSystemBarsAppearance;
    private boolean mHasRemoved;
    private boolean mNavigationContrastEnforced;
    private boolean mStatusContrastEnforced;
    private boolean mDecorFitsSystemWindows;

    /**
     * Internal builder to create a SplashScreenView object.
     * @hide
     */
    public static class Builder {
        private final Context mContext;
        private int mIconSize;
        private @ColorInt int mBackgroundColor;
        private Bitmap mParceledIconBitmap;
        private Bitmap mParceledIconBackgroundBitmap;
        private Drawable mIconDrawable;
        // It is only set for legacy splash screen which won't be sent across processes.
        private Drawable mOverlayDrawable;
        private Drawable mIconBackground;
        private SurfaceControlViewHost.SurfacePackage mSurfacePackage;
        private RemoteCallback mClientCallback;
        private int mBrandingImageWidth;
        private int mBrandingImageHeight;
        private Drawable mBrandingDrawable;
        private Bitmap mParceledBrandingBitmap;
        private Instant mIconAnimationStart;
        private Duration mIconAnimationDuration;
        private Consumer<Runnable> mUiThreadInitTask;

        public Builder(@NonNull Context context) {
            mContext = context;
        }

        /**
         * When create from {@link SplashScreenViewParcelable}, all the materials were be settled so
         * you do not need to call other set methods.
         */
        public Builder createFromParcel(SplashScreenViewParcelable parcelable) {
            mIconSize = parcelable.getIconSize();
            mBackgroundColor = parcelable.getBackgroundColor();
            mSurfacePackage = parcelable.mSurfacePackage;
            if (mSurfacePackage == null && parcelable.mIconBitmap != null) {
                // We only create a Bitmap copies of immobile icons since animated icon are using
                // a surface view
                mIconDrawable = new BitmapDrawable(mContext.getResources(), parcelable.mIconBitmap);
                mParceledIconBitmap = parcelable.mIconBitmap;
            }
            if (parcelable.mIconBackground != null) {
                mIconBackground = new BitmapDrawable(mContext.getResources(),
                        parcelable.mIconBackground);
                mParceledIconBackgroundBitmap = parcelable.mIconBackground;
            }
            if (parcelable.mBrandingBitmap != null) {
                setBrandingDrawable(new BitmapDrawable(mContext.getResources(),
                                parcelable.mBrandingBitmap), parcelable.mBrandingWidth,
                        parcelable.mBrandingHeight);
                mParceledBrandingBitmap = parcelable.mBrandingBitmap;
            }
            mIconAnimationStart = Instant.ofEpochMilli(parcelable.mIconAnimationStartMillis);
            mIconAnimationDuration = Duration.ofMillis(parcelable.mIconAnimationDurationMillis);
            mClientCallback = parcelable.mClientCallback;
            if (DEBUG) {
                Log.d(TAG, String.format("Building from parcel drawable: %s", mIconDrawable));
            }
            return this;
        }

        /**
         * Set the rectangle size for the center view.
         */
        public Builder setIconSize(int iconSize) {
            mIconSize = iconSize;
            return this;
        }

        /**
         * Set the background color for the view.
         */
        public Builder setBackgroundColor(@ColorInt int backgroundColor) {
            mBackgroundColor = backgroundColor;
            return this;
        }

        /**
         * Set the Drawable object to fill entire view
         */
        public Builder setOverlayDrawable(@Nullable Drawable drawable) {
            mOverlayDrawable = drawable;
            return this;
        }

        /**
         * Set the Drawable object to fill the center view.
         */
        public Builder setCenterViewDrawable(@Nullable Drawable drawable) {
            mIconDrawable = drawable;
            return this;
        }

        /**
         * Set the background color for the icon.
         */
        public Builder setIconBackground(Drawable iconBackground) {
            mIconBackground = iconBackground;
            return this;
        }

        /**
         * Set the animation duration if icon is animatable.
         */
        public Builder setAnimationDurationMillis(int duration) {
            mIconAnimationDuration = Duration.ofMillis(duration);
            return this;
        }

        /**
         * Set the Runnable that can receive the task which should be executed on UI thread.
         * @param uiThreadInitTask
         */
        public Builder setUiThreadInitConsumer(Consumer<Runnable> uiThreadInitTask) {
            mUiThreadInitTask = uiThreadInitTask;
            return this;
        }

        /**
         * Set the Drawable object and size for the branding view.
         */
        public Builder setBrandingDrawable(@Nullable Drawable branding, int width, int height) {
            mBrandingDrawable = branding;
            mBrandingImageWidth = width;
            mBrandingImageHeight = height;
            return this;
        }

        /**
         * Create SplashScreenWindowView object from materials.
         */
        public SplashScreenView build() {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "SplashScreenView#build");
            final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
            final SplashScreenView view = (SplashScreenView)
                    layoutInflater.inflate(R.layout.splash_screen_view, null, false);
            view.mInitBackgroundColor = mBackgroundColor;
            if (mOverlayDrawable != null) {
                view.setBackground(mOverlayDrawable);
            } else {
                view.setBackgroundColor(mBackgroundColor);
            }
            view.mClientCallback = mClientCallback;

            view.mBrandingImageView = view.findViewById(R.id.splashscreen_branding_view);

            // center icon
            if (mIconDrawable instanceof SplashScreenView.IconAnimateListener
                    || mSurfacePackage != null) {
                if (mUiThreadInitTask != null) {
                    mUiThreadInitTask.accept(() -> view.mIconView = createSurfaceView(view));
                } else {
                    view.mIconView = createSurfaceView(view);
                }
                view.initIconAnimation(mIconDrawable,
                        mIconAnimationDuration != null ? mIconAnimationDuration.toMillis() : 0);
                view.mIconAnimationStart = mIconAnimationStart;
                view.mIconAnimationDuration = mIconAnimationDuration;
            } else if (mIconSize != 0) {
                ImageView imageView = view.findViewById(R.id.splashscreen_icon_view);
                assert imageView != null;

                final ViewGroup.LayoutParams params = imageView.getLayoutParams();
                params.width = mIconSize;
                params.height = mIconSize;
                imageView.setLayoutParams(params);
                if (mIconDrawable != null) {
                    imageView.setImageDrawable(mIconDrawable);
                }
                if (mIconBackground != null) {
                    imageView.setBackground(mIconBackground);
                }
                view.mIconView = imageView;
            }
            if (mOverlayDrawable != null || mIconDrawable == null) {
                view.setNotCopyable();
            }

            view.mParceledIconBackgroundBitmap = mParceledIconBackgroundBitmap;
            view.mParceledIconBitmap = mParceledIconBitmap;

            // branding image
            if (mBrandingImageHeight > 0 && mBrandingImageWidth > 0 && mBrandingDrawable != null) {
                final ViewGroup.LayoutParams params = view.mBrandingImageView.getLayoutParams();
                params.width = mBrandingImageWidth;
                params.height = mBrandingImageHeight;
                view.mBrandingImageView.setLayoutParams(params);
                view.mBrandingImageView.setBackground(mBrandingDrawable);
            } else {
                view.mBrandingImageView.setVisibility(GONE);
            }
            if (mParceledBrandingBitmap != null) {
                view.mParceledBrandingBitmap = mParceledBrandingBitmap;
            }
            if (DEBUG) {
                Log.d(TAG, "Build " + view
                        + "\nIcon: view: " + view.mIconView + " drawable: "
                        + mIconDrawable + " size: " + mIconSize
                        + "\nBranding: view: " + view.mBrandingImageView + " drawable: "
                        + mBrandingDrawable + " size w: " + mBrandingImageWidth + " h: "
                        + mBrandingImageHeight);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return view;
        }

        private SurfaceView createSurfaceView(@NonNull SplashScreenView view) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "SplashScreenView#createSurfaceView");
            final Context viewContext = view.getContext();
            final SurfaceView surfaceView = new SurfaceView(viewContext);
            surfaceView.setPadding(0, 0, 0, 0);
            surfaceView.setBackground(mIconBackground);
            if (mSurfacePackage == null) {
                if (DEBUG) {
                    Log.d(TAG,
                            "SurfaceControlViewHost created on thread "
                                    + Thread.currentThread().getId());
                }

                SurfaceControlViewHost viewHost = new SurfaceControlViewHost(viewContext,
                        viewContext.getDisplay(),
                        surfaceView.getHostToken());
                ImageView imageView = new ImageView(viewContext);
                imageView.setBackground(mIconDrawable);
                viewHost.setView(imageView, mIconSize, mIconSize);
                SurfaceControlViewHost.SurfacePackage surfacePackage = viewHost.getSurfacePackage();
                surfaceView.setChildSurfacePackage(surfacePackage);
                view.mSurfacePackage = surfacePackage;
                view.mSurfaceHost = viewHost;
                view.mSurfacePackageCopy = new SurfaceControlViewHost.SurfacePackage(
                        surfacePackage);
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Using copy of SurfacePackage in the client");
                }
                view.mSurfacePackage = mSurfacePackage;
            }
            if (mIconSize != 0) {
                LayoutParams lp = new FrameLayout.LayoutParams(mIconSize, mIconSize);
                lp.gravity = Gravity.CENTER;
                surfaceView.setLayoutParams(lp);
                if (DEBUG) {
                    Log.d(TAG, "Icon size " + mIconSize);
                }
            }

            // We ensure that we can blend the alpha of the surface view with the SplashScreenView
            surfaceView.setUseAlpha();
            surfaceView.setZOrderOnTop(true);
            surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);

            view.addView(surfaceView);
            view.mSurfaceView = surfaceView;
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return surfaceView;
        }
    }

    /** @hide */
    public SplashScreenView(Context context) {
        super(context);
    }

    /** @hide */
    public SplashScreenView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    /**
     * Declared this view is not copyable.
     * @hide
     */
    public void setNotCopyable() {
        mNotCopyable = true;
    }

    /**
     * Whether this view is copyable.
     * @hide
     */
    public boolean isCopyable() {
        return !mNotCopyable;
    }

    /**
     * Called when this {@link SplashScreenView} has been copied to be transferred to the client.
     *
     * @hide
     */
    public void onCopied() {
        mIsCopied = true;
        if (mSurfaceView == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Setting SurfaceView's SurfacePackage to null.");
        }
        // If we don't release the surface package, the surface will be reparented to this
        // surface view. So once it's copied into the client process, we release it.
        mSurfacePackage.release();
        mSurfacePackage = null;
    }

    /** @hide **/
    @Nullable
    public SurfaceControlViewHost getSurfaceHost() {
        return mSurfaceHost;
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);

        // The surface view's alpha is not multiplied with the containing view's alpha, so we
        // manually do it here
        if (mSurfaceView != null) {
            mSurfaceView.setAlpha(mSurfaceView.getAlpha() * alpha);
        }
    }

    /**
     * Returns the duration of the icon animation if icon is animatable.
     *
     * @see android.R.attr#windowSplashScreenAnimatedIcon
     * @see android.R.attr#windowSplashScreenAnimationDuration
     */
    @Nullable
    public Duration getIconAnimationDuration() {
        return mIconAnimationDuration;
    }

    /**
     * If the replaced icon is animatable, return the animation start time based on system clock.
     */
    @Nullable
    public Instant getIconAnimationStart() {
        return mIconAnimationStart;
    }


    void transferSurface() {
        if (mSurfacePackage == null) {
            return;
        }
        if (DEBUG) {
            mSurfacePackage.getSurfaceControl().addOnReparentListener(
                    (transaction, parent) -> Log.e(TAG,
                            String.format("SurfacePackage'surface reparented to %s", parent)));
            Log.d(TAG, "Transferring surface " + mSurfaceView.toString());
        }
        mSurfaceView.setChildSurfacePackage(mSurfacePackage);

    }

    void initIconAnimation(Drawable iconDrawable, long duration) {
        if (!(iconDrawable instanceof IconAnimateListener)) {
            return;
        }
        IconAnimateListener aniDrawable = (IconAnimateListener) iconDrawable;
        aniDrawable.prepareAnimate(duration, this::animationStartCallback);
    }

    private void animationStartCallback() {
        mIconAnimationStart = Instant.now();
    }

    /**
     * <p>Remove this view and release its resource. </p>
     * <p><strong>Do not</strong> invoke this method from a drawing method
     * ({@link #onDraw(android.graphics.Canvas)} for instance).</p>
     */
    @UiThread
    public void remove() {
        if (mHasRemoved) {
            return;
        }
        setVisibility(GONE);
        if (mParceledIconBitmap != null) {
            if (mIconView instanceof ImageView) {
                ((ImageView) mIconView).setImageDrawable(null);
            } else if (mIconView != null) {
                mIconView.setBackground(null);
            }
            mParceledIconBitmap.recycle();
            mParceledIconBitmap = null;
        }
        if (mParceledBrandingBitmap != null) {
            mBrandingImageView.setBackground(null);
            mParceledBrandingBitmap.recycle();
            mParceledBrandingBitmap = null;
        }
        if (mParceledIconBackgroundBitmap != null) {
            if (mIconView != null) {
                mIconView.setBackground(null);
            }
            mParceledIconBackgroundBitmap.recycle();
            mParceledIconBackgroundBitmap = null;
        }
        if (mWindow != null) {
            final DecorView decorView = (DecorView) mWindow.peekDecorView();
            if (DEBUG) {
                Log.d(TAG, "remove starting view");
            }
            if (decorView != null) {
                decorView.removeView(this);
            }
            restoreSystemUIColors();
            mWindow = null;
        }
        if (mHostActivity != null) {
            mHostActivity.setSplashScreenView(null);
            mHostActivity = null;
        }
        mHasRemoved = true;
    }

    /** @hide **/
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseAnimationSurfaceHost();
    }

    private void releaseAnimationSurfaceHost() {
        if (mSurfaceHost != null && !mIsCopied) {
            if (DEBUG) {
                Log.d(TAG,
                        "Shell removed splash screen."
                                + " Releasing SurfaceControlViewHost on thread #"
                                + Thread.currentThread().getId());
            }
            releaseIconHost(mSurfaceHost);
            mSurfaceHost = null;
        } else if (mSurfacePackage != null && mSurfaceHost == null) {
            mSurfacePackage = null;
            mClientCallback.sendResult(null);
        }
    }

    /**
     * Release the host which hold the SurfaceView of the icon.
     * @hide
     */
    public static void releaseIconHost(SurfaceControlViewHost host) {
        final Drawable background = host.getView().getBackground();
        if (background instanceof SplashScreenView.IconAnimateListener) {
            ((SplashScreenView.IconAnimateListener) background).stopAnimation();
        }
        host.release();
    }

    /**
     * Called when this view is attached to an activity. This also makes SystemUI colors
     * transparent so the content of splash screen view can draw fully.
     *
     * @hide
     */
    public void attachHostActivityAndSetSystemUIColors(Activity activity, Window window) {
        activity.setSplashScreenView(this);
        mHostActivity = activity;
        mWindow = window;
        final WindowManager.LayoutParams attr = window.getAttributes();
        mAppWindowFlags = attr.flags;
        mStatusBarColor = window.getStatusBarColor();
        mNavigationBarColor = window.getNavigationBarColor();
        mSystemBarsAppearance = window.getInsetsController().getSystemBarsAppearance();
        mNavigationContrastEnforced = window.isNavigationBarContrastEnforced();
        mStatusContrastEnforced = window.isStatusBarContrastEnforced();
        mDecorFitsSystemWindows = window.decorFitsSystemWindows();

        applySystemBarsContrastColor(window.getInsetsController(), mInitBackgroundColor);
        // Let app draw the background of bars.
        window.addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        // Use specified bar colors instead of window background.
        window.clearFlags(FLAG_TRANSLUCENT_STATUS | FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setDecorFitsSystemWindows(false);
        window.setStatusBarContrastEnforced(false);
        window.setNavigationBarContrastEnforced(false);
    }

    /** Called when this view is removed from the host activity. */
    private void restoreSystemUIColors() {
        mWindow.setFlags(mAppWindowFlags, WINDOW_FLAG_MASK);
        mWindow.setStatusBarColor(mStatusBarColor);
        mWindow.setNavigationBarColor(mNavigationBarColor);
        mWindow.getInsetsController().setSystemBarsAppearance(mSystemBarsAppearance,
                LIGHT_BARS_MASK);
        mWindow.setDecorFitsSystemWindows(mDecorFitsSystemWindows);
        mWindow.setStatusBarContrastEnforced(mStatusContrastEnforced);
        mWindow.setNavigationBarContrastEnforced(mNavigationContrastEnforced);
    }

    /**
     * Makes the icon color of system bars contrast.
     * @hide
     */
    public static void applySystemBarsContrastColor(WindowInsetsController windowInsetsController,
            int backgroundColor) {
        final int lightBarAppearance = ContrastColorUtil.isColorLight(backgroundColor)
                ? LIGHT_BARS_MASK : 0;
        windowInsetsController.setSystemBarsAppearance(lightBarAppearance, LIGHT_BARS_MASK);
    }

    /**
     * Get the view containing the Splash Screen icon and its background.
     * @see android.R.attr#windowSplashScreenAnimatedIcon
     */
    public @Nullable View getIconView() {
        return mIconView;
    }

    /**
     * Get the branding image view.
     * @hide
     */
    @TestApi
    public @Nullable View getBrandingView() {
        return mBrandingImageView;
    }

    /**
     * Get the initial background color of this view.
     * @hide
     */
    public @ColorInt int getInitBackgroundColor() {
        return mInitBackgroundColor;
    }

    /**
     * An interface for an animatable drawable object to register a callback when animation start.
     * @hide
     */
    public interface IconAnimateListener {
        /**
         * Prepare the animation if this drawable also be animatable.
         * @param duration The animation duration.
         * @param startListener The callback listener used to receive the start of the animation.
         * @return true if this drawable object can also be animated and it can be played now.
         */
        boolean prepareAnimate(long duration, Runnable startListener);

        /**
         * Stop animation.
         */
        void stopAnimation();
    }

    /**
     * Use to create {@link SplashScreenView} object across process.
     * @hide
     */
    public static class SplashScreenViewParcelable implements Parcelable {
        private int mIconSize;
        private int mBackgroundColor;
        private Bitmap mIconBackground;

        private Bitmap mIconBitmap = null;
        private int mBrandingWidth;
        private int mBrandingHeight;
        private Bitmap mBrandingBitmap;

        private long mIconAnimationStartMillis;
        private long mIconAnimationDurationMillis;

        private SurfaceControlViewHost.SurfacePackage mSurfacePackage;
        private RemoteCallback mClientCallback;

        public SplashScreenViewParcelable(SplashScreenView view) {
            mIconSize = view.mIconView.getWidth();
            mBackgroundColor = view.getInitBackgroundColor();
            mIconBackground = copyDrawable(view.getIconView().getBackground());
            mSurfacePackage = view.mSurfacePackageCopy;
            if (mSurfacePackage == null) {
                // We only need to copy the drawable if we are not using a SurfaceView
                mIconBitmap = copyDrawable(((ImageView) view.getIconView()).getDrawable());
            }
            mBrandingBitmap = copyDrawable(view.getBrandingView().getBackground());

            ViewGroup.LayoutParams params = view.getBrandingView().getLayoutParams();
            mBrandingWidth = params.width;
            mBrandingHeight = params.height;

            if (view.getIconAnimationStart() != null) {
                mIconAnimationStartMillis = view.getIconAnimationStart().toEpochMilli();
            }
            if (view.getIconAnimationDuration() != null) {
                mIconAnimationDurationMillis = view.getIconAnimationDuration().toMillis();
            }
        }

        private Bitmap copyDrawable(Drawable drawable) {
            if (drawable != null) {
                final Rect initialBounds = drawable.copyBounds();
                final int width = initialBounds.width();
                final int height = initialBounds.height();

                final Bitmap snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                final Canvas bmpCanvas = new Canvas(snapshot);
                drawable.setBounds(0, 0, width, height);
                drawable.draw(bmpCanvas);
                final Bitmap copyBitmap = snapshot.createAshmemBitmap();
                snapshot.recycle();
                return copyBitmap;
            }
            return null;
        }

        private SplashScreenViewParcelable(@NonNull Parcel source) {
            readParcel(source);
        }

        private void readParcel(@NonNull Parcel source) {
            mIconSize = source.readInt();
            mBackgroundColor = source.readInt();
            mIconBitmap = source.readTypedObject(Bitmap.CREATOR);
            mBrandingWidth = source.readInt();
            mBrandingHeight = source.readInt();
            mBrandingBitmap = source.readTypedObject(Bitmap.CREATOR);
            mIconAnimationStartMillis = source.readLong();
            mIconAnimationDurationMillis = source.readLong();
            mIconBackground = source.readTypedObject(Bitmap.CREATOR);
            mSurfacePackage = source.readTypedObject(SurfaceControlViewHost.SurfacePackage.CREATOR);
            mClientCallback = source.readTypedObject(RemoteCallback.CREATOR);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mIconSize);
            dest.writeInt(mBackgroundColor);
            dest.writeTypedObject(mIconBitmap, flags);
            dest.writeInt(mBrandingWidth);
            dest.writeInt(mBrandingHeight);
            dest.writeTypedObject(mBrandingBitmap, flags);
            dest.writeLong(mIconAnimationStartMillis);
            dest.writeLong(mIconAnimationDurationMillis);
            dest.writeTypedObject(mIconBackground, flags);
            dest.writeTypedObject(mSurfacePackage, flags);
            dest.writeTypedObject(mClientCallback, flags);
        }

        public static final @NonNull Parcelable.Creator<SplashScreenViewParcelable> CREATOR =
                new Parcelable.Creator<SplashScreenViewParcelable>() {
                    public SplashScreenViewParcelable createFromParcel(@NonNull Parcel source) {
                        return new SplashScreenViewParcelable(source);
                    }
                    public SplashScreenViewParcelable[] newArray(int size) {
                        return new SplashScreenViewParcelable[size];
                    }
                };

        /**
         * Release the bitmap if another process cannot handle it.
         */
        public void clearIfNeeded() {
            if (mIconBitmap != null) {
                mIconBitmap.recycle();
                mIconBitmap = null;
            }
            if (mBrandingBitmap != null) {
                mBrandingBitmap.recycle();
                mBrandingBitmap = null;
            }
        }

        int getIconSize() {
            return mIconSize;
        }

        int getBackgroundColor() {
            return mBackgroundColor;
        }

        /**
         * Sets the {@link RemoteCallback} that will be called by the client to notify the shell
         * of the removal of the {@link SplashScreenView}.
         */
        public void setClientCallback(@NonNull RemoteCallback clientCallback) {
            mClientCallback = clientCallback;
        }
    }
}
