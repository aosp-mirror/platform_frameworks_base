/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.statusbar.NotificationLockscreenUserManager.REDACTION_TYPE_PUBLIC;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.REDACTION_TYPE_SENSITIVE_CONTENT;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.RedactionType;
import static com.android.systemui.statusbar.NotificationLockscreenUserManager.REDACTION_TYPE_NONE;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_PUBLIC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.AsyncTask;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.controls.util.MediaFeatureFlag;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips;
import com.android.systemui.statusbar.notification.ConversationNotificationProcessor;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.promoted.FakePromotedNotificationContentExtractor;
import com.android.systemui.statusbar.notification.promoted.PromotedNotificationUi;
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.BindParams;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationCallback;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.statusbar.notification.row.shared.LockscreenOtpRedaction;
import com.android.systemui.statusbar.notification.row.shared.NotificationRowContentBinderRefactor;
import com.android.systemui.statusbar.policy.InflatedSmartReplyState;
import com.android.systemui.statusbar.policy.InflatedSmartReplyViewHolder;
import com.android.systemui.statusbar.policy.SmartReplyStateInflater;
import com.android.systemui.tests.R;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
@DisableFlags(NotificationRowContentBinderRefactor.FLAG_NAME)
public class NotificationContentInflaterTest extends SysuiTestCase {

    private NotificationContentInflater mNotificationInflater;
    private Notification.Builder mBuilder;
    private ExpandableNotificationRow mRow;

    private NotificationTestHelper mHelper;

    @Mock private NotifRemoteViewCache mCache;
    @Mock private ConversationNotificationProcessor mConversationNotificationProcessor;
    @Mock private InflatedSmartReplyState mInflatedSmartReplyState;
    @Mock private InflatedSmartReplyViewHolder mInflatedSmartReplies;
    @Mock private NotifLayoutInflaterFactory.Provider mNotifLayoutInflaterFactoryProvider;
    @Mock private HeadsUpStyleProvider mHeadsUpStyleProvider;
    @Mock private NotifLayoutInflaterFactory mNotifLayoutInflaterFactory;
    private final FakePromotedNotificationContentExtractor mPromotedNotificationContentExtractor =
            new FakePromotedNotificationContentExtractor();

    private final SmartReplyStateInflater mSmartReplyStateInflater =
            new SmartReplyStateInflater() {
                @Override
                public InflatedSmartReplyViewHolder inflateSmartReplyViewHolder(
                        Context sysuiContext, Context notifPackageContext, NotificationEntry entry,
                        InflatedSmartReplyState existingSmartReplyState,
                        InflatedSmartReplyState newSmartReplyState) {
                    return mInflatedSmartReplies;
                }

                @Override
                public InflatedSmartReplyState inflateSmartReplyState(NotificationEntry entry) {
                    return mInflatedSmartReplyState;
                }
            };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mBuilder = new Notification.Builder(mContext).setSmallIcon(
                com.android.systemui.res.R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .setStyle(new Notification.BigTextStyle().bigText("big text"));
        mHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        ExpandableNotificationRow row = mHelper.createRow(mBuilder.build());
        mRow = spy(row);
        when(mNotifLayoutInflaterFactoryProvider.provide(any(), anyInt()))
                .thenReturn(mNotifLayoutInflaterFactory);

        mNotificationInflater = new NotificationContentInflater(
                mCache,
                mock(NotificationRemoteInputManager.class),
                mConversationNotificationProcessor,
                mock(MediaFeatureFlag.class),
                mock(Executor.class),
                mSmartReplyStateInflater,
                mNotifLayoutInflaterFactoryProvider,
                mHeadsUpStyleProvider,
                mPromotedNotificationContentExtractor,
                mock(NotificationRowContentBinderLogger.class));
    }

