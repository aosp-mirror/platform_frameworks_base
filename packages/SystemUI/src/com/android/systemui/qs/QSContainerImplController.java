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

import javax.inject.Inject;

public class QSContainerImplController {
    private final QSContainerImpl mView;
    private final QuickStatusBarHeaderController mQuickStatusBarHeaderController;

    private QSContainerImplController(QSContainerImpl view,
            QuickStatusBarHeaderController.Builder quickStatusBarHeaderControllerBuilder) {
        mView = view;
        mQuickStatusBarHeaderController = quickStatusBarHeaderControllerBuilder
                .setQuickStatusBarHeader(mView.findViewById(R.id.header)).build();
    }

    public void setListening(boolean listening) {
        mQuickStatusBarHeaderController.setListening(listening);
    }

    public static class Builder {
        private final QuickStatusBarHeaderController.Builder mQuickStatusBarHeaderControllerBuilder;
        private QSContainerImpl mView;

        @Inject
        public Builder(
                QuickStatusBarHeaderController.Builder quickStatusBarHeaderControllerBuilder) {
            mQuickStatusBarHeaderControllerBuilder = quickStatusBarHeaderControllerBuilder;
        }

        public Builder setQSContainerImpl(QSContainerImpl view) {
            mView = view;
            return this;
        }

        public QSContainerImplController build() {
            return new QSContainerImplController(mView, mQuickStatusBarHeaderControllerBuilder);
        }
    }
}
