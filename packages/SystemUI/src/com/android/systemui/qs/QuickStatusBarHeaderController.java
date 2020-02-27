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

package com.android.systemui.qs;

import com.android.systemui.R;
import com.android.systemui.qs.carrier.QSCarrierGroupController;

import javax.inject.Inject;

public class QuickStatusBarHeaderController {
    private final QuickStatusBarHeader mView;
    private final QSCarrierGroupController mQSCarrierGroupController;

    private QuickStatusBarHeaderController(QuickStatusBarHeader view,
            QSCarrierGroupController.Builder qsCarrierGroupControllerBuilder) {
        mView = view;
        mQSCarrierGroupController = qsCarrierGroupControllerBuilder
                .setQSCarrierGroup(mView.findViewById(R.id.carrier_group))
                .build();
    }

    public void setListening(boolean listening) {
        mQSCarrierGroupController.setListening(listening);
        // TODO: move mView.setListening logic into here.
        mView.setListening(listening);
    }


    public static class Builder {
        private final QSCarrierGroupController.Builder mQSCarrierGroupControllerBuilder;
        private QuickStatusBarHeader mView;

        @Inject
        public Builder(QSCarrierGroupController.Builder qsCarrierGroupControllerBuilder) {
            mQSCarrierGroupControllerBuilder = qsCarrierGroupControllerBuilder;
        }

        public Builder setQuickStatusBarHeader(QuickStatusBarHeader view) {
            mView = view;
            return this;
        }

        public QuickStatusBarHeaderController build() {
            return new QuickStatusBarHeaderController(mView, mQSCarrierGroupControllerBuilder);
        }
    }
}
