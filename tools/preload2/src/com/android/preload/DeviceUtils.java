/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.preload;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.AndroidDebugBridge.IDeviceChangeListener;
import com.android.preload.classdataretrieval.hprof.Hprof;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.SyncException;
import com.android.ddmlib.TimeoutException;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Helper class for some device routines.
 */
public class DeviceUtils {

  // Locations
  private static final String PRELOADED_CLASSES_FILE = "/etc/preloaded-classes";
  // Shell commands
  private static final String CREATE_EMPTY_PRELOADED_CMD = "touch " + PRELOADED_CLASSES_FILE;
  private static final String DELETE_CACHE_CMD = "rm /data/dalvik-cache/*/*boot.art";
  private static final String DELETE_PRELOADED_CMD = "rm " + PRELOADED_CLASSES_FILE;
  private static final String READ_PRELOADED_CMD = "cat " + PRELOADED_CLASSES_FILE;
  private static final String START_SHELL_CMD = "start";
  private static final String STOP_SHELL_CMD = "stop";
  private static final String REMOUNT_SYSTEM_CMD = "mount -o rw,remount /system";
  private static final String UNSET_BOOTCOMPLETE_CMD = "setprop dev.bootcomplete \"0\"";

  public static void init(int debugPort) {
    DdmPreferences.setSelectedDebugPort(debugPort);

    Hprof.init();

    AndroidDebugBridge.init(true);

    AndroidDebugBridge.createBridge();
  }

  /**
   * Run a command in the shell on the device.
   */
  public static void doShell(IDevice device, String cmdline, long timeout, TimeUnit unit) {
    doShell(device, cmdline, new NullShellOutputReceiver(), timeout, unit);
  }

  /**
   * Run a command in the shell on the device. Collects and returns the console output.
   */
  public static String doShellReturnString(IDevice device, String cmdline, long timeout,
      TimeUnit unit) {
    CollectStringShellOutputReceiver rec = new CollectStringShellOutputReceiver();
    doShell(device, cmdline, rec, timeout, unit);
    return rec.toString();
  }

