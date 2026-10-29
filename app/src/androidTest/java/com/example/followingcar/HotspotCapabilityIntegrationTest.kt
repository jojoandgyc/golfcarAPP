package com.example.followingcar

import android.Manifest
import android.content.pm.PackageManager
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in hardware probe. Never sends credentials, joins a board, or starts control.
 * Root must first verify the board and App are idle and arrange a management path.
 * A pre-existing P2P group is left intact and causes the probe to skip. */
@RunWith(AndroidJUnit4::class)
class HotspotCapabilityIntegrationTest {
    @Test fun localOnlyHotspotReportsCompatibilityAndReleasesItsReservation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires runHotspotCapability=true", args.getString("runHotspotCapability") == "true")
        assumeTrue("Requires confirmedControlIdle=true", args.getString("confirmedControlIdle") == "true")
        val context = instrumentation.targetContext
        assumeTrue("Grant Nearby Wi-Fi devices in the App first",
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)
        val manager = context.getSystemService(WifiManager::class.java)
        assertNotNull("Device needs Wi-Fi", manager)
        val mainHandler = Handler(Looper.getMainLooper())
        val p2p = context.getSystemService(WifiP2pManager::class.java)
        if (p2p != null) {
            val queried = CountDownLatch(1)
            val hasGroup = AtomicBoolean(true)
            instrumentation.runOnMainSync {
                // Keep this observer channel open until the instrumentation process exits.
                // Closing a P2P channel can remove groups owned by the same process.
                val channel = p2p.initialize(context, Looper.getMainLooper(), null)
                p2p.requestGroupInfo(channel) { group ->
                    hasGroup.set(group != null)
                    queried.countDown()
                }
            }
            assertTrue("Cannot determine existing P2P state", queried.await(8, TimeUnit.SECONDS))
            assumeTrue("Existing Wi-Fi Direct group must be explicitly removed before this probe", !hasGroup.get())
        }

        val finished = AtomicBoolean(false)
        val reservation = AtomicReference<WifiManager.LocalOnlyHotspotReservation?>()
        val reply = CountDownLatch(1)
        val failure = AtomicInteger(-1)
        val stopped = AtomicBoolean(false)
        val before = ipv4InterfaceKeys()
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                instrumentation.runOnMainSync {
                    manager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                            reservation.set(value)
                            if (finished.get()) reservation.getAndSet(null)?.close()
                            reply.countDown()
                        }

                        override fun onFailed(reason: Int) {
                            failure.set(reason)
                            reply.countDown()
                        }

                        override fun onStopped() {
                            stopped.set(true)
                        }
                    }, mainHandler)
                }
                assertTrue("Hotspot callback timed out", reply.await(35, TimeUnit.SECONDS))
                assertTrue("Hotspot failed with public reason code ${failure.get()}", failure.get() == -1)
                val owned = reservation.get()
                assertNotNull("Hotspot callback did not provide a reservation", owned)
                assertFalse("Hotspot stopped before capability inspection", stopped.get())
                val configuration = owned!!.softApConfiguration
                assertTrue("Hotspot must supply a non-empty SSID", !configuration.ssid.isNullOrEmpty())
                assertTrue("Hotspot must supply a protected credential", !configuration.passphrase.isNullOrEmpty())
                // Public channel configuration is only available from API 36. In particular,
                // never reflect on hidden fields or stringify the credential-bearing config.
                val bands = if (Build.VERSION.SDK_INT >= 36) {
                    configuration.channels.let { channels ->
                        (0 until channels.size()).map(channels::keyAt)
                    }
                } else null
                val canUse2g = bands?.any { it and SoftApConfiguration.BAND_2GHZ != 0 }
                val report = JSONObject()
                    .put("kind", "local_only_hotspot_capability")
                    .put("configuredBands", bands?.joinToString(",") ?: JSONObject.NULL)
                    .put("configurationIncludes2g", canUse2g ?: JSONObject.NULL)
                    .put("securityType", configuration.securityType)
                    .put("ipv4InterfacesAdded", (ipv4InterfaceKeys() - before).size)
                    .put("hasCredentials", true)
                    // The band mask is configuration, not a measurement of the active channel.
                    .put("actualOperatingBandVerified", false)
                instrumentation.sendStatus(0, Bundle().apply { putString("hotspotCapability", report.toString()) })
                assertTrue("Board needs WPA2-compatible hotspot security",
                    configuration.securityType == SoftApConfiguration.SECURITY_TYPE_WPA2_PSK ||
                        configuration.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION)
                if (canUse2g != null) {
                    assertTrue("Board requires a configuration containing 2.4 GHz", canUse2g)
                }
            } finally {
                finished.set(true)
                reservation.getAndSet(null)?.close()
            }
        }
    }

    /** Comparison only; neither addresses nor interface names leave this process. */
    private fun ipv4InterfaceKeys(): Set<String> = NetworkInterface.getNetworkInterfaces()
        ?.toList().orEmpty().flatMap { network ->
            network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { network.name + ":" + it.hostAddress }
        }.toSet()
}
