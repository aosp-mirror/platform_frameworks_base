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
import android.util.Pair;
import android.util.SparseArray;
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
import java.util.function.Consumer;

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
                runForEachView(View::onPauseUiTranslation);
                break;
            case STATE_UI_TRANSLATION_RESUMED:
                runForEachView(View::onRestoreUiTranslation);
                break;
            case STATE_UI_TRANSLATION_FINISHED:
                destroyTranslators();
                runForEachView(View::onFinishUiTranslation);
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
        onTranslationCompleted(translatedResult);
    }

    private void onTranslationCompleted(SparseArray<ViewTranslationResponse> translatedResult) {
        if (!mActivity.isResumed()) {
            return;
        }
        final int resultCount = translatedResult.size();
        if (DEBUG) {
            Log.v(TAG, "onTranslationCompleted: receive " + resultCount + " responses.");
        }
        synchronized (mLock) {
            for (int i = 0; i < resultCount; i++) {
                final ViewTranslationResponse response = translatedResult.get(i);
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
                mActivity.runOnUiThread(() -> view.onTranslationComplete(response));
            }
        }
    }

    /**
     * Called when there is an ui translation request comes to request view translation.
     */
    @WorkerThread
    private void createTranslatorAndStart(TranslationSpec sourceSpec, TranslationSpec destSpec,
            List<AutofillId> views) {
        // Create Translator
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
            // Find Views collect the translation data
            final ArrayList<ViewTranslationRequest> requests = new ArrayList<>();
            final ArrayList<View> foundViews = new ArrayList<>();
            findViewsTraversalByAutofillIds(views, foundViews);
            for (int i = 0; i < foundViews.size(); i++) {
                final View view = foundViews.get(i);
                final int currentCount = i;
                mActivity.runOnUiThread(() -> {
                    final ViewTranslationRequest request = view.onCreateTranslationRequest();
                    if (request != null
                            && request.getKeys().size() > 0) {
                        requests.add(request);
                    }
                    if (currentCount == (foundViews.size() - 1)) {
                        Log.v(TAG, "onUiTranslationStarted: collect " + requests.size()
                                + " requests.");
                        mWorkerHandler.sendMessage(PooledLambda.obtainMessage(
                                UiTranslationController::sendTranslationRequest,
                                UiTranslationController.this, translator, requests));
                    }
                });
            }
        }
    }

    private void findViewsTraversalByAutofillIds(List<AutofillId> sourceViewIds,
            ArrayList<View> foundViews) {
        final ArrayList<ViewRootImpl> roots =
                WindowManagerGlobal.getInstance().getRootViews(mActivity.getActivityToken());
        for (int rootNum = 0; rootNum < roots.size(); rootNum++) {
            final View rootView = roots.get(rootNum).getView();
            if (rootView instanceof ViewGroup) {
                findViewsTraversalByAutofillIds((ViewGroup) rootView, sourceViewIds, foundViews);
            } else {
                addViewIfNeeded(sourceViewIds, rootView, foundViews);
            }
        }
    }

    private void findViewsTraversalByAutofillIds(ViewGroup viewGroup,
            List<AutofillId> sourceViewIds, ArrayList<View> foundViews) {
        final int childCount = viewGroup.getChildCount();
        for (int i = 0; i < childCount; ++i) {
            final View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                findViewsTraversalByAutofillIds((ViewGroup) child, sourceViewIds, foundViews);
            } else {
                addViewIfNeeded(sourceViewIds, child, foundViews);
            }
        }
    }

    private void addViewIfNeeded(List<AutofillId> sourceViewIds, View view,
            ArrayList<View> foundViews) {
        final AutofillId autofillId = view.getAutofillId();
        if (sourceViewIds.contains(autofillId)) {
            mViews.put(autofillId, new WeakReference<>(view));
            foundViews.add(view);
        }
    }

    private void runForEachView(Consumer<View> action) {
        synchronized (mLock) {
            final ArrayMap<AutofillId, WeakReference<View>> views = new ArrayMap<>(mViews);
            mActivity.runOnUiThread(() -> {
                final int viewCounts = views.size();
                for (int i = 0; i < viewCounts; i++) {
                    final View view = views.valueAt(i).get();
                    if (view == null) {
                        if (DEBUG) {
                            Log.d(TAG, "View was gone for autofillid = " + views.keyAt(i));
                        }
                        continue;
                    }
                    action.accept(view);
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
