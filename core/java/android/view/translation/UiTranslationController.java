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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
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
        final List<TranslationRequest> translatedResult = response.getTranslations();
        onTranslationCompleted(translatedResult);
    }

    private void onTranslationCompleted(List<TranslationRequest> translatedResult) {
        if (!mActivity.isResumed()) {
            return;
        }
        final int resultCount = translatedResult.size();
        synchronized (mLock) {
            for (int i = 0; i < resultCount; i++) {
                final TranslationRequest request = translatedResult.get(i);
                final AutofillId autofillId = request.getAutofillId();
                if (autofillId == null) {
                    continue;
                }
                final View view = mViews.get(autofillId).get();
                if (view == null) {
                    Log.w(TAG, "onTranslationCompleted: the Veiew for autofill id " + autofillId
                            + " may be gone.");
                    continue;
                }
                mActivity.runOnUiThread(() -> view.onTranslationComplete(request));
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
            ArrayList<TranslationRequest> requests) {
        translator.requestUiTranslate(requests, this::onTranslationCompleted);
    }

    /**
     * Called when there is an ui translation request comes to request view translation.
     */
    private void onUiTranslationStarted(Translator translator, List<AutofillId> views) {
        synchronized (mLock) {
            // Find Views collect the translation data
            final ArrayList<TranslationRequest> requests = new ArrayList<>();
            final ArrayList<View> foundViews = new ArrayList<>();
            findViewsTraversalByAutofillIds(views, foundViews);
            for (int i = 0; i < foundViews.size(); i++) {
                final View view = foundViews.get(i);
                final int currentCount = i;
                mActivity.runOnUiThread(() -> {
                    final TranslationRequest translationRequest = view.onCreateTranslationRequest();
                    if (translationRequest != null
                            && translationRequest.getTranslationText().length() > 0) {
                        requests.add(translationRequest);
                    }
                    if (currentCount == (foundViews.size() - 1)) {
                        Log.v(TAG, "onUiTranslationStarted: send " + requests.size() + " request.");
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
                        continue;
                    }
                    action.accept(view);
                }
            });
        }
    }

    private Translator createTranslatorIfNeeded(
            TranslationSpec sourceSpec, TranslationSpec destSpec) {
        final TranslationManager tm = mContext.getSystemService(TranslationManager.class);
        if (tm == null) {
            Log.e(TAG, "Can not find TranslationManager when trying to create translator.");
            return null;
        }
        final Translator translator = tm.createTranslator(sourceSpec, destSpec);
        if (translator != null) {
            final Pair<TranslationSpec, TranslationSpec> specs = new Pair<>(sourceSpec, destSpec);
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
