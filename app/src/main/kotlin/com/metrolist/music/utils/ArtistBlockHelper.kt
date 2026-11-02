package com.metrolist.music.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.metrolist.innertube.models.ArtistBlockCore
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import java.lang.ref.WeakReference

object ArtistBlockHelper {
    private val mainHandler = Handler(Looper.getMainLooper())
    val versionState = mutableIntStateOf(0)

    @Volatile
    private var playerRef: WeakReference<Player>? = null

    init {
        ArtistBlockCore.addListener {
            mainHandler.post {
                versionState.intValue += 1
                pruneActivePlayer()
            }
        }
    }

    fun initContext(context: Context?) {
        if (context != null) {
            ArtistBlockCore.attachStorageDir(context.applicationContext.filesDir)
        }
    }

    fun isArtistNameBlocked(name: CharSequence?): Boolean =
        ArtistBlockCore.isArtistNameBlocked(name)

    fun isMediaMetadataBlocked(meta: MediaMetadata?): Boolean {
        if (meta == null) return false
        if (isArtistNameBlocked(meta.title)) return true
        if (isArtistNameBlocked(meta.album?.title)) return true
        return meta.artists.any { isArtistNameBlocked(it.name) }
    }

    fun isMediaItemBlocked(item: MediaItem?): Boolean {
        if (item == null) return false
        if (isMediaMetadataBlocked(item.metadata)) return true
        val m = item.mediaMetadata
        if (isArtistNameBlocked(m.artist) || isArtistNameBlocked(m.albumArtist) ||
            isArtistNameBlocked(m.subtitle) || isArtistNameBlocked(m.title)
        ) {
            return true
        }
        return false
    }

    fun onPlayerActivity(player: Player, transitioningItem: MediaItem?): Boolean {
        playerRef = WeakReference(player)
        prunePlayerQueue(player)
        val checkItem = transitioningItem ?: player.currentMediaItem
        if (isMediaItemBlocked(checkItem)) {
            val currentIdx = player.currentMediaItemIndex
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                if (currentIdx >= 0 && currentIdx < player.mediaItemCount) {
                    runCatching { player.removeMediaItem(currentIdx) }
                }
            } else if (currentIdx >= 0 && currentIdx < player.mediaItemCount) {
                runCatching { player.removeMediaItem(currentIdx) }
                player.stop()
            }
            return true
        }
        return false
    }

    fun pruneActivePlayer() {
        val p = playerRef?.get() ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            prunePlayerQueue(p)
            if (isMediaItemBlocked(p.currentMediaItem)) {
                val idx = p.currentMediaItemIndex
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                    if (idx >= 0 && idx < p.mediaItemCount) {
                        runCatching { p.removeMediaItem(idx) }
                    }
                } else if (idx >= 0 && idx < p.mediaItemCount) {
                    runCatching { p.removeMediaItem(idx) }
                    p.stop()
                }
            }
        } else {
            mainHandler.post { pruneActivePlayer() }
        }
    }

    private fun prunePlayerQueue(player: Player) {
        runCatching {
            val count = player.mediaItemCount
            val currentIdx = player.currentMediaItemIndex
            for (i in count - 1 downTo 0) {
                if (i != currentIdx) {
                    val item = player.getMediaItemAt(i)
                    if (isMediaItemBlocked(item)) {
                        player.removeMediaItem(i)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistBlockMenuBar(
    artistNames: List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ArtistBlockHelper.initContext(context)
    }
    val _ver = ArtistBlockHelper.versionState.intValue
    val distinctArtists = remember(artistNames, _ver) {
        artistNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    if (distinctArtists.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            distinctArtists.forEach { artistName ->
                val blocked = ArtistBlockHelper.isArtistNameBlocked(artistName)
                if (blocked) {
                    OutlinedButton(
                        onClick = {
                            ArtistBlockCore.toggleArtist(artistName)
                            Toast.makeText(
                                context,
                                "Unblocked $artistName",
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDismiss()
                        },
                    ) {
                        Text(
                            text = "Unblock $artistName",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            ArtistBlockCore.toggleArtist(artistName)
                            ArtistBlockHelper.pruneActivePlayer()
                            Toast.makeText(
                                context,
                                "Blocked $artistName (won't play or show in search)",
                                Toast.LENGTH_SHORT,
                            ).show()
                            onDismiss()
                        },
                    ) {
                        Text(
                            text = "Block $artistName",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArtistBlockSettingsCard() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ArtistBlockHelper.initContext(context)
    }
    val _ver = ArtistBlockHelper.versionState.intValue
    val blockedList = remember(_ver) { ArtistBlockCore.displayArtists }
    var showDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { showDialog = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Blocked artists (${blockedList.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (blockedList.isEmpty()) {
                    "Tap to block artists from playing or showing in search"
                } else {
                    blockedList.joinToString(", ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (showDialog) {
        var newArtistInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Blocked Artists") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Blocked artists will not play in queues/radio and will be hidden from search results.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = newArtistInput,
                            onValueChange = { newArtistInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Artist name (e.g. Beyonce)") },
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val trimmed = newArtistInput.trim()
                                if (trimmed.isNotEmpty()) {
                                    trimmed.split(',', ';').forEach { part ->
                                        if (part.trim().isNotEmpty()) {
                                            ArtistBlockCore.blockArtist(part.trim())
                                        }
                                    }
                                    ArtistBlockHelper.pruneActivePlayer()
                                    newArtistInput = ""
                                }
                            },
                        ) {
                            Text("Add")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (blockedList.isEmpty()) {
                        Text(
                            text = "No artists currently blocked.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            blockedList.forEach { artist ->
                                OutlinedButton(
                                    onClick = {
                                        ArtistBlockCore.unblockArtist(artist)
                                    },
                                ) {
                                    Text("$artist  ✕")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = newArtistInput.trim()
                        if (trimmed.isNotEmpty()) {
                            trimmed.split(',', ';').forEach { part ->
                                if (part.trim().isNotEmpty()) {
                                    ArtistBlockCore.blockArtist(part.trim())
                                }
                            }
                            ArtistBlockHelper.pruneActivePlayer()
                            newArtistInput = ""
                        }
                        showDialog = false
                    },
                ) {
                    Text("Done")
                }
            },
        )
    }
}
