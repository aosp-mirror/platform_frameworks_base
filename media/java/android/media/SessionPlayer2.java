/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.content.Context;
import android.media.MediaSession2.PlaylistParams;
import android.media.update.ApiLoader;
import android.media.update.SessionPlayer2Provider;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Implementation of the {@link MediaPlayerInterface} which is backed by the {@link MediaPlayer2}
 * @hide
 */
public class SessionPlayer2 implements MediaPlayerInterface {
    private final SessionPlayer2Provider mProvider;

    public SessionPlayer2(Context context) {
        mProvider = ApiLoader.getProvider(context).createSessionPlayer2(context, this);
    }

    @Override
    public void play() {
        mProvider.play_impl();
    }

    @Override
    public void prepare() {
        mProvider.prepare_impl();
    }

    @Override
    public void pause() {
        mProvider.pause_impl();
    }

    @Override
    public void stop() {
        mProvider.stop_impl();
    }

    @Override
    public void skipToPrevious() {
        mProvider.skipToPrevious_impl();
    }

    @Override
    public void skipToNext() {
        mProvider.skipToNext_impl();
    }

    @Override
    public void seekTo(long pos) {
        mProvider.seekTo_impl(pos);
    }

    @Override
    public void fastForward() {
        mProvider.fastForward_impl();
    }

    @Override
    public void rewind() {
        mProvider.rewind_impl();
    }

    @Override
    public PlaybackState2 getPlaybackState() {
        return mProvider.getPlaybackState_impl();
    }

    @Override
    public void setAudioAttributes(AudioAttributes attributes) {
        mProvider.setAudioAttributes_impl(attributes);
    }

    @Override
    public AudioAttributes getAudioAttributes() {
        return mProvider.getAudioAttributes_impl();
    }

    @Override
    public void setPlaylist(List<MediaItem2> playlist) {
        mProvider.setPlaylist_impl(playlist);
    }

    @Override
    public List<MediaItem2> getPlaylist() {
        return mProvider.getPlaylist_impl();
    }

    @Override
    public void setCurrentPlaylistItem(int index) {
        mProvider.setCurrentPlaylistItem_impl(index);
    }

    @Override
    public void setPlaylistParams(PlaylistParams params) {
        mProvider.setPlaylistParams_impl(params);
    }

    @Override
    public void addPlaylistItem(int index, MediaItem2 item) {
        mProvider.addPlaylistItem_impl(index, item);
    }

    @Override
    public void removePlaylistItem(MediaItem2 item) {
        mProvider.removePlaylistItem_impl(item);
    }

    @Override
    public PlaylistParams getPlaylistParams() {
        return mProvider.getPlaylistParams_impl();
    }

    @Override
    public void addPlaybackListener(Executor executor, PlaybackListener listener) {
        mProvider.addPlaybackListener_impl(executor, listener);
    }

    @Override
    public void removePlaybackListener(PlaybackListener listener) {
        mProvider.removePlaybackListener_impl(listener);
    }

    public MediaPlayer2 getPlayer() {
        return mProvider.getPlayer_impl();
    }

    @SystemApi
    public SessionPlayer2Provider getProvider() {
        return mProvider;
    }
}
