/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.app.activity;

import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;

import android.app.ActivityManager;
import android.app.ActivityManager.TaskDescription;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.test.AndroidTestCase;

import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;

import java.util.Iterator;
import java.util.List;

public class ActivityManagerTest extends AndroidTestCase {

    protected Context mContext;
    protected ActivityManager mActivityManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getContext();
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    // TODO should write a test for getRecentTasks()
    // TODO should write a test for getRunningTasks()
    // TODO should write a test for getMemoryInfo()
    
    // TODO: Find a way to re-enable this.  It fails if any other app has failed during startup.
    // This is probably an OK assumption given the desired system status when we run unit tests,
    // but it's not necessarily the right assumption for a unit test.
    @Suppress
    public void disabledTestErrorTasksEmpty() throws Exception {
        
        List<ActivityManager.ProcessErrorStateInfo> errList;
        
        errList = mActivityManager.getProcessesInErrorState();
        
        // test: confirm list is empty
        assertNull(errList);
    }
    
    // TODO: Force an activity into an error state - then see if we can catch it here?
    @SmallTest
    public void testErrorTasksWithError() throws Exception {
        
        List<ActivityManager.ProcessErrorStateInfo> errList;
        
        // TODO force another process into an error condition.  How?
        
        // test: confirm error list length is at least 1 under varying query lengths
//      checkErrorListMax(1,-1);

        errList = mActivityManager.getProcessesInErrorState();

        // test: the list itself is healthy
        checkErrorListSanity(errList);

        // test: confirm our application shows up in the list
    }
    
    // TODO: Force an activity into an ANR state - then see if we can catch it here?
    @SmallTest
    public void testErrorTasksWithANR() throws Exception {
        
        List<ActivityManager.ProcessErrorStateInfo> errList;
        
        // TODO: force an application into an ANR state
        
        errList = mActivityManager.getProcessesInErrorState();

        // test: the list itself is healthy
        checkErrorListSanity(errList);

        // test: confirm our ANR'ing application shows up in the list
    }
    
    @SmallTest
    public void testGetDeviceConfigurationInfo() throws Exception {
        ConfigurationInfo config = mActivityManager.getDeviceConfigurationInfo();
        assertNotNull(config);
        // Validate values against configuration retrieved from resources
        Configuration vconfig = mContext.getResources().getConfiguration();
        assertNotNull(vconfig);
        assertEquals(config.reqKeyboardType, vconfig.keyboard);
        assertEquals(config.reqTouchScreen, vconfig.touchscreen);
        assertEquals(config.reqNavigation, vconfig.navigation);
        if (vconfig.navigation == Configuration.NAVIGATION_NONAV) {
            assertNotNull(config.reqInputFeatures & ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV);
        }
        if (vconfig.keyboard != Configuration.KEYBOARD_UNDEFINED) {
            assertNotNull(config.reqInputFeatures & ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD);
        }    
    }

    @SmallTest
    public void testTaskDescriptionCopyFrom() {
        TaskDescription td1 = new TaskDescription(
                "test label",            // label
                Icon.createWithResource(mContext.getPackageName(), 21), // icon
                0x111111,                // colorPrimary
                0x222222,                // colorBackground
                0x333333,                // statusBarColor
                0x444444,                // navigationBarColor
                true,                    // ensureStatusBarContrastWhenTransparent
                true,                    // ensureNavigationBarContrastWhenTransparent
                RESIZE_MODE_RESIZEABLE,  // resizeMode
                10,                      // minWidth
                20,                      // minHeight
                0                        // colorBackgroundFloating
        );

        TaskDescription td2 = new TaskDescription();
        // Must overwrite all the fields
        td2.copyFrom(td1);

        assertTaskDescriptionEqual(td1, td2, true, true);
    }

    @SmallTest
    public void testTaskDescriptionCopyFromPreserveHiddenFields() {
        TaskDescription td1 = new TaskDescription(
                "test label",              // label
                Icon.createWithResource(mContext.getPackageName(), 21), // icon
                0x111111,                  // colorPrimary
                0x222222,                  // colorBackground
                0x333333,                  // statusBarColor
                0x444444,                  // navigationBarColor
                false,                     // ensureStatusBarContrastWhenTransparent
                false,                     // ensureNavigationBarContrastWhenTransparent
                RESIZE_MODE_UNRESIZEABLE,  // resizeMode
                10,                        // minWidth
                20,                        // minHeight
                0                          // colorBackgroundFloating
        );

        TaskDescription td2 = new TaskDescription(
                "test label2",           // label
                Icon.createWithResource(mContext.getPackageName(), 212), // icon
                0x1111112,               // colorPrimary
                0x2222222,               // colorBackground
                0x3333332,               // statusBarColor
                0x4444442,               // navigationBarColor
                true,                    // ensureStatusBarContrastWhenTransparent
                true,                    // ensureNavigationBarContrastWhenTransparent
                RESIZE_MODE_RESIZEABLE,  // resizeMode
                102,                     // minWidth
                202,                     // minHeight
                0                        // colorBackgroundFloating
        );

        // Must overwrite all public and hidden fields, since other has all fields set.
        td2.copyFromPreserveHiddenFields(td1);

        assertTaskDescriptionEqual(td1, td2, true, true);

        TaskDescription td3 = new TaskDescription();
        // Must overwrite only public fields, and preserve hidden fields.
        td2.copyFromPreserveHiddenFields(td3);

        assertTaskDescriptionEqual(td3, td2, true, false);
        assertTaskDescriptionEqual(td1, td2, false, true);
    }

