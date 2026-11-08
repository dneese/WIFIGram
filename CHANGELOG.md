# Changelog

## v0.4.0

The phone hears the voice clean, and says that a message has come.

- **The noise catcher**: the hum, the fan, the machines and the engine of a car get quieter, the voice stays as it is. Speex works on the frame of twenty milliseconds, so the voice does not get later.
- **The switch in the settings**: the noise catcher can be turned off with one finger, next to the story of the app.
- **The story of the app**: the settings tell the version of the app and open its source on GitHub with one touch.
- **The sound of a message**: the phone says quietly that a message has come, even when the screen is closed.

## v0.3.0

The voice of the call runs faster and smoother.

- **Its own connection**: the voice travels on a second socket, so a file or a long message never stops the talk. The voice connection comes back by itself when the WiFi returns.
- **The cushion**: the sound that arrives lands in a small cushion before the speaker. The bursts of the network are smoothed, a hole in the network fades instead of stopping dead, and the phone feeds the speaker even while the sound is missing.
- **The catch-up**: when the cushion grows too thick — after a quiet stretch of the network — the sound runs a little faster than life, a frame out of every few is dropped with a crossfade, until the cushion is small again and the normal speed is back. The talk is heard in full, only quicker, and never behind.
- **Opus**: the voice is coded with Opus, so a piece of twenty milliseconds is a few dozen bytes instead of six hundred and forty — less load on the WiFi and the same voice.
- **Smaller buffers**: the microphone and the speaker work with eighty milliseconds instead of one hundred and sixty, so the whole line is shorter by a breath.

The voice frames changed their shape, so **every phone must update to this version**: an older phone hears nothing from this one.

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
