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
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.ArrayMap;
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

    // TODO(b/182433547): remove Build.IS_DEBUGGABLE before ship. Enable the logging in debug build
    //  to help the debug during the development phase
    public static final boolean DEBUG = Log.isLoggable(UiTranslationManager.LOG_TAG, Log.DEBUG)
            || Build.IS_DEBUGGABLE;

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
    @NonNull
    private final HandlerThread mWorkerThread;
    @NonNull
    private final Handler mWorkerHandler;
    private int mCurrentState;

    public UiTranslationController(Activity activity, Context context) {
        mActivity = activity;
        mContext = context;
        mViews = new ArrayMap<>();
        mTranslators = new ArrayMap<>();

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
            TranslationSpec destSpec, List<AutofillId> views) {
        if (!mActivity.isResumed()) {
            return;
        }
        Log.i(TAG, "updateUiTranslationState state: " + stateToString(state)
                + (DEBUG ? ", views: " + views : ""));
        synchronized (mLock) {
            mCurrentState = state;
        }
        switch (state) {
            case STATE_UI_TRANSLATION_STARTED:
                final Pair<TranslationSpec, TranslationSpec> specs =
                        new Pair<>(sourceSpec, destSpec);
                if (!mTranslators.containsKey(specs)) {
                    mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                            UiTranslationController::createTranslatorAndStart,
                            UiTranslationController.this, sourceSpec, destSpec, views));
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
                runForEachView((view, callback) -> callback.onClearTranslation(view));
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
            mViews.clear();
            destroyTranslators();
            mWorkerThread.quitSafely();
        }
    }

    /**
     * Called to dump the translation information for Activity.
     */
    public void dump(String outerPrefix, PrintWriter pw) {
        pw.print(outerPrefix); pw.println("UiTranslationController:");
        final String pfx = outerPrefix + "  ";
        pw.print(pfx); pw.print("activity: "); pw.println(mActivity);
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
        }
        // TODO(b/182433547): we will remove debug rom condition before S release then we change
        //  change this back to "DEBUG"
        if (Log.isLoggable(UiTranslationManager.LOG_TAG, Log.DEBUG)) {
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
        synchronized (mLock) {
            final WeakReference<View> viewRef = mViews.get(autofillId);
            if (viewRef != null && viewRef.get() != null) {
                isContainsView = true;
            }
        }
        pw.print(outerPrefix); pw.print("isContainsView: "); pw.println(isContainsView);
    }

    /**
     * The method is used by {@link Translator}, it will be called when the translation is done. The
     * translation result can be get from here.
     */
    public void onTranslationCompleted(TranslationResponse response) {
        if (response == null || response.getTranslationStatus()
                != TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
            Log.w(TAG, "Fail result from TranslationService, response: " + response);
            return;
        }
        final SparseArray<ViewTranslationResponse> translatedResult =
                response.getViewTranslationResponses();
        final SparseArray<ViewTranslationResponse> viewsResult = new SparseArray<>();
        final SparseArray<LongSparseArray<ViewTranslationResponse>> virtualViewsResult =
                new SparseArray<>();
        // TODO: use another structure to prevent autoboxing?
        final List<Integer> viewIds = new ArrayList<>();

        for (int i = 0; i < translatedResult.size(); i++) {
            final ViewTranslationResponse result = translatedResult.valueAt(i);
            final AutofillId autofillId = result.getAutofillId();
            if (!viewIds.contains(autofillId.getViewId())) {
                viewIds.add(autofillId.getViewId());
            }
            if (autofillId.isNonVirtual()) {
                viewsResult.put(translatedResult.keyAt(i), result);
            } else {
                final boolean isVirtualViewAdded =
                        virtualViewsResult.indexOfKey(autofillId.getViewId()) > 0;
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
        if (!mActivity.isResumed()) {
            if (DEBUG) {
                Log.v(TAG, "onTranslationCompleted: Activity is not resumed.");
            }
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
                final View view = mViews.get(autofillId).get();
                if (view == null) {
                    Log.w(TAG, "onTranslationCompleted: the view for autofill id " + autofillId
                            + " may be gone.");
                    continue;
                }
                final LongSparseArray<ViewTranslationResponse> virtualChildResponse =
                        translatedResult.valueAt(i);
                mActivity.runOnUiThread(() -> {
                    if (view.getViewTranslationCallback() == null) {
                        if (DEBUG) {
                            Log.d(TAG, view + " doesn't support showing translation because of "
                                    + "null ViewTranslationCallback.");
                        }
                        return;
                    }
                    view.onTranslationResponse(virtualChildResponse);
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
        if (!mActivity.isResumed()) {
            if (DEBUG) {
                Log.v(TAG, "onTranslationCompleted: Activity is not resumed.");
            }
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
                final AutofillId autofillId = response.getAutofillId();
                if (autofillId == null) {
                    continue;
                }
                final View view = mViews.get(autofillId).get();
                if (view == null) {
                    Log.w(TAG, "onTranslationCompleted: the view for autofill id " + autofillId
                            + " may be gone.");
                    continue;
                }
                mActivity.runOnUiThread(() -> {
                    if (view.getViewTranslationCallback() == null) {
                        if (DEBUG) {
                            Log.d(TAG, view + " doesn't support showing translation because of "
                                    + "null ViewTranslationCallback.");
                        }
                        return;
                    }
                    view.onTranslationResponse(response);
                    if (view.getViewTranslationCallback() != null) {
                        view.getViewTranslationCallback().onShowTranslation(view);
                    }
                });
            }
        }
    }

    /**
     * Creates a Translator for the given source and target translation specs and start the ui
     * translation when the Translator is created successfully.
     */
    @WorkerThread
    private void createTranslatorAndStart(TranslationSpec sourceSpec, TranslationSpec destSpec,
            List<AutofillId> views) {
        final Translator translator = createTranslatorIfNeeded(sourceSpec, destSpec);
        if (translator == null) {
            Log.w(TAG, "Can not create Translator for sourceSpec:" + sourceSpec + " destSpec:"
                    + destSpec);
            return;
        }
        onUiTranslationStarted(translator, views);
    }

    @WorkerThread
    private void sendTranslationRequest(Translator translator,
            List<ViewTranslationRequest> requests) {
        if (requests.size() == 0) {
            Log.wtf(TAG, "No ViewTranslationRequest was collected.");
            return;
        }
        final TranslationRequest request = new TranslationRequest.Builder()
                .setViewTranslationRequests(requests)
                .build();
        translator.requestUiTranslate(request, (r) -> r.run(), this::onTranslationCompleted);
    }

    /**
     * Called when there is an ui translation request comes to request view translation.
     */
    private void onUiTranslationStarted(Translator translator, List<AutofillId> views) {
        synchronized (mLock) {
            // Filter the request views's AutofillId
            SparseIntArray virtualViewChildCount = getRequestVirtualViewChildCount(views);
            Map<AutofillId, long[]> viewIds = new ArrayMap<>();
            for (int i = 0; i < views.size(); i++) {
                AutofillId autofillId = views.get(i);
                if (autofillId.isNonVirtual()) {
                    viewIds.put(autofillId, null);
                } else {
                    // The virtual id get from content capture is long, see getVirtualChildLongId()
                    // e.g. 1001, 1001:2, 1002:1 -> 1001, <1,2>; 1002, <1>
                    AutofillId virtualViewAutofillId = new AutofillId(autofillId.getViewId());
                    long[] childs;
                    if (viewIds.containsKey(virtualViewAutofillId)) {
                        childs = viewIds.get(virtualViewAutofillId);
                    } else {
                        int childCount = virtualViewChildCount.get(autofillId.getViewId());
                        childs = new long[childCount];
                        viewIds.put(virtualViewAutofillId, childs);
                    }
                    int end = childs.length - 1;
                    childs[end] = autofillId.getVirtualChildLongId();
                }
            }
            ArrayList<ViewTranslationRequest> requests = new ArrayList<>();
            int[] supportedFormats = getSupportedFormatsLocked();
            ArrayList<ViewRootImpl> roots =
                    WindowManagerGlobal.getInstance().getRootViews(mActivity.getActivityToken());
            mActivity.runOnUiThread(() -> {
                // traverse the hierarchy to collect ViewTranslationRequests
                for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
                    View rootView = roots.get(rootNum).getView();
                    // TODO(b/183589662): call getTranslationCapabilities() for capability
                    rootView.dispatchRequestTranslation(viewIds, supportedFormats, /* capability */
                            null, requests);
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

    private void findViewsTraversalByAutofillIds(List<Integer> sourceViewIds) {
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
            List<Integer> sourceViewIds) {
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

    private void addViewIfNeeded(List<Integer> sourceViewIds, View view) {
        final AutofillId autofillId = view.getAutofillId();
        if (sourceViewIds.contains(autofillId.getViewId()) && !mViews.containsKey(autofillId)) {
            mViews.put(autofillId, new WeakReference<>(view));
        }
    }

    private void runForEachView(BiConsumer<View, ViewTranslationCallback> action) {
        synchronized (mLock) {
            final ArrayMap<AutofillId, WeakReference<View>> views = new ArrayMap<>(mViews);
            mActivity.runOnUiThread(() -> {
                final int viewCounts = views.size();
                for (int i = 0; i < viewCounts; i++) {
                    final View view = views.valueAt(i).get();
                    if (view == null || view.getViewTranslationCallback() == null) {
                        if (DEBUG) {
                            Log.d(TAG, "View was gone or ViewTranslationCallback for autofillid "
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
}
