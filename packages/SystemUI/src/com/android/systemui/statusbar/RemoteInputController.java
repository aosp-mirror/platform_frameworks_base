/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import android.app.Notification;
import android.app.RemoteInput;
import android.content.Context;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.util.Preconditions;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.RemoteInputView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps track of the currently active {@link RemoteInputView}s.
 */
public class RemoteInputController {
    private static final boolean ENABLE_REMOTE_INPUT =
            SystemProperties.getBoolean("debug.enable_remote_input", true);

    private final ArrayList<Pair<WeakReference<NotificationEntry>, Object>> mOpen
            = new ArrayList<>();
    private final ArrayMap<String, Object> mSpinning = new ArrayMap<>();
    private final ArrayList<Callback> mCallbacks = new ArrayList<>(3);
    private final Delegate mDelegate;

    public RemoteInputController(Delegate delegate) {
        mDelegate = delegate;
    }

    /**
     * Adds RemoteInput actions from the WearableExtender; to be removed once more apps support this
     * via first-class API.
     *
     * TODO: Remove once enough apps specify remote inputs on their own.
     */
    public static void processForRemoteInput(Notification n, Context context) {
        if (!ENABLE_REMOTE_INPUT) {
            return;
        }

        if (n.extras != null && n.extras.containsKey("android.wearable.EXTENSIONS") &&
                (n.actions == null || n.actions.length == 0)) {
            Notification.Action viableAction = null;
            Notification.WearableExtender we = new Notification.WearableExtender(n);

            List<Notification.Action> actions = we.getActions();
            final int numActions = actions.size();

            for (int i = 0; i < numActions; i++) {
                Notification.Action action = actions.get(i);
                if (action == null) {
                    continue;
                }
                RemoteInput[] remoteInputs = action.getRemoteInputs();
                if (remoteInputs == null) {
                    continue;
                }
                for (RemoteInput ri : remoteInputs) {
                    if (ri.getAllowFreeFormInput()) {
                        viableAction = action;
                        break;
                    }
                }
                if (viableAction != null) {
                    break;
                }
            }

            if (viableAction != null) {
                Notification.Builder rebuilder = Notification.Builder.recoverBuilder(context, n);
                rebuilder.setActions(viableAction);
                rebuilder.build(); // will rewrite n
            }
        }
    }

    /**
     * Adds a currently active remote input.
     *
     * @param entry the entry for which a remote input is now active.
     * @param token a token identifying the view that is managing the remote input
     */
    public void addRemoteInput(NotificationEntry entry, Object token) {
        Preconditions.checkNotNull(entry);
        Preconditions.checkNotNull(token);

        boolean found = pruneWeakThenRemoveAndContains(
                entry /* contains */, null /* remove */, token /* removeToken */);
        if (!found) {
            mOpen.add(new Pair<>(new WeakReference<>(entry), token));
        }

        apply(entry);
    }

    /**
     * Removes a currently active remote input.
     *
     * @param entry the entry for which a remote input should be removed.
     * @param token a token identifying the view that is requesting the removal. If non-null,
     *              the entry is only removed if the token matches the last added token for this
     *              entry. If null, the entry is removed regardless.
     */
    public void removeRemoteInput(NotificationEntry entry, Object token) {
        Preconditions.checkNotNull(entry);

        pruneWeakThenRemoveAndContains(null /* contains */, entry /* remove */, token);

        apply(entry);
    }

    /**
     * Adds a currently spinning (i.e. sending) remote input.
     *
     * @param key the key of the entry that's spinning.
     * @param token the token of the view managing the remote input.
     */
    public void addSpinning(String key, Object token) {
        Preconditions.checkNotNull(key);
        Preconditions.checkNotNull(token);

        mSpinning.put(key, token);
    }

    /**
     * Removes a currently spinning remote input.
     *
     * @param key the key of the entry for which a remote input should be removed.
     * @param token a token identifying the view that is requesting the removal. If non-null,
     *              the entry is only removed if the token matches the last added token for this
     *              entry. If null, the entry is removed regardless.
     */
    public void removeSpinning(String key, Object token) {
        Preconditions.checkNotNull(key);

        if (token == null || mSpinning.get(key) == token) {
            mSpinning.remove(key);
        }
    }

    public boolean isSpinning(String key) {
        return mSpinning.containsKey(key);
    }

