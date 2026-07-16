package com.soma.app

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Release gate: the merged manifest requests exactly the permissions the flavor
 * needs — microphone, camera, notifications everywhere; INTERNET only where a
 * network feature exists; ACCESS_NETWORK_STATE only in an outbound flavor. This
 * pins the `tools:node="remove"` entries that strip WorkManager's optional
 * permissions, so a dependency upgrade cannot quietly widen the manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionContractTest {
    @Test
    fun `merged manifest requests exactly the flavor's permission contract`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val requested = application.packageManager
            .getPackageInfo(application.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            // The system appends a package-scoped signature permission for
            // unexported dynamic receivers on SDK 33+; it is not part of the
            // contract this test pins.
            .filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
            .toSortedSet()

        val expected = buildSet {
            add("android.permission.RECORD_AUDIO")
            add("android.permission.CAMERA")
            add("android.permission.POST_NOTIFICATIONS")
            if (
                BuildConfig.BROWSER_VIEW_AVAILABLE ||
                BuildConfig.CLOUD_FEATURES_AVAILABLE ||
                BuildConfig.CODEX_BRIDGE_AVAILABLE
            ) {
                add("android.permission.INTERNET")
            }
            if (BuildConfig.CLOUD_FEATURES_AVAILABLE || BuildConfig.CODEX_BRIDGE_AVAILABLE) {
                add("android.permission.ACCESS_NETWORK_STATE")
            }
        }.toSortedSet()

        assertEquals(expected, requested)
    }
}
