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

import static android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.INTERRUPTION_FILTER_NONE;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN;

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
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;

import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.Date;

/**
 * Implementation of `cmd notification` in NotificationManagerService.
 */
public class NotificationShellCmd extends ShellCommand {
    private static final String TAG = "NotifShellCmd";
    private static final String USAGE = "usage: cmd notification SUBCMD [args]\n\n"
            + "SUBCMDs:\n"
            + "  allow_listener COMPONENT [user_id (current user if not specified)]\n"
            + "  disallow_listener COMPONENT [user_id (current user if not specified)]\n"
            + "  allow_assistant COMPONENT [user_id (current user if not specified)]\n"
            + "  remove_assistant COMPONENT [user_id (current user if not specified)]\n"
            + "  set_dnd [on|none (same as on)|priority|alarms|all|off (same as all)]"
            + "  allow_dnd PACKAGE [user_id (current user if not specified)]\n"
            + "  disallow_dnd PACKAGE [user_id (current user if not specified)]\n"
            + "  reset_assistant_user_set [user_id (current user if not specified)]\n"
            + "  get_approved_assistant [user_id (current user if not specified)]\n"
            + "  post [--help | flags] TAG TEXT\n"
            + "  set_bubbles PACKAGE PREFERENCE (0=none 1=all 2=selected) "
                    + "[user_id (current user if not specified)]\n"
            + "  set_bubbles_channel PACKAGE CHANNEL_ID ALLOW "
                    + "[user_id (current user if not specified)]\n"
            + "  list\n"
            + "  get <notification-key>\n"
            + "  snooze --for <msec> <notification-key>\n"
            + "  unsnooze <notification-key>\n"
            ;

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

    public static final int NOTIFICATION_ID = 2020;
    public static final String CHANNEL_ID = "shell_cmd";
    public static final String CHANNEL_NAME = "Shell command";
    public static final int CHANNEL_IMP = NotificationManager.IMPORTANCE_DEFAULT;

    private final NotificationManagerService mDirectService;
    private final INotificationManager mBinderService;
    private final PackageManager mPm;
    private NotificationChannel mChannel;

    public NotificationShellCmd(NotificationManagerService service) {
        mDirectService = service;
        mBinderService = service.getBinderService();
        mPm = mDirectService.getContext().getPackageManager();
    }

