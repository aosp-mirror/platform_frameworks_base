/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.notification;

import android.app.ActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.Collections;

/**
 * Implementation of `cmd notification` in NotificationManagerService.
 */
public class NotificationShellCmd extends ShellCommand {
    private static final String USAGE =
              "usage: cmd notification SUBCMD [args]\n\n"
            + "SUBCMDs:\n"
            + "  allow_listener COMPONENT [user_id (current user if not specified)]\n"
            + "  disallow_listener COMPONENT [user_id (current user if not specified)]\n"
            + "  allow_assistant COMPONENT [user_id (current user if not specified)]\n"
            + "  remove_assistant COMPONENT [user_id (current user if not specified)]\n"
            + "  allow_dnd PACKAGE [user_id (current user if not specified)]\n"
            + "  disallow_dnd PACKAGE [user_id (current user if not specified)]\n"
            + "  suspend_package PACKAGE\n"
            + "  unsuspend_package PACKAGE\n"
            + "  reset_assistant_user_set [user_id (current user if not specified)]\n"
            + "  get_approved_assistant [user_id (current user if not specified)]\n"
            + "  post [--help | flags] TAG TEXT";

    private static final String NOTIFY_USAGE =
              "usage: cmd notification post [flags] <tag> <text>\n\n"
            + "flags:\n"
            + "  -h|--help\n"
            + "  -v|--verbose\n"
            + "  -t|--title <text>\n"
            + "  -i|--icon <iconspec>\n"
            + "  -I|--large-icon <iconspec>\n"
            + "  -S|--style <style> [styleargs]\n"
            + "  -c|--content-intent <intentspec>\n"
            + "\n"
            + "styles: (default none)\n"
            + "  bigtext\n"
            + "  bigpicture --picture <iconspec>\n"
            + "  inbox --line <text> --line <text> ...\n"
            + "  messaging --conversation <title> --message <who>:<text> ...\n"
            + "  media\n"
            + "\n"
            + "an <iconspec> is one of\n"
            + "  file:///data/local/tmp/<img.png>\n"
            + "  content://<provider>/<path>\n"
            + "  @[<package>:]drawable/<img>\n"
            + "  data:base64,<B64DATA==>\n"
            + "\n"
            + "an <intentspec> is (broadcast|service|activity) <args>\n"
            + "  <args> are as described in `am start`";

    public static final int NOTIFICATION_ID = 1138;
    public static final String NOTIFICATION_PACKAGE = "com.android.shell";
    public static final String CHANNEL_ID = "shellcmd";
    public static final String CHANNEL_NAME = "Shell command";
    public static final int CHANNEL_IMP = NotificationManager.IMPORTANCE_DEFAULT;

    private final NotificationManagerService mDirectService;
    private final INotificationManager mBinderService;

