package ancic.karim.onlyheat

import ancic.karim.onlyheat.ac.AcViewModel
import ancic.karim.onlyheat.data.HeatApi
import ancic.karim.onlyheat.data.historyDbBuilder
import ancic.karim.onlyheat.feature.history.HistoryViewModel
import ancic.karim.onlyheat.feature.rooms.HomeViewModel
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.russhwolf.settings.Settings
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module
import org.koin.mp.KoinPlatform


/** One of each for the whole process: UI, widget and worker share the HTTP client and the database. */
val appModule = module {
    single { HeatApi() }
    single { Settings() }
    single { historyDbBuilder().setDriver(BundledSQLiteDriver()).build().readings() }
    viewModel { (key: String) -> HomeViewModel(get(), get(), key, get()) }
    viewModel { (key: String) -> AcViewModel(get(), key) }
    viewModel { (key: String) -> HistoryViewModel(get(), get(), get(), key) }
}

/** Starts Koin once per process; later calls (iOS recreating its view controller) do nothing. */
fun initKoin(config: KoinAppDeclaration = {}) {
    if (KoinPlatform.getKoinOrNull() == null) startKoin { config(); modules(appModule) }
}
