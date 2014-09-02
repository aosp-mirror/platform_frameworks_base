// Copyright 2014 Google Inc. All Rights Reserved.

package android.service.media;

import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.media.session.MediaSession;
import android.os.Bundle;

/**
 * Media API allows clients to browse through hierarchy of a user’s media collection,
 * playback a specific media entry and interact with the now playing queue.
 * @hide
 */
oneway interface IMediaBrowserServiceCallbacks {
    /**
     * Invoked when the connected has been established.
     * @param root The root media id for browsing.
     * @param session The {@link MediaSession.Token media session token} that can be used to control
     *         the playback of the media app.
     * @param extra Extras returned by the media service.
     */
    void onConnect(String root, in MediaSession.Token session, in Bundle extras);
    void onConnectFailed();
    void onLoadChildren(String mediaId, in ParceledListSlice list);
}
