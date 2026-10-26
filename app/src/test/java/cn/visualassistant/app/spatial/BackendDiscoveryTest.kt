package cn.visualassistant.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The computer's address moves whenever it rejoins the phone's hotspot, so the app has to be
 * able to find it again without the user typing an address.
 */
class BackendDiscoveryTest {

    @Test fun theSubnetHostsCoverTheWholeTwentyFour() {
        // 192.168.43.1 is the phone itself on a hotspot; the computer gets another address.
        val hosts = BackendDiscovery.hostsForPrefix("192.168.43")
        assertEquals(253, hosts.size)
        assertTrue(hosts.first() == "192.168.43.2")
        assertTrue(hosts.last() == "192.168.43.254")
        assertTrue(!hosts.contains("192.168.43.1"))
    }

    @Test fun theHostListNeverIncludesThePhoneItself() {
        val hosts = BackendDiscovery.hostsForPrefix("10.0.0")
        assertTrue(!hosts.contains("10.0.0.1"))
        assertTrue(!hosts.contains("10.0.0.0"))
        assertTrue(!hosts.contains("10.0.0.255"))
    }

    @Test fun theFoundComputerIsTheOneThatAnswers() {
        val hosts = listOf("192.168.43.2", "192.168.43.7", "192.168.43.9")
        val found = BackendDiscovery.findBackend(hosts, listOf(38400), 200) { host, port ->
            host == "192.168.43.7" && port == 38400
        }
        assertEquals("192.168.43.7", found)
    }

    @Test fun aSecondPortIsTriedWhenTheFirstIsClosed() {
        val hosts = listOf("192.168.43.5")
        val found = BackendDiscovery.findBackend(hosts, listOf(38400, 38401), 200) { host, port ->
            host == "192.168.43.5" && port == 38401
        }
        assertEquals("192.168.43.5", found)
    }

    @Test fun nothingIsFoundWhenNoHostAnswers() {
        val hosts = listOf("192.168.43.2", "192.168.43.3")
        assertNull(BackendDiscovery.findBackend(hosts, listOf(38400), 200) { _, _ -> false })
    }

    @Test fun anEmptyListIsHandledWithoutSearching() {
        assertNull(BackendDiscovery.findBackend(emptyList(), listOf(38400), 200) { _, _ -> true })
        assertNull(BackendDiscovery.findBackend(listOf("192.168.43.2"), emptyList(), 200) { _, _ -> true })
    }

    @Test fun aProbeThatThrowsCountsAsNoAnswer() {
        val found = BackendDiscovery.findBackend(listOf("192.168.43.4"), listOf(38400), 200) { _, _ ->
            throw IllegalStateException("network is unreachable")
        }
        assertNull(found)
    }

    @Test fun theSearchBudgetCoversEveryRoundOfProbing() {
        // A phone scans 253 hosts on two ports. The budget has to cover all of those rounds:
        // the addresses that answer slowly are exactly the ones a firewall drops, and a search
        // that gives up one timeout early misses the answer entirely.
        val probes = 253 * 2
        val budget = BackendDiscovery.searchBudgetMillis(probes, 250)
        val singleTimeoutBudget = 250L + 3_000L
        assertTrue("the budget was cut short: $budget ms", budget > singleTimeoutBudget)
        assertTrue("the budget must also leave room for a slow answer", budget > probes / 64 * 250)
    }

    @Test fun everyAddressIsTriedBeforeTheSearchGivesUp() {
        val hosts = (2..254).map { "192.168.43.$it" }
        val tried = java.util.Collections.synchronizedList(ArrayList<String>())
        val found = BackendDiscovery.findBackend(hosts, listOf(38400), 200) { host, _ ->
            tried.add(host)
            host == "192.168.43.254"
        }
        assertEquals("192.168.43.254", found)
        assertTrue("the last address must be tried", tried.contains("192.168.43.254"))
        assertEquals(hosts.size, tried.size)
    }
}
