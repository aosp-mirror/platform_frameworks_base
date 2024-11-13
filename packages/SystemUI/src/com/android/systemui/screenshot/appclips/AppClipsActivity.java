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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;

import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_ACCEPTED;
import static com.android.systemui.screenshot.appclips.AppClipsEvent.SCREENSHOT_FOR_NOTE_CANCELLED;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.ACTION_FINISH_FROM_TRAMPOLINE;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_CALLING_PACKAGE_NAME;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_CALLING_PACKAGE_TASK_ID;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_CLIP_DATA;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_RESULT_RECEIVER;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.EXTRA_SCREENSHOT_URI;
import static com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity.PERMISSION_SELF;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLogger.UiEventEnum;
import com.android.settingslib.Utils;
import com.android.systemui.Flags;
import com.android.systemui.log.DebugLogger;
import com.android.systemui.res.R;
import com.android.systemui.screenshot.appclips.InternalBacklinksData.BacklinksData;
import com.android.systemui.screenshot.appclips.InternalBacklinksData.CrossProfileError;
import com.android.systemui.screenshot.scroll.CropView;
import com.android.systemui.settings.UserTracker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
public class AppClipsActivity extends ComponentActivity {

    private static final String TAG = AppClipsActivity.class.getSimpleName();
    private static final ApplicationInfoFlags APPLICATION_INFO_FLAGS = ApplicationInfoFlags.of(0);
    private static final int DRAWABLE_END = 2;
    private static final float DISABLE_ALPHA = 0.5f;

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
    private CheckBox mBacklinksIncludeDataCheckBox;
    private TextView mBacklinksDataTextView;
    private TextView mBacklinksCrossProfileError;
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

        mBacklinksDataTextView = mLayout.findViewById(R.id.backlinks_data);
        mBacklinksIncludeDataCheckBox = mLayout.findViewById(R.id.backlinks_include_data);
        mBacklinksIncludeDataCheckBox.setOnCheckedChangeListener(
                this::backlinksIncludeDataCheckBoxCheckedChangeListener);
        mBacklinksCrossProfileError = mLayout.findViewById(R.id.backlinks_cross_profile_error);

        mViewModel = new ViewModelProvider(this, mViewModelFactory).get(AppClipsViewModel.class);
        mViewModel.getScreenshot().observe(this, this::setScreenshot);
        mViewModel.getResultLiveData().observe(this, this::setResultThenFinish);
        mViewModel.getErrorLiveData().observe(this, this::setErrorThenFinish);
        mViewModel.getBacklinksLiveData().observe(this, this::setBacklinksData);
        mViewModel.mSelectedBacklinksLiveData.observe(this, this::updateBacklinksTextView);

        if (savedInstanceState == null) {
            int displayId = getDisplayId();
            mViewModel.performScreenshot(displayId);

            if (Flags.appClipsBacklinks()) {
                int appClipsTaskId = getTaskId();
                int callingPackageTaskId = intent.getIntExtra(EXTRA_CALLING_PACKAGE_TASK_ID,
                        INVALID_TASK_ID);
                Set<Integer> taskIdsToIgnore = Set.of(appClipsTaskId, callingPackageTaskId);
                mViewModel.triggerBacklinks(taskIdsToIgnore, displayId);
            }
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

        InternalBacklinksData selectedBacklink = mViewModel.mSelectedBacklinksLiveData.getValue();
        if (mBacklinksIncludeDataCheckBox.getVisibility() == View.VISIBLE
                && mBacklinksIncludeDataCheckBox.isChecked()
                && selectedBacklink instanceof BacklinksData) {
            ClipData backlinksData = ((BacklinksData) selectedBacklink).getClipData();
            data.putParcelable(EXTRA_CLIP_DATA, backlinksData);

            DebugLogger.INSTANCE.logcatMessage(this,
                    () -> "setResultThenFinish: sending notes app ClipData");
        }

        try {
            mResultReceiver.send(Activity.RESULT_OK, data);
            logUiEvent(SCREENSHOT_FOR_NOTE_ACCEPTED);
        } catch (Exception e) {
            Log.e(TAG, "Error while sending data to trampoline activity", e);
        }

        // Nullify the ResultReceiver before finishing to avoid resending the result.
        mResultReceiver = null;
        finish();
    }

