/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.server.dreams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.IntDef;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.RemoteException;
import android.service.dreams.DreamActivity;
import android.service.dreams.DreamOverlayConnectionHandler;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.dreams.IDreamOverlayCallback;
import android.service.dreams.IDreamOverlayClient;
import android.service.dreams.IDreamService;
import android.testing.TestableLooper;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Consumer;


/**
 * {@link TestDreamEnvironment} sets up entire testing environment around a dream service, allowing
 * bring-up and interaction around the dream.
 *
 * @hide
 */
@VisibleForTesting
public class TestDreamEnvironment {
    private static final String FAKE_DREAM_PACKAGE_NAME = "com.foo.bar";
    private static final ComponentName FAKE_DREAM_OVERLAY_COMPONENT =
            ComponentName.createRelative(FAKE_DREAM_PACKAGE_NAME, ".OverlayComponent");
    private static final String FAKE_DREAM_SETTINGS_ACTIVITY = ".SettingsActivity";
    private static final ComponentName FAKE_DREAM_ACTIVITY_COMPONENT =
            ComponentName.createRelative(FAKE_DREAM_PACKAGE_NAME, ".DreamActivity");
    private static final ComponentName FAKE_DREAM_COMPONENT =
            ComponentName.createRelative(FAKE_DREAM_PACKAGE_NAME, ".DreamService");

    /** Initial state when creating a test environment */
    public static final int DREAM_STATE_INIT = 0;

    /** State where the dream has been created */
    public static final int DREAM_STATE_CREATE = 1;

    /** State where the dream has been bound to */
    public static final int DREAM_STATE_BIND = 2;

    /** State where the dream activity has been created and signaled back to the dream */
    public static final int DREAM_STATE_DREAM_ACTIVITY_CREATED = 3;


    /** State where the dream has started after being attached */
    public static final int DREAM_STATE_STARTED = 4;

    /** State where the dream has been woken */
    public static final int DREAM_STATE_WOKEN = 5;


