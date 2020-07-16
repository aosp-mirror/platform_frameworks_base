/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.service.controls.actions.ControlAction;
import android.service.controls.actions.ControlActionWrapper;
import android.service.controls.templates.ControlTemplate;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;

/**
 * Service implementation allowing applications to contribute controls to the
 * System UI.
 */
public abstract class ControlsProviderService extends Service {

    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_CONTROLS =
            "android.service.controls.ControlsProviderService";

    /**
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ADD_CONTROL =
            "android.service.controls.action.ADD_CONTROL";

    /**
     * @hide
     */
    public static final String EXTRA_CONTROL =
            "android.service.controls.extra.CONTROL";

    /**
     * @hide
     */
    public static final String CALLBACK_BUNDLE = "CALLBACK_BUNDLE";

    /**
     * @hide
     */
    public static final String CALLBACK_TOKEN = "CALLBACK_TOKEN";

    public static final @NonNull String TAG = "ControlsProviderService";

    private IBinder mToken;
    private RequestHandler mHandler;

    /**
     * Publisher for all available controls
     *
     * Retrieve all available controls. Use the stateless builder {@link Control.StatelessBuilder}
     * to build each Control. Call {@link Subscriber#onComplete} when done loading all unique
     * controls, or {@link Subscriber#onError} for error scenarios. Duplicate Controls will
     * replace the original.
     */
    @NonNull
    public abstract Publisher<Control> createPublisherForAllAvailable();

    /**
     * (Optional) Publisher for suggested controls
     *
     * The service may be asked to provide a small number of recommended controls, in
     * order to suggest some controls to the user for favoriting. The controls shall be built using
     * the stateless builder {@link Control.StatelessBuilder}. The total number of controls
     * requested through {@link Subscription#request} will be restricted to a maximum. Within this
     * larger limit, only 6 controls per structure will be loaded. Therefore, it is advisable to
     * seed multiple structures if they exist. Any control sent over this limit  will be discarded.
     * Call {@link Subscriber#onComplete} when done, or {@link Subscriber#onError} for error
     * scenarios.
     */
    @Nullable
    public Publisher<Control> createPublisherForSuggested() {
        return null;
    }

    /**
     * Return a valid Publisher for the given controlIds. This publisher will be asked to provide
     * updates for the given list of controlIds as long as the {@link Subscription} is valid.
     * Calls to {@link Subscriber#onComplete} will not be expected. Instead, wait for the call from
     * {@link Subscription#cancel} to indicate that updates are no longer required. It is expected
     * that controls provided by this publisher were created using {@link Control.StatefulBuilder}.
     */
    @NonNull
    public abstract Publisher<Control> createPublisherFor(@NonNull List<String> controlIds);

    /**
     * The user has interacted with a Control. The action is dictated by the type of
     * {@link ControlAction} that was sent. A response can be sent via
     * {@link Consumer#accept}, with the Integer argument being one of the provided
     * {@link ControlAction.ResponseResult}. The Integer should indicate whether the action
     * was received successfully, or if additional prompts should be presented to
     * the user. Any visual control updates should be sent via the Publisher.
     */
    public abstract void performControlAction(@NonNull String controlId,
            @NonNull ControlAction action, @NonNull Consumer<Integer> consumer);

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        mHandler = new RequestHandler(Looper.getMainLooper());

        Bundle bundle = intent.getBundleExtra(CALLBACK_BUNDLE);
        mToken = bundle.getBinder(CALLBACK_TOKEN);

