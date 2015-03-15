package android.net.wifi.hotspot2;

import android.net.wifi.anqp.ANQPElement;

import java.util.Collections;
import java.util.List;

/**
 * Created by jannq on 1/21/15.
 */
public class ANQPData {
    private final List<ANQPElement> mANQPElements;
    private final long mCtime;
    private volatile long mAtime;

    public ANQPData( List<ANQPElement> ANQPElements ) {
        mANQPElements = Collections.unmodifiableList( ANQPElements );
        mCtime = System.currentTimeMillis();
        mAtime = mCtime;
    }

    public List<ANQPElement> getANQPElements() {
        mAtime = System.currentTimeMillis();
        return mANQPElements;
    }
}
