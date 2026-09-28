package com.matt.weather

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matt.weather.ui.WeatherScreen
import com.matt.weather.ui.WeatherTheme

class MainActivity : ComponentActivity() {

    private val vm: WeatherVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            WeatherTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val results by vm.results.collectAsStateWithLifecycle()

                val askPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted.values.any { it }) vm.useGps()
                    else vm.notify("Location permission denied")
                }

                WeatherScreen(
                    state = state,
                    results = results,
                    onRefresh = vm::refresh,
                    onGps = {
                        if (hasLocationPermission()) {
                            vm.useGps()
                        } else {
                            askPermission.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            )
                        }
                    },
                    onQuery = vm::search,
                    onPick = vm::setPlace,
                    onSelectDay = vm::selectDay,
                    onToggleDays = vm::toggleDays,
                    onClearMessage = vm::clearMessage
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshIfStale()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
