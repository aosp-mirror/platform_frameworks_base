/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.commands.incident.sections;

import android.util.Log;
import android.util.PersistedLogProto;
import android.util.TextLogEntry;
import android.util.proto.ProtoOutputStream;

import com.android.commands.incident.ExecutionException;
import com.android.commands.incident.IncidentHelper;
import com.android.commands.incident.Section;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** PersistLogSection reads persisted logs and parses them into a PersistedLogProto. */
public class PersistLogSection implements Section {
    private static final String TAG = "IH_PersistLog";
    private static final String LOG_DIR = "/data/misc/logd/";
    // Persist log files are named logcat, logcat.001, logcat.002, logcat.003, ...
    private static final Pattern LOG_FILE_RE = Pattern.compile("logcat(\\.\\d+)?");
    private static final Pattern BUFFER_BEGIN_RE =
            Pattern.compile("--------- (?:beginning of|switch to) (.*)");
    private static final Map<String, Long> SECTION_NAME_TO_ID = new HashMap<>();
    private static final Map<Character, Integer> LOG_PRIORITY_MAP = new HashMap<>();
    private static final String DEFAULT_BUFFER = "main";

    static {
        SECTION_NAME_TO_ID.put("main", PersistedLogProto.MAIN_LOGS);
        SECTION_NAME_TO_ID.put("radio", PersistedLogProto.RADIO_LOGS);
        SECTION_NAME_TO_ID.put("events", PersistedLogProto.EVENTS_LOGS);
        SECTION_NAME_TO_ID.put("system", PersistedLogProto.SYSTEM_LOGS);
        SECTION_NAME_TO_ID.put("crash", PersistedLogProto.CRASH_LOGS);
        SECTION_NAME_TO_ID.put("kernel", PersistedLogProto.KERNEL_LOGS);
    }

    static {
        LOG_PRIORITY_MAP.put('V', TextLogEntry.LOG_VERBOSE);
        LOG_PRIORITY_MAP.put('D', TextLogEntry.LOG_DEBUG);
        LOG_PRIORITY_MAP.put('I', TextLogEntry.LOG_INFO);
        LOG_PRIORITY_MAP.put('W', TextLogEntry.LOG_WARN);
        LOG_PRIORITY_MAP.put('E', TextLogEntry.LOG_ERROR);
        LOG_PRIORITY_MAP.put('F', TextLogEntry.LOG_FATAL);
        LOG_PRIORITY_MAP.put('S', TextLogEntry.LOG_SILENT);
    }

    /**
     * Caches dates at 00:00:00 to epoch second elapsed conversion. There are only a few different
     * dates in persisted logs in one device, and constructing DateTime object is relatively
     * expensive.
     */
    private Map<Integer, Long> mEpochTimeCache = new HashMap<>();
    private ProtoOutputStream mProto;
    private long mCurrFieldId;
    private long mMaxBytes = Long.MAX_VALUE;

    @Override
    public void run(InputStream in, OutputStream out, List<String> args) throws ExecutionException {
        parseArgs(args);
        Path logDirPath = Paths.get(LOG_DIR);
        if (!Files.exists(logDirPath)) {
            IncidentHelper.log(Log.WARN, TAG, "Skip dump. " + logDirPath + " does not exist.");
            return;
        }
        if (!Files.isReadable(logDirPath)) {
            IncidentHelper.log(Log.WARN, TAG, "Skip dump. " + logDirPath + " is not readable.");
            return;
        }
        mProto = new ProtoOutputStream(out);
        setCurrentSection(DEFAULT_BUFFER);
        final Matcher logFileRe = LOG_FILE_RE.matcher("");
        // Need to process older log files first and write logs to proto in chronological order
        // But we want to process only the latest ones if there is a size limit
        try (Stream<File> stream = Files.list(logDirPath).map(Path::toFile)
                .filter(f -> !f.isDirectory() && match(logFileRe, f.getName()) != null)
                .sorted(Comparator.comparingLong(File::lastModified).reversed())) {
            Iterator<File> iter = stream.iterator();
            List<File> filesToProcess = new ArrayList<>();
            long sumBytes = 0;
            while (iter.hasNext()) {
                File file = iter.next();
                sumBytes += file.length();
                if (sumBytes > mMaxBytes) {
                    break;
                }
                filesToProcess.add(file);
            }
            IncidentHelper.log(Log.INFO, TAG, "Limit # log files to " + filesToProcess.size());
            filesToProcess.stream()
                    .sorted(Comparator.comparingLong(File::lastModified))
                    .forEachOrdered(this::processFile);
        } catch (IOException e) {
            throw new ExecutionException(e);
        } finally {
            mProto.flush();
        }
        IncidentHelper.log(Log.DEBUG, TAG, "Bytes written: " + mProto.getBytes().length);
    }

