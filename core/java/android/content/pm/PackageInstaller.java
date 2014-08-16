/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.FileBridge;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.ExceptionUtils;
import android.util.Log;

import com.android.internal.util.IndentingPrintWriter;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Offers the ability to install, upgrade, and remove applications on the
 * device. This includes support for apps packaged either as a single
 * "monolithic" APK, or apps packaged as multiple "split" APKs.
 * <p>
 * An app is delivered for installation through a
 * {@link PackageInstaller.Session}, which any app can create. Once the session
 * is created, the installer can stream one or more APKs into place until it
 * decides to either commit or destroy the session. Committing may require user
 * intervention to complete the installation.
 * <p>
 * Sessions can install brand new apps, upgrade existing apps, or add new splits
 * into an existing app.
 * <p>
 * Apps packaged as multiple split APKs always consist of a single "base" APK
 * (with a {@code null} split name) and zero or more "split" APKs (with unique
 * split names). Any subset of these APKs can be installed together, as long as
 * the following constraints are met:
 * <ul>
 * <li>All APKs must have the exact same package name, version code, and signing
 * certificates.
 * <li>All APKs must have unique split names.
 * <li>All installations must contain a single base APK.
 * </ul>
 */
public class PackageInstaller {
    private static final String TAG = "PackageInstaller";

    /**
     * Activity Action: Show details about a particular install session. This
     * may surface actions such as pause, resume, or cancel.
     * <p>
     * This should always be scoped to the installer package that owns the
     * session. Clients should use {@link SessionInfo#getDetailsIntent()} to
     * build this intent correctly.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SESSION_DETAILS = "android.content.pm.action.SESSION_DETAILS";

    /** {@hide} */
    public static final String
            ACTION_CONFIRM_PERMISSIONS = "android.content.pm.action.CONFIRM_PERMISSIONS";

    /**
     * An integer session ID.
     *
     * @see #ACTION_SESSION_DETAILS
     */
    public static final String EXTRA_SESSION_ID = "android.content.pm.extra.SESSION_ID";

    public static final String EXTRA_STATUS = "android.content.pm.extra.STATUS";
    public static final String EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE";

    /**
     * List of package names that are relevant to a status.
     *
     * @see Intent#getStringArrayExtra(String)
     */
    public static final String EXTRA_PACKAGE_NAMES = "android.content.pm.extra.PACKAGE_NAMES";

    /** {@hide} */
    public static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";
    /** {@hide} */
    public static final String EXTRA_LEGACY_BUNDLE = "android.content.pm.extra.LEGACY_BUNDLE";
    /** {@hide} */
    public static final String EXTRA_CALLBACK = "android.content.pm.extra.CALLBACK";

    /**
     * User action is currently required to proceed. You can launch the intent
     * activity described by {@link Intent#EXTRA_INTENT} to involve the user and
     * continue.
     * <p>
     * You may choose to immediately launch the intent if the user is actively
     * using your app. Otherwise, you should use a notification to guide the
     * user back into your app before launching.
     *
     * @see Intent#getParcelableExtra(String)
     */
    public static final int STATUS_USER_ACTION_REQUIRED = -1;

    /**
     * The operation succeeded.
     */
    public static final int STATUS_SUCCESS = 0;

