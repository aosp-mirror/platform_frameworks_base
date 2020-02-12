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

import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
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

import java.util.ArrayList;
import java.util.Collections;
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
    public static final String CALLBACK_BUNDLE = "CALLBACK_BUNDLE";

    /**
     * @hide
     */
    public static final String CALLBACK_TOKEN = "CALLBACK_TOKEN";

    public static final @NonNull String TAG = "ControlsProviderService";

    private IBinder mToken;
    private RequestHandler mHandler;

    /**
     * Retrieve all available controls, using the stateless builder
     * {@link Control.StatelessBuilder} to build each Control, then use the
     * provided consumer to callback to the call originator.
     */
    public abstract void loadAvailableControls(@NonNull Consumer<List<Control>> consumer);

    /**
     * (Optional) The service may be asked to provide a small number of recommended controls, in
     * order to suggest some controls to the user for favoriting. The controls shall be built using
     * the stateless builder {@link Control.StatelessBuilder}, followed by an invocation to the
     * provided consumer to callback to the call originator. If the number of controls
     * is greater than maxNumber, the list will be truncated.
     */
    public void loadSuggestedControls(int maxNumber, @NonNull Consumer<List<Control>> consumer) {
        // Override to change the default behavior
        consumer.accept(Collections.emptyList());
    }

    /**
     * Return a valid Publisher for the given controlIds. This publisher will be asked
     * to provide updates for the given list of controlIds as long as the Subscription
     * is valid.
     */
    @NonNull
    public abstract Publisher<Control> publisherFor(@NonNull List<String> controlIds);

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
            public void load(IControlsLoadCallback cb) {
                mHandler.obtainMessage(RequestHandler.MSG_LOAD, cb).sendToTarget();
            }

            public void loadSuggested(int maxNumber, IControlsLoadCallback cb) {
                LoadMessage msg = new LoadMessage(maxNumber, cb);
                mHandler.obtainMessage(RequestHandler.MSG_LOAD_SUGGESTED, msg).sendToTarget();
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
    public boolean onUnbind(@NonNull Intent intent) {
        mHandler = null;
        return true;
    }

    private class RequestHandler extends Handler {
        private static final int MSG_LOAD = 1;
        private static final int MSG_SUBSCRIBE = 2;
        private static final int MSG_ACTION = 3;
        private static final int MSG_LOAD_SUGGESTED = 4;

        /**
         * This the maximum number of controls that can be loaded via
         * {@link ControlsProviderService#loadAvailablecontrols}. Anything over this number
         * will be truncated.
         */
        private static final int MAX_NUMBER_OF_CONTROLS_ALLOWED = 1000;

        RequestHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_LOAD:
                    final IControlsLoadCallback cb = (IControlsLoadCallback) msg.obj;
                    ControlsProviderService.this.loadAvailableControls(consumerFor(
                            MAX_NUMBER_OF_CONTROLS_ALLOWED, cb));
                    break;

                case MSG_LOAD_SUGGESTED:
                    final LoadMessage lMsg = (LoadMessage) msg.obj;
                    ControlsProviderService.this.loadSuggestedControls(lMsg.mMaxNumber,
                            consumerFor(lMsg.mMaxNumber, lMsg.mCb));
                    break;

                case MSG_SUBSCRIBE:
                    final SubscribeMessage sMsg = (SubscribeMessage) msg.obj;
                    final IControlsSubscriber cs = sMsg.mSubscriber;
                    Subscriber<Control> s = new Subscriber<Control>() {
                            public void onSubscribe(Subscription subscription) {
                                try {
                                    cs.onSubscribe(mToken, new SubscriptionAdapter(subscription));
                                } catch (RemoteException ex) {
                                    ex.rethrowAsRuntimeException();
                                }
                            }
                            public void onNext(@NonNull Control statefulControl) {
                                Preconditions.checkNotNull(statefulControl);
                                try {
                                    cs.onNext(mToken, statefulControl);
                                } catch (RemoteException ex) {
                                    ex.rethrowAsRuntimeException();
                                }
                            }
                            public void onError(Throwable t) {
                                try {
                                    cs.onError(mToken, t.toString());
                                } catch (RemoteException ex) {
                                    ex.rethrowAsRuntimeException();
                                }
                            }
                            public void onComplete() {
                                try {
                                    cs.onComplete(mToken);
                                } catch (RemoteException ex) {
                                    ex.rethrowAsRuntimeException();
                                }
                            }
                        };
                    ControlsProviderService.this.publisherFor(sMsg.mControlIds).subscribe(s);
                    break;

                case MSG_ACTION:
                    final ActionMessage aMsg = (ActionMessage) msg.obj;
                    ControlsProviderService.this.performControlAction(aMsg.mControlId,
                            aMsg.mAction, consumerFor(aMsg.mControlId, aMsg.mCb));
                    break;
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

        private Consumer<List<Control>> consumerFor(int maxNumber, IControlsLoadCallback cb) {
            return (@NonNull List<Control> controls) -> {
                Preconditions.checkNotNull(controls);
                if (controls.size() > maxNumber) {
                    Log.w(TAG, "Too many controls. Provided: " + controls.size() + ", Max allowed: "
                            + maxNumber + ". Truncating the list.");
                    controls = controls.subList(0, maxNumber);
                }

                List<Control> list = new ArrayList<>();
                for (Control control: controls) {
                    if (control == null) {
                        Log.e(TAG, "onLoad: null control.");
                    }
                    if (isStatelessControl(control)) {
                        list.add(control);
                    } else {
                        Log.w(TAG, "onLoad: control is not stateless.");
                        list.add(new Control.StatelessBuilder(control).build());
                    }
                }
                try {
                    cb.accept(mToken, list);
                } catch (RemoteException ex) {
                    ex.rethrowAsRuntimeException();
                }
            };
        }

        private boolean isStatelessControl(Control control) {
            return (control.getStatus() == Control.STATUS_UNKNOWN
                    && control.getControlTemplate().getTemplateType() == ControlTemplate.TYPE_NONE
                    && TextUtils.isEmpty(control.getStatusText()));
        }
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

    private static class LoadMessage {
        final int mMaxNumber;
        final IControlsLoadCallback mCb;

        LoadMessage(int maxNumber, IControlsLoadCallback cb) {
            this.mMaxNumber = maxNumber;
            this.mCb = cb;
        }
    }
}
