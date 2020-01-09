/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.smspush.unitTests;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.sqlite.SQLiteDatabase;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Telephony.Sms.Intents;
import android.test.ServiceTestCase;
import android.util.Log;

import com.android.internal.telephony.IWapPushManager;
import com.android.internal.telephony.WapPushManagerParams;
import com.android.internal.telephony.WspTypeDecoder;
import com.android.internal.telephony.uicc.IccUtils;
import com.android.internal.util.HexDump;
import com.android.smspush.WapPushManager;
import com.android.smspush.WapPushManager.WapPushManDBHelper;

import java.io.File;
import java.util.Random;

/**
 * This is a simple framework for a test of a Service.  See {@link android.test.ServiceTestCase
 * ServiceTestCase} for more information on how to write and extend service tests.
 *
 * To run this test, you can type:
 * adb shell am instrument -w \
 * -e class com.android.smspush.unitTests.WapPushTest \
 * com.android.smspush.unitTests/android.test.InstrumentationTestRunner
 */
public class WapPushTest extends ServiceTestCase<WapPushManager> {
    private static final String LOG_TAG = "WAP PUSH";
    private static final boolean LOCAL_LOGV = false;
    private static final int TIME_WAIT = 100;

    protected int mAppIdValue = 0x8002;
    protected String mAppIdName = "x-wap-application:*";
    protected int mContentTypeValue = 0x030a;
    protected String mContentTypeName = "application/vnd.wap.sic";

    protected String mPackageName;
    protected String mClassName;

    protected byte[] mGsmHeader = {
            (byte) 0x00, // sc address
            (byte) 0x40, // TP-MTI
            (byte) 0x04, // sender address length?
            (byte) 0x81, (byte) 0x55, (byte) 0x45, // sender address?
            (byte) 0x00, // data schema
            (byte) 0x00, // proto ID
            (byte) 0x01, (byte) 0x60, (byte) 0x12, (byte) 0x31,
            (byte) 0x74, (byte) 0x34, (byte) 0x63 // time stamp
    };

    protected byte[] mUserDataHeader = {
            (byte) 0x07, // UDH len
            (byte) 0x06, // header len
            (byte) 0x05, // port addressing type?
            (byte) 0x00, // dummy
            (byte) 0x0B, (byte) 0x84, // dest port
            (byte) 0x23, (byte) 0xF0 // src port
    };

    protected byte[] mWspHeader;

    protected byte[] mMessageBody = {
            (byte) 0x00,
            (byte) 0x01,
            (byte) 0x02,
            (byte) 0x03,
            (byte) 0x04,
            (byte) 0x05,
            (byte) 0x06,
            (byte) 0x07,
            (byte) 0x08,
            (byte) 0x09,
            (byte) 0x0a,
            (byte) 0x0b,
            (byte) 0x0c,
            (byte) 0x0d,
            (byte) 0x0e,
            (byte) 0x0f
    };

    protected int mWspHeaderStart;
    protected int mWspHeaderLen;
    protected int mWspContentTypeStart;

    /**
     * OMA application ID in binary form
     * http://www.openmobilealliance.org/tech/omna/omna-push-app-id.aspx
     */
    final int[] OMA_APPLICATION_ID_VALUES = new int[] {
            0x00,
            0x01,
            0x02,
            0x03,
            0x04,
            0x05,
            0x06,
            0x07,
            0x08,
            0x09,
            0x0A,
            0x8000,
            0x8001,
            0x8002,
            0x8003,
            0x8004,
            0x8005,
            0x8006,
            0x8007,
            0x8008,
            0x8009,
            0x800B,
            0x8010
    };

    /**
     * OMA application ID in string form
     * http://www.openmobilealliance.org/tech/omna/omna-push-app-id.aspx
     */
    final String[] OMA_APPLICATION_ID_NAMES = new String[] {
            "x-wap-application:*",
            "x-wap-application:push.sia",
            "x-wap-application:wml.ua",
            "x-wap-application:wta.ua",
            "x-wap-application:mms.ua",
            "x-wap-application:push.syncml",
            "x-wap-application:loc.ua",
            "x-wap-application:syncml.dm",
            "x-wap-application:drm.ua",
            "x-wap-application:emn.ua",
            "x-wap-application:wv.ua",
            "x-wap-microsoft:localcontent.ua",
            "x-wap-microsoft:IMclient.ua",
            "x-wap-docomo:imode.mail.ua",
            "x-wap-docomo:imode.mr.ua",
            "x-wap-docomo:imode.mf.ua",
            "x-motorola:location.ua",
            "x-motorola:now.ua",
            "x-motorola:otaprov.ua",
            "x-motorola:browser.ua",
            "x-motorola:splash.ua",
            "x-wap-nai:mvsw.command",
            "x-wap-openwave:iota.ua"
    };

    /**
     * OMA content type in binary form
     * http://www.openmobilealliance.org/tech/omna/omna-wsp-content-type.aspx
     */
    final int[] OMA_CONTENT_TYPE_VALUES = new int[] {
            0x00,
            0x01,
            0x02,
            0x03,
            0x04,
            0x05,
            0x06,
            0x07,
            0x08,
            0x09,
            0x0A,
            0x0B,
            0x0C,
            0x0D,
            0x0E,
            0x0F,
            0x10,
            0x11,
            0x12,
            0x13,
            0x14,
            0x15,
            0x16,
            0x17,
            0x18,
            0x19,
            0x1A,
            0x1B,
            0x1C,
            0x1D,
            0x1E,
            0x1F,
            0x20,
            0x21,
            0x22,
            0x23,
            0x24,
            0x25,
            0x26,
            0x27,
            0x28,
            0x29,
            0x2A,
            0x2B,
            0x2C,
            0x2D,
            0x2E,
            0x2F,
            0x30,
            0x31,
            0x32,
            0x33,
            0x34,
            0x35,
            0x36,
            0x37,
            0x38,
            0x39,
            0x3A,
            0x3B,
            0x3C,
            0x3D,
            0x3E,
            0x3F,
            0x40,
            0x41,
            0x42,
            0x43,
            0x44,
            0x45,
            0x46,
            0x47,
            0x48,
            0x49,
            0x4A,
            0x4B,
            0x4C,
            0x4D,
            0x4E,
            0x4F,
            0x50,
            0x51,
            0x52,
            0x53,
            0x54,
//            0x55,
//            0x56,
//            0x57,
//            0x58,
            0x0201,
            0x0202,
            0x0203,
            0x0204,
            0x0205,
            0x0206,
            0x0207,
            0x0208,
            0x0209,
            0x020A,
            0x020B,
            0x020C,
            0x0300,
            0x0301,
            0x0302,
            0x0303,
            0x0304,
            0x0305,
            0x0306,
            0x0307,
            0x0308,
            0x0309,
            0x030A,
            0x030B,
            0x030C,
            0x030D,
            0x030E,
            0x030F,
            0x0310,
            0x0311,
            0x0312,
            0x0313,
            0x0314,
            0x0315,
            0x0316,
            0x0317,
            0x0318,
            0x0319,
            0x031A,
            0x031B
            /*0x031C,
              0x031D*/
    };

