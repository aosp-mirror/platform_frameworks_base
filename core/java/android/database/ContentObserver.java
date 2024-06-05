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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.UserIdInt;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver.NotifyFlags;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.Executor;

/**
 * Receives call backs for changes to content.
 * Must be implemented by objects which are added to a {@link ContentObservable}.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public abstract class ContentObserver {
    /**
     * Starting in {@link android.os.Build.VERSION_CODES#R}, there is a new
     * public API overload {@link #onChange(boolean, Uri, int)} that delivers a
     * {@code int flags} argument.
     * <p>
     * Some apps may be relying on a previous hidden API that delivered a
     * {@code int userId} argument, and this change is used to control delivery
     * of the new {@code int flags} argument in its place.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion=android.os.Build.VERSION_CODES.Q)
    private static final long ADD_CONTENT_OBSERVER_FLAGS = 150939131L;
    private final Object mLock = new Object();
    private Transport mTransport; // guarded by mLock

    Handler mHandler;
    private final Executor mExecutor;

    /**
     * Creates a content observer.
     *
     * @param handler The handler to run {@link #onChange} on, or null if none.
     */
    public ContentObserver(Handler handler) {
        mHandler = handler;
        mExecutor = null;
    }

    /**
     * @hide
     * Creates a content observer with an executor.
     *
     * @param executor The executor to run {@link #onChange} on, or null if none.
     * @param unused a second argument to avoid source incompatibility.
     */
    public ContentObserver(@Nullable Executor executor, int unused) {
        mExecutor = executor;
    }

    /**
     * Gets access to the binder transport object. Not for public consumption.
     *
     * {@hide}
     */
    public IContentObserver getContentObserver() {
        synchronized (mLock) {
            if (mTransport == null) {
                mTransport = new Transport(this);
            }
            return mTransport;
        }
    }

    /**
     * Gets access to the binder transport object, and unlinks the transport object
     * from the ContentObserver. Not for public consumption.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public IContentObserver releaseContentObserver() {
        synchronized (mLock) {
            final Transport oldTransport = mTransport;
            if (oldTransport != null) {
                oldTransport.releaseContentObserver();
                mTransport = null;
            }
            return oldTransport;
        }
    }

    /**
     * Returns true if this observer is interested receiving self-change notifications.
     *
     * Subclasses should override this method to indicate whether the observer
     * is interested in receiving notifications for changes that it made to the
     * content itself.
     *
     * @return True if self-change notifications should be delivered to the observer.
     */
    public boolean deliverSelfNotifications() {
        return false;
    }

    /**
     * This method is called when a content change occurs.
     * <p>
     * Subclasses should override this method to handle content changes.
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     */
    public void onChange(boolean selfChange) {
        // Do nothing.  Subclass should override.
    }

    /**
     * This method is called when a content change occurs.
     * Includes the changed content Uri when available.
     * <p>
     * Subclasses should override this method to handle content changes. To
     * ensure correct operation on older versions of the framework that did not
     * provide richer arguments, applications should implement all overloads.
     * <p>
     * Example implementation:
     * <pre><code>
     * // Implement the onChange(boolean) method to delegate the change notification to
     * // the onChange(boolean, Uri) method to ensure correct operation on older versions
     * // of the framework that did not have the onChange(boolean, Uri) method.
     * {@literal @Override}
     * public void onChange(boolean selfChange) {
     *     onChange(selfChange, null);
     * }
     *
     * // Implement the onChange(boolean, Uri) method to take advantage of the new Uri argument.
     * {@literal @Override}
     * public void onChange(boolean selfChange, Uri uri) {
     *     // Handle change.
     * }
     * </code></pre>
     * </p>
     *
     * @param selfChange True if this is a self-change notification.
     * @param uri The Uri of the changed content.
     */
    public void onChange(boolean selfChange, @Nullable Uri uri) {
        onChange(selfChange);
    }

    /**
     * This method is called when a content change occurs. Includes the changed
     * content Uri when available.
     * <p>
     * Subclasses should override this method to handle content changes. To
     * ensure correct operation on older versions of the framework that did not
     * provide richer arguments, applications should implement all overloads.
     *
     * @param selfChange True if this is a self-change notification.
     * @param uri The Uri of the changed content.
     * @param flags Flags indicating details about this change.
     */
    public void onChange(boolean selfChange, @Nullable Uri uri, @NotifyFlags int flags) {
        onChange(selfChange, uri);
    }

    /**
     * This method is called when a content change occurs. Includes the changed
     * content Uris when available.
     * <p>
     * Subclasses should override this method to handle content changes. To
     * ensure correct operation on older versions of the framework that did not
     * provide richer arguments, applications should implement all overloads.
     *
     * @param selfChange True if this is a self-change notification.
     * @param uris The Uris of the changed content.
     * @param flags Flags indicating details about this change.
     */
    public void onChange(boolean selfChange, @NonNull Collection<Uri> uris,
            @NotifyFlags int flags) {
        for (Uri uri : uris) {
            onChange(selfChange, uri, flags);
        }
    }

    /**
     * This method is called when a content change occurs. Includes the changed
     * content Uris when available.
     * <p>
     * Subclasses should override this method to handle content changes. To
     * ensure correct operation on older versions of the framework that did not
     * provide richer arguments, applications should implement all overloads.
     *
     * @param selfChange True if this is a self-change notification.
     * @param uris The Uris of the changed content.
     * @param flags Flags indicating details about this change.
     * @param user The corresponding {@link UserHandle} for the current notification.
     *
     * @hide
     */
    @SystemApi
    public void onChange(boolean selfChange, @NonNull Collection<Uri> uris,
            @NotifyFlags int flags, @NonNull UserHandle user) {
        onChange(selfChange, uris, user.getIdentifier());
    }

    /** @hide */
    public void onChange(boolean selfChange, @NonNull Collection<Uri> uris,
            @NotifyFlags int flags, @UserIdInt int userId) {
        // There are dozens of people relying on the hidden API inside the
        // system UID, so hard-code the old behavior for all of them; for
        // everyone else we gate based on a specific change
        if (!isChangeEnabledAddContentObserverFlags()
                || android.os.Process.myUid() == android.os.Process.SYSTEM_UID) {
            // Deliver userId through argument to preserve hidden API behavior
            onChange(selfChange, uris, flags, UserHandle.of(userId));
        } else {
            onChange(selfChange, uris, flags);
        }
    }

    @android.ravenwood.annotation.RavenwoodReplace
    private static boolean isChangeEnabledAddContentObserverFlags() {
        return CompatChanges.isChangeEnabled(ADD_CONTENT_OBSERVER_FLAGS);
    }

    private static boolean isChangeEnabledAddContentObserverFlags$ravenwood() {
        return true;
    }

    /**
     * Dispatches a change notification to the observer.
     * <p>
     * If a {@link Handler} was supplied to the {@link ContentObserver}
     * constructor, then a call to the {@link #onChange} method is posted to the
     * handler's message queue. Otherwise, the {@link #onChange} method is
     * invoked immediately on this thread.
     *
     * @deprecated Callers should migrate towards using a richer overload that
     *             provides more details about the change, such as
     *             {@link #dispatchChange(boolean, Collection, int)}.
     */
    @Deprecated
    public final void dispatchChange(boolean selfChange) {
        dispatchChange(selfChange, null);
    }

    /**
     * Dispatches a change notification to the observer. Includes the changed
     * content Uri when available.
     * <p>
     * If a {@link Handler} was supplied to the {@link ContentObserver}
     * constructor, then a call to the {@link #onChange} method is posted to the
     * handler's message queue. Otherwise, the {@link #onChange} method is
     * invoked immediately on this thread.
     *
     * @param selfChange True if this is a self-change notification.
     * @param uri The Uri of the changed content.
     */
    public final void dispatchChange(boolean selfChange, @Nullable Uri uri) {
        dispatchChange(selfChange, uri, 0);
    }

    /**
     * Dispatches a change notification to the observer. Includes the changed
     * content Uri when available.
     * <p>
     * If a {@link Handler} was supplied to the {@link ContentObserver}
     * constructor, then a call to the {@link #onChange} method is posted to the
     * handler's message queue. Otherwise, the {@link #onChange} method is
     * invoked immediately on this thread.
     *
     * @param selfChange True if this is a self-change notification.
     * @param uri The Uri of the changed content.
     * @param flags Flags indicating details about this change.
     */
    public final void dispatchChange(boolean selfChange, @Nullable Uri uri,
            @NotifyFlags int flags) {
        dispatchChange(selfChange, Arrays.asList(uri), flags);
    }

    /**
     * Dispatches a change notification to the observer. Includes the changed
     * content Uris when available.
     * <p>
     * If a {@link Handler} was supplied to the {@link ContentObserver}
     * constructor, then a call to the {@link #onChange} method is posted to the
     * handler's message queue. Otherwise, the {@link #onChange} method is
     * invoked immediately on this thread.
     *
     * @param selfChange True if this is a self-change notification.
     * @param uris The Uri of the changed content.
     * @param flags Flags indicating details about this change.
     */
    public final void dispatchChange(boolean selfChange, @NonNull Collection<Uri> uris,
            @NotifyFlags int flags) {
        dispatchChange(selfChange, uris, flags, UserHandle.getCallingUserId());
    }

    /** @hide */
    public final void dispatchChange(boolean selfChange, @NonNull Collection<Uri> uris,
            @NotifyFlags int flags, @UserIdInt int userId) {
        if (mExecutor != null) {
            mExecutor.execute(() -> {
                onChange(selfChange, uris, flags, userId);
            });
        } else if (mHandler != null) {
            // Supporting Handler directly rather than wrapping in a HandlerExecutor
            //  avoids introducing a RejectedExecutionException for legacy code when
            //  the post fails.
            mHandler.post(() -> {
                onChange(selfChange, uris, flags, userId);
            });
        } else {
            onChange(selfChange, uris, flags, userId);
        }
    }

    private static final class Transport extends IContentObserver.Stub {
        private ContentObserver mContentObserver;

        public Transport(ContentObserver contentObserver) {
            mContentObserver = contentObserver;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            // This is kept intact purely for apps using hidden APIs, to
            // redirect to the updated implementation
            onChangeEtc(selfChange, new Uri[] { uri }, 0, userId);
        }

        @Override
        public void onChangeEtc(boolean selfChange, Uri[] uris, int flags, int userId) {
            ContentObserver contentObserver = mContentObserver;
            if (contentObserver != null) {
                contentObserver.dispatchChange(selfChange, Arrays.asList(uris), flags, userId);
            }
        }

        public void releaseContentObserver() {
            mContentObserver = null;
        }
    }
}
