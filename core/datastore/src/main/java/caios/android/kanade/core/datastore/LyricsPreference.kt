package caios.android.kanade.core.datastore

import android.content.Context
import caios.android.kanade.core.common.network.Dispatcher
import caios.android.kanade.core.common.network.KanadeDispatcher
import caios.android.kanade.core.common.network.di.ApplicationScope
import caios.android.kanade.core.model.music.Lyrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

class LyricsPreference @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(KanadeDispatcher.IO) private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val formatter = Json { ignoreUnknownKeys = true }
    private val _data = mutableSetOf<Lyrics>()

    val data get() = _data.toList()

    init {
        scope.launch {
            fetch()
        }
    }

    suspend fun save(lyrics: Lyrics) = withContext(io) {
        val file = File(context.filesDir, FILE_NAME)
        val lyricsList = fetch().toMutableList()

        lyricsList.removeIf { it.songId == lyrics.songId }
        lyricsList.add(lyrics)

        val serializer = ListSerializer(Lyrics.serializer())
        val json = formatter.encodeToString(serializer, lyricsList)

        file.writeText(json)

        _data.addAll(lyricsList)
    }

    private suspend fun fetch(): List<Lyrics> = withContext(io) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return@withContext emptyList()

        val json = file.readText()
        val serializer = ListSerializer(Lyrics.serializer())
        val lyricsList = formatter.decodeFromString(serializer, json)

        _data.addAll(lyricsList)

        return@withContext lyricsList
    }

    companion object {
        private const val FILE_NAME = "MusicLyricsCache.json"
    }
}