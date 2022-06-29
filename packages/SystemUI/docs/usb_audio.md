# USB audio Permission and Confirmation warning dialog resource string id matrix table
### go/support-usb-access-aoc-offload-feature

     |---|------------|----------------|------------------|-----------------|--------------------|
     | # | Permission |isUsbAudioDevice| hasAudioPlayback | hasAudioCapture | string resource ID |
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 1 |  TRUE      |  TRUE          |  TRUE            |  FALSE          | usb_audio_device_
                                                                              permission_prompt  |
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 2 |  TRUE      |  TRUE          |  FALSE           |  TRUE           | usb_audio_device_
                                                                              permission_prompt  |
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 3 |  TRUE      |  TRUE          |  TRUE            |  TRUE           | usb_audio_device_
                                                                              permission_prompt  |
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 4 |  TRUE      |  FALSE         |  N/A             |  N/A            | usb_device_
                                                                              permission_prompt  |
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 5 |  FALSE     |  TRUE          |  TRUE            |  FALSE          | usb_audio_device_
                                                                              permission_prompt  |
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 6 |  FALSE     |  TRUE          |  FALSE           |  TRUE           | usb_audio_device_
                                                                            permission_prompt_warn
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 7 |  FALSE     |  TRUE          |  TRUE            |  TRUE           | usb_audio_device_
                                                                            permission_prompt_warn
     |---|------------|----------------|------------------|-----------------|--------------------|
     | 8 |  FALSE     |  FALSE         |  N/A             |  N/A            | usb_device_
                                                                             permission_prompt   |
     |---|------------|----------------|------------------|-----------------|--------------------|
