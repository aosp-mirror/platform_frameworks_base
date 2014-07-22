// Copyright 2014 Google Inc. All Rights Reserved.

package android.media.browse;

import android.content.pm.ParceledListSlice;
import android.media.session.MediaSession;
import android.net.Uri;

/**
 * Media API allows clients to browse through hierarchy of a user’s media collection,
 * playback a specific media entry and interact with the now playing queue.
 * @hide
 */
oneway interface IMediaBrowserServiceCallbacks {
    /**
     * Invoked when the connected has been established.
     * @param root The root Uri for browsing.
     * @param session The {@link MediaSession.Token media session token} that can be used to control
     * the playback of the media app.
     */
    void onConnect(in Uri root, in MediaSession.Token session);
    void onConnectFailed();
    void onLoadChildren(in Uri uri, in ParceledListSlice list);
}
