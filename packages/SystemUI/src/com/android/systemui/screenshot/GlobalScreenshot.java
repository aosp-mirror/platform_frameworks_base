/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.screenshot;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Environment;
import android.os.ServiceManager;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.Thread;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * POD used in the AsyncTask which saves an image in the background.
 */
class SaveImageInBackgroundData {
    Context context;
    Bitmap image;
    int result;
}

/**
 * An AsyncTask that saves an image to the media store in the background.
 */
class SaveImageInBackgroundTask extends AsyncTask<SaveImageInBackgroundData, Void,
        SaveImageInBackgroundData> {
    private static final String TAG = "SaveImageInBackgroundTask";
    private static final String SCREENSHOTS_DIR_NAME = "Screenshots";
    private static final String SCREENSHOT_FILE_NAME_TEMPLATE = "Screenshot_%s.png";
    private static final String SCREENSHOT_FILE_PATH_TEMPLATE = "%s/%s/%s";

    @Override
    protected SaveImageInBackgroundData doInBackground(SaveImageInBackgroundData... params) {
        if (params.length != 1) return null;

        Context context = params[0].context;
        Bitmap image = params[0].image;

        try {
            long currentTime = System.currentTimeMillis();
            String date = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date(currentTime));
            String imageDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES).getAbsolutePath();
            String imageFileName = String.format(SCREENSHOT_FILE_NAME_TEMPLATE, date);
            String imageFilePath = String.format(SCREENSHOT_FILE_PATH_TEMPLATE, imageDir,
                    SCREENSHOTS_DIR_NAME, imageFileName);

            // Save the screenshot to the MediaStore
            ContentValues values = new ContentValues();
            ContentResolver resolver = context.getContentResolver();
            values.put(MediaStore.Images.ImageColumns.DATA, imageFilePath);
            values.put(MediaStore.Images.ImageColumns.TITLE, imageFileName);
            values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, imageFileName);
            values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, currentTime);
            values.put(MediaStore.Images.ImageColumns.DATE_ADDED, currentTime);
            values.put(MediaStore.Images.ImageColumns.DATE_MODIFIED, currentTime);
            values.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/png");
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            OutputStream out = resolver.openOutputStream(uri);
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();

            // update file size in the database
            values.clear();
            values.put(MediaStore.Images.ImageColumns.SIZE, new File(imageFilePath).length());
            resolver.update(uri, values, null, null);

            params[0].result = 0;
        } catch (Exception e) {
            // IOException/UnsupportedOperationException may be thrown if external storage is not
            // mounted
            params[0].result = 1;
        }

        return params[0];
    };

    @Override
    protected void onPostExecute(SaveImageInBackgroundData params) {
        if (params.result > 0) {
            // Show a message that we've failed to save the image to disk
            Toast.makeText(params.context, R.string.screenshot_failed_toast,
                    Toast.LENGTH_SHORT).show();
        } else {
            // Show a message that we've saved the screenshot to disk
            Toast.makeText(params.context, R.string.screenshot_saving_toast,
                    Toast.LENGTH_SHORT).show();
        }
    };
}

/**
 * TODO:
 *   - Performance when over gl surfaces? Ie. Gallery
 *   - what do we say in the Toast? Which icon do we get if the user uses another
 *     type of gallery?
 */
class GlobalScreenshot {
    private static final String TAG = "GlobalScreenshot";
    private static final int SCREENSHOT_FADE_IN_DURATION = 900;
    private static final int SCREENSHOT_FADE_OUT_DELAY = 1000;
    private static final int SCREENSHOT_FADE_OUT_DURATION = 450;
    private static final int TOAST_FADE_IN_DURATION = 500;
    private static final int TOAST_FADE_OUT_DELAY = 1000;
    private static final int TOAST_FADE_OUT_DURATION = 500;
    private static final float BACKGROUND_ALPHA = 0.65f;
    private static final float SCREENSHOT_SCALE = 0.85f;
    private static final float SCREENSHOT_MIN_SCALE = 0.7f;
    private static final float SCREENSHOT_ROTATION = -6.75f; // -12.5f;

    private Context mContext;
    private LayoutInflater mLayoutInflater;
    private IWindowManager mIWindowManager;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowLayoutParams;
    private Display mDisplay;
    private DisplayMetrics mDisplayMetrics;
    private Matrix mDisplayMatrix;

    private Bitmap mScreenBitmap;
    private View mScreenshotLayout;
    private ImageView mBackgroundView;
    private FrameLayout mScreenshotContainerView;
    private ImageView mScreenshotView;

    private AnimatorSet mScreenshotAnimation;

    // General use cubic interpolator
    final TimeInterpolator mCubicInterpolator = new TimeInterpolator() {
        public float getInterpolation(float t) {
            return t*t*t;
        }
    };
    // The interpolator used to control the background alpha at the start of the animation
    final TimeInterpolator mBackgroundViewAlphaInterpolator = new TimeInterpolator() {
        public float getInterpolation(float t) {
            float tStep = 0.35f;
            if (t < tStep) {
                return t * (1f / tStep);
            } else {
                return 1f;
            }
        }
    };

