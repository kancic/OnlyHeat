package ancic.karim.onlyheat

import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

internal actual fun historyDbBuilder(): RoomDatabase.Builder<HistoryDb> {
    val dir = File(System.getProperty("user.home"), ".onlyheat").apply { mkdirs() }
    return Room.databaseBuilder<HistoryDb>(File(dir, "history.db").absolutePath)
}
