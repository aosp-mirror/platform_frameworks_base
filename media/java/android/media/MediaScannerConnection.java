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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.IMediaScannerListener;
import android.media.IMediaScannerService;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;


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

    private Context mContext;
    private MediaScannerConnectionClient mClient;
    private IMediaScannerService mService;
    private boolean mConnected; // true if connect() has been called since last disconnect()

    private final IMediaScannerListener.Stub mListener = new IMediaScannerListener.Stub() {
        public void scanCompleted(String path, Uri uri) {
            MediaScannerConnectionClient client = mClient;
            if (client != null) {
                client.onScanCompleted(path, uri);
            }
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
            if (!mConnected) {
                Intent intent = new Intent(IMediaScannerService.class.getName());
                mContext.bindService(intent, this, Context.BIND_AUTO_CREATE);
                mConnected = true;
            }
        }
    }

    /**
     * Releases the connection to the media scanner service.
     */
    public void disconnect() {
        synchronized (this) {
            if (mConnected) {
                if (false) {
                    Log.v(TAG, "Disconnecting from Media Scanner");
                }
                try {
                    mContext.unbindService(this);
                } catch (IllegalArgumentException ex) {
                    if (false) {
                        Log.v(TAG, "disconnect failed: " + ex);
                    }
                }
                mConnected = false;
            }
        }
    }

    /**
     * Returns whether we are connected to the media scanner service
     * @return true if we are connected, false otherwise
     */
    public synchronized boolean isConnected() {
        return (mService != null && mConnected);
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
            if (mService == null || !mConnected) {
                throw new IllegalStateException("not connected to MediaScannerService");
            }
            try {
                if (false) {
                    Log.v(TAG, "Scanning file " + path);
                }
                mService.requestScanFile(path, mimeType, mListener);
            } catch (RemoteException e) {
                if (false) {
                    Log.d(TAG, "Failed to scan file " + path);
                }
            }
        }
    }

    static class ClientProxy implements MediaScannerConnectionClient {
        final String[] mPaths;
        final String[] mMimeTypes;
        final OnScanCompletedListener mClient;
        MediaScannerConnection mConnection;
        int mNextPath;

        ClientProxy(String[] paths, String[] mimeTypes, OnScanCompletedListener client) {
            mPaths = paths;
            mMimeTypes = mimeTypes;
            mClient = client;
        }

        public void onMediaScannerConnected() {
            scanNextPath();
        }

        public void onScanCompleted(String path, Uri uri) {
            if (mClient != null) {
                mClient.onScanCompleted(path, uri);
            }
            scanNextPath();
        }

        void scanNextPath() {
            if (mNextPath >= mPaths.length) {
                mConnection.disconnect();
                return;
            }
            String mimeType = mMimeTypes != null ? mMimeTypes[mNextPath] : null;
            mConnection.scanFile(mPaths[mNextPath], mimeType);
            mNextPath++;
        }
    }

    /**
     * Convenience for constructing a {@link MediaScannerConnection}, calling
     * {@link #connect} on it, and calling {@link #scanFile} with the given
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
     * @see scanFile(String, String)
     */
    public static void scanFile(Context context, String[] paths, String[] mimeTypes,
            OnScanCompletedListener callback) {
        ClientProxy client = new ClientProxy(paths, mimeTypes, callback);
        MediaScannerConnection connection = new MediaScannerConnection(context, client);
        client.mConnection = connection;
        connection.connect();
    }

    /**
     * Part of the ServiceConnection interface.  Do not call.
     */
    public void onServiceConnected(ComponentName className, IBinder service) {
        if (false) {
            Log.v(TAG, "Connected to Media Scanner");
        }
        synchronized (this) {
            mService = IMediaScannerService.Stub.asInterface(service);
            if (mService != null && mClient != null) {
                mClient.onMediaScannerConnected();
            }
        }
    }

    /**
     * Part of the ServiceConnection interface.  Do not call.
     */
    public void onServiceDisconnected(ComponentName className) {
        if (false) {
            Log.v(TAG, "Disconnected from Media Scanner");
        }
        synchronized (this) {
            mService = null;
        }
    }
}
