// Copyright 2014 Google Inc. All Rights Reserved.

package android.media.browse;

import android.content.res.Configuration;
import android.media.browse.IMediaBrowserServiceCallbacks;
import android.net.Uri;
import android.os.Bundle;

/**
 * Media API allows clients to browse through hierarchy of a user’s media collection,
 * playback a specific media entry and interact with the now playing queue.
 * @hide
 */
oneway interface IMediaBrowserService {
    void connect(String pkg, in Bundle rootHints, IMediaBrowserServiceCallbacks callbacks);
    void disconnect(IMediaBrowserServiceCallbacks callbacks);

    void addSubscription(in Uri uri, IMediaBrowserServiceCallbacks callbacks);
    void removeSubscription(in Uri uri, IMediaBrowserServiceCallbacks callbacks);
    void loadIcon(in int seqNum, in Uri uri, int width, int height,
            IMediaBrowserServiceCallbacks callbacks);
}