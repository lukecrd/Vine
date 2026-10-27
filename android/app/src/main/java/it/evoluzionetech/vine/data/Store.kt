package it.evoluzionetech.vine.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import it.evoluzionetech.vine.core.State
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** One JSON `State` object, written atomically (the chrome.storage.local equivalent). */
private object StateSerializer : Serializer<State> {
    private val json = Json { ignoreUnknownKeys = true }
    override val defaultValue = State()

    // A corrupt file is reported, never silently replaced: that would drop the already-seen ASINs.
    override suspend fun readFrom(input: InputStream): State = try {
        json.decodeFromString(State.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Stato salvato illeggibile.", e)
    }

    override suspend fun writeTo(t: State, output: OutputStream) =
        output.write(json.encodeToString(State.serializer(), t).encodeToByteArray())
}

val Context.store: DataStore<State> by dataStore("state.json", StateSerializer)
