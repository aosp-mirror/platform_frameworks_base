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
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Trace;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.internal.R;
import com.android.internal.policy.DecorView;

import java.time.Duration;
import java.time.Instant;

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
    private static final boolean DEBUG = false;

    private boolean mNotCopyable;
    private int mInitBackgroundColor;
    private int mInitIconBackgroundColor;
    private View mIconView;
    private Bitmap mParceledIconBitmap;
    private View mBrandingImageView;
    private Bitmap mParceledBrandingBitmap;
    private Duration mIconAnimationDuration;
    private Instant mIconAnimationStart;

    // The host activity when transfer view to it.
    private Activity mHostActivity;
    // cache original window and status
    private Window mWindow;
    private boolean mDrawBarBackground;
    private int mStatusBarColor;
    private int mNavigationBarColor;
    private boolean mHasRemoved;

    /**
     * Internal builder to create a SplashScreenView object.
     * @hide
     */
    public static class Builder {
        private final Context mContext;
        private int mIconSize;
        private @ColorInt int mBackgroundColor;
        private @ColorInt int mIconBackground;
        private Bitmap mParceledIconBitmap;
        private Drawable mIconDrawable;
        private int mBrandingImageWidth;
        private int mBrandingImageHeight;
        private Drawable mBrandingDrawable;
        private Bitmap mParceledBrandingBitmap;
        private Instant mIconAnimationStart;
        private Duration mIconAnimationDuration;

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
            mIconBackground = parcelable.getIconBackground();
            if (parcelable.mIconBitmap != null) {
                mIconDrawable = new BitmapDrawable(mContext.getResources(), parcelable.mIconBitmap);
                mParceledIconBitmap = parcelable.mIconBitmap;
            }
            if (parcelable.mBrandingBitmap != null) {
                setBrandingDrawable(new BitmapDrawable(mContext.getResources(),
                                parcelable.mBrandingBitmap), parcelable.mBrandingWidth,
                        parcelable.mBrandingHeight);
                mParceledBrandingBitmap = parcelable.mBrandingBitmap;
            }
            mIconAnimationStart = Instant.ofEpochMilli(parcelable.mIconAnimationStartMillis);
            mIconAnimationDuration = Duration.ofMillis(parcelable.mIconAnimationDurationMillis);
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
         * Set the Drawable object to fill the center view.
         */
        public Builder setCenterViewDrawable(Drawable drawable) {
            mIconDrawable = drawable;
            return this;
        }

        /**
         * Set the background color for the icon.
         */
        public Builder setIconBackground(int color) {
            mIconBackground = color;
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
         * Set the Drawable object and size for the branding view.
         */
        public Builder setBrandingDrawable(Drawable branding, int width, int height) {
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
            view.mInitIconBackgroundColor = mIconBackground;
            view.setBackgroundColor(mBackgroundColor);
            view.mIconView = view.findViewById(R.id.splashscreen_icon_view);
            view.mBrandingImageView = view.findViewById(R.id.splashscreen_branding_view);
            // center icon
            if (mIconSize != 0) {
                final ViewGroup.LayoutParams params = view.mIconView.getLayoutParams();
                params.width = mIconSize;
                params.height = mIconSize;
                view.mIconView.setLayoutParams(params);
            }
            if (mIconDrawable != null) {
                view.mIconView.setBackground(mIconDrawable);
                view.initIconAnimation(mIconDrawable,
                        mIconAnimationDuration != null ? mIconAnimationDuration.toMillis() : 0);
            }
            view.mIconAnimationStart = mIconAnimationStart;
            view.mIconAnimationDuration = mIconAnimationDuration;
            if (mParceledIconBitmap != null) {
                view.mParceledIconBitmap = mParceledIconBitmap;
            }
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
                Log.d(TAG, " build " + view + " Icon: view: " + view.mIconView + " drawable: "
                        + mIconDrawable + " size: " + mIconSize + "\n Branding: view: "
                        + view.mBrandingImageView + " drawable: " + mBrandingDrawable
                        + " size w: " + mBrandingImageWidth + " h: " + mBrandingImageHeight);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return view;
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

    void initIconAnimation(Drawable iconDrawable, long duration) {
        if (!(iconDrawable instanceof SplashscreenIconDrawable)) {
            return;
        }
        SplashscreenIconDrawable aniDrawable = (SplashscreenIconDrawable) iconDrawable;
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
            mIconView.setBackground(null);
            mParceledIconBitmap.recycle();
            mParceledIconBitmap = null;
        }
        if (mParceledBrandingBitmap != null) {
            mBrandingImageView.setBackground(null);
            mParceledBrandingBitmap.recycle();
            mParceledBrandingBitmap = null;
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
            mHostActivity.detachSplashScreenView();
        }
        mHasRemoved = true;
    }

    /**
     * Called when this view is attached to an activity.
     * @hide
     */
    public void attachHostActivity(Activity activity) {
        mHostActivity = activity;
    }

    /**
     * Cache the root window.
     * @hide
     */
    public void cacheRootWindow(Window window) {
        mWindow = window;
    }

    /**
     * Called after SplashScreenView has added on the root window.
     * @hide
     */
    public void makeSystemUIColorsTransparent() {
        if (mWindow != null) {
            final WindowManager.LayoutParams attr = mWindow.getAttributes();
            mDrawBarBackground = (attr.flags & FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0;
            mWindow.addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            mStatusBarColor = mWindow.getStatusBarColor();
            mNavigationBarColor = mWindow.getNavigationBarDividerColor();
            mWindow.setStatusBarColor(Color.TRANSPARENT);
            mWindow.setNavigationBarColor(Color.TRANSPARENT);
        }
        setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void restoreSystemUIColors() {
        if (mWindow != null) {
            if (!mDrawBarBackground) {
                mWindow.clearFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            }
            mWindow.setStatusBarColor(mStatusBarColor);
            mWindow.setNavigationBarColor(mNavigationBarColor);
        }
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
     * Get the icon background color
     * @hide
     */
    @TestApi
    public @ColorInt int getIconBackgroundColor() {
        return mInitIconBackgroundColor;
    }

    /**
     * Get the initial background color of this view.
     * @hide
     */
    public @ColorInt int getInitBackgroundColor() {
        return mInitBackgroundColor;
    }

    /**
     * A lightweight Drawable object to make the view drawing faster and keep this
     * drawable masked by config_icon_mask.
     * @hide
     */
    public abstract static class SplashscreenIconDrawable extends Drawable {
        /**
         * Prepare the animation if this drawable also be animatable.
         * @param duration The animation duration.
         * @param startListener The callback listener used to receive the start of the animation.
         * @return true if this drawable object can also be animated and it can be played now.
         */
        protected boolean prepareAnimate(long duration, Runnable startListener) {
            return false;
        }
    }

    /**
     * Use to create {@link SplashScreenView} object across process.
     * @hide
     */
    public static class SplashScreenViewParcelable implements Parcelable {
        private int mIconSize;
        private int mBackgroundColor;
        private int mIconBackground;

        private Bitmap mIconBitmap;
        private int mBrandingWidth;
        private int mBrandingHeight;
        private Bitmap mBrandingBitmap;

        private long mIconAnimationStartMillis;
        private long mIconAnimationDurationMillis;

        public SplashScreenViewParcelable(SplashScreenView view) {
            ViewGroup.LayoutParams params = view.getIconView().getLayoutParams();
            mIconSize = params.height;
            mBackgroundColor = view.getInitBackgroundColor();
            mIconBackground = view.getIconBackgroundColor();
            mIconBitmap = copyDrawable(view.getIconView().getBackground());
            mBrandingBitmap = copyDrawable(view.getBrandingView().getBackground());
            params = view.getBrandingView().getLayoutParams();
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
            mIconBackground = source.readInt();
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
            dest.writeInt(mIconBackground);
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

        int getIconBackground() {
            return mIconBackground;
        }
    }
}