    /**
     * OMA content type in string form
     * http://www.openmobilealliance.org/tech/omna/omna-wsp-content-type.aspx
     */
    final String[] OMA_CONTENT_TYPE_NAMES = new String[] {
            "*/*",
            "text/*",
            "text/html",
            "text/plain",
            "text/x-hdml",
            "text/x-ttml",
            "text/x-vCalendar",
            "text/x-vCard",
            "text/vnd.wap.wml",
            "text/vnd.wap.wmlscript",
            "text/vnd.wap.wta-event",
            "multipart/*",
            "multipart/mixed",
            "multipart/form-data",
            "multipart/byterantes",
            "multipart/alternative",
            "application/*",
            "application/java-vm",
            "application/x-www-form-urlencoded",
            "application/x-hdmlc",
            "application/vnd.wap.wmlc",
            "application/vnd.wap.wmlscriptc",
            "application/vnd.wap.wta-eventc",
            "application/vnd.wap.uaprof",
            "application/vnd.wap.wtls-ca-certificate",
            "application/vnd.wap.wtls-user-certificate",
            "application/x-x509-ca-cert",
            "application/x-x509-user-cert",
            "image/*",
            "image/gif",
            "image/jpeg",
            "image/tiff",
            "image/png",
            "image/vnd.wap.wbmp",
            "application/vnd.wap.multipart.*",
            "application/vnd.wap.multipart.mixed",
            "application/vnd.wap.multipart.form-data",
            "application/vnd.wap.multipart.byteranges",
            "application/vnd.wap.multipart.alternative",
            "application/xml",
            "text/xml",
            "application/vnd.wap.wbxml",
            "application/x-x968-cross-cert",
            "application/x-x968-ca-cert",
            "application/x-x968-user-cert",
            "text/vnd.wap.si",
            "application/vnd.wap.sic",
            "text/vnd.wap.sl",
            "application/vnd.wap.slc",
            "text/vnd.wap.co",
            "application/vnd.wap.coc",
            "application/vnd.wap.multipart.related",
            "application/vnd.wap.sia",
            "text/vnd.wap.connectivity-xml",
            "application/vnd.wap.connectivity-wbxml",
            "application/pkcs7-mime",
            "application/vnd.wap.hashed-certificate",
            "application/vnd.wap.signed-certificate",
            "application/vnd.wap.cert-response",
            "application/xhtml+xml",
            "application/wml+xml",
            "text/css",
            "application/vnd.wap.mms-message",
            "application/vnd.wap.rollover-certificate",
            "application/vnd.wap.locc+wbxml",
            "application/vnd.wap.loc+xml",
            "application/vnd.syncml.dm+wbxml",
            "application/vnd.syncml.dm+xml",
            "application/vnd.syncml.notification",
            "application/vnd.wap.xhtml+xml",
            "application/vnd.wv.csp.cir",
            "application/vnd.oma.dd+xml",
            "application/vnd.oma.drm.message",
            "application/vnd.oma.drm.content",
            "application/vnd.oma.drm.rights+xml",
            "application/vnd.oma.drm.rights+wbxml",
            "application/vnd.wv.csp+xml",
            "application/vnd.wv.csp+wbxml",
            "application/vnd.syncml.ds.notification",
            "audio/*",
            "video/*",
            "application/vnd.oma.dd2+xml",
            "application/mikey",
            "application/vnd.oma.dcd",
            "application/vnd.oma.dcdc",
//            "text/x-vMessage",
//            "application/vnd.omads-email+wbxml",
//            "text/x-vBookmark",
//            "application/vnd.syncml.dm.notification",
            "application/vnd.uplanet.cacheop-wbxml",
            "application/vnd.uplanet.signal",
            "application/vnd.uplanet.alert-wbxml",
            "application/vnd.uplanet.list-wbxml",
            "application/vnd.uplanet.listcmd-wbxml",
            "application/vnd.uplanet.channel-wbxml",
            "application/vnd.uplanet.provisioning-status-uri",
            "x-wap.multipart/vnd.uplanet.header-set",
            "application/vnd.uplanet.bearer-choice-wbxml",
            "application/vnd.phonecom.mmc-wbxml",
            "application/vnd.nokia.syncset+wbxml",
            "image/x-up-wpng",
            "application/iota.mmc-wbxml",
            "application/iota.mmc-xml",
            "application/vnd.syncml+xml",
            "application/vnd.syncml+wbxml",
            "text/vnd.wap.emn+xml",
            "text/calendar",
            "application/vnd.omads-email+xml",
            "application/vnd.omads-file+xml",
            "application/vnd.omads-folder+xml",
            "text/directory;profile=vCard",
            "application/vnd.wap.emn+wbxml",
            "application/vnd.nokia.ipdc-purchase-response",
            "application/vnd.motorola.screen3+xml",
            "application/vnd.motorola.screen3+gzip",
            "application/vnd.cmcc.setting+wbxml",
            "application/vnd.cmcc.bombing+wbxml",
            "application/vnd.docomo.pf",
            "application/vnd.docomo.ub",
            "application/vnd.omaloc-supl-init",
            "application/vnd.oma.group-usage-list+xml",
            "application/oma-directory+xml",
            "application/vnd.docomo.pf2",
            "application/vnd.oma.drm.roap-trigger+wbxml",
            "application/vnd.sbm.mid2",
            "application/vnd.wmf.bootstrap",
            "application/vnc.cmcc.dcd+xml",
            "application/vnd.sbm.cid",
            "application/vnd.oma.bcast.provisioningtrigger",
            /*"application/vnd.docomo.dm",
              "application/vnd.oma.scidm.messages+xml"*/
    };

    private IDataVerify mIVerify = null;

