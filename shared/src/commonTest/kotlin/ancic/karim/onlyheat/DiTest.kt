package ancic.karim.onlyheat

import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.Reading
import ancic.karim.onlyheat.data.ReadingDao
import ancic.karim.onlyheat.feature.rooms.HomeViewModel
import com.russhwolf.settings.Settings
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test


class DiTest {
    /** Koin only finds a missing binding at runtime: resolve everything the app asks for. */
    @Test
    fun everyDependencyResolves() {
        val noDb = module { // the real one writes a file in the home directory
            single<ReadingDao> {
                object : ReadingDao {
                    override suspend fun insert(readings: List<Reading>) {}
                    override suspend fun averaged(roomId: String, from: Long, bucket: Long) = emptyList<Reading>()
                    override suspend fun oldest(): Long? = null
                }
            }
        }
        val koin = koinApplication { modules(appModule, noDb) }.koin
        koin.get<HeatApi>()
        koin.get<Settings>()
        koin.get<HomeViewModel> { parametersOf("key") }
        koin.close()
    }
}
