/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.dagger;

import static com.android.systemui.util.Utils.useCollapsedMediaInLandscape;
import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.privacy.OngoingPrivacyChip;
import com.android.systemui.qs.FooterActionsView;
import com.android.systemui.qs.QSContainerImpl;
import com.android.systemui.qs.QSFooter;
import com.android.systemui.qs.QSFooterView;
import com.android.systemui.qs.QSFooterViewController;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.qs.QuickQSPanel;
import com.android.systemui.qs.QuickStatusBarHeader;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.statusbar.phone.StatusIconContainer;

import javax.inject.Named;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module for {@link QSFragmentComponent}.
 */
@Module
public interface QSFragmentModule {
    String QS_FGS_MANAGER_FOOTER_VIEW = "qs_fgs_manager_footer";
    String QS_SECURITY_FOOTER_VIEW = "qs_security_footer";
    String QS_USING_MEDIA_PLAYER = "qs_using_media_player";
    String QS_USING_COLLAPSED_LANDSCAPE_MEDIA = "qs_using_collapsed_landscape_media";

    /**
     * Provide a context themed using the QS theme
     */
    @Provides
    @QSThemedContext
    static Context provideThemedContext(@RootView View view) {
        return view.getContext();
    }

    /** */
    @Provides
    @QSThemedContext
    static LayoutInflater provideThemedLayoutInflater(@QSThemedContext Context context) {
        return LayoutInflater.from(context);
    }

    /** */
    @Provides
    @RootView
    static View provideRootView(QSFragment qsFragment) {
        return qsFragment.getView();
    }

    /** */
    @Provides
    static QSPanel provideQSPanel(@RootView View view) {
        return view.findViewById(R.id.quick_settings_panel);
    }

    /** */
    @Provides
    static QSContainerImpl providesQSContainerImpl(@RootView View view) {
        return view.findViewById(R.id.quick_settings_container);
    }

    /** */
    @Binds
    QS bindQS(QSFragment qsFragment);

    /** */
    @Provides
    static QuickStatusBarHeader providesQuickStatusBarHeader(@RootView View view) {
        return view.findViewById(R.id.header);
    }

    /** */
    @Provides
    static QuickQSPanel providesQuickQSPanel(QuickStatusBarHeader quickStatusBarHeader) {
        return quickStatusBarHeader.findViewById(R.id.quick_qs_panel);
    }

    /** */
    @Provides
    static BatteryMeterView providesBatteryMeterView(QuickStatusBarHeader quickStatusBarHeader) {
        return quickStatusBarHeader.findViewById(R.id.batteryRemainingIcon);
    }

    /** */
    @Provides
    static QSFooterView providesQSFooterView(@RootView View view) {
        return view.findViewById(R.id.qs_footer);
    }

    /**
     * Provides a {@link FooterActionsView}.
     *
     * This will replace a ViewStub either in {@link QSFooterView} or in {@link QSContainerImpl}.
     */
    @Provides
    static FooterActionsView providesQSFooterActionsView(@RootView View view) {
        return view.findViewById(R.id.qs_footer_actions);
    }

    /** */
    @Provides
    @QSScope
    static QSFooter providesQSFooter(QSFooterViewController qsFooterViewController) {
        qsFooterViewController.init();
        return qsFooterViewController;
    }

    /** */
    @Provides
    @QSScope
    static QSCustomizer providesQSCutomizer(@RootView View view) {
        return view.findViewById(R.id.qs_customize);
    }

    /** */
    @Provides
    @QSScope
    @Named(QS_SECURITY_FOOTER_VIEW)
    static View providesQSSecurityFooterView(
            @QSThemedContext LayoutInflater layoutInflater,
            FooterActionsView footerActionsView
    ) {
        return layoutInflater.inflate(R.layout.quick_settings_security_footer, footerActionsView,
                false);
    }

    /** */
    @Provides
    @Named(QS_USING_MEDIA_PLAYER)
    static boolean providesQSUsingMediaPlayer(Context context) {
        return useQsMediaPlayer(context);
    }

    /** */
    @Provides
    @Named(QS_USING_COLLAPSED_LANDSCAPE_MEDIA)
    static boolean providesQSUsingCollapsedLandscapeMedia(Context context) {
        return useCollapsedMediaInLandscape(context.getResources());
    }

    /** */
    @Provides
    @QSScope
    static OngoingPrivacyChip providesPrivacyChip(QuickStatusBarHeader qsHeader) {
        return qsHeader.findViewById(R.id.privacy_chip);
    }

    /** */
    @Provides
    @QSScope
    static StatusIconContainer providesStatusIconContainer(QuickStatusBarHeader qsHeader) {
        return qsHeader.findViewById(R.id.statusIcons);
    }

    /** */
    @Provides
    @QSScope
    @Named(QS_FGS_MANAGER_FOOTER_VIEW)
    static View providesQSFgsManagerFooterView(
            @QSThemedContext LayoutInflater layoutInflater,
            FooterActionsView footerActionsView
    ) {
        return layoutInflater.inflate(R.layout.fgs_footer, footerActionsView, false);
    }
}