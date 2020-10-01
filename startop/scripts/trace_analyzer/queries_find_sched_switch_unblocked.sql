/*
 * Copyright (C) 2019 The Android Open Source Project
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

DROP VIEW IF EXISTS sched_switch_iowaits_pre;

-- scan for the closest pair such that:
--                  sched_block_reason pid=$PID iowait=1 ...
--                  ...
--                  sched_switch next_pid=$PID
CREATE VIEW sched_switch_iowaits_pre AS
    SELECT MAX(sbr.id) AS blocked_id,
           ss.id AS sched_switch_id,
           pid,         -- iow.pid
           iowait,      -- iowait=0 or iowait=1
           caller,
           sbr_f.timestamp AS blocked_timestamp,
           ss_f.timestamp AS sched_switch_timestamp,
           next_comm,   -- name of next_pid
           next_pid     -- same as iow.pid
    FROM sched_blocked_reasons AS sbr,
         raw_ftrace_entries AS sbr_f,
         sched_switches AS ss,
         raw_ftrace_entries AS ss_f
    WHERE sbr_f.id == sbr.id
          AND ss_f.id == ss.id
          AND sbr.pid == ss.next_pid
          AND sbr.iowait = 1
          AND sbr_f.timestamp < ss_f.timestamp     -- ensures the 'closest' sched_blocked_reason is selected.
    GROUP BY ss.id
;

DROP VIEW IF EXISTS sched_switch_iowaits;

CREATE VIEW sched_switch_iowaits AS
    SELECT *, MIN(sched_switch_timestamp) AS ss_timestamp      -- drop all of the 'too large' sched_switch entries except the closest one.
    FROM sched_switch_iowaits_pre
    GROUP BY blocked_id;

SELECT * FROM sched_switch_iowaits;

-- use a real table here instead of a view, otherwise SQLiteStudio segfaults for some reason.
DROP TABLE IF EXISTS blocking_durations;

CREATE TABLE blocking_durations AS
WITH
    blocking_durations_raw AS (
        SELECT MAX(ss.id) AS block_id,
               ssf.timestamp AS block_timestamp,
               iow.sched_switch_timestamp AS unblock_timestamp,
               ss.prev_comm as block_prev_comm,
               iow.next_comm AS unblock_next_comm,
               ss.prev_state AS block_prev_state,
               iow.sched_switch_id AS unblock_id,
               iow.pid AS unblock_pid,
               iow.caller AS unblock_caller
        FROM sched_switches AS ss,          -- this is the sched_switch that caused a block (in the future when it unblocks, the reason is iowait=1).
             sched_switch_iowaits AS iow,    -- this is the sched_switch that removes the block (it is now running again).
             raw_ftrace_entries AS ssf
        WHERE ssf.id = ss.id AND ss.prev_pid == iow.next_pid AND ssf.timestamp < iow.sched_switch_timestamp
        GROUP BY unblock_timestamp 
    ),
    blocking_durations_tmp AS (
        SELECT block_id,
               unblock_timestamp,
               block_timestamp,
               block_prev_comm as comm,
               block_prev_state as block_state,
               unblock_id,
               unblock_pid,
               unblock_caller
        FROM blocking_durations_raw
    )
    SELECT * FROM blocking_durations_tmp;-- ORDER BY block_id ASC;
    --SELECT SUM(block_duration_ms) AS sum, * FROM blocking_durations GROUP BY unblock_pid ORDER BY sum DESC;

DROP INDEX IF EXISTS "blocking_durations_block_timestamp";

CREATE INDEX "blocking_durations_block_timestamp" ON blocking_durations (
    block_timestamp COLLATE BINARY COLLATE BINARY
);

DROP INDEX IF EXISTS "blocking_durations_unblock_timestamp";

CREATE INDEX "blocking_durations_unblock_timestamp" ON blocking_durations (
    unblock_timestamp COLLATE BINARY COLLATE BINARY
);

SELECT * FROM blocking_durations;
