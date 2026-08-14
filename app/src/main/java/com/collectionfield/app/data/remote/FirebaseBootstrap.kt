package com.collectionfield.app.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FirebaseBootstrap {
    fun initialize(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true
        val options = FirebaseOptions.fromResource(context) ?: return false
        return FirebaseApp.initializeApp(context, options) != null
    }

    fun isReady(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()
}
