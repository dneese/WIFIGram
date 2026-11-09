# Changelog

## v0.5.0

The phone speaks your language, and the voice does not wait for the lost words.

- **The language of the program**: Ukrainian or English, chosen in the settings. The choice belongs to the phone and stays there, so the program, the service and the journal of the calls all speak the same language.
- **The voice goes over UDP**: a stream of TCP waits for a lost piece before it lets the next one through, so one lost word held the whole speech back. Over UDP a lost word costs one word and not the sentence. The phones say hello over UDP as well: when the hello comes, the way is open and the voice goes this way; when it does not come, the voice stays on the stream as it was.
- **The cushion follows the network**: the buffer watches how the frames come and measures how much the network stumbles. The bigger the stumble, the deeper the cushion has to be, and the deeper it is, the fewer words are lost in the holes. The next call begins with the cushion that fits the network of the last one.
- **The sound waits for the cushion when it is thin**: it runs a little slower, so the cushion grows back without a hole in the speech.
- **The package is thin now**: the unused code and the unused icons are thrown away, so the APK is many times smaller.

## v0.4.1

The voice comes back whole: the words are not lost and not cut into pieces.

- **The noise catcher of v0.4.0 is gone**: it swallowed the voice instead of the hum, so the phone speaks with its own voice again. The switch in the settings now turns the helper of the phone itself — the platform noise suppressor — and it can be turned off.
- **The echo of the speaker** is taken away by the phone itself while the sound goes to the speaker, so the other phone hears you once, not twice.
- **The microphone gives whole frames now**: the pieces of ten milliseconds were gathered into a frame of twenty, so no word fell out of the conversation.
- **The cushion no longer throws words away**: when it grows too thick, the sound runs a little faster instead of dropping a frame out of every four. The voice is a little quicker for a moment — every word is still there.
- **The sound of the phone does not mix itself up**: the voice, the ping and the chat of one connection no longer write over each other, which used to break the stream of the call.
- **The route of the sound is set in the right order**, so the speaker stays the speaker and the voice comes out where it should.
- **Smaller cushions**: the microphone and the speaker wait forty milliseconds instead of eighty, so the voice is that much closer.
- **The journal of the calls shows the numbers**: how many frames were lost and how many holes of the network were hidden.

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
