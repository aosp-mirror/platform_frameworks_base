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
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Notification;
import android.app.Notification.BigPictureStyle;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.ImageView;

import com.android.systemui.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * POD used in the AsyncTask which saves an image in the background.
 */
class SaveImageInBackgroundData {
    Context context;
    Bitmap image;
    Uri imageUri;
    Runnable finisher;
    int iconSize;
    int result;
    int previewWidth;
    int previewheight;

    void clearImage() {
        image = null;
        imageUri = null;
        iconSize = 0;
    }
    void clearContext() {
        context = null;
    }
}

/**
 * An AsyncTask that saves an image to the media store in the background.
 */
class SaveImageInBackgroundTask extends AsyncTask<SaveImageInBackgroundData, Void,
        SaveImageInBackgroundData> {
    private static final String TAG = "SaveImageInBackgroundTask";

    private static final String SCREENSHOTS_DIR_NAME = "Screenshots";
    private static final String SCREENSHOT_FILE_NAME_TEMPLATE = "Screenshot_%s.png";
    private static final String SCREENSHOT_SHARE_SUBJECT_TEMPLATE = "Screenshot (%s)";

    private final int mNotificationId;
    private final NotificationManager mNotificationManager;
    private final Notification.Builder mNotificationBuilder, mPublicNotificationBuilder;
    private final File mScreenshotDir;
    private final String mImageFileName;
    private final String mImageFilePath;
    private final long mImageTime;
    private final BigPictureStyle mNotificationStyle;
    private final int mImageWidth;
    private final int mImageHeight;

    // WORKAROUND: We want the same notification across screenshots that we update so that we don't
    // spam a user's notification drawer.  However, we only show the ticker for the saving state
    // and if the ticker text is the same as the previous notification, then it will not show. So
    // for now, we just add and remove a space from the ticker text to trigger the animation when
    // necessary.
    private static boolean mTickerAddSpace;

    SaveImageInBackgroundTask(Context context, SaveImageInBackgroundData data,
            NotificationManager nManager, int nId) {
        Resources r = context.getResources();

        // Prepare all the output metadata
        mImageTime = System.currentTimeMillis();
        String imageDate = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date(mImageTime));
        mImageFileName = String.format(SCREENSHOT_FILE_NAME_TEMPLATE, imageDate);

        mScreenshotDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), SCREENSHOTS_DIR_NAME);
        mImageFilePath = new File(mScreenshotDir, mImageFileName).getAbsolutePath();

        // Create the large notification icon
        mImageWidth = data.image.getWidth();
        mImageHeight = data.image.getHeight();
        int iconSize = data.iconSize;
        int previewWidth = data.previewWidth;
        int previewHeight = data.previewheight;

        Canvas c = new Canvas();
        Paint paint = new Paint();
        ColorMatrix desat = new ColorMatrix();
        desat.setSaturation(0.25f);
        paint.setColorFilter(new ColorMatrixColorFilter(desat));
        Matrix matrix = new Matrix();
        int overlayColor = 0x40FFFFFF;

        Bitmap picture = Bitmap.createBitmap(previewWidth, previewHeight, data.image.getConfig());
        matrix.setTranslate((previewWidth - mImageWidth) / 2, (previewHeight - mImageHeight) / 2);
        c.setBitmap(picture);
        c.drawBitmap(data.image, matrix, paint);
        c.drawColor(overlayColor);
        c.setBitmap(null);

        // Note, we can't use the preview for the small icon, since it is non-square
        float scale = (float) iconSize / Math.min(mImageWidth, mImageHeight);
        Bitmap icon = Bitmap.createBitmap(iconSize, iconSize, data.image.getConfig());
        matrix.setScale(scale, scale);
        matrix.postTranslate((iconSize - (scale * mImageWidth)) / 2,
                (iconSize - (scale * mImageHeight)) / 2);
        c.setBitmap(icon);
        c.drawBitmap(data.image, matrix, paint);
        c.drawColor(overlayColor);
        c.setBitmap(null);

        // Show the intermediate notification
        mTickerAddSpace = !mTickerAddSpace;
        mNotificationId = nId;
        mNotificationManager = nManager;
        final long now = System.currentTimeMillis();

        mNotificationBuilder = new Notification.Builder(context)
            .setTicker(r.getString(R.string.screenshot_saving_ticker)
                    + (mTickerAddSpace ? " " : ""))
            .setContentTitle(r.getString(R.string.screenshot_saving_title))
            .setContentText(r.getString(R.string.screenshot_saving_text))
            .setSmallIcon(R.drawable.stat_notify_image)
            .setWhen(now)
            .setColor(r.getColor(com.android.internal.R.color.system_notification_accent_color));

        mNotificationStyle = new Notification.BigPictureStyle()
            .bigPicture(picture.createAshmemBitmap());
        mNotificationBuilder.setStyle(mNotificationStyle);

        // For "public" situations we want to show all the same info but
        // omit the actual screenshot image.
        mPublicNotificationBuilder = new Notification.Builder(context)
                .setContentTitle(r.getString(R.string.screenshot_saving_title))
                .setContentText(r.getString(R.string.screenshot_saving_text))
                .setSmallIcon(R.drawable.stat_notify_image)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setWhen(now)
                .setColor(r.getColor(
                        com.android.internal.R.color.system_notification_accent_color));

        mNotificationBuilder.setPublicVersion(mPublicNotificationBuilder.build());

        Notification n = mNotificationBuilder.build();
        n.flags |= Notification.FLAG_NO_CLEAR;
        mNotificationManager.notify(nId, n);

        // On the tablet, the large icon makes the notification appear as if it is clickable (and
        // on small devices, the large icon is not shown) so defer showing the large icon until
        // we compose the final post-save notification below.
        mNotificationBuilder.setLargeIcon(icon.createAshmemBitmap());
        // But we still don't set it for the expanded view, allowing the smallIcon to show here.
        mNotificationStyle.bigLargeIcon((Bitmap) null);
    }

    @Override
    protected SaveImageInBackgroundData doInBackground(SaveImageInBackgroundData... params) {
        if (params.length != 1) return null;
        if (isCancelled()) {
            params[0].clearImage();
            params[0].clearContext();
            return null;
        }

        // By default, AsyncTask sets the worker thread to have background thread priority, so bump
        // it back up so that we save a little quicker.
        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND);

        Context context = params[0].context;
        Bitmap image = params[0].image;
        Resources r = context.getResources();

        try {
            // Create screenshot directory if it doesn't exist
            mScreenshotDir.mkdirs();

            // media provider uses seconds for DATE_MODIFIED and DATE_ADDED, but milliseconds
            // for DATE_TAKEN
            long dateSeconds = mImageTime / 1000;

            // Save
            OutputStream out = new FileOutputStream(mImageFilePath);
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();

            // Save the screenshot to the MediaStore
            ContentValues values = new ContentValues();
            ContentResolver resolver = context.getContentResolver();
            values.put(MediaStore.Images.ImageColumns.DATA, mImageFilePath);
            values.put(MediaStore.Images.ImageColumns.TITLE, mImageFileName);
            values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, mImageFileName);
            values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, mImageTime);
            values.put(MediaStore.Images.ImageColumns.DATE_ADDED, dateSeconds);
            values.put(MediaStore.Images.ImageColumns.DATE_MODIFIED, dateSeconds);
            values.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.ImageColumns.WIDTH, mImageWidth);
            values.put(MediaStore.Images.ImageColumns.HEIGHT, mImageHeight);
            values.put(MediaStore.Images.ImageColumns.SIZE, new File(mImageFilePath).length());
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            // Create a share intent
            String subjectDate = DateFormat.getDateTimeInstance().format(new Date(mImageTime));
            String subject = String.format(SCREENSHOT_SHARE_SUBJECT_TEMPLATE, subjectDate);
            Intent sharingIntent = new Intent(Intent.ACTION_SEND);
            sharingIntent.setType("image/png");
            sharingIntent.putExtra(Intent.EXTRA_STREAM, uri);
            sharingIntent.putExtra(Intent.EXTRA_SUBJECT, subject);

            // Create a share action for the notification
            final PendingIntent callback = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, GlobalScreenshot.TargetChosenReceiver.class)
                            .putExtra(GlobalScreenshot.CANCEL_ID, mNotificationId),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            Intent chooserIntent = Intent.createChooser(sharingIntent, null,
                    callback.getIntentSender());
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            mNotificationBuilder.addAction(R.drawable.ic_screenshot_share,
                    r.getString(com.android.internal.R.string.share),
                    PendingIntent.getActivity(context, 0, chooserIntent,
                            PendingIntent.FLAG_CANCEL_CURRENT));

            // Create a delete action for the notification
            final PendingIntent deleteAction = PendingIntent.getBroadcast(context,  0,
                    new Intent(context, GlobalScreenshot.DeleteScreenshotReceiver.class)
                            .putExtra(GlobalScreenshot.CANCEL_ID, mNotificationId)
                            .putExtra(GlobalScreenshot.SCREENSHOT_URI_ID, uri.toString()),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            mNotificationBuilder.addAction(R.drawable.ic_screenshot_delete,
                    r.getString(com.android.internal.R.string.delete), deleteAction);

            params[0].imageUri = uri;
            params[0].image = null;
            params[0].result = 0;
        } catch (Exception e) {
            // IOException/UnsupportedOperationException may be thrown if external storage is not
            // mounted
            params[0].clearImage();
            params[0].result = 1;
        }

        // Recycle the bitmap data
        if (image != null) {
            image.recycle();
        }

        return params[0];
    }

    @Override
    protected void onPostExecute(SaveImageInBackgroundData params) {
        if (isCancelled()) {
            params.finisher.run();
            params.clearImage();
            params.clearContext();
            return;
        }

        if (params.result > 0) {
            // Show a message that we've failed to save the image to disk
            GlobalScreenshot.notifyScreenshotError(params.context, mNotificationManager);
        } else {
            // Show the final notification to indicate screenshot saved
            Resources r = params.context.getResources();

            // Create the intent to show the screenshot in gallery
            Intent launchIntent = new Intent(Intent.ACTION_VIEW);
            launchIntent.setDataAndType(params.imageUri, "image/png");
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            final long now = System.currentTimeMillis();

            mNotificationBuilder
                .setContentTitle(r.getString(R.string.screenshot_saved_title))
                .setContentText(r.getString(R.string.screenshot_saved_text))
                .setContentIntent(PendingIntent.getActivity(params.context, 0, launchIntent, 0))
                .setWhen(now)
                .setAutoCancel(true)
                .setColor(r.getColor(
                        com.android.internal.R.color.system_notification_accent_color));;

            // Update the text in the public version as well
            mPublicNotificationBuilder
                .setContentTitle(r.getString(R.string.screenshot_saved_title))
                .setContentText(r.getString(R.string.screenshot_saved_text))
                .setContentIntent(PendingIntent.getActivity(params.context, 0, launchIntent, 0))
                .setWhen(now)
                .setAutoCancel(true)
                .setColor(r.getColor(
                        com.android.internal.R.color.system_notification_accent_color));

            mNotificationBuilder.setPublicVersion(mPublicNotificationBuilder.build());

            Notification n = mNotificationBuilder.build();
            n.flags &= ~Notification.FLAG_NO_CLEAR;
            mNotificationManager.notify(mNotificationId, n);
        }
        params.finisher.run();
        params.clearContext();
    }
}

