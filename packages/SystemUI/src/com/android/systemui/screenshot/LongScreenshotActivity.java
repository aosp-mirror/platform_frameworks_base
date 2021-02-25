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
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * LongScreenshotActivity acquires bitmap data for a long screenshot and lets the user trim the top
 * and bottom before saving/sharing/editing.
 */
public class LongScreenshotActivity extends Activity {
    private static final String TAG = "LongScreenshotActivity";

    private final UiEventLogger mUiEventLogger;
    private final ScrollCaptureController mScrollCaptureController;
    private final ScrollCaptureClient.Connection mConnection;

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
    }

    @Override
    public void onStart() {
        super.onStart();
        if (mPreview.getDrawable() == null) {
            if (mConnection == null) {
                Log.e(TAG, "Failed to get scroll capture connection, bailing out");
                finishAndRemoveTask();
                return;
            }
            doCapture();
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
        mScrollCaptureController.startExport(mCropView.getTopBoundary(),
                mCropView.getBottomBoundary(), new ScrollCaptureController.ExportCallback() {
                    @Override
                    public void onError() {
                        Log.e(TAG, "Error exporting image data.");
                        setButtonsEnabled(true);
                    }

                    @Override
                    public void onExportComplete(Uri outputUri) {
                        setButtonsEnabled(true);
                        switch (action) {
                            case EDIT:
                                doEdit(outputUri);
                                break;
                            case SHARE:
                                doShare(outputUri);
                                break;
                            case SAVE:
                                // Nothing more to do
                                finishAndRemoveTask();
                                break;
                        }
                    }
                });
    }

    private void doCapture() {
        mScrollCaptureController.start(mConnection,
                new ScrollCaptureController.ScrollCaptureCallback() {
            @Override
            public void onError() {
                Log.e(TAG, "Error!");
                finishAndRemoveTask();
            }

            @Override
            public void onComplete(ImageTileSet imageTileSet) {
                Log.i(TAG, "Got tiles " + imageTileSet.getWidth() + " x "
                        + imageTileSet.getHeight());
                mPreview.setImageDrawable(imageTileSet.getDrawable());
                mMagnifierView.setImageTileset(imageTileSet);
                mCropView.animateBoundaryTo(CropView.CropBoundary.BOTTOM, 0.5f);
            }
        });
    }
}
