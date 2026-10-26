// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONObject
import java.math.BigInteger

/** No session secrets are retained in the durable outbox. */
data class HistoryCheckpoint(val progress: BackupProgress,val bookType: Int,val episodeCount: Int) {
    fun json(): JSONObject {
        require(bookType in 2..5 && episodeCount>0 && progress.episodeIndex in 0 until episodeCount)
        require(progress.series.id.matches(Regex("[0-9]{1,30}")) && progress.episodeId.matches(Regex("[0-9]{1,30}")))
        return JSONObject().put("book_id",BigInteger(progress.series.id)).put("book_type",bookType)
            .put("vid",BigInteger(progress.episodeId)).put("vid_index",progress.episodeIndex+1)
            .put("current_play_position",progress.position.coerceAtLeast(0)).put("episode_cnt",episodeCount)
            .put("read_timestamp_ms",progress.updatedAt).put("update_timestamp_ms",progress.updatedAt)
            .put("is_delete",false).put("use_soft_delete",false).put("is_listen_mode",false)
    }
    /** Official progress/upload envelope uses string identifiers, separate from numeric history IDs. */
    fun progressJson(): JSONObject {
        json() // shared identity/index validation
        return JSONObject().put("book_id",progress.series.id).put("item_id",progress.episodeId).put("book_type",bookType)
            .put("channel_id",0).put("cur_channel_id",0).put("check_timestamp",false)
            .put("current_play_time",progress.position.coerceAtLeast(0)).put("read_timestamp_ms",progress.updatedAt)
            .put("vid_index",0).put("player_cumulative_total_duration",0)
            .put("is_listen_mode",false).put("is_local_book",false).put("listen_and_read",false)
            .put("page_index",0).put("page_progress_rate",0).put("paragraph_offset",0).put("progress_type",0).put("tone_id",0)
    }
    fun stored()=JSONObject().put("progress",progress.json()).put("bookType",bookType).put("episodeCount",episodeCount)
    companion object {
        fun restore(o: JSONObject): HistoryCheckpoint {
            val p=o.getJSONObject("progress")
            return HistoryCheckpoint(BackupProgress(Series.fromJson(p.getJSONObject("series")),p.getString("episodeId"),
                p.getInt("episodeIndex"),p.getLong("position"),p.getLong("duration"),p.getBoolean("completed"),p.getLong("updatedAt")),
                o.getInt("bookType"),o.getInt("episodeCount"))
        }
    }
}