    @Test
    public void testInflationCallsUpdated() throws Exception {
        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_ALL, mRow);
        verify(mRow).onNotificationUpdated();
    }

    @Test
    public void testInflationOnlyInflatesSetFlags() throws Exception {
        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_HEADS_UP, mRow);

        assertNotNull(mRow.getPrivateLayout().getHeadsUpChild());
        verify(mRow).onNotificationUpdated();
    }

    @Test
    public void testInflationThrowsErrorDoesntCallUpdated() throws Exception {
        mRow.getPrivateLayout().removeAllViews();
        mRow.getEntry().getSbn().getNotification().contentView
                = new RemoteViews(mContext.getPackageName(), com.android.systemui.res.R.layout.status_bar);
        inflateAndWait(true /* expectingException */, mNotificationInflater, FLAG_CONTENT_VIEW_ALL,
                REDACTION_TYPE_NONE, mRow);
        assertTrue(mRow.getPrivateLayout().getChildCount() == 0);
        verify(mRow, times(0)).onNotificationUpdated();
    }

    @Test
    public void testAsyncTaskRemoved() throws Exception {
        mRow.getEntry().abortTask();
        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_ALL, mRow);
        verify(mRow).onNotificationUpdated();
    }

    @Test
    public void testRemovedNotInflated() throws Exception {
        mRow.setRemoved();
        mNotificationInflater.setInflateSynchronously(true);
        mNotificationInflater.bindContent(
                mRow.getEntry(),
                mRow,
                FLAG_CONTENT_VIEW_ALL,
                new BindParams(false, REDACTION_TYPE_NONE),
                false /* forceInflate */,
                null /* callback */);
        Assert.assertNull(mRow.getEntry().getRunningTask());
    }

    @Test
    @Ignore
    public void testInflationIsRetriedIfAsyncFails() throws Exception {
        NotificationContentInflater.InflationProgress result =
                new NotificationContentInflater.InflationProgress();
        result.packageContext = mContext;
        CountDownLatch countDownLatch = new CountDownLatch(1);
        NotificationContentInflater.applyRemoteView(
                AsyncTask.SERIAL_EXECUTOR,
                false /* inflateSynchronously */,
                /* isMinimized= */ false,
                result,
                FLAG_CONTENT_VIEW_EXPANDED,
                0,
                mock(NotifRemoteViewCache.class),
                mRow.getEntry(),
                mRow,
                true /* isNewView */, (v, p, r) -> true,
                new InflationCallback() {
                    @Override
                    public void handleInflationException(NotificationEntry entry,
                            Exception e) {
                        countDownLatch.countDown();
                        throw new RuntimeException("No Exception expected");
                    }

                    @Override
                    public void onAsyncInflationFinished(NotificationEntry entry) {
                        countDownLatch.countDown();
                    }
                }, mRow.getPrivateLayout(), null, null, new HashMap<>(),
                new NotificationContentInflater.ApplyCallback() {
                    @Override
                    public void setResultView(View v) {
                    }

                    @Override
                    public RemoteViews getRemoteView() {
                        return new AsyncFailRemoteView(mContext.getPackageName(),
                                R.layout.custom_view_dark);
                    }
                },
                mock(NotificationRowContentBinderLogger.class));
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void doesntReapplyDisallowedRemoteView() throws Exception {
        mBuilder.setStyle(new Notification.MediaStyle());
        RemoteViews mediaView = mBuilder.createContentView();
        mBuilder.setStyle(new Notification.DecoratedCustomViewStyle());
        mBuilder.setCustomContentView(new RemoteViews(getContext().getPackageName(),
                R.layout.custom_view_dark));
        RemoteViews decoratedMediaView = mBuilder.createContentView();
        assertFalse("The decorated media style doesn't allow a view to be reapplied!",
                NotificationContentInflater.canReapplyRemoteView(mediaView, decoratedMediaView));
    }

    @Test
    @Ignore
    public void testUsesSameViewWhenCachedPossibleToReuse() throws Exception {
        // GIVEN a cached view.
        RemoteViews contractedRemoteView = mBuilder.createContentView();
        when(mCache.hasCachedView(mRow.getEntry(), FLAG_CONTENT_VIEW_CONTRACTED))
                .thenReturn(true);
        when(mCache.getCachedView(mRow.getEntry(), FLAG_CONTENT_VIEW_CONTRACTED))
                .thenReturn(contractedRemoteView);

        // GIVEN existing bound view with same layout id.
        View view = contractedRemoteView.apply(mContext, null /* parent */);
        mRow.getPrivateLayout().setContractedChild(view);

        // WHEN inflater inflates
        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, mRow);

        // THEN the view should be re-used
        assertEquals("Binder inflated a new view even though the old one was cached and usable.",
                view, mRow.getPrivateLayout().getContractedChild());
    }

    @Test
    public void testInflatesNewViewWhenCachedNotPossibleToReuse() throws Exception {
        // GIVEN a cached remote view.
        RemoteViews contractedRemoteView = mBuilder.createHeadsUpContentView();
        when(mCache.hasCachedView(mRow.getEntry(), FLAG_CONTENT_VIEW_CONTRACTED))
                .thenReturn(true);
        when(mCache.getCachedView(mRow.getEntry(), FLAG_CONTENT_VIEW_CONTRACTED))
                .thenReturn(contractedRemoteView);

        // GIVEN existing bound view with different layout id.
        View view = new TextView(mContext);
        mRow.getPrivateLayout().setContractedChild(view);

        // WHEN inflater inflates
        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, mRow);

        // THEN the view should be a new view
        assertNotEquals("Binder (somehow) used the same view when inflating.",
                view, mRow.getPrivateLayout().getContractedChild());
    }

    @Test
    public void testInflationCachesCreatedRemoteView() throws Exception {
        // WHEN inflater inflates
        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_CONTRACTED, mRow);

        // THEN inflater informs cache of the new remote view
        verify(mCache).putCachedView(
                eq(mRow.getEntry()),
                eq(FLAG_CONTENT_VIEW_CONTRACTED),
                any());
    }

    @Test
    public void testUnbindRemovesCachedRemoteView() {
        // WHEN inflated unbinds content
        mNotificationInflater.unbindContent(mRow.getEntry(), mRow, FLAG_CONTENT_VIEW_HEADS_UP);

        // THEN inflated informs cache to remove remote view
        verify(mCache).removeCachedView(
                eq(mRow.getEntry()),
                eq(FLAG_CONTENT_VIEW_HEADS_UP));
    }

    @Test
    @Ignore
    public void testNotificationViewHeightTooSmallFailsValidation() {
        View view = mock(View.class);
        when(view.getHeight())
                .thenReturn((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                        mContext.getResources().getDisplayMetrics()));
        String result = NotificationContentInflater.isValidView(view, mRow.getEntry(),
                mContext.getResources());
        assertNotNull(result);
    }

    @Test
    public void testNotificationViewPassesValidation() {
        View view = mock(View.class);
        when(view.getHeight())
                .thenReturn((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 17,
                        mContext.getResources().getDisplayMetrics()));
        String result = NotificationContentInflater.isValidView(view, mRow.getEntry(),
                mContext.getResources());
        assertNull(result);
    }

    @Test
    public void testInvalidNotificationDoesNotInvokeCallback() throws Exception {
        mRow.getPrivateLayout().removeAllViews();
        mRow.getEntry().getSbn().getNotification().contentView =
                new RemoteViews(mContext.getPackageName(), R.layout.invalid_notification_height);
        inflateAndWait(true, mNotificationInflater, FLAG_CONTENT_VIEW_ALL, REDACTION_TYPE_NONE,
                mRow);
        assertEquals(0, mRow.getPrivateLayout().getChildCount());
        verify(mRow, times(0)).onNotificationUpdated();
    }

    @Test
    @DisableFlags({PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME})
    public void testExtractsPromotedContent_notWhenBothFlagsDisabled() throws Exception {
        final PromotedNotificationContentModel content =
                new PromotedNotificationContentModel.Builder("key").build();
        mPromotedNotificationContentExtractor.resetForEntry(mRow.getEntry(), content);

        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_ALL, mRow);

        mPromotedNotificationContentExtractor.verifyZeroExtractCalls();
    }

    @Test
    @EnableFlags(PromotedNotificationUi.FLAG_NAME)
    @DisableFlags(StatusBarNotifChips.FLAG_NAME)
    public void testExtractsPromotedContent_whenPromotedNotificationUiFlagEnabled()
            throws Exception {
        final PromotedNotificationContentModel content =
                new PromotedNotificationContentModel.Builder("key").build();
        mPromotedNotificationContentExtractor.resetForEntry(mRow.getEntry(), content);

        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_ALL, mRow);

        mPromotedNotificationContentExtractor.verifyOneExtractCall();
        assertEquals(content, mRow.getEntry().getPromotedNotificationContentModel());
    }

    @Test
    @EnableFlags(StatusBarNotifChips.FLAG_NAME)
    @DisableFlags(PromotedNotificationUi.FLAG_NAME)
    public void testExtractsPromotedContent_whenStatusBarNotifChipsFlagEnabled() throws Exception {
        final PromotedNotificationContentModel content =
                new PromotedNotificationContentModel.Builder("key").build();
        mPromotedNotificationContentExtractor.resetForEntry(mRow.getEntry(), content);

        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_ALL, mRow);

        mPromotedNotificationContentExtractor.verifyOneExtractCall();
        assertEquals(content, mRow.getEntry().getPromotedNotificationContentModel());
    }

    @Test
    @EnableFlags({PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME})
    public void testExtractsPromotedContent_whenBothFlagsEnabled() throws Exception {
        final PromotedNotificationContentModel content =
                new PromotedNotificationContentModel.Builder("key").build();
        mPromotedNotificationContentExtractor.resetForEntry(mRow.getEntry(), content);

        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_ALL, mRow);

        mPromotedNotificationContentExtractor.verifyOneExtractCall();
        assertEquals(content, mRow.getEntry().getPromotedNotificationContentModel());
    }

    @Test
    @EnableFlags({PromotedNotificationUi.FLAG_NAME, StatusBarNotifChips.FLAG_NAME})
    public void testExtractsPromotedContent_null() throws Exception {
        mPromotedNotificationContentExtractor.resetForEntry(mRow.getEntry(), null);

        inflateAndWait(mNotificationInflater, FLAG_CONTENT_VIEW_ALL, mRow);

        mPromotedNotificationContentExtractor.verifyOneExtractCall();
        assertNull(mRow.getEntry().getPromotedNotificationContentModel());
    }

    @Test
    @EnableFlags(LockscreenOtpRedaction.FLAG_NAME)
    public void testSensitiveContentPublicView_messageStyle() throws Exception {
        String displayName = "Display Name";
        String messageText = "Message Text";
        String contentText = "Content Text";
        Icon personIcon = Icon.createWithResource(mContext,
                com.android.systemui.res.R.drawable.ic_person);
        Person testPerson = new Person.Builder()
                .setName(displayName)
                .setIcon(personIcon)
                .build();
        Notification.MessagingStyle messagingStyle = new Notification.MessagingStyle(testPerson);
        messagingStyle.addMessage(new Notification.MessagingStyle.Message(messageText,
                System.currentTimeMillis(), testPerson));
        messagingStyle.setConversationType(Notification.MessagingStyle.CONVERSATION_TYPE_NORMAL);
        messagingStyle.setShortcutIcon(personIcon);
        Notification messageNotif = new Notification.Builder(mContext).setSmallIcon(
                com.android.systemui.res.R.drawable.ic_person).setStyle(messagingStyle).build();
        ExpandableNotificationRow row = mHelper.createRow(messageNotif);
        inflateAndWait(false, mNotificationInflater, FLAG_CONTENT_VIEW_PUBLIC,
                REDACTION_TYPE_SENSITIVE_CONTENT, row);
        NotificationContentView publicView = row.getPublicLayout();
        assertNotNull(publicView);
        // The display name should be included, but not the content or message text
        assertFalse(hasText(publicView, messageText));
        assertFalse(hasText(publicView, contentText));
        assertTrue(hasText(publicView, displayName));
    }

    @Test
    @EnableFlags(LockscreenOtpRedaction.FLAG_NAME)
    public void testSensitiveContentPublicView_nonMessageStyle() throws Exception {
        String contentTitle = "Content Title";
        String contentText = "Content Text";
        Notification notif = new Notification.Builder(mContext).setSmallIcon(
                com.android.systemui.res.R.drawable.ic_person)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .build();
        ExpandableNotificationRow row = mHelper.createRow(notif);
        inflateAndWait(false, mNotificationInflater, FLAG_CONTENT_VIEW_PUBLIC,
                REDACTION_TYPE_SENSITIVE_CONTENT, row);
        NotificationContentView publicView = row.getPublicLayout();
        assertNotNull(publicView);
        assertFalse(hasText(publicView, contentText));
        assertTrue(hasText(publicView, contentTitle));

        // The standard public view should not use the content title or text
        inflateAndWait(false, mNotificationInflater, FLAG_CONTENT_VIEW_PUBLIC,
                REDACTION_TYPE_PUBLIC, row);
        publicView = row.getPublicLayout();
        assertFalse(hasText(publicView, contentText));
        assertFalse(hasText(publicView, contentTitle));
    }

    private static boolean hasText(ViewGroup parent, CharSequence text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) {
                if (hasText((ViewGroup) child, text)) {
                    return true;
                }
            } else if (child instanceof TextView) {
                return ((TextView) child).getText().toString().contains(text);
            }
        }
        return false;
    }

    private static void inflateAndWait(NotificationContentInflater inflater,
            @InflationFlag int contentToInflate,
            ExpandableNotificationRow row)
            throws Exception {
        inflateAndWait(false /* expectingException */, inflater, contentToInflate,
                REDACTION_TYPE_NONE, row);
    }

    private static void inflateAndWait(boolean expectingException,
            NotificationContentInflater inflater,
            @InflationFlag int contentToInflate,
            @RedactionType int redactionType,
            ExpandableNotificationRow row) throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        final ExceptionHolder exceptionHolder = new ExceptionHolder();
        inflater.setInflateSynchronously(true);
        InflationCallback callback = new InflationCallback() {
            @Override
            public void handleInflationException(NotificationEntry entry,
                    Exception e) {
                if (!expectingException) {
                    exceptionHolder.setException(e);
                }
                countDownLatch.countDown();
            }

            @Override
            public void onAsyncInflationFinished(NotificationEntry entry) {
                if (expectingException) {
                    exceptionHolder.setException(new RuntimeException(
                            "Inflation finished even though there should be an error"));
                }
                countDownLatch.countDown();
            }
        };
        inflater.bindContent(
                row.getEntry(),
                row,
                contentToInflate,
                new BindParams(false, redactionType),
                false /* forceInflate */,
                callback /* callback */);
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS));
        if (exceptionHolder.mException != null) {
            throw exceptionHolder.mException;
        }
    }

    private static class ExceptionHolder {
        private Exception mException;

        public void setException(Exception exception) {
            mException = exception;
        }
    }

    private static class AsyncFailRemoteView extends RemoteViews {
        Handler mHandler = Handler.createAsync(Looper.getMainLooper());

        public AsyncFailRemoteView(String packageName, int layoutId) {
            super(packageName, layoutId);
        }

        @Override
        public View apply(Context context, ViewGroup parent) {
            return super.apply(context, parent);
        }

        @Override
        public CancellationSignal applyAsync(Context context, ViewGroup parent, Executor executor,
                OnViewAppliedListener listener, InteractionHandler handler) {
            mHandler.post(() -> listener.onError(new RuntimeException("Failed to inflate async")));
            return new CancellationSignal();
        }

        @Override
        public CancellationSignal applyAsync(Context context, ViewGroup parent, Executor executor,
                OnViewAppliedListener listener) {
            return applyAsync(context, parent, executor, listener, null);
        }
    }
}
