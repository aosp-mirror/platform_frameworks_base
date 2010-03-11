/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.status;

import com.android.internal.R;
import com.android.internal.util.CharSequences;

import android.app.ActivityManagerNative;
import android.app.Dialog;
import android.app.IStatusBar;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Binder;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Telephony;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;


/**
 * The public (ok, semi-public) service for the status bar.
 * <p>
 * This interesting thing to note about this class is that most of the methods that
 * are called from other classes just post a message, and everything else is batched
 * and coalesced into a series of calls to methods that all start with "perform."
 * There are two reasons for this.  The first is that some of the methods (activate/deactivate)
 * are on IStatusBar, so they're called from the thread pool and they need to make their
 * way onto the UI thread.  The second is that the message queue is stopped while animations
 * are happening in order to make for smoother transitions.
 * <p>
 * Each icon is either an icon or an icon and a notification.  They're treated mostly
 * separately throughout the code, although they both use the same key, which is assigned
 * when they are created.
 */
public class StatusBarService extends IStatusBar.Stub
{
    static final String TAG = "StatusBar";
    static final boolean SPEW = false;

    static final int EXPANDED_LEAVE_ALONE = -10000;
    static final int EXPANDED_FULL_OPEN = -10001;

    private static final int MSG_ANIMATE = 1000;
    private static final int MSG_ANIMATE_REVEAL = 1001;

    private static final int OP_ADD_ICON = 1;
    private static final int OP_UPDATE_ICON = 2;
    private static final int OP_REMOVE_ICON = 3;
    private static final int OP_SET_VISIBLE = 4;
    private static final int OP_EXPAND = 5;
    private static final int OP_TOGGLE = 6;
    private static final int OP_DISABLE = 7;
    private class PendingOp {
        IBinder key;
        int code;
        IconData iconData;
        NotificationData notificationData;
        boolean visible;
        int integer;
    }

    private class DisableRecord implements IBinder.DeathRecipient {
        String pkg;
        int what;
        IBinder token;

        public void binderDied() {
            Log.i(TAG, "binder died for pkg=" + pkg);
            disable(0, token, pkg);
            token.unlinkToDeath(this, 0);
        }
    }

    public interface NotificationCallbacks {
        void onSetDisabled(int status);
        void onClearAll();
        void onNotificationClick(String pkg, String tag, int id);
        void onPanelRevealed();
    }

    private class ExpandedDialog extends Dialog {
        ExpandedDialog(Context context) {
            super(context, com.android.internal.R.style.Theme_Light_NoTitleBar);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    StatusBarService.this.deactivate();
                }
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
    
    final Context mContext;
    final Display mDisplay;
    StatusBarView mStatusBarView;
    int mPixelFormat;
    H mHandler = new H();
    ArrayList<PendingOp> mQueue = new ArrayList<PendingOp>();
    NotificationCallbacks mNotificationCallbacks;
    
    // All accesses to mIconMap and mNotificationData are syncronized on those objects,
    // but this is only so dump() can work correctly.  Modifying these outside of the UI
    // thread will not work, there are places in the code that unlock and reaquire between
    // reads and require them to not be modified.

    // icons
    HashMap<IBinder,StatusBarIcon> mIconMap = new HashMap<IBinder,StatusBarIcon>();
    ArrayList<StatusBarIcon> mIconList = new ArrayList<StatusBarIcon>();
    String[] mRightIconSlots;
    StatusBarIcon[] mRightIcons;
    LinearLayout mIcons;
    IconMerger mNotificationIcons;
    LinearLayout mStatusIcons;
    StatusBarIcon mMoreIcon;
    private UninstallReceiver mUninstallReceiver;

    // expanded notifications
    NotificationViewList mNotificationData = new NotificationViewList();
    Dialog mExpandedDialog;
    ExpandedView mExpandedView;
    WindowManager.LayoutParams mExpandedParams;
    ScrollView mScrollView;
    View mNotificationLinearLayout;
    TextView mOngoingTitle;
    LinearLayout mOngoingItems;
    TextView mLatestTitle;
    LinearLayout mLatestItems;
    TextView mNoNotificationsTitle;
    TextView mSpnLabel;
    TextView mPlmnLabel;
    TextView mClearButton;
    CloseDragHandle mCloseView;
    int[] mCloseLocation = new int[2];
    boolean mExpanded;
    boolean mExpandedVisible;

    // the date view
    DateView mDateView;

    // the tracker view
    TrackingView mTrackingView;
    WindowManager.LayoutParams mTrackingParams;
    int mTrackingPosition;

    // ticker
    private Ticker mTicker;
    private View mTickerView;
    private boolean mTicking;
    
    // Tracking finger for opening/closing.
    boolean mTracking;
    VelocityTracker mVelocityTracker;
    
    static final int ANIM_FRAME_DURATION = (1000/60);
    
    boolean mAnimating;
    long mCurAnimationTime;
    float mDisplayHeight;
    float mAnimY;
    float mAnimVel;
    float mAnimAccel;
    long mAnimLastTime;
    boolean mAnimatingReveal = false;
    int mViewDelta;
    int[] mAbsPos = new int[2];
    
    // for disabling the status bar
    ArrayList<DisableRecord> mDisableRecords = new ArrayList<DisableRecord>();
    int mDisabled = 0;

    /**
     * Construct the service, add the status bar view to the window manager
     */
    public StatusBarService(Context context) {
        mContext = context;
        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        makeStatusBarView(context);
        mUninstallReceiver = new UninstallReceiver();
    }

    public void setNotificationCallbacks(NotificationCallbacks listener) {
        mNotificationCallbacks = listener;
    }

