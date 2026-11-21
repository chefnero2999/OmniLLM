package com.omni.llm.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.llm.OmniApp

/**
 * DownloadManager finishes the download in its own process, so this is the only reliable signal
 * that the file is complete — polling alone can be missed if the app was killed. Verification
 * runs off the main thread via the repository's scope.
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id <= 0) return
        val app = context.applicationContext as? OmniApp ?: return
        runCatching { app.container.models.downloads.onDownloadComplete(id) }
    }
}
