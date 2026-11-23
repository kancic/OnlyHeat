package ancic.karim.onlyheat

import androidx.room3.Room
import androidx.room3.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.scope.Scope
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun Scope.historyDbBuilder(): RoomDatabase.Builder<HistoryDb> {
    val docs = NSFileManager.defaultManager.URLForDirectory(NSDocumentDirectory, NSUserDomainMask, null, true, null)
    return Room.databaseBuilder<HistoryDb>(requireNotNull(docs?.path) + "/history.db")
}
