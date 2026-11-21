package io.waves.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app runs at start and on its main screens, for the baseline profile: a cold start to Home, which alone
 * also makes the startup profile (it decides what's laid out first in the app's code), then a pass through the tabs.
 * The tour follows the top show in Discover's charts when the library is empty (the `.benchmark` build starts so), so
 * Home has episode rows to draw.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Home")), TIMEOUT)
        device.wait(Until.hasObject(By.textContains("min")), TIMEOUT) // the rows in
    }

    @Test
    fun tour() = rule.collect(packageName = PACKAGE, includeInStartupProfile = false) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text("Home")), TIMEOUT)
        device.followAShowOnce()
        tab("Home")
        device.wait(Until.hasObject(By.textContains("min")), TIMEOUT) // a row's play pill, once episodes are in
        retrying { device.findObject(By.scrollable(true))?.fling(Direction.DOWN) }
        retrying { device.findObject(By.scrollable(true))?.fling(Direction.UP) }
        tab("Discover")
        device.waitForIdle()
        tab("History")
        device.waitForIdle()
        tab("Settings")
        retrying { device.findObject(By.scrollable(true))?.fling(Direction.DOWN) }
        tab("Home")
        device.waitForIdle()
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.tab(label: String) {
        retrying { device.findObject(By.text(label))?.click() }
        device.waitForIdle()
    }

    /** [action], found afresh and tried again when what it found was redrawn under it (rows still arriving). */
    private fun retrying(action: () -> Unit) {
        repeat(3) {
            try {
                action()
                return
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                Thread.sleep(300)
            }
        }
    }

    /** Subscribes to Discover's top show, unless the library already has shows (the shelf then lists them). */
    private fun UiDevice.followAShowOnce() {
        if (hasObject(By.text("No subscriptions yet")).not()) return
        if (!hasObject(By.text("Discover podcasts"))) return
        retrying { findObject(By.text("Discover podcasts"))?.click() }
        // The charts load from the network: the first cover in the list.
        wait(Until.hasObject(By.clickable(true).hasDescendant(By.textStartsWith("1"))), TIMEOUT)
        retrying { findObjects(By.clickable(true)).firstOrNull { it.visibleBounds.top > displayHeight / 4 }?.click() }
        wait(Until.hasObject(By.desc("Subscribe")), TIMEOUT)
        retrying { findObject(By.desc("Subscribe"))?.click() }
        waitForIdle()
        pressBack()
    }

    private companion object {
        const val PACKAGE = "io.waves.benchmark"
        const val TIMEOUT = 15_000L
    }
}
