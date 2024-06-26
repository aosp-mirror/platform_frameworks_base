/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.screenshot.appclips;

import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_ACCEPTED;
import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_CANCELLED;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.ACTION_FINISH_FROM_TRAMPOLINE;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_CALLING_PACKAGE_NAME;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_RESULT_RECEIVER;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.PERMISSION_SELF;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLogger.UiEventEnum;
import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.scroll.CropView;
import com.android.systemui.settings.UserTracker;

import javax.inject.Inject;

/**
 * An {@link Activity} to take a screenshot for the App Clips flow and presenting a screenshot
 * editing tool.
 *
 * <p>An App Clips flow includes:
 * <ul>
 *     <li>Checking if calling activity meets the prerequisites. This is done by
 *     {@link AppClipsTrampolineActivity}.
 *     <li>Performing the screenshot.
 *     <li>Showing a screenshot editing tool.
 *     <li>Returning the screenshot to the {@link AppClipsTrampolineActivity} so that it can return
 *     the screenshot to the calling activity after explicit user consent.
 * </ul>
 *
 * <p>This {@link Activity} runs in its own separate process to isolate memory intensive image
 * editing from SysUI process.
 *
 * TODO(b/267309532): Polish UI and animations.
 */
public class AppClipsActivity extends ComponentActivity {

    private static final String TAG = AppClipsActivity.class.getSimpleName();
    private static final ApplicationInfoFlags APPLICATION_INFO_FLAGS = ApplicationInfoFlags.of(0);

    private final AppClipsViewModel.Factory mViewModelFactory;
    private final PackageManager mPackageManager;
    private final UserTracker mUserTracker;
    private final UiEventLogger mUiEventLogger;
    private final BroadcastReceiver mBroadcastReceiver;
    private final IntentFilter mIntentFilter;

    private View mLayout;
    private View mRoot;
    private ImageView mPreview;
    private CropView mCropView;
    private Button mSave;
    private Button mCancel;
    private AppClipsViewModel mViewModel;

    private ResultReceiver mResultReceiver;
    @Nullable
    private String mCallingPackageName;
    private int mCallingPackageUid;

