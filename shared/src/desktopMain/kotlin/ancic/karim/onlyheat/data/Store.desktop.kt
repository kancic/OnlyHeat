package ancic.karim.onlyheat.data

import androidx.room3.Room
import androidx.room3.RoomDatabase
import org.koin.core.scope.Scope
import java.io.File


internal actual fun Scope.historyDbBuilder(): RoomDatabase.Builder<HistoryDb> {
    val dir = File(System.getProperty("user.home"), ".onlyheat").apply { mkdirs() }
    return Room.databaseBuilder<HistoryDb>(File(dir, "history.db").absolutePath)
}
