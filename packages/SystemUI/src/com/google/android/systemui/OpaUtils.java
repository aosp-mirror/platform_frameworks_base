package com.google.android.systemui;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import com.android.internal.app.AssistUtils;
import com.android.keyguard.KeyguardUpdateMonitor;

public class OpaUtils {

    public static final String OPA_COMPONENT_NAME = "com.google.android.googlequicksearchbox/com.google.android.voiceinteraction.GsaVoiceInteractionService";

    private static boolean hasSetupCompleted(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0) != 0;
    }

    private static boolean isGsaCurrentAssistant(Context context) {
        AssistUtils assistUtils = new AssistUtils(context);
        ComponentName assistant = assistUtils.getAssistComponentForUser(
                KeyguardUpdateMonitor.getCurrentUser());
        return assistant != null
                && OPA_COMPONENT_NAME.equals(assistant.flattenToString());
    }

    public static boolean shouldEnable(Context context) {
        return hasSetupCompleted(context) && isGsaCurrentAssistant(context);
    }
}
