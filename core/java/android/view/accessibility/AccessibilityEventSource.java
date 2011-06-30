/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.view.accessibility;

/**
 * This interface is implemented by classes source of {@link AccessibilityEvent}s.
 */
public interface AccessibilityEventSource {

    /**
     * Handles the request for sending an {@link AccessibilityEvent} given
     * the event type. The method must first check if accessibility is on
     * via calling {@link AccessibilityManager#isEnabled() AccessibilityManager.isEnabled()},
     * obtain an {@link AccessibilityEvent} from the event pool through calling
     * {@link AccessibilityEvent#obtain(int) AccessibilityEvent.obtain(int)}, populate the
     * event, and send it for dispatch via calling
     * {@link AccessibilityManager#sendAccessibilityEvent(AccessibilityEvent)
     * AccessibilityManager.sendAccessibilityEvent(AccessibilityEvent)}.
     *
     * @see AccessibilityEvent
     * @see AccessibilityManager
     *
     * @param eventType The event type.
     */
    public void sendAccessibilityEvent(int eventType);

    /**
     * Handles the request for sending an {@link AccessibilityEvent}. The
     * method does not guarantee to check if accessibility is on before
     * sending the event for dispatch. It is responsibility of the caller
     * to do the check via calling {@link AccessibilityManager#isEnabled()
     * AccessibilityManager.isEnabled()}.
     *
     * @see AccessibilityEvent
     * @see AccessibilityManager
     *
     * @param event The event.
     */
    public void sendAccessibilityEventUnchecked(AccessibilityEvent event);
}
