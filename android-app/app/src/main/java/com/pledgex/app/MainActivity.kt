package com.pledgex.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.pledgex.app.ui.PledgeApp
import com.pledgex.app.ui.PledgeTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

class MainActivity : ComponentActivity() {
    private val vm: PledgeViewModel by viewModels()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val stepAccess = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setStepPermission(it) }

    /** Asked when a pledge is created: the daily reminder is a notification. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Must be created before the activity starts: it registers an activity-result launcher.
        val sender = ActivityResultSender(this)
        vm.setStepPermission(hasStepPermission())
        // Steps are the core habit: without this permission Android delivers no step events at all.
        if (!hasStepPermission()) stepAccess.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        setContent { PledgeTheme { PledgeApp(vm, sender, ::askNotifications) } }
    }

    fun hasStepPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        vm.start()
    }

    override fun onResume() {
        super.onResume()
        vm.setStepPermission(hasStepPermission())
        vm.onForeground()
    }

    override fun onStop() {
        vm.stop()
        super.onStop()
    }
}
