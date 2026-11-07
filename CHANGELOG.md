# Changelog

## v0.2.0

The app is now an intercom, not a messenger with rooms.

- No rooms, no codes and no passwords. Every phone that runs WIFIGram in the same network appears in the station with its name and its state.
- The calls work like in a phone app: the ringtone of the system, the name of the caller, the buttons to answer and to reject.
- The mute of the microphone and the switch between the speaker and the earpiece during the conversation.
- The line that is busy does not turn the caller away: the caller waits on the line and the phone that talks can add the waiting one to the conversation. So three phones can talk at once.
- The connection is stable: a ping every three seconds notices a lost WiFi in a few seconds, the call is not closed when the phones go out of reach, the phone beeps quietly until the signal is back and the conversation continues by itself.
- The chat and the files work with one phone at a time, like the messages and the pictures of a usual phone.
- The journal of the calls with the missed ones.
- The station lives in a background service, so a call is heard even when the screen is closed.
- The look of a usual phone app: three tabs, the green call button, the round dial screen.
- The name of the app is WIFIGram.