    protected boolean checkShellCommandPermission(int callingUid) {
        return (callingUid == Process.ROOT_UID || callingUid == Process.SHELL_UID);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        String callingPackage = null;
        final int callingUid = Binder.getCallingUid();
        final long identity = Binder.clearCallingIdentity();
        try {
            if (callingUid == Process.ROOT_UID) {
                callingPackage = NotificationManagerService.ROOT_PKG;
            } else {
                String[] packages = mPm.getPackagesForUid(callingUid);
                if (packages != null && packages.length > 0) {
                    callingPackage = packages[0];
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "failed to get caller pkg", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }

        final PrintWriter pw = getOutPrintWriter();

        if (!checkShellCommandPermission(callingUid)) {
            Slog.e(TAG, "error: permission denied: callingUid="
                    + callingUid + " callingPackage=" + callingPackage);
            pw.println("error: permission denied: callingUid="
                    + callingUid + " callingPackage=" + callingPackage);
            return 255;
        }

        try {
            switch (cmd.replace('-', '_')) {
                case "set_dnd": {
                    String mode = getNextArgRequired();
                    int interruptionFilter = INTERRUPTION_FILTER_UNKNOWN;
                    switch(mode) {
                        case "none":
                        case "on":
                            interruptionFilter = INTERRUPTION_FILTER_NONE;
                            break;
                        case "priority":
                            interruptionFilter = INTERRUPTION_FILTER_PRIORITY;
                            break;
                        case "alarms":
                            interruptionFilter = INTERRUPTION_FILTER_ALARMS;
                            break;
                        case "all":
                        case "off":
                            interruptionFilter = INTERRUPTION_FILTER_ALL;
                    }
                    final int filter = interruptionFilter;
                    mBinderService.setInterruptionFilter(callingPackage, filter);
                }
                break;
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
                    mBinderService.setNotificationListenerAccessGrantedForUser(
                            cn, userId, true, true);
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
                    mBinderService.setNotificationListenerAccessGrantedForUser(
                            cn, userId, false, true);
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
                case "set_bubbles": {
                    // only use for testing
                    String packageName = getNextArgRequired();
                    int preference = Integer.parseInt(getNextArgRequired());
                    if (preference > 3 || preference < 0) {
                        pw.println("Invalid preference - must be between 0-3 "
                                + "(0=none 1=all 2=selected)");
                        return -1;
                    }
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    int appUid = UserHandle.getUid(userId, mPm.getPackageUid(packageName, 0));
                    mBinderService.setBubblesAllowed(packageName, appUid, preference);
                    break;
                }
                case "set_bubbles_channel": {
                    // only use for testing
                    String packageName = getNextArgRequired();
                    String channelId = getNextArgRequired();
                    boolean allow = Boolean.parseBoolean(getNextArgRequired());
                    int userId = ActivityManager.getCurrentUser();
                    if (peekNextArg() != null) {
                        userId = Integer.parseInt(getNextArgRequired());
                    }
                    NotificationChannel channel = mBinderService.getNotificationChannel(
                            callingPackage, userId, packageName, channelId);
                    channel.setAllowBubbles(allow);
                    int appUid = UserHandle.getUid(userId, mPm.getPackageUid(packageName, 0));
                    mBinderService.updateNotificationChannelForPackage(packageName, appUid,
                            channel);
                    break;
                }
                case "post":
                case "notify":
                    doNotify(pw, callingPackage, callingUid);
                    break;
                case "list":
                    for (String key : mDirectService.mNotificationsByKey.keySet()) {
                        pw.println(key);
                    }
                    break;
                case "get": {
                    final String key = getNextArgRequired();
                    final NotificationRecord nr = mDirectService.getNotificationRecord(key);
                    if (nr != null) {
                        nr.dump(pw, "", mDirectService.getContext(), false);
                    } else {
                        pw.println("error: no active notification matching key: " + key);
                        return 1;
                    }
                    break;
                }
                case "snoozed": {
                    final StringBuilder sb = new StringBuilder();
                    final SnoozeHelper sh = mDirectService.mSnoozeHelper;
                    for (NotificationRecord nr : sh.getSnoozed()) {
                        final String pkg = nr.getSbn().getPackageName();
                        final String key = nr.getKey();
                        pw.println(key + " snoozed, time="
                                + sh.getSnoozeTimeForUnpostedNotification(
                                        nr.getUserId(), pkg, key)
                                + " context="
                                + sh.getSnoozeContextForUnpostedNotification(
                                        nr.getUserId(), pkg, key));
                    }
                    break;
                }
                case "unsnooze": {
                    boolean mute = false;
                    String key = getNextArgRequired();
                    if ("--mute".equals(key)) {
                        mute = true;
                        key = getNextArgRequired();
                    }
                    if (null != mDirectService.mSnoozeHelper.getNotification(key)) {
                        pw.println("unsnoozing: " + key);
                        mDirectService.unsnoozeNotificationInt(key, null, mute);
                    } else {
                        pw.println("error: no snoozed otification matching key: " + key);
                        return 1;
                    }
                    break;
                }
                case "snooze": {
                    String subflag = getNextArg();
                    if (subflag == null) {
                        subflag = "help";
                    } else if (subflag.startsWith("--")) {
                        subflag = subflag.substring(2);
                    }
                    String flagarg = getNextArg();
                    String key = getNextArg();
                    if (key == null) subflag = "help";
                    String criterion = null;
                    long duration = 0;
                    switch (subflag) {
                        case "context":
                        case "condition":
                        case "criterion":
                            criterion = flagarg;
                            break;
                        case "until":
                        case "for":
                        case "duration":
                            duration = Long.parseLong(flagarg);
                            break;
                        default:
                            pw.println("usage: cmd notification snooze (--for <msec> | "
                                    + "--context <snooze-criterion-id>) <key>");
                            return 1;
                    }
                    if (null == mDirectService.getNotificationRecord(key)) {
                        pw.println("error: no notification matching key: " + key);
                        return 1;
                    }
                    if (duration > 0 || criterion != null) {
                        if (duration > 0) {
                            pw.println(String.format("snoozing <%s> until time: %s", key,
                                    new Date(System.currentTimeMillis() + duration)));
                        } else {
                            pw.println(String.format("snoozing <%s> until criterion: %s", key,
                                    criterion));
                        }
                        mDirectService.snoozeNotificationInt(key, duration, criterion, null);
                    } else {
                        pw.println("error: invalid value for --" + subflag + ": " + flagarg);
                        return 1;
                    }
                    break;
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Error occurred. Check logcat for details. " + e.getMessage());
            Slog.e(NotificationManagerService.TAG, "Error running shell command", e);
        }
        return 0;
    }

    void ensureChannel(String callingPackage, int callingUid) throws RemoteException {
        final NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, CHANNEL_IMP);
        mBinderService.createNotificationChannels(callingPackage,
                new ParceledListSlice<>(Collections.singletonList(channel)));
        Slog.v(NotificationManagerService.TAG, "created channel: "
                + mBinderService.getNotificationChannel(callingPackage,
                UserHandle.getUserId(callingUid), callingPackage, CHANNEL_ID));
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

    private int doNotify(PrintWriter pw, String callingPackage, int callingUid)
            throws RemoteException, URISyntaxException {
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
                                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED,
                                UserHandle.CURRENT);
                    } else if ("service".equals(intentKind)) {
                        pi = PendingIntent.getService(
                                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);
                    } else {
                        pi = PendingIntent.getActivityAsUser(
                                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED, null,
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

        ensureChannel(callingPackage, callingUid);

        final Notification n = builder.build();
        pw.println("posting:\n  " + n);
        Slog.v("NotificationManager", "posting: " + n);

        mBinderService.enqueueNotificationWithTag(callingPackage, callingPackage, tag,
                NOTIFICATION_ID, n, UserHandle.getUserId(callingUid));

        if (verbose) {
            NotificationRecord nr = mDirectService.findNotificationLocked(
                    callingPackage, tag, NOTIFICATION_ID, UserHandle.getUserId(callingUid));
            for (int tries = 3; tries-- > 0; ) {
                if (nr != null) break;
                try {
                    pw.println("waiting for notification to post...");
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
                nr = mDirectService.findNotificationLocked(
                        callingPackage, tag, NOTIFICATION_ID, UserHandle.getUserId(callingUid));
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

