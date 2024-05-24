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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

import java.util.ArrayList;
import java.util.Objects;

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
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ClipboardManager extends android.text.ClipboardManager {

    /**
     * DeviceConfig property, within the clipboard namespace, that determines whether notifications
     * are shown when an app accesses clipboard. This may be overridden by a user-controlled
     * setting.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_SHOW_ACCESS_NOTIFICATIONS =
            "show_access_notifications";

    /**
     * Default value for the DeviceConfig property that determines whether notifications are shown
     * when an app accesses clipboard.
     *
     * @hide
     */
    public static final boolean DEVICE_CONFIG_DEFAULT_SHOW_ACCESS_NOTIFICATIONS = true;

    /**
     * DeviceConfig property, within the clipboard namespace, that determines whether VirtualDevices
     * are allowed to have siloed Clipboards for the apps running on them. If false, then clipboard
     * access is blocked entirely for apps running on VirtualDevices.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_ALLOW_VIRTUALDEVICE_SILOS =
            "allow_virtualdevice_silos";

    /**
     * Default value for the DEVICE_CONFIG_ALLOW_VIRTUALDEVICE_SILOS property.
     *
     * @hide
     */
    public static final boolean DEVICE_CONFIG_DEFAULT_ALLOW_VIRTUALDEVICE_SILOS = true;

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
         *
         * <p>This is called when the result of {@link ClipDescription#getClassificationStatus()}
         * changes, as well as when new clip data is set. So in cases where text classification is
         * performed, this callback may be invoked multiple times for the same clip.
         */
        void onPrimaryClipChanged();
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public ClipboardManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mHandler = handler;
        mService = IClipboard.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.CLIPBOARD_SERVICE));
    }

    /**
     * Determine if the Clipboard Access Notifications are enabled
     *
     * @return true if notifications are enabled, false otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_CLIPBOARD_ACCESS_NOTIFICATION)
    @android.ravenwood.annotation.RavenwoodThrow
    public boolean areClipboardAccessNotificationsEnabled() {
        try {
            return mService.areClipboardAccessNotificationsEnabledForUser(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     *
     * Set the enable state of the Clipboard Access Notifications
     * @param enable Whether to enable notifications
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MANAGE_CLIPBOARD_ACCESS_NOTIFICATION)
    @android.ravenwood.annotation.RavenwoodThrow
    public void setClipboardAccessNotificationsEnabled(boolean enable) {
        try {
            mService.setClipboardAccessNotificationsEnabledForUser(enable, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
            Objects.requireNonNull(clip);
            clip.prepareToLeaveProcess(true);
            mService.setPrimaryClip(
                    clip,
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current primary clip on the clipboard, attributed to the specified {@code
     * sourcePackage}. The primary clip is the clip that is involved in normal cut and paste
     * operations.
     *
     * @param clip The clipped data item to set.
     * @param sourcePackage The package name of the app that is the source of the clip data.
     * @throws IllegalArgumentException if the clip is null or contains no items.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SET_CLIP_SOURCE)
    public void setPrimaryClipAsPackage(@NonNull ClipData clip, @NonNull String sourcePackage) {
        try {
            Objects.requireNonNull(clip);
            Objects.requireNonNull(sourcePackage);
            clip.prepareToLeaveProcess(true);
            mService.setPrimaryClipAsPackage(
                    clip,
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId(),
                    sourcePackage);
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
            mService.clearPrimaryClip(
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current primary clip on the clipboard.
     *
     * <em>If the application is not the default IME or does not have input focus this return
     * {@code null}.</em>
     *
     * @see #setPrimaryClip(ClipData)
     */
    public @Nullable ClipData getPrimaryClip() {
        try {
            return mService.getPrimaryClip(
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a description of the current primary clip on the clipboard but not a copy of its
     * data.
     *
     * <p><em>If the application is not the default IME or does not have input focus this return
     * {@code null}.</em>
     *
     * @see #setPrimaryClip(ClipData)
     */
    public @Nullable ClipDescription getPrimaryClipDescription() {
        try {
            return mService.getPrimaryClipDescription(
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if there is currently a primary clip on the clipboard.
     *
     * <em>If the application is not the default IME or the does not have input focus this will
     * return {@code false}.</em>
     */
    public boolean hasPrimaryClip() {
        try {
            return mService.hasPrimaryClip(
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void addPrimaryClipChangedListener(OnPrimaryClipChangedListener what) {
        synchronized (mPrimaryClipChangedListeners) {
            if (mPrimaryClipChangedListeners.isEmpty()) {
                try {
                    mService.addPrimaryClipChangedListener(
                            mPrimaryClipChangedServiceListener,
                            mContext.getOpPackageName(),
                            mContext.getAttributionTag(),
                            mContext.getUserId(),
                            mContext.getDeviceId());
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
                            mPrimaryClipChangedServiceListener,
                            mContext.getOpPackageName(),
                            mContext.getAttributionTag(),
                            mContext.getUserId(),
                            mContext.getDeviceId());
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
            return mService.hasClipboardText(
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the package name of the source of the current primary clip, or null if there is no
     * primary clip or if a source is not available.
     *
     * @hide
     */
    @TestApi
    @Nullable
    @RequiresPermission(Manifest.permission.SET_CLIP_SOURCE)
    public String getPrimaryClipSource() {
        try {
            return mService.getPrimaryClipSource(
                    mContext.getOpPackageName(),
                    mContext.getAttributionTag(),
                    mContext.getUserId(),
                    mContext.getDeviceId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @UnsupportedAppUsage
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
