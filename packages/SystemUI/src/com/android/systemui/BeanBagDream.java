package com.android.systemui;

import android.service.dreams.Dream;

import com.android.systemui.BeanBag.Board;

public class BeanBagDream extends Dream {

    private Board mBoard;

    @Override
    public void onStart() {
        super.onStart();
        setInteractive(true);
        mBoard = new Board(this, null);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setContentView(mBoard);
        lightsOut();
        mBoard.startAnimation();
    }

    @Override
    public void finish() {
        mBoard.stopAnimation();
        super.finish();
    }
}
