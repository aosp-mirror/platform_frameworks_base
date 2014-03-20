package com.android.onemedia.playback;

import android.media.session.RoutePlaybackControls;
import android.os.Bundle;

/**
 * Renderer for communicating with the OneMRP route
 */
public class OneMRPRenderer extends Renderer {
    private final RoutePlaybackControls mControls;

    public OneMRPRenderer(RoutePlaybackControls controls) {
        super(null, null);
        mControls = controls;
    }

    @Override
    public void setContent(Bundle request) {
        mControls.playNow(request.getString(RequestUtils.EXTRA_KEY_SOURCE));
    }

    @Override
    public boolean onStop() {
        mControls.pause();
        return true;
    }

    @Override
    public boolean onPlay() {
        mControls.resume();
        return true;
    }

    @Override
    public boolean onPause() {
        mControls.pause();
        return true;
    }

    @Override
    public long getSeekPosition() {
        return -1;
    }
}
