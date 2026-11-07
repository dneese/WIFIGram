# WIFIGram

An intercom for the local network. Every phone that runs WIFIGram in the same
WiFi network sees the others, calls them, talks to them and sends messages or
files.

No accounts, no rooms, no codes, no passwords and no internet: the phones find
each other by themselves.

## What it does

- **The station** — the list of the phones in the network with their names and
  their state: free, busy or in a conversation.
- **The calls** — a real phone call: the system ringtone, the name of the
  caller, the buttons to answer and to reject, the mute of the microphone and
  the switch between the speaker and the earpiece.
- **The busy line** — if the phone is already talking, the caller waits on the
  line. The phone that talks can add the waiting one to the conversation, and
  then three phones talk at once.
- **The stable connection** — when the phones go out of reach, the call is not
  closed: the phone beeps quietly, waits and continues by itself when the
  signal is back.
- **The messages and the files** — one phone at a time, like the messages and
  the pictures of a usual phone.
- **The journal of the calls** — who called, who did not answer.
- **WiFi Direct** — two phones talk directly, without a router. It is the mode
  for the car: no mobile network and no internet are needed.

## How it works

- The phones say "I am here" to the broadcast address of the network and to a
  multicast group every two seconds.
- Every phone opens a connection to the others, and the chat, the files, the
  signals of the calls and the voice go through it.
- The voice is captured in pieces of 20 milliseconds and sent to the phones of
  the conversation. A phone that is only a way to the others passes the sound
  further.
- A ping every three seconds notices a dead WiFi in a few seconds.

## The build

The project is built on GitHub Actions. The release workflow builds the
signed APKs for arm64, x86_64 and for every architecture at once:

```
bash scripts/release.sh v0.2.0
```

The tag starts the workflow, it builds the APKs and publishes them on the
releases page of the repository.

## The name

WIFIGram is an intercom for the local network: the phones of one building,
one car park or one van talk to each other without the mobile network.
