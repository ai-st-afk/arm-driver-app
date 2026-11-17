package ru.profstroyservices.armdriver.data.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.profstroyservices.armdriver.data.network.AppReleaseDto

class MandatoryUpdateTest {
    @Test
    fun oldManifestDoesNotRequireUpdate() {
        val release = Json.decodeFromString<AppReleaseDto>(
            """{"version_code":53,"version_name":"0.9.16","sha256":"abc","size":10}"""
        )
        assertFalse(release.isMandatoryFor(52))
    }

    @Test
    fun thresholdBlocksOnlyOlderVersions() {
        val release = Json.decodeFromString<AppReleaseDto>(
            """{"version_code":54,"version_name":"0.9.17","min_supported_version_code":53,"sha256":"abc","size":10}"""
        )
        assertTrue(release.isMandatoryFor(52))
        assertFalse(release.isMandatoryFor(53))
    }
}
