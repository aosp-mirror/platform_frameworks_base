/**
 * Copyright (c) 2010, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * Interface to the clipboard service, for placing and retrieving text in
 * the global clipboard.
 *
 * <p>
 * The ClipboardManager API itself is very simple: it consists of methods
 * to atomically get and set the current primary clipboard data.  That data
 * is expressed as a {@link ClipData} object, which defines the protocol
 * for data exchange between applications.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using the clipboard framework, read the
 * <a href="{@docRoot}guide/topics/clipboard/copy-paste.html">Copy and Paste</a>
 * developer guide.</p>
 * </div>
 */
@SystemService(Context.CLIPBOARD_SERVICE)
public class ClipboardManager extends android.text.ClipboardManager {
    private final Context mContext;
    private final Handler mHandler;
    private final IClipboard mService;

    private final ArrayList<OnPrimaryClipChangedListener> mPrimaryClipChangedListeners
             = new ArrayList<OnPrimaryClipChangedListener>();

    private final IOnPrimaryClipChangedListener.Stub mPrimaryClipChangedServiceListener
            = new IOnPrimaryClipChangedListener.Stub() {
        @Override
        public void dispatchPrimaryClipChanged() {
            mHandler.post(() -> {
                reportPrimaryClipChanged();
            });
        }
    };

    /**
     * Defines a listener callback that is invoked when the primary clip on the clipboard changes.
     * Objects that want to register a listener call
     * {@link android.content.ClipboardManager#addPrimaryClipChangedListener(OnPrimaryClipChangedListener)
     * addPrimaryClipChangedListener()} with an
     * object that implements OnPrimaryClipChangedListener.
     *
     */
    public interface OnPrimaryClipChangedListener {

        /**
         * Callback that is invoked by {@link android.content.ClipboardManager} when the primary
         * clip changes.
         */
        void onPrimaryClipChanged();
    }

    /** {@hide} */
    public ClipboardManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mHandler = handler;
        mService = IClipboard.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.CLIPBOARD_SERVICE));
    }

    /**
     * Sets the current primary clip on the clipboard.  This is the clip that
     * is involved in normal cut and paste operations.
     *
     * @param clip The clipped data item to set.
     * @see #getPrimaryClip()
     * @see #clearPrimaryClip()
     */
    public void setPrimaryClip(@NonNull ClipData clip) {
        try {
            Preconditions.checkNotNull(clip);
            clip.prepareToLeaveProcess(true);
            mService.setPrimaryClip(clip, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears any current primary clip on the clipboard.
     *
     * @see #setPrimaryClip(ClipData)
     */
    public void clearPrimaryClip() {
        try {
            mService.clearPrimaryClip(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current primary clip on the clipboard.
     *
     * @see #setPrimaryClip(ClipData)
     */
    public @Nullable ClipData getPrimaryClip() {
        try {
            return mService.getPrimaryClip(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a description of the current primary clip on the clipboard
     * but not a copy of its data.
     *
     * @see #setPrimaryClip(ClipData)
     */
    public @Nullable ClipDescription getPrimaryClipDescription() {
        try {
            return mService.getPrimaryClipDescription(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if there is currently a primary clip on the clipboard.
     */
    public boolean hasPrimaryClip() {
        try {
            return mService.hasPrimaryClip(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener what) {
        synchronized (mPrimaryClipChangedListeners) {
            if (mPrimaryClipChangedListeners.isEmpty()) {
                try {
                    mService.addPrimaryClipChangedListener(
                            mPrimaryClipChangedServiceListener, mContext.getOpPackageName());
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            mPrimaryClipChangedListeners.add(what);
        }
    }

    public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener what) {
        synchronized (mPrimaryClipChangedListeners) {
            mPrimaryClipChangedListeners.remove(what);
            if (mPrimaryClipChangedListeners.isEmpty()) {
                try {
                    mService.removePrimaryClipChangedListener(
                            mPrimaryClipChangedServiceListener);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * @deprecated Use {@link #getPrimaryClip()} instead.  This retrieves
     * the primary clip and tries to coerce it to a string.
     */
    @Deprecated
    public CharSequence getText() {
        ClipData clip = getPrimaryClip();
        if (clip != null && clip.getItemCount() > 0) {
            return clip.getItemAt(0).coerceToText(mContext);
        }
        return null;
    }

    /**
     * @deprecated Use {@link #setPrimaryClip(ClipData)} instead.  This
     * creates a ClippedItem holding the given text and sets it as the
     * primary clip.  It has no label or icon.
     */
    @Deprecated
    public void setText(CharSequence text) {
        setPrimaryClip(ClipData.newPlainText(null, text));
    }

    /**
     * @deprecated Use {@link #hasPrimaryClip()} instead.
     */
    @Deprecated
    public boolean hasText() {
        try {
            return mService.hasClipboardText(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void reportPrimaryClipChanged() {
        Object[] listeners;

        synchronized (mPrimaryClipChangedListeners) {
            final int N = mPrimaryClipChangedListeners.size();
            if (N <= 0) {
                return;
            }
            listeners = mPrimaryClipChangedListeners.toArray();
        }

        for (int i=0; i<listeners.length; i++) {
            ((OnPrimaryClipChangedListener)listeners[i]).onPrimaryClipChanged();
        }
    }
}
