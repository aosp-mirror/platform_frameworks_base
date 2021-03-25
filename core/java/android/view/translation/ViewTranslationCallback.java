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
 * Callback for handling the translated information show or hide in the {@link View}. See {@link
 * View#onTranslationResponse} for how to get the translated information.
 */
@UiThread
public interface ViewTranslationCallback {
    /**
     * Called when the translated text is ready to show or if the user has requested to reshow the
     * translated content after hiding it. This may be called before the translation results are
     * returned through the {@link View#onTranslationResponse}.
     *
     * @return {@code true} if the View handles showing the translation.
     */
    boolean onShowTranslation(@NonNull View view);
    /**
     * Called when the user wants to show the original text instead of the translated text. This
     * may be called before the translation results are returned through the
     * {@link View#onTranslationResponse}.
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
}
