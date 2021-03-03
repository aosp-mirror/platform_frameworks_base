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
import android.graphics.BitmapFactory;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * LongScreenshotActivity acquires bitmap data for a long screenshot and lets the user trim the top
 * and bottom before saving/sharing/editing.
 */
public class LongScreenshotActivity extends Activity {
    private static final String TAG = "LongScreenshotActivity";

    private static final String IMAGE_PATH_KEY = "saved-image";
    private static final String TOP_BOUNDARY_KEY = "top-boundary";
    private static final String BOTTOM_BOUNDARY_KEY = "bottom-boundary";

    private final UiEventLogger mUiEventLogger;
    private final ScrollCaptureController mScrollCaptureController;
    private final ScrollCaptureClient.Connection mConnection;
    private final Executor mUiExecutor;
    private final Executor mBackgroundExecutor;
    private final ImageExporter mImageExporter;

    private String mSavedImagePath;
    // If true, the activity is re-loading an image from storage, which should either succeed and
    // populate the UI or fail and finish the activity.
    private boolean mRestoringInstance;

    private ImageView mPreview;
    private View mSave;
    private View mCancel;
    private View mEdit;
    private View mShare;
    private CropView mCropView;
    private MagnifierView mMagnifierView;

    private enum PendingAction {
        SHARE,
        EDIT,
        SAVE
    }

    @Inject
    public LongScreenshotActivity(UiEventLogger uiEventLogger,
            ImageExporter imageExporter,
            @Main Executor mainExecutor,
            @Background Executor bgExecutor,
            Context context) {
        mUiEventLogger = uiEventLogger;
        mUiExecutor = mainExecutor;
        mBackgroundExecutor = bgExecutor;
        mImageExporter = imageExporter;

        mScrollCaptureController = new ScrollCaptureController(context, mainExecutor, bgExecutor,
                imageExporter);

        mConnection = ScreenshotController.takeScrollCaptureConnection();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.long_screenshot);

        mPreview = findViewById(R.id.preview);
        mSave = findViewById(R.id.save);
        mCancel = findViewById(R.id.cancel);
        mEdit = findViewById(R.id.edit);
        mShare = findViewById(R.id.share);
        mCropView = findViewById(R.id.crop_view);
        mMagnifierView = findViewById(R.id.magnifier);
        mCropView.setCropInteractionListener(mMagnifierView);

        mSave.setOnClickListener(this::onClicked);
        mCancel.setOnClickListener(this::onClicked);
        mEdit.setOnClickListener(this::onClicked);
        mShare.setOnClickListener(this::onClicked);

