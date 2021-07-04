package com.lagradost.cloudstream3.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import com.anggrayudi.storage.extension.closeStream
import com.anggrayudi.storage.file.DocumentFileCompat
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.bumptech.glide.Glide
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.normalSafeApiCall
import com.lagradost.cloudstream3.services.VideoDownloadService
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.lang.Thread.sleep
import java.net.URL
import java.net.URLConnection
import java.util.*
import kotlin.collections.ArrayList

const val CHANNEL_ID = "cloudstream3.general"
const val CHANNEL_NAME = "Downloads"
const val CHANNEL_DESCRIPT = "The download notification channel"

object VideoDownloadManager {
    var maxConcurrentDownloads = 3
    var currentDownloads = 0

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    @DrawableRes
    const val imgDone = R.drawable.rddone

    @DrawableRes
    const val imgDownloading = R.drawable.rdload

    @DrawableRes
    const val imgPaused = R.drawable.rdpause

    @DrawableRes
    const val imgStopped = R.drawable.rderror

    @DrawableRes
    const val imgError = R.drawable.rderror

    @DrawableRes
    const val pressToPauseIcon = R.drawable.ic_baseline_pause_24

    @DrawableRes
    const val pressToResumeIcon = R.drawable.ic_baseline_play_arrow_24

    @DrawableRes
    const val pressToStopIcon = R.drawable.exo_icon_stop

    enum class DownloadType {
        IsPaused,
        IsDownloading,
        IsDone,
        IsFailed,
        IsStopped,
    }

    enum class DownloadActionType {
        Pause,
        Resume,
        Stop,
    }

    data class DownloadEpisodeMetadata(
        val id: Int,
        val mainName: String,
        val sourceApiName: String?,
        val poster: String?,
        val name: String?,
        val season: Int?,
        val episode: Int?
    )

    data class DownloadItem(
        val source: String,
        val folder: String?,
        val ep: DownloadEpisodeMetadata,
        val links: List<ExtractorLink>
    )

    private const val SUCCESS_DOWNLOAD_DONE = 1
    private const val SUCCESS_STOPPED = 2
    private const val ERROR_DELETING_FILE = -1
    private const val ERROR_FILE_NOT_FOUND = -2
    private const val ERROR_OPEN_FILE = -3
    private const val ERROR_TOO_SMALL_CONNECTION = -4
    private const val ERROR_WRONG_CONTENT = -5
    private const val ERROR_CONNECTION_ERROR = -6

    val events = Event<Pair<Int, DownloadActionType>>()
    private val downloadQueue = LinkedList<DownloadItem>()


    private var hasCreatedNotChanel = false
    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = CHANNEL_NAME //getString(R.string.channel_name)
            val descriptionText = CHANNEL_DESCRIPT//getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val cachedBitmaps = hashMapOf<String, Bitmap>()
    private fun Context.getImageBitmapFromUrl(url: String): Bitmap? {
        if (cachedBitmaps.containsKey(url)) {
            return cachedBitmaps[url]
        }

        val bitmap = Glide.with(this)
            .asBitmap()
            .load(url).into(720, 720)
            .get()
        if (bitmap != null) {
            cachedBitmaps[url] = bitmap
        }
        return null
    }

    private fun createNotification(
        context: Context,
        source: String?,
        linkName: String?,
        ep: DownloadEpisodeMetadata,
        state: DownloadType,
        progress: Long,
        total: Long,
    ) {
        main { // DON'T WANT TO SLOW IT DOWN
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setAutoCancel(true)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(ep.mainName)
                .setSmallIcon(
                    when (state) {
                        DownloadType.IsDone -> imgDone
                        DownloadType.IsDownloading -> imgDownloading
                        DownloadType.IsPaused -> imgPaused
                        DownloadType.IsFailed -> imgError
                        DownloadType.IsStopped -> imgStopped
                    }
                )

            if (ep.sourceApiName != null) {
                builder.setSubText(ep.sourceApiName)
            }

            if (source != null) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    data = source.toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, 0)
                builder.setContentIntent(pendingIntent)
            }

            if (state == DownloadType.IsDownloading || state == DownloadType.IsPaused) {
                builder.setProgress(total.toInt(), progress.toInt(), false)
            }

            val rowTwoExtra = if (ep.name != null) " - ${ep.name}\n" else ""
            val rowTwo = if (ep.season != null && ep.episode != null) {
                "S${ep.season}:E${ep.episode}" + rowTwoExtra
            } else if (ep.episode != null) {
                "Episode ${ep.episode}" + rowTwoExtra
            } else {
                (ep.name ?: "") + ""
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ep.poster != null) {
                    val poster = withContext(Dispatchers.IO) {
                        context.getImageBitmapFromUrl(ep.poster)
                    }
                    if (poster != null)
                        builder.setLargeIcon(poster)
                }

