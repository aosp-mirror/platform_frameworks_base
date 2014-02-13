package com.android.onemedia;


import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import com.android.onemedia.playback.Renderer;

public class OnePlayerActivity extends Activity {
    private static final String TAG = "OnePlayerActivity";

    protected PlayerController mPlayer;

    private Button mStartButton;
    private Button mPlayButton;
    private TextView mStatusView;

    private EditText mContentText;
    private EditText mNextContentText;
    private CheckBox mHasVideo;

    private int mPlaybackState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_one_player);
        mPlayer = new PlayerController(this, OnePlayerService.getServiceIntent(this));


        mStartButton = (Button) findViewById(R.id.start_button);
        mPlayButton = (Button) findViewById(R.id.play_button);
        mStatusView = (TextView) findViewById(R.id.status);
        mContentText = (EditText) findViewById(R.id.content);
        mNextContentText = (EditText) findViewById(R.id.next_content);
        mHasVideo = (CheckBox) findViewById(R.id.has_video);

        mStartButton.setOnClickListener(mButtonListener);
        mPlayButton.setOnClickListener(mButtonListener);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public void onResume() {
        super.onResume();
        mPlayer.onResume();
        mPlayer.setListener(mListener);
    }

    @Override
    public void onPause() {
        mPlayer.setListener(null);
        mPlayer.onPause();
        super.onPause();
    }

    private void setControlsEnabled(boolean enabled) {
        mStartButton.setEnabled(enabled);
        mPlayButton.setEnabled(enabled);
    }

    private View.OnClickListener mButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.play_button:
                    Log.d(TAG, "Play button pressed, in state " + mPlaybackState);
                    if (mPlaybackState == Renderer.STATE_PAUSED
                            || mPlaybackState == Renderer.STATE_ENDED) {
                        mPlayer.play();
                    } else if (mPlaybackState == Renderer.STATE_PLAYING) {
                        mPlayer.pause();
                    }
                    break;
                case R.id.start_button:
                    Log.d(TAG, "Start button pressed, in state " + mPlaybackState);
                    mPlayer.setContent(mContentText.getText().toString());
                    break;
            }

        }
    };

    private PlayerController.Listener mListener = new PlayerController.Listener() {
        @Override
        public void onSessionStateChange(int state) {
            mPlaybackState = state;
            boolean enablePlay = false;
            switch (mPlaybackState) {
                case Renderer.STATE_PLAYING:
                    mStatusView.setText("playing");
                    mPlayButton.setText("Pause");
                    enablePlay = true;
                    break;
                case Renderer.STATE_PAUSED:
                    mStatusView.setText("paused");
                    mPlayButton.setText("Play");
                    enablePlay = true;
                    break;
                case Renderer.STATE_ENDED:
                    mStatusView.setText("ended");
                    mPlayButton.setText("Play");
                    enablePlay = true;
                    break;
                case Renderer.STATE_ERROR:
                    mStatusView.setText("error");
                    break;
                case Renderer.STATE_PREPARING:
                    mStatusView.setText("preparing");
                    break;
                case Renderer.STATE_READY:
                    mStatusView.setText("ready");
                    break;
                case Renderer.STATE_STOPPED:
                    mStatusView.setText("stopped");
                    break;
            }
            mPlayButton.setEnabled(enablePlay);
        }

        @Override
        public void onPlayerStateChange(int state) {
            if (state == PlayerController.STATE_DISCONNECTED) {
                setControlsEnabled(false);
            } else if (state == PlayerController.STATE_CONNECTED) {
                setControlsEnabled(true);
            }
        }
    };
}
