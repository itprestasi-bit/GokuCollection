package com.collectionfield.app.data.remote

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        when {
            task.isSuccessful -> continuation.resume(task.result)
            task.exception != null -> continuation.resumeWithException(task.exception!!)
            else -> continuation.resumeWithException(IllegalStateException("Firebase task gagal tanpa exception"))
        }
    }
}