    @Inject
    public AppClipsActivity(AppClipsViewModel.Factory viewModelFactory,
            PackageManager packageManager, UserTracker userTracker, UiEventLogger uiEventLogger) {
        mViewModelFactory = viewModelFactory;
        mPackageManager = packageManager;
        mUserTracker = userTracker;
        mUiEventLogger = uiEventLogger;

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Trampoline activity was dismissed so finish this activity.
                if (ACTION_FINISH_FROM_TRAMPOLINE.equals(intent.getAction())) {
                    if (!isFinishing()) {
                        // Nullify the ResultReceiver so that result cannot be sent as trampoline
                        // activity is already finishing.
                        mResultReceiver = null;
                        finish();
                    }
                }
            }
        };

        mIntentFilter = new IntentFilter(ACTION_FINISH_FROM_TRAMPOLINE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        overridePendingTransition(0, 0);
        super.onCreate(savedInstanceState);

        // Register the broadcast receiver that informs when the trampoline activity is dismissed.
        registerReceiver(mBroadcastReceiver, mIntentFilter, PERMISSION_SELF, null,
                RECEIVER_NOT_EXPORTED);

        Intent intent = getIntent();
        setUpUiLogging(intent);
        mResultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        if (mResultReceiver == null) {
            setErrorThenFinish(Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED);
            return;
        }

        // Inflate layout but don't add it yet as it should be added after the screenshot is ready
        // for preview.
        mLayout = getLayoutInflater().inflate(R.layout.app_clips_screenshot, null);
        mRoot = mLayout.findViewById(R.id.root);

        // Manually handle window insets post Android V to support edge-to-edge display.
        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mSave = mLayout.findViewById(R.id.save);
        mCancel = mLayout.findViewById(R.id.cancel);
        mSave.setOnClickListener(this::onClick);
        mCancel.setOnClickListener(this::onClick);


        mCropView = mLayout.findViewById(R.id.crop_view);

        mPreview = mLayout.findViewById(R.id.preview);
        mPreview.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        updateImageDimensions());

        mViewModel = new ViewModelProvider(this, mViewModelFactory).get(AppClipsViewModel.class);
        mViewModel.getScreenshot().observe(this, this::setScreenshot);
        mViewModel.getResultLiveData().observe(this, this::setResultThenFinish);
        mViewModel.getErrorLiveData().observe(this, this::setErrorThenFinish);

        if (savedInstanceState == null) {
            mViewModel.performScreenshot();
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(mBroadcastReceiver);

        // If neither error nor result was set, it implies that the activity is finishing due to
        // some other reason such as user dismissing this activity using back gesture. Inform error.
        if (isFinishing() && mViewModel.getErrorLiveData().getValue() == null
                && mViewModel.getResultLiveData().getValue() == null) {
            // Set error but don't finish as the activity is already finishing.
            setError(Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED);
        }
    }

    private void setUpUiLogging(Intent intent) {
        mCallingPackageName = intent.getStringExtra(EXTRA_CALLING_PACKAGE_NAME);
        mCallingPackageUid = 0;
        try {
            mCallingPackageUid = mPackageManager.getApplicationInfoAsUser(mCallingPackageName,
                    APPLICATION_INFO_FLAGS, mUserTracker.getUserId()).uid;
        } catch (NameNotFoundException e) {
            Log.d(TAG, "Couldn't find notes app UID " + e);
        }
    }

    private void setScreenshot(Bitmap screenshot) {
        // Set background, status and navigation bar colors as the activity is no longer
        // translucent.
        int colorBackgroundFloating = Utils.getColorAttr(this,
                android.R.attr.colorBackgroundFloating).getDefaultColor();
        mRoot.setBackgroundColor(colorBackgroundFloating);

        BitmapDrawable drawable = new BitmapDrawable(getResources(), screenshot);
        mPreview.setImageDrawable(drawable);
        mPreview.setAlpha(1f);

        // Screenshot is now available so set content view.
        setContentView(mLayout);

        // Request view to apply insets as it is added late and not when activity was first created.
        mRoot.requestApplyInsets();
    }

    private void onClick(View view) {
        mSave.setEnabled(false);
        mCancel.setEnabled(false);

        int id = view.getId();
        if (id == R.id.save) {
            saveScreenshotThenFinish();
        } else {
            setErrorThenFinish(Intent.CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED);
        }
    }

    private void saveScreenshotThenFinish() {
        Drawable drawable = mPreview.getDrawable();
        if (drawable == null) {
            setErrorThenFinish(Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED);
            return;
        }

        Rect bounds = mCropView.getCropBoundaries(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight());

        if (bounds.isEmpty()) {
            setErrorThenFinish(Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED);
            return;
        }

        updateImageDimensions();
        mViewModel.saveScreenshotThenFinish(drawable, bounds, getUser());
    }

    private void setResultThenFinish(Uri uri) {
        if (mResultReceiver == null) {
            return;
        }

        // Grant permission here instead of in the trampoline activity because this activity can run
        // as work profile user so the URI can belong to the work profile user while the trampoline
        // activity always runs as main user.
        grantUriPermission(mCallingPackageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Bundle data = new Bundle();
        data.putInt(Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE,
                Intent.CAPTURE_CONTENT_FOR_NOTE_SUCCESS);
        data.putParcelable(EXTRA_SCREENSHOT_URI, uri);
        try {
            mResultReceiver.send(Activity.RESULT_OK, data);
            logUiEvent(SCREENSHOT_FOR_NOTE_ACCEPTED);
        } catch (Exception e) {
            Log.e(TAG, "Error while returning data to trampoline activity", e);
        }

        // Nullify the ResultReceiver before finishing to avoid resending the result.
        mResultReceiver = null;
        finish();
    }

    private void setErrorThenFinish(int errorCode) {
        setError(errorCode);
        finish();
    }

    private void setError(int errorCode) {
        if (mResultReceiver == null) {
            return;
        }

        Bundle data = new Bundle();
        data.putInt(Intent.EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE, errorCode);
        try {
            mResultReceiver.send(RESULT_OK, data);
            if (errorCode == Intent.CAPTURE_CONTENT_FOR_NOTE_USER_CANCELED) {
                logUiEvent(SCREENSHOT_FOR_NOTE_CANCELLED);
            }
        } catch (Exception e) {
            // Do nothing.
        }

        // Nullify the ResultReceiver to avoid resending the result.
        mResultReceiver = null;
    }

    private void logUiEvent(UiEventEnum uiEvent) {
        mUiEventLogger.log(uiEvent, mCallingPackageUid, mCallingPackageName);
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
            // CropView.
            int imageHeight = (int) (previewHeight * viewRatio / imageRatio);
            int extraPadding = (previewHeight - imageHeight) / 2;
            mCropView.setExtraPadding(extraPadding, extraPadding);
            mCropView.setImageWidth(previewWidth);
        } else {
            // Image is full height.
            mCropView.setExtraPadding(mPreview.getPaddingTop(), mPreview.getPaddingBottom());
            mCropView.setImageWidth((int) (previewHeight * imageRatio));
        }
    }
}
