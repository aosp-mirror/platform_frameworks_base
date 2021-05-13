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

import android.annotation.NonNull;
import android.icu.util.ULocale;

import java.util.concurrent.Executor;

/**
 * Callback for listening to UI Translation state changes. See {@link
 * UiTranslationManager#registerUiTranslationStateCallback(Executor, UiTranslationStateCallback)}.
 */
public interface UiTranslationStateCallback {

    /**
     * @deprecated use {@link #onStarted(ULocale, ULocale)} instead.
     */
    @Deprecated
    default void onStarted(@NonNull String sourceLocale, @NonNull String targetLocale) {
        // no-op
    }

    /**
     * The system is requesting translation of the UI from {@code sourceLocale} to {@code
     * targetLocale}.
     * <p>
     * This is also called if either the requested {@code sourceLocale} or {@code targetLocale} has
     * changed.
     */
    default void onStarted(@NonNull ULocale sourceLocale, @NonNull ULocale targetLocale) {
        onStarted(sourceLocale.getLanguage(), targetLocale.getLanguage());
    }

    /**
     * The system is requesting that the application temporarily show the UI contents in their
     * original language.
     */
    void onPaused();

    /**
     * The system is requesting that the application restore from the temporarily paused state and
     * show the content in translated language.
     */
    // TODO: Remove the default implementation when clients have implemented this.
    default void onResumed(@NonNull ULocale sourceLocale, @NonNull ULocale targetLocale) {
    }

    /**
     * The UI Translation session has ended.
     */
    void onFinished();
}
