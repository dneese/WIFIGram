# Changelog

## v0.1.1

- The app does not close anymore when the network gives a strange answer: every error is caught and the reason is shown inside the app.
- The text of the last error can be sent to the author from the phone.
- The key of the room is made in the background, so the screen does not freeze when the phone enters the room.

## v0.1.0

- The first version of WIFIGramm: chat, files and voice calls inside one local network or directly between two phones over WiFi Direct.
- Everything is encrypted with ChaCha20-Poly1305, the key comes from the code and the password of the room (PBKDF2-HMAC-SHA256).
- Only a phone that knows the password of the room can join it, the phones greet each other with an HMAC and show a safety code for the check.
- Files are sent in pieces of 64 KB and checked with SHA-256 on the other phone.
- The voice goes over UDP, every piece of sound is encrypted with the key of the call.
