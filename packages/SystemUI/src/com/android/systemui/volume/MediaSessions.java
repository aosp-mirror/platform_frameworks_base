/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.media.IRemoteVolumeController;
import android.media.MediaMetadata;
import android.media.session.ISessionController;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession.QueueItem;
import android.media.session.MediaSession.Token;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.OnActiveSessionsChangedListener;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Convenience client for all media session updates.  Provides a callback interface for events
 * related to remote media sessions.
 */
public class MediaSessions {
    private static final String TAG = Util.logTag(MediaSessions.class);

    private static final boolean USE_SERVICE_LABEL = false;

    private final Context mContext;
    private final H mHandler;
    private final MediaSessionManager mMgr;
    private final Map<Token, MediaControllerRecord> mRecords = new HashMap<>();
    private final Callbacks mCallbacks;

    private boolean mInit;

    public MediaSessions(Context context, Looper looper, Callbacks callbacks) {
        mContext = context;
        mHandler = new H(looper);
        mMgr = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        mCallbacks = callbacks;
    }

    public void dump(PrintWriter writer) {
        writer.println(getClass().getSimpleName() + " state:");
        writer.print("  mInit: "); writer.println(mInit);
        writer.print("  mRecords.size: "); writer.println(mRecords.size());
        int i = 0;
        for (MediaControllerRecord r : mRecords.values()) {
            dump(++i, writer, r.controller);
        }
    }

    public void init() {
        if (D.BUG) Log.d(TAG, "init");
        // will throw if no permission
        mMgr.addOnActiveSessionsChangedListener(mSessionsListener, null, mHandler);
        mInit = true;
        postUpdateSessions();
        mMgr.setRemoteVolumeController(mRvc);
    }

    protected void postUpdateSessions() {
        if (!mInit) return;
        mHandler.sendEmptyMessage(H.UPDATE_SESSIONS);
    }

    public void destroy() {
        if (D.BUG) Log.d(TAG, "destroy");
        mInit = false;
        mMgr.removeOnActiveSessionsChangedListener(mSessionsListener);
    }

    public void setVolume(Token token, int level) {
        final MediaControllerRecord r = mRecords.get(token);
        if (r == null) {
            Log.w(TAG, "setVolume: No record found for token " + token);
            return;
        }
        if (D.BUG) Log.d(TAG, "Setting level to " + level);
        r.controller.setVolumeTo(level, 0);
    }

    private void onRemoteVolumeChangedH(ISessionController session, int flags) {
        final MediaController controller = new MediaController(mContext, session);
        if (D.BUG) Log.d(TAG, "remoteVolumeChangedH " + controller.getPackageName() + " "
                + Util.audioManagerFlagsToString(flags));
        final Token token = controller.getSessionToken();
        mCallbacks.onRemoteVolumeChanged(token, flags);
    }

    private void onUpdateRemoteControllerH(ISessionController session) {
        final MediaController controller = session != null ? new MediaController(mContext, session)
                : null;
        final String pkg = controller != null ? controller.getPackageName() : null;
        if (D.BUG) Log.d(TAG, "updateRemoteControllerH " + pkg);
        // this may be our only indication that a remote session is changed, refresh
        postUpdateSessions();
    }

