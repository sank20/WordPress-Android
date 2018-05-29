package org.wordpress.android.ui.activitylog

import androidx.work.Constraints.Builder
import androidx.work.Data
import androidx.work.NetworkType.NOT_REQUIRED
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.Worker.WorkerResult.FAILURE
import androidx.work.Worker.WorkerResult.RETRY
import androidx.work.Worker.WorkerResult.SUCCESS
import androidx.work.ktx.PeriodicWorkRequestBuilder
import org.wordpress.android.WordPress
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.ActivityLogActionBuilder
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.activity.RewindStatusModel.Rewind.Status.FINISHED
import org.wordpress.android.fluxc.store.ActivityLogStore
import org.wordpress.android.fluxc.store.ActivityLogStore.FetchRewindStatePayload
import org.wordpress.android.fluxc.store.SiteStore
import org.wordpress.android.ui.activitylog.RewindStateProgressWorkerController.RewindStateProgressWorker.Companion.RESTORE_ID_KEY
import org.wordpress.android.ui.activitylog.RewindStateProgressWorkerController.RewindStateProgressWorker.Companion.SITE_ID_KEY
import org.wordpress.android.ui.activitylog.RewindStateProgressWorkerController.RewindStateProgressWorker.Companion.TAG
import java.util.concurrent.TimeUnit.SECONDS
import javax.inject.Inject

class RewindStateProgressWorkerController
@Inject
constructor() {
    fun startWorker(site: SiteModel, restoreId: Long) {
        val workManager = WorkManager.getInstance()
        if (workManager.getStatusesByTag(TAG).value != null) {
            workManager.cancelAllWorkByTag(TAG)
        }
        val networkConstraints = Builder()
                .setRequiredNetworkType(NOT_REQUIRED)
                .build()
        val data = Data.Builder().putInt(SITE_ID_KEY, site.id).putLong(RESTORE_ID_KEY, restoreId).build()
        val work = PeriodicWorkRequestBuilder<RewindStateProgressWorker>(10, SECONDS)
                .setConstraints(networkConstraints)
                .addTag(TAG)
                .setInputData(data)
                .build()
        workManager.enqueue(work)
    }

    fun cancelWorker() {
        WorkManager.getInstance().cancelAllWorkByTag(TAG)
    }

    class RewindStateProgressWorker : Worker() {
        companion object {
            const val TAG = "progressWorkerTag"
            const val SITE_ID_KEY = "SITE_ID"
            const val RESTORE_ID_KEY = "RESTORE_ID"
        }

        @Inject lateinit var activityLogStore: ActivityLogStore
        @Inject lateinit var siteStore: SiteStore
        @Inject lateinit var dispatcher: Dispatcher

        override fun doWork(): WorkerResult {
            (applicationContext as WordPress).component().inject(this)
            val siteId = inputData.getInt(SITE_ID_KEY, -1)
            val restoreId = inputData.getLong(RESTORE_ID_KEY, -1L)
            if (siteId != -1 && restoreId != -1L) {
                val site = siteStore.getSiteByLocalId(siteId)
                val rewindStatusForSite = activityLogStore.getRewindStatusForSite(site)
                val rewind = rewindStatusForSite?.rewind
                rewind?.let {
                    if (rewind.status == FINISHED && rewind.restoreId == restoreId) {
                        return SUCCESS
                    } else if (rewind.restoreId != restoreId) {
                        return FAILURE
                    }
                }
                dispatcher.dispatch(ActivityLogActionBuilder.newFetchRewindStateAction(FetchRewindStatePayload(site)))
                return RETRY
            }
            return FAILURE
        }
    }
}