package ancic.karim.onlyheat.data

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import org.koin.core.scope.Scope
import org.koin.mp.KoinPlatform


internal fun androidContextOrNull(): Context? = KoinPlatform.getKoinOrNull()?.getOrNull()

internal actual fun Scope.historyDbBuilder(): RoomDatabase.Builder<HistoryDb> {
    val context = get<Context>()
    return Room.databaseBuilder<HistoryDb>(context, context.getDatabasePath("history.db").absolutePath)
}
