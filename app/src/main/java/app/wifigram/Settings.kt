package app.wifigram

/**
 * What the user chooses about the voice and the sound of the phone.
 *
 * The voice itself is always WebRTC now, so there is nothing to choose
 * about the way of the words: only how loud they are, whether the phone
 * rings and where the sound goes first.
 */
data class Settings(
    /** How loud the voice of the other phone is, in percent. */
    val voiceVolume: Int = 80,

    /** The phone rings when a call comes. */
    val incomingSound: Boolean = true,

    /** The conversation begins with the speaker of the phone. */
    val speakerDefault: Boolean = false
)
