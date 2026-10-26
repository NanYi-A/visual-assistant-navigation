package cn.visualassistant.app.spatial

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the computer that runs the backends when its address has changed.
 *
 * The usual setup is a phone hotspot with the computer joining it. The phone hands out the
 * computer's address by DHCP, so every restart can move it, and an address typed in once
 * stops working. Scanning the phone's own subnet for a machine that listens on the backend
 * ports survives that, and needs nothing on the computer side.
 */
object BackendDiscovery {

    /**
     * Every host of the /24 subnets this device is on, except the device itself.
     *
     * A hotspot gives the phone 192.168.43.1 and the computer something in 192.168.43.0/24,
     * so one /24 covers the common case without the scan growing into the whole internet.
     */
    fun localSubnetHosts(): List<String> {
        val hosts = LinkedHashSet<String>()
        for (address in localIpv4Addresses()) {
            val octets = address.split('.')
            if (octets.size != 4) continue
            hosts.addAll(hostsForPrefix("${octets[0]}.${octets[1]}.${octets[2]}"))
        }
        return hosts.toList()
    }

    /** Every host of one /24, excluding the address the phone itself usually holds. */
    fun hostsForPrefix(prefix: String): List<String> =
        (2..254).map { host -> "$prefix.$host" }

    /** The device's own IPv4 addresses, loopback excluded. */
    fun localIpv4Addresses(): List<String> {
        val addresses = ArrayList<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull()
            ?: return addresses
        for (candidate in interfaces) {
            if (candidate.isLoopback || !candidate.isUp) continue
            val entries = runCatching { candidate.interfaceAddresses.toList() }.getOrNull() ?: continue
            for (entry in entries) {
                val address = entry.address
                if (address is Inet4Address) addresses.add(address.hostAddress ?: continue)
            }
        }
        return addresses
    }

    /**
     * The first host that answers on one of the given ports.
     *
     * `probe` is injected so the search itself can be tested without a network. Every host and
     * port is tried once, in parallel; the first answer wins and the rest are abandoned.
     */
    fun findBackend(hosts: List<String>, ports: List<Int>, timeoutMs: Int,
                    probe: (String, Int) -> Boolean): String? {
        if (hosts.isEmpty() || ports.isEmpty()) return null
        val executor = Executors.newFixedThreadPool(SCAN_THREADS) { runnable ->
            Thread(runnable, "backend-scan").apply { isDaemon = true }
        }
        try {
            val futures = ArrayList<java.util.concurrent.Future<String?>>()
            for (host in hosts) {
                for (port in ports) {
                    futures.add(executor.submit(Callable {
                        if (runCatching { probe(host, port) }.getOrDefault(false)) host else null
                    }))
                }
            }
            // Every probe gives up on its own after `timeoutMs`, so the whole search is bounded by
            // the number of rounds it takes to get through the queue. An earlier version used
            // `timeoutMs + 3 s` as the limit, which cut the search short just before the answer
            // arrived: 253 hosts times two ports over 48 threads needs about 2.6 s of probing,
            // and the addresses that answer slowly are exactly the ones the firewall drops.
            val deadline = System.currentTimeMillis() + searchBudgetMillis(futures.size, timeoutMs)
            while (System.currentTimeMillis() < deadline) {
                var allFinished = true
                for (future in futures) {
                    if (!future.isDone) { allFinished = false; continue }
                    val hit = runCatching { future.get() }.getOrNull()
                    if (hit != null) return hit
                }
                if (allFinished) break
                Thread.sleep(20)
            }
            return null
        } finally {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(200, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * How long the whole search may take.
     *
     * It has to cover every round of probing, not just one probe: a single timeout multiplied by
     * the number of rounds is what the search actually costs, and cutting that short is how the
     * answer got missed before.
     */
    internal fun searchBudgetMillis(probeCount: Int, timeoutMs: Int): Long {
        val rounds = (probeCount + SCAN_THREADS - 1) / SCAN_THREADS
        return rounds.toLong() * timeoutMs + SCAN_GRACE_MS
    }

    private const val SCAN_THREADS = 64
    private const val SCAN_GRACE_MS = 2_000L
}