  /**
   * Run a command in the shell on the device, directing all output to the given receiver.
   */
  public static void doShell(IDevice device, String cmdline, IShellOutputReceiver receiver,
      long timeout, TimeUnit unit) {
    try {
      device.executeShellCommand(cmdline, receiver, timeout, unit);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Run am start on the device.
   */
  public static void doAMStart(IDevice device, String name, String activity) {
    doShell(device, "am start -n " + name + " /." + activity, 30, TimeUnit.SECONDS);
  }

  /**
   * Find the device with the given serial. Give up after the given timeout (in milliseconds).
   */
  public static IDevice findDevice(String serial, int timeout) {
    WaitForDevice wfd = new WaitForDevice(serial, timeout);
    return wfd.get();
  }

  /**
   * Get all devices ddms knows about. Wait at most for the given timeout.
   */
  public static IDevice[] findDevices(int timeout) {
    WaitForDevice wfd = new WaitForDevice(null, timeout);
    wfd.get();
    return AndroidDebugBridge.getBridge().getDevices();
  }

  /**
   * Return the build type of the given device. This is the value of the "ro.build.type"
   * system property.
   */
  public static String getBuildType(IDevice device) {
    try {
      Future<String> buildType = device.getSystemProperty("ro.build.type");
      return buildType.get(500, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
    }
    return null;
  }

  /**
   * Check whether the given device has a pre-optimized boot image. More precisely, checks
   * whether /system/framework/ * /boot.art exists.
   */
  public static boolean hasPrebuiltBootImage(IDevice device) {
    String ret =
        doShellReturnString(device, "ls /system/framework/*/boot.art", 500, TimeUnit.MILLISECONDS);

    return !ret.contains("No such file or directory");
  }

    /**
     * Write over the preloaded-classes file with an empty or existing file and regenerate the boot
     * image as necessary.
     *
     * @param device
     * @param pcFile
     * @param bootTimeout
     * @throws AdbCommandRejectedException
     * @throws IOException
     * @throws TimeoutException
     * @throws SyncException
     * @return true if successfully overwritten, false otherwise
     */
    public static boolean overwritePreloaded(IDevice device, File pcFile, long bootTimeout)
            throws AdbCommandRejectedException, IOException, TimeoutException, SyncException {
        boolean writeEmpty = (pcFile == null);
        if (writeEmpty) {
            // Check if the preloaded-classes file is already empty.
            String oldContent =
                    doShellReturnString(device, READ_PRELOADED_CMD, 1, TimeUnit.SECONDS);
            if (oldContent.trim().equals("")) {
                System.out.println("Preloaded-classes already empty.");
                return true;
            }
        }

        // Stop the system server etc.
        doShell(device, STOP_SHELL_CMD, 1, TimeUnit.SECONDS);
        // Remount the read-only system partition
        doShell(device, REMOUNT_SYSTEM_CMD, 1, TimeUnit.SECONDS);
        // Delete the preloaded-classes file
        doShell(device, DELETE_PRELOADED_CMD, 1, TimeUnit.SECONDS);
        // Delete the dalvik cache files
        doShell(device, DELETE_CACHE_CMD, 1, TimeUnit.SECONDS);
        if (writeEmpty) {
            // Write an empty preloaded-classes file
            doShell(device, CREATE_EMPTY_PRELOADED_CMD, 500, TimeUnit.MILLISECONDS);
        } else {
            // Push the new preloaded-classes file
            device.pushFile(pcFile.getAbsolutePath(), PRELOADED_CLASSES_FILE);
        }
        // Manually reset the boot complete flag
        doShell(device, UNSET_BOOTCOMPLETE_CMD, 1, TimeUnit.SECONDS);
        // Restart system server on the device
        doShell(device, START_SHELL_CMD, 1, TimeUnit.SECONDS);
        // Wait for the boot complete flag and return the outcome.
        return waitForBootComplete(device, bootTimeout);
  }

  private static boolean waitForBootComplete(IDevice device, long timeout) {
    // Do a loop checking each second whether bootcomplete. Wait for at most the given
    // threshold.
    Date startDate = new Date();
    for (;;) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        // Ignore spurious wakeup.
      }
      // Check whether bootcomplete.
      String ret =
          doShellReturnString(device, "getprop dev.bootcomplete", 500, TimeUnit.MILLISECONDS);
      if (ret.trim().equals("1")) {
        break;
      }
      System.out.println("Still not booted: " + ret);

      // Check whether we timed out. This is a simplistic check that doesn't take into account
      // things like switches in time.
      Date endDate = new Date();
      long seconds =
          TimeUnit.SECONDS.convert(endDate.getTime() - startDate.getTime(), TimeUnit.MILLISECONDS);
      if (seconds > timeout) {
        return false;
      }
    }

    return true;
  }

  /**
   * Enable method-tracing on device. The system should be restarted after this.
   */
  public static void enableTracing(IDevice device) {
    // Disable selinux.
    doShell(device, "setenforce 0", 100, TimeUnit.MILLISECONDS);

    // Make the profile directory world-writable.
    doShell(device, "chmod 777 /data/dalvik-cache/profiles", 100, TimeUnit.MILLISECONDS);

    // Enable streaming method tracing with a small 1K buffer.
    doShell(device, "setprop dalvik.vm.method-trace true", 100, TimeUnit.MILLISECONDS);
    doShell(device, "setprop dalvik.vm.method-trace-file "
                    + "/data/dalvik-cache/profiles/zygote.trace.bin", 100, TimeUnit.MILLISECONDS);
    doShell(device, "setprop dalvik.vm.method-trace-file-siz 1024", 100, TimeUnit.MILLISECONDS);
    doShell(device, "setprop dalvik.vm.method-trace-stream true", 100, TimeUnit.MILLISECONDS);
  }

  private static class NullShellOutputReceiver implements IShellOutputReceiver {
    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public void flush() {}

    @Override
    public void addOutput(byte[] arg0, int arg1, int arg2) {}
  }

  private static class CollectStringShellOutputReceiver implements IShellOutputReceiver {

    private StringBuilder builder = new StringBuilder();

    @Override
    public String toString() {
      String ret = builder.toString();
      // Strip trailing newlines. They are especially ugly because adb uses DOS line endings.
      while (ret.endsWith("\r") || ret.endsWith("\n")) {
        ret = ret.substring(0, ret.length() - 1);
      }
      return ret;
    }

    @Override
    public void addOutput(byte[] arg0, int arg1, int arg2) {
      builder.append(new String(arg0, arg1, arg2));
    }

    @Override
    public void flush() {}

    @Override
    public boolean isCancelled() {
      return false;
    }
  }

  private static class WaitForDevice {

    private String serial;
    private long timeout;
    private IDevice device;

    public WaitForDevice(String serial, long timeout) {
      this.serial = serial;
      this.timeout = timeout;
      device = null;
    }

    public IDevice get() {
      if (device == null) {
          WaitForDeviceListener wfdl = new WaitForDeviceListener(serial);
          synchronized (wfdl) {
              AndroidDebugBridge.addDeviceChangeListener(wfdl);

              // Check whether we already know about this device.
              IDevice[] devices = AndroidDebugBridge.getBridge().getDevices();
              if (serial != null) {
                  for (IDevice d : devices) {
                      if (serial.equals(d.getSerialNumber())) {
                          // Only accept if there are clients already. Else wait for the callback informing
                          // us that we now have clients.
                          if (d.hasClients()) {
                              device = d;
                          }

                          break;
                      }
                  }
              } else {
                  if (devices.length > 0) {
                      device = devices[0];
                  }
              }

              if (device == null) {
                  try {
                      wfdl.wait(timeout);
                  } catch (InterruptedException e) {
                      // Ignore spurious wakeups.
                  }
                  device = wfdl.getDevice();
              }

              AndroidDebugBridge.removeDeviceChangeListener(wfdl);
          }
      }

      if (device != null) {
          // Wait for clients.
          WaitForClientsListener wfcl = new WaitForClientsListener(device);
          synchronized (wfcl) {
              AndroidDebugBridge.addDeviceChangeListener(wfcl);

              if (!device.hasClients()) {
                  try {
                      wfcl.wait(timeout);
                  } catch (InterruptedException e) {
                      // Ignore spurious wakeups.
                  }
              }

              AndroidDebugBridge.removeDeviceChangeListener(wfcl);
          }
      }

      return device;
    }

    private static class WaitForDeviceListener implements IDeviceChangeListener {

        private String serial;
        private IDevice device;

        public WaitForDeviceListener(String serial) {
            this.serial = serial;
        }

        public IDevice getDevice() {
            return device;
        }

        @Override
        public void deviceChanged(IDevice arg0, int arg1) {
            // We may get a device changed instead of connected. Handle like a connection.
            deviceConnected(arg0);
        }

        @Override
        public void deviceConnected(IDevice arg0) {
            if (device != null) {
                // Ignore updates.
                return;
            }

            if (serial == null || serial.equals(arg0.getSerialNumber())) {
                device = arg0;
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        @Override
        public void deviceDisconnected(IDevice arg0) {
            // Ignore disconnects.
        }

    }

    private static class WaitForClientsListener implements IDeviceChangeListener {

        private IDevice myDevice;

        public WaitForClientsListener(IDevice myDevice) {
            this.myDevice = myDevice;
        }

        @Override
        public void deviceChanged(IDevice arg0, int arg1) {
            if (arg0 == myDevice && (arg1 & IDevice.CHANGE_CLIENT_LIST) != 0) {
                // Got a client list, done here.
                synchronized (this) {
                    notifyAll();
                }
            }
        }

        @Override
        public void deviceConnected(IDevice arg0) {
        }

        @Override
        public void deviceDisconnected(IDevice arg0) {
        }

    }
  }

}