    /**
     * The operation failed in a generic way. The system will always try to
     * provide a more specific failure reason, but in some rare cases this may
     * be delivered.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE = 1;

    /**
     * The operation failed because it was blocked. For example, a device policy
     * may be blocking the operation, a package verifier may have blocked the
     * operation, or the app may be required for core system operation.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_BLOCKED = 1;

    /**
     * The operation failed because it was actively aborted. For example, the
     * user actively declined requested permissions, or the session was
     * abandoned.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_ABORTED = 2;

    /**
     * The operation failed because one or more of the APKs was invalid. For
     * example, they might be malformed, corrupt, incorrectly signed,
     * mismatched, etc.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INVALID = 3;

    /**
     * The operation failed because it conflicts (or is inconsistent with) with
     * another package already installed on the device. For example, an existing
     * permission, incompatible certificates, etc. The user may be able to
     * uninstall another app to fix the issue.
     * <p>
     * The result may also contain {@link #EXTRA_PACKAGE_NAMES} with the
     * specific packages identified as the cause of the conflict.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_CONFLICT = 4;

    /**
     * The operation failed because of storage issues. For example, the device
     * may be running low on space, or external media may be unavailable. The
     * user may be able to help free space or insert different external media.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_STORAGE = 5;

    /**
     * The operation failed because it is fundamentally incompatible with this
     * device. For example, the app may require a hardware feature that doesn't
     * exist, it may be missing native code for the ABIs supported by the
     * device, or it requires a newer SDK version, etc.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INCOMPATIBLE = 6;

    private final Context mContext;
    private final PackageManager mPm;
    private final IPackageInstaller mInstaller;
    private final int mUserId;
    private final String mInstallerPackageName;

    private final ArrayList<SessionCallbackDelegate> mDelegates = new ArrayList<>();

    /** {@hide} */
    public PackageInstaller(Context context, PackageManager pm, IPackageInstaller installer,
            String installerPackageName, int userId) {
        mContext = context;
        mPm = pm;
        mInstaller = installer;
        mInstallerPackageName = installerPackageName;
        mUserId = userId;
    }

