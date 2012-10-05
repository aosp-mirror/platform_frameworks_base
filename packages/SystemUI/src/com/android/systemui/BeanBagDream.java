package com.android.systemui;

import android.service.dreams.DreamService;

import com.android.systemui.BeanBag.Board;

public class BeanBagDream extends DreamService {

    private Board mBoard;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        mBoard = new Board(this, null);
        setContentView(mBoard);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        mBoard.startAnimation();
    }

    @Override
    public void onDreamingStopped() {
        mBoard.stopAnimation();
        super.onDreamingStopped();
    }
}