    ServiceConnection mConn = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.v(LOG_TAG, "data verify interface connected.");
                mIVerify = IDataVerify.Stub.asInterface(service);
            }
            public void onServiceDisconnected(ComponentName name) {
            }
        };

    /**
     * Main WapPushManager test module constructor
     */
    public WapPushTest() {
        super(WapPushManager.class);
        mClassName = this.getClass().getName();
        mPackageName = this.getClass().getPackage().getName();
    }

    /**
     * Initialize the verifier
     */
    @Override
    public void setUp() {
        try {
            super.setUp();
            // get verifier
            Intent intent = new Intent(IDataVerify.class.getName());
            intent.setPackage("com.android.smspush.unitTests");
            getContext().bindService(intent, mConn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.w(LOG_TAG, "super exception");
        }
        // Log.d(LOG_TAG, "test setup");
    }

    private IWapPushManager mWapPush = null;
    IWapPushManager getInterface() {
        if (mWapPush != null) return mWapPush;
        Intent startIntent = new Intent();
        startIntent.setClass(getContext(), WapPushManager.class);
        IBinder service = bindService(startIntent);

        mWapPush = IWapPushManager.Stub.asInterface(service);
        return mWapPush;
    }

    /*
     * All methods need to start with 'test'.
     * Use various assert methods to pass/fail the test case.
     */
    protected void utAddPackage(boolean need_sig, boolean more_proc) {
        IWapPushManager iwapman = getInterface();

        // insert new data
        try {
            assertTrue(iwapman.addPackage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_SERVICE, need_sig, more_proc));
        } catch (RemoteException e) {
            assertTrue(false);
        }

        // verify the data
        WapPushManager wpman = getService();
        assertTrue(wpman.verifyData(Integer.toString(mAppIdValue),
                Integer.toString(mContentTypeValue),
                mPackageName, mClassName,
                WapPushManagerParams.APP_TYPE_SERVICE, need_sig, more_proc));
    }

    /**
     * Add package test
     */
    public void testAddPackage1() {
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;

        utAddPackage(true, true);
        mAppIdValue += 10;
        utAddPackage(true, false);
        mContentTypeValue += 20;
        utAddPackage(false, true);
        mContentTypeValue += 20;
        utAddPackage(false, false);

        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;

        // clean up data
        try {
            IWapPushManager iwapman = getInterface();
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            mAppIdValue += 10;
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            mContentTypeValue += 20;
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            mContentTypeValue += 20;
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
        } catch (RemoteException e) {
            assertTrue(false);
        }
        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
    }

    /**
     * Sqlite injection test
     */
    public void testSqliteInjection() {
        String inject = "' union select 0,'com.android.settings','com.android.settings.Settings',0,0,0--";

        // update data
        IWapPushManager iwapman = getInterface();
        try {
            assertFalse(iwapman.updatePackage(
                    inject,
                    Integer.toString(mContentTypeValue),
                    mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_SERVICE, true, true));
        } catch (RemoteException e) {
            assertTrue(false);
        }
    }

    /**
     * Add duprecated package test.
     */
    public void testAddPackage2() {
        try {
            IWapPushManager iwapman = getInterface();

            // set up data
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName, 0,
                    false, false);
            iwapman.addPackage(Integer.toString(mAppIdValue + 10),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName, 0,
                    false, false);
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue + 10), mPackageName, mClassName, 0,
                    false, false);

            assertFalse(iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName, 0,
                    false, false));
            assertFalse(iwapman.addPackage(Integer.toString(mAppIdValue + 10),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName, 0,
                    false, false));
            assertFalse(iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue + 10), mPackageName, mClassName, 0,
                    false, false));

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            iwapman.deletePackage(Integer.toString(mAppIdValue + 10),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue + 10), mPackageName, mClassName);
        } catch (RemoteException e) {
            assertTrue(false);
        }
    }

    protected void utUpdatePackage(boolean need_sig, boolean more_proc) {
        IWapPushManager iwapman = getInterface();

        // insert new data
        try {
            assertTrue(iwapman.updatePackage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_SERVICE, need_sig, more_proc));
        } catch (RemoteException e) {
            assertTrue(false);
        }

        // verify the data
        WapPushManager wpman = getService();
        assertTrue(wpman.verifyData(
                Integer.toString(mAppIdValue),
                Integer.toString(mContentTypeValue),
                mPackageName, mClassName,
                WapPushManagerParams.APP_TYPE_SERVICE, need_sig, more_proc));
    }

    /**
     * Updating package test
     */
    public void testUpdatePackage1() {
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;

        // set up data
        try {
            IWapPushManager iwapman = getInterface();

            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
            mAppIdValue += 10;
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
            mContentTypeValue += 20;
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
            mContentTypeValue += 20;
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
        } catch (RemoteException e) {
            assertTrue(false);
        }

        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
        utUpdatePackage(false, false);
        mAppIdValue += 10;
        utUpdatePackage(false, true);
        mContentTypeValue += 20;
        utUpdatePackage(true, false);
        mContentTypeValue += 20;
        utUpdatePackage(true, true);

        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;

        // clean up data
        try {
            IWapPushManager iwapman = getInterface();

            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            mAppIdValue += 10;
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            mContentTypeValue += 20;
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            mContentTypeValue += 20;
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
        } catch (RemoteException e) {
            assertTrue(false);
        }
        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
    }

    /**
     * Updating invalid package test
     */
    public void testUpdatePackage2() {
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;

        try {
            // set up data
            IWapPushManager iwapman = getInterface();

            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
            assertFalse(iwapman.updatePackage(
                    Integer.toString(mAppIdValue + 10),
                    Integer.toString(mContentTypeValue),
                    mPackageName, mClassName, 0, false, false));
            assertFalse(iwapman.updatePackage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue + 10),
                    mPackageName, mClassName, 0, false, false));
            assertTrue(iwapman.updatePackage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    mPackageName + "dummy_data", mClassName, 0, false, false));
            assertTrue(iwapman.updatePackage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    mPackageName, mClassName + "dummy_data", 0, false, false));
            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName,
                    mClassName + "dummy_data");
        } catch (RemoteException e) {
            assertTrue(false);
        }
    }

    protected void utDeletePackage() {
        IWapPushManager iwapman = getInterface();

        try {
            assertTrue(iwapman.deletePackage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    mPackageName, mClassName));
        } catch (RemoteException e) {
            assertTrue(false);
        }

        // verify the data
        WapPushManager wpman = getService();
        assertTrue(!wpman.isDataExist(
                Integer.toString(mAppIdValue),
                Integer.toString(mContentTypeValue),
                mPackageName, mClassName));
    }

    /**
     * Deleting package test
     */
    public void testDeletePackage1() {
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;

        // set up data
        try {
            IWapPushManager iwapman = getInterface();

            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
            mAppIdValue += 10;
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
            mContentTypeValue += 20;
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
            mContentTypeValue += 20;
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);
        } catch (RemoteException e) {
            assertTrue(false);
        }

        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
        utDeletePackage();
        mAppIdValue += 10;
        utDeletePackage();
        mContentTypeValue += 20;
        utDeletePackage();
        mContentTypeValue += 20;
        utDeletePackage();

        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
    }

    /**
     * Deleting invalid package test
     */
    public void testDeletePackage2() {
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;

        try {
            // set up data
            IWapPushManager iwapman = getInterface();

            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    0, false, false);

            assertFalse(iwapman.deletePackage(Integer.toString(mAppIdValue + 10),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName));
            assertFalse(iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue + 20), mPackageName, mClassName));
            assertFalse(iwapman.deletePackage(Integer.toString(mAppIdValue + 10),
                    Integer.toString(mContentTypeValue + 20), mPackageName, mClassName));

            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

        } catch (RemoteException e) {
            assertTrue(false);
        }
    }


    protected int encodeUint32(int uint32Val, byte[] arr, int start) {
        int bit = 1;
        int topbit = 0;
        int encodeLen;
        int tmpVal;

        assertTrue(uint32Val >= 0);
        for (int i = 0; i < 31; i++) {
            if ((bit & uint32Val) > 0) topbit = i;
            bit = (bit << 1);
        }
        encodeLen = topbit/7 + 1;
        if (arr == null) return encodeLen;

        //Log.d(LOG_TAG, "uint32Val = " + Integer.toHexString(uint32Val) + ", topbit = "
        //      + topbit + ", encodeLen = " + encodeLen);

        tmpVal = uint32Val;
        for (int i = encodeLen - 1; i >= 0; i--) {
            long val = 0;
            if (i < encodeLen - 1) val = 0x80;
            val |= tmpVal & 0x7f;
            arr[start + i] = (byte) (val & 0xFF);
            tmpVal = (tmpVal >> 7);
        }
        return encodeLen;
    }

    protected int encodeShortInt(int sintVal, byte[] arr, int start) {
        int encodeLen = 0;

        if (sintVal >= 0x80) return encodeLen;
        encodeLen = 1;
        arr[start] = (byte) (sintVal | 0x80);
        return encodeLen;
    }


    /**
     * Generate Random WSP header with integer application ID
     */
    protected void createRandomWspHeader(byte[] arr, Random rd, int headerStart,
            boolean noAppId) {

        boolean appIdAdded = false;

        Log.d(LOG_TAG, "headerStart = " + headerStart + ", appId = " + mAppIdValue
                + "(" + Integer.toHexString(mAppIdValue) + ")");
        Log.d(LOG_TAG, "random arr length:" + arr.length);
        String typename[] = new String[] { "short int", "long int", "string", "uint32"};

        while (!appIdAdded) {
            int type;
            int index = headerStart;
            int len = arr.length;
            int i;
            boolean addAppid = false;
            int tmpVal = 0;
            int tmpVal2 = 0;

            while (true) {
                int add;

                /*
                 * field name
                 * 0: short int
                 * 1: long int
                 * 2: text
                 * (no uint for param value)
                 */
                type = rd.nextInt(3);
                switch (type) {
                case 0: // header short integer
                    if (index > 100 && !appIdAdded) addAppid = true;
                    add = 1;
                    break;
                case 1: // header long int
                    add = 1 + rd.nextInt(29);
                    break;
                default: // header string
                    add = 2 + rd.nextInt(10);
                    break;
                }
                if (index + add >= len) break;

                // fill header name
                switch (type) {
                case 0: // header short integer
                    if (!addAppid) {
                        do {
                            arr[index] = (byte) (0x80 | rd.nextInt(128));
                        } while (arr[index] == (byte) 0xaf);
                    } else {
                        Log.d(LOG_TAG, "appId added.");
                        arr[index] = (byte) 0xaf;
                        // if noAppId case, appId fld must be decieved.
                        if (noAppId) arr[index]++;
                    }
                    break;
                case 1: // header long int
                    arr[index] = (byte) (add - 1);
                    tmpVal2 = 0;
                    for (i = 1; i < add; i++) {
                        tmpVal = rd.nextInt(255);
                        tmpVal2 = (tmpVal2 << 8) | tmpVal;
                        arr[index + i] = (byte) tmpVal;
                    }
                    // don't set application id
                    if (tmpVal2 == 0x2f) arr[index + 1]++;
                    break;
                default: // header string
                    for (i = 0; i < add - 1; i++) {
                        tmpVal = rd.nextInt(127);
                        if (tmpVal < 32) tmpVal= (32 + tmpVal);
                        arr[index + i] = (byte) tmpVal;
                    }
                    arr[index + i] = (byte) 0x0;
                    break;
                }

                if (LOCAL_LOGV) {
                    Log.d(LOG_TAG, "field name index:" + index);
                    Log.d(LOG_TAG, "type:" + typename[type] + ", add:" + add);
                    if (type != 2) {
                        for (i = index; i< index + add; i++) {
                            System.out.print(Integer.toHexString(0xff & arr[i]));
                            System.out.print(' ');
                        }
                    } else {
                        System.out.print(Integer.toHexString(0xff & arr[index]));
                        System.out.print(' ');
                        String str = new String(arr, index + 1, add - 2);
                        for (i = 0; i < str.length(); i++) {
                            System.out.print(str.charAt(i));
                            System.out.print(' ');
                        }
                    }
                    System.out.print('\n');
                }
                index += add;


                /*
                 * field value
                 * 0: short int
                 * 1: long int
                 * 2: text
                 * 3: uint
                 */
                if (addAppid) {
                    type = 1;
                } else {
                    type = rd.nextInt(4);
                }
                switch (type) {
                case 0: // header short integer
                    add = 1;
                    break;
                case 1: // header long int
                    if (addAppid) {
                        int bit = 1;
                        int topBit = 0;

                        for (i = 0; i < 31; i++) {
                            if ((mAppIdValue & bit) > 0) topBit = i;
                            bit = (bit << 1);
                        }
                        add = 2 + topBit/8;
                    } else {
                        add = 1 + rd.nextInt(29);
                    }
                    break;
                case 2: // header string
                    add = 2 + rd.nextInt(10);
                    break;
                default: // uint32
                    add = 6;
                }
                if (index + add >= len) break;

                // fill field value
                switch (type) {
                case 0: // header short int
                    arr[index] = (byte) (0x80 | rd.nextInt(128));
                    break;
                case 1: // header long int
                    if (addAppid) {
                        addAppid = false;
                        appIdAdded = true;

                        arr[index] = (byte) (add - 1);
                        tmpVal = mAppIdValue;
                        for (i = add; i > 1; i--) {
                            arr[index + i - 1] = (byte) (tmpVal & 0xff);
                            tmpVal = (tmpVal >> 8);
                        }
                    } else {
                        arr[index] = (byte) (add - 1);
                        for (i = 1; i < add; i++) {
                            arr[index + i] = (byte) rd.nextInt(255);
                        }
                    }
                    break;
                case 2:// header string
                    for (i = 0; i < add - 1; i++) {
                        tmpVal = rd.nextInt(127);
                        if (tmpVal < 32) tmpVal= (32 + tmpVal);
                        arr[index + i] = (byte) tmpVal;
                    }
                    arr[index + i] = (byte) 0x0;
                    break;
                default: // header uvarint
                    arr[index] = (byte) 31;
                    tmpVal = rd.nextInt(0x0FFFFFFF);
                    add = 1 + encodeUint32(tmpVal, null, index + 1);
                    encodeUint32(tmpVal, arr, index + 1);
                    break;

                }

                if (LOCAL_LOGV) {
                    Log.d(LOG_TAG, "field value index:" + index);
                    Log.d(LOG_TAG, "type:" + typename[type] + ", add:" + add);
                    if (type != 2) {
                        for (i = index; i< index + add; i++) {
                            System.out.print(Integer.toHexString(0xff & arr[i]));
                            System.out.print(' ');
                        }
                    } else {
                        System.out.print(Integer.toHexString(0xff & arr[index]));
                        System.out.print(' ');
                        String str = new String(arr, index + 1, add - 2);
                        for (i = 0; i < str.length(); i++) {
                            System.out.print(str.charAt(i));
                            System.out.print(' ');
                        }
                    }
                    System.out.print('\n');
                }
                index += add;
            }
            if (noAppId) break;
        }

        Log.d(LOG_TAG, HexDump.dumpHexString(arr));
    }

    /**
     * Generate Random WSP header with string application ID
     */
    protected void createRandomWspHeaderStrAppId(byte[] arr, Random rd, int headerStart,
            boolean randomStr) {

        boolean appIdAdded = false;

        Log.d(LOG_TAG, "random arr length:" + arr.length);
        String typename[] = new String[] { "short int", "long int", "string", "uint32"};

        while (!appIdAdded) {
            int type;
            int index = headerStart;
            int len = arr.length;
            int i;
            boolean addAppid = false;
            int tmpVal = 0;
            int tmpVal2 = 0;

            while (true) {
                int add;

                /*
                 * field name
                 * 0: short int
                 * 1: long int
                 * 2: text
                 * (no uint for param value)
                 */
                type = rd.nextInt(3);
                switch (type) {
                case 0: // header short integer
                    if (index > 100 && !appIdAdded) addAppid = true;
                    add = 1;
                    break;
                case 1: // header long int
                    add = 1 + rd.nextInt(29);
                    break;
                default: // header string
                    add = 2 + rd.nextInt(10);
                    break;
                }
                if (index + add >= len) break;

                // fill header name
                switch (type) {
                case 0: // header short integer
                    if (!addAppid) {
                        do {
                            arr[index] = (byte) (0x80 | rd.nextInt(128));
                        } while (arr[index] == (byte) 0xaf);
                    } else {
                        Log.d(LOG_TAG, "appId added.");
                        arr[index] = (byte) 0xaf;
                    }
                    break;
                case 1: // header long int
                    arr[index] = (byte) (add - 1);
                    tmpVal2 = 0;
                    for (i = 1; i < add; i++) {
                        tmpVal = rd.nextInt(255);
                        tmpVal2 = (tmpVal2 << 8) | tmpVal;
                        arr[index + i] = (byte) tmpVal;
                    }
                    // don't set application id
                    if (tmpVal2 == 0x2f) arr[index + 1]++;
                    break;
                default: // header string
                    for (i = 0; i < add - 1; i++) {
                        tmpVal = rd.nextInt(127);
                        if (tmpVal < 32) tmpVal= (32 + tmpVal);
                        arr[index + i] = (byte) tmpVal;
                    }
                    arr[index + i] = (byte) 0x0;
                    break;
                }

                if (LOCAL_LOGV) {
                    Log.d(LOG_TAG, "field name index:" + index);
                    Log.d(LOG_TAG, "type:" + typename[type] + ", add:" + add);
                    if (type != 2) {
                        for (i = index; i < index + add; i++) {
                            System.out.print(Integer.toHexString(0xff & arr[i]));
                            System.out.print(' ');
                        }
                    } else {
                        System.out.print(Integer.toHexString(0xff & arr[index]));
                        System.out.print(' ');
                        String str = new String(arr, index + 1, add - 2);
                        for (i = 0; i < str.length(); i++) {
                            System.out.print(str.charAt(i));
                            System.out.print(' ');
                        }
                    }
                    System.out.print('\n');
                }
                index += add;


                /*
                 * field value
                 * 0: short int
                 * 1: long int
                 * 2: text
                 * 3: uint
                 */
                if (addAppid) {
                    type = 2;
                } else {
                    type = rd.nextInt(4);
                }
                switch (type) {
                case 0: // header short integer
                    add = 1;
                    break;
                case 1: // header long int
                    add = 1 + rd.nextInt(29);
                    break;
                case 2: // header string
                    if (addAppid) {
                        if (randomStr) {
                            add = 1 + rd.nextInt(10);
                            byte[] randStr= new byte[add];
                            for (i = 0; i < add; i++) {
                                tmpVal = rd.nextInt(127);
                                if (tmpVal < 32) tmpVal= (32 + tmpVal);
                                randStr[i] = (byte) tmpVal;
                            }
                            mAppIdName = new String(randStr);
                        }
                        add = mAppIdName.length() + 1;
                    } else {
                        add = 2 + rd.nextInt(10);
                    }
                    break;
                default: // uint32
                    add = 6;
                }
                if (index + add >= len) break;

                // fill field value
                switch (type) {
                case 0: // header short int
                    arr[index] = (byte) (0x80 | rd.nextInt(128));
                    break;
                case 1: // header long int
                    arr[index] = (byte) (add - 1);
                    for (i = 1; i < add; i++)
                        arr[index + i] = (byte) rd.nextInt(255);
                    break;
                case 2:// header string
                    if (addAppid) {
                        addAppid = false;
                        appIdAdded = true;
                        for (i = 0; i < add - 1; i++) {
                            arr[index + i] = (byte) (mAppIdName.charAt(i));
                        }
                        Log.d(LOG_TAG, "mAppIdName added [" + mAppIdName + "]");
                    } else {
                        for (i = 0; i < add - 1; i++) {
                            tmpVal = rd.nextInt(127);
                            if (tmpVal < 32) tmpVal= (32 + tmpVal);
                            arr[index + i] = (byte) tmpVal;
                        }
                    }
                    arr[index + i] = (byte) 0x0;
                    break;
                default: // header uvarint
                    arr[index] = (byte) 31;
                    tmpVal = rd.nextInt(0x0FFFFFFF);
                    add = 1 + encodeUint32(tmpVal, null, index + 1);
                    encodeUint32(tmpVal, arr, index + 1);
                    break;

                }

                if (LOCAL_LOGV) {
                    Log.d(LOG_TAG, "field value index:" + index);
                    Log.d(LOG_TAG, "type:" + typename[type] + ", add:" + add);
                    if (type != 2) {
                        for (i = index; i < index + add; i++) {
                            System.out.print(Integer.toHexString(0xff & arr[i]));
                            System.out.print(' ');
                        }
                    } else {
                        System.out.print(Integer.toHexString(0xff & arr[index]));
                        System.out.print(' ');
                        String str = new String(arr, index + 1, add - 2);
                        for (i = 0; i < str.length(); i++) {
                            System.out.print(str.charAt(i));
                            System.out.print(' ');
                        }
                    }
                    System.out.print('\n');
                }
                index += add;
            }
        }

        Log.d(LOG_TAG, "headerStart = " + headerStart + ", mAppIdName = " + mAppIdName);
        Log.d(LOG_TAG, HexDump.dumpHexString(arr));
    }

    protected byte[] createPDU(int testNum) {
        byte[] array = null;
        // byte[] wsp = null;

        switch (testNum) {
            // sample pdu
        case 1:
            byte[] array1 = {
                    (byte) 0x00, // TID
                    (byte) 0x06, // Type = wap push
                    (byte) 0x00, // Length to be set later.

                    // Content-Type
                    (byte) 0x03, (byte) 0x02,
                    (byte) ((mContentTypeValue >> 8) & 0xff),
                    (byte) (mContentTypeValue & 0xff),

                    // Application-id
                    (byte) 0xaf, (byte) 0x02,
                    (byte) ((mAppIdValue >> 8) & 0xff),
                    (byte) (mAppIdValue& 0xff)
            };
            array1[2] = (byte) (array1.length - 3);
            mWspHeader = array1;
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length + 7;
            mWspHeaderLen = array1.length;
            break;

            // invalid wsp header
        case 2:
            byte[] array2 = {
                    (byte) 0x00, // invalid data
            };
            mWspHeader = array2;
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length;
            mWspHeaderLen = array2.length;
            break;

            // random wsp header
        case 3:
            Random rd = new Random();
            int arrSize = 150 + rd.nextInt(100);
            byte[] array3 = new byte[arrSize];
            int hdrEncodeLen;

            array3[0] = (byte) 0x0;
            array3[1] = (byte) 0x6;
            hdrEncodeLen = encodeUint32(array3.length, null, 2);
            hdrEncodeLen = encodeUint32(array3.length - hdrEncodeLen - 2, array3, 2);
            array3[hdrEncodeLen + 2] = (byte) 0x3;
            array3[hdrEncodeLen + 3] = (byte) 0x2;
            array3[hdrEncodeLen + 4] = (byte) ((mContentTypeValue >> 8) & 0xff);
            array3[hdrEncodeLen + 5] = (byte) (mContentTypeValue & 0xff);
            createRandomWspHeader(array3, rd, hdrEncodeLen + 6, false);
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length + hdrEncodeLen + 6;
            mWspHeaderLen = array3.length;

            Log.d(LOG_TAG, "mContentTypeValue = " + mContentTypeValue
                    + "(" + Integer.toHexString(mContentTypeValue) + ")");

            mWspHeader = array3;
            break;

            // random wsp header w/o appid
        case 4:
            rd = new Random();
            arrSize = 150 + rd.nextInt(100);
            array3 = new byte[arrSize];

            array3[0] = (byte) 0x0;
            array3[1] = (byte) 0x6;
            hdrEncodeLen = encodeUint32(array3.length, null, 2);
            hdrEncodeLen = encodeUint32(array3.length - hdrEncodeLen - 2, array3, 2);
            array3[hdrEncodeLen + 2] = (byte) 0x3;
            array3[hdrEncodeLen + 3] = (byte) 0x2;
            array3[hdrEncodeLen + 4] = (byte) ((mContentTypeValue >> 8) & 0xff);
            array3[hdrEncodeLen + 5] = (byte) (mContentTypeValue & 0xff);
            createRandomWspHeader(array3, rd, hdrEncodeLen + 6, true);
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length + hdrEncodeLen + 6;
            mWspHeaderLen = array3.length;

            Log.d(LOG_TAG, "mContentTypeValue = " + mContentTypeValue
                    + "(" + Integer.toHexString(mContentTypeValue) + ")");

            mWspHeader = array3;
            break;

            // random wsp header w/ random appid string
        case 5:
            rd = new Random();
            arrSize = 150 + rd.nextInt(100);
            array3 = new byte[arrSize];

            array3[0] = (byte) 0x0;
            array3[1] = (byte) 0x6;
            hdrEncodeLen = encodeUint32(array3.length, null, 2);
            hdrEncodeLen = encodeUint32(array3.length - hdrEncodeLen - 2, array3, 2);
            array3[hdrEncodeLen + 2] = (byte) 0x3;
            array3[hdrEncodeLen + 3] = (byte) 0x2;
            array3[hdrEncodeLen + 4] = (byte) ((mContentTypeValue >> 8) & 0xff);
            array3[hdrEncodeLen + 5] = (byte) (mContentTypeValue & 0xff);
            createRandomWspHeaderStrAppId(array3, rd, hdrEncodeLen + 6, true);
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length + hdrEncodeLen + 6;
            mWspHeaderLen = array3.length;

            Log.d(LOG_TAG, "mContentTypeValue = " + mContentTypeValue
                    + "(" + Integer.toHexString(mContentTypeValue) + ")");

            mWspHeader = array3;
            break;

            // random wsp header w/ OMA appid string
        case 6:
            rd = new Random();
            arrSize = 150 + rd.nextInt(100);
            array3 = new byte[arrSize];

            array3[0] = (byte) 0x0;
            array3[1] = (byte) 0x6;
            hdrEncodeLen = encodeUint32(array3.length, null, 2);
            hdrEncodeLen = encodeUint32(array3.length - hdrEncodeLen - 2, array3, 2);
            array3[hdrEncodeLen + 2] = (byte) 0x3;
            array3[hdrEncodeLen + 3] = (byte) 0x2;
            array3[hdrEncodeLen + 4] = (byte) ((mContentTypeValue >> 8) & 0xff);
            array3[hdrEncodeLen + 5] = (byte) (mContentTypeValue & 0xff);
            createRandomWspHeaderStrAppId(array3, rd, hdrEncodeLen + 6, false);
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length + hdrEncodeLen + 6;
            mWspHeaderLen = array3.length;

            Log.d(LOG_TAG, "mContentTypeValue = " + mContentTypeValue
                    + "(" + Integer.toHexString(mContentTypeValue) + ")");

            mWspHeader = array3;
            break;

            // random wsp header w/ OMA content type
        case 7:
            rd = new Random();
            arrSize = 150 + rd.nextInt(100);
            array3 = new byte[arrSize];

            array3[0] = (byte) 0x0;
            array3[1] = (byte) 0x6;
            hdrEncodeLen = encodeUint32(array3.length, null, 2);
            hdrEncodeLen = encodeUint32(array3.length - hdrEncodeLen - 2, array3, 2);

            // encode content type
            int contentLen = mContentTypeName.length();
            int next = 2 + hdrEncodeLen;
            mWspContentTypeStart = mGsmHeader.length + mUserDataHeader.length + next;
            // next += encodeUint32(contentLen, array3, next);
            int i;
            Log.d(LOG_TAG, "mContentTypeName = " + mContentTypeName
                    + ", contentLen = " + contentLen);

            for (i = 0; i < contentLen; i++) {
                array3[next + i] = (byte) mContentTypeName.charAt(i);
            }
            array3[next + i] = (byte) 0x0;

            createRandomWspHeader(array3, rd, next + contentLen + 1, false);
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length
                    + next + contentLen + 1;
            mWspHeaderLen = array3.length;

            mWspHeader = array3;
            break;

            // random wsp header w/ OMA content type, OMA app ID
        case 8:
            rd = new Random();
            arrSize = 150 + rd.nextInt(100);
            array3 = new byte[arrSize];

            array3[0] = (byte) 0x0;
            array3[1] = (byte) 0x6;
            hdrEncodeLen = encodeUint32(array3.length, null, 2);
            hdrEncodeLen = encodeUint32(array3.length - hdrEncodeLen - 2, array3, 2);

            // encode content type
            contentLen = mContentTypeName.length();
            next = 2 + hdrEncodeLen;
            mWspContentTypeStart = mGsmHeader.length + mUserDataHeader.length + next;
            // next += encodeUint32(contentLen, array3, next);
            Log.d(LOG_TAG, "mContentTypeName = " + mContentTypeName
                    + ", contentLen = " + contentLen);

            for (i = 0; i < contentLen; i++) {
                array3[next + i] = (byte) mContentTypeName.charAt(i);
            }
            array3[next + i] = (byte) 0x0;

            createRandomWspHeaderStrAppId(array3, rd, next + contentLen + 1, false);
            mWspHeaderStart = mGsmHeader.length + mUserDataHeader.length
                    + next + contentLen + 1;
            mWspHeaderLen = array3.length;

            mWspHeader = array3;
            break;

        default:
            return null;
        }
        array = new byte[mGsmHeader.length + mUserDataHeader.length + mWspHeader.length
                + mMessageBody.length];
        System.arraycopy(mGsmHeader, 0, array, 0, mGsmHeader.length);
        System.arraycopy(mUserDataHeader, 0, array,
                mGsmHeader.length, mUserDataHeader.length);
        System.arraycopy(mWspHeader, 0, array,
                mGsmHeader.length + mUserDataHeader.length, mWspHeader.length);
        System.arraycopy(mMessageBody, 0, array,
                mGsmHeader.length + mUserDataHeader.length + mWspHeader.length,
                mMessageBody.length);
        return array;

    }

    Intent createIntent(int pduType, int tranId) {
        Intent intent = new Intent();
        intent.putExtra("transactionId", tranId);
        intent.putExtra("pduType", pduType);
        intent.putExtra("header", mGsmHeader);
        intent.putExtra("data", mMessageBody);
        // intent.putExtra("contentTypeParameters", null);
        return intent;
    }

    /**
     * Message processing test, start activity
     */
    public void testProcessMsg1() {
        byte[] pdu = createPDU(1);
        int headerLen = pdu.length -
                (mGsmHeader.length + mUserDataHeader.length + mMessageBody.length);
        int pduType = 6;
        int tranId = 0;
        String originalPackageName = mPackageName;
        String originalClassName = mClassName;

        try {

            mClassName = "com.android.smspush.unitTests.ReceiverActivity";

            // set up data
            IWapPushManager iwapman = getInterface();
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_ACTIVITY, false, false);

            assertTrue((iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId))
                    & WapPushManagerParams.MESSAGE_HANDLED) ==
                    WapPushManagerParams.MESSAGE_HANDLED);

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

        } catch (RemoteException e) {
            assertTrue(false);
        }

        mPackageName = originalPackageName;
        mClassName = originalClassName;
    }

    /**
     * Message processing test, start service
     */
    public void testProcessMsg2() {
        byte[] pdu = createPDU(1);
        int headerLen = pdu.length - (mGsmHeader.length +
                mUserDataHeader.length + mMessageBody.length);
        int pduType = 6;
        int tranId = 0;
        String originalPackageName = mPackageName;
        String originalClassName = mClassName;

        try {

            mClassName = "com.android.smspush.unitTests.ReceiverService";

            // set up data
            IWapPushManager iwapman = getInterface();

            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_SERVICE, false, false);

            assertTrue((iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId))
                    & WapPushManagerParams.MESSAGE_HANDLED) ==
                    WapPushManagerParams.MESSAGE_HANDLED);

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

        } catch (RemoteException e) {
            assertTrue(false);
        }

        mPackageName = originalPackageName;
        mClassName = originalClassName;
    }

    /**
     * Message processing test, no signature
     */
    public void testProcessMsg3() {
        byte[] pdu = createPDU(1);
        int headerLen = pdu.length -
                (mGsmHeader.length + mUserDataHeader.length + mMessageBody.length);
        int pduType = 6;
        int tranId = 0;
        String originalPackageName = mPackageName;
        String originalClassName = mClassName;

        try {

            mPackageName = "com.android.development";
            mClassName = "com.android.development.Development";

            // set up data
            IWapPushManager iwapman = getInterface();

            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_SERVICE, true, false);

            assertFalse((iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId))
                    & WapPushManagerParams.MESSAGE_HANDLED) ==
                    WapPushManagerParams.MESSAGE_HANDLED);

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

        } catch (RemoteException e) {
            assertTrue(false);
        }

        mPackageName = originalPackageName;
        mClassName = originalClassName;
    }

    IDataVerify getVerifyInterface() {
        while (mIVerify == null) {
            // wait for the activity receive data.
            try {
                Thread.sleep(TIME_WAIT);
            } catch (InterruptedException e) {}
        }
        return mIVerify;
    }


    /**
     * Message processing test, received body data verification test
     */
    public void testProcessMsg4() {
        byte[] originalMessageBody = mMessageBody;
        mMessageBody = new byte[] {
                (byte) 0xee,
                (byte) 0xff,
                (byte) 0xee,
                (byte) 0xff,
                (byte) 0xee,
                (byte) 0xff,
                (byte) 0xee,
                (byte) 0xff,
                (byte) 0xee,
                (byte) 0xff,
                (byte) 0xee,
                (byte) 0xff,
        };

        byte[] pdu = createPDU(1);
        int headerLen = pdu.length -
                (mGsmHeader.length + mUserDataHeader.length + mMessageBody.length);
        int pduType = 6;
        int tranId = 0;
        String originalPackageName = mPackageName;
        String originalClassName = mClassName;

        try {
            IWapPushManager iwapman = getInterface();
            IDataVerify dataverify = getVerifyInterface();

            dataverify.resetData();

            // set up data
            mClassName = "com.android.smspush.unitTests.ReceiverActivity";
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_ACTIVITY, false, false);

            iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId));

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

            assertTrue(dataverify.verifyData(mMessageBody));

            // set up data
            dataverify.resetData();
            mClassName = "com.android.smspush.unitTests.ReceiverService";
            mMessageBody = new byte[] {
                    (byte) 0xaa,
                    (byte) 0xbb,
                    (byte) 0x11,
                    (byte) 0x22,
                    (byte) 0xaa,
                    (byte) 0xbb,
                    (byte) 0x11,
                    (byte) 0x22,
                    (byte) 0xaa,
                    (byte) 0xbb,
                    (byte) 0x11,
                    (byte) 0x22,
                    (byte) 0xaa,
                    (byte) 0xbb,
                    (byte) 0x11,
                    (byte) 0x22,
                    (byte) 0xaa,
                    (byte) 0xbb,
                    (byte) 0x11,
                    (byte) 0x22,
                    (byte) 0xaa,
                    (byte) 0xbb,
                    (byte) 0x11,
                    (byte) 0x22,
            };
            pdu = createPDU(1);
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_SERVICE, false, false);

            iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId));

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

            // Log.d(LOG_TAG, HexDump.dumpHexString(mMessageBody));
            assertTrue(dataverify.verifyData(mMessageBody));
        } catch (RemoteException e) {
            assertTrue(false);
        }

        mPackageName = originalPackageName;
        mClassName = originalClassName;
        mMessageBody = originalMessageBody;
    }

    /**
     * Message processing test, send invalid sms data
     */
    public void testProcessMsg5() {
        byte[] pdu = createPDU(2);
        int headerLen = pdu.length -
                (mGsmHeader.length + mUserDataHeader.length + mMessageBody.length);
        int pduType = 6;
        int tranId = 0;
        String originalPackageName = mPackageName;
        String originalClassName = mClassName;

        try {

            mClassName = "com.android.smspush.unitTests.ReceiverActivity";

            // set up data
            IWapPushManager iwapman = getInterface();
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_ACTIVITY, false, false);

            assertTrue((iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId))
                    & WapPushManagerParams.MESSAGE_HANDLED) ==
                    WapPushManagerParams.MESSAGE_HANDLED);

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

        } catch (RemoteException e) {
            assertTrue(false);
        }

        mPackageName = originalPackageName;
        mClassName = originalClassName;
    }

    /**
     * Message processing test, no receiver application
     */
    public void testProcessMsg6() {
        byte[] pdu = createPDU(1);
        int headerLen = pdu.length -
                (mGsmHeader.length + mUserDataHeader.length + mMessageBody.length);
        int pduType = 6;
        int tranId = 0;
        String originalPackageName = mPackageName;
        String originalClassName = mClassName;

        try {

            mClassName = "com.android.smspush.unitTests.NoReceiver";

            // set up data
            IWapPushManager iwapman = getInterface();
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_ACTIVITY, false, false);

            assertFalse((iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId))
                    & WapPushManagerParams.MESSAGE_HANDLED) ==
                    WapPushManagerParams.MESSAGE_HANDLED);

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

            // set up data
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_SERVICE, false, false);

            assertFalse((iwapman.processMessage(
                    Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue),
                    createIntent(pduType, tranId))
                    & WapPushManagerParams.MESSAGE_HANDLED) ==
                    WapPushManagerParams.MESSAGE_HANDLED);

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);

        } catch (RemoteException e) {
            assertTrue(false);
        }

        mPackageName = originalPackageName;
        mClassName = originalClassName;
    }

    /**
     * WspTypeDecoder test, normal pdu
     */
    public void testDecoder1() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        Random rd = new Random();

        for (int i = 0; i < 10; i++) {
            mAppIdValue = rd.nextInt(0xFFFF);
            byte[] pdu = createPDU(1);
            WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

            res = pduDecoder.seekXWapApplicationId(mWspHeaderStart,
                    mWspHeaderStart + mWspHeaderLen - 1);
            assertTrue(res);

            int index = (int) pduDecoder.getValue32();
            res = pduDecoder.decodeXWapApplicationId(index);
            assertTrue(res);

            Log.d(LOG_TAG, "mAppIdValue: " + mAppIdValue
                    + ", val: " + pduDecoder.getValue32());
            assertTrue(mAppIdValue == (int) pduDecoder.getValue32());
        }

        mAppIdValue = originalAppIdValue;
    }

    /**
     * WspTypeDecoder test, no header
     */
    public void testDecoder2() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        Random rd = new Random();

        mAppIdValue = rd.nextInt(0xFFFF);
        byte[] pdu = createPDU(2);
        WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

        res = pduDecoder.seekXWapApplicationId(mWspHeaderStart,
                mWspHeaderStart + mWspHeaderLen - 1);
        assertFalse(res);

        mAppIdValue = originalAppIdValue;
    }

    /**
     * WspTypeDecoder test, decode appid test
     */
    public void testDecoder3() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;
        Random rd = new Random();

        for (int i = 0; i < 100; i++) {
            mAppIdValue = rd.nextInt(0x0FFFFFFF);
            mContentTypeValue = rd.nextInt(0x0FFF);
            byte[] pdu = createPDU(3);
            WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

            res = pduDecoder.seekXWapApplicationId(mWspHeaderStart,
                    mWspHeaderStart + mWspHeaderLen - 1);
            assertTrue(res);

            int index = (int) pduDecoder.getValue32();
            res = pduDecoder.decodeXWapApplicationId(index);
            assertTrue(res);

            Log.d(LOG_TAG, "mAppIdValue: " + mAppIdValue
                    + ", val: " + pduDecoder.getValue32());
            assertTrue(mAppIdValue == (int) pduDecoder.getValue32());
        }

        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
    }

    /*
      public void testEnc() {
      byte[] arr = new byte[20];
      int index = 0;
      index += encodeUint32(0x87a5, arr, index);
      index += encodeUint32(0x1, arr, index);
      index += encodeUint32(0x9b, arr, index);
      index += encodeUint32(0x10, arr, index);
      index += encodeUint32(0xe0887, arr, index);
      index += encodeUint32(0x791a23d0, arr, index);

      Log.d(LOG_TAG, HexDump.dumpHexString(arr));
      }
    */

    /**
     * WspTypeDecoder test, no appid test
     */
    public void testDecoder4() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;
        Random rd = new Random();

        for (int i = 0; i < 100; i++) {
            mAppIdValue = rd.nextInt(0x0FFFFFFF);
            mContentTypeValue = rd.nextInt(0x0FFF);
            byte[] pdu = createPDU(4);
            WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

            res = pduDecoder.seekXWapApplicationId(mWspHeaderStart,
                    mWspHeaderStart + mWspHeaderLen - 1);
            assertFalse(res);

        }

        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
    }

    /**
     * WspTypeDecoder test, decode string appid test
     */
    public void testDecoder5() {
        boolean res;
        String originalAppIdName = mAppIdName;
        int originalContentTypeValue  = mContentTypeValue;
        Random rd = new Random();

        for (int i = 0; i < 10; i++) {
            mAppIdValue = rd.nextInt(0x0FFFFFFF);
            mContentTypeValue = rd.nextInt(0x0FFF);
            byte[] pdu = createPDU(5);
            WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

            res = pduDecoder.seekXWapApplicationId(mWspHeaderStart,
                    mWspHeaderStart + mWspHeaderLen - 1);
            assertTrue(res);

            int index = (int) pduDecoder.getValue32();
            res = pduDecoder.decodeXWapApplicationId(index);
            assertTrue(res);

            Log.d(LOG_TAG, "mAppIdValue: [" + mAppIdName + "], val: ["
                    + pduDecoder.getValueString() + "]");
            assertTrue(mAppIdName.equals(pduDecoder.getValueString()));
        }

        mAppIdName = originalAppIdName;
        mContentTypeValue = originalContentTypeValue;
    }

    /**
     * WspTypeDecoder test, decode string appid test
     */
    public void testDecoder6() {
        boolean res;
        String originalAppIdName = mAppIdName;
        int originalContentTypeValue  = mContentTypeValue;
        Random rd = new Random();

        for (int i = 0; i < OMA_APPLICATION_ID_NAMES.length; i++) {
            mAppIdName = OMA_APPLICATION_ID_NAMES[i];
            mContentTypeValue = rd.nextInt(0x0FFF);
            byte[] pdu = createPDU(6);
            WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

            res = pduDecoder.seekXWapApplicationId(mWspHeaderStart,
                    mWspHeaderStart + mWspHeaderLen - 1);
            assertTrue(res);

            int index = (int) pduDecoder.getValue32();
            res = pduDecoder.decodeXWapApplicationId(index);
            assertTrue(res);

            Log.d(LOG_TAG, "mAppIdValue: [" + mAppIdName + "], val: ["
                    + pduDecoder.getValueString() + "]");
            assertTrue(mAppIdName.equals(pduDecoder.getValueString()));
        }

        mAppIdName = originalAppIdName;
        mContentTypeValue = originalContentTypeValue;
    }

    /**
     * WspTypeDecoder test, decode OMA content type
     */
    public void testDecoder7() {
        boolean res;
        String originalAppIdName = mAppIdName;
        int originalContentTypeValue  = mContentTypeValue;
        Random rd = new Random();

        for (int i = 0; i < OMA_CONTENT_TYPE_NAMES.length; i++) {
            mContentTypeName = OMA_CONTENT_TYPE_NAMES[i];
            byte[] pdu = createPDU(7);
            WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

            res = pduDecoder.decodeContentType(mWspContentTypeStart);
            assertTrue(res);

            Log.d(LOG_TAG, "mContentTypeName: [" + mContentTypeName + "], val: ["
                    + pduDecoder.getValueString() + "]");
            assertTrue(mContentTypeName.equals(pduDecoder.getValueString()));
        }

        mAppIdName = originalAppIdName;
        mContentTypeValue = originalContentTypeValue;
    }


    /**
     * Copied from WapPushOverSms.
     * The code flow is not changed from the original.
     */
    public int dispatchWapPdu(byte[] pdu, IWapPushManager wapPushMan) {

        if (false) Log.d(LOG_TAG, "Rx: " + IccUtils.bytesToHexString(pdu));

        int index = 0;
        int transactionId = pdu[index++] & 0xFF;
        int pduType = pdu[index++] & 0xFF;
        int headerLength = 0;

        if ((pduType != WspTypeDecoder.PDU_TYPE_PUSH) &&
                (pduType != WspTypeDecoder.PDU_TYPE_CONFIRMED_PUSH)) {
            if (false) Log.w(LOG_TAG, "Received non-PUSH WAP PDU. Type = " + pduType);
            return Intents.RESULT_SMS_HANDLED;
        }

        WspTypeDecoder pduDecoder = new WspTypeDecoder(pdu);

        /**
         * Parse HeaderLen(unsigned integer).
         * From wap-230-wsp-20010705-a section 8.1.2
         * The maximum size of a uintvar is 32 bits.
         * So it will be encoded in no more than 5 octets.
         */
        if (pduDecoder.decodeUintvarInteger(index) == false) {
            if (false) Log.w(LOG_TAG, "Received PDU. Header Length error.");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }
        headerLength = (int) pduDecoder.getValue32();
        index += pduDecoder.getDecodedDataLength();

        int headerStartIndex = index;

        /**
         * Parse Content-Type.
         * From wap-230-wsp-20010705-a section 8.4.2.24
         *
         * Content-type-value = Constrained-media | Content-general-form
         * Content-general-form = Value-length Media-type
         * Media-type = (Well-known-media | Extension-Media) *(Parameter)
         * Value-length = Short-length | (Length-quote Length)
         * Short-length = <Any octet 0-30>   (octet <= WAP_PDU_SHORT_LENGTH_MAX)
         * Length-quote = <Octet 31>         (WAP_PDU_LENGTH_QUOTE)
         * Length = Uintvar-integer
         */
        if (pduDecoder.decodeContentType(index) == false) {
            if (false) Log.w(LOG_TAG, "Received PDU. Header Content-Type error.");
            return Intents.RESULT_SMS_GENERIC_ERROR;
        }

        String mimeType = pduDecoder.getValueString();
        long binaryContentType = pduDecoder.getValue32();
        index += pduDecoder.getDecodedDataLength();

        byte[] header = new byte[headerLength];
        System.arraycopy(pdu, headerStartIndex, header, 0, header.length);

        byte[] intentData;

        if (mimeType != null && mimeType.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO)) {
            intentData = pdu;
        } else {
            int dataIndex = headerStartIndex + headerLength;
            intentData = new byte[pdu.length - dataIndex];
            System.arraycopy(pdu, dataIndex, intentData, 0, intentData.length);
        }

        /**
         * Seek for application ID field in WSP header.
         * If application ID is found, WapPushManager substitute the message
         * processing. Since WapPushManager is optional module, if WapPushManager
         * is not found, legacy message processing will be continued.
         */
        if (pduDecoder.seekXWapApplicationId(index, index + headerLength - 1)) {
            index = (int) pduDecoder.getValue32();
            pduDecoder.decodeXWapApplicationId(index);
            String wapAppId = pduDecoder.getValueString();
            if (wapAppId == null) {
                wapAppId = Integer.toString((int) pduDecoder.getValue32());
            }

            String contentType = ((mimeType == null) ?
                    Long.toString(binaryContentType) : mimeType);
            if (false) Log.v(LOG_TAG, "appid found: " + wapAppId + ":" + contentType);

            try {
                boolean processFurther = true;
                // IWapPushManager wapPushMan = mWapConn.getWapPushManager();
                if (wapPushMan == null) {
                    if (false) Log.w(LOG_TAG, "wap push manager not found!");
                } else {
                    Intent intent = new Intent();
                    intent.putExtra("transactionId", transactionId);
                    intent.putExtra("pduType", pduType);
                    intent.putExtra("header", header);
                    intent.putExtra("data", intentData);
                    intent.putExtra("contentTypeParameters",
                            pduDecoder.getContentParameters());

                    int procRet = wapPushMan.processMessage(wapAppId, contentType, intent);
                    if (false) Log.v(LOG_TAG, "procRet:" + procRet);
                    if ((procRet & WapPushManagerParams.MESSAGE_HANDLED) > 0
                            && (procRet & WapPushManagerParams.FURTHER_PROCESSING) == 0) {
                        processFurther = false;
                    }
                }
                if (!processFurther) {
                    return Intents.RESULT_SMS_HANDLED;
                }
            } catch (RemoteException e) {
                if (false) Log.w(LOG_TAG, "remote func failed...");
            }
        }
        if (false) Log.v(LOG_TAG, "fall back to existing handler");

        return Activity.RESULT_OK;
    }

    protected byte[] retrieveWspBody() {
        byte[] array = new byte[mWspHeader.length + mMessageBody.length];

        System.arraycopy(mWspHeader, 0, array, 0, mWspHeader.length);
        System.arraycopy(mMessageBody, 0, array, mWspHeader.length, mMessageBody.length);
        return array;
    }

    protected String getContentTypeName(int ctypeVal) {
        int i;

        for (i = 0; i < OMA_CONTENT_TYPE_VALUES.length; i++) {
            if (ctypeVal == OMA_CONTENT_TYPE_VALUES[i]) {
                return OMA_CONTENT_TYPE_NAMES[i];
            }
        }
        return null;
    }

    protected boolean isContentTypeMapped(int ctypeVal) {
        int i;

        for (i = 0; i < OMA_CONTENT_TYPE_VALUES.length; i++) {
            if (ctypeVal == OMA_CONTENT_TYPE_VALUES[i]) return true;
        }
        return false;
    }

    /**
     * Integration test 1, simple case
     */
    public void testIntegration1() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;
        String originalAppIdName = mAppIdName;
        String originalContentTypeName = mContentTypeName;
        String originalClassName = mClassName;
        byte[] originalMessageBody = mMessageBody;
        Random rd = new Random();

        mMessageBody = new byte[100 + rd.nextInt(100)];
        rd.nextBytes(mMessageBody);

        byte[] pdu = createPDU(1);
        byte[] wappushPdu = retrieveWspBody();


        mClassName = "com.android.smspush.unitTests.ReceiverActivity";
        // Phone dummy = new DummyPhone(getContext());
        // Phone gsm = PhoneFactory.getGsmPhone();
        // GSMPhone gsm = new GSMPhone(getContext(), new SimulatedCommands(), null, true);
        // WapPushOverSms dispatcher = new WapPushOverSms(dummy, null);

        try {
            // set up data
            IWapPushManager iwapman = getInterface();
            IDataVerify dataverify = getVerifyInterface();

            dataverify.resetData();

            if (isContentTypeMapped(mContentTypeValue)) {
                // content type is mapped
                mContentTypeName = getContentTypeName(mContentTypeValue);
                Log.d(LOG_TAG, "mContentTypeValue mapping "
                        + mContentTypeName + ":" + mContentTypeValue);
            } else {
                mContentTypeName = Integer.toString(mContentTypeValue);
            }
            iwapman.addPackage(Integer.toString(mAppIdValue),
                    mContentTypeName, mPackageName, mClassName,
                    WapPushManagerParams.APP_TYPE_ACTIVITY, false, false);

            dispatchWapPdu(wappushPdu, iwapman);

            // clean up data
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    mContentTypeName, mPackageName, mClassName);

            assertTrue(dataverify.verifyData(mMessageBody));
        } catch (RemoteException e) {
        }


        mClassName = originalClassName;
        mAppIdName = originalAppIdName;
        mContentTypeName = originalContentTypeName;
        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
        mMessageBody = originalMessageBody;
    }

    /**
     * Integration test 2, random mAppIdValue(int), all OMA content type
     */
    public void testIntegration2() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;
        String originalAppIdName = mAppIdName;
        String originalContentTypeName = mContentTypeName;
        String originalClassName = mClassName;
        byte[] originalMessageBody = mMessageBody;
        Random rd = new Random();

        IWapPushManager iwapman = getInterface();
        IDataVerify dataverify = getVerifyInterface();
        mClassName = "com.android.smspush.unitTests.ReceiverActivity";

        for (int i = 0; i < OMA_CONTENT_TYPE_NAMES.length; i++) {
            mContentTypeName = OMA_CONTENT_TYPE_NAMES[i];
            mAppIdValue = rd.nextInt(0x0FFFFFFF);

            mMessageBody = new byte[100 + rd.nextInt(100)];
            rd.nextBytes(mMessageBody);

            byte[] pdu = createPDU(7);
            byte[] wappushPdu = retrieveWspBody();

            try {
                dataverify.resetData();
                // set up data
                iwapman.addPackage(Integer.toString(mAppIdValue),
                        mContentTypeName, mPackageName, mClassName,
                        WapPushManagerParams.APP_TYPE_ACTIVITY, false, false);

                dispatchWapPdu(wappushPdu, iwapman);

                // clean up data
                iwapman.deletePackage(Integer.toString(mAppIdValue),
                        mContentTypeName, mPackageName, mClassName);

                if (mContentTypeName.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO)) {
                    assertTrue(dataverify.verifyData(wappushPdu));
                } else {
                    assertTrue(dataverify.verifyData(mMessageBody));
                }
            } catch (RemoteException e) {
            }
        }


        mClassName = originalClassName;
        mAppIdName = originalAppIdName;
        mContentTypeName = originalContentTypeName;
        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
        mMessageBody = originalMessageBody;
    }

    /**
     * Integration test 3, iterate OmaApplication ID, random binary content type
     */
    public void testIntegration3() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;
        String originalAppIdName = mAppIdName;
        String originalContentTypeName = mContentTypeName;
        String originalClassName = mClassName;
        byte[] originalMessageBody = mMessageBody;
        Random rd = new Random();

        IWapPushManager iwapman = getInterface();
        IDataVerify dataverify = getVerifyInterface();
        mClassName = "com.android.smspush.unitTests.ReceiverService";

        for (int i = 0; i < OMA_APPLICATION_ID_NAMES.length; i++) {
            mAppIdName = OMA_APPLICATION_ID_NAMES[i];
            mContentTypeValue = rd.nextInt(0x0FFF);

            mMessageBody = new byte[100 + rd.nextInt(100)];
            rd.nextBytes(mMessageBody);

            byte[] pdu = createPDU(6);
            byte[] wappushPdu = retrieveWspBody();

            try {
                dataverify.resetData();
                // set up data
                if (isContentTypeMapped(mContentTypeValue)) {
                    // content type is mapped to integer value
                    mContentTypeName = getContentTypeName(mContentTypeValue);
                    Log.d(LOG_TAG, "mContentTypeValue mapping "
                            + mContentTypeValue + ":" + mContentTypeName);
                } else {
                    mContentTypeName = Integer.toString(mContentTypeValue);
                }

                iwapman.addPackage(mAppIdName,
                        mContentTypeName, mPackageName, mClassName,
                        WapPushManagerParams.APP_TYPE_SERVICE, false, false);

                dispatchWapPdu(wappushPdu, iwapman);

                // clean up data
                iwapman.deletePackage(mAppIdName,
                        mContentTypeName, mPackageName, mClassName);

                if (mContentTypeName.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO)) {
                    assertTrue(dataverify.verifyData(wappushPdu));
                } else {
                    assertTrue(dataverify.verifyData(mMessageBody));
                }
            } catch (RemoteException e) {
            }
        }

        mClassName = originalClassName;
        mAppIdName = originalAppIdName;
        mContentTypeName = originalContentTypeName;
        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
        mMessageBody = originalMessageBody;
    }

    /**
     * Integration test 4, iterate OmaApplication ID, Oma content type
     */
    public void testIntegration4() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;
        String originalAppIdName = mAppIdName;
        String originalContentTypeName = mContentTypeName;
        String originalClassName = mClassName;
        byte[] originalMessageBody = mMessageBody;
        Random rd = new Random();

        IWapPushManager iwapman = getInterface();
        IDataVerify dataverify = getVerifyInterface();
        mClassName = "com.android.smspush.unitTests.ReceiverService";

        for (int i = 0; i < OMA_APPLICATION_ID_NAMES.length
                + OMA_CONTENT_TYPE_NAMES.length; i++) {
            mAppIdName = OMA_APPLICATION_ID_NAMES[rd.nextInt(OMA_APPLICATION_ID_NAMES.length)];
            int contIndex = rd.nextInt(OMA_CONTENT_TYPE_NAMES.length);
            mContentTypeName = OMA_CONTENT_TYPE_NAMES[contIndex];

            mMessageBody = new byte[100 + rd.nextInt(100)];
            rd.nextBytes(mMessageBody);

            byte[] pdu = createPDU(8);
            byte[] wappushPdu = retrieveWspBody();

            try {
                dataverify.resetData();
                // set up data
                iwapman.addPackage(mAppIdName,
                        mContentTypeName, mPackageName, mClassName,
                        WapPushManagerParams.APP_TYPE_SERVICE, false, false);

                dispatchWapPdu(wappushPdu, iwapman);

                // clean up data
                iwapman.deletePackage(mAppIdName,
                        mContentTypeName, mPackageName, mClassName);

                if (mContentTypeName.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO)) {
                    assertTrue(dataverify.verifyData(wappushPdu));
                } else {
                    assertTrue(dataverify.verifyData(mMessageBody));
                }
            } catch (RemoteException e) {
            }
        }

        mClassName = originalClassName;
        mAppIdName = originalAppIdName;
        mContentTypeName = originalContentTypeName;
        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
        mMessageBody = originalMessageBody;
    }

    /**
     * Integration test 5, iterate binary OmaApplication ID, Oma binary content type
     */
    public void testIntegration5() {
        boolean res;
        int originalAppIdValue = mAppIdValue;
        int originalContentTypeValue  = mContentTypeValue;
        String originalAppIdName = mAppIdName;
        String originalContentTypeName = mContentTypeName;
        String originalClassName = mClassName;
        byte[] originalMessageBody = mMessageBody;
        Random rd = new Random();

        IWapPushManager iwapman = getInterface();
        IDataVerify dataverify = getVerifyInterface();
        mClassName = "com.android.smspush.unitTests.ReceiverService";

        for (int i = 0; i < OMA_APPLICATION_ID_VALUES.length +
                    OMA_CONTENT_TYPE_VALUES.length; i++) {
            mAppIdValue = OMA_APPLICATION_ID_VALUES[rd.nextInt(
                    OMA_APPLICATION_ID_VALUES.length)];
            mContentTypeValue =
                    OMA_CONTENT_TYPE_VALUES[rd.nextInt(OMA_CONTENT_TYPE_VALUES.length)];

            mMessageBody = new byte[100 + rd.nextInt(100)];
            rd.nextBytes(mMessageBody);

            byte[] pdu = createPDU(3);
            byte[] wappushPdu = retrieveWspBody();

            try {
                dataverify.resetData();
                // set up data
                if (isContentTypeMapped(mContentTypeValue)) {
                    // content type is mapped to integer value
                    mContentTypeName = getContentTypeName(mContentTypeValue);
                    Log.d(LOG_TAG, "mContentTypeValue mapping "
                            + mContentTypeValue + ":" + mContentTypeName);
                } else {
                    mContentTypeName = Integer.toString(mContentTypeValue);
                }

                iwapman.addPackage(Integer.toString(mAppIdValue),
                        mContentTypeName, mPackageName, mClassName,
                        WapPushManagerParams.APP_TYPE_SERVICE, false, false);

                dispatchWapPdu(wappushPdu, iwapman);

                // clean up data
                iwapman.deletePackage(Integer.toString(mAppIdValue),
                        mContentTypeName, mPackageName, mClassName);

                if (mContentTypeName.equals(WspTypeDecoder.CONTENT_TYPE_B_PUSH_CO)) {
                    assertTrue(dataverify.verifyData(wappushPdu));
                } else {
                    assertTrue(dataverify.verifyData(mMessageBody));
                }
            } catch (RemoteException e) {
            }
        }

        mClassName = originalClassName;
        mAppIdName = originalAppIdName;
        mContentTypeName = originalContentTypeName;
        mAppIdValue = originalAppIdValue;
        mContentTypeValue = originalContentTypeValue;
        mMessageBody = originalMessageBody;
    }

    /**
     * DataBase migration test.
     */
    public void testDataBaseMigration() {
        IWapPushManager iwapman = getInterface();
        WapPushManager wpman = getService();
        Context context = getContext();

        addPackageToLegacyDB(mAppIdValue, mContentTypeValue, mPackageName, mClassName,
                WapPushManagerParams.APP_TYPE_SERVICE, true, true);
        addPackageToLegacyDB(mAppIdValue + 10, mContentTypeValue, mPackageName, mClassName,
                WapPushManagerParams.APP_TYPE_SERVICE, true, true);

        File oldDbFile = context.getDatabasePath("wappush.db");
        assertTrue(oldDbFile.exists());
        assertTrue(wpman.verifyData(Integer.toString(mAppIdValue),
                Integer.toString(mContentTypeValue),
                mPackageName, mClassName,
                WapPushManagerParams.APP_TYPE_SERVICE, true, true));
        assertFalse(oldDbFile.exists());

        // Clean up DB
        try {
            iwapman.deletePackage(Integer.toString(mAppIdValue),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
            iwapman.deletePackage(Integer.toString(mAppIdValue + 10),
                    Integer.toString(mContentTypeValue), mPackageName, mClassName);
        } catch (RemoteException e) {
            assertTrue(false);
        }
    }

    private void addPackageToLegacyDB(int appId, int contextType, String packagename,
            String classnName, int appType, boolean signature, boolean furtherProcessing) {
        WapPushManager wpman = getService();
        WapPushManDBHelper dbh = new WapPushManDBHelper(getContext());
        SQLiteDatabase db = dbh.getWritableDatabase();

        wpman.insertPackage(dbh, db, Integer.toString(appId), Integer.toString(contextType),
                packagename, classnName, appType, signature, furtherProcessing);
    }
}
