package com.google.android.systemui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import com.android.systemui.R;
import com.android.systemui.assist.ui.DefaultUiController;

public class GoogleDefaultUiController extends DefaultUiController {
    public GoogleDefaultUiController(Context context) {
        super(context);
        context.getResources();
        setGoogleAssistant(false);
        mInvocationLightsView = (AssistantInvocationLightsView) LayoutInflater.from(context).inflate(R.layout.invocation_lights, (ViewGroup) mRoot, false);
        mRoot.addView(mInvocationLightsView);
    }

    public void setGoogleAssistant(boolean z) {
        ((AssistantInvocationLightsView) mInvocationLightsView).setGoogleAssistant(z);
    }
}
