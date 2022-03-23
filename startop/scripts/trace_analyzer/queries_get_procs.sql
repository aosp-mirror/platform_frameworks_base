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

DROP VIEW IF EXISTS start_procs;

CREATE VIEW IF NOT EXISTS start_procs AS
WITH
  start_procs_raw AS (
      SELECT * from tracing_mark_write_split WHERE atrace_message LIKE 'Start proc: %'
  ),
  start_procs_substr AS (
      -- note: "12" is len("Start proc: ")+1. sqlite indices start at 1.
      SELECT raw_ftrace_entry_id, atrace_pid, SUBSTR(atrace_message, 13) AS process_name FROM start_procs_raw
  )
SELECT * from start_procs_substr;

SELECT * from start_procs;