    protected void onActiveSessionsUpdatedH(List<MediaController> controllers) {
        if (D.BUG) Log.d(TAG, "onActiveSessionsUpdatedH n=" + controllers.size());
        final Set<Token> toRemove = new HashSet<Token>(mRecords.keySet());
        for (MediaController controller : controllers) {
            final Token token = controller.getSessionToken();
            final PlaybackInfo pi = controller.getPlaybackInfo();
            toRemove.remove(token);
            if (!mRecords.containsKey(token)) {
                final MediaControllerRecord r = new MediaControllerRecord(controller);
                r.name = getControllerName(controller);
                mRecords.put(token, r);
                controller.registerCallback(r, mHandler);
            }
            final MediaControllerRecord r = mRecords.get(token);
            final boolean remote = isRemote(pi);
            if (remote) {
                updateRemoteH(token, r.name, pi);
                r.sentRemote = true;
            }
        }
        for (Token t : toRemove) {
            final MediaControllerRecord r = mRecords.get(t);
            r.controller.unregisterCallback(r);
            mRecords.remove(t);
            if (D.BUG) Log.d(TAG, "Removing " + r.name + " sentRemote=" + r.sentRemote);
            if (r.sentRemote) {
                mCallbacks.onRemoteRemoved(t);
                r.sentRemote = false;
            }
        }
    }

    private static boolean isRemote(PlaybackInfo pi) {
        return pi != null && pi.getPlaybackType() == PlaybackInfo.PLAYBACK_TYPE_REMOTE;
    }

    protected String getControllerName(MediaController controller) {
        final PackageManager pm = mContext.getPackageManager();
        final String pkg = controller.getPackageName();
        try {
            if (USE_SERVICE_LABEL) {
                final List<ResolveInfo> ris = pm.queryIntentServices(
                        new Intent("android.media.MediaRouteProviderService").setPackage(pkg), 0);
                if (ris != null) {
                    for (ResolveInfo ri : ris) {
                        if (ri.serviceInfo == null) continue;
                        if (pkg.equals(ri.serviceInfo.packageName)) {
                            final String serviceLabel =
                                    Objects.toString(ri.serviceInfo.loadLabel(pm), "").trim();
                            if (serviceLabel.length() > 0) {
                                return serviceLabel;
                            }
                        }
                    }
                }
            }
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            final String appLabel = Objects.toString(ai.loadLabel(pm), "").trim();
            if (appLabel.length() > 0) {
                return appLabel;
            }
        } catch (NameNotFoundException e) { }
        return pkg;
    }

    private void updateRemoteH(Token token, String name, PlaybackInfo pi) {
        if (mCallbacks != null) {
            mCallbacks.onRemoteUpdate(token, name, pi);
        }
    }

    private static void dump(int n, PrintWriter writer, MediaController c) {
        writer.println("  Controller " + n + ": " + c.getPackageName());
        final Bundle extras = c.getExtras();
        final long flags = c.getFlags();
        final MediaMetadata mm = c.getMetadata();
        final PlaybackInfo pi = c.getPlaybackInfo();
        final PlaybackState playbackState = c.getPlaybackState();
        final List<QueueItem> queue = c.getQueue();
        final CharSequence queueTitle = c.getQueueTitle();
        final int ratingType = c.getRatingType();
        final PendingIntent sessionActivity = c.getSessionActivity();

        writer.println("    PlaybackState: " + Util.playbackStateToString(playbackState));
        writer.println("    PlaybackInfo: " + Util.playbackInfoToString(pi));
        if (mm != null) {
            writer.println("  MediaMetadata.desc=" + mm.getDescription());
        }
        writer.println("    RatingType: " + ratingType);
        writer.println("    Flags: " + flags);
        if (extras != null) {
            writer.println("    Extras:");
            for (String key : extras.keySet()) {
                writer.println("      " + key + "=" + extras.get(key));
            }
        }
        if (queueTitle != null) {
            writer.println("    QueueTitle: " + queueTitle);
        }
        if (queue != null && !queue.isEmpty()) {
            writer.println("    Queue:");
            for (QueueItem qi : queue) {
                writer.println("      " + qi);
            }
        }
        if (pi != null) {
            writer.println("    sessionActivity: " + sessionActivity);
        }
    }

