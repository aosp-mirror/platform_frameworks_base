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
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.util.Log;
import android.view.ScrollCaptureResponse;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.app.ChooserActivity;
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
    private final Executor mUiExecutor;
    private final Executor mBackgroundExecutor;
    private final ImageExporter mImageExporter;
    private final LongScreenshotHolder mLongScreenshotHolder;

    private ImageView mPreview;
    private ImageView mTransitionView;
    private ImageView mEnterTransitionView;
    private View mSave;
    private View mEdit;
    private View mShare;
    private CropView mCropView;
    private MagnifierView mMagnifierView;
    private ScrollCaptureResponse mScrollCaptureResponse;
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
            LongScreenshotHolder longScreenshotHolder) {
        mUiEventLogger = uiEventLogger;
        mUiExecutor = mainExecutor;
        mBackgroundExecutor = bgExecutor;
        mImageExporter = imageExporter;
        mLongScreenshotHolder = longScreenshotHolder;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "onCreate(savedInstanceState = " + savedInstanceState + ")");
        super.onCreate(savedInstanceState);
        postponeEnterTransition();
        setContentView(R.layout.long_screenshot);

        mPreview = requireViewById(R.id.preview);
        mSave = requireViewById(R.id.save);
        mEdit = requireViewById(R.id.edit);
        mShare = requireViewById(R.id.share);
        mCropView = requireViewById(R.id.crop_view);
        mMagnifierView = requireViewById(R.id.magnifier);
        mCropView.setCropInteractionListener(mMagnifierView);
        mTransitionView = requireViewById(R.id.transition);
        mEnterTransitionView = requireViewById(R.id.enter_transition);

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
        Log.d(TAG, "onLongScreenshotReceived(longScreenshot=" + longScreenshot + ")");
        mLongScreenshot = longScreenshot;
        Drawable drawable = mLongScreenshot.getDrawable();
        mPreview.setImageDrawable(drawable);
        mCropView.setVisibility(View.VISIBLE);
        mMagnifierView.setDrawable(mLongScreenshot.getDrawable(),
                mLongScreenshot.getWidth(), mLongScreenshot.getHeight());
        // Original boundaries go from the image tile set's y=0 to y=pageSize, so
        // we animate to that as a starting crop position.
        float topFraction = Math.max(0,
                -mLongScreenshot.getTop() / (float) mLongScreenshot.getHeight());
        float bottomFraction = Math.min(1f,
                1 - (mLongScreenshot.getBottom() - mLongScreenshot.getPageHeight())
                        / (float) mLongScreenshot.getHeight());

        mEnterTransitionView.setImageDrawable(drawable);

        mEnterTransitionView.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mEnterTransitionView.getViewTreeObserver().removeOnPreDrawListener(this);
                        updateImageDimensions();
                        startPostponedEnterTransition();
                        if (isActivityTransitionRunning()) {
                            getWindow().getSharedElementEnterTransition().addListener(
                                    new TransitionListenerAdapter() {
                                        @Override
                                        public void onTransitionEnd(Transition transition) {
                                            super.onTransitionEnd(transition);
                                            mPreview.animate().alpha(1f);
                                            mCropView.animateBoundaryTo(
                                                    CropView.CropBoundary.TOP, topFraction);
                                            mCropView.animateBoundaryTo(
                                                    CropView.CropBoundary.BOTTOM, bottomFraction);
                                            setButtonsEnabled(true);
                                            mEnterTransitionView.setVisibility(View.GONE);
                                        }
                                    });
                        }
                        return true;
                    }
                });

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
        mCropView.setVisibility(View.VISIBLE);
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
        if (mTransitionStarted) {
            finish();
        }
        if (isFinishing()) {
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
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        mTransitionView.setImageBitmap(mOutputBitmap);
        mTransitionView.setVisibility(View.VISIBLE);
        mTransitionView.setTransitionName(
                ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME);
        // TODO: listen for transition completing instead of finishing onStop
        mTransitionStarted = true;
        startActivity(intent,
                ActivityOptions.makeSceneTransitionAnimation(this, mTransitionView,
                        ChooserActivity.FIRST_IMAGE_PREVIEW_TRANSITION_NAME).toBundle());
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

        updateImageDimensions();

        mOutputBitmap = renderBitmap(drawable, bounds);
        ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(
                mBackgroundExecutor, UUID.randomUUID(), mOutputBitmap, ZonedDateTime.now());
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
            mCropView.setExtraPadding(extraPadding, extraPadding);
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
