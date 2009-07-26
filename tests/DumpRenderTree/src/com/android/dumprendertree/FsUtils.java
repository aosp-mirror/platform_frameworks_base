package com.android.dumprendertree;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;

public class FsUtils {

    private static final String LOGTAG = "FsUtils";
    private FsUtils() {
        //no creation of instances
    }

    public static void findLayoutTestsRecursively(BufferedOutputStream bos,
            String dir) throws IOException {
        Log.v(LOGTAG, "Searching tests under " + dir);

        File d = new File(dir);
        if (!d.isDirectory()) {
            throw new AssertionError("A directory expected, but got " + dir);
        }

        String[] files = d.list();
        for (int i = 0; i < files.length; i++) {
            String s = dir + "/" + files[i];
            if (FileFilter.ignoreTest(s)) {
                Log.v(LOGTAG, "  Ignoring: " + s);
                continue;
            }
            if (s.toLowerCase().endsWith(".html")
                    || s.toLowerCase().endsWith(".xml")) {
                bos.write(s.getBytes());
                bos.write('\n');
                continue;
            }

            File f = new File(s);
            if (f.isDirectory()) {
                findLayoutTestsRecursively(bos, s);
                continue;
            }

            Log.v(LOGTAG, "Skipping " + s);
        }
    }

    public static void updateTestStatus(String statusFile, String s) {
        try {
            BufferedOutputStream bos = new BufferedOutputStream(
                    new FileOutputStream(statusFile));
            bos.write(s.getBytes());
            bos.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot update file " + statusFile);
        }
    }

    public static String readTestStatus(String statusFile) {
        // read out the test name it stopped last time.
        String status = null;
        File testStatusFile = new File(statusFile);
        if(testStatusFile.exists()) {
            try {
                BufferedReader inReader = new BufferedReader(
                        new FileReader(testStatusFile));
                status = inReader.readLine();
                inReader.close();
            } catch (IOException e) {
                Log.e(LOGTAG, "Error reading test status.", e);
            }
        }
        return status;
    }

}