    public static void dumpMediaSessions(Context context) {
        final MediaSessionManager mgr = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            final List<MediaController> controllers = mgr.getActiveSessions(null);
            final int N = controllers.size();
            if (D.BUG) Log.d(TAG, N + " controllers");
            for (int i = 0; i < N; i++) {
                final StringWriter sw = new StringWriter();
                final PrintWriter pw = new PrintWriter(sw, true);
                dump(i + 1, pw, controllers.get(i));
                if (D.BUG) Log.d(TAG, sw.toString());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to get sessions", e);
        }
    }

    private final class MediaControllerRecord extends MediaController.Callback {
        private final MediaController controller;

        private boolean sentRemote;
        private String name;

        private MediaControllerRecord(MediaController controller) {
            this.controller = controller;
        }

        private String cb(String method) {
            return method + " " + controller.getPackageName() + " ";
        }

        @Override
        public void onAudioInfoChanged(PlaybackInfo info) {
            if (D.BUG) Log.d(TAG, cb("onAudioInfoChanged") + Util.playbackInfoToString(info)
                    + " sentRemote=" + sentRemote);
            final boolean remote = isRemote(info);
            if (!remote && sentRemote) {
                mCallbacks.onRemoteRemoved(controller.getSessionToken());
                sentRemote = false;
            } else if (remote) {
                updateRemoteH(controller.getSessionToken(), name, info);
                sentRemote = true;
            }
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            if (D.BUG) Log.d(TAG, cb("onExtrasChanged") + extras);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (D.BUG) Log.d(TAG, cb("onMetadataChanged") + Util.mediaMetadataToString(metadata));
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (D.BUG) Log.d(TAG, cb("onPlaybackStateChanged") + Util.playbackStateToString(state));
        }

        @Override
        public void onQueueChanged(List<QueueItem> queue) {
            if (D.BUG) Log.d(TAG, cb("onQueueChanged") + queue);
        }

        @Override
        public void onQueueTitleChanged(CharSequence title) {
            if (D.BUG) Log.d(TAG, cb("onQueueTitleChanged") + title);
        }

        @Override
        public void onSessionDestroyed() {
            if (D.BUG) Log.d(TAG, cb("onSessionDestroyed"));
        }

        @Override
        public void onSessionEvent(String event, Bundle extras) {
            if (D.BUG) Log.d(TAG, cb("onSessionEvent") + "event=" + event + " extras=" + extras);
        }
    }

    private final OnActiveSessionsChangedListener mSessionsListener =
            new OnActiveSessionsChangedListener() {
        @Override
        public void onActiveSessionsChanged(List<MediaController> controllers) {
            onActiveSessionsUpdatedH(controllers);
        }
    };

    private final IRemoteVolumeController mRvc = new IRemoteVolumeController.Stub() {
        @Override
        public void remoteVolumeChanged(ISessionController session, int flags)
                throws RemoteException {
            mHandler.obtainMessage(H.REMOTE_VOLUME_CHANGED, flags, 0, session).sendToTarget();
        }

        @Override
        public void updateRemoteController(final ISessionController session)
                throws RemoteException {
            mHandler.obtainMessage(H.UPDATE_REMOTE_CONTROLLER, session).sendToTarget();
        }
    };

    private final class H extends Handler {
        private static final int UPDATE_SESSIONS = 1;
        private static final int REMOTE_VOLUME_CHANGED = 2;
        private static final int UPDATE_REMOTE_CONTROLLER = 3;

        private H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UPDATE_SESSIONS:
                    onActiveSessionsUpdatedH(mMgr.getActiveSessions(null));
                    break;
                case REMOTE_VOLUME_CHANGED:
                    onRemoteVolumeChangedH((ISessionController) msg.obj, msg.arg1);
                    break;
                case UPDATE_REMOTE_CONTROLLER:
                    onUpdateRemoteControllerH((ISessionController) msg.obj);
                    break;
            }
        }
    }

    public interface Callbacks {
        void onRemoteUpdate(Token token, String name, PlaybackInfo pi);
        void onRemoteRemoved(Token t);
        void onRemoteVolumeChanged(Token token, int flags);
    }

}
