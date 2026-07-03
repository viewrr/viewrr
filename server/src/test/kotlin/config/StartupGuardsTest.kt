package wtf.jobin.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupGuardsTest {

    @Test
    fun `prod with unsafe defaults is fatal`() {
        assertTrue(prodBootFatal("prod", hasUnsafeDefaults = true))
    }

    @Test
    fun `prod without unsafe defaults is not fatal`() {
        assertFalse(prodBootFatal("prod", hasUnsafeDefaults = false))
    }

    @Test
    fun `dev never fatal even with unsafe defaults`() {
        assertFalse(prodBootFatal("dev", hasUnsafeDefaults = true))
    }

    @Test
    fun `production is not the canonical value - guard must use prod (regression for the dead-guard bug)`() {
        // The .env.schema enum is dev|prod. The guard once compared to "production",
        // which never occurs, so the prod safety check silently never fired.
        assertFalse(prodBootFatal("production", hasUnsafeDefaults = true))
    }
}