    @SmallTest
    public void testTaskDescriptionParceling() throws Exception {
        TaskDescription tdBitmapNull = new TaskDescription(
                "test label",              // label
                Icon.createWithResource(mContext.getPackageName(), 21), // icon
                0x111111,                  // colorPrimary
                0x222222,                  // colorBackground
                0x333333,                  // statusBarColor
                0x444444,                  // navigationBarColor
                false,                     // ensureStatusBarContrastWhenTransparent
                false,                     // ensureNavigationBarContrastWhenTransparent
                RESIZE_MODE_UNRESIZEABLE,  // resizeMode
                10,                        // minWidth
                20,                        // minHeight
                0                          // colorBackgroundFloating
        );

        // Normal parceling should keep everything the same.
        TaskDescription tdParcelled = new TaskDescription(parcelingRoundTrip(tdBitmapNull));
        assertTaskDescriptionEqual(tdBitmapNull, tdParcelled, true, true);

        Bitmap recycledBitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        recycledBitmap.recycle();
        assertTrue(recycledBitmap.isRecycled());
        TaskDescription tdBitmapRecycled = new TaskDescription(
                "test label",              // label
                Icon.createWithBitmap(recycledBitmap), // icon
                0x111111,                  // colorPrimary
                0x222222,                  // colorBackground
                0x333333,                  // statusBarColor
                0x444444,                  // navigationBarColor
                false,                     // ensureStatusBarContrastWhenTransparent
                false,                     // ensureNavigationBarContrastWhenTransparent
                RESIZE_MODE_UNRESIZEABLE,  // resizeMode
                10,                        // minWidth
                20,                        // minHeight
                0                          // colorBackgroundFloating
        );
        // Recycled bitmap will be ignored while parceling.
        tdParcelled = new TaskDescription(parcelingRoundTrip(tdBitmapRecycled));
        assertTaskDescriptionEqual(tdBitmapNull, tdParcelled, true, true);

    }

    private void assertTaskDescriptionEqual(TaskDescription td1, TaskDescription td2,
            boolean checkOverwrittenFields, boolean checkPreservedFields) {
        if (checkOverwrittenFields) {
            assertEquals(td1.getLabel(), td2.getLabel());
            assertEquals(td1.getInMemoryIcon(), td2.getInMemoryIcon());
            assertEquals(td1.getIconFilename(), td2.getIconFilename());
            assertEquals(td1.getIconResourcePackage(), td2.getIconResourcePackage());
            assertEquals(td1.getIconResource(), td2.getIconResource());
            assertEquals(td1.getPrimaryColor(), td2.getPrimaryColor());
            assertEquals(td1.getEnsureStatusBarContrastWhenTransparent(),
                    td2.getEnsureStatusBarContrastWhenTransparent());
            assertEquals(td1.getEnsureNavigationBarContrastWhenTransparent(),
                    td2.getEnsureNavigationBarContrastWhenTransparent());
        }
        if (checkPreservedFields) {
            assertEquals(td1.getBackgroundColor(), td2.getBackgroundColor());
            assertEquals(td1.getStatusBarColor(), td2.getStatusBarColor());
            assertEquals(td1.getNavigationBarColor(), td2.getNavigationBarColor());
            assertEquals(td1.getResizeMode(), td2.getResizeMode());
            assertEquals(td1.getMinWidth(), td2.getMinWidth());
            assertEquals(td1.getMinHeight(), td2.getMinHeight());
        }
    }

    private <T extends Parcelable> T parcelingRoundTrip(final T in) throws Exception {
        final Parcel p = Parcel.obtain();
        in.writeToParcel(p, /* flags */ 0);
        p.setDataPosition(0);
        final byte[] marshalledData = p.marshall();
        p.recycle();

        final Parcel q = Parcel.obtain();
        q.unmarshall(marshalledData, 0, marshalledData.length);
        q.setDataPosition(0);

        final Parcelable.Creator<T> creator = (Parcelable.Creator<T>)
                in.getClass().getField("CREATOR").get(null); // static object, so null receiver
        final T unmarshalled = (T) creator.createFromParcel(q);
        q.recycle();
        return unmarshalled;
    }

    // If any entries in appear in the list, validity check them against all running applications
    private void checkErrorListSanity(List<ActivityManager.ProcessErrorStateInfo> errList) {
        if (errList == null) return;
        
        Iterator<ActivityManager.ProcessErrorStateInfo> iter = errList.iterator();
        while (iter.hasNext()) {
            ActivityManager.ProcessErrorStateInfo info = iter.next();
            assertNotNull(info);
            // validity checks
            assertTrue((info.condition == ActivityManager.ProcessErrorStateInfo.CRASHED) ||
                       (info.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING));
            // TODO look at each of these and consider a stronger test
            // TODO can we cross-check at the process name via some other API?
            // TODO is there a better test for strings, e.g. "assertIsLegalString")
            assertNotNull(info.processName);
            // reasonableness test for info.pid ?
            assertNotNull(info.longMsg);
            assertNotNull(info.shortMsg);
            // is there any reasonable test for the crashData?  Probably not. 
        }
    }
}

