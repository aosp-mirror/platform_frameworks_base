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

-- note: These queries do comparisons based on raw_ftrace_entries.id by treating it as if it was equivalent to the temporal timestamp.
-- in practice, the ID of raw_ftrace_entries is based on its order in the ftrace buffer [and on the same cpu its equivalent].
-- we can always resort raw_ftrace_entries to ensure id order matches timestamp order. We should rarely need to compare by timestamp directly.
-- accessing 'floats' is inferior as they are harder to index, and will result in slower queries.
--
-- Naming convention note: '_fid' corresponds to 'raw_ftrace_entry.id'.
DROP VIEW IF EXISTS start_process_ui_threads;

-- Map of started process names to their UI thread's TID (as returned by gettid).
CREATE VIEW IF NOT EXISTS start_process_ui_threads AS
WITH
  start_proc_tids AS (
    SELECT sp.raw_ftrace_entry_id AS start_proc_fid,
           sp.atrace_pid AS atrace_pid,
           sp.process_name AS process_name,
           --MIN(nc.raw_ftrace_entry_id) as next_comm_fid,
           nc.raw_ftrace_entry_id AS next_comm_fid,
           nc.next_pid as next_pid,
           nc.next_comm as next_comm,
           SUBSTR(sp.process_name, -15) AS cut      -- why -15? See TASK_MAX in kernel, the sched_switch name is truncated to 16 bytes.
    FROM start_procs AS sp,
         sched_switch_next_comm_pids AS nc
    WHERE sp.process_name LIKE '%' || nc.next_comm  -- kernel truncates the sched_switch::next_comm event, so we must match the prefix of the full name.
    --WHERE SUBSTR(sp.process_name, -16) == nc.next_comm
    --WHERE cut == nc.next_comm
  ),
  start_proc_tids_filtered AS (
      SELECT *
      FROM start_proc_tids
      WHERE next_comm_fid > start_proc_fid        -- safeguard that avoids choosing "earlier" sched_switch before process was even started.
      --ORDER BY start_proc_fid, next_comm_fid
  ),
  start_proc_all_threads AS (
    SELECT DISTINCT
        start_proc_fid, -- this is the ftrace entry of the system server 'Start proc: $process_name'. only need this to join for timestamp.
        process_name,               -- this is the '$process_name' from the system server entry.
        -- next up we have all the possible thread IDs as parsed from sched_switch that corresponds most closest to the start proc.
        next_pid AS ui_thread_tpid, -- sched_switch.next_pid. This can be any of the threads in that process, it's not necessarily the main UI thread yet.
        next_comm,
        MIN(next_comm_fid) AS next_comm_fid   -- don't pick the 'later' next_comm_fid because it could correspond to another app start.
    FROM start_proc_tids_filtered
    GROUP BY start_proc_fid, ui_thread_tpid
  ),
  activity_thread_mains AS (
    SELECT * FROM tracing_mark_write_split WHERE atrace_message = 'ActivityThreadMain'
  ),
  start_proc_ui_threads AS (
    SELECT start_proc_fid,
           process_name,
           ui_thread_tpid,
           next_comm,
           next_comm_fid,
           atm.raw_ftrace_entry_id as atm_fid,
           atm.atrace_pid as atm_ui_thread_tid
    FROM start_proc_all_threads AS spt, 
         activity_thread_mains AS atm
    WHERE atm.atrace_pid == spt.ui_thread_tpid AND atm.raw_ftrace_entry_id > spt.start_proc_fid -- Ensure we ignore earlier ActivityThreadMains prior to their Start proc.
  ),
  start_proc_ui_threads_filtered AS (
    SELECT start_proc_fid,
           process_name,                -- e.g. 'com.android.settings'
           --ui_thread_tpid,
           --next_comm,
           --next_comm_fid,
           MIN(atm_fid) AS atm_fid,
           atm_ui_thread_tid            -- equivalent to gettid() for the process's UI thread.
    FROM start_proc_ui_threads
    GROUP BY start_proc_fid, atm_ui_thread_tid    -- find the temporally closest ActivityTaskMain to a "Start proc: $process_name"
  )
SELECT * FROM start_proc_ui_threads_filtered;

SELECT * FROM start_process_ui_threads;
