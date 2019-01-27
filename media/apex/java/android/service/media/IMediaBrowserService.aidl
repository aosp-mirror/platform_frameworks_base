// Copyright 2014 Google Inc. All Rights Reserved.

package android.service.media;

import android.service.media.IMediaBrowserServiceCallbacks;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Media API allows clients to browse through hierarchy of a user’s media collection,
 * playback a specific media entry and interact with the now playing queue.
 * @hide
 */
oneway interface IMediaBrowserService {
    void connect(String pkg, in Bundle rootHints, IMediaBrowserServiceCallbacks callbacks);
    void disconnect(IMediaBrowserServiceCallbacks callbacks);

    void addSubscriptionDeprecated(String uri, IMediaBrowserServiceCallbacks callbacks);
    void removeSubscriptionDeprecated(String uri, IMediaBrowserServiceCallbacks callbacks);

    void getMediaItem(String uri, in ResultReceiver cb, IMediaBrowserServiceCallbacks callbacks);
    void addSubscription(String uri, in IBinder token, in Bundle options,
            IMediaBrowserServiceCallbacks callbacks);
    void removeSubscription(String uri, in IBinder token, IMediaBrowserServiceCallbacks callbacks);
}
