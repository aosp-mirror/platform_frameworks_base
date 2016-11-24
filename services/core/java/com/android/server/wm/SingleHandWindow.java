/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.wm;

import android.content.Context;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.IWindowManager;
import android.view.Surface;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.app.WallpaperManager;

import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.os.Handler;
import android.graphics.PixelFormat;
import android.widget.RelativeLayout;
import android.view.ViewGroup;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.hardware.display.DisplayManager;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;

/**
 * Manages an single hand window.
 */
final class SingleHandWindow {
    private static final String TAG = "SingleHandWindow";
    private static final boolean DEBUG = false;

    private final static float INITIAL_SCALE = 0.75f;
    private final static float MIN_SCALE = 0.3f;
    private final static float MAX_SCALE = 1.0f;
    private final static float WINDOW_ALPHA = 1.0f;

    private final Context mContext;
    private final String mName;
    private int mWidth;
    private int mHeight;
    private final boolean mLeft;

    private final Display mDefaultDisplay;
    private DisplayInfo mDefaultDisplayInfo = new DisplayInfo();
    private Configuration mConfiguration = new Configuration();

    private DisplayInfo mPreDisplayInfo = new DisplayInfo();

    private View mWindowContent;
    private WindowManager.LayoutParams mWindowParams;
    private TextView mTitleTextView;

    private boolean mWindowVisible;

    private float mWidthScale;
    private float mHeightScale;

    private final DisplayManager mDisplayManager;
    private final WindowManager mWindowManager;
    private final WindowManagerService mService;

    private Handler mHandler;
    private RelativeLayout mRelateViewtop;
    private RelativeLayout mRelateViewbottom;
    private ViewGroup.LayoutParams mLayoutParams;
    private static final String SINGLE_HAND_MODE_HINT_SHOWN = "single_hand_mode_hint_shown";
    private static final String YES = "yes";
    private static final String HINT_INFO_TAG = "hint_info";
    private static final String WINDOW_BG_TAG = "other_area";
    private boolean mPointDownOuter = false;
    private ImageView mImageView;
    private TextView overlay_display_window = null;
    private TextView singlehandmode_slide_hint = null;
    private boolean mAttachedToWindow=false;
    private boolean mIsNeedRelayout=false;
    private boolean mIsBlurTopWindow = false;

    public SingleHandWindow(Context context, boolean left, String name, int width, int height, WindowManagerService service) {
        mContext = context;
        mName = name;
        mWidth = width;
        mHeight = height;
        mLeft = left;
        mHandler = new Handler();
        mWindowManager = (WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE);
        mDisplayManager = (DisplayManager)context.getSystemService(
                Context.DISPLAY_SERVICE);
        mService = service;
        mDefaultDisplay = mWindowManager.getDefaultDisplay();
        mDefaultDisplayInfo = mService.getDefaultDisplayInfoLocked();
        mConfiguration =mContext.getResources().getConfiguration();
        mPreDisplayInfo.copyFrom(mDefaultDisplayInfo);
        if (mName.contains("blurpaper")) {
            mIsBlurTopWindow = true;
            createWindow();
        }
    }

    public void show() {
        if (!mWindowVisible) {
            if (!mIsBlurTopWindow) {
                mService.freezeOrThawRotation(Surface.ROTATION_0);
                mService.setSingleHandMode(mLeft ? 1 : 2);
                mService.requestTraversal();
            }

            mDisplayManager.registerDisplayListener(mDisplayListener, null);
            if (!updateDefaultDisplayInfo()) {
                mDisplayManager.unregisterDisplayListener(mDisplayListener);
                return;
            }

            if (mIsBlurTopWindow) {
                mWindowParams.x = 0;
                mWindowParams.y = 0;
                mWindowParams.width = mWidth;
                mWindowParams.height = mHeight;
                mWindowContent.setOnTouchListener(mOnTouchListener);
                mWindowManager.addView(mWindowContent, mWindowParams);
            }
            mWindowVisible = true;
        }
    }

    public void dismiss() {
        if(mAttachedToWindow){
            mAttachedToWindow = false;
            mContext.unregisterReceiver(mIntentReceiver);
        }
        if (mWindowVisible) {
            if (!updateDefaultDisplayInfo()) {
                mDisplayManager.unregisterDisplayListener(mDisplayListener);
                return;
            }
            if (!mIsBlurTopWindow) {
                mHandler.postDelayed(new Runnable(){
                        public void run() {
                            mService.freezeOrThawRotation(-1);
                        }
                }, 100);
                mService.setSingleHandMode(0);
            } else {
                mWindowManager.removeView(mWindowContent);
            }
            mWindowVisible = false;
        }
    }