    private void parseArgs(List<String> args) {
        Iterator<String> iter = args.iterator();
        while (iter.hasNext()) {
            String arg = iter.next();
            if ("--limit".equals(arg) && iter.hasNext()) {
                String sizeStr = iter.next().toLowerCase();
                if (sizeStr.endsWith("mb")) {
                    mMaxBytes = Long.parseLong(sizeStr.replace("mb", "")) * 1024 * 1024;
                } else if (sizeStr.endsWith("kb")) {
                    mMaxBytes = Long.parseLong(sizeStr.replace("kb", "")) * 1024;
                } else {
                    mMaxBytes = Long.parseLong(sizeStr);
                }
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
    }

    private void processFile(File file) {
        final Matcher bufferBeginRe = BUFFER_BEGIN_RE.matcher("");
        try (BufferedReader reader = Files.newBufferedReader(file.toPath(),
                StandardCharsets.UTF_8)) {
            String line;
            Matcher m;
            while ((line = reader.readLine()) != null) {
                if ((m = match(bufferBeginRe, line)) != null) {
                    setCurrentSection(m.group(1));
                    continue;
                }
                parseLine(line);
            }
        } catch (IOException e) {
            // Non-fatal error. We can skip and still process other files.
            IncidentHelper.log(Log.WARN, TAG, "Error reading \"" + file + "\": " + e.getMessage());
        }
        IncidentHelper.log(Log.DEBUG, TAG, "Finished reading " + file);
    }

    private void setCurrentSection(String sectionName) {
        Long sectionId = SECTION_NAME_TO_ID.get(sectionName);
        if (sectionId == null) {
            IncidentHelper.log(Log.WARN, TAG, "Section does not exist: " + sectionName);
            sectionId = SECTION_NAME_TO_ID.get(DEFAULT_BUFFER);
        }
        mCurrFieldId = sectionId;
    }

    /**
     * Parse a log line in the following format:
     * 01-01 15:01:47.723501  2738  2895 I Exp_TAG: example log line
     *
     * It does not use RegExp for performance reasons. Using this RegExp "(\\d{2})-(\\d{2})\\s
     * (\\d{2}):(\\d{2}):(\\d{2}).(\\d{6})\\s+(\\d+)\\s+(\\d+)\\s+(.)\\s+(.*?):\\s(.*)" is twice as
     * slow as the current approach.
     */
    private void parseLine(String line) {
        long token = mProto.start(mCurrFieldId);
        try {
            mProto.write(TextLogEntry.SEC, getEpochSec(line));
            // Nanosec is 15th to 20th digits of "10-01 02:57:27.710652" times 1000
            mProto.write(TextLogEntry.NANOSEC, parseInt(line, 15, 21) * 1000L);

            int start = nextNonBlank(line, 21);
            int end = line.indexOf(' ', start + 1);
            mProto.write(TextLogEntry.PID, parseInt(line, start, end));

            start = nextNonBlank(line, end);
            end = line.indexOf(' ', start + 1);
            mProto.write(TextLogEntry.TID, parseInt(line, start, end));

            start = nextNonBlank(line, end);
            char priority = line.charAt(start);
            mProto.write(TextLogEntry.PRIORITY,
                    LOG_PRIORITY_MAP.getOrDefault(priority, TextLogEntry.LOG_DEFAULT));

            start = nextNonBlank(line, start + 1);
            end = line.indexOf(": ", start);
            mProto.write(TextLogEntry.TAG, line.substring(start, end).trim());
            mProto.write(TextLogEntry.LOG, line.substring(Math.min(end + 2, line.length())));
        } catch (RuntimeException e) {
            // Error reporting is likely piped to /dev/null. Inserting it into the proto to make
            // it more useful.
            mProto.write(TextLogEntry.SEC, System.currentTimeMillis() / 1000);
            mProto.write(TextLogEntry.PRIORITY, TextLogEntry.LOG_ERROR);
            mProto.write(TextLogEntry.TAG, TAG);
            mProto.write(TextLogEntry.LOG,
                    "Error parsing \"" + line + "\"" + ": " + e.getMessage());
        }
        mProto.end(token);
    }

    // ============== Below are util methods to parse log lines ==============

    private static int nextNonBlank(String line, int start) {
        for (int i = start; i < line.length(); i++) {
            if (line.charAt(i) != ' ') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Gets the epoch second from the line string. Line starts with a fixed-length timestamp like
     * "10-01 02:57:27.710652"
     */
    private long getEpochSec(String line) {
        int month = getDigit(line, 0) * 10 + getDigit(line, 1);
        int day = getDigit(line, 3) * 10 + getDigit(line, 4);

        int mmdd = month * 100 + day;
        long epochSecBase = mEpochTimeCache.computeIfAbsent(mmdd, (key) -> {
            final GregorianCalendar calendar = new GregorianCalendar();
            calendar.set(Calendar.MONTH, (month + 12 - 1) % 12);
            calendar.set(Calendar.DAY_OF_MONTH, day);
            calendar.set(Calendar.HOUR_OF_DAY, 0);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            // Date in log entries can never be in the future. If it happens, it means we are off
            // by one year.
            if (calendar.getTimeInMillis() > System.currentTimeMillis()) {
                calendar.roll(Calendar.YEAR, /*amount=*/-1);
            }
            return calendar.getTimeInMillis() / 1000;
        });

        int hh = getDigit(line, 6) * 10 + getDigit(line, 7);
        int mm = getDigit(line, 9) * 10 + getDigit(line, 10);
        int ss = getDigit(line, 12) * 10 + getDigit(line, 13);
        return epochSecBase + hh * 3600 + mm * 60 + ss;
    }

    private static int parseInt(String line, /*inclusive*/ int start, /*exclusive*/ int end) {
        int num = 0;
        for (int i = start; i < end; i++) {
            num = num * 10 + getDigit(line, i);
        }
        return num;
    }

    private static int getDigit(String str, int pos) {
        int digit = str.charAt(pos) - '0';
        if (digit < 0 || digit > 9) {
            throw new NumberFormatException("'" + str.charAt(pos) + "' is not a digit.");
        }
        return digit;
    }

    private static Matcher match(Matcher matcher, String text) {
        matcher.reset(text);
        return matcher.matches() ? matcher : null;
    }
}
