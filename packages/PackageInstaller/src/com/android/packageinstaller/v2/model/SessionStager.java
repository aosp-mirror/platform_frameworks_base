/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.model;

import static android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH;

import android.content.Context;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import androidx.lifecycle.MutableLiveData;
import com.android.packageinstaller.v2.model.InstallRepository.SessionStageListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SessionStager extends AsyncTask<Void, Integer, SessionInfo> {

    private static final String TAG = SessionStager.class.getSimpleName();
    private final Context mContext;
    private final Uri mUri;
    private final int mStagedSessionId;
    private final MutableLiveData<Integer> mProgressLiveData = new MutableLiveData<>(0);
    private final SessionStageListener mListener;

    SessionStager(Context context, Uri uri, int stagedSessionId, SessionStageListener listener) {
        mContext = context;
        mUri = uri;
        mStagedSessionId = stagedSessionId;
        mListener = listener;
    }

    @Override
    protected PackageInstaller.SessionInfo doInBackground(Void... params) {
        PackageInstaller pi = mContext.getPackageManager().getPackageInstaller();
        try (PackageInstaller.Session session = pi.openSession(mStagedSessionId);
            InputStream in = mContext.getContentResolver().openInputStream(mUri)) {
            session.setStagingProgress(0);

            if (in == null) {
                return null;
            }
            final long sizeBytes = getContentSizeBytes();
            mProgressLiveData.postValue(sizeBytes > 0 ? 0 : -1);

            long totalRead = 0;
            try (OutputStream out = session.openWrite("PackageInstaller", 0, sizeBytes)) {
                byte[] buffer = new byte[1024 * 1024];
                while (true) {
                    int numRead = in.read(buffer);

                    if (numRead == -1) {
                        session.fsync(out);
                        break;
                    }

                    if (isCancelled()) {
                        break;
                    }

                    out.write(buffer, 0, numRead);
                    if (sizeBytes > 0) {
                        totalRead += numRead;
                        float fraction = ((float) totalRead / (float) sizeBytes);
                        session.setStagingProgress(fraction);
                        publishProgress((int) (fraction * 100.0));
                    }
                }
            }
            return pi.getSessionInfo(mStagedSessionId);
        } catch (IOException | SecurityException | IllegalStateException
                 | IllegalArgumentException e) {
            Log.w(TAG, "Error staging apk from content URI", e);
            return null;
        }
    }

    private long getContentSizeBytes() {
        try (AssetFileDescriptor afd = mContext.getContentResolver()
            .openAssetFileDescriptor(mUri, "r")) {
            return afd != null ? afd.getLength() : UNKNOWN_LENGTH;
        } catch (IOException e) {
            Log.w(TAG, "Failed to open asset file descriptor", e);
            return UNKNOWN_LENGTH;
        }
    }

    public MutableLiveData<Integer> getProgress() {
        return mProgressLiveData;
    }

    @Override
    protected void onProgressUpdate(Integer... progress) {
        if (progress != null && progress.length > 0) {
            mProgressLiveData.setValue(progress[0]);
        }
    }

    @Override
    protected void onPostExecute(SessionInfo sessionInfo) {
        if (sessionInfo == null || !sessionInfo.isActive()
            || sessionInfo.getResolvedBaseApkPath() == null) {
            Log.w(TAG, "Session info is invalid: " + sessionInfo);
            mListener.onStagingFailure();
            return;
        }
        mListener.onStagingSuccess(sessionInfo);
    }
}
