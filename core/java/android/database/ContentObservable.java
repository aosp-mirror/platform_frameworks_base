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

/**
 * A specialization of Observable for ContentObserver that provides methods for
 * invoking the various callback methods of ContentObserver.
 */
public class ContentObservable extends Observable<ContentObserver> {

    @Override
    public void registerObserver(ContentObserver observer) {
        super.registerObserver(observer);
    }

    /**
     * invokes dispatchUpdate on each observer, unless the observer doesn't want
     * self-notifications and the update is from a self-notification
     * @param selfChange
     */
    public void dispatchChange(boolean selfChange) {
        synchronized(mObservers) {
            for (ContentObserver observer : mObservers) {
                if (!selfChange || observer.deliverSelfNotifications()) {
                    observer.dispatchChange(selfChange);
                }
            }
        }
    }

    /**
     * invokes onChange on each observer
     * @param selfChange
     */
    public void notifyChange(boolean selfChange) {
        synchronized(mObservers) {
            for (ContentObserver observer : mObservers) {
                observer.onChange(selfChange);
            }
        }
    }
}
