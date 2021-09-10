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

package android.view.translation;

import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_FINISHED;
import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_PAUSED;
import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_RESUMED;
import static android.view.translation.UiTranslationManager.STATE_UI_TRANSLATION_STARTED;

import android.annotation.NonNull;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.app.assist.ActivityId;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;
import android.view.autofill.AutofillId;
import android.view.translation.UiTranslationManager.UiTranslationState;
import android.widget.TextView;
import android.widget.TextViewTranslationCallback;

import com.android.internal.util.function.pooled.PooledLambda;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A controller to manage the ui translation requests for the {@link Activity}.
 *
 * @hide
 */
public class UiTranslationController {

    public static final boolean DEBUG = Log.isLoggable(UiTranslationManager.LOG_TAG, Log.DEBUG);

    private static final String TAG = "UiTranslationController";
    @NonNull
    private final Activity mActivity;
    @NonNull
    private final Context mContext;
    @NonNull
    private final Object mLock = new Object();

    // Each Translator is distinguished by sourceSpec and desSepc.
    @NonNull
    private final ArrayMap<Pair<TranslationSpec, TranslationSpec>, Translator> mTranslators;
    @NonNull
    private final ArrayMap<AutofillId, WeakReference<View>> mViews;
    /**
     * Views for which {@link UiTranslationSpec#shouldPadContentForCompat()} is true.
     */
    @NonNull
    private final ArraySet<AutofillId> mViewsToPadContent;
    @NonNull
    private final HandlerThread mWorkerThread;
    @NonNull
    private final Handler mWorkerHandler;
    private int mCurrentState;
    @NonNull
    private ArraySet<AutofillId> mLastRequestAutofillIds;

    public UiTranslationController(Activity activity, Context context) {
        mActivity = activity;
        mContext = context;
        mViews = new ArrayMap<>();
        mTranslators = new ArrayMap<>();
        mViewsToPadContent = new ArraySet<>();

        mWorkerThread =
                new HandlerThread("UiTranslationController_" + mActivity.getComponentName(),
                        Process.THREAD_PRIORITY_FOREGROUND);
        mWorkerThread.start();
        mWorkerHandler = mWorkerThread.getThreadHandler();
    }

