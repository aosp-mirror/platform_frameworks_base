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

import android.content.Context;
import android.os.Message;
import android.os.RemoteException;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.StrictMode;
import android.util.Log;

import java.util.ArrayList;

/**
 * Interface to the clipboard service, for placing and retrieving text in
 * the global clipboard.
 *
 * <p>
 * You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService}.
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
 *
 * @see android.content.Context#getSystemService
 */
public class ClipboardManager extends android.text.ClipboardManager {
    private final static Object sStaticLock = new Object();
    private static IClipboard sService;

    private final Context mContext;

    private final ArrayList<OnPrimaryClipChangedListener> mPrimaryClipChangedListeners
             = new ArrayList<OnPrimaryClipChangedListener>();

    private final IOnPrimaryClipChangedListener.Stub mPrimaryClipChangedServiceListener
            = new IOnPrimaryClipChangedListener.Stub() {
        public void dispatchPrimaryClipChanged() {
            mHandler.sendEmptyMessage(MSG_REPORT_PRIMARY_CLIP_CHANGED);
        }
    };

    static final int MSG_REPORT_PRIMARY_CLIP_CHANGED = 1;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REPORT_PRIMARY_CLIP_CHANGED:
                    reportPrimaryClipChanged();
            }
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

    static private IClipboard getService() {
        synchronized (sStaticLock) {
            if (sService != null) {
                return sService;
            }
            IBinder b = ServiceManager.getService("clipboard");
            sService = IClipboard.Stub.asInterface(b);
            return sService;
        }
    }

    /** {@hide} */
    public ClipboardManager(Context context, Handler handler) {
        mContext = context;
    }

    /**
     * Sets the current primary clip on the clipboard.  This is the clip that
     * is involved in normal cut and paste operations.
     *
     * @param clip The clipped data item to set.
     */
    public void setPrimaryClip(ClipData clip) {
        try {
            if (clip != null) {
                clip.prepareToLeaveProcess();
            }
            getService().setPrimaryClip(clip, mContext.getOpPackageName());
        } catch (RemoteException e) {
        }
    }

    /**
     * Returns the current primary clip on the clipboard.
     */
    public ClipData getPrimaryClip() {
        try {
            return getService().getPrimaryClip(mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns a description of the current primary clip on the clipboard
     * but not a copy of its data.
     */
    public ClipDescription getPrimaryClipDescription() {
        try {
            return getService().getPrimaryClipDescription(mContext.getOpPackageName());
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns true if there is currently a primary clip on the clipboard.
     */
    public boolean hasPrimaryClip() {
        try {
            return getService().hasPrimaryClip(mContext.getOpPackageName());
        } catch (RemoteException e) {
            return false;
        }
    }

    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener what) {
        synchronized (mPrimaryClipChangedListeners) {
            if (mPrimaryClipChangedListeners.size() == 0) {
                try {
                    getService().addPrimaryClipChangedListener(
                            mPrimaryClipChangedServiceListener, mContext.getOpPackageName());
                } catch (RemoteException e) {
                }
            }
            mPrimaryClipChangedListeners.add(what);
        }
    }

    public void removePrimaryClipChangedListener(OnPrimaryClipChangedListener what) {
        synchronized (mPrimaryClipChangedListeners) {
            mPrimaryClipChangedListeners.remove(what);
            if (mPrimaryClipChangedListeners.size() == 0) {
                try {
                    getService().removePrimaryClipChangedListener(
                            mPrimaryClipChangedServiceListener);
                } catch (RemoteException e) {
                }
            }
        }
    }

    /**
     * @deprecated Use {@link #getPrimaryClip()} instead.  This retrieves
     * the primary clip and tries to coerce it to a string.
     */
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
    public void setText(CharSequence text) {
        setPrimaryClip(ClipData.newPlainText(null, text));
    }

    /**
     * @deprecated Use {@link #hasPrimaryClip()} instead.
     */
    public boolean hasText() {
        try {
            return getService().hasClipboardText(mContext.getOpPackageName());
        } catch (RemoteException e) {
            return false;
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
