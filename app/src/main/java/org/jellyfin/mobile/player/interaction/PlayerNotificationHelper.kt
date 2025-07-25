package org.jellyfin.mobile.player.interaction

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.player.PlayerViewModel
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.mobile.player.source.RemoteJellyfinMediaSource
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.VIDEO_PLAYER_NOTIFICATION_ID
import org.jellyfin.mobile.utils.createMediaNotificationChannel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.operations.ImageApi
import org.jellyfin.sdk.model.api.ImageType
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PlayerNotificationHelper(private val viewModel: PlayerViewModel) : KoinComponent {
    private val context: Context = viewModel.getApplication()
    private val appPreferences: AppPreferences by inject()
    private val notificationManager: NotificationManager? by lazy { context.getSystemService() }
    private val imageApi: ImageApi = get<ApiClient>().imageApi
    private val imageLoader: ImageLoader by inject()
    private val downloadDao: DownloadDao by inject()
    private val receiverRegistered = AtomicBoolean(false)

    val allowBackgroundAudio: Boolean
        get() = appPreferences.exoPlayerAllowBackgroundAudio

    private val notificationActionReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_PLAY -> viewModel.play()
                Constants.ACTION_PAUSE -> viewModel.pause()
                Constants.ACTION_REWIND -> viewModel.rewind()
                Constants.ACTION_FAST_FORWARD -> viewModel.fastForward()
                Constants.ACTION_PREVIOUS -> viewModel.skipToPrevious()
                Constants.ACTION_NEXT -> viewModel.skipToNext()
                Constants.ACTION_STOP -> viewModel.stop()
            }
        }
    }

    @Suppress("DEPRECATION", "CyclomaticComplexMethod", "LongMethod")
    fun postNotification() {
        val nm = notificationManager ?: return
        val player = viewModel.playerOrNull ?: return
        val currentMediaSource = viewModel.queueManager.getCurrentMediaSourceOrNull() ?: return
        val hasPrevious = viewModel.queueManager.hasPrevious()
        val hasNext = viewModel.queueManager.hasNext()
        val playbackState = player.playbackState
        if (playbackState != Player.STATE_READY && playbackState != Player.STATE_BUFFERING) return

        // Create notification channel
        context.createMediaNotificationChannel(nm)

        viewModel.viewModelScope.launch {
            val style = Notification.MediaStyle().apply {
                setMediaSession(viewModel.mediaSession.sessionToken)
                setShowActionsInCompactView(0, 1, 2)
            }

            val notification = Notification.Builder(context).apply {
                if (AndroidVersion.isAtLeastO) {
                    // Set notification channel on Android O and above
                    setChannelId(Constants.MEDIA_NOTIFICATION_CHANNEL_ID)
                    setColorized(true)
                } else {
                    setPriority(Notification.PRIORITY_LOW)
                }
                setStyle(style)
                setSmallIcon(R.drawable.ic_notification)
                if (!AndroidVersion.isAtLeastQ) {
                    val mediaIcon: Bitmap? = withContext(Dispatchers.IO) {
                        loadImage(currentMediaSource)
                    }
                    if (mediaIcon != null) {
                        setLargeIcon(mediaIcon)
                    }
                }
                setContentTitle(currentMediaSource.name)
                currentMediaSource.item?.artists?.joinToString()?.let { artists ->
                    setContentText(artists)
                }
                setVisibility(Notification.VISIBILITY_PUBLIC)
                when {
                    hasPrevious -> addAction(generateAction(PlayerNotificationAction.PREVIOUS))
                    else -> addAction(generateAction(PlayerNotificationAction.REWIND))
                }
                val playbackAction = when {
                    !player.playWhenReady -> PlayerNotificationAction.PLAY
                    else -> PlayerNotificationAction.PAUSE
                }
                addAction(generateAction(playbackAction))
                when {
                    hasNext -> addAction(generateAction(PlayerNotificationAction.NEXT))
                    else -> addAction(generateAction(PlayerNotificationAction.FAST_FORWARD))
                }
                setContentIntent(buildContentIntent())
                setDeleteIntent(buildDeleteIntent())

                // prevents the notification from being dismissed while playback is ongoing
                setOngoing(player.isPlaying)
            }.build()

            nm.notify(VIDEO_PLAYER_NOTIFICATION_ID, notification)
        }

        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter()
            for (notificationAction in PlayerNotificationAction.values()) {
                filter.addAction(notificationAction.action)
            }
            ContextCompat.registerReceiver(
                context,
                notificationActionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun dismissNotification() {
        notificationManager?.cancel(VIDEO_PLAYER_NOTIFICATION_ID)
        if (receiverRegistered.compareAndSet(true, false)) {
            context.unregisterReceiver(notificationActionReceiver)
        }
    }

    private suspend fun loadImage(mediaSource: JellyfinMediaSource) = when (mediaSource) {
        is LocalJellyfinMediaSource -> {
            val downloadFolder = File(
                downloadDao
                    .get(mediaSource.id)
                    .let(::requireNotNull)
                    .asMediaSource()
                    .localDirectoryUri,
            )
            val thumbnailFile = File(downloadFolder, Constants.DOWNLOAD_THUMBNAIL_FILENAME)
            BitmapFactory.decodeFile(thumbnailFile.canonicalPath)
        }
        is RemoteJellyfinMediaSource -> {
            val height = context.resources.getDimensionPixelSize(R.dimen.media_notification_height)

            val imageUrl = imageApi.getItemImageUrl(
                itemId = mediaSource.itemId,
                imageType = ImageType.PRIMARY,
                fillHeight = height,
                tag = mediaSource.item?.imageTags?.get(ImageType.PRIMARY),
            )

            val imageRequest = ImageRequest.Builder(context).data(imageUrl).build()
            imageLoader.execute(imageRequest).image?.toBitmap()
        }
    }

    private fun generateAction(playerNotificationAction: PlayerNotificationAction): Notification.Action {
        val intent = Intent(playerNotificationAction.action).apply {
            `package` = BuildConfig.APPLICATION_ID
        }
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, Constants.PENDING_INTENT_FLAGS)
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(
            playerNotificationAction.icon,
            context.getString(playerNotificationAction.label),
            pendingIntent,
        ).build()
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        return PendingIntent.getActivity(context, 0, intent, Constants.PENDING_INTENT_FLAGS)
    }

    private fun buildDeleteIntent(): PendingIntent {
        val intent = Intent(Constants.ACTION_STOP).apply {
            `package` = BuildConfig.APPLICATION_ID
        }
        return PendingIntent.getBroadcast(context, 0, intent, Constants.PENDING_INTENT_FLAGS)
    }
}
