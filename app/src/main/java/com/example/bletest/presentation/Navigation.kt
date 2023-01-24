package com.example.bletest.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun Navigation(
    onBluetoothStateChanged:()->Unit
){

    val navigationController = rememberNavController()
    NavHost(navController = navigationController, startDestination = Screen.StartScreen.route){
        composable(Screen.StartScreen.route){
            StartScreen(navController = navigationController)
        }
        composable(Screen.SendScreen.route){
            SendScreen(
                onBluetoothStateChanged
            )
        }
    }
}
sealed class Screen(val route: String){
    object StartScreen:Screen("start_screen")
    object SendScreen:Screen("send_screen")
}