    public NotificationShellCmd(NotificationManagerService service) {
        mDirectService = service;
        mBinderService = service.getBinderService();
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd.replace('-', '_')) {
                case "allow_dnd": {
                    String packageName = getNextArgRequired();
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    mBinderService.setNotificationPolicyAccessGrantedForUser(
                            packageName, userId, true);
                }
                break;

                case "disallow_dnd": {
                    String packageName = getNextArgRequired();
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    mBinderService.setNotificationPolicyAccessGrantedForUser(
                            packageName, userId, false);
                }
                break;
                case "allow_listener": {
                    ComponentName cn = ComponentName.unflattenFromString(getNextArgRequired());
                    if (cn == null) {
                        pw.println("Invalid listener - must be a ComponentName");
                        return -1;
                    }
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    mBinderService.setNotificationListenerAccessGrantedForUser(cn, userId, true);
                }
                break;
                case "disallow_listener": {
                    ComponentName cn = ComponentName.unflattenFromString(getNextArgRequired());
                    if (cn == null) {
                        pw.println("Invalid listener - must be a ComponentName");
                        return -1;
                    }
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    mBinderService.setNotificationListenerAccessGrantedForUser(cn, userId, false);
                }
                break;
                case "allow_assistant": {
                    ComponentName cn = ComponentName.unflattenFromString(getNextArgRequired());
                    if (cn == null) {
                        pw.println("Invalid assistant - must be a ComponentName");
                        return -1;
                    }
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    mBinderService.setNotificationAssistantAccessGrantedForUser(cn, userId, true);
                }
                break;
                case "disallow_assistant": {
                    ComponentName cn = ComponentName.unflattenFromString(getNextArgRequired());
                    if (cn == null) {
                        pw.println("Invalid assistant - must be a ComponentName");
                        return -1;
                    }
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    mBinderService.setNotificationAssistantAccessGrantedForUser(cn, userId, false);
                }
                break;
                case "suspend_package": {
                    // only use for testing
                    mDirectService.simulatePackageSuspendBroadcast(true, getNextArgRequired());
                }
                break;
                case "unsuspend_package": {
                    // only use for testing
                    mDirectService.simulatePackageSuspendBroadcast(false, getNextArgRequired());
                }
                break;
                case "distract_package": {
                    // only use for testing
                    // Flag values are in
                    // {@link android.content.pm.PackageManager.DistractionRestriction}.
                    mDirectService.simulatePackageDistractionBroadcast(
                            Integer.parseInt(getNextArgRequired()),
                            getNextArgRequired().split(","));
                    break;
                }
                case "reset_assistant_user_set": {
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    mDirectService.resetAssistantUserSet(userId);
                    break;
                }
                case "get_approved_assistant": {
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    ComponentName approvedAssistant = mDirectService.getApprovedAssistant(userId);
                    if (approvedAssistant == null) {
                        pw.println("null");
                    } else {
                        pw.println(approvedAssistant.flattenToString());
                    }
                    break;
                }
                case "post":
                case "notify":
                    doNotify(pw);
                    break;
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Error occurred. Check logcat for details. " + e.getMessage());
            Slog.e(NotificationManagerService.TAG, "Error running shell command", e);
        }
        return 0;
    }

    void ensureChannel() throws RemoteException {
        final int uid = Binder.getCallingUid();
        final int userid = UserHandle.getCallingUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            if (mBinderService.getNotificationChannelForPackage(NOTIFICATION_PACKAGE,
                    uid, CHANNEL_ID, false) == null) {
                final NotificationChannel chan = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                        CHANNEL_IMP);
                Slog.v(NotificationManagerService.TAG,
                        "creating shell channel for user " + userid + " uid " + uid + ": " + chan);
                mBinderService.createNotificationChannelsForPackage(NOTIFICATION_PACKAGE, uid,
                        new ParceledListSlice<NotificationChannel>(
                                Collections.singletonList(chan)));
                Slog.v(NotificationManagerService.TAG, "created channel: "
                        + mBinderService.getNotificationChannelForPackage(NOTIFICATION_PACKAGE,
                                uid, CHANNEL_ID, false));
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    Icon parseIcon(Resources res, String encoded) throws IllegalArgumentException {
        if (TextUtils.isEmpty(encoded)) return null;
        if (encoded.startsWith("/")) {
            encoded = "file://" + encoded;
        }
        if (encoded.startsWith("http:")
                || encoded.startsWith("https:")
                || encoded.startsWith("content:")
                || encoded.startsWith("file:")
                || encoded.startsWith("android.resource:")) {
            Uri asUri = Uri.parse(encoded);
            return Icon.createWithContentUri(asUri);
        } else if (encoded.startsWith("@")) {
            final int resid = res.getIdentifier(encoded.substring(1),
                    "drawable", "android");
            if (resid != 0) {
                return Icon.createWithResource(res, resid);
            }
        } else if (encoded.startsWith("data:")) {
            encoded = encoded.substring(encoded.indexOf(',') + 1);
            byte[] bits = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT);
            return Icon.createWithData(bits, 0, bits.length);
        }
        return null;
    }

    private int doNotify(PrintWriter pw) throws RemoteException, URISyntaxException {
        final Context context = mDirectService.getContext();
        final Resources res = context.getResources();
        final Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID);
        String opt;

        boolean verbose = false;
        Notification.BigPictureStyle bigPictureStyle = null;
        Notification.BigTextStyle bigTextStyle = null;
        Notification.InboxStyle inboxStyle = null;
        Notification.MediaStyle mediaStyle = null;
        Notification.MessagingStyle messagingStyle = null;

        Icon smallIcon = null;
        while ((opt = getNextOption()) != null) {
            boolean large = false;
            switch (opt) {
                case "-v":
                case "--verbose":
                    verbose = true;
                    break;
                case "-t":
                case "--title":
                case "title":
                    builder.setContentTitle(getNextArgRequired());
                    break;
                case "-I":
                case "--large-icon":
                case "--largeicon":
                case "largeicon":
                case "large-icon":
                    large = true;
                    // fall through
                case "-i":
                case "--icon":
                case "icon":
                    final String iconSpec = getNextArgRequired();
                    final Icon icon = parseIcon(res, iconSpec);
                    if (icon == null) {
                        pw.println("error: invalid icon: " + iconSpec);
                        return -1;
                    }
                    if (large) {
                        builder.setLargeIcon(icon);
                        large = false;
                    } else {
                        smallIcon = icon;
                    }
                    break;
                case "-c":
                case "--content-intent":
                case "content-intent":
                case "--intent":
                case "intent":
                    String intentKind = null;
                    switch (peekNextArg()) {
                        case "broadcast":
                        case "service":
                        case "activity":
                            intentKind = getNextArg();
                    }
                    final Intent intent = Intent.parseCommandArgs(this, null);
                    if (intent.getData() == null) {
                        // force unique intents unless you know what you're doing
                        intent.setData(Uri.parse("xyz:" + System.currentTimeMillis()));
                    }
                    final PendingIntent pi;
                    if ("broadcast".equals(intentKind)) {
                        pi = PendingIntent.getBroadcastAsUser(
                                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT,
                                UserHandle.CURRENT);
                    } else if ("service".equals(intentKind)) {
                        pi = PendingIntent.getService(
                                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                    } else {
                        pi = PendingIntent.getActivityAsUser(
                                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, null,
                                UserHandle.CURRENT);
                    }
                    builder.setContentIntent(pi);
                    break;
                case "-S":
                case "--style":
                    final String styleSpec = getNextArgRequired().toLowerCase();
                    switch (styleSpec) {
                        case "bigtext":
                            bigTextStyle = new Notification.BigTextStyle();
                            builder.setStyle(bigTextStyle);
                            break;
                        case "bigpicture":
                            bigPictureStyle = new Notification.BigPictureStyle();
                            builder.setStyle(bigPictureStyle);
                            break;
                        case "inbox":
                            inboxStyle = new Notification.InboxStyle();
                            builder.setStyle(inboxStyle);
                            break;
                        case "messaging":
                            String name = "You";
                            if ("--user".equals(peekNextArg())) {
                                getNextArg();
                                name = getNextArgRequired();
                            }
                            messagingStyle = new Notification.MessagingStyle(
                                    new Person.Builder().setName(name).build());
                            builder.setStyle(messagingStyle);
                            break;
                        case "media":
                            mediaStyle = new Notification.MediaStyle();
                            builder.setStyle(mediaStyle);
                            break;
                        default:
                            throw new IllegalArgumentException(
                                    "unrecognized notification style: " + styleSpec);
                    }
                    break;
                case "--bigText": case "--bigtext": case "--big-text":
                    if (bigTextStyle == null) {
                        throw new IllegalArgumentException("--bigtext requires --style bigtext");
                    }
                    bigTextStyle.bigText(getNextArgRequired());
                    break;
                case "--picture":
                    if (bigPictureStyle == null) {
                        throw new IllegalArgumentException("--picture requires --style bigpicture");
                    }
                    final String pictureSpec = getNextArgRequired();
                    final Icon pictureAsIcon = parseIcon(res, pictureSpec);
                    if (pictureAsIcon == null) {
                        throw new IllegalArgumentException("bad picture spec: " + pictureSpec);
                    }
                    final Drawable d = pictureAsIcon.loadDrawable(context);
                    if (d instanceof BitmapDrawable) {
                        bigPictureStyle.bigPicture(((BitmapDrawable) d).getBitmap());
                    } else {
                        throw new IllegalArgumentException("not a bitmap: " + pictureSpec);
                    }
                    break;
                case "--line":
                    if (inboxStyle == null) {
                        throw new IllegalArgumentException("--line requires --style inbox");
                    }
                    inboxStyle.addLine(getNextArgRequired());
                    break;
                case "--message":
                    if (messagingStyle == null) {
                        throw new IllegalArgumentException(
                                "--message requires --style messaging");
                    }
                    String arg = getNextArgRequired();
                    String[] parts = arg.split(":", 2);
                    if (parts.length > 1) {
                        messagingStyle.addMessage(parts[1], System.currentTimeMillis(),
                                parts[0]);
                    } else {
                        messagingStyle.addMessage(parts[0], System.currentTimeMillis(),
                                new String[]{
                                        messagingStyle.getUserDisplayName().toString(),
                                        "Them"
                                }[messagingStyle.getMessages().size() % 2]);
                    }
                    break;
                case "--conversation":
                    if (messagingStyle == null) {
                        throw new IllegalArgumentException(
                                "--conversation requires --style messaging");
                    }
                    messagingStyle.setConversationTitle(getNextArgRequired());
                    break;
                case "-h":
                case "--help":
                case "--wtf":
                default:
                    pw.println(NOTIFY_USAGE);
                    return 0;
            }
        }

        final String tag = getNextArg();
        final String text = getNextArg();
        if (tag == null || text == null) {
            pw.println(NOTIFY_USAGE);
            return -1;
        }

        builder.setContentText(text);

        if (smallIcon == null) {
            // uh oh, let's substitute something
            builder.setSmallIcon(com.android.internal.R.drawable.stat_notify_chat);
        } else {
            builder.setSmallIcon(smallIcon);
        }

        ensureChannel();

        final Notification n = builder.build();
        pw.println("posting:\n  " + n);
        Slog.v("NotificationManager", "posting: " + n);

        final int userId = UserHandle.getCallingUserId();
        final long token = Binder.clearCallingIdentity();
        try {
            mBinderService.enqueueNotificationWithTag(
                    NOTIFICATION_PACKAGE, "android",
                    tag, NOTIFICATION_ID,
                    n, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        if (verbose) {
            NotificationRecord nr = mDirectService.findNotificationLocked(
                    NOTIFICATION_PACKAGE, tag, NOTIFICATION_ID, userId);
            for (int tries = 3; tries-- > 0; ) {
                if (nr != null) break;
                try {
                    pw.println("waiting for notification to post...");
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
                nr = mDirectService.findNotificationLocked(
                        NOTIFICATION_PACKAGE, tag, NOTIFICATION_ID, userId);
            }
            if (nr == null) {
                pw.println("warning: couldn't find notification after enqueueing");
            } else {
                pw.println("posted: ");
                nr.dump(pw, "  ", context, false);
            }
        }

        return 0;
    }

    @Override
    public void onHelp() {
        getOutPrintWriter().println(USAGE);
    }
}