    @IntDef(prefix = { "DREAM_STATE_" }, value = {
            DREAM_STATE_INIT,
            DREAM_STATE_CREATE,
            DREAM_STATE_BIND,
            DREAM_STATE_DREAM_ACTIVITY_CREATED,
            DREAM_STATE_STARTED,
            DREAM_STATE_WOKEN,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DreamState{}

    /**
     * A convenience builder for assembling a {@link TestDreamEnvironment}.
     */
    public static class Builder {
        private final TestableLooper mLooper;
        private boolean mShouldShowComplications;
        private boolean mOverlayPresent;
        private boolean mWindowless;

        public Builder(TestableLooper looper) {
            mLooper = looper;
        }

        /**
         * Sets whether the dream should show complications.
         * @param show {@code true} for showing complications, {@code false} otherwise.
         * @return The updated {@link Builder}.
         */
        public Builder setShouldShowComplications(boolean show) {
            mShouldShowComplications = show;
            return this;
        }

        /**
         * Sets whether a dream overlay is present
         * @param present {@code true} if the overlay is present, {@code false} otherwise.
         */
        public Builder setDreamOverlayPresent(boolean present) {
            mOverlayPresent = present;
            return this;
        }

        /**
         * Sets whether the dream should be windowless
         */
        public Builder setWindowless(boolean windowless) {
            mWindowless = windowless;
            return this;
        }

        /**
         * Builds the a {@link TestDreamEnvironment} based on the builder.
         */
        public TestDreamEnvironment build() throws Exception {
            return new TestDreamEnvironment(mLooper, mShouldShowComplications,
                    mOverlayPresent, mWindowless);
        }
    }

    private static class TestInjector implements DreamService.Injector {
        @Mock
        private Resources mResources;

        @Mock
        private PackageManager mPackageManager;

        @Mock
        private TypedArray mAttributes;

        @Mock
        private ServiceInfo mServiceInfo;

        private final Handler mHandler;
        private final IDreamManager mDreamManager;
        private final DreamOverlayConnectionHandler mDreamOverlayConnectionHandler;

        TestInjector(Looper looper,
                IDreamManager dreamManager,
                DreamOverlayConnectionHandler dreamOverlayConnectionHandler,
                boolean shouldShowComplications) {
            MockitoAnnotations.initMocks(this);
            mHandler = new Handler(looper);
            mDreamManager = dreamManager;
            mDreamOverlayConnectionHandler = dreamOverlayConnectionHandler;
            mServiceInfo.packageName = FAKE_DREAM_PACKAGE_NAME;
            when(mAttributes.getBoolean(eq(R.styleable.Dream_showClockAndComplications),
                    anyBoolean()))
                    .thenReturn(shouldShowComplications);
            when(mAttributes.getDrawable(eq(com.android.internal.R.styleable.Dream_previewImage)))
                    .thenReturn(mock(Drawable.class));
            when(mAttributes.getString(eq(com.android.internal.R.styleable.Dream_settingsActivity)))
                    .thenReturn(FAKE_DREAM_SETTINGS_ACTIVITY);
            when(mPackageManager.extractPackageItemInfoAttributes(any(), any(), any(), any()))
                    .thenReturn(mAttributes);
        }
        @Override
        public void init(Context context) {
        }

        @Override
        public DreamOverlayConnectionHandler createOverlayConnection(
                ComponentName overlayComponent, Runnable onDisconnected) {
            return mDreamOverlayConnectionHandler;
        }

        @Override
        public ComponentName getDreamActivityComponent() {
            return FAKE_DREAM_ACTIVITY_COMPONENT;
        }

        @Override
        public ComponentName getDreamComponent() {
            return FAKE_DREAM_COMPONENT;
        }

        @Override
        public String getDreamPackageName() {
            return FAKE_DREAM_PACKAGE_NAME;
        }

        @Override
        public IDreamManager getDreamManager() {
            return mDreamManager;
        }

        @Override
        public ServiceInfo getServiceInfo() {
            return mServiceInfo;
        }

        @Override
        public Handler getHandler() {
            return mHandler;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPackageManager;
        }

        @Override
        public Resources getResources() {
            return mResources;
        }
    }

    @Mock
    private IDreamManager mDreamManager;

    @Mock
    private DreamOverlayConnectionHandler mDreamOverlayConnectionHandler;

    @Mock
    private DreamActivity mDreamActivity;

    @Mock
    private Window mActivityWindow;

    @Mock
    private View mDecorView;

    @Mock
    private IDreamOverlayClient mDreamOverlayClient;

    private final TestableLooper mTestableLooper;
    private final DreamService mService;
    private final boolean mWindowless;

    private IDreamService mDreamServiceWrapper;

    private @DreamState int mCurrentDreamState = DREAM_STATE_INIT;

    private IDreamOverlayCallback mDreamOverlayCallback;

    private boolean mOverlayPresent;

    public TestDreamEnvironment(
            TestableLooper looper,
            boolean shouldShowComplications,
            boolean overlayPresent,
            boolean windowless
    ) {
        MockitoAnnotations.initMocks(this);

        mOverlayPresent = overlayPresent;
        mWindowless = windowless;

        mTestableLooper = looper;
        final DreamService.Injector injector = new TestInjector(mTestableLooper.getLooper(),
                mDreamManager, mDreamOverlayConnectionHandler, shouldShowComplications);

        when(mDreamActivity.getWindow()).thenReturn(mActivityWindow);
        when(mActivityWindow.getAttributes()).thenReturn(mock(WindowManager.LayoutParams.class));
        when(mActivityWindow.getDecorView()).thenReturn(mDecorView);
        when(mDreamOverlayConnectionHandler.bind()).thenReturn(true);

        doAnswer((InvocationOnMock invocation) -> {
            Consumer<IDreamOverlayClient> client =
                    (Consumer<IDreamOverlayClient>) invocation.getArguments()[0];
            client.accept(mDreamOverlayClient);
            return null;
        }).when(mDreamOverlayConnectionHandler).addConsumer(any());
        when(mDecorView.getWindowInsetsController()).thenReturn(
                mock(WindowInsetsController.class));

        mService = new DreamService(injector);
    }

    /**
     * Advances a dream to a given state, progressing through all previous states.
     */
    public boolean advance(@DreamState int state) throws Exception {
        if (state <= mCurrentDreamState) {
            return false;
        }

        do {
            switch(++mCurrentDreamState) {
                case DREAM_STATE_CREATE -> createDream();
                case DREAM_STATE_BIND -> bindToDream();
                case DREAM_STATE_DREAM_ACTIVITY_CREATED -> createDreamActivity();
                case DREAM_STATE_STARTED -> startDream();
                case DREAM_STATE_WOKEN -> wakeDream();
            }
        } while (mCurrentDreamState < state);

        return true;
    }

    private void createDream() {
        mService.onCreate();
        mService.setWindowless(mWindowless);
    }

    private void bindToDream() {
        final Intent intent = new Intent();

        if (mOverlayPresent) {
            DreamService.setDreamOverlayComponent(intent, FAKE_DREAM_OVERLAY_COMPONENT);
        }

        mDreamServiceWrapper = IDreamService.Stub.asInterface(mService.onBind(intent));

        if (mOverlayPresent) {
            // Ensure that the overlay has been bound by the dream.
            verify(mDreamOverlayConnectionHandler,
                    description("dream did not bind to the dream overlay")).bind();
        }
    }

    private void createDreamActivity() throws RemoteException {
        final IRemoteCallback callback = mock(IRemoteCallback.class);
        mDreamServiceWrapper.attach(mock(IBinder.class), false, false, callback);
        mTestableLooper.processAllMessages();

        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mDreamManager,
                description("dream manager was not informed to start the dream activity"))
                .startDreamActivity(intentCaptor.capture());
        final Intent intent = intentCaptor.getValue();

        final DreamService.DreamActivityCallbacks dreamActivityCallbacks =
                DreamActivity.getCallback(intent);

        dreamActivityCallbacks.onActivityCreated(mDreamActivity);
    }

    private void startDream() throws RemoteException {
        final ArgumentCaptor<View.OnAttachStateChangeListener> attachChangeListenerCaptor =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);
        verify(mDecorView,
                description("dream did not add a listener to when the decor view is attached"))
                .addOnAttachStateChangeListener(attachChangeListenerCaptor.capture());

        resetClientInvocations();
        attachChangeListenerCaptor.getValue().onViewAttachedToWindow(mDecorView);

        if (mOverlayPresent) {
            final ArgumentCaptor<IDreamOverlayCallback> overlayCallbackCaptor =
                    ArgumentCaptor.forClass(IDreamOverlayCallback.class);
            verify(mDreamOverlayClient, description("dream client not informed of dream start"))
                    .startDream(any(), overlayCallbackCaptor.capture(), any(), anyBoolean());

            mDreamOverlayCallback = overlayCallbackCaptor.getValue();
        }
    }

    /**
     * Sends a key event to the dream.
     */
    public void dispatchKeyEvent(KeyEvent event) {
        mService.dispatchKeyEvent(event);
    }

    private void wakeDream() throws RemoteException {
        mService.wakeUp();
    }

    /**
     * Retrieves the dream overlay callback.
     */
    public IDreamOverlayCallback getDreamOverlayCallback() throws Exception {
        advance(DREAM_STATE_STARTED);
        return mDreamOverlayCallback;
    }

    /**
     * Resets interactions with the dream overlay client.
     */
    public void resetClientInvocations() {
        Mockito.clearInvocations(mDreamOverlayClient);
    }

    /**
     * Retrieves the dream overlay client.
     */
    public IDreamOverlayClient getDreamOverlayClient() {
        return mDreamOverlayClient;
    }
}
