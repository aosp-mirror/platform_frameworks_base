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

package com.android.systemui.screenshot;

import android.annotation.IdRes;
import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.Window;
import android.widget.ImageView;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.screenshot.ScrollCaptureClient.Connection;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback;

import com.google.common.util.concurrent.ListenableFuture;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Interaction controller between the UI and ScrollCaptureClient.
 */
public class ScrollCaptureController implements OnComputeInternalInsetsListener {
    private static final String TAG = "ScrollCaptureController";

    // TODO: Support saving without additional action.
    private enum PendingAction {
        SHARE,
        EDIT
    }

    public static final int MAX_PAGES = 5;
    public static final int MAX_HEIGHT = 12000;

    private final Connection mConnection;
    private final Context mContext;

    private final Executor mUiExecutor;
    private final Executor mBgExecutor;
    private final ImageExporter mImageExporter;
    private final ImageTileSet mImageTileSet;
    private final UiEventLogger mUiEventLogger;

    private ZonedDateTime mCaptureTime;
    private UUID mRequestId;
    private RequestCallback mCallback;
    private Window mWindow;
    private ImageView mPreview;
    private View mClose;
    private View mEdit;
    private View mShare;

    public ScrollCaptureController(Context context, Connection connection, Executor uiExecutor,
            Executor bgExecutor, ImageExporter exporter, UiEventLogger uiEventLogger) {
        mContext = context;
        mConnection = connection;
        mUiExecutor = uiExecutor;
        mBgExecutor = bgExecutor;
        mImageExporter = exporter;
        mUiEventLogger = uiEventLogger;
        mImageTileSet = new ImageTileSet();
    }

    /**
     * @param window the window to display the preview
     */
    public void attach(Window window) {
        mWindow = window;
    }

    /**
     * Run scroll capture!
     *
     * @param callback request callback to report back to the service
     */
    public void start(RequestCallback callback) {
        mCaptureTime = ZonedDateTime.now();
        mRequestId = UUID.randomUUID();
        mCallback = callback;

        setContentView(R.layout.long_screenshot);
        mWindow.getDecorView().getViewTreeObserver()
                .addOnComputeInternalInsetsListener(this);
        mPreview = findViewById(R.id.preview);

        mClose = findViewById(R.id.close);
        mEdit = findViewById(R.id.edit);
        mShare = findViewById(R.id.share);

        mClose.setOnClickListener(this::onClicked);
        mEdit.setOnClickListener(this::onClicked);
        mShare.setOnClickListener(this::onClicked);

        //mPreview.setImageDrawable(mImageTileSet.getDrawable());
        mConnection.start(this::startCapture);
    }


    /** Ensure the entire window is touchable */
    public void onComputeInternalInsets(InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
    }

    void disableButtons() {
        mClose.setEnabled(false);
        mEdit.setEnabled(false);
        mShare.setEnabled(false);
    }

    private void onClicked(View v) {
        Log.d(TAG, "button clicked!");

        int id = v.getId();
        if (id == R.id.close) {
            v.setPressed(true);
            disableButtons();
            doFinish();
        } else if (id == R.id.edit) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_EDIT);
            v.setPressed(true);
            disableButtons();
            startExport(PendingAction.EDIT);
        } else if (id == R.id.share) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_SHARE);
            v.setPressed(true);
            disableButtons();
            startExport(PendingAction.SHARE);
        }
    }

    private void doFinish() {
        mPreview.setImageDrawable(null);
        mImageTileSet.clear();
        mCallback.onFinish();
        mWindow.getDecorView().getViewTreeObserver()
                .removeOnComputeInternalInsetsListener(this);
    }

    private void startExport(PendingAction action) {
        ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(
                mBgExecutor, mRequestId, mImageTileSet.toBitmap(), mCaptureTime);
        exportFuture.addListener(() -> {
            try {
                ImageExporter.Result result = exportFuture.get();
                if (action == PendingAction.EDIT) {
                    doEdit(result.uri);
                } else if (action == PendingAction.SHARE) {
                    doShare(result.uri);
                }
                doFinish();
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, "failed to export", e);
                mCallback.onFinish();
            }
        }, mUiExecutor);
    }

    private void doEdit(Uri uri) {
        String editorPackage = mContext.getString(R.string.config_screenshotEditor);
        Intent intent = new Intent(Intent.ACTION_EDIT);
        if (!TextUtils.isEmpty(editorPackage)) {
            intent.setComponent(ComponentName.unflattenFromString(editorPackage));
        }
        intent.setType("image/png");
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent sharingChooserIntent = Intent.createChooser(intent, null)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        mContext.startActivityAsUser(sharingChooserIntent, UserHandle.CURRENT);
    }

    private void doShare(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent sharingChooserIntent = Intent.createChooser(intent, null)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        mContext.startActivityAsUser(sharingChooserIntent, UserHandle.CURRENT);
    }

    private void setContentView(@IdRes int id) {
        mWindow.setContentView(id);
    }

    <T extends View> T findViewById(@IdRes int res) {
        return mWindow.findViewById(res);
    }

    private void startCapture(Session session) {
        Log.d(TAG, "startCapture");
        Consumer<ScrollCaptureClient.CaptureResult> consumer =
                new Consumer<ScrollCaptureClient.CaptureResult>() {

                    int mFrameCount = 0;
                    int mTop = 0;

                    @Override
                    public void accept(ScrollCaptureClient.CaptureResult result) {
                        mFrameCount++;

                        boolean emptyFrame = result.captured.height() == 0;
                        if (!emptyFrame) {
                            ImageTile tile = new ImageTile(result.image, result.captured);
                            Log.d(TAG, "Adding tile: " + tile);
                            mImageTileSet.addTile(tile);
                            Log.d(TAG, "New dimens: w=" + mImageTileSet.getWidth() + ", "
                                    + "h=" + mImageTileSet.getHeight());
                        }

                        if (emptyFrame || mFrameCount >= MAX_PAGES
                                || mTop + session.getTileHeight() > MAX_HEIGHT) {

                            mUiExecutor.execute(() -> afterCaptureComplete(session));
                            return;
                        }
                        mTop += result.captured.height();
                        session.requestTile(mTop, /* consumer */ this);
                    }
                };

        // fire it up!
        session.requestTile(0, consumer);
    };

    @UiThread
    void afterCaptureComplete(Session session) {
        Log.d(TAG, "afterCaptureComplete");

        if (mImageTileSet.isEmpty()) {
            session.end(mCallback::onFinish);
        } else {
            mPreview.setImageDrawable(mImageTileSet.getDrawable());
        }
    }
}
