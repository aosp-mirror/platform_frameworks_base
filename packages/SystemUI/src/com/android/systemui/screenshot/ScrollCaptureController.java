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

import static com.android.systemui.screenshot.LogConfig.DEBUG_INPUT;

import android.annotation.IdRes;
import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewTreeObserver.InternalInsetsInfo;
import android.view.ViewTreeObserver.OnComputeInternalInsetsListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.screenshot.ScrollCaptureClient.CaptureResult;
import com.android.systemui.screenshot.ScrollCaptureClient.Connection;
import com.android.systemui.screenshot.ScrollCaptureClient.Session;
import com.android.systemui.screenshot.TakeScreenshotService.RequestCallback;

import com.google.common.util.concurrent.ListenableFuture;

import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Interaction controller between the UI and ScrollCaptureClient.
 */
public class ScrollCaptureController implements OnComputeInternalInsetsListener {
    private static final String TAG = "ScrollCaptureController";
    private static final float MAX_PAGES_DEFAULT = 3f;

    private static final String SETTING_KEY_MAX_PAGES = "screenshot.scroll_max_pages";

    private static final int UP = -1;
    private static final int DOWN = 1;

    private int mDirection = DOWN;
    private boolean mAtBottomEdge;
    private boolean mAtTopEdge;
    private Session mSession;

    // TODO: Support saving without additional action.
    private enum PendingAction {
        SHARE,
        EDIT,
        SAVE
    }

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
    private View mSave;
    private View mCancel;
    private View mEdit;
    private View mShare;
    private CropView mCropView;
    private MagnifierView mMagnifierView;

    public ScrollCaptureController(Context context, Connection connection, Executor uiExecutor,
            Executor bgExecutor, ImageExporter exporter, UiEventLogger uiEventLogger) {
        mContext = context;
        mConnection = connection;
        mUiExecutor = uiExecutor;
        mBgExecutor = bgExecutor;
        mImageExporter = exporter;
        mUiEventLogger = uiEventLogger;
        mImageTileSet = new ImageTileSet(context.getMainThreadHandler());
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
        mWindow.setCallback(new WindowCallbacks(this::doFinish));
        mWindow.getDecorView().getViewTreeObserver()
                .addOnComputeInternalInsetsListener(this);

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

        float maxPages = Settings.Secure.getFloat(mContext.getContentResolver(),
                SETTING_KEY_MAX_PAGES, MAX_PAGES_DEFAULT);
        mConnection.start(this::startCapture, maxPages);
    }


    /** Ensure the entire window is touchable */
    public void onComputeInternalInsets(InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
    }

    void disableButtons() {
        mSave.setEnabled(false);
        mCancel.setEnabled(false);
        mEdit.setEnabled(false);
        mShare.setEnabled(false);
    }

