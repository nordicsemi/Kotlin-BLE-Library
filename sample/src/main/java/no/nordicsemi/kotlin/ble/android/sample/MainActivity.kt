package no.nordicsemi.kotlin.ble.android.sample

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import no.nordicsemi.kotlin.ble.android.sample.advertiser.AdvertiserScreen
import no.nordicsemi.kotlin.ble.android.sample.menu.MenuScreen
import no.nordicsemi.kotlin.ble.android.sample.scanner.ScannerScreen
import no.nordicsemi.kotlin.ble.android.sample.theme.AppTheme
import no.nordicsemi.kotlin.ble.android.sample.view.AppBarState
import no.nordicsemi.kotlin.ble.android.sample.view.LocalAppBarState
import no.nordicsemi.kotlin.ble.core.android.AndroidEnvironment
import no.nordicsemi.kotlin.ble.environment.android.compose.LocalEnvironmentOwner
import javax.inject.Inject

const val NAV_MENU = "Menu"
const val NAV_ADVERTISER = "Advertiser"
const val NAV_SCANNER = "Scanner"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var environment: AndroidEnvironment

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        setContent {
            AppTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route

                // The app bar hides when the content is scrolled down and shows again
                // when scrolled up. This saves a lot of space, especially in landscape.
                val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
                // Show the app bar when navigating to another screen.
                LaunchedEffect(currentRoute) {
                    scrollBehavior.state.heightOffset = 0f
                }

                // Actions shown in the app bar, set by the current screen using AppBarActions.
                val appBarState = remember { AppBarState() }

                // Note the plural in "valueS = " below. The Environment owner provides an array of values.
                CompositionLocalProvider(
                    values = (LocalEnvironmentOwner provides environment) +
                            (LocalAppBarState provides appBarState),
                ) {
                    Scaffold(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        topBar = {
                            TopAppBar(
                                // TODO: Titles should go from strings.xml
                                title = { Text(text = currentRoute ?: NAV_MENU) },
                                navigationIcon = {
                                    // Show the home icon only on the main menu
                                    if (currentRoute != NAV_MENU) {
                                        IconButton(
                                            onClick = { navController.popBackStack() }
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                                                contentDescription = "Navigate back",
                                            )
                                        }
                                    }
                                },
                                actions = {
                                    appBarState.actions?.invoke(this)
                                },
                                scrollBehavior = scrollBehavior,
                            )
                        },
                        contentWindowInsets = WindowInsets.statusBars,
                    ) { paddings ->
                        NavHost(
                            modifier = Modifier.padding(paddings),
                            navController = navController,
                            startDestination = NAV_MENU
                        ) {
                            composable(NAV_MENU) {
                                MenuScreen(
                                    onMenuClicked = { navController.navigate(it) }
                                )
                            }
                            composable(NAV_ADVERTISER) {
                                AdvertiserScreen()
                            }
                            composable(NAV_SCANNER) {
                                ScannerScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}