package ancic.karim.onlyheat

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreTest {
    @Test fun averagesIntoBucketsAndIgnoresDuplicates() = runTest {
        val dao = Room.inMemoryDatabaseBuilder<HistoryDb>().setDriver(BundledSQLiteDriver()).build().readings()
        val r = listOf(
            Reading("1", 3600, 20.0, 22.0), Reading("1", 5400, 21.0, null), // one hour: target avg skips the null
            Reading("1", 7200, 23.0, 23.0),
            Reading("2", 3600, 10.0, 10.0), // other room
            Reading("1", 0, 99.0, 99.0), // before `from`
        )
        dao.insert(r)
        dao.insert(listOf(Reading("1", 3600, 50.0, 50.0))) // same time again: kept the first
        assertEquals(
            listOf(Reading("1", 3600, 20.5, 22.0), Reading("1", 7200, 23.0, 23.0)),
            dao.averaged("1", from = 1, bucket = 3600),
        )
        assertEquals(0L, dao.oldest())
    }
}