    /**
     * Same as {@link #isSpinning}, but also verifies that the token is the same
     * @param key the key that is spinning
     * @param token the token that needs to be the same
     * @return if this key with a given token is spinning
     */
    public boolean isSpinning(String key, Object token) {
        return mSpinning.get(key) == token;
    }

    private void apply(NotificationEntry entry) {
        mDelegate.setRemoteInputActive(entry, isRemoteInputActive(entry));
        boolean remoteInputActive = isRemoteInputActive();
        int N = mCallbacks.size();
        for (int i = 0; i < N; i++) {
            mCallbacks.get(i).onRemoteInputActive(remoteInputActive);
        }
    }

    /**
     * @return true if {@param entry} has an active RemoteInput
     */
    public boolean isRemoteInputActive(NotificationEntry entry) {
        return pruneWeakThenRemoveAndContains(entry /* contains */, null /* remove */,
                null /* removeToken */);
    }

    /**
     * @return true if any entry has an active RemoteInput
     */
    public boolean isRemoteInputActive() {
        pruneWeakThenRemoveAndContains(null /* contains */, null /* remove */,
                null /* removeToken */);
        return !mOpen.isEmpty();
    }

    /**
     * Prunes dangling weak references, removes entries referring to {@param remove} and returns
     * whether {@param contains} is part of the array in a single loop.
     * @param remove if non-null, removes this entry from the active remote inputs
     * @param removeToken if non-null, only removes an entry if this matches the token when the
     *                    entry was added.
     * @return true if {@param contains} is in the set of active remote inputs
     */
    private boolean pruneWeakThenRemoveAndContains(
            NotificationEntry contains, NotificationEntry remove, Object removeToken) {
        boolean found = false;
        for (int i = mOpen.size() - 1; i >= 0; i--) {
            NotificationEntry item = mOpen.get(i).first.get();
            Object itemToken = mOpen.get(i).second;
            boolean removeTokenMatches = (removeToken == null || itemToken == removeToken);

            if (item == null || (item == remove && removeTokenMatches)) {
                mOpen.remove(i);
            } else if (item == contains) {
                if (removeToken != null && removeToken != itemToken) {
                    // We need to update the token. Remove here and let caller reinsert it.
                    mOpen.remove(i);
                } else {
                    found = true;
                }
            }
        }
        return found;
    }


    public void addCallback(Callback callback) {
        Preconditions.checkNotNull(callback);
        mCallbacks.add(callback);
    }

    public void remoteInputSent(NotificationEntry entry) {
        int N = mCallbacks.size();
        for (int i = 0; i < N; i++) {
            mCallbacks.get(i).onRemoteInputSent(entry);
        }
    }

    public void closeRemoteInputs() {
        if (mOpen.size() == 0) {
            return;
        }

        // Make a copy because closing the remote inputs will modify mOpen.
        ArrayList<NotificationEntry> list = new ArrayList<>(mOpen.size());
        for (int i = mOpen.size() - 1; i >= 0; i--) {
            NotificationEntry entry = mOpen.get(i).first.get();
            if (entry != null && entry.rowExists()) {
                list.add(entry);
            }
        }

        for (int i = list.size() - 1; i >= 0; i--) {
            NotificationEntry entry = list.get(i);
            if (entry.rowExists()) {
                entry.closeRemoteInput();
            }
        }
    }

    public void requestDisallowLongPressAndDismiss() {
        mDelegate.requestDisallowLongPressAndDismiss();
    }

    public void lockScrollTo(NotificationEntry entry) {
        mDelegate.lockScrollTo(entry);
    }

    public interface Callback {
        default void onRemoteInputActive(boolean active) {}

        default void onRemoteInputSent(NotificationEntry entry) {}
    }

    public interface Delegate {
        /**
         * Activate remote input if necessary.
         */
        void setRemoteInputActive(NotificationEntry entry, boolean remoteInputActive);

        /**
         * Request that the view does not dismiss nor perform long press for the current touch.
         */
        void requestDisallowLongPressAndDismiss();

        /**
         * Request that the view is made visible by scrolling to it, and keep the scroll locked until
         * the user scrolls, or {@param entry} loses focus or is detached.
         */
        void lockScrollTo(NotificationEntry entry);
    }
}
