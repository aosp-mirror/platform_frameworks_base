/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.scroll;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.ScrollCaptureResponse;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.WindowCompat;

import com.android.internal.app.ChooserActivity;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.view.OneShotPreDrawListener;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.ActionIntentCreator;
import com.android.systemui.screenshot.ActionIntentExecutor;
import com.android.systemui.screenshot.ImageExporter;
import com.android.systemui.screenshot.LogConfig;
import com.android.systemui.screenshot.ScreenshotEvent;
import com.android.systemui.screenshot.scroll.CropView.CropBoundary;
import com.android.systemui.screenshot.scroll.ScrollCaptureController.LongScreenshot;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * LongScreenshotActivity acquires bitmap data for a long screenshot and lets the user trim the top
 * and bottom before saving/sharing/editing.
 */
public class LongScreenshotActivity extends Activity {
    private static final String TAG = LogConfig.logTag(LongScreenshotActivity.class);

    public static final String EXTRA_CAPTURE_RESPONSE = "capture-response";
    public static final String EXTRA_SCREENSHOT_USER_HANDLE = "screenshot-userhandle";
    private static final String KEY_SAVED_IMAGE_PATH = "saved-image-path";

    private final UiEventLogger mUiEventLogger;
    private final Executor mUiExecutor;
    private final Executor mBackgroundExecutor;
    private final ImageExporter mImageExporter;
    private final LongScreenshotData mLongScreenshotHolder;
    private final ActionIntentExecutor mActionExecutor;

    private ImageView mPreview;
    private ImageView mTransitionView;
    private ImageView mEnterTransitionView;
    private View mSave;
    private View mCancel;
    private View mEdit;
    private View mShare;
    private CropView mCropView;
    private MagnifierView mMagnifierView;
    private ScrollCaptureResponse mScrollCaptureResponse;
    private UserHandle mScreenshotUserHandle;
    private File mSavedImagePath;

    private ListenableFuture<File> mCacheSaveFuture;
    private ListenableFuture<ImageLoader.Result> mCacheLoadFuture;

    private Bitmap mOutputBitmap;
    private LongScreenshot mLongScreenshot;
    private boolean mTransitionStarted;

    private enum PendingAction {
        SHARE,
        EDIT,
        SAVE
    }

    @Inject
    public LongScreenshotActivity(UiEventLogger uiEventLogger, ImageExporter imageExporter,
            @Main Executor mainExecutor, @Background Executor bgExecutor,
            LongScreenshotData longScreenshotHolder, ActionIntentExecutor actionExecutor) {
        mUiEventLogger = uiEventLogger;
        mUiExecutor = mainExecutor;
        mBackgroundExecutor = bgExecutor;
        mImageExporter = imageExporter;
        mLongScreenshotHolder = longScreenshotHolder;
        mActionExecutor = actionExecutor;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge explicitly.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.long_screenshot);

        mPreview = requireViewById(R.id.preview);
        mSave = requireViewById(R.id.save);
        mEdit = requireViewById(R.id.edit);
        mShare = requireViewById(R.id.share);
        mCancel = requireViewById(R.id.cancel);
        mCropView = requireViewById(R.id.crop_view);
        mMagnifierView = requireViewById(R.id.magnifier);
        mCropView.setCropInteractionListener(mMagnifierView);
        mTransitionView = requireViewById(R.id.transition);
        mEnterTransitionView = requireViewById(R.id.enter_transition);

        mSave.setOnClickListener(this::onClicked);
        mCancel.setOnClickListener(this::onClicked);
        mEdit.setOnClickListener(this::onClicked);
        mShare.setOnClickListener(this::onClicked);