    private void onClicked(View v) {
        Log.d(TAG, "button clicked!");

        int id = v.getId();
        v.setPressed(true);
        disableButtons();
        if (id == R.id.save) {
            startExport(PendingAction.SAVE);
        } else if (id == R.id.cancel) {
            doFinish();
        } else if (id == R.id.edit) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_EDIT);
            startExport(PendingAction.EDIT);
        } else if (id == R.id.share) {
            mUiEventLogger.log(ScreenshotEvent.SCREENSHOT_LONG_SCREENSHOT_SHARE);
            startExport(PendingAction.SHARE);
        }
    }

    private void doFinish() {
        mPreview.setImageDrawable(null);
        mMagnifierView.setImageTileset(null);
        mImageTileSet.clear();
        mCallback.onFinish();
        mWindow.getDecorView().getViewTreeObserver()
                .removeOnComputeInternalInsetsListener(this);
    }

    private void startExport(PendingAction action) {
        Rect croppedPortion = new Rect(
                0,
                (int) (mImageTileSet.getHeight() * mCropView.getTopBoundary()),
                mImageTileSet.getWidth(),
                (int) (mImageTileSet.getHeight() * mCropView.getBottomBoundary()));
        ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(
                mBgExecutor, mRequestId, mImageTileSet.toBitmap(croppedPortion), mCaptureTime);
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
    }

    private void doShare(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent sharingChooserIntent = Intent.createChooser(intent, null)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        mContext.startActivityAsUser(sharingChooserIntent, UserHandle.CURRENT);
    }

    private void setContentView(@IdRes int id) {
        mWindow.setContentView(id);
    }

    <T extends View> T findViewById(@IdRes int res) {
        return mWindow.findViewById(res);
    }


    private void onCaptureResult(CaptureResult result) {
        Log.d(TAG, "onCaptureResult: " + result);
        boolean emptyResult = result.captured.height() == 0;
        boolean partialResult = !emptyResult
                && result.captured.height() < result.requested.height();
        boolean finish = false;

        if (partialResult) {
            // Potentially reached a vertical boundary. Extend in the other direction.
            switch (mDirection) {
                case DOWN:
                    Log.d(TAG, "Reached bottom edge.");
                    mAtBottomEdge = true;
                    mDirection = UP;
                    break;
                case UP:
                    Log.d(TAG, "Reached top edge.");
                    mAtTopEdge = true;
                    mDirection = DOWN;
                    break;
            }

            if (mAtTopEdge && mAtBottomEdge) {
                Log.d(TAG, "Reached both top and bottom edge, ending.");
                finish = true;
            } else {
                // only reverse if the edge was relatively close to the starting point
                if (mImageTileSet.getHeight() < mSession.getPageHeight() * 3) {
                    Log.d(TAG, "Restarting in reverse direction.");

                    // Because of temporary limitations, we cannot just jump to the opposite edge
                    // and continue there. Instead, clear the results and start over capturing from
                    // here in the other direction.
                    mImageTileSet.clear();
                } else {
                    Log.d(TAG, "Capture is tall enough, stopping here.");
                    finish = true;
                }
            }
        }

        if (!emptyResult) {
            mImageTileSet.addTile(new ImageTile(result.image, result.captured));
        }

        Log.d(TAG, "bounds: " + mImageTileSet.getLeft() + "," + mImageTileSet.getTop()
                + " - " +  mImageTileSet.getRight() + "," + mImageTileSet.getBottom()
                + " (" + mImageTileSet.getWidth() + "x" + mImageTileSet.getHeight() + ")");


        // Stop when "too tall"
        if (mImageTileSet.size() >= mSession.getMaxTiles()
                || mImageTileSet.getHeight() > MAX_HEIGHT) {
            Log.d(TAG, "Max height and/or tile count reached.");
            finish = true;
        }

        if (finish) {
            Session session = mSession;
            mSession = null;
            Log.d(TAG, "Stop.");
            mUiExecutor.execute(() -> afterCaptureComplete(session));
            return;
        }

        int nextTop = (mDirection == DOWN) ? result.captured.bottom
                : result.captured.top - mSession.getTileHeight();
        Log.d(TAG, "requestTile: " + nextTop);
        mSession.requestTile(nextTop, /* consumer */ this::onCaptureResult);
    }

    private void startCapture(Session session) {
        mSession = session;
        session.requestTile(0, this::onCaptureResult);
    }

    @UiThread
    void afterCaptureComplete(Session session) {
        Log.d(TAG, "afterCaptureComplete");

        if (mImageTileSet.isEmpty()) {
            session.end(mCallback::onFinish);
        } else {
            mPreview.setImageDrawable(mImageTileSet.getDrawable());
            mMagnifierView.setImageTileset(mImageTileSet);
            mCropView.animateBoundaryTo(CropView.CropBoundary.BOTTOM, 0.5f);
        }
    }

    private static class WindowCallbacks implements Window.Callback {

        private final Runnable mFinish;

        WindowCallbacks(Runnable finish) {
            mFinish = finish;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (DEBUG_INPUT) {
                    Log.d(TAG, "onKeyEvent: KeyEvent.KEYCODE_BACK");
                }
                mFinish.run();
                return true;
            }
            return false;
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return false;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return false;
        }

        @Nullable
        @Override
        public View onCreatePanelView(int featureId) {
            return null;
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, @NonNull Menu menu) {
            return false;
        }

        @Override
        public boolean onPreparePanel(int featureId, @Nullable View view, @NonNull Menu menu) {
            return false;
        }

        @Override
        public boolean onMenuOpened(int featureId, @NonNull Menu menu) {
            return false;
        }

        @Override
        public boolean onMenuItemSelected(int featureId, @NonNull MenuItem item) {
            return false;
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        }

        @Override
        public void onContentChanged() {
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
        }

        @Override
        public void onAttachedToWindow() {
        }

        @Override
        public void onDetachedFromWindow() {
        }

        @Override
        public void onPanelClosed(int featureId, @NonNull Menu menu) {
        }

        @Override
        public boolean onSearchRequested() {
            return false;
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return false;
        }

        @Nullable
        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return null;
        }

        @Nullable
        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return null;
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
        }
    }
}
