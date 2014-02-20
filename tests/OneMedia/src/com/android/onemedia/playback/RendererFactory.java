package com.android.onemedia.playback;

import android.content.Context;
import android.media.MediaRouter;
import android.os.Bundle;
import android.util.Log;

/**
 * TODO: Insert description here.
 */
public class RendererFactory {
    private static final String TAG = "RendererFactory";

    public Renderer createRenderer(MediaRouter.RouteInfo route, Context context, Bundle params) {
        if (route.getPlaybackType() == MediaRouter.RouteInfo.PLAYBACK_TYPE_LOCAL) {
            return new LocalRenderer(context, params);
        }
        Log.e(TAG, "Unable to create renderer for route of playback type "
                + route.getPlaybackType());
        return null;
    }
}
