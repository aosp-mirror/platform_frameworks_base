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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionManager;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
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
import android.content.pm.ApplicationInfo;
import android.content.pm.LabeledIntent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.BitmapDrawable;
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
import android.provider.DeviceConfig;
import android.provider.DocumentsContract;
import android.provider.Downloads;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.service.chooser.ChooserTarget;
import android.service.chooser.ChooserTargetService;
import android.service.chooser.IChooserTargetResult;
import android.service.chooser.IChooserTargetService;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.HashedStringCache;
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
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.internal.content.PackageMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ImageUtils;
import com.android.internal.widget.ResolverDrawerLayout;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Chooser Activity handles intent resolution specifically for sharing intents -
 * for example, those generated by @see android.content.Intent#createChooser(Intent, CharSequence).
 *
 */
public class ChooserActivity extends ResolverActivity {
    private static final String TAG = "ChooserActivity";


    /**
     * Boolean extra to change the following behavior: Normally, ChooserActivity finishes itself
     * in onStop when launched in a new task. If this extra is set to true, we do not finish
     * ourselves when onStop gets called.
     */
    public static final String EXTRA_PRIVATE_RETAIN_IN_ON_STOP
            = "com.android.internal.app.ChooserActivity.EXTRA_PRIVATE_RETAIN_IN_ON_STOP";

    private static final String PREF_NUM_SHEET_EXPANSIONS = "pref_num_sheet_expansions";

    private static final String CHIP_LABEL_METADATA_KEY = "android.service.chooser.chip_label";
    private static final String CHIP_ICON_METADATA_KEY = "android.service.chooser.chip_icon";

    private static final boolean DEBUG = false;

    /**
     * If {@link #USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS} and this is set to true,
     * {@link AppPredictionManager} will be queried for direct share targets.
     */
    // TODO(b/123089490): Replace with system flag
    private static final boolean USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS = true;
    private static final boolean USE_PREDICTION_MANAGER_FOR_SHARE_ACTIVITIES = true;
    // TODO(b/123088566) Share these in a better way.
    private static final String APP_PREDICTION_SHARE_UI_SURFACE = "share";
    public static final String LAUNCH_LOCATON_DIRECT_SHARE = "direct_share";
    private static final int APP_PREDICTION_SHARE_TARGET_QUERY_PACKAGE_LIMIT = 20;
    public static final String APP_PREDICTION_INTENT_FILTER_KEY = "intent_filter";

    private boolean mIsAppPredictorComponentAvailable;
    private AppPredictor mAppPredictor;
    private AppPredictor.Callback mAppPredictorCallback;
    private Map<ChooserTarget, AppTarget> mDirectShareAppTargetCache;

    /**
     * If set to true, use ShortcutManager to retrieve the matching direct share targets, instead of
     * binding to every ChooserTargetService implementation.
     */
    // TODO(b/121287573): Replace with a system flag (setprop?)
    private static final boolean USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS = true;
    private static final boolean USE_CHOOSER_TARGET_SERVICE_FOR_DIRECT_TARGETS = true;

    public static final int TARGET_TYPE_DEFAULT = 0;
    public static final int TARGET_TYPE_CHOOSER_TARGET = 1;
    public static final int TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER = 2;
    public static final int TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE = 3;

    @IntDef(flag = false, prefix = { "TARGET_TYPE_" }, value = {
            TARGET_TYPE_DEFAULT,
            TARGET_TYPE_CHOOSER_TARGET,
            TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER,
            TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShareTargetType {}

    /**
     * The transition time between placeholders for direct share to a message
     * indicating that non are available.
     */
    private static final int NO_DIRECT_SHARE_ANIM_IN_MILLIS = 200;

    private static final float DIRECT_SHARE_EXPANSION_RATE = 0.78f;

    // TODO(b/121287224): Re-evaluate this limit
    private static final int SHARE_TARGET_QUERY_PACKAGE_LIMIT = 20;

    private static final int QUERY_TARGET_SERVICE_LIMIT = 3;

    private static final int DEFAULT_SALT_EXPIRATION_DAYS = 7;
    private int mMaxHashSaltDays = DeviceConfig.getInt(DeviceConfig.NAMESPACE_SYSTEMUI,
            SystemUiDeviceConfigFlags.HASH_SALT_MAX_DAYS,
            DEFAULT_SALT_EXPIRATION_DAYS);

    private Bundle mReplacementExtras;
    private IntentSender mChosenComponentSender;
    private IntentSender mRefinementIntentSender;
    private RefinementResultReceiver mRefinementResultReceiver;
    private ChooserTarget[] mCallerChooserTargets;
    private ComponentName[] mFilteredComponentNames;

    private Intent mReferrerFillInIntent;

    private long mChooserShownTime;
    protected boolean mIsSuccessfullySelected;

    private long mQueriedTargetServicesTimeMs;
    private long mQueriedSharingShortcutsTimeMs;

    private ChooserListAdapter mChooserListAdapter;
    private ChooserRowAdapter mChooserRowAdapter;
    private int mChooserRowServiceSpacing;

    private int mCurrAvailableWidth = 0;

    /** {@link ChooserActivity#getBaseScore} */
    public static final float CALLER_TARGET_SCORE_BOOST = 900.f;
    /** {@link ChooserActivity#getBaseScore} */
    public static final float SHORTCUT_TARGET_SCORE_BOOST = 90.f;
    private static final String TARGET_DETAILS_FRAGMENT_TAG = "targetDetailsFragment";
    // TODO: Update to handle landscape instead of using static value
    private static final int MAX_RANKED_TARGETS = 4;

    private final List<ChooserTargetServiceConnection> mServiceConnections = new ArrayList<>();
    private final Set<ComponentName> mServicesRequested = new HashSet<>();

    private static final int MAX_LOG_RANK_POSITION = 12;

    @VisibleForTesting
    public static final int LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS = 250;

    private static final int MAX_EXTRA_INITIAL_INTENTS = 2;
    private static final int MAX_EXTRA_CHOOSER_TARGETS = 2;

    private SharedPreferences mPinnedSharedPrefs;
    private static final String PINNED_SHARED_PREFS_NAME = "chooser_pin_settings";

    private boolean mListViewDataChanged = false;

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

    // Sorted list of DisplayResolveInfos for the alphabetical app section.
    private List<ResolverActivity.DisplayResolveInfo> mSortedList = new ArrayList<>();

    private ContentPreviewCoordinator mPreviewCoord;

    private class ContentPreviewCoordinator {
        private static final int IMAGE_FADE_IN_MILLIS = 150;
        private static final int IMAGE_LOAD_TIMEOUT = 1;
        private static final int IMAGE_LOAD_INTO_VIEW = 2;

        private final int mImageLoadTimeoutMillis =
                getResources().getInteger(R.integer.config_shortAnimTime);

        private final View mParentView;
        private boolean mHideParentOnFail;
        private boolean mAtLeastOneLoaded = false;

        class LoadUriTask {
            public final Uri mUri;
            public final int mImageResourceId;
            public final int mExtraCount;
            public final Bitmap mBmp;

            LoadUriTask(int imageResourceId, Uri uri, int extraCount, Bitmap bmp) {
                this.mImageResourceId = imageResourceId;
                this.mUri = uri;
                this.mExtraCount = extraCount;
                this.mBmp = bmp;
            }
        }

        // If at least one image loads within the timeout period, allow other
        // loads to continue. Otherwise terminate and optionally hide
        // the parent area
        private final Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case IMAGE_LOAD_TIMEOUT:
                        maybeHideContentPreview();
                        break;

                    case IMAGE_LOAD_INTO_VIEW:
                        if (isFinishing()) break;

                        LoadUriTask task = (LoadUriTask) msg.obj;
                        RoundedRectImageView imageView = mParentView.findViewById(
                                task.mImageResourceId);
                        if (task.mBmp == null) {
                            imageView.setVisibility(View.GONE);
                            maybeHideContentPreview();
                            return;
                        }

                        mAtLeastOneLoaded = true;
                        imageView.setVisibility(View.VISIBLE);
                        imageView.setAlpha(0.0f);
                        imageView.setImageBitmap(task.mBmp);

                        ValueAnimator fadeAnim = ObjectAnimator.ofFloat(imageView, "alpha", 0.0f,
                                1.0f);
                        fadeAnim.setInterpolator(new DecelerateInterpolator(1.0f));
                        fadeAnim.setDuration(IMAGE_FADE_IN_MILLIS);
                        fadeAnim.start();

                        if (task.mExtraCount > 0) {
                            imageView.setExtraImageCount(task.mExtraCount);
                        }
                }
            }
        };

        ContentPreviewCoordinator(View parentView, boolean hideParentOnFail) {
            super();

            this.mParentView = parentView;
            this.mHideParentOnFail = hideParentOnFail;
        }