                val progressPercentage = progress * 100 / total
                val progressMbString = "%.1f".format(progress / 1000000f)
                val totalMbString = "%.1f".format(total / 1000000f)

                val bigText =
                    if (state == DownloadType.IsDownloading || state == DownloadType.IsPaused) {
                        (if (linkName == null) "" else "$linkName\n") + "$rowTwo\n$progressPercentage % ($progressMbString MB/$totalMbString MB)"
                    } else if (state == DownloadType.IsFailed) {
                        "Download Failed - $rowTwo"
                    } else if (state == DownloadType.IsDone) {
                        "Download Done - $rowTwo"
                    } else {
                        "Download Stopped - $rowTwo"
                    }

                val bodyStyle = NotificationCompat.BigTextStyle()
                bodyStyle.bigText(bigText)
                builder.setStyle(bodyStyle)
            } else {
                val txt = if (state == DownloadType.IsDownloading || state == DownloadType.IsPaused) {
                    rowTwo
                } else if (state == DownloadType.IsFailed) {
                    "Download Failed - $rowTwo"
                } else if (state == DownloadType.IsDone) {
                    "Download Done - $rowTwo"
                } else {
                    "Download Stopped - $rowTwo"
                }

                builder.setContentText(txt)
            }

            if ((state == DownloadType.IsDownloading || state == DownloadType.IsPaused) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val actionTypes: MutableList<DownloadActionType> = ArrayList()
                // INIT
                if (state == DownloadType.IsDownloading) {
                    actionTypes.add(DownloadActionType.Pause)
                    actionTypes.add(DownloadActionType.Stop)
                }

                if (state == DownloadType.IsPaused) {
                    actionTypes.add(DownloadActionType.Resume)
                    actionTypes.add(DownloadActionType.Stop)
                }

                // ADD ACTIONS
                for ((index, i) in actionTypes.withIndex()) {
                    val actionResultIntent = Intent(context, VideoDownloadService::class.java)

                    actionResultIntent.putExtra(
                        "type", when (i) {
                            DownloadActionType.Resume -> "resume"
                            DownloadActionType.Pause -> "pause"
                            DownloadActionType.Stop -> "stop"
                        }
                    )

                    actionResultIntent.putExtra("id", ep.id)

                    val pending: PendingIntent = PendingIntent.getService(
                        context, 4337 + index + ep.id,
                        actionResultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                    )

                    builder.addAction(
                        NotificationCompat.Action(
                            when (i) {
                                DownloadActionType.Resume -> pressToResumeIcon
                                DownloadActionType.Pause -> pressToPauseIcon
                                DownloadActionType.Stop -> pressToStopIcon
                            }, when (i) {
                                DownloadActionType.Resume -> "Resume"
                                DownloadActionType.Pause -> "Pause"
                                DownloadActionType.Stop -> "Stop"
                            }, pending
                        )
                    )
                }
            }

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            with(NotificationManagerCompat.from(context)) {
                // notificationId is a unique int for each notification that you must define
                notify(ep.id, builder.build())
            }
        }
    }

    private const val reservedChars = "|\\?*<\":>+[]/\'"
    fun sanitizeFilename(name: String): String {
        var tempName = name
        for (c in reservedChars) {
            tempName = tempName.replace(c, ' ')
        }
        return tempName.replace("  ", " ").trim(' ')
    }

    private const val reservedCharsPath = "|\\?*<\":>+[]\'"
    fun sanitizePath(name: String): String {
        var tempName = name
        for (c in reservedCharsPath) {
            tempName = tempName.replace(c, ' ')
        }
        return tempName.replace("  ", " ").trim(' ')
    }

    private fun downloadSingleEpisode(
        context: Context,
        source: String?,
        folder: String?,
        ep: DownloadEpisodeMetadata,
        link: ExtractorLink
    ): Int {
        val name = sanitizeFilename(ep.name ?: "Episode ${ep.episode}")
        val path = "${Environment.DIRECTORY_DOWNLOADS}/${if (folder == null) "" else "$folder/"}$name.mp4"
        var resume = false

        // IF RESUME, DON'T DELETE FILE, CONTINUE, RECREATE IF NOT FOUND
        // IF NOT RESUME CREATE FILE
        val tempFile = DocumentFileCompat.fromSimplePath(context, basePath = path)
        val fileExists = tempFile?.exists() ?: false

        if (!fileExists) resume = false
        if (fileExists && !resume) {
            if (tempFile?.delete() == false) { // DELETE FAILED ON RESUME FILE
                return ERROR_DELETING_FILE
            }
        }

        val dFile =
            if (resume) tempFile
            else DocumentFileCompat.createFile(context, basePath = path, mimeType = "video/mp4")

        // END OF FILE CREATION

        if (dFile == null || !dFile.exists()) {
            return ERROR_FILE_NOT_FOUND
        }

        // OPEN FILE
        val fileStream = dFile.openOutputStream(context, resume) ?: return ERROR_OPEN_FILE

        // CONNECT
        val connection: URLConnection = URL(link.url).openConnection()

        // SET CONNECTION SETTINGS
        connection.connectTimeout = 10000
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (link.referer.isNotEmpty()) connection.setRequestProperty("Referer", link.referer)
        if (resume) connection.setRequestProperty("Range", "bytes=${dFile.length()}-")
        val resumeLength = (if (resume) dFile.length() else 0)

        // ON CONNECTION
        connection.connect()
        val contentLength = connection.contentLength
        val bytesTotal = contentLength + resumeLength
        if (bytesTotal < 5000000) return ERROR_TOO_SMALL_CONNECTION // DATA IS LESS THAN 5MB, SOMETHING IS WRONG

        // Could use connection.contentType for mime types when creating the file,
        // however file is already created and players don't go of file type

        // https://stackoverflow.com/questions/23714383/what-are-all-the-possible-values-for-http-content-type-header
        // might receive application/octet-stream
        /*if (!connection.contentType.isNullOrEmpty() && !connection.contentType.startsWith("video")) {
            return ERROR_WRONG_CONTENT // CONTENT IS NOT VIDEO, SHOULD NEVER HAPPENED, BUT JUST IN CASE
        }*/

        // READ DATA FROM CONNECTION
        val connectionInputStream: InputStream = BufferedInputStream(connection.inputStream)
        val buffer = ByteArray(1024)
        var count: Int
        var bytesDownloaded = resumeLength


        var isPaused = false
        var isStopped = false
        var isDone = false
        var isFailed = false

        // TO NOT REUSE CODE
        fun updateNotification() {
            val type = when {
                isDone -> DownloadType.IsDone
                isStopped -> DownloadType.IsStopped
                isFailed -> DownloadType.IsFailed
                isPaused -> DownloadType.IsPaused
                else -> DownloadType.IsDownloading
            }
            createNotification(
                context,
                source,
                link.name,
                ep,
                type,
                bytesDownloaded,
                bytesTotal
            )
        }

        fun onEvent(event: Pair<Int, DownloadActionType>) {
            if (event.first == ep.id) {
                when (event.second) {
                    DownloadActionType.Pause -> {
                        isPaused = true; updateNotification()
                    }
                    DownloadActionType.Stop -> {
                        isStopped = true; updateNotification()
                    }
                    DownloadActionType.Resume -> {
                        isPaused = false; updateNotification()
                    }
                }
            }
        }

        events += ::onEvent

        // UPDATE DOWNLOAD NOTIFICATION
        val notificationCoroutine = main {
            while (true) {
                if (!isPaused) {
                    updateNotification()
                }
                for (i in 1..10) {
                    delay(100)
                }
            }
        }

        // THE REAL READ
        try {
            while (true) {
                count = connectionInputStream.read(buffer)
                if (count < 0) break
                bytesDownloaded += count
                while (isPaused) {
                    sleep(100)
                    if (isStopped) {
                        break
                    }
                }
                if (isStopped) {
                    break
                }
                fileStream.write(buffer, 0, count)
            }
        } catch (e: Exception) {
            isFailed = true
            updateNotification()
        }

        // REMOVE AND EXIT ALL
        events -= ::onEvent
        fileStream.closeStream()
        connectionInputStream.closeStream()
        notificationCoroutine.cancel()

        // RETURN MESSAGE
        return when {
            isFailed -> {
                ERROR_CONNECTION_ERROR
            }
            isStopped -> {
                dFile.delete()
                SUCCESS_STOPPED
            }
            else -> {
                isDone = true
                updateNotification()
                SUCCESS_DOWNLOAD_DONE
            }
        }
    }

    private fun downloadCheck(context: Context) {
        if (currentDownloads < maxConcurrentDownloads && downloadQueue.size > 0) {
            val item = downloadQueue.removeFirst()
            currentDownloads++
            try {
                main {
                    for (link in item.links) {
                        val connectionResult = withContext(Dispatchers.IO) {
                            normalSafeApiCall {
                                downloadSingleEpisode(context, item.source, item.folder, item.ep, link)
                            }
                        }
                        if (connectionResult != null && connectionResult > 0) { // SUCCESS
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                logError(e)
            } finally {
                currentDownloads--
                downloadCheck(context)
            }
        }
    }

    fun downloadEpisode(
        context: Context,
        source: String,
        folder: String?,
        ep: DownloadEpisodeMetadata,
        links: List<ExtractorLink>
    ) {
        val validLinks = links.filter { !it.isM3u8 }
        if (validLinks.isNotEmpty()) {
            downloadQueue.addLast(DownloadItem(source, folder, ep, validLinks))
            downloadCheck(context)
        }
    }
}