    private void setErrorThenFinish(int errorCode) {
        setError(errorCode);
        finish();
    }

    private void setBacklinksData(List<InternalBacklinksData> backlinksData) {
        mBacklinksIncludeDataCheckBox.setVisibility(View.VISIBLE);
        mBacklinksDataTextView.setVisibility(
                mBacklinksIncludeDataCheckBox.isChecked() ? View.VISIBLE : View.GONE);

        // Set up the dropdown when multiple backlinks are available.
        if (backlinksData.size() > 1) {
            setUpListPopupWindow(updateBacklinkLabelsWithDuplicateNames(backlinksData),
                    mBacklinksDataTextView);
        }
    }

    /**
     * If there are more than 1 backlinks that have the same app name, then this method appends
     * a numerical suffix to such backlinks to help users distinguish.
     */
    private List<InternalBacklinksData> updateBacklinkLabelsWithDuplicateNames(
            List<InternalBacklinksData> backlinksData) {
        // Check if there are multiple backlinks with same name.
        Map<String, Integer> duplicateNamedBacklinksCountMap = new HashMap<>();
        for (InternalBacklinksData data : backlinksData) {
            if (duplicateNamedBacklinksCountMap.containsKey(data.getDisplayLabel())) {
                int duplicateCount = duplicateNamedBacklinksCountMap.get(data.getDisplayLabel());
                if (duplicateCount == 0) {
                    // If this is the first time the loop is coming across a duplicate name, set the
                    // count to 2. This way the count starts from 1 for all duplicate named
                    // backlinks.
                    duplicateNamedBacklinksCountMap.put(data.getDisplayLabel(), 2);
                } else {
                    // For all duplicate named backlinks, increase the duplicate count by 1.
                    duplicateNamedBacklinksCountMap.put(data.getDisplayLabel(), duplicateCount + 1);
                }
            } else {
                // This is the first time the loop is coming across a backlink with this name. Set
                // its count to 0. The loop will increase its count by 1 when a duplicate is found.
                duplicateNamedBacklinksCountMap.put(data.getDisplayLabel(), 0);
            }
        }

        // Go through the backlinks in reverse order as it is easier to assign the numerical suffix
        // in descending order of frequency using the duplicate map that was built earlier. For
        // example, if "App A" is present 3 times, then we assign display label "App A (3)" first
        // and then "App A (2)", lastly "App A (1)".
        for (InternalBacklinksData data : backlinksData.reversed()) {
            String originalBacklinkLabel = data.getDisplayLabel();
            int duplicateCount = duplicateNamedBacklinksCountMap.get(originalBacklinkLabel);

            // The display label should only be updated if there are multiple backlinks with the
            // same name.
            if (duplicateCount > 0) {
                // Update the display label to: "App name (count)"
                data.setDisplayLabel(
                        getString(R.string.backlinks_duplicate_label_format, originalBacklinkLabel,
                                duplicateCount));

                // Decrease the duplicate count and update the map.
                duplicateCount--;
                duplicateNamedBacklinksCountMap.put(originalBacklinkLabel, duplicateCount);
            }
        }

        return backlinksData;
    }

    private void setUpListPopupWindow(List<InternalBacklinksData> backlinksData, View anchor) {
        ListPopupWindow listPopupWindow = new ListPopupWindow(this);
        listPopupWindow.setAnchorView(anchor);
        listPopupWindow.setOverlapAnchor(true);
        listPopupWindow.setBackgroundDrawable(
                AppCompatResources.getDrawable(this, R.drawable.backlinks_rounded_rectangle));
        listPopupWindow.setOnItemClickListener((parent, view, position, id) -> {
            mViewModel.mSelectedBacklinksLiveData.setValue(backlinksData.get(position));
            listPopupWindow.dismiss();
        });

        ArrayAdapter<InternalBacklinksData> adapter = new ArrayAdapter<>(this,
                R.layout.app_clips_backlinks_drop_down_entry) {
            @Override
            public View getView(int position, @Nullable View convertView, ViewGroup parent) {
                TextView itemView = (TextView) super.getView(position, convertView, parent);
                InternalBacklinksData data = backlinksData.get(position);
                itemView.setText(data.getDisplayLabel());

                Drawable icon = data.getAppIcon();
                icon.setBounds(createBacklinksTextViewDrawableBounds());
                itemView.setCompoundDrawablesRelative(/* start= */ icon, /* top= */ null,
                        /* end= */ null, /* bottom= */ null);

                return itemView;
            }
        };
        adapter.addAll(backlinksData);
        listPopupWindow.setAdapter(adapter);

        mBacklinksDataTextView.setOnClickListener(unused -> listPopupWindow.show());
    }

