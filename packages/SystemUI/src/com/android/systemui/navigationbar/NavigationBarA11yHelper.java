package com.android.systemui.navigationbar;

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;

import android.view.accessibility.AccessibilityManager;

import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Extracts shared elements of a11y necessary between navbar and taskbar delegate
 */
@SysUISingleton
public final class NavigationBarA11yHelper implements
        AccessibilityButtonModeObserver.ModeChangedListener {
    private int mA11yBtnMode;
    private final AccessibilityManager mAccessibilityManager;
    private final List<NavA11yEventListener> mA11yEventListeners = new ArrayList<>();

    @Inject
    public NavigationBarA11yHelper(AccessibilityManager accessibilityManager,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            AccessibilityButtonModeObserver accessibilityButtonModeObserver) {
        mAccessibilityManager = accessibilityManager;
        mA11yBtnMode = accessibilityButtonModeObserver.getCurrentAccessibilityButtonMode();
        accessibilityManagerWrapper.addCallback(
                accessibilityManager1 -> NavigationBarA11yHelper.this.dispatchEventUpdate());
        accessibilityButtonModeObserver.addListener(this);
    }

    public void registerA11yEventListener(NavA11yEventListener listener) {
        mA11yEventListeners.add(listener);
    }

    public void removeA11yEventListener(NavA11yEventListener listener) {
        mA11yEventListeners.remove(listener);
    }

    private void dispatchEventUpdate() {
        for (NavA11yEventListener listener : mA11yEventListeners) {
            listener.updateAccessibilityServicesState();
        }
    }

    @Override
    public void onAccessibilityButtonModeChanged(int mode) {
        mA11yBtnMode = mode;
        dispatchEventUpdate();
    }

    /**
     * See {@link QuickStepContract#SYSUI_STATE_A11Y_BUTTON_CLICKABLE} and
     * {@link QuickStepContract#SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE}
     *
     * @return the a11y button clickable and long_clickable states, or 0 if there is no
     *         a11y button in the navbar
     */
    public int getA11yButtonState() {
        // AccessibilityManagerService resolves services for the current user since the local
        // AccessibilityManager is created from a Context with the INTERACT_ACROSS_USERS permission
        final List<String> a11yButtonTargets =
                mAccessibilityManager.getAccessibilityShortcutTargets(
                        AccessibilityManager.ACCESSIBILITY_BUTTON);
        final int requestingServices = a11yButtonTargets.size();

        // If accessibility button is floating menu mode, click and long click state should be
        // disabled.
        if (mA11yBtnMode == ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU) {
            return 0;
        }

        return (requestingServices >= 1 ? SYSUI_STATE_A11Y_BUTTON_CLICKABLE : 0)
                | (requestingServices >= 2 ? SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE : 0);
    }

    public interface NavA11yEventListener {
        void updateAccessibilityServicesState();
    }
}