/**
 * An AsyncTask that deletes an image from the media store in the background.
 */
class DeleteImageInBackgroundTask extends AsyncTask<Uri, Void, Void> {
    private static final String TAG = "DeleteImageInBackgroundTask";

    private Context mContext;

    DeleteImageInBackgroundTask(Context context) {
        mContext = context;
    }

    @Override
    protected Void doInBackground(Uri... params) {
        if (params.length != 1) return null;

        Uri screenshotUri = params[0];
        ContentResolver resolver = mContext.getContentResolver();
        resolver.delete(screenshotUri, null, null);
        return null;
    }
}

/**
 * TODO:
 *   - Performance when over gl surfaces? Ie. Gallery
 *   - what do we say in the Toast? Which icon do we get if the user uses another
 *     type of gallery?
 */
class GlobalScreenshot {
    private static final String TAG = "GlobalScreenshot";

    static final String CANCEL_ID = "android:cancel_id";
    static final String SCREENSHOT_URI_ID = "android:screenshot_uri_id";

    private static final int SCREENSHOT_FLASH_TO_PEAK_DURATION = 130;
    private static final int SCREENSHOT_DROP_IN_DURATION = 430;
    private static final int SCREENSHOT_DROP_OUT_DELAY = 500;
    private static final int SCREENSHOT_DROP_OUT_DURATION = 430;
    private static final int SCREENSHOT_DROP_OUT_SCALE_DURATION = 370;
    private static final int SCREENSHOT_FAST_DROP_OUT_DURATION = 320;
    private static final float BACKGROUND_ALPHA = 0.5f;
    private static final float SCREENSHOT_SCALE = 1f;
    private static final float SCREENSHOT_DROP_IN_MIN_SCALE = SCREENSHOT_SCALE * 0.725f;
    private static final float SCREENSHOT_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.45f;
    private static final float SCREENSHOT_FAST_DROP_OUT_MIN_SCALE = SCREENSHOT_SCALE * 0.6f;
    private static final float SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET = 0f;
    private final int mPreviewWidth;
    private final int mPreviewHeight;

