/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.view.View;

import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** Controller for {@link OperatorNameView}. */
public class OperatorNameViewController extends ViewController<OperatorNameView> {
    private final DarkIconDispatcher mDarkIconDispatcher;
    private final NetworkController mNetworkController;

    private OperatorNameViewController(OperatorNameView view,
            DarkIconDispatcher darkIconDispatcher,
            NetworkController networkController) {
        super(view);
        mDarkIconDispatcher = darkIconDispatcher;
        mNetworkController = networkController;
    }

    @Override
    protected void onViewAttached() {
        mDarkIconDispatcher.addDarkReceiver(mDarkReceiver);
        mNetworkController.addCallback(mSignalCallback);
    }

    @Override
    protected void onViewDetached() {
        mDarkIconDispatcher.removeDarkReceiver(mDarkReceiver);
        mNetworkController.addCallback(mSignalCallback);
    }

    /** Factory for constructing an {@link OperatorNameViewController}. */
    public static class Factory {
        private final DarkIconDispatcher mDarkIconDispatcher;
        private final NetworkController mNetworkController;

        @Inject
        public Factory(DarkIconDispatcher darkIconDispatcher, NetworkController networkController) {
            mDarkIconDispatcher = darkIconDispatcher;
            mNetworkController = networkController;
        }

        /** Create an {@link OperatorNameViewController}. */
        public OperatorNameViewController create(OperatorNameView view) {
            return new OperatorNameViewController(view, mDarkIconDispatcher, mNetworkController);
        }
    }

    /**
     * Needed because of how {@link CollapsedStatusBarFragment} works.
     *
     * Ideally this can be done internally.
     **/
    public View getView() {
        return mView;
    }

    private final DarkIconDispatcher.DarkReceiver mDarkReceiver =
            (area, darkIntensity, tint) ->
                    mView.setTextColor(DarkIconDispatcher.getTint(area, mView, tint));
    private final NetworkController.SignalCallback mSignalCallback =
            new NetworkController.SignalCallback() {
        @Override
        public void setIsAirplaneMode(NetworkController.IconState icon) {
            mView.update();
        }
    };
}
