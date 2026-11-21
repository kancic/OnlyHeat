package ancic.karim.onlyheat

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase

private lateinit var appContext: Context

/** Call before touching [history] from any Android entry point (activity, worker). */
fun initHistory(context: Context) { appContext = context.applicationContext }

internal actual fun historyDbBuilder(): RoomDatabase.Builder<HistoryDb> =
    Room.databaseBuilder<HistoryDb>(appContext, appContext.getDatabasePath("history.db").absolutePath)
