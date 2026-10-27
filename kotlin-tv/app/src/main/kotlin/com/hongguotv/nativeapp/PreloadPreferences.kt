package com.hongguotv.nativeapp
import android.content.Context
import com.hongguotv.core.PreloadMode
import com.hongguotv.core.PreloadSettings
internal class PreloadPreferences(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("preload-settings-v1",Context.MODE_PRIVATE)
    fun load()=PreloadSettings(
        runCatching { PreloadMode.valueOf(prefs.getString("mode","TIME")!!) }.getOrDefault(PreloadMode.TIME),
        prefs.getInt("minutes",5),prefs.getInt("episodes",10),prefs.getInt("checkMinutes",1),prefs.getInt("checkEpisodes",1)
    ).normalized()
    fun save(config: PreloadSettings) { val c=config.normalized()
        prefs.edit().putString("mode",c.mode.name).putInt("minutes",c.minutes).putInt("episodes",c.episodes)
            .putInt("checkMinutes",c.checkMinutes).putInt("checkEpisodes",c.checkEpisodes).apply()
    }
}
