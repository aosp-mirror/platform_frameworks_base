/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.controls;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.controls.actions.CommandAction;
import android.service.controls.actions.ControlAction;
import android.service.controls.actions.ControlActionWrapper;
import android.service.controls.templates.ThumbnailTemplate;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ControlProviderServiceTest {

    private static final String TEST_CONTROLS_PACKAGE = "sysui";
    private static final ComponentName TEST_COMPONENT =
            ComponentName.unflattenFromString("test.pkg/.test.cls");

    private IBinder mToken = new Binder();
    @Mock
    private IControlsActionCallback.Stub mActionCallback;
    @Mock
    private IControlsSubscriber.Stub mSubscriber;
    @Mock
    private IIntentSender mIIntentSender;
    @Mock
    private Resources mResources;
    @Mock
    private Context mContext;
    @Captor
    private ArgumentCaptor<Intent> mIntentArgumentCaptor;

    private PendingIntent mPendingIntent;
    private FakeControlsProviderService mControlsProviderService;

    private IControlsProvider mControlsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mActionCallback.asBinder()).thenCallRealMethod();
        when(mActionCallback.queryLocalInterface(any())).thenReturn(mActionCallback);
        when(mSubscriber.asBinder()).thenCallRealMethod();
        when(mSubscriber.queryLocalInterface(any())).thenReturn(mSubscriber);

        when(mResources.getString(com.android.internal.R.string.config_controlsPackage))
                .thenReturn(TEST_CONTROLS_PACKAGE);
        when(mContext.getResources()).thenReturn(mResources);

        Bundle b = new Bundle();
        b.putBinder(ControlsProviderService.CALLBACK_TOKEN, mToken);
        Intent intent = new Intent();
        intent.putExtra(ControlsProviderService.CALLBACK_BUNDLE, b);

        mPendingIntent = new PendingIntent(mIIntentSender);

        mControlsProviderService = new FakeControlsProviderService(
                InstrumentationRegistry.getInstrumentation().getContext());
        mControlsProvider = IControlsProvider.Stub.asInterface(
                mControlsProviderService.onBind(intent));
    }

    @Test
    public void testOnLoad_allStateless() throws RemoteException {
        Control control1 = new Control.StatelessBuilder("TEST_ID", mPendingIntent).build();
        Control control2 = new Control.StatelessBuilder("TEST_ID_2", mPendingIntent)
                .setDeviceType(DeviceTypes.TYPE_AIR_FRESHENER).build();

        ArgumentCaptor<IControlsSubscription.Stub> subscriptionCaptor =
                ArgumentCaptor.forClass(IControlsSubscription.Stub.class);
        ArgumentCaptor<Control> controlCaptor =
                ArgumentCaptor.forClass(Control.class);

        ArrayList<Control> list = new ArrayList<>();
        list.add(control1);
        list.add(control2);

        mControlsProviderService.setControls(list);
        mControlsProvider.load(mSubscriber);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        verify(mSubscriber).onSubscribe(eq(mToken), subscriptionCaptor.capture());
        subscriptionCaptor.getValue().request(1000);

        verify(mSubscriber, times(2))
                .onNext(eq(mToken), controlCaptor.capture());
        List<Control> values = controlCaptor.getAllValues();
        assertTrue(equals(values.get(0), list.get(0)));
        assertTrue(equals(values.get(1), list.get(1)));

        verify(mSubscriber).onComplete(eq(mToken));
    }

    @Test
    public void testOnLoad_statefulConvertedToStateless() throws RemoteException {
        Control control = new Control.StatefulBuilder("TEST_ID", mPendingIntent)
                .setTitle("TEST_TITLE")
                .setStatus(Control.STATUS_OK)
                .build();
        Control statelessControl = new Control.StatelessBuilder(control).build();

        ArgumentCaptor<IControlsSubscription.Stub> subscriptionCaptor =
                ArgumentCaptor.forClass(IControlsSubscription.Stub.class);
        ArgumentCaptor<Control> controlCaptor =
                ArgumentCaptor.forClass(Control.class);

        ArrayList<Control> list = new ArrayList<>();
        list.add(control);

        mControlsProviderService.setControls(list);
        mControlsProvider.load(mSubscriber);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        verify(mSubscriber).onSubscribe(eq(mToken), subscriptionCaptor.capture());
        subscriptionCaptor.getValue().request(1000);

        verify(mSubscriber).onNext(eq(mToken), controlCaptor.capture());
        Control c = controlCaptor.getValue();
        assertFalse(equals(control, c));
        assertTrue(equals(statelessControl, c));
        assertEquals(Control.STATUS_UNKNOWN, c.getStatus());

        verify(mSubscriber).onComplete(eq(mToken));
    }

    @Test
    public void testOnLoadSuggested_allStateless() throws RemoteException {
        Control control1 = new Control.StatelessBuilder("TEST_ID", mPendingIntent).build();
        Control control2 = new Control.StatelessBuilder("TEST_ID_2", mPendingIntent)
                .setDeviceType(DeviceTypes.TYPE_AIR_FRESHENER).build();

        ArgumentCaptor<IControlsSubscription.Stub> subscriptionCaptor =
                ArgumentCaptor.forClass(IControlsSubscription.Stub.class);
        ArgumentCaptor<Control> controlCaptor =
                ArgumentCaptor.forClass(Control.class);

        ArrayList<Control> list = new ArrayList<>();
        list.add(control1);
        list.add(control2);

        mControlsProviderService.setControls(list);
        mControlsProvider.loadSuggested(mSubscriber);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        verify(mSubscriber).onSubscribe(eq(mToken), subscriptionCaptor.capture());
        subscriptionCaptor.getValue().request(1);

        verify(mSubscriber).onNext(eq(mToken), controlCaptor.capture());
        Control c = controlCaptor.getValue();
        assertTrue(equals(c, list.get(0)));

        verify(mSubscriber).onComplete(eq(mToken));
    }

    @Test
    public void testSubscribe() throws RemoteException {
        Control control = new Control.StatefulBuilder("TEST_ID", mPendingIntent)
                .setTitle("TEST_TITLE")
                .setStatus(Control.STATUS_OK)
                .build();

        Control c = sendControlGetControl(control);
        assertTrue(equals(c, control));
    }

    @Test
    public void testThumbnailRescaled_bigger() throws RemoteException {
        Context context = mControlsProviderService.getBaseContext();
        int maxWidth = context.getResources().getDimensionPixelSize(
                R.dimen.controls_thumbnail_image_max_width);
        int maxHeight = context.getResources().getDimensionPixelSize(
                R.dimen.controls_thumbnail_image_max_height);

        int min = Math.min(maxWidth, maxHeight);
        int max = Math.max(maxWidth, maxHeight);

        Bitmap bitmap = Bitmap.createBitmap(max * 2, max * 2, Bitmap.Config.ALPHA_8);
        Icon icon = Icon.createWithBitmap(bitmap);
        ThumbnailTemplate template = new ThumbnailTemplate("ID", false, icon, "");

        Control control = new Control.StatefulBuilder("TEST_ID", mPendingIntent)
                .setTitle("TEST_TITLE")
                .setStatus(Control.STATUS_OK)
                .setControlTemplate(template)
                .build();

        Control c = sendControlGetControl(control);

        ThumbnailTemplate sentTemplate = (ThumbnailTemplate) c.getControlTemplate();
        Bitmap sentBitmap = sentTemplate.getThumbnail().getBitmap();

        // Aspect ratio is kept
        assertEquals(sentBitmap.getWidth(), sentBitmap.getHeight());

        assertEquals(min, sentBitmap.getWidth());
    }

    @Test
    public void testThumbnailRescaled_smaller() throws RemoteException {
        Context context = mControlsProviderService.getBaseContext();
        int maxWidth = context.getResources().getDimensionPixelSize(
                R.dimen.controls_thumbnail_image_max_width);
        int maxHeight = context.getResources().getDimensionPixelSize(
                R.dimen.controls_thumbnail_image_max_height);

        int min = Math.min(maxWidth, maxHeight);

        Bitmap bitmap = Bitmap.createBitmap(min / 2, min / 2, Bitmap.Config.ALPHA_8);
        Icon icon = Icon.createWithBitmap(bitmap);
        ThumbnailTemplate template = new ThumbnailTemplate("ID", false, icon, "");

        Control control = new Control.StatefulBuilder("TEST_ID", mPendingIntent)
                .setTitle("TEST_TITLE")
                .setStatus(Control.STATUS_OK)
                .setControlTemplate(template)
                .build();

        Control c = sendControlGetControl(control);

        ThumbnailTemplate sentTemplate = (ThumbnailTemplate) c.getControlTemplate();
        Bitmap sentBitmap = sentTemplate.getThumbnail().getBitmap();

        assertEquals(bitmap.getHeight(), sentBitmap.getHeight());
        assertEquals(bitmap.getWidth(), sentBitmap.getWidth());
    }

    @Test
    public void testOnAction() throws RemoteException {
        mControlsProvider.action("TEST_ID", new ControlActionWrapper(
                new CommandAction("", null)), mActionCallback);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        verify(mActionCallback).accept(mToken, "TEST_ID",
                ControlAction.RESPONSE_OK);
    }

    @Test
    public void testRequestAdd() {
        Control control = new Control.StatelessBuilder("TEST_ID", mPendingIntent).build();
        ControlsProviderService.requestAddControl(mContext, TEST_COMPONENT, control);

        verify(mContext).sendBroadcast(mIntentArgumentCaptor.capture(),
                eq(Manifest.permission.BIND_CONTROLS));
        Intent intent = mIntentArgumentCaptor.getValue();
        assertEquals(ControlsProviderService.ACTION_ADD_CONTROL, intent.getAction());
        assertEquals(TEST_CONTROLS_PACKAGE, intent.getPackage());
        assertEquals(TEST_COMPONENT, intent.getParcelableExtra(Intent.EXTRA_COMPONENT_NAME));
        assertTrue(equals(control,
                intent.getParcelableExtra(ControlsProviderService.EXTRA_CONTROL)));
    }

    @Test
    public void testOnNextDoesntRethrowDeadObjectException() throws RemoteException {
        doAnswer(invocation -> {
            throw new DeadObjectException();
        }).when(mSubscriber).onNext(ArgumentMatchers.any(), ArgumentMatchers.any());
        Control control = new Control.StatelessBuilder("TEST_ID", mPendingIntent).build();

        sendControlGetControl(control);

        assertTrue(mControlsProviderService.mSubscription.mIsCancelled);
    }

    /**
     * Sends the control through the publisher in {@code mControlsProviderService}, returning
     * the control obtained by the subscriber
     */
    private Control sendControlGetControl(Control control) throws RemoteException {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Control> controlCaptor =
                ArgumentCaptor.forClass(Control.class);
        ArgumentCaptor<IControlsSubscription.Stub> subscriptionCaptor =
                ArgumentCaptor.forClass(IControlsSubscription.Stub.class);

        ArrayList<Control> list = new ArrayList<>();
        list.add(control);

        mControlsProviderService.setControls(list);

        mControlsProvider.subscribe(new ArrayList<String>(), mSubscriber);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        verify(mSubscriber).onSubscribe(eq(mToken), subscriptionCaptor.capture());
        subscriptionCaptor.getValue().request(1);

        verify(mSubscriber).onNext(eq(mToken), controlCaptor.capture());
        return controlCaptor.getValue();
    }

    private static boolean equals(Control c1, Control c2) {
        if (c1 == c2) return true;
        if (c1 == null || c2 == null) return false;
        return Objects.equals(c1.getControlId(), c2.getControlId())
                && c1.getDeviceType() == c2.getDeviceType()
                && Objects.equals(c1.getTitle(), c2.getTitle())
                && Objects.equals(c1.getSubtitle(), c2.getSubtitle())
                && Objects.equals(c1.getStructure(), c2.getStructure())
                && Objects.equals(c1.getZone(), c2.getZone())
                && Objects.equals(c1.getAppIntent(), c2.getAppIntent())
                && Objects.equals(c1.getCustomIcon(), c2.getCustomIcon())
                && Objects.equals(c1.getCustomColor(), c2.getCustomColor())
                && c1.getStatus() == c2.getStatus()
                && Objects.equals(c1.getControlTemplate(), c2.getControlTemplate())
                && Objects.equals(c1.isAuthRequired(), c2.isAuthRequired())
                && Objects.equals(c1.getStatusText(), c2.getStatusText());
    }

    static class FakeControlsProviderService extends ControlsProviderService {

        FakeControlsProviderService(Context context) {
            super();
            attachBaseContext(context);
        }

        private List<Control> mControls;
        private FakeSubscription mSubscription;

        public void setControls(List<Control> controls) {
            mControls = controls;
        }

        @Override
        public Publisher<Control> createPublisherForAllAvailable() {
            return new Publisher<Control>() {
                public void subscribe(final Subscriber s) {
                    s.onSubscribe(createSubscription(s, mControls));
                }
            };
        }

        @Override
        public Publisher<Control> createPublisherFor(List<String> ids) {
            return new Publisher<Control>() {
                public void subscribe(final Subscriber s) {
                    s.onSubscribe(createSubscription(s, mControls));
                }
            };
        }

        @Override
        public Publisher<Control> createPublisherForSuggested() {
            return new Publisher<Control>() {
                public void subscribe(final Subscriber s) {
                    s.onSubscribe(createSubscription(s, mControls));
                }
            };
        }

        @Override
        public void performControlAction(String controlId, ControlAction action,
                Consumer<Integer> cb) {
            cb.accept(ControlAction.RESPONSE_OK);
        }

        private Subscription createSubscription(Subscriber s, List<Control> controls) {
            FakeSubscription subscription = new FakeSubscription(s, controls);
            mSubscription = subscription;
            return subscription;
        }
    }

    private static final class FakeSubscription implements Subscription {

        private final Subscriber mSubscriber;
        private final List<Control> mControls;

        private boolean mIsCancelled = false;

        FakeSubscription(Subscriber s, List<Control> controls) {
            mSubscriber = s;
            mControls = controls;
        }

        public void request(long n) {
            int i = 0;
            for (Control c : mControls) {
                if (i++ < n) mSubscriber.onNext(c);
                else break;
            }
            mSubscriber.onComplete();
        }

        public void cancel() {
            mIsCancelled = true;
        }
    }
}
