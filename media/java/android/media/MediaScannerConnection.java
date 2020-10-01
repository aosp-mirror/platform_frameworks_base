/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.media;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import com.android.internal.os.BackgroundThread;

import java.io.File;

/**
 * MediaScannerConnection provides a way for applications to pass a
 * newly created or downloaded media file to the media scanner service.
 * The media scanner service will read metadata from the file and add
 * the file to the media content provider.
 * The MediaScannerConnectionClient provides an interface for the
 * media scanner service to return the Uri for a newly scanned file
 * to the client of the MediaScannerConnection class.
 */
public class MediaScannerConnection implements ServiceConnection {
    private static final String TAG = "MediaScannerConnection";

    private final Context mContext;
    private final MediaScannerConnectionClient mClient;

    private ContentProviderClient mProvider;

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
    private IMediaScannerService mService;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
    private boolean mConnected;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
    private final IMediaScannerListener.Stub mListener = new IMediaScannerListener.Stub() {
        @Override
        public void scanCompleted(String path, Uri uri) {
        }
    };

    /**
     * Interface for notifying clients of the result of scanning a
     * requested media file.
     */
    public interface OnScanCompletedListener {
        /**
         * Called to notify the client when the media scanner has finished
         * scanning a file.
         * @param path the path to the file that has been scanned.
         * @param uri the Uri for the file if the scanning operation succeeded
         * and the file was added to the media database, or null if scanning failed.
         */
        public void onScanCompleted(String path, Uri uri);
    }

    /**
     * An interface for notifying clients of MediaScannerConnection
     * when a connection to the MediaScanner service has been established
     * and when the scanning of a file has completed.
     */
    public interface MediaScannerConnectionClient extends OnScanCompletedListener {
        /**
         * Called to notify the client when a connection to the
         * MediaScanner service has been established.
         */
        public void onMediaScannerConnected();
    }

    /**
     * Constructs a new MediaScannerConnection object.
     * @param context the Context object, required for establishing a connection to
     * the media scanner service.
     * @param client an optional object implementing the MediaScannerConnectionClient
     * interface, for receiving notifications from the media scanner.
     */
    public MediaScannerConnection(Context context, MediaScannerConnectionClient client) {
        mContext = context;
        mClient = client;
    }

    /**
     * Initiates a connection to the media scanner service.
     * {@link MediaScannerConnectionClient#onMediaScannerConnected()}
     * will be called when the connection is established.
     */
    public void connect() {
        synchronized (this) {
            if (mProvider == null) {
                mProvider = mContext.getContentResolver()
                        .acquireContentProviderClient(MediaStore.AUTHORITY);
                if (mClient != null) {
                    mClient.onMediaScannerConnected();
                }
            }
        }
    }

    /**
     * Releases the connection to the media scanner service.
     */
    public void disconnect() {
        synchronized (this) {
            if (mProvider != null) {
                mProvider.close();
                mProvider = null;
            }
        }
    }

    /**
     * Returns whether we are connected to the media scanner service
     * @return true if we are connected, false otherwise
     */
    public synchronized boolean isConnected() {
        return (mProvider != null);
    }

    /**
     * Requests the media scanner to scan a file.
     * Success or failure of the scanning operation cannot be determined until
     * {@link MediaScannerConnectionClient#onScanCompleted(String, Uri)} is called.
     *
     * @param path the path to the file to be scanned.
     * @param mimeType  an optional mimeType for the file.
     * If mimeType is null, then the mimeType will be inferred from the file extension.
     */
     public void scanFile(String path, String mimeType) {
        synchronized (this) {
            if (mProvider == null) {
                throw new IllegalStateException("not connected to MediaScannerService");
            }
            BackgroundThread.getExecutor().execute(() -> {
                final Uri uri = scanFileQuietly(mProvider, new File(path));
                runCallBack(mContext, mClient, path, uri);
            });
        }
    }

    /**
     * Convenience for constructing a {@link MediaScannerConnection}, calling
     * {@link #connect} on it, and calling {@link #scanFile(String, String)} with the given
     * <var>path</var> and <var>mimeType</var> when the connection is
     * established.
     * @param context The caller's Context, required for establishing a connection to
     * the media scanner service.
     * Success or failure of the scanning operation cannot be determined until
     * {@link MediaScannerConnectionClient#onScanCompleted(String, Uri)} is called.
     * @param paths Array of paths to be scanned.
     * @param mimeTypes Optional array of MIME types for each path.
     * If mimeType is null, then the mimeType will be inferred from the file extension.
     * @param callback Optional callback through which you can receive the
     * scanned URI and MIME type; If null, the file will be scanned but
     * you will not get a result back.
     * @see #scanFile(String, String)
     */
    public static void scanFile(Context context, String[] paths, String[] mimeTypes,
            OnScanCompletedListener callback) {
        BackgroundThread.getExecutor().execute(() -> {
            try (ContentProviderClient client = context.getContentResolver()
                    .acquireContentProviderClient(MediaStore.AUTHORITY)) {
                for (String path : paths) {
                    final Uri uri = scanFileQuietly(client, new File(path));
                    runCallBack(context, callback, path, uri);
                }
            }
        });
    }

    private static Uri scanFileQuietly(ContentProviderClient client, File file) {
        Uri uri = null;
        try {
            uri = MediaStore.scanFile(ContentResolver.wrap(client), file.getCanonicalFile());
            Log.d(TAG, "Scanned " + file + " to " + uri);
        } catch (Exception e) {
            Log.w(TAG, "Failed to scan " + file + ": " + e);
        }
        return uri;
    }

    private static void runCallBack(Context context, OnScanCompletedListener callback,
            String path, Uri uri) {
        if (callback != null) {
            // Ignore exceptions from callback to avoid calling app from crashing.
            // Don't ignore exceptions for apps targeting 'R' or higher.
            try {
                callback.onScanCompleted(path, uri);
            } catch (Throwable e) {
                if (context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R) {
                    throw e;
                } else {
                    Log.w(TAG, "Ignoring exception from callback for backward compatibility", e);
                }
            }
        }
    }

    @Deprecated
    static class ClientProxy implements MediaScannerConnectionClient {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        final String[] mPaths;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        final String[] mMimeTypes;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        final OnScanCompletedListener mClient;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        MediaScannerConnection mConnection;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        int mNextPath;

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        ClientProxy(String[] paths, String[] mimeTypes, OnScanCompletedListener client) {
            mPaths = paths;
            mMimeTypes = mimeTypes;
            mClient = client;
        }

        @Override
        public void onMediaScannerConnected() {
        }

        @Override
        public void onScanCompleted(String path, Uri uri) {
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.O)
        void scanNextPath() {
        }
    }

    /**
     * Part of the ServiceConnection interface.  Do not call.
     */
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
        // No longer needed
    }

    /**
     * Part of the ServiceConnection interface.  Do not call.
     */
    @Override
    public void onServiceDisconnected(ComponentName className) {
        // No longer needed
    }
}