        mPreview.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateImageDimensions());

        requireViewById(R.id.root).setOnApplyWindowInsetsListener(
                (view, windowInsets) -> {
                    Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
                    view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                    return WindowInsets.CONSUMED;
                });

        Intent intent = getIntent();
        mScrollCaptureResponse = intent.getParcelableExtra(EXTRA_CAPTURE_RESPONSE);
        mScreenshotUserHandle = intent.getParcelableExtra(EXTRA_SCREENSHOT_USER_HANDLE,
                UserHandle.class);
        if (mScreenshotUserHandle == null) {
            mScreenshotUserHandle = Process.myUserHandle();
        }

        if (savedInstanceState != null) {
            String savedImagePath = savedInstanceState.getString(KEY_SAVED_IMAGE_PATH);
            if (savedImagePath == null) {
                Log.e(TAG, "Missing saved state entry with key '" + KEY_SAVED_IMAGE_PATH + "'!");
                finishAndRemoveTask();
                return;
            }
            mSavedImagePath = new File(savedImagePath);
            ImageLoader imageLoader = new ImageLoader(getContentResolver());
            mCacheLoadFuture = imageLoader.load(mSavedImagePath);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_ACTIVITY_STARTED);

        if (mPreview.getDrawable() != null) {
            // We already have an image, so no need to try to load again.
            return;
        }

        if (mCacheLoadFuture != null) {
            Log.d(TAG, "mCacheLoadFuture != null");
            final ListenableFuture<ImageLoader.Result> future = mCacheLoadFuture;
            mCacheLoadFuture.addListener(() -> {
                Log.d(TAG, "cached bitmap load complete");
                try {
                    onCachedImageLoaded(future.get());
                } catch (CancellationException | ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Failed to load cached image", e);
                    if (mSavedImagePath != null) {
                        //noinspection ResultOfMethodCallIgnored
                        mSavedImagePath.delete();
                        mSavedImagePath = null;
                    }
                    finishAndRemoveTask();
                }
            }, mUiExecutor);
            mCacheLoadFuture = null;
        } else {
            LongScreenshot longScreenshot = mLongScreenshotHolder.takeLongScreenshot();
            if (longScreenshot != null) {
                onLongScreenshotReceived(longScreenshot);
            } else {
                Log.e(TAG, "No long screenshot available!");
                finishAndRemoveTask();
            }
        }
    }

    private void onLongScreenshotReceived(LongScreenshot longScreenshot) {
        Log.i(TAG, "Completed: " + longScreenshot);
        mLongScreenshot = longScreenshot;
        Drawable drawable = mLongScreenshot.getDrawable();
        mPreview.setImageDrawable(drawable);
        mMagnifierView.setDrawable(mLongScreenshot.getDrawable(),
                mLongScreenshot.getWidth(), mLongScreenshot.getHeight());
        Log.i(TAG, "Completed: " + longScreenshot);
        // Original boundaries go from the image tile set's y=0 to y=pageSize, so
        // we animate to that as a starting crop position.
        float topFraction = Math.max(0,
                -mLongScreenshot.getTop() / (float) mLongScreenshot.getHeight());
        float bottomFraction = Math.min(1f,
                1 - (mLongScreenshot.getBottom() - mLongScreenshot.getPageHeight())
                        / (float) mLongScreenshot.getHeight());

        Log.i(TAG, "topFraction: " + topFraction);
        Log.i(TAG, "bottomFraction: " + bottomFraction);

        mEnterTransitionView.setImageDrawable(drawable);
        OneShotPreDrawListener.add(mEnterTransitionView, () -> {
            updateImageDimensions();
            mEnterTransitionView.post(() -> {
                Rect dest = new Rect();
                mEnterTransitionView.getBoundsOnScreen(dest);
                mLongScreenshotHolder.takeTransitionDestinationCallback()
                        .setTransitionDestination(dest, () -> {
                            mPreview.animate().alpha(1f);
                            mCropView.setBoundaryPosition(CropBoundary.TOP, topFraction);
                            mCropView.setBoundaryPosition(CropBoundary.BOTTOM, bottomFraction);
                            mCropView.animateEntrance();
                            mCropView.setVisibility(View.VISIBLE);
                            setButtonsEnabled(true);
                        });
            });
        });

        // Immediately export to temp image file for saved state
        mCacheSaveFuture = mImageExporter.exportToRawFile(mBackgroundExecutor,
                mLongScreenshot.toBitmap(), new File(getCacheDir(), "long_screenshot_cache.png"));
        mCacheSaveFuture.addListener(() -> {
            try {
                // Get the temp file path to persist, used in onSavedInstanceState
                mSavedImagePath = mCacheSaveFuture.get();
            } catch (CancellationException | InterruptedException | ExecutionException e) {
                Log.e(TAG, "Error saving temp image file", e);
                finishAndRemoveTask();
            }
        }, mUiExecutor);
    }

    private void onCachedImageLoaded(ImageLoader.Result imageResult) {
        mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_ACTIVITY_CACHED_IMAGE_LOADED);

        BitmapDrawable drawable = new BitmapDrawable(getResources(), imageResult.mBitmap);
        mPreview.setImageDrawable(drawable);
        mPreview.setAlpha(1f);
        mMagnifierView.setDrawable(drawable, imageResult.mBitmap.getWidth(),
                imageResult.mBitmap.getHeight());
        mCropView.setVisibility(View.VISIBLE);
        mSavedImagePath = imageResult.mFilename;

        setButtonsEnabled(true);
    }

    private static Bitmap renderBitmap(Drawable drawable, Rect bounds) {
        final RenderNode output = new RenderNode("Bitmap Export");
        output.setPosition(0, 0, bounds.width(), bounds.height());
        RecordingCanvas canvas = output.beginRecording();
        canvas.translate(-bounds.left, -bounds.top);
        canvas.clipRect(bounds);
        drawable.draw(canvas);
        output.endRecording();
        return HardwareRenderer.createHardwareBitmap(output, bounds.width(), bounds.height());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mSavedImagePath != null) {
            outState.putString(KEY_SAVED_IMAGE_PATH, mSavedImagePath.getPath());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mTransitionStarted) {
            finish();
        }
        if (isFinishing()) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_ACTIVITY_FINISHED);

            if (mScrollCaptureResponse != null) {
                mScrollCaptureResponse.close();
            }
            cleanupCache();

            if (mLongScreenshot != null) {
                mLongScreenshot.release();
            }
        }
    }

    void cleanupCache() {
        if (mCacheSaveFuture != null) {
            mCacheSaveFuture.cancel(true);
        }
        if (mSavedImagePath != null) {
            //noinspection ResultOfMethodCallIgnored
            mSavedImagePath.delete();
            mSavedImagePath = null;
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        mSave.setEnabled(enabled);
        mEdit.setEnabled(enabled);
        mShare.setEnabled(enabled);
    }

    private void doEdit(Uri uri) {
        if (mScreenshotUserHandle != Process.myUserHandle()) {
            // TODO: Fix transition for work profile. Omitting it in the meantime.
            mActionExecutor.launchIntentAsync(
                    ActionIntentCreator.INSTANCE.createEdit(uri, this),
                    mScreenshotUserHandle, false,
                    /* activityOptions */ null, /* transitionCoordinator */ null);
        } else {
            String editorPackage = getString(R.string.config_screenshotEditor);
            Intent intent = new Intent(Intent.ACTION_EDIT);
            intent.setDataAndType(uri, "image/png");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Bundle options = null;

            // Skip shared element transition for implicit edit intents
            if (!TextUtils.isEmpty(editorPackage)) {
                intent.setComponent(ComponentName.unflattenFromString(editorPackage));
                mTransitionView.setImageBitmap(mOutputBitmap);
                mTransitionView.setVisibility(View.VISIBLE);
                mTransitionView.setTransitionName(
                        ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME);
                options = ActivityOptions.makeSceneTransitionAnimation(this, mTransitionView,
                        ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME).toBundle();
                // TODO: listen for transition completing instead of finishing onStop
                mTransitionStarted = true;
            }
            startActivity(intent, options);
        }
    }

    private void doShare(Uri uri) {
        Intent shareIntent = ActionIntentCreator.INSTANCE.createShare(uri);
        mActionExecutor.launchIntentAsync(shareIntent, mScreenshotUserHandle, false,
                /* activityOptions */ null, /* transitionCoordinator */ null);
    }

    private void onClicked(View v) {
        int id = v.getId();
        v.setPressed(true);
        setButtonsEnabled(false);
        if (id == R.id.save) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_SAVED);
            startExport(PendingAction.SAVE);
        } else if (id == R.id.edit) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_EDIT);
            startExport(PendingAction.EDIT);
        } else if (id == R.id.share) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_SHARE);
            startExport(PendingAction.SHARE);
        } else if (id == R.id.cancel) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_EXIT);
            finishAndRemoveTask();
        }
    }

    private void startExport(PendingAction action) {
        Drawable drawable = mPreview.getDrawable();
        if (drawable == null) {
            Log.e(TAG, "No drawable, skipping export!");
            return;
        }

        Rect bounds = mCropView.getCropBoundaries(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight());

        if (bounds.isEmpty()) {
            Log.w(TAG, "Crop bounds empty, skipping export.");
            return;
        }

        updateImageDimensions();

        mOutputBitmap = renderBitmap(drawable, bounds);
        // TODO(b/298931528): Add support for long screenshot on external displays.
        ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(
                mBackgroundExecutor, UUID.randomUUID(), mOutputBitmap, ZonedDateTime.now(),
                mScreenshotUserHandle, Display.DEFAULT_DISPLAY);
        exportFuture.addListener(() -> onExportCompleted(action, exportFuture), mUiExecutor);
    }

    private void onExportCompleted(PendingAction action,
            ListenableFuture<ImageExporter.Result> exportFuture) {
        setButtonsEnabled(true);
        ImageExporter.Result result;
        try {
            result = exportFuture.get();
        } catch (CancellationException | InterruptedException | ExecutionException e) {
            Log.e(TAG, "failed to export", e);
            return;
        }
        Uri exported = ContentProvider.getUriWithoutUserId(result.uri);
        Log.e(TAG, action + " uri=" + exported);

        switch (action) {
            case EDIT:
                doEdit(exported);
                break;
            case SHARE:
                doShare(exported);
                break;
            case SAVE:
                // Nothing more to do
                finishAndRemoveTask();
                break;
        }
    }

    private void updateImageDimensions() {
        Drawable drawable = mPreview.getDrawable();
        if (drawable == null) {
            return;
        }
        Rect bounds = drawable.getBounds();
        float imageRatio = bounds.width() / (float) bounds.height();
        int previewWidth = mPreview.getWidth() - mPreview.getPaddingLeft()
                - mPreview.getPaddingRight();
        int previewHeight = mPreview.getHeight() - mPreview.getPaddingTop()
                - mPreview.getPaddingBottom();
        float viewRatio = previewWidth / (float) previewHeight;

        // Top and left offsets of the image relative to mPreview.
        int imageLeft = mPreview.getPaddingLeft();
        int imageTop = mPreview.getPaddingTop();

        // The image width and height on screen
        int imageHeight = previewHeight;
        int imageWidth = previewWidth;
        float scale;
        int extraPadding = 0;
        if (imageRatio > viewRatio) {
            // Image is full width and height is constrained, compute extra padding to inform
            // CropView
            imageHeight = (int) (previewHeight * viewRatio / imageRatio);
            extraPadding = (previewHeight - imageHeight) / 2;
            mCropView.setExtraPadding(extraPadding + mPreview.getPaddingTop(),
                    extraPadding + mPreview.getPaddingBottom());
            imageTop += (previewHeight - imageHeight) / 2;
            mCropView.setImageWidth(previewWidth);
            scale = previewWidth / (float) mPreview.getDrawable().getIntrinsicWidth();
        } else {
            imageWidth = (int) (previewWidth * imageRatio / viewRatio);
            imageLeft += (previewWidth - imageWidth) / 2;
            // Image is full height
            mCropView.setExtraPadding(mPreview.getPaddingTop(), mPreview.getPaddingBottom());
            mCropView.setImageWidth((int) (previewHeight * imageRatio));
            scale = previewHeight / (float) mPreview.getDrawable().getIntrinsicHeight();
        }

        // Update transition view's position and scale.
        Rect boundaries = mCropView.getCropBoundaries(imageWidth, imageHeight);
        mTransitionView.setTranslationX(imageLeft + boundaries.left);
        mTransitionView.setTranslationY(imageTop + boundaries.top);
        ConstraintLayout.LayoutParams params =
                (ConstraintLayout.LayoutParams) mTransitionView.getLayoutParams();
        params.width = boundaries.width();
        params.height = boundaries.height();
        mTransitionView.setLayoutParams(params);

        if (mLongScreenshot != null) {
            ConstraintLayout.LayoutParams enterTransitionParams =
                    (ConstraintLayout.LayoutParams) mEnterTransitionView.getLayoutParams();
            float topFraction = Math.max(0,
                    -mLongScreenshot.getTop() / (float) mLongScreenshot.getHeight());
            enterTransitionParams.width = (int) (scale * drawable.getIntrinsicWidth());
            enterTransitionParams.height = (int) (scale * mLongScreenshot.getPageHeight());
            mEnterTransitionView.setLayoutParams(enterTransitionParams);

            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(0, -scale * drawable.getIntrinsicHeight() * topFraction);
            mEnterTransitionView.setImageMatrix(matrix);
            mEnterTransitionView.setTranslationY(
                    topFraction * previewHeight + mPreview.getPaddingTop() + extraPadding);
        }
    }
}
