package ancic.karim.onlyheat

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscoverTest {
    @Test fun recognizesTheControllerLoginPageInsteadOfABrandMention() {
        val title = "<title>Dashboard | TermoTehnik PI\n</title>"
        val login = """
            <form id="loginForm">
                <input name="loginInput1"><input name="loginInput2">
                <input name="loginInput3"><input name="loginInput4">
            </form>
        """.trimIndent()
        assertFalse(looksLikeDashboard("<title>Router dashboard</title><p>TermoTehnik</p>"))
        assertFalse(looksLikeDashboard(title))
        assertFalse(looksLikeDashboard(title + login.replace("loginInput4", "unrelated")))
        assertTrue(looksLikeDashboard(title + login))
        assertTrue(looksLikeDashboard("<title>Dashboard | ThermoTechnik ADVANCE</title>$login")) // firmware 1.0.9.2.0
    }

    @Test fun readsTheFirmwareVersionNotTheUnitName() {
        val unit = """<option value="127.0.1.1" >TTO Advance-A1B2C3
 (*)</option>"""
        assertEquals("1.0.9.2.0", dashboardFirmware("$unit<p class=\"mt-3 text-center\">TTO Advance 1.0.9.2.0<br>S/N: TTO-1</p>"))
        assertEquals("1.0.7.0.8", dashboardFirmware("$unit<p><br>TTO Advance 1.0.7.0.8<br>S/N: TTO-1</p>"))
        assertNull(dashboardFirmware(unit))
    }

    @Test fun thermalIntelligenceOnlyFrom1092() {
        assertTrue(hasThermalIntelligence(null))
        assertTrue(hasThermalIntelligence("1.0.9.2.0"))
        assertTrue(hasThermalIntelligence("1.0.10.0.0"))
        assertTrue(hasThermalIntelligence("1.1"))
        assertFalse(hasThermalIntelligence("1.0.7.0.8"))
        assertFalse(hasThermalIntelligence("1.0.9.1.9"))
        assertFalse(hasThermalIntelligence("1.0.9"))
    }

    @Test fun showsTheLocalUnitNameRatherThanAnotherDeviceInTheDashboard() {
        val local = """<option value="127.0.1.1" >TTO Advance-A1B2C3
            | (*)</option>""".trimMargin()
        val other = """<option value="172.0.1.1">TTO Advance-ABC123</option>"""
        assertEquals("TTO Advance-A1B2C3", dashboardName(other + local))
        assertNull(dashboardName(other))
        assertNull(dashboardName(local + local))
    }

    @Test fun lateHostsGetAProbeSlotAndTheirOwnTimeout() = runTest {
        val hosts = (1..254).map { "192.168.100.$it" }
        var active = 0
        var peak = 0
        val found = firstDashboard(hosts) {
            active++
            peak = maxOf(peak, active)
            try {
                delay(2_000)
                it == hosts.last()
            } finally {
                active--
            }
        }
        assertEquals(hosts.last(), found)
        assertTrue(peak in 2 until 64)
        assertEquals(0, active)
    }

    @Test fun lanDestinationRequiresAnIndependentlyConfirmedAddress() = runTest {
        val hotspot = "192.168.42.1"
        val lan = DashboardTarget("192.168.100.240", "TTO Advance-A1B2C3")
        val direct = DashboardTarget(hotspot)
        val probed = mutableListOf<DashboardTarget>()
        suspend fun probe(target: DashboardTarget): Boolean {
            probed += target
            return target == lan
        }
        assertNull(dashboardAddress(hotspot, { null }, probe = ::probe))
        assertNull(dashboardAddress(hotspot, { DashboardTarget("192.168.100.3@elsewhere") }, probe = ::probe))
        assertNull(dashboardAddress(hotspot, { lan.copy(name = "TTO Advance-ABC123") }, probe = ::probe))
        assertEquals(lan, dashboardAddress(hotspot, { lan }, probe = ::probe))
        assertEquals(listOf(direct, direct, direct, lan.copy(name = "TTO Advance-ABC123"), direct, lan), probed)
    }

    @Test fun reusesTheSavedControllerWithoutAskingAndAsksWhenItMoved() = runTest {
        val hotspot = "192.168.42.1"
        val saved = DashboardTarget("192.168.100.49", "TTO Advance-A1B2C3", "8:103")
        val value = saved.rememberedValue()
        assertEquals(saved, savedLanTarget(value))
        assertEquals(saved, dashboardAddress(
            hotspot,
            confirmedLanAddress = { error("Should not ask about the verified saved controller") },
            remembered = savedLanTarget(value),
        ) { it == saved })
        val replacement = saved.copy(host = "192.168.100.50")
        assertEquals(replacement, dashboardAddress(
            hotspot,
            confirmedLanAddress = { replacement },
            remembered = savedLanTarget(value),
        ) { it == replacement })
    }

    @Test fun anUnavailableSelectionDoesNotFallBackToAnotherController() = runTest {
        val selected = DashboardTarget("192.168.100.49", "TTO Advance-A1B2C3", "8:103")
        val saved = DashboardTarget("192.168.100.50", "TTO Advance-ABC123", "8:103")
        val probed = mutableListOf<DashboardTarget>()
        assertNull(dashboardAddress(
            HOTSPOT,
            confirmedLanAddress = { error("A new selection must come from the login screen") },
            remembered = saved,
            selected = selected,
        ) {
            probed += it
            it != selected
        })
        assertEquals(listOf(selected), probed)
    }

    @Test fun savesTheNameEvenWhenThePlatformCannotIdentifyTheWifiConnection() {
        val target = DashboardTarget("192.168.100.49", "TTO Advance-A1B2C3")
        val value = target.rememberedValue()
        assertEquals(target, savedLanTarget(value))
    }

    @Test fun retryCanUseTheHotspotAfterSwitchingWifi() = runTest {
        val hotspot = "192.168.42.1"
        var connected = false
        val target = DashboardTarget(hotspot)
        assertEquals(target, dashboardAddress(hotspot, {
            connected = true
            target
        }) { it == target && connected })
    }

    @Test fun rebootWaitCanRecoverAtANewLocalAddressWithCloudDown() = runTest {
        var oldChecks = 0
        var newChecks = 0
        assertTrue(waitForRecovery(
            localUp = { oldChecks++ == 0 },
            rediscovered = { ++newChecks == 2 },
            cloudUp = { false },
        ))
        assertEquals(4, oldChecks) // up once, then three misses in a row
        assertEquals(2, newChecks)
    }

    @Test fun oneSlowAnswerBeforeTheRebootIsNotALoss() = runTest {
        var checks = 0
        val answers = listOf(true, false, true, true, false, false, false) // a single miss, then the real reboot
        var rediscoveredAfter = -1
        assertTrue(waitForRecovery(
            localUp = { answers[checks++] },
            rediscovered = { rediscoveredAfter = checks; true },
            cloudUp = { false },
        ))
        assertEquals(answers.size, rediscoveredAfter)
    }

    @Test fun dismissingThePickerEndsTheWait() = runTest {
        assertFalse(waitForRecovery(localUp = { false }, rediscovered = { null }, cloudUp = { false }))
        assertTrue(testScheduler.currentTime < 60_000L)
    }

    @Test fun theHotspotMustBeTheSavedControllerOnceOneIsKnown() = runTest {
        val hotspot = "192.168.42.1"
        val name = "TTO Advance-A1B2C3"
        val probed = mutableListOf<DashboardTarget>()
        assertNull(dashboardAddress(hotspot, { null }, hotspotName = name) { probed += it; it.name == null })
        assertEquals(listOf(DashboardTarget(hotspot, name)), probed)
    }

    @Test fun aDifferentControllerFromThePickerCannotReceiveTheSavedPin() = runTest {
        val name = "TTO Advance-A1B2C3"
        val other = DashboardTarget("192.168.100.50", "TTO Advance-ABC123")
        val probed = mutableListOf<DashboardTarget>()
        assertNull(dashboardAddress(HOTSPOT, { other }, remembered = other, hotspotName = name) {
            probed += it
            it == other
        })
        assertEquals(listOf(DashboardTarget(HOTSPOT, name)), probed)

        val moved = other.copy(name = name)
        assertEquals(moved, dashboardAddress(HOTSPOT, { moved }, hotspotName = name) { it == moved })
    }

    @Test fun cloudRecoveryCancelsPendingLocalVerification() = runTest {
        var cloudChecks = 0
        var localCancelled = false
        assertTrue(waitForRecovery(
            localUp = { false },
            rediscovered = {
                try {
                    awaitCancellation()
                } finally {
                    localCancelled = true
                }
            },
            cloudUp = { cloudChecks++ > 5 }, // after the local side has seen three misses
        ))
        assertTrue(localCancelled)
    }

    @Test fun rebootWaitIsBoundedEvenIfVerificationNeverReturns() = runTest {
        assertFalse(waitForRecovery(
            localUp = { false },
            rediscovered = { awaitCancellation() },
            cloudUp = { false },
        ))
        assertEquals(240_000L, testScheduler.currentTime)
    }

    @Test fun scansPrivateNetworksOnceWithinTheirPrefix() {
        val hosts = scanHosts(listOf(
            "192.168.100.42" to 24, "192.168.100.7" to 24, // same /24 twice
            "10.0.0.20" to 28, // small subnet: only 10.0.0.17..30
            "172.16.5.1" to 16, // big subnet: only this device's /24
            "172.32.0.1" to 24, "8.8.8.8" to 24, "junk" to 24, // not private / not an address
        ))
        assertEquals((1..254).map { "192.168.100.$it" } + (17..30).map { "10.0.0.$it" } + (1..254).map { "172.16.5.$it" }, hosts)
    }
}