    /**
     * Update the Ui translation state.
     */
    public void updateUiTranslationState(@UiTranslationState int state, TranslationSpec sourceSpec,
            TranslationSpec targetSpec, List<AutofillId> views,
            UiTranslationSpec uiTranslationSpec) {
        if (mActivity.isDestroyed()) {
            Log.i(TAG, "Cannot update " + stateToString(state) + " for destroyed " + mActivity);
            return;
        }
        Log.i(TAG, "updateUiTranslationState state: " + stateToString(state)
                + (DEBUG ? (", views: " + views + ", spec: " + uiTranslationSpec) : ""));
        synchronized (mLock) {
            mCurrentState = state;
            if (views != null) {
                setLastRequestAutofillIdsLocked(views);
            }
        }
        switch (state) {
            case STATE_UI_TRANSLATION_STARTED:
                if (uiTranslationSpec != null && uiTranslationSpec.shouldPadContentForCompat()) {
                    synchronized (mLock) {
                        mViewsToPadContent.addAll(views);
                        // TODO: Cleanup disappeared views from mViews and mViewsToPadContent at
                        //  some appropriate place.
                    }
                }
                final Pair<TranslationSpec, TranslationSpec> specs =
                        new Pair<>(sourceSpec, targetSpec);
                if (!mTranslators.containsKey(specs)) {
                    mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                            UiTranslationController::createTranslatorAndStart,
                            UiTranslationController.this, sourceSpec, targetSpec, views));
                } else {
                    onUiTranslationStarted(mTranslators.get(specs), views);
                }
                break;
            case STATE_UI_TRANSLATION_PAUSED:
                runForEachView((view, callback) -> callback.onHideTranslation(view));
                break;
            case STATE_UI_TRANSLATION_RESUMED:
                runForEachView((view, callback) -> callback.onShowTranslation(view));
                break;
            case STATE_UI_TRANSLATION_FINISHED:
                destroyTranslators();
                runForEachView((view, callback) -> {
                    callback.onClearTranslation(view);
                    view.clearViewTranslationResponse();
                    if (view.hasTranslationTransientState()) {
                        view.setHasTransientState(false);
                        view.setHasTranslationTransientState(false);
                    }
                });
                notifyTranslationFinished(/* activityDestroyed= */ false);
                synchronized (mLock) {
                    mViews.clear();
                }
                break;
            default:
                Log.w(TAG, "onAutoTranslationStateChange(): unknown state: " + state);
        }
    }

    /**
     * Called when the Activity is destroyed.
     */
    public void onActivityDestroyed() {
        synchronized (mLock) {
            if (DEBUG) {
                Log.i(TAG,
                        "onActivityDestroyed(): mCurrentState is " + stateToString(mCurrentState));
            }
            if (mCurrentState != STATE_UI_TRANSLATION_FINISHED) {
                notifyTranslationFinished(/* activityDestroyed= */ true);
            }
            mViews.clear();
            destroyTranslators();
            mWorkerThread.quitSafely();
        }
    }

    private void notifyTranslationFinished(boolean activityDestroyed) {
        UiTranslationManager manager = mContext.getSystemService(UiTranslationManager.class);
        if (manager != null) {
            manager.onTranslationFinished(activityDestroyed,
                    new ActivityId(mActivity.getTaskId(), mActivity.getShareableActivityToken()),
                    mActivity.getComponentName());
        }
    }

    private void setLastRequestAutofillIdsLocked(List<AutofillId> views) {
        if (mLastRequestAutofillIds == null) {
            mLastRequestAutofillIds = new ArraySet<>();
        }
        if (mLastRequestAutofillIds.size() > 0) {
            mLastRequestAutofillIds.clear();
        }
        mLastRequestAutofillIds.addAll(views);
    }

    /**
     * Called to dump the translation information for Activity.
     */
    public void dump(String outerPrefix, PrintWriter pw) {
        pw.print(outerPrefix); pw.println("UiTranslationController:");
        final String pfx = outerPrefix + "  ";
        pw.print(pfx); pw.print("activity: "); pw.print(mActivity);
        pw.print(pfx); pw.print("resumed: "); pw.println(mActivity.isResumed());
        pw.print(pfx); pw.print("current state: "); pw.println(mCurrentState);
        final int translatorSize = mTranslators.size();
        pw.print(outerPrefix); pw.print("number translator: "); pw.println(translatorSize);
        for (int i = 0; i < translatorSize; i++) {
            pw.print(outerPrefix); pw.print("#"); pw.println(i);
            final Translator translator = mTranslators.valueAt(i);
            translator.dump(outerPrefix, pw);
            pw.println();
        }
        synchronized (mLock) {
            final int viewSize = mViews.size();
            pw.print(outerPrefix); pw.print("number views: "); pw.println(viewSize);
            for (int i = 0; i < viewSize; i++) {
                pw.print(outerPrefix); pw.print("#"); pw.println(i);
                final AutofillId autofillId = mViews.keyAt(i);
                final View view = mViews.valueAt(i).get();
                pw.print(pfx); pw.print("autofillId: "); pw.println(autofillId);
                pw.print(pfx); pw.print("view:"); pw.println(view);
            }
            pw.print(outerPrefix); pw.print("padded views: "); pw.println(mViewsToPadContent);
        }
        if (DEBUG) {
            dumpViewByTraversal(outerPrefix, pw);
        }
    }

    private void dumpViewByTraversal(String outerPrefix, PrintWriter pw) {
        final ArrayList<ViewRootImpl> roots =
                WindowManagerGlobal.getInstance().getRootViews(mActivity.getActivityToken());
        pw.print(outerPrefix); pw.println("Dump views:");
        for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
            final View rootView = roots.get(rootNum).getView();
            if (rootView instanceof ViewGroup) {
                dumpChildren((ViewGroup) rootView, outerPrefix, pw);
            } else {
                dumpViewInfo(rootView, outerPrefix, pw);
            }
        }
    }

    private void dumpChildren(ViewGroup viewGroup, String outerPrefix, PrintWriter pw) {
        final int childCount = viewGroup.getChildCount();
        for (int i = 0; i < childCount; ++i) {
            final View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                pw.print(outerPrefix); pw.println("Children: ");
                pw.print(outerPrefix); pw.print(outerPrefix); pw.println(child);
                dumpChildren((ViewGroup) child, outerPrefix, pw);
            } else {
                pw.print(outerPrefix); pw.println("End Children: ");
                pw.print(outerPrefix); pw.print(outerPrefix); pw.print(child);
                dumpViewInfo(child, outerPrefix, pw);
            }
        }
    }

    private void dumpViewInfo(View view, String outerPrefix, PrintWriter pw) {
        final AutofillId autofillId = view.getAutofillId();
        pw.print(outerPrefix); pw.print("autofillId: "); pw.print(autofillId);
        // TODO: print TranslationTransformation
        boolean isContainsView = false;
        boolean isRequestedView = false;
        synchronized (mLock) {
            if (mLastRequestAutofillIds.contains(autofillId)) {
                isRequestedView = true;
            }
            final WeakReference<View> viewRef = mViews.get(autofillId);
            if (viewRef != null && viewRef.get() != null) {
                isContainsView = true;
            }
        }
        pw.print(outerPrefix); pw.print("isContainsView: "); pw.print(isContainsView);
        pw.print(outerPrefix); pw.print("isRequestedView: "); pw.println(isRequestedView);
    }

    /**
     * The method is used by {@link Translator}, it will be called when the translation is done. The
     * translation result can be get from here.
     */
    public void onTranslationCompleted(TranslationResponse response) {
        if (response == null || response.getTranslationStatus()
                != TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
            Log.w(TAG, "Fail result from TranslationService, status=" + (response == null
                    ? "null"
                    : response.getTranslationStatus()));
            return;
        }
        final SparseArray<ViewTranslationResponse> translatedResult =
                response.getViewTranslationResponses();
        final SparseArray<ViewTranslationResponse> viewsResult = new SparseArray<>();
        final SparseArray<LongSparseArray<ViewTranslationResponse>> virtualViewsResult =
                new SparseArray<>();
        final IntArray viewIds = new IntArray(1);
        for (int i = 0; i < translatedResult.size(); i++) {
            final ViewTranslationResponse result = translatedResult.valueAt(i);
            final AutofillId autofillId = result.getAutofillId();
            if (viewIds.indexOf(autofillId.getViewId()) < 0) {
                viewIds.add(autofillId.getViewId());
            }
            if (autofillId.isNonVirtual()) {
                viewsResult.put(translatedResult.keyAt(i), result);
            } else {
                final boolean isVirtualViewAdded =
                        virtualViewsResult.indexOfKey(autofillId.getViewId()) >= 0;
                final LongSparseArray<ViewTranslationResponse> childIds =
                        isVirtualViewAdded ? virtualViewsResult.get(autofillId.getViewId())
                                : new LongSparseArray<>();
                childIds.put(autofillId.getVirtualChildLongId(), result);
                if (!isVirtualViewAdded) {
                    virtualViewsResult.put(autofillId.getViewId(), childIds);
                }
            }
        }
        // Traverse tree and get views by the responsed AutofillId
        findViewsTraversalByAutofillIds(viewIds);

        if (viewsResult.size() > 0) {
            onTranslationCompleted(viewsResult);
        }
        if (virtualViewsResult.size() > 0) {
            onVirtualViewTranslationCompleted(virtualViewsResult);
        }
    }

    /**
     * The method is used to handle the translation result for the vertual views.
     */
    private void onVirtualViewTranslationCompleted(
            SparseArray<LongSparseArray<ViewTranslationResponse>> translatedResult) {
        if (mActivity.isDestroyed()) {
            Log.v(TAG, "onTranslationCompleted:" + mActivity + "is destroyed.");
            return;
        }
        synchronized (mLock) {
            if (mCurrentState == STATE_UI_TRANSLATION_FINISHED) {
                Log.w(TAG, "onTranslationCompleted: the translation state is finished now. "
                        + "Skip to show the translated text.");
                return;
            }
            for (int i = 0; i < translatedResult.size(); i++) {
                final AutofillId autofillId = new AutofillId(translatedResult.keyAt(i));
                final WeakReference<View> viewRef = mViews.get(autofillId);
                if (viewRef == null) {
                    continue;
                }
                final View view = viewRef.get();
                if (view == null) {
                    Log.w(TAG, "onTranslationCompleted: the view for autofill id " + autofillId
                            + " may be gone.");
                    continue;
                }
                final LongSparseArray<ViewTranslationResponse> virtualChildResponse =
                        translatedResult.valueAt(i);
                if (DEBUG) {
                    Log.v(TAG, "onVirtualViewTranslationCompleted: received response for "
                            + "AutofillId " + autofillId);
                }
                mActivity.runOnUiThread(() -> {
                    if (view.getViewTranslationCallback() == null) {
                        if (DEBUG) {
                            Log.d(TAG, view + " doesn't support showing translation because of "
                                    + "null ViewTranslationCallback.");
                        }
                        return;
                    }
                    view.onVirtualViewTranslationResponses(virtualChildResponse);
                    if (view.getViewTranslationCallback() != null) {
                        view.getViewTranslationCallback().onShowTranslation(view);
                    }
                });
            }
        }
    }

    /**
     * The method is used to handle the translation result for non-vertual views.
     */
    private void onTranslationCompleted(SparseArray<ViewTranslationResponse> translatedResult) {
        if (mActivity.isDestroyed()) {
            Log.v(TAG, "onTranslationCompleted:" + mActivity + "is destroyed.");
            return;
        }
        final int resultCount = translatedResult.size();
        if (DEBUG) {
            Log.v(TAG, "onTranslationCompleted: receive " + resultCount + " responses.");
        }
        synchronized (mLock) {
            if (mCurrentState == STATE_UI_TRANSLATION_FINISHED) {
                Log.w(TAG, "onTranslationCompleted: the translation state is finished now. "
                        + "Skip to show the translated text.");
                return;
            }
            for (int i = 0; i < resultCount; i++) {
                final ViewTranslationResponse response = translatedResult.valueAt(i);
                if (DEBUG) {
                    Log.v(TAG, "onTranslationCompleted: "
                            + sanitizedViewTranslationResponse(response));
                }
                final AutofillId autofillId = response.getAutofillId();
                if (autofillId == null) {
                    Log.w(TAG, "No AutofillId is set in ViewTranslationResponse");
                    continue;
                }
                final WeakReference<View> viewRef = mViews.get(autofillId);
                if (viewRef == null) {
                    continue;
                }
                final View view = viewRef.get();
                if (view == null) {
                    Log.w(TAG, "onTranslationCompleted: the view for autofill id " + autofillId
                            + " may be gone.");
                    continue;
                }
                mActivity.runOnUiThread(() -> {
                    if (view.getViewTranslationResponse() != null
                            && view.getViewTranslationResponse().equals(response)) {
                        if (DEBUG) {
                            Log.d(TAG, "Duplicate ViewTranslationResponse for " + autofillId
                                    + ". Ignoring.");
                        }
                        return;
                    }
                    ViewTranslationCallback callback = view.getViewTranslationCallback();
                    if (callback == null) {
                        if (view instanceof TextView) {
                            // developer doesn't provide their override, we set the default TextView
                            // implementation.
                            callback = new TextViewTranslationCallback();
                            view.setViewTranslationCallback(callback);
                        } else {
                            if (DEBUG) {
                                Log.d(TAG, view + " doesn't support showing translation because of "
                                        + "null ViewTranslationCallback.");
                            }
                            return;
                        }
                    }
                    callback.setAnimationDurationMillis(ANIMATION_DURATION_MILLIS);
                    if (mViewsToPadContent.contains(autofillId)) {
                        callback.enableContentPadding();
                    }
                    view.onViewTranslationResponse(response);
                    callback.onShowTranslation(view);
                });
            }
        }
    }

    // TODO: Use a device config value.
    private static final int ANIMATION_DURATION_MILLIS = 250;

    /**
     * Creates a Translator for the given source and target translation specs and start the ui
     * translation when the Translator is created successfully.
     */
    @WorkerThread
    private void createTranslatorAndStart(TranslationSpec sourceSpec, TranslationSpec targetSpec,
            List<AutofillId> views) {
        // Create Translator
        final Translator translator = createTranslatorIfNeeded(sourceSpec, targetSpec);
        if (translator == null) {
            Log.w(TAG, "Can not create Translator for sourceSpec:" + sourceSpec + " targetSpec:"
                    + targetSpec);
            return;
        }
        onUiTranslationStarted(translator, views);
    }

    @WorkerThread
    private void sendTranslationRequest(Translator translator,
            List<ViewTranslationRequest> requests) {
        if (requests.size() == 0) {
            Log.w(TAG, "No ViewTranslationRequest was collected.");
            return;
        }
        final TranslationRequest request = new TranslationRequest.Builder()
                .setViewTranslationRequests(requests)
                .build();
        if (DEBUG) {
            StringBuilder msg = new StringBuilder("sendTranslationRequest:{requests=[");
            for (ViewTranslationRequest viewRequest: requests) {
                msg.append("{request=")
                        .append(sanitizedViewTranslationRequest(viewRequest))
                        .append("}, ");
            }
            Log.d(TAG, "sendTranslationRequest: " + msg.toString());
        }
        translator.requestUiTranslate(request, (r) -> r.run(), this::onTranslationCompleted);
    }

    /**
     * Called when there is an ui translation request comes to request view translation.
     */
    private void onUiTranslationStarted(Translator translator, List<AutofillId> views) {
        synchronized (mLock) {
            // Filter the request views' AutofillId
            SparseIntArray virtualViewChildCount = getRequestVirtualViewChildCount(views);
            Map<AutofillId, long[]> viewIds = new ArrayMap<>();
            Map<AutofillId, Integer> unusedIndices = null;
            for (int i = 0; i < views.size(); i++) {
                AutofillId autofillId = views.get(i);
                if (autofillId.isNonVirtual()) {
                    viewIds.put(autofillId, null);
                } else {
                    if (unusedIndices == null) {
                        unusedIndices = new ArrayMap<>();
                    }
                    // The virtual id get from content capture is long, see getVirtualChildLongId()
                    // e.g. 1001, 1001:2, 1002:1 -> 1001, <1,2>; 1002, <1>
                    AutofillId virtualViewAutofillId = new AutofillId(autofillId.getViewId());
                    long[] childs;
                    int end = 0;
                    if (viewIds.containsKey(virtualViewAutofillId)) {
                        childs = viewIds.get(virtualViewAutofillId);
                        end = unusedIndices.get(virtualViewAutofillId);
                    } else {
                        int childCount = virtualViewChildCount.get(autofillId.getViewId());
                        childs = new long[childCount];
                        viewIds.put(virtualViewAutofillId, childs);
                    }
                    unusedIndices.put(virtualViewAutofillId, end + 1);
                    childs[end] = autofillId.getVirtualChildLongId();
                }
            }
            ArrayList<ViewTranslationRequest> requests = new ArrayList<>();
            int[] supportedFormats = getSupportedFormatsLocked();
            ArrayList<ViewRootImpl> roots =
                    WindowManagerGlobal.getInstance().getRootViews(mActivity.getActivityToken());
            TranslationCapability capability =
                    getTranslationCapability(translator.getTranslationContext());
            mActivity.runOnUiThread(() -> {
                // traverse the hierarchy to collect ViewTranslationRequests
                for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
                    View rootView = roots.get(rootNum).getView();
                    rootView.dispatchCreateViewTranslationRequest(viewIds, supportedFormats,
                            capability, requests);
                }
                mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                        UiTranslationController::sendTranslationRequest,
                        UiTranslationController.this, translator, requests));
            });
        }
    }

    private SparseIntArray getRequestVirtualViewChildCount(List<AutofillId> views) {
        SparseIntArray virtualViewCount = new SparseIntArray();
        for (int i = 0; i < views.size(); i++) {
            AutofillId autofillId = views.get(i);
            if (!autofillId.isNonVirtual()) {
                int virtualViewId = autofillId.getViewId();
                if (virtualViewCount.indexOfKey(virtualViewId) < 0) {
                    virtualViewCount.put(virtualViewId, 1);
                } else {
                    virtualViewCount.put(virtualViewId, (virtualViewCount.get(virtualViewId) + 1));
                }
            }
        }
        return virtualViewCount;
    }

    private int[] getSupportedFormatsLocked() {
        // We only support text now
        return new int[] {TranslationSpec.DATA_FORMAT_TEXT};
    }

    private TranslationCapability getTranslationCapability(TranslationContext translationContext) {
        // We only support text to text capability now, we will query real status from service when
        // we support more translation capabilities.
        return new TranslationCapability(TranslationCapability.STATE_ON_DEVICE,
                translationContext.getSourceSpec(),
                translationContext.getTargetSpec(), /* uiTranslationEnabled= */ true,
                /* supportedTranslationFlags= */ 0);
    }

    private void findViewsTraversalByAutofillIds(IntArray sourceViewIds) {
        final ArrayList<ViewRootImpl> roots =
                WindowManagerGlobal.getInstance().getRootViews(mActivity.getActivityToken());
        for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
            final View rootView = roots.get(rootNum).getView();
            if (rootView instanceof ViewGroup) {
                findViewsTraversalByAutofillIds((ViewGroup) rootView, sourceViewIds);
            } else {
                addViewIfNeeded(sourceViewIds, rootView);
            }
        }
    }

    private void findViewsTraversalByAutofillIds(ViewGroup viewGroup,
            IntArray sourceViewIds) {
        final int childCount = viewGroup.getChildCount();
        for (int i = 0; i < childCount; ++i) {
            final View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                findViewsTraversalByAutofillIds((ViewGroup) child, sourceViewIds);
            } else {
                addViewIfNeeded(sourceViewIds, child);
            }
        }
    }

    private void addViewIfNeeded(IntArray sourceViewIds, View view) {
        final AutofillId autofillId = view.getAutofillId();
        if ((sourceViewIds.indexOf(autofillId.getViewId()) >= 0)
                && !mViews.containsKey(autofillId)) {
            mViews.put(autofillId, new WeakReference<>(view));
        }
    }

    private void runForEachView(BiConsumer<View, ViewTranslationCallback> action) {
        synchronized (mLock) {
            final ArrayMap<AutofillId, WeakReference<View>> views = new ArrayMap<>(mViews);
            if (views.size() == 0) {
                Log.w(TAG, "No views can be excuted for runForEachView.");
            }
            mActivity.runOnUiThread(() -> {
                final int viewCounts = views.size();
                for (int i = 0; i < viewCounts; i++) {
                    final View view = views.valueAt(i).get();
                    if (DEBUG) {
                        Log.d(TAG, "runForEachView for autofillId = " + (view != null
                                ? view.getAutofillId() : " null"));
                    }
                    if (view == null || view.getViewTranslationCallback() == null) {
                        if (DEBUG) {
                            Log.d(TAG, "View was gone or ViewTranslationCallback for autofillId "
                                    + "= " + views.keyAt(i));
                        }
                        continue;
                    }
                    action.accept(view, view.getViewTranslationCallback());
                }
            });
        }
    }

    private Translator createTranslatorIfNeeded(
            TranslationSpec sourceSpec, TranslationSpec targetSpec) {
        final TranslationManager tm = mContext.getSystemService(TranslationManager.class);
        if (tm == null) {
            Log.e(TAG, "Can not find TranslationManager when trying to create translator.");
            return null;
        }
        final TranslationContext translationContext = new TranslationContext(sourceSpec,
                targetSpec, /* translationFlags= */ 0);
        final Translator translator = tm.createTranslator(translationContext);
        if (translator != null) {
            final Pair<TranslationSpec, TranslationSpec> specs = new Pair<>(sourceSpec, targetSpec);
            mTranslators.put(specs, translator);
        }
        return translator;
    }

    private void destroyTranslators() {
        synchronized (mLock) {
            final int count = mTranslators.size();
            for (int i = 0; i < count; i++) {
                Translator translator = mTranslators.valueAt(i);
                translator.destroy();
            }
            mTranslators.clear();
        }
    }

    /**
     * Returns a string representation of the state.
     */
    public static String stateToString(@UiTranslationState int state) {
        switch (state) {
            case STATE_UI_TRANSLATION_STARTED:
                return "UI_TRANSLATION_STARTED";
            case STATE_UI_TRANSLATION_PAUSED:
                return "UI_TRANSLATION_PAUSED";
            case STATE_UI_TRANSLATION_RESUMED:
                return "UI_TRANSLATION_RESUMED";
            case STATE_UI_TRANSLATION_FINISHED:
                return "UI_TRANSLATION_FINISHED";
            default:
                return "Unknown state (" + state + ")";
        }
    }

    /**
     * Returns a sanitized string representation of {@link ViewTranslationRequest};
     */
    private static String sanitizedViewTranslationRequest(@NonNull ViewTranslationRequest request) {
        StringBuilder msg = new StringBuilder("ViewTranslationRequest:{values=[");
        for (String key: request.getKeys()) {
            final TranslationRequestValue value = request.getValue(key);
            msg.append("{text=").append(value.getText() == null
                    ? "null"
                    : "string[" + value.getText().length() + "]}, ");
        }
        return msg.toString();
    }

    /**
     * Returns a sanitized string representation of {@link ViewTranslationResponse};
     */
    private static String sanitizedViewTranslationResponse(
            @NonNull ViewTranslationResponse response) {
        StringBuilder msg = new StringBuilder("ViewTranslationResponse:{values=[");
        for (String key: response.getKeys()) {
            final TranslationResponseValue value = response.getValue(key);
            msg.append("{status=").append(value.getStatusCode()).append(", ");
            msg.append("text=").append(value.getText() == null
                    ? "null"
                    : "string[" + value.getText().length() + "], ");
            //TODO: append dictionary results.
            msg.append("transliteration=").append(value.getTransliteration() == null
                    ? "null"
                    : "string[" + value.getTransliteration().length() + "]}, ");
        }
        return msg.toString();
    }
}