    /**
     * Create a new session using the given parameters, returning a unique ID
     * that represents the session. Once created, the session can be opened
     * multiple times across multiple device boots.
     * <p>
     * The system may automatically destroy sessions that have not been
     * finalized (either committed or abandoned) within a reasonable period of
     * time, typically on the order of a day.
     *
     * @throws IOException if parameters were unsatisfiable, such as lack of
     *             disk space or unavailable media.
     * @return positive, non-zero unique ID that represents the created session.
     *         This ID remains consistent across device reboots until the
     *         session is finalized. IDs are not reused during a given boot.
     */
    public int createSession(@NonNull SessionParams params) throws IOException {
        try {
            return mInstaller.createSession(params, mInstallerPackageName, mUserId);
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Open an existing session to actively perform work. To succeed, the caller
     * must be the owner of the install session.
     */
    public @NonNull Session openSession(int sessionId) {
        try {
            return new Session(mInstaller.openSession(sessionId));
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return details for a specific session. To succeed, the caller must either
     * own this session, or be the current home app.
     */
    public @Nullable SessionInfo getSessionInfo(int sessionId) {
        try {
            return mInstaller.getSessionInfo(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return list of all active install sessions, regardless of the installer.
     * To succeed, the caller must be the current home app.
     */
    public @NonNull List<SessionInfo> getAllSessions() {
        try {
            return mInstaller.getAllSessions(mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Return list of all install sessions owned by the calling app.
     */
    public @NonNull List<SessionInfo> getMySessions() {
        try {
            return mInstaller.getMySessions(mInstallerPackageName, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is only available to the current "installer of record" for the
     * package.
     */
    public void uninstall(@NonNull String packageName, @NonNull IntentSender statusReceiver) {
        try {
            mInstaller.uninstall(packageName, 0, statusReceiver, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void setPermissionsResult(int sessionId, boolean accepted) {
        try {
            mInstaller.setPermissionsResult(sessionId, accepted);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Events for observing session lifecycle.
     * <p>
     * A typical session lifecycle looks like this:
     * <ul>
     * <li>An installer creates a session to indicate pending app delivery. All
     * install details are available at this point.
     * <li>The installer opens the session to deliver APK data. Note that a
     * session may be opened and closed multiple times as network connectivity
     * changes. The installer may deliver periodic progress updates.
     * <li>The installer commits or abandons the session, resulting in the
     * session being finished.
     * </ul>
     */
    public static abstract class SessionCallback {
        /**
         * New session has been created. Details about the session can be
         * obtained from {@link PackageInstaller#getSessionInfo(int)}.
         */
        public abstract void onCreated(int sessionId);

        /**
         * Session has been opened. A session is usually opened when the
         * installer is actively writing data.
         */
        public abstract void onOpened(int sessionId);

        /**
         * Progress for given session has been updated.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by {@link PackageInstaller.Session#setProgress(float)}, as
         * the system may carve out a portion of the overall progress to
         * represent its own internal installation work.
         */
        public abstract void onProgressChanged(int sessionId, float progress);

        /**
         * Session has been closed.
         */
        public abstract void onClosed(int sessionId);

        /**
         * Session has completely finished, either with success or failure.
         */
        public abstract void onFinished(int sessionId, boolean success);
    }

    /** {@hide} */
    private static class SessionCallbackDelegate extends IPackageInstallerCallback.Stub implements
            Handler.Callback {
        private static final int MSG_SESSION_CREATED = 1;
        private static final int MSG_SESSION_OPENED = 2;
        private static final int MSG_SESSION_PROGRESS_CHANGED = 3;
        private static final int MSG_SESSION_CLOSED = 4;
        private static final int MSG_SESSION_FINISHED = 5;

        final SessionCallback mCallback;
        final Handler mHandler;

        public SessionCallbackDelegate(SessionCallback callback, Looper looper) {
            mCallback = callback;
            mHandler = new Handler(looper, this);
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SESSION_CREATED:
                    mCallback.onCreated(msg.arg1);
                    return true;
                case MSG_SESSION_OPENED:
                    mCallback.onOpened(msg.arg1);
                    return true;
                case MSG_SESSION_PROGRESS_CHANGED:
                    mCallback.onProgressChanged(msg.arg1, (float) msg.obj);
                    return true;
                case MSG_SESSION_CLOSED:
                    mCallback.onClosed(msg.arg1);
                    return true;
                case MSG_SESSION_FINISHED:
                    mCallback.onFinished(msg.arg1, msg.arg2 != 0);
                    return true;
            }
            return false;
        }

        @Override
        public void onSessionCreated(int sessionId) {
            mHandler.obtainMessage(MSG_SESSION_CREATED, sessionId, 0).sendToTarget();
        }

        @Override
        public void onSessionOpened(int sessionId) {
            mHandler.obtainMessage(MSG_SESSION_OPENED, sessionId, 0).sendToTarget();
        }

        @Override
        public void onSessionProgressChanged(int sessionId, float progress) {
            mHandler.obtainMessage(MSG_SESSION_PROGRESS_CHANGED, sessionId, 0, progress)
                    .sendToTarget();
        }

        @Override
        public void onSessionClosed(int sessionId) {
            mHandler.obtainMessage(MSG_SESSION_CLOSED, sessionId, 0).sendToTarget();
        }

        @Override
        public void onSessionFinished(int sessionId, boolean success) {
            mHandler.obtainMessage(MSG_SESSION_FINISHED, sessionId, success ? 1 : 0)
                    .sendToTarget();
        }
    }

    /**
     * Register to watch for session lifecycle events. To succeed, the caller
     * must be the current home app.
     */
    public void addSessionCallback(@NonNull SessionCallback callback) {
        addSessionCallback(callback, new Handler());
    }

    /**
     * Register to watch for session lifecycle events. To succeed, the caller
     * must be the current home app.
     *
     * @param handler to dispatch callback events through, otherwise uses
     *            calling thread.
     */
    public void addSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        // TODO: remove this temporary guard once we have new prebuilts
        final ApplicationInfo info = mContext.getApplicationInfo();
        if ("com.google.android.googlequicksearchbox".equals(info.packageName)
                && info.versionCode <= 300400070) {
            Log.d(TAG, "Ignoring callback request from old prebuilt");
            return;
        }

        synchronized (mDelegates) {
            final SessionCallbackDelegate delegate = new SessionCallbackDelegate(callback,
                    handler.getLooper());
            try {
                mInstaller.registerCallback(delegate, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
            mDelegates.add(delegate);
        }
    }

    /**
     * Unregister an existing callback.
     */
    public void removeSessionCallback(@NonNull SessionCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<SessionCallbackDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final SessionCallbackDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    try {
                        mInstaller.unregisterCallback(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowAsRuntimeException();
                    }
                    i.remove();
                }
            }
        }
    }

    /**
     * An installation that is being actively staged. For an install to succeed,
     * all existing and new packages must have identical package names, version
     * codes, and signing certificates.
     * <p>
     * A session may contain any number of split packages. If the application
     * does not yet exist, this session must include a base package.
     * <p>
     * If an APK included in this session is already defined by the existing
     * installation (for example, the same split name), the APK in this session
     * will replace the existing APK.
     */
    public static class Session implements Closeable {
        private IPackageInstallerSession mSession;

        /** {@hide} */
        public Session(IPackageInstallerSession session) {
            mSession = session;
        }

        /**
         * Set current progress. Valid values are anywhere between 0 and 1.
         */
        public void setProgress(float progress) {
            try {
                mSession.setClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /** {@hide} */
        public void addProgress(float progress) {
            try {
                mSession.addClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Open a stream to write an APK file into the session.
         * <p>
         * The returned stream will start writing data at the requested offset
         * in the underlying file, which can be used to resume a partially
         * written file. If a valid file length is specified, the system will
         * preallocate the underlying disk space to optimize placement on disk.
         * It's strongly recommended to provide a valid file length when known.
         * <p>
         * You can write data into the returned stream, optionally call
         * {@link #fsync(OutputStream)} as needed to ensure bytes have been
         * persisted to disk, and then close when finished. All streams must be
         * closed before calling {@link #commit(IntentSender)}.
         *
         * @param name arbitrary, unique name of your choosing to identify the
         *            APK being written. You can open a file again for
         *            additional writes (such as after a reboot) by using the
         *            same name. This name is only meaningful within the context
         *            of a single install session.
         * @param offsetBytes offset into the file to begin writing at, or 0 to
         *            start at the beginning of the file.
         * @param lengthBytes total size of the file being written, used to
         *            preallocate the underlying disk space, or -1 if unknown.
         */
        public @NonNull OutputStream openWrite(@NonNull String name, long offsetBytes,
                long lengthBytes) throws IOException {
            try {
                final ParcelFileDescriptor clientSocket = mSession.openWrite(name,
                        offsetBytes, lengthBytes);
                return new FileBridge.FileBridgeOutputStream(clientSocket.getFileDescriptor());
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Ensure that any outstanding data for given stream has been committed
         * to disk. This is only valid for streams returned from
         * {@link #openWrite(String, long, long)}.
         */
        public void fsync(@NonNull OutputStream out) throws IOException {
            if (out instanceof FileBridge.FileBridgeOutputStream) {
                ((FileBridge.FileBridgeOutputStream) out).fsync();
            } else {
                throw new IllegalArgumentException("Unrecognized stream");
            }
        }

        /**
         * Return all APK names contained in this session.
         * <p>
         * This returns all names which have been previously written through
         * {@link #openWrite(String, long, long)} as part of this session.
         */
        public @NonNull String[] getNames() {
            try {
                return mSession.getNames();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Open a stream to read an APK file from the session.
         * <p>
         * This is only valid for names which have been previously written
         * through {@link #openWrite(String, long, long)} as part of this
         * session. For example, this stream may be used to calculate a
         * {@link MessageDigest} of a written APK before committing.
         */
        public @NonNull InputStream openRead(@NonNull String name) throws IOException {
            try {
                final ParcelFileDescriptor pfd = mSession.openRead(name);
                return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Attempt to commit everything staged in this session. This may require
         * user intervention, and so it may not happen immediately. The final
         * result of the commit will be reported through the given callback.
         * <p>
         * Once this method is called, no additional mutations may be performed
         * on the session. If the device reboots before the session has been
         * finalized, you may commit the session again.
         */
        public void commit(@NonNull IntentSender statusReceiver) {
            try {
                mSession.commit(statusReceiver);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Release this session object. You can open the session again if it
         * hasn't been finalized.
         */
        @Override
        public void close() {
            try {
                mSession.close();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }

        /**
         * Completely abandon this session, destroying all staged data and
         * rendering it invalid.
         */
        public void abandon() {
            try {
                mSession.abandon();
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Parameters for creating a new {@link PackageInstaller.Session}.
     */
    public static class SessionParams implements Parcelable {

        /** {@hide} */
        public static final int MODE_INVALID = -1;

        /**
         * Mode for an install session whose staged APKs should fully replace any
         * existing APKs for the target app.
         */
        public static final int MODE_FULL_INSTALL = 1;

        /**
         * Mode for an install session that should inherit any existing APKs for the
         * target app, unless they have been explicitly overridden (based on split
         * name) by the session. For example, this can be used to add one or more
         * split APKs to an existing installation.
         * <p>
         * If there are no existing APKs for the target app, this behaves like
         * {@link #MODE_FULL_INSTALL}.
         */
        public static final int MODE_INHERIT_EXISTING = 2;

        /** {@hide} */
        public int mode = MODE_INVALID;
        /** {@hide} */
        public int installFlags;
        /** {@hide} */
        public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
        /** {@hide} */
        public long sizeBytes = -1;
        /** {@hide} */
        public String appPackageName;
        /** {@hide} */
        public Bitmap appIcon;
        /** {@hide} */
        public String appLabel;
        /** {@hide} */
        public Uri originatingUri;
        /** {@hide} */
        public Uri referrerUri;
        /** {@hide} */
        public String abiOverride;

        /**
         * Construct parameters for a new package install session.
         *
         * @param mode one of {@link #MODE_FULL_INSTALL} or
         *            {@link #MODE_INHERIT_EXISTING} describing how the session
         *            should interact with an existing app.
         */
        public SessionParams(int mode) {
            this.mode = mode;
        }

        /** {@hide} */
        public SessionParams(Parcel source) {
            mode = source.readInt();
            installFlags = source.readInt();
            installLocation = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null);
            appLabel = source.readString();
            originatingUri = source.readParcelable(null);
            referrerUri = source.readParcelable(null);
            abiOverride = source.readString();
        }

        /**
         * Provide value of {@link PackageInfo#installLocation}, which may be used
         * to determine where the app will be staged. Defaults to
         * {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}.
         */
        public void setInstallLocation(int installLocation) {
            this.installLocation = installLocation;
        }

        /**
         * Optionally indicate the total size (in bytes) of all APKs that will be
         * delivered in this session. The system may use this to ensure enough disk
         * space exists before proceeding, or to estimate container size for
         * installations living on external storage.
         *
         * @see PackageInfo#INSTALL_LOCATION_AUTO
         * @see PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL
         */
        public void setSize(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        /**
         * Optionally set the package name of the app being installed. It's strongly
         * recommended that you provide this value when known, so that observers can
         * communicate installing apps to users.
         * <p>
         * If the APKs staged in the session aren't consistent with this package
         * name, the install will fail. Regardless of this value, all APKs in the
         * app must have the same package name.
         */
        public void setAppPackageName(@Nullable String appPackageName) {
            this.appPackageName = appPackageName;
        }

        /**
         * Optionally set an icon representing the app being installed. This should
         * be roughly {@link ActivityManager#getLauncherLargeIconSize()} in both
         * dimensions.
         */
        public void setAppIcon(@Nullable Bitmap appIcon) {
            this.appIcon = appIcon;
        }

        /**
         * Optionally set a label representing the app being installed.
         */
        public void setAppLabel(@Nullable CharSequence appLabel) {
            this.appLabel = (appLabel != null) ? appLabel.toString() : null;
        }

        /**
         * Optionally set the URI where this package was downloaded from. Used for
         * verification purposes.
         *
         * @see Intent#EXTRA_ORIGINATING_URI
         */
        public void setOriginatingUri(@Nullable Uri originatingUri) {
            this.originatingUri = originatingUri;
        }

        /**
         * Optionally set the URI that referred you to install this package. Used
         * for verification purposes.
         *
         * @see Intent#EXTRA_REFERRER
         */
        public void setReferrerUri(@Nullable Uri referrerUri) {
            this.referrerUri = referrerUri;
        }

        /** {@hide} */
        public void dump(IndentingPrintWriter pw) {
            pw.printPair("mode", mode);
            pw.printHexPair("installFlags", installFlags);
            pw.printPair("installLocation", installLocation);
            pw.printPair("sizeBytes", sizeBytes);
            pw.printPair("appPackageName", appPackageName);
            pw.printPair("appIcon", (appIcon != null));
            pw.printPair("appLabel", appLabel);
            pw.printPair("originatingUri", originatingUri);
            pw.printPair("referrerUri", referrerUri);
            pw.printPair("abiOverride", abiOverride);
            pw.println();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mode);
            dest.writeInt(installFlags);
            dest.writeInt(installLocation);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel);
            dest.writeParcelable(originatingUri, flags);
            dest.writeParcelable(referrerUri, flags);
            dest.writeString(abiOverride);
        }

        public static final Parcelable.Creator<SessionParams>
                CREATOR = new Parcelable.Creator<SessionParams>() {
                    @Override
                    public SessionParams createFromParcel(Parcel p) {
                        return new SessionParams(p);
                    }

                    @Override
                    public SessionParams[] newArray(int size) {
                        return new SessionParams[size];
                    }
                };
    }

    /**
     * Details for an active install session.
     */
    public static class SessionInfo implements Parcelable {

        /** {@hide} */
        public int sessionId;
        /** {@hide} */
        public String installerPackageName;
        /** {@hide} */
        public String resolvedBaseCodePath;
        /** {@hide} */
        public float progress;
        /** {@hide} */
        public boolean sealed;
        /** {@hide} */
        public boolean open;

        /** {@hide} */
        public int mode;
        /** {@hide} */
        public long sizeBytes;
        /** {@hide} */
        public String appPackageName;
        /** {@hide} */
        public Bitmap appIcon;
        /** {@hide} */
        public CharSequence appLabel;

        /** {@hide} */
        public SessionInfo() {
        }

        /** {@hide} */
        public SessionInfo(Parcel source) {
            sessionId = source.readInt();
            installerPackageName = source.readString();
            resolvedBaseCodePath = source.readString();
            progress = source.readFloat();
            sealed = source.readInt() != 0;
            open = source.readInt() != 0;

            mode = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null);
            appLabel = source.readString();
        }

        /**
         * Return the ID for this session.
         */
        public int getSessionId() {
            return sessionId;
        }

        /**
         * Return the package name of the app that owns this session.
         */
        public @Nullable String getInstallerPackageName() {
            return installerPackageName;
        }

        /**
         * Return current overall progress of this session, between 0 and 1.
         * <p>
         * Note that this progress may not directly correspond to the value reported
         * by {@link PackageInstaller.Session#setProgress(float)}, as the system may
         * carve out a portion of the overall progress to represent its own internal
         * installation work.
         */
        public float getProgress() {
            return progress;
        }

        /**
         * Return if this session is currently open.
         */
        public boolean isOpen() {
            return open;
        }

        /**
         * Return the package name this session is working with. May be {@code null}
         * if unknown.
         */
        public @Nullable String getAppPackageName() {
            return appPackageName;
        }

        /**
         * Return an icon representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable Bitmap getAppIcon() {
            return appIcon;
        }

        /**
         * Return a label representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable CharSequence getAppLabel() {
            return appLabel;
        }

        /**
         * Return an Intent that can be started to view details about this install
         * session. This may surface actions such as pause, resume, or cancel.
         * <p>
         * In some cases, a matching Activity may not exist, so ensure you safeguard
         * against this.
         *
         * @see PackageInstaller#ACTION_SESSION_DETAILS
         */
        public @Nullable Intent getDetailsIntent() {
            final Intent intent = new Intent(PackageInstaller.ACTION_SESSION_DETAILS);
            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
            intent.setPackage(installerPackageName);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(sessionId);
            dest.writeString(installerPackageName);
            dest.writeString(resolvedBaseCodePath);
            dest.writeFloat(progress);
            dest.writeInt(sealed ? 1 : 0);
            dest.writeInt(open ? 1 : 0);

            dest.writeInt(mode);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel != null ? appLabel.toString() : null);
        }

        public static final Parcelable.Creator<SessionInfo>
                CREATOR = new Parcelable.Creator<SessionInfo>() {
                    @Override
                    public SessionInfo createFromParcel(Parcel p) {
                        return new SessionInfo(p);
                    }

                    @Override
                    public SessionInfo[] newArray(int size) {
                        return new SessionInfo[size];
                    }
                };
    }
}
