package com.android.onemedia.playback;

import android.os.Bundle;
import android.support.media.protocols.MediaPlayerProtocol;
import android.support.media.protocols.MediaPlayerProtocol.MediaInfo;

/**
 * Renderer for communicating with the OneMRP route
 */
public class OneMRPRenderer extends Renderer {
    private final MediaPlayerProtocol mProtocol;

    public OneMRPRenderer(MediaPlayerProtocol protocol) {
        super(null, null);
        mProtocol = protocol;
    }

    @Override
    public void setContent(Bundle request) {
        MediaInfo mediaInfo = new MediaInfo(request.getString(RequestUtils.EXTRA_KEY_SOURCE),
                MediaInfo.STREAM_TYPE_BUFFERED, "audio/mp3");
        mProtocol.load(mediaInfo, true, 0, null);
    }

    @Override
    public boolean onStop() {
        mProtocol.stop(null);
        return true;
    }

    @Override
    public boolean onPlay() {
        mProtocol.play(null);
        return true;
    }

    @Override
    public boolean onPause() {
        mProtocol.pause(null);
        return true;
    }

    @Override
    public long getSeekPosition() {
        return -1;
    }
}
