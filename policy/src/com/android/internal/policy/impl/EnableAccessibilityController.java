/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.internal.policy.impl;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.MathUtils;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class EnableAccessibilityController {

    private static final int SPEAK_WARNING_DELAY_MILLIS = 2000;
    private static final int ENABLE_ACCESSIBILITY_DELAY_MILLIS = 6000;

    public static final int MESSAGE_SPEAK_WARNING = 1;
    public static final int MESSAGE_SPEAK_ENABLE_CANCELED = 2;
    public static final int MESSAGE_ENABLE_ACCESSIBILITY = 3;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MESSAGE_SPEAK_WARNING: {
                    String text = mContext.getString(R.string.continue_to_enable_accessibility);
                    mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                } break;
                case MESSAGE_SPEAK_ENABLE_CANCELED: {
                    String text = mContext.getString(R.string.enable_accessibility_canceled);
                    mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                } break;
                case MESSAGE_ENABLE_ACCESSIBILITY: {
                    enableAccessibility();
                    mTone.play();
                    mTts.speak(mContext.getString(R.string.accessibility_enabled),
                            TextToSpeech.QUEUE_FLUSH, null);
                } break;
            }
        }
    };

    private final IWindowManager mWindowManager = IWindowManager.Stub.asInterface(
            ServiceManager.getService("window"));

    private final IAccessibilityManager mAccessibilityManager = IAccessibilityManager
            .Stub.asInterface(ServiceManager.getService("accessibility"));


    private final Context mContext;
    private final Runnable mOnAccessibilityEnabledCallback;
    private final UserManager mUserManager;
    private final TextToSpeech mTts;
    private final Ringtone mTone;

    private final float mTouchSlop;

    private boolean mDestroyed;
    private boolean mCanceled;

    private float mFirstPointerDownX;
    private float mFirstPointerDownY;
    private float mSecondPointerDownX;
    private float mSecondPointerDownY;

    public EnableAccessibilityController(Context context, Runnable onAccessibilityEnabledCallback) {
        mContext = context;
        mOnAccessibilityEnabledCallback = onAccessibilityEnabledCallback;
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mTts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (mDestroyed) {
                    mTts.shutdown();
                }
            }
        });
        mTone = RingtoneManager.getRingtone(context, Settings.System.DEFAULT_NOTIFICATION_URI);
        mTone.setStreamType(AudioManager.STREAM_MUSIC);
        mTouchSlop = context.getResources().getDimensionPixelSize(
                R.dimen.accessibility_touch_slop);
    }

    public static boolean canEnableAccessibilityViaGesture(Context context) {
        AccessibilityManager accessibilityManager = AccessibilityManager.getInstance(context);
        // Accessibility is enabled and there is an enabled speaking
        // accessibility service, then we have nothing to do.
        if (accessibilityManager.isEnabled()
                && !accessibilityManager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty()) {
            return false;
        }
        // If the global gesture is enabled and there is a speaking service
        // installed we are good to go, otherwise there is nothing to do.
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.ENABLE_ACCESSIBILITY_GLOBAL_GESTURE_ENABLED, 0) == 1
                && !getInstalledSpeakingAccessibilityServices(context).isEmpty();
    }

    private static List<AccessibilityServiceInfo> getInstalledSpeakingAccessibilityServices(
            Context context) {
        List<AccessibilityServiceInfo> services = new ArrayList<AccessibilityServiceInfo>();
        services.addAll(AccessibilityManager.getInstance(context)
                .getInstalledAccessibilityServiceList());
        Iterator<AccessibilityServiceInfo> iterator = services.iterator();
        while (iterator.hasNext()) {
            AccessibilityServiceInfo service = iterator.next();
            if ((service.feedbackType & AccessibilityServiceInfo.FEEDBACK_SPOKEN) == 0) {
                iterator.remove();
            }
        }
        return services;
    }

    public void onDestroy() {
        mDestroyed = true;
    }

    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                && event.getPointerCount() == 2) {
            mFirstPointerDownX = event.getX(0);
            mFirstPointerDownY = event.getY(0);
            mSecondPointerDownX = event.getX(1);
            mSecondPointerDownY = event.getY(1);
            mHandler.sendEmptyMessageDelayed(MESSAGE_SPEAK_WARNING,
                    SPEAK_WARNING_DELAY_MILLIS);
            mHandler.sendEmptyMessageDelayed(MESSAGE_ENABLE_ACCESSIBILITY,
                   ENABLE_ACCESSIBILITY_DELAY_MILLIS);
            return true;
        }
        return false;
    }

    public boolean onTouchEvent(MotionEvent event) {
        final int pointerCount = event.getPointerCount();
        final int action = event.getActionMasked();
        if (mCanceled) {
            if (action == MotionEvent.ACTION_UP) {
                mCanceled = false;
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (pointerCount > 2) {
                    cancel();
                }
            } break;
            case MotionEvent.ACTION_MOVE: {
                final float firstPointerMove = MathUtils.dist(event.getX(0),
                        event.getY(0), mFirstPointerDownX, mFirstPointerDownY);
                if (Math.abs(firstPointerMove) > mTouchSlop) {
                    cancel();
                }
                final float secondPointerMove = MathUtils.dist(event.getX(1),
                        event.getY(1), mSecondPointerDownX, mSecondPointerDownY);
                if (Math.abs(secondPointerMove) > mTouchSlop) {
                    cancel();
                }
            } break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancel();
            } break;
        }
        return true;
    }

    private void cancel() {
        mCanceled = true;
        if (mHandler.hasMessages(MESSAGE_SPEAK_WARNING)) {
            mHandler.removeMessages(MESSAGE_SPEAK_WARNING);
        } else if (mHandler.hasMessages(MESSAGE_ENABLE_ACCESSIBILITY)) {
            mHandler.sendEmptyMessage(MESSAGE_SPEAK_ENABLE_CANCELED);
        }
        mHandler.removeMessages(MESSAGE_ENABLE_ACCESSIBILITY);
    }

    private void enableAccessibility() {
        List<AccessibilityServiceInfo> services = getInstalledSpeakingAccessibilityServices(
                mContext);
        if (services.isEmpty()) {
            return;
        }
        boolean keyguardLocked = false;
        try {
            keyguardLocked = mWindowManager.isKeyguardLocked();
        } catch (RemoteException re) {
            /* ignore */
        }

        final boolean hasMoreThanOneUser = mUserManager.getUsers().size() > 1;

        AccessibilityServiceInfo service = services.get(0);
        boolean enableTouchExploration = (service.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
        // Try to find a service supporting explore by touch.
        if (!enableTouchExploration) {
            final int serviceCount = services.size();
            for (int i = 1; i < serviceCount; i++) {
                AccessibilityServiceInfo candidate = services.get(i);
                if ((candidate.flags & AccessibilityServiceInfo
                        .FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0) {
                    enableTouchExploration = true;
                    service = candidate;
                    break;
                }
            }
        }

        ServiceInfo serviceInfo = service.getResolveInfo().serviceInfo;
        ComponentName componentName = new ComponentName(serviceInfo.packageName, serviceInfo.name);
        if (!keyguardLocked || !hasMoreThanOneUser) {
            final int userId = ActivityManager.getCurrentUser();
            String enabledServiceString = componentName.flattenToString();
            ContentResolver resolver = mContext.getContentResolver();
            // Enable one speaking accessibility service.
            Settings.Secure.putStringForUser(resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    enabledServiceString, userId);
            // Allow the services we just enabled to toggle touch exploration.
            Settings.Secure.putStringForUser(resolver,
                    Settings.Secure.TOUCH_EXPLORATION_GRANTED_ACCESSIBILITY_SERVICES,
                    enabledServiceString, userId);
            // Enable touch exploration.
            if (enableTouchExploration) {
                Settings.Secure.putIntForUser(resolver, Settings.Secure.TOUCH_EXPLORATION_ENABLED,
                        1, userId);
            }
            // Enable accessibility script injection (AndroidVox) for web content.
            Settings.Secure.putIntForUser(resolver, Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION,
                    1, userId);
            // Turn on accessibility mode last.
            Settings.Secure.putIntForUser(resolver, Settings.Secure.ACCESSIBILITY_ENABLED,
                    1, userId);
        } else if (keyguardLocked) {
            try {
                mAccessibilityManager.temporaryEnableAccessibilityStateUntilKeyguardRemoved(
                        componentName, enableTouchExploration);
            } catch (RemoteException re) {
                /* ignore */
            }
        }

        mOnAccessibilityEnabledCallback.run();
    }
}