    public void relayout() {
        if (mWindowVisible && mIsBlurTopWindow) {
            mWindowManager.removeView(mWindowContent);
            mWindowVisible=false;
            createWindow();
            updateWindowParams();
            mWindowContent.setOnTouchListener(mOnTouchListener);
            mWindowManager.addView(mWindowContent, mWindowParams);
            mWindowVisible=true;

        }
    }

    private Bitmap cropwallpaper(boolean isTop) {
        if(SingleHandAdapter.scaleWallpaper == null) {
            return null;
        }
        int w = SingleHandAdapter.scaleWallpaper.getWidth();
        int h = SingleHandAdapter.scaleWallpaper.getHeight();
        Bitmap crop;
        if (isTop) {
            crop = Bitmap.createBitmap(SingleHandAdapter.scaleWallpaper, 0, 0, w, (int)(h * (1-INITIAL_SCALE)));
        } else {
            if (mLeft) {
                crop = Bitmap.createBitmap(SingleHandAdapter.scaleWallpaper, (int)(w * INITIAL_SCALE), (int)(h * (1-INITIAL_SCALE)), (int)(w - w * INITIAL_SCALE), (int)(h * INITIAL_SCALE));
            } else {
                crop = Bitmap.createBitmap(SingleHandAdapter.scaleWallpaper, 0, (int)(h * (1-INITIAL_SCALE)), (int)(w - w * INITIAL_SCALE), (int)(h * INITIAL_SCALE));
            }
        }
        return crop;
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (null == intent || null == intent.getAction()) {
                return;
            }
            final String action = intent.getAction();
            if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                updateLocale();
            }