        return new IControlsProvider.Stub() {
            public void load(IControlsSubscriber subscriber) {
                mHandler.obtainMessage(RequestHandler.MSG_LOAD, subscriber).sendToTarget();
            }

            public void loadSuggested(IControlsSubscriber subscriber) {
                mHandler.obtainMessage(RequestHandler.MSG_LOAD_SUGGESTED, subscriber)
                        .sendToTarget();
            }

            public void subscribe(List<String> controlIds,
                    IControlsSubscriber subscriber) {
                SubscribeMessage msg = new SubscribeMessage(controlIds, subscriber);
                mHandler.obtainMessage(RequestHandler.MSG_SUBSCRIBE, msg).sendToTarget();
            }

            public void action(String controlId, ControlActionWrapper action,
                               IControlsActionCallback cb) {
                ActionMessage msg = new ActionMessage(controlId, action.getWrappedAction(), cb);
                mHandler.obtainMessage(RequestHandler.MSG_ACTION, msg).sendToTarget();
            }
        };
    }

    @Override
    public final boolean onUnbind(@NonNull Intent intent) {
        mHandler = null;
        return true;
    }

    private class RequestHandler extends Handler {
        private static final int MSG_LOAD = 1;
        private static final int MSG_SUBSCRIBE = 2;
        private static final int MSG_ACTION = 3;
        private static final int MSG_LOAD_SUGGESTED = 4;

        RequestHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_LOAD: {
                    final IControlsSubscriber cs = (IControlsSubscriber) msg.obj;
                    final SubscriberProxy proxy = new SubscriberProxy(true, mToken, cs);

                    ControlsProviderService.this.createPublisherForAllAvailable().subscribe(proxy);
                    break;
                }

                case MSG_LOAD_SUGGESTED: {
                    final IControlsSubscriber cs = (IControlsSubscriber) msg.obj;
                    final SubscriberProxy proxy = new SubscriberProxy(true, mToken, cs);

                    Publisher<Control> publisher =
                            ControlsProviderService.this.createPublisherForSuggested();
                    if (publisher == null) {
                        Log.i(TAG, "No publisher provided for suggested controls");
                        proxy.onComplete();
                    } else {
                        publisher.subscribe(proxy);
                    }
                    break;
                }

                case MSG_SUBSCRIBE: {
                    final SubscribeMessage sMsg = (SubscribeMessage) msg.obj;
                    final SubscriberProxy proxy = new SubscriberProxy(false, mToken,
                            sMsg.mSubscriber);

                    ControlsProviderService.this.createPublisherFor(sMsg.mControlIds)
                            .subscribe(proxy);
                    break;
                }

                case MSG_ACTION: {
                    final ActionMessage aMsg = (ActionMessage) msg.obj;
                    ControlsProviderService.this.performControlAction(aMsg.mControlId,
                            aMsg.mAction, consumerFor(aMsg.mControlId, aMsg.mCb));
                    break;
                }
            }
        }

        private Consumer<Integer> consumerFor(final String controlId,
                final IControlsActionCallback cb) {
            return (@NonNull Integer response) -> {
                Preconditions.checkNotNull(response);
                if (!ControlAction.isValidResponse(response)) {
                    Log.e(TAG, "Not valid response result: " + response);
                    response = ControlAction.RESPONSE_UNKNOWN;
                }
                try {
                    cb.accept(mToken, controlId, response);
                } catch (RemoteException ex) {
                    ex.rethrowAsRuntimeException();
                }
            };
        }
    }

    private static boolean isStatelessControl(Control control) {
        return (control.getStatus() == Control.STATUS_UNKNOWN
                && control.getControlTemplate().getTemplateType()
                == ControlTemplate.TYPE_NO_TEMPLATE
                && TextUtils.isEmpty(control.getStatusText()));
    }

    private static class SubscriberProxy implements Subscriber<Control> {
        private IBinder mToken;
        private IControlsSubscriber mCs;
        private boolean mEnforceStateless;

        SubscriberProxy(boolean enforceStateless, IBinder token, IControlsSubscriber cs) {
            mEnforceStateless = enforceStateless;
            mToken = token;
            mCs = cs;
        }

        public void onSubscribe(Subscription subscription) {
            try {
                mCs.onSubscribe(mToken, new SubscriptionAdapter(subscription));
            } catch (RemoteException ex) {
                ex.rethrowAsRuntimeException();
            }
        }
        public void onNext(@NonNull Control control) {
            Preconditions.checkNotNull(control);
            try {
                if (mEnforceStateless && !isStatelessControl(control)) {
                    Log.w(TAG, "onNext(): control is not stateless. Use the "
                            + "Control.StatelessBuilder() to build the control.");
                    control = new Control.StatelessBuilder(control).build();
                }
                mCs.onNext(mToken, control);
            } catch (RemoteException ex) {
                ex.rethrowAsRuntimeException();
            }
        }
        public void onError(Throwable t) {
            try {
                mCs.onError(mToken, t.toString());
            } catch (RemoteException ex) {
                ex.rethrowAsRuntimeException();
            }
        }
        public void onComplete() {
            try {
                mCs.onComplete(mToken);
            } catch (RemoteException ex) {
                ex.rethrowAsRuntimeException();
            }
        }
    }

    /**
     * Request SystemUI to prompt the user to add a control to favorites.
     * <br>
     * SystemUI may not honor this request in some cases, for example if the requested
     * {@link Control} is already a favorite, or the requesting package is not currently in the
     * foreground.
     *
     * @param context A context
     * @param componentName Component name of the {@link ControlsProviderService}
     * @param control A stateless control to show to the user
     */
    public static void requestAddControl(@NonNull Context context,
            @NonNull ComponentName componentName,
            @NonNull Control control) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(componentName);
        Preconditions.checkNotNull(control);
        final String controlsPackage = context.getString(
                com.android.internal.R.string.config_controlsPackage);
        Intent intent = new Intent(ACTION_ADD_CONTROL);
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME, componentName);
        intent.setPackage(controlsPackage);
        if (isStatelessControl(control)) {
            intent.putExtra(EXTRA_CONTROL, control);
        } else {
            intent.putExtra(EXTRA_CONTROL, new Control.StatelessBuilder(control).build());
        }
        context.sendBroadcast(intent, Manifest.permission.BIND_CONTROLS);
    }

    private static class SubscriptionAdapter extends IControlsSubscription.Stub {
        final Subscription mSubscription;

        SubscriptionAdapter(Subscription s) {
            this.mSubscription = s;
        }

        public void request(long n) {
            mSubscription.request(n);
        }

        public void cancel() {
            mSubscription.cancel();
        }
    }

    private static class ActionMessage {
        final String mControlId;
        final ControlAction mAction;
        final IControlsActionCallback mCb;

        ActionMessage(String controlId, ControlAction action, IControlsActionCallback cb) {
            this.mControlId = controlId;
            this.mAction = action;
            this.mCb = cb;
        }
    }

    private static class SubscribeMessage {
        final List<String> mControlIds;
        final IControlsSubscriber mSubscriber;

        SubscribeMessage(List<String> controlIds, IControlsSubscriber subscriber) {
            this.mControlIds = controlIds;
            this.mSubscriber = subscriber;
        }
    }
}
