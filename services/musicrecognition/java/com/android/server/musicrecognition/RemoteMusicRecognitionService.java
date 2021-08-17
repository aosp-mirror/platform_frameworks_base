/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.musicrecognition;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioFormat;
import android.media.musicrecognition.IMusicRecognitionAttributionTagCallback;
import android.media.musicrecognition.IMusicRecognitionService;
import android.media.musicrecognition.MusicRecognitionService;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.format.DateUtils;

import com.android.internal.infra.AbstractMultiplePendingRequestsRemoteService;
import com.android.server.musicrecognition.MusicRecognitionManagerPerUserService.MusicRecognitionServiceCallback;

import java.util.concurrent.CompletableFuture;


/** Remote connection to an instance of {@link MusicRecognitionService}. */
public class RemoteMusicRecognitionService extends
        AbstractMultiplePendingRequestsRemoteService<RemoteMusicRecognitionService,
                IMusicRecognitionService> {

    // Maximum time allotted for the remote service to return a result. Up to 24s of audio plus
    // time to fingerprint and make rpcs.
    private static final long TIMEOUT_IDLE_BIND_MILLIS = 40 * DateUtils.SECOND_IN_MILLIS;

    // Allows the remote service to send back a result.
    private final MusicRecognitionServiceCallback
            mServerCallback;

    public RemoteMusicRecognitionService(Context context, ComponentName serviceName,
            int userId, MusicRecognitionManagerPerUserService perUserService,
            MusicRecognitionServiceCallback callback,
            boolean bindInstantServiceAllowed, boolean verbose) {
        super(context, MusicRecognitionService.ACTION_MUSIC_SEARCH_LOOKUP, serviceName, userId,
                perUserService,
                context.getMainThreadHandler(),
                // Prevents the service from having its permissions stripped while in background.
                Context.BIND_INCLUDE_CAPABILITIES | (bindInstantServiceAllowed
                        ? Context.BIND_ALLOW_INSTANT : 0), verbose,
                /* initialCapacity= */ 1);
        mServerCallback = callback;
    }

    @NonNull
    @Override
    protected IMusicRecognitionService getServiceInterface(@NonNull IBinder service) {
        return IMusicRecognitionService.Stub.asInterface(service);
    }

    @Override
    protected long getTimeoutIdleBindMillis() {
        return TIMEOUT_IDLE_BIND_MILLIS;
    }

    MusicRecognitionServiceCallback getServerCallback() {
        return mServerCallback;
    }

    /**
     * Required, but empty since we don't need to notify the callback implementation of the request
     * results.
     */
    interface Callbacks extends VultureCallback<RemoteMusicRecognitionService> {}

    /**
     * Sends the given descriptor to the app's {@link MusicRecognitionService} to read the
     * audio.
     */
    public void onAudioStreamStarted(@NonNull ParcelFileDescriptor fd,
            @NonNull AudioFormat audioFormat) {
        scheduleAsyncRequest(
                binder -> binder.onAudioStreamStarted(fd, audioFormat, mServerCallback));
    }


    /**
     * Returns the name of the <attribution> tag defined in the remote service's manifest.
     */
    public CompletableFuture<String> getAttributionTag() {
        CompletableFuture<String> attributionTagFuture = new CompletableFuture<String>();
        scheduleAsyncRequest(
                binder -> binder.getAttributionTag(
                    new IMusicRecognitionAttributionTagCallback.Stub() {
                        @Override
                        public void onAttributionTag(String tag) throws RemoteException {
                            attributionTagFuture.complete(tag);
                        }
                    }));
        return attributionTagFuture;
    }
}
