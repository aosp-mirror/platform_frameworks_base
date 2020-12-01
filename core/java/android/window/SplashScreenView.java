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

import static android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
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
    private View mIconView;
    private Bitmap mParceledBitmap;
    // cache original window and status
    private Window mWindow;
    private boolean mDrawBarBackground;
    private int mStatusBarColor;
    private int mNavigationBarColor;

    /**
     * Internal builder to create a SplashScreenWindowView object.
     * @hide
     */
    public static class Builder {
        private final Context mContext;
        private int mIconSize;
        private @ColorInt int mBackgroundColor;
        private Bitmap mParceledBitmap;
        private Drawable mIconDrawable;

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
            if (parcelable.getBitmap() != null) {
                mIconDrawable = new BitmapDrawable(mContext.getResources(), parcelable.getBitmap());
                mParceledBitmap = parcelable.getBitmap();
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
         * Set the Drawable object to fill the center view.
         */
        public Builder setCenterViewDrawable(Drawable drawable) {
            mIconDrawable = drawable;
            return this;
        }

        /**
         * Create SplashScreenWindowView object from materials.
         */
        public SplashScreenView build() {
            final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
            final SplashScreenView view = (SplashScreenView)
                    layoutInflater.inflate(R.layout.splash_screen_view, null, false);
            view.mInitBackgroundColor = mBackgroundColor;
            view.setBackgroundColor(mBackgroundColor);
            view.mIconView = view.findViewById(R.id.splashscreen_icon_view);
            if (mIconSize != 0) {
                final ViewGroup.LayoutParams params = view.mIconView.getLayoutParams();
                params.width = mIconSize;
                params.height = mIconSize;
                view.mIconView.setLayoutParams(params);
            }
            if (mIconDrawable != null) {
                view.mIconView.setBackground(mIconDrawable);
            }
            if (mParceledBitmap != null) {
                view.mParceledBitmap = mParceledBitmap;
            }
            if (DEBUG) {
                Log.d(TAG, " build " + view + " center view? " + view.mIconView
                        + " iconSize " + mIconSize);
            }
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
     * <p>Remove this view and release its resource. </p>
     * <p><strong>Do not</strong> invoke this method from a drawing method
     * ({@link #onDraw(android.graphics.Canvas)} for instance).</p>
     */
    public void remove() {
        setVisibility(GONE);
        if (mParceledBitmap != null) {
            mIconView.setBackground(null);
            mParceledBitmap.recycle();
            mParceledBitmap = null;
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
     */
    public @Nullable View getIconView() {
        return mIconView;
    }

    /**
     * Get the initial background color of this view.
     * @hide
     */
    @ColorInt int getInitBackgroundColor() {
        return mInitBackgroundColor;
    }

    /**
     * Use to create {@link SplashScreenView} object across process.
     * @hide
     */
    public static class SplashScreenViewParcelable implements Parcelable {
        private int mIconSize;
        private int mBackgroundColor;
        private Bitmap mBitmap;

        public SplashScreenViewParcelable(SplashScreenView view) {
            final ViewGroup.LayoutParams params = view.getIconView().getLayoutParams();
            mIconSize = params.height;
            mBackgroundColor = view.getInitBackgroundColor();

            final Drawable background = view.getIconView().getBackground();
            if (background != null) {
                final Rect initialBounds = background.copyBounds();
                final int width = initialBounds.width();
                final int height = initialBounds.height();

                final Bitmap iconSnapshot = Bitmap.createBitmap(width, height,
                        Bitmap.Config.ARGB_8888);
                final Canvas bmpCanvas = new Canvas(iconSnapshot);
                background.setBounds(0, 0, width, height);
                background.draw(bmpCanvas);
                mBitmap = iconSnapshot.createAshmemBitmap();
                iconSnapshot.recycle();
            }
        }

        private SplashScreenViewParcelable(@NonNull Parcel source) {
            readParcel(source);
        }

        private void readParcel(@NonNull Parcel source) {
            mIconSize = source.readInt();
            mBackgroundColor = source.readInt();
            mBitmap = source.readTypedObject(Bitmap.CREATOR);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mIconSize);
            dest.writeInt(mBackgroundColor);
            dest.writeTypedObject(mBitmap, flags);
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
            if (mBitmap != null) {
                mBitmap.recycle();
                mBitmap = null;
            }
        }

        int getIconSize() {
            return mIconSize;
        }

        int getBackgroundColor() {
            return mBackgroundColor;
        }

        Bitmap getBitmap() {
            return mBitmap;
        }
    }
}
