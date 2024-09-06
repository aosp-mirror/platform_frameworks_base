/*
 * Copyright 2024 The Android Open Source Project
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

package android.app.servertransaction;


import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import static java.util.Objects.requireNonNull;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.os.Parcel;
import android.view.IWindow;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * {@link ClientTransactionItem} to report changes to a window.
 *
 * @hide
 */
public abstract class WindowStateTransactionItem extends ClientTransactionItem {

    /** The interface for IWindow to perform callback directly if possible. */
    public interface TransactionListener {
        /** Notifies that the transaction item is going to be executed. */
        void onExecutingWindowStateTransactionItem();
    }

    /** Target window. */
    private IWindow mWindow;

    WindowStateTransactionItem() {}

    @Override
    public final void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        if (mWindow instanceof TransactionListener listener) {
            listener.onExecutingWindowStateTransactionItem();
        }
        execute(client, mWindow, pendingActions);
    }

    /**
     * Like {@link #execute(ClientTransactionHandler, PendingTransactionActions)},
     * but take non-null {@link IWindow} as a parameter.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public abstract void execute(@NonNull ClientTransactionHandler client,
            @NonNull IWindow window, @NonNull PendingTransactionActions pendingActions);

    void setWindow(@NonNull IWindow window) {
        mWindow = requireNonNull(window);
    }

    // To be overridden

    WindowStateTransactionItem(@NonNull Parcel in) {
        mWindow = IWindow.Stub.asInterface(in.readStrongBinder());
    }

    @CallSuper
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mWindow.asBinder());
    }

    @CallSuper
    @Override
    public void recycle() {
        mWindow = null;
    }

    // Subclass must override and call super.equals to compare the mActivityToken.
    @SuppressWarnings("EqualsGetClass")
    @CallSuper
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WindowStateTransactionItem other = (WindowStateTransactionItem) o;
        return Objects.equals(mWindow, other.mWindow);
    }

    @CallSuper
    @Override
    public int hashCode() {
        return Objects.hashCode(mWindow);
    }

    @CallSuper
    @Override
    public String toString() {
        return "mWindow=" + mWindow;
    }
}