            if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateConfiguration();
            }

        }
    };

    void updateLocale() {
        Slog.d(TAG, "updateLocale .");
        if (null != overlay_display_window) {
            overlay_display_window.setText(mContext.getResources().getString(com.android.internal.R.string.singlehandmode_click_hint_message));
        }
        if (null != singlehandmode_slide_hint) {
            singlehandmode_slide_hint.setText(mContext.getResources().getString(com.android.internal.R.string.singlehandmode_slide_hint_message));
        }
    }

    void updateConfiguration() {
        Configuration newConfiguration = mContext.getResources().getConfiguration();
        int diff = mConfiguration.diff(newConfiguration);
        mConfiguration=newConfiguration;
        if (DEBUG) {
            Slog.d(TAG, "updateConfiguration diff =#0x"+Integer.toHexString(diff));       
        }
        if ((diff & ActivityInfo.CONFIG_ORIENTATION) != 0) {
            Settings.Global.putString(mContext.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, "");
            return;
        }
    }

    private boolean updateDefaultDisplayInfo() {
        boolean value=false;
        mIsNeedRelayout=false;
        value=mDefaultDisplay.getDisplayInfo(mDefaultDisplayInfo);
        if (!value) {
            Slog.w(TAG, "Cannot show overlay display because there is no "
                    + "default display upon which to show it.");
            return false;
        }
        if (mPreDisplayInfo == null) {
            return false;
        }
        if (!mPreDisplayInfo.equals(mDefaultDisplayInfo)) {
            mWidthScale = (float)mDefaultDisplayInfo.logicalWidth/mPreDisplayInfo.logicalWidth;
            mHeightScale = (float)mDefaultDisplayInfo.logicalHeight/mPreDisplayInfo.logicalHeight;
            if (mDefaultDisplayInfo.logicalWidth !=mPreDisplayInfo.logicalWidth
                || mDefaultDisplayInfo.logicalHeight !=mPreDisplayInfo.logicalHeight
                || mDefaultDisplayInfo.logicalDensityDpi !=mPreDisplayInfo.logicalDensityDpi
                )
            mIsNeedRelayout=true;
            mPreDisplayInfo.copyFrom(mDefaultDisplayInfo);
        }
        return true;
    }
    
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == mDefaultDisplay.getDisplayId()) {
                if (updateDefaultDisplayInfo()) {
                    if(mIsNeedRelayout)
                    relayout();
                } else {
                    dismiss();
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            if (displayId == mDefaultDisplay.getDisplayId()) {
                dismiss();
            }
        }
    };

    public void updateLayoutParams() {
        mLayoutParams = mRelateViewtop.getLayoutParams();
        mLayoutParams.height = mDefaultDisplayInfo.logicalHeight / 4;
        mRelateViewtop.setLayoutParams(mLayoutParams);

        mLayoutParams = mRelateViewbottom.getLayoutParams();
        mLayoutParams.height = mDefaultDisplayInfo.logicalHeight * 3 / 4;
        mLayoutParams.width = mDefaultDisplayInfo.logicalWidth / 4;
        if (mLeft)
            ((android.widget.RelativeLayout.LayoutParams)mLayoutParams).addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        else
            ((android.widget.RelativeLayout.LayoutParams)mLayoutParams).addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        ((android.widget.RelativeLayout.LayoutParams)mLayoutParams).addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        mRelateViewbottom.setLayoutParams(mLayoutParams);
    }

    private void createWindow() {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        Drawable mDrawable;
        Drawable mDrawable1;

        mWindowContent = inflater.inflate(
            com.android.internal.R.layout.single_hand_window, null);

        boolean hintShown = isSingleHandModeHintShown();

        if (!mAttachedToWindow) {
            mAttachedToWindow = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);
        }

        if (mIsBlurTopWindow) {
            synchronized (SingleHandAdapter.mLock) {
                mWindowContent.setBackgroundColor(0x00000000);
                mRelateViewtop = (RelativeLayout)mWindowContent.findViewById(
                        com.android.internal.R.id.relative_top);
                mLayoutParams = mRelateViewtop.getLayoutParams();
                mLayoutParams.height = mDefaultDisplayInfo.logicalHeight / 4;
                mRelateViewtop.setLayoutParams(mLayoutParams);
                Bitmap bg = cropwallpaper(true);
                if(null != bg) {
                    mDrawable = new BitmapDrawable(mRelateViewtop.getResources(), bg);
                    mRelateViewtop.setBackground(mDrawable);
                }

                mRelateViewbottom = (RelativeLayout)mWindowContent.findViewById(
                        com.android.internal.R.id.relative_bottom);
                mLayoutParams = mRelateViewbottom.getLayoutParams();
                mLayoutParams.height = mDefaultDisplayInfo.logicalHeight * 3 / 4;
                mLayoutParams.width = mDefaultDisplayInfo.logicalWidth / 4;
                if (mLeft)
                    ((android.widget.RelativeLayout.LayoutParams)mLayoutParams).addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                else
                    ((android.widget.RelativeLayout.LayoutParams)mLayoutParams).addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                ((android.widget.RelativeLayout.LayoutParams)mLayoutParams).addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                mRelateViewbottom.setLayoutParams(mLayoutParams);
                Bitmap bg1 = cropwallpaper(false);
                if(bg1 != null) {
                    mDrawable1 = new BitmapDrawable(mRelateViewbottom.getResources(), bg1);
                    mRelateViewbottom.setBackground(mDrawable1);
                }
            }
        }

        mWindowParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_DISPLAY_OVERLAY);
        mWindowParams.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        mWindowParams.privateFlags |=
                WindowManager.LayoutParams.PRIVATE_FLAG_FORCE_HARDWARE_ACCELERATED;
        mWindowParams.alpha = WINDOW_ALPHA;
        mWindowParams.gravity = Gravity.TOP | Gravity.LEFT;
        mWindowParams.format = PixelFormat.TRANSLUCENT;

        if (mIsBlurTopWindow) {
            showHint(!hintShown);
            mWidthScale = 1.0f;
            mHeightScale = 1.0f;
        }
    }

    private void updateWindowParams() {
        mWindowParams.x = 0;
        mWindowParams.y = 0;
        mWindowParams.width = mDefaultDisplayInfo.logicalWidth;
        mWindowParams.height = mDefaultDisplayInfo.logicalHeight;

        mWidth = (int)(mWidth * mWidthScale);
        mHeight = (int)(mHeight * mHeightScale);
    }

    boolean isSingleHandModeHintShown() {
        String value = Settings.Global.getString(mContext.getContentResolver(),
                SINGLE_HAND_MODE_HINT_SHOWN);
        if (value == null || !value.equals(YES)) {
            return false;
        } else {
            return true;
        }
    }

    private void show(View v, boolean visible) {
        if (null != v) {
            if (visible) {
                v.setVisibility(View.VISIBLE);
            } else {
                v.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showHint(boolean visible) {
        FrameLayout layout;
        /* if hint visible, hide the hint_info_icon; else show it */
        mImageView = (ImageView) mWindowContent.findViewById(com.android.internal.R.id.hint_info);
        layout = (FrameLayout) mWindowContent.findViewById(com.android.internal.R.id.hint_section);
        layout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
               mImageView.performClick();
            }
        });
        if (!visible && mImageView != null) {
            mImageView.setOnClickListener(mActionClickListener);
        }
        show(mImageView, !visible);

        /* setBackground so that hint is clear to see */
        if (!visible) {
            mWindowContent.setBackgroundColor(0x00000000);
        } else {
            mWindowContent.setBackgroundColor(0x99000000);
        }

        /* show/hide click hint  */
        overlay_display_window = (TextView)mWindowContent.findViewById(com.android.internal.R.id.single_hand_window_title);
        show(overlay_display_window, visible);

        ImageView imageView = (ImageView) mWindowContent.findViewById(com.android.internal.R.id.click_hint);

        show(imageView, visible);

        /* put slide_hint in correct place */
        LinearLayout viewSlideHint = (LinearLayout)mWindowContent.findViewById(com.android.internal.R.id.slide_hint_area);
        if (null != viewSlideHint) {
            if (visible) {
                ViewGroup.LayoutParams layoutParams = viewSlideHint.getLayoutParams();
                if (mLeft)
                    ((android.widget.RelativeLayout.LayoutParams)layoutParams).addRule(RelativeLayout.ALIGN_PARENT_LEFT);
                else
                    ((android.widget.RelativeLayout.LayoutParams)layoutParams).addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                viewSlideHint.setLayoutParams(layoutParams);
                viewSlideHint.setVisibility(View.VISIBLE);
            } else {
                viewSlideHint.setVisibility(View.INVISIBLE);
            }
        }

        /* show/hide slide hint */
        singlehandmode_slide_hint = (TextView)mWindowContent.findViewById(com.android.internal.R.id.singlehandmode_slide_hint_text);
        show(singlehandmode_slide_hint, visible);
        imageView = (ImageView) mWindowContent.findViewById(com.android.internal.R.id.slide_hint);
        if (null != imageView) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) imageView.getLayoutParams();
            params.width = (int)(mDefaultDisplayInfo.logicalWidth * INITIAL_SCALE);
            params.height = (int)(mContext.getResources().getDimensionPixelSize(com.android.internal.R.dimen.navigation_bar_height) * INITIAL_SCALE);
            imageView.setLayoutParams(params);
        }
        show(imageView, visible);

        /* update title, InputFlinger filter click_event depending on title */
        if (visible) {
            mWindowParams.setTitle("SingleMode_windowbg_hint");
        } else {
            if (mLeft) {
                mWindowParams.setTitle("SingleMode_windowbg_left");
            } else {
                mWindowParams.setTitle("SingleMode_windowbg_right");
            }
        }
        if (mWindowVisible) {
            mWindowManager.updateViewLayout(mWindowContent, mWindowParams);
        }
    }

    boolean singlehandRegionContainsPoint (int x, int y) {
        int top = (int)((float)mDefaultDisplayInfo.logicalHeight * (1-INITIAL_SCALE));
        int bottom = mDefaultDisplayInfo.logicalHeight;
        int left;
        int right;

        if(mLeft) {
            left = 0;
            right = (int)(mDefaultDisplayInfo.logicalWidth * INITIAL_SCALE);
        } else {
            left = (int)(mDefaultDisplayInfo.logicalWidth * (1-INITIAL_SCALE));
            right = mDefaultDisplayInfo.logicalWidth;
        }

        if (y >= top && y < bottom && x >= left && x < right) {
            return true;
        }

        return false;
    }

    private final View.OnTouchListener mOnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            final float rawX = event.getRawX();
            final float rawY = event.getRawY();
            boolean inRegion = singlehandRegionContainsPoint((int)rawX, (int)rawY);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mPointDownOuter = !inRegion;
                    break;
                case MotionEvent.ACTION_UP:
                    ImageView imageView = (ImageView) mWindowContent.findViewById(com.android.internal.R.id.hint_info);
                    /* In case hint_info icon is shown, clicking empty_area trigger Exiting SingleHandMode */
                    if (imageView != null && imageView.getVisibility() == View.VISIBLE) {
                        if (!inRegion && mPointDownOuter) {
                            Settings.Global.putString(mContext.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, "");
                        }
                    } else {
                        /* Else, clicking any_area trigger Hidding hint and show hint_info Icon */
                        // hide hint
                        showHint(false);
                        // update Setting, so not show hint again when entering single_hand_mode
                        Settings.Global.putString(mContext.getContentResolver(), SINGLE_HAND_MODE_HINT_SHOWN, YES);
                    }

                    mPointDownOuter = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    mPointDownOuter = false;
                    break;
                default:
                    break;
            }

            return true;
        }
    };

    private View.OnClickListener mActionClickListener = new View.OnClickListener(){
        @Override
        public void onClick(View v) {
            // Click the hint_info icon
            showHint(true);
        }
    };

    public void onBlurWallpaperChanged() {
        Drawable mDrawable;
        Drawable mDrawable1;
        mRelateViewtop = (RelativeLayout)mWindowContent.findViewById(
                com.android.internal.R.id.relative_top);
        Bitmap bg = cropwallpaper(true);
        if (bg != null) {
            mDrawable = new BitmapDrawable(mRelateViewtop.getResources(), bg);
            mRelateViewtop.setBackground(mDrawable);
        }
        mRelateViewbottom = (RelativeLayout)mWindowContent.findViewById(
                com.android.internal.R.id.relative_bottom);
        Bitmap bg1 = cropwallpaper(false);
        if (bg1 != null) {
            mDrawable1 = new BitmapDrawable(mRelateViewbottom.getResources(), bg1);
            mRelateViewbottom.setBackground(mDrawable1);
        }
    }
}