    // ================================================================================
    // Constructing the view
    // ================================================================================
    private void makeStatusBarView(Context context) {
        Resources res = context.getResources();
        mRightIconSlots = res.getStringArray(com.android.internal.R.array.status_bar_icon_order);
        mRightIcons = new StatusBarIcon[mRightIconSlots.length];

        ExpandedView expanded = (ExpandedView)View.inflate(context,
                com.android.internal.R.layout.status_bar_expanded, null);
        expanded.mService = this;
        StatusBarView sb = (StatusBarView)View.inflate(context,
                com.android.internal.R.layout.status_bar, null);
        sb.mService = this;

        // figure out which pixel-format to use for the status bar.
        mPixelFormat = PixelFormat.TRANSLUCENT;
        Drawable bg = sb.getBackground();
        if (bg != null) {
            mPixelFormat = bg.getOpacity();
        }

        mStatusBarView = sb;
        mStatusIcons = (LinearLayout)sb.findViewById(R.id.statusIcons);
        mNotificationIcons = (IconMerger)sb.findViewById(R.id.notificationIcons);
        mNotificationIcons.service = this;
        mIcons = (LinearLayout)sb.findViewById(R.id.icons);
        mTickerView = sb.findViewById(R.id.ticker);
        mDateView = (DateView)sb.findViewById(R.id.date);

        mExpandedDialog = new ExpandedDialog(context);
        mExpandedView = expanded;
        mOngoingTitle = (TextView)expanded.findViewById(R.id.ongoingTitle);
        mOngoingItems = (LinearLayout)expanded.findViewById(R.id.ongoingItems);
        mLatestTitle = (TextView)expanded.findViewById(R.id.latestTitle);
        mLatestItems = (LinearLayout)expanded.findViewById(R.id.latestItems);
        mNoNotificationsTitle = (TextView)expanded.findViewById(R.id.noNotificationsTitle);
        mClearButton = (TextView)expanded.findViewById(R.id.clear_all_button);
        mClearButton.setOnClickListener(mClearButtonListener);
        mSpnLabel = (TextView)expanded.findViewById(R.id.spnLabel);
        mPlmnLabel = (TextView)expanded.findViewById(R.id.plmnLabel);
        mScrollView = (ScrollView)expanded.findViewById(R.id.scroll);
        mNotificationLinearLayout = expanded.findViewById(R.id.notificationLinearLayout);

        mOngoingTitle.setVisibility(View.GONE);
        mLatestTitle.setVisibility(View.GONE);
        
        mTicker = new MyTicker(context, sb);

        TickerView tickerView = (TickerView)sb.findViewById(R.id.tickerText);
        tickerView.mTicker = mTicker;

        mTrackingView = (TrackingView)View.inflate(context,
                com.android.internal.R.layout.status_bar_tracking, null);
        mTrackingView.mService = this;
        mCloseView = (CloseDragHandle)mTrackingView.findViewById(R.id.close);
        mCloseView.mService = this;

        // add the more icon for the notifications
        IconData moreData = IconData.makeIcon(null, context.getPackageName(),
                R.drawable.stat_notify_more, 0, 42);
        mMoreIcon = new StatusBarIcon(context, moreData, mNotificationIcons);
        mMoreIcon.view.setId(R.drawable.stat_notify_more);
        mNotificationIcons.moreIcon = mMoreIcon;
        mNotificationIcons.addView(mMoreIcon.view);

        // set the inital view visibility
        setAreThereNotifications();
        mDateView.setVisibility(View.INVISIBLE);

        // before we register for broadcasts
        mPlmnLabel.setText(R.string.lockscreen_carrier_default);
        mPlmnLabel.setVisibility(View.VISIBLE);
        mSpnLabel.setText("");
        mSpnLabel.setVisibility(View.GONE);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Telephony.Intents.SPN_STRINGS_UPDATED_ACTION);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    public void systemReady() {
        final StatusBarView view = mStatusBarView;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                view.getContext().getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height),
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|
                WindowManager.LayoutParams.FLAG_TOUCHABLE_WHEN_WAKING,
                mPixelFormat);
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBar");
        lp.windowAnimations = R.style.Animation_StatusBar;

