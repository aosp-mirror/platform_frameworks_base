package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.PrintWriter;
import java.util.ArrayList;

interface TransitionTracer {
    void logSentTransition(Transition transition, ArrayList<Transition.ChangeInfo> targets);
    void logFinishedTransition(Transition transition);
    void logAbortedTransition(Transition transition);
    void logRemovingStartingWindow(@NonNull StartingData startingData);

    void startTrace(@Nullable PrintWriter pw);
    void stopTrace(@Nullable PrintWriter pw);
    boolean isTracing();
    void saveForBugreport(@Nullable PrintWriter pw);
}
