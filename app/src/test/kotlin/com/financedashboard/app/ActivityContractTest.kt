package com.financedashboard.app

import androidx.activity.ComponentActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the "Can only use lower 16 bits for requestCode" crash:
 * MainActivity must be a plain ComponentActivity. A FragmentActivity (or
 * AppCompatActivity) rejects the request codes that Compose's ActivityResult
 * launchers generate, crashing the CSV pickers and the notification-permission
 * request.
 */
class ActivityContractTest {

    @Test
    fun `MainActivity is a ComponentActivity`() {
        assertTrue(ComponentActivity::class.java.isAssignableFrom(MainActivity::class.java))
    }

    @Test
    fun `MainActivity is not a FragmentActivity`() {
        var isFragmentActivity = false
        var superClass: Class<*>? = MainActivity::class.java
        while (superClass != null) {
            if (superClass.name == "androidx.fragment.app.FragmentActivity") isFragmentActivity = true
            superClass = superClass.superclass
        }
        assertFalse(
            "MainActivity must not extend FragmentActivity — it breaks Compose ActivityResult launchers.",
            isFragmentActivity,
        )
    }
}
