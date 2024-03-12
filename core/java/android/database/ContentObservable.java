/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.database;

import android.net.Uri;

/**
 * A specialization of {@link Observable} for {@link ContentObserver}
 * that provides methods for sending notifications to a list of
 * {@link ContentObserver} objects.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ContentObservable extends Observable<ContentObserver> {
    // Even though the generic method defined in Observable would be perfectly
    // fine on its own, we can't delete this overridden method because it would
    // potentially break binary compatibility with existing applications.
    @Override
    public void registerObserver(ContentObserver observer) {
        super.registerObserver(observer);
    }

    /**
     * Invokes {@link ContentObserver#dispatchChange(boolean)} on each observer.
     * <p>
     * If <code>selfChange</code> is true, only delivers the notification
     * to the observer if it has indicated that it wants to receive self-change
     * notifications by implementing {@link ContentObserver#deliverSelfNotifications}
     * to return true.
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     *
     * @deprecated Use {@link #dispatchChange(boolean, Uri)} instead.
     */
    @Deprecated
    public void dispatchChange(boolean selfChange) {
        dispatchChange(selfChange, null);
    }

    /**
     * Invokes {@link ContentObserver#dispatchChange(boolean, Uri)} on each observer.
     * Includes the changed content Uri when available.
     * <p>
     * If <code>selfChange</code> is true, only delivers the notification
     * to the observer if it has indicated that it wants to receive self-change
     * notifications by implementing {@link ContentObserver#deliverSelfNotifications}
     * to return true.
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     * @param uri The Uri of the changed content, or null if unknown.
     */
    public void dispatchChange(boolean selfChange, Uri uri) {
        synchronized(mObservers) {
            for (ContentObserver observer : mObservers) {
                if (!selfChange || observer.deliverSelfNotifications()) {
                    observer.dispatchChange(selfChange, uri);
                }
            }
        }
    }

    /**
     * Invokes {@link ContentObserver#onChange} on each observer.
     *
     * @param selfChange True if this is a self-change notification.
     *
     * @deprecated Use {@link #dispatchChange} instead.
     */
    @Deprecated
    public void notifyChange(boolean selfChange) {
        synchronized(mObservers) {
            for (ContentObserver observer : mObservers) {
                observer.onChange(selfChange, null);
            }
        }
    }
}
