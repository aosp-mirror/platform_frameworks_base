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

package android.view.translation;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.RemoteException;
import android.view.View;
import android.view.autofill.AutofillId;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;

/**
 * The {@link UiTranslationManager} class provides ways for apps to use the ui translation
 * function in framework.
 *
 * @hide
 */
@SystemApi
public final class UiTranslationManager {

    private static final String TAG = "UiTranslationManager";

    /**
     * The state caller request to disable utranslation,, it is no longer need to ui translation.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_STARTED = 0;
    /**
     * The state caller request to pause ui translation, it will switch back to the original text.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_PAUSED = 1;
    /**
     * The state caller request to resume the paused ui translation, it will show the translated
     * text again if the text had been translated.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_RESUMED = 2;
    /**
     * The state the caller request to enable ui translation.
     *
     * @hide
     */
    public static final int STATE_UI_TRANSLATION_FINISHED = 3;
    /**
     * @hide
     */
    @IntDef(prefix = {"STATE__TRANSLATION"}, value = {
            STATE_UI_TRANSLATION_STARTED,
            STATE_UI_TRANSLATION_PAUSED,
            STATE_UI_TRANSLATION_RESUMED,
            STATE_UI_TRANSLATION_FINISHED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UiTranslationState {
    }

    @NonNull
    private final Context mContext;

    private final ITranslationManager mService;

    /**
     * @hide
     */
    public UiTranslationManager(@NonNull Context context, ITranslationManager service) {
        mContext = Objects.requireNonNull(context);
        mService = service;
    }

    /**
     * Request ui translation for a given Views.
     *
     * @param sourceSpec {@link TranslationSpec} for the data to be translated.
     * @param destSpec {@link TranslationSpec} for the translated data.
     * @param viewIds A list of the {@link View}'s {@link AutofillId} which needs to be translated
     * @param taskId the Activity Task id which needs ui translation
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    public void startTranslation(@NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec destSpec, @NonNull List<AutofillId> viewIds,
            int taskId) {
        // TODO(b/177789967): Return result code or find a way to notify the status.
        // TODO(b/177394471): The is a temparary API, the expected is requestUiTranslation(
        //  TranslationSpec, TranslationSpec,List<AutofillId>, Binder). We may need more time to
        //  implement it, use task id as initial version for demo.
        Objects.requireNonNull(sourceSpec);
        Objects.requireNonNull(destSpec);
        Objects.requireNonNull(viewIds);

        try {
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_STARTED, sourceSpec,
                    destSpec, viewIds, taskId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to disable the ui translation. It will destroy all the {@link Translator}s and no
     * longer to show to show the translated text.
     *
     * @param taskId the Activity Task id which needs ui translation
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    public void finishTranslation(int taskId) {
        try {
            // TODO(b/177394471): The is a temparary API, the expected is finishUiTranslation(
            //  Binder). We may need more time to implement it, use task id as initial version.
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_FINISHED,
                    null /* sourceSpec */, null /* destSpec*/, null /* viewIds */, taskId,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to pause the current ui translation's {@link Translator} which will switch back to
     * the original language.
     *
     * @param taskId the Activity Task id which needs ui translation
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    public void pauseTranslation(int taskId) {
        try {
            // TODO(b/177394471): The is a temparary API, the expected is pauseUiTranslation(Binder)
            // We may need more time to implement it, use task id as initial version for demo
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_PAUSED,
                    null /* sourceSpec */, null /* destSpec*/, null /* viewIds */, taskId,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request to resume the paused ui translation's {@link Translator} which will switch to the
     * translated language if the text had been translated.
     *
     * @param taskId the Activity Task id which needs ui translation
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_UI_TRANSLATION)
    public void resumeTranslation(int taskId) {
        try {
            // TODO(b/177394471): The is a temparary API, the expected is resumeUiTranslation(
            //  Binder). We may need more time to implement it, use task id as initial version.
            mService.updateUiTranslationState(STATE_UI_TRANSLATION_RESUMED,
                    null /* sourceSpec */, null /* destSpec*/, null /* viewIds */,
                    taskId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
