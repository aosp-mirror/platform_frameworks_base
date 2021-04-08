/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.IWindowManager;
import android.view.ScrollCaptureResponse;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.screenshot.ScrollCaptureController.LongScreenshot;

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
    private static final String TAG = "LongScreenshotActivity";

    public static final String EXTRA_CAPTURE_RESPONSE = "capture-response";
    private static final String KEY_SAVED_IMAGE_PATH = "saved-image-path";

    private final UiEventLogger mUiEventLogger;
    private final ScrollCaptureController mScrollCaptureController;
    private final Executor mUiExecutor;
    private final Executor mBackgroundExecutor;
    private final ImageExporter mImageExporter;

    // If true, the activity is re-loading an image from storage, which should either succeed and
    // populate the UI or fail and finish the activity.
    private boolean mRestoringInstance;

    private ImageView mPreview;
    private View mSave;
    private View mEdit;
    private View mShare;
    private CropView mCropView;
    private MagnifierView mMagnifierView;
    private ScrollCaptureResponse mScrollCaptureResponse;
    private File mSavedImagePath;

    private ListenableFuture<File> mCacheSaveFuture;
    private ListenableFuture<ImageLoader.Result> mCacheLoadFuture;

    private ListenableFuture<LongScreenshot> mLongScreenshotFuture;
    private LongScreenshot mLongScreenshot;

    private enum PendingAction {
        SHARE,
        EDIT,
        SAVE
    }

    @Inject
    public LongScreenshotActivity(UiEventLogger uiEventLogger, ImageExporter imageExporter,
            @Main Executor mainExecutor, @Background Executor bgExecutor, IWindowManager wms,
            Context context, ScrollCaptureController scrollCaptureController) {
        mUiEventLogger = uiEventLogger;
        mUiExecutor = mainExecutor;
        mBackgroundExecutor = bgExecutor;
        mImageExporter = imageExporter;
        mScrollCaptureController = scrollCaptureController;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate(savedInstanceState = " + savedInstanceState + ")");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.long_screenshot);

        mPreview = requireViewById(R.id.preview);
        mSave = requireViewById(R.id.save);
        mEdit = requireViewById(R.id.edit);
        mShare = requireViewById(R.id.share);
        mCropView = requireViewById(R.id.crop_view);
        mMagnifierView = requireViewById(R.id.magnifier);
        mCropView.setCropInteractionListener(mMagnifierView);

        mSave.setOnClickListener(this::onClicked);
        mEdit.setOnClickListener(this::onClicked);
        mShare.setOnClickListener(this::onClicked);

        mPreview.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateImageDimensions());

        Intent intent = getIntent();
        mScrollCaptureResponse = intent.getParcelableExtra(EXTRA_CAPTURE_RESPONSE);

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
        Log.d(TAG, "onStart");
        super.onStart();

        if (mCacheLoadFuture != null) {
            Log.d(TAG, "mRestoringInstance = true");
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
            return;
        }

        if (mLongScreenshotFuture == null) {
            Log.d(TAG, "mLongScreenshotFuture == null");
            // First run through, ensure we have a connection to use (see #onCreate)
            if (mScrollCaptureResponse == null || !mScrollCaptureResponse.isConnected()) {
                Log.e(TAG, "Did not receive a live scroll capture connection, bailing out!");
                finishAndRemoveTask();
                return;
            }
            mLongScreenshotFuture = mScrollCaptureController.run(mScrollCaptureResponse);
            mLongScreenshotFuture.addListener(() -> {
                LongScreenshot longScreenshot;
                try {
                    longScreenshot = mLongScreenshotFuture.get();
                } catch (CancellationException | InterruptedException | ExecutionException e) {
                    Log.e(TAG, "Error capturing long screenshot!", e);
                    finishAndRemoveTask();
                    return;
                }
                if (longScreenshot.getHeight() == 0) {
                    Log.e(TAG, "Got a zero height result");
                    finishAndRemoveTask();
                    return;
                }
                onCaptureCompleted(longScreenshot);
            }, mUiExecutor);
        } else {
            Log.d(TAG, "mLongScreenshotFuture != null");
        }
    }

    private void onCaptureCompleted(LongScreenshot longScreenshot) {
        Log.d(TAG, "onCaptureCompleted(longScreenshot=" + longScreenshot + ")");
        mLongScreenshot = longScreenshot;
        mPreview.setImageDrawable(mLongScreenshot.getDrawable());
        updateImageDimensions();
        mMagnifierView.setDrawable(mLongScreenshot.getDrawable(),
                mLongScreenshot.getWidth(), mLongScreenshot.getHeight());
        // Original boundaries go from the image tile set's y=0 to y=pageSize, so
        // we animate to that as a starting crop position.
        float topFraction = Math.max(0,
                -mLongScreenshot.getTop() / (float) mLongScreenshot.getHeight());
        float bottomFraction = Math.min(1f,
                1 - (mLongScreenshot.getBottom() - mLongScreenshot.getPageHeight())
                        / (float) mLongScreenshot.getHeight());
        mCropView.animateBoundaryTo(CropView.CropBoundary.TOP, topFraction);
        mCropView.animateBoundaryTo(CropView.CropBoundary.BOTTOM, bottomFraction);
        setButtonsEnabled(true);

        // Immediately export to temp image file for saved state
        mCacheSaveFuture = mImageExporter.exportAsTempFile(mBackgroundExecutor,
                mLongScreenshot.toBitmap());
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
        Log.d(TAG, "onCachedImageLoaded(imageResult=" + imageResult + ")");
        BitmapDrawable drawable = new BitmapDrawable(getResources(), imageResult.bitmap);
        mPreview.setImageDrawable(drawable);
        mMagnifierView.setDrawable(drawable, imageResult.bitmap.getWidth(),
                imageResult.bitmap.getHeight());
        mSavedImagePath = imageResult.fileName;

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
        Log.d(TAG, "onSaveInstanceState");
        super.onSaveInstanceState(outState);
        if (mSavedImagePath != null) {
            outState.putString(KEY_SAVED_IMAGE_PATH, mSavedImagePath.getPath());
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop finishing=" + isFinishing());
        super.onStop();
        if (isFinishing()) {
            if (mScrollCaptureResponse != null) {
                mScrollCaptureResponse.close();
            }
            cleanupCache();

            if (mLongScreenshotFuture != null) {
                mLongScreenshotFuture.cancel(true);
            }
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
            Log.d(TAG, "Deleting " + mSavedImagePath);
            //noinspection ResultOfMethodCallIgnored
            mSavedImagePath.delete();
            mSavedImagePath = null;
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
    }

    private void setButtonsEnabled(boolean enabled) {
        mSave.setEnabled(enabled);
        mEdit.setEnabled(enabled);
        mShare.setEnabled(enabled);
    }

    private void doEdit(Uri uri) {
        String editorPackage = getString(R.string.config_screenshotEditor);
        Intent intent = new Intent(Intent.ACTION_EDIT);
        if (!TextUtils.isEmpty(editorPackage)) {
            intent.setComponent(ComponentName.unflattenFromString(editorPackage));
        }
        intent.setDataAndType(uri, "image/png");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        startActivityAsUser(intent, UserHandle.CURRENT);
        finishAndRemoveTask();
    }

    private void doShare(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent sharingChooserIntent = Intent.createChooser(intent, null)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        startActivityAsUser(sharingChooserIntent, UserHandle.CURRENT);
    }

    private void onClicked(View v) {
        int id = v.getId();
        v.setPressed(true);
        setButtonsEnabled(false);
        if (id == R.id.save) {
            startExport(PendingAction.SAVE);
        } else if (id == R.id.edit) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_EDIT);
            startExport(PendingAction.EDIT);
        } else if (id == R.id.share) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_SHARE);
            startExport(PendingAction.SHARE);
        }
    }

    private void startExport(PendingAction action) {
        Log.d(TAG, "startExport(action = " + action + ")");
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

        Bitmap output = renderBitmap(mPreview.getDrawable(), bounds);
        ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(
                mBackgroundExecutor, UUID.randomUUID(), output, ZonedDateTime.now());
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

        switch (action) {
            case EDIT:
                doEdit(result.uri);
                break;
            case SHARE:
                doShare(result.uri);
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

        if (imageRatio > viewRatio) {
            // Image is full width and height is constrained, compute extra padding to inform
            // CropView
            float imageHeight = previewHeight * viewRatio / imageRatio;
            int extraPadding = (int) (previewHeight - imageHeight) / 2;
            mCropView.setExtraPadding(extraPadding + mPreview.getPaddingTop(),
                    extraPadding + mPreview.getPaddingBottom());
            mCropView.setImageWidth(previewWidth);
        } else {
            // Image is full height
            mCropView.setExtraPadding(mPreview.getPaddingTop(),  mPreview.getPaddingBottom());
            mCropView.setImageWidth((int) (previewHeight * imageRatio));
        }

    }
}
