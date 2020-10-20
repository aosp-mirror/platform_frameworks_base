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

package com.android.systemui.bubbles;

import android.annotation.NonNull;

import androidx.annotation.MainThread;

import com.android.systemui.statusbar.ScrimView;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.ScrimController;

import java.util.List;

/**
 * Interface to engage bubbles feature.
 */
public interface Bubbles {

    /**
     * @return {@code true} if there is a bubble associated with the provided key and if its
     * notification is hidden from the shade or there is a group summary associated with the
     * provided key that is hidden from the shade because it has been dismissed but still has child
     * bubbles active.
     */
    boolean isBubbleNotificationSuppressedFromShade(NotificationEntry entry);

    /**
     * @return {@code true} if the current notification entry same as selected bubble
     * notification entry and the stack is currently expanded.
     */
    boolean isBubbleExpanded(NotificationEntry entry);

    /** @return {@code true} if stack of bubbles is expanded or not. */
    boolean isStackExpanded();

    /**
     * @return the {@link ScrimView} drawn behind the bubble stack. This is managed by
     * {@link ScrimController} since we want the scrim's appearance and behavior to be identical to
     * that of the notification shade scrim.
     */
    ScrimView getScrimForBubble();

    /** @return Bubbles for updating overflow. */
    List<Bubble> getOverflowBubbles();

    /** Tell the stack of bubbles to collapse. */
    void collapseStack();

    /**
     * Request the stack expand if needed, then select the specified Bubble as current.
     * If no bubble exists for this entry, one is created.
     *
     * @param entry the notification for the bubble to be selected
     */
    void expandStackAndSelectBubble(NotificationEntry entry);

    /** Promote the provided bubbles when overflow view. */
    void promoteBubbleFromOverflow(Bubble bubble);

    /**
     * We intercept notification entries (including group summaries) dismissed by the user when
     * there is an active bubble associated with it. We do this so that developers can still
     * cancel it (and hence the bubbles associated with it). However, these intercepted
     * notifications should then be hidden from the shade since the user has cancelled them, so we
     * {@link Bubble#setSuppressNotification}.  For the case of suppressed summaries, we also add
     * {@link BubbleData#addSummaryToSuppress}.
     *
     * @return true if we want to intercept the dismissal of the entry, else false.
     */
    boolean handleDismissalInterception(NotificationEntry entry);

    /**
     * Removes the bubble with the given key.
     * <p>
     * Must be called from the main thread.
     */
    @MainThread
    void removeBubble(String key, int reason);


    /**
     * When a notification is marked Priority, expand the stack if needed,
     * then (maybe create and) select the given bubble.
     *
     * @param entry the notification for the bubble to show
     */
    void onUserChangedImportance(NotificationEntry entry);

    /**
     * Called when the status bar has become visible or invisible (either permanently or
     * temporarily).
     */
    void onStatusBarVisibilityChanged(boolean visible);

    /**
     * Called when a user has indicated that an active notification should be shown as a bubble.
     * <p>
     * This method will collapse the shade, create the bubble without a flyout or dot, and suppress
     * the notification from appearing in the shade.
     *
     * @param entry the notification to change bubble state for.
     * @param shouldBubble whether the notification should show as a bubble or not.
     */
    void onUserChangedBubble(@NonNull NotificationEntry entry, boolean shouldBubble);


    /** See {@link BubbleController.NotifCallback}. */
    void addNotifCallback(BubbleController.NotifCallback callback);

    /** Set a listener to be notified of bubble expand events. */
    void setExpandListener(BubbleController.BubbleExpandListener listener);

    /** Set a listener to be notified of when overflow view update. */
    void setOverflowListener(BubbleData.Listener listener);

    /** The task listener for events in bubble tasks. */
    MultiWindowTaskListener getTaskManager();

    /** Contains information to help position things on the screen.  */
    BubblePositioner getPositioner();
}
