/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accessibilityservice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import java.util.Timer;
import java.util.TimerTask;

/**
 * This class text the accessibility framework end to end.
 * <p>
 * Note: Since accessibility is provided by {@link AccessibilityService}s we create one,
 * and it generates an event and an interruption dispatching them through the
 * {@link AccessibilityManager}. We verify the received result. To trigger the test
 * go to Settings->Accessibility and select the enable accessibility check and then
 * select the check for this service (same name as the class).
 */
public class AccessibilityTestService extends AccessibilityService {

    private static final String LOG_TAG = "AccessibilityTestService";

    private static final String CLASS_NAME = "foo.bar.baz.Test";
    private static final String PACKAGE_NAME = "foo.bar.baz";
    private static final String TEXT = "Some stuff";
    private static final String BEFORE_TEXT = "Some other stuff";

    private static final String CONTENT_DESCRIPTION = "Content description";

    private static final int ITEM_COUNT = 10;
    private static final int CURRENT_ITEM_INDEX = 1;
    private static final int INTERRUPT_INVOCATION_TYPE = 0x00000200;

    private static final int FROM_INDEX = 1;
    private static final int ADDED_COUNT = 2;
    private static final int REMOVED_COUNT = 1;

    private static final int NOTIFICATION_TIMEOUT_MILLIS = 80;

    private int mReceivedResult;

    private Timer mTimer = new Timer();

    @Override
    public void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_AUDIBLE;
        info.notificationTimeout = NOTIFICATION_TIMEOUT_MILLIS;
        info.flags &= AccessibilityServiceInfo.DEFAULT;
        setServiceInfo(info);

        // we need to wait until the system picks our configuration
        // otherwise it will not notify us
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    testAccessibilityEventDispatching();
                    testInterrupt();
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error in testing Accessibility feature", e);
                }
            }
        }, 1000);
    }

    /**
     * Check here if the event we received is actually the one we sent.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        assert(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED == event.getEventType());
        assert(event != null);
        assert(event.getEventTime() > 0);
        assert(CLASS_NAME.equals(event.getClassName()));
        assert(PACKAGE_NAME.equals(event.getPackageName()));
        assert(1 == event.getText().size());
        assert(TEXT.equals(event.getText().get(0)));
        assert(BEFORE_TEXT.equals(event.getBeforeText()));
        assert(event.isChecked());
        assert(CONTENT_DESCRIPTION.equals(event.getContentDescription()));
        assert(ITEM_COUNT == event.getItemCount());
        assert(CURRENT_ITEM_INDEX == event.getCurrentItemIndex());
        assert(event.isEnabled());
        assert(event.isPassword());
        assert(FROM_INDEX == event.getFromIndex());
        assert(ADDED_COUNT == event.getAddedCount());
        assert(REMOVED_COUNT == event.getRemovedCount());
        assert(event.getParcelableData() != null);
        assert(1 == ((Notification) event.getParcelableData()).icon);

        // set the type of the receved request
        mReceivedResult = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
    }

    /**
     * Set a flag that we received the interrupt request.
     */
    @Override
    public void onInterrupt() {

        // set the type of the receved request
        mReceivedResult = INTERRUPT_INVOCATION_TYPE;
    }

    /**
     * If an {@link AccessibilityEvent} is sent and received correctly.
     */
   public void testAccessibilityEventDispatching() throws Exception {
       AccessibilityEvent event =
           AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);

       assert(event != null);
       event.setClassName(CLASS_NAME);
       event.setPackageName(PACKAGE_NAME);
       event.getText().add(TEXT);
       event.setBeforeText(BEFORE_TEXT);
       event.setChecked(true);
       event.setContentDescription(CONTENT_DESCRIPTION);
       event.setItemCount(ITEM_COUNT);
       event.setCurrentItemIndex(CURRENT_ITEM_INDEX);
       event.setEnabled(true);
       event.setPassword(true);
       event.setFromIndex(FROM_INDEX);
       event.setAddedCount(ADDED_COUNT);
       event.setRemovedCount(REMOVED_COUNT);
       event.setParcelableData(new Notification(1, "Foo", 1234));

       AccessibilityManager.getInstance(this).sendAccessibilityEvent(event);

       assert(mReceivedResult == event.getEventType());

       Log.i(LOG_TAG, "AccessibilityTestService#testAccessibilityEventDispatching: Success");
   }

   /**
    * If accessibility feedback interruption is triggered and received correctly.
    */
   public void testInterrupt() throws Exception {
       AccessibilityManager.getInstance(this).interrupt();

       assert(INTERRUPT_INVOCATION_TYPE == mReceivedResult);

       Log.i(LOG_TAG, "AccessibilityTestService#testInterrupt: Success");
   }
}