    private Context mContext;
    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mWindowLayoutParams;
    private NotificationManager mNotificationManager;
    private Display mDisplay;
    private DisplayMetrics mDisplayMetrics;
    private Matrix mDisplayMatrix;

    private Bitmap mScreenBitmap;
    private View mScreenshotLayout;
    private ImageView mBackgroundView;
    private ImageView mScreenshotView;
    private ImageView mScreenshotFlash;

    private AnimatorSet mScreenshotAnimation;

    private int mNotificationIconSize;
    private float mBgPadding;
    private float mBgPaddingScale;

    private AsyncTask<SaveImageInBackgroundData, Void, SaveImageInBackgroundData> mSaveInBgTask;

    private MediaActionSound mCameraSound;


    /**
     * @param context everything needs a context :(
     */
    public GlobalScreenshot(Context context) {
        Resources r = context.getResources();
        mContext = context;
        LayoutInflater layoutInflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Inflate the screenshot layout
        mDisplayMatrix = new Matrix();
        mScreenshotLayout = layoutInflater.inflate(R.layout.global_screenshot, null);
        mBackgroundView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot_background);
        mScreenshotView = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot);
        mScreenshotFlash = (ImageView) mScreenshotLayout.findViewById(R.id.global_screenshot_flash);
        mScreenshotLayout.setFocusable(true);
        mScreenshotLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // Intercept and ignore all touch events
                return true;
            }
        });

        // Setup the window that we are going to use
        mWindowLayoutParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        mWindowLayoutParams.setTitle("ScreenshotAnimation");
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mNotificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mDisplay = mWindowManager.getDefaultDisplay();
        mDisplayMetrics = new DisplayMetrics();
        mDisplay.getRealMetrics(mDisplayMetrics);

        // Get the various target sizes
        mNotificationIconSize =
            r.getDimensionPixelSize(android.R.dimen.notification_large_icon_height);

        // Scale has to account for both sides of the bg
        mBgPadding = (float) r.getDimensionPixelSize(R.dimen.global_screenshot_bg_padding);
        mBgPaddingScale = mBgPadding /  mDisplayMetrics.widthPixels;

        // determine the optimal preview size
        int panelWidth = 0;
        try {
            panelWidth = r.getDimensionPixelSize(R.dimen.notification_panel_width);
        } catch (Resources.NotFoundException e) {
        }
        if (panelWidth <= 0) {
            // includes notification_panel_width==match_parent (-1)
            panelWidth = mDisplayMetrics.widthPixels;
        }
        mPreviewWidth = panelWidth;
        mPreviewHeight = r.getDimensionPixelSize(R.dimen.notification_max_height);

        // Setup the Camera shutter sound
        mCameraSound = new MediaActionSound();
        mCameraSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * Creates a new worker thread and saves the screenshot to the media store.
     */
    private void saveScreenshotInWorkerThread(Runnable finisher) {
        SaveImageInBackgroundData data = new SaveImageInBackgroundData();
        data.context = mContext;
        data.image = mScreenBitmap;
        data.iconSize = mNotificationIconSize;
        data.finisher = finisher;
        data.previewWidth = mPreviewWidth;
        data.previewheight = mPreviewHeight;
        if (mSaveInBgTask != null) {
            mSaveInBgTask.cancel(false);
        }
        mSaveInBgTask = new SaveImageInBackgroundTask(mContext, data, mNotificationManager,
                R.id.notification_screenshot).execute(data);
    }

    /**
     * @return the current display rotation in degrees
     */
    private float getDegreesForRotation(int value) {
        switch (value) {
        case Surface.ROTATION_90:
            return 360f - 90f;
        case Surface.ROTATION_180:
            return 360f - 180f;
        case Surface.ROTATION_270:
            return 360f - 270f;
        }
        return 0f;
    }

    /**
     * Takes a screenshot of the current display and shows an animation.
     */
    void takeScreenshot(Runnable finisher, boolean statusBarVisible, boolean navBarVisible) {
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

        // Take the screenshot
        mScreenBitmap = SurfaceControl.screenshot((int) dims[0], (int) dims[1]);
        if (mScreenBitmap == null) {
            notifyScreenshotError(mContext, mNotificationManager);
            finisher.run();
            return;
        }

        if (requiresRotation) {
            // Rotate the screenshot to the current orientation
            Bitmap ss = Bitmap.createBitmap(mDisplayMetrics.widthPixels,
                    mDisplayMetrics.heightPixels, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(ss);
            c.translate(ss.getWidth() / 2, ss.getHeight() / 2);
            c.rotate(degrees);
            c.translate(-dims[0] / 2, -dims[1] / 2);
            c.drawBitmap(mScreenBitmap, 0, 0, null);
            c.setBitmap(null);
            // Recycle the previous bitmap
            mScreenBitmap.recycle();
            mScreenBitmap = ss;
        }

        // Optimizations
        mScreenBitmap.setHasAlpha(false);
        mScreenBitmap.prepareToDraw();

        // Start the post-screenshot animation
        startAnimation(finisher, mDisplayMetrics.widthPixels, mDisplayMetrics.heightPixels,
                statusBarVisible, navBarVisible);
    }


    /**
     * Starts the animation after taking the screenshot
     */
    private void startAnimation(final Runnable finisher, int w, int h, boolean statusBarVisible,
            boolean navBarVisible) {
        // Add the view for the animation
        mScreenshotView.setImageBitmap(mScreenBitmap);
        mScreenshotLayout.requestFocus();

        // Setup the animation with the screenshot just taken
        if (mScreenshotAnimation != null) {
            mScreenshotAnimation.end();
            mScreenshotAnimation.removeAllListeners();
        }

        mWindowManager.addView(mScreenshotLayout, mWindowLayoutParams);
        ValueAnimator screenshotDropInAnim = createScreenshotDropInAnimation();
        ValueAnimator screenshotFadeOutAnim = createScreenshotDropOutAnimation(w, h,
                statusBarVisible, navBarVisible);
        mScreenshotAnimation = new AnimatorSet();
        mScreenshotAnimation.playSequentially(screenshotDropInAnim, screenshotFadeOutAnim);
        mScreenshotAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Save the screenshot once we have a bit of time now
                saveScreenshotInWorkerThread(finisher);
                mWindowManager.removeView(mScreenshotLayout);

                // Clear any references to the bitmap
                mScreenBitmap = null;
                mScreenshotView.setImageBitmap(null);
            }
        });
        mScreenshotLayout.post(new Runnable() {
            @Override
            public void run() {
                // Play the shutter sound to notify that we've taken a screenshot
                mCameraSound.play(MediaActionSound.SHUTTER_CLICK);

                mScreenshotView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                mScreenshotView.buildLayer();
                mScreenshotAnimation.start();
            }
        });
    }
    private ValueAnimator createScreenshotDropInAnimation() {
        final float flashPeakDurationPct = ((float) (SCREENSHOT_FLASH_TO_PEAK_DURATION)
                / SCREENSHOT_DROP_IN_DURATION);
        final float flashDurationPct = 2f * flashPeakDurationPct;
        final Interpolator flashAlphaInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float x) {
                // Flash the flash view in and out quickly
                if (x <= flashDurationPct) {
                    return (float) Math.sin(Math.PI * (x / flashDurationPct));
                }
                return 0;
            }
        };
        final Interpolator scaleInterpolator = new Interpolator() {
            @Override
            public float getInterpolation(float x) {
                // We start scaling when the flash is at it's peak
                if (x < flashPeakDurationPct) {
                    return 0;
                }
                return (x - flashDurationPct) / (1f - flashDurationPct);
            }
        };
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(SCREENSHOT_DROP_IN_DURATION);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                mBackgroundView.setAlpha(0f);
                mBackgroundView.setVisibility(View.VISIBLE);
                mScreenshotView.setAlpha(0f);
                mScreenshotView.setTranslationX(0f);
                mScreenshotView.setTranslationY(0f);
                mScreenshotView.setScaleX(SCREENSHOT_SCALE + mBgPaddingScale);
                mScreenshotView.setScaleY(SCREENSHOT_SCALE + mBgPaddingScale);
                mScreenshotView.setVisibility(View.VISIBLE);
                mScreenshotFlash.setAlpha(0f);
                mScreenshotFlash.setVisibility(View.VISIBLE);
            }
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                mScreenshotFlash.setVisibility(View.GONE);
            }
        });
        anim.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                float scaleT = (SCREENSHOT_SCALE + mBgPaddingScale)
                    - scaleInterpolator.getInterpolation(t)
                        * (SCREENSHOT_SCALE - SCREENSHOT_DROP_IN_MIN_SCALE);
                mBackgroundView.setAlpha(scaleInterpolator.getInterpolation(t) * BACKGROUND_ALPHA);
                mScreenshotView.setAlpha(t);
                mScreenshotView.setScaleX(scaleT);
                mScreenshotView.setScaleY(scaleT);
                mScreenshotFlash.setAlpha(flashAlphaInterpolator.getInterpolation(t));
            }
        });
        return anim;
    }
    private ValueAnimator createScreenshotDropOutAnimation(int w, int h, boolean statusBarVisible,
            boolean navBarVisible) {
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setStartDelay(SCREENSHOT_DROP_OUT_DELAY);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBackgroundView.setVisibility(View.GONE);
                mScreenshotView.setVisibility(View.GONE);
                mScreenshotView.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        });

        if (!statusBarVisible || !navBarVisible) {
            // There is no status bar/nav bar, so just fade the screenshot away in place
            anim.setDuration(SCREENSHOT_FAST_DROP_OUT_DURATION);
            anim.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
                            - t * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_FAST_DROP_OUT_MIN_SCALE);
                    mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
                    mScreenshotView.setAlpha(1f - t);
                    mScreenshotView.setScaleX(scaleT);
                    mScreenshotView.setScaleY(scaleT);
                }
            });
        } else {
            // In the case where there is a status bar, animate to the origin of the bar (top-left)
            final float scaleDurationPct = (float) SCREENSHOT_DROP_OUT_SCALE_DURATION
                    / SCREENSHOT_DROP_OUT_DURATION;
            final Interpolator scaleInterpolator = new Interpolator() {
                @Override
                public float getInterpolation(float x) {
                    if (x < scaleDurationPct) {
                        // Decelerate, and scale the input accordingly
                        return (float) (1f - Math.pow(1f - (x / scaleDurationPct), 2f));
                    }
                    return 1f;
                }
            };

            // Determine the bounds of how to scale
            float halfScreenWidth = (w - 2f * mBgPadding) / 2f;
            float halfScreenHeight = (h - 2f * mBgPadding) / 2f;
            final float offsetPct = SCREENSHOT_DROP_OUT_MIN_SCALE_OFFSET;
            final PointF finalPos = new PointF(
                -halfScreenWidth + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenWidth,
                -halfScreenHeight + (SCREENSHOT_DROP_OUT_MIN_SCALE + offsetPct) * halfScreenHeight);

            // Animate the screenshot to the status bar
            anim.setDuration(SCREENSHOT_DROP_OUT_DURATION);
            anim.addUpdateListener(new AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float t = (Float) animation.getAnimatedValue();
                    float scaleT = (SCREENSHOT_DROP_IN_MIN_SCALE + mBgPaddingScale)
                        - scaleInterpolator.getInterpolation(t)
                            * (SCREENSHOT_DROP_IN_MIN_SCALE - SCREENSHOT_DROP_OUT_MIN_SCALE);
                    mBackgroundView.setAlpha((1f - t) * BACKGROUND_ALPHA);
                    mScreenshotView.setAlpha(1f - scaleInterpolator.getInterpolation(t));
                    mScreenshotView.setScaleX(scaleT);
                    mScreenshotView.setScaleY(scaleT);
                    mScreenshotView.setTranslationX(t * finalPos.x);
                    mScreenshotView.setTranslationY(t * finalPos.y);
                }
            });
        }
        return anim;
    }

    static void notifyScreenshotError(Context context, NotificationManager nManager) {
        Resources r = context.getResources();

        // Clear all existing notification, compose the new notification and show it
        Notification.Builder b = new Notification.Builder(context)
            .setTicker(r.getString(R.string.screenshot_failed_title))
            .setContentTitle(r.getString(R.string.screenshot_failed_title))
            .setContentText(r.getString(R.string.screenshot_failed_text))
            .setSmallIcon(R.drawable.stat_notify_image_error)
            .setWhen(System.currentTimeMillis())
            .setVisibility(Notification.VISIBILITY_PUBLIC) // ok to show outside lockscreen
            .setCategory(Notification.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));
        Notification n =
            new Notification.BigTextStyle(b)
                .bigText(r.getString(R.string.screenshot_failed_text))
                .build();
        nManager.notify(R.id.notification_screenshot, n);
    }

    /**
     * Removes the notification for a screenshot after a share target is chosen.
     */
    public static class TargetChosenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.hasExtra(CANCEL_ID)) {
                return;
            }

            // Clear the notification
            final NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            final int id = intent.getIntExtra(CANCEL_ID, 0);
            nm.cancel(id);
        }
    }

    /**
     * Removes the last screenshot.
     */
    public static class DeleteScreenshotReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.hasExtra(CANCEL_ID) || !intent.hasExtra(SCREENSHOT_URI_ID)) {
                return;
            }

            // Clear the notification
            final NotificationManager nm =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            final int id = intent.getIntExtra(CANCEL_ID, 0);
            final Uri uri = Uri.parse(intent.getStringExtra(SCREENSHOT_URI_ID));
            nm.cancel(id);

            // And delete the image from the media store
            new DeleteImageInBackgroundTask(context).execute(uri);
        }
    }
}
