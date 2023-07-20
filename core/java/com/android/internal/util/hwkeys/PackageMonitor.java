/*
 * Copyright (C) 2015-2016 The TeamEos Project
 *
 * Author: Randall Rushing <bigrushdog@teameos.org>
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
 *
 * A simplified package monitor class with easy-to-use callbacks when a
 * state changes. We register the receiver on background thread but post events
 * to the UI thread
 */

package com.android.internal.util.hwkeys;

import android.content.Context;
import android.os.Handler;
import android.os.Message;

import java.util.ArrayList;

public class PackageMonitor extends com.android.internal.content.PackageMonitor {
    private static final int MSG_PACKAGE_ADDED = 1;
    private static final int MSG_PACKAGE_REMOVED = 2;
    private static final int MSG_PACKAGE_CHANGED = 3;

    public static enum PackageState {
        PACKAGE_REMOVED,
        PACKAGE_ADDED,
        PACKAGE_CHANGED
    }

    public interface PackageChangedListener {
        public void onPackageChanged(String pkg, PackageState state);
    }

    private Handler mHandler;

    private ArrayList<PackageChangedListener> mListeners = new ArrayList<PackageChangedListener>();

    public void register(Context context, Handler foreground) {
        register(context, null, null, true);

        mHandler = new Handler(foreground.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_PACKAGE_ADDED:
                        for (PackageChangedListener listener : mListeners) {
                            listener.onPackageChanged((String) msg.obj, PackageState.PACKAGE_ADDED);
                        }
                        break;
                    case MSG_PACKAGE_REMOVED:
                        for (PackageChangedListener listener : mListeners) {
                            listener.onPackageChanged((String) msg.obj,
                                    PackageState.PACKAGE_REMOVED);
                        }
                        break;
                    case MSG_PACKAGE_CHANGED:
                        for (PackageChangedListener listener : mListeners) {
                            listener.onPackageChanged((String) msg.obj,
                                    PackageState.PACKAGE_CHANGED);
                        }
                        break;
                }
            }
        };
    }

	public void addListener(PackageChangedListener listener) {
		if (listener != null) {
			mListeners.add(listener);
		}
	}

	public void removeListener(PackageChangedListener listener) {
		if (listener != null) {
			mListeners.remove(listener);
		}
	}

    /**
     * Called when a package is really added (and not replaced).
     */
    public void onPackageAdded(String packageName, int uid) {
        Message msg = mHandler.obtainMessage(MSG_PACKAGE_ADDED, packageName);
        mHandler.sendMessage(msg);
    }

    /**
     * Called when a package is really removed (and not replaced).
     */
    public void onPackageRemoved(String packageName, int uid) {
        Message msg = mHandler.obtainMessage(MSG_PACKAGE_REMOVED, packageName);
        mHandler.sendMessage(msg);
    }

    /**
     * Direct reflection of {@link Intent#ACTION_PACKAGE_CHANGED Intent.ACTION_PACKAGE_CHANGED}
     * being received, informing you of changes to the enabled/disabled state of components in a
     * package and/or of the overall package.
     * 
     * @param packageName The name of the package that is changing.
     * @param uid The user ID the package runs under.
     * @param components Any components in the package that are changing. If the overall package is
     *            changing, this will contain an entry of the package name itself.
     * @return Return true to indicate you care about this change, which will result in
     *         {@link #onSomePackagesChanged()} being called later. If you return false, no further
     *         callbacks will happen about this change. The default implementation returns true if
     *         this is a change to the entire package.
     */
    public boolean onPackageChanged(String packageName, int uid, String[] components) {
        Message msg = mHandler.obtainMessage(MSG_PACKAGE_CHANGED, packageName);
        mHandler.sendMessage(msg);
        return super.onPackageChanged(packageName, uid, components);
    }
}
