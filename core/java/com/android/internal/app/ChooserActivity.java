/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.app;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionManager;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.LabeledIntent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.Downloads;
import android.provider.OpenableColumns;
import android.service.chooser.ChooserTarget;
import android.service.chooser.ChooserTargetService;
import android.service.chooser.IChooserTargetResult;
import android.service.chooser.IChooserTargetService;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ImageUtils;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ChooserActivity extends ResolverActivity {
    private static final String TAG = "ChooserActivity";

    /**
     * Boolean extra to change the following behavior: Normally, ChooserActivity finishes itself
     * in onStop when launched in a new task. If this extra is set to true, we do not finish
     * ourselves when onStop gets called.
     */
    public static final String EXTRA_PRIVATE_RETAIN_IN_ON_STOP
            = "com.android.internal.app.ChooserActivity.EXTRA_PRIVATE_RETAIN_IN_ON_STOP";

    private static final boolean DEBUG = false;


    /**
     * If {@link #USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS} and this is set to true,
     * {@link AppPredictionManager} will be queried for direct share targets.
     */
    // TODO(b/123089490): Replace with system flag
    private static final boolean USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS = false;
    // TODO(b/123088566) Share these in a better way.
    private static final String APP_PREDICTION_SHARE_UI_SURFACE = "share";
    public static final String LAUNCH_LOCATON_DIRECT_SHARE = "direct_share";
    private static final int APP_PREDICTION_SHARE_TARGET_QUERY_PACKAGE_LIMIT = 20;
    public static final String APP_PREDICTION_INTENT_FILTER_KEY = "intent_filter";
    private AppPredictor mAppPredictor;
    private AppPredictor.Callback mAppPredictorCallback;

    /**
     * If set to true, use ShortcutManager to retrieve the matching direct share targets, instead of
     * binding to every ChooserTargetService implementation.
     */
    // TODO(b/121287573): Replace with a system flag (setprop?)
    private static final boolean USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS = true;
    private static final boolean USE_CHOOSER_TARGET_SERVICE_FOR_DIRECT_TARGETS = true;

    /**
     * The transition time between placeholders for direct share to a message
     * indicating that non are available.
     */
    private static final int NO_DIRECT_SHARE_ANIM_IN_MILLIS = 200;

    // TODO(b/121287224): Re-evaluate this limit
    private static final int SHARE_TARGET_QUERY_PACKAGE_LIMIT = 20;

    private static final int QUERY_TARGET_SERVICE_LIMIT = 5;
    private static final int WATCHDOG_TIMEOUT_MILLIS = 3000;

    private Bundle mReplacementExtras;
    private IntentSender mChosenComponentSender;
    private IntentSender mRefinementIntentSender;
    private RefinementResultReceiver mRefinementResultReceiver;
    private ChooserTarget[] mCallerChooserTargets;
    private ComponentName[] mFilteredComponentNames;

    private Intent mReferrerFillInIntent;

    private long mChooserShownTime;
    protected boolean mIsSuccessfullySelected;

    private ChooserListAdapter mChooserListAdapter;
    private ChooserRowAdapter mChooserRowAdapter;
    private Drawable mChooserRowLayer;
    private int mChooserRowServiceSpacing;

    private SharedPreferences mPinnedSharedPrefs;
    private static final float PINNED_TARGET_SCORE_BOOST = 1000.f;
    private static final float CALLER_TARGET_SCORE_BOOST = 900.f;
    private static final String PINNED_SHARED_PREFS_NAME = "chooser_pin_settings";
    private static final String TARGET_DETAILS_FRAGMENT_TAG = "targetDetailsFragment";

    private final List<ChooserTargetServiceConnection> mServiceConnections = new ArrayList<>();

    private static final int CHOOSER_TARGET_SERVICE_RESULT = 1;
    private static final int CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT = 2;
    private static final int SHORTCUT_MANAGER_SHARE_TARGET_RESULT = 3;
    private static final int SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED = 4;

    @Retention(SOURCE)
    @IntDef({CONTENT_PREVIEW_FILE, CONTENT_PREVIEW_IMAGE, CONTENT_PREVIEW_TEXT})
    private @interface ContentPreviewType {
    }

    // Starting at 1 since 0 is considered "undefined" for some of the database transformations
    // of tron logs.
    private static final int CONTENT_PREVIEW_IMAGE = 1;
    private static final int CONTENT_PREVIEW_FILE = 2;
    private static final int CONTENT_PREVIEW_TEXT = 3;
    protected MetricsLogger mMetricsLogger;

    private final Handler mChooserHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CHOOSER_TARGET_SERVICE_RESULT:
                    if (DEBUG) Log.d(TAG, "CHOOSER_TARGET_SERVICE_RESULT");
                    if (isDestroyed()) break;
                    final ServiceResultInfo sri = (ServiceResultInfo) msg.obj;
                    if (!mServiceConnections.contains(sri.connection)) {
                        Log.w(TAG, "ChooserTargetServiceConnection " + sri.connection
                                + " returned after being removed from active connections."
                                + " Have you considered returning results faster?");
                        break;
                    }
                    if (sri.resultTargets != null) {
                        mChooserListAdapter.addServiceResults(sri.originalTarget,
                                sri.resultTargets);
                    }
                    unbindService(sri.connection);
                    sri.connection.destroy();
                    mServiceConnections.remove(sri.connection);
                    if (mServiceConnections.isEmpty()) {
                        sendVoiceChoicesIfNeeded();
                    }
                    break;

                case CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT:
                    if (DEBUG) {
                        Log.d(TAG, "CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT; unbinding services");
                    }
                    if (isDestroyed()) {
                        break;
                    }
                    unbindRemainingServices();
                    sendVoiceChoicesIfNeeded();
                    mChooserListAdapter.completeServiceTargetLoading();
                    break;

                case SHORTCUT_MANAGER_SHARE_TARGET_RESULT:
                    if (DEBUG) Log.d(TAG, "SHORTCUT_MANAGER_SHARE_TARGET_RESULT");
                    if (isDestroyed()) break;
                    final ServiceResultInfo resultInfo = (ServiceResultInfo) msg.obj;
                    if (resultInfo.resultTargets != null) {
                        mChooserListAdapter.addServiceResults(resultInfo.originalTarget,
                                resultInfo.resultTargets);
                    }
                    break;

                case SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED:
                    sendVoiceChoicesIfNeeded();
                    break;

                default:
                    super.handleMessage(msg);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final long intentReceivedTime = System.currentTimeMillis();
        mIsSuccessfullySelected = false;
        Intent intent = getIntent();
        Parcelable targetParcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (!(targetParcelable instanceof Intent)) {
            Log.w("ChooserActivity", "Target is not an intent: " + targetParcelable);
            finish();
            super.onCreate(null);
            return;
        }
        Intent target = (Intent) targetParcelable;
        if (target != null) {
            modifyTargetIntent(target);
        }
        Parcelable[] targetsParcelable
                = intent.getParcelableArrayExtra(Intent.EXTRA_ALTERNATE_INTENTS);
        if (targetsParcelable != null) {
            final boolean offset = target == null;
            Intent[] additionalTargets =
                    new Intent[offset ? targetsParcelable.length - 1 : targetsParcelable.length];
            for (int i = 0; i < targetsParcelable.length; i++) {
                if (!(targetsParcelable[i] instanceof Intent)) {
                    Log.w(TAG, "EXTRA_ALTERNATE_INTENTS array entry #" + i + " is not an Intent: "
                            + targetsParcelable[i]);
                    finish();
                    super.onCreate(null);
                    return;
                }
                final Intent additionalTarget = (Intent) targetsParcelable[i];
                if (i == 0 && target == null) {
                    target = additionalTarget;
                    modifyTargetIntent(target);
                } else {
                    additionalTargets[offset ? i - 1 : i] = additionalTarget;
                    modifyTargetIntent(additionalTarget);
                }
            }
            setAdditionalTargets(additionalTargets);
        }

        mReplacementExtras = intent.getBundleExtra(Intent.EXTRA_REPLACEMENT_EXTRAS);

        // Do not allow the title to be changed when sharing content
        CharSequence title = null;
        if (target != null) {
            if (!isSendAction(target)) {
                title = intent.getCharSequenceExtra(Intent.EXTRA_TITLE);
            } else {
                Log.w(TAG, "Ignoring intent's EXTRA_TITLE, deprecated in P. You may wish to set a"
                        + " preview title by using EXTRA_TITLE property of the wrapped"
                        + " EXTRA_INTENT.");
            }
        }

        int defaultTitleRes = 0;
        if (title == null) {
            defaultTitleRes = com.android.internal.R.string.chooseActivity;
        }

        Parcelable[] pa = intent.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS);
        Intent[] initialIntents = null;
        if (pa != null) {
            initialIntents = new Intent[pa.length];
            for (int i = 0; i < pa.length; i++) {
                if (!(pa[i] instanceof Intent)) {
                    Log.w(TAG, "Initial intent #" + i + " not an Intent: " + pa[i]);
                    finish();
                    super.onCreate(null);
                    return;
                }
                final Intent in = (Intent) pa[i];
                modifyTargetIntent(in);
                initialIntents[i] = in;
            }
        }

        mReferrerFillInIntent = new Intent().putExtra(Intent.EXTRA_REFERRER, getReferrer());

        mChosenComponentSender = intent.getParcelableExtra(
                Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER);
        mRefinementIntentSender = intent.getParcelableExtra(
                Intent.EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER);
        setSafeForwardingMode(true);

        pa = intent.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS);
        if (pa != null) {
            ComponentName[] names = new ComponentName[pa.length];
            for (int i = 0; i < pa.length; i++) {
                if (!(pa[i] instanceof ComponentName)) {
                    Log.w(TAG, "Filtered component #" + i + " not a ComponentName: " + pa[i]);
                    names = null;
                    break;
                }
                names[i] = (ComponentName) pa[i];
            }
            mFilteredComponentNames = names;
        }

        pa = intent.getParcelableArrayExtra(Intent.EXTRA_CHOOSER_TARGETS);
        if (pa != null) {
            ChooserTarget[] targets = new ChooserTarget[pa.length];
            for (int i = 0; i < pa.length; i++) {
                if (!(pa[i] instanceof ChooserTarget)) {
                    Log.w(TAG, "Chooser target #" + i + " not a ChooserTarget: " + pa[i]);
                    targets = null;
                    break;
                }
                targets[i] = (ChooserTarget) pa[i];
            }
            mCallerChooserTargets = targets;
        }

        mPinnedSharedPrefs = getPinnedSharedPrefs(this);
        setRetainInOnStop(intent.getBooleanExtra(EXTRA_PRIVATE_RETAIN_IN_ON_STOP, false));
        super.onCreate(savedInstanceState, target, title, defaultTitleRes, initialIntents,
                null, false);

        mChooserShownTime = System.currentTimeMillis();
        final long systemCost = mChooserShownTime - intentReceivedTime;

        getMetricsLogger().write(new LogMaker(MetricsEvent.ACTION_ACTIVITY_CHOOSER_SHOWN)
                .setSubtype(isWorkProfile() ? MetricsEvent.MANAGED_PROFILE :
                        MetricsEvent.PARENT_PROFILE)
                .addTaggedData(MetricsEvent.FIELD_SHARESHEET_MIMETYPE, target.getType())
                .addTaggedData(MetricsEvent.FIELD_TIME_TO_APP_TARGETS, systemCost));

        if (USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS) {
            final IntentFilter filter = getTargetIntentFilter();
            Bundle extras = new Bundle();
            extras.putParcelable(APP_PREDICTION_INTENT_FILTER_KEY, filter);
            AppPredictionManager appPredictionManager =
                    getSystemService(AppPredictionManager.class);
            mAppPredictor = appPredictionManager.createAppPredictionSession(
                new AppPredictionContext.Builder(this)
                    .setPredictedTargetCount(APP_PREDICTION_SHARE_TARGET_QUERY_PACKAGE_LIMIT)
                    .setUiSurface(APP_PREDICTION_SHARE_UI_SURFACE)
                    .setExtras(extras)
                    .build());
            mAppPredictorCallback = resultList -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // May be null if there are no apps to perform share/open action.
                if (mChooserListAdapter == null) {
                    return;
                }
                final List<DisplayResolveInfo> driList =
                        getDisplayResolveInfos(mChooserListAdapter);
                final List<ShortcutManager.ShareShortcutInfo> shareShortcutInfos =
                        new ArrayList<>();
                for (AppTarget appTarget : resultList) {
                    if (appTarget.getShortcutInfo() == null) {
                        continue;
                    }
                    shareShortcutInfos.add(new ShortcutManager.ShareShortcutInfo(
                            appTarget.getShortcutInfo(),
                            new ComponentName(
                                appTarget.getPackageName(), appTarget.getClassName())));
                }
                sendShareShortcutInfoList(shareShortcutInfos, driList);
            };
            mAppPredictor.registerPredictionUpdates(this.getMainExecutor(), mAppPredictorCallback);
        }

        mChooserRowLayer = getResources().getDrawable(R.drawable.chooser_row_layer_list, null);
        mChooserRowServiceSpacing = getResources()
                                        .getDimensionPixelSize(R.dimen.chooser_service_spacing);

        // expand/shrink direct share 4 -> 8 viewgroup
        if (mResolverDrawerLayout != null && isSendAction(target)) {
            mResolverDrawerLayout.setOnScrollChangeListener(this::handleScroll);
        }

        if (DEBUG) {
            Log.d(TAG, "System Time Cost is " + systemCost);
        }
    }

    /**
     * Check if the profile currently used is a work profile.
     * @return true if it is work profile, false if it is parent profile (or no work profile is
     * set up)
     */
    protected boolean isWorkProfile() {
        return ((UserManager) getSystemService(Context.USER_SERVICE))
                .getUserInfo(UserHandle.myUserId()).isManagedProfile();
    }

    private void onCopyButtonClicked(View v) {
        Intent targetIntent = getTargetIntent();
        if (targetIntent == null) {
            finish();
        } else {
            final String action = targetIntent.getAction();

            ClipData clipData = null;
            if (Intent.ACTION_SEND.equals(action)) {
                String extraText = targetIntent.getStringExtra(Intent.EXTRA_TEXT);
                Uri extraStream = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);

                if (extraText != null) {
                    clipData = ClipData.newPlainText(null, extraText);
                } else if (extraStream != null) {
                    clipData = ClipData.newUri(getContentResolver(), null, extraStream);
                } else {
                    Log.w(TAG, "No data available to copy to clipboard");
                    return;
                }
            } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                final ArrayList<Uri> streams = targetIntent.getParcelableArrayListExtra(
                        Intent.EXTRA_STREAM);
                clipData = ClipData.newUri(getContentResolver(), null, streams.get(0));
                for (int i = 1; i < streams.size(); i++) {
                    clipData.addItem(getContentResolver(), new ClipData.Item(streams.get(i)));
                }
            } else {
                // expected to only be visible with ACTION_SEND or ACTION_SEND_MULTIPLE
                // so warn about unexpected action
                Log.w(TAG, "Action (" + action + ") not supported for copying to clipboard");
                return;
            }

            ClipboardManager clipboardManager = (ClipboardManager) getSystemService(
                    Context.CLIPBOARD_SERVICE);
            clipboardManager.setPrimaryClip(clipData);
            Toast.makeText(getApplicationContext(), R.string.copied, Toast.LENGTH_SHORT).show();

            finish();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        int width = -1;
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            width = getResources().getDimensionPixelSize(R.dimen.chooser_preview_width);
        }

        updateLayoutWidth(R.id.content_preview_text_layout, width);
        updateLayoutWidth(R.id.content_preview_title_layout, width);
        updateLayoutWidth(R.id.content_preview_file_layout, width);
    }

    private void updateLayoutWidth(int layoutResourceId, int width) {
        View view = findViewById(layoutResourceId);
        if (view != null && view.getLayoutParams() != null) {
            LayoutParams params = view.getLayoutParams();
            params.width = width;
            view.setLayoutParams(params);
        }
    }

    private ViewGroup displayContentPreview(@ContentPreviewType int previewType,
            Intent targetIntent, LayoutInflater layoutInflater, ViewGroup convertView,
            ViewGroup parent) {
        switch (previewType) {
            case CONTENT_PREVIEW_TEXT:
                return displayTextContentPreview(targetIntent, layoutInflater, convertView, parent);
            case CONTENT_PREVIEW_IMAGE:
                return displayImageContentPreview(targetIntent, layoutInflater, convertView,
                        parent);
            case CONTENT_PREVIEW_FILE:
                return displayFileContentPreview(targetIntent, layoutInflater, convertView, parent);
            default:
                Log.e(TAG, "Unexpected content preview type: " + previewType);
        }

        return null;
    }

    private ViewGroup displayTextContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup convertView, ViewGroup parent) {
        ViewGroup contentPreviewLayout =
                convertView != null ? convertView : (ViewGroup) layoutInflater.inflate(
                        R.layout.chooser_grid_preview_text, parent, false);

        contentPreviewLayout.findViewById(R.id.copy_button).setOnClickListener(
                this::onCopyButtonClicked);

        CharSequence sharingText = targetIntent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (sharingText == null) {
            contentPreviewLayout.findViewById(R.id.content_preview_text_layout).setVisibility(
                    View.GONE);
        } else {
            TextView textView = contentPreviewLayout.findViewById(R.id.content_preview_text);
            textView.setText(sharingText);
        }

        String previewTitle = targetIntent.getStringExtra(Intent.EXTRA_TITLE);
        if (TextUtils.isEmpty(previewTitle)) {
            contentPreviewLayout.findViewById(R.id.content_preview_title_layout).setVisibility(
                    View.GONE);
        } else {
            TextView previewTitleView = contentPreviewLayout.findViewById(
                    R.id.content_preview_title);
            previewTitleView.setText(previewTitle);

            ClipData previewData = targetIntent.getClipData();
            Uri previewThumbnail = null;
            if (previewData != null) {
                if (previewData.getItemCount() > 0) {
                    ClipData.Item previewDataItem = previewData.getItemAt(0);
                    previewThumbnail = previewDataItem.getUri();
                }
            }

            ImageView previewThumbnailView = contentPreviewLayout.findViewById(
                    R.id.content_preview_thumbnail);
            if (previewThumbnail == null) {
                previewThumbnailView.setVisibility(View.GONE);
            } else {
                Bitmap bmp = loadThumbnail(previewThumbnail, new Size(100, 100));
                if (bmp == null) {
                    previewThumbnailView.setVisibility(View.GONE);
                } else {
                    previewThumbnailView.setImageBitmap(bmp);
                }
            }
        }

        return contentPreviewLayout;
    }

    private ViewGroup displayImageContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup convertView, ViewGroup parent) {
        ViewGroup contentPreviewLayout =
                convertView != null ? convertView : (ViewGroup) layoutInflater.inflate(
                        R.layout.chooser_grid_preview_image, parent, false);

        String action = targetIntent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            loadUriIntoView(R.id.content_preview_image_1_large, uri, contentPreviewLayout);
        } else {
            ContentResolver resolver = getContentResolver();

            List<Uri> uris = targetIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            List<Uri> imageUris = new ArrayList<>();
            for (Uri uri : uris) {
                if (isImageType(resolver.getType(uri))) {
                    imageUris.add(uri);
                }
            }

            if (imageUris.size() == 0) {
                Log.i(TAG, "Attempted to display image preview area with zero"
                        + " available images detected in EXTRA_STREAM list");
                contentPreviewLayout.setVisibility(View.GONE);
                return contentPreviewLayout;
            }

            loadUriIntoView(R.id.content_preview_image_1_large, imageUris.get(0),
                    contentPreviewLayout);

            if (imageUris.size() == 2) {
                loadUriIntoView(R.id.content_preview_image_2_large, imageUris.get(1),
                        contentPreviewLayout);
            } else if (imageUris.size() > 2) {
                loadUriIntoView(R.id.content_preview_image_2_small, imageUris.get(1),
                        contentPreviewLayout);
                RoundedRectImageView imageView = loadUriIntoView(
                        R.id.content_preview_image_3_small, imageUris.get(2), contentPreviewLayout);

                if (imageUris.size() > 3) {
                    imageView.setExtraImageCount(imageUris.size() - 3);
                }
            }
        }

        return contentPreviewLayout;
    }

    private static class FileInfo {
        public final String name;
        public final boolean hasThumbnail;

        FileInfo(String name, boolean hasThumbnail) {
            this.name = name;
            this.hasThumbnail = hasThumbnail;
        }
    }

    /**
     * Wrapping the ContentResolver call to expose for easier mocking,
     * and to avoid mocking Android core classes.
     */
    @VisibleForTesting
    public Cursor queryResolver(ContentResolver resolver, Uri uri) {
        return resolver.query(uri, null, null, null, null);
    }

    private FileInfo extractFileInfo(Uri uri, ContentResolver resolver) {
        String fileName = null;
        boolean hasThumbnail = false;

        try (Cursor cursor = queryResolver(resolver, uri)) {
            if (cursor != null && cursor.getCount() > 0) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int titleIndex = cursor.getColumnIndex(Downloads.Impl.COLUMN_TITLE);
                int flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);

                cursor.moveToFirst();
                if (nameIndex != -1) {
                    fileName = cursor.getString(nameIndex);
                } else if (titleIndex != -1) {
                    fileName = cursor.getString(titleIndex);
                }

                if (flagsIndex != -1) {
                    hasThumbnail = (cursor.getInt(flagsIndex)
                            & DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL) != 0;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Error loading file preview", e);
        }

        if (TextUtils.isEmpty(fileName)) {
            fileName = uri.getPath();
            int index = fileName.lastIndexOf('/');
            if (index != -1) {
                fileName = fileName.substring(index + 1);
            }
        }

        return new FileInfo(fileName, hasThumbnail);
    }

    private ViewGroup displayFileContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup convertView, ViewGroup parent) {

        ViewGroup contentPreviewLayout =
                convertView != null ? convertView : (ViewGroup) layoutInflater.inflate(
                        R.layout.chooser_grid_preview_file, parent, false);

        // TODO(b/120417119): Disable file copy until after moving to sysui,
        // due to permissions issues
        contentPreviewLayout.findViewById(R.id.file_copy_button).setVisibility(View.GONE);

        String action = targetIntent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            loadFileUriIntoView(uri, contentPreviewLayout);
        } else {
            List<Uri> uris = targetIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            int uriCount = uris.size();

            if (uriCount == 0) {
                contentPreviewLayout.setVisibility(View.GONE);
                Log.i(TAG,
                        "Appears to be no uris available in EXTRA_STREAM, removing "
                                + "preview area");
                return contentPreviewLayout;
            } else if (uriCount == 1) {
                loadFileUriIntoView(uris.get(0), contentPreviewLayout);
            } else {
                FileInfo fileInfo = extractFileInfo(uris.get(0), getContentResolver());
                int remUriCount = uriCount - 1;
                String fileName = getResources().getQuantityString(R.plurals.file_count,
                        remUriCount, fileInfo.name, remUriCount);

                TextView fileNameView = contentPreviewLayout.findViewById(
                        R.id.content_preview_filename);
                fileNameView.setText(fileName);

                ImageView fileIconView = contentPreviewLayout.findViewById(
                        R.id.content_preview_file_icon);
                fileIconView.setVisibility(View.VISIBLE);
                fileIconView.setImageResource(R.drawable.ic_file_copy);
            }
        }

        return contentPreviewLayout;
    }

    private void loadFileUriIntoView(Uri uri, View parent) {
        FileInfo fileInfo = extractFileInfo(uri, getContentResolver());

        TextView fileNameView = parent.findViewById(R.id.content_preview_filename);
        fileNameView.setText(fileInfo.name);

        if (fileInfo.hasThumbnail) {
            loadUriIntoView(R.id.content_preview_file_thumbnail, uri, parent);
        } else {
            ImageView fileIconView = parent.findViewById(R.id.content_preview_file_icon);
            fileIconView.setVisibility(View.VISIBLE);
            fileIconView.setImageResource(R.drawable.ic_doc_generic);
        }
    }

    private RoundedRectImageView loadUriIntoView(int imageResourceId, Uri uri, View parent) {
        RoundedRectImageView imageView = parent.findViewById(imageResourceId);
        Bitmap bmp = loadThumbnail(uri, new Size(200, 200));
        if (bmp != null) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageBitmap(bmp);
        }

        return imageView;
    }

    @VisibleForTesting
    protected boolean isImageType(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    @ContentPreviewType
    private int findPreferredContentPreview(Uri uri, ContentResolver resolver) {
        if (uri == null) {
            return CONTENT_PREVIEW_TEXT;
        }

        String mimeType = resolver.getType(uri);
        return isImageType(mimeType) ? CONTENT_PREVIEW_IMAGE : CONTENT_PREVIEW_FILE;
    }

    /**
     * In {@link android.content.Intent#getType}, the app may specify a very general
     * mime-type that broadly covers all data being shared, such as {@literal *}/*
     * when sending an image and text. We therefore should inspect each item for the
     * the preferred type, in order of IMAGE, FILE, TEXT.
     */
    @ContentPreviewType
    private int findPreferredContentPreview(Intent targetIntent, ContentResolver resolver) {
        String action = targetIntent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            return findPreferredContentPreview(uri, resolver);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> uris = targetIntent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris == null || uris.isEmpty()) {
                return CONTENT_PREVIEW_TEXT;
            }

            for (Uri uri : uris) {
                if (findPreferredContentPreview(uri, resolver) == CONTENT_PREVIEW_IMAGE) {
                    return CONTENT_PREVIEW_IMAGE;
                }
            }

            return CONTENT_PREVIEW_FILE;
        }

        return CONTENT_PREVIEW_TEXT;
    }

    static SharedPreferences getPinnedSharedPrefs(Context context) {
        // The code below is because in the android:ui process, no one can hear you scream.
        // The package info in the context isn't initialized in the way it is for normal apps,
        // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
        // build the path manually below using the same policy that appears in ContextImpl.
        // This fails silently under the hood if there's a problem, so if we find ourselves in
        // the case where we don't have access to credential encrypted storage we just won't
        // have our pinned target info.
        final File prefsFile = new File(new File(
                Environment.getDataUserCePackageDirectory(StorageManager.UUID_PRIVATE_INTERNAL,
                        context.getUserId(), context.getPackageName()),
                "shared_prefs"),
                PINNED_SHARED_PREFS_NAME + ".xml");
        return context.getSharedPreferences(prefsFile, MODE_PRIVATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        unbindRemainingServices();
        mChooserHandler.removeMessages(CHOOSER_TARGET_SERVICE_RESULT);
        if (USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS) {
            mAppPredictor.unregisterPredictionUpdates(mAppPredictorCallback);
            mAppPredictor.destroy();
        }
    }

    @Override
    public Intent getReplacementIntent(ActivityInfo aInfo, Intent defIntent) {
        Intent result = defIntent;
        if (mReplacementExtras != null) {
            final Bundle replExtras = mReplacementExtras.getBundle(aInfo.packageName);
            if (replExtras != null) {
                result = new Intent(defIntent);
                result.putExtras(replExtras);
            }
        }
        if (aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_PARENT)
                || aInfo.name.equals(IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE)) {
            result = Intent.createChooser(result,
                    getIntent().getCharSequenceExtra(Intent.EXTRA_TITLE));

            // Don't auto-launch single intents if the intent is being forwarded. This is done
            // because automatically launching a resolving application as a response to the user
            // action of switching accounts is pretty unexpected.
            result.putExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, false);
        }
        return result;
    }

    @Override
    public void onActivityStarted(TargetInfo cti) {
        if (mChosenComponentSender != null) {
            final ComponentName target = cti.getResolvedComponentName();
            if (target != null) {
                final Intent fillIn = new Intent().putExtra(Intent.EXTRA_CHOSEN_COMPONENT, target);
                try {
                    mChosenComponentSender.sendIntent(this, Activity.RESULT_OK, fillIn, null, null);
                } catch (IntentSender.SendIntentException e) {
                    Slog.e(TAG, "Unable to launch supplied IntentSender to report "
                            + "the chosen component: " + e);
                }
            }
        }
    }

    @Override
    public void onPrepareAdapterView(AbsListView adapterView, ResolveListAdapter adapter) {
        final ListView listView = adapterView instanceof ListView ? (ListView) adapterView : null;
        mChooserListAdapter = (ChooserListAdapter) adapter;
        if (mCallerChooserTargets != null && mCallerChooserTargets.length > 0) {
            mChooserListAdapter.addServiceResults(null, Lists.newArrayList(mCallerChooserTargets));
        }
        mChooserRowAdapter = new ChooserRowAdapter(mChooserListAdapter);
        mChooserRowAdapter.registerDataSetObserver(new OffsetDataSetObserver(adapterView));
        adapterView.setAdapter(mChooserRowAdapter);
        if (listView != null) {
            listView.setItemsCanFocus(true);
        }
    }

    @Override
    public int getLayoutResource() {
        return R.layout.chooser_grid;
    }

    @Override
    public boolean shouldGetActivityMetadata() {
        return true;
    }

    @Override
    public boolean shouldAutoLaunchSingleChoice(TargetInfo target) {
        // Note that this is only safe because the Intent handled by the ChooserActivity is
        // guaranteed to contain no extras unknown to the local ClassLoader. That is why this
        // method can not be replaced in the ResolverActivity whole hog.
        return getIntent().getBooleanExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE,
                super.shouldAutoLaunchSingleChoice(target));
    }

    @Override
    public void showTargetDetails(ResolveInfo ri) {
        if (ri == null) {
            return;
        }

        ComponentName name = ri.activityInfo.getComponentName();
        boolean pinned = mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
        ResolverTargetActionsDialogFragment f =
                new ResolverTargetActionsDialogFragment(ri.loadLabel(getPackageManager()),
                        name, pinned);
        f.show(getFragmentManager(), TARGET_DETAILS_FRAGMENT_TAG);
    }

    private void modifyTargetIntent(Intent in) {
        if (isSendAction(in)) {
            in.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
    }

    @Override
    protected boolean onTargetSelected(TargetInfo target, boolean alwaysCheck) {
        if (target instanceof NotSelectableTargetInfo) {
            return false;
        }

        if (mRefinementIntentSender != null) {
            final Intent fillIn = new Intent();
            final List<Intent> sourceIntents = target.getAllSourceIntents();
            if (!sourceIntents.isEmpty()) {
                fillIn.putExtra(Intent.EXTRA_INTENT, sourceIntents.get(0));
                if (sourceIntents.size() > 1) {
                    final Intent[] alts = new Intent[sourceIntents.size() - 1];
                    for (int i = 1, N = sourceIntents.size(); i < N; i++) {
                        alts[i - 1] = sourceIntents.get(i);
                    }
                    fillIn.putExtra(Intent.EXTRA_ALTERNATE_INTENTS, alts);
                }
                if (mRefinementResultReceiver != null) {
                    mRefinementResultReceiver.destroy();
                }
                mRefinementResultReceiver = new RefinementResultReceiver(this, target, null);
                fillIn.putExtra(Intent.EXTRA_RESULT_RECEIVER,
                        mRefinementResultReceiver);
                try {
                    mRefinementIntentSender.sendIntent(this, 0, fillIn, null, null);
                    return false;
                } catch (SendIntentException e) {
                    Log.e(TAG, "Refinement IntentSender failed to send", e);
                }
            }
        }
        updateModelAndChooserCounts(target);
        return super.onTargetSelected(target, alwaysCheck);
    }

    @Override
    public void startSelected(int which, boolean always, boolean filtered) {
        final long selectionCost = System.currentTimeMillis() - mChooserShownTime;
        super.startSelected(which, always, filtered);

        if (mChooserListAdapter != null) {
            // Log the index of which type of target the user picked.
            // Lower values mean the ranking was better.
            int cat = 0;
            int value = which;
            switch (mChooserListAdapter.getPositionTargetType(which)) {
                case ChooserListAdapter.TARGET_CALLER:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_APP_TARGET;
                    value -= mChooserListAdapter.getSelectableServiceTargetCount();
                    break;
                case ChooserListAdapter.TARGET_SERVICE:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET;
                    break;
                case ChooserListAdapter.TARGET_STANDARD:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_STANDARD_TARGET;
                    value -= mChooserListAdapter.getCallerTargetCount()
                            + mChooserListAdapter.getSelectableServiceTargetCount();
                    break;
            }

            if (cat != 0) {
                MetricsLogger.action(this, cat, value);
            }

            if (mIsSuccessfullySelected) {
                if (DEBUG) {
                    Log.d(TAG, "User Selection Time Cost is " + selectionCost);
                    Log.d(TAG, "position of selected app/service/caller is " +
                            Integer.toString(value));
                }
                MetricsLogger.histogram(null, "user_selection_cost_for_smart_sharing",
                        (int) selectionCost);
                MetricsLogger.histogram(null, "app_position_for_smart_sharing", value);
            }
        }
    }

    void queryTargetServices(ChooserListAdapter adapter) {
        final PackageManager pm = getPackageManager();
        ShortcutManager sm = (ShortcutManager) getSystemService(ShortcutManager.class);
        int targetsToQuery = 0;
        for (int i = 0, N = adapter.getDisplayResolveInfoCount(); i < N; i++) {
            final DisplayResolveInfo dri = adapter.getDisplayResolveInfo(i);
            if (adapter.getScore(dri) == 0) {
                // A score of 0 means the app hasn't been used in some time;
                // don't query it as it's not likely to be relevant.
                continue;
            }
            final ActivityInfo ai = dri.getResolveInfo().activityInfo;
            if (USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS
                    && sm.hasShareTargets(ai.packageName)) {
                // Share targets will be queried from ShortcutManager
                continue;
            }
            final Bundle md = ai.metaData;
            final String serviceName = md != null ? convertServiceName(ai.packageName,
                    md.getString(ChooserTargetService.META_DATA_NAME)) : null;
            if (serviceName != null) {
                final ComponentName serviceComponent = new ComponentName(
                        ai.packageName, serviceName);
                final Intent serviceIntent = new Intent(ChooserTargetService.SERVICE_INTERFACE)
                        .setComponent(serviceComponent);

                if (DEBUG) {
                    Log.d(TAG, "queryTargets found target with service " + serviceComponent);
                }

                try {
                    final String perm = pm.getServiceInfo(serviceComponent, 0).permission;
                    if (!ChooserTargetService.BIND_PERMISSION.equals(perm)) {
                        Log.w(TAG, "ChooserTargetService " + serviceComponent + " does not require"
                                + " permission " + ChooserTargetService.BIND_PERMISSION
                                + " - this service will not be queried for ChooserTargets."
                                + " add android:permission=\""
                                + ChooserTargetService.BIND_PERMISSION + "\""
                                + " to the <service> tag for " + serviceComponent
                                + " in the manifest.");
                        continue;
                    }
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Could not look up service " + serviceComponent
                            + "; component name not found");
                    continue;
                }

                final ChooserTargetServiceConnection conn =
                        new ChooserTargetServiceConnection(this, dri);

                // Explicitly specify Process.myUserHandle instead of calling bindService
                // to avoid the warning from calling from the system process without an explicit
                // user handle
                if (bindServiceAsUser(serviceIntent, conn, BIND_AUTO_CREATE | BIND_NOT_FOREGROUND,
                        Process.myUserHandle())) {
                    if (DEBUG) {
                        Log.d(TAG, "Binding service connection for target " + dri
                                + " intent " + serviceIntent);
                    }
                    mServiceConnections.add(conn);
                    targetsToQuery++;
                }
            }
            if (targetsToQuery >= QUERY_TARGET_SERVICE_LIMIT) {
                if (DEBUG) {
                    Log.d(TAG, "queryTargets hit query target limit "
                            + QUERY_TARGET_SERVICE_LIMIT);
                }
                break;
            }
        }

        if (DEBUG) {
            Log.d(TAG, "queryTargets setting watchdog timer for "
                    + WATCHDOG_TIMEOUT_MILLIS + "ms");
        }
        mChooserHandler.sendEmptyMessageDelayed(CHOOSER_TARGET_SERVICE_WATCHDOG_TIMEOUT,
                WATCHDOG_TIMEOUT_MILLIS);

        if (mServiceConnections.isEmpty()) {
            sendVoiceChoicesIfNeeded();
        }
    }

    private IntentFilter getTargetIntentFilter() {
        try {
            final Intent intent = getTargetIntent();
            String dataString = intent.getDataString();
            if (TextUtils.isEmpty(dataString)) {
                dataString = intent.getType();
            }
            return new IntentFilter(intent.getAction(), dataString);
        } catch (Exception e) {
            Log.e(TAG, "failed to get target intent filter " + e);
            return null;
        }
    }

    private List<DisplayResolveInfo> getDisplayResolveInfos(ChooserListAdapter adapter) {
        // Need to keep the original DisplayResolveInfos to be able to reconstruct ServiceResultInfo
        // and use the old code path. This Ugliness should go away when Sharesheet is refactored.
        List<DisplayResolveInfo> driList = new ArrayList<>();
        int targetsToQuery = 0;
        for (int i = 0, n = adapter.getDisplayResolveInfoCount(); i < n; i++) {
            final DisplayResolveInfo dri = adapter.getDisplayResolveInfo(i);
            if (adapter.getScore(dri) == 0) {
                // A score of 0 means the app hasn't been used in some time;
                // don't query it as it's not likely to be relevant.
                continue;
            }
            driList.add(dri);
            targetsToQuery++;
            // TODO(b/121287224): Do we need this here? (similar to queryTargetServices)
            if (targetsToQuery >= SHARE_TARGET_QUERY_PACKAGE_LIMIT) {
                if (DEBUG) {
                    Log.d(TAG, "queryTargets hit query target limit "
                            + SHARE_TARGET_QUERY_PACKAGE_LIMIT);
                }
                break;
            }
        }
        return driList;
    }

    private void queryDirectShareTargets(ChooserListAdapter adapter) {
        if (USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS) {
            mAppPredictor.requestPredictionUpdate();
            return;
        }
        final IntentFilter filter = getTargetIntentFilter();
        if (filter == null) {
            return;
        }
        final List<DisplayResolveInfo> driList = getDisplayResolveInfos(adapter);

        AsyncTask.execute(() -> {
            ShortcutManager sm = (ShortcutManager) getSystemService(Context.SHORTCUT_SERVICE);
            List<ShortcutManager.ShareShortcutInfo> resultList = sm.getShareTargets(filter);
            sendShareShortcutInfoList(resultList, driList);
        });
    }

    private void sendShareShortcutInfoList(
                List<ShortcutManager.ShareShortcutInfo> resultList,
                List<DisplayResolveInfo> driList) {
        // Match ShareShortcutInfos with DisplayResolveInfos to be able to use the old code path
        // for direct share targets. After ShareSheet is refactored we should use the
        // ShareShortcutInfos directly.
        boolean resultMessageSent = false;
        for (int i = 0; i < driList.size(); i++) {
            List<ChooserTarget> chooserTargets = new ArrayList<>();
            for (int j = 0; j < resultList.size(); j++) {
                if (driList.get(i).getResolvedComponentName().equals(
                            resultList.get(j).getTargetComponent())) {
                    chooserTargets.add(convertToChooserTarget(resultList.get(j)));
                }
            }
            if (chooserTargets.isEmpty()) {
                continue;
            }
            final Message msg = Message.obtain();
            msg.what = SHORTCUT_MANAGER_SHARE_TARGET_RESULT;
            msg.obj = new ServiceResultInfo(driList.get(i), chooserTargets, null);
            mChooserHandler.sendMessage(msg);
            resultMessageSent = true;
        }

        if (resultMessageSent) {
            final Message msg = Message.obtain();
            msg.what = SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED;
            mChooserHandler.sendMessage(msg);
        }
    }

    private ChooserTarget convertToChooserTarget(ShortcutManager.ShareShortcutInfo shareShortcut) {
        ShortcutInfo shortcutInfo = shareShortcut.getShortcutInfo();
        Bundle extras = new Bundle();
        extras.putString(Intent.EXTRA_SHORTCUT_ID, shortcutInfo.getId());
        return new ChooserTarget(
                // The name of this target.
                shortcutInfo.getShortLabel(),
                // Don't load the icon until it is selected to be shown
                null,
                // The ranking score for this target (0.0-1.0); the system will omit items with low
                // scores when there are too many Direct Share items.
                0.5f,
                // The name of the component to be launched if this target is chosen.
                shareShortcut.getTargetComponent().clone(),
                // The extra values here will be merged into the Intent when this target is chosen.
                extras);
    }

    private String convertServiceName(String packageName, String serviceName) {
        if (TextUtils.isEmpty(serviceName)) {
            return null;
        }

        final String fullName;
        if (serviceName.startsWith(".")) {
            // Relative to the app package. Prepend the app package name.
            fullName = packageName + serviceName;
        } else if (serviceName.indexOf('.') >= 0) {
            // Fully qualified package name.
            fullName = serviceName;
        } else {
            fullName = null;
        }
        return fullName;
    }

    void unbindRemainingServices() {
        if (DEBUG) {
            Log.d(TAG, "unbindRemainingServices, " + mServiceConnections.size() + " left");
        }
        for (int i = 0, N = mServiceConnections.size(); i < N; i++) {
            final ChooserTargetServiceConnection conn = mServiceConnections.get(i);
            if (DEBUG) Log.d(TAG, "unbinding " + conn);
            unbindService(conn);
            conn.destroy();
        }
        mServiceConnections.clear();
    }

    public void onSetupVoiceInteraction() {
        // Do nothing. We'll send the voice stuff ourselves.
    }

    void updateModelAndChooserCounts(TargetInfo info) {
        if (info != null) {
            if (USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS) {
                sendClickToAppPredictor(info);
            }
            final ResolveInfo ri = info.getResolveInfo();
            Intent targetIntent = getTargetIntent();
            if (ri != null && ri.activityInfo != null && targetIntent != null) {
                if (mAdapter != null) {
                    mAdapter.updateModel(info.getResolvedComponentName());
                    mAdapter.updateChooserCounts(ri.activityInfo.packageName, getUserId(),
                            targetIntent.getAction());
                }
                if (DEBUG) {
                    Log.d(TAG, "ResolveInfo Package is " + ri.activityInfo.packageName);
                    Log.d(TAG, "Action to be updated is " + targetIntent.getAction());
                }
            } else if (DEBUG) {
                Log.d(TAG, "Can not log Chooser Counts of null ResovleInfo");
            }
        }
        mIsSuccessfullySelected = true;
    }

    private void sendClickToAppPredictor(TargetInfo targetInfo) {
        if (!(targetInfo instanceof ChooserTargetInfo)) {
            return;
        }
        ChooserTarget chooserTarget = ((ChooserTargetInfo) targetInfo).getChooserTarget();
        ComponentName componentName = chooserTarget.getComponentName();
        Bundle extras = chooserTarget.getIntentExtras();
        if (extras == null) {
            return;
        }
        String shortcutId = extras.getString(Intent.EXTRA_SHORTCUT_ID);
        if (shortcutId == null) {
            return;
        }
        mAppPredictor.notifyAppTargetEvent(
                new AppTargetEvent.Builder(
                    new AppTarget(
                        new AppTargetId(shortcutId),
                        componentName.getPackageName(),
                        componentName.getClassName(),
                        getUser()),
                    AppTargetEvent.ACTION_LAUNCH
                ).setLaunchLocation(LAUNCH_LOCATON_DIRECT_SHARE)
                .build());
    }

    void onRefinementResult(TargetInfo selectedTarget, Intent matchingIntent) {
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        if (selectedTarget == null) {
            Log.e(TAG, "Refinement result intent did not match any known targets; canceling");
        } else if (!checkTargetSourceIntent(selectedTarget, matchingIntent)) {
            Log.e(TAG, "onRefinementResult: Selected target " + selectedTarget
                    + " cannot match refined source intent " + matchingIntent);
        } else {
            TargetInfo clonedTarget = selectedTarget.cloneFilledIn(matchingIntent, 0);
            if (super.onTargetSelected(clonedTarget, false)) {
                updateModelAndChooserCounts(clonedTarget);
                finish();
                return;
            }
        }
        onRefinementCanceled();
    }

    void onRefinementCanceled() {
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        finish();
    }

    boolean checkTargetSourceIntent(TargetInfo target, Intent matchingIntent) {
        final List<Intent> targetIntents = target.getAllSourceIntents();
        for (int i = 0, N = targetIntents.size(); i < N; i++) {
            final Intent targetIntent = targetIntents.get(i);
            if (targetIntent.filterEquals(matchingIntent)) {
                return true;
            }
        }
        return false;
    }

    void filterServiceTargets(String packageName, List<ChooserTarget> targets) {
        if (targets == null) {
            return;
        }

        final PackageManager pm = getPackageManager();
        for (int i = targets.size() - 1; i >= 0; i--) {
            final ChooserTarget target = targets.get(i);
            final ComponentName targetName = target.getComponentName();
            if (packageName != null && packageName.equals(targetName.getPackageName())) {
                // Anything from the original target's package is fine.
                continue;
            }

            boolean remove;
            try {
                final ActivityInfo ai = pm.getActivityInfo(targetName, 0);
                remove = !ai.exported || ai.permission != null;
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Target " + target + " returned by " + packageName
                        + " component not found");
                remove = true;
            }

            if (remove) {
                targets.remove(i);
            }
        }
    }

    protected MetricsLogger getMetricsLogger() {
        if (mMetricsLogger == null) {
            mMetricsLogger = new MetricsLogger();
        }
        return mMetricsLogger;
    }

    public class ChooserListController extends ResolverListController {
        public ChooserListController(Context context,
                PackageManager pm,
                Intent targetIntent,
                String referrerPackageName,
                int launchedFromUid) {
            super(context, pm, targetIntent, referrerPackageName, launchedFromUid);
        }

        @Override
        boolean isComponentPinned(ComponentName name) {
            return mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
        }

        @Override
        boolean isComponentFiltered(ComponentName name) {
            if (mFilteredComponentNames == null) {
                return false;
            }
            for (ComponentName filteredComponentName : mFilteredComponentNames) {
                if (name.equals(filteredComponentName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public float getScore(DisplayResolveInfo target) {
            if (target == null) {
                return CALLER_TARGET_SCORE_BOOST;
            }
            float score = super.getScore(target);
            if (target.isPinned()) {
                score += PINNED_TARGET_SCORE_BOOST;
            }
            return score;
        }
    }

    @Override
    public ResolveListAdapter createAdapter(Context context, List<Intent> payloadIntents,
            Intent[] initialIntents, List<ResolveInfo> rList, int launchedFromUid,
            boolean filterLastUsed) {
        final ChooserListAdapter adapter = new ChooserListAdapter(context, payloadIntents,
                initialIntents, rList, launchedFromUid, filterLastUsed, createListController());
        return adapter;
    }

    @VisibleForTesting
    protected ResolverListController createListController() {
        return new ChooserListController(
                this,
                mPm,
                getTargetIntent(),
                getReferrerPackageName(),
                mLaunchedFromUid);
    }

    @VisibleForTesting
    protected Bitmap loadThumbnail(Uri uri, Size size) {
        if (uri == null || size == null) {
            return null;
        }

        try {
            return ImageUtils.loadThumbnail(getContentResolver(), uri, size);
        } catch (IOException | NullPointerException | SecurityException ex) {
            Log.w(TAG, "Error loading preview thumbnail for uri: " + uri.toString(), ex);
        }
        return null;
    }

    interface ChooserTargetInfo extends TargetInfo {
        float getModifiedScore();

        ChooserTarget getChooserTarget();
    }

    /**
      * Distinguish between targets that selectable by the user, vs those that are
      * placeholders for the system while information is loading in an async manner.
      */
    abstract class NotSelectableTargetInfo implements ChooserTargetInfo {

        public Intent getResolvedIntent() {
            return null;
        }

        public ComponentName getResolvedComponentName() {
            return null;
        }

        public boolean start(Activity activity, Bundle options) {
            return false;
        }

        public boolean startAsCaller(ResolverActivity activity, Bundle options, int userId) {
            return false;
        }

        public boolean startAsUser(Activity activity, Bundle options, UserHandle user) {
            return false;
        }

        public ResolveInfo getResolveInfo() {
            return null;
        }

        public CharSequence getDisplayLabel() {
            return null;
        }

        public CharSequence getExtendedInfo() {
            return null;
        }

        public Drawable getBadgeIcon() {
            return null;
        }

        public CharSequence getBadgeContentDescription() {
            return null;
        }

        public TargetInfo cloneFilledIn(Intent fillInIntent, int flags) {
            return null;
        }

        public List<Intent> getAllSourceIntents() {
            return null;
        }

        public boolean isPinned() {
            return false;
        }

        public float getModifiedScore() {
            return 0.1f;
        }

        public ChooserTarget getChooserTarget() {
            return null;
        }
    }

    final class PlaceHolderTargetInfo extends NotSelectableTargetInfo {
        public Drawable getDisplayIcon() {
            return getDrawable(R.drawable.resolver_icon_placeholder);
        }
    }


    final class EmptyTargetInfo extends NotSelectableTargetInfo {
        public Drawable getDisplayIcon() {
            return null;
        }
    }

    final class SelectableTargetInfo implements ChooserTargetInfo {
        private final DisplayResolveInfo mSourceInfo;
        private final ResolveInfo mBackupResolveInfo;
        private final ChooserTarget mChooserTarget;
        private Drawable mBadgeIcon = null;
        private CharSequence mBadgeContentDescription;
        private Drawable mDisplayIcon;
        private final Intent mFillInIntent;
        private final int mFillInFlags;
        private final float mModifiedScore;

        SelectableTargetInfo(DisplayResolveInfo sourceInfo, ChooserTarget chooserTarget,
                float modifiedScore) {
            mSourceInfo = sourceInfo;
            mChooserTarget = chooserTarget;
            mModifiedScore = modifiedScore;
            if (sourceInfo != null) {
                final ResolveInfo ri = sourceInfo.getResolveInfo();
                if (ri != null) {
                    final ActivityInfo ai = ri.activityInfo;
                    if (ai != null && ai.applicationInfo != null) {
                        final PackageManager pm = getPackageManager();
                        mBadgeIcon = pm.getApplicationIcon(ai.applicationInfo);
                        mBadgeContentDescription = pm.getApplicationLabel(ai.applicationInfo);
                    }
                }
            }
            // TODO(b/121287224): do this in the background thread, and only for selected targets
            mDisplayIcon = getChooserTargetIconDrawable(chooserTarget);

            if (sourceInfo != null) {
                mBackupResolveInfo = null;
            } else {
                mBackupResolveInfo = getPackageManager().resolveActivity(getResolvedIntent(), 0);
            }

            mFillInIntent = null;
            mFillInFlags = 0;
        }

        private SelectableTargetInfo(SelectableTargetInfo other, Intent fillInIntent, int flags) {
            mSourceInfo = other.mSourceInfo;
            mBackupResolveInfo = other.mBackupResolveInfo;
            mChooserTarget = other.mChooserTarget;
            mBadgeIcon = other.mBadgeIcon;
            mBadgeContentDescription = other.mBadgeContentDescription;
            mDisplayIcon = other.mDisplayIcon;
            mFillInIntent = fillInIntent;
            mFillInFlags = flags;
            mModifiedScore = other.mModifiedScore;
        }

        /**
         * Since ShortcutInfos are returned by ShortcutManager, we can cache the shortcuts and skip
         * the call to LauncherApps#getShortcuts(ShortcutQuery).
         */
        // TODO(121287224): Refactor code to apply the suggestion above
        private Drawable getChooserTargetIconDrawable(ChooserTarget target) {
            final Icon icon = target.getIcon();
            if (icon != null) {
                return icon.loadDrawable(ChooserActivity.this);
            }
            if (!USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS) {
                return null;
            }

            Bundle extras = target.getIntentExtras();
            if (extras == null || !extras.containsKey(Intent.EXTRA_SHORTCUT_ID)) {
                return null;
            }
            CharSequence shortcutId = extras.getCharSequence(Intent.EXTRA_SHORTCUT_ID);
            LauncherApps launcherApps = (LauncherApps) getSystemService(
                    Context.LAUNCHER_APPS_SERVICE);
            final LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setPackage(target.getComponentName().getPackageName());
            q.setShortcutIds(Arrays.asList(shortcutId.toString()));
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
            final List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(q, getUser());
            if (shortcuts != null && shortcuts.size() > 0) {
                return launcherApps.getShortcutIconDrawable(shortcuts.get(0), 0);
            }

            return null;
        }

        public float getModifiedScore() {
            return mModifiedScore;
        }

        @Override
        public Intent getResolvedIntent() {
            if (mSourceInfo != null) {
                return mSourceInfo.getResolvedIntent();
            }

            final Intent targetIntent = new Intent(getTargetIntent());
            targetIntent.setComponent(mChooserTarget.getComponentName());
            targetIntent.putExtras(mChooserTarget.getIntentExtras());
            return targetIntent;
        }

        @Override
        public ComponentName getResolvedComponentName() {
            if (mSourceInfo != null) {
                return mSourceInfo.getResolvedComponentName();
            } else if (mBackupResolveInfo != null) {
                return new ComponentName(mBackupResolveInfo.activityInfo.packageName,
                        mBackupResolveInfo.activityInfo.name);
            }
            return null;
        }

        private Intent getBaseIntentToSend() {
            Intent result = getResolvedIntent();
            if (result == null) {
                Log.e(TAG, "ChooserTargetInfo: no base intent available to send");
            } else {
                result = new Intent(result);
                if (mFillInIntent != null) {
                    result.fillIn(mFillInIntent, mFillInFlags);
                }
                result.fillIn(mReferrerFillInIntent, 0);
            }
            return result;
        }

        @Override
        public boolean start(Activity activity, Bundle options) {
            throw new RuntimeException("ChooserTargets should be started as caller.");
        }

        @Override
        public boolean startAsCaller(ResolverActivity activity, Bundle options, int userId) {
            final Intent intent = getBaseIntentToSend();
            if (intent == null) {
                return false;
            }
            intent.setComponent(mChooserTarget.getComponentName());
            intent.putExtras(mChooserTarget.getIntentExtras());

            // Important: we will ignore the target security checks in ActivityManager
            // if and only if the ChooserTarget's target package is the same package
            // where we got the ChooserTargetService that provided it. This lets a
            // ChooserTargetService provide a non-exported or permission-guarded target
            // to the chooser for the user to pick.
            //
            // If mSourceInfo is null, we got this ChooserTarget from the caller or elsewhere
            // so we'll obey the caller's normal security checks.
            final boolean ignoreTargetSecurity = mSourceInfo != null
                    && mSourceInfo.getResolvedComponentName().getPackageName()
                    .equals(mChooserTarget.getComponentName().getPackageName());
            return activity.startAsCallerImpl(intent, options, ignoreTargetSecurity, userId);
        }

        @Override
        public boolean startAsUser(Activity activity, Bundle options, UserHandle user) {
            throw new RuntimeException("ChooserTargets should be started as caller.");
        }

        @Override
        public ResolveInfo getResolveInfo() {
            return mSourceInfo != null ? mSourceInfo.getResolveInfo() : mBackupResolveInfo;
        }

        @Override
        public CharSequence getDisplayLabel() {
            return mChooserTarget.getTitle();
        }

        @Override
        public CharSequence getExtendedInfo() {
            // ChooserTargets have badge icons, so we won't show the extended info to disambiguate.
            return null;
        }

        @Override
        public Drawable getDisplayIcon() {
            return mDisplayIcon;
        }

        @Override
        public Drawable getBadgeIcon() {
            return mBadgeIcon;
        }

        @Override
        public CharSequence getBadgeContentDescription() {
            return mBadgeContentDescription;
        }

        public ChooserTarget getChooserTarget() {
            return mChooserTarget;
        }

        @Override
        public TargetInfo cloneFilledIn(Intent fillInIntent, int flags) {
            return new SelectableTargetInfo(this, fillInIntent, flags);
        }

        @Override
        public List<Intent> getAllSourceIntents() {
            final List<Intent> results = new ArrayList<>();
            if (mSourceInfo != null) {
                // We only queried the service for the first one in our sourceinfo.
                results.add(mSourceInfo.getAllSourceIntents().get(0));
            }
            return results;
        }

        @Override
        public boolean isPinned() {
            return mSourceInfo != null ? mSourceInfo.isPinned() : false;
        }
    }

    private void handleScroll(View view, int x, int y, int oldx, int oldy) {
        if (mChooserRowAdapter != null) {
            mChooserRowAdapter.handleScroll(view, y, oldy);
        }
    }

    public class ChooserListAdapter extends ResolveListAdapter {
        public static final int TARGET_BAD = -1;
        public static final int TARGET_CALLER = 0;
        public static final int TARGET_SERVICE = 1;
        public static final int TARGET_STANDARD = 2;

        private static final int MAX_SUGGESTED_APP_TARGETS = 4;
        private static final int MAX_TARGETS_PER_SERVICE = 2;

        private static final int MAX_SERVICE_TARGETS = 8;

        // Reserve spots for incoming direct share targets by adding placeholders
        private ChooserTargetInfo mPlaceHolderTargetInfo = new PlaceHolderTargetInfo();
        private final List<ChooserTargetInfo> mServiceTargets = new ArrayList<>();
        private final List<TargetInfo> mCallerTargets = new ArrayList<>();
        private boolean mShowServiceTargets;

        private float mLateFee = 1.f;

        private boolean mTargetsNeedPruning = false;

        private final BaseChooserTargetComparator mBaseTargetComparator
                = new BaseChooserTargetComparator();

        public ChooserListAdapter(Context context, List<Intent> payloadIntents,
                Intent[] initialIntents, List<ResolveInfo> rList, int launchedFromUid,
                boolean filterLastUsed, ResolverListController resolverListController) {
            // Don't send the initial intents through the shared ResolverActivity path,
            // we want to separate them into a different section.
            super(context, payloadIntents, null, rList, launchedFromUid, filterLastUsed,
                    resolverListController);

            createPlaceHolders();

            if (initialIntents != null) {
                final PackageManager pm = getPackageManager();
                for (int i = 0; i < initialIntents.length; i++) {
                    final Intent ii = initialIntents[i];
                    if (ii == null) {
                        continue;
                    }

                    // We reimplement Intent#resolveActivityInfo here because if we have an
                    // implicit intent, we want the ResolveInfo returned by PackageManager
                    // instead of one we reconstruct ourselves. The ResolveInfo returned might
                    // have extra metadata and resolvePackageName set and we want to respect that.
                    ResolveInfo ri = null;
                    ActivityInfo ai = null;
                    final ComponentName cn = ii.getComponent();
                    if (cn != null) {
                        try {
                            ai = pm.getActivityInfo(ii.getComponent(), 0);
                            ri = new ResolveInfo();
                            ri.activityInfo = ai;
                        } catch (PackageManager.NameNotFoundException ignored) {
                            // ai will == null below
                        }
                    }
                    if (ai == null) {
                        ri = pm.resolveActivity(ii, PackageManager.MATCH_DEFAULT_ONLY);
                        ai = ri != null ? ri.activityInfo : null;
                    }
                    if (ai == null) {
                        Log.w(TAG, "No activity found for " + ii);
                        continue;
                    }
                    UserManager userManager =
                            (UserManager) getSystemService(Context.USER_SERVICE);
                    if (ii instanceof LabeledIntent) {
                        LabeledIntent li = (LabeledIntent) ii;
                        ri.resolvePackageName = li.getSourcePackage();
                        ri.labelRes = li.getLabelResource();
                        ri.nonLocalizedLabel = li.getNonLocalizedLabel();
                        ri.icon = li.getIconResource();
                        ri.iconResourceId = ri.icon;
                    }
                    if (userManager.isManagedProfile()) {
                        ri.noResourceId = true;
                        ri.icon = 0;
                    }
                    mCallerTargets.add(new DisplayResolveInfo(ii, ri,
                            ri.loadLabel(pm), null, ii));
                }
            }
        }

        private void createPlaceHolders() {
            mServiceTargets.clear();
            for (int i = 0; i < MAX_SERVICE_TARGETS; i++) {
                mServiceTargets.add(mPlaceHolderTargetInfo);
            }
        }

        @Override
        public boolean showsExtendedInfo(TargetInfo info) {
            // We have badges so we don't need this text shown.
            return false;
        }

        @Override
        public boolean isComponentPinned(ComponentName name) {
            return mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
        }

        @Override
        public View onCreateView(ViewGroup parent) {
            return mInflater.inflate(
                    com.android.internal.R.layout.resolve_grid_item, parent, false);
        }

        @Override
        public void onListRebuilt() {
            // don't support direct share on low ram devices
            if (ActivityManager.isLowRamDeviceStatic()) {
                return;
            }

            if (mServiceTargets != null) {
                if (getDisplayInfoCount() == 0) {
                    // b/109676071: When packages change, onListRebuilt() is called before
                    // ResolverActivity.mDisplayList is re-populated; pruning now would cause the
                    // list to disappear briefly, so instead we detect this case (the
                    // set of targets suddenly dropping to zero) and remember to prune later.
                    mTargetsNeedPruning = true;
                }
            }

            if (USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS) {
                if (DEBUG) {
                    Log.d(TAG, "querying direct share targets from ShortcutManager");
                }
                queryDirectShareTargets(this);
            }
            if (USE_CHOOSER_TARGET_SERVICE_FOR_DIRECT_TARGETS) {
                if (DEBUG) {
                    Log.d(TAG, "List built querying services");
                }
                queryTargetServices(this);
            }
        }

        @Override
        public boolean shouldGetResolvedFilter() {
            return true;
        }

        @Override
        public int getCount() {
            return super.getCount() + getSelectableServiceTargetCount() + getCallerTargetCount();
        }

        @Override
        public int getUnfilteredCount() {
            return super.getUnfilteredCount() + getSelectableServiceTargetCount()
                    + getCallerTargetCount();
        }

        public int getCallerTargetCount() {
            return Math.min(mCallerTargets.size(), MAX_SUGGESTED_APP_TARGETS);
        }

        /**
          * Filter out placeholders and non-selectable service targets
          */
        public int getSelectableServiceTargetCount() {
            int count = 0;
            for (ChooserTargetInfo info : mServiceTargets) {
                if (info instanceof SelectableTargetInfo) {
                    count++;
                }
            }
            return count;
        }

        public int getServiceTargetCount() {
            if (isSendAction(getTargetIntent())) {
                return Math.min(mServiceTargets.size(), MAX_SERVICE_TARGETS);
            }

            return 0;
        }

        public int getStandardTargetCount() {
            return super.getCount();
        }

        public int getPositionTargetType(int position) {
            int offset = 0;

            final int serviceTargetCount = getServiceTargetCount();
            if (position < serviceTargetCount) {
                return TARGET_SERVICE;
            }
            offset += serviceTargetCount;

            final int callerTargetCount = getCallerTargetCount();
            if (position - offset < callerTargetCount) {
                return TARGET_CALLER;
            }
            offset += callerTargetCount;

            final int standardTargetCount = super.getCount();
            if (position - offset < standardTargetCount) {
                return TARGET_STANDARD;
            }

            return TARGET_BAD;
        }

        @Override
        public TargetInfo getItem(int position) {
            return targetInfoForPosition(position, true);
        }

        @Override
        public TargetInfo targetInfoForPosition(int position, boolean filtered) {
            int offset = 0;

            final int serviceTargetCount = filtered ? getServiceTargetCount() :
                                               getSelectableServiceTargetCount();
            if (position < serviceTargetCount) {
                return mServiceTargets.get(position);
            }
            offset += serviceTargetCount;

            final int callerTargetCount = getCallerTargetCount();
            if (position - offset < callerTargetCount) {
                return mCallerTargets.get(position - offset);
            }
            offset += callerTargetCount;

            return filtered ? super.getItem(position - offset)
                    : getDisplayInfoAt(position - offset);
        }

        public void addServiceResults(DisplayResolveInfo origTarget, List<ChooserTarget> targets) {
            if (DEBUG) {
                Log.d(TAG, "addServiceResults " + origTarget + ", " + targets.size()
                        + " targets");
            }

            if (mTargetsNeedPruning && targets.size() > 0) {
                // First proper update since we got an onListRebuilt() with (transient) 0 items.
                // Clear out the target list and rebuild.
                createPlaceHolders();
                mTargetsNeedPruning = false;

                // Add back any app-supplied direct share targets that may have been
                // wiped by this clear
                if (mCallerChooserTargets != null) {
                    addServiceResults(null, Lists.newArrayList(mCallerChooserTargets));
                }
            }

            final float parentScore = getScore(origTarget);
            Collections.sort(targets, mBaseTargetComparator);
            float lastScore = 0;
            for (int i = 0, N = Math.min(targets.size(), MAX_TARGETS_PER_SERVICE); i < N; i++) {
                final ChooserTarget target = targets.get(i);
                float targetScore = target.getScore();
                targetScore *= parentScore;
                targetScore *= mLateFee;
                if (i > 0 && targetScore >= lastScore) {
                    // Apply a decay so that the top app can't crowd out everything else.
                    // This incents ChooserTargetServices to define what's truly better.
                    targetScore = lastScore * 0.95f;
                }
                insertServiceTarget(new SelectableTargetInfo(origTarget, target, targetScore));

                if (DEBUG) {
                    Log.d(TAG, " => " + target.toString() + " score=" + targetScore
                            + " base=" + target.getScore()
                            + " lastScore=" + lastScore
                            + " parentScore=" + parentScore
                            + " lateFee=" + mLateFee);
                }

                lastScore = targetScore;
            }

            mLateFee *= 0.95f;

            notifyDataSetChanged();
        }

        /**
         * Calling this marks service target loading complete, and will attempt to no longer
         * update the direct share area.
         */
        public void completeServiceTargetLoading() {
            mServiceTargets.removeIf(o -> o instanceof PlaceHolderTargetInfo);

            if (mServiceTargets.isEmpty()) {
                mServiceTargets.add(new EmptyTargetInfo());
            }
            notifyDataSetChanged();
        }

        private void insertServiceTarget(ChooserTargetInfo chooserTargetInfo) {
            // Avoid inserting any potentially late results
            if (mServiceTargets.size() == 1
                    && mServiceTargets.get(0) instanceof EmptyTargetInfo) {
                return;
            }

            final float newScore = chooserTargetInfo.getModifiedScore();
            for (int i = 0, N = mServiceTargets.size(); i < N; i++) {
                final ChooserTargetInfo serviceTarget = mServiceTargets.get(i);
                if (serviceTarget == null) {
                    mServiceTargets.set(i, chooserTargetInfo);
                    return;
                } else if (newScore > serviceTarget.getModifiedScore()) {
                    mServiceTargets.add(i, chooserTargetInfo);
                    return;
                }
            }
            mServiceTargets.add(chooserTargetInfo);
        }
    }

    static class BaseChooserTargetComparator implements Comparator<ChooserTarget> {
        @Override
        public int compare(ChooserTarget lhs, ChooserTarget rhs) {
            // Descending order
            return (int) Math.signum(rhs.getScore() - lhs.getScore());
        }
    }

    private boolean isSendAction(Intent targetIntent) {
        if (targetIntent == null) {
            return false;
        }

        String action = targetIntent.getAction();
        if (action == null) {
            return false;
        }

        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return true;
        }

        return false;
    }

    class ChooserRowAdapter extends BaseAdapter {
        private ChooserListAdapter mChooserListAdapter;
        private final LayoutInflater mLayoutInflater;
        private int mAnimationCount = 0;

        private DirectShareViewHolder mDirectShareViewHolder;

        private static final int VIEW_TYPE_DIRECT_SHARE = 0;
        private static final int VIEW_TYPE_NORMAL = 1;
        private static final int VIEW_TYPE_CONTENT_PREVIEW = 2;

        public ChooserRowAdapter(ChooserListAdapter wrappedAdapter) {
            mChooserListAdapter = wrappedAdapter;
            mLayoutInflater = LayoutInflater.from(ChooserActivity.this);

            wrappedAdapter.registerDataSetObserver(new DataSetObserver() {
                @Override
                public void onChanged() {
                    super.onChanged();
                    notifyDataSetChanged();
                }

                @Override
                public void onInvalidated() {
                    super.onInvalidated();
                    notifyDataSetInvalidated();
                }
            });
        }

        private int getMaxTargetsPerRow() {
            // this will soon hold logic for portrait/landscape
            return 4;
        }

        @Override
        public int getCount() {
            return (int) (
                    getContentPreviewRowCount()
                            + getCallerTargetRowCount()
                            + getServiceTargetRowCount()
                            + Math.ceil(
                            (float) mChooserListAdapter.getStandardTargetCount()
                                    / getMaxTargetsPerRow())
            );
        }

        public int getContentPreviewRowCount() {
            if (!isSendAction(getTargetIntent())) {
                return 0;
            }

            if (mChooserListAdapter == null || mChooserListAdapter.getCount() == 0) {
                return 0;
            }

            return 1;
        }

        public int getCallerTargetRowCount() {
            return (int) Math.ceil(
                    (float) mChooserListAdapter.getCallerTargetCount() / getMaxTargetsPerRow());
        }

        // There can be at most one row in the listview, that is internally
        // a ViewGroup with 2 rows
        public int getServiceTargetRowCount() {
            if (isSendAction(getTargetIntent())) {
                return 1;
            }
            return 0;
        }

        @Override
        public Object getItem(int position) {
            // We have nothing useful to return here.
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final RowViewHolder holder;
            int viewType = getItemViewType(position);

            if (viewType == VIEW_TYPE_CONTENT_PREVIEW) {
                return createContentPreviewView(convertView, parent);
            }

            if (convertView == null) {
                holder = createViewHolder(viewType, parent);
            } else {
                holder = (RowViewHolder) convertView.getTag();
            }

            bindViewHolder(position, holder,
                    viewType == VIEW_TYPE_DIRECT_SHARE
                            ? ChooserListAdapter.MAX_SERVICE_TARGETS : getMaxTargetsPerRow());

            return holder.getViewGroup();
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0 && getContentPreviewRowCount() == 1) {
                return VIEW_TYPE_CONTENT_PREVIEW;
            }

            final int start = getFirstRowPosition(position);
            final int startType = mChooserListAdapter.getPositionTargetType(start);

            if (startType == ChooserListAdapter.TARGET_SERVICE) {
                return VIEW_TYPE_DIRECT_SHARE;
            }

            return VIEW_TYPE_NORMAL;
        }

        @Override
        public int getViewTypeCount() {
            return 3;
        }

        private ViewGroup createContentPreviewView(View convertView, ViewGroup parent) {
            Intent targetIntent = getTargetIntent();
            int previewType = findPreferredContentPreview(targetIntent, getContentResolver());

            if (convertView == null) {
                getMetricsLogger().write(new LogMaker(MetricsEvent.ACTION_SHARE_WITH_PREVIEW)
                        .setSubtype(previewType));
            }

            return displayContentPreview(previewType, targetIntent, mLayoutInflater,
                    (ViewGroup) convertView, parent);
        }

        private RowViewHolder loadViewsIntoRow(RowViewHolder holder) {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int columnCount = holder.getColumnCount();

            for (int i = 0; i < columnCount; i++) {
                final View v = mChooserListAdapter.createView(holder.getRow(i));
                final int column = i;
                v.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startSelected(holder.getItemIndex(column), false, true);
                    }
                });
                v.setOnLongClickListener(new OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        showTargetDetails(
                                mChooserListAdapter.resolveInfoForPosition(
                                        holder.getItemIndex(column), true));
                        return true;
                    }
                });
                ViewGroup row = holder.addView(i, v);

                // Force height to be a given so we don't have visual disruption during scaling.
                LayoutParams lp = v.getLayoutParams();
                v.measure(spec, spec);
                if (lp == null) {
                    lp = new LayoutParams(LayoutParams.MATCH_PARENT, v.getMeasuredHeight());
                    row.setLayoutParams(lp);
                } else {
                    lp.height = v.getMeasuredHeight();
                }
            }

            final ViewGroup viewGroup = holder.getViewGroup();

            // Pre-measure so we can scale later.
            holder.measure();
            LayoutParams lp = viewGroup.getLayoutParams();
            if (lp == null) {
                lp = new LayoutParams(LayoutParams.MATCH_PARENT, holder.getMeasuredRowHeight());
                viewGroup.setLayoutParams(lp);
            } else {
                lp.height = holder.getMeasuredRowHeight();
            }

            viewGroup.setTag(holder);

            return holder;
        }

        RowViewHolder createViewHolder(int viewType, ViewGroup parent) {
            if (viewType == VIEW_TYPE_DIRECT_SHARE) {
                ViewGroup parentGroup = (ViewGroup) mLayoutInflater.inflate(
                        R.layout.chooser_row_direct_share, parent, false);
                ViewGroup row1 = (ViewGroup) mLayoutInflater.inflate(R.layout.chooser_row,
                        parentGroup, false);
                ViewGroup row2 = (ViewGroup) mLayoutInflater.inflate(R.layout.chooser_row,
                        parentGroup, false);
                parentGroup.addView(row1);
                parentGroup.addView(row2);

                mDirectShareViewHolder = new DirectShareViewHolder(parentGroup,
                        Lists.newArrayList(row1, row2), getMaxTargetsPerRow());
                loadViewsIntoRow(mDirectShareViewHolder);

                return mDirectShareViewHolder;
            } else {
                ViewGroup row = (ViewGroup) mLayoutInflater.inflate(R.layout.chooser_row, parent,
                        false);
                RowViewHolder holder = new SingleRowViewHolder(row, getMaxTargetsPerRow());
                loadViewsIntoRow(holder);

                return holder;
            }
        }

        void bindViewHolder(int rowPosition, RowViewHolder holder, int columnCount) {
            final int start = getFirstRowPosition(rowPosition);
            final int startType = mChooserListAdapter.getPositionTargetType(start);

            final int lastStartType = mChooserListAdapter.getPositionTargetType(
                    getFirstRowPosition(rowPosition - 1));

            final ViewGroup row = holder.getViewGroup();

            if (startType != lastStartType || rowPosition == getContentPreviewRowCount()) {
                row.setBackground(mChooserRowLayer);
                setVertPadding(row, mChooserRowServiceSpacing, 0);
            } else {
                row.setBackground(null);
                setVertPadding(row, 0, 0);
            }

            int end = start + columnCount - 1;
            while (mChooserListAdapter.getPositionTargetType(end) != startType && end >= start) {
                end--;
            }

            if (end == start && mChooserListAdapter.getItem(start) instanceof EmptyTargetInfo) {
                final TextView textView = row.findViewById(R.id.chooser_row_text_option);

                if (textView.getVisibility() != View.VISIBLE) {
                    textView.setAlpha(0.0f);
                    textView.setVisibility(View.VISIBLE);
                    textView.setText(R.string.chooser_no_direct_share_targets);

                    ValueAnimator fadeAnim = ObjectAnimator.ofFloat(textView, "alpha", 0.0f, 1.0f);
                    fadeAnim.setInterpolator(new DecelerateInterpolator(1.0f));

                    float translationInPx = getResources().getDimensionPixelSize(
                            R.dimen.chooser_row_text_option_translate);
                    textView.setTranslationY(translationInPx);
                    ValueAnimator translateAnim = ObjectAnimator.ofFloat(textView, "translationY",
                            0.0f);
                    translateAnim.setInterpolator(new DecelerateInterpolator(1.0f));

                    AnimatorSet animSet = new AnimatorSet();
                    animSet.setDuration(NO_DIRECT_SHARE_ANIM_IN_MILLIS);
                    animSet.setStartDelay(NO_DIRECT_SHARE_ANIM_IN_MILLIS);
                    animSet.playTogether(fadeAnim, translateAnim);
                    animSet.start();
                }
            }

            for (int i = 0; i < columnCount; i++) {
                final View v = holder.getView(i);
                if (start + i <= end) {
                    setCellVisibility(holder, i, View.VISIBLE);
                    holder.setItemIndex(i, start + i);
                    mChooserListAdapter.bindView(holder.getItemIndex(i), v);
                } else {
                    setCellVisibility(holder, i, View.INVISIBLE);
                }
            }
        }

        private void setCellVisibility(RowViewHolder holder, int i, int visibility) {
            final View v = holder.getView(i);
            if (visibility == View.VISIBLE) {
                holder.setViewVisibility(i, true);
                v.setVisibility(visibility);
                v.setAlpha(1.0f);
            } else if (visibility == View.INVISIBLE && holder.getViewVisibility(i)) {
                holder.setViewVisibility(i, false);

                ValueAnimator fadeAnim = ObjectAnimator.ofFloat(v, "alpha", 1.0f, 0f);
                fadeAnim.setDuration(NO_DIRECT_SHARE_ANIM_IN_MILLIS);
                fadeAnim.setInterpolator(new AccelerateInterpolator(1.0f));
                fadeAnim.addListener(new AnimatorListenerAdapter() {
                    public void onAnimationEnd(Animator animation) {
                        v.setVisibility(View.INVISIBLE);
                    }
                });
                fadeAnim.start();
            }
        }

        private void setVertPadding(ViewGroup row, int top, int bottom) {
            row.setPadding(row.getPaddingLeft(), top, row.getPaddingRight(), bottom);
        }

        int getFirstRowPosition(int row) {
            row -= getContentPreviewRowCount();

            final int serviceCount = mChooserListAdapter.getServiceTargetCount();
            final int serviceRows = (int) Math.ceil((float) serviceCount
                    / ChooserListAdapter.MAX_SERVICE_TARGETS);
            if (row < serviceRows) {
                return row * getMaxTargetsPerRow();
            }

            final int callerCount = mChooserListAdapter.getCallerTargetCount();
            final int callerRows = (int) Math.ceil((float) callerCount / getMaxTargetsPerRow());
            if (row < callerRows + serviceRows) {
                return serviceCount + (row - serviceRows) * getMaxTargetsPerRow();
            }

            return callerCount + serviceCount
                    + (row - callerRows - serviceRows) * getMaxTargetsPerRow();
        }

        public void handleScroll(View v, int y, int oldy) {
            if (mDirectShareViewHolder != null) {
                mDirectShareViewHolder.handleScroll(mAdapterView, y, oldy, getMaxTargetsPerRow());
            }
        }
    }

    abstract class RowViewHolder {
        protected int mMeasuredRowHeight;
        private int[] mItemIndices;
        protected final View[] mCells;
        private final boolean[] mCellVisibility;
        private final int mColumnCount;

        RowViewHolder(int cellCount) {
            this.mCells = new View[cellCount];
            this.mItemIndices = new int[cellCount];
            this.mCellVisibility = new boolean[cellCount];
            this.mColumnCount = cellCount;
        }

        abstract ViewGroup addView(int index, View v);

        abstract ViewGroup getViewGroup();

        abstract ViewGroup getRow(int index);

        public int getColumnCount() {
            return mColumnCount;
        }

        public void setViewVisibility(int index, boolean visibility) {
            mCellVisibility[index] = visibility;
        }

        public boolean getViewVisibility(int index) {
            return mCellVisibility[index];
        }

        public void measure() {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            getViewGroup().measure(spec, spec);
            mMeasuredRowHeight = getViewGroup().getMeasuredHeight();
        }

        public int getMeasuredRowHeight() {
            return mMeasuredRowHeight;
        }

        protected void addSpacer(ViewGroup row) {
            row.addView(new Space(ChooserActivity.this),
                    new LinearLayout.LayoutParams(0, 0, 1));
        }

        public void setItemIndex(int itemIndex, int listIndex) {
            mItemIndices[itemIndex] = listIndex;
        }

        public int getItemIndex(int itemIndex) {
            return mItemIndices[itemIndex];
        }

        public View getView(int index) {
            return mCells[index];
        }
    }

    class SingleRowViewHolder extends RowViewHolder {
        private final ViewGroup mRow;

        SingleRowViewHolder(ViewGroup row, int cellCount) {
            super(cellCount);

            this.mRow = row;
        }

        public ViewGroup getViewGroup() {
            return mRow;
        }

        public ViewGroup getRow(int index) {
            return mRow;
        }

        public ViewGroup addView(int index, View v) {
            mRow.addView(v);
            mCells[index] = v;

            if (index != (mCells.length - 1)) {
                addSpacer(mRow);
            }

            return mRow;
        }
    }

    class DirectShareViewHolder extends RowViewHolder {
        private final ViewGroup mParent;
        private final List<ViewGroup> mRows;
        private int mCellCountPerRow;

        private boolean mHideDirectShareExpansion = false;
        private int mDirectShareMinHeight = 0;
        private int mDirectShareCurrHeight = 0;
        private int mDirectShareMaxHeight = 0;

        DirectShareViewHolder(ViewGroup parent, List<ViewGroup> rows, int cellCountPerRow) {
            super(rows.size() * cellCountPerRow);

            this.mParent = parent;
            this.mRows = rows;
            this.mCellCountPerRow = cellCountPerRow;
        }

        public ViewGroup addView(int index, View v) {
            ViewGroup row = getRow(index);
            row.addView(v);
            mCells[index] = v;

            if (index % mCellCountPerRow != (mCellCountPerRow - 1)) {
                addSpacer(row);
            }

            return row;
        }

        public ViewGroup getViewGroup() {
            return mParent;
        }

        public ViewGroup getRow(int index) {
            return mRows.get(index / mCellCountPerRow);
        }

        public void measure() {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            getRow(0).measure(spec, spec);
            getRow(1).measure(spec, spec);

            // uses ChooserActiivty state variables to track height
            mDirectShareMinHeight = getRow(0).getMeasuredHeight();
            mDirectShareCurrHeight = mDirectShareCurrHeight > 0
                                         ? mDirectShareCurrHeight : mDirectShareMinHeight;
            mDirectShareMaxHeight = 2 * mDirectShareMinHeight;
        }

        public int getMeasuredRowHeight() {
            return mDirectShareCurrHeight;
        }

        public void handleScroll(AbsListView view, int y, int oldy, int maxTargetsPerRow) {
            // only expand if we have more than 4 targets, and delay that decision until
            // they start to scroll
            if (mHideDirectShareExpansion) {
                return;
            }

            if (mChooserListAdapter.getSelectableServiceTargetCount() <= maxTargetsPerRow) {
                mHideDirectShareExpansion = true;
                return;
            }

            int yDiff = (int) ((oldy - y) * 0.7f);

            int prevHeight = mDirectShareCurrHeight;
            mDirectShareCurrHeight = Math.min(mDirectShareCurrHeight + yDiff,
                    mDirectShareMaxHeight);
            mDirectShareCurrHeight = Math.max(mDirectShareCurrHeight, mDirectShareMinHeight);
            yDiff = mDirectShareCurrHeight - prevHeight;

            if (view == null || view.getChildCount() == 0) {
                return;
            }

            int index = mChooserRowAdapter.getContentPreviewRowCount();

            ViewGroup expansionGroup = (ViewGroup) view.getChildAt(index);
            int widthSpec = MeasureSpec.makeMeasureSpec(expansionGroup.getWidth(),
                    MeasureSpec.EXACTLY);
            int heightSpec = MeasureSpec.makeMeasureSpec(mDirectShareCurrHeight,
                    MeasureSpec.EXACTLY);
            expansionGroup.measure(widthSpec, heightSpec);
            expansionGroup.getLayoutParams().height = expansionGroup.getMeasuredHeight();
            expansionGroup.layout(expansionGroup.getLeft(), expansionGroup.getTop(),
                    expansionGroup.getRight(),
                    expansionGroup.getTop() + expansionGroup.getMeasuredHeight());

            // reposition list items
            int items = view.getChildCount();
            for (int i = index + 1; i < items; i++) {
                view.getChildAt(i).offsetTopAndBottom(yDiff);
            }
        }
    }

    static class ChooserTargetServiceConnection implements ServiceConnection {
        private DisplayResolveInfo mOriginalTarget;
        private ComponentName mConnectedComponent;
        private ChooserActivity mChooserActivity;
        private final Object mLock = new Object();

        private final IChooserTargetResult mChooserTargetResult = new IChooserTargetResult.Stub() {
            @Override
            public void sendResult(List<ChooserTarget> targets) throws RemoteException {
                synchronized (mLock) {
                    if (mChooserActivity == null) {
                        Log.e(TAG, "destroyed ChooserTargetServiceConnection received result from "
                                + mConnectedComponent + "; ignoring...");
                        return;
                    }
                    mChooserActivity.filterServiceTargets(
                            mOriginalTarget.getResolveInfo().activityInfo.packageName, targets);
                    final Message msg = Message.obtain();
                    msg.what = CHOOSER_TARGET_SERVICE_RESULT;
                    msg.obj = new ServiceResultInfo(mOriginalTarget, targets,
                            ChooserTargetServiceConnection.this);
                    mChooserActivity.mChooserHandler.sendMessage(msg);
                }
            }
        };

        public ChooserTargetServiceConnection(ChooserActivity chooserActivity,
                DisplayResolveInfo dri) {
            mChooserActivity = chooserActivity;
            mOriginalTarget = dri;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.d(TAG, "onServiceConnected: " + name);
            synchronized (mLock) {
                if (mChooserActivity == null) {
                    Log.e(TAG, "destroyed ChooserTargetServiceConnection got onServiceConnected");
                    return;
                }

                final IChooserTargetService icts = IChooserTargetService.Stub.asInterface(service);
                try {
                    icts.getChooserTargets(mOriginalTarget.getResolvedComponentName(),
                            mOriginalTarget.getResolveInfo().filter, mChooserTargetResult);
                } catch (RemoteException e) {
                    Log.e(TAG, "Querying ChooserTargetService " + name + " failed.", e);
                    mChooserActivity.unbindService(this);
                    mChooserActivity.mServiceConnections.remove(this);
                    destroy();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.d(TAG, "onServiceDisconnected: " + name);
            synchronized (mLock) {
                if (mChooserActivity == null) {
                    Log.e(TAG,
                            "destroyed ChooserTargetServiceConnection got onServiceDisconnected");
                    return;
                }

                mChooserActivity.unbindService(this);
                mChooserActivity.mServiceConnections.remove(this);
                if (mChooserActivity.mServiceConnections.isEmpty()) {
                    mChooserActivity.sendVoiceChoicesIfNeeded();
                }
                mConnectedComponent = null;
                destroy();
            }
        }

        public void destroy() {
            synchronized (mLock) {
                mChooserActivity = null;
                mOriginalTarget = null;
            }
        }

        @Override
        public String toString() {
            return "ChooserTargetServiceConnection{service="
                    + mConnectedComponent + ", activity="
                    + (mOriginalTarget != null
                    ? mOriginalTarget.getResolveInfo().activityInfo.toString()
                    : "<connection destroyed>") + "}";
        }
    }

    static class ServiceResultInfo {
        public final DisplayResolveInfo originalTarget;
        public final List<ChooserTarget> resultTargets;
        public final ChooserTargetServiceConnection connection;

        public ServiceResultInfo(DisplayResolveInfo ot, List<ChooserTarget> rt,
                ChooserTargetServiceConnection c) {
            originalTarget = ot;
            resultTargets = rt;
            connection = c;
        }
    }

    static class RefinementResultReceiver extends ResultReceiver {
        private ChooserActivity mChooserActivity;
        private TargetInfo mSelectedTarget;

        public RefinementResultReceiver(ChooserActivity host, TargetInfo target,
                Handler handler) {
            super(handler);
            mChooserActivity = host;
            mSelectedTarget = target;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (mChooserActivity == null) {
                Log.e(TAG, "Destroyed RefinementResultReceiver received a result");
                return;
            }
            if (resultData == null) {
                Log.e(TAG, "RefinementResultReceiver received null resultData");
                return;
            }

            switch (resultCode) {
                case RESULT_CANCELED:
                    mChooserActivity.onRefinementCanceled();
                    break;
                case RESULT_OK:
                    Parcelable intentParcelable = resultData.getParcelable(Intent.EXTRA_INTENT);
                    if (intentParcelable instanceof Intent) {
                        mChooserActivity.onRefinementResult(mSelectedTarget,
                                (Intent) intentParcelable);
                    } else {
                        Log.e(TAG, "RefinementResultReceiver received RESULT_OK but no Intent"
                                + " in resultData with key Intent.EXTRA_INTENT");
                    }
                    break;
                default:
                    Log.w(TAG, "Unknown result code " + resultCode
                            + " sent to RefinementResultReceiver");
                    break;
            }
        }

        public void destroy() {
            mChooserActivity = null;
            mSelectedTarget = null;
        }
    }

    class OffsetDataSetObserver extends DataSetObserver {
        private final AbsListView mListView;
        private int mCachedViewType = -1;
        private View mCachedView;

        public OffsetDataSetObserver(AbsListView listView) {
            mListView = listView;
        }

        @Override
        public void onChanged() {
            if (mResolverDrawerLayout == null) {
                return;
            }

            final int chooserTargetRows = mChooserRowAdapter.getServiceTargetRowCount();
            int offset = 0;
            for (int i = 0; i < chooserTargetRows; i++) {
                final int pos = mChooserRowAdapter.getContentPreviewRowCount() + i;
                final int vt = mChooserRowAdapter.getItemViewType(pos);
                if (vt != mCachedViewType) {
                    mCachedView = null;
                }
                final View v = mChooserRowAdapter.getView(pos, mCachedView, mListView);
                int height = ((RowViewHolder) (v.getTag())).getMeasuredRowHeight();

                offset += (int) (height);

                if (vt >= 0) {
                    mCachedViewType = vt;
                    mCachedView = v;
                } else {
                    mCachedViewType = -1;
                }
            }

            mResolverDrawerLayout.setCollapsibleHeightReserved(offset);
        }
    }


    /**
     * Used internally to round image corners while obeying view padding.
     */
    public static class RoundedRectImageView extends ImageView {
        private int mRadius = 0;
        private Path mPath = new Path();
        private Paint mOverlayPaint = new Paint(0);
        private Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String mExtraImageCount = null;

        public RoundedRectImageView(Context context) {
            super(context);
        }

        public RoundedRectImageView(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public RoundedRectImageView(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public RoundedRectImageView(Context context, AttributeSet attrs, int defStyleAttr,
                int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
            mRadius = context.getResources().getDimensionPixelSize(R.dimen.chooser_corner_radius);

            mOverlayPaint.setColor(0x99000000);
            mOverlayPaint.setStyle(Paint.Style.FILL);

            mTextPaint.setColor(Color.WHITE);
            mTextPaint.setTextSize(context.getResources()
                    .getDimensionPixelSize(R.dimen.chooser_preview_image_font_size));
            mTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        private void updatePath(int width, int height) {
            mPath.reset();

            int imageWidth = width - getPaddingRight();
            int imageHeight = height - getPaddingBottom();
            mPath.addRoundRect(getPaddingLeft(), getPaddingTop(), imageWidth, imageHeight, mRadius,
                    mRadius, Path.Direction.CW);
        }

        /**
          * Sets the corner radius on all corners
          *
          * param radius 0 for no radius, &gt; 0 for a visible corner radius
          */
        public void setRadius(int radius) {
            mRadius = radius;
            updatePath(getWidth(), getHeight());
        }

        /**
          * Display an overlay with extra image count on 3rd image
          */
        public void setExtraImageCount(int count) {
            if (count > 0) {
                this.mExtraImageCount = "+" + count;
            } else {
                this.mExtraImageCount = null;
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            updatePath(width, height);
        }


        @Override
        protected void onDraw(Canvas canvas) {
            if (mRadius != 0) {
                canvas.clipPath(mPath);
            }

            super.onDraw(canvas);

            if (mExtraImageCount != null) {
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mOverlayPaint);

                int xPos = canvas.getWidth() / 2;
                int yPos = (int) ((canvas.getHeight() / 2.0f)
                        - ((mTextPaint.descent() + mTextPaint.ascent()) / 2.0f));

                canvas.drawText(mExtraImageCount, xPos, yPos, mTextPaint);
            }
        }
    }
}
