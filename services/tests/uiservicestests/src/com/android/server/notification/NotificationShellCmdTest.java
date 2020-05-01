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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertSame;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// this is a lazy way to do in/out/err but we're not particularly interested in the output
import static java.io.FileDescriptor.err;
import static java.io.FileDescriptor.in;
import static java.io.FileDescriptor.out;

import android.app.INotificationManager;
import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationShellCmdTest extends UiServiceTestCase {
    private final Binder mBinder = new Binder();
    private final ShellCallback mCallback = new ShellCallback();
    private final TestableContext mTestableContext = spy(getContext());
    @Mock
    NotificationManagerService mMockService;
    @Mock
    INotificationManager mMockBinderService;
    private TestableLooper mTestableLooper;
    private ResultReceiver mResultReceiver;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mResultReceiver = new ResultReceiver(new Handler(mTestableLooper.getLooper()));

        when(mMockService.getContext()).thenReturn(mTestableContext);
        when(mMockService.getBinderService()).thenReturn(mMockBinderService);
    }

    private Bitmap createTestBitmap() {
        final Bitmap bits = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bits);
        final GradientDrawable grad = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.RED, Color.YELLOW, Color.GREEN,
                        Color.CYAN, Color.BLUE, Color.MAGENTA});
        grad.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        grad.draw(canvas);
        return bits;
    }

    private void doCmd(String... args) {
        System.out.println("Running command: " + String.join(" ", args));
        new TestNotificationShellCmd(mMockService)
                .exec(mBinder, in, out, err, args, mCallback, mResultReceiver);
    }

    @Test
    public void testNoArgs() throws Exception {
        doCmd();
    }

    @Test
    public void testHelp() throws Exception {
        doCmd("--help");
    }

    Notification captureNotification(String aTag) throws Exception {
        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);
        verify(mMockBinderService).enqueueNotificationWithTag(
                eq(getContext().getPackageName()),
                eq(getContext().getPackageName()),
                eq(aTag),
                eq(NotificationShellCmd.NOTIFICATION_ID),
                notificationCaptor.capture(),
                eq(UserHandle.getCallingUserId()));
        return notificationCaptor.getValue();
    }

    @Test
    public void testBasic() throws Exception {
        final String aTag = "aTag";
        final String aText = "someText";
        final String aTitle = "theTitle";
        doCmd("notify",
                "--title", aTitle,
                aTag, aText);
        final Notification captured = captureNotification(aTag);
        assertEquals(aText, captured.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(aTitle, captured.extras.getString(Notification.EXTRA_TITLE));
    }

    @Test
    public void testIcon() throws Exception {
        final String aTag = "aTag";
        final String aText = "someText";
        doCmd("notify", "--icon", "@android:drawable/stat_sys_adb", aTag, aText);
        final Notification captured = captureNotification(aTag);
        final Icon icon = captured.getSmallIcon();
        assertEquals("android", icon.getResPackage());
        assertEquals(com.android.internal.R.drawable.stat_sys_adb, icon.getResId());
    }

    @Test
    public void testBigText() throws Exception {
        final String aTag = "aTag";
        final String aText = "someText";
        final String bigText = "someBigText";
        doCmd("notify",
                "--style", "bigtext",
                "--big-text", bigText,
                aTag, aText);
        final Notification captured = captureNotification(aTag);
        assertSame(captured.getNotificationStyle(), Notification.BigTextStyle.class);
        assertEquals(aText, captured.extras.getString(Notification.EXTRA_TEXT));
        assertEquals(bigText, captured.extras.getString(Notification.EXTRA_BIG_TEXT));
    }

    @Test
    public void testBigPicture() throws Exception {
        final String aTag = "aTag";
        final String aText = "someText";
        final String bigPicture = "@android:drawable/default_wallpaper";
        doCmd("notify",
                "--style", "bigpicture",
                "--picture", bigPicture,
                aTag, aText);
        final Notification captured = captureNotification(aTag);
        assertSame(captured.getNotificationStyle(), Notification.BigPictureStyle.class);
        final Object pic = captured.extras.get(Notification.EXTRA_PICTURE);
        assertThat(pic, instanceOf(Bitmap.class));
    }

    @Test
    public void testInbox() throws Exception {
        final int n = 25;
        final String aTag = "inboxTag";
        final String aText = "inboxText";
        ArrayList<String> args = new ArrayList<>();
        args.add("notify");
        args.add("--style");
        args.add("inbox");
        final int startOfLineArgs = args.size();
        for (int i = 0; i < n; i++) {
            args.add("--line");
            args.add(String.format("Line %02d", i));
        }
        args.add(aTag);
        args.add(aText);

        doCmd(args.toArray(new String[0]));
        final Notification captured = captureNotification(aTag);
        assertSame(captured.getNotificationStyle(), Notification.InboxStyle.class);
        final Notification.Builder builder =
                Notification.Builder.recoverBuilder(mContext, captured);
        final ArrayList<CharSequence> lines =
                ((Notification.InboxStyle) (builder.getStyle())).getLines();
        for (int i = 0; i < n; i++) {
            assertEquals(lines.get(i), args.get(1 + 2 * i + startOfLineArgs));
        }
    }

    static final String[] PEOPLE = {
            "Alice",
            "Bob",
            "Charlotte"
    };
    static final String[] MESSAGES = {
            "Shall I compare thee to a summer's day?",
            "Thou art more lovely and more temperate:",
            "Rough winds do shake the darling buds of May,",
            "And summer's lease hath all too short a date;",
            "Sometime too hot the eye of heaven shines,",
            "And often is his gold complexion dimm'd;",
            "And every fair from fair sometime declines,",
            "By chance or nature's changing course untrimm'd;",
            "But thy eternal summer shall not fade,",
            "Nor lose possession of that fair thou ow'st;",
            "Nor shall death brag thou wander'st in his shade,",
            "When in eternal lines to time thou grow'st:",
            "   So long as men can breathe or eyes can see,",
            "   So long lives this, and this gives life to thee.",
    };

    @Test
    public void testMessaging() throws Exception {
        final String aTag = "messagingTag";
        final String aText = "messagingText";
        ArrayList<String> args = new ArrayList<>();
        args.add("notify");
        args.add("--style");
        args.add("messaging");
        args.add("--conversation");
        args.add("Sonnet 18");
        final int startOfLineArgs = args.size();
        for (int i = 0; i < MESSAGES.length; i++) {
            args.add("--message");
            args.add(String.format("%s:%s",
                    PEOPLE[i % PEOPLE.length],
                    MESSAGES[i % MESSAGES.length]));
        }
        args.add(aTag);
        args.add(aText);

        doCmd(args.toArray(new String[0]));
        final Notification captured = captureNotification(aTag);
        assertSame(Notification.MessagingStyle.class, captured.getNotificationStyle());
        final Notification.Builder builder =
                Notification.Builder.recoverBuilder(mContext, captured);
        final Notification.MessagingStyle messagingStyle =
                (Notification.MessagingStyle) (builder.getStyle());

        assertEquals("Sonnet 18", messagingStyle.getConversationTitle());
        final List<Notification.MessagingStyle.Message> messages = messagingStyle.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            final Notification.MessagingStyle.Message m = messages.get(i);
            assertEquals(MESSAGES[i], m.getText());
            assertEquals(PEOPLE[i % PEOPLE.length], m.getSenderPerson().getName());
        }

    }

    /**
     * Version of NotificationShellCmd that allows this atest to work properly despite coming in
     * from the wrong uid.
     */
    private final class TestNotificationShellCmd extends NotificationShellCmd {
        TestNotificationShellCmd(NotificationManagerService service) {
            super(service);
        }

        @Override
        protected boolean checkShellCommandPermission(int callingUid) {
            return true;
        }
    }
}
