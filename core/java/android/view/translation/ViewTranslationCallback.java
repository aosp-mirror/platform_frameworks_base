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
import android.annotation.UiThread;
import android.view.View;

/**
 * Callback for handling the translated information show or hide in the {@link View}.
 */
@UiThread
public interface ViewTranslationCallback {
    /**
     * Called when the translated text is ready to show or if the user has requested to reshow the
     * translated content after hiding it.
     * <p>
     * The translated content can be obtained from {@link View#getViewTranslationResponse}. This
     * method will not be called before {@link View#onViewTranslationResponse} or
     * {@link View#onVirtualViewTranslationResponses}.
     *
     * See {@link View#onViewTranslationResponse} for how to get the translated information.
     *
     * @return {@code true} if the View handles showing the translation.
     */
    boolean onShowTranslation(@NonNull View view);
    /**
     * Called when the user wants to show the original text instead of the translated text. This
     * method will not be called before {@link View#onViewTranslationResponse} or
     * {@link View#onViewTranslationResponse}.
     *
     * @return {@code true} if the View handles hiding the translation.
     */
    boolean onHideTranslation(@NonNull View view);
    /**
     * Called when the user finish the Ui translation and no longer to show the translated text.
     *
     * @return {@code true} if the View handles clearing the translation.
     */
    boolean onClearTranslation(@NonNull View view);

    /**
     * Enables padding on the view's original content.
     * <p>
     * This is useful when we do not modify the content directly, rather use a mechanism like
     * {@link android.text.method.TransformationMethod}. If the app misbehaves when the displayed
     * translation and the underlying content have different sizes, the platform intelligence can
     * request that the original content be padded to make the sizes match.
     *
     * @hide
     */
    default void enableContentPadding() {}

    /**
     * Sets the duration for animations while transitioning the view between the original and
     * translated contents.
     *
     * @hide
     */
    default void setAnimationDurationMillis(int durationMillis) {}
}
