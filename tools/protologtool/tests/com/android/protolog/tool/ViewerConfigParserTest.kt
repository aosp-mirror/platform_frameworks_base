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

package com.android.protolog.tool

import com.android.json.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class ViewerConfigParserTest {
    private val parser = ViewerConfigParser()

    private fun getJSONReader(str: String): JsonReader {
        return JsonReader(StringReader(str))
    }

    @Test
    fun parseMessage() {
        val json = """
        {
            "message": "Test completed successfully: %b",
            "level": "ERROR",
            "group": "GENERIC_WM"
        }
        """
        val msg = parser.parseMessage(getJSONReader(json))
        assertEquals("Test completed successfully: %b", msg.messageString)
        assertEquals("ERROR", msg.level)
        assertEquals("GENERIC_WM", msg.groupName)
    }

    @Test
    fun parseMessage_reorder() {
        val json = """
        {
            "group": "GENERIC_WM",
            "level": "ERROR",
            "message": "Test completed successfully: %b"
        }
        """
        val msg = parser.parseMessage(getJSONReader(json))
        assertEquals("Test completed successfully: %b", msg.messageString)
        assertEquals("ERROR", msg.level)
        assertEquals("GENERIC_WM", msg.groupName)
    }

    @Test
    fun parseMessage_unknownEntry() {
        val json = """
        {
            "unknown": "unknown entries should not block parsing",
            "message": "Test completed successfully: %b",
            "level": "ERROR",
            "group": "GENERIC_WM"
        }
        """
        val msg = parser.parseMessage(getJSONReader(json))
        assertEquals("Test completed successfully: %b", msg.messageString)
        assertEquals("ERROR", msg.level)
        assertEquals("GENERIC_WM", msg.groupName)
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseMessage_noMessage() {
        val json = """
        {
            "level": "ERROR",
            "group": "GENERIC_WM"
        }
        """
        parser.parseMessage(getJSONReader(json))
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseMessage_noLevel() {
        val json = """
        {
            "message": "Test completed successfully: %b",
            "group": "GENERIC_WM"
        }
        """
        parser.parseMessage(getJSONReader(json))
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseMessage_noGroup() {
        val json = """
        {
            "message": "Test completed successfully: %b",
            "level": "ERROR"
        }
        """
        parser.parseMessage(getJSONReader(json))
    }

    @Test
    fun parseGroup() {
        val json = """
        {
            "tag": "WindowManager"
        }
        """
        val group = parser.parseGroup(getJSONReader(json))
        assertEquals("WindowManager", group.tag)
    }

    @Test
    fun parseGroup_unknownEntry() {
        val json = """
        {
            "unknown": "unknown entries should not block parsing",
            "tag": "WindowManager"
        }
        """
        val group = parser.parseGroup(getJSONReader(json))
        assertEquals("WindowManager", group.tag)
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseGroup_noTag() {
        val json = """
        {
        }
        """
        parser.parseGroup(getJSONReader(json))
    }

    @Test
    fun parseMessages() {
        val json = """
        {
            "70933285": {
              "message": "Test completed successfully: %b",
              "level": "ERROR",
              "group": "GENERIC_WM"
            },
            "1792430067": {
              "message": "Attempted to add window to a display that does not exist: %d. Aborting.",
              "level": "WARN",
              "group": "ERROR_WM"
            }
        }
        """
        val messages = parser.parseMessages(getJSONReader(json))
        assertEquals(2, messages.size)
        val msg1 =
                ViewerConfigParser.MessageEntry("Test completed successfully: %b",
                        "ERROR", "GENERIC_WM")
        val msg2 =
                ViewerConfigParser.MessageEntry("Attempted to add window to a display that " +
                        "does not exist: %d. Aborting.", "WARN", "ERROR_WM")

        assertEquals(msg1, messages[70933285])
        assertEquals(msg2, messages[1792430067])
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseMessages_invalidHash() {
        val json = """
        {
            "invalid": {
              "message": "Test completed successfully: %b",
              "level": "ERROR",
              "group": "GENERIC_WM"
            }
        }
        """
        parser.parseMessages(getJSONReader(json))
    }

    @Test
    fun parseGroups() {
        val json = """
        {
            "GENERIC_WM": {
              "tag": "WindowManager"
            },
            "ERROR_WM": {
              "tag": "WindowManagerError"
            }
        }
        """
        val groups = parser.parseGroups(getJSONReader(json))
        assertEquals(2, groups.size)
        val grp1 = ViewerConfigParser.GroupEntry("WindowManager")
        val grp2 = ViewerConfigParser.GroupEntry("WindowManagerError")
        assertEquals(grp1, groups["GENERIC_WM"])
        assertEquals(grp2, groups["ERROR_WM"])
    }

    @Test
    fun parseConfig() {
        val json = """
        {
          "version": "${Constants.VERSION}",
          "messages": {
            "70933285": {
              "message": "Test completed successfully: %b",
              "level": "ERROR",
              "group": "GENERIC_WM"
            }
          },
          "groups": {
            "GENERIC_WM": {
              "tag": "WindowManager"
            }
          }
        }
        """
        val config = parser.parseConfig(getJSONReader(json))
        assertEquals(1, config.size)
        val cfg1 = ViewerConfigParser.ConfigEntry("Test completed successfully: %b",
                "ERROR", "WindowManager")
        assertEquals(cfg1, config[70933285])
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseConfig_invalidVersion() {
        val json = """
        {
          "version": "invalid",
          "messages": {
            "70933285": {
              "message": "Test completed successfully: %b",
              "level": "ERROR",
              "group": "GENERIC_WM"
            }
          },
          "groups": {
            "GENERIC_WM": {
              "tag": "WindowManager"
            }
          }
        }
        """
        parser.parseConfig(getJSONReader(json))
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseConfig_noVersion() {
        val json = """
        {
          "messages": {
            "70933285": {
              "message": "Test completed successfully: %b",
              "level": "ERROR",
              "group": "GENERIC_WM"
            }
          },
          "groups": {
            "GENERIC_WM": {
              "tag": "WindowManager"
            }
          }
        }
        """
        parser.parseConfig(getJSONReader(json))
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseConfig_noMessages() {
        val json = """
        {
          "version": "${Constants.VERSION}",
          "groups": {
            "GENERIC_WM": {
              "tag": "WindowManager"
            }
          }
        }
        """
        parser.parseConfig(getJSONReader(json))
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseConfig_noGroups() {
        val json = """
        {
          "version": "${Constants.VERSION}",
          "messages": {
            "70933285": {
              "message": "Test completed successfully: %b",
              "level": "ERROR",
              "group": "GENERIC_WM"
            }
          }
        }
        """
        parser.parseConfig(getJSONReader(json))
    }

    @Test(expected = InvalidViewerConfigException::class)
    fun parseConfig_missingGroup() {
        val json = """
        {
          "version": "${Constants.VERSION}",
          "messages": {
            "70933285": {
              "message": "Test completed successfully: %b",
              "level": "ERROR",
              "group": "GENERIC_WM"
            }
          },
          "groups": {
            "ERROR_WM": {
              "tag": "WindowManager"
            }
          }
        }
        """
        parser.parseConfig(getJSONReader(json))
    }
}
