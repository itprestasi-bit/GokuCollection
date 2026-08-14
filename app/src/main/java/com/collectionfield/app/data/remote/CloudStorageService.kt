package com.collectionfield.app.data.remote

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File

class CloudStorageService {
    private val storage = FirebaseStorage.getInstance()

    /**
     * Uploads a file to Firebase Storage and returns the download URL.
     * Path must match storage.rules: visit_photos/{uid}/{visitId}/{fileName}.
     */
    suspend fun uploadVisitPhoto(uid: String, visitId: String, file: File): Result<String> = runCatching {
        val timestamp = System.currentTimeMillis()
        val path = "visit_photos/$uid/$visitId/$timestamp.jpg"
        val storageRef = storage.reference.child(path)

        storageRef.putFile(Uri.fromFile(file)).await()
        storageRef.downloadUrl.await().toString()
    }
}
