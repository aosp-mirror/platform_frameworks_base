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
 * <p>
 * Prior to Android version {@link android.os.Build.VERSION_CODES#TIRAMISU}, callback methods
 * <em>without</em> {@code packageName} are invoked. Apps with minSdkVersion lower than {@link
 * android.os.Build.VERSION_CODES#TIRAMISU} <em>must</em> implement those methods if they want to
 * handle the events.
 * <p>
 * In Android version {@link android.os.Build.VERSION_CODES#TIRAMISU} and later, if both methods
 * with and without {@code packageName} are implemented (e.g., {@link #onFinished()} and {@link
 * #onFinished(String)}, only the one <em>with</em> {@code packageName} will be called.
 */
public interface UiTranslationStateCallback {

    /**
     * @removed use {@link #onStarted(ULocale, ULocale)} or {@link #onStarted(ULocale, ULocale,
     * String)} instead.
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
     * <p>
     * Apps should implement {@link #onStarted(ULocale, ULocale, String)} instead if they need the
     * name of the package that owns the activity being translated.
     * <p>
     * Apps with minSdkVersion lower than {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * <em>must</em> implement this method if they want to handle the "started" event.
     *
     * @param sourceLocale {@link ULocale} the UI is being translated from.
     * @param targetLocale {@link ULocale} the UI is being translated to.
     */
    default void onStarted(@NonNull ULocale sourceLocale, @NonNull ULocale targetLocale) {
        onStarted(sourceLocale.getLanguage(), targetLocale.getLanguage());
    }

    /**
     * The system is requesting translation of the UI from {@code sourceLocale} to {@code
     * targetLocale}.
     * <p>
     * This is also called if either the requested {@code sourceLocale} or {@code targetLocale} has
     * changed.
     * <p>
     * Apps <em>may</em> implement {@link #onStarted(ULocale, ULocale)} instead if they don't need
     * the name of the package that owns the activity being translated.
     * <p>
     * Apps with minSdkVersion lower than {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * <em>must</em> implement {@link #onStarted(ULocale, ULocale)} if they want to handle the
     * "started" event.
     *
     * @param sourceLocale {@link ULocale} the UI is being translated from.
     * @param targetLocale {@link ULocale} the UI is being translated to.
     * @param packageName  The name of the package that owns the activity being translated.
     */
    default void onStarted(@NonNull ULocale sourceLocale, @NonNull ULocale targetLocale,
            @NonNull String packageName) {
        onStarted(sourceLocale, targetLocale);
    }

    /**
     * The system is requesting that the application temporarily show the UI contents in their
     * original language.
     * <p>
     * Apps should implement {@link #onPaused(String)} as well if they need the name of the
     * package that owns the activity being translated.
     */
    void onPaused();

    /**
     * The system is requesting that the application temporarily show the UI contents in their
     * original language.
     * <p>
     * Apps <em>may</em> implement {@link #onPaused()} instead if they don't need the name of the
     * package that owns the activity being translated.
     * <p>
     * Apps with minSdkVersion lower than {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * <em>must</em> implement {@link #onPaused()} if they want to handle the "paused" event.
     */
    default void onPaused(@NonNull String packageName) {
        onPaused();
    }

    /**
     * The system is requesting that the application restore from the temporarily paused state and
     * show the content in the translated language.
     * <p>
     * Apps should implement {@link #onResumed(ULocale, ULocale, String)} instead if they need the
     * name of the package that owns the activity being translated.
     * <p>
     * Apps with minSdkVersion lower than {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * <em>must</em> implement this method if they want to handle the "resumed" event.
     *
     * @param sourceLocale {@link ULocale} the UI is being translated from.
     * @param targetLocale {@link ULocale} the UI is being translated to.
     */
    default void onResumed(@NonNull ULocale sourceLocale, @NonNull ULocale targetLocale) {
    }

    /**
     * The system is requesting that the application restore from the temporarily paused state and
     * show the content in the translated language.
     * <p>
     * Apps <em>may</em> implement {@link #onResumed(ULocale, ULocale)} instead if they don't need
     * the name of the package that owns the activity being translated.
     * <p>
     * Apps with minSdkVersion lower than {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * <em>must</em> implement {@link #onResumed(ULocale, ULocale)} if they want to handle the
     * "resumed" event.
     *
     * @param sourceLocale {@link ULocale} the UI is being translated from.
     * @param targetLocale {@link ULocale} the UI is being translated to.
     * @param packageName  The name of the package that owns the activity being translated.
     */
    default void onResumed(@NonNull ULocale sourceLocale, @NonNull ULocale targetLocale,
            @NonNull String packageName) {
        onResumed(sourceLocale, targetLocale);
    }

    /**
     * The UI Translation session has ended.
     * <p>
     * Apps should implement {@link #onFinished(String)} as well if they need the name of the
     * package that owns the activity being translated.
     */
    void onFinished();

    /**
     * The UI Translation session has ended.
     * <p>
     * Apps <em>may</em> implement {@link #onFinished()} instead if they don't need the name of the
     * package that owns the activity being translated.
     * <p>
     * Apps with minSdkVersion lower than {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * <em>must</em> implement {@link #onFinished()} if they want to handle the "finished" event.
     *
     * @param packageName The name of the package that owns the activity being translated.
     */
    default void onFinished(@NonNull String packageName) {
        onFinished();
    }
}
