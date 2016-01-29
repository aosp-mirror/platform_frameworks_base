// Copyright 2014 Google Inc. All Rights Reserved.

package android.service.media;

import android.content.res.Configuration;
import android.service.media.IMediaBrowserServiceCallbacks;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

/**
 * Media API allows clients to browse through hierarchy of a user’s media collection,
 * playback a specific media entry and interact with the now playing queue.
 * @hide
 */
oneway interface IMediaBrowserService {

    // Warning: DO NOT CHANGE the methods signature and order of methods.
    // A change of the order or the method signatures could break the support library.

    void connect(String pkg, in Bundle rootHints, IMediaBrowserServiceCallbacks callbacks);
    void disconnect(IMediaBrowserServiceCallbacks callbacks);

    void addSubscription(String uri, IMediaBrowserServiceCallbacks callbacks);
    void removeSubscription(String uri, IMediaBrowserServiceCallbacks callbacks);
    void getMediaItem(String uri, in ResultReceiver cb);

    void addSubscriptionWithOptions(String uri, in Bundle options,
            IMediaBrowserServiceCallbacks callbacks);
    void removeSubscriptionWithOptions(String uri, in Bundle options,
            IMediaBrowserServiceCallbacks callbacks);
}