    /**
     * Updates the {@link #mBacklinksDataTextView} with the currently selected
     * {@link InternalBacklinksData}. The {@link AppClipsViewModel#getBacklinksLiveData()} is
     * expected to be already set when this method is called.
     */
    private void updateBacklinksTextView(InternalBacklinksData backlinksData) {
        mBacklinksDataTextView.setText(backlinksData.getDisplayLabel());
        Drawable appIcon = backlinksData.getAppIcon();
        Rect compoundDrawableBounds = createBacklinksTextViewDrawableBounds();
        appIcon.setBounds(compoundDrawableBounds);

        // Try to reuse the dropdown down arrow icon if available, will be null if never set.
        Drawable dropDownIcon = mBacklinksDataTextView.getCompoundDrawablesRelative()[DRAWABLE_END];
        if (mViewModel.getBacklinksLiveData().getValue().size() > 1 && dropDownIcon == null) {
            // Set up the dropdown down arrow drawable only if it is required.
            dropDownIcon = AppCompatResources.getDrawable(this, R.drawable.arrow_pointing_down);
            dropDownIcon.setBounds(compoundDrawableBounds);
            dropDownIcon.setTint(Utils.getColorAttr(this,
                    android.R.attr.textColorSecondary).getDefaultColor());
        }

        mBacklinksDataTextView.setCompoundDrawablesRelative(/* start= */ appIcon, /* top= */
                null, /* end= */ dropDownIcon, /* bottom= */ null);

        updateViewsToShowOrHideBacklinkError(backlinksData);
    }

    /** Updates views to show or hide error with backlink.  */
    private void updateViewsToShowOrHideBacklinkError(InternalBacklinksData backlinksData) {
        // Remove the check box change listener before updating it to avoid updating backlink text
        // view visibility.
        mBacklinksIncludeDataCheckBox.setOnCheckedChangeListener(null);
        if (backlinksData instanceof CrossProfileError) {
            // There's error with the backlink, unselect the checkbox and disable it.
            mBacklinksIncludeDataCheckBox.setEnabled(false);
            mBacklinksIncludeDataCheckBox.setChecked(false);
            mBacklinksIncludeDataCheckBox.setAlpha(DISABLE_ALPHA);

            mBacklinksCrossProfileError.setVisibility(View.VISIBLE);
        } else {
            // When there is no error, ensure the check box is enabled and checked.
            mBacklinksIncludeDataCheckBox.setEnabled(true);
            mBacklinksIncludeDataCheckBox.setChecked(true);
            mBacklinksIncludeDataCheckBox.setAlpha(1.0f);

            mBacklinksCrossProfileError.setVisibility(View.GONE);
        }

        // (Re)Set the check box change listener as we're done making changes to the check box.
        mBacklinksIncludeDataCheckBox.setOnCheckedChangeListener(
                this::backlinksIncludeDataCheckBoxCheckedChangeListener);
    }

    private void backlinksIncludeDataCheckBoxCheckedChangeListener(View unused, boolean isChecked) {
        mBacklinksDataTextView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
    }

    private Rect createBacklinksTextViewDrawableBounds() {
        int size = getResources().getDimensionPixelSize(R.dimen.appclips_backlinks_icon_size);
        Rect bounds = new Rect();
        bounds.set(/* left= */ 0, /* top= */ 0, /* right= */ size, /* bottom= */ size);
        return bounds;
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
            Log.e(TAG, "Error while sending trampoline activity error code: " + errorCode, e);
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