    /**
     * @param context everything needs a context :(
     */
    public GlobalScreenshot(Context context) {
        mContext = context;
        mLayoutInflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Inflate the screenshot layout
        mDisplayMetrics = new DisplayMetrics();
        mDisplayMatrix = new Matrix();
        mScreenshotLayout = mLayoutInflater.inflate(R.layout.global_screenshot, null);
        mBackgroundView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot_background);
        mScreenshotContainerView = (FrameLayout) mScreenshotLayout.findViewById(R.id.global_screenshot_container);
        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot);
        mScreenshotLayout.setFocusable(true);
        mScreenshotLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Intercept and ignore all touch events
                return true;
            }
        });

        // Setup the window that we are going to use
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED_SYSTEM
                    | WindowManager.LayoutParams.FLAG_KEEP_SURFACE_WHILE_ANIMATING
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.token = new Binder();
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread() {
        SaveImageInBackgroundData data = new SaveImageInBackgroundData();
        data.context = mContext;
        data.image = mScreenBitmap;
        new SaveImageInBackgroundTask().execute(data);
    }

    /**
     * @return the current display rotation in degrees
     */
    private float getDegreesForRotation(int value) {
        switch (value) {
        case Surface.ROTATION_90:
            return 90f;
        case Surface.ROTATION_180:
            return 180f;
        case Surface.ROTATION_270:
            return 270f;
        }
        return 0f;
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    void takeScreenshot() {
        // We need to orient the screenshot correctly (and the Surface api seems to take screenshots
        // only in the natural orientation of the device :!)
        mDisplay.getRealMetrics(mDisplayMetrics);
        float[] dims = {mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels};
        float degrees = getDegreesForRotation(mDisplay.getRotation());
        boolean requiresRotation = (degrees > 0);
        if (requiresRotation) {
            // Get the dimensions of the device in its native orientation
            mDisplayMatrix.reset();
            mDisplayMatrix.preRotate(-degrees);
            mDisplayMatrix.mapPoints(dims);
            dims[0] = Math.abs(dims[0]);
            dims[1] = Math.abs(dims[1]);
        }
        mScreenBitmap = Surface.screenshot((int) dims[0], (int) dims[1]);
        if (requiresRotation) {
            // Rotate the screenshot to the current orientation
            Bitmap ss = Bitmap.createBitmap(mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(ss);
            c.translate(ss.getWidth() / 2, ss.getHeight() / 2);
            c.rotate(360f - degrees);
            c.translate(-dims[0] / 2, -dims[1] / 2);
            c.drawBitmap(mScreenBitmap, 0, 0, null);
            c.setBitmap(null);
            mScreenBitmap = ss;
        }

        // If we couldn't take the screenshot, notify the user
        if (mScreenBitmap == null) {
            Toast.makeText(mContext, R.string.screenshot_failed_toast,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // Start the post-screenshot animation
        startAnimation();
    }


    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation() {
        // Add the view for the animation
        mScreenshotView.setImageBitmap(mScreenBitmap);
        mScreenshotLayout.requestFocus();

        // Setup the animation with the screenshot just taken
        if (mScreenshotAnimation != null) {
            mScreenshotAnimation.end();
        }

        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        ValueAnimator screenshotFadeInAnim = createScreenshotFadeInAnimation();
        ValueAnimator screenshotFadeOutAnim = createScreenshotFadeOutAnimation();
        mScreenshotAnimation = new AnimatorSet();
        mScreenshotAnimation.play(screenshotFadeInAnim).before(screenshotFadeOutAnim);
        mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Save the screenshot once we have a bit of time now
                saveScreenshotInWorkerThread();

                mWindowManager.removeView(mScreenshotLayout);
            }
        });
        mScreenshotAnimation.start();
    }
    private ValueAnimator createScreenshotFadeInAnimation() {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setInterpolator(mCubicInterpolator);
        anim.setDuration(SCREENSHOT_FADE_IN_DURATION);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mBackgroundView.setVisibility(View.VISIBLE);
                mScreenshotContainerView.setVisibility(View.VISIBLE);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = ((Float) animation.getAnimatedValue()).floatValue();
                mBackgroundView.setAlpha(mBackgroundViewAlphaInterpolator.getInterpolation(t) *
                        BACKGROUND_ALPHA);
                float scaleT = SCREENSHOT_SCALE + (1f - t) * SCREENSHOT_SCALE;
                mScreenshotContainerView.setAlpha(t*t*t*t);
                mScreenshotContainerView.setScaleX(scaleT);
                mScreenshotContainerView.setScaleY(scaleT);
                mScreenshotContainerView.setRotation(t * SCREENSHOT_ROTATION);
            }
        });
        return anim;
    }
    private ValueAnimator createScreenshotFadeOutAnimation() {
        ValueAnimator anim = ValueAnimator.ofFloat(1f, 0f);
        anim.setInterpolator(mCubicInterpolator);
        anim.setStartDelay(SCREENSHOT_FADE_OUT_DELAY);
        anim.setDuration(SCREENSHOT_FADE_OUT_DURATION);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBackgroundView.setVisibility(View.GONE);
                mScreenshotContainerView.setVisibility(View.GONE);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = ((Float) animation.getAnimatedValue()).floatValue();
                float scaleT = SCREENSHOT_MIN_SCALE +
                        t*(SCREENSHOT_SCALE - SCREENSHOT_MIN_SCALE);
                mScreenshotContainerView.setAlpha(t);
                mScreenshotContainerView.setScaleX(scaleT);
                mScreenshotContainerView.setScaleY(scaleT);
                mBackgroundView.setAlpha(t * t * BACKGROUND_ALPHA);
            }
        });
        return anim;
    }
}
