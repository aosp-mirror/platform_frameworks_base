package android.os;

import android.os.Process;
import android.os.SELinux;
import android.test.AndroidTestCase;
import static junit.framework.Assert.assertEquals;

public class SELinuxTest extends AndroidTestCase {

    public void testgetFileCon() {
        if(SELinux.isSELinuxEnabled() == false)
            return;

        String ctx = SELinux.getFileContext("/system/bin/toolbox");
        assertEquals(ctx, "u:object_r:system_file:s0");
    }

    public void testgetCon() {
        if(SELinux.isSELinuxEnabled() == false)
            return;

        String mycon = SELinux.getContext();
        assertEquals(mycon, "u:r:untrusted_app:s0:c33");
    }

    public void testgetPidCon() {
        if(SELinux.isSELinuxEnabled() == false)
            return;

        String mycon = SELinux.getPidContext(Process.myPid());
        assertEquals(mycon, "u:r:untrusted_app:s0:c33");
    }

    public void testcheckSELinuxAccess() {
        if(SELinux.isSELinuxEnabled() == false)
            return;

        String mycon = SELinux.getContext();
        boolean ret;
        ret = SELinux.checkSELinuxAccess(mycon, mycon, "process", "fork");
        assertEquals(ret,"true");
        ret = SELinux.checkSELinuxAccess(mycon, mycon, "memprotect", "mmap_zero");
        assertEquals(ret,"true");
    }
}
