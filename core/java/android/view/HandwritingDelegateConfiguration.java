/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view;

import android.annotation.IdRes;
import android.annotation.NonNull;

/**
 * Configuration for a view to act as a handwriting initiation delegate. This allows handwriting
 * mode for a delegator editor view to be initiated by stylus movement on the delegate view.
 *
 * <p>If a stylus {@link MotionEvent} occurs within the delegate view's bounds, the callback
 * returned by {@link #getInitiationCallback()} will be called. The callback implementation is
 * expected to show and focus the delegator editor view. If a view with identifier matching {@link
 * #getDelegatorViewId()} creates an input connection while the same stylus {@link MotionEvent}
 * sequence is ongoing, handwriting mode will be initiated for that view.
 *
 * <p>A common use case is a custom view which looks like a text editor but does not actually
 * support text editing itself, and clicking on the custom view causes an EditText to be shown. To
 * support handwriting initiation in this case, {@link View#setHandwritingDelegateConfiguration} can
 * be called on the custom view to configure it as a delegate, and set the EditText as the delegator
 * by passing the EditText's identifier as the {@code delegatorViewId}. The {@code
 * initiationCallback} implementation is typically the same as the click listener implementation
 * which shows the EditText.
 */
public class HandwritingDelegateConfiguration {
    @IdRes private final int mDelegatorViewId;
    @NonNull private final Runnable mInitiationCallback;

    /**
     * Constructs a HandwritingDelegateConfiguration instance.
     *
     * @param delegatorViewId identifier of the delegator editor view for which handwriting mode
     *     should be initiated
     * @param initiationCallback callback called when a stylus {@link MotionEvent} occurs within
     *     this view's bounds. This will be called from the UI thread.
     */
    public HandwritingDelegateConfiguration(
            @IdRes int delegatorViewId, @NonNull Runnable initiationCallback) {
        mDelegatorViewId = delegatorViewId;
        mInitiationCallback = initiationCallback;
    }

    /**
     * Returns the identifier of the delegator editor view for which handwriting mode should be
     * initiated.
     */
    public int getDelegatorViewId() {
        return mDelegatorViewId;
    }

    /**
     * Returns the callback which should be called when a stylus {@link MotionEvent} occurs within
     * the delegate view's bounds. The callback should only be called from the UI thread.
     */
    @NonNull
    public Runnable getInitiationCallback() {
        return mInitiationCallback;
    }
}