        WindowManagerImpl.getDefault().addView(view, lp);
    }
    
    // ================================================================================
    // From IStatusBar
    // ================================================================================
    public void activate() {
        enforceExpandStatusBar();
        addPendingOp(OP_EXPAND, null, true);
    }

    public void deactivate() {
        enforceExpandStatusBar();
        addPendingOp(OP_EXPAND, null, false);
    }

    public void toggle() {
        enforceExpandStatusBar();
        addPendingOp(OP_TOGGLE, null, false);
    }

    public void disable(int what, IBinder token, String pkg) {
        enforceStatusBar();
        synchronized (mNotificationCallbacks) {
            // This is a little gross, but I think it's safe as long as nobody else
            // synchronizes on mNotificationCallbacks.  It's important that the the callback
            // and the pending op get done in the correct order and not interleaved with
            // other calls, otherwise they'll get out of sync.
            int net;
            synchronized (mDisableRecords) {
                manageDisableListLocked(what, token, pkg);
                net = gatherDisableActionsLocked();
                mNotificationCallbacks.onSetDisabled(net);
            }
            addPendingOp(OP_DISABLE, net);
        }
    }

    public IBinder addIcon(String slot, String iconPackage, int iconId, int iconLevel) {
        enforceStatusBar();
        return addIcon(IconData.makeIcon(slot, iconPackage, iconId, iconLevel, 0), null);
    }

    public void updateIcon(IBinder key,
            String slot, String iconPackage, int iconId, int iconLevel) {
        enforceStatusBar();
        updateIcon(key, IconData.makeIcon(slot, iconPackage, iconId, iconLevel, 0), null);
    }

    public void removeIcon(IBinder key) {
        enforceStatusBar();
        addPendingOp(OP_REMOVE_ICON, key, null, null, -1);
    }

    private void enforceStatusBar() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.STATUS_BAR,
                "StatusBarService");
    }

    private void enforceExpandStatusBar() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.EXPAND_STATUS_BAR,
                "StatusBarService");
    }

    // ================================================================================
    // Can be called from any thread
    // ================================================================================
    public IBinder addIcon(IconData data, NotificationData n) {
        int slot;
        // assert early-on if they using a slot that doesn't exist.
        if (data != null && n == null) {
            slot = getRightIconIndex(data.slot);
            if (slot < 0) {
                throw new SecurityException("invalid status bar icon slot: "
                        + (data.slot != null ? "'" + data.slot + "'" : "null"));
            }
        } else {
            slot = -1;
        }
        IBinder key = new Binder();
        addPendingOp(OP_ADD_ICON, key, data, n, -1);
        return key;
    }

    public void updateIcon(IBinder key, IconData data, NotificationData n) {
        addPendingOp(OP_UPDATE_ICON, key, data, n, -1);
    }

    public void setIconVisibility(IBinder key, boolean visible) {
        addPendingOp(OP_SET_VISIBLE, key, visible);
    }

    private void addPendingOp(int code, IBinder key, IconData data, NotificationData n, int i) {
        synchronized (mQueue) {
            PendingOp op = new PendingOp();
            op.key = key;
            op.code = code;
            op.iconData = data == null ? null : data.clone();
            op.notificationData = n;
            op.integer = i;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                mHandler.sendEmptyMessage(2);
            }
        }
    }

    private void addPendingOp(int code, IBinder key, boolean visible) {
        synchronized (mQueue) {
            PendingOp op = new PendingOp();
            op.key = key;
            op.code = code;
            op.visible = visible;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                mHandler.sendEmptyMessage(1);
            }
        }
    }

    private void addPendingOp(int code, int integer) {
        synchronized (mQueue) {
            PendingOp op = new PendingOp();
            op.code = code;
            op.integer = integer;
            mQueue.add(op);
            if (mQueue.size() == 1) {
                mHandler.sendEmptyMessage(1);
            }
        }
    }

    // lock on mDisableRecords
    void manageDisableListLocked(int what, IBinder token, String pkg) {
        if (SPEW) {
            Log.d(TAG, "manageDisableList what=0x" + Integer.toHexString(what)
                    + " pkg=" + pkg);
        }
        // update the list
        synchronized (mDisableRecords) {
            final int N = mDisableRecords.size();
            DisableRecord tok = null;
            int i;
            for (i=0; i<N; i++) {
                DisableRecord t = mDisableRecords.get(i);
                if (t.token == token) {
                    tok = t;
                    break;
                }
            }
            if (what == 0 || !token.isBinderAlive()) {
                if (tok != null) {
                    mDisableRecords.remove(i);
                    tok.token.unlinkToDeath(tok, 0);
                }
            } else {
                if (tok == null) {
                    tok = new DisableRecord();
                    try {
                        token.linkToDeath(tok, 0);
                    }
                    catch (RemoteException ex) {
                        return; // give up
                    }
                    mDisableRecords.add(tok);
                }
                tok.what = what;
                tok.token = token;
                tok.pkg = pkg;
            }
        }
    }

    // lock on mDisableRecords
    int gatherDisableActionsLocked() {
        final int N = mDisableRecords.size();
        // gather the new net flags
        int net = 0;
        for (int i=0; i<N; i++) {
            net |= mDisableRecords.get(i).what;
        }
        return net;
    }

    private int getRightIconIndex(String slot) {
        final int N = mRightIconSlots.length;
        for (int i=0; i<N; i++) {
            if (mRightIconSlots[i].equals(slot)) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================================
    // Always called from UI thread
    // ================================================================================
    /**
     * All changes to the status bar and notifications funnel through here and are batched.
     */
    private class H extends Handler {
        public void handleMessage(Message m) {
            if (m.what == MSG_ANIMATE) {
                doAnimation();
                return;
            }
            if (m.what == MSG_ANIMATE_REVEAL) {
                doRevealAnimation();
                return;
            }
            boolean expand = false;
            boolean doExpand = false;
            boolean doDisable = false;
            int disableWhat = 0;

            synchronized (mQueue) {
                boolean wasExpanded = mExpanded;

                // for each one in the queue, find all of the ones with the same key
                // and collapse that down into a final op and/or call to setVisibility, etc
                expand = wasExpanded;
                int N = mQueue.size();
                while (N > 0) {
                    PendingOp op = mQueue.get(0);
                    boolean doOp = false;
                    boolean visible = false;
                    boolean doVisibility = false;
                    if (op.code == OP_SET_VISIBLE) {
                        doVisibility = true;
                        visible = op.visible;
                    }
                    else if (op.code == OP_EXPAND) {
                        doExpand = true;
                        expand = op.visible;
                    }
                    else if (op.code == OP_TOGGLE) {
                        doExpand = true;
                        expand = !expand;
                    }
                    else {
                        doOp = true;
                    }

                    if (alwaysHandle(op.code)) {
                        // coalesce these
                        for (int i=1; i<N; i++) {
                            PendingOp o = mQueue.get(i);
                            if (!alwaysHandle(o.code) && o.key == op.key) {
                                if (o.code == OP_SET_VISIBLE) {
                                    visible = o.visible;
                                    doVisibility = true;
                                }
                                else if (o.code == OP_EXPAND) {
                                    expand = o.visible;
                                    doExpand = true;
                                }
                                else {
                                    op.code = o.code;
                                    op.iconData = o.iconData;
                                    op.notificationData = o.notificationData;
                                }
                                mQueue.remove(i);
                                i--;
                                N--;
                            }
                        }
                    }

                    mQueue.remove(0);
                    N--;

                    if (doOp) {
                        switch (op.code) {
                            case OP_ADD_ICON:
                            case OP_UPDATE_ICON:
                                performAddUpdateIcon(op.key, op.iconData, op.notificationData);
                                break;
                            case OP_REMOVE_ICON:
                                performRemoveIcon(op.key);
                                break;
                            case OP_DISABLE:
                                doDisable = true;
                                disableWhat = op.integer;
                                break;
                        }
                    }
                    if (doVisibility && op.code != OP_REMOVE_ICON) {
                        performSetIconVisibility(op.key, visible);
                    }
                }

                if (mQueue.size() != 0) {
                    throw new RuntimeException("Assertion failed: mQueue.size=" + mQueue.size());
                }
            }
            // This must be done outside the synchronized block above to prevent a deadlock where
            // we call into the NotificationManagerService and it is in turn attempting to post a
            // message to our queue.
            if (doExpand) {
                // this is last so that we capture all of the pending changes before doing it
                if (expand) {
                    animateExpand();
                } else {
                    animateCollapse();
                }
            }
            if (doDisable) {
                performDisableActions(disableWhat);
            }
        }
    }

    private boolean alwaysHandle(int code) {
        return code == OP_DISABLE;
    }

    /* private */ void performAddUpdateIcon(IBinder key, IconData data, NotificationData n)
                        throws StatusBarException {
        if (SPEW) {
            Log.d(TAG, "performAddUpdateIcon icon=" + data + " notification=" + n + " key=" + key);
        }
        // notification
        if (n != null) {
            StatusBarNotification notification = getNotification(key);
            NotificationData oldData = null;
            if (notification == null) {
                // add
                notification = new StatusBarNotification();
                notification.key = key;
                notification.data = n;
                synchronized (mNotificationData) {
                    mNotificationData.add(notification);
                }
                addNotificationView(notification);
                setAreThereNotifications();
            } else {
                // update
                oldData = notification.data;
                notification.data = n;
                updateNotificationView(notification, oldData);
            }
            // Show the ticker if one is requested, and the text is different
            // than the currently displayed ticker.  Also don't do this
            // until status bar window is attached to the window manager,
            // because...  well, what's the point otherwise?  And trying to
            // run a ticker without being attached will crash!
            if (n.tickerText != null && mStatusBarView.getWindowToken() != null
                    && (oldData == null
                        || oldData.tickerText == null
                        || !CharSequences.equals(oldData.tickerText, n.tickerText))) {
                if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
                    mTicker.addEntry(n, StatusBarIcon.getIcon(mContext, data), n.tickerText);
                }
            }
            updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        }

        // icon
        synchronized (mIconMap) {
            StatusBarIcon icon = mIconMap.get(key);
            if (icon == null) {
                // add
                LinearLayout v = n == null ? mStatusIcons : mNotificationIcons;

                icon = new StatusBarIcon(mContext, data, v);
                mIconMap.put(key, icon);
                mIconList.add(icon);

                if (n == null) {
                    int slotIndex = getRightIconIndex(data.slot);
                    StatusBarIcon[] rightIcons = mRightIcons;
                    if (rightIcons[slotIndex] == null) {
                        int pos = 0;
                        for (int i=mRightIcons.length-1; i>slotIndex; i--) {
                            StatusBarIcon ic = rightIcons[i];
                            if (ic != null) {
                                pos++;
                            }
                        }
                        rightIcons[slotIndex] = icon;
                        mStatusIcons.addView(icon.view, pos);
                    } else {
                        Log.e(TAG, "duplicate icon in slot " + slotIndex + "/" + data.slot);
                        mIconMap.remove(key);
                        mIconList.remove(icon);
                        return ;
                    }
                } else {
                    int iconIndex = mNotificationData.getIconIndex(n);
                    mNotificationIcons.addView(icon.view, iconIndex);
                }
            } else {
                if (n == null) {
                    // right hand side icons -- these don't reorder
                    icon.update(mContext, data);
                } else {
                    // remove old
                    ViewGroup parent = (ViewGroup)icon.view.getParent();
                    parent.removeView(icon.view);
                    // add new
                    icon.update(mContext, data);
                    int iconIndex = mNotificationData.getIconIndex(n);
                    mNotificationIcons.addView(icon.view, iconIndex);
                }
            }
        }
    }

    /* private */ void performSetIconVisibility(IBinder key, boolean visible) {
        synchronized (mIconMap) {
            if (SPEW) {
                Log.d(TAG, "performSetIconVisibility key=" + key + " visible=" + visible);
            }
            StatusBarIcon icon = mIconMap.get(key);
            icon.view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    /* private */ void performRemoveIcon(IBinder key) {
        synchronized (this) {
            if (SPEW) {
                Log.d(TAG, "performRemoveIcon key=" + key);
            }
            StatusBarIcon icon = mIconMap.remove(key);
            mIconList.remove(icon);
            if (icon != null) {
                ViewGroup parent = (ViewGroup)icon.view.getParent();
                parent.removeView(icon.view);
                int slotIndex = getRightIconIndex(icon.mData.slot);
                if (slotIndex >= 0) {
                    mRightIcons[slotIndex] = null;
                }
            }
            StatusBarNotification notification = getNotification(key);
            if (notification != null) {
                removeNotificationView(notification);
                synchronized (mNotificationData) {
                    mNotificationData.remove(notification);
                }
                setAreThereNotifications();
            }
        }
    }

    int getIconNumberForView(View v) {
        synchronized (mIconMap) {
            StatusBarIcon icon = null;
            final int N = mIconList.size();
            for (int i=0; i<N; i++) {
                StatusBarIcon ic = mIconList.get(i);
                if (ic.view == v) {
                    icon = ic;
                    break;
                }
            }
            if (icon != null) {
                return icon.getNumber();
            } else {
                return -1;
            }
        }
    }


    StatusBarNotification getNotification(IBinder key) {
        synchronized (mNotificationData) {
            return mNotificationData.get(key);
        }
    }

    View.OnFocusChangeListener mFocusChangeListener = new View.OnFocusChangeListener() {
        public void onFocusChange(View v, boolean hasFocus) {
            // Because 'v' is a ViewGroup, all its children will be (un)selected
            // too, which allows marqueeing to work.
            v.setSelected(hasFocus);
        }
    };
    
    View makeNotificationView(StatusBarNotification notification, ViewGroup parent) {
        NotificationData n = notification.data;
        RemoteViews remoteViews = n.contentView;
        if (remoteViews == null) {
            return null;
        }

        // create the row view
        LayoutInflater inflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        View row = inflater.inflate(com.android.internal.R.layout.status_bar_latest_event, parent, false);

        // bind the click event to the content area
        ViewGroup content = (ViewGroup)row.findViewById(com.android.internal.R.id.content);
        content.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        content.setOnFocusChangeListener(mFocusChangeListener);
        PendingIntent contentIntent = n.contentIntent;
        if (contentIntent != null) {
            content.setOnClickListener(new Launcher(contentIntent, n.pkg, n.tag, n.id));
        }

        View child = null;
        Exception exception = null;
        try {
            child = remoteViews.apply(mContext, content);
        }
        catch (RuntimeException e) {
            exception = e;
        }
        if (child == null) {
            Log.e(TAG, "couldn't inflate view for package " + n.pkg, exception);
            return null;
        }
        content.addView(child);

        row.setDrawingCacheEnabled(true);

        notification.view = row;
        notification.contentView = child;

        return row;
    }

    void addNotificationView(StatusBarNotification notification) {
        if (notification.view != null) {
            throw new RuntimeException("Assertion failed: notification.view="
                    + notification.view);
        }

        LinearLayout parent = notification.data.ongoingEvent ? mOngoingItems : mLatestItems;

        View child = makeNotificationView(notification, parent);
        if (child == null) {
            return ;
        }

        int index = mNotificationData.getExpandedIndex(notification);
        parent.addView(child, index);
    }

    /**
     * Remove the old one and put the new one in its place.
     * @param notification the notification
     */
    void updateNotificationView(StatusBarNotification notification, NotificationData oldData) {
        NotificationData n = notification.data;
        if (oldData != null && n != null
                && n.contentView != null && oldData.contentView != null
                && n.contentView.getPackage() != null
                && oldData.contentView.getPackage() != null
                && oldData.contentView.getPackage().equals(n.contentView.getPackage())
                && oldData.contentView.getLayoutId() == n.contentView.getLayoutId()) {
            mNotificationData.update(notification);
            try {
                n.contentView.reapply(mContext, notification.contentView);

                // update the contentIntent
                ViewGroup content = (ViewGroup)notification.view.findViewById(
                        com.android.internal.R.id.content);
                PendingIntent contentIntent = n.contentIntent;
                if (contentIntent != null) {
                    content.setOnClickListener(new Launcher(contentIntent, n.pkg, n.tag, n.id));
                }
            }
            catch (RuntimeException e) {
                // It failed to add cleanly.  Log, and remove the view from the panel.
                Log.w(TAG, "couldn't reapply views for package " + n.contentView.getPackage(), e);
                removeNotificationView(notification);
            }
        } else {
            mNotificationData.update(notification);
            removeNotificationView(notification);
            addNotificationView(notification);
        }
        setAreThereNotifications();
    }

    void removeNotificationView(StatusBarNotification notification) {
        View v = notification.view;
        if (v != null) {
            ViewGroup parent = (ViewGroup)v.getParent();
            parent.removeView(v);
            notification.view = null;
        }
    }

    private void setAreThereNotifications() {
        boolean ongoing = mOngoingItems.getChildCount() != 0;
        boolean latest = mLatestItems.getChildCount() != 0;

        if (mNotificationData.hasClearableItems()) {
            mClearButton.setVisibility(View.VISIBLE);
        } else {
            mClearButton.setVisibility(View.INVISIBLE);
        }

        mOngoingTitle.setVisibility(ongoing ? View.VISIBLE : View.GONE);
        mLatestTitle.setVisibility(latest ? View.VISIBLE : View.GONE);

        if (ongoing || latest) {
            mNoNotificationsTitle.setVisibility(View.GONE);
        } else {
            mNoNotificationsTitle.setVisibility(View.VISIBLE);
        }
    }

    private void makeExpandedVisible() {
        if (SPEW) Log.d(TAG, "Make expanded visible: expanded visible=" + mExpandedVisible);
        if (mExpandedVisible) {
            return;
        }
        mExpandedVisible = true;
        panelSlightlyVisible(true);
        
        updateExpandedViewPos(EXPANDED_LEAVE_ALONE);
        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mExpandedView.requestFocus(View.FOCUS_FORWARD);
        mTrackingView.setVisibility(View.VISIBLE);
        
        if (!mTicking) {
            setDateViewVisibility(true, com.android.internal.R.anim.fade_in);
        }
    }
    
    void animateExpand() {
        if (SPEW) Log.d(TAG, "Animate expand: expanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        prepareTracking(0);
        performFling(0, 2000.0f, true);
    }
    
    void animateCollapse() {
        if (SPEW) {
            Log.d(TAG, "animateCollapse(): mExpanded=" + mExpanded
                    + " mExpandedVisible=" + mExpandedVisible
                    + " mAnimating=" + mAnimating
                    + " mAnimVel=" + mAnimVel);
        }
        
        if (!mExpandedVisible) {
            return;
        }

        if (mAnimating) {
            return;
        }

        int y = mDisplay.getHeight()-1;
        prepareTracking(y);
        performFling(y, -2000.0f, true);
    }
    
    void performExpand() {
        if (SPEW) Log.d(TAG, "performExpand: mExpanded=" + mExpanded);
        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return ;
        }
        if (mExpanded) {
            return;
        }

        // It seems strange to sometimes not expand...
        if (false) {
            synchronized (mNotificationData) {
                if (mNotificationData.size() == 0) {
                    return;
                }
            }
        }
        
        mExpanded = true;
        makeExpandedVisible();
        updateExpandedViewPos(EXPANDED_FULL_OPEN);

        if (false) postStartTracing();
    }

    void performCollapse() {
        if (SPEW) Log.d(TAG, "performCollapse: mExpanded=" + mExpanded
                + " mExpandedVisible=" + mExpandedVisible);
        
        if (!mExpandedVisible) {
            return;
        }
        mExpandedVisible = false;
        panelSlightlyVisible(false);
        mExpandedParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        mExpandedParams.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        mTrackingView.setVisibility(View.GONE);

        if ((mDisabled & StatusBarManager.DISABLE_NOTIFICATION_ICONS) == 0) {
            setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
        }
        setDateViewVisibility(false, com.android.internal.R.anim.fade_out);
        
        if (!mExpanded) {
            return;
        }
        mExpanded = false;
    }

    void doAnimation() {
        if (mAnimating) {
            if (SPEW) Log.d(TAG, "doAnimation");
            if (SPEW) Log.d(TAG, "doAnimation before mAnimY=" + mAnimY);
            incrementAnim();
            if (SPEW) Log.d(TAG, "doAnimation after  mAnimY=" + mAnimY);
            if (mAnimY >= mDisplay.getHeight()-1) {
                if (SPEW) Log.d(TAG, "Animation completed to expanded state.");
                mAnimating = false;
                updateExpandedViewPos(EXPANDED_FULL_OPEN);
                performExpand();
            }
            else if (mAnimY < mStatusBarView.getHeight()) {
                if (SPEW) Log.d(TAG, "Animation completed to collapsed state.");
                mAnimating = false;
                updateExpandedViewPos(0);
                performCollapse();
            }
            else {
                updateExpandedViewPos((int)mAnimY);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
            }
        }
    }

    void stopTracking() {
        mTracking = false;
        mVelocityTracker.recycle();
        mVelocityTracker = null;
    }

    void incrementAnim() {
        long now = SystemClock.uptimeMillis();
        float t = ((float)(now - mAnimLastTime)) / 1000;            // ms -> s
        final float y = mAnimY;
        final float v = mAnimVel;                                   // px/s
        final float a = mAnimAccel;                                 // px/s/s
        mAnimY = y + (v*t) + (0.5f*a*t*t);                          // px
        mAnimVel = v + (a*t);                                       // px/s
        mAnimLastTime = now;                                        // ms
        //Log.d(TAG, "y=" + y + " v=" + v + " a=" + a + " t=" + t + " mAnimY=" + mAnimY
        //        + " mAnimAccel=" + mAnimAccel);
    }

    void doRevealAnimation() {
        final int h = mCloseView.getHeight() + mStatusBarView.getHeight();
        if (mAnimatingReveal && mAnimating && mAnimY < h) {
            incrementAnim();
            if (mAnimY >= h) {
                mAnimY = h;
                updateExpandedViewPos((int)mAnimY);
            } else {
                updateExpandedViewPos((int)mAnimY);
                mCurAnimationTime += ANIM_FRAME_DURATION;
                mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE_REVEAL),
                        mCurAnimationTime);
            }
        }
    }
    
    void prepareTracking(int y) {
        mTracking = true;
        mVelocityTracker = VelocityTracker.obtain();
        boolean opening = !mExpanded;
        if (opening) {
            mAnimAccel = 2000.0f;
            mAnimVel = 200;
            mAnimY = mStatusBarView.getHeight();
            updateExpandedViewPos((int)mAnimY);
            mAnimating = true;
            mAnimatingReveal = true;
            mHandler.removeMessages(MSG_ANIMATE);
            mHandler.removeMessages(MSG_ANIMATE_REVEAL);
            long now = SystemClock.uptimeMillis();
            mAnimLastTime = now;
            mCurAnimationTime = now + ANIM_FRAME_DURATION;
            mAnimating = true;
            mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE_REVEAL),
                    mCurAnimationTime);
            makeExpandedVisible();
        } else {
            // it's open, close it?
            if (mAnimating) {
                mAnimating = false;
                mHandler.removeMessages(MSG_ANIMATE);
            }
            updateExpandedViewPos(y + mViewDelta);
        }
    }
    
    void performFling(int y, float vel, boolean always) {
        mAnimatingReveal = false;
        mDisplayHeight = mDisplay.getHeight();

        mAnimY = y;
        mAnimVel = vel;

        //Log.d(TAG, "starting with mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel);

        if (mExpanded) {
            if (!always && (
                    vel > 200.0f
                    || (y > (mDisplayHeight-25) && vel > -200.0f))) {
                // We are expanded, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the expanded position.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are expanded and are now going to animate away.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        } else {
            if (always || (
                    vel > 200.0f
                    || (y > (mDisplayHeight/2) && vel > -200.0f))) {
                // We are collapsed, and they moved enough to allow us to
                // expand.  Animate in the notifications.
                mAnimAccel = 2000.0f;
                if (vel < 0) {
                    mAnimVel = 0;
                }
            }
            else {
                // We are collapsed, but they didn't move sufficiently to cause
                // us to retract.  Animate back to the collapsed position.
                mAnimAccel = -2000.0f;
                if (vel > 0) {
                    mAnimVel = 0;
                }
            }
        }
        //Log.d(TAG, "mAnimY=" + mAnimY + " mAnimVel=" + mAnimVel
        //        + " mAnimAccel=" + mAnimAccel);

        long now = SystemClock.uptimeMillis();
        mAnimLastTime = now;
        mCurAnimationTime = now + ANIM_FRAME_DURATION;
        mAnimating = true;
        mHandler.removeMessages(MSG_ANIMATE);
        mHandler.removeMessages(MSG_ANIMATE_REVEAL);
        mHandler.sendMessageAtTime(mHandler.obtainMessage(MSG_ANIMATE), mCurAnimationTime);
        stopTracking();
    }
    
    boolean interceptTouchEvent(MotionEvent event) {
        if (SPEW) Log.d(TAG, "Touch: rawY=" + event.getRawY() + " event=" + event);

        if ((mDisabled & StatusBarManager.DISABLE_EXPAND) != 0) {
            return true;
        }
        
        final int statusBarSize = mStatusBarView.getHeight();
        final int hitSize = statusBarSize*2;
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            int y = (int)event.getRawY();

            if (!mExpanded) {
                mViewDelta = statusBarSize - y;
            } else {
                mTrackingView.getLocationOnScreen(mAbsPos);
                mViewDelta = mAbsPos[1] + mTrackingView.getHeight() - y;
            }
            if ((!mExpanded && y < hitSize) ||
                    (mExpanded && y > (mDisplay.getHeight()-hitSize))) {
                prepareTracking(y);
                mVelocityTracker.addMovement(event);
            }
        } else if (mTracking) {
            mVelocityTracker.addMovement(event);
            final int minY = statusBarSize + mCloseView.getHeight();
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                int y = (int)event.getRawY();
                if (mAnimatingReveal && y < minY) {
                    // nothing
                } else  {
                    mAnimatingReveal = false;
                    updateExpandedViewPos(y + mViewDelta);
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                mVelocityTracker.computeCurrentVelocity(1000);

                float yVel = mVelocityTracker.getYVelocity();
                boolean negative = yVel < 0;

                float xVel = mVelocityTracker.getXVelocity();
                if (xVel < 0) {
                    xVel = -xVel;
                }
                if (xVel > 150.0f) {
                    xVel = 150.0f; // limit how much we care about the x axis
                }

                float vel = (float)Math.hypot(yVel, xVel);
                if (negative) {
                    vel = -vel;
                }
                
                performFling((int)event.getRawY(), vel, false);
            }
            
        }
        return false;
    }

    private class Launcher implements View.OnClickListener {
        private PendingIntent mIntent;
        private String mPkg;
        private String mTag;
        private int mId;

        Launcher(PendingIntent intent, String pkg, String tag, int id) {
            mIntent = intent;
            mPkg = pkg;
            mTag = tag;
            mId = id;
        }

        public void onClick(View v) {
            try {
                // The intent we are sending is for the application, which
                // won't have permission to immediately start an activity after
                // the user switches to home.  We know it is safe to do at this
                // point, so make sure new activity switches are now allowed.
                ActivityManagerNative.getDefault().resumeAppSwitches();
            } catch (RemoteException e) {
            }
            int[] pos = new int[2];
            v.getLocationOnScreen(pos);
            Intent overlay = new Intent();
            overlay.setSourceBounds(
                    new Rect(pos[0], pos[1], pos[0]+v.getWidth(), pos[1]+v.getHeight()));
            try {
                mIntent.send(mContext, 0, overlay);
                mNotificationCallbacks.onNotificationClick(mPkg, mTag, mId);
            } catch (PendingIntent.CanceledException e) {
                // the stack trace isn't very helpful here.  Just log the exception message.
                Log.w(TAG, "Sending contentIntent failed: " + e);
            }
            deactivate();
        }
    }

    private class MyTicker extends Ticker {
        MyTicker(Context context, StatusBarView sb) {
            super(context, sb);
        }
        
        @Override
        void tickerStarting() {
            mTicking = true;
            mIcons.setVisibility(View.GONE);
            mTickerView.setVisibility(View.VISIBLE);
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_up_in, null));
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_up_out, null));
            if (mExpandedVisible) {
                setDateViewVisibility(false, com.android.internal.R.anim.push_up_out);
            }
        }

        @Override
        void tickerDone() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.push_down_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.push_down_out,
                        mTickingDoneListener));
            if (mExpandedVisible) {
                setDateViewVisibility(true, com.android.internal.R.anim.push_down_in);
            }
        }

        void tickerHalting() {
            mIcons.setVisibility(View.VISIBLE);
            mTickerView.setVisibility(View.GONE);
            mIcons.startAnimation(loadAnim(com.android.internal.R.anim.fade_in, null));
            mTickerView.startAnimation(loadAnim(com.android.internal.R.anim.fade_out,
                        mTickingDoneListener));
            if (mExpandedVisible) {
                setDateViewVisibility(true, com.android.internal.R.anim.fade_in);
            }
        }
    }

    Animation.AnimationListener mTickingDoneListener = new Animation.AnimationListener() {;
        public void onAnimationEnd(Animation animation) {
            mTicking = false;
        }
        public void onAnimationRepeat(Animation animation) {
        }
        public void onAnimationStart(Animation animation) {
        }
    };

    private Animation loadAnim(int id, Animation.AnimationListener listener) {
        Animation anim = AnimationUtils.loadAnimation(mContext, id);
        if (listener != null) {
            anim.setAnimationListener(listener);
        }
        return anim;
    }

    public String viewInfo(View v) {
        return "(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + " " + v.getWidth() + "x" + v.getHeight() + ")";
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump StatusBar from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        
        synchronized (mQueue) {
            pw.println("Current Status Bar state:");
            pw.println("  mExpanded=" + mExpanded
                    + ", mExpandedVisible=" + mExpandedVisible);
            pw.println("  mTicking=" + mTicking);
            pw.println("  mTracking=" + mTracking);
            pw.println("  mAnimating=" + mAnimating
                    + ", mAnimY=" + mAnimY + ", mAnimVel=" + mAnimVel
                    + ", mAnimAccel=" + mAnimAccel);
            pw.println("  mCurAnimationTime=" + mCurAnimationTime
                    + " mAnimLastTime=" + mAnimLastTime);
            pw.println("  mDisplayHeight=" + mDisplayHeight
                    + " mAnimatingReveal=" + mAnimatingReveal
                    + " mViewDelta=" + mViewDelta);
            pw.println("  mDisplayHeight=" + mDisplayHeight);
            final int N = mQueue.size();
            pw.println("  mQueue.size=" + N);
            for (int i=0; i<N; i++) {
                PendingOp op = mQueue.get(i);
                pw.println("    [" + i + "] key=" + op.key + " code=" + op.code + " visible="
                        + op.visible);
                pw.println("           iconData=" + op.iconData);
                pw.println("           notificationData=" + op.notificationData);
            }
            pw.println("  mExpandedParams: " + mExpandedParams);
            pw.println("  mExpandedView: " + viewInfo(mExpandedView));
            pw.println("  mExpandedDialog: " + mExpandedDialog);
            pw.println("  mTrackingParams: " + mTrackingParams);
            pw.println("  mTrackingView: " + viewInfo(mTrackingView));
            pw.println("  mOngoingTitle: " + viewInfo(mOngoingTitle));
            pw.println("  mOngoingItems: " + viewInfo(mOngoingItems));
            pw.println("  mLatestTitle: " + viewInfo(mLatestTitle));
            pw.println("  mLatestItems: " + viewInfo(mLatestItems));
            pw.println("  mNoNotificationsTitle: " + viewInfo(mNoNotificationsTitle));
            pw.println("  mCloseView: " + viewInfo(mCloseView));
            pw.println("  mTickerView: " + viewInfo(mTickerView));
            pw.println("  mScrollView: " + viewInfo(mScrollView)
                    + " scroll " + mScrollView.getScrollX() + "," + mScrollView.getScrollY());
            pw.println("mNotificationLinearLayout: " + viewInfo(mNotificationLinearLayout));
        }
        synchronized (mIconMap) {
            final int N = mIconMap.size();
            pw.println("  mIconMap.size=" + N);
            Set<IBinder> keys = mIconMap.keySet();
            int i=0;
            for (IBinder key: keys) {
                StatusBarIcon icon = mIconMap.get(key);
                pw.println("    [" + i + "] key=" + key);
                pw.println("           data=" + icon.mData);
                i++;
            }
        }
        synchronized (mNotificationData) {
            int N = mNotificationData.ongoingCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getOngoing(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
            }
            N = mNotificationData.latestCount();
            pw.println("  ongoingCount.size=" + N);
            for (int i=0; i<N; i++) {
                StatusBarNotification n = mNotificationData.getLatest(i);
                pw.println("    [" + i + "] key=" + n.key + " view=" + n.view);
                pw.println("           data=" + n.data);
            }
        }
        synchronized (mDisableRecords) {
            final int N = mDisableRecords.size();
            pw.println("  mDisableRecords.size=" + N
                    + " mDisabled=0x" + Integer.toHexString(mDisabled));
            for (int i=0; i<N; i++) {
                DisableRecord tok = mDisableRecords.get(i);
                pw.println("    [" + i + "] what=0x" + Integer.toHexString(tok.what)
                                + " pkg=" + tok.pkg + " token=" + tok.token);
            }
        }
        
        if (false) {
            pw.println("see the logcat for a dump of the views we have created.");
            // must happen on ui thread
            mHandler.post(new Runnable() {
                    public void run() {
                        mStatusBarView.getLocationOnScreen(mAbsPos);
                        Log.d(TAG, "mStatusBarView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mStatusBarView.getWidth() + "x"
                                + mStatusBarView.getHeight());
                        mStatusBarView.debug();

                        mExpandedView.getLocationOnScreen(mAbsPos);
                        Log.d(TAG, "mExpandedView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mExpandedView.getWidth() + "x"
                                + mExpandedView.getHeight());
                        mExpandedView.debug();

                        mTrackingView.getLocationOnScreen(mAbsPos);
                        Log.d(TAG, "mTrackingView: ----- (" + mAbsPos[0] + "," + mAbsPos[1]
                                + ") " + mTrackingView.getWidth() + "x"
                                + mTrackingView.getHeight());
                        mTrackingView.debug();
                    }
                });
        }
    }

    void onBarViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Tracking View --------------
        pixelFormat = PixelFormat.TRANSLUCENT;
        bg = mTrackingView.getBackground();
        if (bg != null) {
            pixelFormat = bg.getOpacity();
        }

        lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.FILL_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                pixelFormat);
