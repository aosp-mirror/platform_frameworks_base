package android.app.ondeviceintelligence;

import android.os.PersistableBundle;

/**
* Signal to provide to the remote implementation in context of a given request or
* feature specific event.
*
* @hide
*/

oneway interface IProcessingSignal {
    void sendSignal(in PersistableBundle actionParams) = 2;
}
