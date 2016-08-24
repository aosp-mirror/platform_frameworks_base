package android.animation;

import com.android.frameworks.coretests.R;

import android.app.Activity;
import android.os.Bundle;

public class AnimatorSetActivity extends Activity {
    @Override
    public void onCreate(Bundle savedBundleInstance) {
        super.onCreate(savedBundleInstance);
        setContentView(R.layout.animator_set_squares);
    }
}