        private void loadUriIntoView(final int imageResourceId, final Uri uri,
                final int extraImages) {
            mHandler.sendEmptyMessageDelayed(IMAGE_LOAD_TIMEOUT, mImageLoadTimeoutMillis);

            AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                final Bitmap bmp = loadThumbnail(uri, new Size(200, 200));
                final Message msg = Message.obtain();
                msg.what = IMAGE_LOAD_INTO_VIEW;
                msg.obj = new LoadUriTask(imageResourceId, uri, extraImages, bmp);
                mHandler.sendMessage(msg);
            });
        }

        private void cancelLoads() {
            mHandler.removeMessages(IMAGE_LOAD_INTO_VIEW);
            mHandler.removeMessages(IMAGE_LOAD_TIMEOUT);
        }

        private void maybeHideContentPreview() {
            if (!mAtLeastOneLoaded && mHideParentOnFail) {
                Log.i(TAG, "Hiding image preview area. Timed out waiting for preview to load"
                        + " within " + mImageLoadTimeoutMillis + "ms.");
                collapseParentView();
                if (mChooserRowAdapter != null) {
                    mChooserRowAdapter.hideContentPreview();
                }
                mHideParentOnFail = false;
            }
        }

        private void collapseParentView() {
            // This will effectively hide the content preview row by forcing the height
            // to zero. It is faster than forcing a relayout of the listview
            final View v = mParentView;
            int widthSpec = MeasureSpec.makeMeasureSpec(v.getWidth(), MeasureSpec.EXACTLY);
            int heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY);
            v.measure(widthSpec, heightSpec);
            v.getLayoutParams().height = 0;
            v.layout(v.getLeft(), v.getTop(), v.getRight(), v.getTop());
            v.invalidate();
        }
    }

    private final ChooserHandler mChooserHandler = new ChooserHandler();

    private class ChooserHandler extends Handler {
        private static final int CHOOSER_TARGET_SERVICE_RESULT = 1;
        private static final int CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT = 2;
        private static final int CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT = 3;
        private static final int SHORTCUT_MANAGER_SHARE_TARGET_RESULT = 4;
        private static final int SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED = 5;
        private static final int LIST_VIEW_UPDATE_MESSAGE = 6;

        private static final int WATCHDOG_TIMEOUT_MAX_MILLIS = 10000;
        private static final int WATCHDOG_TIMEOUT_MIN_MILLIS = 3000;

        private boolean mMinTimeoutPassed = false;

        private void removeAllMessages() {
            removeMessages(LIST_VIEW_UPDATE_MESSAGE);
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT);
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT);
            removeMessages(CHOOSER_TARGET_SERVICE_RESULT);
            removeMessages(SHORTCUT_MANAGER_SHARE_TARGET_RESULT);
            removeMessages(SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED);
        }

        private void restartServiceRequestTimer() {
            mMinTimeoutPassed = false;
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT);
            removeMessages(CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT);

            if (DEBUG) {
                Log.d(TAG, "queryTargets setting watchdog timer for "
                        + WATCHDOG_TIMEOUT_MIN_MILLIS + "-"
                        + WATCHDOG_TIMEOUT_MAX_MILLIS + "ms");
            }

            sendEmptyMessageDelayed(CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT,
                    WATCHDOG_TIMEOUT_MIN_MILLIS);
            sendEmptyMessageDelayed(CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT,
                    WATCHDOG_TIMEOUT_MAX_MILLIS);
        }

        private void maybeStopServiceRequestTimer() {
            // Set a minimum timeout threshold, to ensure both apis, sharing shortcuts
            // and older-style direct share services, have had time to load, otherwise
            // just checking mServiceConnections could force us to end prematurely
            if (mMinTimeoutPassed && mServiceConnections.isEmpty()) {
                logDirectShareTargetReceived(
                        MetricsEvent.ACTION_DIRECT_SHARE_TARGETS_LOADED_CHOOSER_SERVICE);
                sendVoiceChoicesIfNeeded();
                mChooserListAdapter.completeServiceTargetLoading();
            }
        }

        @Override
        public void handleMessage(Message msg) {
            if (mChooserListAdapter == null || isDestroyed()) {
                return;
            }

            switch (msg.what) {
                case CHOOSER_TARGET_SERVICE_RESULT:
                    if (DEBUG) Log.d(TAG, "CHOOSER_TARGET_SERVICE_RESULT");
                    final ServiceResultInfo sri = (ServiceResultInfo) msg.obj;
                    if (!mServiceConnections.contains(sri.connection)) {
                        Log.w(TAG, "ChooserTargetServiceConnection " + sri.connection
                                + " returned after being removed from active connections."
                                + " Have you considered returning results faster?");
                        break;
                    }
                    if (sri.resultTargets != null) {
                        mChooserListAdapter.addServiceResults(sri.originalTarget,
                                sri.resultTargets, TARGET_TYPE_CHOOSER_TARGET);
                    }
                    unbindService(sri.connection);
                    sri.connection.destroy();
                    mServiceConnections.remove(sri.connection);
                    maybeStopServiceRequestTimer();
                    break;

                case CHOOSER_TARGET_SERVICE_WATCHDOG_MIN_TIMEOUT:
                    mMinTimeoutPassed = true;
                    maybeStopServiceRequestTimer();
                    break;

                case CHOOSER_TARGET_SERVICE_WATCHDOG_MAX_TIMEOUT:
                    unbindRemainingServices();
                    maybeStopServiceRequestTimer();
                    break;

                case LIST_VIEW_UPDATE_MESSAGE:
                    if (DEBUG) {
                        Log.d(TAG, "LIST_VIEW_UPDATE_MESSAGE; ");
                    }

                    mChooserListAdapter.refreshListView();
                    break;

                case SHORTCUT_MANAGER_SHARE_TARGET_RESULT:
                    if (DEBUG) Log.d(TAG, "SHORTCUT_MANAGER_SHARE_TARGET_RESULT");
                    final ServiceResultInfo resultInfo = (ServiceResultInfo) msg.obj;
                    if (resultInfo.resultTargets != null) {
                        mChooserListAdapter.addServiceResults(resultInfo.originalTarget,
                                resultInfo.resultTargets, msg.arg1);
                    }
                    break;

                case SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED:
                    logDirectShareTargetReceived(
                            MetricsEvent.ACTION_DIRECT_SHARE_TARGETS_LOADED_SHORTCUT_MANAGER);
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
        // This is the only place this value is being set. Effectively final.
        mIsAppPredictorComponentAvailable = isAppPredictionServiceAvailable();

        mIsSuccessfullySelected = false;
        Intent intent = getIntent();
        Parcelable targetParcelable = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        if (targetParcelable instanceof Uri) {
            try {
                targetParcelable = Intent.parseUri(targetParcelable.toString(),
                        Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException ex) {
                // doesn't parse as an intent; let the next test fail and error out
            }
        }

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
            int count = Math.min(pa.length, MAX_EXTRA_INITIAL_INTENTS);
            initialIntents = new Intent[count];
            for (int i = 0; i < count; i++) {
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

        mPinnedSharedPrefs = getPinnedSharedPrefs(this);

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
            int count = Math.min(pa.length, MAX_EXTRA_CHOOSER_TARGETS);
            ChooserTarget[] targets = new ChooserTarget[count];
            for (int i = 0; i < count; i++) {
                if (!(pa[i] instanceof ChooserTarget)) {
                    Log.w(TAG, "Chooser target #" + i + " not a ChooserTarget: " + pa[i]);
                    targets = null;
                    break;
                }
                targets[i] = (ChooserTarget) pa[i];
            }
            mCallerChooserTargets = targets;
        }

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

        AppPredictor appPredictor = getAppPredictorForDirectShareIfEnabled();
        if (appPredictor != null) {
            mDirectShareAppTargetCache = new HashMap<>();
            mAppPredictorCallback = resultList -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                // May be null if there are no apps to perform share/open action.
                if (mChooserListAdapter == null) {
                    return;
                }
                if (resultList.isEmpty()) {
                    // APS may be disabled, so try querying targets ourselves.
                    queryDirectShareTargets(mChooserListAdapter, true);
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
                sendShareShortcutInfoList(shareShortcutInfos, driList, resultList);
            };
            appPredictor
                .registerPredictionUpdates(this.getMainExecutor(), mAppPredictorCallback);
        }

        mChooserRowServiceSpacing = getResources()
                                        .getDimensionPixelSize(R.dimen.chooser_service_spacing);

        if (mResolverDrawerLayout != null) {
            mResolverDrawerLayout.addOnLayoutChangeListener(this::handleLayoutChange);

            // expand/shrink direct share 4 -> 8 viewgroup
            if (isSendAction(target)) {
                mResolverDrawerLayout.setOnScrollChangeListener(this::handleScroll);
            }

            final View chooserHeader = mResolverDrawerLayout.findViewById(R.id.chooser_header);
            final float defaultElevation = chooserHeader.getElevation();
            final float chooserHeaderScrollElevation =
                    getResources().getDimensionPixelSize(R.dimen.chooser_header_scroll_elevation);

            mAdapterView.setOnScrollListener(new AbsListView.OnScrollListener() {
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                        int totalItemCount) {
                    if (view.getChildCount() > 0) {
                        if (firstVisibleItem > 0 || view.getChildAt(0).getTop() < 0) {
                            chooserHeader.setElevation(chooserHeaderScrollElevation);
                            return;
                        }
                    }

                    chooserHeader.setElevation(defaultElevation);
                }
            });

            mResolverDrawerLayout.setOnCollapsedChangedListener(
                    new ResolverDrawerLayout.OnCollapsedChangedListener() {

                        // Only consider one expansion per activity creation
                        private boolean mWrittenOnce = false;

                        @Override
                        public void onCollapsedChanged(boolean isCollapsed) {
                            if (!isCollapsed && !mWrittenOnce) {
                                incrementNumSheetExpansions();
                                mWrittenOnce = true;
                            }
                        }
                    });
        }

        if (DEBUG) {
            Log.d(TAG, "System Time Cost is " + systemCost);
        }
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

    /**
     * Returns true if app prediction service is defined and the component exists on device.
     */
    @VisibleForTesting
    public boolean isAppPredictionServiceAvailable() {
        if (getPackageManager().getAppPredictionServicePackageName() == null) {
            // Default AppPredictionService is not defined.
            return false;
        }

        final String appPredictionServiceName =
                getString(R.string.config_defaultAppPredictionService);
        if (appPredictionServiceName == null) {
            return false;
        }
        final ComponentName appPredictionComponentName =
                ComponentName.unflattenFromString(appPredictionServiceName);
        if (appPredictionComponentName == null) {
            return false;
        }

        // Check if the app prediction component actually exists on the device.
        Intent intent = new Intent();
        intent.setComponent(appPredictionComponentName);
        if (getPackageManager().resolveService(intent, PackageManager.MATCH_ALL) == null) {
            Log.e(TAG, "App prediction service is defined, but does not exist: "
                    + appPredictionServiceName);
            return false;
        }
        return true;
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

    @Override
    protected PackageMonitor createPackageMonitor() {
        return new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                handlePackagesChanged();
            }
        };
    }

    /**
     * Update UI to reflect changes in data.
     */
    public void handlePackagesChanged() {
        mAdapter.handlePackagesChanged();
        bindProfileView();
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

            // Log share completion via copy
            LogMaker targetLogMaker = new LogMaker(
                    MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SYSTEM_TARGET).setSubtype(1);
            getMetricsLogger().write(targetLogMaker);

            finish();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        adjustPreviewWidth(newConfig.orientation, null);
    }

    private boolean shouldDisplayLandscape(int orientation) {
        // Sharesheet fixes the # of items per row and therefore can not correctly lay out
        // when in the restricted size of multi-window mode. In the future, would be nice
        // to use minimum dp size requirements instead
        return orientation == Configuration.ORIENTATION_LANDSCAPE && !isInMultiWindowMode();
    }

    private void adjustPreviewWidth(int orientation, View parent) {
        int width = -1;
        if (shouldDisplayLandscape(orientation)) {
            width = getResources().getDimensionPixelSize(R.dimen.chooser_preview_width);
        }

        parent = parent == null ? getWindow().getDecorView() : parent;

        updateLayoutWidth(R.id.content_preview_text_layout, width, parent);
        updateLayoutWidth(R.id.content_preview_title_layout, width, parent);
        updateLayoutWidth(R.id.content_preview_file_layout, width, parent);
    }

    private void updateLayoutWidth(int layoutResourceId, int width, View parent) {
        View view = parent.findViewById(layoutResourceId);
        if (view != null && view.getLayoutParams() != null) {
            LayoutParams params = view.getLayoutParams();
            params.width = width;
            view.setLayoutParams(params);
        }
    }

    private ComponentName getNearbySharingComponent() {
        String nearbyComponent = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.NEARBY_SHARING_COMPONENT);
        if (TextUtils.isEmpty(nearbyComponent)) {
            nearbyComponent = getString(R.string.config_defaultNearbySharingComponent);
        }
        if (TextUtils.isEmpty(nearbyComponent)) {
            return null;
        }
        return ComponentName.unflattenFromString(nearbyComponent);
    }

    private TargetInfo getNearbySharingTarget(Intent originalIntent) {
        final ComponentName cn = getNearbySharingComponent();
        if (cn == null) return null;

        final Intent resolveIntent = new Intent();
        resolveIntent.setComponent(cn);
        final ResolveInfo ri = getPackageManager().resolveActivity(
                resolveIntent, PackageManager.GET_META_DATA);
        if (ri == null || ri.activityInfo == null) {
            Log.e(TAG, "Device-specified nearby sharing component (" + cn
                    + ") not available");
            return null;
        }

        // Allow the nearby sharing component to provide a more appropriate icon and label
        // for the chip.
        CharSequence name = null;
        Drawable icon = null;
        final Bundle metaData = ri.activityInfo.metaData;
        if (metaData != null) {
            try {
                final Resources pkgRes = getPackageManager().getResourcesForActivity(cn);
                final int nameResId = metaData.getInt(CHIP_LABEL_METADATA_KEY);
                name = pkgRes.getString(nameResId);
                final int resId = metaData.getInt(CHIP_ICON_METADATA_KEY);
                icon = pkgRes.getDrawable(resId);
            } catch (Resources.NotFoundException ex) {
            } catch (NameNotFoundException ex) {
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = ri.loadLabel(getPackageManager());
        }
        if (icon == null) {
            icon = ri.loadIcon(getPackageManager());
        }

        final DisplayResolveInfo dri = new DisplayResolveInfo(
                originalIntent, ri, name, "", null);
        dri.setDisplayIcon(icon);
        return dri;
    }

    private Button createActionButton(Drawable icon, CharSequence title, View.OnClickListener r) {
        Button b = (Button) LayoutInflater.from(this).inflate(R.layout.chooser_action_button, null);
        if (icon != null) {
            final int size = getResources()
                    .getDimensionPixelSize(R.dimen.chooser_action_button_icon_size);
            icon.setBounds(0, 0, size, size);
            b.setCompoundDrawablesRelative(icon, null, null, null);
        }
        b.setText(title);
        b.setOnClickListener(r);
        return b;
    }

    private Button createCopyButton() {
        final Button b = createActionButton(
                getDrawable(R.drawable.ic_menu_copy_material),
                getString(R.string.copy), this::onCopyButtonClicked);
        b.setId(R.id.chooser_copy_button);
        return b;
    }

    private @Nullable Button createNearbyButton(Intent originalIntent) {
        final TargetInfo ti = getNearbySharingTarget(originalIntent);
        if (ti == null) return null;

        return createActionButton(
                ti.getDisplayIcon(),
                ti.getDisplayLabel(),
                (View unused) -> {
                    safelyStartActivity(ti);
                    finish();
                }
        );
    }

    private void addActionButton(ViewGroup parent, Button b) {
        if (b == null) return;
        final ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                );
        final int gap = getResources().getDimensionPixelSize(R.dimen.resolver_icon_margin) / 2;
        lp.setMarginsRelative(gap, 0, gap, 0);
        parent.addView(b, lp);
    }

    private ViewGroup displayContentPreview(@ContentPreviewType int previewType,
            Intent targetIntent, LayoutInflater layoutInflater, ViewGroup convertView,
            ViewGroup parent) {
        if (convertView != null) return convertView;

        ViewGroup layout = null;

        switch (previewType) {
            case CONTENT_PREVIEW_TEXT:
                layout = displayTextContentPreview(targetIntent, layoutInflater, parent);
                break;
            case CONTENT_PREVIEW_IMAGE:
                layout = displayImageContentPreview(targetIntent, layoutInflater, parent);
                break;
            case CONTENT_PREVIEW_FILE:
                layout = displayFileContentPreview(targetIntent, layoutInflater, parent);
                break;
            default:
                Log.e(TAG, "Unexpected content preview type: " + previewType);
        }

        if (layout != null) {
            adjustPreviewWidth(getResources().getConfiguration().orientation, layout);
        }

        return layout;
    }

    private ViewGroup displayTextContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup parent) {
        ViewGroup contentPreviewLayout = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_text, parent, false);

        final ViewGroup actionRow =
                (ViewGroup) contentPreviewLayout.findViewById(R.id.chooser_action_row);
        addActionButton(actionRow, createCopyButton());
        addActionButton(actionRow, createNearbyButton(targetIntent));

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
                mPreviewCoord = new ContentPreviewCoordinator(contentPreviewLayout, false);
                mPreviewCoord.loadUriIntoView(R.id.content_preview_thumbnail, previewThumbnail, 0);
            }
        }

        return contentPreviewLayout;
    }

    private ViewGroup displayImageContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup parent) {
        ViewGroup contentPreviewLayout = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_image, parent, false);
        mPreviewCoord = new ContentPreviewCoordinator(contentPreviewLayout, true);

        String action = targetIntent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = targetIntent.getParcelableExtra(Intent.EXTRA_STREAM);
            mPreviewCoord.loadUriIntoView(R.id.content_preview_image_1_large, uri, 0);
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

            mPreviewCoord.loadUriIntoView(R.id.content_preview_image_1_large, imageUris.get(0), 0);

            if (imageUris.size() == 2) {
                mPreviewCoord.loadUriIntoView(R.id.content_preview_image_2_large,
                        imageUris.get(1), 0);
            } else if (imageUris.size() > 2) {
                mPreviewCoord.loadUriIntoView(R.id.content_preview_image_2_small,
                        imageUris.get(1), 0);
                mPreviewCoord.loadUriIntoView(R.id.content_preview_image_3_small,
                        imageUris.get(2), imageUris.size() - 3);
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
        } catch (SecurityException | NullPointerException e) {
            logContentPreviewWarning(uri);
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

    private void logContentPreviewWarning(Uri uri) {
        // The ContentResolver already logs the exception. Log something more informative.
        Log.w(TAG, "Could not load (" + uri.toString() + ") thumbnail/name for preview. If "
                + "desired, consider using Intent#createChooser to launch the ChooserActivity, "
                + "and set your Intent's clipData and flags in accordance with that method's "
                + "documentation");
    }

    private ViewGroup displayFileContentPreview(Intent targetIntent, LayoutInflater layoutInflater,
            ViewGroup parent) {

        ViewGroup contentPreviewLayout = (ViewGroup) layoutInflater.inflate(
                R.layout.chooser_grid_preview_file, parent, false);

        // TODO(b/120417119): Disable file copy until after moving to sysui,
        // due to permissions issues
        //((ViewGroup) contentPreviewLayout.findViewById(R.id.chooser_action_row))
        //        .addView(createCopyButton());

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

                View thumbnailView = contentPreviewLayout.findViewById(
                        R.id.content_preview_file_thumbnail);
                thumbnailView.setVisibility(View.GONE);

                ImageView fileIconView = contentPreviewLayout.findViewById(
                        R.id.content_preview_file_icon);
                fileIconView.setVisibility(View.VISIBLE);
                fileIconView.setImageResource(R.drawable.ic_file_copy);
            }
        }

        return contentPreviewLayout;
    }

    private void loadFileUriIntoView(final Uri uri, final View parent) {
        FileInfo fileInfo = extractFileInfo(uri, getContentResolver());

        TextView fileNameView = parent.findViewById(R.id.content_preview_filename);
        fileNameView.setText(fileInfo.name);

        if (fileInfo.hasThumbnail) {
            mPreviewCoord = new ContentPreviewCoordinator(parent, false);
            mPreviewCoord.loadUriIntoView(R.id.content_preview_file_thumbnail, uri, 0);
        } else {
            View thumbnailView = parent.findViewById(R.id.content_preview_file_thumbnail);
            thumbnailView.setVisibility(View.GONE);

            ImageView fileIconView = parent.findViewById(R.id.content_preview_file_icon);
            fileIconView.setVisibility(View.VISIBLE);
            fileIconView.setImageResource(R.drawable.chooser_file_generic);
        }
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
                // Defaulting to file preview when there are mixed image/file types is
                // preferable, as it shows the user the correct number of items being shared
                if (findPreferredContentPreview(uri, resolver) == CONTENT_PREVIEW_FILE) {
                    return CONTENT_PREVIEW_FILE;
                }
            }

            return CONTENT_PREVIEW_IMAGE;
        }

        return CONTENT_PREVIEW_TEXT;
    }

    private int getNumSheetExpansions() {
        return getPreferences(Context.MODE_PRIVATE).getInt(PREF_NUM_SHEET_EXPANSIONS, 0);
    }

    private void incrementNumSheetExpansions() {
        getPreferences(Context.MODE_PRIVATE).edit().putInt(PREF_NUM_SHEET_EXPANSIONS,
                getNumSheetExpansions() + 1).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mRefinementResultReceiver != null) {
            mRefinementResultReceiver.destroy();
            mRefinementResultReceiver = null;
        }
        unbindRemainingServices();
        mChooserHandler.removeAllMessages();

        if (mPreviewCoord != null) mPreviewCoord.cancelLoads();

        if (mAppPredictor != null) {
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
            mChooserListAdapter.addServiceResults(null, Lists.newArrayList(mCallerChooserTargets),
                    TARGET_TYPE_DEFAULT);
        }
        mChooserRowAdapter = new ChooserRowAdapter(mChooserListAdapter);
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
        if (!super.shouldAutoLaunchSingleChoice(target)) {
            return false;
        }

        return getIntent().getBooleanExtra(Intent.EXTRA_AUTO_LAUNCH_SINGLE_CHOICE, true);
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
        TargetInfo targetInfo = mChooserListAdapter.targetInfoForPosition(which, filtered);
        if (targetInfo != null && targetInfo instanceof NotSelectableTargetInfo) {
            return;
        }

        final long selectionCost = System.currentTimeMillis() - mChooserShownTime;
        super.startSelected(which, always, filtered);

        if (mChooserListAdapter != null) {
            // Log the index of which type of target the user picked.
            // Lower values mean the ranking was better.
            int cat = 0;
            int value = which;
            int directTargetAlsoRanked = -1;
            int numCallerProvided = 0;
            HashedStringCache.HashResult directTargetHashed = null;
            switch (mChooserListAdapter.getPositionTargetType(which)) {
                case ChooserListAdapter.TARGET_SERVICE:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_SERVICE_TARGET;
                    // Log the package name + target name to answer the question if most users
                    // share to mostly the same person or to a bunch of different people.
                    ChooserTarget target =
                            mChooserListAdapter.mServiceTargets.get(value).getChooserTarget();
                    directTargetHashed = HashedStringCache.getInstance().hashString(
                            this,
                            TAG,
                            target.getComponentName().getPackageName()
                                    + target.getTitle().toString(),
                            mMaxHashSaltDays);
                    directTargetAlsoRanked = getRankedPosition((SelectableTargetInfo) targetInfo);

                    if (mCallerChooserTargets != null) {
                        numCallerProvided = mCallerChooserTargets.length;
                    }
                    break;
                case ChooserListAdapter.TARGET_CALLER:
                case ChooserListAdapter.TARGET_STANDARD:
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_APP_TARGET;
                    value -= mChooserListAdapter.getSelectableServiceTargetCount();
                    numCallerProvided = mChooserListAdapter.getCallerTargetCount();
                    break;
                case ChooserListAdapter.TARGET_STANDARD_AZ:
                    // A-Z targets are unranked standard targets; we use -1 to mark that they
                    // are from the alphabetical pool.
                    value = -1;
                    cat = MetricsEvent.ACTION_ACTIVITY_CHOOSER_PICKED_STANDARD_TARGET;
                    break;
            }

            if (cat != 0) {
                LogMaker targetLogMaker = new LogMaker(cat).setSubtype(value);
                if (directTargetHashed != null) {
                    targetLogMaker.addTaggedData(
                            MetricsEvent.FIELD_HASHED_TARGET_NAME, directTargetHashed.hashedString);
                    targetLogMaker.addTaggedData(
                                    MetricsEvent.FIELD_HASHED_TARGET_SALT_GEN,
                                    directTargetHashed.saltGeneration);
                    targetLogMaker.addTaggedData(MetricsEvent.FIELD_RANKED_POSITION,
                                    directTargetAlsoRanked);
                }
                targetLogMaker.addTaggedData(MetricsEvent.FIELD_IS_CATEGORY_USED,
                        numCallerProvided);
                getMetricsLogger().write(targetLogMaker);
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

    private int getRankedPosition(SelectableTargetInfo targetInfo) {
        String targetPackageName =
                targetInfo.getChooserTarget().getComponentName().getPackageName();
        int maxRankedResults = Math.min(mChooserListAdapter.mDisplayList.size(),
                        MAX_LOG_RANK_POSITION);

        for (int i = 0; i < maxRankedResults; i++) {
            if (mChooserListAdapter.mDisplayList.get(i)
                    .getResolveInfo().activityInfo.packageName.equals(targetPackageName)) {
                return i;
            }
        }
        return -1;
    }

    void queryTargetServices(ChooserListAdapter adapter) {
        mQueriedTargetServicesTimeMs = System.currentTimeMillis();

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

                if (mServicesRequested.contains(serviceComponent)) {
                    continue;
                }
                mServicesRequested.add(serviceComponent);

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

        mChooserHandler.restartServiceRequestTimer();
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
            Log.e(TAG, "failed to get target intent filter", e);
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

    private void queryDirectShareTargets(
                ChooserListAdapter adapter, boolean skipAppPredictionService) {
        mQueriedSharingShortcutsTimeMs = System.currentTimeMillis();
        if (!skipAppPredictionService) {
            AppPredictor appPredictor = getAppPredictorForDirectShareIfEnabled();
            if (appPredictor != null) {
                appPredictor.requestPredictionUpdate();
                return;
            }
        }
        // Default to just querying ShortcutManager if AppPredictor not present.
        final IntentFilter filter = getTargetIntentFilter();
        if (filter == null) {
            return;
        }
        final List<DisplayResolveInfo> driList = getDisplayResolveInfos(adapter);

        AsyncTask.execute(() -> {
            ShortcutManager sm = (ShortcutManager) getSystemService(Context.SHORTCUT_SERVICE);
            List<ShortcutManager.ShareShortcutInfo> resultList = sm.getShareTargets(filter);
            sendShareShortcutInfoList(resultList, driList, null);
        });
    }

    private void sendShareShortcutInfoList(
                List<ShortcutManager.ShareShortcutInfo> resultList,
                List<DisplayResolveInfo> driList,
                @Nullable List<AppTarget> appTargets) {
        if (appTargets != null && appTargets.size() != resultList.size()) {
            throw new RuntimeException("resultList and appTargets must have the same size."
                    + " resultList.size()=" + resultList.size()
                    + " appTargets.size()=" + appTargets.size());
        }

        for (int i = resultList.size() - 1; i >= 0; i--) {
            final String packageName = resultList.get(i).getTargetComponent().getPackageName();
            if (!isPackageEnabled(packageName)) {
                resultList.remove(i);
                if (appTargets != null) {
                    appTargets.remove(i);
                }
            }
        }

        // If |appTargets| is not null, results are from AppPredictionService and already sorted.
        final int shortcutType = (appTargets == null ? TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER :
                TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE);

        // Match ShareShortcutInfos with DisplayResolveInfos to be able to use the old code path
        // for direct share targets. After ShareSheet is refactored we should use the
        // ShareShortcutInfos directly.
        boolean resultMessageSent = false;
        for (int i = 0; i < driList.size(); i++) {
            List<ShortcutManager.ShareShortcutInfo> matchingShortcuts = new ArrayList<>();
            for (int j = 0; j < resultList.size(); j++) {
                if (driList.get(i).getResolvedComponentName().equals(
                            resultList.get(j).getTargetComponent())) {
                    matchingShortcuts.add(resultList.get(j));
                }
            }
            if (matchingShortcuts.isEmpty()) {
                continue;
            }
            List<ChooserTarget> chooserTargets = convertToChooserTarget(
                    matchingShortcuts, resultList, appTargets, shortcutType);

            final Message msg = Message.obtain();
            msg.what = ChooserHandler.SHORTCUT_MANAGER_SHARE_TARGET_RESULT;
            msg.obj = new ServiceResultInfo(driList.get(i), chooserTargets, null);
            msg.arg1 = shortcutType;
            mChooserHandler.sendMessage(msg);
            resultMessageSent = true;
        }

        if (resultMessageSent) {
            sendShortcutManagerShareTargetResultCompleted();
        }
    }

    private void sendShortcutManagerShareTargetResultCompleted() {
        final Message msg = Message.obtain();
        msg.what = ChooserHandler.SHORTCUT_MANAGER_SHARE_TARGET_RESULT_COMPLETED;
        mChooserHandler.sendMessage(msg);
    }

    private boolean isPackageEnabled(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        ApplicationInfo appInfo;
        try {
            appInfo = getPackageManager().getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            return false;
        }

        if (appInfo != null && appInfo.enabled
                && (appInfo.flags & ApplicationInfo.FLAG_SUSPENDED) == 0) {
            return true;
        }
        return false;
    }

    /**
     * Converts a list of ShareShortcutInfos to ChooserTargets.
     * @param matchingShortcuts List of shortcuts, all from the same package, that match the current
     *                         share intent filter.
     * @param allShortcuts List of all the shortcuts from all the packages on the device that are
     *                    returned for the current sharing action.
     * @param allAppTargets List of AppTargets. Null if the results are not from prediction service.
     * @param shortcutType One of the values TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER or
     *                    TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE
     * @return A list of ChooserTargets sorted by score in descending order.
     */
    @VisibleForTesting
    @NonNull
    public List<ChooserTarget> convertToChooserTarget(
            @NonNull List<ShortcutManager.ShareShortcutInfo> matchingShortcuts,
            @NonNull List<ShortcutManager.ShareShortcutInfo> allShortcuts,
            @Nullable List<AppTarget> allAppTargets, @ShareTargetType int shortcutType) {
        // A set of distinct scores for the matched shortcuts. We use index of a rank in the sorted
        // list instead of the actual rank value when converting a rank to a score.
        List<Integer> scoreList = new ArrayList<>();
        if (shortcutType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER) {
            for (int i = 0; i < matchingShortcuts.size(); i++) {
                int shortcutRank = matchingShortcuts.get(i).getShortcutInfo().getRank();
                if (!scoreList.contains(shortcutRank)) {
                    scoreList.add(shortcutRank);
                }
            }
            Collections.sort(scoreList);
        }

        List<ChooserTarget> chooserTargetList = new ArrayList<>(matchingShortcuts.size());
        for (int i = 0; i < matchingShortcuts.size(); i++) {
            ShortcutInfo shortcutInfo = matchingShortcuts.get(i).getShortcutInfo();
            int indexInAllShortcuts = allShortcuts.indexOf(matchingShortcuts.get(i));

            float score;
            if (shortcutType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE) {
                // Incoming results are ordered. Create a score based on index in the original list.
                score = Math.max(1.0f - (0.01f * indexInAllShortcuts), 0.0f);
            } else {
                // Create a score based on the rank of the shortcut.
                int rankIndex = scoreList.indexOf(shortcutInfo.getRank());
                score = Math.max(1.0f - (0.01f * rankIndex), 0.0f);
            }

            Bundle extras = new Bundle();
            extras.putString(Intent.EXTRA_SHORTCUT_ID, shortcutInfo.getId());
            ChooserTarget chooserTarget = new ChooserTarget(shortcutInfo.getShortLabel(),
                    null, // Icon will be loaded later if this target is selected to be shown.
                    score, matchingShortcuts.get(i).getTargetComponent().clone(), extras);

            chooserTargetList.add(chooserTarget);
            if (mDirectShareAppTargetCache != null && allAppTargets != null) {
                mDirectShareAppTargetCache.put(chooserTarget,
                        allAppTargets.get(indexInAllShortcuts));
            }
        }

        // Sort ChooserTargets by score in descending order
        Comparator<ChooserTarget> byScore =
                (ChooserTarget a, ChooserTarget b) -> -Float.compare(a.getScore(), b.getScore());
        Collections.sort(chooserTargetList, byScore);
        return chooserTargetList;
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
        mServicesRequested.clear();
        mServiceConnections.clear();
    }

    private void logDirectShareTargetReceived(int logCategory) {
        final long queryTime =
                logCategory == MetricsEvent.ACTION_DIRECT_SHARE_TARGETS_LOADED_SHORTCUT_MANAGER
                        ? mQueriedSharingShortcutsTimeMs : mQueriedTargetServicesTimeMs;
        final int apiLatency = (int) (System.currentTimeMillis() - queryTime);
        getMetricsLogger().write(new LogMaker(logCategory).setSubtype(apiLatency));
    }

    void updateModelAndChooserCounts(TargetInfo info) {
        if (info != null) {
            sendClickToAppPredictor(info);
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
        AppPredictor directShareAppPredictor = getAppPredictorForDirectShareIfEnabled();
        if (directShareAppPredictor == null) {
            return;
        }
        if (!(targetInfo instanceof ChooserTargetInfo)) {
            return;
        }
        ChooserTarget chooserTarget = ((ChooserTargetInfo) targetInfo).getChooserTarget();
        AppTarget appTarget = null;
        if (mDirectShareAppTargetCache != null) {
            appTarget = mDirectShareAppTargetCache.get(chooserTarget);
        }
        // This is a direct share click that was provided by the APS
        if (appTarget != null) {
            directShareAppPredictor.notifyAppTargetEvent(
                    new AppTargetEvent.Builder(appTarget, AppTargetEvent.ACTION_LAUNCH)
                        .setLaunchLocation(LAUNCH_LOCATON_DIRECT_SHARE)
                        .build());
        }
    }

    @Nullable
    private AppPredictor getAppPredictor() {
        if (!mIsAppPredictorComponentAvailable) {
            return null;
        }
        if (mAppPredictor == null) {
            final IntentFilter filter = getTargetIntentFilter();
            Bundle extras = new Bundle();
            extras.putParcelable(APP_PREDICTION_INTENT_FILTER_KEY, filter);
            AppPredictionContext appPredictionContext = new AppPredictionContext.Builder(this)
                .setUiSurface(APP_PREDICTION_SHARE_UI_SURFACE)
                .setPredictedTargetCount(APP_PREDICTION_SHARE_TARGET_QUERY_PACKAGE_LIMIT)
                .setExtras(extras)
                .build();
            AppPredictionManager appPredictionManager
                    = getSystemService(AppPredictionManager.class);
            mAppPredictor = appPredictionManager.createAppPredictionSession(appPredictionContext);
        }
        return mAppPredictor;
    }

    /**
     * This will return an app predictor if it is enabled for direct share sorting
     * and if one exists. Otherwise, it returns null.
     */
    @Nullable
    private AppPredictor getAppPredictorForDirectShareIfEnabled() {
        return USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS && !ActivityManager.isLowRamDeviceStatic()
                ? getAppPredictor() : null;
    }

    /**
     * This will return an app predictor if it is enabled for share activity sorting
     * and if one exists. Otherwise, it returns null.
     */
    @Nullable
    private AppPredictor getAppPredictorForShareActivitesIfEnabled() {
        return USE_PREDICTION_MANAGER_FOR_SHARE_ACTIVITIES ? getAppPredictor() : null;
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

    private void updateAlphabeticalList() {
        mSortedList.clear();
        mSortedList.addAll(getDisplayList());
        Collections.sort(mSortedList, new AzInfoComparator(ChooserActivity.this));
    }

    /**
     * Sort intents alphabetically based on display label.
     */
    class AzInfoComparator implements Comparator<ResolverActivity.DisplayResolveInfo> {
        Collator mCollator;
        AzInfoComparator(Context context) {
            mCollator = Collator.getInstance(context.getResources().getConfiguration().locale);
        }

        @Override
        public int compare(ResolverActivity.DisplayResolveInfo lhsp,
                ResolverActivity.DisplayResolveInfo rhsp) {
            return mCollator.compare(lhsp.getDisplayLabel(), rhsp.getDisplayLabel());
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
                int launchedFromUid,
                AbstractResolverComparator resolverComparator) {
            super(context, pm, targetIntent, referrerPackageName, launchedFromUid,
                    resolverComparator);
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
        public boolean isComponentPinned(ComponentName name) {
            return mPinnedSharedPrefs.getBoolean(name.flattenToString(), false);
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
        AppPredictor appPredictor = getAppPredictorForShareActivitesIfEnabled();
        AbstractResolverComparator resolverComparator;
        if (appPredictor != null) {
            resolverComparator = new AppPredictionServiceResolverComparator(this, getTargetIntent(),
                    getReferrerPackageName(), appPredictor, getUser());
        } else {
            resolverComparator =
                    new ResolverRankerServiceResolverComparator(this, getTargetIntent(),
                        getReferrerPackageName(), null);
        }

        return new ChooserListController(
                this,
                mPm,
                getTargetIntent(),
                getReferrerPackageName(),
                mLaunchedFromUid,
                resolverComparator);
    }

    @VisibleForTesting
    protected Bitmap loadThumbnail(Uri uri, Size size) {
        if (uri == null || size == null) {
            return null;
        }

        try {
            return ImageUtils.loadThumbnail(getContentResolver(), uri, size);
        } catch (IOException | NullPointerException | SecurityException ex) {
            logContentPreviewWarning(uri);
        }
        return null;
    }

    interface ChooserTargetInfo extends TargetInfo {
        float getModifiedScore();

        ChooserTarget getChooserTarget();

        /**
          * Do not label as 'equals', since this doesn't quite work
          * as intended with java 8.
          */
        default boolean isSimilar(ChooserTargetInfo other) {
            if (other == null) return false;

            ChooserTarget ct1 = getChooserTarget();
            ChooserTarget ct2 = other.getChooserTarget();

            // If either is null, there is not enough info to make an informed decision
            // about equality, so just exit
            if (ct1 == null || ct2 == null) return false;

            if (ct1.getComponentName().equals(ct2.getComponentName())
                    && TextUtils.equals(getDisplayLabel(), other.getDisplayLabel())
                    && TextUtils.equals(getExtendedInfo(), other.getExtendedInfo())) {
                return true;
            }

            return false;
        }
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

        public TargetInfo cloneFilledIn(Intent fillInIntent, int flags) {
            return null;
        }

        public List<Intent> getAllSourceIntents() {
            return null;
        }

        public float getModifiedScore() {
            return -0.1f;
        }

        public ChooserTarget getChooserTarget() {
            return null;
        }

        public boolean isSuspended() {
            return false;
        }

        public boolean isPinned() {
            return false;
        }
    }

    final class PlaceHolderTargetInfo extends NotSelectableTargetInfo {
        public Drawable getDisplayIcon() {
            AnimatedVectorDrawable avd = (AnimatedVectorDrawable)
                    getDrawable(R.drawable.chooser_direct_share_icon_placeholder);
            avd.start(); // Start animation after generation
            return avd;
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
        private final String mDisplayLabel;
        private Drawable mBadgeIcon = null;
        private CharSequence mBadgeContentDescription;
        private Drawable mDisplayIcon;
        private final Intent mFillInIntent;
        private final int mFillInFlags;
        private final float mModifiedScore;
        private boolean mIsSuspended = false;

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
                        mIsSuspended =
                                (ai.applicationInfo.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
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

            mDisplayLabel = sanitizeDisplayLabel(chooserTarget.getTitle());
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

            mDisplayLabel = sanitizeDisplayLabel(mChooserTarget.getTitle());
        }

        private String sanitizeDisplayLabel(CharSequence label) {
            SpannableStringBuilder sb = new SpannableStringBuilder(label);
            sb.clearSpans();
            return sb.toString();
        }

        public boolean isSuspended() {
            return mIsSuspended;
        }

        public boolean isPinned() {
            return mSourceInfo != null && mSourceInfo.isPinned();
        }

        /**
         * Since ShortcutInfos are returned by ShortcutManager, we can cache the shortcuts and skip
         * the call to LauncherApps#getShortcuts(ShortcutQuery).
         */
        // TODO(121287224): Refactor code to apply the suggestion above
        private Drawable getChooserTargetIconDrawable(ChooserTarget target) {
            Drawable directShareIcon = null;

            // First get the target drawable and associated activity info
            final Icon icon = target.getIcon();
            if (icon != null) {
                directShareIcon = icon.loadDrawable(ChooserActivity.this);
            } else if (USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS) {
                Bundle extras = target.getIntentExtras();
                if (extras != null && extras.containsKey(Intent.EXTRA_SHORTCUT_ID)) {
                    CharSequence shortcutId = extras.getCharSequence(Intent.EXTRA_SHORTCUT_ID);
                    LauncherApps launcherApps = (LauncherApps) getSystemService(
                            Context.LAUNCHER_APPS_SERVICE);
                    final LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                    q.setPackage(target.getComponentName().getPackageName());
                    q.setShortcutIds(Arrays.asList(shortcutId.toString()));
                    q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
                    final List<ShortcutInfo> shortcuts = launcherApps.getShortcuts(q, getUser());
                    if (shortcuts != null && shortcuts.size() > 0) {
                        directShareIcon = launcherApps.getShortcutIconDrawable(shortcuts.get(0), 0);
                    }
                }
            }

            if (directShareIcon == null) return null;

            ActivityInfo info = null;
            try {
                info = mPm.getActivityInfo(target.getComponentName(), 0);
            } catch (NameNotFoundException error) {
                Log.e(TAG, "Could not find activity associated with ChooserTarget");
            }

            if (info == null) return null;

            // Now fetch app icon and raster with no badging even in work profile
            Bitmap appIcon = makePresentationGetter(info).getIconBitmap(
                    UserHandle.getUserHandleForUid(UserHandle.myUserId()));

            // Raster target drawable with appIcon as a badge
            SimpleIconFactory sif = SimpleIconFactory.obtain(ChooserActivity.this);
            Bitmap directShareBadgedIcon = sif.createAppBadgedIconBitmap(directShareIcon, appIcon);
            sif.recycle();

            return new BitmapDrawable(getResources(), directShareBadgedIcon);
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
            return mDisplayLabel;
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
    }

    private void handleScroll(View view, int x, int y, int oldx, int oldy) {
        if (mChooserRowAdapter != null) {
            mChooserRowAdapter.handleScroll(view, y, oldy);
        }
    }

    /*
     * Need to dynamically adjust how many icons can fit per row before we add them,
     * which also means setting the correct offset to initially show the content
     * preview area + 2 rows of targets
     */
    private void handleLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        if (mChooserRowAdapter == null || mAdapterView == null) {
            return;
        }

        final int availableWidth = right - left - v.getPaddingLeft() - v.getPaddingRight();
        if (mChooserRowAdapter.consumeLayoutRequest()
                || mChooserRowAdapter.calculateChooserTargetWidth(availableWidth)
                || mAdapterView.getAdapter() == null
                || availableWidth != mCurrAvailableWidth) {
            mCurrAvailableWidth = availableWidth;
            mAdapterView.setAdapter(mChooserRowAdapter);

            getMainThreadHandler().post(() -> {
                if (mResolverDrawerLayout == null || mChooserRowAdapter == null) {
                    return;
                }

                final int bottomInset = mSystemWindowInsets != null
                                            ? mSystemWindowInsets.bottom : 0;
                int offset = bottomInset;
                int rowsToShow = mChooserRowAdapter.getContentPreviewRowCount()
                        + mChooserRowAdapter.getProfileRowCount()
                        + mChooserRowAdapter.getServiceTargetRowCount()
                        + mChooserRowAdapter.getCallerAndRankedTargetRowCount();

                // then this is most likely not a SEND_* action, so check
                // the app target count
                if (rowsToShow == 0) {
                    rowsToShow = mChooserRowAdapter.getCount();
                }

                // still zero? then use a default height and leave, which
                // can happen when there are no targets to show
                if (rowsToShow == 0) {
                    offset += getResources().getDimensionPixelSize(
                            R.dimen.chooser_max_collapsed_height);
                    mResolverDrawerLayout.setCollapsibleHeightReserved(offset);
                    return;
                }

                int directShareHeight = 0;
                rowsToShow = Math.min(4, rowsToShow);
                for (int i = 0; i < Math.min(rowsToShow, mAdapterView.getChildCount()); i++) {
                    View child = mAdapterView.getChildAt(i);
                    int height = child.getHeight();
                    offset += height;

                    if (child.getTag() != null
                            && (child.getTag() instanceof DirectShareViewHolder)) {
                        directShareHeight = height;
                    }
                }

                boolean isExpandable = getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT && !isInMultiWindowMode();
                if (directShareHeight != 0 && isSendAction(getTargetIntent()) && isExpandable) {
                    // make sure to leave room for direct share 4->8 expansion
                    int requiredExpansionHeight =
                            (int) (directShareHeight / DIRECT_SHARE_EXPANSION_RATE);
                    int topInset = mSystemWindowInsets != null ? mSystemWindowInsets.top : 0;
                    int minHeight = bottom - top - mResolverDrawerLayout.getAlwaysShowHeight()
                                        - requiredExpansionHeight - topInset - bottomInset;

                    offset = Math.min(offset, minHeight);
                }

                mResolverDrawerLayout.setCollapsibleHeightReserved(Math.min(offset, bottom - top));
            });
        }
    }

    @Override
    protected boolean shouldAddFooterView() {
        // To accommodate for window insets
        return true;
    }

    public class ChooserListAdapter extends ResolveListAdapter {
        public static final int TARGET_BAD = -1;
        public static final int TARGET_CALLER = 0;
        public static final int TARGET_SERVICE = 1;
        public static final int TARGET_STANDARD = 2;
        public static final int TARGET_STANDARD_AZ = 3;

        private static final int MAX_SUGGESTED_APP_TARGETS = 4;
        private static final int MAX_CHOOSER_TARGETS_PER_APP = 2;

        private static final int MAX_SERVICE_TARGETS = 8;

        private final int mMaxShortcutTargetsPerApp =
                getResources().getInteger(R.integer.config_maxShortcutTargetsPerApp);

        private int mNumShortcutResults = 0;

        // Reserve spots for incoming direct share targets by adding placeholders
        private ChooserTargetInfo mPlaceHolderTargetInfo = new PlaceHolderTargetInfo();
        private final List<ChooserTargetInfo> mServiceTargets = new ArrayList<>();
        private final List<TargetInfo> mCallerTargets = new ArrayList<>();

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
                    ResolveInfoPresentationGetter getter = makePresentationGetter(ri);
                    mCallerTargets.add(new DisplayResolveInfo(ii, ri,
                            getter.getLabel(), getter.getSubLabel(), ii));
                }
            }
        }

        @Override
        public void handlePackagesChanged() {
            if (DEBUG) {
                Log.d(TAG, "clearing queryTargets on package change");
            }
            createPlaceHolders();
            mServicesRequested.clear();
            notifyDataSetChanged();

            super.handlePackagesChanged();
        }

        @Override
        public void notifyDataSetChanged() {
            if (!mListViewDataChanged) {
                mChooserHandler.sendEmptyMessageDelayed(ChooserHandler.LIST_VIEW_UPDATE_MESSAGE,
                        LIST_VIEW_UPDATE_INTERVAL_IN_MILLIS);
                mListViewDataChanged = true;
            }
        }

        private void refreshListView() {
            if (mListViewDataChanged) {
                super.notifyDataSetChanged();
            }
            mListViewDataChanged = false;
        }


        private void createPlaceHolders() {
            mNumShortcutResults = 0;
            mServiceTargets.clear();
            for (int i = 0; i < MAX_SERVICE_TARGETS; i++) {
                mServiceTargets.add(mPlaceHolderTargetInfo);
            }
        }

        @Override
        public View onCreateView(ViewGroup parent) {
            return mInflater.inflate(
                    com.android.internal.R.layout.resolve_grid_item, parent, false);
        }

        @Override
        protected void onBindView(View view, TargetInfo info) {
            super.onBindView(view, info);

            // If target is loading, show a special placeholder shape in the label, make unclickable
            final ViewHolder holder = (ViewHolder) view.getTag();
            if (info instanceof PlaceHolderTargetInfo) {
                final int maxWidth = getResources().getDimensionPixelSize(
                        R.dimen.chooser_direct_share_label_placeholder_max_width);
                holder.text.setMaxWidth(maxWidth);
                holder.text.setBackground(getResources().getDrawable(
                        R.drawable.chooser_direct_share_label_placeholder, getTheme()));
                // Prevent rippling by removing background containing ripple
                holder.itemView.setBackground(null);
            } else {
                holder.text.setMaxWidth(Integer.MAX_VALUE);
                holder.text.setBackground(null);
                holder.itemView.setBackground(holder.defaultItemViewBackground);
            }
        }

        @Override
        public void onListRebuilt() {
            updateAlphabeticalList();

            // don't support direct share on low ram devices
            if (ActivityManager.isLowRamDeviceStatic()) {
                return;
            }

            if (USE_SHORTCUT_MANAGER_FOR_DIRECT_TARGETS
                        || USE_PREDICTION_MANAGER_FOR_DIRECT_TARGETS) {
                if (DEBUG) {
                    Log.d(TAG, "querying direct share targets from ShortcutManager");
                }

                queryDirectShareTargets(this, false);
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
            return getRankedTargetCount() + getAlphaTargetCount()
                    + getSelectableServiceTargetCount() + getCallerTargetCount();
        }

        @Override
        public int getUnfilteredCount() {
            int appTargets = super.getUnfilteredCount();
            if (appTargets > getMaxRankedTargets()) {
                appTargets = appTargets + getMaxRankedTargets();
            }
            return appTargets + getSelectableServiceTargetCount() + getCallerTargetCount();
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
            if (isSendAction(getTargetIntent()) && !ActivityManager.isLowRamDeviceStatic()) {
                return Math.min(mServiceTargets.size(), MAX_SERVICE_TARGETS);
            }

            return 0;
        }

        int getAlphaTargetCount() {
            int standardCount = super.getCount();
            return standardCount > getMaxRankedTargets() ? standardCount : 0;
        }

        int getRankedTargetCount() {
            int spacesAvailable = getMaxRankedTargets() - getCallerTargetCount();
            return Math.min(spacesAvailable, super.getCount());
        }

        private int getMaxRankedTargets() {
            return mChooserRowAdapter == null ? 4 : mChooserRowAdapter.getMaxTargetsPerRow();
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

            final int rankedTargetCount = getRankedTargetCount();
            if (position - offset < rankedTargetCount) {
                return TARGET_STANDARD;
            }
            offset += rankedTargetCount;

            final int standardTargetCount = getAlphaTargetCount();
            if (position - offset < standardTargetCount) {
                return TARGET_STANDARD_AZ;
            }

            return TARGET_BAD;
        }

        @Override
        public TargetInfo getItem(int position) {
            return targetInfoForPosition(position, true);
        }


        /**
         * Find target info for a given position.
         * Since ChooserActivity displays several sections of content, determine which
         * section provides this item.
         */
        @Override
        public TargetInfo targetInfoForPosition(int position, boolean filtered) {
            int offset = 0;

            // Direct share targets
            final int serviceTargetCount = filtered ? getServiceTargetCount() :
                                               getSelectableServiceTargetCount();
            if (position < serviceTargetCount) {
                return mServiceTargets.get(position);
            }
            offset += serviceTargetCount;

            // Targets provided by calling app
            final int callerTargetCount = getCallerTargetCount();
            if (position - offset < callerTargetCount) {
                return mCallerTargets.get(position - offset);
            }
            offset += callerTargetCount;

            // Ranked standard app targets
            final int rankedTargetCount = getRankedTargetCount();
            if (position - offset < rankedTargetCount) {
                return filtered ? super.getItem(position - offset)
                        : getDisplayResolveInfo(position - offset);
            }
            offset += rankedTargetCount;

            // Alphabetical complete app target list.
            if (position - offset < getAlphaTargetCount() && !mSortedList.isEmpty()) {
                return mSortedList.get(position - offset);
            }

            return null;
        }


        /**
         * Evaluate targets for inclusion in the direct share area. May not be included
         * if score is too low.
         */
        public void addServiceResults(DisplayResolveInfo origTarget, List<ChooserTarget> targets,
                @ShareTargetType int targetType) {
            if (DEBUG) {
                Log.d(TAG, "addServiceResults " + origTarget + ", " + targets.size()
                        + " targets");
            }

            if (targets.size() == 0) {
                return;
            }

            final float baseScore = getBaseScore(origTarget, targetType);
            Collections.sort(targets, mBaseTargetComparator);

            final boolean isShortcutResult =
                    (targetType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER
                            || targetType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE);
            final int maxTargets = isShortcutResult ? mMaxShortcutTargetsPerApp
                                       : MAX_CHOOSER_TARGETS_PER_APP;
            float lastScore = 0;
            boolean shouldNotify = false;
            for (int i = 0, count = Math.min(targets.size(), maxTargets); i < count; i++) {
                final ChooserTarget target = targets.get(i);
                float targetScore = target.getScore();
                targetScore *= baseScore;
                if (i > 0 && targetScore >= lastScore) {
                    // Apply a decay so that the top app can't crowd out everything else.
                    // This incents ChooserTargetServices to define what's truly better.
                    targetScore = lastScore * 0.95f;
                }
                boolean isInserted = insertServiceTarget(
                        new SelectableTargetInfo(origTarget, target, targetScore));

                if (isInserted && isShortcutResult) {
                    mNumShortcutResults++;
                }

                shouldNotify |= isInserted;

                if (DEBUG) {
                    Log.d(TAG, " => " + target.toString() + " score=" + targetScore
                            + " base=" + target.getScore()
                            + " lastScore=" + lastScore
                            + " baseScore=" + baseScore);
                }

                lastScore = targetScore;
            }

            if (shouldNotify) {
                notifyDataSetChanged();
            }
        }

        private int getNumShortcutResults() {
            return mNumShortcutResults;
        }

        /**
          * Use the scoring system along with artificial boosts to create up to 4 distinct buckets:
          * <ol>
          *   <li>App-supplied targets
          *   <li>Shortcuts ranked via App Prediction Manager
          *   <li>Shortcuts ranked via legacy heuristics
          *   <li>Legacy direct share targets
          * </ol>
          */
        public float getBaseScore(DisplayResolveInfo target, @ShareTargetType int targetType) {
            if (target == null) {
                return CALLER_TARGET_SCORE_BOOST;
            }

            if (targetType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE) {
                return SHORTCUT_TARGET_SCORE_BOOST;
            }

            float score = super.getScore(target);
            if (targetType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER) {
                return score * SHORTCUT_TARGET_SCORE_BOOST;
            }

            return score;
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

        private boolean insertServiceTarget(ChooserTargetInfo chooserTargetInfo) {
            // Avoid inserting any potentially late results
            if (mServiceTargets.size() == 1
                    && mServiceTargets.get(0) instanceof EmptyTargetInfo) {
                return false;
            }

            // Check for duplicates and abort if found
            for (ChooserTargetInfo otherTargetInfo : mServiceTargets) {
                if (chooserTargetInfo.isSimilar(otherTargetInfo)) {
                    return false;
                }
            }

            int currentSize = mServiceTargets.size();
            final float newScore = chooserTargetInfo.getModifiedScore();
            for (int i = 0; i < Math.min(currentSize, MAX_SERVICE_TARGETS); i++) {
                final ChooserTargetInfo serviceTarget = mServiceTargets.get(i);
                if (serviceTarget == null) {
                    mServiceTargets.set(i, chooserTargetInfo);
                    return true;
                } else if (newScore > serviceTarget.getModifiedScore()) {
                    mServiceTargets.add(i, chooserTargetInfo);
                    return true;
                }
            }

            if (currentSize < MAX_SERVICE_TARGETS) {
                mServiceTargets.add(chooserTargetInfo);
                return true;
            }

            return false;
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

        private DirectShareViewHolder mDirectShareViewHolder;
        private int mChooserTargetWidth = 0;
        private boolean mShowAzLabelIfPoss;

        private boolean mHideContentPreview = false;
        private boolean mLayoutRequested = false;

        private static final int VIEW_TYPE_DIRECT_SHARE = 0;
        private static final int VIEW_TYPE_NORMAL = 1;
        private static final int VIEW_TYPE_CONTENT_PREVIEW = 2;
        private static final int VIEW_TYPE_PROFILE = 3;
        private static final int VIEW_TYPE_AZ_LABEL = 4;

        private static final int MAX_TARGETS_PER_ROW_PORTRAIT = 4;
        private static final int MAX_TARGETS_PER_ROW_LANDSCAPE = 8;

        private static final int NUM_EXPANSIONS_TO_HIDE_AZ_LABEL = 20;

        public ChooserRowAdapter(ChooserListAdapter wrappedAdapter) {
            mChooserListAdapter = wrappedAdapter;
            mLayoutInflater = LayoutInflater.from(ChooserActivity.this);

            mShowAzLabelIfPoss = getNumSheetExpansions() < NUM_EXPANSIONS_TO_HIDE_AZ_LABEL;

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

        /**
         * Calculate the chooser target width to maximize space per item
         *
         * @param width The new row width to use for recalculation
         * @return true if the view width has changed
         */
        public boolean calculateChooserTargetWidth(int width) {
            if (width == 0) {
                return false;
            }

            int newWidth =  width / getMaxTargetsPerRow();
            if (newWidth != mChooserTargetWidth) {
                mChooserTargetWidth = newWidth;
                return true;
            }

            return false;
        }

        private int getMaxTargetsPerRow() {
            int maxTargets = MAX_TARGETS_PER_ROW_PORTRAIT;
            if (shouldDisplayLandscape(getResources().getConfiguration().orientation)) {
                maxTargets = MAX_TARGETS_PER_ROW_LANDSCAPE;
            }

            return maxTargets;
        }

        public void hideContentPreview() {
            mHideContentPreview = true;
            mLayoutRequested = true;
            notifyDataSetChanged();
        }

        public boolean consumeLayoutRequest() {
            boolean oldValue = mLayoutRequested;
            mLayoutRequested = false;
            return oldValue;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            int viewType = getItemViewType(position);
            if (viewType == VIEW_TYPE_CONTENT_PREVIEW || viewType == VIEW_TYPE_AZ_LABEL) {
                return false;
            }
            return true;
        }

        @Override
        public int getCount() {
            return (int) (
                    getContentPreviewRowCount()
                            + getProfileRowCount()
                            + getServiceTargetRowCount()
                            + getCallerAndRankedTargetRowCount()
                            + getAzLabelRowCount()
                            + Math.ceil(
                            (float) mChooserListAdapter.getAlphaTargetCount()
                                    / getMaxTargetsPerRow())
            );
        }

        public int getContentPreviewRowCount() {
            if (!isSendAction(getTargetIntent())) {
                return 0;
            }

            if (mHideContentPreview || mChooserListAdapter == null
                    || mChooserListAdapter.getCount() == 0) {
                return 0;
            }

            return 1;
        }

        public int getProfileRowCount() {
            return mChooserListAdapter.getOtherProfile() == null ? 0 : 1;
        }

        public int getCallerAndRankedTargetRowCount() {
            return (int) Math.ceil(
                    ((float) mChooserListAdapter.getCallerTargetCount()
                            + mChooserListAdapter.getRankedTargetCount()) / getMaxTargetsPerRow());
        }

        // There can be at most one row in the listview, that is internally
        // a ViewGroup with 2 rows
        public int getServiceTargetRowCount() {
            if (isSendAction(getTargetIntent()) && !ActivityManager.isLowRamDeviceStatic()) {
                return 1;
            }
            return 0;
        }

        public int getAzLabelRowCount() {
            // Only show a label if the a-z list is showing
            return (mShowAzLabelIfPoss && mChooserListAdapter.getAlphaTargetCount() > 0) ? 1 : 0;
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

            if (viewType == VIEW_TYPE_PROFILE) {
                return createProfileView(convertView, parent);
            }

            if (viewType == VIEW_TYPE_AZ_LABEL) {
                return createAzLabelView(parent);
            }

            if (convertView == null) {
                holder = createViewHolder(viewType, parent);
            } else {
                holder = (RowViewHolder) convertView.getTag();
            }

            bindViewHolder(position, holder);

            return holder.getViewGroup();
        }

        @Override
        public int getItemViewType(int position) {
            int count;

            int countSum = (count = getContentPreviewRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_CONTENT_PREVIEW;

            countSum += (count = getProfileRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_PROFILE;

            countSum += (count = getServiceTargetRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_DIRECT_SHARE;

            countSum += (count = getCallerAndRankedTargetRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_NORMAL;

            countSum += (count = getAzLabelRowCount());
            if (count > 0 && position < countSum) return VIEW_TYPE_AZ_LABEL;

            return VIEW_TYPE_NORMAL;
        }

        @Override
        public int getViewTypeCount() {
            return 5;
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

        private View createProfileView(View convertView, ViewGroup parent) {
            View profileRow = convertView != null ? convertView : mLayoutInflater.inflate(
                    R.layout.chooser_profile_row, parent, false);
            profileRow.setBackground(
                    getResources().getDrawable(R.drawable.chooser_row_layer_list, null));
            mProfileView = profileRow.findViewById(R.id.profile_button);
            mProfileView.setOnClickListener(ChooserActivity.this::onProfileClick);
            bindProfileView();
            return profileRow;
        }

        private View createAzLabelView(ViewGroup parent) {
            return mLayoutInflater.inflate(R.layout.chooser_az_label_row, parent, false);
        }

        private RowViewHolder loadViewsIntoRow(RowViewHolder holder) {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            final int exactSpec = MeasureSpec.makeMeasureSpec(mChooserTargetWidth,
                    MeasureSpec.EXACTLY);
            int columnCount = holder.getColumnCount();

            final boolean isDirectShare = holder instanceof DirectShareViewHolder;

            for (int i = 0; i < columnCount; i++) {
                final View v = mChooserListAdapter.createView(holder.getRowByIndex(i));
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

                // Force Direct Share to be 2 lines and auto-wrap to second line via hoz scroll =
                // false. TextView#setHorizontallyScrolling must be reset after #setLines. Must be
                // done before measuring.
                if (isDirectShare) {
                    final ViewHolder vh = (ViewHolder) v.getTag();
                    vh.text.setLines(2);
                    vh.text.setHorizontallyScrolling(false);
                    vh.text2.setVisibility(View.GONE);
                }

                // Force height to be a given so we don't have visual disruption during scaling.
                v.measure(exactSpec, spec);
                setViewBounds(v, v.getMeasuredWidth(), v.getMeasuredHeight());
            }

            final ViewGroup viewGroup = holder.getViewGroup();

            // Pre-measure and fix height so we can scale later.
            holder.measure();
            setViewBounds(viewGroup, LayoutParams.MATCH_PARENT, holder.getMeasuredRowHeight());

            if (isDirectShare) {
                DirectShareViewHolder dsvh = (DirectShareViewHolder) holder;
                setViewBounds(dsvh.getRow(0), LayoutParams.MATCH_PARENT, dsvh.getMinRowHeight());
                setViewBounds(dsvh.getRow(1), LayoutParams.MATCH_PARENT, dsvh.getMinRowHeight());
            }

            viewGroup.setTag(holder);

            return holder;
        }

        private void setViewBounds(View view, int widthPx, int heightPx) {
            LayoutParams lp = view.getLayoutParams();
            if (lp == null) {
                lp = new LayoutParams(widthPx, heightPx);
                view.setLayoutParams(lp);
            } else {
                lp.height = heightPx;
                lp.width = widthPx;
            }
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

        /**
         * Need to merge CALLER + ranked STANDARD into a single row and prevent a separator from
         * showing on top of the AZ list if the AZ label is visible. All other types are placed into
         * their own row as determined by their target type, and dividers are added in the list to
         * separate each type.
         */
        int getRowType(int rowPosition) {
            // Merge caller and ranked standard into a single row
            int positionType = mChooserListAdapter.getPositionTargetType(rowPosition);
            if (positionType == ChooserListAdapter.TARGET_CALLER) {
                return ChooserListAdapter.TARGET_STANDARD;
            }

            // If an the A-Z label is shown, prevent a separator from appearing by making the A-Z
            // row type the same as the suggestion row type
            if (getAzLabelRowCount() > 0 && positionType == ChooserListAdapter.TARGET_STANDARD_AZ) {
                return ChooserListAdapter.TARGET_STANDARD;
            }

            return positionType;
        }

        void bindViewHolder(int rowPosition, RowViewHolder holder) {
            final int start = getFirstRowPosition(rowPosition);
            final int startType = getRowType(start);
            final int lastStartType = getRowType(getFirstRowPosition(rowPosition - 1));

            final ViewGroup row = holder.getViewGroup();

            if (startType != lastStartType
                    || rowPosition == getContentPreviewRowCount() + getProfileRowCount()) {
                row.setForeground(
                        getResources().getDrawable(R.drawable.chooser_row_layer_list, null));
            } else {
                row.setForeground(null);
            }

            int columnCount = holder.getColumnCount();
            int end = start + columnCount - 1;
            while (getRowType(end) != startType && end >= start) {
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
                    holder.setViewVisibility(i, View.VISIBLE);
                    holder.setItemIndex(i, start + i);
                    mChooserListAdapter.bindView(holder.getItemIndex(i), v);
                } else {
                    holder.setViewVisibility(i, View.INVISIBLE);
                }
            }
        }

        int getFirstRowPosition(int row) {
            row -= getContentPreviewRowCount() + getProfileRowCount();

            final int serviceCount = mChooserListAdapter.getServiceTargetCount();
            final int serviceRows = (int) Math.ceil((float) serviceCount
                    / ChooserListAdapter.MAX_SERVICE_TARGETS);
            if (row < serviceRows) {
                return row * getMaxTargetsPerRow();
            }

            final int callerAndRankedCount = mChooserListAdapter.getCallerTargetCount()
                                                 + mChooserListAdapter.getRankedTargetCount();
            final int callerAndRankedRows = getCallerAndRankedTargetRowCount();
            if (row < callerAndRankedRows + serviceRows) {
                return serviceCount + (row - serviceRows) * getMaxTargetsPerRow();
            }

            row -= getAzLabelRowCount();

            return callerAndRankedCount + serviceCount
                    + (row - callerAndRankedRows - serviceRows) * getMaxTargetsPerRow();
        }

        public void handleScroll(View v, int y, int oldy) {
            // Only expand direct share area if there is a minimum number of shortcuts,
            // which will help reduce the amount of visible shuffling due to older-style
            // direct share targets.
            int orientation = getResources().getConfiguration().orientation;
            boolean canExpandDirectShare =
                    mChooserListAdapter.getNumShortcutResults() > getMaxTargetsPerRow()
                    && orientation == Configuration.ORIENTATION_PORTRAIT
                    && !isInMultiWindowMode();

            if (mDirectShareViewHolder != null && canExpandDirectShare) {
                mDirectShareViewHolder.handleScroll(mAdapterView, y, oldy, getMaxTargetsPerRow());
            }
        }
    }

    abstract class RowViewHolder {
        protected int mMeasuredRowHeight;
        private int[] mItemIndices;
        protected final View[] mCells;
        private final int mColumnCount;

        RowViewHolder(int cellCount) {
            this.mCells = new View[cellCount];
            this.mItemIndices = new int[cellCount];
            this.mColumnCount = cellCount;
        }

        abstract ViewGroup addView(int index, View v);

        abstract ViewGroup getViewGroup();

        abstract ViewGroup getRowByIndex(int index);

        abstract ViewGroup getRow(int rowNumber);

        abstract void setViewVisibility(int i, int visibility);

        public int getColumnCount() {
            return mColumnCount;
        }

        public void measure() {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            getViewGroup().measure(spec, spec);
            mMeasuredRowHeight = getViewGroup().getMeasuredHeight();
        }

        public int getMeasuredRowHeight() {
            return mMeasuredRowHeight;
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

        public ViewGroup getRowByIndex(int index) {
            return mRow;
        }

        public ViewGroup getRow(int rowNumber) {
            if (rowNumber == 0) return mRow;
            return null;
        }

        public ViewGroup addView(int index, View v) {
            mRow.addView(v);
            mCells[index] = v;

            return mRow;
        }

        public void setViewVisibility(int i, int visibility) {
            getView(i).setVisibility(visibility);
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

        private final boolean[] mCellVisibility;

        DirectShareViewHolder(ViewGroup parent, List<ViewGroup> rows, int cellCountPerRow) {
            super(rows.size() * cellCountPerRow);

            this.mParent = parent;
            this.mRows = rows;
            this.mCellCountPerRow = cellCountPerRow;
            this.mCellVisibility = new boolean[rows.size() * cellCountPerRow];
        }

        public ViewGroup addView(int index, View v) {
            ViewGroup row = getRowByIndex(index);
            row.addView(v);
            mCells[index] = v;

            return row;
        }

        public ViewGroup getViewGroup() {
            return mParent;
        }

        public ViewGroup getRowByIndex(int index) {
            return mRows.get(index / mCellCountPerRow);
        }

        public ViewGroup getRow(int rowNumber) {
            return mRows.get(rowNumber);
        }

        public void measure() {
            final int spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            getRow(0).measure(spec, spec);
            getRow(1).measure(spec, spec);

            mDirectShareMinHeight = getRow(0).getMeasuredHeight();
            mDirectShareCurrHeight = mDirectShareCurrHeight > 0
                                         ? mDirectShareCurrHeight : mDirectShareMinHeight;
            mDirectShareMaxHeight = 2 * mDirectShareMinHeight;
        }

        public int getMeasuredRowHeight() {
            return mDirectShareCurrHeight;
        }

        public int getMinRowHeight() {
            return mDirectShareMinHeight;
        }

        public void setViewVisibility(int i, int visibility) {
            final View v = getView(i);
            if (visibility == View.VISIBLE) {
                mCellVisibility[i] = true;
                v.setVisibility(visibility);
                v.setAlpha(1.0f);
            } else if (visibility == View.INVISIBLE && mCellVisibility[i]) {
                mCellVisibility[i] = false;

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

        public void handleScroll(AbsListView view, int y, int oldy, int maxTargetsPerRow) {
            // only exit early if fully collapsed, otherwise onListRebuilt() with shifting
            // targets can lock us into an expanded mode
            boolean notExpanded = mDirectShareCurrHeight == mDirectShareMinHeight;
            if (notExpanded) {
                if (mHideDirectShareExpansion) {
                    return;
                }

                // only expand if we have more than maxTargetsPerRow, and delay that decision
                // until they start to scroll
                if (mChooserListAdapter.getSelectableServiceTargetCount() <= maxTargetsPerRow) {
                    mHideDirectShareExpansion = true;
                    return;
                }
            }

            int yDiff = (int) ((oldy - y) * DIRECT_SHARE_EXPANSION_RATE);

            int prevHeight = mDirectShareCurrHeight;
            int newHeight = Math.min(prevHeight + yDiff, mDirectShareMaxHeight);
            newHeight = Math.max(newHeight, mDirectShareMinHeight);
            yDiff = newHeight - prevHeight;

            if (view == null || view.getChildCount() == 0 || yDiff == 0) {
                return;
            }

            // locate the item to expand, and offset the rows below that one
            boolean foundExpansion = false;
            for (int i = 0; i < view.getChildCount(); i++) {
                View child = view.getChildAt(i);

                if (foundExpansion) {
                    child.offsetTopAndBottom(yDiff);
                } else {
                    if (child.getTag() != null && child.getTag() instanceof DirectShareViewHolder) {
                        int widthSpec = MeasureSpec.makeMeasureSpec(child.getWidth(),
                                MeasureSpec.EXACTLY);
                        int heightSpec = MeasureSpec.makeMeasureSpec(newHeight,
                                MeasureSpec.EXACTLY);
                        child.measure(widthSpec, heightSpec);
                        child.getLayoutParams().height = child.getMeasuredHeight();
                        child.layout(child.getLeft(), child.getTop(), child.getRight(),
                                child.getTop() + child.getMeasuredHeight());

                        foundExpansion = true;
                    }
                }
            }

            if (foundExpansion) {
                mDirectShareCurrHeight = newHeight;
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
                    msg.what = ChooserHandler.CHOOSER_TARGET_SERVICE_RESULT;
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

    /**
     * Used internally to round image corners while obeying view padding.
     */
    public static class RoundedRectImageView extends ImageView {
        private int mRadius = 0;
        private Path mPath = new Path();
        private Paint mOverlayPaint = new Paint(0);
        private Paint mRoundRectPaint = new Paint(0);
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

            mRoundRectPaint.setColor(context.getResources().getColor(R.color.chooser_row_divider));
            mRoundRectPaint.setStyle(Paint.Style.STROKE);
            mRoundRectPaint.setStrokeWidth(context.getResources()
                    .getDimensionPixelSize(R.dimen.chooser_preview_image_border));

            mTextPaint.setColor(Color.WHITE);
            mTextPaint.setTextSize(context.getResources()
                    .getDimensionPixelSize(R.dimen.chooser_preview_image_font_size));
            mTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        private void updatePath(int width, int height) {
            mPath.reset();

            int imageWidth = width - getPaddingRight() - getPaddingLeft();
            int imageHeight = height - getPaddingBottom() - getPaddingTop();
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

            int x = getPaddingLeft();
            int y = getPaddingRight();
            int width = getWidth() - getPaddingRight() - getPaddingLeft();
            int height = getHeight() - getPaddingBottom() - getPaddingTop();
            if (mExtraImageCount != null) {
                canvas.drawRect(x, y, width, height, mOverlayPaint);

                int xPos = canvas.getWidth() / 2;
                int yPos = (int) ((canvas.getHeight() / 2.0f)
                        - ((mTextPaint.descent() + mTextPaint.ascent()) / 2.0f));

                canvas.drawText(mExtraImageCount, xPos, yPos, mTextPaint);
            }

            canvas.drawRoundRect(x, y, width, height, mRadius, mRadius, mRoundRectPaint);
        }
    }
}