        if (savedInstanceState != null) {
            String imagePath = savedInstanceState.getString(IMAGE_PATH_KEY);
            if (!TextUtils.isEmpty(imagePath)) {
                mRestoringInstance = true;
                mBackgroundExecutor.execute(() -> {
                    Bitmap bitmap = BitmapFactory.decodeFile(imagePath);
                    if (bitmap == null) {
                        Log.e(TAG, "Failed to read bitmap from " + imagePath);
                        finishAndRemoveTask();
                    } else {
                        runOnUiThread(() -> {
                            BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
                            mPreview.setImageDrawable(drawable);
                            mMagnifierView.setDrawable(drawable, bitmap.getWidth(),
                                    bitmap.getHeight());

                            mCropView.setBoundaryTo(CropView.CropBoundary.TOP,
                                    savedInstanceState.getFloat(TOP_BOUNDARY_KEY, 0f));
                            mCropView.setBoundaryTo(CropView.CropBoundary.BOTTOM,
                                    savedInstanceState.getFloat(BOTTOM_BOUNDARY_KEY, 1f));
                            mRestoringInstance = false;
                            // Reuse the same path for subsequent restoration.
                            mSavedImagePath = imagePath;
                            Log.d(TAG, "Loaded bitmap from " + imagePath);
                        });
                    }
                });
            }
        }
        mPreview.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateCropLocation());
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mPreview.getDrawable() == null && !mRestoringInstance) {
            if (mConnection == null) {
                Log.e(TAG, "Failed to get scroll capture connection, bailing out");
                finishAndRemoveTask();
                return;
            }
            doCapture();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(IMAGE_PATH_KEY, mSavedImagePath);
        outState.putFloat(TOP_BOUNDARY_KEY, mCropView.getTopBoundary());
        outState.putFloat(BOTTOM_BOUNDARY_KEY, mCropView.getBottomBoundary());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && !TextUtils.isEmpty(mSavedImagePath)) {
            Log.d(TAG, "Deleting " + mSavedImagePath);
            File file = new File(mSavedImagePath);
            file.delete();
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        mSave.setEnabled(enabled);
        mCancel.setEnabled(enabled);
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
        } else if (id == R.id.cancel) {
            finishAndRemoveTask();
        } else if (id == R.id.edit) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_EDIT);
            startExport(PendingAction.EDIT);
        } else if (id == R.id.share) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_SHARE);
            startExport(PendingAction.SHARE);
        }
    }

    private void startExport(PendingAction action) {
        Drawable drawable = mPreview.getDrawable();

        Rect croppedPortion = new Rect(
                0,
                (int) (drawable.getIntrinsicHeight() * mCropView.getTopBoundary()),
                drawable.getIntrinsicWidth(),
                (int) (drawable.getIntrinsicHeight() * mCropView.getBottomBoundary()));
        ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(
                mBackgroundExecutor, UUID.randomUUID(), getBitmap(croppedPortion, drawable),
                ZonedDateTime.now());
        exportFuture.addListener(() -> {
            try {
                ImageExporter.Result result = exportFuture.get();
                setButtonsEnabled(true);
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
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "failed to export", e);
                setButtonsEnabled(true);
            }
        }, mUiExecutor);
    }

    private Bitmap getBitmap(Rect bounds, Drawable drawable) {
        final RenderNode output = new RenderNode("Bitmap Export");
        output.setPosition(0, 0, bounds.width(), bounds.height());
        RecordingCanvas canvas = output.beginRecording();
        // Translating the canvas instead of setting drawable bounds since the drawable is still
        // used in the preview.
        canvas.translate(0, -bounds.top);
        drawable.draw(canvas);
        output.endRecording();
        return HardwareRenderer.createHardwareBitmap(output, bounds.width(), bounds.height());
    }

    private void saveCacheBitmap(ImageTileSet tileSet) {
        long startTime = SystemClock.uptimeMillis();
        Bitmap bitmap = tileSet.toBitmap();
        // TODO(b/181562529) Remove this
        mPreview.setImageDrawable(tileSet.getDrawable());
        try {
            File file = File.createTempFile("long_screenshot", ".png", null);
            FileOutputStream stream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.flush();
            stream.close();
            mSavedImagePath = file.getAbsolutePath();
            Log.d(TAG, "Saved to " + file.getAbsolutePath() + " in "
                    + (SystemClock.uptimeMillis() - startTime) + "ms");
        } catch (IOException e) {
            Log.e(TAG, "Failed to save bitmap", e);
        }
    }

    private void updateCropLocation() {
        Drawable drawable = mPreview.getDrawable();
        if (drawable == null) {
            return;
        }

        float imageRatio = drawable.getBounds().width() / (float) drawable.getBounds().height();
        float viewRatio = mPreview.getWidth() / (float) mPreview.getHeight();

        if (imageRatio > viewRatio) {
            // Image is full width and height is constrained, compute extra padding to inform
            // CropView
            float imageHeight = mPreview.getHeight() * viewRatio / imageRatio;
            int extraPadding = (int) (mPreview.getHeight() - imageHeight) / 2;
            mCropView.setExtraPadding(extraPadding, extraPadding);
        } else {
            // Image is full height
            mCropView.setExtraPadding(0, 0);
        }
    }

    private void doCapture() {
        mScrollCaptureController.start(mConnection,
                new ScrollCaptureController.ScrollCaptureCallback() {
                    @Override
                    public void onError() {
                        Log.e(TAG, "Error capturing long screenshot!");
                        finishAndRemoveTask();
                    }

                    @Override
                    public void onComplete(ImageTileSet imageTileSet) {
                        Log.i(TAG, "Got tiles " + imageTileSet.getWidth() + " x "
                                + imageTileSet.getHeight());
                        mPreview.setImageDrawable(imageTileSet.getDrawable());
                        updateCropLocation();
                        mMagnifierView.setDrawable(imageTileSet.getDrawable(),
                                imageTileSet.getWidth(), imageTileSet.getHeight());
                        mCropView.animateBoundaryTo(CropView.CropBoundary.BOTTOM, 0.5f);
                        mBackgroundExecutor.execute(() -> saveCacheBitmap(imageTileSet));
                    }
                });
    }
}