//        lp.token = mStatusBarView.getWindowToken();
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("TrackingView");
        mTrackingParams = lp;

        WindowManagerImpl.getDefault().addView(mTrackingView, lp);
    }

    void onTrackingViewAttached() {
        WindowManager.LayoutParams lp;
        int pixelFormat;
        Drawable bg;

        /// ---------- Expanded View --------------
        pixelFormat = PixelFormat.TRANSLUCENT;
        bg = mExpandedView.getBackground();
        if (bg != null) {
            pixelFormat = bg.getOpacity();
            if (pixelFormat != PixelFormat.TRANSLUCENT) {
                // we want good-looking gradients, so we force a 8-bits per
                // pixel format.
                pixelFormat = PixelFormat.RGBX_8888;
            }
        }

        final int disph = mDisplay.getHeight();
        lp = mExpandedDialog.getWindow().getAttributes();
        lp.width = ViewGroup.LayoutParams.FILL_PARENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.x = 0;
        mTrackingPosition = lp.y = -disph; // sufficiently large negative
        lp.type = WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_DITHER
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.format = pixelFormat;
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.setTitle("StatusBarExpanded");
        mExpandedDialog.getWindow().setAttributes(lp);
        mExpandedDialog.getWindow().setFormat(pixelFormat);
        mExpandedParams = lp;

        mExpandedDialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        mExpandedDialog.setContentView(mExpandedView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT,
                                           ViewGroup.LayoutParams.WRAP_CONTENT));
        mExpandedDialog.show();
        FrameLayout hack = (FrameLayout)mExpandedView.getParent();
        hack.setForeground(null);
    }

    void setDateViewVisibility(boolean visible, int anim) {
        mDateView.setUpdates(visible);
        mDateView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mDateView.startAnimation(loadAnim(anim, null));
    }

    void setNotificationIconVisibility(boolean visible, int anim) {
        int old = mNotificationIcons.getVisibility();
        int v = visible ? View.VISIBLE : View.INVISIBLE;
        if (old != v) {
            mNotificationIcons.setVisibility(v);
            mNotificationIcons.startAnimation(loadAnim(anim, null));
        }
    }

    void updateExpandedViewPos(int expandedPosition) {
        if (SPEW) {
            Log.d(TAG, "updateExpandedViewPos before expandedPosition=" + expandedPosition
                    + " mTrackingParams.y=" + mTrackingParams.y
                    + " mTrackingPosition=" + mTrackingPosition);
        }

        int h = mStatusBarView.getHeight();
        int disph = mDisplay.getHeight();

        // If the expanded view is not visible, make sure they're still off screen.
        // Maybe the view was resized.
        if (!mExpandedVisible) {
            if (mTrackingView != null) {
                mTrackingPosition = mTrackingParams.y = -disph;
                WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);
            }
            if (mExpandedParams != null) {
                mExpandedParams.y = -disph;
                mExpandedDialog.getWindow().setAttributes(mExpandedParams);
            }
            return;
        }

        // tracking view...
        int pos;
        if (expandedPosition == EXPANDED_FULL_OPEN) {
            pos = h;
        }
        else if (expandedPosition == EXPANDED_LEAVE_ALONE) {
            pos = mTrackingPosition;
        }
        else {
            if (expandedPosition <= disph) {
                pos = expandedPosition;
            } else {
                pos = disph;
            }
            pos -= disph-h;
        }
        mTrackingPosition = mTrackingParams.y = pos;
        mTrackingParams.height = disph-h;
        WindowManagerImpl.getDefault().updateViewLayout(mTrackingView, mTrackingParams);

        mCloseView.getLocationInWindow(mCloseLocation);

        if (mExpandedParams != null) {
            mExpandedParams.y = pos + mTrackingView.getHeight()
                    - (mTrackingParams.height-mCloseLocation[1]) - mExpandedView.getHeight();
            int max = h;
            if (mExpandedParams.y > max) {
                mExpandedParams.y = max;
            }
            int min = mTrackingPosition;
            if (mExpandedParams.y < min) {
                mExpandedParams.y = min;
            }

            /*
            Log.d(TAG, "mTrackingPosition=" + mTrackingPosition
                    + " mTrackingView.height=" + mTrackingView.getHeight()
                    + " diff=" + (mTrackingPosition + mTrackingView.getHeight())
                    + " h=" + h);
            */
            panelSlightlyVisible((mTrackingPosition + mTrackingView.getHeight()) > h);
            mExpandedDialog.getWindow().setAttributes(mExpandedParams);
        }

        if (SPEW) {
            Log.d(TAG, "updateExpandedViewPos after  expandedPosition=" + expandedPosition
                    + " mTrackingParams.y=" + mTrackingParams.y
                    + " mTrackingPosition=" + mTrackingPosition
                    + " mExpandedParams.y=" + mExpandedParams.y);
        }
    }

    void updateAvailableHeight() {
        if (mExpandedView != null) {
            int disph = mDisplay.getHeight();
            int h = mStatusBarView.getHeight();
            int max = disph - (mCloseView.getHeight() + h);
            mExpandedView.setMaxHeight(max);
        }
    }

    /**
     * The LEDs are turned o)ff when the notification panel is shown, even just a little bit.
     * This was added last-minute and is inconsistent with the way the rest of the notifications
     * are handled, because the notification isn't really cancelled.  The lights are just
     * turned off.  If any other notifications happen, the lights will turn back on.  Steve says
     * this is what he wants. (see bug 1131461)
     */
    private boolean mPanelSlightlyVisible;
    void panelSlightlyVisible(boolean visible) {
        if (mPanelSlightlyVisible != visible) {
            mPanelSlightlyVisible = visible;
            if (visible) {
                // tell the notification manager to turn off the lights.
                mNotificationCallbacks.onPanelRevealed();
            }
        }
    }

    void performDisableActions(int net) {
        int old = mDisabled;
        int diff = net ^ old;
        mDisabled = net;

        // act accordingly
        if ((diff & StatusBarManager.DISABLE_EXPAND) != 0) {
            if ((net & StatusBarManager.DISABLE_EXPAND) != 0) {
                Log.d(TAG, "DISABLE_EXPAND: yes");
                mAnimating = false;
                updateExpandedViewPos(0);
                performCollapse();
            }
        }
        if ((diff & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
            if ((net & StatusBarManager.DISABLE_NOTIFICATION_ICONS) != 0) {
                Log.d(TAG, "DISABLE_NOTIFICATION_ICONS: yes");
                if (mTicking) {
                    mNotificationIcons.setVisibility(View.INVISIBLE);
                    mTicker.halt();
                } else {
                    setNotificationIconVisibility(false, com.android.internal.R.anim.fade_out);
                }
            } else {
                Log.d(TAG, "DISABLE_NOTIFICATION_ICONS: no");
                if (!mExpandedVisible) {
                    setNotificationIconVisibility(true, com.android.internal.R.anim.fade_in);
                }
            }
        }
    }

    private View.OnClickListener mClearButtonListener = new View.OnClickListener() {
        public void onClick(View v) {
            mNotificationCallbacks.onClearAll();
            addPendingOp(OP_EXPAND, null, false);
        }
    };

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)
                    || Intent.ACTION_SCREEN_OFF.equals(action)) {
                deactivate();
            }
            else if (Telephony.Intents.SPN_STRINGS_UPDATED_ACTION.equals(action)) {
                updateNetworkName(intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_SPN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_SPN),
                        intent.getBooleanExtra(Telephony.Intents.EXTRA_SHOW_PLMN, false),
                        intent.getStringExtra(Telephony.Intents.EXTRA_PLMN));
            }
            else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                updateResources();
            }
        }
    };

    void updateNetworkName(boolean showSpn, String spn, boolean showPlmn, String plmn) {
        if (false) {
            Log.d(TAG, "updateNetworkName showSpn=" + showSpn + " spn=" + spn
                    + " showPlmn=" + showPlmn + " plmn=" + plmn);
        }
        boolean something = false;
        if (showPlmn) {
            mPlmnLabel.setVisibility(View.VISIBLE);
            if (plmn != null) {
                mPlmnLabel.setText(plmn);
            } else {
                mPlmnLabel.setText(R.string.lockscreen_carrier_default);
            }
        } else {
            mPlmnLabel.setText("");
            mPlmnLabel.setVisibility(View.GONE);
        }
        if (showSpn && spn != null) {
            mSpnLabel.setText(spn);
            mSpnLabel.setVisibility(View.VISIBLE);
            something = true;
        } else {
            mSpnLabel.setText("");
            mSpnLabel.setVisibility(View.GONE);
        }
    }

    /**
     * Reload some of our resources when the configuration changes.
     * 
     * We don't reload everything when the configuration changes -- we probably
     * should, but getting that smooth is tough.  Someday we'll fix that.  In the
     * meantime, just update the things that we know change.
     */
    void updateResources() {
        mClearButton.setText(mContext.getText(R.string.status_bar_clear_all_button));
        mOngoingTitle.setText(mContext.getText(R.string.status_bar_ongoing_events_title));
        mLatestTitle.setText(mContext.getText(R.string.status_bar_latest_events_title));
        mNoNotificationsTitle.setText(mContext.getText(R.string.status_bar_no_notifications_title));
        if (false) Log.v(TAG, "updateResources");
    }

    //
    // tracing
    //

    void postStartTracing() {
        mHandler.postDelayed(mStartTracing, 3000);
    }

    void vibrate() {
        android.os.Vibrator vib = (android.os.Vibrator)mContext.getSystemService(
                Context.VIBRATOR_SERVICE);
        vib.vibrate(250);
    }

    Runnable mStartTracing = new Runnable() {
        public void run() {
            vibrate();
            SystemClock.sleep(250);
            Log.d(TAG, "startTracing");
            android.os.Debug.startMethodTracing("/data/statusbar-traces/trace");
            mHandler.postDelayed(mStopTracing, 10000);
        }
    };

    Runnable mStopTracing = new Runnable() {
        public void run() {
            android.os.Debug.stopMethodTracing();
            Log.d(TAG, "stopTracing");
            vibrate();
        }
    };
    
    class UninstallReceiver extends BroadcastReceiver {
        public UninstallReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
            filter.addDataScheme("package");
            mContext.registerReceiver(this, filter);
        }
        
        @Override
        public void onReceive(Context context, Intent intent) {
            ArrayList<StatusBarNotification> list = null;
            synchronized (StatusBarService.this) {
                Uri data = intent.getData();
                if (data != null) {
                    String pkg = data.getSchemeSpecificPart();
                    list = mNotificationData.notificationsForPackage(pkg);
                }
            }
            
            if (list != null) {
                final int N = list.size();
                for (int i=0; i<N; i++) {
                    removeIcon(list.get(i).key);
                }
            }
        }
    }
}
